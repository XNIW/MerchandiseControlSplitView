package com.example.merchandisecontrolsplitview.util

import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DirectoryNode
import org.apache.poi.poifs.filesystem.Entry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.util.LittleEndian
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val OLE2_OBJ_RECORD_SID = 0x005D
private const val OLE2_END_SUBRECORD_SID = 0x0000
private const val OLE2_UNKNOWN_SUBRECORD_SID = 0xFFFF
private val OLE2_MAGIC = byteArrayOf(
    0xD0.toByte(),
    0xCF.toByte(),
    0x11.toByte(),
    0xE0.toByte(),
    0xA1.toByte(),
    0xB1.toByte(),
    0x1A.toByte(),
    0xE1.toByte()
)

internal fun createWorkbookWithLegacyFallback(bytes: ByteArray): Workbook {
    return try {
        WorkbookFactory.create(ByteArrayInputStream(bytes))
    } catch (throwable: Throwable) {
        if (throwable is OutOfMemoryError) {
            throw throwable
        }

        if (looksLikeOle2Workbook(bytes) && isLegacyXlsObjectRecordFailure(throwable)) {
            return retryWithSanitizedWorkbook(bytes, throwable, ::sanitizeLegacyObjRecords)
        }

        if (looksLikeZipArchive(bytes) && isStrictOoXmlFailure(throwable)) {
            return retryWithSanitizedWorkbook(bytes, throwable, ::sanitizeStrictOoXmlPackage)
        }

        throw throwable
    }
}

private fun retryWithSanitizedWorkbook(
    bytes: ByteArray,
    originalFailure: Throwable,
    sanitizer: (ByteArray) -> ByteArray?
): Workbook {
    val sanitizedBytes = sanitizer(bytes) ?: throw originalFailure
    try {
        return WorkbookFactory.create(ByteArrayInputStream(sanitizedBytes))
    } catch (retryFailure: Throwable) {
        retryFailure.addSuppressed(originalFailure)
        throw retryFailure
    }
}

private fun looksLikeOle2Workbook(bytes: ByteArray): Boolean {
    if (bytes.size < OLE2_MAGIC.size) return false
    return OLE2_MAGIC.indices.all { index -> bytes[index] == OLE2_MAGIC[index] }
}

private fun sanitizeLegacyObjRecords(bytes: ByteArray): ByteArray? {
    POIFSFileSystem(ByteArrayInputStream(bytes)).use { sourceFs ->
        val workbookEntryName = findWorkbookEntryName(sourceFs) ?: return null
        val workbookBytes = sourceFs.root.createDocumentInputStream(workbookEntryName).use { input ->
            input.readBytes()
        }
        val patchedCount = patchMalformedObjSubRecords(workbookBytes)
        if (patchedCount == 0) return null

        POIFSFileSystem().use { targetFs ->
            copyDirectory(
                source = sourceFs.root,
                target = targetFs.root,
                workbookEntryName = workbookEntryName,
                patchedWorkbookBytes = workbookBytes
            )
            ByteArrayOutputStream().use { output ->
                targetFs.writeFilesystem(output)
                return output.toByteArray()
            }
        }
    }
}

private fun findWorkbookEntryName(fs: POIFSFileSystem): String? {
    return when {
        fs.root.hasEntry("Workbook") -> "Workbook"
        fs.root.hasEntry("Book") -> "Book"
        else -> null
    }
}

private fun copyDirectory(
    source: DirectoryNode,
    target: DirectoryEntry,
    workbookEntryName: String,
    patchedWorkbookBytes: ByteArray
) {
    for (entry in source) {
        when (entry) {
            is DirectoryEntry -> {
                val childTarget = target.createDirectory(entry.name)
                copyDirectory(
                    source = entry as DirectoryNode,
                    target = childTarget,
                    workbookEntryName = workbookEntryName,
                    patchedWorkbookBytes = patchedWorkbookBytes
                )
            }

            else -> copyDocument(
                source = source,
                entry = entry,
                target = target,
                workbookEntryName = workbookEntryName,
                patchedWorkbookBytes = patchedWorkbookBytes
            )
        }
    }
}

private fun copyDocument(
    source: DirectoryNode,
    entry: Entry,
    target: DirectoryEntry,
    workbookEntryName: String,
    patchedWorkbookBytes: ByteArray
) {
    if (entry.name == workbookEntryName) {
        target.createDocument(entry.name, ByteArrayInputStream(patchedWorkbookBytes))
        return
    }

    source.createDocumentInputStream(entry).use { input ->
        target.createDocument(entry.name, input)
    }
}

private fun patchMalformedObjSubRecords(workbookBytes: ByteArray): Int {
    var patched = 0
    var pos = 0

    while (pos + 4 <= workbookBytes.size) {
        val sid = LittleEndian.getUShort(workbookBytes, pos)
        val length = LittleEndian.getUShort(workbookBytes, pos + 2)
        pos += 4

        if (pos + length > workbookBytes.size) {
            break
        }

        if (sid == OLE2_OBJ_RECORD_SID) {
            patched += patchObjRecordSubRecords(workbookBytes, pos, length)
        }

        pos += length
    }

    return patched
}

private fun patchObjRecordSubRecords(
    workbookBytes: ByteArray,
    start: Int,
    length: Int
): Int {
    var patched = 0
    var subRecordOffset = start
    val end = start + length

    while (subRecordOffset + 4 <= end) {
        val subRecordSid = LittleEndian.getUShort(workbookBytes, subRecordOffset)
        val subRecordLength = LittleEndian.getUShort(workbookBytes, subRecordOffset + 2)

        if (subRecordLength == 0 && subRecordSid != OLE2_END_SUBRECORD_SID) {
            LittleEndian.putShort(
                workbookBytes,
                subRecordOffset,
                OLE2_UNKNOWN_SUBRECORD_SID.toShort()
            )
            patched++
        }

        subRecordOffset += 4 + subRecordLength
        if (subRecordSid == OLE2_END_SUBRECORD_SID) {
            break
        }
    }

    return patched
}

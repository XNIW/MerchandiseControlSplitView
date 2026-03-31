package com.example.merchandisecontrolsplitview.testutil

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.poi.hssf.record.CommonObjectDataSubRecord
import org.apache.poi.hssf.record.EndSubRecord
import org.apache.poi.hssf.record.FtCfSubRecord
import org.apache.poi.hssf.record.FtPioGrbitSubRecord
import org.apache.poi.hssf.record.ObjRecord
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DirectoryNode
import org.apache.poi.poifs.filesystem.Entry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.util.LittleEndian

fun createMalformedLegacyObjWorkbookFile(
    cacheDir: File,
    name: String,
    rows: List<List<Any>>
): File {
    val file = File.createTempFile(name, ".xls", cacheDir)
    file.writeBytes(injectMalformedObjRecord(createBaseWorkbook(rows)))
    return file
}

private fun createBaseWorkbook(rows: List<List<Any>>): ByteArray {
    ByteArrayOutputStream().use { output ->
        HSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet1")
            rows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex)
                values.forEachIndexed { cellIndex, value ->
                    val cell = row.createCell(cellIndex)
                    when (value) {
                        is Number -> cell.setCellValue(value.toDouble())
                        else -> cell.setCellValue(value.toString())
                    }
                }
            }
            workbook.write(output)
        }
        return output.toByteArray()
    }
}

private fun injectMalformedObjRecord(fileBytes: ByteArray): ByteArray {
    POIFSFileSystem(ByteArrayInputStream(fileBytes)).use { sourceFs ->
        val workbookBytes = sourceFs.root.createDocumentInputStream("Workbook").use { input ->
            input.readBytes()
        }
        val eofOffset = findLastRecordOffset(workbookBytes, 0x000A)
        val injectedWorkbookBytes = ByteArrayOutputStream().use { output ->
            output.write(workbookBytes, 0, eofOffset)
            output.write(buildMalformedObjRecord())
            output.write(workbookBytes, eofOffset, workbookBytes.size - eofOffset)
            output.toByteArray()
        }

        POIFSFileSystem().use { targetFs ->
            copyRootEntries(
                source = sourceFs.root,
                target = targetFs.root,
                injectedWorkbookBytes = injectedWorkbookBytes
            )
            ByteArrayOutputStream().use { output ->
                targetFs.writeFilesystem(output)
                return output.toByteArray()
            }
        }
    }
}

private fun copyRootEntries(
    source: DirectoryNode,
    target: DirectoryEntry,
    injectedWorkbookBytes: ByteArray
) {
    for (entry in source) {
        when (entry) {
            is DirectoryEntry -> {
                val childTarget = target.createDirectory(entry.name)
                copyRootEntries(
                    source = entry as DirectoryNode,
                    target = childTarget,
                    injectedWorkbookBytes = injectedWorkbookBytes
                )
            }

            else -> copyDocument(
                source = source,
                target = target,
                entry = entry,
                injectedWorkbookBytes = injectedWorkbookBytes
            )
        }
    }
}

private fun copyDocument(
    source: DirectoryNode,
    target: DirectoryEntry,
    entry: Entry,
    injectedWorkbookBytes: ByteArray
) {
    if (entry.name == "Workbook") {
        target.createDocument(entry.name, ByteArrayInputStream(injectedWorkbookBytes))
        return
    }

    source.createDocumentInputStream(entry).use { input ->
        target.createDocument(entry.name, input)
    }
}

private fun buildMalformedObjRecord(): ByteArray {
    val cmo = CommonObjectDataSubRecord().apply {
        objectType = CommonObjectDataSubRecord.OBJECT_TYPE_PICTURE
        objectId = 1
        option = 0x6011.toShort()
    }
    val ftCf = FtCfSubRecord().apply {
        flags = FtCfSubRecord.BITMAP_BIT
    }
    val ftPio = FtPioGrbitSubRecord()
    val end = EndSubRecord()

    val payload = ByteArrayOutputStream().apply {
        writeSubRecord(cmo.serialize(), zeroLength = false)
        writeSubRecord(ftCf.serialize(), zeroLength = true)
        writeSubRecord(ftPio.serialize(), zeroLength = true)
        writeSubRecord(end.serialize(), zeroLength = false)
    }.toByteArray()

    val header = ByteArray(4).apply {
        LittleEndian.putShort(this, 0, ObjRecord.sid)
        LittleEndian.putShort(this, 2, payload.size.toShort())
    }

    return ByteArrayOutputStream().use { output ->
        output.write(header)
        output.write(payload)
        output.toByteArray()
    }
}

private fun ByteArrayOutputStream.writeSubRecord(
    serialized: ByteArray,
    zeroLength: Boolean
) {
    if (!zeroLength) {
        write(serialized)
        return
    }

    val header = ByteArray(4).apply {
        LittleEndian.putShort(this, 0, LittleEndian.getShort(serialized, 0))
        LittleEndian.putShort(this, 2, 0)
    }
    write(header)
}

private fun findLastRecordOffset(
    workbookBytes: ByteArray,
    targetSid: Int
): Int {
    var pos = 0
    var lastOffset = -1

    while (pos + 4 <= workbookBytes.size) {
        val recordOffset = pos
        val sid = LittleEndian.getUShort(workbookBytes, pos)
        val length = LittleEndian.getUShort(workbookBytes, pos + 2)
        pos += 4
        if (pos + length > workbookBytes.size) {
            break
        }
        if (sid == targetSid) {
            lastOffset = recordOffset
        }
        pos += length
    }

    check(lastOffset >= 0) { "No EOF record found while building malformed legacy workbook fixture" }
    return lastOffset
}

package com.example.merchandisecontrolsplitview.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val ZIP_MAGIC = byteArrayOf(
    0x50.toByte(),
    0x4B.toByte(),
    0x03.toByte(),
    0x04.toByte()
)

private const val STRICT_OOXML_MARKER = "http://purl.oclc.org/ooxml/"

private val STRICT_OOXML_NAMESPACE_REPLACEMENTS = listOf(
    "http://purl.oclc.org/ooxml/officeDocument/relationships" to
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "http://purl.oclc.org/ooxml/spreadsheetml/main" to
        "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
    "http://purl.oclc.org/ooxml/drawingml/main" to
        "http://schemas.openxmlformats.org/drawingml/2006/main",
    "http://purl.oclc.org/ooxml/drawingml/spreadsheetDrawing" to
        "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing",
    "http://purl.oclc.org/ooxml/officeDocument/extendedProperties" to
        "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties",
    "http://purl.oclc.org/ooxml/officeDocument/docPropsVTypes" to
        "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
)

internal fun looksLikeZipArchive(bytes: ByteArray): Boolean {
    if (bytes.size < ZIP_MAGIC.size) return false
    return ZIP_MAGIC.indices.all { index -> bytes[index] == ZIP_MAGIC[index] }
}

internal fun sanitizeStrictOoXmlPackage(bytes: ByteArray): ByteArray? {
    var changed = false

    ByteArrayInputStream(bytes).use { input ->
        ZipInputStream(input).use { zipInput ->
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zipOutput ->
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        val entryBytes = if (entry.isDirectory) {
                            byteArrayOf()
                        } else {
                            zipInput.readBytes()
                        }
                        val rewrittenBytes = if (shouldRewriteStrictOoXmlEntry(entry.name)) {
                            rewriteStrictOoXmlXmlEntry(entryBytes).also { candidate ->
                                changed = changed || !candidate.contentEquals(entryBytes)
                            }
                        } else {
                            entryBytes
                        }

                        val outputEntry = ZipEntry(entry.name).apply {
                            comment = entry.comment
                            extra = entry.extra
                            time = entry.time
                        }
                        zipOutput.putNextEntry(outputEntry)
                        if (rewrittenBytes.isNotEmpty()) {
                            zipOutput.write(rewrittenBytes)
                        }
                        zipOutput.closeEntry()
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                }
                return if (changed) output.toByteArray() else null
            }
        }
    }
}

private fun shouldRewriteStrictOoXmlEntry(entryName: String): Boolean {
    return entryName.endsWith(".xml", ignoreCase = true) ||
        entryName.endsWith(".rels", ignoreCase = true)
}

private fun rewriteStrictOoXmlXmlEntry(bytes: ByteArray): ByteArray {
    val xml = bytes.toString(StandardCharsets.UTF_8)
    if (!xml.contains(STRICT_OOXML_MARKER)) {
        return bytes
    }

    var rewritten = xml
    STRICT_OOXML_NAMESPACE_REPLACEMENTS.forEach { (strictNamespace, transitionalNamespace) ->
        rewritten = rewritten.replace(strictNamespace, transitionalNamespace)
    }

    return if (rewritten == xml) {
        bytes
    } else {
        rewritten.toByteArray(StandardCharsets.UTF_8)
    }
}

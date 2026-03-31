package com.example.merchandisecontrolsplitview.testutil

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook

private const val STRICT_CONFORMANCE_MARKER = "<workbook "

private val TRANSITIONAL_TO_STRICT_REPLACEMENTS = listOf(
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships" to
        "http://purl.oclc.org/ooxml/officeDocument/relationships",
    "http://schemas.openxmlformats.org/spreadsheetml/2006/main" to
        "http://purl.oclc.org/ooxml/spreadsheetml/main",
    "http://schemas.openxmlformats.org/drawingml/2006/main" to
        "http://purl.oclc.org/ooxml/drawingml/main",
    "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" to
        "http://purl.oclc.org/ooxml/drawingml/spreadsheetDrawing",
    "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" to
        "http://purl.oclc.org/ooxml/officeDocument/extendedProperties",
    "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes" to
        "http://purl.oclc.org/ooxml/officeDocument/docPropsVTypes"
)

private val TINY_PNG_BYTES: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Y7XkAAAAASUVORK5CYII="
)

fun createStrictOoXmlWorkbookFile(
    cacheDir: File,
    name: String,
    rows: List<List<Any>>,
    includeImage: Boolean = true
): File {
    val file = File.createTempFile(name, ".xlsx", cacheDir)
    file.writeBytes(convertWorkbookToStrictPackage(createBaseWorkbook(rows, includeImage)))
    return file
}

private fun createBaseWorkbook(
    rows: List<List<Any>>,
    includeImage: Boolean
): ByteArray {
    ByteArrayOutputStream().use { output ->
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("YGO2603274845")
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

            if (includeImage) {
                addWorkbookImage(workbook, 0, 1, 1, 2)
            }

            workbook.write(output)
        }
        return output.toByteArray()
    }
}

private fun addWorkbookImage(
    workbook: Workbook,
    col1: Int,
    row1: Int,
    col2: Int,
    row2: Int
) {
    val sheet = workbook.getSheetAt(0)
    val pictureIndex = workbook.addPicture(TINY_PNG_BYTES, Workbook.PICTURE_TYPE_PNG)
    val anchor = workbook.creationHelper.createClientAnchor()
    anchor.setCol1(col1)
    anchor.row1 = row1
    anchor.setCol2(col2)
    anchor.row2 = row2
    sheet.createDrawingPatriarch().createPicture(anchor, pictureIndex)
}

private fun convertWorkbookToStrictPackage(bytes: ByteArray): ByteArray {
    ByteArrayInputStream(bytes).use { input ->
        ZipInputStream(input).use { zipInput ->
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zipOutput ->
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        val entryBytes = if (entry.isDirectory) byteArrayOf() else zipInput.readBytes()
                        val strictBytes = if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                            rewriteTransitionalXmlEntry(entry.name, entryBytes)
                        } else {
                            entryBytes
                        }

                        zipOutput.putNextEntry(ZipEntry(entry.name))
                        if (strictBytes.isNotEmpty()) {
                            zipOutput.write(strictBytes)
                        }
                        zipOutput.closeEntry()
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                }
                return output.toByteArray()
            }
        }
    }
}

private fun rewriteTransitionalXmlEntry(
    entryName: String,
    bytes: ByteArray
): ByteArray {
    var xml = bytes.toString(StandardCharsets.UTF_8)
    TRANSITIONAL_TO_STRICT_REPLACEMENTS.forEach { (transitionalNamespace, strictNamespace) ->
        xml = xml.replace(transitionalNamespace, strictNamespace)
    }

    if (entryName == "xl/workbook.xml" && !xml.contains("conformance=\"strict\"")) {
        xml = xml.replaceFirst(
            STRICT_CONFORMANCE_MARKER,
            "$STRICT_CONFORMANCE_MARKER" + "conformance=\"strict\" "
        )
    }

    return xml.toByteArray(StandardCharsets.UTF_8)
}

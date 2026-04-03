package com.example.merchandisecontrolsplitview.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.testutil.createMalformedLegacyObjWorkbookFile
import com.example.merchandisecontrolsplitview.testutil.createStrictOoXmlWorkbookFile
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.util.RecordFormatException
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExcelUtilsTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `parseNumber returns null for null input`() {
        assertNull(parseNumber(null))
    }

    @Test
    fun `parseNumber returns null for blank input`() {
        assertNull(parseNumber("   "))
    }

    @Test
    fun `parseNumber parses US thousand and decimal separators`() {
        assertEquals(1234.56, parseNumber("1,234.56")!!, 0.0001)
    }

    @Test
    fun `parseNumber parses EU thousand and decimal separators`() {
        assertEquals(1234.56, parseNumber("1.234,56")!!, 0.0001)
    }

    @Test
    fun `parseNumber documents ambiguous comma format`() {
        assertEquals(1.234, parseNumber("1,234")!!, 0.0001)
    }

    @Test
    fun `parseNumber documents ambiguous dot format`() {
        assertEquals(1.234, parseNumber("1.234")!!, 0.0001)
    }

    @Test
    fun `parseNumber trims surrounding spaces`() {
        assertEquals(10.5, parseNumber("  10.5  ")!!, 0.0001)
    }

    @Test
    fun `parseNumber parses negative values`() {
        assertEquals(-10.5, parseNumber("-10,5")!!, 0.0001)
    }

    @Test
    fun `getLocalizedHeader resolves known key from resources`() {
        assertEquals(
            context.getString(R.string.header_purchase_price),
            getLocalizedHeader(context, "purchasePrice")
        )
    }

    @Test
    fun `getLocalizedHeader returns raw key for unknown header`() {
        assertEquals("unknownCustomKey", getLocalizedHeader(context, "unknownCustomKey"))
    }

    @Test
    fun `getLocalizedHeader resolves legacy retail price alias`() {
        assertEquals(
            context.getString(R.string.header_retail_price),
            getLocalizedHeader(context, "RetailPrice")
        )
    }

    @Test
    fun `analyzePoiSheet maps localized aliases to canonical headers`() {
        withSheet(
            listOf("Código de barras", "Nombre del Producto", "prezzo acquisto", "Cantidad", "Importe"),
            listOf("12345678", "Producto Uno", "4", "2", "8"),
            listOf("23456789", "Producto Dos", "5", "3", "15")
        ) { sheet ->
            val (header, rows, headerSource) = analyzePoiSheet(context, sheet)

            assertEquals(
                listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
                header
            )
            assertEquals(2, rows.size)
            assertEquals(listOf("alias", "alias", "alias", "alias", "alias"), headerSource)
        }
    }

    @Test
    fun `analyzePoiSheet returns expected data for a minimal happy path`() {
        withSheet(
            listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
            listOf("12345678", "Alpha", "4.5", "2", "9"),
            listOf("23456789", "Beta", "5", "3", "15")
        ) { sheet ->
            val (header, rows, headerSource) = analyzePoiSheet(context, sheet)

            assertEquals(
                listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
                header
            )
            assertEquals(
                listOf(
                    listOf("12345678", "Alpha", "4.5", "2", "9"),
                    listOf("23456789", "Beta", "5", "3", "15")
                ),
                rows
            )
            assertEquals(header.size, headerSource.size)
        }
    }

    @Test
    fun `analyzePoiSheet trims cells drops trailing blanks and ignores blank rows`() {
        withSheet(
            listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice", "", ""),
            listOf("   ", " ", "  ", "", "", "", ""),
            listOf(" 12345678 ", "  Trim Me  ", " 4 ", " 2 ", " 8 ", "", " ")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)

            assertEquals(
                listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
                header
            )
            assertEquals(1, rows.size)
            assertEquals(listOf("12345678", "Trim Me", "4", "2", "8"), rows.single())
        }
    }

    @Test
    fun `analyzePoiSheet removes columns that are empty in all data rows`() {
        withSheet(
            listOf("barcode", "productName", "purchasePrice", "quantity", "supplier", "totalPrice"),
            listOf("12345678", "Alpha", "4", "2", "", "8"),
            listOf("23456789", "Beta", "5", "3", "", "15")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)

            assertFalse(header.contains("supplier"))
            assertEquals(5, header.size)
            assertEquals(listOf("12345678", "Alpha", "4", "2", "8"), rows.first())
        }
    }

    @Test
    fun `analyzePoiSheet filters summary rows when they match summary heuristics`() {
        withSheet(
            listOf("rowNumber", "barcode", "itemNumber", "productName", "quantity", "purchasePrice", "totalPrice"),
            listOf("1", "12345678", "ITEM-1", "Alpha", "2", "4", "8"),
            listOf("Total", "", "", "", "3", "4", "12")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)
            val barcodeCol = header.indexOf("barcode")
            assertTrue(barcodeCol >= 0)

            assertEquals(1, rows.size)
            assertEquals("12345678", rows.single()[barcodeCol])
        }
    }

    @Test
    fun `analyzePoiSheet ensures barcode and purchasePrice columns when header is incomplete`() {
        withSheet(
            listOf("row", "product name", "qty", "totale"),
            listOf("1", "Alpha", "2", "8"),
            listOf("2", "Beta", "3", "12")
        ) { sheet ->
            val (header, rows, headerSource) = analyzePoiSheet(context, sheet)

            assertEquals(
                listOf("barcode", "rowNumber", "productName", "quantity", "purchasePrice", "totalPrice"),
                header
            )
            assertEquals("generated", headerSource[header.indexOf("barcode")])
            assertEquals("generated", headerSource[header.indexOf("purchasePrice")])
            assertEquals("", rows.first()[header.indexOf("barcode")])
            assertEquals("", rows.first()[header.indexOf("purchasePrice")])
        }
    }

    @Test
    fun `analyzePoiSheet generates headers when the sheet has no explicit header row`() {
        withSheet(
            listOf("SKU-1", "12345678", "Alpha", "2", "4", "X"),
            listOf("SKU-2", "23456789", "Beta", "3", "5", "Y")
        ) { sheet ->
            val (header, rows, headerSource) = analyzePoiSheet(context, sheet)

            assertTrue(header.contains("barcode"))
            assertTrue(header.contains("productName"))
            assertTrue(header.contains("purchasePrice"))
            assertTrue(header.any { it.startsWith(context.getString(R.string.generated_column_prefix)) })
            assertTrue(headerSource.contains("generated"))
            assertEquals(2, rows.size)
        }
    }

    @Test
    fun `readAndAnalyzeExcel cleans no-header structural blanks without reconstructing merge or image columns`() {
        val workbookFile = createWorkbookFile(
            name = "excel-no-header-structural-cleanup",
            rows = listOf(
                listOf(null, "20034", "6871128200344", "Dream Item One", null, 12.0, 270.0, 3240.0),
                listOf(null, "20089", "6871128200894", "Dream Item Two", null, 24.0, 480.0, 11520.0),
                emptyList(),
                emptyList()
            )
        ) { workbook, sheet ->
            sheet.addMergedRegion(CellRangeAddress(0, 0, 3, 4))
            sheet.addMergedRegion(CellRangeAddress(1, 1, 3, 4))

            val pictureBytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6bcAAAAASUVORK5CYII="
            )
            val pictureIndex = workbook.addPicture(pictureBytes, Workbook.PICTURE_TYPE_PNG)
            val drawing = sheet.createDrawingPatriarch()
            drawing.createPicture(workbook.creationHelper.createClientAnchor().apply {
                setCol1(0)
                setCol2(1)
                setRow1(0)
                setRow2(2)
            }, pictureIndex)
        }

        val (header, rows, headerSource) = readAndAnalyzeExcel(context, Uri.fromFile(workbookFile))

        assertEquals(
            listOf("itemNumber", "barcode", "productName", "quantity", "purchasePrice", "totalPrice"),
            header
        )
        assertEquals(
            listOf(
                listOf("20034", "6871128200344", "Dream Item One", "12", "270", "3240"),
                listOf("20089", "6871128200894", "Dream Item Two", "24", "480", "11520")
            ),
            rows
        )
        assertEquals(listOf("pattern", "pattern", "pattern", "pattern", "pattern", "pattern"), headerSource)
    }

    @Test
    fun `readAndAnalyzeExcel and analyzePoiSheet stay aligned for no-header structural cleanup`() {
        val workbookFile = createWorkbookFile(
            name = "excel-no-header-shared-cleanup",
            rows = listOf(
                listOf(null, "30001", "9876543210123", "Aligned One", null, 6.0, 150.0, 900.0),
                listOf(null, "30002", "9876543210456", "Aligned Two", null, 8.0, 125.0, 1000.0),
                emptyList()
            )
        ) { _, sheet ->
            sheet.addMergedRegion(CellRangeAddress(0, 0, 3, 4))
            sheet.addMergedRegion(CellRangeAddress(1, 1, 3, 4))
        }

        val fromFile = readAndAnalyzeExcel(context, Uri.fromFile(workbookFile))

        XSSFWorkbook(workbookFile.inputStream()).use { workbook ->
            val fromSheet = analyzePoiSheet(context, workbook.getSheetAt(0))
            assertEquals(fromSheet, fromFile)
        }
    }

    @Test
    fun `readAndAnalyzeExcel empty byte file throws localized empty file error`() {
        val emptyFile = File.createTempFile("excel-empty-bytes", ".xlsx", context.cacheDir)

        val error = assertThrows(IllegalArgumentException::class.java) {
            readAndAnalyzeExcel(context, Uri.fromFile(emptyFile))
        }

        assertEquals(context.getString(R.string.error_file_empty_or_invalid), error.message)
    }

    @Test
    fun `readAndAnalyzeExcel null input stream throws localized empty file error`() {
        val resolver = mockk<ContentResolver>()
        val testContext = mockk<Context>()
        every { testContext.getString(R.string.error_file_empty_or_invalid) } returns "unused"

        every { testContext.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns null

        val error = assertThrows(ExcelInputStreamUnavailableException::class.java) {
            readAndAnalyzeExcel(testContext, Uri.parse("content://test/null"))
        }

        assertEquals("Excel input stream unavailable", error.message)
    }

    @Test
    fun `readAndAnalyzeExcel empty workbook returns empty triple only when allowed`() {
        val workbookFile = createWorkbookFile("excel-empty-workbook")

        val error = assertThrows(IllegalArgumentException::class.java) {
            readAndAnalyzeExcel(context, Uri.fromFile(workbookFile))
        }
        assertEquals(context.getString(R.string.error_file_empty_or_invalid), error.message)

        val result = readAndAnalyzeExcel(
            context = context,
            uri = Uri.fromFile(workbookFile),
            allowEmptyTabularResult = true
        )

        assertTrue(result.first.isEmpty())
        assertTrue(result.second.isEmpty())
        assertTrue(result.third.isEmpty())
    }

    @Test
    fun `readAndAnalyzeExcel html without table throws localized empty file error`() {
        val htmlFile = File.createTempFile("excel-no-table", ".xls", context.cacheDir).apply {
            writeText("<html><body><p>No table here</p></body></html>")
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            readAndAnalyzeExcel(context, Uri.fromFile(htmlFile))
        }

        assertEquals(context.getString(R.string.error_file_empty_or_invalid), error.message)
    }

    @Test
    fun `readAndAnalyzeExcel recovers malformed legacy xls obj records`() {
        val malformedWorkbook = createMalformedLegacyObjWorkbookFile(
            cacheDir = context.cacheDir,
            name = "excel-malformed-legacy",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Quantity", "Total Price"),
                listOf("12345678", "Recovered", 4.0, 2.0, 8.0)
            )
        )

        val (header, rows, headerSource) = readAndAnalyzeExcel(context, Uri.fromFile(malformedWorkbook))

        assertEquals(
            listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
            header
        )
        assertEquals(listOf("12345678", "Recovered", "4", "2", "8"), rows.single())
        assertEquals(header.size, headerSource.size)
    }

    @Test
    fun `readAndAnalyzeExcel recovers strict ooxml xlsx workbook`() {
        val strictWorkbook = createStrictOoXmlWorkbookFile(
            cacheDir = context.cacheDir,
            name = "excel-strict-ooxml",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Quantity", "Total Price"),
                listOf("12345678", "Strict Recovery", 4.0, 2.0, 8.0)
            )
        )

        val (header, rows, headerSource) = readAndAnalyzeExcel(context, Uri.fromFile(strictWorkbook))

        assertEquals(
            listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
            header
        )
        assertEquals(listOf("12345678", "Strict Recovery", "4", "2", "8"), rows.single())
        assertEquals(header.size, headerSource.size)
    }

    @Test
    fun `resolveExcelFileErrorMessage maps strict ooxml to localized copy`() {
        val message = resolveExcelFileErrorMessage(
            context = context,
            throwable = POIXMLException("Strict OOXML isn't currently supported, please see bug #57699"),
            unknownFallbackResId = R.string.error_data_analysis_generic
        )

        assertEquals(context.getString(R.string.error_strict_ooxml_not_supported), message)
    }

    @Test
    fun `resolveExcelFileErrorMessage maps suppressed strict ooxml to localized copy`() {
        val originalFailure = POIXMLException("Strict OOXML isn't currently supported, please see bug #57699")
        val retryFailure = IOException("retry failed").apply {
            addSuppressed(originalFailure)
        }

        val message = resolveExcelFileErrorMessage(
            context = context,
            throwable = retryFailure,
            unknownFallbackResId = R.string.error_data_analysis_generic
        )

        assertEquals(context.getString(R.string.error_strict_ooxml_not_supported), message)
    }

    @Test
    fun `resolveExcelFileErrorMessage maps legacy hssf obj corruption to localized copy`() {
        val error = RecordFormatException("Unexpected size (0)").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "org.apache.poi.hssf.record.FtCfSubRecord",
                    "<init>",
                    "FtCfSubRecord.java",
                    73
                )
            )
        }

        val message = resolveExcelFileErrorMessage(
            context = context,
            throwable = error,
            unknownFallbackResId = R.string.error_data_analysis_generic
        )

        assertEquals(context.getString(R.string.error_legacy_xls_unreadable), message)
    }

    @Test
    fun `resolveExcelFileErrorMessage maps io exception to read failure copy`() {
        val message = resolveExcelFileErrorMessage(
            context = context,
            throwable = IOException("stream closed"),
            unknownFallbackResId = R.string.error_data_analysis_generic
        )

        assertEquals(context.getString(R.string.error_file_read_failed), message)
    }

    private fun withSheet(vararg rows: List<Any?>, block: (Sheet) -> Unit) {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("test")
            rows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex)
                values.forEachIndexed { cellIndex, value ->
                    when (value) {
                        null -> Unit
                        is Number -> row.createCell(cellIndex).setCellValue(value.toDouble())
                        else -> row.createCell(cellIndex).setCellValue(value.toString())
                    }
                }
            }
            block(sheet)
        }
    }

    private fun createWorkbookFile(
        name: String,
        rows: List<List<Any?>> = emptyList(),
        configure: (XSSFWorkbook, Sheet) -> Unit = { _, _ -> }
    ): File {
        val file = File.createTempFile(name, ".xlsx", context.cacheDir)
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet1")
            rows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex)
                values.forEachIndexed { cellIndex, value ->
                    when (value) {
                        null -> Unit
                        is Number -> row.createCell(cellIndex).setCellValue(value.toDouble())
                        else -> row.createCell(cellIndex).setCellValue(value.toString())
                    }
                }
            }
            configure(workbook, sheet)
            file.outputStream().use(workbook::write)
        }
        return file
    }
}

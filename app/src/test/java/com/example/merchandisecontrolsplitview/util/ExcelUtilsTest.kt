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
    fun `analyzePoiSheet keeps legacy single-row header fast path for clean files`() {
        withSheet(
            listOf("Barcode", "Product name", "Purchase Price", "Quantity", "Total Price"),
            listOf("12345678", "Alpha", "4", "2", "8"),
            listOf("23456789", "Beta", "5", "3", "15")
        ) { sheet ->
            val result = analyzePoiSheetDetailed(context, sheet)

            assertEquals("legacy-fast-path", result.trace.headerMode)
            assertEquals(listOf(0), result.trace.headerRows)
            assertEquals(
                listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
                result.header
            )
        }
    }

    @Test
    fun `analyzePoiSheet isolates printable table and combines split headers`() {
        withSheet(
            listOf("SHOPPING HOGAR SPA", "", "", "", "", "", "", "", ""),
            listOf("Nº ALBARAN :", "13076", "FECHA", "1/4/2026 16:41", "COD.CLIE:1048", "", "PAG:", "1/1", ""),
            listOf("", "REF.CAJAS", "COD.BARRA", "", "CANTID", "PRE/U", "DTO%", "PRE/U", "IMPORTE"),
            listOf("", "", "", "ARTICULO", "", "", "", "", ""),
            listOf(1, "10161", "6120000101614", "XJ2204-3", 12, 900, 0, 900, 10800),
            listOf(2, "10162", "6120000101621", "YJ5237", 12, 1100, 0, 1100, 13200)
        ) { sheet ->
            val result = analyzePoiSheetDetailed(context, sheet)
            val header = result.header
            val rows = result.dataRows

            assertEquals("combined-lookback", result.trace.headerMode)
            assertEquals(listOf(2, 3), result.trace.headerRows)
            assertEquals("itemNumber", header[1])
            assertEquals("barcode", header[2])
            assertEquals("productName", header[3])
            assertEquals("quantity", header[4])
            assertEquals("purchasePrice", header[5])
            assertEquals("discount", header[6])
            assertEquals("totalPrice", header[8])
            assertEquals(
                listOf("1", "10161", "6120000101614", "XJ2204-3", "12", "900", "0", "900", "10800"),
                rows.first()
            )
            assertEquals("alias", result.headerSource[1])
            assertEquals("alias", result.headerSource[2])
            assertEquals("alias", result.headerSource[3])
            assertEquals("alias", result.headerSource[4])
            assertEquals("alias", result.headerSource[5])
            assertEquals("alias", result.headerSource[6])
        }
    }

    @Test
    fun `analyzePoiSheet keeps REF CAJAS as item number when values are not row-like`() {
        withSheet(
            listOf("REF.CAJAS", "COD.BARRA", "ARTICULO", "CANTID", "PRE/U", "IMPORTE"),
            listOf("10161", "6120000101614", "Alpha", "12", "900", "10800"),
            listOf("11683", "6120000116830", "Beta", "8", "1500", "12000"),
            listOf("12970", "6120000129700", "Gamma", "12", "1500", "18000")
        ) { sheet ->
            val result = analyzePoiSheetDetailed(context, sheet)

            assertEquals("itemNumber", result.header[0])
            assertEquals("alias", result.headerSource[0])
            assertEquals(
                "header-alias",
                result.trace.fieldDecisions.first { it.field == "itemNumber" }.reason
            )
            assertEquals("10161", result.dataRows.first()[0])
        }
    }

    @Test
    fun `analyzePoiSheet distinguishes barcode and item number on headerless numeric columns`() {
        withSheet(
            listOf(900, "10161", "6120000101614", "Glass One", 12, 10800),
            listOf(1100, "10162", "6120000101621", "Glass Two", 12, 13200),
            listOf(1500, "11683", "6120000116830", "Glass Three", 8, 12000)
        ) { sheet ->
            val result = analyzePoiSheetDetailed(context, sheet)
            val header = result.header
            val firstRow = result.dataRows.first()

            assertEquals("10161", firstRow[header.indexOf("itemNumber")])
            assertEquals("6120000101614", firstRow[header.indexOf("barcode")])
            assertEquals(
                "pattern-score",
                result.trace.fieldDecisions.first { it.field == "barcode" }.reason
            )
            assertEquals(
                "pattern-score",
                result.trace.fieldDecisions.first { it.field == "itemNumber" }.reason
            )
        }
    }

    @Test
    fun `analyzePoiSheet distinguishes quantity and purchase price when both are integers`() {
        withSheet(
            listOf("6120000101614", 900, 12, "10161", "Glass One"),
            listOf("6120000101621", 1100, 12, "10162", "Glass Two"),
            listOf("6120000116830", 1500, 8, "11683", "Glass Three")
        ) { sheet ->
            val result = analyzePoiSheetDetailed(context, sheet)
            val header = result.header
            val firstRow = result.dataRows.first()

            assertEquals("12", firstRow[header.indexOf("quantity")])
            assertEquals("900", firstRow[header.indexOf("purchasePrice")])
            assertEquals("high", result.trace.fieldDecisions.first { it.field == "quantity" }.confidence)
            assertEquals("high", result.trace.fieldDecisions.first { it.field == "purchasePrice" }.confidence)
        }
    }

    @Test
    fun `analyzePoiSheet handles printable shopping layout with grouped integer totals`() {
        fun sparseRow(lastColumn: Int, values: Map<Int, Any?>): List<Any?> {
            val row = MutableList<Any?>(lastColumn + 1) { "" }
            values.forEach { (index, value) -> row[index] = value }
            return row
        }

        withSheet(
            sparseRow(
                51,
                mapOf(
                    3 to "Nº ALBARAN : 13076",
                    13 to "FECHA: 1/4/2026 16:41",
                    20 to "COD.CLIE:1048",
                    36 to "PAG: 1/1"
                )
            ),
            sparseRow(
                51,
                mapOf(
                    1 to "REF.CAJAS",
                    9 to "COD.BARRA",
                    27 to "CANTID",
                    33 to "PRE/U",
                    40 to "DTO%",
                    44 to "PRE/U",
                    49 to "IMPORTE"
                )
            ),
            sparseRow(51, mapOf(9 to "ARTICULO")),
            sparseRow(
                51,
                mapOf(
                    1 to "1",
                    6 to "10161",
                    11 to "6120000101614",
                    15 to "XJ2204-3马桶刷",
                    29 to "12",
                    38 to "900",
                    43 to "0",
                    47 to "900",
                    51 to "10,800"
                )
            ),
            sparseRow(
                51,
                mapOf(
                    1 to "2",
                    6 to "10162",
                    11 to "6120000101621",
                    15 to "YJ5237马桶刷",
                    29 to "12",
                    38 to "1,100",
                    43 to "0",
                    47 to "1,100",
                    51 to "13,200"
                )
            ),
            sparseRow(
                51,
                mapOf(
                    1 to "3",
                    6 to "10163",
                    11 to "6120000101638",
                    15 to "HX-093马桶吸",
                    29 to "12",
                    38 to "1,000",
                    43 to "0",
                    47 to "1,000",
                    51 to "12,000"
                )
            ),
            sparseRow(
                51,
                mapOf(
                    1 to "4",
                    6 to "11683",
                    11 to "6120000116830",
                    15 to "45X2M 无胶膜玻璃贴 R041",
                    29 to "8",
                    38 to "1,500",
                    43 to "0",
                    47 to "1,500",
                    51 to "12,000"
                )
            ),
            sparseRow(
                51,
                mapOf(
                    1 to "5",
                    6 to "11692",
                    11 to "6120000116922",
                    15 to "45X2M 无胶膜玻璃贴 R001",
                    29 to "8",
                    38 to "1,500",
                    43 to "0",
                    47 to "1,500",
                    51 to "12,000"
                )
            )
        ) { sheet ->
            val result = analyzePoiSheetDetailed(context, sheet)
            val header = result.header
            val firstRow = result.dataRows.first()
            val itemNumberIdx = header.indexOf("itemNumber")
            val barcodeIdx = header.indexOf("barcode")
            val productNameIdx = header.indexOf("productName")
            val quantityIdx = header.indexOf("quantity")
            val purchasePriceIdx = header.indexOf("purchasePrice")
            val totalPriceIdx = header.indexOf("totalPrice")

            assertEquals("combined-lookback", result.trace.headerMode)
            assertTrue(header.contains("REF.CAJAS"))
            assertTrue(itemNumberIdx >= 0)
            assertTrue(barcodeIdx >= 0)
            assertTrue(productNameIdx >= 0)
            assertTrue(quantityIdx >= 0)
            assertTrue(purchasePriceIdx >= 0)
            assertTrue(totalPriceIdx >= 0)
            assertEquals("10161", firstRow[itemNumberIdx])
            assertEquals("6120000101614", firstRow[barcodeIdx])
            assertEquals("XJ2204-3马桶刷", firstRow[productNameIdx])
            assertEquals("12", firstRow[quantityIdx])
            assertEquals("900", firstRow[purchasePriceIdx])
            assertEquals("10,800", firstRow[totalPriceIdx])
            assertEquals("pattern-score", result.trace.fieldDecisions.first { it.field == "itemNumber" }.reason)
            assertEquals(
                "quantity-multiplication",
                result.trace.fieldDecisions.first { it.field == "purchasePrice" }.reason
            )
            assertEquals(
                "quantity-multiplication",
                result.trace.fieldDecisions.first { it.field == "totalPrice" }.reason
            )
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
    fun `analyzePoiSheet filters hyperasian like footer rows with Chinese total labels`() {
        withSheet(
            listOf("rowNumber", "barcode", "itemNumber", "productName", "quantity", "purchasePrice", "totalPrice"),
            listOf("1", "12345678", "ITEM-1", "Alpha", "2", "4", "8"),
            listOf("2", "23456789", "ITEM-2", "Beta", "1", "5", "5"),
            listOf("总数", "", "", "总价", "3", "9", "13")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)
            val nameCol = header.indexOf("productName")

            assertEquals(2, rows.size)
            assertFalse(rows.any { row -> row.getOrNull(nameCol) == "总价" })
        }
    }

    @Test
    fun `analyzePoiSheet filters footer rows even when they keep false product identity`() {
        withSheet(
            listOf("rowNumber", "barcode", "itemNumber", "productName", "quantity", "purchasePrice", "totalPrice"),
            listOf("1", "12345678", "ITEM-1", "Alpha", "2", "4", "8"),
            listOf("2", "23456789", "ITEM-2", "Beta", "1", "5", "5"),
            listOf("3", "0", "150", "合计总数", "3", "9", "13")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)
            val itemCol = header.indexOf("itemNumber")

            assertEquals(2, rows.size)
            assertFalse(rows.any { row -> row.getOrNull(itemCol) == "150" })
        }
    }

    @Test
    fun `analyzePoiSheet filters footer rows when aggregates shift into identity columns`() {
        withSheet(
            listOf("产品货号", "条码", "产品名1", "产品名2", "数量", "单价", "总价"),
            listOf("148995", "7877771489951", "6027水杯650ml(120)", "BOTELLA DE AGUA(120)", "12", "700", "8400"),
            listOf("149346", "7877771493460", "横扫款 10cm粘毛器20撕 3pc(12/144）", "QUITA PELUSAS(12/144）", "12", "600", "7200"),
            listOf("总数", "728.000", "总价", "685920.000", "", "", "")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)
            val itemCol = header.indexOf("itemNumber")
            val barcodeCol = header.indexOf("barcode")
            val nameCol = header.indexOf("productName")

            assertEquals(2, rows.size)
            assertFalse(rows.any { row -> row.getOrNull(itemCol) == "总数" })
            assertFalse(rows.any { row -> row.getOrNull(barcodeCol) == "728.000" })
            assertFalse(rows.any { row -> row.getOrNull(nameCol) == "总价" })
        }
    }

    @Test
    fun `analyzePoiSheet keeps real products whose names start with total`() {
        withSheet(
            listOf("rowNumber", "barcode", "itemNumber", "productName", "quantity", "purchasePrice", "totalPrice"),
            listOf("1", "12345678", "ITEM-001", "Total Care Shampoo", "5", "10", "50")
        ) { sheet ->
            val (header, rows) = analyzePoiSheet(context, sheet)
            val nameCol = header.indexOf("productName")

            assertEquals(1, rows.size)
            assertEquals("Total Care Shampoo", rows.single()[nameCol])
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

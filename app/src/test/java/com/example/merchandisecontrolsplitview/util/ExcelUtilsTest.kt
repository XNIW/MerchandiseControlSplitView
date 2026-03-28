package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.R
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `formatNumberAsRoundedString returns dash for null`() {
        assertEquals("-", formatNumberAsRoundedString(null))
    }

    @Test
    fun `formatNumberAsRoundedString rounds non integer values`() {
        assertEquals("5", formatNumberAsRoundedString(4.6))
    }

    @Test
    fun `formatNumberAsRoundedString keeps zero`() {
        assertEquals("0", formatNumberAsRoundedString(0.0))
    }

    @Test
    fun `formatNumberAsRoundedStringForInput returns empty string for null`() {
        assertEquals("", formatNumberAsRoundedStringForInput(null))
    }

    @Test
    fun `formatNumberAsRoundedStringForInput rounds non integer values`() {
        assertEquals("4", formatNumberAsRoundedStringForInput(3.6))
    }

    @Test
    fun `formatNumberAsRoundedStringForInput keeps zero`() {
        assertEquals("0", formatNumberAsRoundedStringForInput(0.0))
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
}

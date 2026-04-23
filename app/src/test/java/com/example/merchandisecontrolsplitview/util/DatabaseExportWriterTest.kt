package com.example.merchandisecontrolsplitview.util

import com.example.merchandisecontrolsplitview.data.PriceHistoryExportRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.data.Supplier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseExportWriterTest {

    @Test
    fun `buildDatabaseExportDisplayName follows full and partial naming rules`() {
        val timestamp = LocalDateTime.of(2026, 3, 29, 14, 30, 0)

        assertEquals(
            "Database_2026_03_29_14-30-00.xlsx",
            buildDatabaseExportDisplayName(
                selection = ExportSheetSelection.full(),
                timestamp = timestamp
            )
        )
        assertEquals(
            "Database_partial_S_C_2026_03_29_14-30-00.xlsx",
            buildDatabaseExportDisplayName(
                selection = ExportSheetSelection.catalogOnly(),
                timestamp = timestamp
            )
        )
        assertEquals(
            "Database_partial_P_S_PH_2026_03_29_14-30-00.xlsx",
            buildDatabaseExportDisplayName(
                selection = ExportSheetSelection(
                    products = true,
                    suppliers = true,
                    categories = false,
                    priceHistory = true
                ),
                timestamp = timestamp
            )
        )
    }

    @Test
    fun `writeDatabaseExport keeps workbook selective and writes header only for empty selected sheet`() {
        val output = ByteArrayOutputStream()

        writeDatabaseExport(
            outputStream = output,
            selection = ExportSheetSelection.catalogOnly(),
            schema = sampleSchema(),
            content = DatabaseExportContent(
                suppliers = listOf(Supplier(id = 1L, name = "Supplier One")),
                categories = emptyList()
            )
        )

        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { workbook ->
            val sheetNames = (0 until workbook.numberOfSheets).map(workbook::getSheetName)
            assertEquals(
                listOf(
                    DatabaseExportConstants.SHEET_SUPPLIERS,
                    DatabaseExportConstants.SHEET_CATEGORIES
                ),
                sheetNames
            )
            assertEquals(
                DatabaseExportConstants.SUPPLIER_HEADERS,
                firstRowAsStrings(workbook, DatabaseExportConstants.SHEET_SUPPLIERS)
            )
            assertEquals(
                DatabaseExportConstants.CATEGORY_HEADERS,
                firstRowAsStrings(workbook, DatabaseExportConstants.SHEET_CATEGORIES)
            )
            assertEquals(
                2,
                workbook.getSheet(DatabaseExportConstants.SHEET_SUPPLIERS).physicalNumberOfRows
            )
            assertEquals(
                1,
                workbook.getSheet(DatabaseExportConstants.SHEET_CATEGORIES).physicalNumberOfRows
            )
        }
    }

    @Test
    fun `writeDatabaseExport computes old price per barcode and type without sheet grouping`() {
        val output = ByteArrayOutputStream()

        writeDatabaseExport(
            outputStream = output,
            selection = ExportSheetSelection.priceHistoryOnly(),
            schema = sampleSchema(),
            content = DatabaseExportContent(
                priceHistoryRows = listOf(
                    PriceHistoryExportRow(
                        barcode = "00000001",
                        timestamp = "2026-01-01 10:00:00",
                        type = "PURCHASE",
                        price = 3.0,
                        source = "MANUAL"
                    ),
                    PriceHistoryExportRow(
                        barcode = "00000001",
                        timestamp = "2026-02-01 10:00:00",
                        type = "PURCHASE",
                        price = 4.0,
                        source = "MANUAL"
                    ),
                    PriceHistoryExportRow(
                        barcode = "00000001",
                        timestamp = "2026-03-01 10:00:00",
                        type = "RETAIL",
                        price = 7.0,
                        source = "MANUAL"
                    )
                )
            )
        )

        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { workbook ->
            val sheet = workbook.getSheet(DatabaseExportConstants.SHEET_PRICE_HISTORY)

            assertEquals(
                DatabaseExportConstants.PRICE_HISTORY_HEADERS,
                firstRowAsStrings(workbook, DatabaseExportConstants.SHEET_PRICE_HISTORY)
            )
            assertTrue(sheet.getRow(1).getCell(3) == null || sheet.getRow(1).getCell(3).toString().isBlank())
            assertEquals(3.0, sheet.getRow(2).getCell(3).numericCellValue, 0.0001)
            assertEquals(
                DatabaseExportConstants.PRICE_TYPE_RETAIL,
                sheet.getRow(3).getCell(2).stringCellValue
            )
            assertTrue(sheet.getRow(3).getCell(3) == null || sheet.getRow(3).getCell(3).toString().isBlank())
        }
    }

    @Test
    fun `writeDatabaseExportStreaming merges paged product fetches into one sheet`() = runBlocking {
        val output = ByteArrayOutputStream()
        writeDatabaseExportStreaming(
            outputStream = output,
            selection = ExportSheetSelection.productsOnly(),
            schema = sampleSchema(),
            suppliers = emptyList(),
            categories = emptyList(),
            fetchProductPage = { _, offset ->
                when (offset) {
                    0 -> listOf(sampleProductWithDetails("111"))
                    1 -> listOf(sampleProductWithDetails("222"))
                    else -> emptyList()
                }
            },
            fetchPriceHistoryPage = { _, _ -> emptyList() },
            pageSize = 1
        )
        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { workbook ->
            val sheet = workbook.getSheet(DatabaseExportConstants.SHEET_PRODUCTS)
            assertEquals(3, sheet.physicalNumberOfRows)
            assertEquals("111", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("222", sheet.getRow(2).getCell(0).stringCellValue)
        }
    }

    @Test
    fun `writeDatabaseExportStreaming keeps previous price continuity across page boundaries`() = runBlocking {
        val output = ByteArrayOutputStream()
        writeDatabaseExportStreaming(
            outputStream = output,
            selection = ExportSheetSelection.priceHistoryOnly(),
            schema = sampleSchema(),
            suppliers = emptyList(),
            categories = emptyList(),
            fetchProductPage = { _, _ -> emptyList() },
            fetchPriceHistoryPage = { _, offset ->
                when (offset) {
                    0 -> listOf(
                        PriceHistoryExportRow(
                            barcode = "00000001",
                            timestamp = "2026-01-01 10:00:00",
                            type = "PURCHASE",
                            price = 3.0,
                            source = "MANUAL"
                        )
                    )
                    1 -> listOf(
                        PriceHistoryExportRow(
                            barcode = "00000001",
                            timestamp = "2026-02-01 10:00:00",
                            type = "PURCHASE",
                            price = 4.0,
                            source = "MANUAL"
                        )
                    )
                    2 -> listOf(
                        PriceHistoryExportRow(
                            barcode = "00000001",
                            timestamp = "2026-03-01 10:00:00",
                            type = "RETAIL",
                            price = 7.0,
                            source = "MANUAL"
                        )
                    )
                    else -> emptyList()
                }
            },
            pageSize = 1
        )

        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { workbook ->
            val sheet = workbook.getSheet(DatabaseExportConstants.SHEET_PRICE_HISTORY)
            assertTrue(sheet.getRow(1).getCell(3) == null || sheet.getRow(1).getCell(3).toString().isBlank())
            assertEquals(3.0, sheet.getRow(2).getCell(3).numericCellValue, 0.0001)
            assertTrue(sheet.getRow(3).getCell(3) == null || sheet.getRow(3).getCell(3).toString().isBlank())
        }
    }

    @Test
    fun `writeDatabaseExport writes products sheet current prices from price summary`() {
        val output = ByteArrayOutputStream()

        writeDatabaseExport(
            outputStream = output,
            selection = ExportSheetSelection.productsOnly(),
            schema = sampleSchema(),
            content = DatabaseExportContent(
                products = listOf(
                    ProductWithDetails(
                        product = Product(
                            barcode = "dm04-043",
                            productName = "DM04 043",
                            purchasePrice = 10.0,
                            retailPrice = 1100.0
                        ),
                        supplierName = null,
                        categoryName = null,
                        lastPurchase = 11.0,
                        prevPurchase = 10.0,
                        lastRetail = 1101.0,
                        prevRetail = 1100.0
                    )
                )
            )
        )

        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { workbook ->
            val row = workbook.getSheet(DatabaseExportConstants.SHEET_PRODUCTS).getRow(1)
            assertEquals(11.0, row.getCell(4).numericCellValue, 0.0001)
            assertEquals(1101.0, row.getCell(5).numericCellValue, 0.0001)
            assertEquals(10.0, row.getCell(6).numericCellValue, 0.0001)
            assertEquals(1100.0, row.getCell(7).numericCellValue, 0.0001)
        }
    }

    private fun sampleProductWithDetails(barcode: String): ProductWithDetails =
        ProductWithDetails(
            product = Product(
                barcode = barcode,
                productName = "P",
                retailPrice = 1.0
            ),
            supplierName = null,
            categoryName = null,
            lastPurchase = null,
            prevPurchase = null,
            lastRetail = null,
            prevRetail = null
        )

    private fun sampleSchema(): DatabaseExportSchema =
        DatabaseExportSchema(
            productHeaders = listOf("barcode", "name")
        )

    private fun firstRowAsStrings(workbook: XSSFWorkbook, sheetName: String): List<String> {
        val row = workbook.getSheet(sheetName).getRow(0)
        return (0 until row.lastCellNum).map { index ->
            row.getCell(index).stringCellValue
        }
    }
}

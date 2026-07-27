package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.DuplicateWarning
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportAnalyzerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: InventoryRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = mockk(relaxed = true)

        coEvery { repository.getAllSuppliers() } returns emptyList()
        coEvery { repository.getAllCategories() } returns emptyList()
        coEvery { repository.findSupplierByName(any()) } returns null
        coEvery { repository.findCategoryByName(any()) } returns null
        coEvery { repository.addSupplier(any()) } answers {
            Supplier(id = 100L, name = firstArg())
        }
        coEvery { repository.addCategory(any()) } answers {
            Category(id = 200L, name = firstArg())
        }
    }

    @Test
    fun `analyze keeps missing supplier and category deferred without preview writes`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    supplier = "Supplier A",
                    category = "Category A"
                )
            )
        )

        val product = analysis.newProducts.single()
        assertTrue((product.supplierId ?: 0L) < 0L)
        assertTrue((product.categoryId ?: 0L) < 0L)
        coVerify(exactly = 0) { repository.addSupplier(any()) }
        coVerify(exactly = 0) { repository.addCategory(any()) }
    }

    @Test
    fun `analyze does not add supplier when it is already cached from repository`() = runTest {
        coEvery { repository.getAllSuppliers() } returns listOf(Supplier(id = 7L, name = "Supplier A"))

        val analysis = analyze(importedRows = listOf(importedRow(supplier = "supplier a")))

        assertEquals(7L, analysis.newProducts.single().supplierId)
        coVerify(exactly = 0) { repository.findSupplierByName(any()) }
        coVerify(exactly = 0) { repository.addSupplier(any()) }
    }

    @Test
    fun `analyze does not add category when find lookup resolves it`() = runTest {
        coEvery { repository.findCategoryByName("Category A") } returns Category(id = 8L, name = "Category A")

        val analysis = analyze(importedRows = listOf(importedRow(category = "Category A")))

        assertEquals(8L, analysis.newProducts.single().categoryId)
        coVerify(exactly = 1) { repository.findCategoryByName("Category A") }
        coVerify(exactly = 0) { repository.addCategory(any()) }
    }

    @Test
    fun `analyze does not create update when price difference stays within tolerance`() = runTest {
        val current = existingProduct(purchasePrice = 10.0)

        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = current.barcode, purchasePrice = "10.001")),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `xlsx numeric barcode display format preserves leading zeroes`() {
        val temp = File.createTempFile("task090-leading-zero", ".xlsx")
        try {
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet("Products")
                val header = sheet.createRow(0)
                header.createCell(0).setCellValue("barcode")
                header.createCell(1).setCellValue("productName")
                header.createCell(2).setCellValue("retailPrice")
                val barcodeStyle = workbook.createCellStyle()
                barcodeStyle.dataFormat = workbook.creationHelper
                    .createDataFormat()
                    .getFormat("0000000000000")
                val row = sheet.createRow(1)
                row.createCell(0).apply {
                    setCellValue(123456789.0)
                    cellStyle = barcodeStyle
                }
                row.createCell(1).setCellValue("Leading Zero Product")
                row.createCell(2).setCellValue(10.0)

                temp.outputStream().use { output -> workbook.write(output) }
            }

            val result = readAndAnalyzeExcelDetailed(context, Uri.fromFile(temp))
            val parsedValues = result.dataRows.flatten()

            assertTrue(parsedValues.contains("0000123456789"))
            assertFalse(parsedValues.contains("123456789"))
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `analyze adds update when price difference exceeds tolerance`() = runTest {
        val current = existingProduct(purchasePrice = 10.0)

        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = current.barcode, purchasePrice = "10.002")),
            currentDbProducts = listOf(current)
        )

        val update = analysis.updatedProducts.single()
        assertTrue(update.changedFields.contains(R.string.purchase_price_label))
        assertEquals(10.002, update.newProduct.purchasePrice!!, 0.0001)
    }

    @Test
    fun `analyze treats product name comparison as case insensitive`() = runTest {
        val current = existingProduct(productName = "Alpha")

        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = current.barcode, productName = "alpha")),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
    }

    @Test
    fun `analyze treats item number comparison as case insensitive`() = runTest {
        val current = existingProduct(itemNumber = "ITEM-ABC")

        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = current.barcode, itemNumber = "item-abc")),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
    }

    @Test
    fun `analyze treats trim case and blank differences as semantic no-op`() = runTest {
        val current = existingProduct(
            barcode = " 55556666 ",
            itemNumber = " ITEM-ABC ",
            productName = "Alpha Product",
            secondProductName = "Second Name"
        )

        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = "55556666",
                    itemNumber = "item-abc",
                    productName = " alpha product ",
                    secondProductName = " second name "
                )
            ),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze treats imported blank optional text as unchanged`() = runTest {
        val current = existingProduct(
            itemNumber = "ITEM-ABC",
            secondProductName = "Second Name"
        )

        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = current.barcode,
                    itemNumber = "",
                    secondProductName = ""
                )
            ),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze skips supplier changed field when supplier names match ignoring case`() = runTest {
        val current = existingProduct(supplierId = 1L)
        coEvery { repository.getAllSuppliers() } returns listOf(
            Supplier(id = 1L, name = "ACME"),
            Supplier(id = 2L, name = "acme")
        )

        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = current.barcode, supplier = "acme")),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze preserves existing supplier id when equivalent supplier name accompanies another change`() = runTest {
        val current = existingProduct(supplierId = 1L, stockQuantity = 2.0)
        coEvery { repository.getAllSuppliers() } returns listOf(
            Supplier(id = 1L, name = "Café Supplier"),
            Supplier(id = 2L, name = " cafe supplier ")
        )

        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = current.barcode,
                    supplier = "CAFE SUPPLIER",
                    quantity = "3"
                )
            ),
            currentDbProducts = listOf(current)
        )

        val update = analysis.updatedProducts.single()
        assertEquals(1L, update.newProduct.supplierId)
        assertFalse(update.changedFields.contains(R.string.field_supplier))
        assertTrue(update.changedFields.contains(R.string.field_stock_quantity))
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze skips category changed field when category names match ignoring case`() = runTest {
        val current = existingProduct(categoryId = 11L)
        coEvery { repository.getAllCategories() } returns listOf(
            Category(id = 11L, name = "Snacks"),
            Category(id = 12L, name = "snacks")
        )

        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = current.barcode, category = "snacks")),
            currentDbProducts = listOf(current)
        )

        assertTrue(analysis.updatedProducts.isEmpty())
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze preserves existing category id when equivalent category name accompanies another change`() = runTest {
        val current = existingProduct(categoryId = 11L, stockQuantity = 2.0)
        coEvery { repository.getAllCategories() } returns listOf(
            Category(id = 11L, name = "Bebidas"),
            Category(id = 12L, name = " bebidas ")
        )

        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = current.barcode,
                    category = "BEBIDAS",
                    quantity = "3"
                )
            ),
            currentDbProducts = listOf(current)
        )

        val update = analysis.updatedProducts.single()
        assertEquals(11L, update.newProduct.categoryId)
        assertFalse(update.changedFields.contains(R.string.field_category))
        assertTrue(update.changedFields.contains(R.string.field_stock_quantity))
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze merges duplicate rows with last row wins without summing quantity`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(barcode = "11111111", productName = "Other", itemNumber = "O-1"),
                importedRow(barcode = "99999999", productName = "First", itemNumber = "A-1", quantity = "2", purchasePrice = "4", retailPrice = "6"),
                importedRow(barcode = "22222222", productName = "Another", itemNumber = "O-2"),
                importedRow(barcode = "33333333", productName = "Third", itemNumber = "O-3"),
                importedRow(barcode = "99999999", productName = "Last", itemNumber = "A-2", quantity = "3", realQuantity = "10", purchasePrice = "5", retailPrice = "7")
            )
        )

        val merged = analysis.newProducts.single { it.barcode == "99999999" }
        val warning = duplicateWarningFor(analysis, "99999999")

        assertEquals("Last", merged.productName)
        assertEquals("A-2", merged.itemNumber)
        assertEquals(5.0, merged.purchasePrice!!, 0.0001)
        assertEquals(7.0, merged.retailPrice!!, 0.0001)
        assertEquals(10.0, merged.stockQuantity!!, 0.0001)
        assertEquals(listOf(2, 5), warning.rowNumbers)
    }

    @Test
    fun `analyze uses last duplicate row number for post merge validation errors`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(barcode = "11111111", productName = "Other", itemNumber = "O-1"),
                importedRow(barcode = "99999999", productName = "First", itemNumber = "A-1", discount = "10"),
                importedRow(barcode = "22222222", productName = "Another", itemNumber = "O-2"),
                importedRow(barcode = "99999999", productName = "Last", itemNumber = "A-2", discount = "150")
            )
        )

        val error = rowErrorFor(analysis, R.string.error_invalid_discount)
        val warning = duplicateWarningFor(analysis, "99999999")

        assertEquals(listOf(2, 4), warning.rowNumbers)
        assertEquals(4, error.rowNumber)
    }

    @Test
    fun `analyze prefers discounted price over purchase price and discount formula`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    purchasePrice = "10",
                    discount = "20",
                    discountedPrice = "7"
                )
            )
        )

        assertEquals(7.0, analysis.newProducts.single().purchasePrice!!, 0.0001)
    }

    @Test
    fun `analyze maps old purchase and retail canonical keys into old prices`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    oldPurchasePrice = "2",
                    oldRetailPrice = "5"
                )
            )
        )

        val product = analysis.newProducts.single()
        assertEquals(2.0, product.oldPurchasePrice!!, 0.0001)
        assertEquals(5.0, product.oldRetailPrice!!, 0.0001)
    }

    @Test
    fun `analyze rejects product names beyond the common contract limit`() = runTest {
        val longName = "N".repeat(CatalogTextPolicy.Limits.PRODUCT_NAME + 1)

        val analysis = analyze(importedRows = listOf(importedRow(productName = longName)))

        assertTrue(analysis.newProducts.isEmpty())
        val error = analysis.errors.single()
        assertEquals(R.string.error_catalog_text_rejected, error.errorReasonResId)
        assertTrue(
            error.formatArgs.contains(context.getString(R.string.catalog_text_error_too_long))
        )
    }

    @Test
    fun `analyze canonicalizes display text in preview and reports fields without raw values`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    productName = "  Café\t\nCasa\u00a0 ",
                    supplier = "  Proveedor\nUno ",
                    category = " Té\t "
                )
            )
        )

        val product = analysis.newProducts.single()
        assertEquals("Café Casa", product.productName)
        val warning = analysis.textNormalizationWarnings.single()
        assertEquals(
            setOf(
                CatalogTextField.PRODUCT_NAME,
                CatalogTextField.SUPPLIER_NAME,
                CatalogTextField.CATEGORY_NAME
            ),
            warning.fields
        )
        assertEquals(1, analysis.normalizedRowCount)
        assertEquals(3, analysis.normalizedFieldCount)
    }

    @Test
    fun `analyze rejects strict barcode controls before dedupe`() = runTest {
        val analysis = analyze(
            importedRows = listOf(importedRow(barcode = "1234\n5678"))
        )

        assertTrue(analysis.newProducts.isEmpty())
        assertTrue(analysis.warnings.isEmpty())
        assertEquals(R.string.error_catalog_text_rejected, analysis.errors.single().errorReasonResId)
    }

    @Test
    fun `analyze blocks distinct raw barcodes that collide after trim instead of last wins`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(barcode = " CODE-001 ", productName = "First"),
                importedRow(barcode = "CODE-001", productName = "Second")
            )
        )

        assertTrue(analysis.newProducts.isEmpty())
        assertTrue(analysis.updatedProducts.isEmpty())
        val error = analysis.errors.single()
        assertEquals(R.string.error_catalog_text_rejected, error.errorReasonResId)
        assertTrue(
            error.formatArgs.contains(context.getString(R.string.catalog_text_error_identity_collision))
        )
    }

    @Test
    fun `analyze blocks every product in an item number trim collision`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(barcode = "CODE-101", itemNumber = " ITEM-001 "),
                importedRow(barcode = "CODE-102", itemNumber = "ITEM-001")
            )
        )

        assertTrue(analysis.newProducts.isEmpty())
        assertEquals(2, analysis.errors.size)
        assertTrue(
            analysis.errors.all {
                it.formatArgs.contains(context.getString(R.string.catalog_text_error_identity_collision))
            }
        )
    }

    @Test
    fun `analyze catches item number trim collision before same barcode last row wins`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = "CODE-SAME",
                    productName = "First",
                    itemNumber = " ITEM-SAME "
                ),
                importedRow(
                    barcode = "CODE-SAME",
                    productName = "Last",
                    itemNumber = "ITEM-SAME"
                )
            )
        )

        assertTrue(analysis.newProducts.isEmpty())
        assertTrue(analysis.updatedProducts.isEmpty())
        assertTrue(analysis.warnings.isEmpty())
        val error = analysis.errors.single()
        assertEquals(2, error.rowNumber)
        assertTrue(
            error.formatArgs.contains(context.getString(R.string.catalog_text_error_identity_collision))
        )
    }

    @Test
    fun `analyze keeps strict identity collision detection case sensitive`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(barcode = "CASE-A", itemNumber = "ITEM-A"),
                importedRow(barcode = "case-a", itemNumber = "item-a")
            )
        )

        assertEquals(2, analysis.newProducts.size)
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyze unexpected row error hides technical exception text`() = runTest {
        coEvery { repository.findSupplierByName("Broken Supplier") } throws IllegalStateException("db boom")

        val analysis = analyze(
            importedRows = listOf(importedRow(supplier = "Broken Supplier"))
        )

        val error = analysis.errors.single()
        assertEquals(R.string.error_import_row_processing_failed, error.errorReasonResId)
        assertTrue(error.formatArgs.isEmpty())
        assertEquals(
            context.getString(R.string.error_import_row_processing_failed),
            context.getString(error.errorReasonResId, *error.formatArgs.toTypedArray())
        )
    }

    @Test
    fun `analyzeStreaming processes a basic new product chunk`() = runTest {
        val analysis = analyzeStreaming(
            chunks = sequenceOf(
                listOf(importedRow(barcode = "12344321", productName = "Streaming Product"))
            )
        )

        assertEquals(1, analysis.newProducts.size)
        assertEquals("12344321", analysis.newProducts.single().barcode)
        assertTrue(analysis.errors.isEmpty())
    }

    @Test
    fun `analyzeStreaming keeps missing supplier and category deferred without preview writes`() = runTest {
        val analysis = analyzeStreaming(
            chunks = sequenceOf(
                listOf(
                    importedRow(
                        supplier = "Streaming Supplier",
                        category = "Streaming Category"
                    )
                )
            )
        )

        val product = analysis.newProducts.single()
        assertTrue((product.supplierId ?: 0L) < 0L)
        assertTrue((product.categoryId ?: 0L) < 0L)
        coVerify(exactly = 0) { repository.addSupplier(any()) }
        coVerify(exactly = 0) { repository.addCategory(any()) }
    }

    @Test
    fun `analyzeStreaming merges cross chunk duplicates with last row wins without summing quantity`() = runTest {
        val analysis = analyzeStreaming(
            chunks = sequenceOf(
                listOf(
                    importedRow(barcode = "11111111", productName = "Other"),
                    importedRow(barcode = "99999999", productName = "First", itemNumber = "A-1", quantity = "2", purchasePrice = "4", retailPrice = "6")
                ),
                listOf(
                    importedRow(barcode = "22222222", productName = "Another"),
                    importedRow(barcode = "99999999", productName = "Last", itemNumber = "A-2", quantity = "3", realQuantity = "7", purchasePrice = "5", retailPrice = "8")
                )
            )
        )

        val merged = analysis.newProducts.single { it.barcode == "99999999" }
        val warning = duplicateWarningFor(analysis, "99999999")

        assertEquals("Last", merged.productName)
        assertEquals("A-2", merged.itemNumber)
        assertEquals(7.0, merged.stockQuantity!!, 0.0001)
        assertEquals(5.0, merged.purchasePrice!!, 0.0001)
        assertEquals(listOf(2, 4), warning.rowNumbers)
    }

    @Test
    fun `analyzeStreaming uses last duplicate row number for post merge validation errors`() = runTest {
        val analysis = analyzeStreaming(
            chunks = sequenceOf(
                listOf(
                    importedRow(barcode = "99999999", productName = "First", itemNumber = "A-1", discount = "10")
                ),
                listOf(
                    importedRow(barcode = "11111111", productName = "Other"),
                    importedRow(barcode = "99999999", productName = "Last", itemNumber = "A-2", discount = "150")
                )
            )
        )

        val error = rowErrorFor(analysis, R.string.error_invalid_discount)
        val warning = duplicateWarningFor(analysis, "99999999")

        assertEquals(listOf(1, 3), warning.rowNumbers)
        assertEquals(3, error.rowNumber)
    }

    @Test
    fun `analyze caps duplicate warning samples while preserving total occurrences and winner row`() = runTest {
        val importedRows = (1..52).map { index ->
            importedRow(
                barcode = "99999999",
                itemNumber = "ITEM-$index",
                productName = "Product $index",
                quantity = "1",
                purchasePrice = "4",
                retailPrice = "6"
            )
        }

        val analysis = analyze(importedRows = importedRows)
        val warning = duplicateWarningFor(analysis, "99999999")
        val merged = analysis.newProducts.single { it.barcode == "99999999" }

        assertEquals(52, warning.totalOccurrences)
        assertEquals(50, warning.rowNumbers.size)
        assertEquals((1..49).toList(), warning.rowNumbers.dropLast(1))
        assertEquals(52, warning.rowNumbers.last())
        assertEquals("ITEM-52", merged.itemNumber)
        assertEquals("Product 52", merged.productName)
        assertEquals(1.0, merged.stockQuantity!!, 0.0001)
    }

    @Test
    fun `golden supplier import fixture matches Android analyzer contract`() = runTest {
        val fixture = JSONObject(
            supplierImportFixtureFile().readText()
        )
        val rows = fixture.getJSONArray("sampleRows").toStringRows()
        val result = analyzeRowsDetailed(context, rows)

        assertEquals(fixture.getJSONArray("normalizedHeader").toStringList(), result.header)
        assertEquals(fixture.getInt("dataRowsCount"), result.dataRows.size)

        val sheetRows = fixture.getJSONArray("sheetRows").toStringRows()
        val sheetResult = analyzeRowsDetailed(context, sheetRows)
        assertEquals(fixture.getJSONArray("normalizedHeader").toStringList(), sheetResult.header)
        assertEquals(fixture.getInt("dataRowsCount"), sheetResult.dataRows.size)
        assertTrue(fixture.getJSONArray("metadataRowsBeforeHeader").length() > 0)

        val aliasSamples = fixture.getJSONObject("aliasSamples")
        val aliasSampleKeys = aliasSamples.keys()
        while (aliasSampleKeys.hasNext()) {
            val aliasRows = aliasSamples.getJSONArray(aliasSampleKeys.next()).toStringRows()
            val aliasResult = analyzeRowsDetailed(context, aliasRows)
            assertEquals(fixture.getJSONArray("normalizedHeader").toStringList(), aliasResult.header)
        }

        val headerlessSample = fixture.getJSONObject("headerlessSample")
        val headerlessResult = analyzeRowsDetailed(context, headerlessSample.getJSONArray("rows").toStringRows())
        assertEquals(headerlessSample.getJSONArray("normalizedHeader").toStringList(), headerlessResult.header)
        assertEquals(headerlessSample.getJSONArray("headerSource").toStringList(), headerlessResult.headerSource)

        val headerSource = fixture.getJSONObject("headerSource")
        assertEquals(
            result.header.map { headerSource.getString(it) },
            result.headerSource
        )
        assertEquals(
            listOf(
                "barcode",
                "productName",
                "itemNumber",
                "purchasePrice",
                "retailPrice",
                "quantity",
                "supplier",
                "category",
                "secondProductName",
                "totalPrice",
                "rowNumber",
                "discount",
                "discountedPrice",
                "oldPurchasePrice",
                "oldRetailPrice",
                "realQuantity",
                "complete"
            ),
            fixture.getJSONObject("publicKeysAudit").getJSONArray("allowed").toStringList()
        )

        val parseNumberCases = fixture.getJSONObject("parseNumberResults")
        parseNumberCases.keys().forEach { raw ->
            assertEquals(parseNumberCases.getDouble(raw), parseNumber(raw)!!, 0.0001)
        }

        val importedRows = result.dataRows.mapIndexed { index, row ->
            result.header.mapIndexed { columnIndex, key ->
                key to row.getOrElse(columnIndex) { "" }
            }.toMap().toMutableMap().apply {
                put("rowNumber", (index + 2).toString())
            }
        }

        val analysis = analyze(
            importedRows = importedRows,
            currentDbProducts = listOf(
                existingProduct(
                    barcode = "9999999900001",
                    itemNumber = "EX-001",
                    productName = "Existing old",
                    purchasePrice = 90.0,
                    retailPrice = 140.0,
                    stockQuantity = null
                )
            )
        )

        assertEquals(fixture.getInt("newProducts"), analysis.newProducts.size)
        assertEquals(fixture.getInt("updatedProducts"), analysis.updatedProducts.size)
        assertEquals(fixture.getJSONArray("errors").length(), analysis.errors.size)
        assertEquals(fixture.getBoolean("canApply"), analysis.errors.isEmpty())
        assertTrue(
            analysis.newProducts.any {
                it.barcode == fixture.getString("itemNumberOnlyAcceptedBarcode")
            }
        )

        val duplicateWarning = fixture.getJSONObject("duplicateWarning")
        val warning = duplicateWarningFor(analysis, duplicateWarning.getString("barcode"))
        assertEquals(duplicateWarning.getJSONArray("rows").toIntList(), warning.rowNumbers)

        val missingRetail = fixture.getJSONObject("newProductMissingRetail")
        val blockedAnalysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = missingRetail.getString("barcode"),
                    itemNumber = missingRetail.getString("itemNumber"),
                    productName = "",
                    purchasePrice = missingRetail.getDouble("purchasePrice").toString(),
                    quantity = missingRetail.getDouble("quantity").toString(),
                    retailPrice = null
                )
            ),
            currentDbProducts = emptyList()
        )
        assertTrue(blockedAnalysis.errors.isNotEmpty())

        val forbiddenKeys = fixture.getJSONArray("forbiddenPublicKeys").toStringList()
        forbiddenKeys.forEach { key ->
            assertFalse(result.header.contains(key))
            importedRows.forEach { row -> assertFalse(row.containsKey(key)) }
        }
        val auditForbiddenKeys = fixture.getJSONObject("publicKeysAudit").getJSONArray("forbidden").toStringList()
        fixture.getJSONArray("previewRows").let { previewRows ->
            auditForbiddenKeys.forEach { key ->
                for (index in 0 until previewRows.length()) {
                    assertFalse(previewRows.getJSONObject(index).has(key))
                }
            }
        }
    }

    @Test
    fun `analyzeStreaming unexpected row error hides technical exception text`() = runTest {
        coEvery { repository.findSupplierByName("Broken Supplier") } throws IllegalStateException("stream boom")

        val analysis = analyzeStreaming(
            chunks = sequenceOf(listOf(importedRow(supplier = "Broken Supplier")))
        )

        val error = analysis.errors.single()
        assertEquals(R.string.error_import_row_processing_failed, error.errorReasonResId)
        assertTrue(error.formatArgs.isEmpty())
        assertEquals(
            context.getString(R.string.error_import_row_processing_failed),
            context.getString(error.errorReasonResId, *error.formatArgs.toTypedArray())
        )
    }

    @Test
    fun `analyzeStreamingDeferredRelations exposes pending relation maps for missing names`() = runTest {
        val analysis = ImportAnalyzer.analyzeStreamingDeferredRelations(
            context = context,
            currentDbProducts = emptyList(),
            repository = repository
        ) { consumer ->
            consumer(
                importedRow(
                    supplier = "Deferred Supplier",
                    category = "Deferred Category"
                )
            )
        }

        val product = analysis.analysis.newProducts.single()
        assertEquals("Deferred Supplier", analysis.pendingSuppliers[product.supplierId])
        assertEquals("Deferred Category", analysis.pendingCategories[product.categoryId])
        coVerify(exactly = 0) { repository.addSupplier(any()) }
        coVerify(exactly = 0) { repository.addCategory(any()) }
    }

    private suspend fun analyze(
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product> = emptyList()
    ): ImportAnalysis = ImportAnalyzer.analyze(
        context = context,
        importedRows = importedRows,
        currentDbProducts = currentDbProducts,
        repository = repository
    )

    private suspend fun analyzeStreaming(
        chunks: Sequence<List<Map<String, String>>>,
        currentDbProducts: List<Product> = emptyList()
    ): ImportAnalysis = ImportAnalyzer.analyzeStreaming(
        context = context,
        chunks = chunks,
        currentDbProducts = currentDbProducts,
        repository = repository
    )

    private fun existingProduct(
        barcode: String = "55556666",
        itemNumber: String? = "ITEM-1",
        productName: String? = "Alpha",
        secondProductName: String? = "Second",
        purchasePrice: Double? = 4.0,
        retailPrice: Double? = 6.0,
        supplierId: Long? = null,
        categoryId: Long? = null,
        stockQuantity: Double? = 2.0
    ) = Product(
        id = 99L,
        barcode = barcode,
        itemNumber = itemNumber,
        productName = productName,
        secondProductName = secondProductName,
        purchasePrice = purchasePrice,
        retailPrice = retailPrice,
        supplierId = supplierId,
        categoryId = categoryId,
        stockQuantity = stockQuantity
    )

    private fun importedRow(
        barcode: String = "12345678",
        itemNumber: String? = "ITEM-1",
        productName: String? = "Alpha",
        secondProductName: String? = "Second",
        supplier: String? = null,
        category: String? = null,
        quantity: String? = "2",
        realQuantity: String? = null,
        purchasePrice: String? = "4",
        retailPrice: String? = "6",
        discount: String? = null,
        discountedPrice: String? = null,
        oldPurchasePrice: String? = null,
        oldRetailPrice: String? = null
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        put("barcode", barcode)
        itemNumber?.let { put("itemNumber", it) }
        productName?.let { put("productName", it) }
        secondProductName?.let { put("secondProductName", it) }
        supplier?.let { put("supplier", it) }
        category?.let { put("category", it) }
        quantity?.let { put("quantity", it) }
        realQuantity?.let { put("realQuantity", it) }
        purchasePrice?.let { put("purchasePrice", it) }
        retailPrice?.let { put("retailPrice", it) }
        discount?.let { put("discount", it) }
        discountedPrice?.let { put("discountedPrice", it) }
        oldPurchasePrice?.let { put("oldPurchasePrice", it) }
        oldRetailPrice?.let { put("oldRetailPrice", it) }
    }

    private fun duplicateWarningFor(analysis: ImportAnalysis, barcode: String): DuplicateWarning =
        analysis.warnings.single { it.barcode == barcode }

    private fun rowErrorFor(analysis: ImportAnalysis, errorReasonResId: Int): RowImportError =
        analysis.errors.single { it.errorReasonResId == errorReasonResId }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun JSONArray.toIntList(): List<Int> =
        (0 until length()).map { getInt(it) }

    private fun JSONArray.toStringRows(): List<List<String>> =
        (0 until length()).map { rowIndex ->
            getJSONArray(rowIndex).toStringList()
        }

    private fun supplierImportFixtureFile(): File =
        listOf(
            File("tests/fixtures/supplier-import/android-canonical-sample.json"),
            File("../tests/fixtures/supplier-import/android-canonical-sample.json")
        ).first { it.isFile }
}

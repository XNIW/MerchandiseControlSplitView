package com.example.merchandisecontrolsplitview.util

import android.content.Context
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
    fun `analyze creates missing supplier and category`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    supplier = "Supplier A",
                    category = "Category A"
                )
            )
        )

        val product = analysis.newProducts.single()
        assertEquals(100L, product.supplierId)
        assertEquals(200L, product.categoryId)
        coVerify(exactly = 1) { repository.addSupplier("Supplier A") }
        coVerify(exactly = 1) { repository.addCategory("Category A") }
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
    fun `analyze treats second product name comparison as case sensitive`() = runTest {
        val current = existingProduct(secondProductName = "Second Name")

        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    barcode = current.barcode,
                    secondProductName = "second name"
                )
            ),
            currentDbProducts = listOf(current)
        )

        val update = analysis.updatedProducts.single()
        assertTrue(update.changedFields.contains(R.string.field_second_product_name))
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
    fun `analyze merges duplicate rows with last row wins and aggregated quantity`() = runTest {
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
        assertEquals(12.0, merged.stockQuantity!!, 0.0001)
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
    fun `analyze maps prev purchase and retail aliases into old prices`() = runTest {
        val analysis = analyze(
            importedRows = listOf(
                importedRow(
                    prevPurchase = "2",
                    prevRetail = "5"
                )
            )
        )

        val product = analysis.newProducts.single()
        assertEquals(2.0, product.oldPurchasePrice!!, 0.0001)
        assertEquals(5.0, product.oldRetailPrice!!, 0.0001)
    }

    @Test
    fun `analyze truncates product names beyond the maximum length`() = runTest {
        val longName = "N".repeat(150)

        val analysis = analyze(importedRows = listOf(importedRow(productName = longName)))

        assertEquals(100, analysis.newProducts.single().productName!!.length)
    }

    @Test
    fun `analyze unexpected row error hides technical exception text`() = runTest {
        coEvery { repository.addSupplier("Broken Supplier") } throws IllegalStateException("db boom")

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
    fun `analyzeStreaming merges cross chunk duplicates with last row wins and aggregated quantity`() = runTest {
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
        assertEquals(9.0, merged.stockQuantity!!, 0.0001)
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
    fun `analyzeStreaming unexpected row error hides technical exception text`() = runTest {
        coEvery { repository.addSupplier("Broken Supplier") } throws IllegalStateException("stream boom")

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
        prevPurchase: String? = null,
        prevRetail: String? = null
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
        prevPurchase?.let { put("prevPurchase", it) }
        prevRetail?.let { put("prevRetail", it) }
    }

    private fun duplicateWarningFor(analysis: ImportAnalysis, barcode: String): DuplicateWarning =
        analysis.warnings.single { it.barcode == barcode }

    private fun rowErrorFor(analysis: ImportAnalysis, errorReasonResId: Int): RowImportError =
        analysis.errors.single { it.errorReasonResId == errorReasonResId }
}

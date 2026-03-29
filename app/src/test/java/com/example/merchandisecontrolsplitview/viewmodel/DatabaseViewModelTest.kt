package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Looper
import app.cash.turbine.test
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: InventoryRepository
    private lateinit var viewModel: DatabaseViewModel
    private lateinit var app: Application

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        repository = mockk(relaxed = true)

        every { repository.getProductsWithDetailsPaged(any()) } returns mockk(relaxed = true)
        every { repository.getPriceSeries(any(), any()) } returns emptyFlow()
        every { repository.getFilteredHistoryFlow(any()) } returns flowOf(emptyList())

        coEvery { repository.getAllSuppliers() } returns emptyList()
        coEvery { repository.getAllCategories() } returns emptyList()
        coEvery { repository.findSupplierByName(any()) } returns null
        coEvery { repository.findCategoryByName(any()) } returns null
        coEvery { repository.addSupplier(any()) } returns null
        coEvery { repository.addCategory(any()) } returns null

        viewModel = DatabaseViewModel(app, repository)
    }

    @Test
    fun `addProduct success emits success state`() = runTest {
        val product = sampleProduct(barcode = "12345678")
        coEvery { repository.addProduct(product) } just runs

        viewModel.addProduct(product)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.addProduct(product) }
        viewModel.uiState.test {
            assertEquals(
                UiState.Success(app.getString(R.string.success_product_added)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addProduct duplicate barcode emits duplicate error`() = runTest {
        val product = sampleProduct(barcode = "12345678")
        coEvery { repository.addProduct(product) } throws SQLiteConstraintException("duplicate")

        viewModel.addProduct(product)
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(
                UiState.Error(app.getString(R.string.error_barcode_already_exists)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateProduct success emits success state`() = runTest {
        val product = sampleProduct(id = 9L, barcode = "22223333")
        coEvery { repository.updateProduct(product) } just runs

        viewModel.updateProduct(product)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateProduct(product) }
        viewModel.uiState.test {
            assertEquals(
                UiState.Success(app.getString(R.string.success_product_updated)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateProduct constraint error emits duplicate error`() = runTest {
        val product = sampleProduct(id = 9L, barcode = "22223333")
        coEvery { repository.updateProduct(product) } throws SQLiteConstraintException("duplicate")

        viewModel.updateProduct(product)
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(
                UiState.Error(app.getString(R.string.error_barcode_already_exists)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteProduct success emits success state`() = runTest {
        val product = sampleProduct(id = 12L, barcode = "44445555")
        coEvery { repository.deleteProduct(product) } just runs

        viewModel.deleteProduct(product)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteProduct(product) }
        viewModel.uiState.test {
            assertEquals(
                UiState.Success(app.getString(R.string.success_product_deleted)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analyzeGridData success updates analysis result and returns idle`() = runTest {
        val existing = sampleProduct(id = 1L, barcode = "12345678", productName = "Existing")
        coEvery { repository.getAllProducts() } returns listOf(existing)

        val gridData = listOf(
            mapOf(
                "barcode" to "12345678",
                "productName" to "Existing Updated",
                "purchasePrice" to "5.0",
                "retailPrice" to "8.0",
                "quantity" to "4"
            ),
            mapOf(
                "barcode" to "87654321",
                "productName" to "Brand New",
                "purchasePrice" to "7.0",
                "retailPrice" to "10.0",
                "quantity" to "3"
            )
        )

        viewModel.analyzeGridData(gridData)
        advanceUntilIdle()
        waitForCondition {
            viewModel.importAnalysisResult.value != null && viewModel.uiState.value == UiState.Idle
        }

        val result = viewModel.importAnalysisResult.value
        assertNotNull(result)
        assertTrue(result!!.newProducts.any { it.barcode == "87654321" })
        assertTrue(result.updatedProducts.any { it.oldProduct.barcode == "12345678" })
        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `analyzeGridData repository failure emits error state`() = runTest {
        coEvery { repository.getAllProducts() } throws IllegalStateException("db unavailable")

        viewModel.analyzeGridData(
            listOf(
                mapOf(
                    "barcode" to "12345678",
                    "productName" to "Broken",
                    "purchasePrice" to "4.0",
                    "retailPrice" to "6.0",
                    "quantity" to "1"
                )
            )
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Error }

        val state = viewModel.uiState.value
        assertEquals(
            UiState.Error(app.getString(R.string.error_data_analysis_generic)),
            state
        )
    }

    @Test
    fun `startImportAnalysis happy path analyzes workbook generated in test`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()
        val workbookFile = createWorkbook(
            name = "import-success",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(12345678.0, "Imported Item", 4.0, 6.0, 2.0)
            )
        )

        viewModel.startImportAnalysis(app, Uri.fromFile(workbookFile))
        advanceUntilIdle()
        waitForCondition {
            viewModel.importAnalysisResult.value != null || viewModel.uiState.value is UiState.Error
        }

        val result = viewModel.importAnalysisResult.value
        assertNotNull(result)
        assertTrue(result!!.newProducts.any { it.barcode == "12345678" })
        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `startImportAnalysis invalid file emits error state`() = runTest {
        val invalidFile = File.createTempFile("invalid-import", ".xlsx", app.cacheDir).apply {
            writeText("not an excel workbook")
        }

        viewModel.startImportAnalysis(app, Uri.fromFile(invalidFile))
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Error }

        assertNull(viewModel.importAnalysisResult.value)
        assertEquals(
            UiState.Error(app.getString(R.string.error_file_access_denied)),
            viewModel.uiState.value
        )
    }

    @Test
    fun `exportToExcel with empty dataset emits no products error`() = runTest {
        coEvery { repository.getAllProductsWithDetails() } returns emptyList()
        val targetFile = File.createTempFile("export-empty", ".xlsx", app.cacheDir)

        viewModel.exportToExcel(app, Uri.fromFile(targetFile))
        advanceUntilIdle()

        viewModel.uiState.test {
            assertEquals(
                UiState.Error(app.getString(R.string.error_no_products_to_export)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exportToExcel with products writes workbook and emits success`() = runTest {
        coEvery { repository.getAllProductsWithDetails() } returns listOf(
            ProductWithDetails(
                product = sampleProduct(id = 7L, barcode = "55554444"),
                supplierName = "Supplier",
                categoryName = "Category",
                lastPurchase = 3.0,
                prevPurchase = 2.5,
                lastRetail = 5.0,
                prevRetail = 4.5
            )
        )
        val targetFile = File.createTempFile("export-success", ".xlsx", app.cacheDir)

        viewModel.exportToExcel(app, Uri.fromFile(targetFile))
        advanceUntilIdle()

        waitForCondition { viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error }

        assertTrue(targetFile.length() > 0)
        assertEquals(
            UiState.Success(app.getString(R.string.export_success)),
            viewModel.uiState.value
        )
    }

    @Test
    fun `exportFullDbToExcel maps out of memory failures to error state`() = runTest {
        coEvery { repository.getAllProductsWithDetails() } returns emptyList()
        coEvery { repository.getAllSuppliers() } returns emptyList()
        coEvery { repository.getAllCategories() } returns emptyList()
        coEvery { repository.getAllPriceHistoryRows() } throws OutOfMemoryError("heap exhausted")
        val targetFile = File.createTempFile("export-full-oom", ".xlsx", app.cacheDir)

        viewModel.exportFullDbToExcel(app, Uri.fromFile(targetFile))
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Error }

        val state = viewModel.uiState.value
        assertEquals(
            UiState.Error(app.getString(R.string.error_file_too_large_or_complex)),
            state
        )
    }

    @Test
    fun `consumeUiState resets state to idle`() = runTest {
        coEvery { repository.addProduct(any()) } just runs
        viewModel.addProduct(sampleProduct(barcode = "98989898"))
        advanceUntilIdle()

        viewModel.consumeUiState()

        viewModel.uiState.test {
            assertEquals(UiState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearImportAnalysis clears previous analysis result`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()

        viewModel.analyzeGridData(
            listOf(
                mapOf(
                    "barcode" to "87654321",
                    "productName" to "Item",
                    "purchasePrice" to "4.0",
                    "retailPrice" to "5.0",
                    "quantity" to "2"
                )
            )
        )
        advanceUntilIdle()
        waitForCondition { viewModel.importAnalysisResult.value != null }

        viewModel.clearImportAnalysis()

        assertNull(viewModel.importAnalysisResult.value)
    }

    @Test
    fun `importProducts applies import and appends success log`() = runTest {
        val insertedHistory = slot<HistoryEntry>()
        val updatedHistory = slot<HistoryEntry>()
        val oldProduct = sampleProduct(id = 10L, barcode = "11111111", productName = "Old Name")
        val updatedProduct = oldProduct.copy(productName = "New Name", purchasePrice = 8.0)
        val pendingEntry = historyEntry().copy(uid = 88L)

        coEvery { repository.insertHistoryEntry(capture(insertedHistory)) } returns 88L
        coEvery { repository.getHistoryEntryByUid(88L) } returns pendingEntry
        coEvery { repository.updateHistoryEntry(capture(updatedHistory)) } just runs
        coEvery { repository.applyImport(any(), any()) } just runs

        viewModel.importProducts(
            newProducts = listOf(sampleProduct(barcode = "22222222", productName = "Brand New")),
            updatedProducts = listOf(ProductUpdate(oldProduct, updatedProduct, changedFields = listOf(1))),
            context = app
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error }

        coVerify(timeout = 3_000, exactly = 1) {
            repository.applyImport(
                match { it.single().barcode == "22222222" },
                match { it.single().id == oldProduct.id && it.single().productName == "New Name" }
            )
        }
        assertEquals("STARTED", insertedHistory.captured.data[1][0])
        assertEquals("SUCCESS", updatedHistory.captured.data.last()[0])
        viewModel.uiState.test {
            assertEquals(
                UiState.Success(app.getString(R.string.import_success)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importProducts repository failure emits generic error and safe history log`() = runTest {
        val insertedHistory = slot<HistoryEntry>()
        val updatedHistory = slot<HistoryEntry>()
        val pendingEntry = historyEntry().copy(uid = 91L)

        coEvery { repository.insertHistoryEntry(capture(insertedHistory)) } returns 91L
        coEvery { repository.getHistoryEntryByUid(91L) } returns pendingEntry
        coEvery { repository.updateHistoryEntry(capture(updatedHistory)) } just runs
        coEvery { repository.applyImport(any(), any()) } throws IllegalStateException("db offline")

        viewModel.importProducts(
            newProducts = listOf(sampleProduct(barcode = "33333333", productName = "Broken")),
            updatedProducts = emptyList(),
            context = app
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Error }

        assertEquals(
            UiState.Error(app.getString(R.string.error_import_generic)),
            viewModel.uiState.value
        )
        assertEquals("FAILED", updatedHistory.captured.data.last()[0])
        assertEquals(
            app.getString(R.string.error_import_generic),
            updatedHistory.captured.data.last()[1]
        )
    }

    private fun sampleProduct(
        id: Long = 0L,
        barcode: String,
        productName: String = "Product"
    ) = Product(
        id = id,
        barcode = barcode,
        productName = productName,
        purchasePrice = 3.0,
        retailPrice = 4.0,
        stockQuantity = 1.0
    )

    private fun historyEntry() = HistoryEntry(
        id = "apply-import",
        timestamp = "2026-03-28 10:00:00",
        data = listOf(
            listOf("status", "message"),
            listOf("STARTED", "Applying import")
        ),
        editable = listOf(listOf("", "")),
        complete = listOf(false),
        supplier = "Supplier",
        category = "Category",
        totalItems = 0,
        orderTotal = 0.0,
        paymentTotal = 0.0,
        missingItems = 0,
        syncStatus = SyncStatus.NOT_ATTEMPTED,
        wasExported = false
    )

    private fun createWorkbook(
        name: String,
        rows: List<List<Any>>
    ): File {
        val file = File.createTempFile(name, ".xlsx", app.cacheDir)
        XSSFWorkbook().use { workbook ->
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
            file.outputStream().use(workbook::write)
        }
        return file
    }

    private fun waitForCondition(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }
}

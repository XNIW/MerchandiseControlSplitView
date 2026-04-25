package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Looper
import app.cash.turbine.test
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.CatalogBlankNameException
import com.example.merchandisecontrolsplitview.data.CatalogDeleteResult
import com.example.merchandisecontrolsplitview.data.CatalogDeleteStrategy
import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.CatalogListItem
import com.example.merchandisecontrolsplitview.data.ImportApplyResult
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.testutil.createMalformedLegacyObjWorkbookFile
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import com.example.merchandisecontrolsplitview.testutil.createStrictOoXmlWorkbookFile
import com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOrigin
import com.example.merchandisecontrolsplitview.util.DatabaseExportConstants
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        every { repository.observeSuppliersForHubSearch(any()) } returns flowOf(emptyList())
        every { repository.observeCategoriesForHubSearch(any()) } returns flowOf(emptyList())
        every { repository.observeCatalogItems(any(), any()) } returns flowOf(emptyList())
        coEvery { repository.findSupplierByName(any()) } returns null
        coEvery { repository.findCategoryByName(any()) } returns null
        coEvery { repository.addSupplier(any()) } returns null
        coEvery { repository.addCategory(any()) } returns null
        coEvery { repository.getCatalogItems(any(), any()) } returns emptyList()
        coEvery { repository.deleteCatalogEntry(any(), any(), any()) } returns CatalogDeleteResult(
            affectedProducts = 0,
            strategy = CatalogDeleteStrategy.DeleteIfUnused
        )
        coEvery { repository.getProductsWithDetailsPage(any(), any()) } returns emptyList()
        coEvery { repository.getPriceHistoryRowsPage(any(), any()) } returns emptyList()

        viewModel = DatabaseViewModel(app, repository)
    }

    @Test
    fun `supplierCatalogSection emits loaded state from repository`() = runTest {
        every { repository.observeCatalogItems(CatalogEntityKind.SUPPLIER, null) } returns flowOf(
            listOf(
                CatalogListItem(
                    id = 7L,
                    name = "North Supplier",
                    productCount = 3
                )
            )
        )

        viewModel.supplierCatalogSection.test {
            assertTrue(awaitItem().isLoading)
            val loaded = awaitItem()
            assertEquals("", loaded.query)
            assertEquals(1, loaded.items.size)
            assertEquals("North Supplier", loaded.items.single().name)
            assertEquals(3, loaded.items.single().productCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `createCatalogEntry blank name emits localized error`() = runTest {
        coEvery {
            repository.createCatalogEntry(CatalogEntityKind.CATEGORY, "   ")
        } throws CatalogBlankNameException

        val result = viewModel.createCatalogEntry(CatalogEntityKind.CATEGORY, "   ")

        assertNull(result)
        assertEquals(
            UiState.Error(
                app.getString(
                    R.string.database_catalog_name_required,
                    app.getString(R.string.database_catalog_entity_category)
                )
            ),
            viewModel.uiState.value
        )
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
        assertTrue(viewModel.importFlowState.value is ImportFlowState.PreviewReady)
    }

    @Test
    fun `analyzeGridData records import origin and clear resets it to home`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()

        viewModel.analyzeGridData(
            listOf(
                mapOf(
                    "barcode" to "87654321",
                    "productName" to "Origin Item",
                    "purchasePrice" to "7.0",
                    "retailPrice" to "10.0",
                    "quantity" to "3"
                )
            ),
            navigationOrigin = ImportNavOrigin.HISTORY
        )
        advanceUntilIdle()
        waitForCondition { viewModel.importAnalysisResult.value != null }

        assertEquals(ImportNavOrigin.HISTORY, viewModel.importNavigationOrigin.value)

        viewModel.clearImportAnalysis()

        assertEquals(ImportNavOrigin.HOME, viewModel.importNavigationOrigin.value)
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
    fun `startSmartImport single sheet defaults origin to database`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()
        val workbookFile = createWorkbook(
            name = "smart-import-origin",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(12345679.0, "Database Import Item", 4.0, 6.0, 2.0)
            )
        )

        viewModel.startSmartImport(app, Uri.fromFile(workbookFile))
        advanceUntilIdle()
        waitForCondition {
            viewModel.importAnalysisResult.value != null || viewModel.uiState.value is UiState.Error
        }

        assertNotNull(viewModel.importAnalysisResult.value)
        assertEquals(ImportNavOrigin.DATABASE, viewModel.importNavigationOrigin.value)
    }

    @Test
    fun `startImportAnalysis excludes footer rows with false product identity`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()
        val workbookFile = createWorkbook(
            name = "import-footer-summary",
            rows = hyperAsianLikeRows(
                listOf(
                    listOf("1", "12345678", "ITEM-1", "Alpha", "2", "4", "6", "8"),
                    listOf("2", "23456789", "ITEM-2", "Beta", "1", "5", "8", "5")
                ),
                listOf("3", "0", "150", "合计总数", "3", "9", "14", "13")
            )
        )

        viewModel.startImportAnalysis(app, Uri.fromFile(workbookFile))
        advanceUntilIdle()
        waitForCondition {
            viewModel.importAnalysisResult.value != null || viewModel.uiState.value is UiState.Error
        }

        val result = viewModel.importAnalysisResult.value
        assertNotNull(result)
        assertEquals(2, result!!.newProducts.size)
        assertTrue(result.newProducts.none { it.barcode == "0" })
        assertTrue(result.newProducts.none { it.productName == "合计总数" })
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
            UiState.Error(app.getString(R.string.error_file_read_failed)),
            viewModel.uiState.value
        )
    }

    @Test
    fun `startImportAnalysis malformed legacy xls succeeds after fallback`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()
        val malformedWorkbook = createMalformedLegacyObjWorkbookFile(
            cacheDir = app.cacheDir,
            name = "import-malformed-legacy",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf("12345678", "Recovered Import", 4.0, 6.0, 2.0)
            )
        )

        viewModel.startImportAnalysis(app, Uri.fromFile(malformedWorkbook))
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
    fun `startImportAnalysis strict ooxml xlsx succeeds after fallback`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()
        val strictWorkbook = createStrictOoXmlWorkbookFile(
            cacheDir = app.cacheDir,
            name = "import-strict-ooxml",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf("12345678", "Strict Import", 4.0, 6.0, 2.0)
            )
        )

        viewModel.startImportAnalysis(app, Uri.fromFile(strictWorkbook))
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
    fun `startImportAnalysis empty workbook emits empty file error state`() = runTest {
        val emptyWorkbook = createWorkbook(name = "import-empty", rows = emptyList())

        viewModel.startImportAnalysis(app, Uri.fromFile(emptyWorkbook))
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Error }

        assertNull(viewModel.importAnalysisResult.value)
        assertEquals(
            UiState.Error(app.getString(R.string.error_file_empty_or_invalid)),
            viewModel.uiState.value
        )
    }

    @Test
    fun `exportDatabase products only with empty dataset writes header only and emits success`() = runTest {
        coEvery { repository.getProductsWithDetailsPage(any(), any()) } returns emptyList()
        val targetFile = File.createTempFile("export-products-empty", ".xlsx", app.cacheDir)

        viewModel.exportDatabase(
            context = app,
            uri = Uri.fromFile(targetFile),
            selection = ExportSheetSelection.productsOnly()
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error }

        assertEquals(
            UiState.Success(app.getString(R.string.export_success)),
            viewModel.uiState.value
        )
        XSSFWorkbook(targetFile.inputStream()).use { workbook ->
            assertEquals(1, workbook.numberOfSheets)
            assertEquals(
                DatabaseExportConstants.SHEET_PRODUCTS,
                workbook.getSheetName(0)
            )
            assertEquals(
                1,
                workbook.getSheet(DatabaseExportConstants.SHEET_PRODUCTS).physicalNumberOfRows
            )
        }
        coVerify(exactly = 1) { repository.getProductsWithDetailsPage(any(), any()) }
        coVerify(exactly = 0) { repository.getAllSuppliers() }
        coVerify(exactly = 0) { repository.getAllCategories() }
        coVerify(exactly = 0) { repository.getPriceHistoryRowsPage(any(), any()) }
        coVerify(exactly = 0) { repository.getAllProductsWithDetails() }
        coVerify(exactly = 0) { repository.getAllPriceHistoryRows() }
    }

    @Test
    fun `exportDatabase catalog only skips product and price history fetches`() = runTest {
        coEvery { repository.getAllSuppliers() } returns listOf(
            Supplier(
                id = 9L,
                name = "Supplier"
            )
        )
        coEvery { repository.getAllCategories() } returns emptyList()
        val targetFile = File.createTempFile("export-catalog", ".xlsx", app.cacheDir)

        viewModel.exportDatabase(
            context = app,
            uri = Uri.fromFile(targetFile),
            selection = ExportSheetSelection.catalogOnly()
        )
        advanceUntilIdle()

        waitForCondition { viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error }

        assertEquals(
            UiState.Success(app.getString(R.string.export_success)),
            viewModel.uiState.value
        )
        XSSFWorkbook(targetFile.inputStream()).use { workbook ->
            assertEquals(2, workbook.numberOfSheets)
            assertEquals(DatabaseExportConstants.SHEET_SUPPLIERS, workbook.getSheetName(0))
            assertEquals(DatabaseExportConstants.SHEET_CATEGORIES, workbook.getSheetName(1))
            assertEquals(
                2,
                workbook.getSheet(DatabaseExportConstants.SHEET_SUPPLIERS).physicalNumberOfRows
            )
            assertEquals(
                1,
                workbook.getSheet(DatabaseExportConstants.SHEET_CATEGORIES).physicalNumberOfRows
            )
        }
        coVerify(exactly = 1) { repository.getAllSuppliers() }
        coVerify(exactly = 1) { repository.getAllCategories() }
        coVerify(exactly = 0) { repository.getProductsWithDetailsPage(any(), any()) }
        coVerify(exactly = 0) { repository.getPriceHistoryRowsPage(any(), any()) }
        coVerify(exactly = 0) { repository.getAllProductsWithDetails() }
        coVerify(exactly = 0) { repository.getAllPriceHistoryRows() }
    }

    @Test
    fun `exportDatabase ignores second request while one export is already running`() = runTest {
        coEvery { repository.getProductsWithDetailsPage(any(), any()) } returns emptyList()
        val firstTargetFile = File.createTempFile("export-guard-first", ".xlsx", app.cacheDir)
        val secondTargetFile = File.createTempFile("export-guard-second", ".xlsx", app.cacheDir)

        viewModel.exportDatabase(
            context = app,
            uri = Uri.fromFile(firstTargetFile),
            selection = ExportSheetSelection.productsOnly()
        )

        viewModel.exportDatabase(
            context = app,
            uri = Uri.fromFile(secondTargetFile),
            selection = ExportSheetSelection.productsOnly()
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error }

        coVerify(exactly = 1) { repository.getProductsWithDetailsPage(any(), any()) }
        coVerify(exactly = 0) { repository.getAllProductsWithDetails() }
    }

    @Test
    fun `exportDatabase full selection maps out of memory failures to error state`() = runTest {
        coEvery { repository.getProductsWithDetailsPage(any(), any()) } returns emptyList()
        coEvery { repository.getAllSuppliers() } returns emptyList()
        coEvery { repository.getAllCategories() } returns emptyList()
        coEvery { repository.getPriceHistoryRowsPage(any(), any()) } throws OutOfMemoryError("heap exhausted")
        val targetFile = File.createTempFile("export-full-oom", ".xlsx", app.cacheDir)

        viewModel.exportDatabase(
            context = app,
            uri = Uri.fromFile(targetFile),
            selection = ExportSheetSelection.full()
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Error }

        val state = viewModel.uiState.value
        assertEquals(
            UiState.Error(app.getString(R.string.error_file_too_large_or_complex)),
            state
        )
        coVerify(exactly = 0) { repository.getAllProductsWithDetails() }
        coVerify(exactly = 0) { repository.getAllPriceHistoryRows() }
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
        assertTrue(viewModel.importFlowState.value is ImportFlowState.Cancelled)
    }

    @Test
    fun `importProducts applies import without persisting technical history entries`() = runTest {
        val oldProduct = sampleProduct(id = 10L, barcode = "11111111", productName = "Old Name")
        val updatedProduct = oldProduct.copy(productName = "New Name", purchasePrice = 8.0)
        val previewId = preparePreview()

        coEvery { repository.applyImport(any()) } returns ImportApplyResult.Success

        viewModel.importProducts(
            previewId = previewId,
            newProducts = listOf(sampleProduct(barcode = "22222222", productName = "Brand New")),
            updatedProducts = listOf(ProductUpdate(oldProduct, updatedProduct, changedFields = listOf(1))),
            context = app
        )
        advanceUntilIdle()
        waitForCondition { viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error }

        coVerify(timeout = 3_000, exactly = 1) {
            repository.applyImport(
                match {
                    it.newProducts.single().barcode == "22222222" &&
                        it.updatedProducts.single().oldProduct.id == oldProduct.id &&
                        it.updatedProducts.single().newProduct.productName == "New Name"
                }
            )
        }
        coVerify(exactly = 0) { repository.insertHistoryEntry(any()) }
        coVerify(exactly = 0) { repository.updateHistoryEntry(any()) }
        coVerify(exactly = 0) { repository.getHistoryEntryByUid(any()) }
        assertEquals(ImportFlowState.Success(previewId), viewModel.importFlowState.value)
        viewModel.uiState.test {
            assertEquals(
                UiState.Success(app.getString(R.string.import_success)),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importProducts applies valid updates when analysis also has row errors`() = runTest {
        val oldProduct = sampleProduct(id = 11L, barcode = "11112222", productName = "Old Name")
        coEvery { repository.getAllProducts() } returns listOf(oldProduct)

        viewModel.analyzeGridData(
            listOf(
                mapOf(
                    "barcode" to "11112222",
                    "productName" to "Updated Name",
                    "purchasePrice" to "3.0",
                    "retailPrice" to "5.0",
                    "quantity" to "2"
                ),
                mapOf(
                    "barcode" to "",
                    "productName" to "Broken Row",
                    "purchasePrice" to "4.0",
                    "retailPrice" to "6.0",
                    "quantity" to "1"
                )
            )
        )
        advanceUntilIdle()
        waitForCondition { viewModel.importAnalysisResult.value != null }
        val previewId = (viewModel.importFlowState.value as ImportFlowState.PreviewReady).previewId
        val analysis = viewModel.importAnalysisResult.value!!
        assertTrue(analysis.hasValidRowsToApply)
        assertTrue(analysis.errors.isNotEmpty())
        assertEquals(1, analysis.updatedProducts.size)
        assertEquals(0, analysis.newProducts.size)

        coEvery { repository.applyImport(any()) } returns ImportApplyResult.Success

        viewModel.importProducts(
            previewId = previewId,
            newProducts = analysis.newProducts,
            updatedProducts = analysis.updatedProducts,
            context = app
        )
        advanceUntilIdle()
        waitForCondition { viewModel.importFlowState.value is ImportFlowState.Success }

        coVerify(exactly = 1) {
            repository.applyImport(
                match {
                    it.newProducts.isEmpty() &&
                        it.updatedProducts.single().oldProduct.id == oldProduct.id &&
                        it.updatedProducts.single().newProduct.productName == "Updated Name"
                }
            )
        }
        assertEquals(ImportFlowState.Success(previewId), viewModel.importFlowState.value)
    }

    @Test
    fun `importProducts rejects preview with only row errors and no valid rows`() = runTest {
        coEvery { repository.getAllProducts() } returns emptyList()

        viewModel.analyzeGridData(
            listOf(
                mapOf(
                    "barcode" to "",
                    "productName" to "Broken Row",
                    "purchasePrice" to "4.0",
                    "retailPrice" to "6.0",
                    "quantity" to "1"
                )
            )
        )
        advanceUntilIdle()
        waitForCondition { viewModel.importAnalysisResult.value != null }
        val previewId = (viewModel.importFlowState.value as ImportFlowState.PreviewReady).previewId
        val analysis = viewModel.importAnalysisResult.value!!
        assertFalse(analysis.hasValidRowsToApply)
        assertTrue(analysis.errors.isNotEmpty())

        viewModel.importProducts(
            previewId = previewId,
            newProducts = analysis.newProducts,
            updatedProducts = analysis.updatedProducts,
            context = app
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.applyImport(any()) }
        assertEquals(
            ImportFlowState.Error(
                previewId = previewId,
                message = app.getString(R.string.import_no_valid_rows_to_apply),
                occurredDuringApply = false
            ),
            viewModel.importFlowState.value
        )
        assertEquals(
            UiState.Error(app.getString(R.string.import_no_valid_rows_to_apply)),
            viewModel.uiState.value
        )
    }

    @Test
    fun `importProducts repository failure emits generic error without persisting technical history entries`() = runTest {
        val previewId = preparePreview()

        coEvery { repository.applyImport(any()) } returns ImportApplyResult.Failure(
            IllegalStateException("db offline")
        )

        viewModel.importProducts(
            previewId = previewId,
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
        coVerify(exactly = 0) { repository.insertHistoryEntry(any()) }
        coVerify(exactly = 0) { repository.updateHistoryEntry(any()) }
        coVerify(exactly = 0) { repository.getHistoryEntryByUid(any()) }
        assertEquals(
            ImportFlowState.Error(
                previewId = previewId,
                message = app.getString(R.string.error_import_generic),
                occurredDuringApply = true
            ),
            viewModel.importFlowState.value
        )
    }

    @Test
    fun `recoverImportPreviewAfterApplyError restores preview and keeps analysis result`() = runTest {
        val previewId = preparePreview()

        coEvery { repository.applyImport(any()) } returns ImportApplyResult.Failure(
            IllegalStateException("db offline")
        )

        viewModel.importProducts(
            previewId = previewId,
            newProducts = listOf(sampleProduct(barcode = "33334444", productName = "Broken")),
            updatedProducts = emptyList(),
            context = app
        )
        advanceUntilIdle()
        waitForCondition { viewModel.importFlowState.value is ImportFlowState.Error }

        viewModel.recoverImportPreviewAfterApplyError()

        assertEquals(ImportFlowState.PreviewReady(previewId), viewModel.importFlowState.value)
        assertNotNull(viewModel.importAnalysisResult.value)
        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `importProducts ignores double confirm while apply is already running`() = runTest {
        val previewId = preparePreview()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.applyImport(any()) } coAnswers {
            gate.await()
            ImportApplyResult.Success
        }

        val firstApply = async {
            viewModel.importProducts(
                previewId = previewId,
                newProducts = listOf(sampleProduct(barcode = "66667777", productName = "First")),
                updatedProducts = emptyList(),
                context = app
            )
        }
        advanceUntilIdle()

        viewModel.importProducts(
            previewId = previewId,
            newProducts = listOf(sampleProduct(barcode = "66667778", productName = "Second")),
            updatedProducts = emptyList(),
            context = app
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.applyImport(any()) }
        coVerify(exactly = 0) { repository.insertHistoryEntry(any()) }
        gate.complete(Unit)
        firstApply.await()
        advanceUntilIdle()
    }

    @Test
    fun `clearImportAnalysis does not cancel an apply already in progress`() = runTest {
        val previewId = preparePreview()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.applyImport(any()) } coAnswers {
            gate.await()
            ImportApplyResult.Success
        }

        viewModel.importProducts(
            previewId = previewId,
            newProducts = listOf(sampleProduct(barcode = "77776666", productName = "In Flight")),
            updatedProducts = emptyList(),
            context = app
        )
        waitForCondition { viewModel.importFlowState.value is ImportFlowState.Applying }

        viewModel.clearImportAnalysis()

        assertTrue(viewModel.importFlowState.value is ImportFlowState.Applying)
        assertNotNull(viewModel.importAnalysisResult.value)

        gate.complete(Unit)
        waitForCondition { viewModel.importFlowState.value is ImportFlowState.Success }

        coVerify(exactly = 0) { repository.insertHistoryEntry(any()) }
        assertEquals(ImportFlowState.Success(previewId), viewModel.importFlowState.value)
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

    private suspend fun preparePreview(): Long {
        coEvery { repository.getAllProducts() } returns emptyList()
        viewModel.analyzeGridData(
            listOf(
                mapOf(
                    "barcode" to "55554444",
                    "productName" to "Preview Product",
                    "purchasePrice" to "4.0",
                    "retailPrice" to "6.0",
                    "quantity" to "1"
                )
            )
        )
        waitForCondition { viewModel.importFlowState.value is ImportFlowState.PreviewReady }
        return (viewModel.importFlowState.value as ImportFlowState.PreviewReady).previewId
    }

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

    private fun hyperAsianLikeRows(
        products: List<List<Any>>,
        footer: List<Any>
    ): List<List<Any>> {
        return listOf(
            listOf(
                "rowNumber",
                "barcode",
                "itemNumber",
                "productName",
                "quantity",
                "purchasePrice",
                "retailPrice",
                "totalPrice"
            )
        ) + products + listOf(footer)
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

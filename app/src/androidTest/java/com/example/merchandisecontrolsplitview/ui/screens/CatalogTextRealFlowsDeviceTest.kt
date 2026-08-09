package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogTextNormalizationWarning
import com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import com.example.merchandisecontrolsplitview.data.ImportRowSource
import com.example.merchandisecontrolsplitview.data.InventoryCatalogFetchBundle
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.analyzeFullDbImportStreaming
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseHubTab
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ImportFlowState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogTextRealFlowsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun databaseScreenImportActionReachesProductionCallback() {
        var importClicked = false
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface {
                    DatabaseRootHeader(
                        selectedTab = DatabaseHubTab.PRODUCTS,
                        onTabSelected = {},
                        onImportClick = { importClicked = true },
                        onExportClick = {},
                        exportEnabled = true
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.import_file))
            .performClick()
        assertTrue(importClicked)
    }

    @Test
    fun manualCatalogDialogAndRepositoryReachCanonicalBoundary() {
        var confirmedName: String? = null
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                CatalogNameDialog(
                    title = "Edit supplier",
                    fieldLabel = "Supplier",
                    confirmLabel = "Save",
                    initialValue = "  Supplier\nOne  ",
                    onConfirm = { confirmedName = it },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("Save").performClick()
        assertEquals("Supplier\nOne", confirmedName)

        withRepository { repository ->
            val supplier = repository.addSupplier(checkNotNull(confirmedName))
            assertEquals("Supplier One", supplier?.name)
        }
    }

    @Test
    fun manualEntryDialogBlocksInvalidNumbersAndPreservesBlankFallbackSemantics() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = DefaultInventoryRepository(db)
        val application = context.applicationContext as MerchandiseControlApplication
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(DatabaseViewModel::class.java) ->
                    DatabaseViewModel(application, repository) as T
                modelClass.isAssignableFrom(ExcelViewModel::class.java) ->
                    ExcelViewModel(application, repository) as T
                else -> error("Unsupported test ViewModel: ${modelClass.name}")
            }
        }
        val viewModelProvider = ViewModelProvider(viewModelStoreOwner, viewModelFactory)
        val databaseViewModel = viewModelProvider[DatabaseViewModel::class.java]
        val excelViewModel = viewModelProvider[ExcelViewModel::class.java]
        excelViewModel.excelData.add(
            listOf(
                "barcode",
                "productName",
                "purchasePrice",
                "retailPrice",
                "quantity",
                "category",
            )
        )
        val showDialog = mutableStateOf(true)

        try {
            composeRule.setContent {
                MerchandiseControlTheme(darkTheme = false) {
                    if (showDialog.value) {
                        ManualEntryDialog(
                            viewModel = excelViewModel,
                            databaseViewModel = databaseViewModel,
                            rowIndexToEdit = null,
                            entryUid = 0L,
                            initialBarcode = "MANUAL-ENTRY-001",
                            productToPrefill = null,
                            onDismiss = { showDialog.value = false },
                            onScanNext = {},
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("task141.manual.retail-price")
                .performTextReplacement("100")
            composeRule.onNodeWithTag("task141.manual.purchase-price")
                .performTextReplacement("not-a-price")
            composeRule.onNodeWithTag("task141.manual.quantity")
                .performTextReplacement("-1")

            composeRule.onNodeWithText(context.getString(R.string.error_invalid_purchase_price))
                .assertExists()
            composeRule.onNodeWithText(context.getString(R.string.error_negative_quantity))
                .assertExists()
            composeRule.onNodeWithText(context.getString(R.string.confirm))
                .assertIsNotEnabled()
            assertEquals(1, excelViewModel.excelData.size)

            composeRule.onNodeWithTag("task141.manual.purchase-price")
                .performTextReplacement("")
            composeRule.onNodeWithTag("task141.manual.quantity")
                .performTextReplacement("")
            composeRule.onNodeWithText(context.getString(R.string.confirm))
                .assertIsEnabled()
                .performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                excelViewModel.excelData.size == 2
            }
            val persistedRow = excelViewModel.excelData[1]
            assertEquals("MANUAL-ENTRY-001", persistedRow[0])
            assertEquals("50", persistedRow[2])
            assertEquals("100", persistedRow[3])
            assertEquals("", persistedRow[4])
            assertFalse(persistedRow.contains("not-a-price"))
            assertFalse(persistedRow.contains("-1"))
        } finally {
            composeRule.runOnIdle { showDialog.value = false }
            composeRule.waitForIdle()
            viewModelStoreOwner.viewModelStore.clear()
            db.close()
        }
    }

    @Test
    fun fullSupplierExcelAnalysisUsesTypedWarningsAndRedactedErrorsOnDevice() {
        val workbook = createFullSupplierWorkbook()

        withRepository { repository ->
            val result = analyzeFullDbImportStreaming(
                context = context,
                uri = Uri.fromFile(workbook),
                currentDbProducts = emptyList(),
                repository = repository
            )
            val analysis = result.analysis.analysis

            assertEquals(setOf("Supplier One"), result.pendingSupplierNames)
            assertEquals(2, result.supplierRowCount)
            assertEquals(
                CatalogTextField.SUPPLIER_NAME,
                analysis.textNormalizationWarnings.single().fields.single()
            )
            assertEquals(
                ImportRowSource.SUPPLIERS,
                analysis.textNormalizationWarnings.single().source
            )
            val error = analysis.errors.single()
            assertEquals(ImportRowSource.SUPPLIERS, error.source)
            assertEquals(setOf("supplier"), error.redactedFields)
            assertFalse(error.rowContent.values.any { it.contains('\u200B') })
        }
    }

    @Test
    fun manualProductSaveAndCatalogPushSendCanonicalTextOnDevice() {
        withRepository { repository ->
            repository.addProduct(
                Product(
                    barcode = " DEVICE-SYNC-001 ",
                    itemNumber = " DEVICE-ITEM-001 ",
                    productName = "  Café\nCasa  ",
                    retailPrice = 20.0
                )
            )
            val remote = RecordingCatalogRemote()

            val summary = repository.pushDirtyCatalogDeltaToRemote(
                remote = remote,
                priceRemote = DisabledPriceRemote(),
                ownerUserId = "00000000-0000-4000-8000-000000000140",
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()

            assertEquals(1, summary.pushedProducts)
            val payload = remote.products.single()
            assertEquals("DEVICE-SYNC-001", payload.barcode)
            assertEquals("DEVICE-ITEM-001", payload.itemNumber)
            assertEquals("Café Casa", payload.productName)
        }
    }

    @Test
    fun productionImportAnalysisDatabaseAndManualEditorReachObservableBoundaries() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = DefaultInventoryRepository(db)
        val application = context.applicationContext as MerchandiseControlApplication
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(DatabaseViewModel::class.java) ->
                    DatabaseViewModel(application, repository) as T
                modelClass.isAssignableFrom(ExcelViewModel::class.java) ->
                    ExcelViewModel(application, repository) as T
                else -> error("Unsupported test ViewModel: ${modelClass.name}")
            }
        }
        val viewModelProvider = ViewModelProvider(viewModelStoreOwner, viewModelFactory)
        val databaseViewModel = viewModelProvider[DatabaseViewModel::class.java]
        val excelViewModel = viewModelProvider[ExcelViewModel::class.java]
        val existingBarcode = "DEVICE-EDITOR-001"
        runBlocking {
            repository.addProduct(
                Product(
                    barcode = existingBarcode,
                    itemNumber = "DEVICE-ITEM-EDITOR",
                    productName = "Device Existing",
                    retailPrice = 20.0
                )
            )
        }

        val visibleScreen = mutableStateOf(ProductionScreen.IMPORT_ANALYSIS)
        var confirmedPreviewId: Long? = null
        var confirmedProducts: List<Product> = emptyList()
        val previewProduct = Product(
            barcode = "DEVICE-PREVIEW-001",
            itemNumber = "DEVICE-PREVIEW-ITEM",
            productName = "Preview Product",
            retailPrice = 12.0
        )
        val previewAnalysis = ImportAnalysis(
            newProducts = listOf(previewProduct),
            updatedProducts = emptyList(),
            errors = emptyList(),
            warnings = emptyList(),
            textNormalizationWarnings = listOf(
                CatalogTextNormalizationWarning(
                    rowNumber = 2,
                    fields = setOf(CatalogTextField.PRODUCT_NAME)
                )
            )
        )

        try {
            composeRule.setContent {
                MerchandiseControlTheme(darkTheme = false) {
                    Surface {
                        when (visibleScreen.value) {
                            ProductionScreen.IMPORT_ANALYSIS -> ImportAnalysisScreen(
                                excelViewModel = excelViewModel,
                                databaseViewModel = databaseViewModel,
                                importAnalysis = previewAnalysis,
                                importFlowState = ImportFlowState.PreviewReady(previewId = 140L),
                                onConfirm = { previewId, products, _ ->
                                    confirmedPreviewId = previewId
                                    confirmedProducts = products
                                },
                                onCorrectRows = {},
                                onClose = {}
                            )
                            ProductionScreen.DATABASE -> DatabaseScreen(
                                viewModel = databaseViewModel
                            )
                            ProductionScreen.NONE -> Unit
                        }
                    }
                }
            }

            composeRule.onNodeWithText(context.getString(R.string.import_analysis_title))
                .assertExists()
            composeRule.onNodeWithText(context.getString(R.string.confirm_import))
                .performClick()
            assertEquals(140L, confirmedPreviewId)
            assertEquals(previewProduct, confirmedProducts.single())

            composeRule.runOnIdle {
                visibleScreen.value = ProductionScreen.DATABASE
            }
            composeRule.onNodeWithContentDescription(context.getString(R.string.import_file))
                .assertExists()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Device Existing")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithText("Device Existing").performClick()
            composeRule.onNodeWithText(context.getString(R.string.product_image_title))
                .assertExists()
            composeRule.onNodeWithTag("task141.edit.purchase-price")
                .performTextReplacement("not-a-price")
            composeRule.onNodeWithTag("task141.edit.stock-quantity")
                .performTextReplacement("1..2")
            composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.error_invalid_purchase_price))
                .assertExists()
            composeRule.onNodeWithText(context.getString(R.string.error_invalid_quantity))
                .assertExists()
            composeRule.onNodeWithTag("task141.edit.stock-quantity").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    context.getString(R.string.error_invalid_quantity),
                )
            )
            val evidenceRoot = requireNotNull(context.getExternalFilesDir("task141-evidence"))
            File(evidenceRoot, "task141-edit-invalid.png").outputStream().use { output ->
                composeRule.onNodeWithTag("task141.edit.dialog-root")
                    .captureToImage().asAndroidBitmap().compress(
                    android.graphics.Bitmap.CompressFormat.PNG,
                    100,
                    output,
                )
            }

            composeRule.onNodeWithTag("task141.edit.purchase-price")
                .performTextReplacement("10")
            composeRule.onNodeWithTag("task141.edit.stock-quantity")
                .performTextReplacement("2")
            composeRule.onNode(
                hasSetTextAction() and hasText("Device Existing")
            ).performTextReplacement("  Café\nCasa  ")
            composeRule.onNodeWithText(context.getString(R.string.save)).performClick()

            var persistedProduct: Product? = null
            composeRule.waitUntil(timeoutMillis = 10_000) {
                persistedProduct = runBlocking {
                    repository.findProductByBarcode(existingBarcode)
                }
                persistedProduct?.productName == "Café Casa"
            }
            assertEquals("Café Casa", persistedProduct?.productName)
            assertEquals("DEVICE-ITEM-EDITOR", persistedProduct?.itemNumber)
            assertEquals(10.0, persistedProduct?.purchasePrice ?: Double.NaN, 0.0001)
            assertEquals(2.0, persistedProduct?.stockQuantity ?: Double.NaN, 0.0001)
        } finally {
            composeRule.runOnIdle {
                visibleScreen.value = ProductionScreen.NONE
            }
            composeRule.waitForIdle()
            viewModelStoreOwner.viewModelStore.clear()
            db.close()
        }
    }

    private fun withRepository(block: suspend (DefaultInventoryRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                block(DefaultInventoryRepository(db))
            }
        } finally {
            db.close()
        }
    }

    private fun createFullSupplierWorkbook(): File {
        val file = File.createTempFile("catalog-text-device", ".xlsx", context.cacheDir)
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("Products").apply {
                createRow(0).apply {
                    createCell(0).setCellValue("Barcode")
                    createCell(1).setCellValue("Product Name")
                    createCell(2).setCellValue("Retail Price")
                }
                createRow(1).apply {
                    createCell(0).setCellValue("DEVICE-XLSX-001")
                    createCell(1).setCellValue("Device Product")
                    createCell(2).setCellValue("12")
                }
            }
            workbook.createSheet("Suppliers").apply {
                createRow(0).createCell(0).setCellValue("Name")
                createRow(1).createCell(0).setCellValue("  Supplier\nOne  ")
                createRow(2).createCell(0).setCellValue("Unsafe\u200BSupplier")
            }
            file.outputStream().use(workbook::write)
        }
        return file
    }
}

private enum class ProductionScreen {
    IMPORT_ANALYSIS,
    DATABASE,
    NONE
}

private class RecordingCatalogRemote : CatalogRemoteDataSource {
    val products = mutableListOf<InventoryProductRow>()
    override val isConfigured: Boolean = true

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>) = Result.success(Unit)
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>) = Result.success(Unit)
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        products += rows
        return Result.success(Unit)
    }
    override suspend fun fetchCatalog() = Result.success(
        InventoryCatalogFetchBundle(
            suppliers = emptyList(),
            categories = emptyList(),
            products = emptyList()
        )
    )
    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ) = Result.success(
        InventoryCatalogFetchBundle(
            suppliers = emptyList(),
            categories = emptyList(),
            products = emptyList(),
            isCompleteSnapshot = false
        )
    )
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch) = Result.success(Unit)
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch) = Result.success(Unit)
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch) = Result.success(Unit)
}

private class DisabledPriceRemote : ProductPriceRemoteDataSource {
    override val isConfigured: Boolean = false
    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>) = Result.success(Unit)
    override suspend fun fetchProductPrices() = Result.success(emptyList<InventoryProductPriceRow>())
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>) =
        Result.success(emptyList<InventoryProductPriceRow>())
}

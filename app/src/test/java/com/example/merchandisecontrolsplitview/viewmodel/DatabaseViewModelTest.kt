package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Looper
import app.cash.turbine.test
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
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
import com.example.merchandisecontrolsplitview.data.SelectedShop
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.ShopContextRepository
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.data.SupabaseAuthManager
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeChangedException
import com.example.merchandisecontrolsplitview.productimage.ProductImageBatchItem
import com.example.merchandisecontrolsplitview.productimage.PreparedProductImage
import com.example.merchandisecontrolsplitview.productimage.PreparedProductImageVariant
import com.example.merchandisecontrolsplitview.productimage.ProductImageException
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadRequest
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadResult
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadSource
import com.example.merchandisecontrolsplitview.productimage.ProductImageMetadata
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationPhase
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationResult
import com.example.merchandisecontrolsplitview.productimage.ProductImageService
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import com.example.merchandisecontrolsplitview.productimage.SharedPreferencesPendingStagedProductImageStore
import com.example.merchandisecontrolsplitview.testutil.createMalformedLegacyObjWorkbookFile
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import com.example.merchandisecontrolsplitview.testutil.createStrictOoXmlWorkbookFile
import com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOrigin
import com.example.merchandisecontrolsplitview.util.DatabaseExportConstants
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.CatalogTextPolicy
import com.example.merchandisecontrolsplitview.util.CatalogTextValidationException
import com.example.merchandisecontrolsplitview.util.catalogTextErrorMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
    private lateinit var remoteAppliedProductIds: MutableSharedFlow<Set<Long>>

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        repository = mockk(relaxed = true)
        remoteAppliedProductIds = MutableSharedFlow(extraBufferCapacity = 16)

        every { repository.getProductsWithDetailsPaged(any()) } returns mockk(relaxed = true)
        every { repository.remoteAppliedProductIds } returns remoteAppliedProductIds
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
        coEvery { repository.getProductDetailsById(any()) } returns null

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
    fun `createCatalogEntry catalog text rejection emits localized reason`() = runTest {
        val rejection = CatalogTextPolicy.FieldRejection(
            field = CatalogTextField.CATEGORY_NAME,
            reason = CatalogTextPolicy.RejectionReason.PROHIBITED_ZERO_WIDTH
        )
        coEvery {
            repository.createCatalogEntry(CatalogEntityKind.CATEGORY, any())
        } throws CatalogTextValidationException(rejection)

        val result = viewModel.createCatalogEntry(
            CatalogEntityKind.CATEGORY,
            "invalid hidden text"
        )

        assertNull(result)
        assertEquals(
            UiState.Error(app.catalogTextErrorMessage(rejection)),
            viewModel.uiState.value
        )
    }

    @Test
    fun `addProduct success emits success state`() = runTest {
        val product = sampleProduct(barcode = "12345678")
        coEvery { repository.addProductAndReturnId(product) } returns 41L

        viewModel.addProduct(product)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.addProductAndReturnId(product) }
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
        coEvery { repository.addProductAndReturnId(product) } throws
            SQLiteConstraintException("duplicate")

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
    fun `updateProduct success stores fresh product details override`() = runTest {
        val product = sampleProduct(id = 9L, barcode = "22223333")
        val freshDetails = sampleProductDetails(
            product = product.copy(productName = "Updated", retailPrice = 9.0),
            lastRetail = 9.0,
            prevRetail = 4.0
        )
        coEvery { repository.updateProduct(product) } just runs
        coEvery { repository.getProductDetailsById(product.id) } returns freshDetails

        viewModel.updateProduct(product)
        advanceUntilIdle()

        assertEquals(freshDetails, viewModel.productDetailsOverrides.value[product.id])
        coVerify(exactly = 1) { repository.getProductDetailsById(product.id) }
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
    fun `updateProduct failure does not store product details override`() = runTest {
        val product = sampleProduct(id = 9L, barcode = "22223333")
        coEvery { repository.updateProduct(product) } throws IllegalStateException("db unavailable")

        viewModel.updateProduct(product)
        advanceUntilIdle()

        assertFalse(viewModel.productDetailsOverrides.value.containsKey(product.id))
        coVerify(exactly = 0) { repository.getProductDetailsById(product.id) }
    }

    @Test
    fun `editor save failure is recoverable and retry succeeds without an implicit second write`() = runTest {
        val product = sampleProduct(id = 9L, barcode = "22223333", productName = "Draft kept")
        var attempts = 0
        coEvery { repository.updateProduct(product) } coAnswers {
            attempts += 1
            if (attempts == 1) {
                throw IllegalStateException("db unavailable")
            }
        }

        val failed = viewModel.saveProductFromEditor(product)

        assertEquals(
            ProductEditorSaveResult.Failed(app.getString(R.string.error_product_updated)),
            failed
        )
        assertEquals(UiState.Idle, viewModel.uiState.value)
        assertEquals(1, attempts)

        val recovered = viewModel.saveProductFromEditor(product)

        assertEquals(ProductEditorSaveResult.Saved(product.id), recovered)
        assertEquals(
            UiState.Success(app.getString(R.string.success_product_updated)),
            viewModel.uiState.value
        )
        assertEquals(2, attempts)
        coVerify(exactly = 2) { repository.updateProduct(product) }
    }

    @Test
    fun `editor repeated retry performs exactly one repository attempt per explicit save`() = runTest {
        val product = sampleProduct(id = 10L, barcode = "33334444", productName = "Retry draft")
        var attempts = 0
        coEvery { repository.updateProduct(product) } coAnswers {
            attempts += 1
            if (attempts < 3) {
                throw IllegalStateException("temporary failure $attempts")
            }
        }

        assertTrue(viewModel.saveProductFromEditor(product) is ProductEditorSaveResult.Failed)
        assertTrue(viewModel.saveProductFromEditor(product) is ProductEditorSaveResult.Failed)
        assertEquals(ProductEditorSaveResult.Saved(product.id), viewModel.saveProductFromEditor(product))

        assertEquals(3, attempts)
        coVerify(exactly = 3) { repository.updateProduct(product) }
    }

    @Test
    fun `editor treats a post commit details refresh failure as saved without retrying the write`() = runTest {
        val product = sampleProduct(id = 11L, barcode = "44445555", productName = "Committed")
        coEvery { repository.updateProduct(product) } returns Unit
        coEvery { repository.getProductDetailsById(product.id) } throws
            IllegalStateException("read-through unavailable")

        val result = viewModel.saveProductFromEditor(product)

        assertEquals(ProductEditorSaveResult.Saved(product.id), result)
        assertEquals(
            UiState.Success(app.getString(R.string.success_product_updated)),
            viewModel.uiState.value
        )
        coVerify(exactly = 1) { repository.updateProduct(product) }
        coVerify(exactly = 1) { repository.getProductDetailsById(product.id) }
    }

    @Test
    fun `editor save job and draft target survive UI recreation with one write`() = runTest {
        val product = sampleProduct(id = 12L, barcode = "55556666", productName = "Lifecycle draft")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        viewModel.openProductEditor(product)

        viewModel.startProductEditorSave(product) {
            writes += 1
            started.complete(Unit)
            release.await()
            ProductEditorSaveResult.Saved(product.id)
        }
        runCurrent()
        started.await()

        assertEquals(product, viewModel.productEditorTarget.value)
        assertEquals(ProductEditorOperationState.Saving, viewModel.productEditorOperationState.value)
        viewModel.startProductEditorSave(product) {
            writes += 1
            ProductEditorSaveResult.Saved(product.id)
        }
        runCurrent()
        assertEquals(1, writes)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProductEditorOperationState.Saved, viewModel.productEditorOperationState.value)
        assertEquals(product, viewModel.productEditorTarget.value)
        assertEquals(1, writes)

        viewModel.dismissProductEditor()
        assertNull(viewModel.productEditorTarget.value)
    }

    @Test
    fun `scanner lookup reports repository failure and discards stale scope result`() = runTest {
        val barcode = "780000000042"
        coEvery { repository.findProductByBarcode(barcode) } throws
            IllegalStateException("database unavailable")

        assertEquals(
            ScannedBarcodeLookupResult.Failed,
            viewModel.lookupScannedBarcode(barcode, viewModel.currentProductImageScopeEpoch())
        )

        val product = sampleProduct(id = 42L, barcode = barcode)
        coEvery { repository.findProductByBarcode(barcode) } returns product
        assertEquals(
            ScannedBarcodeLookupResult.StaleScope,
            viewModel.lookupScannedBarcode(
                barcode,
                viewModel.currentProductImageScopeEpoch() - 1L
            )
        )
    }

    @Test
    fun `new product staged image survives save failure and transfers once after retry`() = runTest {
        val prepared = PreparedProductImage(
            main = PreparedProductImageVariant(
                bytes = byteArrayOf(1, 2, 3),
                metadata = ProductImageMetadata(3, 1, sha256 = "a".repeat(64), width = 1)
            ),
            thumb = PreparedProductImageVariant(
                bytes = byteArrayOf(4, 5),
                metadata = ProductImageMetadata(2, 1, sha256 = "b".repeat(64), width = 1)
            )
        )
        val imageService = mockk<ProductImageService>(relaxed = true)
        val imageViewModel = DatabaseViewModel(
            app = stableScopedImageApplication("test-account", "test-shop"),
            repository = repository,
            productImageService = imageService,
            stagedImagePreparer = { _, _ -> prepared },
            stagedImageWriter = { _, _ -> Uri.parse("file:///tmp/staged-product-image.jpg") },
            canStageProductImages = { true }
        )
        val draft = sampleProduct(id = 0L, barcode = "STAGED-IMAGE-1")
        val persisted = draft.copy(id = 101L)
        coEvery { repository.findProductByBarcode(draft.barcode) } returns persisted
        coEvery { repository.hasSyncedProductRemoteRef(persisted.id) } returns false
        imageViewModel.openProductEditor(draft)
        imageViewModel.stageNewProductImage(Uri.EMPTY)
        advanceUntilIdle()
        assertTrue(imageViewModel.stagedProductImageState.value is StagedProductImageState.Ready)

        imageViewModel.startProductEditorSave(draft) {
            ProductEditorSaveResult.Failed("retry")
        }
        advanceUntilIdle()
        assertEquals(
            ProductEditorOperationState.Failed("retry"),
            imageViewModel.productEditorOperationState.value
        )
        assertTrue(imageViewModel.stagedProductImageState.value is StagedProductImageState.Ready)

        imageViewModel.startProductEditorSave(draft) { ProductEditorSaveResult.Saved(persisted.id) }
        advanceUntilIdle()
        assertEquals(ProductEditorOperationState.Saved, imageViewModel.productEditorOperationState.value)
        assertEquals(StagedProductImageState.Empty, imageViewModel.stagedProductImageState.value)
        assertTrue(imageViewModel.hasPendingStagedProductImage(persisted.id))
        coVerify(exactly = 0) { imageService.upload(any(), any(), any(), any()) }

        coEvery { repository.hasSyncedProductRemoteRef(persisted.id) } returns true
        coEvery { imageService.upload(persisted.id, any(), any(), any()) } returns
            ProductImageMutationResult(
                status = "published",
                versionId = "00000000-0000-4000-8000-000000000101",
                imageUpdatedAt = "now"
            )
        coEvery { imageService.loadBatch(any()) } coAnswers {
            firstArg<List<ProductImageLoadRequest>>().map { request ->
                ProductImageBatchItem(
                    request = request,
                    result = ProductImageLoadResult.Ready(
                        bytes = byteArrayOf(9),
                        source = ProductImageLoadSource.NETWORK,
                        versionId = requireNotNull(request.expectedVersionId)
                    )
                )
            }
        }
        remoteAppliedProductIds.emit(setOf(persisted.id))
        advanceUntilIdle()
        remoteAppliedProductIds.emit(setOf(persisted.id))
        advanceUntilIdle()

        assertFalse(imageViewModel.hasPendingStagedProductImage(persisted.id))
        coVerify(exactly = 1) { imageService.upload(persisted.id, any(), any(), any()) }
        coVerify(exactly = 0) { repository.findProductByBarcode(draft.barcode) }
    }

    @Test
    fun `post commit remote lookup failure keeps staged image durably pending`() = runTest {
        val store = SharedPreferencesPendingStagedProductImageStore(app)
        store.clear().forEach { uri -> uri.path?.let(::File)?.delete() }
        val prepared = PreparedProductImage(
            main = PreparedProductImageVariant(
                bytes = byteArrayOf(21, 22, 23),
                metadata = ProductImageMetadata(3, 1, sha256 = "c".repeat(64), width = 1)
            ),
            thumb = PreparedProductImageVariant(
                bytes = byteArrayOf(24, 25),
                metadata = ProductImageMetadata(2, 1, sha256 = "d".repeat(64), width = 1)
            )
        )
        val imageService = mockk<ProductImageService>(relaxed = true)
        val draft = sampleProduct(id = 0L, barcode = "STAGED-DURABLE-1")
        val persistedId = 102L
        val stagedUri = writeStagedProductImage(app, prepared.main.bytes)
        coEvery { repository.hasSyncedProductRemoteRef(persistedId) } throws
            IllegalStateException("remote ref temporarily unavailable")
        val imageViewModel = DatabaseViewModel(
            app = stableScopedImageApplication("test-account", "test-shop"),
            repository = repository,
            productImageService = imageService,
            stagedImagePreparer = { _, _ -> prepared },
            stagedImageWriter = { _, _ -> stagedUri },
            pendingStagedImageStore = store,
            canStageProductImages = { true }
        )
        imageViewModel.openProductEditor(draft)
        imageViewModel.stageNewProductImage(Uri.EMPTY)
        advanceUntilIdle()
        assertTrue(imageViewModel.stagedProductImageState.value is StagedProductImageState.Ready)

        imageViewModel.startProductEditorSave(draft) {
            ProductEditorSaveResult.Saved(persistedId)
        }
        runCurrent()

        assertEquals(ProductEditorOperationState.Saved, imageViewModel.productEditorOperationState.value)
        assertEquals(persistedId, store.records().single().productId)
        coVerify(exactly = 0) { imageService.upload(any(), any(), any(), any()) }
        assertTrue(imageViewModel.hasPendingStagedProductImage(persistedId))
        assertEquals(StagedProductImageState.Empty, imageViewModel.stagedProductImageState.value)
        coVerify(exactly = 0) { repository.findProductByBarcode(draft.barcode) }
        imageViewModel.discardPendingStagedProductImage(persistedId)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `new view model recovers durable staged image and uploads exactly once`() = runTest {
        val store = SharedPreferencesPendingStagedProductImageStore(app)
        store.clear().forEach { uri -> uri.path?.let(::File)?.delete() }
        val productId = 103L
        val barcode = "STAGED-RECOVERY-1"
        val stagedUri = writeStagedProductImage(app, byteArrayOf(31, 32, 33))
        assertTrue(store.prepare(stagedUri, barcode, "test-account", "test-shop"))
        assertTrue(store.bind(stagedUri, productId))
        coEvery { repository.hasSyncedProductRemoteRef(productId) } returns true
        val imageService = mockk<ProductImageService>(relaxed = true)
        coEvery { imageService.upload(productId, any(), any(), any()) } returns
            ProductImageMutationResult(
                status = "published",
                versionId = "00000000-0000-4000-8000-000000000103",
                imageUpdatedAt = "now"
            )
        coEvery { imageService.loadBatch(any()) } coAnswers {
            firstArg<List<ProductImageLoadRequest>>().map { request ->
                ProductImageBatchItem(
                    request = request,
                    result = ProductImageLoadResult.Ready(
                        bytes = byteArrayOf(34),
                        source = ProductImageLoadSource.NETWORK,
                        versionId = requireNotNull(request.expectedVersionId)
                    )
                )
            }
        }

        val recoveredViewModel = DatabaseViewModel(
            app = stableScopedImageApplication("test-account", "test-shop"),
            repository = repository,
            productImageService = imageService,
            pendingStagedImageStore = store
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { imageService.upload(productId, any(), any(), any()) }
        assertFalse(recoveredViewModel.hasPendingStagedProductImage(productId))
        assertTrue(store.records().isEmpty())
        assertFalse(File(requireNotNull(stagedUri.path)).exists())
    }

    @Test
    fun `auth bootstrap and same scope loading preserve durable staged image until upload commits`() =
        runTest {
            val store = SharedPreferencesPendingStagedProductImageStore(app)
            store.clear().forEach { uri -> uri.path?.let(::File)?.delete() }
            val accountId = "bootstrap-account"
            val shopId = "bootstrap-shop"
            val productId = 104L
            val barcode = "STAGED-BOOTSTRAP-1"
            val stagedUri = writeStagedProductImage(app, byteArrayOf(41, 42, 43))
            assertTrue(store.prepare(stagedUri, barcode, accountId, shopId))
            assertTrue(store.bind(stagedUri, productId))

            val authState = MutableStateFlow<AuthState>(AuthState.Checking)
            val shopState = MutableStateFlow(ShopContext.legacy())
            val uploadEntered = CompletableDeferred<Unit>()
            val releaseUpload = CompletableDeferred<Unit>()
            val imageService = mockk<ProductImageService>(relaxed = true)
            coEvery { repository.hasSyncedProductRemoteRef(productId) } returns true
            coEvery { imageService.upload(productId, any(), any(), any()) } coAnswers {
                uploadEntered.complete(Unit)
                releaseUpload.await()
                ProductImageMutationResult(
                    status = "published",
                    versionId = "00000000-0000-4000-8000-000000000104",
                    imageUpdatedAt = "now"
                )
            }
            coEvery { imageService.loadBatch(any()) } coAnswers {
                firstArg<List<ProductImageLoadRequest>>().map { request ->
                    ProductImageBatchItem(
                        request = request,
                        result = ProductImageLoadResult.Ready(
                            bytes = byteArrayOf(44),
                            source = ProductImageLoadSource.NETWORK,
                            versionId = requireNotNull(request.expectedVersionId)
                        )
                    )
                }
            }

            val recoveredViewModel = DatabaseViewModel(
                app = scopedImageApplication(authState, shopState),
                repository = repository,
                productImageService = imageService,
                pendingStagedImageStore = store
            )
            runCurrent()

            assertEquals(1, store.records().size)
            assertTrue(File(requireNotNull(stagedUri.path)).isFile)

            authState.value = AuthState.SignedIn(accountId, null)
            shopState.value = shopContext(accountId, shopId).copy(
                isLoading = true,
                syncAllowed = false
            )
            runCurrent()
            assertEquals(1, store.records().size)
            assertTrue(File(requireNotNull(stagedUri.path)).isFile)

            shopState.value = shopContext(accountId, shopId)
            runCurrent()
            assertTrue(uploadEntered.isCompleted)

            shopState.value = shopContext(accountId, shopId).copy(
                isLoading = true,
                syncAllowed = false
            )
            runCurrent()
            assertEquals(1, store.records().size)
            assertTrue(File(requireNotNull(stagedUri.path)).isFile)

            shopState.value = shopContext(accountId, shopId)
            runCurrent()
            coVerify(exactly = 1) { imageService.upload(productId, any(), any(), any()) }

            releaseUpload.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { imageService.upload(productId, any(), any(), any()) }
            assertFalse(recoveredViewModel.hasPendingStagedProductImage(productId))
            assertTrue(store.records().isEmpty())
            assertFalse(File(requireNotNull(stagedUri.path)).exists())
        }

    @Test
    fun `staged image writer persists normalized bytes in durable application storage`() = runTest {
        val bytes = byteArrayOf(7, 8, 9, 10)

        val uri = writeStagedProductImage(app, bytes)
        val stagedFile = File(requireNotNull(uri.path))

        assertTrue(stagedFile.isFile)
        assertArrayEquals(bytes, stagedFile.readBytes())
        assertTrue(stagedFile.delete())
    }

    @Test
    fun `product search cancels obsolete debounce windows and clear is immediate`() = runTest {
        viewModel.appliedProductFilter.test {
            assertNull(awaitItem())

            viewModel.setFilter("a")
            runCurrent()
            advanceTimeBy(80)
            viewModel.setFilter("ab")
            runCurrent()
            advanceTimeBy(80)
            viewModel.setFilter("abc")
            runCurrent()

            advanceTimeBy(249)
            expectNoEvents()
            advanceTimeBy(1)
            runCurrent()
            assertEquals("abc", awaitItem())

            viewModel.setFilter("")
            runCurrent()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateProduct sequential saves leave latest product details override`() = runTest {
        val first = sampleProduct(id = 9L, barcode = "22223333", productName = "First")
        val second = sampleProduct(id = 9L, barcode = "22223333", productName = "Second")
        val firstDetails = sampleProductDetails(first.copy(retailPrice = 8.0), lastRetail = 8.0)
        val secondDetails = sampleProductDetails(second.copy(retailPrice = 12.0), lastRetail = 12.0)
        coEvery { repository.updateProduct(first) } just runs
        coEvery { repository.updateProduct(second) } just runs
        coEvery { repository.getProductDetailsById(9L) } returnsMany listOf(firstDetails, secondDetails)

        viewModel.updateProduct(first)
        advanceUntilIdle()
        viewModel.updateProduct(second)
        advanceUntilIdle()

        assertEquals(secondDetails, viewModel.productDetailsOverrides.value[9L])
    }

    @Test
    fun `remote applied product ids store fresh product details override without global refresh`() = runTest {
        val product = sampleProduct(id = 21L, barcode = "remote-21", productName = "Remote")
        val freshDetails = sampleProductDetails(
            product = product.copy(productName = "Remote Updated", retailPrice = 14.0),
            lastRetail = 14.0,
            prevRetail = 9.0
        )
        coEvery { repository.getProductDetailsById(product.id) } returns freshDetails

        advanceUntilIdle()
        remoteAppliedProductIds.emit(setOf(product.id))
        advanceUntilIdle()

        assertEquals(freshDetails, viewModel.productDetailsOverrides.value[product.id])
        assertEquals(UiState.Idle, viewModel.uiState.value)
        coVerify(exactly = 1) { repository.getProductDetailsById(product.id) }
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
    fun `deleteProduct success removes product details override`() = runTest {
        val product = sampleProduct(id = 12L, barcode = "44445555")
        val freshDetails = sampleProductDetails(product.copy(retailPrice = 10.0), lastRetail = 10.0)
        coEvery { repository.updateProduct(product) } just runs
        coEvery { repository.getProductDetailsById(product.id) } returns freshDetails
        coEvery { repository.deleteProduct(product) } just runs

        viewModel.updateProduct(product)
        advanceUntilIdle()
        assertTrue(viewModel.productDetailsOverrides.value.containsKey(product.id))

        viewModel.deleteProduct(product)
        advanceUntilIdle()

        assertFalse(viewModel.productDetailsOverrides.value.containsKey(product.id))
    }

    @Test
    fun `visible product without image stays absent without image service request`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val key = ProductImageUiKey(51L, ProductImageVariant.THUMB)

        imageViewModel.setProductImageVisible(
            productId = key.productId,
            expectedVersionId = null,
            visible = true
        )
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.ABSENT, imageViewModel.productImageStates.value[key]?.status)
        assertNull(imageViewModel.productImageStates.value[key]?.bytes)
        coVerify(exactly = 0) { imageService.loadBatch(any()) }
    }

    @Test
    fun `image mutation eligibility requires a persisted remote apply`() = runTest {
        coEvery { repository.hasSyncedProductRemoteRef(61L) } returns true
        coEvery { repository.hasSyncedProductRemoteRef(62L) } returns false

        assertFalse(viewModel.hasSyncedProductImageReference(0L))
        assertTrue(viewModel.hasSyncedProductImageReference(61L))
        assertFalse(viewModel.hasSyncedProductImageReference(62L))

        coVerify(exactly = 0) { repository.hasSyncedProductRemoteRef(0L) }
        coVerify(exactly = 1) { repository.hasSyncedProductRemoteRef(61L) }
        coVerify(exactly = 1) { repository.hasSyncedProductRemoteRef(62L) }
    }

    @Test
    fun `visible thumbnail batch is cancelled after its last consumer leaves`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { imageService.loadBatch(any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)

        imageViewModel.setProductImageVisible(63L, "version-63", visible = true)
        advanceTimeBy(17L)
        runCurrent()
        assertTrue(entered.isCompleted)

        imageViewModel.setProductImageVisible(63L, "version-63", visible = false)
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertTrue(imageViewModel.productImageStates.value.isEmpty())
    }

    @Test
    fun `two hundred rows load only composed thumbnails and release offscreen bytes`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val requestedBatches = mutableListOf<List<ProductImageLoadRequest>>()
        coEvery { imageService.loadBatch(any()) } coAnswers {
            val requests = firstArg<List<ProductImageLoadRequest>>()
            requestedBatches += requests
            requests.map { request ->
                ProductImageBatchItem(
                    request = request,
                    result = ProductImageLoadResult.Ready(
                        bytes = byteArrayOf(request.localProductId.toByte()),
                        source = ProductImageLoadSource.NETWORK,
                        versionId = requireNotNull(request.expectedVersionId)
                    )
                )
            }
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)

        (1L..200L).forEach { productId ->
            imageViewModel.setProductImageVisible(productId, "version-$productId", visible = true)
        }
        (1L..188L).forEach { productId ->
            imageViewModel.setProductImageVisible(productId, "version-$productId", visible = false)
        }
        advanceTimeBy(17L)
        advanceUntilIdle()

        assertEquals(1, requestedBatches.size)
        assertEquals((189L..200L).toList(), requestedBatches.single().map { it.localProductId })
        assertEquals(12, imageViewModel.productImageStates.value.size)
        assertTrue(imageViewModel.productImageStates.value.values.all {
            it.status == ProductImageUiStatus.READY && it.bytes != null
        })

        (189L..200L).forEach { productId ->
            imageViewModel.setProductImageVisible(productId, "version-$productId", visible = false)
        }

        assertTrue(imageViewModel.productImageStates.value.isEmpty())
    }

    @Test
    fun `stale main image completion cannot replace latest version`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val firstResult = CompletableDeferred<List<ProductImageBatchItem>>()
        val secondResult = CompletableDeferred<List<ProductImageBatchItem>>()
        coEvery { imageService.loadBatch(any()) } coAnswers {
            val request = firstArg<List<ProductImageLoadRequest>>().single()
            if (request.expectedVersionId == "version-old") {
                withContext(NonCancellable) { firstResult.await() }
            } else {
                secondResult.await()
            }
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val key = ProductImageUiKey(71L, ProductImageVariant.MAIN)
        val oldRequest = ProductImageLoadRequest(key.productId, key.variant, "version-old")
        val newRequest = ProductImageLoadRequest(key.productId, key.variant, "version-new")

        imageViewModel.loadProductImage(key.productId, key.variant, oldRequest.expectedVersionId)
        runCurrent()
        imageViewModel.loadProductImage(key.productId, key.variant, newRequest.expectedVersionId)
        runCurrent()
        secondResult.complete(
            listOf(
                ProductImageBatchItem(
                    newRequest,
                    ProductImageLoadResult.Ready(
                        byteArrayOf(2),
                        ProductImageLoadSource.NETWORK,
                        requireNotNull(newRequest.expectedVersionId)
                    )
                )
            )
        )
        runCurrent()
        firstResult.complete(
            listOf(
                ProductImageBatchItem(
                    oldRequest,
                    ProductImageLoadResult.Ready(
                        byteArrayOf(1),
                        ProductImageLoadSource.NETWORK,
                        requireNotNull(oldRequest.expectedVersionId)
                    )
                )
            )
        )
        advanceUntilIdle()

        val state = requireNotNull(imageViewModel.productImageStates.value[key])
        assertEquals(ProductImageUiStatus.READY, state.status)
        assertEquals("version-new", state.versionId)
        assertTrue(state.bytes!!.contentEquals(byteArrayOf(2)))
    }

    @Test
    fun `detail loads thumbnail before main and ignores duplicate recomposition`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val mainGate = CompletableDeferred<Unit>()
        val requestedVariants = mutableListOf<ProductImageVariant>()
        coEvery { imageService.loadBatch(any()) } coAnswers {
            val request = firstArg<List<ProductImageLoadRequest>>().single()
            requestedVariants += request.variant
            if (request.variant == ProductImageVariant.MAIN) mainGate.await()
            listOf(
                ProductImageBatchItem(
                    request = request,
                    result = ProductImageLoadResult.Ready(
                        bytes = byteArrayOf(
                            if (request.variant == ProductImageVariant.THUMB) 1 else 2
                        ),
                        source = ProductImageLoadSource.NETWORK,
                        versionId = requireNotNull(request.expectedVersionId)
                    )
                )
            )
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val thumbKey = ProductImageUiKey(76L, ProductImageVariant.THUMB)
        val mainKey = ProductImageUiKey(76L, ProductImageVariant.MAIN)

        imageViewModel.loadProductImageProgressively(76L, "version-progressive")
        runCurrent()

        assertEquals(listOf(ProductImageVariant.THUMB, ProductImageVariant.MAIN), requestedVariants)
        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[thumbKey]?.status)
        assertTrue(
            imageViewModel.productImageStates.value[thumbKey]?.bytes?.contentEquals(byteArrayOf(1)) == true
        )
        assertEquals(ProductImageUiStatus.LOADING, imageViewModel.productImageStates.value[mainKey]?.status)

        imageViewModel.loadProductImageProgressively(76L, "version-progressive")
        runCurrent()
        assertEquals(2, requestedVariants.size)

        mainGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[mainKey]?.status)
        assertTrue(
            imageViewModel.productImageStates.value[mainKey]?.bytes?.contentEquals(byteArrayOf(2)) == true
        )
    }

    @Test
    fun `shop switch and logout purge the previous image cache scope`() = runTest {
        val accountId = "account-one"
        val firstShopId = "shop-one"
        val secondShopId = "shop-two"
        val authStates = MutableStateFlow<AuthState>(AuthState.SignedIn(accountId, null))
        val shopStates = MutableStateFlow(shopContext(accountId, firstShopId))
        val authManager = mockk<SupabaseAuthManager>()
        val shopContextRepository = mockk<ShopContextRepository>()
        val scopedApp = mockk<MerchandiseControlApplication>(relaxed = true)
        val imageService = mockk<ProductImageService>(relaxed = true)
        every { authManager.state } returns authStates
        every { shopContextRepository.state } returns shopStates
        every { scopedApp.authManager } returns authManager
        every { scopedApp.shopContextRepository } returns shopContextRepository
        val imageViewModel = DatabaseViewModel(scopedApp, repository, imageService)
        runCurrent()
        imageViewModel.setProductImageVisible(81L, null, visible = true)
        assertFalse(imageViewModel.productImageStates.value.isEmpty())

        shopStates.value = shopContext(accountId, secondShopId)
        runCurrent()

        assertTrue(imageViewModel.productImageStates.value.isEmpty())
        assertEquals(1L, imageViewModel.productImageScopeEpoch.value)
        coVerify(exactly = 1) { imageService.purgeScope(accountId, firstShopId) }

        authStates.value = AuthState.SignedOut
        runCurrent()

        assertEquals(2L, imageViewModel.productImageScopeEpoch.value)
        coVerify(exactly = 1) { imageService.purgeScope(accountId, null) }
    }

    @Test
    fun `139 restored auth preserves resolved scope through transient shop states`() = runTest {
        val accountId = "account-restored"
        val firstShopId = "shop-restored"
        val secondShopId = "shop-next"
        val authStates = MutableStateFlow<AuthState>(AuthState.SignedIn(accountId, null))
        val shopStates = MutableStateFlow(ShopContext.legacy())
        val authManager = mockk<SupabaseAuthManager>()
        val shopContextRepository = mockk<ShopContextRepository>()
        val scopedApp = mockk<MerchandiseControlApplication>(relaxed = true)
        val imageService = mockk<ProductImageService>(relaxed = true)
        every { authManager.state } returns authStates
        every { shopContextRepository.state } returns shopStates
        every { scopedApp.authManager } returns authManager
        every { scopedApp.shopContextRepository } returns shopContextRepository
        val imageViewModel = DatabaseViewModel(scopedApp, repository, imageService)
        runCurrent()

        shopStates.value = ShopContext(
            ownerUserId = accountId,
            linkedShops = emptyList(),
            selectedShop = null,
            isLoading = true,
            syncAllowed = false
        )
        runCurrent()
        shopStates.value = shopContext(accountId, firstShopId)
        runCurrent()

        coVerify(exactly = 0) { imageService.purgeScope(any(), any()) }
        assertEquals(0L, imageViewModel.productImageScopeEpoch.value)

        imageViewModel.setProductImageVisible(81L, null, visible = true)
        assertFalse(imageViewModel.productImageStates.value.isEmpty())

        shopStates.value = shopContext(accountId, firstShopId).copy(
            selectedShop = null,
            errorMessage = "offline",
            syncAllowed = false
        )
        runCurrent()
        coVerify(exactly = 0) { imageService.purgeScope(any(), any()) }
        assertFalse(imageViewModel.productImageStates.value.isEmpty())
        assertEquals(0L, imageViewModel.productImageScopeEpoch.value)

        shopStates.value = shopContext(accountId, secondShopId)
        runCurrent()

        coVerify(exactly = 1) { imageService.purgeScope(accountId, firstShopId) }
        assertEquals(1L, imageViewModel.productImageScopeEpoch.value)
    }

    @Test
    fun `prepared preview is immediate and failed replacement preserves current image`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val uploadGate = CompletableDeferred<Unit>()
        val oldBytes = byteArrayOf(1, 2, 3)
        val newMainBytes = byteArrayOf(4, 5, 6)
        val newThumbBytes = byteArrayOf(7, 8)
        val prepared = PreparedProductImage(
            main = PreparedProductImageVariant(
                bytes = newMainBytes,
                metadata = ProductImageMetadata(3, 1, sha256 = "a".repeat(64), width = 1)
            ),
            thumb = PreparedProductImageVariant(
                bytes = newThumbBytes,
                metadata = ProductImageMetadata(2, 1, sha256 = "b".repeat(64), width = 1)
            )
        )
        coEvery { imageService.loadBatch(any()) } coAnswers {
            val request = firstArg<List<ProductImageLoadRequest>>().single()
            listOf(
                ProductImageBatchItem(
                    request,
                    ProductImageLoadResult.Ready(
                        bytes = oldBytes,
                        source = ProductImageLoadSource.CACHE,
                        versionId = requireNotNull(request.expectedVersionId)
                    )
                )
            )
        }
        coEvery { imageService.upload(92L, any(), any(), any()) } coAnswers {
            arg<(PreparedProductImage) -> Unit>(3)(prepared)
            uploadGate.await()
            throw ProductImageException("image_request_failed")
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val mainKey = ProductImageUiKey(92L, ProductImageVariant.MAIN)
        val thumbKey = ProductImageUiKey(92L, ProductImageVariant.THUMB)

        imageViewModel.loadProductImage(92L, ProductImageVariant.MAIN, "version-old")
        advanceUntilIdle()
        imageViewModel.uploadProductImage(92L, Uri.EMPTY)
        runCurrent()

        val uploadingMain = requireNotNull(imageViewModel.productImageStates.value[mainKey])
        val uploadingThumb = requireNotNull(imageViewModel.productImageStates.value[thumbKey])
        assertEquals(ProductImageUiStatus.UPLOADING, uploadingMain.status)
        assertTrue(uploadingMain.bytes?.contentEquals(oldBytes) == true)
        assertTrue(uploadingMain.pendingPreviewBytes?.contentEquals(newMainBytes) == true)
        assertTrue(uploadingThumb.pendingPreviewBytes?.contentEquals(newThumbBytes) == true)

        uploadGate.complete(Unit)
        advanceUntilIdle()

        val failedMain = requireNotNull(imageViewModel.productImageStates.value[mainKey])
        assertEquals(ProductImageUiStatus.ERROR, failedMain.status)
        assertTrue(failedMain.bytes?.contentEquals(oldBytes) == true)
        assertNull(failedMain.pendingPreviewBytes)
    }

    @Test
    fun `upload progress is exposed and cancel restores previous image state`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val uploadGate = CompletableDeferred<Unit>()
        coEvery { imageService.upload(91L, any(), any(), any()) } coAnswers {
            thirdArg<(ProductImageMutationPhase) -> Unit>()(
                ProductImageMutationPhase.UPLOAD_MAIN
            )
            uploadGate.await()
            error("Cancelled upload must not continue")
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val mainKey = ProductImageUiKey(91L, ProductImageVariant.MAIN)
        val thumbKey = ProductImageUiKey(91L, ProductImageVariant.THUMB)
        imageViewModel.loadProductImage(91L, ProductImageVariant.MAIN, null)

        imageViewModel.uploadProductImage(91L, Uri.EMPTY)
        runCurrent()

        assertEquals(ProductImageUiStatus.UPLOADING, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(
            ProductImageMutationPhase.UPLOAD_MAIN,
            imageViewModel.productImageStates.value[mainKey]?.mutationPhase
        )

        imageViewModel.cancelProductImageOperation(91L)
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.ABSENT, imageViewModel.productImageStates.value[mainKey]?.status)
        assertFalse(imageViewModel.productImageStates.value.containsKey(thumbKey))
        coVerify(exactly = 1) { imageService.upload(91L, Uri.EMPTY, any(), any()) }
    }

    @Test
    fun `scope cancellation during remove restores previous image and releases mutation job`() =
        runTest {
            val imageService = mockk<ProductImageService>(relaxed = true)
            val previousBytes = byteArrayOf(9, 5, 1)
            coEvery { imageService.loadBatch(any()) } coAnswers {
                val request = firstArg<List<ProductImageLoadRequest>>().single()
                listOf(
                    ProductImageBatchItem(
                        request,
                        ProductImageLoadResult.Ready(
                            bytes = previousBytes,
                            source = ProductImageLoadSource.CACHE,
                            versionId = requireNotNull(request.expectedVersionId)
                        )
                    )
                )
            }
            var removeAttempt = 0
            coEvery { imageService.remove(95L) } coAnswers {
                removeAttempt += 1
                if (removeAttempt == 1) {
                    throw Task126BusinessDataScopeChangedException()
                }
                ProductImageMutationResult(
                    status = "removed",
                    versionId = null,
                    imageUpdatedAt = "now"
                )
            }
            val imageViewModel = DatabaseViewModel(app, repository, imageService)
            val mainKey = ProductImageUiKey(95L, ProductImageVariant.MAIN)
            val thumbKey = ProductImageUiKey(95L, ProductImageVariant.THUMB)

            imageViewModel.loadProductImage(95L, ProductImageVariant.MAIN, "version-old")
            advanceUntilIdle()
            imageViewModel.removeProductImage(95L)
            advanceUntilIdle()

            val restored = requireNotNull(imageViewModel.productImageStates.value[mainKey])
            assertEquals(ProductImageUiStatus.READY, restored.status)
            assertTrue(restored.bytes?.contentEquals(previousBytes) == true)
            assertFalse(imageViewModel.productImageStates.value.containsKey(thumbKey))

            imageViewModel.removeProductImage(95L)
            advanceUntilIdle()

            assertEquals(ProductImageUiStatus.ABSENT, imageViewModel.productImageStates.value[mainKey]?.status)
            assertEquals(ProductImageUiStatus.ABSENT, imageViewModel.productImageStates.value[thumbKey]?.status)
            coVerify(exactly = 2) { imageService.remove(95L) }
        }

    @Test
    fun `failed upload and failed read retry can be discarded before a new selection`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val oldMainBytes = byteArrayOf(1, 2, 3)
        val oldThumbBytes = byteArrayOf(4, 5)
        var readsFail = false
        coEvery { imageService.loadBatch(any()) } coAnswers {
            val request = firstArg<List<ProductImageLoadRequest>>().single()
            if (readsFail) {
                listOf(ProductImageBatchItem(request, errorCode = "image_request_failed"))
            } else {
                listOf(
                    ProductImageBatchItem(
                        request,
                        ProductImageLoadResult.Ready(
                            bytes = if (request.variant == ProductImageVariant.MAIN) {
                                oldMainBytes
                            } else {
                                oldThumbBytes
                            },
                            source = ProductImageLoadSource.CACHE,
                            versionId = requireNotNull(request.expectedVersionId)
                        )
                    )
                )
            }
        }
        var uploadAttempt = 0
        coEvery { imageService.upload(93L, any(), any(), any()) } coAnswers {
            uploadAttempt += 1
            if (uploadAttempt == 1) {
                throw ProductImageException("image_request_failed")
            }
            thirdArg<(ProductImageMutationPhase) -> Unit>()(
                ProductImageMutationPhase.UPLOAD_MAIN
            )
            awaitCancellation()
        }
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val mainKey = ProductImageUiKey(93L, ProductImageVariant.MAIN)
        val thumbKey = ProductImageUiKey(93L, ProductImageVariant.THUMB)

        imageViewModel.loadProductImageProgressively(93L, "version-old")
        advanceUntilIdle()
        imageViewModel.uploadProductImage(93L, Uri.EMPTY)
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.ERROR, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(ProductImageUiStatus.ERROR, imageViewModel.productImageStates.value[thumbKey]?.status)

        readsFail = true
        imageViewModel.loadProductImageProgressively(93L, "version-old", force = true)
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.ERROR, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(ProductImageUiStatus.ERROR, imageViewModel.productImageStates.value[thumbKey]?.status)

        imageViewModel.discardFailedProductImageOperation(93L)

        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[thumbKey]?.status)
        assertTrue(imageViewModel.productImageStates.value[mainKey]?.bytes?.contentEquals(oldMainBytes) == true)
        assertTrue(imageViewModel.productImageStates.value[thumbKey]?.bytes?.contentEquals(oldThumbBytes) == true)

        imageViewModel.uploadProductImage(93L, Uri.EMPTY)
        runCurrent()
        assertEquals(ProductImageUiStatus.UPLOADING, imageViewModel.productImageStates.value[mainKey]?.status)

        imageViewModel.cancelProductImageOperation(93L)
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[thumbKey]?.status)
        coVerify(exactly = 2) { imageService.upload(93L, Uri.EMPTY, any(), any()) }
    }

    @Test
    fun `closing after a stuck retry restores current image and permits reopen`() = runTest {
        val imageService = mockk<ProductImageService>(relaxed = true)
        val retryEntered = CompletableDeferred<Unit>()
        var hangReads = false
        coEvery { imageService.loadBatch(any()) } coAnswers {
            val request = firstArg<List<ProductImageLoadRequest>>().single()
            if (hangReads) {
                retryEntered.complete(Unit)
                awaitCancellation()
            }
            listOf(
                ProductImageBatchItem(
                    request,
                    ProductImageLoadResult.Ready(
                        bytes = byteArrayOf(if (request.variant == ProductImageVariant.MAIN) 7 else 8),
                        source = ProductImageLoadSource.CACHE,
                        versionId = requireNotNull(request.expectedVersionId)
                    )
                )
            )
        }
        coEvery { imageService.upload(94L, any(), any(), any()) } throws
            ProductImageException("image_request_failed")
        val imageViewModel = DatabaseViewModel(app, repository, imageService)
        val mainKey = ProductImageUiKey(94L, ProductImageVariant.MAIN)
        val thumbKey = ProductImageUiKey(94L, ProductImageVariant.THUMB)

        imageViewModel.loadProductImageProgressively(94L, "version-old")
        advanceUntilIdle()
        imageViewModel.uploadProductImage(94L, Uri.EMPTY)
        advanceUntilIdle()
        hangReads = true
        imageViewModel.loadProductImageProgressively(94L, "version-old", force = true)
        runCurrent()
        assertTrue(retryEntered.isCompleted)

        imageViewModel.closeProductImageEditor(94L)
        runCurrent()

        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[thumbKey]?.status)

        hangReads = false
        imageViewModel.loadProductImageProgressively(94L, "version-old", force = true)
        advanceUntilIdle()

        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[mainKey]?.status)
        assertEquals(ProductImageUiStatus.READY, imageViewModel.productImageStates.value[thumbKey]?.status)
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
            (viewModel.importAnalysisResult.value != null &&
                viewModel.uiState.value == UiState.Idle) ||
                viewModel.uiState.value is UiState.Error
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
        waitForCondition { viewModel.importFlowState.value is ImportFlowState.Success }

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
        assertEquals(UiState.Idle, viewModel.uiState.value)
        assertEquals(2, viewModel.storefrontImportSummary.value?.internalProductsUpdated)
        assertEquals(0, viewModel.storefrontImportSummary.value?.publicProductsNowDifferent)
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

    private fun sampleProductDetails(
        product: Product,
        lastPurchase: Double? = product.purchasePrice,
        prevPurchase: Double? = null,
        lastRetail: Double? = product.retailPrice,
        prevRetail: Double? = null
    ) = ProductWithDetails(
        product = product,
        supplierName = null,
        categoryName = null,
        lastPurchase = lastPurchase,
        prevPurchase = prevPurchase,
        lastRetail = lastRetail,
        prevRetail = prevRetail
    )

    private fun shopContext(accountId: String, shopId: String) = ShopContext(
        ownerUserId = accountId,
        linkedShops = emptyList(),
        selectedShop = SelectedShop(
            shopId = shopId,
            code = null,
            name = shopId,
            role = "owner",
            status = "active",
            canWrite = true
        )
    )

    private fun stableScopedImageApplication(
        accountId: String,
        shopId: String
    ): MerchandiseControlApplication = scopedImageApplication(
        MutableStateFlow(AuthState.SignedIn(accountId, null)),
        MutableStateFlow(shopContext(accountId, shopId))
    )

    private fun scopedImageApplication(
        authState: MutableStateFlow<AuthState>,
        shopState: MutableStateFlow<ShopContext>
    ): MerchandiseControlApplication {
        val authManager = mockk<SupabaseAuthManager>()
        val shopContextRepository = mockk<ShopContextRepository>()
        val scopedApp = mockk<MerchandiseControlApplication>(relaxed = true)
        every { authManager.state } returns authState
        every { shopContextRepository.state } returns shopState
        every { scopedApp.applicationContext } returns app
        every { scopedApp.authManager } returns authManager
        every { scopedApp.shopContextRepository } returns shopContextRepository
        return scopedApp
    }

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

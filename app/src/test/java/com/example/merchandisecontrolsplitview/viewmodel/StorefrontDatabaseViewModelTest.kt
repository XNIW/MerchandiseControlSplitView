package com.example.merchandisecontrolsplitview.viewmodel

import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.StorefrontAuthoringMutationResponse
import com.example.merchandisecontrolsplitview.data.StorefrontAuthoringReadResponse
import com.example.merchandisecontrolsplitview.data.StorefrontAuthoringRemoteDataSource
import com.example.merchandisecontrolsplitview.data.StorefrontAuthoringSummaryResponse
import com.example.merchandisecontrolsplitview.data.StorefrontEditorDraft
import com.example.merchandisecontrolsplitview.data.StorefrontDraftField
import com.example.merchandisecontrolsplitview.data.StorefrontMutationOperation
import com.example.merchandisecontrolsplitview.data.StorefrontPublication
import com.example.merchandisecontrolsplitview.data.StorefrontPublicationListSummary
import com.example.merchandisecontrolsplitview.data.StorefrontSummaryFilter
import com.example.merchandisecontrolsplitview.productimage.ProductImageService
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorefrontDatabaseViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: MerchandiseControlApplication
    private lateinit var repository: InventoryRepository
    private lateinit var imageService: ProductImageService
    private lateinit var remote: FakeStorefrontRemote
    private var network = true
    private var scope: Pair<String, String>? = ACCOUNT_ID to SHOP_ID

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        repository = mockk(relaxed = true)
        imageService = mockk(relaxed = true)
        remote = FakeStorefrontRemote()
        every { repository.getProductsWithDetailsPaged(any()) } returns mockk(relaxed = true)
        every { repository.remoteAppliedProductIds } returns emptyFlow()
        coEvery { repository.getSyncedProductRemoteIds(any()) } returns mapOf(LOCAL_ID to REMOTE_ID)
    }

    @Test
    fun `offline draft remains local then reconnect verifies version before ACK`() = runTest {
        network = false
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()
        viewModel.updateStorefrontDraft { it.copy(publicName = "Offline", publicPrice = 1_990) }

        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)

        assertTrue(viewModel.storefrontEditorState.value.pendingConnection)
        assertTrue(viewModel.storefrontEditorState.value.serverVersionUnverified)
        assertEquals(0, remote.mutations.size)

        network = true
        remote.readResponse = StorefrontAuthoringReadResponse(ok = true, code = "success")
        remote.mutationResponse = StorefrontAuthoringMutationResponse(
            ok = true,
            code = "success",
            payload = publication(version = 1, name = "Offline")
        )
        viewModel.retryPendingStorefrontDraft()
        advanceUntilIdle()

        assertEquals(listOf(0L), remote.mutations.map { it.expectedVersion })
        assertEquals(1, remote.mutations.map { it.idempotencyKey }.distinct().size)
        assertFalse(viewModel.storefrontEditorState.value.pendingConnection)
        assertFalse(viewModel.storefrontEditorState.value.serverVersionUnverified)
        assertEquals(1L, viewModel.storefrontEditorState.value.publication?.version)
    }

    @Test
    fun `stale version produces conflict and reapply requires fresh expected version`() = runTest {
        remote.readResponse = StorefrontAuthoringReadResponse(
            ok = true,
            code = "success",
            rows = listOf(
                publication(
                    version = 10,
                    name = "Server v10",
                    description = "Description v10",
                    categoryId = CATEGORY_ID,
                    imageId = IMAGE_ID
                )
            )
        )
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()
        viewModel.updateStorefrontDraft { it.copy(publicName = "Local edit") }
        remote.mutationResponse = StorefrontAuthoringMutationResponse(
            ok = false,
            code = "stale_revision",
            server = publication(
                version = 11,
                name = "Server v11",
                source = "ios",
                description = "Description changed on iOS",
                categoryId = OTHER_CATEGORY_ID,
                imageId = OTHER_IMAGE_ID
            )
        )

        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()

        val conflict = viewModel.storefrontEditorState.value.conflict
        assertNotNull(conflict)
        assertEquals("Local edit", conflict?.localDraft?.publicName)
        assertEquals(11L, conflict?.server?.version)
        assertEquals(setOf(StorefrontDraftField.PUBLIC_NAME), conflict?.dirtyFields)
        viewModel.reapplyStorefrontConflict()
        assertEquals("Local edit", viewModel.storefrontEditorState.value.draft.publicName)
        assertEquals(
            "Description changed on iOS",
            viewModel.storefrontEditorState.value.draft.publicDescription
        )
        assertEquals(OTHER_CATEGORY_ID, viewModel.storefrontEditorState.value.draft.storefrontCategoryId)
        assertEquals(OTHER_IMAGE_ID, viewModel.storefrontEditorState.value.draft.publicImageId)
        remote.mutationResponse = StorefrontAuthoringMutationResponse(
            ok = true,
            code = "success",
            payload = publication(version = 12, name = "Local edit")
        )
        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()

        assertEquals(listOf(10L, 11L), remote.mutations.map { it.expectedVersion })
        assertEquals("Description changed on iOS", remote.mutations.last().draft.publicDescription)
        assertEquals(OTHER_CATEGORY_ID, remote.mutations.last().draft.storefrontCategoryId)
        assertEquals(OTHER_IMAGE_ID, remote.mutations.last().draft.publicImageId)
        assertNull(viewModel.storefrontEditorState.value.conflict)
        assertEquals(12L, viewModel.storefrontEditorState.value.publication?.version)
    }

    @Test
    fun `reverted local field is not overlaid on a newer server value`() = runTest {
        remote.readResponse = StorefrontAuthoringReadResponse(
            ok = true,
            code = "success",
            rows = listOf(publication(version = 10, name = "A"))
        )
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()

        viewModel.updateStorefrontDraft { it.copy(publicName = "B") }
        viewModel.updateStorefrontDraft { it.copy(publicName = "A") }
        assertTrue(viewModel.storefrontEditorState.value.dirtyFields.isEmpty())

        remote.mutationResponse = StorefrontAuthoringMutationResponse(
            ok = false,
            code = "stale_revision",
            server = publication(version = 11, name = "C", source = "ios")
        )
        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        viewModel.reapplyStorefrontConflict()

        assertEquals("C", viewModel.storefrontEditorState.value.draft.publicName)
        assertTrue(viewModel.storefrontEditorState.value.dirtyFields.isEmpty())
    }

    @Test
    fun `publish is never queued or acknowledged while offline`() = runTest {
        network = false
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()
        viewModel.updateStorefrontDraft {
            it.copy(publicName = "Public", publicPrice = 1_990, storefrontCategoryId = CATEGORY_ID)
        }

        viewModel.mutateStorefront(StorefrontMutationOperation.PUBLISH)

        assertEquals("network_required", viewModel.storefrontEditorState.value.errorCode)
        assertFalse(viewModel.storefrontEditorState.value.pendingConnection)
        assertEquals(0, remote.mutations.size)
    }

    @Test
    fun `operational save never mutates Storefront`() = runTest {
        remote.readResponse = StorefrontAuthoringReadResponse(
            ok = true,
            code = "success",
            rows = listOf(publication(version = 4))
        )
        val viewModel = viewModel()
        val product = product()
        coEvery { repository.updateProduct(any()) } returns Unit
        coEvery { repository.getProductDetailsById(LOCAL_ID) } returns null

        viewModel.openProductEditor(product)
        advanceUntilIdle()
        viewModel.startProductEditorSave(product.copy(productName = "Internal only"))
        advanceUntilIdle()

        assertEquals(0, remote.mutations.size)
        assertEquals(1_990L, viewModel.storefrontEditorState.value.draft.publicPrice)
    }

    @Test
    fun `operational image adoption finalizes public variants before draft mutation`() = runTest {
        remote.readResponse = StorefrontAuthoringReadResponse(
            ok = true,
            code = "success",
            rows = listOf(publication(version = 4))
        )
        coEvery { imageService.adoptForStorefront(LOCAL_ID, PUBLICATION_ID) } returns IMAGE_ID
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()

        viewModel.adoptOperationalImageForStorefront(product())
        advanceUntilIdle()

        assertEquals(IMAGE_ID, viewModel.storefrontEditorState.value.draft.publicImageId)
        assertEquals(0, remote.mutations.size)
        coVerify(exactly = 1) { imageService.adoptForStorefront(LOCAL_ID, PUBLICATION_ID) }
    }

    @Test
    fun `published operational product delete fails closed until archive`() = runTest {
        remote.readResponse = StorefrontAuthoringReadResponse(
            ok = true,
            code = "success",
            rows = listOf(publication(version = 2, status = "published"))
        )
        val viewModel = viewModel()

        viewModel.deleteProduct(product())
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.deleteProduct(any()) }
        assertTrue(viewModel.uiState.value is UiState.Error)
    }

    @Test
    fun `shop switch during mutation never applies stale ACK`() = runTest {
        remote.readResponse = StorefrontAuthoringReadResponse(ok = true, code = "success")
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()
        viewModel.updateStorefrontDraft { it.copy(publicName = "Scoped", publicPrice = 1_990) }
        remote.beforeMutationResponse = { scope = ACCOUNT_ID to OTHER_SHOP_ID }
        remote.mutationResponse = StorefrontAuthoringMutationResponse(
            ok = true,
            code = "success",
            payload = publication(version = 1, name = "Scoped")
        )

        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()

        assertNull(viewModel.storefrontEditorState.value.publication)
    }

    @Test
    fun `storefront list filter never materializes the complete local catalog`() = runTest {
        val viewModel = viewModel()

        viewModel.setStorefrontListFilter(StorefrontListFilter.PUBLISHED)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getAllProducts() }
        assertNull(viewModel.storefrontFilteredProductIds.value)
    }

    @Test
    fun `visible summaries continue beyond one hundred without duplicate reload`() = runTest {
        val products = (1L..150L).map { id ->
            product().copy(id = id, barcode = "barcode-$id")
        }
        coEvery { repository.getSyncedProductRemoteIds(any()) } answers {
            firstArg<List<Long>>().associateWith { id ->
                "dddddddd-dddd-dddd-dddd-${id.toString().padStart(12, '0')}"
            }
        }
        remote.readResponse = StorefrontAuthoringReadResponse(ok = true, code = "success")
        val viewModel = viewModel()

        viewModel.loadStorefrontSummaries(products)
        advanceUntilIdle()
        viewModel.loadStorefrontSummaries(products)
        advanceUntilIdle()

        assertTrue(remote.readBatchSizes.isEmpty())
        assertEquals(listOf(100, 50), remote.summaryBatchSizes)
        assertEquals(150, viewModel.storefrontSummaries.value.size)
    }

    private fun viewModel() = DatabaseViewModel(
        app = app,
        repository = repository,
        productImageService = imageService,
        storefrontRemote = remote,
        storefrontEnabled = true,
        storefrontNetworkAvailable = { network },
        storefrontScopeProvider = { scope }
    )

    private fun product() = Product(
        id = LOCAL_ID,
        barcode = "780000000001",
        productName = "Internal tea",
        purchasePrice = 700.0,
        retailPrice = 1_500.0,
        stockQuantity = 32.0
    )

    private fun publication(
        version: Long,
        name: String = "Public tea",
        status: String = "draft",
        source: String = "android",
        description: String = "Public description",
        categoryId: String = CATEGORY_ID,
        imageId: String? = null
    ) = StorefrontPublication(
        publicationId = PUBLICATION_ID,
        sourceProductId = REMOTE_ID,
        status = status,
        publicName = name,
        publicDescription = description,
        storefrontCategoryId = categoryId,
        publicPrice = 1_990,
        publicImageId = imageId,
        pickupEnabled = true,
        version = version,
        updatedAt = "2026-08-21T12:00:00Z",
        mutationSource = source
    )

    private class FakeStorefrontRemote : StorefrontAuthoringRemoteDataSource {
        override val isConfigured: Boolean = true
        var readResponse = StorefrontAuthoringReadResponse(ok = true, code = "success")
        var mutationResponse = StorefrontAuthoringMutationResponse()
        var beforeMutationResponse: (() -> Unit)? = null
        val mutations = mutableListOf<Mutation>()
        val readBatchSizes = mutableListOf<Int>()
        val summaryBatchSizes = mutableListOf<Int>()
        var summaryResponse = StorefrontAuthoringSummaryResponse(ok = true, code = "success")
        var summaryCalls = 0

        override suspend fun read(
            shopId: String,
            sourceProductIds: List<String>?,
            status: com.example.merchandisecontrolsplitview.data.StorefrontPublicationStatus?,
            page: Int
        ): Result<StorefrontAuthoringReadResponse> {
            sourceProductIds?.let { readBatchSizes += it.size }
            return Result.success(readResponse)
        }

        override suspend fun readSummary(
            shopId: String,
            filter: StorefrontSummaryFilter,
            query: String?,
            sourceProductIds: List<String>?,
            page: Int,
            pageSize: Int
        ): Result<StorefrontAuthoringSummaryResponse> {
            summaryCalls += 1
            sourceProductIds?.let { summaryBatchSizes += it.size }
            return Result.success(summaryResponse)
        }

        override suspend fun mutate(
            shopId: String,
            sourceProductId: String,
            operation: StorefrontMutationOperation,
            draft: StorefrontEditorDraft,
            expectedVersion: Long,
            idempotencyKey: String
        ): Result<StorefrontAuthoringMutationResponse> {
            mutations += Mutation(shopId, operation, expectedVersion, idempotencyKey, draft)
            beforeMutationResponse?.invoke()
            return Result.success(mutationResponse)
        }
    }

    private data class Mutation(
        val shopId: String,
        val operation: StorefrontMutationOperation,
        val expectedVersion: Long,
        val idempotencyKey: String,
        val draft: StorefrontEditorDraft
    )

    private companion object {
        const val LOCAL_ID = 42L
        const val ACCOUNT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val SHOP_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val OTHER_SHOP_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val REMOTE_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val PUBLICATION_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val CATEGORY_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        const val OTHER_CATEGORY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val IMAGE_ID = "99999999-9999-9999-9999-999999999999"
        const val OTHER_IMAGE_ID = "88888888-8888-8888-8888-888888888888"
    }
}

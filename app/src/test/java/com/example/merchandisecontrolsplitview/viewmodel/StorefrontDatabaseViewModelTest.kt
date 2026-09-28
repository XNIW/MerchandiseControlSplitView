package com.example.merchandisecontrolsplitview.viewmodel

import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.data.FileStorefrontPendingMutationStore
import com.example.merchandisecontrolsplitview.data.StorefrontPendingMutationStore
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
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

    @get:Rule
    val temporaryFolder = org.junit.rules.TemporaryFolder()
    private lateinit var pendingStore: StorefrontPendingMutationStore

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
        pendingStore = FileStorefrontPendingMutationStore(temporaryFolder.root)
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

    @Test
    fun `saved offline draft survives dismiss and new viewmodel`() = runTest {
        network = false
        val original = viewModel()
        original.openProductEditor(product())
        advanceUntilIdle()
        original.updateStorefrontDraft { it.copy(publicName = "Durable", publicPrice = 1990) }
        original.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        original.dismissProductEditor()
        original.openProductEditor(product())
        advanceUntilIdle()
        assertEquals("Durable", original.storefrontEditorState.value.draft.publicName)
        original.dismissProductEditor()
        pendingStore = FileStorefrontPendingMutationStore(temporaryFolder.root)
        val recreated = viewModel()
        recreated.openProductEditor(product())
        advanceUntilIdle()
        assertEquals("Durable", recreated.storefrontEditorState.value.draft.publicName)
        assertTrue(recreated.storefrontEditorState.value.pendingConnection)
    }

    @Test
    fun `lost ACK followed by edit replays immutable payload before new intent`() = runTest {
        val viewModel = viewModel()
        viewModel.openProductEditor(product())
        advanceUntilIdle()
        viewModel.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.mutationResponse = StorefrontAuthoringMutationResponse(code = "backend_unavailable")
        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        viewModel.updateStorefrontDraft { it.copy(publicName = "B") }
        remote.mutationResponse = StorefrontAuthoringMutationResponse(
            ok = true, code = "success", payload = publication(1, "A")
        )
        remote.readResponse = remote.readResponse.copy(rows = listOf(publication(1, "A")))
        viewModel.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertEquals("A", remote.mutations[1].draft.publicName)
        assertEquals(remote.mutations[0].idempotencyKey, remote.mutations[1].idempotencyKey)
        assertEquals("B", remote.mutations[2].draft.publicName)
        assertTrue(remote.mutations[1].idempotencyKey != remote.mutations[2].idempotencyKey)
        assertEquals(1L, remote.mutations[2].expectedVersion)
    }

    @Test
    fun `disk failure never confirms local save or loses current input`() = runTest {
        network = false
        val realStore = pendingStore
        pendingStore = object : StorefrontPendingMutationStore by realStore {
            override fun write(record: com.example.merchandisecontrolsplitview.data.StorefrontPendingMutation) {
                throw java.io.IOException("injected disk failure")
            }
        }
        val vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "Input retained", publicPrice = 1990) }
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertFalse(vm.storefrontEditorState.value.pendingConnection)
        assertEquals("local_persistence_failed", vm.storefrontEditorState.value.errorCode)
        assertEquals("Input retained", vm.storefrontEditorState.value.draft.publicName)
        assertTrue(remote.mutations.isEmpty())
    }

    @Test
    fun `saved drafts remain isolated across products account shop and logout`() = runTest {
        network = false
        coEvery { repository.getSyncedProductRemoteIds(any()) } answers {
            firstArg<List<Long>>().associateWith { if (it == LOCAL_ID) REMOTE_ID else OTHER_IMAGE_ID }
        }
        val vm = viewModel()
        suspend fun save(product: Product, name: String) {
            vm.openProductEditor(product)
            advanceUntilIdle()
            vm.updateStorefrontDraft { it.copy(publicName = name, publicPrice = 1990) }
            vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
            advanceUntilIdle()
            vm.dismissProductEditor()
        }
        save(product(), "First")
        save(product().copy(id = LOCAL_ID + 1), "Second")
        scope = ACCOUNT_ID to OTHER_SHOP_ID
        vm.openProductEditor(product())
        advanceUntilIdle()
        assertFalse(vm.storefrontEditorState.value.pendingConnection)
        save(product(), "Other shop")
        scope = OTHER_SHOP_ID to SHOP_ID
        vm.openProductEditor(product())
        advanceUntilIdle()
        assertFalse(vm.storefrontEditorState.value.pendingConnection)
        scope = null
        vm.openProductEditor(product())
        advanceUntilIdle()
        assertFalse(vm.storefrontEditorState.value.canAuthor)
        scope = ACCOUNT_ID to SHOP_ID
        vm.openProductEditor(product())
        advanceUntilIdle()
        assertEquals("First", vm.storefrontEditorState.value.draft.publicName)
        vm.openProductEditor(product().copy(id = LOCAL_ID + 1))
        advanceUntilIdle()
        assertEquals("Second", vm.storefrontEditorState.value.draft.publicName)
    }

    @Test
    fun `ACK lost survives restart and sends successor once after exact replay`() = runTest {
        installReceiptServer()
        var vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.loseAck = true
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertEquals(1L, remote.readResponse.rows.single().version)
        vm.updateStorefrontDraft { it.copy(publicName = "B") }
        remote.failBeforeCommit = true
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        vm.dismissProductEditor()
        pendingStore = FileStorefrontPendingMutationStore(temporaryFolder.root)
        vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        assertEquals("B", vm.storefrontEditorState.value.draft.publicName)
        vm.retryPendingStorefrontDraft()
        advanceUntilIdle()
        assertEquals(listOf("A", "A", "A", "B"), remote.mutations.map { it.draft.publicName })
        assertEquals(1, remote.mutations.take(3).map { it.idempotencyKey }.distinct().size)
        assertEquals(2L, remote.readResponse.rows.single().version)
        assertEquals("B", vm.storefrontEditorState.value.draft.publicName)
        assertFalse(vm.storefrontEditorState.value.pendingConnection)
    }

    @Test
    fun `timeout before commit retries same payload and key without duplicate`() = runTest {
        installReceiptServer()
        val vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.failBeforeCommit = true
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        vm.retryPendingStorefrontDraft()
        advanceUntilIdle()
        assertEquals(1L, remote.readResponse.rows.single().version)
        assertEquals(1, remote.mutations.map { it.idempotencyKey }.distinct().size)
        assertFalse(vm.storefrontEditorState.value.pendingConnection)
    }

    @Test
    fun `validation rejection then edit creates fresh intent while unknown permission failure does not`() = runTest {
        val vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.mutationResponse = StorefrontAuthoringMutationResponse(code = "validation_failed")
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "B") }
        remote.mutationResponse = StorefrontAuthoringMutationResponse(code = "backend_unavailable")
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertTrue(remote.mutations[0].idempotencyKey != remote.mutations[1].idempotencyKey)
        remote.mutationResponse = StorefrontAuthoringMutationResponse(code = "permission_denied")
        vm.updateStorefrontDraft { it.copy(publicName = "C") }
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertEquals(listOf("A", "B", "B", "B"), remote.mutations.map { it.draft.publicName })
        assertEquals(1, remote.mutations.drop(1).map { it.idempotencyKey }.distinct().size)
    }

    @Test
    fun `receipt snapshot never overwrites newer other platform state`() = runTest {
        installReceiptServer()
        val vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.loseAck = true
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        remote.readResponse = remote.readResponse.copy(rows = listOf(publication(2, "Other platform", source = "ios")))
        vm.retryPendingStorefrontDraft()
        advanceUntilIdle()
        assertEquals("Other platform", vm.storefrontEditorState.value.draft.publicName)
        assertEquals(2L, vm.storefrontEditorState.value.publication?.version)
        assertFalse(vm.storefrontEditorState.value.pendingConnection)
    }

    @Test
    fun `successor after lost ACK and concurrent update requires durable conflict decision`() = runTest {
        installReceiptServer()
        var vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.loseAck = true
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        remote.readResponse = remote.readResponse.copy(rows = listOf(publication(2, "Other platform", source = "ios")))
        vm.updateStorefrontDraft { it.copy(publicName = "B") }
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertEquals(2, remote.mutations.size)
        assertEquals("B", vm.storefrontEditorState.value.conflict?.localDraft?.publicName)
        vm.dismissProductEditor()
        vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        assertEquals(2L, vm.storefrontEditorState.value.conflict?.server?.version)
        vm.reapplyStorefrontConflict()
        advanceUntilIdle()
        vm.retryPendingStorefrontDraft()
        advanceUntilIdle()
        assertEquals(3L, remote.readResponse.rows.single().version)
        assertEquals("B", vm.storefrontEditorState.value.draft.publicName)
    }

    @Test
    fun `all publication operations keep exact intent through lost ACK`() = runTest {
        for (operation in listOf(StorefrontMutationOperation.PUBLISH, StorefrontMutationOperation.SCHEDULE,
            StorefrontMutationOperation.HIDE, StorefrontMutationOperation.ARCHIVE)) {
            remote = FakeStorefrontRemote()
            remote.readResponse = remote.readResponse.copy(rows = listOf(publication(4)))
            installReceiptServer()
            val vm = viewModel()
            vm.openProductEditor(product())
            advanceUntilIdle()
            if (operation == StorefrontMutationOperation.SCHEDULE) vm.updateStorefrontDraft {
                it.copy(promotionStartsAt = "2027-01-01T00:00:00Z", promotionEndsAt = "2027-02-01T00:00:00Z")
            }
            remote.loseAck = true
            vm.mutateStorefront(operation)
            advanceUntilIdle()
            vm.dismissProductEditor()
            val restored = viewModel()
            restored.openProductEditor(product())
            advanceUntilIdle()
            restored.retryPendingStorefrontDraft()
            advanceUntilIdle()
            assertEquals(operation, remote.mutations.last().operation)
            assertEquals(2, remote.mutations.size)
            assertEquals(1, remote.mutations.map { it.idempotencyKey }.distinct().size)
            assertEquals(5L, restored.storefrontEditorState.value.publication?.version)
            restored.dismissProductEditor()
        }
    }

    @Test
    fun `changing filter while prior summary is suspended accepts only new rows and resets All`() = runTest {
        val oldReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val oldStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<StorefrontSummaryFilter, String?>>()
        remote.summaryHandler = { selected, query, _ ->
            calls += selected to query
            if (selected == StorefrontSummaryFilter.PUBLISHED) {
                oldStarted.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldReply.await() }
                Result.failure(java.io.IOException("late old request failure"))
            } else Result.success(StorefrontAuthoringSummaryResponse(ok = true, code = "success",
                rows = listOf(StorefrontPublicationListSummary(REMOTE_ID, "draft", version = 1))))
        }
        val row = com.example.merchandisecontrolsplitview.data.ProductWithDetails(product(), null, null, null, null, null, null)
        coEvery { repository.getProductsWithDetailsByRemoteIds(any()) } returns listOf(row)
        every { repository.getProductsWithDetailsPaged(any(), any()) } answers {
            object : androidx.paging.PagingSource<Int, com.example.merchandisecontrolsplitview.data.ProductWithDetails>() {
                override suspend fun load(params: LoadParams<Int>) = LoadResult.Page<Int, com.example.merchandisecontrolsplitview.data.ProductWithDetails>(emptyList(), null, null)
                override fun getRefreshKey(state: androidx.paging.PagingState<Int, com.example.merchandisecontrolsplitview.data.ProductWithDetails>): Int? = null
            }
        }
        val vm = viewModel()
        val presenter = object : androidx.paging.PagingDataPresenter<com.example.merchandisecontrolsplitview.data.ProductWithDetails>(mainContext = mainDispatcherRule.dispatcher) {
            override suspend fun presentPagingDataEvent(event: androidx.paging.PagingDataEvent<com.example.merchandisecontrolsplitview.data.ProductWithDetails>) = Unit
        }
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.pager.collectLatest { presenter.collectFrom(it) } }
        vm.setStorefrontListFilter(StorefrontListFilter.PUBLISHED)
        runCurrent()
        oldStarted.await()
        vm.setStorefrontListFilter(StorefrontListFilter.DRAFT)
        runCurrent()
        assertTrue(calls.any { it.first == StorefrontSummaryFilter.DRAFT })
        assertEquals(listOf(LOCAL_ID), presenter.snapshot().items.map { it.product.id })
        oldReply.complete(Unit)
        runCurrent()
        assertEquals(listOf(LOCAL_ID), presenter.snapshot().items.map { it.product.id })
        vm.setStorefrontListFilter(StorefrontListFilter.ALL)
        runCurrent()
        assertTrue(presenter.snapshot().items.isEmpty())
        assertFalse(vm.storefrontFilterLoading.value)
    }

    @Test
    fun `same filter query changes start new bounded request`() = runTest {
        val queries = mutableListOf<String?>()
        remote.summaryHandler = { _, query, _ ->
            queries += query
            Result.success(StorefrontAuthoringSummaryResponse(ok = true, code = "success"))
        }
        coEvery { repository.getProductsWithDetailsByRemoteIds(any()) } returns emptyList()
        val vm = viewModel()
        vm.setStorefrontListFilter(StorefrontListFilter.DRAFT)
        val presenter = object : androidx.paging.PagingDataPresenter<com.example.merchandisecontrolsplitview.data.ProductWithDetails>(mainContext = mainDispatcherRule.dispatcher) {
            override suspend fun presentPagingDataEvent(event: androidx.paging.PagingDataEvent<com.example.merchandisecontrolsplitview.data.ProductWithDetails>) = Unit
        }
        backgroundScope.launch(mainDispatcherRule.dispatcher) { vm.pager.collectLatest { presenter.collectFrom(it) } }
        vm.setFilter("tea")
        advanceTimeBy(500)
        runCurrent()
        vm.setFilter("coffee")
        advanceTimeBy(500)
        runCurrent()
        assertTrue(queries.contains("tea"))
        assertEquals("coffee", queries.last())
        assertFalse(vm.storefrontFilterLoading.value)
    }

    @Test
    fun `busy editor rejects edits and duplicate taps while ACK is suspended`() = runTest {
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.updateStorefrontDraft { it.copy(publicName = "A", publicPrice = 1990) }
        remote.mutationHandler = {
            response.await()
            Result.success(StorefrontAuthoringMutationResponse(ok = true, code = "success", payload = publication(1, "A")))
        }
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        runCurrent()
        assertTrue(vm.storefrontEditorState.value.busy)
        vm.updateStorefrontDraft { it.copy(publicName = "B") }
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        assertEquals("A", vm.storefrontEditorState.value.draft.publicName)
        assertEquals(1, remote.mutations.size)
        response.complete(Unit)
        advanceUntilIdle()
        assertEquals("A", vm.storefrontEditorState.value.draft.publicName)
    }

    @Test
    fun `expired receipt is reconciled to explicit conflict without a blind replay`() = runTest {
        val draft = StorefrontEditorDraft(publicName = "Expired", publicPrice = 1990)
        pendingStore.write(com.example.merchandisecontrolsplitview.data.StorefrontPendingMutation(
            ACCOUNT_ID, SHOP_ID, REMOTE_ID, StorefrontMutationOperation.SAVE_DRAFT,
            draft, draft, null, 0, OTHER_IMAGE_ID,
            state = com.example.merchandisecontrolsplitview.data.StorefrontIntentState.DISPATCHED,
            dispatchedAtMs = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(8)
        ))
        remote.readResponse = remote.readResponse.copy(rows = listOf(publication(3, "Current")))
        val vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.retryPendingStorefrontDraft()
        advanceUntilIdle()
        assertEquals(0, remote.mutations.size)
        assertEquals(3L, vm.storefrontEditorState.value.conflict?.server?.version)
        assertEquals("Expired", vm.storefrontEditorState.value.conflict?.localDraft?.publicName)
        vm.cancelStorefrontConflict()
        advanceUntilIdle()
        assertNull(pendingStore.read(ACCOUNT_ID, SHOP_ID, REMOTE_ID))
    }

    @Test
    fun `expired uncommitted intent with absent publication permits explicit discard preserving draft`() = runTest {
        val draft = StorefrontEditorDraft(publicName = "Still editable", publicPrice = 1990)
        pendingStore.write(com.example.merchandisecontrolsplitview.data.StorefrontPendingMutation(
            ACCOUNT_ID, SHOP_ID, REMOTE_ID, StorefrontMutationOperation.SAVE_DRAFT,
            draft, StorefrontEditorDraft(), null, 0, OTHER_IMAGE_ID,
            state = com.example.merchandisecontrolsplitview.data.StorefrontIntentState.DISPATCHED,
            dispatchedAtMs = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(8)
        ))
        var vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.retryPendingStorefrontDraft()
        advanceUntilIdle()
        assertTrue(remote.mutations.isEmpty())
        vm.dismissProductEditor()
        vm = viewModel()
        vm.openProductEditor(product())
        advanceUntilIdle()
        vm.cancelStorefrontConflict()
        advanceUntilIdle()
        assertNull(pendingStore.read(ACCOUNT_ID, SHOP_ID, REMOTE_ID))
        assertEquals("Still editable", vm.storefrontEditorState.value.draft.publicName)
        installReceiptServer()
        vm.mutateStorefront(StorefrontMutationOperation.SAVE_DRAFT)
        advanceUntilIdle()
        assertEquals(1, remote.mutations.size)
        assertTrue(remote.mutations.single().idempotencyKey != OTHER_IMAGE_ID)
        assertEquals("Still editable", remote.readResponse.rows.single().publicName)
    }

    private fun installReceiptServer() {
        val receipts = mutableMapOf<String, Pair<Mutation, StorefrontAuthoringMutationResponse>>()
        remote.mutationHandler = { request ->
            if (remote.failBeforeCommit) {
                remote.failBeforeCommit = false
                Result.failure(java.io.IOException("before commit"))
            } else {
                val receipt = receipts[request.idempotencyKey]
                val response = if (receipt != null) {
                    if (receipt.first == request) receipt.second.copy(idempotent = true)
                    else StorefrontAuthoringMutationResponse(code = "idempotency_conflict")
                } else if (request.expectedVersion != (remote.readResponse.rows.singleOrNull()?.version ?: 0L)) {
                    StorefrontAuthoringMutationResponse(code = "stale_revision", server = remote.readResponse.rows.singleOrNull())
                } else {
                    val result = StorefrontAuthoringMutationResponse(ok = true, code = "success",
                        payload = publication(request.expectedVersion + 1, request.draft.publicName).copy(
                            publicDescription = request.draft.publicDescription, publicPrice = request.draft.publicPrice ?: 1990,
                            promotionStartsAt = request.draft.promotionStartsAt, promotionEndsAt = request.draft.promotionEndsAt))
                    receipts[request.idempotencyKey] = request to result
                    remote.readResponse = remote.readResponse.copy(rows = listOf(requireNotNull(result.payload)))
                    result
                }
                if (remote.loseAck) {
                    remote.loseAck = false
                    Result.failure(java.io.IOException("committed response lost"))
                } else Result.success(response)
            }
        }
    }

    private fun viewModel() = DatabaseViewModel(
        app = app,
        repository = repository,
        productImageService = imageService,
        storefrontRemote = remote,
        storefrontEnabled = true,
        storefrontPendingStore = pendingStore,
        storefrontStorageDispatcher = mainDispatcherRule.dispatcher,
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
        var mutationHandler: (suspend (Mutation) -> Result<StorefrontAuthoringMutationResponse>)? = null
        var failBeforeCommit = false
        var loseAck = false
        val mutations = mutableListOf<Mutation>()
        val readBatchSizes = mutableListOf<Int>()
        val summaryBatchSizes = mutableListOf<Int>()
        var summaryResponse = StorefrontAuthoringSummaryResponse(ok = true, code = "success")
        var summaryCalls = 0
        var summaryHandler: (suspend (StorefrontSummaryFilter, String?, Int) -> Result<StorefrontAuthoringSummaryResponse>)? = null

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
            return summaryHandler?.invoke(filter, query, page) ?: Result.success(summaryResponse)
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
            return mutationHandler?.invoke(mutations.last()) ?: Result.success(mutationResponse)
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

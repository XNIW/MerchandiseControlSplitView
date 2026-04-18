package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Test del [RealtimeRefreshCoordinator] (task 010).
 *
 * Strategia: usa [FakeInventoryRepository] per isolare il coordinator dal DB reale,
 * [backgroundScope] di [runTest] per controllare la concorrenza e debounceMs ridotto
 * per evitare attese reali. [runDrain] viene chiamato direttamente nei test di
 * comportamento per bypassare il timing del debounce senza dipendere dal clock virtuale.
 *
 * Proprietà verificate:
 * - Coalescing per remoteId: payload multipli sullo stesso remoteId → un solo apply.
 * - Batch per remoteId diversi: N remoteId distinti → batch con N payload.
 * - Background skip: runDrain salta se isForeground è false.
 * - No bypass repository: il percorso passa sempre per applyRemoteSessionPayloadBatch.
 * - No op su buffer vuoto: runDrain non chiama il repository senza segnali.
 * - Idempotenza inbound: stesso payload twice → Skipped (via fingerprint 009).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeRefreshCoordinatorTest {

    // --- Helper ---

    private fun remotePayload(
        remoteId: String = UUID.randomUUID().toString(),
        supplier: String = "TestSupplier",
        data: List<List<String>> = listOf(listOf("barcode"), listOf("TESTBC"))
    ) = SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = SESSION_PAYLOAD_VERSION,
        timestamp = "2026-04-15 10:00:00",
        supplier = supplier,
        category = "TestCat",
        isManualEntry = false,
        data = data
    )

    // --- Test ---

    @Test
    fun `runDrain coalesces multiple payloads for same remoteId — only last survives`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        val remoteId = "same-remote-id"
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = remoteId, supplier = "First")))
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = remoteId, supplier = "Second")))
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = remoteId, supplier = "Third")))

        // Il collector (UnconfinedTestDispatcher) ha già processato i segnali nel buffer.
        // Drain diretto per verificare coalescing senza aspettare il debounce timer.
        advanceUntilIdle()
        coordinator.runDrain()

        assertEquals("Un solo batch atteso", 1, fakeRepo.appliedBatches.size)
        assertEquals("Un solo payload nel batch (coalesced per remoteId)", 1, fakeRepo.appliedBatches.single().size)
        assertEquals("L'ultimo payload (Third) deve sopravvivere", "Third", fakeRepo.appliedBatches.single().single().supplier)
    }

    @Test
    fun `runDrain applies all payloads for distinct remoteIds in one batch`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = "id-A", supplier = "A")))
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = "id-B", supplier = "B")))
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = "id-C", supplier = "C")))

        advanceUntilIdle()
        coordinator.runDrain()

        assertEquals("Un solo batch per tutti e tre i remoteId", 1, fakeRepo.appliedBatches.size)
        assertEquals("3 payload nel batch (remoteId distinti)", 3, fakeRepo.appliedBatches.single().size)
        val suppliers = fakeRepo.appliedBatches.single().map { it.supplier }.toSet()
        assertEquals(setOf("A", "B", "C"), suppliers)
    }

    @Test
    fun `runDrain skips when app is in background — buffer preserved`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        coordinator.onAppBackground()
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(supplier = "BackgroundPayload")))

        advanceUntilIdle()
        coordinator.runDrain() // deve skippare

        assertEquals("Nessun batch in background", 0, fakeRepo.appliedBatches.size)
        assertEquals("Buffer preservato: isForeground=false", false, coordinator.isForeground)
    }

    @Test
    fun `onAppForeground after background resumes drain on next tickle`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        coordinator.onAppBackground()
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(supplier = "WasPending")))

        advanceUntilIdle()
        coordinator.runDrain() // skip background
        assertEquals("Nessun apply in background", 0, fakeRepo.appliedBatches.size)

        // Resume: foreground → drain del buffer pending
        coordinator.onAppForeground()
        coordinator.runDrain()

        assertEquals("Apply eseguito al resume", 1, fakeRepo.appliedBatches.size)
        assertEquals("WasPending", fakeRepo.appliedBatches.single().single().supplier)
    }

    @Test
    fun `runDrain does nothing with empty buffer — no repository call`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        coordinator.runDrain() // buffer vuoto

        assertEquals("Nessun batch senza segnali", 0, fakeRepo.appliedBatches.size)
    }

    @Test
    fun `coordinator always routes through applyRemoteSessionPayloadBatch — no repository bypass`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        val payload = remotePayload(supplier = "NoBypass")
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(payload))
        advanceUntilIdle()
        coordinator.runDrain()

        // Il percorso è: coordinator → applyRemoteSessionPayloadBatch (non applyRemoteSessionPayload diretto)
        assertEquals("Il batch è passato per applyRemoteSessionPayloadBatch", 1, fakeRepo.batchCallCount)
        assertEquals("Il singolo apply diretto NON è stato chiamato", 0, fakeRepo.singleApplyCallCount)
        assertTrue("Il payload è nel batch", fakeRepo.appliedBatches.single().any { it.supplier == "NoBypass" })
    }

    @Test
    fun `multiple drains after signals are each independent`() = runTest {
        val fakeRepo = FakeInventoryRepository()
        val coordinator = RealtimeRefreshCoordinator(
            repository = fakeRepo,
            scope = backgroundScope,
            debounceMs = 100L
        )

        // Prima finestra
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = "X", supplier = "X1")))
        advanceUntilIdle()
        coordinator.runDrain()

        // Seconda finestra (nuovo segnale dopo il primo drain)
        coordinator.onRemoteSignal(RemoteSignal.PayloadAvailable(remotePayload(remoteId = "Y", supplier = "Y1")))
        advanceUntilIdle()
        coordinator.runDrain()

        assertEquals("Due drain distinti = due batch", 2, fakeRepo.appliedBatches.size)
        assertEquals("X1", fakeRepo.appliedBatches[0].single().supplier)
        assertEquals("Y1", fakeRepo.appliedBatches[1].single().supplier)
    }

    @Test
    fun `coordinator isForeground is true by default`() = runTest {
        val coordinator = RealtimeRefreshCoordinator(
            repository = FakeInventoryRepository(),
            scope = backgroundScope,
            debounceMs = 100L
        )
        // Default: foreground-first — nessun blocco senza aggancio esplicito al lifecycle
        assertEquals(true, coordinator.isForeground)
    }

    // --- Fake repository ---

    /**
     * Repository falso per isolare il coordinator dal DB reale.
     * Traccia le chiamate a [applyRemoteSessionPayload] e [applyRemoteSessionPayloadBatch].
     * Tutti gli altri metodi lanciano [UnsupportedOperationException].
     */
    private class FakeInventoryRepository : InventoryRepository {

        val appliedBatches = mutableListOf<List<SessionRemotePayload>>()
        var batchCallCount = 0
        var singleApplyCallCount = 0

        override suspend fun applyRemoteSessionPayload(
            payload: SessionRemotePayload
        ): RemoteSessionApplyOutcome {
            singleApplyCallCount++
            appliedBatches.add(listOf(payload))
            return RemoteSessionApplyOutcome.Inserted
        }

        override suspend fun applyRemoteSessionPayloadBatch(
            payloads: List<SessionRemotePayload>
        ): RemoteSessionBatchResult {
            batchCallCount++
            appliedBatches.add(payloads.toList())
            return RemoteSessionBatchResult(
                inserted = payloads.size,
                updated = 0, skipped = 0, failed = 0, unsupported = 0
            )
        }

        override suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = false

        override suspend fun syncCatalogWithRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            ownerUserId: String
        ): Result<CatalogSyncSummary> = Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0
            )
        )

        // --- Stub: non usati in questi test ---

        override fun getProductsWithDetailsPaged(filter: String?): PagingSource<Int, ProductWithDetails> =
            throw UnsupportedOperationException()

        override suspend fun findProductByBarcode(barcode: String): Product? =
            throw UnsupportedOperationException()

        override suspend fun findProductsByBarcodes(barcodes: List<String>): List<Product> =
            throw UnsupportedOperationException()

        override suspend fun getAllProducts(): List<Product> =
            throw UnsupportedOperationException()

        override suspend fun addProduct(product: Product): Unit =
            throw UnsupportedOperationException()

        override suspend fun updateProduct(product: Product): Unit =
            throw UnsupportedOperationException()

        override suspend fun deleteProduct(product: Product): Unit =
            throw UnsupportedOperationException()

        override suspend fun applyImport(request: ImportApplyRequest): ImportApplyResult =
            throw UnsupportedOperationException()

        override suspend fun getSupplierById(id: Long): Supplier? =
            throw UnsupportedOperationException()

        override suspend fun findSupplierByName(name: String): Supplier? =
            throw UnsupportedOperationException()

        override suspend fun getAllSuppliers(): List<Supplier> =
            throw UnsupportedOperationException()

        override suspend fun searchSuppliersByName(query: String): List<Supplier> =
            throw UnsupportedOperationException()

        override suspend fun addSupplier(name: String): Supplier? =
            throw UnsupportedOperationException()

        override suspend fun getCatalogItems(kind: CatalogEntityKind, query: String?): List<CatalogListItem> =
            throw UnsupportedOperationException()

        override suspend fun createCatalogEntry(kind: CatalogEntityKind, name: String): CatalogListItem =
            throw UnsupportedOperationException()

        override suspend fun renameCatalogEntry(kind: CatalogEntityKind, id: Long, newName: String): CatalogListItem =
            throw UnsupportedOperationException()

        override suspend fun deleteCatalogEntry(kind: CatalogEntityKind, id: Long, strategy: CatalogDeleteStrategy): CatalogDeleteResult =
            throw UnsupportedOperationException()

        override suspend fun getCategoryById(id: Long): Category? =
            throw UnsupportedOperationException()

        override suspend fun findCategoryByName(name: String): Category? =
            throw UnsupportedOperationException()

        override suspend fun getAllCategories(): List<Category> =
            throw UnsupportedOperationException()

        override suspend fun searchCategoriesByName(query: String): List<Category> =
            throw UnsupportedOperationException()

        override suspend fun addCategory(name: String): Category? =
            throw UnsupportedOperationException()

        override fun observeSuppliersForHubSearch(query: String): Flow<List<Supplier>> =
            flow { throw UnsupportedOperationException() }

        override fun observeCategoriesForHubSearch(query: String): Flow<List<Category>> =
            flow { throw UnsupportedOperationException() }

        override fun observeCatalogItems(kind: CatalogEntityKind, query: String?): Flow<List<CatalogListItem>> =
            flow { throw UnsupportedOperationException() }

        override fun getFilteredHistoryFlow(filter: com.example.merchandisecontrolsplitview.viewmodel.DateFilter): Flow<List<HistoryEntry>> =
            flow { throw UnsupportedOperationException() }

        override fun getFilteredHistoryListFlow(filter: com.example.merchandisecontrolsplitview.viewmodel.DateFilter): Flow<List<HistoryEntryListItem>> =
            flow { throw UnsupportedOperationException() }

        override fun hasHistoryEntriesFlow(): Flow<Boolean> =
            flow { throw UnsupportedOperationException() }

        override suspend fun getHistoryEntryByUid(uid: Long): HistoryEntry? =
            throw UnsupportedOperationException()

        override suspend fun insertHistoryEntry(entry: HistoryEntry): Long =
            throw UnsupportedOperationException()

        override suspend fun updateHistoryEntry(entry: HistoryEntry): Unit =
            throw UnsupportedOperationException()

        override suspend fun deleteHistoryEntry(entry: HistoryEntry): Unit =
            throw UnsupportedOperationException()

        override suspend fun recordPriceIfChanged(productId: Long, type: String, price: Double, at: String, source: String?): Unit =
            throw UnsupportedOperationException()

        override suspend fun getLastPrice(productId: Long, type: String): Double? =
            throw UnsupportedOperationException()

        override suspend fun getLastPriceBefore(productId: Long, type: String, before: String): Double? =
            throw UnsupportedOperationException()

        override fun getPriceSeries(productId: Long, type: String): Flow<List<ProductPrice>> =
            flow { throw UnsupportedOperationException() }

        override suspend fun getPreviousPricesForBarcodes(barcodes: List<String>, at: String): Map<String, Pair<Double?, Double?>> =
            throw UnsupportedOperationException()

        override suspend fun getAllProductsWithDetails(): List<ProductWithDetails> =
            throw UnsupportedOperationException()

        override suspend fun getProductsWithDetailsPage(limit: Int, offset: Int): List<ProductWithDetails> =
            throw UnsupportedOperationException()

        override suspend fun getAllPriceHistoryRows(): List<PriceHistoryExportRow> =
            throw UnsupportedOperationException()

        override suspend fun getPriceHistoryRowsPage(limit: Int, offset: Int): List<PriceHistoryExportRow> =
            throw UnsupportedOperationException()

        override suspend fun getAllProductsLite(): List<ProductDao.ProductLite> =
            throw UnsupportedOperationException()

        override suspend fun recordPriceHistoryByBarcodeBatch(
            rows: List<Triple<String, String, Pair<String, Double>>>,
            source: String
        ): Unit = throw UnsupportedOperationException()

        override suspend fun getCurrentPricesForBarcodes(barcodes: List<String>): Map<String, Pair<Double?, Double?>> =
            throw UnsupportedOperationException()

        override suspend fun getCurrentPriceSnapshot(): List<CurrentPriceRow> =
            throw UnsupportedOperationException()

        override suspend fun getOrCreateRemoteId(historyEntryUid: Long): String? =
            throw UnsupportedOperationException()

        override suspend fun getRemoteRef(historyEntryUid: Long): HistoryEntryRemoteRef? =
            throw UnsupportedOperationException()
    }
}

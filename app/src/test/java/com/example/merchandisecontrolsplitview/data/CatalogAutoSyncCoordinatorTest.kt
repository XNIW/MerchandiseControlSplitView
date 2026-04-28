package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogAutoSyncCoordinatorTest {

    @Test
    fun `043 auto push runs targeted repository lane when signed in and foreground`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        coordinator.runPushCycle("test")

        assertEquals(1, repository.pushCalls)
        assertEquals(0, repository.bootstrapCalls)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `043 auto push skips while a manual catalog flight owns the tracker`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        tracker.tryBegin(CatalogSyncFlightOwner.MANUAL)
        coordinator.runPushCycle("manual_busy")

        assertEquals(0, repository.pushCalls)
        assertEquals(null, tracker.lastOutcome.value)
        tracker.finish(CatalogSyncFlightOwner.MANUAL)
        coordinator.shutdown()
    }

    @Test
    fun `061 auto push with sync events publishes summary outcome`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            nextQuickSummary = emptySummary(pushedProducts = 1).copy(
                manualFullSyncRequired = true,
                syncEventsGapDetected = true,
                syncEventsTooLarge = true,
                syncEventOutboxRetried = 1,
                syncEventOutboxPending = 2
            )
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runPushCycle("sync_events")

        assertEquals(0, repository.pushCalls)
        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(CatalogSyncFlightOwner.AUTO_PUSH, tracker.lastOutcome.value?.source)
        assertEquals(USER_ID, tracker.lastOutcome.value?.ownerUserId)
        assertEquals(true, tracker.lastOutcome.value?.summary?.manualFullSyncRequired)
        assertTrue(logs.any { it.contains("manualFullSyncRequired=true") })
        assertTrue(logs.any { it.contains("syncEventsGapDetected=true") })
        assertTrue(logs.any { it.contains("syncEventsTooLarge=true") })
        assertTrue(logs.any { it.contains("syncEventOutboxRetried=1") })
        assertTrue(logs.any { it.contains("syncEventOutboxPending=2") })
        coordinator.shutdown()
    }

    @Test
    fun `061 sync event drain publishes summary outcome`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            nextDrainSummary = emptySummary(pulledProducts = 1).copy(
                manualFullSyncRequired = true,
                syncEventsFetched = 3,
                syncEventsProcessed = 2,
                syncEventsGapDetected = true,
                syncEventOutboxRetried = 1,
                syncEventOutboxPending = 1
            )
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runSyncEventDrainCycle("test")

        assertEquals(1, repository.drainCalls)
        assertEquals(CatalogSyncFlightOwner.SYNC_EVENTS, tracker.lastOutcome.value?.source)
        assertEquals(USER_ID, tracker.lastOutcome.value?.ownerUserId)
        assertEquals(true, tracker.lastOutcome.value?.summary?.manualFullSyncRequired)
        assertTrue(logs.any { it.contains("manualFullSyncRequired=true") })
        assertTrue(logs.any { it.contains("syncEventsGapDetected=true") })
        assertTrue(logs.any { it.contains("syncEventOutboxRetried=1") })
        assertTrue(logs.any { it.contains("outboxPending=1") })
        coordinator.shutdown()
    }

    @Test
    fun `061 auto push failure does not publish summary outcome`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            failQuick = IllegalStateException("quick failed")
        }
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        coordinator.runPushCycle("failure")

        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(null, tracker.lastOutcome.value)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `043 bootstrap pull is process scoped by user and staleness guard`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            bootstrapStalenessMs = Long.MAX_VALUE
        )

        coordinator.runBootstrapCycle("first")
        coordinator.runBootstrapCycle("second")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.pushCalls)
        coordinator.shutdown()
    }

    @Test
    fun `056 network available schedules catalog catch up push`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            bootstrapStalenessMs = Long.MAX_VALUE
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onNetworkAvailable()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.pushCalls)
        assertTrue(repository.bootstrapCalls in 0..1)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `056 generic local catalog change schedules catch up push`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.pushCalls)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    private class FakeCatalogAutoSyncRepository043 : CatalogAutoSyncRepository {
        var pushCalls = 0
        var quickWithEventsCalls = 0
        var drainCalls = 0
        var bootstrapCalls = 0
        var nextQuickSummary: CatalogSyncSummary = emptySummary(pushedProducts = 1, pushedProductPrices = 1)
        var nextDrainSummary: CatalogSyncSummary = emptySummary(pulledProducts = 1)
        var failQuick: Throwable? = null

        override suspend fun pushDirtyCatalogDeltaToRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            pushCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            return Result.success(emptySummary(pushedProducts = 1, pushedProductPrices = 1))
        }

        override suspend fun syncCatalogQuickWithEvents(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            syncEventRemote: SyncEventRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            quickWithEventsCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            failQuick?.let { return Result.failure(it) }
            return Result.success(nextQuickSummary)
        }

        override suspend fun drainSyncEventsFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            syncEventRemote: SyncEventRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            drainCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN))
            return Result.success(nextDrainSummary)
        }

        override suspend fun pullCatalogBootstrapFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            bootstrapCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            return Result.success(emptySummary(pulledProducts = 1, pulledProductPrices = 1))
        }
    }

    private class FakeCatalogRemote043(
        override val isConfigured: Boolean = true
    ) : CatalogRemoteDataSource {
        override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = Result.success(Unit)
        override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = Result.success(Unit)
        override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> = Result.success(Unit)
        override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
            Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()))
        override suspend fun fetchCatalogByIds(
            supplierIds: Set<String>,
            categoryIds: Set<String>,
            productIds: Set<String>
        ): Result<InventoryCatalogFetchBundle> =
            Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()))
        override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
        override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
        override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
    }

    private class FakePriceRemote043(
        override val isConfigured: Boolean = true
    ) : ProductPriceRemoteDataSource {
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> = Result.success(Unit)
        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = Result.success(emptyList())
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            Result.success(emptyList())
    }

    private class FakeSyncEventRemote043(
        override val isConfigured: Boolean = true
    ) : SyncEventRemoteDataSource {
        override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
            Result.success(
                SyncEventRemoteCapabilities(
                    syncEventsAvailable = true,
                    recordSyncEventAvailable = true,
                    realtimeSyncEventsAvailable = true
                )
            )

        override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
            Result.failure(UnsupportedOperationException("not used"))

        override suspend fun fetchSyncEventsAfter(
            ownerUserId: String,
            storeId: String?,
            afterId: Long,
            limit: Long
        ): Result<List<SyncEventRemoteRow>> =
            Result.success(emptyList())
    }

    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000043"

        fun emptySummary(
            pushedProducts: Int = 0,
            pulledProducts: Int = 0,
            pushedProductPrices: Int = 0,
            pulledProductPrices: Int = 0
        ): CatalogSyncSummary =
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = pushedProducts,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = pulledProducts,
                pushedProductPrices = pushedProductPrices,
                pulledProductPrices = pulledProductPrices,
                deferredProductPricesNoProductRef = 0,
                skippedProductPricesPullNoProductRef = 0,
                priceSyncFailed = false
            )
    }
}

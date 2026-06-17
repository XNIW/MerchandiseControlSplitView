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
    fun `123 default catalog auto push debounce stays within warm autosync budget`() {
        assertEquals(500L, CatalogAutoSyncCoordinator.DEBOUNCE_MS)
    }

    @Test
    fun `132 automatic push is blocked by safety guard when signed in and foreground`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runPushCycle("test")

        assertEquals(0, repository.pushCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(0, repository.bootstrapCalls)
        assertEquals(false, tracker.isSyncing.value)
        assertTrue(logs.any { it.contains("automatic_push_safety_guard") })
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
    fun `132 local push waits while sync event drain owns tracker then retries`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
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
            debounceMs = 1L,
            logger = { logs += it }
        )

        assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.SYNC_EVENTS))
        coordinator.runPushCycle("local_commit")

        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("cycle=catalog_push outcome=skip reason=sync_busy") })

        tracker.finish(CatalogSyncFlightOwner.SYNC_EVENTS)
        advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_BUSY_MS + 2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(CatalogSyncFlightOwner.AUTO_PUSH, tracker.lastOutcome.value?.source)
        coordinator.shutdown()
    }

    @Test
    fun `132 sync event drain yields to catalog bootstrap when bootstrap is required`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = true
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

        coordinator.runSyncEventDrainCycle("foreground")

        assertEquals(0, repository.drainCalls)
        assertEquals(false, tracker.isSyncing.value)
        assertTrue(logs.any { it.contains("cycle=sync_events_drain outcome=skip reason=bootstrap_required") })
        coordinator.shutdown()
    }

    @Test
    fun `132 auto push with sync events is blocked before repository calls`() = runTest {
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
        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(null, tracker.lastOutcome.value?.source)
        assertTrue(logs.any { it.contains("automatic_push_safety_guard") })
        coordinator.shutdown()
    }

    @Test
    fun `061 sync event drain publishes summary outcome`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
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
    fun `114 foreground does not run bootstrap when local baseline is already usable`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
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

        coordinator.runBootstrapCycle("foreground")

        assertEquals(0, repository.bootstrapCalls)
        assertTrue(logs.any { it.contains("reason=not_needed") })
        coordinator.shutdown()
    }

    @Test
    fun `114 sync event gap can force bootstrap even when normal foreground bootstrap is not needed`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            nextDrainSummary = emptySummary().copy(manualFullSyncRequired = true)
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
            debounceMs = 1L
        )

        runCurrent()
        coordinator.runSyncEventDrainCycle("test")
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.drainCalls)
        assertEquals(1, repository.bootstrapCalls)
        coordinator.shutdown()
    }

    @Test
    fun `114 foreground sync event fallback keeps draining while app stays active`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
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
            debounceMs = 1L,
            foregroundSyncEventIntervalMs = 10L
        )

        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.drainCalls = 0

        coordinator.onAppForeground()
        advanceTimeBy(12L)
        advanceUntilIdle()

        assertTrue(repository.drainCalls >= 1)
        coordinator.shutdown()
    }

    @Test
    fun `132 guarded auto push failure path does not touch repository or publish outcome`() = runTest {
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

        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(null, tracker.lastOutcome.value)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `043 bootstrap pull is process scoped by user and retry guard`() = runTest {
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
            bootstrapRetryGuardMs = Long.MAX_VALUE
        )

        coordinator.runBootstrapCycle("first")
        coordinator.runBootstrapCycle("second")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.pushCalls)
        coordinator.shutdown()
    }

    @Test
    fun `132 network available does not schedule automatic push while guard is active`() = runTest {
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
            bootstrapRetryGuardMs = Long.MAX_VALUE
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onNetworkAvailable()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(0, repository.pushCalls)
        assertTrue(repository.bootstrapCalls in 0..1)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `132 generic local catalog change pushes when baseline is usable and pending work exists`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
        }
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

    @Test
    fun `132 local catalog change is blocked when bootstrap is required`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(0, repository.pushCalls)
        assertTrue(logs.any { it.contains("policy=bootstrap_required") })
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `132 local catalog change is skipped when no pending catalog work exists`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = false
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(0, repository.pushCalls)
        assertTrue(logs.any { it.contains("reason=no_pending_catalog_work") })
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    private class FakeCatalogAutoSyncRepository043 : CatalogAutoSyncRepository {
        var pushCalls = 0
        var quickWithEventsCalls = 0
        var drainCalls = 0
        var bootstrapCalls = 0
        var shouldBootstrap = true
        var hasPendingWork = true
        var nextQuickSummary: CatalogSyncSummary = emptySummary(pushedProducts = 1, pushedProductPrices = 1)
        var nextDrainSummary: CatalogSyncSummary = emptySummary(pulledProducts = 1)
        var failQuick: Throwable? = null

        override suspend fun shouldRunCatalogBootstrap(ownerUserId: String): Boolean = shouldBootstrap

        override suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = hasPendingWork

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
            progressReporter: CatalogSyncProgressReporter,
            sessionRemote: SessionBackupRemoteDataSource?
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
            progressReporter: CatalogSyncProgressReporter,
            sessionRemote: SessionBackupRemoteDataSource?
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

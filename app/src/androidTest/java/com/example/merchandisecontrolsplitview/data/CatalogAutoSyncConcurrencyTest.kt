package com.example.merchandisecontrolsplitview.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic, local instrumentation coverage of the production coordinator and
 * authorization gate on actual IO/Default dispatchers. No transport or account
 * configuration is read by these fixtures.
 */
@RunWith(AndroidJUnit4::class)
class CatalogAutoSyncConcurrencyTest {

    @Test
    fun concurrentSignalsReserveAuthorizationFlightAndDeferredDrainChecksRevocation(): Unit = runBlocking {
        val fixture = Fixture(
            blockFirstStatus = true,
            blockFirstDrain = true,
            subsequentStatus = "revoked"
        )
        try {
            val admitted = async(Dispatchers.IO) {
                fixture.coordinator.runSyncEventDrainCycle("foreground")
            }
            withTimeout(TIMEOUT_MS) { fixture.deviceRemote.firstStatusStarted.await() }

            withTimeout(TIMEOUT_MS) {
                coroutineScope {
                    (0 until BURST_SIGNALS).map { index ->
                        async(Dispatchers.Default) { fixture.runCycle(index % 3) }
                    }.awaitAll()
                }
            }

            // Includes at least one real busy-retry deadline for each path.
            // This is a concurrency probe, not a virtual-time polling benchmark.
            delay(BUSY_WINDOW_MS)
            assertEquals("busy signals must add no status RPC", 1, fixture.deviceRemote.statusCalls.get())
            assertEquals("authorization must precede all repository work", 0, fixture.repository.totalWorkCalls())
            assertTrue(
                "a real deferred retry must have run while authorization was suspended",
                fixture.logs.any { it.contains("sync_busy") && it.contains("retry_after_busy") }
            )
            assertFlightReserved(fixture.tracker)

            fixture.deviceRemote.releaseFirstStatus.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.repository.firstDrainStarted.await() }
            // The first admitted drain has already consumed the original busy
            // signals. A fresh signal during its suspended repository work
            // must remain pending for a later authorized drain.
            fixture.coordinator.runSyncEventDrainCycle("realtime_signal")
            fixture.repository.releaseFirstDrain.complete(Unit)
            withTimeout(TIMEOUT_MS) { admitted.await() }
            withTimeout(TIMEOUT_MS) { fixture.deviceRemote.deferredDrainChecked.await() }
            withTimeout(TIMEOUT_MS) {
                while (fixture.logs.none {
                        it.contains("cycle=sync_events_drain outcome=blocked_by_device_status") &&
                            it.contains("status=revoked")
                    }) {
                    delay(5L)
                }
            }

            assertEquals(1, fixture.repository.drainCalls.get())
            assertEquals("revocation must block every deferred remote operation", 1, fixture.repository.totalWorkCalls())
            assertTrue("deferred drain must bypass the cached active result", fixture.deviceRemote.statusCalls.get() >= 2)
            assertEquals(setOf(SHOP_A), fixture.deviceRemote.checkedShops.toSet())
            println(
                "sync_efficiency_runtime burst=$BURST_SIGNALS heldStatusMs=$BUSY_WINDOW_MS " +
                    "statusCallsWhileBusy=1 admittedWork=1 deferredDrain=revoked"
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun backgroundOfflineAndSignedOutTriggersDoNotRequestDeviceStatus(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.coordinator.onAppBackground()
            coroutineScope {
                (0 until 3).map { cycle ->
                    async(Dispatchers.Default) { fixture.runCycle(cycle) }
                }.awaitAll()
            }
            assertEquals(0, fixture.deviceRemote.statusCalls.get())
            assertEquals(0, fixture.repository.totalWorkCalls())

            fixture.tracker.updateNetworkAvailability(false)
            fixture.coordinator.onAppForeground()
            fixture.coordinator.runSyncEventDrainCycle("foreground")
            assertEquals("unvalidated network must gate event polling", 0, fixture.deviceRemote.statusCalls.get())

            fixture.auth.value = AuthState.SignedOut
            coroutineScope {
                (0 until 3).map { cycle ->
                    async(Dispatchers.IO) { fixture.runCycle(cycle) }
                }.awaitAll()
            }
            assertEquals(0, fixture.deviceRemote.statusCalls.get())
            assertEquals(0, fixture.repository.totalWorkCalls())
            assertFlightReleased(fixture.tracker)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationOfSlowAuthorizationReleasesEachReservedCycle(): Unit = runBlocking {
        for (cycle in 0 until 3) {
            val fixture = Fixture(blockFirstStatus = true)
            try {
                val admitted = async(Dispatchers.IO) { fixture.runCycle(cycle) }
                withTimeout(TIMEOUT_MS) { fixture.deviceRemote.firstStatusStarted.await() }
                assertFlightReserved(fixture.tracker)

                withTimeout(TIMEOUT_MS) { admitted.cancelAndJoin() }

                assertEquals(1, fixture.deviceRemote.statusCalls.get())
                assertEquals(0, fixture.repository.totalWorkCalls())
                assertFlightReleased(fixture.tracker)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun logoutAccountAndShopChangesDuringAuthorizationRejectStaleWork(): Unit = runBlocking {
        for (transition in 0 until 3) {
            val fixture = Fixture(blockFirstStatus = true)
            try {
                val admitted = async(Dispatchers.IO) {
                    fixture.coordinator.runSyncEventDrainCycle("foreground")
                }
                withTimeout(TIMEOUT_MS) { fixture.deviceRemote.firstStatusStarted.await() }
                when (transition) {
                    0 -> fixture.auth.value = AuthState.SignedOut
                    1 -> fixture.auth.value = AuthState.SignedIn(OWNER_B, "other@example.test")
                    2 -> fixture.selectedShop.set(selectedShop(SHOP_B))
                }
                fixture.deviceRemote.releaseFirstStatus.complete(Unit)
                withTimeout(TIMEOUT_MS) { admitted.await() }

                assertEquals(1, fixture.deviceRemote.statusCalls.get())
                assertEquals("stale authorization cannot admit repository work", 0, fixture.repository.totalWorkCalls())
                assertFlightReleased(fixture.tracker)
            } finally {
                fixture.close()
            }
        }
    }

    private fun assertFlightReserved(tracker: CatalogSyncStateTracker) {
        val acquired = tracker.tryBegin(CatalogSyncFlightOwner.MANUAL)
        if (acquired) tracker.finish(CatalogSyncFlightOwner.MANUAL)
        assertFalse("slow device status must already own the single flight", acquired)
    }

    private fun assertFlightReleased(tracker: CatalogSyncStateTracker) {
        val acquired = tracker.tryBegin(CatalogSyncFlightOwner.MANUAL)
        if (acquired) tracker.finish(CatalogSyncFlightOwner.MANUAL)
        assertTrue("terminal path must release the single flight", acquired)
    }

    private class Fixture(
        blockFirstStatus: Boolean = false,
        blockFirstDrain: Boolean = false,
        subsequentStatus: String = "active"
    ) {
        val repository = LocalRepository(blockFirstDrain)
        val deviceRemote = LocalDeviceRemote(blockFirstStatus, subsequentStatus)
        val auth = kotlinx.coroutines.flow.MutableStateFlow<AuthState>(
            AuthState.SignedIn(OWNER_A, "synthetic@example.test")
        )
        val selectedShop = AtomicReference(selectedShop(SHOP_A))
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(
                task126ActiveOwnerStoreScope(OWNER_A, selectedShop.get())
            )
        ).apply { updateNetworkAvailability(true) }
        val logs = ConcurrentLinkedQueue<String>()
        private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = LocalCatalogRemote(),
            priceRemote = LocalPriceRemote(),
            syncEventRemote = LocalSyncEventRemote(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote),
            authFlow = auth,
            selectedShopProvider = selectedShop::get,
            syncStateTracker = tracker,
            scope = runtimeScope,
            // Direct production entry points and their real busy retries are
            // exercised; initial auth/debounce/poll ticks stay isolated.
            debounceMs = Long.MAX_VALUE,
            foregroundSyncEventIntervalMs = 0L,
            logger = { logs.add(it) }
        )

        suspend fun runCycle(cycle: Int) {
            when (cycle) {
                0 -> coordinator.runPushCycle("local_catalog_commit")
                1 -> coordinator.runBootstrapCycle("foreground")
                2 -> coordinator.runSyncEventDrainCycle("realtime_signal")
                else -> error("unknown synthetic cycle")
            }
        }

        suspend fun close() {
            releaseStatusForCleanup()
            repository.releaseFirstDrain.complete(Unit)
            coordinator.shutdown()
            runtimeScope.coroutineContext[Job]?.cancelAndJoin()
        }

        private fun releaseStatusForCleanup() {
            deviceRemote.releaseFirstStatus.complete(Unit)
        }
    }

    private class LocalRepository(
        private val blockFirstDrain: Boolean
    ) : CatalogAutoSyncRepository {
        val pushCalls = AtomicInteger()
        val drainCalls = AtomicInteger()
        val bootstrapCalls = AtomicInteger()
        val firstDrainStarted = CompletableDeferred<Unit>()
        val releaseFirstDrain = CompletableDeferred<Unit>()

        fun totalWorkCalls(): Int = pushCalls.get() + drainCalls.get() + bootstrapCalls.get()

        override suspend fun shouldRunCatalogBootstrap(ownerUserId: String): Boolean = false
        override suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = true

        override suspend fun pushDirtyCatalogDeltaToRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            pushCalls.incrementAndGet()
            return Result.success(emptySummary())
        }

        override suspend fun syncCatalogQuickWithEvents(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            syncEventRemote: SyncEventRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter,
            sessionRemote: SessionBackupRemoteDataSource?
        ): Result<CatalogSyncSummary> =
            pushDirtyCatalogDeltaToRemote(remote, priceRemote, ownerUserId, progressReporter)

        override suspend fun drainSyncEventsFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            syncEventRemote: SyncEventRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter,
            sessionRemote: SessionBackupRemoteDataSource?
        ): Result<CatalogSyncSummary> {
            if (drainCalls.incrementAndGet() == 1) {
                firstDrainStarted.complete(Unit)
                if (blockFirstDrain) releaseFirstDrain.await()
            }
            return Result.success(emptySummary())
        }

        override suspend fun pullCatalogBootstrapFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            bootstrapCalls.incrementAndGet()
            return Result.success(emptySummary())
        }
    }

    private class LocalDeviceRemote(
        private val blockFirstStatus: Boolean,
        private val subsequentStatus: String
    ) : ShopDeviceRegistrationRemote {
        override val isConfigured = true
        val statusCalls = AtomicInteger()
        val checkedShops = ConcurrentLinkedQueue<String>()
        val firstStatusStarted = CompletableDeferred<Unit>()
        val releaseFirstStatus = CompletableDeferred<Unit>()
        val deferredDrainChecked = CompletableDeferred<Unit>()

        override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
            error("registration is outside this local authorization probe")

        override suspend fun shopDeviceStatusForShop(
            shopId: String,
            reason: String
        ): Result<ShopDeviceAuthorizationSnapshot> {
            checkedShops.add(shopId)
            return currentOwnerDeviceStatus(reason)
        }

        override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> {
            val call = statusCalls.incrementAndGet()
            if (call == 1) {
                firstStatusStarted.complete(Unit)
                if (blockFirstStatus) releaseFirstStatus.await()
            } else if (reason.startsWith("sync_events_drain:")) {
                deferredDrainChecked.complete(Unit)
            }
            val status = if (call == 1) "active" else subsequentStatus
            return Result.success(
                ShopDeviceAuthorizationSnapshot(
                    status = status,
                    code = if (status == "active") "success" else status,
                    canWrite = status == "active",
                    serverTime = "2026-10-02T00:00:00Z",
                    lastSeenAt = "2026-10-02T00:00:00Z",
                    reasonCode = status,
                    recommendedAction = if (status == "active") "allow" else "contact_shop_admin",
                    checkedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    private class LocalCatalogRemote : CatalogRemoteDataSource {
        override val isConfigured = true
        override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = unexpectedTransport()
        override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = unexpectedTransport()
        override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> = unexpectedTransport()
        override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = unexpectedTransport()
        override suspend fun fetchCatalogByIds(
            supplierIds: Set<String>,
            categoryIds: Set<String>,
            productIds: Set<String>
        ): Result<InventoryCatalogFetchBundle> = unexpectedTransport()
        override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = unexpectedTransport()
        override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = unexpectedTransport()
        override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = unexpectedTransport()
    }

    private class LocalPriceRemote : ProductPriceRemoteDataSource {
        override val isConfigured = true
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> = unexpectedTransport()
        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = unexpectedTransport()
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            unexpectedTransport()
    }

    private class LocalSyncEventRemote : SyncEventRemoteDataSource {
        override val isConfigured = true
        override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> = unexpectedTransport()
        override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> = unexpectedTransport()
        override suspend fun fetchSyncEventsAfter(
            ownerUserId: String,
            storeId: String?,
            afterId: Long,
            limit: Long
        ): Result<List<SyncEventRemoteRow>> = unexpectedTransport()
    }

    private companion object {
        const val OWNER_A = "00000000-0000-4000-8000-000000000201"
        const val OWNER_B = "00000000-0000-4000-8000-000000000202"
        const val SHOP_A = "00000000-0000-4000-8000-000000000203"
        const val SHOP_B = "00000000-0000-4000-8000-000000000204"
        const val BURST_SIGNALS = 48
        const val BUSY_WINDOW_MS = 1_250L
        const val TIMEOUT_MS = 10_000L

        fun selectedShop(shopId: String): SelectedShop = SelectedShop(
            shopId = shopId,
            code = "synthetic-shop",
            name = "Synthetic shop",
            role = "owner",
            status = "active",
            canWrite = true
        )

        fun emptySummary(): CatalogSyncSummary = CatalogSyncSummary(
            pushedSuppliers = 0,
            pushedCategories = 0,
            pushedProducts = 0,
            pulledSuppliers = 0,
            pulledCategories = 0,
            pulledProducts = 0
        )

        fun <T> unexpectedTransport(): Result<T> = error("transport must remain unused in the local fixture")
    }
}

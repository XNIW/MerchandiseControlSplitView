package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Task126BusinessDataScopeRuntimeGuardTest {

    @Test
    fun `diagnostic quiet stamp only observes unchanged idle generation`() = runTest {
        val tracker = trackerReady(ownerScope(OWNER_A, SHOP_A))
        val stamp = requireNotNull(tracker.captureDiagnosticQuietStamp())
        assertTrue(tracker.isDiagnosticQuietStampCurrent(stamp))
        tracker.withBusinessDataScopeTransition {
            assertTrue(tracker.captureDiagnosticQuietStamp() == null)
            assertFalse(tracker.isDiagnosticQuietStampCurrent(stamp))
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(ownerScope(OWNER_B, SHOP_B)))
        }
        tracker.withBusinessDataScopeTransition {
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A)))
        }
        assertFalse(tracker.isDiagnosticQuietStampCurrent(stamp))
        assertTrue(tracker.captureDiagnosticQuietStamp() != null)
        assertTrue(tracker.allowsBusinessDataScope(OWNER_A, selectedShop(SHOP_A)))
    }

    @Test
    fun `diagnostic quiet stamp waits real registered flight finally boundary`() = runTest {
        val tracker = trackerReady(ownerScope(OWNER_A, SHOP_A))
        val stamp = requireNotNull(tracker.captureDiagnosticQuietStamp())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flight = backgroundScope.async {
            runCatching {
                tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                    try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable) { release.await() } }
                }
            }
        }
        entered.await()
        assertTrue(tracker.captureDiagnosticQuietStamp() == null)
        assertFalse(tracker.isDiagnosticQuietStampCurrent(stamp))
        flight.cancel()
        testScheduler.runCurrent()
        assertTrue(tracker.captureDiagnosticQuietStamp() == null)
        release.complete(Unit)
        flight.join()
        assertTrue(tracker.captureDiagnosticQuietStamp() != null)
        assertTrue(tracker.isDiagnosticQuietStampCurrent(stamp))
    }

    @Test
    fun `transition waits non cooperative flight and rejects new outbound admission`() = runTest {
        val scopeA = ownerScope(OWNER_A, SHOP_A)
        val tracker = trackerReady(scopeA)
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        var lateOutboundCalls = 0

        val oldFlight = async {
            runCatching {
                tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                    remoteStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRemote.await() }
                    tracker.requireCurrentBusinessDataScope()
                }
            }
        }
        remoteStarted.await()

        val transition = async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(
                    Task126BusinessDataScopeState(
                        status = Task126BusinessDataScopeStatus.READY,
                        boundScope = ownerScope(OWNER_B, SHOP_B)
                    )
                )
            }
        }
        testScheduler.runCurrent()
        assertFalse(transition.isCompleted)

        val lateAdmission = runCatching {
            tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                lateOutboundCalls++
            }
        }
        assertTrue(lateAdmission.exceptionOrNull() is Task126BusinessDataScopeChangedException)
        assertEquals(0, lateOutboundCalls)

        releaseRemote.complete(Unit)
        assertTrue(oldFlight.await().exceptionOrNull() is Task126BusinessDataScopeChangedException)
        transition.await()
        assertTrue(tracker.allowsBusinessDataScope(OWNER_B, selectedShop(SHOP_B)))
    }

    @Test
    fun `cancelled transition keeps admission closed until quiescence and publishes B`() = runTest {
        val tracker = trackerReady(ownerScope(OWNER_A, SHOP_A))
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        var transitionApplied = false
        val oldFlight = backgroundScope.async {
            runCatching {
                tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                    remoteStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRemote.await() }
                    tracker.requireCurrentBusinessDataScope()
                }
            }
        }
        remoteStarted.await()

        val transition = backgroundScope.async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(
                    Task126BusinessDataScopeState.ready(ownerScope(OWNER_B, SHOP_B))
                )
                transitionApplied = true
            }
        }
        testScheduler.runCurrent()
        transition.cancel()
        testScheduler.runCurrent()

        assertFalse(tracker.allowsBusinessDataScope(OWNER_A, selectedShop(SHOP_A)))
        assertFalse(tracker.allowsBusinessDataScope(OWNER_B, selectedShop(SHOP_B)))

        releaseRemote.complete(Unit)
        oldFlight.await()
        transition.join()

        assertTrue(transitionApplied)
        assertTrue(tracker.allowsBusinessDataScope(OWNER_B, selectedShop(SHOP_B)))
    }

    @Test
    fun `transition invoked inside registered flight fails fast`() = runTest {
        val tracker = trackerReady(ownerScope(OWNER_A, SHOP_A))

        val result = runCatching {
            tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                tracker.withBusinessDataScopeTransition { Unit }
            }
        }

        assertTrue(result.exceptionOrNull() is Task126BusinessDataScopeChangedException)
        assertTrue(tracker.allowsBusinessDataScope(OWNER_A, selectedShop(SHOP_A)))
    }

    @Test
    fun `143 local lease survives cloud checking but never admits outbound work`() = runTest {
        val scope = ownerScope(OWNER_A, SHOP_A)
        val tracker = Task126BusinessDataScopeFlightGate(Task126BusinessDataScopeState.ready(scope))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val local = async {
            tracker.withLocalBusinessDataScopeFlight {
                entered.complete(Unit); release.await()
                tracker.requireCurrentBusinessDataScope()
                assertTrue(runCatching { tracker.requireCloudBusinessDataScope() }.exceptionOrNull() is Task126BusinessDataScopeChangedException)
                143
            }
        }
        entered.await()
        tracker.updateState(Task126BusinessDataScopeState(
            Task126BusinessDataScopeStatus.CHECKING, boundScope = scope, localAccessScope = scope))
        assertFalse(tracker.allowsBusinessDataScope(OWNER_A, selectedShop(SHOP_A)))
        assertTrue(runCatching { tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {} }.isFailure)
        release.complete(Unit)
        assertEquals(143, local.await())
    }

    @Test
    fun `143 local lease is invalidated by scope or permission change`() = runTest {
        for (next in listOf(
            Task126BusinessDataScopeState.ready(ownerScope(OWNER_B, SHOP_B)),
            Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A)).copy(localWritesAllowed = false))) {
            val tracker = trackerReady(ownerScope(OWNER_A, SHOP_A))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val local = async {
                runCatching {
                    tracker.withLocalBusinessDataScopeFlight {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        tracker.requireCurrentBusinessDataScope()
                    }
                }
            }
            entered.await()
            tracker.updateBusinessDataScopeState(next)
            release.complete(Unit)
            assertTrue(local.await().exceptionOrNull() is Task126BusinessDataScopeChangedException)
        }
    }

    @Test
    fun `143 readonly or mismatched local provenance cannot authorize save`() = runTest {
        val scope = ownerScope(OWNER_A, SHOP_A)
        for (state in listOf(
            Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.CHECKING, boundScope=scope,
                localAccessScope=ownerScope(OWNER_B, SHOP_A)),
            Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.CHECKING, boundScope=scope,
                localAccessScope=scope, localWritesAllowed=false))) {
            val tracker = CatalogSyncStateTracker(state)
            var calls = 0
            assertTrue(runCatching { tracker.withLocalBusinessDataScopeFlight { calls++ } }.isFailure)
            assertEquals(0, calls)
        }
    }

    @Test
    fun `recovery publication rejects permission and scope ABA during repository qualification`() = runTest {
        for (changed in listOf(
            Task126BusinessDataScopeState.ready(ownerScope(OWNER_B, SHOP_B)),
            Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A)).copy(localWritesAllowed = false))) {
            val initial = Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A))
            val tracker = CatalogSyncStateTracker(initial)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resolving = async {
                tracker.resolveAndPublishBusinessDataScope(stillAuthorized = { true }) {
                    entered.complete(Unit); release.await()
                    initial.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                        localAccessScope = initial.boundScope, errorCode = "sync_recovery_required")
                }
            }
            entered.await()
            tracker.updateBusinessDataScopeState(changed)
            tracker.updateBusinessDataScopeState(initial)
            release.complete(Unit)
            assertFalse(resolving.await())
            assertEquals(initial, tracker.businessDataScopeState.value)
        }
    }

    @Test
    fun `cancelled repository qualification never publishes recovery state`() = runTest {
        val initial = Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A))
        val tracker = CatalogSyncStateTracker(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolving = async {
            tracker.resolveAndPublishBusinessDataScope(stillAuthorized = { true }) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                initial.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "sync_recovery_required")
            }
        }
        entered.await(); resolving.cancel(); release.complete(Unit)
        resolving.join()
        assertTrue(resolving.isCancelled)
        assertEquals(initial, tracker.businessDataScopeState.value)
        tracker.withBusinessDataScopeTransition { } // The cancelled read released the boundary mutex.
    }

    @Test
    fun `authority revoked while repository qualification suspends cannot publish`() = runTest {
        val initial = Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A))
        val tracker = CatalogSyncStateTracker(initial)
        var authorized = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolving = async {
            tracker.resolveAndPublishBusinessDataScope(stillAuthorized = { authorized }) {
                entered.complete(Unit); release.await()
                initial.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    localAccessScope = initial.boundScope, errorCode = "sync_recovery_required")
            }
        }
        entered.await(); authorized = false; release.complete(Unit)
        assertFalse(resolving.await())
        assertEquals(initial, tracker.businessDataScopeState.value)
    }

    private fun trackerReady(scope: Task126OwnerStoreScope): CatalogSyncStateTracker =
        CatalogSyncStateTracker(
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.READY,
                boundScope = scope
            )
        )

    private fun ownerScope(ownerUserId: String, shopId: String): Task126OwnerStoreScope =
        Task126OwnerStoreScope(
            ownerHash = task126OwnerHash(ownerUserId),
            storeId = "shop:$shopId",
            localStoreId = null
        )

    private fun selectedShop(shopId: String): SelectedShop =
        SelectedShop(
            shopId = shopId,
            code = shopId,
            name = shopId,
            role = "owner",
            status = "active",
            canWrite = true
        )

    private companion object {
        const val OWNER_A = "00000000-0000-4000-8000-000000000126"
        const val OWNER_B = "00000000-0000-4000-8000-000000000226"
        const val SHOP_A = "task126-shop-a"
        const val SHOP_B = "task126-shop-b"
    }
}

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

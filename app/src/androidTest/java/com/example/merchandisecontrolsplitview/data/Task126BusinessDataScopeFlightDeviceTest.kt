package com.example.merchandisecontrolsplitview.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level counterpart of the JVM lease test. It exercises the actual
 * Android coroutine/runtime boundary, without a signed-in account, storage
 * mutation or outbound network call.
 */
@RunWith(AndroidJUnit4::class)
class Task126BusinessDataScopeFlightDeviceTest {

    @Test
    fun accountSwitchQuiescesOldFlightBeforeAllowingNewScope() = runBlocking {
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(ownerScope(OWNER_A, SHOP_A))
        )
        val oldFlightStarted = CompletableDeferred<Unit>()
        val releaseOldFlight = CompletableDeferred<Unit>()
        var staleOutboundCalls = 0

        try {
            val oldFlight = async(Dispatchers.Default) {
                runCatching {
                    tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                        oldFlightStarted.complete(Unit)
                        withContext(NonCancellable) { releaseOldFlight.await() }
                        tracker.requireCurrentBusinessDataScope()
                    }
                }
            }
            withTimeout(2_000L) { oldFlightStarted.await() }

            val transition = async(Dispatchers.Default) {
                tracker.withBusinessDataScopeTransition {
                    tracker.updateBusinessDataScopeState(
                        Task126BusinessDataScopeState.ready(ownerScope(OWNER_B, SHOP_B))
                    )
                }
            }
            withTimeout(2_000L) {
                while (tracker.allowsBusinessDataScope(OWNER_A, selectedShop(SHOP_A))) {
                    delay(10L)
                }
            }
            assertFalse(transition.isCompleted)

            val staleAdmission = runCatching {
                tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                    staleOutboundCalls += 1
                }
            }
            assertTrue(staleAdmission.exceptionOrNull() is Task126BusinessDataScopeChangedException)
            assertEquals(0, staleOutboundCalls)

            releaseOldFlight.complete(Unit)
            assertTrue(
                withTimeout(2_000L) {
                    oldFlight.await().exceptionOrNull() is Task126BusinessDataScopeChangedException
                }
            )
            withTimeout(2_000L) { transition.await() }
            assertTrue(tracker.allowsBusinessDataScope(OWNER_B, selectedShop(SHOP_B)))
        } finally {
            // A failed assertion or timeout must not strand the intentionally
            // non-cooperative test flight on an instrumentation worker.
            releaseOldFlight.complete(Unit)
        }
    }

    private fun ownerScope(ownerUserId: String, shopId: String): Task126OwnerStoreScope =
        Task126OwnerStoreScope(
            ownerHash = task126OwnerHash(ownerUserId),
            storeId = "shop:$shopId",
            localStoreId = null
        )

    private fun selectedShop(shopId: String): SelectedShop = SelectedShop(
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

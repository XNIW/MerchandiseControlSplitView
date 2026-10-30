package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopWriteContextEpochTest {
    @Test
    fun alreadyActiveNoOpPreservesCapturedWriteContextGuard() = runTest {
        val fixture = readyFixture()
        val capturedContext = fixture.repository.state.value
        val capturedEpoch = fixture.repository.diagnosticShopEpoch()
        val capturedRequestFence = rawRequestFence(fixture.repository)
        fixture.store.effects.clear()

        repeat(4) {
            fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, "active", true)
        }

        assertEquals(capturedRequestFence + 4L, rawRequestFence(fixture.repository))
        assertEquals(List(4) { "device_denied:false" }, fixture.store.effects)
        assertFalse(fixture.store.isDeviceDenied(OWNER, SHOP_A, DEVICE))
        assertEquals(capturedContext, fixture.repository.state.value)
        assertTrue("Identical ordinary active confirmations must retain the captured write-context guard",
            fixture.repository.state.value == capturedContext &&
                fixture.repository.diagnosticShopEpoch() == capturedEpoch)
    }

    @Test
    fun deviceCallbacksRetainRequestFenceAndDiscardHeldOldRefreshResponse() = runTest {
        for (status in listOf("active", "revoked", "retired", "suspended")) {
            val fixture = readyFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.remote.fetch = {
                entered.complete(Unit)
                release.await()
                Result.success(listOf(shop("late-response-must-not-be-selected")))
            }
            val refresh = launch { fixture.repository.refresh(OWNER) }
            val observed = try {
                entered.await()
                val fenceBeforeCallback = rawRequestFence(fixture.repository)
                fixture.store.effects.clear()
                fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, status, status == "active")
                assertEquals(fenceBeforeCallback + 1L, rawRequestFence(fixture.repository))
                CallbackObservation(fixture.repository.state.value, rawRequestFence(fixture.repository),
                    fixture.repository.diagnosticShopEpoch(), fixture.store.effects.toList())
            } finally {
                release.complete(Unit)
                refresh.join()
            }

            assertEquals("The original request fence must discard its superseded response", observed.context, fixture.repository.state.value)
            assertEquals(observed.requestFence, rawRequestFence(fixture.repository))
            assertEquals(observed.writeEpoch, fixture.repository.diagnosticShopEpoch())
            assertEquals(observed.effects, fixture.store.effects)
            assertEquals(SHOP_A, fixture.repository.state.value.activeShopId)
            assertEquals(status != "active", fixture.store.isDeviceDenied(OWNER, SHOP_A, DEVICE))
        }
    }

    @Test
    fun denyRestoreAbaInvalidatesOldWriteContextForEveryDeniedStatus() = runTest {
        for (status in listOf("revoked", "retired", "suspended")) {
            val fixture = readyFixture()
            val original = fixture.repository.state.value
            val originalEpoch = fixture.repository.diagnosticShopEpoch()
            val originalFence = rawRequestFence(fixture.repository)
            fixture.store.effects.clear()

            fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, status, false)
            val deniedEpoch = fixture.repository.diagnosticShopEpoch()
            assertTrue(deniedEpoch > originalEpoch)
            assertFalse(fixture.repository.state.value.localAccessAllowed)
            assertTrue(fixture.store.isDeviceDenied(OWNER, SHOP_A, DEVICE))

            fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, "active", true)

            assertEquals(original, fixture.repository.state.value)
            assertTrue(fixture.repository.diagnosticShopEpoch() > deniedEpoch)
            assertFalse(fixture.repository.state.value == original &&
                fixture.repository.diagnosticShopEpoch() == originalEpoch)
            assertEquals(originalFence + 2L, rawRequestFence(fixture.repository))
            assertFalse(fixture.store.isDeviceDenied(OWNER, SHOP_A, DEVICE))
            assertEquals(listOf("device_denied:true", "device_denied:false"), fixture.store.effects)
        }
    }

    @Test
    fun durableDenialRestorationInvalidatesEvenWhenFullContextStaysEqual() = runTest {
        val fixture = readyFixture()
        // The durable bit is deliberately out of line with the already-published value.
        // The callback must account for the durable transition as well as value equality.
        fixture.store.seedDeviceDenied(OWNER, SHOP_A, DEVICE, true)
        val original = fixture.repository.state.value
        val epoch = fixture.repository.diagnosticShopEpoch()
        val requestFence = rawRequestFence(fixture.repository)
        fixture.store.effects.clear()

        fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, "active", true)

        assertEquals(original, fixture.repository.state.value)
        assertTrue(fixture.repository.diagnosticShopEpoch() > epoch)
        assertEquals(requestFence + 1L, rawRequestFence(fixture.repository))
        assertFalse(fixture.store.isDeviceDenied(OWNER, SHOP_A, DEVICE))
        assertEquals(listOf("device_denied:false"), fixture.store.effects)
    }

    @Test
    fun acceptedOwnerShopRefreshClearAndLocalRevokeStillInvalidateWriteContext() = runTest {
        for (action in listOf("owner", "shop_aba", "same_shop", "refresh", "clear", "local_revoke", "permission", "membership")) {
            val fixture = readyFixture()
            val original = fixture.repository.state.value
            val epoch = fixture.repository.diagnosticShopEpoch()
            val requestFence = rawRequestFence(fixture.repository)
            var expectedFenceAdvances = 1L

            when (action) {
                "owner" -> {
                    fixture.owner = FOREIGN_OWNER
                    fixture.repository.refresh(FOREIGN_OWNER)
                }
                "shop_aba" -> {
                    assertTrue(fixture.repository.selectShop(SHOP_B))
                    assertTrue(fixture.repository.selectShop(SHOP_A))
                    expectedFenceAdvances = 2L
                }
                "same_shop" -> assertTrue(fixture.repository.selectShop(SHOP_A))
                "refresh" -> fixture.repository.refresh(OWNER)
                "clear" -> fixture.repository.clear()
                "local_revoke" -> fixture.repository.invalidateLocalAuthorization(OWNER, SHOP_A)
                "permission" -> {
                    fixture.remote.fetch = { Result.success(listOf(shop(SHOP_A).copy(canWrite = false), shop(SHOP_B))) }
                    fixture.repository.refresh(OWNER)
                    assertEquals(SHOP_A, fixture.repository.state.value.activeShopId)
                    assertFalse(requireNotNull(fixture.repository.state.value.selectedShop).canWrite)
                    assertTrue(fixture.repository.state.value.localAccessAllowed)
                }
                "membership" -> {
                    fixture.remote.fetch = { Result.success(listOf(shop(SHOP_A).copy(membershipStatus = "revoked"), shop(SHOP_B))) }
                    fixture.repository.refresh(OWNER)
                    assertEquals(SHOP_B, fixture.repository.state.value.activeShopId)
                    assertFalse(fixture.repository.state.value.linkedShops.single { it.shopId == SHOP_A }.canBeSelected)
                    assertTrue(fixture.repository.state.value.localAccessAllowed)
                }
                else -> throw AssertionError("Unknown static test action")
            }

            assertEquals(requestFence + expectedFenceAdvances, rawRequestFence(fixture.repository))
            assertTrue("Accepted $action remains an invalidator", fixture.repository.diagnosticShopEpoch() > epoch)
            assertFalse(fixture.repository.state.value == original && fixture.repository.diagnosticShopEpoch() == epoch)
        }
    }

    @Test
    fun foreignAndRejectedCallbacksCannotAdvanceEitherEpochOrMutateState() = runTest {
        val fixture = readyFixture()
        val original = fixture.repository.state.value
        val epoch = fixture.repository.diagnosticShopEpoch()
        val requestFence = rawRequestFence(fixture.repository)
        val fetchCount = fixture.remote.fetchCount
        fixture.store.effects.clear()

        fixture.repository.refresh(FOREIGN_OWNER)
        assertFalse(fixture.repository.selectShop("not-linked"))
        fixture.repository.invalidateLocalAuthorization(FOREIGN_OWNER, SHOP_A)
        fixture.repository.invalidateLocalAuthorization(OWNER, "foreign-shop")
        fixture.repository.recordDeviceAuthorization(FOREIGN_OWNER, SHOP_A, DEVICE, "revoked", false)
        fixture.repository.recordDeviceAuthorization(OWNER, "foreign-shop", DEVICE, "revoked", false)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, "foreign-device", "revoked", false)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, "unknown", false)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, "active", false)
        fixture.owner = FOREIGN_OWNER
        assertFalse(fixture.repository.selectShop(SHOP_A))
        fixture.repository.invalidateLocalAuthorization(OWNER, SHOP_A)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP_A, DEVICE, "active", true)

        assertEquals(original, fixture.repository.state.value)
        assertEquals(epoch, fixture.repository.diagnosticShopEpoch())
        assertEquals(requestFence, rawRequestFence(fixture.repository))
        assertEquals(fetchCount, fixture.remote.fetchCount)
        assertTrue(fixture.store.effects.isEmpty())
        assertFalse(fixture.store.isDeviceDenied(OWNER, SHOP_A, DEVICE))
    }

    private suspend fun readyFixture(): Fixture {
        val fixture = Fixture()
        fixture.repository.refresh(OWNER)
        assertEquals(SHOP_A, fixture.repository.state.value.activeShopId)
        assertTrue(fixture.repository.state.value.syncAllowed)
        assertTrue(fixture.repository.state.value.localAccessAllowed)
        return fixture
    }

    private fun rawRequestFence(repository: ShopContextRepository): Long {
        val field = ShopContextRepository::class.java.getDeclaredField("refreshGeneration")
        field.isAccessible = true
        return field.getLong(repository)
    }

    private data class CallbackObservation(val context: ShopContext, val requestFence: Long,
        val writeEpoch: Long, val effects: List<String>)

    private class Fixture {
        var owner = OWNER
        val store = Store()
        val remote = Remote { Result.success(listOf(shop(SHOP_A), shop(SHOP_B))) }
        val repository = ShopContextRepository(remote, store, currentOwnerUserId = { owner },
            currentDeviceIdentifier = { DEVICE })
    }

    private class Remote(var fetch: suspend () -> Result<List<LinkedShop>>) : LinkedShopRemoteDataSource {
        var fetchCount = 0
        override val isConfigured = true
        override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> {
            fetchCount += 1
            return fetch()
        }
    }

    private class Store : SelectedShopStore {
        val effects = mutableListOf<String>()
        private val selections = mutableMapOf<String, String>()
        private val confirmations = mutableMapOf<String, ShopContext>()
        private val denials = mutableMapOf<Triple<String, String?, String>, Boolean>()
        override fun getSelectedShopId(ownerUserId: String): String? = selections[ownerUserId]
        override fun setSelectedShopId(ownerUserId: String, shopId: String) {
            effects += "selected:$shopId"
            selections[ownerUserId] = shopId
        }
        override fun clearSelectedShopId(ownerUserId: String) { effects += "clear_selected"; selections.remove(ownerUserId) }
        override fun getConfirmedContext(ownerUserId: String): ShopContext? = confirmations[ownerUserId]
        override fun setConfirmedContext(ownerUserId: String, context: ShopContext) { effects += "confirmed"; confirmations[ownerUserId] = context }
        override fun clearConfirmedContext(ownerUserId: String) { effects += "clear_confirmed"; confirmations.remove(ownerUserId) }
        override fun isDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String): Boolean =
            denials[Triple(ownerUserId, shopId, deviceId)] == true
        override fun setDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String, denied: Boolean) {
            effects += "device_denied:$denied"
            seedDeviceDenied(ownerUserId, shopId, deviceId, denied)
        }
        fun seedDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String, denied: Boolean) {
            denials[Triple(ownerUserId, shopId, deviceId)] = denied
        }
    }

    private companion object {
        const val OWNER = "00000000-0000-4000-8000-000000009901"
        const val FOREIGN_OWNER = "00000000-0000-4000-8000-000000009902"
        const val SHOP_A = "00000000-0000-4000-8000-000000009911"
        const val SHOP_B = "00000000-0000-4000-8000-000000009912"
        const val DEVICE = "00000000-0000-4000-8000-000000009920"
        fun shop(id: String) = LinkedShop(id, "QA", "Write-context fixture", "shop_owner", "active", "active", "active", true, true)
    }
}

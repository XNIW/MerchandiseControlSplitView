package com.example.merchandisecontrolsplitview.data

import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopEpochDiagnosticsTest {
    @Test
    fun refreshRetainsOneIncrementAndOriginalRemoteAndStoreEffects() = runTest {
        val fixture = Fixture()

        fixture.repository.refresh(OWNER)

        assertEquals(1L, refreshFence(fixture.repository))
        assertEquals(1, fixture.remote.calls)
        assertEquals(listOf("selected:$SHOP", "confirmed"), fixture.store.effects)
        assertEquals(SHOP, fixture.repository.state.value.activeShopId)
        assertFalse(fixture.repository.state.value.isLoading)
        assertTrue(fixture.repository.state.value.syncAllowed)
    }

    @Test
    fun clearRetainsOneIncrementAndLegacyStateWithoutStoreEffects() = runTest {
        val fixture = Fixture()
        fixture.repository.refresh(OWNER)
        val epoch = refreshFence(fixture.repository)
        val effects = fixture.store.effects.toList()

        val returned: Unit = fixture.repository.clear()

        assertEquals(Unit, returned)
        assertEquals(epoch + 1L, refreshFence(fixture.repository))
        assertEquals(ShopContext.legacy(), fixture.repository.state.value)
        assertEquals(effects, fixture.store.effects)
    }

    @Test
    fun sameShopSelectionRetainsAcceptedReturnIncrementAndStoreOrder() = runTest {
        val fixture = Fixture()
        fixture.repository.refresh(OWNER)
        val before = fixture.repository.state.value
        val epoch = refreshFence(fixture.repository)
        fixture.store.effects.clear()

        assertTrue(fixture.repository.selectShop(SHOP))

        assertEquals(epoch + 1L, refreshFence(fixture.repository))
        assertEquals(before, fixture.repository.state.value)
        assertEquals(listOf("selected:$SHOP", "confirmed"), fixture.store.effects)
    }

    @Test
    fun localInvalidationRetainsOneIncrementAndDurableDenial() = runTest {
        val fixture = Fixture()
        fixture.repository.refresh(OWNER)
        val epoch = refreshFence(fixture.repository)
        fixture.store.effects.clear()

        val returned: Unit = fixture.repository.invalidateLocalAuthorization(OWNER, SHOP)

        assertEquals(Unit, returned)
        assertEquals(epoch + 1L, refreshFence(fixture.repository))
        assertEquals(listOf("clear_confirmed"), fixture.store.effects)
        assertNull(fixture.repository.state.value.selectedShop)
        assertFalse(fixture.repository.state.value.localAccessAllowed)
        assertFalse(fixture.repository.state.value.syncAllowed)
    }

    @Test
    fun deviceConfirmationRetainsBothNoOpAndDenialIncrementsAndWrites() = runTest {
        val fixture = Fixture()
        fixture.repository.refresh(OWNER)
        val original = fixture.repository.state.value
        var epoch = refreshFence(fixture.repository)
        fixture.store.effects.clear()

        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, DEVICE, "active", true)
        assertEquals(++epoch, refreshFence(fixture.repository))
        assertEquals(original, fixture.repository.state.value)
        assertEquals(listOf("device_denied:false"), fixture.store.effects)

        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, DEVICE, "revoked", false)
        assertEquals(++epoch, refreshFence(fixture.repository))
        assertFalse(fixture.repository.state.value.localAccessAllowed)
        assertEquals("device_access_rejected", fixture.repository.state.value.errorMessage)

        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, DEVICE, "active", true)
        assertEquals(++epoch, refreshFence(fixture.repository))
        assertEquals(original, fixture.repository.state.value)
        assertEquals(listOf("device_denied:false", "device_denied:true", "device_denied:false"), fixture.store.effects)
    }

    @Test
    fun originalRejectGuardsRetainZeroEpochRemoteOrStoreEffects() = runTest {
        val fixture = Fixture()
        fixture.repository.refresh(OWNER)
        val original = fixture.repository.state.value
        val epoch = refreshFence(fixture.repository)
        val remoteCalls = fixture.remote.calls
        fixture.store.effects.clear()

        fixture.repository.refresh(FOREIGN_OWNER)
        assertFalse(fixture.repository.selectShop("not-linked"))
        fixture.repository.invalidateLocalAuthorization(FOREIGN_OWNER, SHOP)
        fixture.repository.invalidateLocalAuthorization(OWNER, "foreign-shop")
        fixture.repository.recordDeviceAuthorization(FOREIGN_OWNER, SHOP, DEVICE, "revoked", false)
        fixture.repository.recordDeviceAuthorization(OWNER, "foreign-shop", DEVICE, "revoked", false)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, "foreign-device", "revoked", false)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, DEVICE, "unknown-status", false)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, DEVICE, "active", false)
        fixture.owner = FOREIGN_OWNER
        assertFalse(fixture.repository.selectShop(SHOP))
        fixture.repository.invalidateLocalAuthorization(OWNER, SHOP)
        fixture.repository.recordDeviceAuthorization(OWNER, SHOP, DEVICE, "revoked", false)

        assertEquals(epoch, refreshFence(fixture.repository))
        assertEquals(remoteCalls, fixture.remote.calls)
        assertEquals(emptyList<String>(), fixture.store.effects)
        assertEquals(original, fixture.repository.state.value)
    }

    @Test
    fun storeExceptionRemainsTheExactThrowableAfterOriginalIncrement() = runTest {
        val failures = listOf(IllegalStateException("private-store-failure"),
            AssertionError("private-store-error"), CancellationException("private-store-cancellation"))
        for (failure in failures) {
            val fixture = Fixture()
            fixture.repository.refresh(OWNER)
            val original = fixture.repository.state.value
            val epoch = refreshFence(fixture.repository)
            fixture.store.selectionFailure = failure
            fixture.store.effects.clear()

            val actual = captureFailure { fixture.repository.selectShop(SHOP) }

            assertSame(failure, actual)
            assertEquals(epoch + 1L, refreshFence(fixture.repository))
            assertEquals(original, fixture.repository.state.value)
            assertEquals(listOf("selected:$SHOP"), fixture.store.effects)
        }
    }

    @Test
    fun clearStillInvalidatesAnInFlightRefreshBeforeItsResponseArrives() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.remote.fetch = {
            entered.complete(Unit)
            release.await()
            Result.success(listOf(linkedShop()))
        }
        val job = launch { fixture.repository.refresh(OWNER) }
        entered.await()
        assertEquals(1L, refreshFence(fixture.repository))
        assertTrue(fixture.repository.state.value.isLoading)

        fixture.repository.clear()
        release.complete(Unit)
        job.join()

        assertEquals(2L, refreshFence(fixture.repository))
        assertEquals(ShopContext.legacy(), fixture.repository.state.value)
        assertEquals(emptyList<String>(), fixture.store.effects)
    }

    @Test
    fun formatterIsPureAndExposesOnlyStaticFactsAndGenerationNumbers() {
        val fixture = Fixture()
        val before = context()
        val after = before.copy(ownerUserId = FOREIGN_OWNER, isLoading = true, syncAllowed = false)
        val state = fixture.repository.state.value
        val epoch = refreshFence(fixture.repository)
        val originsAndReasons = listOf(
            "refresh" to "REQUEST_STARTED",
            "clear" to "CLEAR",
            "select_shop" to "SELECTION_ACCEPTED",
            "invalidate_local_authorization" to "LOCAL_ACCESS_REJECTED",
            "record_device_authorization" to "DEVICE_DENIED",
            "record_device_authorization" to "DEVICE_ACTIVE_WRITE"
        )
        for ((origin, reason) in originsAndReasons) {
            val line = diagnosticLine(fixture.repository, origin, reason, 41L, 42L, before, after)
            val fields = fields(line)
            assertEquals(EXPECTED_FIELDS, fields.keys)
            assertEquals(origin, fields.getValue("origin"))
            assertEquals(reason, fields.getValue("reason"))
            assertEquals("41", fields.getValue("old_generation"))
            assertEquals("42", fields.getValue("new_generation"))
            assertEquals("true", fields.getValue("context_value_changed"))
            assertEquals("true", fields.getValue("owner_changed"))
            for (secret in listOf(OWNER, FOREIGN_OWNER, SHOP, DEVICE, PRIVATE_NAME, PRIVATE_CODE, PRIVATE_ERROR)) {
                assertFalse("No context identity or display/error value may be logged", line.contains(secret))
            }
        }
        for ((origin, reason) in listOf(OWNER to "CLEAR", "clear" to PRIVATE_ERROR,
            "clear" to "REQUEST_STARTED")) {
            val failure = captureFailure {
                diagnosticLine(fixture.repository, origin, reason, 41L, 42L, before, after)
            }
            assertTrue(failure is IllegalArgumentException)
            assertEquals("shop_epoch_unknown_origin_or_reason", failure.message)
        }
        assertEquals(state, fixture.repository.state.value)
        assertEquals(epoch, refreshFence(fixture.repository))
        assertEquals(0, fixture.remote.calls)
        assertTrue(fixture.store.effects.isEmpty())
    }

    @Test
    fun displayAndErrorOnlyDifferencesDoNotPretendAuthorityChanged() {
        val fixture = Fixture()
        val before = context()
        val after = before.copy(
            linkedShops = before.linkedShops.map { it.copy(name = "changed-private-name", code = "changed-private-code") },
            selectedShop = before.selectedShop?.copy(name = "changed-private-name", code = "changed-private-code"),
            errorMessage = "changed-private-error"
        )

        val values = fields(diagnosticLine(fixture.repository, before = before, after = after))

        assertEquals("true", values.getValue("context_value_changed"))
        assertEquals("false", values.getValue("context_semantic_change"))
        for (key in AUTHORITY_FIELDS) assertEquals("$key must exclude display/error changes", "false", values.getValue(key))
    }

    @Test
    fun authorityFieldsMembershipRevocationAndPermissionsAreSemanticDifferences() {
        val fixture = Fixture()
        val before = context()
        val original = before.linkedShops.single()
        val changedMemberships = listOf(
            original.copy(role = "staff"),
            original.copy(status = "revoked"),
            original.copy(membershipStatus = "revoked"),
            original.copy(shopStatus = "suspended"),
            original.copy(selectable = false),
            original.copy(canWrite = false)
        )
        for (membership in changedMemberships) {
            val values = fields(diagnosticLine(fixture.repository, before = before,
                after = before.copy(linkedShops = listOf(membership))))
            assertEquals("true", values.getValue("membership_permission_changed"))
            assertEquals("true", values.getValue("context_semantic_change"))
        }
        val selected = requireNotNull(before.selectedShop)
        for (changed in listOf(selected.copy(role = "staff"), selected.copy(status = "revoked"), selected.copy(canWrite = false))) {
            val values = fields(diagnosticLine(fixture.repository, before = before, after = before.copy(selectedShop = changed)))
            assertEquals("true", values.getValue("selected_permission_changed"))
            assertEquals("true", values.getValue("context_semantic_change"))
        }
        val authorityChanges = listOf(
            "owner_changed" to before.copy(ownerUserId = FOREIGN_OWNER),
            "active_shop_changed" to before.copy(selectedShop = selected.copy(shopId = "changed-private-shop")),
            "loading_changed" to before.copy(isLoading = true),
            "sync_allowed_changed" to before.copy(syncAllowed = false),
            "local_access_changed" to before.copy(localAccessAllowed = false)
        )
        for ((key, after) in authorityChanges) {
            val values = fields(diagnosticLine(fixture.repository, before = before, after = after))
            assertEquals("true", values.getValue(key))
            assertEquals("true", values.getValue("context_semantic_change"))
        }
    }

    @Test
    fun membershipReorderingRetainsSemanticEqualityWhileFullValueDiffers() {
        val fixture = Fixture()
        val before = context().copy(linkedShops = listOf(linkedShop(), linkedShop().copy(shopId = "private-second-shop")))
        val after = before.copy(linkedShops = before.linkedShops.reversed())

        val values = fields(diagnosticLine(fixture.repository, before = before, after = after))

        assertEquals("true", values.getValue("context_value_changed"))
        assertEquals("false", values.getValue("membership_permission_changed"))
        assertEquals("false", values.getValue("context_semantic_change"))
    }

    private fun diagnosticLine(
        repository: ShopContextRepository,
        origin: String = "record_device_authorization",
        reason: String = "DEVICE_ACTIVE_WRITE",
        old: Long = 1L,
        new: Long = 2L,
        before: ShopContext,
        after: ShopContext
    ): String {
        val formatter = ShopContextRepository::class.java.getDeclaredMethod(
            "epochDiagnosticLine", String::class.java, String::class.java,
            java.lang.Long.TYPE, java.lang.Long.TYPE, ShopContext::class.java, ShopContext::class.java
        )
        formatter.isAccessible = true
        return try {
            formatter.invoke(repository, origin, reason, old, new, before, after) as String
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun refreshFence(repository: ShopContextRepository): Long =
        ShopContextRepository::class.java.getDeclaredField("refreshGeneration").apply {
            isAccessible = true
        }.getLong(repository)

    private fun fields(line: String): Map<String, String> {
        val parts = line.split(' ')
        assertEquals("shop_epoch", parts.first())
        return parts.drop(1).associate { field ->
            val pair = field.split('=', limit = 2)
            assertEquals("Each diagnostic field has one static key and value", 2, pair.size)
            pair[0] to pair[1]
        }.also { assertEquals("Diagnostic keys must be unique", parts.size - 1, it.size) }
    }

    private fun captureFailure(block: () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected the original store failure")
    }

    private class Fixture {
        var owner: String = OWNER
        val store = Store()
        val remote = Remote { Result.success(listOf(linkedShop())) }
        val repository = ShopContextRepository(remote, store, currentOwnerUserId = { owner },
            currentDeviceIdentifier = { DEVICE })
    }

    private class Remote(var fetch: suspend () -> Result<List<LinkedShop>>) : LinkedShopRemoteDataSource {
        var calls = 0
        override val isConfigured = true
        override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> {
            calls += 1
            return fetch()
        }
    }

    private class Store : SelectedShopStore {
        val effects = mutableListOf<String>()
        var selectionFailure: Throwable? = null
        private var selected: String? = null
        private var confirmed: ShopContext? = null
        private var denied = false
        override fun getSelectedShopId(ownerUserId: String): String? = selected
        override fun setSelectedShopId(ownerUserId: String, shopId: String) {
            effects += "selected:$shopId"
            selectionFailure?.let { throw it }
            selected = shopId
        }
        override fun clearSelectedShopId(ownerUserId: String) { effects += "clear_selected"; selected = null }
        override fun getConfirmedContext(ownerUserId: String): ShopContext? = confirmed
        override fun setConfirmedContext(ownerUserId: String, context: ShopContext) { effects += "confirmed"; confirmed = context }
        override fun clearConfirmedContext(ownerUserId: String) { effects += "clear_confirmed"; confirmed = null }
        override fun isDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String): Boolean = denied
        override fun setDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String, denied: Boolean) {
            effects += "device_denied:$denied"
            this.denied = denied
        }
    }

    private companion object {
        const val OWNER = "private-owner-must-never-appear"
        const val FOREIGN_OWNER = "private-foreign-owner-must-never-appear"
        const val SHOP = "private-shop-must-never-appear"
        const val DEVICE = "private-device-must-never-appear"
        const val PRIVATE_NAME = "private-display-name-token-secret"
        const val PRIVATE_CODE = "private-display-code-token-secret"
        const val PRIVATE_ERROR = "private-error-bearer-token-secret"

        val AUTHORITY_FIELDS = setOf("owner_changed", "active_shop_changed", "selected_permission_changed",
            "membership_permission_changed", "loading_changed", "sync_allowed_changed", "local_access_changed")
        val EXPECTED_FIELDS = setOf("origin", "reason", "old_generation", "new_generation", "context_value_changed",
            "owner_changed", "active_shop_changed", "selected_permission_changed", "membership_permission_changed",
            "loading_changed", "sync_allowed_changed", "local_access_changed", "context_semantic_change")

        fun linkedShop() = LinkedShop(SHOP, PRIVATE_CODE, PRIVATE_NAME, "shop_owner", "active",
            "active", "active", true, true)
        fun context(): ShopContext {
            val linked = linkedShop()
            return ShopContext(OWNER, listOf(linked), SelectedShop(SHOP, PRIVATE_CODE, PRIVATE_NAME,
                "shop_owner", "active", true), errorMessage = PRIVATE_ERROR)
        }
    }
}

package com.example.merchandisecontrolsplitview.data

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShopContextOfflineAuthorizationTest {
    private val owner = "00000000-0000-4000-8000-000000001499"
    private val shop = LinkedShop("00000000-0000-4000-8000-000000001401", "QA", "Fixture shop", "owner", "active", "active", "active", true, true)
    private fun store(): SharedPreferencesSelectedShopStore {
        val context = RuntimeEnvironment.getApplication()
        return SharedPreferencesSelectedShopStore(context.getSharedPreferences("143-offline-authority", Context.MODE_PRIVATE))
    }
    private class Remote(var fetch: suspend () -> Result<List<LinkedShop>>) : LinkedShopRemoteDataSource {
        override val isConfigured = true
        override suspend fun fetchLinkedShops() = fetch()
    }

    @Test fun `143 transient linked shops failure preserves last authorized local scope`() = runTest {
        val remote = Remote { Result.success(listOf(shop)) }
        val repository = ShopContextRepository(remote, store(), currentOwnerUserId = { owner })
        repository.refresh(owner)
        remote.fetch = { Result.failure(IOException("fixture timeout")) }
        repository.refresh(owner)
        assertEquals(shop.shopId, repository.state.value.selectedShop?.shopId)
        assertFalse(repository.state.value.syncAllowed)
        assertTrue(repository.state.value.localAccessAllowed)
    }

    @Test fun `143 ordinary reopen publishes durable selected scope before held network returns`() = runTest {
        val remote = Remote { Result.success(listOf(shop)) }
        ShopContextRepository(remote, store(), currentOwnerUserId = { owner }).refresh(owner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        remote.fetch = { entered.complete(Unit); release.await(); Result.success(listOf(shop)) }
        val reopened = ShopContextRepository(remote, store(), currentOwnerUserId = { owner })
        val refresh = async { reopened.refresh(owner) }
        try {
            entered.await()
            assertTrue(reopened.state.value.isLoading)
            assertEquals(shop.shopId, reopened.state.value.selectedShop?.shopId)
            assertFalse(reopened.state.value.syncAllowed)
            assertTrue(reopened.state.value.localAccessAllowed)
        } finally { release.complete(Unit); refresh.await() }
    }
    @Test fun `143 confirmed refusal clears durable local authorization and transient retry cannot restore it`() = runTest {
        val remote = Remote { Result.success(listOf(shop)) }
        val repository = ShopContextRepository(remote, store(), currentOwnerUserId = { owner })
        repository.refresh(owner)
        remote.fetch = { Result.failure(ShopAccessRejectedException()) }
        repository.refresh(owner)
        assertNull(repository.state.value.selectedShop)
        assertFalse(repository.state.value.localAccessAllowed)
        remote.fetch = { Result.failure(IOException("fixture timeout")) }
        val reopened = ShopContextRepository(remote, store(), currentOwnerUserId = { owner })
        reopened.refresh(owner)
        assertNull(reopened.state.value.selectedShop)
        assertFalse(reopened.state.value.localAccessAllowed)
        assertFalse(reopened.state.value.syncAllowed)
    }

    @Test fun `143 cache cannot follow an unconfirmed shop selection or another account`() = runTest {
        val remote = Remote { Result.success(listOf(shop)) }
        ShopContextRepository(remote, store(), currentOwnerUserId = { owner }).refresh(owner)
        store().setSelectedShopId(owner, "00000000-0000-4000-8000-000000001402")
        remote.fetch = { Result.failure(IOException("fixture timeout")) }
        val reopened = ShopContextRepository(remote, store(), currentOwnerUserId = { owner })
        reopened.refresh(owner)
        assertNull(reopened.state.value.selectedShop)
        assertFalse(reopened.state.value.localAccessAllowed)
        val foreign = "00000000-0000-4000-8000-000000001498"
        val changedOwner = ShopContextRepository(remote, store(), currentOwnerUserId = { foreign })
        changedOwner.refresh(foreign)
        assertNull(changedOwner.state.value.selectedShop)
        assertFalse(changedOwner.state.value.localAccessAllowed)
    }

    @Test fun `143 confirmed device denial survives membership refresh and reopen until exact fresh active response`() = runTest {
        val device = "00000000-0000-4000-8000-000000001400"
        for (status in listOf("retired", "suspended", "revoked")) {
            RuntimeEnvironment.getApplication().getSharedPreferences("143-offline-authority", Context.MODE_PRIVATE).edit().clear().commit()
            val remote = Remote { Result.success(listOf(shop)) }
            fun contextRepository() = ShopContextRepository(remote, store(), currentOwnerUserId = { owner },
                currentDeviceIdentifier = { device })
            var context = contextRepository()
            context.refresh(owner)
            val deviceRemote = object : ShopDeviceRegistrationRemote {
                override val isConfigured = true
                var result = Result.success(ShopDeviceAuthorizationSnapshot(status, status, false,
                    null, null, status, "contact_shop_admin", 1L, device))
                override suspend fun registerCurrentOwnerDevice(reason: String) =
                    Result.success(ShopDeviceRegistrationResult(ok=true))
                override suspend fun currentOwnerDeviceStatus(reason: String) = result
            }
            val authorization = ShopDeviceAuthorizationRepository(deviceRemote,
                onConfirmedAuthorization = { shopId, snapshot ->
                    context.recordDeviceAuthorization(owner, shopId, requireNotNull(snapshot.deviceIdentifier), snapshot.status, snapshot.canWrite)
                })
            authorization.checkStatus("fixture", force=true, shopId=shop.shopId)
            assertFalse("$status must revoke local use", context.state.value.localAccessAllowed)
            context.refresh(owner)
            assertFalse("membership success must not clear $status", context.state.value.localAccessAllowed)
            context = contextRepository()
            context.restoreLocalAuthorization(owner)
            assertFalse("reopen must retain $status", context.state.value.localAccessAllowed)
            context.refresh(owner)
            assertFalse(context.state.value.localAccessAllowed)
            context.recordDeviceAuthorization(owner, shop.shopId, "foreign-device", "active", true)
            assertFalse(context.state.value.localAccessAllowed)
            context.recordDeviceAuthorization(owner, shop.shopId, device, "active", false)
            assertFalse(context.state.value.localAccessAllowed)
            deviceRemote.result = Result.failure(IOException("fixture transient status timeout"))
            authorization.checkStatus("fixture", force=true, shopId=shop.shopId)
            assertFalse(context.state.value.localAccessAllowed)
            deviceRemote.result = Result.success(ShopDeviceAuthorizationSnapshot("active", "ok", true,
                null, null, "ok", "allow", 2L, device))
            authorization.checkStatus("fixture", force=true, shopId=shop.shopId)
            assertTrue("only fresh exact active response restores $status", context.state.value.localAccessAllowed)
            assertFalse(store().isDeviceDenied(owner, shop.shopId, device))
        }
    }

    @Test fun `143 device denial closes real Save even when cloud scope is READY`() = runTest {
        val device = "00000000-0000-4000-8000-000000001400"
        val remote = Remote { Result.success(listOf(shop)) }
        val context = ShopContextRepository(remote, store(), currentOwnerUserId={owner}, currentDeviceIdentifier={device})
        context.refresh(owner)
        context.recordDeviceAuthorization(owner, shop.shopId, device, "retired", false)
        val scope = task126ActiveOwnerStoreScope(owner, context.state.value.selectedShop)
        val state = Task126BusinessDataScopeState.ready(scope).copy(localReadsAllowed=context.state.value.localAccessAllowed,
            localWritesAllowed=context.state.value.localAccessAllowed)
        assertTrue(state.allowsCloudSync)
        assertFalse(state.allowsLocalOperations)
        assertFalse(com.example.merchandisecontrolsplitview.ui.navigation.businessContentAvailable(
            true, AuthState.SignedIn(owner, "qa@example.test"), state, scope))
        val db = androidx.room.Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = DefaultInventoryRepository(db, businessDataScopeRuntimeGuard=Task126BusinessDataScopeFlightGate(state))
            val result = runCatching { repository.addProduct(Product(barcode="deny-save", productName="must not commit")) }
            assertTrue(result.exceptionOrNull() is Task126BusinessDataScopeChangedException)
            assertEquals(0, db.productDao().count())
        } finally { db.close() }
    }

}

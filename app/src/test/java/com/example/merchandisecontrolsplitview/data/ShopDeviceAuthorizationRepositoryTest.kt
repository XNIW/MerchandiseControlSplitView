package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopDeviceAuthorizationRepositoryTest {

    @Test
    fun `072 active device allows guarded cloud write`() = runTest {
        val authorization = ShopDeviceAuthorizationRepository(
            FakeDeviceRegistrationRemote072(Result.success(snapshot(status = "active")))
        )
        val remote = FakeProductPriceRemote072()
        val guarded = DeviceGuardedProductPriceRemoteDataSource(remote, authorization)

        val result = guarded.upsertProductPrices(emptyList())

        assertTrue(result.isSuccess)
        assertEquals(1, remote.upsertCalls)
    }

    @Test
    fun `072 revoked device blocks guarded cloud write`() = runTest {
        val authorization = ShopDeviceAuthorizationRepository(
            FakeDeviceRegistrationRemote072(Result.success(snapshot(status = "revoked")))
        )
        val remote = FakeProductPriceRemote072()
        val guarded = DeviceGuardedProductPriceRemoteDataSource(remote, authorization)

        val result = guarded.upsertProductPrices(emptyList())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ShopDeviceAuthorizationBlockedException)
        assertEquals(0, remote.upsertCalls)
    }

    @Test
    fun `072 network error does not reuse active status before cloud write`() = runTest {
        val fakeDeviceRemote = FakeDeviceRegistrationRemote072(
            Result.success(snapshot(status = "active"))
        )
        val authorization = ShopDeviceAuthorizationRepository(fakeDeviceRemote)

        assertTrue(authorization.checkStatus(reason = "foreground", force = true).isSuccess)

        fakeDeviceRemote.nextStatus = Result.failure(IllegalStateException("offline"))
        val result = authorization.ensureActiveForCloudWrite("manual_quick_sync")

        assertTrue(result.isFailure)
        val snapshot =
            (result.exceptionOrNull() as? ShopDeviceAuthorizationBlockedException)?.snapshot
        assertEquals("network_error", snapshot?.status)
        assertFalse(snapshot?.canWrite ?: true)
    }

    @Test
    fun `072 transient cancellation can reuse fresh active status for automatic cloud write`() = runTest {
        val fakeDeviceRemote = FakeDeviceRegistrationRemote072(
            Result.success(snapshot(status = "active"))
        )
        val authorization = ShopDeviceAuthorizationRepository(fakeDeviceRemote)

        assertTrue(authorization.checkStatus(reason = "foreground", force = true).isSuccess)

        fakeDeviceRemote.nextStatus = Result.failure(CancellationException("cancelled"))
        val result = authorization.ensureActiveForCloudWrite("catalog_push:local_catalog_commit")

        assertTrue(result.isSuccess)
        assertEquals("active", result.getOrThrow().status)
    }

    @Test
    fun `072 transient cancellation does not reuse active status for manual cloud write`() = runTest {
        val fakeDeviceRemote = FakeDeviceRegistrationRemote072(
            Result.success(snapshot(status = "active"))
        )
        val authorization = ShopDeviceAuthorizationRepository(fakeDeviceRemote)

        assertTrue(authorization.checkStatus(reason = "foreground", force = true).isSuccess)

        fakeDeviceRemote.nextStatus = Result.failure(CancellationException("cancelled"))
        val result = authorization.ensureActiveForCloudWrite("manual_quick_sync")

        assertTrue(result.isFailure)
        val snapshot =
            (result.exceptionOrNull() as? ShopDeviceAuthorizationBlockedException)?.snapshot
        assertEquals("network_error", snapshot?.status)
        assertFalse(snapshot?.canWrite ?: true)
    }

    @Test
    fun `shop scoped device gate does not reuse another shop active status`() = runTest {
        val fakeDeviceRemote = ScopedDeviceRegistrationRemote(
            statuses = mutableMapOf(
                "shop-a" to Result.success(snapshot(status = "active")),
                "shop-b" to Result.success(snapshot(status = "revoked"))
            )
        )
        val authorization = ShopDeviceAuthorizationRepository(fakeDeviceRemote)
        val remote = FakeProductPriceRemote072()
        val guarded = DeviceGuardedProductPriceRemoteDataSource(remote, authorization)

        assertTrue(
            authorization
                .checkStatus(reason = "foreground", force = true, shopId = "shop-a")
                .isSuccess
        )

        val result = guarded.upsertProductPrices(emptyList(), "shop-b")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ShopDeviceAuthorizationBlockedException)
        assertEquals(0, remote.upsertCalls)
        assertEquals(listOf("shop-a", "shop-b"), fakeDeviceRemote.statusShopIds)
    }

    private class FakeDeviceRegistrationRemote072(
        var nextStatus: Result<ShopDeviceAuthorizationSnapshot>
    ) : ShopDeviceRegistrationRemote {
        override val isConfigured: Boolean = true

        override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success"))

        override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
            nextStatus
    }

    private class FakeProductPriceRemote072 : ProductPriceRemoteDataSource {
        override val isConfigured: Boolean = true
        var upsertCalls = 0

        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> {
            upsertCalls++
            return Result.success(Unit)
        }

        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
            Result.success(emptyList())

        override suspend fun fetchProductPricesByIds(
            remoteIds: Set<String>
        ): Result<List<InventoryProductPriceRow>> =
            Result.success(emptyList())
    }

    private class ScopedDeviceRegistrationRemote(
        private val statuses: MutableMap<String, Result<ShopDeviceAuthorizationSnapshot>>
    ) : ShopDeviceRegistrationRemote {
        override val isConfigured: Boolean = true
        val statusShopIds = mutableListOf<String>()

        override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success"))

        override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
            Result.failure(IllegalStateException("legacy status should not be used"))

        override suspend fun shopDeviceStatusForShop(
            shopId: String,
            reason: String
        ): Result<ShopDeviceAuthorizationSnapshot> {
            statusShopIds.add(shopId)
            return statuses[shopId] ?: Result.failure(IllegalArgumentException("unknown shop"))
        }
    }

    private companion object {
        fun snapshot(status: String): ShopDeviceAuthorizationSnapshot =
            ShopDeviceAuthorizationSnapshot(
                status = status,
                code = if (status == "active") "success" else status,
                canWrite = status == "active",
                serverTime = "2026-06-19T00:00:00Z",
                lastSeenAt = "2026-06-19T00:00:00Z",
                reasonCode = status,
                recommendedAction = if (status == "active") "allow" else "contact_shop_admin",
                checkedAtMs = System.currentTimeMillis()
            )
    }
}

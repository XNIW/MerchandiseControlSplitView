package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ShopContextTest {
    @Test
    fun zeroShopsKeepsLegacyCleanState() {
        val resolution = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = emptyList(),
            persistedShopId = null
        )

        assertNull(resolution.context.selectedShop)
        assertNull(resolution.context.activeShopId)
        assertFalse(resolution.context.shouldShowSelector)
        assertEquals(emptyList<LinkedShop>(), resolution.context.selectableShops)
        assertEquals("", shopScopedStoreScope(resolution.context.selectedShop))
    }

    @Test
    fun oneShopAutoSelectsWithoutSelector() {
        val resolution = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(shop("shop-a", "Moda Lina")),
            persistedShopId = null
        )

        assertEquals("shop-a", resolution.context.activeShopId)
        assertEquals("Moda Lina", resolution.context.selectedShop?.displayName)
        assertFalse(resolution.context.shouldShowSelector)
        assertEquals("shop:shop-a", shopScopedStoreScope(resolution.context.selectedShop))
    }

    @Test
    fun shopScopedStoreScopeKeepsLocalPrefixButRemoteStoreIdUsesShopId() {
        assertEquals("shop-a", remoteStoreIdFromStoreScope("shop:shop-a"))
        assertEquals("legacy-store", remoteStoreIdFromStoreScope("legacy-store"))
        assertNull(remoteStoreIdFromStoreScope(""))
        assertNull(remoteStoreIdFromStoreScope(null))
    }

    @Test
    fun multipleShopsRestoreValidSelectionAndShowSelector() {
        val resolution = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(shop("shop-a", "Shop A"), shop("shop-b", "Shop B")),
            persistedShopId = "shop-b"
        )

        assertEquals("shop-b", resolution.context.activeShopId)
        assertEquals("Shop B", resolution.context.selectedShop?.displayName)
        assertTrue(resolution.context.shouldShowSelector)
        assertEquals(2, resolution.context.selectableShops.size)
    }

    @Test
    fun revokedPersistedShopIsResetToRemainingValidShop() {
        val resolution = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(
                shop("shop-a", "Shop A", status = "revoked", selectable = false),
                shop("shop-b", "Shop B")
            ),
            persistedShopId = "shop-a"
        )

        assertEquals("shop-b", resolution.context.activeShopId)
        assertEquals("shop-b", resolution.persistedSelection)
        assertFalse(resolution.context.shouldShowSelector)
    }

    @Test
    fun revokedPersistedShopFallsBackToLegacyWhenNoValidShopRemains() {
        val resolution = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(
                shop("shop-a", "Shop A", status = "revoked", selectable = false)
            ),
            persistedShopId = "shop-a"
        )

        assertNull(resolution.context.selectedShop)
        assertNull(resolution.context.activeShopId)
        assertNull(resolution.persistedSelection)
        assertFalse(resolution.context.shouldShowSelector)
        assertEquals("", shopScopedStoreScope(resolution.context.selectedShop))
    }

    @Test
    fun membershipRevokedIsNotSelectableEvenWhenShopIsActive() {
        val resolution = ShopContextResolver.resolve(
            ownerUserId = OWNER_A,
            linkedShops = listOf(
                shop(
                    "shop-a",
                    "Shop A",
                    status = "active",
                    membershipStatus = "revoked",
                    shopStatus = "active",
                    selectable = true
                )
            ),
            persistedShopId = "shop-a"
        )

        assertNull(resolution.context.selectedShop)
        assertNull(resolution.context.activeShopId)
        assertNull(resolution.persistedSelection)
        assertTrue(resolution.context.syncAllowed)
    }

    @Test
    fun linkedShopFetchErrorBlocksSyncInsteadOfPretendingLegacyZeroShops() = runTest {
        val repository = ShopContextRepository(
            remote = object : LinkedShopRemoteDataSource {
                override val isConfigured: Boolean = true

                override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> =
                    Result.failure(IllegalStateException("linked shops unavailable"))
            },
            selectedShopStore = InMemorySelectedShopStore(),
            currentOwnerUserId = { OWNER_A }
        )

        repository.refresh(OWNER_A)

        assertNull(repository.state.value.selectedShop)
        assertFalse(repository.state.value.syncAllowed)
        assertEquals("linked shops unavailable", repository.state.value.errorMessage)
    }

    @Test
    fun refreshLoadingStateIsFailClosedForExistingAccount() = runTest {
        val remote = MutableLinkedShopRemoteDataSource {
            Result.success(listOf(shop("shop-a", "Shop A")))
        }
        val repository = ShopContextRepository(
            remote = remote,
            selectedShopStore = InMemorySelectedShopStore(),
            currentOwnerUserId = { OWNER_A }
        )
        repository.refresh(OWNER_A)
        assertTrue(repository.state.value.syncAllowed)
        assertEquals("shop-a", repository.state.value.activeShopId)

        val gate = CompletableDeferred<List<LinkedShop>>()
        remote.fetch = {
            Result.success(gate.await())
        }
        val refreshJob = launch { repository.refresh(OWNER_A) }
        testScheduler.runCurrent()

        val loading = repository.state.value
        assertTrue(loading.isLoading)
        assertFalse(loading.syncAllowed)
        assertEquals("shop-a", loading.activeShopId)

        gate.complete(listOf(shop("shop-a", "Shop A")))
        refreshJob.join()
        assertFalse(repository.state.value.isLoading)
        assertTrue(repository.state.value.syncAllowed)
    }

    @Test
    fun refreshLoadingStateClearsPreviousShopWhenAccountChanges() = runTest {
        val remote = MutableLinkedShopRemoteDataSource {
            Result.success(listOf(shop("shop-a", "Shop A")))
        }
        var currentOwnerUserId = OWNER_A
        val repository = ShopContextRepository(
            remote = remote,
            selectedShopStore = InMemorySelectedShopStore(),
            currentOwnerUserId = { currentOwnerUserId }
        )
        repository.refresh(OWNER_A)
        assertEquals("shop-a", repository.state.value.activeShopId)

        val gate = CompletableDeferred<List<LinkedShop>>()
        remote.fetch = {
            Result.success(gate.await())
        }
        currentOwnerUserId = OWNER_B
        val refreshJob = launch { repository.refresh(OWNER_B) }
        testScheduler.runCurrent()

        val loading = repository.state.value
        assertEquals(OWNER_B, loading.ownerUserId)
        assertTrue(loading.isLoading)
        assertFalse(loading.syncAllowed)
        assertNull(loading.activeShopId)
        assertTrue(loading.linkedShops.isEmpty())

        gate.complete(emptyList())
        refreshJob.join()
        assertFalse(repository.state.value.isLoading)
        assertTrue(repository.state.value.syncAllowed)
    }

    @Test
    fun refreshDiscardsDeferredResponseWhenAuthOwnerChangesBeforeNextRefresh() = runTest {
        val ownerAFetchStarted = CompletableDeferred<Unit>()
        val releaseOwnerAFetch = CompletableDeferred<Unit>()
        var currentOwnerUserId = OWNER_A
        val remote = MutableLinkedShopRemoteDataSource {
            ownerAFetchStarted.complete(Unit)
            releaseOwnerAFetch.await()
            Result.success(listOf(shop("shop-a", "Shop A")))
        }
        val selectedShopStore = InMemorySelectedShopStore()
        val repository = ShopContextRepository(
            remote = remote,
            selectedShopStore = selectedShopStore,
            currentOwnerUserId = { currentOwnerUserId }
        )

        val ownerARefresh = launch { repository.refresh(OWNER_A) }
        ownerAFetchStarted.await()
        currentOwnerUserId = OWNER_B
        releaseOwnerAFetch.complete(Unit)
        ownerARefresh.join()

        assertEquals(OWNER_A, repository.state.value.ownerUserId)
        assertTrue(repository.state.value.isLoading)
        assertFalse(repository.state.value.syncAllowed)
        assertNull(repository.state.value.activeShopId)
        assertEquals(0, selectedShopStore.mutationCount(OWNER_A))

        remote.fetch = {
            Result.success(listOf(shop("shop-b", "Shop B")))
        }
        repository.refresh(OWNER_B)

        assertEquals(OWNER_B, repository.state.value.ownerUserId)
        assertEquals("shop-b", repository.state.value.activeShopId)
        assertTrue(repository.state.value.syncAllowed)
    }

    @Test
    fun latestRefreshGenerationWinsWhenPreviousOwnerResponseArrivesLast() = runTest {
        val ownerAFetchStarted = CompletableDeferred<Unit>()
        val releaseOwnerAFetch = CompletableDeferred<Unit>()
        var currentOwnerUserId = OWNER_A
        val remote = MutableLinkedShopRemoteDataSource {
            ownerAFetchStarted.complete(Unit)
            releaseOwnerAFetch.await()
            Result.success(listOf(shop("shop-a", "Shop A")))
        }
        val repository = ShopContextRepository(
            remote = remote,
            selectedShopStore = InMemorySelectedShopStore(),
            currentOwnerUserId = { currentOwnerUserId }
        )

        val ownerARefresh = launch { repository.refresh(OWNER_A) }
        ownerAFetchStarted.await()
        currentOwnerUserId = OWNER_B
        remote.fetch = { Result.success(listOf(shop("shop-b", "Shop B"))) }
        repository.refresh(OWNER_B)
        releaseOwnerAFetch.complete(Unit)
        ownerARefresh.join()

        assertEquals(OWNER_B, repository.state.value.ownerUserId)
        assertEquals("shop-b", repository.state.value.activeShopId)
    }

    @Test
    fun staleOwnerRefreshCannotInvalidateCurrentOwnerLoadingGeneration() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var currentOwnerUserId = OWNER_B
        val remote = MutableLinkedShopRemoteDataSource {
            fetchStarted.complete(Unit)
            releaseFetch.await()
            Result.success(listOf(shop("shop-b", "Shop B")))
        }
        val repository = ShopContextRepository(
            remote = remote,
            selectedShopStore = InMemorySelectedShopStore(),
            currentOwnerUserId = { currentOwnerUserId }
        )

        val ownerBRefresh = launch { repository.refresh(OWNER_B) }
        fetchStarted.await()
        repository.refresh(OWNER_A)
        releaseFetch.complete(Unit)
        ownerBRefresh.join()

        assertEquals(OWNER_B, repository.state.value.ownerUserId)
        assertEquals("shop-b", repository.state.value.activeShopId)
        assertFalse(repository.state.value.isLoading)
        assertTrue(repository.state.value.syncAllowed)
    }

    @Test
    fun shopSelectionDuringRefreshIsRejectedWithoutStrandingLoadingState() = runTest {
        val remote = MutableLinkedShopRemoteDataSource {
            Result.success(listOf(shop("shop-a", "Shop A"), shop("shop-b", "Shop B")))
        }
        val repository = ShopContextRepository(
            remote = remote,
            selectedShopStore = InMemorySelectedShopStore(),
            currentOwnerUserId = { OWNER_A }
        )
        repository.refresh(OWNER_A)

        val releaseFetch = CompletableDeferred<List<LinkedShop>>()
        remote.fetch = {
            Result.success(releaseFetch.await())
        }
        val refreshJob = launch { repository.refresh(OWNER_A) }
        testScheduler.runCurrent()

        assertTrue(repository.state.value.isLoading)
        assertFalse(repository.state.value.shouldShowSelector)
        assertFalse(repository.selectShop("shop-b"))

        releaseFetch.complete(listOf(shop("shop-a", "Shop A"), shop("shop-b", "Shop B")))
        refreshJob.join()

        assertFalse(repository.state.value.isLoading)
        assertTrue(repository.state.value.syncAllowed)
        assertTrue(repository.state.value.shouldShowSelector)
    }

    @Test
    fun selectedShopStoreIsAccountScoped() {
        val prefs = InMemorySelectedShopStore()

        prefs.setSelectedShopId(OWNER_A, "shop-a")
        prefs.setSelectedShopId(OWNER_B, "shop-b")

        assertEquals("shop-a", prefs.getSelectedShopId(OWNER_A))
        assertEquals("shop-b", prefs.getSelectedShopId(OWNER_B))

        prefs.clearSelectedShopId(OWNER_A)

        assertNull(prefs.getSelectedShopId(OWNER_A))
        assertEquals("shop-b", prefs.getSelectedShopId(OWNER_B))
    }

    private fun shop(
        id: String,
        name: String,
        status: String = "active",
        membershipStatus: String? = status,
        shopStatus: String? = status,
        selectable: Boolean = true
    ) = LinkedShop(
        shopId = id,
        code = id.uppercase(),
        name = name,
        role = "shop_owner",
        status = status,
        membershipStatus = membershipStatus,
        shopStatus = shopStatus,
        selectable = selectable,
        canWrite = true
    )

    private class InMemorySelectedShopStore : SelectedShopStore {
        private val values = mutableMapOf<String, String>()
        private val mutations = mutableMapOf<String, Int>()

        override fun getSelectedShopId(ownerUserId: String): String? = values[ownerUserId]

        override fun setSelectedShopId(ownerUserId: String, shopId: String) {
            values[ownerUserId] = shopId
            mutations[ownerUserId] = mutationCount(ownerUserId) + 1
        }

        override fun clearSelectedShopId(ownerUserId: String) {
            values.remove(ownerUserId)
            mutations[ownerUserId] = mutationCount(ownerUserId) + 1
        }

        fun mutationCount(ownerUserId: String): Int = mutations[ownerUserId] ?: 0
    }

    private class MutableLinkedShopRemoteDataSource(
        var fetch: suspend () -> Result<List<LinkedShop>>
    ) : LinkedShopRemoteDataSource {
        override val isConfigured: Boolean = true

        override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> = fetch()
    }

    private companion object {
        const val OWNER_A = "00000000-0000-4000-8000-0000000000aa"
        const val OWNER_B = "00000000-0000-4000-8000-0000000000bb"
    }
}

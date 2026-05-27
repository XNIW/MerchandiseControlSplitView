package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Task126CacheMemoryTest {
    @Test
    fun `cache manifest privacy snapshot redacts owner and store`() {
        val manifest = Task126CacheManifest(
            ownerHash = "raw-owner",
            storeId = "raw-store",
            localStoreId = "raw-local-store",
            schemaVersion = 2,
            syncProtocolVersion = 126,
            storeEpoch = 1,
            isActive = true,
            isDirty = false,
            estimatedBytes = 1_024L
        )

        val snapshot = manifest.privacySafeSnapshot

        assertFalse(snapshot.toString().contains("raw-owner"))
        assertFalse(snapshot.toString().contains("raw-store"))
        assertEquals("redacted:owner", snapshot.ownerHashRedacted)
        assertEquals("redacted:store", snapshot.storeIdRedacted)
    }

    @Test
    fun `active store only blocks inactive cache loaded in memory`() {
        val active = Task126CacheManifest.fixture(storeId = "store-a", isActive = true, isDirty = false)
        val inactive = Task126CacheManifest.fixture(storeId = "store-b", isActive = false, isDirty = false)

        assertEquals(
            Task126CachePolicyDecision.Blocked(Task126CachePolicyDecision.Reason.InactiveStoreLoaded),
            Task126CachePolicy.validateActiveStoreOnly("store-a", listOf(active, inactive))
        )
    }

    @Test
    fun `inactive dirty cache requires backup export before cleanup`() {
        val dirtyInactive = Task126CacheManifest.fixture(storeId = "store-b", isActive = false, isDirty = true)

        assertEquals(
            Task126InactiveCacheCleanupDecision.KeepDirtyRequiresBackupExport,
            Task126CachePolicy.cleanupDecision(dirtyInactive)
        )
    }

    @Test
    fun `product price page limit caps memory budget`() {
        assertEquals(500, Task126ProductPriceHistoryPolicy.pageLimit(20_000))
        assertEquals(100, Task126ProductPriceHistoryPolicy.pageLimit(100))
    }
}

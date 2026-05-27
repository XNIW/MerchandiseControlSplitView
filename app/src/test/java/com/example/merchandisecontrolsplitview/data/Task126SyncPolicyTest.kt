package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Task126SyncPolicyTest {
    @Test
    fun `policy defaults use local default store only and logical scope`() {
        assertEquals(StoreScopeMode.LocalDefaultStoreOnly, Task126SyncPolicy.storeScopeMode)
        assertEquals(CacheMode.LogicalScope, Task126SyncPolicy.cacheMode)
        assertEquals(126, Task126SyncPolicy.SYNC_PROTOCOL_VERSION)
        assertTrue(Task126SyncPolicy.featureFlags.strictOwnerStoreGate)
        assertFalse(Task126SyncPolicy.featureFlags.physicalMultiStoreCache)
    }

    @Test
    fun `conflict matrix covers C126-00 through C126-60`() {
        val ids = Task126ConflictMatrix.allCases.map { it.id }.toSet()

        assertEquals(61, ids.size)
        (0..60).forEach { index ->
            assertTrue(ids.contains("C126-%02d".format(index)))
        }
    }

    @Test
    fun `different fields can merge but same field requires review`() {
        assertEquals(
            Task126ConflictDecision.AutoMerge,
            Task126ConflictResolver.resolve(
                localChangedFields = listOf("productName"),
                remoteChangedFields = listOf("retailPrice")
            )
        )
        assertEquals(
            Task126ConflictDecision.Review(Task126ReviewReason.SameField),
            Task126ConflictResolver.resolve(
                localChangedFields = listOf("productName"),
                remoteChangedFields = listOf("productName")
            )
        )
    }
}

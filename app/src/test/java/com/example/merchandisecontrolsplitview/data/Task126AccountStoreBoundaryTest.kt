package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Test

class Task126AccountStoreBoundaryTest {
    @Test
    fun `owner store scope normalizes default store and local store id`() {
        val scope = Task126OwnerStoreScope(
            ownerHash = "owner-hash",
            storeId = "",
            localStoreId = ""
        )

        assertEquals(Task126SyncPolicy.DEFAULT_STORE_ID, scope.storeId)
        assertEquals("local-${Task126SyncPolicy.DEFAULT_STORE_ID}", scope.localStoreId)
        assertEquals(126, scope.syncProtocolVersion)
    }

    @Test
    fun `owner mismatch and store mismatch fail closed`() {
        val entry = Task126OutboxEntryScope(
            ownerUserId = "owner-a",
            storeId = "store-a",
            localStoreId = "local-store-a"
        )

        assertEquals(
            Task126OwnerStoreGateDecision.Blocked(Task126OwnerStoreGateDecision.Reason.OwnerMismatch),
            Task126OwnerStoreGate.validate(entry, activeOwnerUserId = "owner-b", activeStoreId = "store-a")
        )
        assertEquals(
            Task126OwnerStoreGateDecision.Blocked(Task126OwnerStoreGateDecision.Reason.StoreMismatch),
            Task126OwnerStoreGate.validate(entry, activeOwnerUserId = "owner-a", activeStoreId = "store-b")
        )
    }

    @Test
    fun `matching owner and store allows outbox push`() {
        val entry = Task126OutboxEntryScope(
            ownerUserId = "owner-a",
            storeId = "store-a",
            localStoreId = "local-store-a"
        )

        assertEquals(
            Task126OwnerStoreGateDecision.Allowed,
            Task126OwnerStoreGate.validate(
                entry,
                activeOwnerUserId = "owner-a",
                activeStoreId = "store-a",
                activeLocalStoreId = "local-store-a"
            )
        )
    }
}

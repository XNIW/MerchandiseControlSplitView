package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEventContractTest {

    @Test
    fun `event type must match its domain exactly`() {
        assertTrue(
            SyncEventContract.hasSupportedEventType(
                SyncEventDomains.CATALOG,
                SyncEventTypes.CATALOG_TOMBSTONE
            )
        )
        assertTrue(
            SyncEventContract.hasSupportedEventType(
                SyncEventDomains.PRICES,
                SyncEventTypes.PRICES_CHANGED
            )
        )
        assertTrue(
            SyncEventContract.hasSupportedEventType(
                SyncEventDomains.HISTORY,
                SyncEventTypes.HISTORY_TOMBSTONE
            )
        )
        assertFalse(
            SyncEventContract.hasSupportedEventType(
                SyncEventDomains.CATALOG,
                SyncEventTypes.PRICES_CHANGED
            )
        )
        assertFalse(
            SyncEventContract.hasSupportedEventType(
                SyncEventDomains.CATALOG,
                "catalog_changed "
            )
        )
    }

    @Test
    fun `prices accepts multiple primary rows sharing one auxiliary product`() {
        val ids = SyncEventEntityIds(
            priceIds = listOf(uuid(1), uuid(2), uuid(3)),
            productIds = listOf(uuid(10))
        )

        assertTrue(
            SyncEventContract.hasCompletePrimaryIds(
                domain = SyncEventDomains.PRICES,
                changedCount = 3,
                ids = ids
            )
        )
    }

    @Test
    fun `prices rejects more auxiliary products than primary price rows`() {
        val ids = SyncEventEntityIds(
            priceIds = listOf(uuid(1)),
            productIds = listOf(uuid(10), uuid(11))
        )

        assertFalse(
            SyncEventContract.hasCompletePrimaryIds(
                domain = SyncEventDomains.PRICES,
                changedCount = 1,
                ids = ids
            )
        )
    }

    @Test
    fun `prices rejects auxiliary products when changed count is zero`() {
        val ids = SyncEventEntityIds(productIds = listOf(uuid(10)))

        assertFalse(
            SyncEventContract.hasCompletePrimaryIds(
                domain = SyncEventDomains.PRICES,
                changedCount = 0,
                ids = ids
            )
        )
    }

    private fun uuid(index: Int): String =
        "13900000-0000-4000-8000-${index.toString().padStart(12, '0')}"
}

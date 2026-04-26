package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabaseSyncEventRemoteDataSourceTest {

    @Test
    fun `record sync event decoder accepts object response from rpc`() {
        val row = decodeRecordSyncEventResponse(syncEventObjectJson)

        assertEquals(42L, row.id)
        assertEquals("00000000-0000-4000-8000-000000000001", row.ownerUserId)
        assertEquals(SyncEventDomains.CATALOG, row.domain)
        assertEquals(SyncEventTypes.CATALOG_CHANGED, row.eventType)
        assertEquals("android-batch-catalog_changed-0", row.clientEventId)
        assertEquals(1, row.changedCount)
        assertEquals(listOf("00000000-0000-4000-8000-000000000101"), row.entityIds?.productIds)
        assertNull(row.storeId)
    }

    @Test
    fun `record sync event decoder accepts array response from rpc`() {
        val row = decodeRecordSyncEventResponse("[$syncEventObjectJson]")

        assertEquals(42L, row.id)
        assertEquals(SyncEventTypes.CATALOG_CHANGED, row.eventType)
    }

    @Test
    fun `record sync event decoder rejects empty array`() {
        assertThrows(SerializationException::class.java) {
            decodeRecordSyncEventResponse("[]")
        }
    }

    private companion object {
        private val syncEventObjectJson = """
            {
              "id": 42,
              "owner_user_id": "00000000-0000-4000-8000-000000000001",
              "store_id": null,
              "domain": "catalog",
              "event_type": "catalog_changed",
              "source": "android",
              "source_device_id": "00000000-0000-4000-8000-000000000002",
              "batch_id": "00000000-0000-4000-8000-000000000003",
              "client_event_id": "android-batch-catalog_changed-0",
              "changed_count": 1,
              "entity_ids": {
                "supplier_ids": [],
                "category_ids": [],
                "product_ids": ["00000000-0000-4000-8000-000000000101"],
                "price_ids": []
              },
              "created_at": "2026-04-26T12:00:00Z",
              "expires_at": null,
              "metadata": {
                "task": "045"
              }
            }
        """.trimIndent()
    }
}

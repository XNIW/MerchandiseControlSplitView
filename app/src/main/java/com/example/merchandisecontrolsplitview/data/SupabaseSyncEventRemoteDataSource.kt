package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc

private const val SYNC_EVENTS_TABLE = "sync_events"

class SupabaseSyncEventRemoteDataSource(
    private val client: SupabaseClient?
) : SyncEventRemoteDataSource {

    override val isConfigured: Boolean get() = client != null

    private fun requireClient(): SupabaseClient =
        client ?: error("Supabase non configurato")

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        runCatching {
            if (client == null) {
                return@runCatching SyncEventRemoteCapabilities.disabled("supabase_client_missing")
            }
            client.postgrest[SYNC_EVENTS_TABLE].select {
                filter {
                    eq("owner_user_id", ownerUserId)
                }
                order("id", Order.ASCENDING)
                range(0, 0)
            }.decodeList<SyncEventRemoteRow>()
            SyncEventRemoteCapabilities(
                syncEventsAvailable = true,
                recordSyncEventAvailable = true,
                realtimeSyncEventsAvailable = true
            )
        }.recoverCatching {
            SyncEventRemoteCapabilities.disabled("sync_events_schema_or_rls_unavailable")
        }

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
        runCatching {
            requireClient()
                .postgrest
                .rpc("record_sync_event", params)
                .decodeSingle()
        }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        runCatching {
            require(limit in 1L..500L) { "sync event fetch limit out of range" }
            requireClient().postgrest[SYNC_EVENTS_TABLE].select {
                filter {
                    eq("owner_user_id", ownerUserId)
                    gt("id", afterId)
                    if (storeId == null) {
                        filter("store_id", FilterOperator.IS, "null")
                    } else {
                        eq("store_id", storeId)
                    }
                }
                order("id", Order.ASCENDING)
                range(0, limit - 1)
            }.decodeList()
        }
}

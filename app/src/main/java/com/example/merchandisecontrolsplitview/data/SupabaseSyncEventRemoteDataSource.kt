package com.example.merchandisecontrolsplitview.data

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc

private const val SYNC_EVENTS_TABLE = "sync_events"
private const val TAG = "CatalogCloudSync"

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

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
        val result = runCatching {
            requireClient()
                .postgrest
                .rpc("record_sync_event", params)
                .decodeAs<SyncEventRemoteRow>()
        }
        result.exceptionOrNull()?.let { error ->
            val classification = SyncErrorClassifier.classify(error)
            Log.w(
                TAG,
                "sync_event_record_failure domain=${params.domain} eventType=${params.eventType} " +
                    "changedCount=${params.changedCount} errClass=${error::class.java.simpleName} " +
                    "errCategory=${classification.category} httpStatus=${classification.httpStatus} " +
                    "postgrestCode=${classification.postgrestCode ?: (error as? PostgrestRestException)?.code ?: "none"} " +
                    "message=${redactRpcError(error.message)}"
            )
        }
        return result
    }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        fetchSyncEventsAfter(
            ownerUserId = ownerUserId,
            storeId = storeId,
            shopId = null,
            afterId = afterId,
            limit = limit
        )

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        shopId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        runCatching {
            require(limit in 1L..500L) { "sync event fetch limit out of range" }
            requireClient().postgrest[SYNC_EVENTS_TABLE].select {
                filter {
                    gt("id", afterId)
                    if (!shopId.isNullOrBlank()) {
                        eq("shop_id", shopId)
                    } else if (storeId == null) {
                        eq("owner_user_id", ownerUserId)
                        filter("store_id", FilterOperator.IS, "null")
                    } else {
                        eq("owner_user_id", ownerUserId)
                        eq("store_id", storeId)
                    }
                }
                order("id", Order.ASCENDING)
                range(0, limit - 1)
            }.decodeList()
        }

    private fun redactRpcError(message: String?): String {
        if (message.isNullOrBlank()) return "none"
        return message
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer <redacted>")
            .replace(Regex("https?://\\S+"), "<url-redacted>")
            .take(240)
    }
}

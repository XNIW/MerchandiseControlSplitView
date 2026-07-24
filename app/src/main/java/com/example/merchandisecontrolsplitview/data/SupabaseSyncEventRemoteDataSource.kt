package com.example.merchandisecontrolsplitview.data

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

private const val TAG = "CatalogCloudSync"
private const val STRICT_SYNC_EVENT_RPC = "record_sync_event_v6"
private const val LEGACY_SYNC_EVENT_RPC = "record_sync_event"
private const val LEGACY_SYNC_EVENT_METADATA_MAX_BYTES = 4_096
private const val LEGACY_SYNC_EVENT_ENTITY_IDS_MAX_BYTES = 16_384

internal class SyncEventRpcCodedException(
    val rpcCode: String,
    cause: Throwable? = null
) : RuntimeException("sync event RPC failed with code $rpcCode", cause)

class SupabaseSyncEventRemoteDataSource private constructor(
    @Suppress("UNUSED_PARAMETER") client: SupabaseClient?,
    private val configured: Boolean,
    private val invokeRpc: suspend (String, JsonObject) -> Result<SyncEventRemoteRow>
) : SyncEventRemoteDataSource {

    constructor(client: SupabaseClient?) : this(
        client = client,
        configured = client != null,
        invokeRpc = syncEventRpcInvoker(client)
    )

    internal constructor(
        configured: Boolean = true,
        invokeRpc: suspend (String, JsonObject) -> Result<SyncEventRemoteRow>
    ) : this(
        client = null,
        configured = configured,
        invokeRpc = invokeRpc
    )

    override val isConfigured: Boolean get() = configured

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        if (!configured) {
            Result.success(SyncEventRemoteCapabilities.disabled("supabase_client_missing"))
        } else {
            // Il backend revoca SELECT authenticated su public.sync_events. La
            // disponibilita' del read path viene quindi provata dalla prima
            // shop_sync_event_page_v1 nel reader shop-scoped, mai sondando la tabella.
            Result.success(
                SyncEventRemoteCapabilities(
                    syncEventsAvailable = true,
                    recordSyncEventAvailable = true,
                    realtimeSyncEventsAvailable = false
                )
            )
        }

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
        val strictPayload = Json.encodeToJsonElement(
            SyncEventRecordRpcParams.serializer(),
            params
        ) as JsonObject
        val strictResult = invokeRpc(STRICT_SYNC_EVENT_RPC, strictPayload)
        val strictError = strictResult.exceptionOrNull()
        val result = if (
            strictError != null &&
            strictError.isMissingStrictSyncEventRpc() &&
            params.isLegacySyncEventCompatible()
        ) {
            invokeRpc(
                LEGACY_SYNC_EVENT_RPC,
                JsonObject(strictPayload.filterKeys { it != "p_shop_id" })
            )
        } else {
            strictResult
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
    ): Result<List<SyncEventRemoteRow>> = Result.failure(
        ShopSyncContractException("sync_event_direct_read_forbidden")
    )

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        shopId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> = Result.failure(
        ShopSyncContractException("sync_event_direct_read_forbidden")
    )

    private fun redactRpcError(message: String?): String {
        if (message.isNullOrBlank()) return "none"
        return message
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer <redacted>")
            .replace(Regex("https?://\\S+"), "<url-redacted>")
            .take(240)
    }
}

private fun syncEventRpcInvoker(
    client: SupabaseClient?
): suspend (String, JsonObject) -> Result<SyncEventRemoteRow> = { functionName, payload ->
    runCatching {
        requireNotNull(client) { "Supabase non configurato" }
            .postgrest
            .rpc(functionName, payload)
            .decodeAs<SyncEventRemoteRow>()
    }
}

private fun Throwable.isMissingStrictSyncEventRpc(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .mapNotNull { error ->
            when (error) {
                is PostgrestRestException -> error.code
                is SyncEventRpcCodedException -> error.rpcCode
                else -> null
            }
        }
        .any { it == "PGRST202" || it == "42883" }

/**
 * The legacy writer predates the shop lease/fence contract. It is only safe
 * for a genuinely unscoped event that already fits every legacy validation
 * and payload bound. Any uncertainty leaves the strict failure untouched so
 * the repository retains/retries the durable outbox row.
 */
internal fun SyncEventRecordRpcParams.isLegacySyncEventCompatible(): Boolean {
    if (shopId != null) return false
    val ids = entityIds ?: return false
    if (!SyncEventContract.hasCompletePrimaryIds(domain, changedCount, ids)) return false
    if (storeId != null && !LEGACY_SYNC_EVENT_UUID_PATTERN.matches(storeId)) return false
    if (batchId != null && !LEGACY_SYNC_EVENT_UUID_PATTERN.matches(batchId)) return false
    if (sourceDeviceId != null && sourceDeviceId.length > 160) return false
    if (clientEventId != null && clientEventId.length > 160) return false
    if (metadata.keys.any { it in LEGACY_SYNC_EVENT_FORBIDDEN_METADATA_KEYS }) return false
    if (metadata.keys.any { it in LEGACY_SYNC_EVENT_SHOP_SEMANTIC_KEYS }) return false
    if (Json.encodeToString(metadata).encodeToByteArray().size >
        LEGACY_SYNC_EVENT_METADATA_MAX_BYTES
    ) {
        return false
    }
    if (Json.encodeToString(ids).encodeToByteArray().size >
        LEGACY_SYNC_EVENT_ENTITY_IDS_MAX_BYTES
    ) {
        return false
    }
    return true
}

private val LEGACY_SYNC_EVENT_UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
        "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)

private val LEGACY_SYNC_EVENT_FORBIDDEN_METADATA_KEYS = setOf(
    "barcode",
    "email",
    "excel",
    "path",
    "price",
    "product_name",
    "supplier_name",
    "category_name",
    "token"
)

private val LEGACY_SYNC_EVENT_SHOP_SEMANTIC_KEYS = setOf(
    "authorized_shop_id",
    "shop_id",
    "shop_scope",
    "scope_key",
    "store_scope"
)

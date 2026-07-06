package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

object SyncEventDomains {
    const val CATALOG = "catalog"
    const val PRICES = "prices"
    const val HISTORY = "history"
}

object SyncEventTypes {
    const val CATALOG_CHANGED = "catalog_changed"
    const val PRICES_CHANGED = "prices_changed"
    const val CATALOG_TOMBSTONE = "catalog_tombstone"
    const val PRICES_TOMBSTONE = "prices_tombstone"
    const val HISTORY_CHANGED = "history_changed"
    const val HISTORY_TOMBSTONE = "history_tombstone"
}

object SyncEventApplyStatusValues {
    const val APPLIED = "applied"
    const val BLOCKED = "blocked"
    const val SKIPPED = "skipped"
    const val RETRYING = "retrying"
}

object SyncEventApplyStatusReasons {
    const val APPLIED = "applied"
    const val SELF_ORIGIN = "self_origin"
    const val PROTECTED_LOCAL_COMMIT = "protected_local_commit"
    const val DIRTY_LOCAL = "dirty_local"
    const val MISSING_ENTITY_IDS = "missing_entity_ids"
    const val ENTITY_IDS_TOO_LARGE = "entity_ids_too_large"
    const val MISSING_REMOTE = "missing_remote"
    const val UNSUPPORTED_DOMAIN = "unsupported_domain"
}

@Serializable
data class SyncEventEntityIds(
    @SerialName("supplier_ids")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val supplierIds: List<String> = emptyList(),
    @SerialName("category_ids")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val categoryIds: List<String> = emptyList(),
    @SerialName("product_ids")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val productIds: List<String> = emptyList(),
    @SerialName("price_ids")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val priceIds: List<String> = emptyList(),
    @SerialName("session_ids")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val sessionIds: List<String> = emptyList()
) {
    val totalIds: Int
        get() = supplierIds.size + categoryIds.size + productIds.size + priceIds.size + sessionIds.size

    val isEmpty: Boolean
        get() = totalIds == 0
}

@Serializable
data class SyncEventRemoteRow(
    val id: Long,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    @SerialName("store_id") val storeId: String? = null,
    val domain: String,
    @SerialName("event_type") val eventType: String,
    val source: String? = null,
    @SerialName("source_device_id") val sourceDeviceId: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("client_event_id") val clientEventId: String? = null,
    @SerialName("changed_count") val changedCount: Int = 0,
    @SerialName("entity_ids") val entityIds: SyncEventEntityIds? = null,
    @SerialName("created_at") val createdAt: String,
    val metadata: JsonObject = buildJsonObject { }
)

@Serializable
data class SyncEventRecordRpcParams(
    @SerialName("p_domain") val domain: String,
    @SerialName("p_event_type") val eventType: String,
    @SerialName("p_changed_count") val changedCount: Int,
    @SerialName("p_entity_ids") val entityIds: SyncEventEntityIds?,
    @SerialName("p_store_id") val storeId: String? = null,
    @SerialName("p_source") val source: String?,
    @SerialName("p_source_device_id") val sourceDeviceId: String? = null,
    @SerialName("p_batch_id") val batchId: String? = null,
    @SerialName("p_client_event_id") val clientEventId: String? = null,
    @SerialName("p_metadata") val metadata: JsonObject = buildJsonObject { },
    @SerialName("p_shop_id") val shopId: String? = null
)

data class SyncEventRemoteCapabilities(
    val syncEventsAvailable: Boolean,
    val recordSyncEventAvailable: Boolean,
    val realtimeSyncEventsAvailable: Boolean,
    val fallbackReason: String? = null
) {
    companion object {
        fun disabled(reason: String): SyncEventRemoteCapabilities =
            SyncEventRemoteCapabilities(
                syncEventsAvailable = false,
                recordSyncEventAvailable = false,
                realtimeSyncEventsAvailable = false,
                fallbackReason = reason
            )
    }
}

interface SyncEventRemoteDataSource {
    val isConfigured: Boolean

    suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities>

    suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow>

    suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>>

    suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        shopId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        fetchSyncEventsAfter(ownerUserId, storeId, afterId, limit)
}

object DisabledSyncEventRemoteDataSource : SyncEventRemoteDataSource {
    override val isConfigured: Boolean = false

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        Result.success(SyncEventRemoteCapabilities.disabled("sync_events_remote_disabled"))

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
        Result.failure(IllegalStateException("sync_events remote disabled"))

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        Result.failure(IllegalStateException("sync_events remote disabled"))
}

@Entity(
    tableName = "sync_event_watermarks",
    primaryKeys = ["ownerUserId", "storeScope"]
)
data class SyncEventWatermark(
    val ownerUserId: String,
    val storeScope: String,
    val lastSyncEventId: Long
)

@Dao
interface SyncEventWatermarkDao {
    @Query(
        """
        SELECT * FROM sync_event_watermarks
        WHERE ownerUserId = :ownerUserId AND storeScope = :storeScope
        LIMIT 1
        """
    )
    suspend fun get(ownerUserId: String, storeScope: String): SyncEventWatermark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncEventWatermark)
}

@Entity(tableName = "sync_event_device_state")
data class SyncEventDeviceState(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    val createdAtMs: Long
)

@Dao
interface SyncEventDeviceStateDao {
    @Query("SELECT * FROM sync_event_device_state WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncEventDeviceState?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SyncEventDeviceState): Long
}

@Entity(
    tableName = "sync_event_outbox",
    indices = [
        Index(value = ["ownerUserId", "clientEventId"], unique = true),
        Index(value = ["ownerUserId", "createdAtMs"])
    ]
)
data class SyncEventOutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: String,
    val storeScope: String,
    val domain: String,
    val eventType: String,
    val source: String?,
    val sourceDeviceId: String?,
    val batchId: String?,
    val clientEventId: String,
    val changedCount: Int,
    val entityIdsJson: String,
    val metadataJson: String,
    val createdAtMs: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val lastErrorType: String? = null
)

@Dao
interface SyncEventOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SyncEventOutboxEntry): Long

    @Update
    suspend fun update(row: SyncEventOutboxEntry)

    @Query(
        """
        SELECT * FROM sync_event_outbox
        WHERE ownerUserId = :ownerUserId
        ORDER BY createdAtMs ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun listPending(ownerUserId: String, limit: Int): List<SyncEventOutboxEntry>

    @Query(
        """
        SELECT * FROM sync_event_outbox
        WHERE ownerUserId = :ownerUserId
          AND attemptCount < :maxAttempts
        ORDER BY createdAtMs ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun listPendingRetryable(
        ownerUserId: String,
        maxAttempts: Int,
        limit: Int
    ): List<SyncEventOutboxEntry>

    @Query(
        """
        SELECT * FROM sync_event_outbox
        WHERE ownerUserId = :ownerUserId
          AND storeScope = :storeScope
          AND attemptCount < :maxAttempts
        ORDER BY createdAtMs ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun listPendingRetryableForScope(
        ownerUserId: String,
        storeScope: String,
        maxAttempts: Int,
        limit: Int
    ): List<SyncEventOutboxEntry>

    @Query("SELECT COUNT(*) FROM sync_event_outbox WHERE ownerUserId = :ownerUserId")
    suspend fun countPending(ownerUserId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_event_outbox
        WHERE ownerUserId = :ownerUserId AND storeScope = :storeScope
        """
    )
    suspend fun countPendingForScope(ownerUserId: String, storeScope: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_event_outbox
        WHERE ownerUserId = :ownerUserId
          AND attemptCount >= :maxAttempts
        """
    )
    suspend fun countPendingAtOrAboveAttempts(ownerUserId: String, maxAttempts: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_event_outbox
        WHERE ownerUserId = :ownerUserId
          AND storeScope = :storeScope
          AND attemptCount >= :maxAttempts
        """
    )
    suspend fun countPendingAtOrAboveAttemptsForScope(
        ownerUserId: String,
        storeScope: String,
        maxAttempts: Int
    ): Int

    @Query("DELETE FROM sync_event_outbox WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Entity(
    tableName = "sync_event_apply_status",
    primaryKeys = ["owner_user_id", "store_scope", "event_id"],
    indices = [
        Index(value = ["owner_user_id", "store_scope", "status"]),
        Index(value = ["owner_user_id", "store_scope", "next_retry_at_ms"])
    ]
)
data class SyncEventApplyStatus(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "store_scope") val storeScope: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "shop_id") val shopId: String?,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "entity_type") val entityType: String?,
    @ColumnInfo(name = "entity_ids_json") val entityIdsJson: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long,
    @ColumnInfo(name = "next_retry_at_ms") val nextRetryAtMs: Long?,
    @ColumnInfo(name = "correlation_id") val correlationId: String?,
    @ColumnInfo(name = "client_event_id") val clientEventId: String?,
    @ColumnInfo(name = "remote_created_at") val remoteCreatedAt: String?
)

@Dao
interface SyncEventApplyStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncEventApplyStatus)

    @Query(
        """
        SELECT * FROM sync_event_apply_status
        WHERE owner_user_id = :ownerUserId
          AND store_scope = :storeScope
          AND event_id = :eventId
        LIMIT 1
        """
    )
    suspend fun get(ownerUserId: String, storeScope: String, eventId: Long): SyncEventApplyStatus?

    @Query(
        """
        SELECT * FROM sync_event_apply_status
        WHERE owner_user_id = :ownerUserId
          AND store_scope = :storeScope
          AND status = :status
        ORDER BY event_id ASC
        """
    )
    suspend fun listByStatus(
        ownerUserId: String,
        storeScope: String,
        status: String
    ): List<SyncEventApplyStatus>

    @Query(
        """
        SELECT COUNT(*) FROM sync_event_apply_status
        WHERE owner_user_id = :ownerUserId
          AND store_scope = :storeScope
          AND status = :status
        """
    )
    suspend fun countByStatus(ownerUserId: String, storeScope: String, status: String): Int
}

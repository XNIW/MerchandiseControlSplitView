package com.example.merchandisecontrolsplitview.data

import androidx.room.withTransaction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val BUSINESS_WRITE_EVENT = "LOCAL_BUSINESS_WRITE_V1"
internal const val BUSINESS_WRITE_MAX_BYTES = 64 * 1024 * 1024

data class BusinessWriteHeader(val id: Long, val payloadBytes: Long)

@Serializable
internal data class BusinessWriteRevision(
    val domain: String, val remoteId: String, val revision: Long,
    val fingerprint: String?, val remoteUpdatedAt: String? = null,
    val localPriceId: Long? = null
)

/** A local receipt for the exact existing wire call, never sent to record_sync_event. */
@Serializable
internal data class BusinessWritePayload(
    val kind: String,
    val suppliers: List<InventorySupplierRow> = emptyList(),
    val categories: List<InventoryCategoryRow> = emptyList(),
    val products: List<InventoryProductRow> = emptyList(),
    val prices: List<InventoryProductPriceRow> = emptyList(),
    val history: List<SharedSheetSessionUpsertRow> = emptyList(),
    val patchId: String? = null,
    val patch: InventoryProductPatch? = null,
    val revisions: List<BusinessWriteRevision>
)

@Serializable
internal data class BusinessWriteAttempt(
    val version: Int = 1, val owner: String, val store: String, val shop: String?,
    val device: String, val schema: Int = Task126SyncPolicy.LOCAL_SCHEMA_VERSION,
    val generation: String?, val payload: BusinessWritePayload,
    val protocol: Int = Task126SyncPolicy.SYNC_PROTOCOL_VERSION,
    val epoch: Int = Task126SyncPolicy.DEFAULT_STORE_EPOCH,
    val localStore: String = Task126OwnerStoreScope.normalizedLocalStoreId(null,
        Task126OwnerStoreScope.normalizedStoreId(store))
)

/** Validate the immutable typed call once, before either replay or recovery projection. */
internal fun validateBusinessWritePayload(attempt: BusinessWriteAttempt) {
    val p=attempt.payload
    val domain=when(p.kind) {
        "SUPPLIERS" -> "SUPPLIER"; "CATEGORIES" -> "CATEGORY"; "PRODUCTS", "PATCH" -> "PRODUCT"
        "PRICES" -> "PRICE"; "HISTORY" -> "HISTORY"
        else -> error("business_write_kind_invalid")
    }
    require((p.kind=="SUPPLIERS" || p.suppliers.isEmpty()) &&
        (p.kind=="CATEGORIES" || p.categories.isEmpty()) &&
        (p.kind=="PRODUCTS" || p.products.isEmpty()) &&
        (p.kind=="PRICES" || p.prices.isEmpty()) && (p.kind=="HISTORY" || p.history.isEmpty()) &&
        (p.kind=="PATCH" || (p.patch==null && p.patchId==null))) { "business_write_payload_invalid" }
    val ids=when(p.kind) {
        "SUPPLIERS" -> p.suppliers.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        "CATEGORIES" -> p.categories.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        "PRODUCTS" -> p.products.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        "PATCH" -> listOf(requireNotNull(p.patchId)).also { require(p.patch!=null && !p.patch.isEmpty) }
        "PRICES" -> p.prices.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        else -> p.history.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.remoteId }
    }
    require(ids.size in 1..500 && ids.all { it.isNotBlank() } && ids.distinct().size==ids.size &&
        ids.toSet()==p.revisions.map { it.remoteId }.toSet() && p.revisions.size==ids.size &&
        p.revisions.all { it.domain==domain && it.revision in 0..Int.MAX_VALUE.toLong() &&
            (if(domain=="PRICE") it.localPriceId!=null && it.localPriceId>0 else it.localPriceId==null) }) {
        "business_write_revision_invalid"
    }
}

/** Uses the existing durable outbox. A receipt and ACK update are committed atomically. */
internal class BusinessWriteOutbox(
    private val db: AppDatabase,
    private val requireScope: suspend () -> Unit,
    private val deviceId: suspend () -> String,
    private val acknowledge: suspend (BusinessWriteAttempt) -> Unit,
    private val rejectedWithoutCommit: (Throwable) -> Boolean
) {
    private val json = Json { encodeDefaults = true }
    private val dao get() = db.syncEventOutboxDao()

    suspend fun execute(owner: String, shop: String?, payload: BusinessWritePayload,
        send: suspend (BusinessWriteAttempt) -> Result<Unit>): Result<Unit> {
        val store = shop?.let { "shop:$it" }.orEmpty()
        val binding=db.businessDataScopeBindingDao().get()
        val attempt = BusinessWriteAttempt(owner=owner, store=store, shop=shop,
            device=deviceId(), generation=db.syncRecoveryBaselineDao().get()?.generationId, payload=payload,
            schema=binding?.schemaVersion ?: Task126SyncPolicy.LOCAL_SCHEMA_VERSION,
            protocol=binding?.syncProtocolVersion ?: Task126SyncPolicy.SYNC_PROTOCOL_VERSION,
            epoch=binding?.storeEpoch ?: Task126SyncPolicy.DEFAULT_STORE_EPOCH,
            localStore=binding?.localStoreId ?: Task126OwnerStoreScope.normalizedLocalStoreId(null,
                Task126OwnerStoreScope.normalizedStoreId(store)))
        validate(attempt, owner, store)
        val raw = json.encodeToString(attempt)
        require(raw.encodeToByteArray().size <= BUSINESS_WRITE_MAX_BYTES) { "business_write_payload_too_large" }
        val key = "business-write-v1:" + MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val entry = db.withTransaction {
            requireScope()
            dao.insert(SyncEventOutboxEntry(ownerUserId=owner, storeScope=store,
                domain=payload.kind, eventType=BUSINESS_WRITE_EVENT, source="local_business_write",
                sourceDeviceId=attempt.device, batchId=null, clientEventId=key,
                changedCount=payload.revisions.size, entityIdsJson="{}", metadataJson=raw,
                createdAtMs=System.currentTimeMillis()))
            requireNotNull(dao.getByOperationKey(owner,key)).also {
                require(it.metadataJson == raw && it.storeScope == store && it.sourceDeviceId == attempt.device) {
                    "business_write_receipt_mismatch"
                }
            }
        }
        return deliver(entry, attempt, send)
    }

    suspend fun replay(owner: String, shop: String?, kinds: Set<String>,
        send: suspend (BusinessWriteAttempt) -> Result<Unit>): List<BusinessWriteAttempt> {
        val store = shop?.let { "shop:$it" }.orEmpty()
        val completed = mutableListOf<BusinessWriteAttempt>()
        // Bound every read, including malformed receipts. Never skip an oversized predecessor.
        val headers = dao.listBusinessWriteHeaders(owner,store,kinds,100)
        for (header in headers) {
            require(header.payloadBytes in 1..BUSINESS_WRITE_MAX_BYTES.toLong()) { "business_write_payload_invalid" }
            val entry = requireNotNull(dao.getById(header.id))
            val attempt = json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
            validate(attempt,owner,store)
            require(entry.sourceDeviceId == attempt.device && entry.domain==attempt.payload.kind &&
                entry.clientEventId == operationKey(entry.metadataJson)) {
                "business_write_receipt_mismatch"
            }
            require(attempt.payload.kind in kinds) { "business_write_kind_mismatch" }
            deliver(entry,attempt,send).getOrThrow()
            completed += attempt
        }
        require(dao.listBusinessWriteHeaders(owner,store,kinds,1).isEmpty()) { "business_write_replay_budget_exhausted" }
        return completed
    }

    private suspend fun validate(attempt: BusinessWriteAttempt, owner: String, store: String) {
        requireScope()
        require(attempt.version == 1 && attempt.schema == Task126SyncPolicy.LOCAL_SCHEMA_VERSION &&
            attempt.owner == owner && attempt.store == store && attempt.device == deviceId() &&
            attempt.store == attempt.shop?.let { "shop:$it" }.orEmpty()) {
            "business_write_scope_mismatch"
        }
        db.businessDataScopeBindingDao().get()?.let {
            require(it.ownerHash == task126OwnerHash(owner) && it.storeId == Task126OwnerStoreScope.normalizedStoreId(store) &&
                it.schemaVersion == attempt.schema && it.syncProtocolVersion==attempt.protocol &&
                it.storeEpoch==attempt.epoch && it.localStoreId==attempt.localStore) { "business_write_binding_mismatch" }
        }
        validateBusinessWritePayload(attempt)
    }

    private fun operationKey(raw: String): String = "business-write-v1:" +
        MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun deliver(entry: SyncEventOutboxEntry, attempt: BusinessWriteAttempt,
        send: suspend (BusinessWriteAttempt) -> Result<Unit>): Result<Unit> {
        validate(attempt,entry.ownerUserId,entry.storeScope)
        val stamped = entry.copy(attemptCount=(entry.attemptCount.toLong()+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastAttemptAtMs=System.currentTimeMillis())
        db.withTransaction { requireScope(); dao.update(stamped) }
        val result = try { send(attempt) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        if (result.isSuccess) db.withTransaction {
            validate(attempt,entry.ownerUserId,entry.storeScope)
            require(dao.getById(entry.id)?.metadataJson == entry.metadataJson) { "business_write_receipt_changed" }
            acknowledge(attempt)
            dao.deleteById(entry.id)
        }
        else db.withTransaction {
            requireScope()
            if (result.exceptionOrNull()?.let(rejectedWithoutCommit) == true) dao.deleteById(entry.id)
            else dao.update(stamped.copy(lastErrorType="business_write_response_unconfirmed"))
        }
        return result
    }
}

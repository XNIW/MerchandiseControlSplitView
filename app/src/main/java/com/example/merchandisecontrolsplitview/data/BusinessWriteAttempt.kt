package com.example.merchandisecontrolsplitview.data

import androidx.room.withTransaction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val revisions: List<BusinessWriteRevision>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedUpdatedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedDeletedAt: String? = null
)

internal sealed interface BusinessWriteConfirmation {
    data object LegacyAccepted : BusinessWriteConfirmation
    data class ConditionalProduct(val row: InventoryProductRow) : BusinessWriteConfirmation
}

/** Zero rows describes only this dispatch. In particular it cannot confirm an earlier lost response. */
internal class BusinessWriteConditionalNoMatch(val readback: QualifiedCatalogConflictRemote) :
    IllegalStateException("business_write_conditional_no_match")

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
        "SUPPLIERS" -> "SUPPLIER"; "CATEGORIES" -> "CATEGORY"; "PRODUCTS", "PATCH", "PATCH_CONDITIONAL" -> "PRODUCT"
        "PRICES" -> "PRICE"; "HISTORY" -> "HISTORY"
        else -> error("business_write_kind_invalid")
    }
    require((p.kind=="SUPPLIERS" || p.suppliers.isEmpty()) &&
        (p.kind=="CATEGORIES" || p.categories.isEmpty()) &&
        (p.kind=="PRODUCTS" || p.products.isEmpty()) &&
        (p.kind=="PRICES" || p.prices.isEmpty()) && (p.kind=="HISTORY" || p.history.isEmpty()) &&
        (p.kind in setOf("PATCH", "PATCH_CONDITIONAL") || (p.patch==null && p.patchId==null))) { "business_write_payload_invalid" }
    require(if (p.kind == "PATCH_CONDITIONAL") {
        attempt.version == 2 && !p.expectedUpdatedAt.isNullOrBlank() &&
            p.expectedDeletedAt == null && p.revisions.size == 1 && p.patch?.deletedAt == null
    } else attempt.version == 1 && p.expectedUpdatedAt == null && p.expectedDeletedAt == null) {
        "business_write_format_invalid"
    }
    val ids=when(p.kind) {
        "SUPPLIERS" -> p.suppliers.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        "CATEGORIES" -> p.categories.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        "PRODUCTS" -> p.products.map { require(it.ownerUserId==attempt.owner && it.shopId==attempt.shop); it.id }
        "PATCH", "PATCH_CONDITIONAL" -> listOf(requireNotNull(p.patchId)).also { require(p.patch!=null && !p.patch.isEmpty) }
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
    // Serializes remote dispatch and decisions, not ordinary local Save or unrelated Room readers.
    private val deliveryOwner = Mutex()
    suspend fun <T> withResolutionOwner(block: suspend () -> T): T = deliveryOwner.withLock { block() }

    suspend fun execute(owner: String, shop: String?, payload: BusinessWritePayload,
        send: suspend (BusinessWriteAttempt) -> Result<Unit>): Result<Unit> =
        executeVerified(owner, shop, payload) { send(it).map { BusinessWriteConfirmation.LegacyAccepted } }

    suspend fun executeVerified(owner: String, shop: String?, payload: BusinessWritePayload,
        send: suspend (BusinessWriteAttempt) -> Result<BusinessWriteConfirmation>): Result<Unit> =
        deliveryOwner.withLock {
            val (entry, attempt) = seal(owner, shop, payload)
            deliver(entry, attempt, send)
        }

    /** Also called inside the resolution transaction; sealing does not dispatch. */
    suspend fun seal(owner: String, shop: String?, payload: BusinessWritePayload): Pair<SyncEventOutboxEntry, BusinessWriteAttempt> {
        val store = shop?.let { "shop:$it" }.orEmpty()
        val binding=db.businessDataScopeBindingDao().get()
        val attempt = BusinessWriteAttempt(version=if(payload.kind=="PATCH_CONDITIONAL") 2 else 1, owner=owner, store=store, shop=shop,
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
        PilotCommitTrace.b6Sealed(entry,attempt, lease = PilotCommitTrace.b6LeaseStamp(kotlin.coroutines.coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease))
        return entry to attempt
    }

    suspend fun replay(owner: String, shop: String?, kinds: Set<String>,
        send: suspend (BusinessWriteAttempt) -> Result<Unit>): List<BusinessWriteAttempt> =
        replayVerified(owner, shop, kinds) { send(it).map { BusinessWriteConfirmation.LegacyAccepted } }

    suspend fun replayVerified(owner: String, shop: String?, kinds: Set<String>,
        continueIndependentProducts: Boolean = false,
        send: suspend (BusinessWriteAttempt) -> Result<BusinessWriteConfirmation>): List<BusinessWriteAttempt> =
        deliveryOwner.withLock {
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
            val effective = loadBusinessResolutionSnapshot(db, owner, store)
            if (effective?.supersededAttempt(entry) == true) continue
            if (BusinessResolutionLedger.hasNoCommitProof(entry)) continue
            val delivered = deliver(entry, attempt, send)
            if (delivered.isSuccess) completed += attempt
            else if (!continueIndependentProducts || attempt.payload.revisions.any { it.domain != "PRODUCT" }) delivered.getOrThrow()
        }
        val residual = dao.listBusinessWriteHeaders(owner, store, kinds, 101)
        require(residual.size <= 100 && residual.all { header ->
            val remaining = requireNotNull(dao.getById(header.id))
            (continueIndependentProducts && json.decodeFromString<BusinessWriteAttempt>(remaining.metadataJson).payload.revisions.all { it.domain == "PRODUCT" }) ||
                BusinessResolutionLedger.hasNoCommitProof(remaining) ||
                loadBusinessResolutionSnapshot(db, owner, store)?.supersededAttempt(remaining) == true
        }) { "business_write_replay_budget_exhausted" }
        completed
    }

    private suspend fun validate(attempt: BusinessWriteAttempt, owner: String, store: String) {
        requireScope()
        require(attempt.version in 1..2 && attempt.schema == Task126SyncPolicy.LOCAL_SCHEMA_VERSION &&
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
        send: suspend (BusinessWriteAttempt) -> Result<BusinessWriteConfirmation>): Result<Unit> {
        validate(attempt,entry.ownerUserId,entry.storeScope)
        require(dao.getById(entry.id) == entry) { "business_write_receipt_changed" }
        val effective = loadBusinessResolutionSnapshot(db, entry.ownerUserId, entry.storeScope)
        if (effective?.supersededAttempt(entry) == true || BusinessResolutionLedger.hasNoCommitProof(entry)) {
            return Result.failure(IllegalStateException("business_write_requires_new_resolution"))
        }
        val stamped = entry.copy(attemptCount=(entry.attemptCount.toLong()+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastAttemptAtMs=System.currentTimeMillis())
        db.withTransaction { requireScope(); recordBusinessIntentDispatch(db, attempt, entry.clientEventId); dao.update(stamped) }
        PilotCommitTrace.b6Dispatched(stamped, lease = PilotCommitTrace.b6LeaseStamp(kotlin.coroutines.coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease))
        val result = try { send(attempt) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        PilotCommitTrace.b6Response(stamped,result.isSuccess, lease = PilotCommitTrace.b6LeaseStamp(kotlin.coroutines.coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease))
        if (result.isSuccess) {
          db.withTransaction {
            validate(attempt,entry.ownerUserId,entry.storeScope)
            require(dao.getById(entry.id)?.metadataJson == entry.metadataJson) { "business_write_receipt_changed" }
            val confirmation = result.getOrThrow()
            val acknowledgedAttempt = when (confirmation) {
                BusinessWriteConfirmation.LegacyAccepted -> {
                    require(attempt.payload.kind != "PATCH_CONDITIONAL") { "conditional_ack_missing" }
                    attempt
                }
                is BusinessWriteConfirmation.ConditionalProduct -> {
                    require(attempt.payload.kind == "PATCH_CONDITIONAL") { "conditional_ack_unexpected" }
                    val row = confirmation.row
                    require(row.id == attempt.payload.patchId && row.ownerUserId == attempt.owner &&
                        row.shopId == attempt.shop && !row.updatedAt.isNullOrBlank() && row.deletedAt == null) {
                        "conditional_ack_identity_invalid"
                    }
                    val patch = requireNotNull(attempt.payload.patch)
                    val expected = mapOf<String, Any?>("barcode" to patch.barcode, "itemnumber" to patch.itemNumber,
                        "productname" to patch.productName, "secondproductname" to patch.secondProductName,
                        "purchaseprice" to patch.purchasePrice, "retailprice" to patch.retailPrice,
                        "supplier" to patch.supplierId, "category" to patch.categoryId, "stockquantity" to patch.stockQuantity)
                    val returned = mapOf<String, Any?>("barcode" to row.barcode, "itemnumber" to row.itemNumber,
                        "productname" to row.productName, "secondproductname" to row.secondProductName,
                        "purchaseprice" to row.purchasePrice, "retailprice" to row.retailPrice,
                        "supplier" to row.supplierId, "category" to row.categoryId, "stockquantity" to row.stockQuantity)
                    require(patch.changedFields.all { it in expected && returned[it] == expected[it] }) {
                        "conditional_ack_values_mismatch"
                    }
                    // Only the ACK projection uses the actual returned version. The original seal is untouched.
                    attempt.copy(payload = attempt.payload.copy(revisions = listOf(
                        attempt.payload.revisions.single().copy(remoteUpdatedAt = row.updatedAt,
                            fingerprint = fingerprintProductInbound(row)))))
                }
            }
            acknowledge(acknowledgedAttempt)
            appendBusinessActualAcknowledgement(db, stamped, attempt, confirmation)
            dao.deleteById(entry.id)
            loadBusinessResolutionSnapshot(db, entry.ownerUserId, entry.storeScope)?.requireValid()
          }
          val b6AckReturnedNS=if(PilotCommitTrace.b6IsArmed()) PilotCommitTrace.now() else 0L
          PilotCommitTrace.b6OwnAck(stamped,result.getOrThrow(),b6AckReturnedNS, lease = PilotCommitTrace.b6LeaseStamp(kotlin.coroutines.coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease))
        }
        else db.withTransaction {
            requireScope()
            require(dao.getById(entry.id)?.metadataJson == entry.metadataJson) { "business_write_receipt_changed" }
            val error = result.exceptionOrNull()
            val sqlState = error?.let(SyncErrorClassifier::classify)?.postgrestCode
            val firstDispatch = entry.attemptCount == 0 && entry.lastAttemptAtMs == null && entry.lastErrorType == null
            val proof = if (firstDispatch && error is BusinessWriteConditionalNoMatch) {
                BusinessResolutionLedger.encodeNoCommitProof(stamped, conditionalZero = true)
            } else if (firstDispatch && error != null && rejectedWithoutCommit(error) && sqlState in setOf("23503", "23505")) {
                BusinessResolutionLedger.encodeNoCommitProof(stamped, sqlState)
            } else null
            // A rejection of a replay never erases the uncertain first dispatch.
            dao.update(stamped.copy(lastErrorType = proof ?: "business_write_response_unconfirmed"))
        }
        return result.map { Unit }
    }
}

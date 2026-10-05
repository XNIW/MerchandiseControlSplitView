package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Sparse local readback proofs. Remote canonical A/C domains are never changed by an ACK. */
internal const val LOCAL_ACK_BODY_PREFIX = "local-ack-body-v1:"

@Serializable
internal data class LocalAcknowledgedBodyIdentity(
    val ownerHash: String, val store: String, val localStore: String,
    val protocol: Int, val schema: Int, val epoch: Int, val device: String,
    val revision: Long
) {
    fun matches(binding: BusinessDataScopeBinding, deviceId: String) =
        ownerHash==binding.ownerHash && store==binding.storeId && localStore==binding.localStoreId &&
            protocol==binding.syncProtocolVersion && schema==binding.schemaVersion && epoch==binding.storeEpoch &&
            device==deviceId && revision in 0..Int.MAX_VALUE.toLong()
}

internal fun localAcknowledgedPriceFingerprint(row: InventoryProductPriceRow): String {
    val body = JsonArray(listOf(JsonPrimitive(row.id),JsonPrimitive(row.productId),JsonPrimitive(row.type),
        JsonPrimitive(row.price),JsonPrimitive(row.effectiveAt),row.source?.let(::JsonPrimitive) ?: JsonNull,
        row.note?.let(::JsonPrimitive) ?: JsonNull,JsonPrimitive(row.createdAt)))
    return "local-price-body-v1:" + MessageDigest.getInstance("SHA-256").digest(body.toString().encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Retire only verified private price proofs before the legitimate parent's FK cascade. */
internal suspend fun retireAcknowledgedPriceBodiesForProductDelete(
    db: AppDatabase, productId: Long, productRemoteId: String
) {
    check(db.inTransaction()) { "local_ack_delete_requires_transaction" }
    val baseline = db.syncRecoveryBaselineDao().get() ?: return
    val binding = requireNotNull(db.businessDataScopeBindingDao().get()) { "local_ack_binding_missing" }
    val device = requireNotNull(db.syncEventDeviceStateDao().get()).deviceId
    require(baseline.ownerHash == binding.ownerHash && baseline.storeScope == binding.storeId &&
        baseline.deviceId == device) { "local_ack_scope_mismatch" }
    val domain = LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.PRICES.wireValue
    var afterId = ""
    while (true) {
        val ids = mutableListOf<String>()
        db.openHelper.writableDatabase.query(
            """
            SELECT m.remoteId, m.versionLine, m.payloadDigest, m.active, m.idLine,
                   p.type, p.price, p.effectiveAt, p.source, p.note, p.createdAt
            FROM sync_recovery_manifest m
            JOIN product_price_remote_refs r ON r.remoteId = m.remoteId
            JOIN product_prices p ON p.id = r.productPriceId
            JOIN product_remote_refs parent ON parent.productId = p.productId
            WHERE m.generationId = ? AND m.domain = ? AND m.remoteId > ?
              AND p.productId = ? AND parent.remoteId = ?
            ORDER BY m.remoteId LIMIT 256
            """.trimIndent(),
            arrayOf<Any>(baseline.generationId, domain, afterId, productId, productRemoteId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val version = cursor.getString(1)
                require(version.length <= 4096) { "local_ack_identity_invalid" }
                val identity = Json.decodeFromString<LocalAcknowledgedBodyIdentity>(version)
                require(identity.matches(binding, device) && identity.revision == 0L && cursor.getInt(3) == 1 && cursor.getString(4) == id) {
                    "local_ack_identity_mismatch"
                }
                val row = InventoryProductPriceRow(
                    id = id, ownerUserId = "", productId = productRemoteId,
                    type = cursor.getString(5), price = cursor.getDouble(6), effectiveAt = cursor.getString(7),
                    source = if (cursor.isNull(8)) null else cursor.getString(8),
                    note = if (cursor.isNull(9)) null else cursor.getString(9), createdAt = cursor.getString(10)
                )
                require(cursor.getString(2) == localAcknowledgedPriceFingerprint(row)) { "local_ack_delete_body_mismatch" }
                ids += id
            }
        }
        if (ids.isEmpty()) break
        db.syncRecoveryManifestDao().deleteByRemoteIds(baseline.generationId, domain, ids)
        afterId = ids.last()
    }
}

/** Called inside the existing actual ACK transaction, after captured-revision acknowledgement. */
internal suspend fun recordAcknowledgedLocalBodies(db: AppDatabase, attempt: BusinessWriteAttempt) {
    val baseline=db.syncRecoveryBaselineDao().get() ?: return
    val binding=db.businessDataScopeBindingDao().get() ?: error("local_ack_binding_missing")
    val device=requireNotNull(db.syncEventDeviceStateDao().get()).deviceId
    require(baseline.ownerHash==binding.ownerHash && baseline.storeScope==binding.storeId &&
        baseline.deviceId==device && device==attempt.device && binding.ownerHash==task126OwnerHash(attempt.owner) &&
        binding.storeId==Task126OwnerStoreScope.normalizedStoreId(attempt.store)) { "local_ack_scope_mismatch" }
    for (sent in attempt.payload.revisions) {
        val domain=when(sent.domain) {
            "PRODUCT" -> ShopSyncRowDomain.PRODUCTS
            "SUPPLIER" -> ShopSyncRowDomain.SUPPLIERS
            "CATEGORY" -> ShopSyncRowDomain.CATEGORIES
            "PRICE" -> ShopSyncRowDomain.PRICES
            "HISTORY" -> ShopSyncRowDomain.HISTORY
            else -> error("local_ack_domain_invalid")
        }
        // A late predecessor cannot replace the proof for a newer already acknowledged revision.
        val acknowledged=when(sent.domain) {
            "PRODUCT" -> db.productRemoteRefDao().getByRemoteId(sent.remoteId)?.lastSyncedLocalRevision?.toLong()
            "SUPPLIER" -> db.supplierRemoteRefDao().getByRemoteId(sent.remoteId)?.lastSyncedLocalRevision?.toLong()
            "CATEGORY" -> db.categoryRemoteRefDao().getByRemoteId(sent.remoteId)?.lastSyncedLocalRevision?.toLong()
            "HISTORY" -> db.historyEntryRemoteRefDao().getByRemoteId(sent.remoteId)?.lastSyncedLocalRevision?.toLong()
            else -> sent.revision.takeIf { db.productPriceRemoteRefDao().getByRemoteId(sent.remoteId)!=null }
        }
        if (acknowledged!=sent.revision) continue
        val fingerprint=if(sent.domain=="PRICE") localAcknowledgedPriceFingerprint(attempt.payload.prices.single { it.id==sent.remoteId })
            else requireNotNull(sent.fingerprint)
        val identity=LocalAcknowledgedBodyIdentity(binding.ownerHash,binding.storeId,binding.localStoreId,
            binding.syncProtocolVersion,binding.schemaVersion,binding.storeEpoch,device,sent.revision)
        val proofDomain=LOCAL_ACK_BODY_PREFIX+domain.wireValue
        val previous=db.syncRecoveryManifestDao().get(baseline.generationId,proofDomain,sent.remoteId)
        if (previous!=null) {
            require(previous.versionLine.length<=4096) { "local_ack_identity_invalid" }
            val old=Json.decodeFromString<LocalAcknowledgedBodyIdentity>(previous.versionLine)
            require(old.matches(binding,device)) { "local_ack_identity_mismatch" }
            if(old.revision>sent.revision) continue
        }
        db.syncRecoveryManifestDao().upsertAll(listOf(SyncRecoveryManifestRow(
            generationId=baseline.generationId,domain=proofDomain,remoteId=sent.remoteId,active=true,
            idLine=sent.remoteId,versionLine=Json.encodeToString(identity),payloadDigest=fingerprint)))
    }
}

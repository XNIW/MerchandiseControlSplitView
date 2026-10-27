package com.example.merchandisecontrolsplitview.data

/** Bounded reads include malformed predecessors. A limit failure never silently removes local work. */
internal suspend fun readLocalBusinessEntries(
    db: AppDatabase, owner: String, store: String
): List<SyncEventOutboxEntry> {
    val dao = db.syncEventOutboxDao()
    val entries = mutableListOf<SyncEventOutboxEntry>()
    var after = 0L
    var bytes = 0L
    while (true) {
        val page = dao.listLocalBusinessHeaders(owner, store, after, 32)
        if (page.isEmpty()) return entries
        for (header in page) {
            require(header.id > after && header.payloadBytes in 1..BUSINESS_WRITE_MAX_BYTES.toLong()) {
                "resolution_entry_bound_invalid"
            }
            bytes = Math.addExact(bytes, header.payloadBytes)
            require(bytes <= 128L * 1024 * 1024 && entries.size < 10_000) { "resolution_ledger_bound_exceeded" }
            entries += requireNotNull(dao.getById(header.id)) { "resolution_entry_changed" }
            after = header.id
        }
    }
}

/** One projection shared by repository, replay and recovery. Physical scope counts remain separate. */
internal suspend fun loadBusinessResolutionSnapshot(
    db: AppDatabase, owner: String, store: String,
    entries: List<SyncEventOutboxEntry>? = null
): BusinessResolutionLedger.Snapshot? {
    val currentEntries = entries ?: readLocalBusinessEntries(db, owner, store)
    val foreign = db.openHelper.readableDatabase.query(
        "SELECT 1 FROM sync_event_outbox WHERE eventType=? AND (ownerUserId<>? OR storeScope<>?) LIMIT 1",
        arrayOf(BUSINESS_RESOLUTION_EVENT, owner, store)).use { it.moveToFirst() }
    require(!foreign) { "resolution_foreign_scope_evidence" }
    val records = currentEntries.filter { it.eventType == BUSINESS_RESOLUTION_EVENT }
    if (records.isEmpty()) return null
    val binding = requireNotNull(db.businessDataScopeBindingDao().get()) { "resolution_binding_missing" }
    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get()) { "resolution_baseline_missing" }
    val device = requireNotNull(db.syncEventDeviceStateDao().get()).deviceId
    require(binding.ownerHash == task126OwnerHash(owner) && binding.storeId == store &&
        baseline.ownerHash == binding.ownerHash && baseline.storeScope == store && baseline.deviceId == device) {
        "resolution_scope_mismatch"
    }
    val scope = BusinessResolutionScope(owner, store, baseline.shopId, binding.localStoreId,
        binding.syncProtocolVersion, binding.schemaVersion, binding.storeEpoch, device)
    return BusinessResolutionLedger.evaluate(records, scope, baseline.generationId,
        decodeRecoveryCheckpointJson(baseline.checkpointJson).checkpointDigest,
        currentEntries.filter { it.eventType == BUSINESS_WRITE_EVENT }) { _, _ ->
        // The strict codec already proved every unsealed named revision's durable provenance,
        // including empty dispatch keys. This store read supplies its current Room journal.
        true
    }.requireValid()
}

/** The dispatch join is appended in the same transaction as the attempt stamp, before HTTP.
 * Older provenance may be referenced by a generation transfer and is never edited in place.
 */
internal suspend fun recordBusinessIntentDispatch(db: AppDatabase, attempt: BusinessWriteAttempt, key: String) {
    check(db.inTransaction()) { "resolution_dispatch_requires_transaction" }
    val snapshot = loadBusinessResolutionSnapshot(db, attempt.owner, attempt.store) ?: return
    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
    for (revision in attempt.payload.revisions.filter { it.domain == "PRODUCT" }) {
        val previous = snapshot.intents.singleOrNull {
            it.remoteId == revision.remoteId && it.intent!!.revision == revision.revision.toInt()
        } ?: continue
        require(snapshot.effectiveRevision(revision.remoteId, revision.revision.toInt()) ==
            BusinessResolutionLedger.EffectiveRevision.Active) { "resolution_withdrawn_dispatch_forbidden" }
        val intent = requireNotNull(previous.intent)
        if (key in intent.dispatchOperationKeys) continue
        val next = previous.copy(recordId = java.util.UUID.randomUUID().toString(),
            generationId = baseline.generationId,
            checkpointDigest = decodeRecoveryCheckpointJson(baseline.checkpointJson).checkpointDigest,
            intent = intent.copy(dispatchOperationKeys = intent.dispatchOperationKeys + key,
                supersedesProvenanceId = previous.recordId))
        require(db.syncEventOutboxDao().insert(BusinessResolutionLedger.entry(next, System.currentTimeMillis())) > 0)
    }
}

internal suspend fun appendBusinessResolutionTransfer(
    db: AppDatabase, owner: String, previous: SyncRecoveryBaseline?, preserved: List<SyncEventOutboxEntry>
) {
    check(db.inTransaction()) { "resolution_transfer_requires_transaction" }
    previous ?: return
    val active = requireNotNull(db.syncRecoveryBaselineDao().get())
    if (active.generationId == previous.generationId) return
    val records = preserved.filter { it.eventType == BUSINESS_RESOLUTION_EVENT }
    val originals = preserved.filter { it.eventType == BUSINESS_WRITE_EVENT }.filter {
        kotlinx.serialization.json.Json.decodeFromString<BusinessWriteAttempt>(it.metadataJson).generation == previous.generationId
    }
    if (records.isEmpty() && originals.isEmpty()) return
    val binding = requireNotNull(db.businessDataScopeBindingDao().get())
    val scope = BusinessResolutionScope(owner, binding.storeId, active.shopId, binding.localStoreId,
        binding.syncProtocolVersion, binding.schemaVersion, binding.storeEpoch, active.deviceId)
    val digest = decodeRecoveryCheckpointJson(active.checkpointJson).checkpointDigest
    val transfer = BusinessResolutionRecord(kind = "generation_transfer", recordId = java.util.UUID.randomUUID().toString(),
        scope = scope, generationId = active.generationId, checkpointDigest = digest, productId = null, remoteId = null,
        transfer = CatalogResolutionTransfer(previous.generationId,
            decodeRecoveryCheckpointJson(previous.checkpointJson).checkpointDigest, digest,
            records.map { entry -> java.security.MessageDigest.getInstance("SHA-256").digest(entry.metadataJson.encodeToByteArray())
                .joinToString("") { "%02x".format(it) } },
            originals.map { CatalogResolutionOriginalSeal(it.id, it.clientEventId, it.metadataJson,
                it.attemptCount, it.lastAttemptAtMs, it.lastErrorType) }))
    require(db.syncEventOutboxDao().insert(BusinessResolutionLedger.entry(transfer, System.currentTimeMillis())) > 0)
    loadBusinessResolutionSnapshot(db, owner, binding.storeId)?.requireValid()
}

internal data class BusinessResolutionPhysicalProof(
    val resolvedRemoteIds: Set<String>,
    val tombstoneRemoteIds: Set<String>,
    val acknowledgedFingerprints: Map<String, String?>
)

/** Validate resolved material against either the exact chosen projection or a later canonical read.
 * The receipt never substitutes for the actual physical row or advances an ACK watermark.
 */
internal suspend fun validateBusinessResolutionPhysical(db: AppDatabase): BusinessResolutionPhysicalProof {
    val scope = db.openHelper.readableDatabase.query(
        "SELECT ownerUserId,storeScope FROM sync_event_outbox WHERE eventType=? LIMIT 1",
        arrayOf(BUSINESS_RESOLUTION_EVENT)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
        ?: return BusinessResolutionPhysicalProof(emptySet(), emptySet(), emptyMap())
    val snapshot = requireNotNull(loadBusinessResolutionSnapshot(db, scope.first, scope.second))
    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
    val resolved = mutableSetOf<String>()
    val tombstones = mutableSetOf<String>()
    val acknowledged = mutableMapOf<String, String?>()
    for ((remoteId, records) in snapshot.decisions.groupBy { requireNotNull(it.remoteId) }) {
        val ref = db.productRemoteRefDao().getByRemoteId(remoteId)
        if (ref == null) {
            val tombstone = records.lastOrNull()?.decision?.choice == "adopt_remote_tombstone"
            val canonicalTombstone = db.syncRecoveryManifestDao().get(baseline.generationId,
                ShopSyncRowDomain.PRODUCTS.wireValue, remoteId)?.active == false
            require(tombstone || canonicalTombstone) { "resolution_projection_missing" }
            require(records.last().productId?.let { db.productDao().getById(it) } == null) {
                "resolution_tombstone_projection_changed"
            }
            // This is a locally adopted, qualified remote deletion. Preserve the old
            // canonical/actual-ACK rows; only their physical expectation is superseded.
            tombstones += remoteId
            continue
        }
        require(ref.lastSyncedLocalRevision <= ref.localChangeRevision) { "resolution_revision_invalid" }
        records.filter { it.decision!!.acknowledgedRevision == ref.lastSyncedLocalRevision }
            .minByOrNull { it.decision!!.conflictRevision }?.let {
                acknowledged[remoteId] = it.decision!!.originalFingerprint
            }
        val decision = records.singleOrNull { ref.localChangeRevision in it.decision!!.withdrawnRevisions }?.decision ?: continue
        val product = requireNotNull(db.productDao().getById(ref.productId)) { "resolution_projection_missing" }
        val supplier = product.supplierId?.let { requireNotNull(db.supplierRemoteRefDao().getBySupplierId(it)).remoteId }
        val category = product.categoryId?.let { requireNotNull(db.categoryRemoteRefDao().getByCategoryId(it)).remoteId }
        val exact = decision.projection?.product() == product && ref.remoteUpdatedAt == decision.remote.row?.updatedAt &&
            supplier == decision.remote.row?.supplierId && category == decision.remote.row?.categoryId
        if (!exact) {
            val row = InventoryProductRow(remoteId, scope.first, baseline.shopId, product.barcode,
                product.itemNumber, product.productName, product.secondProductName, product.purchasePrice,
                product.retailPrice, supplier, category, product.stockQuantity, product.primaryImageVersionId,
                product.primaryImageUpdatedAt, ref.remoteUpdatedAt)
            val physical = ShopSyncRows.Products(listOf(row)).toManifestRows(baseline.generationId, ShopSyncRowDomain.PRODUCTS).single()
            val canonical = db.syncRecoveryManifestDao().get(baseline.generationId, ShopSyncRowDomain.PRODUCTS.wireValue, remoteId)
            require(canonical != null && canonical.active && physical.payloadDigest == canonical.payloadDigest &&
                physical.versionLine == canonical.versionLine && physical.identityLine == canonical.identityLine) {
                "resolution_projection_unqualified"
            }
        }
        resolved += remoteId
    }
    return BusinessResolutionPhysicalProof(resolved, tombstones, acknowledged)
}

/** Retains only ACKs needed by an earlier rejected call; routine healthy ACKs do not grow this ledger.
 * The caller invokes this after actual ACK application and before exact attempt deletion in one Room transaction.
 */
internal suspend fun appendBusinessActualAcknowledgement(db: AppDatabase, stamped: SyncEventOutboxEntry,
    attempt: BusinessWriteAttempt, confirmation: BusinessWriteConfirmation) {
    check(db.inTransaction()) { "business_ack_receipt_requires_transaction" }
    if (attempt.shop == null || attempt.generation == null) return
    val entries = readLocalBusinessEntries(db, attempt.owner, attempt.store)
    val residualAcknowledgement = entries.filter { it.eventType == BUSINESS_RESOLUTION_EVENT }.any { entry ->
        BusinessResolutionLedger.decode(entry).decision?.residuals.orEmpty().any {
            it.newOperationKey == stamped.clientEventId && it.rawSuccessorAttempt == stamped.metadataJson
        }
    }
    val needed = residualAcknowledgement || entries.filter { it.eventType == BUSINESS_WRITE_EVENT && it.clientEventId != stamped.clientEventId &&
        BusinessResolutionLedger.hasNoCommitProof(it) }.any { old ->
        val original = kotlinx.serialization.json.Json.decodeFromString<BusinessWriteAttempt>(old.metadataJson)
        original.payload.revisions.any { BusinessResolutionLedger.equivalentAcknowledgedMember(original, it, attempt) }
    }
    if (!needed) return
    val binding = requireNotNull(db.businessDataScopeBindingDao().get()) { "business_ack_binding_missing" }
    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get()) { "business_ack_baseline_missing" }
    val scope = BusinessResolutionScope(attempt.owner, attempt.store, attempt.shop, binding.localStoreId,
        binding.syncProtocolVersion, binding.schemaVersion, binding.storeEpoch, attempt.device)
    require(binding.ownerHash == task126OwnerHash(attempt.owner) && binding.storeId == attempt.store &&
        baseline.ownerHash == binding.ownerHash && baseline.storeScope == attempt.store && baseline.deviceId == attempt.device)
    val receipt = BusinessResolutionRecord(kind = "actual_ack", recordId = java.util.UUID.randomUUID().toString(),
        scope = scope, generationId = baseline.generationId,
        checkpointDigest = decodeRecoveryCheckpointJson(baseline.checkpointJson).checkpointDigest,
        productId = null, remoteId = null,
        acknowledgement = CatalogActualAcknowledgement(CatalogResolutionOriginalSeal(stamped.id, stamped.clientEventId,
            stamped.metadataJson, stamped.attemptCount, stamped.lastAttemptAtMs, stamped.lastErrorType),
            (confirmation as? BusinessWriteConfirmation.ConditionalProduct)?.row))
    require(db.syncEventOutboxDao().insert(BusinessResolutionLedger.entry(receipt, System.currentTimeMillis())) > 0)
}

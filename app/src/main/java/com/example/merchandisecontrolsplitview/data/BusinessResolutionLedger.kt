package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Codec and read-only projection of local receipts in sync_event_outbox; no separate store or ACK. */
internal object BusinessResolutionLedger {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val fields = setOf("barcode", "itemnumber", "productname", "secondproductname",
        "purchaseprice", "retailprice", "supplier", "category", "stockquantity")
    private const val KEY_PREFIX = "business-resolution-v1:"
    private const val WRITE_PREFIX = "business-write-v1:"

    enum class EffectiveRevision { Active, SupersededLocally, Invalid }

    class Snapshot internal constructor(
        val records: List<BusinessResolutionRecord>,
        val invalidReason: String?,
        private val scope: BusinessResolutionScope,
        private val withdrawn: Map<Pair<String, Int>, BusinessResolutionRecord>,
        val intents: List<BusinessResolutionRecord>,
        private val residualCoverage: Map<Triple<String, String, Int>, BusinessResolutionRecord>
    ) {
        val decisions: List<BusinessResolutionRecord> = records.filter { it.kind == "decision" }
        val isValid: Boolean get() = invalidReason == null

        fun requireValid(): Snapshot = apply { check(isValid) { "business_resolution_invalid" } }

        fun effectiveRevision(remoteId: String, revision: Int): EffectiveRevision = when {
            !isValid || remoteId.isBlank() || revision < 0 -> EffectiveRevision.Invalid
            withdrawn.containsKey(remoteId to revision) -> EffectiveRevision.SupersededLocally
            else -> EffectiveRevision.Active
        }

        private val acknowledgements: List<BusinessWriteAttempt> = records.mapNotNull { record ->
            record.acknowledgement?.let { readAttempt(sealEntry(it.seal)) }
        }

        fun hasActualAcknowledgement(entry: SyncEventOutboxEntry, revision: BusinessWriteRevision): Boolean =
            isValid && hasNoCommitProof(entry) && runCatching {
                val original = readAttempt(entry)
                require(attemptScope(original) == scope && revision in original.payload.revisions)
                acknowledgements.any { acknowledged -> equivalentAcknowledgedMember(original, revision, acknowledged) } ||
                    residualCoverage[Triple(entry.clientEventId, revision.remoteId, revision.revision.toInt())]?.decision?.residuals
                        .orEmpty().any { residual ->
                            residual.originalOperationKey == entry.clientEventId && residual.remoteId == revision.remoteId &&
                                residual.revision == revision.revision.toInt() && records.any { record -> record.acknowledgement?.let {
                                    it.seal.operationKey == residual.newOperationKey && it.seal.rawAttempt == residual.rawSuccessorAttempt
                                } == true }
                        }
            }.getOrDefault(false)

        /** A partial product decision cannot retire another member of the immutable batch. */
        fun supersededAttempt(entry: SyncEventOutboxEntry): Boolean {
            if (!isValid) return false
            return runCatching {
                val attempt = readAttempt(entry)
                require(attemptScope(attempt) == scope)
                attempt.payload.revisions.all { revision ->
                    if (hasActualAcknowledgement(entry, revision)) return@all true
                    if (revision.domain != "PRODUCT") return@all false
                    val direct = withdrawn[revision.remoteId to revision.revision.toInt()]?.decision
                    val residual = residualCoverage[Triple(entry.clientEventId, revision.remoteId, revision.revision.toInt())]?.decision
                    listOfNotNull(direct, residual).any { decision -> decision.originalSeals.any { seal ->
                        seal.operationKey == entry.clientEventId && matches(seal, entry) &&
                            (if (seal.dispatchCount == 0) entry.lastErrorType == null
                             else hasNoCommitProof(entry))
                    } }
                }
            }.getOrDefault(false)
        }
    }

    fun encode(record: BusinessResolutionRecord): String {
        validateRecord(record)
        return json.encodeToString(record).also { require(it.encodeToByteArray().size <= BUSINESS_WRITE_MAX_BYTES) }
    }

    fun entry(record: BusinessResolutionRecord, createdAtMs: Long): SyncEventOutboxEntry {
        require(createdAtMs >= 0)
        val raw = encode(record)
        return SyncEventOutboxEntry(ownerUserId = record.scope.owner, storeScope = record.scope.store,
            domain = "PRODUCT", eventType = BUSINESS_RESOLUTION_EVENT, source = "local_business_resolution",
            sourceDeviceId = record.scope.device, batchId = hash(raw), clientEventId = KEY_PREFIX + record.recordId,
            changedCount = 0, entityIdsJson = "{}", metadataJson = raw, createdAtMs = createdAtMs)
    }

    fun decode(entry: SyncEventOutboxEntry): BusinessResolutionRecord {
        require(entry.metadataJson.encodeToByteArray().size in 1..BUSINESS_WRITE_MAX_BYTES)
        val record = json.decodeFromString<BusinessResolutionRecord>(entry.metadataJson)
        // Missing default/null fields, duplicate keys, alternate encodings and unknown keys fail closed.
        require(encode(record) == entry.metadataJson) { "business_resolution_noncanonical" }
        require(entry.eventType == BUSINESS_RESOLUTION_EVENT && entry.domain == "PRODUCT" &&
            entry.source == "local_business_resolution" && entry.ownerUserId == record.scope.owner &&
            entry.storeScope == record.scope.store && entry.sourceDeviceId == record.scope.device &&
            entry.clientEventId == KEY_PREFIX + record.recordId && entry.batchId == hash(entry.metadataJson) &&
            entry.changedCount == 0 && entry.entityIdsJson == "{}" && entry.createdAtMs >= 0 &&
            entry.attemptCount == 0 && entry.lastAttemptAtMs == null && entry.lastErrorType == null) {
            "business_resolution_envelope_invalid"
        }
        return record
    }

    /** The callback is only for revisions with no seal, after proving their durable writer journal.
     * It defaults to refusal: missing ACK/seal and lastAttemptAt=null are never sufficient evidence.
     */
    fun evaluate(
        entries: List<SyncEventOutboxEntry>,
        scope: BusinessResolutionScope,
        generationId: String,
        checkpointDigest: String,
        originals: List<SyncEventOutboxEntry>,
        validateNeverDispatched: (BusinessResolutionRecord, List<BusinessResolutionRecord>) -> Boolean = { _, _ -> false }
    ): Snapshot = try {
        validateScope(scope)
        require(generationId.isNotBlank() && checkpointDigest.isNotBlank())
        val records = entries.map(::decode)
        require(records.map { it.recordId }.distinct().size == records.size)
        require(records.all { it.scope == scope }) { "business_resolution_scope_mismatch" }
        val parsedOriginals = originals.map { it to readAttempt(it) }
        val lineage = validateTransfers(records, generationId, scope, parsedOriginals)
        records.filter { it.kind == "actual_ack" }.forEach { record ->
            val acknowledgement = requireNotNull(record.acknowledgement)
            val acknowledged = readAttempt(sealEntry(acknowledgement.seal))
            require(attemptScope(acknowledged) == scope && acknowledged.generation in lineage &&
                lineage.getValue(requireNotNull(acknowledged.generation)) <= lineage.getValue(record.generationId))
            // ACK deletion and this receipt must be atomic. A still-live same operation is not a completed ACK.
            require(originals.none { it.clientEventId == acknowledgement.seal.operationKey }) {
                "business_resolution_ack_operation_still_pending"
            }
        }
        val byKey = entries.associateBy { it.clientEventId }
        require(originals.map { it.clientEventId }.distinct().size == originals.size)
        val originalByKey = originals.associateBy { it.clientEventId }
        val allIntents = records.filter { it.kind == "intent_provenance" }
        val intents = validateProvenanceChains(allIntents, lineage)
        val byRevision = intents.associateBy { it.remoteId to it.intent!!.revision }
        val decisionById = records.filter { it.kind == "decision" }.associateBy { it.recordId }
        allIntents.forEach { record ->
            record.intent!!.priorResolutionIds.forEach { id ->
                val prior = requireNotNull(decisionById[id]) { "business_resolution_prior_missing" }
                require(prior.remoteId == record.remoteId && prior.productId == record.productId &&
                    prior.decision!!.conflictRevision <= record.intent.parentRevision &&
                    lineage.getValue(prior.generationId) <= lineage.getValue(record.generationId))
            }
        }
        val withdrawn = mutableMapOf<Pair<String, Int>, BusinessResolutionRecord>()
        val residualCoverage = mutableMapOf<Triple<String, String, Int>, BusinessResolutionRecord>()
        val residualKeys = mutableSetOf<String>()
        records.filter { it.kind == "decision" }.forEach { record ->
            val decision = requireNotNull(record.decision)
            val namedKeys = decision.originalSeals.map { it.operationKey }.toSet()
            val pendingKeys = parsedOriginals.filter { (_, attempt) -> attempt.payload.revisions.any {
                it.domain == "PRODUCT" && it.remoteId == record.remoteId && it.revision.toInt() in decision.withdrawnRevisions
            } }.map { it.first.clientEventId }
            val residualKeysForRevision = records.flatMap { it.decision?.residuals.orEmpty() }.filter {
                it.remoteId == record.remoteId && it.revision in decision.withdrawnRevisions
            }.map { it.newOperationKey }
            // A prior whole-batch rejection says nothing about a later residual CAS for the same revision.
            // Its immutable operation remains required even after ACK deletes the live outbox row.
            require(namedKeys.containsAll(pendingKeys + residualKeysForRevision)) {
                "business_resolution_operation_history_incomplete"
            }
            val provenIntents = decision.provenanceKeys.map { key ->
                val provenance = decode(requireNotNull(byKey[key]) { "business_resolution_provenance_missing" })
                require(provenance.kind == "intent_provenance" && provenance.remoteId == record.remoteId &&
                    provenance.productId == record.productId &&
                    provenance.intent!!.revision in decision.withdrawnRevisions &&
                    lineage.getValue(provenance.generationId) <= lineage.getValue(record.generationId))
                provenance
            }
            // Every extant provenance for the withdrawn lineage must be included, including dispatch history.
            require(decision.withdrawnRevisions.mapNotNull { byRevision[record.remoteId to it] }.toSet() == provenIntents.toSet())
            byRevision[record.remoteId to decision.conflictRevision]?.intent?.let { current ->
                require(current.desired == decision.originalDesired && current.provenBase == decision.provenBase) {
                    "business_resolution_desired_changed"
                }
            }
            val seals = decision.originalSeals.map { seal ->
                val attempt = readAttempt(sealEntry(seal))
                val attemptGeneration = requireNotNull(attempt.generation)
                require(attemptScope(attempt) == scope && attemptGeneration in lineage &&
                    lineage.getValue(attemptGeneration) <= lineage.getValue(record.generationId))
                originalByKey[seal.operationKey]?.let { require(matches(seal, it)) { "business_resolution_original_changed" } }
                require(attempt.payload.revisions.any { it.domain == "PRODUCT" && it.remoteId == record.remoteId &&
                    it.revision.toInt() in decision.withdrawnRevisions })
                if (seal.dispatchCount == 0) {
                    require(seal.noCommitProof == null && seal.lastAttemptAtMs == null)
                    // This is the complete sealed pre-dispatch journal, not an absent row or nil date alone.
                    require(originalByKey.containsKey(seal.operationKey)) { "business_resolution_unstarted_seal_missing" }
                } else require(hasNoCommitProof(sealEntry(seal))) { "business_resolution_no_commit_unproven" }
                attempt.payload.revisions.filter { it.domain == "PRODUCT" && it.remoteId == record.remoteId &&
                    it.revision.toInt() in decision.withdrawnRevisions }.forEach { revision ->
                    if (revision.revision.toInt() == decision.conflictRevision) {
                        validateSealedDesired(attempt.payload, record.remoteId!!, decision.originalDesired)
                    }
                    byRevision[record.remoteId to revision.revision.toInt()]?.intent?.let { provenance ->
                        require(if (seal.dispatchCount == 0) provenance.dispatchOperationKeys.isEmpty()
                            else seal.operationKey in provenance.dispatchOperationKeys)
                    }
                }
                seal to attempt
            }
            require(if (decision.disposition == "never_dispatched") seals.all { it.first.dispatchCount == 0 }
                else seals.any { it.first.dispatchCount > 0 })
            val unsealed = decision.withdrawnRevisions.filter { revision -> seals.none { (_, attempt) ->
                attempt.payload.revisions.any { it.domain == "PRODUCT" && it.remoteId == record.remoteId && it.revision == revision.toLong() }
            } }
            if (unsealed.isNotEmpty()) {
                require(unsealed.all { revision -> provenIntents.any { provenance ->
                    provenance.intent!!.revision == revision && provenance.intent.dispatchOperationKeys.isEmpty()
                } } && validateNeverDispatched(record, provenIntents)) { "business_resolution_never_dispatch_unproven" }
            }
            // Any dispatched operation named by provenance needs its own exact whole-call no-commit proof.
            provenIntents.flatMap { it.intent!!.dispatchOperationKeys }.forEach { key ->
                require(seals.any { it.first.operationKey == key && it.first.dispatchCount > 0 })
            }
            decision.residuals.forEach { residual ->
                val originalSeal = decision.originalSeals.single { it.operationKey == residual.originalOperationKey }
                require(hasNoCommitProof(sealEntry(originalSeal))) { "business_resolution_residual_no_commit_unproven" }
                val old = readAttempt(sealEntry(originalSeal))
                require(old.payload.kind == "PRODUCTS" && old.payload.revisions.size > 1 && residual.remoteId != record.remoteId)
                val oldRevision = old.payload.revisions.single { it.remoteId == residual.remoteId }
                require(oldRevision.domain == "PRODUCT" && oldRevision.revision == residual.revision.toLong())
                val originalRow = old.payload.products.single { it.id == residual.remoteId }
                require(originalRow.deletedAt == null && originalRow.primaryImageVersionId == null && originalRow.primaryImageUpdatedAt == null)
                val successor = readResidualAttempt(residual)
                require(attemptScope(successor) == scope && successor.generation == record.generationId &&
                    successor.payload.revisions.single().let { it.domain == oldRevision.domain && it.remoteId == oldRevision.remoteId &&
                        it.revision == oldRevision.revision && it.fingerprint == oldRevision.fingerprint })
                validateQualifiedRemote(residual.qualifiedRemote, residual.remoteId, scope)
                val remote = requireNotNull(residual.qualifiedRemote.row)
                require(remote.deletedAt == null && residual.provenBaseFingerprint == fingerprintProductInbound(remote) &&
                    successor.payload.expectedUpdatedAt == remote.updatedAt && successor.payload.expectedDeletedAt == null &&
                    patchMatchesRow(requireNotNull(successor.payload.patch), originalRow)) { "business_resolution_residual_changed" }
                originalByKey[residual.newOperationKey]?.let { current ->
                    require(current.metadataJson == residual.rawSuccessorAttempt && readAttempt(current) == successor)
                }
                require(residualKeys.add(residual.newOperationKey) &&
                    residualCoverage.put(Triple(residual.originalOperationKey, residual.remoteId, residual.revision), record) == null)
            }
            decision.withdrawnRevisions.forEach { revision ->
                require(withdrawn.put(record.remoteId!! to revision, record) == null) { "business_resolution_duplicate_withdrawal" }
            }
        }
        Snapshot(records, null, scope, withdrawn, intents, residualCoverage)
    } catch (_: Exception) {
        // Preserve the source rows. Consumers must stop unsafe work rather than treat an invalid record as absent.
        Snapshot(emptyList(), "business_resolution_invalid", scope, emptyMap(), emptyList(), emptyMap())
    }

    /** Typed response attribution is the caller's responsibility. Call only after its first atomic dispatch. */
    fun encodeNoCommitProof(stampedEntry: SyncEventOutboxEntry, sqlState: String? = null,
        conditionalZero: Boolean = false): String {
        val attempt = readAttempt(stampedEntry)
        require(stampedEntry.attemptCount == 1 && stampedEntry.lastAttemptAtMs != null &&
            stampedEntry.lastErrorType == null) { "business_write_prior_dispatch_uncertain" }
        require(if (conditionalZero) sqlState == null && attempt.version == 2 && attempt.payload.kind == "PATCH_CONDITIONAL"
            else sqlState in setOf("23503", "23505"))
        return json.encodeToString(BusinessWriteNoCommitProof(kind = if (conditionalZero) "conditional_zero" else "atomic_rejection",
            operationKey = stampedEntry.clientEventId, rawAttemptSha256 = hash(stampedEntry.metadataJson),
            dispatchCount = stampedEntry.attemptCount, dispatchedAtMs = requireNotNull(stampedEntry.lastAttemptAtMs), sqlState = sqlState))
    }

    fun hasNoCommitProof(entry: SyncEventOutboxEntry): Boolean = runCatching {
        val raw = requireNotNull(entry.lastErrorType)
        require(raw.encodeToByteArray().size <= 4096)
        val proof = json.decodeFromString<BusinessWriteNoCommitProof>(raw)
        require(json.encodeToString(proof) == raw && proof.version == 1)
        val regenerated = encodeNoCommitProof(entry.copy(lastErrorType = null), proof.sqlState,
            conditionalZero = proof.kind == "conditional_zero")
        require(regenerated == raw)
        true
    }.getOrDefault(false)

    private fun validateRecord(record: BusinessResolutionRecord) {
        validateScope(record.scope)
        require(record.version == 1 && record.recordId.isNotBlank() && record.generationId.isNotBlank() && record.checkpointDigest.isNotBlank())
        require(record.kind == "actual_ack" || record.acknowledgement == null)
        when (record.kind) {
            "actual_ack" -> {
                require(record.productId == null && record.remoteId == null && record.intent == null &&
                    record.decision == null && record.transfer == null && record.acknowledgement != null)
                val acknowledged = record.acknowledgement
                require(acknowledged.seal.entryId > 0 && acknowledged.seal.dispatchCount > 0 && acknowledged.seal.lastAttemptAtMs != null)
                val attempt = readAttempt(sealEntry(acknowledged.seal))
                require(attemptScope(attempt) == record.scope)
                if (attempt.payload.kind == "PATCH_CONDITIONAL") {
                    val row = requireNotNull(acknowledged.returnedRow)
                    validateRow(row, requireNotNull(attempt.payload.patchId), record.scope)
                    require(row.deletedAt == null && selectedPatchMatchesRow(requireNotNull(attempt.payload.patch), row)) {
                        "business_resolution_ack_values_mismatch"
                    }
                } else require(acknowledged.returnedRow == null)
            }
            "intent_provenance" -> {
                require(record.productId != null && record.productId > 0 && !record.remoteId.isNullOrBlank() &&
                    record.intent != null && record.decision == null && record.transfer == null)
                val intent = record.intent
                require(intent.revision > 0 && intent.parentRevision in 0 until intent.revision)
                validateSnapshot(intent.desired, record.productId)
                validateFields(intent.intendedFields, allowEmpty = false)
                unique(intent.priorResolutionIds); unique(intent.dispatchOperationKeys)
                require(intent.dispatchOperationKeys.all { isWriteKey(it) })
                require(intent.supersedesProvenanceId == null || intent.supersedesProvenanceId.isNotBlank())
                intent.provenBase?.let { validateRow(it, record.remoteId, record.scope) }
            }
            "decision" -> {
                require(record.productId != null && record.productId > 0 && !record.remoteId.isNullOrBlank() &&
                    record.intent == null && record.decision != null && record.transfer == null)
                val decision = record.decision
                require(decision.outcome == "superseded_locally" && decision.conflictRevision > 0 &&
                    decision.acknowledgedRevision in 0 until decision.conflictRevision &&
                    decision.disposition in setOf("never_dispatched", "proven_no_commit"))
                require(decision.withdrawnRevisions.isNotEmpty() && decision.withdrawnRevisions.distinct().size == decision.withdrawnRevisions.size &&
                    decision.conflictRevision in decision.withdrawnRevisions &&
                    decision.withdrawnRevisions.all { it > decision.acknowledgedRevision && it <= decision.conflictRevision })
                unique(decision.provenanceKeys)
                require(decision.provenanceKeys.all { it.startsWith(KEY_PREFIX) })
                require(decision.originalSeals.map { it.operationKey }.distinct().size == decision.originalSeals.size)
                require(decision.residuals.map { Triple(it.originalOperationKey, it.remoteId, it.revision) }.distinct().size == decision.residuals.size)
                decision.residuals.forEach { residual ->
                    require(decision.disposition == "proven_no_commit" && residual.revision > 0 && residual.remoteId.isNotBlank() &&
                        isWriteKey(residual.originalOperationKey) && isWriteKey(residual.newOperationKey) &&
                        residual.originalOperationKey in decision.originalSeals.map { it.operationKey } &&
                        residual.newOperationKey !in decision.originalSeals.map { it.operationKey } &&
                        residual.newOperationKey != decision.successorOperationKey && residual.provenBaseFingerprint.isNotBlank())
                    readResidualAttempt(residual)
                }
                decision.originalSeals.forEach { seal ->
                    require(seal.entryId > 0 && isWriteKey(seal.operationKey) && seal.dispatchCount >= 0 &&
                        (if (seal.dispatchCount == 0) seal.lastAttemptAtMs == null else seal.lastAttemptAtMs != null && seal.lastAttemptAtMs >= 0))
                }
                validateSnapshot(decision.originalDesired, record.productId)
                decision.provenBase?.let { validateRow(it, record.remoteId, record.scope) }
                decision.projection?.let { validateSnapshot(it, record.productId) }
                validateQualifiedRemote(decision.remote, record.remoteId, record.scope)
                val remote = requireNotNull(decision.remote.row) { "business_resolution_absence_not_tombstone" }
                validateFields(decision.selectedFields, allowEmpty = true)
                when (decision.choice) {
                    "adopt_remote" -> require(remote.deletedAt == null && decision.selectedFields.isEmpty() &&
                        decision.projection != null && decision.successorRevision == null && decision.successorOperationKey == null)
                    "adopt_remote_tombstone" -> require(remote.deletedAt != null && decision.selectedFields.isEmpty() &&
                        decision.projection == null && decision.successorRevision == null && decision.successorOperationKey == null)
                    "selected_fields" -> require(remote.deletedAt == null && decision.selectedFields.isNotEmpty() &&
                        decision.projection != null && decision.successorRevision != null && decision.successorRevision > decision.conflictRevision &&
                        decision.successorOperationKey?.let(::isWriteKey) == true &&
                        decision.originalSeals.none { it.operationKey == decision.successorOperationKey })
                    else -> error("business_resolution_choice_invalid")
                }
                decision.projection?.let { projection ->
                    val selected = if (decision.choice == "selected_fields") decision.selectedFields.toSet() else emptySet()
                    scalarSnapshot(projection).forEach { (field, value) ->
                        if (field !in selected) require(value == scalarRow(remote).getValue(field)) {
                            "business_resolution_projection_changed_unselected_field"
                        }
                    }
                }
            }
            "generation_transfer" -> {
                require(record.productId == null && record.remoteId == null && record.intent == null && record.decision == null && record.transfer != null)
                val transfer = record.transfer
                require(transfer.previousGeneration.isNotBlank() && transfer.previousGeneration != record.generationId &&
                    transfer.previousCheckpointDigest.isNotBlank() && transfer.newCheckpointDigest == record.checkpointDigest)
                unique(transfer.transferredRecordHashes)
                require((transfer.transferredRecordHashes.isNotEmpty() || transfer.originalAttemptSeals.isNotEmpty()) &&
                    transfer.transferredRecordHashes.all(::isHash))
                require(transfer.originalAttemptSeals.map { it.operationKey }.distinct().size == transfer.originalAttemptSeals.size)
                transfer.originalAttemptSeals.forEach { seal ->
                    require(seal.entryId > 0)
                    val attempt = readAttempt(sealEntry(seal))
                    require(attemptScope(attempt) == record.scope && !attempt.generation.isNullOrBlank())
                }
            }
            else -> error("business_resolution_kind_invalid")
        }
    }

    private fun validateTransfers(records: List<BusinessResolutionRecord>, activeGeneration: String,
        scope: BusinessResolutionScope, pending: List<Pair<SyncEventOutboxEntry, BusinessWriteAttempt>>): Map<String, Int> {
        val byGeneration = records.groupBy { it.generationId }
        val seen = mutableSetOf<String>()
        val order = linkedMapOf<String, Int>()
        require(pending.all { attemptScope(it.second) == scope })
        fun visit(generation: String, incomingAnchors: List<CatalogResolutionOriginalSeal> = emptyList()): List<BusinessResolutionRecord> {
            require(seen.add(generation)) { "business_resolution_transfer_cycle" }
            val group = byGeneration[generation].orEmpty()
            if (group.isEmpty() && generation != activeGeneration) require(incomingAnchors.any {
                readAttempt(sealEntry(it)).generation == generation
            }) { "business_resolution_transfer_origin_missing" }
            val transfers = group.filter { it.kind == "generation_transfer" }
            require(transfers.size <= 1)
            val inherited = transfers.singleOrNull()?.let { transferRecord ->
                val transfer = requireNotNull(transferRecord.transfer)
                visit(transfer.previousGeneration, transfer.originalAttemptSeals).also { prior ->
                    require(prior.map { hash(encode(it)) }.toSet() == transfer.transferredRecordHashes.toSet()) {
                        "business_resolution_transfer_incomplete"
                    }
                    require(transfer.originalAttemptSeals.all { readAttempt(sealEntry(it)).generation in order })
                    val anchors = prior.flatMap { it.transfer?.originalAttemptSeals.orEmpty() } + transfer.originalAttemptSeals
                    // Current counters/proofs can advance. A lineage anchor protects only immutable body/key/scope.
                    pending.filter { it.second.generation in order }.forEach { (entry, _) ->
                        require(anchors.any { it.operationKey == entry.clientEventId && it.rawAttempt == entry.metadataJson }) {
                            "business_resolution_transferred_attempt_missing"
                        }
                    }
                }
            }.orEmpty()
            order[generation] = order.size
            return inherited + group
        }
        require(visit(activeGeneration).size == records.size) { "business_resolution_generation_unproven" }
        require(pending.all { it.second.generation in order }) { "business_resolution_attempt_generation_unproven" }
        // The ordinary checkpoint can advance inside a generation. It never revives withdrawn intent.
        return order
    }

    /** A transferred intent may gain a dispatch link only by appending an immutable linear proof. */
    private fun validateProvenanceChains(intents: List<BusinessResolutionRecord>, lineage: Map<String, Int>): List<BusinessResolutionRecord> =
        intents.groupBy { it.remoteId to it.intent!!.revision }.values.map { group ->
            val byId = group.associateBy { it.recordId }
            val children = group.filter { it.intent!!.supersedesProvenanceId != null }
                .groupBy { it.intent!!.supersedesProvenanceId }
            require(children.values.all { it.size == 1 }) { "business_resolution_provenance_fork" }
            val root = group.single { it.intent!!.supersedesProvenanceId == null }
            val seen = mutableSetOf<String>()
            var current = root
            while (true) {
                require(seen.add(current.recordId))
                val next = children[current.recordId]?.singleOrNull() ?: break
                val before = requireNotNull(current.intent)
                val after = requireNotNull(next.intent)
                require(byId[after.supersedesProvenanceId] == current && current.productId == next.productId &&
                    lineage.getValue(current.generationId) <= lineage.getValue(next.generationId) &&
                    before.copy(dispatchOperationKeys = emptyList(), supersedesProvenanceId = null) ==
                        after.copy(dispatchOperationKeys = emptyList(), supersedesProvenanceId = null) &&
                    after.dispatchOperationKeys.size > before.dispatchOperationKeys.size &&
                    after.dispatchOperationKeys.containsAll(before.dispatchOperationKeys)) {
                    "business_resolution_provenance_changed"
                }
                current = next
            }
            require(seen.size == group.size) { "business_resolution_provenance_disconnected" }
            current
        }

    private fun readAttempt(entry: SyncEventOutboxEntry): BusinessWriteAttempt {
        require(entry.metadataJson.encodeToByteArray().size in 1..BUSINESS_WRITE_MAX_BYTES)
        val attempt = json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
        validateBusinessWritePayload(attempt)
        require(json.encodeToString(attempt) == entry.metadataJson &&
            entry.clientEventId == WRITE_PREFIX + hash(entry.metadataJson) && entry.eventType == BUSINESS_WRITE_EVENT &&
            entry.ownerUserId == attempt.owner && entry.storeScope == attempt.store && entry.sourceDeviceId == attempt.device &&
            entry.domain == attempt.payload.kind && entry.source == "local_business_write" && entry.batchId == null &&
            entry.entityIdsJson == "{}" && entry.changedCount == attempt.payload.revisions.size && entry.attemptCount >= 0 &&
            (if (entry.attemptCount == 0) entry.lastAttemptAtMs == null else entry.lastAttemptAtMs != null && entry.lastAttemptAtMs >= 0)) {
            "business_resolution_attempt_invalid"
        }
        return attempt
    }

    private fun sealEntry(seal: CatalogResolutionOriginalSeal): SyncEventOutboxEntry {
        require(seal.rawAttempt.encodeToByteArray().size in 1..BUSINESS_WRITE_MAX_BYTES)
        val attempt = json.decodeFromString<BusinessWriteAttempt>(seal.rawAttempt)
        return SyncEventOutboxEntry(id = seal.entryId, ownerUserId = attempt.owner, storeScope = attempt.store,
            domain = attempt.payload.kind, eventType = BUSINESS_WRITE_EVENT, source = "local_business_write",
            sourceDeviceId = attempt.device, batchId = null, clientEventId = seal.operationKey,
            changedCount = attempt.payload.revisions.size, entityIdsJson = "{}", metadataJson = seal.rawAttempt,
            createdAtMs = 0, attemptCount = seal.dispatchCount, lastAttemptAtMs = seal.lastAttemptAtMs, lastErrorType = seal.noCommitProof)
    }

    /** Exact member correspondence across an acknowledged split call. The old whole request stays raw. */
    internal fun equivalentAcknowledgedMember(original: BusinessWriteAttempt, revision: BusinessWriteRevision,
        acknowledged: BusinessWriteAttempt): Boolean {
        if (attemptScope(original) != attemptScope(acknowledged)) return false
        val accepted = acknowledged.payload.revisions.singleOrNull { it.domain == revision.domain &&
            it.remoteId == revision.remoteId && it.revision == revision.revision && it.fingerprint == revision.fingerprint }
            ?: return false
        if (accepted.localPriceId != revision.localPriceId) return false
        val before = original.payload
        val after = acknowledged.payload
        return when (revision.domain) {
            "PRODUCT" -> {
                if (before.kind == "PRODUCTS" && after.kind == "PRODUCTS") {
                    before.products.singleOrNull { it.id == revision.remoteId } == after.products.singleOrNull { it.id == revision.remoteId }
                } else if (before.kind in setOf("PATCH", "PATCH_CONDITIONAL") && after.kind in setOf("PATCH", "PATCH_CONDITIONAL")) {
                    before.patchId == after.patchId && before.patch == after.patch
                } else false
            }
            "SUPPLIER" -> before.suppliers.singleOrNull { it.id == revision.remoteId } == after.suppliers.singleOrNull { it.id == revision.remoteId }
            "CATEGORY" -> before.categories.singleOrNull { it.id == revision.remoteId } == after.categories.singleOrNull { it.id == revision.remoteId }
            "PRICE" -> before.prices.singleOrNull { it.id == revision.remoteId } == after.prices.singleOrNull { it.id == revision.remoteId }
            "HISTORY" -> before.history.singleOrNull { it.remoteId == revision.remoteId } == after.history.singleOrNull { it.remoteId == revision.remoteId }
            else -> false
        }
    }

    private fun readResidualAttempt(residual: CatalogResolutionResidual): BusinessWriteAttempt {
        val attempt = readAttempt(sealEntry(CatalogResolutionOriginalSeal(1, residual.newOperationKey,
            residual.rawSuccessorAttempt, 0, null, null)))
        val patch = requireNotNull(attempt.payload.patch)
        require(attempt.version == 2 && attempt.payload.kind == "PATCH_CONDITIONAL" &&
            attempt.payload.patchId == residual.remoteId && patch.changedFields == fields && patch.deletedAt == null)
        return attempt
    }

    private fun selectedPatchMatchesRow(patch: InventoryProductPatch, row: InventoryProductRow): Boolean {
        val expected = mapOf<String, Any?>("barcode" to patch.barcode, "itemnumber" to patch.itemNumber,
            "productname" to patch.productName, "secondproductname" to patch.secondProductName,
            "purchaseprice" to patch.purchasePrice, "retailprice" to patch.retailPrice,
            "supplier" to patch.supplierId, "category" to patch.categoryId, "stockquantity" to patch.stockQuantity)
        val actual = mapOf<String, Any?>("barcode" to row.barcode, "itemnumber" to row.itemNumber,
            "productname" to row.productName, "secondproductname" to row.secondProductName,
            "purchaseprice" to row.purchasePrice, "retailprice" to row.retailPrice,
            "supplier" to row.supplierId, "category" to row.categoryId, "stockquantity" to row.stockQuantity)
        return patch.changedFields.isNotEmpty() && patch.changedFields.all { it in expected && expected[it] == actual[it] }
    }

    private fun patchMatchesRow(patch: InventoryProductPatch, row: InventoryProductRow): Boolean =
        patch.changedFields == fields && patch.deletedAt == null && patch.barcode == row.barcode &&
            patch.itemNumber == row.itemNumber && patch.productName == row.productName && patch.secondProductName == row.secondProductName &&
            patch.purchasePrice == row.purchasePrice && patch.retailPrice == row.retailPrice &&
            patch.supplierId == row.supplierId && patch.categoryId == row.categoryId && patch.stockQuantity == row.stockQuantity

    private fun matches(seal: CatalogResolutionOriginalSeal, entry: SyncEventOutboxEntry): Boolean =
        seal.entryId == entry.id && seal.operationKey == entry.clientEventId && seal.rawAttempt == entry.metadataJson &&
            seal.dispatchCount == entry.attemptCount && seal.lastAttemptAtMs == entry.lastAttemptAtMs && seal.noCommitProof == entry.lastErrorType &&
            runCatching { readAttempt(entry) }.isSuccess

    private fun attemptScope(attempt: BusinessWriteAttempt) = BusinessResolutionScope(attempt.owner, attempt.store,
        requireNotNull(attempt.shop), attempt.localStore, attempt.protocol, attempt.schema, attempt.epoch, attempt.device)

    private fun validateScope(scope: BusinessResolutionScope) {
        require(scope.owner.isNotBlank() && scope.shop.isNotBlank() && scope.store == "shop:${scope.shop}" &&
            scope.localStore.isNotBlank() && scope.device.isNotBlank() && scope.protocol > 0 && scope.schema > 0 && scope.epoch > 0)
    }

    private fun validateFields(values: List<String>, allowEmpty: Boolean) {
        require((allowEmpty || values.isNotEmpty()) && values.distinct().size == values.size && values.all { it in fields })
    }

    private fun validateSnapshot(snapshot: CatalogProductSnapshot, id: Long) {
        require(snapshot.id == id && snapshot.barcode.isNotBlank() && listOf(snapshot.purchasePrice,
            snapshot.retailPrice, snapshot.oldPurchasePrice, snapshot.oldRetailPrice, snapshot.stockQuantity).all { it == null || it.isFinite() })
    }

    // Supplier/category are local Long IDs versus remote UUIDs. Their qualified mapping belongs to
    // the writer's existing bridge transaction; old price/history values have no scalar row equivalent.
    private fun scalarSnapshot(value: CatalogProductSnapshot): Map<String, Any?> = mapOf(
        "barcode" to value.barcode, "itemnumber" to value.itemNumber, "productname" to value.productName,
        "secondproductname" to value.secondProductName, "purchaseprice" to value.purchasePrice,
        "retailprice" to value.retailPrice, "stockquantity" to value.stockQuantity)

    private fun scalarRow(value: InventoryProductRow): Map<String, Any?> = mapOf(
        "barcode" to value.barcode, "itemnumber" to value.itemNumber, "productname" to value.productName,
        "secondproductname" to value.secondProductName, "purchaseprice" to value.purchasePrice,
        "retailprice" to value.retailPrice, "stockquantity" to value.stockQuantity)

    private fun validateSealedDesired(payload: BusinessWritePayload, remoteId: String, desired: CatalogProductSnapshot) {
        val expected = scalarSnapshot(desired)
        when (payload.kind) {
            "PRODUCTS" -> require(scalarRow(payload.products.single { it.id == remoteId }) == expected)
            "PATCH", "PATCH_CONDITIONAL" -> {
                require(payload.patchId == remoteId)
                val patch = requireNotNull(payload.patch)
                val actual = mapOf("barcode" to patch.barcode, "itemnumber" to patch.itemNumber,
                    "productname" to patch.productName, "secondproductname" to patch.secondProductName,
                    "purchaseprice" to patch.purchasePrice, "retailprice" to patch.retailPrice,
                    "stockquantity" to patch.stockQuantity)
                actual.forEach { (field, value) -> if (field in patch.changedFields) require(expected.getValue(field) == value) }
            }
            else -> error("business_resolution_catalog_seal_required")
        }
    }

    private fun validateRow(row: InventoryProductRow, remoteId: String, scope: BusinessResolutionScope) {
        require(row.id == remoteId && row.ownerUserId == scope.owner && row.shopId == scope.shop && row.barcode.isNotBlank())
        require(validTimestamp(row.updatedAt) && (row.deletedAt == null || validTimestamp(row.deletedAt)))
        require(listOf(row.purchasePrice, row.retailPrice, row.stockQuantity).all { it == null || it.isFinite() })
    }

    private fun validateQualifiedRemote(remote: QualifiedCatalogConflictRemote, remoteId: String, scope: BusinessResolutionScope) {
        require(remote.schemaVersion == "shop-sync-rows-by-ids-v1" && remote.domainScope == remote.scope.kind &&
            remote.scope.kind in setOf(ShopSyncScopeKinds.SHOP_SCOPED, ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY) &&
            remote.scope.key.isNotBlank())
        validateRecoveryScopeIdentity(remote.scope, scope.owner, scope.device)
        require(parseShopSyncMaxEventId(remote.currentScopeEventMaxId) >= parseShopSyncMaxEventId(remote.asOfEventMaxId) &&
            parseShopSyncMaxEventId(remote.materializedDomainEventMaxId) >= parseShopSyncMaxEventId(remote.minimumDomainEventMaxId))
        remote.row?.let { validateRow(it, remoteId, scope); require(remote.missingIds.isEmpty()) }
            ?: require(remote.missingIds == listOf(remoteId))
    }

    private fun validTimestamp(raw: String?): Boolean = raw != null &&
        Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})$").matches(raw) &&
        runCatching { OffsetDateTime.parse(raw) }.isSuccess

    private fun unique(values: List<String>) { require(values.distinct().size == values.size && values.all { it.isNotBlank() }) }
    private fun isHash(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    private fun isWriteKey(value: String) = value.startsWith(WRITE_PREFIX) && isHash(value.removePrefix(WRITE_PREFIX))
    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable
internal data class BusinessWriteNoCommitProof(
    val version: Int = 1,
    val kind: String,
    val operationKey: String,
    val rawAttemptSha256: String,
    val dispatchCount: Int,
    val dispatchedAtMs: Long,
    val sqlState: String?
)

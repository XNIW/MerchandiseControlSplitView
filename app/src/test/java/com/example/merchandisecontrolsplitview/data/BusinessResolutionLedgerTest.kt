package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure receipt tests. These do not replace Room transaction, writer, recovery or native acceptance. */
class BusinessResolutionLedgerTest {
    private val json = Json { encodeDefaults = true }
    private val scope = BusinessResolutionScope("owner", "shop:shop", "shop", "local-store", 126, 2, 1, "device")

    @Test
    fun strictCodecRejectsMissingDefaultsUnknownDuplicateKeysAndEnvelopeChanges() {
        val record = intent()
        val entry = BusinessResolutionLedger.entry(record, 10)
        assertEquals(record, BusinessResolutionLedger.decode(entry))
        val alternatives = listOf(entry.metadataJson.replaceFirst("\"version\":1,", ""),
            entry.metadataJson.replaceFirst("{", "{\"unrecognized\":true,"),
            entry.metadataJson.replaceFirst("{", "{\"version\":1,"), " " + entry.metadataJson,
            entry.metadataJson.replaceFirst(",\"dispatchOperationKeys\":[]", ""))
        alternatives.forEach { raw -> rejected { BusinessResolutionLedger.decode(entry.copy(metadataJson = raw, batchId = hash(raw))) } }
        listOf(entry.copy(ownerUserId = "foreign"), entry.copy(batchId = "0".repeat(64)),
            entry.copy(clientEventId = "different"), entry.copy(attemptCount = 1), entry.copy(source = null),
            entry.copy(domain = "PRICES"), entry.copy(changedCount = 1)).forEach {
            rejected { BusinessResolutionLedger.decode(it) }
        }
    }

    @Test
    fun variantsCannotSmuggleAnotherKindOrAckOutcome() {
        rejected { BusinessResolutionLedger.encode(intent().copy(decision = decision().decision)) }
        rejected { BusinessResolutionLedger.encode(decision().let { it.copy(decision = it.decision!!.copy(outcome = "acknowledged")) }) }
        rejected { BusinessResolutionLedger.encode(intent().let { it.copy(intent = it.intent!!.copy(intendedFields = listOf("__all__"))) }) }
        rejected { BusinessResolutionLedger.encode(decision().let { it.copy(decision = it.decision!!.copy(withdrawnRevisions = listOf(1, 2))) }) }
    }

    @Test
    fun absentSealNeedsDurableProvenanceAndWriterQualificationAndDoesNotWithdrawSuccessor() {
        val provenance = intent()
        val resolution = decision(provenance = listOf(provenance))
        val denied = evaluate(listOf(provenance, resolution))
        assertFalse(denied.isValid)
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Invalid, denied.effectiveRevision(REMOTE, 1))
        rejected { denied.requireValid() }
        assertFalse(evaluate(listOf(decision()), allowUnsealed = true).isValid)
        val valid = evaluate(listOf(provenance, resolution), allowUnsealed = true).requireValid()
        assertEquals(BusinessResolutionLedger.EffectiveRevision.SupersededLocally, valid.effectiveRevision(REMOTE, 1))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, valid.effectiveRevision(REMOTE, 2))
        assertEquals(0, valid.decisions.single().decision!!.acknowledgedRevision)
        assertEquals(listOf(provenance), valid.intents)
    }

    @Test
    fun acknowledgedAttemptDeletionNeverTurnsDispatchedProvenanceIntoNeverDispatched() {
        val original = attempt()
        val provenance = intent(dispatchKeys = listOf(original.clientEventId))
        val resolution = decision(provenance = listOf(provenance))
        assertFalse(evaluate(listOf(provenance, resolution), allowUnsealed = true).isValid)
    }

    @Test
    fun legacyCompletePreDispatchSealIsPreservedAndRequiresOriginalJournal() {
        val original = attempt()
        val resolution = decision(seals = listOf(seal(original)))
        assertFalse(evaluate(listOf(resolution)).isValid)
        val valid = evaluate(listOf(resolution), originals = listOf(original)).requireValid()
        assertTrue(valid.supersededAttempt(original))
        val persisted = BusinessResolutionLedger.decode(BusinessResolutionLedger.entry(resolution, 10))
        assertEquals(original.metadataJson, persisted.decision!!.originalSeals.single().rawAttempt)
        assertEquals(original.clientEventId, requireNotNull(persisted.decision).originalSeals.single().operationKey)
    }

    @Test
    fun noCommitProofBindsExactWholeRequestFirstDispatchTimestampAndSqlstate() {
        val stamped = attempt().copy(attemptCount = 1, lastAttemptAtMs = 20)
        val proof = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23505")
        val rejectedEntry = stamped.copy(lastErrorType = proof)
        assertTrue(BusinessResolutionLedger.hasNoCommitProof(rejectedEntry))
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(rejectedEntry.copy(lastAttemptAtMs = 21)))
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(rejectedEntry.copy(attemptCount = 2)))
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(rejectedEntry.copy(metadataJson = rejectedEntry.metadataJson + " ")))
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(rejectedEntry.copy(lastErrorType = "23505 transport text")))
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(rejectedEntry.copy(lastErrorType = proof.replace("23505", "57014"))))
        rejected { BusinessResolutionLedger.encodeNoCommitProof(stamped, "57014") }
        rejected { BusinessResolutionLedger.encodeNoCommitProof(stamped.copy(lastErrorType = "unknown"), "23505") }
        rejected { BusinessResolutionLedger.encodeNoCommitProof(stamped.copy(attemptCount = 2), "23505") }
        val resolution = decision(seals = listOf(seal(rejectedEntry)), disposition = "proven_no_commit")
        // Legacy has no new intent record: the exact typed atomic rejection is independently sufficient.
        val valid = evaluate(listOf(resolution), originals = listOf(rejectedEntry)).requireValid()
        assertTrue(valid.supersededAttempt(rejectedEntry))
        assertFalse(evaluate(listOf(resolution), originals = listOf(rejectedEntry.copy(lastErrorType = null))).isValid)
    }

    @Test
    fun conditionalZeroProofCannotConfirmAnEarlierLostResponse() {
        val conditional = attempt(conditional = true).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val proof = BusinessResolutionLedger.encodeNoCommitProof(conditional, conditionalZero = true)
        assertTrue(BusinessResolutionLedger.hasNoCommitProof(conditional.copy(lastErrorType = proof)))
        rejected { BusinessResolutionLedger.encodeNoCommitProof(conditional.copy(attemptCount = 2), conditionalZero = true) }
        rejected { BusinessResolutionLedger.encodeNoCommitProof(attempt().copy(attemptCount = 1, lastAttemptAtMs = 20), conditionalZero = true) }
        rejected { BusinessResolutionLedger.encodeNoCommitProof(conditional, "23505", conditionalZero = true) }
    }

    @Test
    fun partialBatchDecisionNeverRetiresUnselectedRevision() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val original = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23503"))
        val first = decision(seals = listOf(seal(original)), disposition = "proven_no_commit")
        val partial = evaluate(listOf(first), originals = listOf(original)).requireValid()
        assertFalse(partial.supersededAttempt(original))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, partial.effectiveRevision(OTHER, 1))
        val second = decision(id = "decision-other", remoteId = OTHER, productId = 2,
            seals = listOf(seal(original)), disposition = "proven_no_commit")
        assertTrue(evaluate(listOf(first, second), originals = listOf(original)).requireValid().supersededAttempt(original))
    }

    @Test
    fun provenBatchRetiresOnlyWithExactResidualCasAndKeepsResidualRevisionActiveAfterReopen() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val original = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23505"))
        val successor = residualSuccessor()
        val residual = CatalogResolutionResidual(original.clientEventId, OTHER, 1, successor.clientEventId,
            successor.metadataJson, remote(OTHER), fingerprintProductInbound(row(OTHER)))
        val resolution = decision(seals = listOf(seal(original)), disposition = "proven_no_commit").let {
            it.copy(decision = it.decision!!.copy(residuals = listOf(residual)))
        }
        val valid = evaluate(listOf(resolution), originals = listOf(original, successor)).requireValid()
        assertTrue(valid.supersededAttempt(original))
        assertFalse(valid.supersededAttempt(successor))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.SupersededLocally, valid.effectiveRevision(REMOTE, 1))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, valid.effectiveRevision(OTHER, 1))
        assertEquals(original.metadataJson, valid.decisions.single().decision!!.originalSeals.single().rawAttempt)
        // An exact ACK may remove the successor's outbox row, but cannot erase why the old batch was retired.
        val afterAck = evaluate(listOf(resolution), originals = listOf(original)).requireValid()
        assertTrue(afterAck.supersededAttempt(original))
        assertEquals(successor.metadataJson, afterAck.decisions.single().decision!!.residuals.single().rawSuccessorAttempt)
    }

    @Test
    fun residualCannotChangeBodyMaskRevisionExpectedVersionOrClaimUnknownBatch() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val original = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23503"))
        fun resolution(successor: SyncEventOutboxEntry) = decision(seals = listOf(seal(original)), disposition = "proven_no_commit").let {
            it.copy(decision = it.decision!!.copy(residuals = listOf(CatalogResolutionResidual(original.clientEventId,
                OTHER, 1, successor.clientEventId, successor.metadataJson, remote(OTHER), fingerprintProductInbound(row(OTHER))))))
        }
        val variants = listOf(residualSuccessor(name = "Changed B"), residualSuccessor(version = "2026-10-23T12:00:00.123455Z"),
            residualSuccessor(mask = setOf("productname")), residualSuccessor(revision = 2))
        variants.forEach { successor ->
            assertFalse(runCatching { evaluate(listOf(resolution(successor)), originals = listOf(original, successor)).isValid }.getOrDefault(false))
        }
        val successor = residualSuccessor()
        val valid = resolution(successor)
        val residual = valid.decision!!.residuals.single()
        val wrongBase = valid.copy(decision = valid.decision.copy(residuals = listOf(residual.copy(provenBaseFingerprint = "unknown"))))
        assertFalse(evaluate(listOf(wrongBase), originals = listOf(original, successor)).isValid)
        val duplicate = valid.copy(decision = valid.decision.copy(residuals = listOf(residual, residual)))
        rejected { BusinessResolutionLedger.encode(duplicate) }
        assertFalse(evaluate(listOf(valid), originals = listOf(original.copy(lastErrorType = "unconfirmed"), successor)).isValid)
    }

    @Test
    fun oldWholeBatchRejectionCannotWithdrawResidualWhoseLaterDispatchIsUncertain() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val original = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23505"))
        val residual = residualSuccessor()
        val first = decision(seals = listOf(seal(original)), disposition = "proven_no_commit").let {
            it.copy(decision = it.decision!!.copy(residuals = listOf(CatalogResolutionResidual(original.clientEventId,
                OTHER, 1, residual.clientEventId, residual.metadataJson, remote(OTHER), fingerprintProductInbound(row(OTHER))))))
        }
        val uncertain = residual.copy(attemptCount = 1, lastAttemptAtMs = 30, lastErrorType = "business_write_response_unconfirmed")
        val stale = decision(id = "decision-B", remoteId = OTHER, productId = 2,
            seals = listOf(seal(original)), disposition = "proven_no_commit")
        assertFalse(evaluate(listOf(first, stale), originals = listOf(original, uncertain)).isValid)
        // Even if a live row has disappeared, the retained residual chain prevents inferring no dispatch.
        assertFalse(evaluate(listOf(first, stale), originals = listOf(original)).isValid)
        val alsoNamesUnknown = stale.copy(decision = stale.decision!!.copy(originalSeals = listOf(seal(original), seal(uncertain))))
        assertFalse(evaluate(listOf(first, alsoNamesUnknown), originals = listOf(original, uncertain)).isValid)
    }

    @Test
    fun mixedRejectedAAndUnstartedBNeedBothIndependentProofs() {
        val stamped = attempt().copy(attemptCount = 1, lastAttemptAtMs = 20)
        val original = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23505"))
        val later = intent(revision = 2)
        val resolution = decision(revision = 2, seals = listOf(seal(original)), provenance = listOf(later),
            withdrawn = listOf(1, 2), disposition = "proven_no_commit")
        assertFalse(evaluate(listOf(later, resolution), originals = listOf(original)).isValid)
        val valid = evaluate(listOf(later, resolution), originals = listOf(original), allowUnsealed = true).requireValid()
        assertTrue(valid.supersededAttempt(original))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, valid.effectiveRevision(REMOTE, 3))
        val dispatchedLater = later.copy(intent = later.intent!!.copy(dispatchOperationKeys = listOf(original.clientEventId)))
        assertFalse(evaluate(listOf(dispatchedLater, resolution), originals = listOf(original), allowUnsealed = true).isValid)
    }

    @Test
    fun noOpAdoptionSurvivesMovingCheckpointAndLaterDisjointIntentStaysActive() {
        val provenance = intent()
        val resolution = decision(provenance = listOf(provenance))
        val next = intent(revision = 2).copy(intent = intent(revision = 2).intent!!.copy(
            intendedFields = listOf("stockquantity"), priorResolutionIds = listOf(resolution.recordId)))
        val reopened = evaluate(listOf(provenance, resolution, next), checkpoint = "newer-ordinary-checkpoint", allowUnsealed = true).requireValid()
        assertEquals(BusinessResolutionLedger.EffectiveRevision.SupersededLocally, reopened.effectiveRevision(REMOTE, 1))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, reopened.effectiveRevision(REMOTE, 2))
        assertEquals(listOf("stockquantity"), reopened.intents.last().intent!!.intendedFields)
        val invalidNext = next.copy(intent = requireNotNull(next.intent).copy(priorResolutionIds = listOf("missing")))
        assertFalse(evaluate(listOf(provenance, resolution, invalidNext), allowUnsealed = true).isValid)
    }

    @Test
    fun transferRequiresEveryPreservedRecordAndHashAndRejectsForeignOrMissingGeneration() {
        val provenance = intent()
        val resolution = decision(provenance = listOf(provenance))
        val initial = listOf(provenance, resolution)
        val transfer = transfer(initial, "generation-2", "generation-1")
        val complete = initial + transfer
        assertTrue(evaluate(complete, generation = "generation-2", allowUnsealed = true).isValid)
        assertFalse(evaluate(initial, generation = "generation-2", allowUnsealed = true).isValid)
        assertFalse(evaluate(listOf(resolution, transfer), generation = "generation-2", allowUnsealed = true).isValid)
        assertFalse(evaluate(complete.map { if (it == provenance) it.copy(checkpointDigest = "changed") else it },
            generation = "generation-2", allowUnsealed = true).isValid)
        val third = transfer(complete, "generation-3", "generation-2")
        assertTrue(evaluate(complete + third, generation = "generation-3", allowUnsealed = true).isValid)
        assertFalse(evaluate(listOf(provenance.copy(scope = scope.copy(device = "foreign")))).isValid)
    }

    @Test
    fun transferredIntentGainsDispatchEvidenceByImmutableLinearAppendOnlyChain() {
        val prior = intent()
        val originalRaw = BusinessResolutionLedger.encode(prior)
        val transfer = transfer(listOf(prior), "generation-2", "generation-1")
        val operationKey = attempt().clientEventId
        val linked = prior.copy(recordId = "dispatch-link", generationId = "generation-2", checkpointDigest = "later",
            intent = prior.intent!!.copy(dispatchOperationKeys = listOf(operationKey), supersedesProvenanceId = prior.recordId))
        val records = listOf(prior, transfer, linked)
        val valid = evaluate(records, generation = "generation-2").requireValid()
        assertEquals(listOf(linked), valid.intents)
        assertEquals(originalRaw, BusinessResolutionLedger.encode(valid.records.first()))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, valid.effectiveRevision(REMOTE, 1))
        assertFalse(evaluate(records + linked.copy(recordId = "fork"), generation = "generation-2").isValid)
        val changed = linked.copy(intent = linked.intent!!.copy(intendedFields = listOf("stockquantity")))
        assertFalse(evaluate(listOf(prior, transfer, changed), generation = "generation-2").isValid)
        val missingParent = linked.copy(intent = linked.intent.copy(supersedesProvenanceId = "missing"))
        assertFalse(evaluate(listOf(prior, transfer, missingParent), generation = "generation-2").isValid)
        val removedDispatch = linked.copy(recordId = "removed", intent = linked.intent.copy(
            dispatchOperationKeys = emptyList(), supersedesProvenanceId = linked.recordId))
        assertFalse(evaluate(records + removedDispatch, generation = "generation-2").isValid)
        val terminal = decision(provenance = listOf(prior))
        assertFalse(evaluate(listOf(prior, terminal, linked.copy(generationId = "generation-1")), allowUnsealed = true).isValid)
    }

    @Test
    fun legacyOnlyTransferRetainsLineageAfterAckButAnchorNeverWithdrawsOrProvesNoCommit() {
        val original = attempt()
        val anchor = transfer(emptyList(), "generation-2", "generation-1").let {
            it.copy(transfer = it.transfer!!.copy(originalAttemptSeals = listOf(seal(original))))
        }
        val before = evaluate(listOf(anchor), originals = listOf(original), generation = "generation-2").requireValid()
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, before.effectiveRevision(REMOTE, 1))
        assertFalse(before.supersededAttempt(original))
        val dispatched = original.copy(attemptCount = 1, lastAttemptAtMs = 20, lastErrorType = "unconfirmed")
        assertTrue(evaluate(listOf(anchor), originals = listOf(dispatched), generation = "generation-2").isValid)
        assertTrue(evaluate(listOf(anchor), generation = "generation-2").isValid)
        assertFalse(evaluate(listOf(anchor), originals = listOf(attempt(ids = listOf(OTHER))), generation = "generation-2").isValid)
        val emptyAnchor = anchor.copy(transfer = anchor.transfer!!.copy(originalAttemptSeals = emptyList()))
        rejected { BusinessResolutionLedger.encode(emptyAnchor) }
    }

    @Test
    fun malformedProjectionAndLatestSealDesiredCannotWithdrawIntent() {
        val original = attempt()
        val resolution = decision(seals = listOf(seal(original)))
        val wrongAdoption = resolution.copy(decision = resolution.decision!!.copy(projection = snapshot().copy(productName = "Old name")))
        rejected { BusinessResolutionLedger.encode(wrongAdoption) }
        val selected = resolution.copy(decision = resolution.decision.copy(choice = "selected_fields", selectedFields = listOf("productname"),
            projection = snapshot().copy(productName = "Chosen", stockQuantity = 7.0), successorRevision = 2,
            successorOperationKey = "business-write-v1:${hash("new")}"))
        rejected { BusinessResolutionLedger.encode(selected) }
        val wrongDesired = resolution.copy(decision = resolution.decision.copy(originalDesired = snapshot().copy(productName = "Not sealed")))
        assertFalse(evaluate(listOf(wrongDesired), originals = listOf(original)).isValid)
    }

    @Test
    fun staleSealDuplicateWithdrawalAndAbsenceOrInvalidDateCannotBecomeTerminal() {
        val original = attempt()
        val resolution = decision(seals = listOf(seal(original)))
        assertFalse(evaluate(listOf(resolution), originals = listOf(original.copy(attemptCount = 1, lastAttemptAtMs = 20))).isValid)
        assertFalse(evaluate(listOf(resolution, resolution.copy(recordId = "duplicate")), originals = listOf(original)).isValid)
        val unknown = resolution.copy(decision = resolution.decision!!.copy(remote = remote().copy(row = null, missingIds = listOf(REMOTE))))
        rejected { BusinessResolutionLedger.encode(unknown) }
        val invalidDate = resolution.copy(decision = requireNotNull(resolution.decision).copy(remote = remote().copy(row = row().copy(updatedAt = "2026-02-31T10:00:00Z"))))
        rejected { BusinessResolutionLedger.encode(invalidDate) }
    }

    @Test
    fun actualSplitAcknowledgementsRetireOnlyFullyCoveredRejectedBatchAndNeverWithdrawRevision() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val old = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23503"))
        val first = actualAck(attempt(ids = listOf(REMOTE)), "ack-first")
        val second = actualAck(attempt(ids = listOf(OTHER)), "ack-second")
        val partial = evaluate(listOf(first), originals = listOf(old)).requireValid()
        assertFalse(partial.supersededAttempt(old))
        val all = evaluate(listOf(first, second), originals = listOf(old)).requireValid()
        assertTrue(all.supersededAttempt(old))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, all.effectiveRevision(REMOTE, 1))
        assertEquals(0, all.decisions.size)
        assertEquals(stamped.metadataJson, old.metadataJson)
        assertEquals(stamped.clientEventId, old.clientEventId)
        assertFalse(all.supersededAttempt(old.copy(attemptCount = 2, lastErrorType = "business_write_response_unconfirmed")))
    }

    @Test
    fun residualActualAckRetainsExactOperationAfterDeletionWithoutAcknowledgingB2() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val original = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23503"))
        val residual = residualSuccessor()
        val first = decision(seals = listOf(seal(original)), disposition = "proven_no_commit").let {
            it.copy(decision = it.decision!!.copy(residuals = listOf(CatalogResolutionResidual(original.clientEventId,
                OTHER, 1, residual.clientEventId, residual.metadataJson, remote(OTHER), fingerprintProductInbound(row(OTHER))))))
        }
        val accepted = actualAck(residual, "ack-residual").let { it.copy(acknowledgement = it.acknowledgement!!.copy(
            returnedRow = row(OTHER).copy(updatedAt = "2026-10-23T12:00:01.123456Z"))) }
        val reopened = evaluate(listOf(first, accepted), originals = listOf(original)).requireValid()
        val revisionB = json.decodeFromString<BusinessWriteAttempt>(original.metadataJson).payload.revisions.single { it.remoteId == OTHER }
        assertTrue(reopened.hasActualAcknowledgement(original, revisionB))
        assertFalse(reopened.hasActualAcknowledgement(original, revisionB.copy(revision = 2)))
        assertEquals(BusinessResolutionLedger.EffectiveRevision.Active, reopened.effectiveRevision(OTHER, 2))
        assertFalse(evaluate(listOf(first, accepted), originals = listOf(original, residual)).isValid)
    }

    @Test
    fun actualAckDoesNotRetireUnknownDifferentBodyOrNewerRevisionAndCannotStillBePending() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val old = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23505"))
        val accepted = attempt(ids = listOf(REMOTE))
        val acknowledgement = actualAck(accepted, "ack-one")
        assertFalse(evaluate(listOf(acknowledgement), originals = listOf(old, accepted)).isValid)
        val original = json.decodeFromString<BusinessWriteAttempt>(accepted.metadataJson)
        val changed = original.copy(payload = original.payload.copy(products = original.payload.products.map { it.copy(productName = "Different") }))
        assertFalse(BusinessResolutionLedger.equivalentAcknowledgedMember(original, original.payload.revisions.single(), changed))
        val newer = original.copy(payload = original.payload.copy(revisions = original.payload.revisions.map { it.copy(revision = 2) }))
        assertFalse(BusinessResolutionLedger.equivalentAcknowledgedMember(original, original.payload.revisions.single(), newer))
        val snapshot = evaluate(listOf(acknowledgement), originals = listOf(old)).requireValid()
        assertFalse(snapshot.supersededAttempt(old.copy(lastErrorType = "business_write_response_unconfirmed")))
        rejected { BusinessResolutionLedger.encode(acknowledgement.copy(acknowledgement = acknowledgement.acknowledgement!!.copy(
            seal = acknowledgement.acknowledgement.seal.copy(dispatchCount = 0, lastAttemptAtMs = null)))) }
    }

    @Test
    fun actualAckLineageSurvivesTransferAndExactRawOldBatchRemainsRequired() {
        val stamped = attempt(ids = listOf(REMOTE, OTHER)).copy(attemptCount = 1, lastAttemptAtMs = 20)
        val old = stamped.copy(lastErrorType = BusinessResolutionLedger.encodeNoCommitProof(stamped, "23503"))
        val records = listOf(actualAck(attempt(ids = listOf(REMOTE)), "ack-first"), actualAck(attempt(ids = listOf(OTHER)), "ack-second"))
        val transferred = transfer(records, "generation-2", "generation-1").let {
            it.copy(transfer = it.transfer!!.copy(originalAttemptSeals = listOf(seal(old))))
        }
        val after = evaluate(records + transferred, originals = listOf(old), generation = "generation-2").requireValid()
        assertTrue(after.supersededAttempt(old))
        assertFalse(evaluate(records + transferred.copy(transfer = transferred.transfer!!.copy(originalAttemptSeals = emptyList())),
            originals = listOf(old), generation = "generation-2").isValid)
    }

    private fun actualAck(entry: SyncEventOutboxEntry, id: String) = BusinessResolutionRecord(
        kind = "actual_ack", recordId = id, scope = scope, generationId = "generation-1", checkpointDigest = "checkpoint-1",
        productId = null, remoteId = null, acknowledgement = CatalogActualAcknowledgement(seal(entry.copy(attemptCount = 1, lastAttemptAtMs = 30))))

    private fun evaluate(records: List<BusinessResolutionRecord>, originals: List<SyncEventOutboxEntry> = emptyList(),
        allowUnsealed: Boolean = false, generation: String = "generation-1", checkpoint: String = "checkpoint-1") =
        BusinessResolutionLedger.evaluate(records.mapIndexed { index, record -> BusinessResolutionLedger.entry(record, index.toLong()) },
            scope, generation, checkpoint, originals) { _, _ -> allowUnsealed }

    private fun intent(revision: Int = 1, dispatchKeys: List<String> = emptyList()) = BusinessResolutionRecord(
        kind = "intent_provenance", recordId = "intent-$revision", scope = scope, generationId = "generation-1",
        checkpointDigest = "checkpoint-1", productId = 1, remoteId = REMOTE,
        intent = CatalogIntentProvenance(revision, revision - 1, snapshot(), listOf("productname"), row(), emptyList(), dispatchKeys))

    private fun decision(id: String = "decision", revision: Int = 1, remoteId: String = REMOTE, productId: Long = 1,
        seals: List<CatalogResolutionOriginalSeal> = emptyList(), provenance: List<BusinessResolutionRecord> = emptyList(),
        withdrawn: List<Int> = listOf(revision), disposition: String = "never_dispatched") = BusinessResolutionRecord(
        kind = "decision", recordId = id, scope = scope, generationId = "generation-1", checkpointDigest = "checkpoint-1",
        productId = productId, remoteId = remoteId, decision = CatalogResolutionDecision(
            conflictRevision = revision, acknowledgedRevision = 0, originalMask = "productname", originalFingerprint = "original",
            originalDesired = snapshot(productId), provenBase = row(remoteId), remote = remote(remoteId), disposition = disposition,
            originalSeals = seals, provenanceKeys = provenance.map { "business-resolution-v1:${it.recordId}" },
            withdrawnRevisions = withdrawn, choice = "adopt_remote", selectedFields = emptyList(),
            projection = snapshot(productId), successorRevision = null, successorOperationKey = null))

    private fun transfer(records: List<BusinessResolutionRecord>, generation: String, previous: String) = BusinessResolutionRecord(
        kind = "generation_transfer", recordId = "transfer-$generation", scope = scope, generationId = generation,
        checkpointDigest = "checkpoint-$generation", productId = null, remoteId = null,
        transfer = CatalogResolutionTransfer(previous, "latest-source-checkpoint", "checkpoint-$generation",
            records.map { hash(BusinessResolutionLedger.encode(it)) }))

    private fun attempt(ids: List<String> = listOf(REMOTE), conditional: Boolean = false): SyncEventOutboxEntry {
        val payload = if (conditional) BusinessWritePayload(kind = "PATCH_CONDITIONAL", patchId = ids.single(),
            patch = InventoryProductPatch(setOf("productname"), productName = "Chosen"),
            revisions = listOf(BusinessWriteRevision("PRODUCT", ids.single(), 1, null)), expectedUpdatedAt = VERSION)
        else BusinessWritePayload(kind = "PRODUCTS", products = ids.map(::row),
            revisions = ids.map { BusinessWriteRevision("PRODUCT", it, 1, null) })
        val attempt = BusinessWriteAttempt(version = if (conditional) 2 else 1, owner = scope.owner, store = scope.store,
            shop = scope.shop, device = scope.device, generation = "generation-1", localStore = scope.localStore, payload = payload)
        val raw = json.encodeToString(attempt)
        return SyncEventOutboxEntry(id = 17, ownerUserId = scope.owner, storeScope = scope.store,
            domain = payload.kind, eventType = BUSINESS_WRITE_EVENT, source = "local_business_write", sourceDeviceId = scope.device,
            batchId = null, clientEventId = "business-write-v1:${hash(raw)}", changedCount = ids.size,
            entityIdsJson = "{}", metadataJson = raw, createdAtMs = 10)
    }

    private fun seal(entry: SyncEventOutboxEntry) = CatalogResolutionOriginalSeal(entry.id, entry.clientEventId,
        entry.metadataJson, entry.attemptCount, entry.lastAttemptAtMs, entry.lastErrorType)

    private fun residualSuccessor(name: String = "Name", version: String = VERSION, revision: Long = 1,
        mask: Set<String> = setOf("barcode", "itemnumber", "productname", "secondproductname", "purchaseprice",
            "retailprice", "supplier", "category", "stockquantity")): SyncEventOutboxEntry {
        val payload = BusinessWritePayload(kind = "PATCH_CONDITIONAL", patchId = OTHER,
            patch = InventoryProductPatch(mask, barcode = "BARCODE", productName = name),
            revisions = listOf(BusinessWriteRevision("PRODUCT", OTHER, revision, null)), expectedUpdatedAt = version)
        val attempt = BusinessWriteAttempt(version = 2, owner = scope.owner, store = scope.store, shop = scope.shop,
            device = scope.device, generation = "generation-1", localStore = scope.localStore, payload = payload)
        val raw = json.encodeToString(attempt)
        return SyncEventOutboxEntry(id = 18, ownerUserId = scope.owner, storeScope = scope.store, domain = payload.kind,
            eventType = BUSINESS_WRITE_EVENT, source = "local_business_write", sourceDeviceId = scope.device, batchId = null,
            clientEventId = "business-write-v1:${hash(raw)}", changedCount = 1, entityIdsJson = "{}", metadataJson = raw, createdAtMs = 11)
    }

    private fun row(id: String = REMOTE) = InventoryProductRow(id, scope.owner, scope.shop, "BARCODE", productName = "Name", updatedAt = VERSION)
    private fun remote(id: String = REMOTE) = QualifiedCatalogConflictRemote(row(id), "shop-sync-rows-by-ids-v1",
        ShopSyncScope(ShopSyncScopeKinds.SHOP_SCOPED, "scope-key", accountKey = hash(scope.owner), deviceKey = hash(scope.device)),
        "10", "10", "10", "10", ShopSyncScopeKinds.SHOP_SCOPED, emptyList())
    private fun snapshot(id: Long = 1) = CatalogProductSnapshot(id, "BARCODE", null, "Name", null, null, null,
        null, null, null, null, null, null, null)
    private fun rejected(block: () -> Unit) { assertTrue("Expected fail-closed validation", runCatching(block).isFailure) }
    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val REMOTE = "c2f9156e-1031-424d-bb54-aa2f90a87be4"
        private const val OTHER = "c5ea9708-e00b-458b-96cd-bf174bd03044"
        private const val VERSION = "2026-10-23T12:00:00.123456Z"
    }
}

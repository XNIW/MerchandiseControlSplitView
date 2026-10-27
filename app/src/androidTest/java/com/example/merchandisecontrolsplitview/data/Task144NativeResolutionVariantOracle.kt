package com.example.merchandisecontrolsplitview.data

import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

@Serializable
internal data class NativeResolutionVariantBefore(
    val generation: String,
    val productId: Long,
    val revision: Int,
    val acknowledgedRevision: Int,
    val productRows: JsonArray,
    val priceRows: JsonArray,
    val productRefs: JsonArray,
    val priceRefs: JsonArray,
    val originals: List<NativeResolutionSealImage>,
    val server: NativeResolutionServerState
)

@Serializable
internal data class NativeResolutionVariantMarker(
    val protocol: String = NativeResolutionContract.PROTOCOL,
    val runId: String,
    val scenario: String,
    val preparePid: Int,
    val elapsedRealtimeMs: Long,
    val before: NativeResolutionVariantBefore,
    val dbImage: JsonObject,
    val remote: NativeResolutionServerState,
    val serverSha256: String,
    val decisionCount: Int,
    val facts: JsonObject
)

/** Additional R4 boundaries use the same owned DB, remote ledger and production writer/reader. */
internal class NativeResolutionVariantOracle(val fixture: ScopeSwitchFixture) {
    val base = NativeResolutionOracle(fixture)
    val remote get() = requireNotNull(fixture.resolutionBackend)
    private val db get() = fixture.db
    private val json get() = NativeResolutionContract.json

    suspend fun waitForRejectedSave() {
        base.awaitCondition("variant_save_sealed_no_commit") {
            !fixture.tracker.isSyncing.value && base.writes().isNotEmpty() && base.writes().all(BusinessResolutionLedger::hasNoCommitProof)
        }
    }

    suspend fun before(): NativeResolutionVariantBefore {
        val ref = base.ref()
        val originals = base.writes()
        assertTrue(originals.isNotEmpty() && originals.all(BusinessResolutionLedger::hasNoCommitProof))
        return NativeResolutionVariantBefore(requireNotNull(db.syncRecoveryBaselineDao().get()).generationId,
            fixture.product().id, ref.localChangeRevision, ref.lastSyncedLocalRevision,
            base.table("products"), base.table("product_prices"), base.table("product_remote_refs"),
            base.table("product_price_remote_refs"), originals.map { base.seal(it) }, remote.state())
    }

    suspend fun originalsUnchanged(before: NativeResolutionVariantBefore, superseded: Boolean) {
        for (old in before.originals) {
            val row = requireNotNull(db.syncEventOutboxDao().getById(old.id))
            assertEquals(old.key, row.clientEventId); assertEquals(old.raw, row.metadataJson)
            assertEquals(old.row, base.row("sync_event_outbox", old.id))
            assertTrue(BusinessResolutionLedger.hasNoCommitProof(row))
            assertEquals(superseded, base.ledger().supersededAttempt(row))
        }
    }

    suspend fun unchangedBusiness(before: NativeResolutionVariantBefore) {
        assertEquals(before.productRows, base.table("products")); assertEquals(before.priceRows, base.table("product_prices"))
        assertEquals(before.productRefs, base.table("product_remote_refs")); assertEquals(before.priceRefs, base.table("product_price_remote_refs"))
        originalsUnchanged(before, superseded = false)
        assertTrue(base.ledger().decisions.isEmpty())
    }

    suspend fun assertDriftRejected(before: NativeResolutionVariantBefore) {
        unchangedBusiness(before)
        assertEquals(before.server.calls, remote.state().calls)
        val ui = requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value)
        assertFalse(ui.resolved); assertFalse(ui.resolving)
        assertEquals("Peer drift", ui.context?.remote?.itemNumber)
        assertEquals("Saved A", ui.context?.local?.productName)
        fixture.record("drift_returned_fresh_context_without_decision")
    }

    suspend fun assertSelectedAck(before: NativeResolutionVariantBefore, field: String, value: String?, peerName: String?, peerItem: String?) {
        base.awaitCondition("selected_variant_exact_ack") {
            val ref = base.ref()
            ref.localChangeRevision > before.revision && ref.localChangeRevision == ref.lastSyncedLocalRevision &&
                fixture.repository.observeProductCloudConfirmed(before.productId).first()
        }
        base.backgroundAndAwaitIdle(); originalsUnchanged(before, superseded = true)
        val calls = remote.state().calls.drop(before.server.calls.size)
        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals("PATCH_CONDITIONAL", call.kind); assertEquals("committed_returned", call.outcome)
        assertEquals(setOf(field), requireNotNull(call.patch).changedFields)
        assertEquals(1, call.stampedAttemptCount)
        val decision = requireNotNull(base.ledger().decisions.single().decision)
        assertEquals(before.server.product?.id, base.ledger().decisions.single().remoteId)
        assertEquals(listOf(field), decision.selectedFields)
        assertEquals(decision.remote.row?.updatedAt, call.expectedUpdatedAt)
        assertEquals(decision.remote.row?.deletedAt, call.expectedDeletedAt)
        if (field == "itemnumber") {
            assertEquals(value, call.patch.itemNumber); assertEquals(value, remote.state().product?.itemNumber)
        } else {
            assertEquals(value, call.patch.productName); assertEquals(value, remote.state().product?.productName)
        }
        assertEquals(peerName, remote.state().product?.productName)
        assertEquals(peerItem, remote.state().product?.itemNumber)
        assertEquals(before.priceRows, base.table("product_prices"))
        assertEquals(before.server.prices, remote.state().prices)
        assertFalse(base.writes().any { it.clientEventId == call.operationKey })
    }

    suspend fun assertTombstone(before: NativeResolutionVariantBefore, allowed: Boolean) {
        base.backgroundAndAwaitIdle()
        assertNotNull(remote.state().product?.deletedAt)
        assertEquals(before.server.calls, remote.state().calls)
        if (allowed) {
            assertTrue(before.priceRows.isEmpty()); assertTrue(before.server.prices.isEmpty())
            assertNull(db.productDao().getById(before.productId))
            assertNull(db.productRemoteRefDao().getByProductId(before.productId))
            assertEquals(before.priceRows, base.table("product_prices"))
            val decision = requireNotNull(base.ledger().decisions.single().decision)
            assertEquals("adopt_remote_tombstone", decision.choice)
            assertEquals(before.acknowledgedRevision, decision.acknowledgedRevision)
            assertNull(decision.successorOperationKey); assertNull(decision.successorRevision)
            assertEquals(remote.state().product, decision.remote.row)
            originalsUnchanged(before, superseded = true)
        } else {
            unchangedBusiness(before)
            assertTrue(before.priceRows.isNotEmpty())
            assertEquals(before.server.prices, remote.state().prices)
            val review = requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value)
            assertFalse(review.resolved); assertTrue(review.requiresRefresh)
            assertEquals(fixture.app.getString(com.example.merchandisecontrolsplitview.R.string.catalog_conflict_history_blocks_delete), review.message)
        }
    }

    suspend fun assertFirstCasDrift(before: NativeResolutionVariantBefore) {
        base.awaitCondition("first_conditional_drift_proven_zero_not_ack") {
            base.writes().any { it.domain == "PATCH_CONDITIONAL" && BusinessResolutionLedger.hasNoCommitProof(it) }
        }
        base.backgroundAndAwaitIdle(); originalsUnchanged(before, superseded = true)
        val call = remote.state().calls.drop(before.server.calls.size).single()
        assertEquals("PATCH_CONDITIONAL", call.kind); assertEquals("conditional_zero", call.outcome)
        assertEquals(1, call.stampedAttemptCount)
        assertEquals(before.server.product?.updatedAt, call.expectedUpdatedAt)
        assertNotEquals(call.expectedUpdatedAt, remote.state().product?.updatedAt)
        assertEquals("Original", remote.state().product?.productName)
        assertEquals("CAS peer drift", remote.state().product?.itemNumber)
        val seal = base.writes().single { it.clientEventId == call.operationKey }
        assertEquals(call.rawAttempt, seal.metadataJson); assertTrue(BusinessResolutionLedger.hasNoCommitProof(seal))
        assertEquals(before.acknowledgedRevision, base.ref().lastSyncedLocalRevision)
        assertTrue(base.ref().localChangeRevision > before.revision)
        assertFalse(fixture.repository.observeProductCloudConfirmed(before.productId).first())
        assertEquals(1, base.ledger().decisions.size)
        assertFalse(base.ledger().records.any { it.acknowledgement?.seal?.operationKey == call.operationKey })
        assertEquals(before.priceRows, base.table("product_prices")); assertEquals(before.server.prices, remote.state().prices)
    }

    suspend fun assertScopeChange(before: NativeResolutionVariantBefore) {
        fixture.awaitScope(ScopeSwitchContract.SHOP_B, Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH)
        base.backgroundAndAwaitIdle()
        unchangedBusiness(before)
        assertEquals(before.server.calls, remote.state().calls)
        assertEquals(before.generation, db.syncRecoveryBaselineDao().get()?.generationId)
        assertTrue(remote.state().reads.drop(before.server.reads.size).none { it["shop"]?.jsonPrimitive?.content == ScopeSwitchContract.SHOP_B })
        assertFalse(fixture.tracker.businessDataScopeState.value.allowsLocalOperations)
        assertFalse(fixture.databaseVM.persistedCatalogConflictReview.value?.resolved == true)
    }

    /** Force an actual same-scope recovery requirement, then let the existing Application own it. */
    suspend fun recoverWhileReviewOpen(before: NativeResolutionVariantBefore, stale: PersistedCatalogConflictContext) {
        base.backgroundAndAwaitIdle()
        val scope = NativeAckContract.scope
        val now = System.currentTimeMillis()
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = scope.ownerHash, storeScope = scope.storeId,
            shopId = NativeAckContract.SHOP, deviceId = NativeAckContract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE, phase = SyncRecoveryJournalPhases.REQUIRED,
            reason = "native R4 generation change during review", blockingEventId = null, attemptCount = 0,
            createdAtMs = now, updatedAtMs = now, nextRetryAtMs = now))
        // Real refresh drives the existing Application scope resolver and recovery admission.
        fixture.shops.refresh(NativeAckContract.OWNER)
        withTimeout(45_000) {
            while (db.syncRecoveryBaselineDao().get()?.generationId == before.generation || db.syncRecoveryJournalDao().get() != null) delay(25)
        }
        fixture.verifyResolutionReady()
        val result = fixture.repository.resolvePersistedCatalogConflict(stale, PersistedCatalogConflictChoice.AdoptRemote)
        assertEquals(PersistedCatalogConflictResult.Blocked("resolution_scope_changed"), result)
        assertTrue(base.ledger().decisions.isEmpty())
        assertTrue(base.ledger().records.any { it.kind == "generation_transfer" })
        originalsUnchanged(before, superseded = false)
        assertEquals(before.productRows, base.table("products"))
        assertEquals(before.priceRows, base.table("product_prices"))
        assertEquals(before.server.calls, remote.state().calls)
        assertFalse(fixture.databaseVM.persistedCatalogConflictReview.value?.resolved == true)
        fixture.record("stale_generation_command_rejected", buildJsonObject {
            put("previousGeneration", before.generation); put("currentGeneration", db.syncRecoveryBaselineDao().get()?.generationId)
            put("boundary", "UI comparison plus actual Application recovery; direct stale repository command is a negative fence probe")
        })
    }

    /** Native setup for an indivisible full-row batch; uses the actual import writer, not SQL edits. */
    suspend fun prepareResidualSeed() {
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote.reader)
        val tracker = CatalogSyncStateTracker(initial.resolveBusinessDataScope(NativeAckContract.scope))
        val repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote.reader, businessDataScopeRuntimeGuard = tracker)
        repository.addProduct(Product(barcode = NativeResolutionContract.BARCODE_B, productName = "Original B",
            itemNumber = "Original item B", purchasePrice = 3.0, retailPrice = 5.0))
        tracker.withBusinessDataScopeFlight(NativeAckContract.OWNER, NativeAckContract.shop) {
            repository.pushDirtyCatalogDeltaToRemote(remote, remote.prices, NativeAckContract.OWNER,
                CatalogSyncProgressReporter { }, NativeAckContract.shop).getOrThrow()
        }
    }

    suspend fun saveAtomicImportBatch() {
        val a = fixture.product()
        val b = requireNotNull(db.productDao().findByBarcode(NativeResolutionContract.BARCODE_B))
        val result = fixture.repository.applyImport(ImportApplyRequest(emptyList(), listOf(
            ProductUpdate(a, a.copy(productName = "Saved A"), listOf(2)),
            ProductUpdate(b, b.copy(productName = "Saved B1"), listOf(3)))))
        assertEquals(ImportApplyResult.Success, result)
        waitForRejectedSave()
        assertTrue(base.writes().any { json.decodeFromString<BusinessWriteAttempt>(it.metadataJson).payload.products.size == 2 })
        remote.peerChange { it.copy(productName = "Saved A") }
        fixture.record("actual_import_atomic_batch_prepared", buildJsonObject {
            put("boundary", "actual repository import writer; this preparation is not claimed as an editor Save")
        })
    }

    suspend fun assertResidualB2(before: NativeResolutionVariantBefore, b1Revision: Int, b2Revision: Int) {
        val b = requireNotNull(db.productDao().findByBarcode(NativeResolutionContract.BARCODE_B))
        base.awaitCondition("residual_b2_exact_ack") {
            db.productRemoteRefDao().getByProductId(b.id)?.lastSyncedLocalRevision == b2Revision
        }
        base.backgroundAndAwaitIdle()
        originalsUnchanged(before, superseded = true)
        val ref = requireNotNull(db.productRemoteRefDao().getByProductId(b.id))
        assertEquals(b2Revision, ref.localChangeRevision)
        assertEquals("Saved B2", requireNotNull(db.productDao().getById(b.id)).productName)
        val decisions = base.ledger().decisions
        assertEquals(1, decisions.size)
        assertEquals(before.server.product?.id, decisions.single().remoteId)
        val residual = requireNotNull(decisions.single().decision).residuals.single()
        assertEquals(ref.remoteId, residual.remoteId); assertEquals(b1Revision, residual.revision)
        val calls = remote.state().calls.drop(before.server.calls.size)
        val conditional = calls.single { it.kind == "PATCH_CONDITIONAL" }
        val newer = calls.single { it.kind == "PATCH" }
        assertEquals(2, calls.size)
        assertEquals(residual.newOperationKey, conditional.operationKey)
        assertEquals(residual.rawSuccessorAttempt, conditional.rawAttempt)
        assertEquals("Saved B1", conditional.patch?.productName)
        assertEquals("Saved B2", newer.patch?.productName)
        assertEquals(setOf("productname"), newer.patch?.changedFields)
        assertEquals(b2Revision, newer.parentLocalRevision)
        assertEquals(b1Revision, newer.parentAcknowledgedRevision)
        assertEquals("committed_returned", conditional.outcome); assertEquals("committed_returned", newer.outcome)
        assertTrue(base.ledger().records.any { it.acknowledgement?.seal?.operationKey == conditional.operationKey })
        assertEquals("Saved B2", remote.state().additionalProducts.single().productName)
        assertEquals(before.priceRows, base.table("product_prices")); assertEquals(before.server.prices, remote.state().prices)
        assertTrue(fixture.repository.observeProductCloudConfirmed(b.id).first())
    }

    suspend fun publish(before: NativeResolutionVariantBefore, facts: JsonObject = buildJsonObject { }): NativeResolutionVariantMarker {
        base.backgroundAndAwaitIdle()
        val marker = NativeResolutionVariantMarker(runId = fixture.context.runId, scenario = fixture.context.scenario,
            preparePid = Process.myPid(), elapsedRealtimeMs = SystemClock.elapsedRealtime(), before = before,
            dbImage = fixture.saveImage("variant-before-kill"), remote = remote.state(),
            serverSha256 = nativeSha(remote.file.readBytes()), decisionCount = base.ledger().decisions.size, facts = facts)
        val file = File(fixture.context.root, "ready-for-force-stop.json")
        check(!file.exists())
        nativeDurableWrite(file, json.encodeToString(marker).encodeToByteArray())
        return marker
    }

    suspend fun verifyBeforeRuntime(): NativeResolutionVariantMarker {
        val marker = json.decodeFromString<NativeResolutionVariantMarker>(File(fixture.context.root, "ready-for-force-stop.json").readText())
        assertEquals(NativeResolutionContract.PROTOCOL, marker.protocol)
        assertEquals(fixture.context.runId, marker.runId); assertEquals(fixture.context.scenario, marker.scenario)
        assertNotEquals(marker.preparePid, Process.myPid()); assertTrue(SystemClock.elapsedRealtime() >= marker.elapsedRealtimeMs)
        assertEquals(marker.dbImage, fixture.image()); assertEquals(marker.serverSha256, nativeSha(remote.file.readBytes()))
        assertEquals(marker.remote, remote.state()); assertEquals(marker.decisionCount, base.ledger().decisions.size)
        for (old in marker.before.originals) {
            assertEquals(old.row, base.row("sync_event_outbox", old.id))
            assertEquals(old.raw, db.syncEventOutboxDao().getById(old.id)?.metadataJson)
        }
        fixture.record("variant_distinct_process_verified_before_runtime", buildJsonObject {
            put("scenario", marker.scenario); put("preparePid", marker.preparePid); put("verifyPid", Process.myPid())
        })
        return marker
    }
}

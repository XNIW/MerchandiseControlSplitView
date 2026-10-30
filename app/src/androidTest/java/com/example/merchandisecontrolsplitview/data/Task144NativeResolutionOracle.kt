package com.example.merchandisecontrolsplitview.data

import android.database.Cursor
import android.os.Process
import android.os.SystemClock
import androidx.room.withTransaction
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
internal data class NativeResolutionSealImage(val id: Long, val key: String, val raw: String, val row: JsonObject)

@Serializable
internal data class NativeResolutionConflictSnapshot(
    val productId: Long,
    val remoteId: String,
    val revisionA: Int,
    val lastSyncedBeforeA: Int,
    val generation: String,
    val product: JsonObject,
    val originals: List<NativeResolutionSealImage>,
    val remoteAtComparison: InventoryProductRow,
    val callsBeforeDecision: Int,
    val childPriceId: Long? = null,
    val childPrice: JsonObject? = null,
    val priceRows: JsonArray,
    val remotePricesBeforeDecision: List<InventoryProductPriceRow>
)

@Serializable
internal data class NativeResolutionProcessMarker(
    val protocol: String = NativeResolutionContract.PROTOCOL,
    val runId: String,
    val scenario: String,
    val stage: String,
    val preparePid: Int,
    val elapsedRealtimeMs: Long,
    val conflict: NativeResolutionConflictSnapshot,
    val dbImage: JsonObject,
    val serverSha256: String,
    val state: NativeResolutionServerState,
    val revision: Int,
    val lastSynced: Int,
    val decisionEntries: List<NativeResolutionSealImage>,
    val conditionalKey: String? = null,
    val conditionalRaw: String? = null
)

/** Storage/transport oracle, independent of Compose assertions. Setup alone may request recovery.
 * All scenario dispatches are reached through the real Application coordinator's Save callbacks.
 * The marker is evidence for an external owned OS force-stop, never a substitute for that stop.
 */
internal class NativeResolutionOracle(val fixture: ScopeSwitchFixture) {
    private val db get() = fixture.db
    private val remote get() = requireNotNull(fixture.resolutionBackend)
    private val json get() = NativeResolutionContract.json
    private var foregroundAtMs: Long? = null
    private var outcomeBeforeForeground: CatalogSyncOutcomeState? = null
    private var readsBeforeForeground = 0
    internal suspend fun ref() = requireNotNull(db.productRemoteRefDao().getByProductId(fixture.product().id))
    internal suspend fun entries() = readLocalBusinessEntries(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)
    internal suspend fun writes() = entries().filter { it.eventType == BUSINESS_WRITE_EVENT }
    internal suspend fun ledger() = requireNotNull(loadBusinessResolutionSnapshot(db, NativeAckContract.OWNER,
        NativeAckContract.scope.storeId)).requireValid()

    /** Existing durable server rows become the proven canonical base via the production recovery. */
    suspend fun qualifyCanonicalBase() {
        assertEquals(NativeResolutionTransportMode.Accept, remote.state().mode)
        val scope = NativeAckContract.scope
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote.reader)
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = scope.ownerHash, storeScope = scope.storeId,
            shopId = NativeAckContract.SHOP, deviceId = NativeAckContract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "native R4 canonical fixture base",
            blockingEventId = null, attemptCount = 0, createdAtMs = 2, updatedAtMs = 2, nextRetryAtMs = 2))
        val result = ShopSyncRecoveryCoordinator(fixture.context, db, initial, remote.reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(true, "success", it)) },
            scopeStillValid = { owner, shop -> owner == NativeAckContract.OWNER && shop == NativeAckContract.SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
            .recover(NativeAckContract.OWNER, NativeAckContract.shop, scope)
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(Task126BusinessDataScopeStatus.READY, initial.resolveBusinessDataScope(scope).status)
        val ref = ref()
        assertNotNull(ref.remoteUpdatedAt); assertNotNull(ref.lastRemotePayloadFingerprint)
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        assertEquals(remote.state().product?.updatedAt, ref.remoteUpdatedAt)
        assertEquals(3.0, requireNotNull(fixture.product().purchasePrice), 0.0)
        assertEquals(5.0, requireNotNull(fixture.product().retailPrice), 0.0)
        assertTrue(writes().isEmpty())
        fixture.saveImage("canonical-base")
    }

    suspend fun startRuntime() {
        fixture.openRuntime()
        fixture.beginObservation()
        fixture.verifyResolutionReady()
        // Access installs the normal writer callbacks before any UI Save. No test callback is set.
        val coordinator = fixture.app.catalogAutoSyncCoordinator
        outcomeBeforeForeground = fixture.tracker.lastQualifiedOutcome.value
        readsBeforeForeground = remote.state().reads.size
        foregroundAtMs = System.currentTimeMillis()
        coordinator.onAppForeground()
        fixture.record("real_catalog_coordinator_foreground")
    }

    fun rejectSaveA() { remote.setMode(NativeResolutionTransportMode.RejectWithoutCommit) }
    fun loseConditionalResponse() { remote.setMode(NativeResolutionTransportMode.CommitThenLoseAck) }

    internal suspend fun awaitCondition(label: String, predicate: suspend () -> Boolean) {
        withTimeout(25_000) { while (!predicate()) delay(25) }
        fixture.record("oracle_condition", buildJsonObject { put("condition", label) })
    }

    /** Called after the actual editor Save. The peer update is an explicit controlled remote actor. */
    suspend fun afterNormalSaveAAndReject(selected: Boolean = false): NativeResolutionConflictSnapshot {
        awaitCondition("actual_save_a_typed_no_commit") {
            val writes = writes()
            writes.isNotEmpty() && writes.all(BusinessResolutionLedger::hasNoCommitProof) &&
                remote.state().calls.any { it.patch?.productName == "Saved A" && it.outcome == "rejected_no_commit" }
        }
        val product = fixture.product()
        assertEquals("Saved A", product.productName)
        val ref = ref()
        assertEquals("productname", ref.localChangedFields)
        assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
        assertFalse(fixture.repository.observeProductCloudConfirmed(product.id).first())
        remote.peerChange { if (selected) it.copy(itemNumber = "Peer item") else it.copy(productName = "Saved A") }
        var childId: Long? = null
        var childImage: JsonObject? = null
        if (!selected) {
            // A separate, already durable history intent exists before the parent decision.
            // This is fixture preparation through the normal writer, not an editor price change.
            fixture.repository.recordPriceIfChanged(product.id, "PURCHASE", 7.0,
                "2026-10-23 13:00:00", "MANUAL")
            val pending = db.productPriceDao().getAllForCloudPush()
            val child = pending.single { it.productId == product.id && it.type == "PURCHASE" && it.price == 7.0 }
            childId = child.id; childImage = row("product_prices", child.id)
            assertNull(child.existingPriceRemoteId)
            assertFalse(remote.state().prices.any { it.price == 7.0 })
            assertEquals(product, fixture.product())
        }
        val original = writes()
        assertTrue(original.isNotEmpty() && original.all(BusinessResolutionLedger::hasNoCommitProof))
        val snapshot = NativeResolutionConflictSnapshot(product.id, ref.remoteId, ref.localChangeRevision,
            ref.lastSyncedLocalRevision, requireNotNull(db.syncRecoveryBaselineDao().get()).generationId,
            row("products", product.id), original.map { seal(it) }, requireNotNull(remote.state().product),
            remote.state().calls.size, childId, childImage, table("product_prices"), remote.state().prices)
        assertEquals(listOf(product.id), fixture.repository.findPersistedCatalogConflictProductIds(NativeAckContract.OWNER, NativeAckContract.SHOP))
        save("conflict-before-review.json", json.encodeToString(snapshot))
        return snapshot
    }

    private suspend fun assertOriginals(snapshot: NativeResolutionConflictSnapshot) {
        for (original in snapshot.originals) {
            val actual = requireNotNull(db.syncEventOutboxDao().getById(original.id))
            assertEquals(original.key, actual.clientEventId); assertEquals(original.raw, actual.metadataJson)
            assertEquals(original.row, row("sync_event_outbox", original.id))
            assertTrue(BusinessResolutionLedger.hasNoCommitProof(actual))
            assertTrue(ledger().supersededAttempt(actual))
        }
    }

    private suspend fun assertChildAcknowledgedOnce(snapshot: NativeResolutionConflictSnapshot) {
        val childId = requireNotNull(snapshot.childPriceId)
        assertEquals(snapshot.childPrice, row("product_prices", childId))
        assertEquals(snapshot.priceRows, table("product_prices"))
        val calls = remote.state().calls.filter { call -> call.kind == "PRICES" && call.rawAttempt?.let { raw ->
            json.decodeFromString<BusinessWriteAttempt>(raw).payload.revisions.any { it.localPriceId == childId }
        } == true }
        assertEquals("Child price must have exactly one actual typed transport call", 1, calls.size)
        val call = calls.single()
        assertEquals("committed_returned", call.outcome)
        assertEquals(1, call.stampedAttemptCount)
        assertEquals(ledger().decisions.map { it.recordId }, call.decisionRecordIds)
        assertEquals(snapshot.revisionA, call.parentLocalRevision)
        assertEquals(snapshot.lastSyncedBeforeA, call.parentAcknowledgedRevision)
        assertEquals(BusinessResolutionLedger.EffectiveRevision.SupersededLocally.name, call.parentEffectiveRevision)
        assertFalse(writes().any { it.clientEventId == call.operationKey })
        val price = call.prices.single { it.price == 7.0 }
        assertEquals(1, remote.state().prices.count { it.id == price.id })
        assertEquals(snapshot.remoteId, price.productId)
        assertFalse(db.productPriceDao().getAllForCloudPush().any { it.id == childId })
        assertEquals(snapshot.remotePricesBeforeDecision.map { it.id }.toSet() + price.id,
            remote.state().prices.map { it.id }.toSet())
        // Existing parent decision and child ACK cannot ACK withdrawn catalog revision A.
        assertEquals(snapshot.lastSyncedBeforeA, ref().lastSyncedLocalRevision)
    }

    suspend fun assertAdoptedAndPublishRestartMarker(snapshot: NativeResolutionConflictSnapshot): NativeResolutionProcessMarker {
        awaitCondition("child_own_ack_after_parent_decision") {
            ledger().decisions.size == 1 && !db.productPriceDao().getAllForCloudPush().any { it.id == snapshot.childPriceId }
        }
        backgroundAndAwaitIdle()
        assertOriginals(snapshot)
        assertEquals(snapshot.product, row("products", snapshot.productId))
        assertEquals(snapshot.revisionA, ref().localChangeRevision)
        assertChildAcknowledgedOnce(snapshot)
        val decision = requireNotNull(ledger().decisions.single().decision)
        assertEquals("adopt_remote", decision.choice)
        assertNull(decision.successorRevision); assertNull(decision.successorOperationKey)
        assertEquals(snapshot.remoteAtComparison, decision.remote.row)
        assertEquals(snapshot.revisionA, decision.conflictRevision)
        assertEquals(snapshot.lastSyncedBeforeA, decision.acknowledgedRevision)
        assertFalse(fixture.repository.observeProductCloudConfirmed(snapshot.productId).first())
        assertTrue(fixture.repository.findPersistedCatalogConflictProductIds(NativeAckContract.OWNER, NativeAckContract.SHOP).isEmpty())
        val after = remote.state().calls.drop(snapshot.callsBeforeDecision)
        assertTrue(after.isNotEmpty() && after.all { it.kind == "PRICES" })
        return publish("ready-for-force-stop.json", "adopt", snapshot)
    }

    /** Must run before openRuntime or mount: no callback is allowed to repair a missing receipt. */
    suspend fun verifyReopen(markerName: String): NativeResolutionProcessMarker {
        val marker = json.decodeFromString<NativeResolutionProcessMarker>(File(fixture.context.root, markerName).readText())
        assertEquals(NativeResolutionContract.PROTOCOL, marker.protocol)
        assertEquals(fixture.context.runId, marker.runId); assertEquals(fixture.context.scenario, marker.scenario)
        assertNotEquals("A different native process is mandatory", marker.preparePid, Process.myPid())
        assertTrue(SystemClock.elapsedRealtime() >= marker.elapsedRealtimeMs)
        assertEquals(marker.serverSha256, nativeSha(remote.file.readBytes()))
        assertEquals(marker.state, remote.state())
        assertEquals(marker.dbImage, fixture.image())
        assertEquals(marker.revision, ref().localChangeRevision)
        assertEquals(marker.lastSynced, ref().lastSyncedLocalRevision)
        assertEquals(marker.conflict.generation, db.syncRecoveryBaselineDao().get()?.generationId)
        assertOriginals(marker.conflict)
        for (decision in marker.decisionEntries) assertEquals(decision.row, row("sync_event_outbox", decision.id))
        if (marker.stage != "unknown") assertChildAcknowledgedOnceExceptParentAck(marker.conflict, marker.lastSynced)
        fixture.record("distinct_process_receipt_verified_before_runtime", buildJsonObject {
            put("stage", marker.stage); put("previousPid", marker.preparePid); put("currentPid", Process.myPid())
        })
        return marker
    }

    private suspend fun assertChildAcknowledgedOnceExceptParentAck(snapshot: NativeResolutionConflictSnapshot, expectedAck: Int) {
        // Same proof with the valid later B acknowledgement explicitly accounted for.
        assertEquals(snapshot.childPrice, row("product_prices", requireNotNull(snapshot.childPriceId)))
        assertEquals(snapshot.priceRows, table("product_prices"))
        val calls = remote.state().calls.filter { it.kind == "PRICES" && it.rawAttempt?.let { raw ->
            json.decodeFromString<BusinessWriteAttempt>(raw).payload.revisions.any { it.localPriceId == snapshot.childPriceId }
        } == true }
        assertEquals(1, calls.size); assertEquals("committed_returned", calls.single().outcome)
        assertEquals(1, calls.single().stampedAttemptCount)
        assertFalse(writes().any { it.clientEventId == calls.single().operationKey })
        assertEquals(expectedAck, ref().lastSyncedLocalRevision)
    }

    fun acceptNormalSaveB() { remote.setMode(NativeResolutionTransportMode.Accept) }

    suspend fun assertNormalSaveBMaskAndPublishRestartMarker(previous: NativeResolutionProcessMarker): NativeResolutionProcessMarker {
        val snapshot = previous.conflict
        awaitCondition("normal_save_b_actual_ack") {
            val ref = ref()
            ref.localChangeRevision > snapshot.revisionA && ref.lastSyncedLocalRevision == ref.localChangeRevision &&
                fixture.repository.observeProductCloudConfirmed(snapshot.productId).first()
        }
        backgroundAndAwaitIdle()
        assertOriginals(snapshot)
        assertEquals("Only B", fixture.product().itemNumber)
        assertEquals("Saved A", fixture.product().productName)
        val calls = remote.state().calls.drop(previous.state.calls.size)
        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals("PATCH", call.kind)
        assertEquals(setOf("itemnumber"), requireNotNull(call.patch).changedFields)
        assertEquals("Only B", call.patch.itemNumber)
        // The production patch DTO retains contextual values; only changedFields is serialized/applied.
        assertEquals("Saved A", call.patch.productName)
        assertEquals("committed_returned", call.outcome)
        assertNotEquals(snapshot.originals.single().key, call.operationKey)
        val revisionB = ref().localChangeRevision
        val provenance = ledger().intents.single { it.intent?.revision == revisionB }.intent!!
        assertEquals(listOf("itemnumber"), provenance.intendedFields)
        assertEquals(snapshot.remoteAtComparison, provenance.provenBase)
        assertEquals("Saved A", remote.state().product?.productName)
        assertEquals("Only B", remote.state().product?.itemNumber)
        for (decision in previous.decisionEntries) assertEquals(decision.row, row("sync_event_outbox", decision.id))
        assertChildAcknowledgedOnceExceptParentAck(snapshot, revisionB)
        return publish("ready-for-second-force-stop.json", "normal-b", snapshot)
    }

    private suspend fun assertQualifiedOrdinaryWakeWithoutBusinessWrites(
        expectedCalls: List<NativeResolutionWireCall>, generation: String,
        observation: String = "terminal_ordinary_opportunity_proven") {
        val foreground = requireNotNull(foregroundAtMs)
        awaitCondition("fresh_current_scope_ordinary_outcome_after_foreground") {
            val outcome = fixture.tracker.lastQualifiedOutcome.value
            outcome != null && outcome !== outcomeBeforeForeground && outcome.source == CatalogSyncFlightOwner.SYNC_EVENTS &&
                outcome.completedAtMs?.let { it >= foreground } == true &&
                fixture.tracker.isOutcomeCurrent(outcome, NativeAckContract.OWNER, fixture.shops.state.value.selectedShop) &&
                !outcome.summary.manualFullSyncRequired && !outcome.summary.syncEventsOrdinaryPending &&
                !outcome.summary.priceSyncFailed && !outcome.summary.syncEventsGapDetected && !outcome.summary.syncEventsTooLarge &&
                outcome.summary.syncEventsAvailable && !outcome.summary.syncEventsDisabled && !outcome.summary.syncEventsFallback044 &&
                outcome.summary.syncEventOutboxPending == 0 && outcome.summary.syncEventOutboxRetried == 0 &&
                outcome.summary.syncEventsSkippedDirtyLocal == 0 &&
                remote.state().reads.drop(readsBeforeForeground).any {
                    it["method"]?.jsonPrimitive?.content in setOf("checkpoint", "marker", "eventPage") &&
                        it["account"]?.jsonPrimitive?.content == NativeAckContract.OWNER &&
                        it["shop"]?.jsonPrimitive?.content == NativeAckContract.SHOP &&
                        it["device"]?.jsonPrimitive?.content == NativeAckContract.DEVICE
                }
        }
        backgroundAndAwaitIdle()
        assertFalse(remote.state().reads.any { it["method"]?.jsonPrimitive?.content == "unexpectedOrdinaryWakeEventWrite" })
        val outcome = requireNotNull(fixture.tracker.lastQualifiedOutcome.value)
        fixture.record(observation, buildJsonObject {
            put("foregroundAtMs", foreground); put("completedAtMs", outcome.completedAtMs)
            put("owner", outcome.ownerUserId); put("source", outcome.source.name)
            put("scopedOutcomeCurrent", fixture.tracker.isOutcomeCurrent(outcome, NativeAckContract.OWNER, fixture.shops.state.value.selectedShop))
        })
        assertEquals(expectedCalls, remote.state().calls)
        assertEquals(generation, fixture.db.syncRecoveryBaselineDao().get()?.generationId)
        val active = requireNotNull(fixture.db.syncRecoveryBaselineDao().get())
        val checkpoint = decodeRecoveryCheckpointJson(active.checkpointJson)
        assertEquals(remote.state().events.size.toString(), checkpoint.syncEvents.maxId)
        assertEquals(remote.state().events.size.toLong(), fixture.db.syncEventWatermarkDao()
            .get(NativeAckContract.OWNER, NativeAckContract.scope.storeId)?.lastSyncEventId)
        validateShopSyncActiveReceipt(fixture.db, active.generationId, checkpoint)
    }

    suspend fun assertAdoptedStableBeforeNextSave(marker: NativeResolutionProcessMarker) {
        assertEquals("adopt", marker.stage)
        assertQualifiedOrdinaryWakeWithoutBusinessWrites(marker.state.calls, marker.conflict.generation,
            "adopt_reopen_ordinary_opportunity_before_save_b")
        assertOriginals(marker.conflict)
        assertEquals(marker.revision, ref().localChangeRevision)
        assertEquals(marker.lastSynced, ref().lastSyncedLocalRevision)
        assertFalse(fixture.repository.observeProductCloudConfirmed(marker.conflict.productId).first())
        assertTrue(fixture.repository.findPersistedCatalogConflictProductIds(NativeAckContract.OWNER, NativeAckContract.SHOP).isEmpty())
        for (decision in marker.decisionEntries) assertEquals(decision.row, row("sync_event_outbox", decision.id))
        assertChildAcknowledgedOnceExceptParentAck(marker.conflict, marker.lastSynced)
        // Every assertion above runs with the read-only ordinary transport configured.
        // Only its completed, quiescent boundary restores the original Save B fixture path.
        remote.finishOrdinaryWakeBeforeNormalSave()
        assertFalse(remote.ordinaryWakeEvents.isConfigured)
        fixture.record("qualified_ordinary_wake_finished_before_normal_save_b")
    }

    suspend fun assertPostAckStable(marker: NativeResolutionCrashMarker) {
        assertEquals("r4-crash-after-ack", marker.scenario)
        assertQualifiedOrdinaryWakeWithoutBusinessWrites(marker.state.calls, marker.conflict.generation)
        assertOriginals(marker.conflict)
        assertTrue(fixture.repository.observeProductCloudConfirmed(marker.conflict.productId).first())
        assertEquals(marker.conflict.revisionA + 1, ref().lastSyncedLocalRevision)
        assertNull(writes().singleOrNull { it.clientEventId == marker.conditionalKey })
        assertEquals(marker.decisionId, ledger().decisions.single().recordId)
        val decision = marker.dbImage.getValue("sync_event_outbox").jsonArray
            .map { json.parseToJsonElement(it.jsonPrimitive.content).jsonObject }
            .single { it["clientEventId"]?.jsonPrimitive?.content == "3:business-resolution-v1:${marker.decisionId}" }
        val id = decision.getValue("id").jsonPrimitive.content.removePrefix("1:").toLong()
        assertEquals(decision, row("sync_event_outbox", id))
        assertEquals(marker.conflict.priceRows, table("product_prices"))
        assertEquals(marker.state.prices, remote.state().prices)
    }

    suspend fun assertFinalStable(marker: NativeResolutionProcessMarker) {
        assertEquals("normal-b", marker.stage)
        assertQualifiedOrdinaryWakeWithoutBusinessWrites(marker.state.calls, marker.conflict.generation)
        assertOriginals(marker.conflict)
        assertTrue(fixture.repository.observeProductCloudConfirmed(marker.conflict.productId).first())
        assertEquals(marker.revision, ref().lastSyncedLocalRevision)
        assertChildAcknowledgedOnceExceptParentAck(marker.conflict, marker.revision)
    }

    suspend fun assertUnknownAndPublishRestartMarker(snapshot: NativeResolutionConflictSnapshot): NativeResolutionProcessMarker {
        awaitCondition("conditional_commit_response_unconfirmed") {
            writes().any { it.domain == "PATCH_CONDITIONAL" && it.lastErrorType == "business_write_response_unconfirmed" } &&
                remote.state().calls.any { it.kind == "PATCH_CONDITIONAL" && it.outcome == "committed_response_lost" }
        }
        backgroundAndAwaitIdle(); assertOriginals(snapshot)
        val call = remote.state().calls.single { it.kind == "PATCH_CONDITIONAL" }
        val seal = writes().single { it.clientEventId == call.operationKey }
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(seal))
        assertEquals(snapshot.lastSyncedBeforeA, ref().lastSyncedLocalRevision)
        assertFalse(fixture.repository.observeProductCloudConfirmed(snapshot.productId).first())
        assertEquals(setOf("productname"), requireNotNull(call.patch).changedFields)
        assertEquals(snapshot.remoteAtComparison.updatedAt, call.expectedUpdatedAt)
        assertEquals(snapshot.remoteAtComparison.deletedAt, call.expectedDeletedAt)
        assertEquals("Saved A", remote.state().product?.productName)
        assertEquals("Peer item", remote.state().product?.itemNumber)
        assertEquals(1, ledger().decisions.size)
        assertFalse(ledger().records.any { it.acknowledgement?.seal?.operationKey == call.operationKey })
        return publish("ready-for-force-stop.json", "unknown", snapshot, call.operationKey, call.rawAttempt)
    }

    /** Actual ordinary Save B after reopen causes the real writer callback to replay the seal. */
    suspend fun assertUnknownReplayRemainsPending(previous: NativeResolutionProcessMarker,
        replayOutcome: String = "conditional_zero") {
        check(replayOutcome in setOf("conditional_zero", "rejected_no_commit"))
        awaitCondition("exact_conditional_replay_$replayOutcome") {
            remote.state().calls.drop(previous.state.calls.size).any { it.operationKey == previous.conditionalKey && it.outcome == replayOutcome }
        }
        backgroundAndAwaitIdle()
        assertOriginals(previous.conflict)
        val original = previous.state.calls.single { it.operationKey == previous.conditionalKey }
        val replays = remote.state().calls.drop(previous.state.calls.size)
        assertTrue(replays.isNotEmpty() && replays.all { it.operationKey == previous.conditionalKey })
        for (call in replays) {
            assertEquals(previous.conditionalRaw, call.rawAttempt)
            assertEquals(original.expectedUpdatedAt, call.expectedUpdatedAt)
            assertEquals(original.expectedDeletedAt, call.expectedDeletedAt)
            assertEquals(original.patch, call.patch); assertEquals(replayOutcome, call.outcome)
            assertTrue(requireNotNull(call.stampedAttemptCount) > requireNotNull(original.stampedAttemptCount))
        }
        val seal = writes().single { it.clientEventId == previous.conditionalKey }
        assertEquals(previous.conditionalRaw, seal.metadataJson)
        assertEquals("business_write_response_unconfirmed", seal.lastErrorType)
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(seal))
        assertEquals(previous.conflict.lastSyncedBeforeA, ref().lastSyncedLocalRevision)
        assertTrue(ref().localChangeRevision > previous.revision)
        assertEquals("B after unknown", fixture.product().itemNumber)
        assertEquals(previous.state.product, remote.state().product)
        assertEquals(1, ledger().decisions.size)
        assertFalse(ledger().records.any { it.acknowledgement?.seal?.operationKey == previous.conditionalKey })
        assertTrue(fixture.repository.hasCatalogCloudPendingWorkInclusive())
        assertFalse(fixture.repository.observeProductCloudConfirmed(previous.conflict.productId).first())
        val review = fixture.repository.preparePersistedCatalogConflict(previous.conflict.productId, NativeAckContract.OWNER, NativeAckContract.SHOP)
        assertFalse(review.canResolve); assertEquals("resolution_outcome_unconfirmed", review.blockingReason)
        fixture.saveImage("unknown-after-replay")
    }

    internal suspend fun backgroundAndAwaitIdle() {
        fixture.app.catalogAutoSyncCoordinator.onAppBackground()
        awaitCondition("catalog_background_quiescent") { !fixture.tracker.isSyncing.value }
    }

    private suspend fun publish(name: String, stage: String, snapshot: NativeResolutionConflictSnapshot,
        conditionalKey: String? = null, conditionalRaw: String? = null): NativeResolutionProcessMarker {
        val image = fixture.saveImage("before-kill-$stage")
        // An independent SQLite connection sees the same committed logical contents (including WAL).
        val readback = android.database.sqlite.SQLiteDatabase.openDatabase(fixture.context.getDatabasePath(ScopeSwitchContract.DB).path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            readback.rawQuery("SELECT productName, itemNumber FROM products WHERE id = ?", arrayOf(snapshot.productId.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals(fixture.product().productName, it.getString(0))
                assertEquals(fixture.product().itemNumber, it.getString(1)); assertFalse(it.moveToNext())
            }
        } finally { readback.close() }
        val decisionEntries = entries().filter { it.eventType == BUSINESS_RESOLUTION_EVENT && BusinessResolutionLedger.decode(it).kind == "decision" }
        assertEquals(1, decisionEntries.size)
        val marker = NativeResolutionProcessMarker(runId = fixture.context.runId, scenario = fixture.context.scenario,
            stage = stage, preparePid = Process.myPid(), elapsedRealtimeMs = SystemClock.elapsedRealtime(), conflict = snapshot,
            dbImage = image, serverSha256 = nativeSha(remote.file.readBytes()), state = remote.state(),
            revision = ref().localChangeRevision, lastSynced = ref().lastSyncedLocalRevision,
            decisionEntries = decisionEntries.map { seal(it) }, conditionalKey = conditionalKey, conditionalRaw = conditionalRaw)
        save(name, json.encodeToString(marker))
        return marker
    }

    internal suspend fun seal(entry: SyncEventOutboxEntry) = NativeResolutionSealImage(entry.id,
        entry.clientEventId, entry.metadataJson, row("sync_event_outbox", entry.id))

    internal suspend fun row(table: String, id: Long): JsonObject = rows(table, id).single().jsonObject
    internal suspend fun table(table: String): JsonArray = JsonArray(rows(table, null))
    private suspend fun rows(table: String, id: Long?): List<JsonElement> = db.withTransaction {
        check(table in setOf("products", "product_prices", "product_remote_refs", "product_price_remote_refs", "sync_event_outbox"))
        db.openHelper.readableDatabase.query("SELECT * FROM `$table`" + if (id == null) " ORDER BY id" else " WHERE id = $id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(buildJsonObject {
                    cursor.columnNames.forEachIndexed { index, column -> put(column, when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                        else -> "${cursor.getType(index)}:" + cursor.getString(index)
                    }) }
                })
            }
        }
    }

    private fun save(name: String, text: String) {
        check(Regex("[a-z0-9-]+\\.json").matches(name))
        val file = File(fixture.context.root, name)
        check(!file.exists()) { "Prior native evidence is immutable: $name" }
        nativeDurableWrite(file, text.encodeToByteArray())
    }
}

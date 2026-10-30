package com.example.merchandisecontrolsplitview.ui.navigation

import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Crash selectors extend the existing owned R4 campaign. Host force-stop is mandatory.
 * No unsaved editor draft is expected to survive death; Save A has already committed in every case.
 */
@RunWith(AndroidJUnit4::class)
class Task144PersistedResolutionCrashDeviceTest {
    // Preserve the v1 test scheduler/clock baseline; v2 changes UnconfinedTestDispatcher to StandardTestDispatcher.
    @Suppress("DEPRECATION")
    @get:Rule val compose = createComposeRule()
    private val ui by lazy { NativeResolutionUiDriver(compose) }
    private val json get() = NativeResolutionContract.json

    @Test fun prepareCrashBeforeSuccessorSeal(): Unit = runBlocking { prepare("r4-crash-before-seal") }
    @Test fun prepareCrashAfterSealBeforeDecisionInsert(): Unit = runBlocking { prepare("r4-crash-before-decision") }
    @Test fun prepareCrashAfterReceiptBeforeDecisionCommit(): Unit = runBlocking { prepare("r4-crash-before-commit") }
    @Test fun prepareCrashAfterDecisionCommitBeforeDispatchStamp(): Unit = runBlocking { prepare("r4-crash-after-commit") }
    @Test fun prepareCrashAfterDispatchBeforeRemoteCommit(): Unit = runBlocking { prepare("r4-crash-before-remote") }
    @Test fun prepareCrashAfterRemoteCommitBeforeResponse(): Unit = runBlocking { prepare("r4-crash-after-remote") }
    @Test fun prepareCrashAfterResponseBeforeAcknowledgement(): Unit = runBlocking { prepare("r4-crash-before-ack") }
    @Test fun prepareCrashAfterAckProjectionBeforeReceiptDelete(): Unit = runBlocking { prepare("r4-crash-during-ack") }
    @Test fun prepareCrashAfterAcknowledgementCommit(): Unit = runBlocking { prepare("r4-crash-after-ack") }

    private suspend fun prepare(scenario: String) = withTimeout(180_000) {
        owned(scenario, fresh = true) { fixture, base, probe ->
            fixture.seed(); base.qualifyCanonicalBase(); base.startRuntime(); ui.mount(fixture)
            base.rejectSaveA()
            assertEquals(fixture.product().id, ui.saveNameThroughEditor("Saved A"))
            val conflict = base.afterNormalSaveAAndReject(selected = true)
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            ui.captureUi("crash-qualified-review-before-choice")
            base.awaitCondition("precrash_previous_cycle_finished") { !fixture.tracker.isSyncing.value }
            val before = fixture.saveImage("crash-before-decision")
            val context = requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value?.context)
            assertEquals(conflict.revisionA, context.conflictRevision)
            val variant = NativeResolutionVariantOracle(fixture)
            val variantBefore = variant.before()
            requireNotNull(probe).arm(fixture, conflict, before, context.decisionId)
            val remote = requireNotNull(fixture.resolutionBackend)
            remote.setMode(NativeResolutionTransportMode.Accept)
            if (scenario == "r4-crash-before-remote") remote.beforeConditionalCompare = probe::holdBeforeRemoteCommit
            if (scenario == "r4-crash-after-remote") remote.afterConditionalCommit = { probe.holdAfterRemoteCommit() }
            ui.applySelected()
            if (scenario == "r4-crash-after-ack") {
                ui.awaitResolved()
                variant.assertSelectedAck(variantBefore, "productname", "Saved A", "Saved A", "Peer item")
                withContext(Dispatchers.IO) { probe.publishAfterAck() }
            } else {
                withTimeout(25_000) {
                    while (probe.marker == null) { probe.failure?.let { throw it }; delay(25) }
                }
                // No Room call here: the deliberately held writer may own the connection.
                // Timeout/cleanup releases as failure; only host OS death meets this boundary.
                awaitCancellation()
            }
        }
    }

    @Test fun verifyCrashBoundaryInNewProcess(): Unit = runBlocking {
        withTimeout(180_000) {
            val scenario = requireNotNull(InstrumentationRegistry.getArguments().getString("nativeResolutionScenario"))
            owned(scenario, fresh = false) { fixture, base, _ ->
                val root = fixture.context.root
                assertFalse(File(root, "crash-boundary-failed.json").exists())
                val marker = json.decodeFromString<NativeResolutionCrashMarker>(File(root, "ready-for-force-stop.json").readText())
                assertEquals(NativeResolutionContract.PROTOCOL, marker.protocol)
                assertEquals(fixture.context.runId, marker.runId); assertEquals(scenario, marker.scenario)
                assertNotEquals(marker.preparePid, Process.myPid())
                assertTrue(SystemClock.elapsedRealtime() >= marker.elapsedRealtimeMs)
                assertEquals(marker.dbImage, fixture.image())
                val remote = requireNotNull(fixture.resolutionBackend)
                assertEquals(marker.serverSha256, nativeSha(remote.file.readBytes()))
                assertEquals(marker.state, remote.state())
                NativeResolutionCrashProbe.assertBoundary(marker)
                fixture.record("crash_reopen_exact_committed_state_before_runtime", buildJsonObject {
                    put("boundary", marker.boundary); put("preparePid", marker.preparePid); put("verifyPid", Process.myPid())
                    put("committedImageSha256", nativeSha(marker.dbImage.toString().encodeToByteArray()))
                })
                val ledger = base.ledger()
                val preCommit = scenario in setOf("r4-crash-before-seal", "r4-crash-before-decision", "r4-crash-before-commit")
                if (preCommit) {
                    assertTrue(ledger.decisions.isEmpty())
                    assertEquals(marker.beforeDecisionImage, fixture.image())
                    for (original in marker.conflict.originals) {
                        assertFalse(ledger.supersededAttempt(requireNotNull(fixture.db.syncEventOutboxDao().getById(original.id))))
                    }
                    // Real review is rediscovered; no unsaved proposal is required to survive.
                    base.startRuntime(); ui.mount(fixture); ui.openReviewFromOptions(); ui.awaitDecisionReady()
                    val restored = requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value?.context)
                    assertEquals(marker.conflict.revisionA, restored.conflictRevision)
                    assertNotEquals(marker.decisionId, restored.decisionId)
                    assertEquals("Saved A", restored.local.productName)
                    ui.captureUi("crash-precommit-reopened-unresolved-save-a")
                    base.backgroundAndAwaitIdle()
                    assertTrue(base.ledger().decisions.isEmpty())
                    assertEquals(marker.state.calls, remote.state().calls)
                } else if (scenario == "r4-crash-after-ack") {
                    base.startRuntime()
                    base.assertPostAckStable(marker)
                } else {
                    assertEquals(marker.decisionId, ledger.decisions.single().recordId)
                    for (original in marker.conflict.originals) {
                        assertTrue(ledger.supersededAttempt(requireNotNull(fixture.db.syncEventOutboxDao().getById(original.id))))
                    }
                    assertEquals(marker.conflict.lastSyncedBeforeA, base.ref().lastSyncedLocalRevision)
                    base.startRuntime(); ui.mount(fixture)
                    assertFalse(fixture.repository.observeProductCloudConfirmed(marker.conflict.productId).first())
                    // Actual Save B triggers existing coordinator; foreground alone is not claimed to dispatch.
                    assertEquals(marker.conflict.productId, ui.saveItemNumberThroughEditor("Peer item", "B after crash"))
                    val remoteCommitted = scenario in setOf("r4-crash-after-remote", "r4-crash-before-ack", "r4-crash-during-ack")
                    base.awaitCondition("crash_exact_successor_replay") {
                        remote.state().calls.drop(marker.state.calls.size).any { it.operationKey == marker.conditionalKey &&
                            it.outcome == if (remoteCommitted) "conditional_zero" else "committed_returned" }
                    }
                    if (remoteCommitted) assertUnconfirmedReplay(fixture, base, marker)
                    else assertExactReplayAndOwnB(fixture, base, marker)
                }
                nativeDurableWrite(File(root, "crash-reopen-oracle.json"), buildJsonObject {
                    put("protocol", marker.protocol); put("runId", marker.runId); put("scenario", marker.scenario)
                    put("boundary", marker.boundary); put("preparePid", marker.preparePid); put("verifyPid", Process.myPid())
                    put("originalsPreserved", true); put("committedReopenMatchedBeforeRuntime", true)
                }.toString().encodeToByteArray())
            }
        }
    }

    private suspend fun assertUnconfirmedReplay(fixture: ScopeSwitchFixture, base: NativeResolutionOracle,
        marker: NativeResolutionCrashMarker) {
        base.awaitCondition("crash_replay_zero_persisted_unknown") {
            base.writes().singleOrNull { it.clientEventId == marker.conditionalKey }?.lastErrorType == "business_write_response_unconfirmed"
        }
        base.backgroundAndAwaitIdle()
        val remote = requireNotNull(fixture.resolutionBackend)
        val calls = remote.state().calls.drop(marker.state.calls.size)
        assertTrue(calls.isNotEmpty())
        for ((index, replay) in calls.withIndex()) {
            assertEquals(marker.conditionalKey, replay.operationKey); assertEquals(marker.conditionalRaw, replay.rawAttempt)
            assertEquals(marker.conflict.remoteAtComparison.updatedAt, replay.expectedUpdatedAt)
            assertEquals(marker.conflict.remoteAtComparison.deletedAt, replay.expectedDeletedAt)
            assertEquals(2 + index, replay.stampedAttemptCount); assertEquals("conditional_zero", replay.outcome)
        }
        val retained = base.writes().single { it.clientEventId == marker.conditionalKey }
        assertEquals(marker.conditionalRaw, retained.metadataJson)
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(retained))
        assertEquals(marker.conflict.lastSyncedBeforeA, base.ref().lastSyncedLocalRevision)
        assertTrue(base.ref().localChangeRevision > marker.conflict.revisionA + 1)
        assertEquals("B after crash", fixture.product().itemNumber)
        assertEquals(marker.state.product, remote.state().product)
        assertFalse(fixture.repository.observeProductCloudConfirmed(marker.conflict.productId).first())
        assertFalse(base.ledger().records.any { it.acknowledgement?.seal?.operationKey == marker.conditionalKey })
        ui.openReviewFromOptions()
        val context = requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value?.context)
        assertFalse(context.canResolve); assertEquals("resolution_outcome_unconfirmed", context.blockingReason)
        ui.captureUi("crash-remote-commit-replay-remains-unconfirmed")
        assertOriginals(fixture, base, marker)
    }

    private suspend fun assertExactReplayAndOwnB(fixture: ScopeSwitchFixture, base: NativeResolutionOracle,
        marker: NativeResolutionCrashMarker) {
        base.awaitCondition("crash_replay_and_newer_b_own_ack") {
            base.ref().localChangeRevision == base.ref().lastSyncedLocalRevision &&
                fixture.repository.observeProductCloudConfirmed(marker.conflict.productId).first()
        }
        base.backgroundAndAwaitIdle()
        val remote = requireNotNull(fixture.resolutionBackend)
        val calls = remote.state().calls.drop(marker.state.calls.size)
        assertEquals(2, calls.size)
        val replay = calls[0]; val newer = calls[1]
        assertEquals(marker.conditionalKey, replay.operationKey); assertEquals(marker.conditionalRaw, replay.rawAttempt)
        assertEquals(if (marker.scenario == "r4-crash-after-commit") 1 else 2, replay.stampedAttemptCount)
        assertEquals("PATCH_CONDITIONAL", replay.kind); assertEquals("committed_returned", replay.outcome)
        assertEquals(marker.conflict.remoteAtComparison.updatedAt, replay.expectedUpdatedAt)
        assertEquals("PATCH", newer.kind); assertEquals("committed_returned", newer.outcome)
        val newerFields = requireNotNull(newer.patch).changedFields
        assertTrue("itemnumber" in newerFields)
        assertTrue(setOf("productname", "itemnumber").containsAll(newerFields))
        assertEquals("B after crash", newer.patch.itemNumber)
        // A was still active when B was saved; its name may remain in B's aggregate mask.
        // The separate adopt-no-op scenario, where A is withdrawn, requires itemnumber only.
        assertEquals(marker.conflict.revisionA + 1, newer.parentAcknowledgedRevision)
        assertEquals(base.ref().localChangeRevision, newer.parentLocalRevision)
        assertEquals("B after crash", remote.state().product?.itemNumber)
        assertEquals("Saved A", remote.state().product?.productName)
        assertNull(base.writes().singleOrNull { it.clientEventId == marker.conditionalKey })
        assertOriginals(fixture, base, marker)
    }

    private suspend fun assertOriginals(fixture: ScopeSwitchFixture, base: NativeResolutionOracle,
        marker: NativeResolutionCrashMarker) {
        for (original in marker.conflict.originals) {
            assertEquals(original.row, base.row("sync_event_outbox", original.id))
            assertEquals(original.raw, fixture.db.syncEventOutboxDao().getById(original.id)?.metadataJson)
        }
        assertEquals(marker.conflict.priceRows, base.table("product_prices"))
        assertEquals(marker.state.prices, requireNotNull(fixture.resolutionBackend).state().prices)
    }

    private suspend fun owned(scenario: String, fresh: Boolean,
        block: suspend (ScopeSwitchFixture, NativeResolutionOracle, NativeResolutionCrashProbe?) -> Unit) {
        check(scenario in NativeResolutionContract.crashMethods)
        ui.installContent()
        val context = nativeResolutionContext(scenario, fresh)
        val probe = if (fresh) NativeResolutionCrashProbe(context) else null
        val fixture = ScopeSwitchFixture(context, nativeResolutionMode = true, nativeResolutionCrashProbe = probe,
            nativeResolutionOrdinaryWake = !fresh && scenario == "r4-crash-after-ack")
        var primary: Throwable? = null
        var cleanupFailure: Throwable? = null
        try { block(fixture, NativeResolutionOracle(fixture), probe) }
        catch (failure: Throwable) {
            primary = failure
            runCatching { fixture.record("FAIL", buildJsonObject { put("type", failure::class.java.name) }) }
            throw failure
        } finally {
            probe?.disarm()
            withContext(NonCancellable) {
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        val original = primary ?: cleanupFailure
                        if (original == null) cleanupFailure = failure else if (original !== failure) original.addSuppressed(failure)
                    }
                }
                cleanup { ui.unmount() }; cleanup { fixture.close() }
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
        check(!fresh)
        val file = File(context.root, "verified.json")
        check(!file.exists())
        nativeDurableWrite(file, buildJsonObject {
            put("protocol", NativeResolutionContract.PROTOCOL); put("runId", context.runId); put("scenario", scenario)
            put("verifyPid", Process.myPid()); put("status", "PASS_AFTER_CLEANUP")
            put("boundary", "native actual UI/Room transaction/OS kill with synthetic scoped remote; not authenticated TEST")
        }.toString().encodeToByteArray())
    }
}

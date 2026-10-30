package com.example.merchandisecontrolsplitview.ui.navigation

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Additional mandatory R4 cases in the same owned native campaign, with exact one-method selection.
 * Every prepare publishes an immutable marker and waits for the existing external OS force-stop.
 * No case is complete until verifyVariantInNewProcess opens the same DB in a different process.
 */
@RunWith(AndroidJUnit4::class)
class Task144PersistedResolutionVariantsDeviceTest {
    // Preserve the v1 test scheduler/clock baseline; v2 changes UnconfinedTestDispatcher to StandardTestDispatcher.
    @Suppress("DEPRECATION")
    @get:Rule val compose = createComposeRule()
    private val ui by lazy { NativeResolutionUiDriver(compose) }

    @Test fun prepareDriftRequiresNewDecision(): Unit = runBlocking {
        prepare("r4-drift") { fixture, oracle ->
            saveA(fixture, oracle)
            oracle.remote.peerChange { it.copy(itemNumber = "Peer item") }
            val before = oracle.before()
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            ui.captureUi("drift-before-choice")
            oracle.remote.peerChange { it.copy(itemNumber = "Peer drift") }
            ui.applySelected()
            compose.waitUntil(10_000) {
                fixture.databaseVM.persistedCatalogConflictReview.value?.let {
                    !it.resolving && it.context?.remote?.itemNumber == "Peer drift"
                } == true
            }
            oracle.assertDriftRejected(before)
            compose.onNodeWithTag("catalog-conflict-field-productname").assertIsOn()
            ui.captureUi("drift-new-comparison")
            oracle.remote.setMode(NativeResolutionTransportMode.Accept)
            // A second explicit real button tap acts on the updated qualified comparison.
            ui.applySelected(); ui.awaitResolved()
            oracle.assertSelectedAck(before, "productname", "Saved A", "Saved A", "Peer drift")
            oracle.publish(before, buildJsonObject { put("outcome", "drift_rejected_then_fresh_choice_acknowledged") })
        }
    }

    @Test fun prepareExplicitNullSelectedField(): Unit = runBlocking {
        prepare("r4-null") { fixture, oracle ->
            oracle.base.rejectSaveA()
            assertEquals(fixture.product().id, ui.clearItemNumberThroughEditor("Original item"))
            oracle.waitForRejectedSave()
            assertNull(fixture.product().itemNumber)
            oracle.remote.peerChange { it.copy(itemNumber = "Peer item", productName = "Peer title") }
            val before = oracle.before()
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("itemnumber")
            ui.captureUi("null-ready")
            oracle.remote.setMode(NativeResolutionTransportMode.Accept)
            ui.applySelected(); ui.awaitResolved()
            oracle.assertSelectedAck(before, "itemnumber", null, "Peer title", null)
            oracle.publish(before, buildJsonObject { put("outcome", "explicit_null_only_selected_field_acknowledged") })
        }
    }

    @Test fun prepareCasDriftAfterDecisionNeverAcknowledges(): Unit = runBlocking {
        prepare("r4-cas-drift") { fixture, oracle ->
            saveA(fixture, oracle)
            oracle.remote.peerChange { it.copy(itemNumber = "Peer item") }
            val before = oracle.before()
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            oracle.remote.setMode(NativeResolutionTransportMode.Accept)
            oracle.remote.beforeConditionalCompare = {
                oracle.remote.beforeConditionalCompare = null
                oracle.remote.peerChange { it.copy(itemNumber = "CAS peer drift") }
            }
            ui.applySelected(); ui.awaitResolved()
            oracle.assertFirstCasDrift(before)
            ui.dismissReview(); ui.cancelUnderlyingEditor(); ui.openReviewFromOptions()
            assertFalse(requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value).resolved)
            assertEquals("CAS peer drift", fixture.databaseVM.persistedCatalogConflictReview.value?.context?.remote?.itemNumber)
            ui.captureUi("cas-drift-needs-new-choice")
            oracle.publish(before, buildJsonObject { put("outcome", "postdecision_cas_drift_zero_not_ack_new_review") })
        }
    }

    @Test fun prepareAllowedTombstoneWithoutChildren(): Unit = runBlocking {
        prepare("r4-tombstone") { fixture, oracle ->
            saveA(fixture, oracle)
            oracle.remote.peerChange { it.copy(deletedAt = "2026-10-23T14:00:00.000000Z") }
            val before = oracle.before()
            assertTrue(before.priceRows.isEmpty()); assertTrue(before.server.prices.isEmpty())
            ui.openReviewFromOptions(); ui.awaitTombstoneReady(); ui.captureUi("tombstone-ready")
            ui.adoptTombstone(); ui.awaitResolved(); ui.captureUi("tombstone-adopted")
            oracle.assertTombstone(before, allowed = true)
            oracle.publish(before, buildJsonObject { put("outcome", "remote_tombstone_adopted_without_dependencies") })
        }
    }

    @Test fun prepareTombstonePreservesChildren(): Unit = runBlocking {
        prepare("r4-tombstone-child") { fixture, oracle ->
            saveA(fixture, oracle)
            oracle.remote.peerChange { it.copy(deletedAt = "2026-10-23T14:00:00.000000Z") }
            val before = oracle.before()
            ui.openReviewFromOptions(); ui.awaitTombstoneReady(); ui.captureUi("tombstone-child-ready")
            ui.adoptTombstone()
            compose.waitUntil(10_000) {
                fixture.databaseVM.persistedCatalogConflictReview.value?.let { !it.resolving && it.requiresRefresh } == true
            }
            compose.onNodeWithTag("catalog-conflict-adopt-tombstone").assertIsNotEnabled()
            ui.captureUi("tombstone-child-preserved")
            oracle.assertTombstone(before, allowed = false)
            oracle.publish(before, buildJsonObject { put("outcome", "tombstone_blocked_history_preserved_no_cascade") })
        }
    }

    @Test fun prepareScopeSwitchInvalidatesReview(): Unit = runBlocking {
        prepare("r4-scope") { fixture, oracle ->
            saveA(fixture, oracle)
            oracle.remote.peerChange { it.copy(itemNumber = "Peer item") }
            val before = oracle.before()
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            ui.captureUi("scope-review-before-switch")
            assertTrue(fixture.shops.selectShop(ScopeSwitchContract.SHOP_B))
            oracle.assertScopeChange(before)
            compose.waitForIdle()
            compose.onNodeWithTag("catalog-conflict-apply-selected").assertDoesNotExist()
            compose.onNodeWithTag("catalog-conflict-adopt-remote").assertDoesNotExist()
            ui.captureUi("scope-review-inaccessible")
            oracle.publish(before, buildJsonObject { put("outcome", "old_scope_review_inaccessible_and_business_preserved") })
        }
    }

    @Test fun prepareGenerationChangeInvalidatesReview(): Unit = runBlocking {
        prepare("r4-generation") { fixture, oracle ->
            saveA(fixture, oracle)
            oracle.remote.peerChange { it.copy(itemNumber = "Peer item") }
            val before = oracle.before()
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            val stale = requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value?.context)
            ui.captureUi("generation-review-before-recovery")
            oracle.recoverWhileReviewOpen(before, stale)
            compose.waitForIdle()
            val review = fixture.databaseVM.persistedCatalogConflictReview.value
            assertTrue(review == null || review.requiresRefresh || review.context?.decisionId != stale.decisionId)
            ui.captureUi("generation-stale-review-rejected")
            oracle.publish(before, buildJsonObject { put("outcome", "actual_recovery_transfer_stale_generation_fenced") })
        }
    }

    @Test fun prepareUnknownThen23503Replay(): Unit = runBlocking {
        prepare("r4-unknown-23503") { fixture, oracle ->
            oracle.base.rejectSaveA()
            assertEquals(fixture.product().id, ui.saveNameThroughEditor("Saved A"))
            val before = oracle.base.afterNormalSaveAAndReject(selected = true)
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            oracle.base.loseConditionalResponse()
            ui.applySelected(); ui.awaitResolved()
            oracle.base.assertUnknownAndPublishRestartMarker(before)
        }
    }

    @Test fun prepareUnknownThen23505Replay(): Unit = runBlocking {
        prepare("r4-unknown-23505") { fixture, oracle ->
            oracle.base.rejectSaveA()
            assertEquals(fixture.product().id, ui.saveNameThroughEditor("Saved A"))
            val before = oracle.base.afterNormalSaveAAndReject(selected = true)
            ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.selectField("productname")
            oracle.base.loseConditionalResponse()
            ui.applySelected(); ui.awaitResolved()
            oracle.base.assertUnknownAndPublishRestartMarker(before)
        }
    }

    @Test fun prepareResidualB1AckPreservesNewerB2(): Unit = runBlocking {
        prepare("r4-residual-b2") { fixture, oracle ->
            oracle.base.rejectSaveA()
            oracle.saveAtomicImportBatch()
            val before = oracle.before()
            val b = requireNotNull(fixture.db.productDao().findByBarcode(NativeResolutionContract.BARCODE_B))
            val b1Ref = requireNotNull(fixture.db.productRemoteRefDao().getByProductId(b.id))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            oracle.remote.afterConditionalCommit = { row ->
                assertEquals(b1Ref.remoteId, row.id)
                entered.complete(Unit); release.await()
            }
            try {
                ui.openReviewFromOptions(); ui.awaitDecisionReady(); ui.captureUi("residual-a-review")
                assertEquals(before.productId, fixture.databaseVM.persistedCatalogConflictReview.value?.context?.productId)
                oracle.remote.setMode(NativeResolutionTransportMode.Accept)
                ui.adoptRemote(); ui.awaitResolved()
                withTimeout(25_000) { entered.await() }
                // B1 has committed remotely but its real response/ACK has not reached the outbox.
                assertEquals(b1Ref.lastSyncedLocalRevision,
                    fixture.db.productRemoteRefDao().getByProductId(b.id)?.lastSyncedLocalRevision)
                assertEquals("committed_ack_held", oracle.remote.state().calls.last().outcome)
                ui.dismissReview(); ui.cancelUnderlyingEditor()
                assertEquals(b.id, ui.saveNameThroughEditor("Saved B2", NativeResolutionContract.BARCODE_B, "residual-b2-draft"))
                val b2Ref = requireNotNull(fixture.db.productRemoteRefDao().getByProductId(b.id))
                assertTrue(b2Ref.localChangeRevision > b1Ref.localChangeRevision)
                assertEquals(b1Ref.lastSyncedLocalRevision, b2Ref.lastSyncedLocalRevision)
                release.complete(Unit)
                oracle.assertResidualB2(before, b1Ref.localChangeRevision, b2Ref.localChangeRevision)
                oracle.publish(before, buildJsonObject {
                    put("outcome", "residual_b1_ack_preserved_newer_b2_then_own_ack")
                    put("b1Revision", b1Ref.localChangeRevision); put("b2Revision", b2Ref.localChangeRevision)
                })
            } finally { release.complete(Unit); oracle.remote.afterConditionalCommit = null }
        }
    }

    @Test fun verifyVariantInNewProcess(): Unit = runBlocking {
        withTimeout(180_000) {
            val scenario = requireNotNull(InstrumentationRegistry.getArguments().getString("nativeResolutionScenario"))
            owned(scenario, fresh = false) { fixture, oracle ->
                if (scenario in setOf("r4-unknown-23503", "r4-unknown-23505")) {
                    val marker = oracle.base.verifyReopen("ready-for-force-stop.json")
                    assertEquals("unknown", marker.stage)
                    oracle.base.startRuntime(); ui.mount(fixture)
                    val sqlState = if (scenario == "r4-unknown-23505") "23505" else "23503"
                    oracle.remote.setMode(if (sqlState == "23505") NativeResolutionTransportMode.RejectUniqueWithoutCommit
                        else NativeResolutionTransportMode.RejectWithoutCommit)
                    ui.saveItemNumberThroughEditor("Peer item", "B after unknown")
                    oracle.base.assertUnknownReplayRemainsPending(marker, replayOutcome = "rejected_no_commit")
                    ui.openReviewFromOptions()
                    assertFalse(requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value?.context).canResolve)
                    assertEquals("resolution_outcome_unconfirmed", fixture.databaseVM.persistedCatalogConflictReview.value?.context?.blockingReason)
                    val rejectedReplays = oracle.remote.state().calls.drop(marker.state.calls.size)
                    assertTrue(rejectedReplays.isNotEmpty())
                    assertTrue(rejectedReplays.all { it.operationKey == marker.conditionalKey && it.rejectionSqlState == sqlState })
                    ui.captureUi("unknown-$sqlState-remains-pending")
                } else {
                    oracle.verifyBeforeRuntime() // Read-only new-process proof; no callback may repair missing data.
                }
            }
        }
    }

    private suspend fun saveA(fixture: ScopeSwitchFixture, oracle: NativeResolutionVariantOracle) {
        oracle.base.rejectSaveA()
        assertEquals(fixture.product().id, ui.saveNameThroughEditor("Saved A"))
        oracle.waitForRejectedSave()
        assertEquals("Saved A", fixture.product().productName)
    }

    private suspend fun prepare(scenario: String, block: suspend (ScopeSwitchFixture, NativeResolutionVariantOracle) -> Unit) {
        withTimeout(180_000) {
            owned(scenario, fresh = true) { fixture, oracle ->
                fixture.seed()
                if (scenario == "r4-tombstone") {
                    // Valid editor values arrive from a peer without inventing/deleting local history.
                    assertEquals(0, fixture.db.productPriceDao().countAll())
                    oracle.remote.peerChange { it.copy(purchasePrice = 3.0, retailPrice = 5.0) }
                }
                if (scenario == "r4-residual-b2") oracle.prepareResidualSeed()
                oracle.base.qualifyCanonicalBase(); oracle.base.startRuntime(); ui.mount(fixture)
                block(fixture, oracle)
                awaitCancellation()
            }
        }
    }

    private suspend fun owned(scenario: String, fresh: Boolean,
        block: suspend (ScopeSwitchFixture, NativeResolutionVariantOracle) -> Unit) {
        check(scenario in NativeResolutionContract.variantMethods)
        ui.installContent()
        val context = nativeResolutionContext(scenario, fresh)
        val fixture = ScopeSwitchFixture(context, nativeResolutionMode = true,
            nativeResolutionSeedPriceHistory = scenario != "r4-tombstone")
        var primary: Throwable? = null
        var cleanupFailure: Throwable? = null
        try { block(fixture, NativeResolutionVariantOracle(fixture)) }
        catch (failure: Throwable) {
            primary = failure
            runCatching { fixture.record("FAIL", buildJsonObject { put("type", failure::class.java.name) }) }
            throw failure
        } finally {
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
            put("boundary", "native UI/repository/Room across owned OS process death with synthetic transport; no authenticated TEST claim")
        }.toString().encodeToByteArray())
    }
}

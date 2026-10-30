package com.example.merchandisecontrolsplitview.ui.navigation

import android.os.Process
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.merchandisecontrolsplitview.data.*
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in selectors in the existing neutral native runner; one fresh owned identity per scenario.
 * Three distinct processes prove no-op decision -> ordinary Save B -> stable second reopen.
 * Two distinct processes prove selected CAS commit/lost ACK -> exact zero-row replay remains unknown.
 * Synthetic scoped transport is explicit. These tests are not authenticated TEST acceptance.
 */
@RunWith(AndroidJUnit4::class)
class Task144PersistedResolutionDeviceTest {
    // Preserve the v1 test scheduler/clock baseline; v2 changes UnconfinedTestDispatcher to StandardTestDispatcher.
    @Suppress("DEPRECATION")
    @get:Rule val compose = createComposeRule()
    private val ui by lazy { NativeResolutionUiDriver(compose) }

    @Test fun prepareAdoptNoOpWithIndependentPrice(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("r4-adopt", fresh = true) { fixture, oracle ->
                fixture.seed(); oracle.qualifyCanonicalBase(); oracle.startRuntime(); ui.mount(fixture)
                oracle.rejectSaveA()
                assertEquals(fixture.product().id, ui.saveNameThroughEditor("Saved A"))
                val snapshot = oracle.afterNormalSaveAAndReject()
                ui.openReviewFromOptions(); ui.awaitDecisionReady()
                ui.captureUi("adopt-ready")
                ui.adoptRemote(); ui.awaitResolved(); ui.captureUi("adopt-resolved")
                oracle.assertAdoptedAndPublishRestartMarker(snapshot)
                // The existing host campaign must observe/validate the marker and OS force-stop
                // the exact owned instrumented process. Returning or timing out is not success.
                awaitCancellation()
            }
        }
    }

    @Test fun verifyAdoptReopenThenNormalSaveB(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("r4-adopt", fresh = false, ordinaryWake = true) { fixture, oracle ->
                val marker = oracle.verifyReopen("ready-for-force-stop.json")
                assertEquals("adopt", marker.stage)
                oracle.startRuntime(); ui.mount(fixture)
                ui.captureUi("adopt-reopened")
                oracle.assertAdoptedStableBeforeNextSave(marker)
                // The oracle quiesces the completed ordinary cycle. Restore the normal
                // foreground lifecycle before exercising the next genuine editor Save.
                fixture.app.catalogAutoSyncCoordinator.onAppForeground()
                oracle.acceptNormalSaveB()
                assertEquals(marker.conflict.productId, ui.saveItemNumberThroughEditor("Original item", "Only B"))
                ui.captureUi("normal-b-saved")
                oracle.assertNormalSaveBMaskAndPublishRestartMarker(marker)
                awaitCancellation()
            }
        }
    }

    @Test fun verifyNormalSaveBAfterSecondReopen(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("r4-adopt", fresh = false, ordinaryWake = true) { fixture, oracle ->
                val marker = oracle.verifyReopen("ready-for-second-force-stop.json")
                assertEquals("normal-b", marker.stage)
                oracle.startRuntime(); ui.mount(fixture)
                ui.captureUi("normal-b-second-reopen")
                oracle.assertFinalStable(marker)
            }
        }
    }

    @Test fun prepareSelectedConditionalCommitWithLostAck(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("r4-selected-unknown", fresh = true) { fixture, oracle ->
                fixture.seed(); oracle.qualifyCanonicalBase(); oracle.startRuntime(); ui.mount(fixture)
                oracle.rejectSaveA()
                assertEquals(fixture.product().id, ui.saveNameThroughEditor("Saved A"))
                val snapshot = oracle.afterNormalSaveAAndReject(selected = true)
                ui.openReviewFromOptions(); ui.awaitDecisionReady()
                ui.selectField("productname"); ui.captureUi("selected-ready")
                oracle.loseConditionalResponse()
                ui.applySelected(); ui.awaitResolved(); ui.captureUi("selected-resolved")
                oracle.assertUnknownAndPublishRestartMarker(snapshot)
                awaitCancellation()
            }
        }
    }

    @Test fun verifySelectedUnknownReopenAndExactZeroReplay(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("r4-selected-unknown", fresh = false) { fixture, oracle ->
                val marker = oracle.verifyReopen("ready-for-force-stop.json")
                assertEquals("unknown", marker.stage)
                oracle.startRuntime(); ui.mount(fixture)
                ui.captureUi("selected-unknown-reopened")
                // A real new Save is the production retry trigger. No direct push/coordinator
                // internals manufacture a retained-work dispatch after process death.
                assertEquals(marker.conflict.productId, ui.saveItemNumberThroughEditor("Peer item", "B after unknown"))
                oracle.assertUnknownReplayRemainsPending(marker)
                ui.openReviewFromOptions()
                assertFalse(requireNotNull(fixture.databaseVM.persistedCatalogConflictReview.value?.context).canResolve)
                assertEquals("resolution_outcome_unconfirmed",
                    fixture.databaseVM.persistedCatalogConflictReview.value?.context?.blockingReason)
                ui.captureUi("selected-unknown-zero-replay")
            }
        }
    }

    private suspend fun owned(scenario: String, fresh: Boolean, ordinaryWake: Boolean = false,
        block: suspend (ScopeSwitchFixture, NativeResolutionOracle) -> Unit) {
        ui.installContent()
        val context = nativeResolutionContext(scenario, fresh)
        val fixture = ScopeSwitchFixture(context, nativeResolutionMode = true, nativeResolutionOrdinaryWake = ordinaryWake)
        var primary: Throwable? = null
        var cleanupFailure: Throwable? = null
        try {
            block(fixture, NativeResolutionOracle(fixture))
        } catch (failure: Throwable) {
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
                cleanup { ui.unmount() }
                cleanup { fixture.close() }
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
        // Only terminal selectors reach this after every assertion and owned cleanup succeeds.
        val verified = File(context.root, "verified.json")
        check(!verified.exists())
        nativeDurableWrite(verified, buildJsonObject {
            put("protocol", NativeResolutionContract.PROTOCOL); put("runId", context.runId)
            put("scenario", scenario); put("verifyPid", Process.myPid()); put("status", "PASS_AFTER_CLEANUP")
            put("acceptanceBoundary", "real native UI and Room across owned process deaths; synthetic transport; not authenticated TEST")
        }.toString().encodeToByteArray())
    }
}

package com.example.merchandisecontrolsplitview.ui.navigation

import android.os.Process
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.merchandisecontrolsplitview.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** R23-T04/T06/T10a owned native process slices. Actual Application observer/scheduler,
 * recovery/Room and editor; synthetic scope/auth/transports. onCreate/login/TEST are NOT covered.
 * Every preparation terminates only by a separately admitted host OS force-stop, never by PASS.
 */
@RunWith(AndroidJUnit4::class)
class Task144AutomaticRecoveryProcessDeviceTest {
    // Preserve the v1 test scheduler/clock baseline; v2 changes UnconfinedTestDispatcher to StandardTestDispatcher.
    @Suppress("DEPRECATION")
    @get:Rule val compose = createComposeRule()
    private val ui by lazy { NativeResolutionUiDriver(compose) }

    @Test fun prepareAutomaticAcceptedPrefix(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("prepareAutomaticAcceptedPrefix", NativeAutomaticHold.BEFORE_PRICES) { _, oracle ->
                oracle.prepareQualifiedRecovery(); oracle.startAutomatic(); oracle.waitForHeldPrefix()
                oracle.publish("ready-for-force-stop.json", oracle.snapshotMarker("accepted-prefix"))
                awaitCancellation()
            }
        }
    }
    @Test fun verifyAutomaticPrefixAfterProcessDeath(): Unit = runBlocking {
        // Real persisted300s interrupted-owner policy + bounded native setup/completion, no accelerated clock.
        withTimeout(420_000) {
            owned("verifyAutomaticPrefixAfterProcessDeath") { _, oracle ->
                val before = oracle.verifyReopen("ready-for-force-stop.json")
                assertEquals("accepted-prefix", before.getValue("stage").jsonPrimitive.content)
                oracle.startAutomatic(); oracle.verifyRequalification(before); oracle.finishAfterRealDue(before)
            }
        }
    }
    @Test fun prepareAbandonedAutomaticClaim(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("prepareAbandonedAutomaticClaim", NativeAutomaticHold.BEFORE_CHECKPOINT_B) { _, oracle ->
                oracle.prepareQualifiedRecovery(); oracle.startAutomatic(); oracle.waitForHeldPrefix()
                oracle.publish("ready-for-force-stop.json", oracle.snapshotMarker("checkpoint-b-read-held"))
                awaitCancellation()
            }
        }
    }
    @Test fun prepareRequalificationDeadlineAfterProcessDeath(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("prepareRequalificationDeadlineAfterProcessDeath") { _, oracle ->
                val before = oracle.verifyReopen("ready-for-force-stop.json")
                assertEquals("checkpoint-b-read-held", before.getValue("stage").jsonPrimitive.content)
                oracle.startAutomatic(); oracle.verifyRequalification(before)
                oracle.publish("ready-for-requalification-force-stop.json", oracle.snapshotMarker("one-durable-deadline", before))
                awaitCancellation()
            }
        }
    }
    @Test fun verifySecondReopenKeepsDeadlineAndRecovers(): Unit = runBlocking {
        withTimeout(420_000) {
            owned("verifySecondReopenKeepsDeadlineAndRecovers") { _, oracle ->
                val waiting = oracle.verifyReopen("ready-for-requalification-force-stop.json")
                assertEquals("one-durable-deadline", waiting.getValue("stage").jsonPrimitive.content)
                val before = waiting.getValue("originalMarker").jsonObject
                assertNotEquals(before.getValue("pid").jsonPrimitive.int, Process.myPid())
                val due = requireNotNull(AutomaticRecoveryRetryMetadata.decode(
                    waiting.getValue("journal").jsonObject.getValue("rawMetadata").jsonPrimitive.content)).nextEligibleAtMs
                assertTrue("A real unexpired deadline must survive the second reopen", System.currentTimeMillis() < due - 5_000)
                oracle.startAutomatic()
                oracle.finishAfterRealDue(before, waiting)
            }
        }
    }
    @Test fun prepareEditorSaveBeforeActivation(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("prepareEditorSaveBeforeActivation", NativeAutomaticHold.BEFORE_PRICES) { f, oracle ->
                oracle.prepareQualifiedRecovery(); oracle.startAutomatic(); oracle.waitForHeldPrefix()
                val remoteBefore = requireNotNull(f.resolutionBackend).state()
                ui.mount(f, allowPendingRecovery = true)
                val id = ui.saveNameAndRetailPriceThroughEditor(NativeAutomaticRecoveryContract.SAVED_NAME,
                    NativeAutomaticRecoveryContract.SAVED_RETAIL.toString())
                assertEquals(f.product().id, id)
                val marker = oracle.verifySavedBeforeKill()
                assertEquals("Recovery must block cloud while allowing the genuine local Save", remoteBefore.calls,
                    requireNotNull(f.resolutionBackend).state().calls)
                ui.captureUi("save-before-activation-confirmed")
                oracle.publish("ready-for-force-stop.json", marker)
                awaitCancellation()
            }
        }
    }
    @Test fun prepareSavedRecoveryAfterFirstReopen(): Unit = runBlocking {
        // Adds a90s host-marker observation window to the real300s policy + bounded completion.
        withTimeout(520_000) {
            owned("prepareSavedRecoveryAfterFirstReopen") { f, oracle ->
                val saved = oracle.verifyReopen("ready-for-force-stop.json")
                assertEquals("saved-before-activation", saved.getValue("stage").jsonPrimitive.content)
                oracle.startAutomatic(); oracle.verifyRequalification(saved); oracle.finishAfterRealDue(saved)
                ui.mount(f); ui.captureUi("automatic-saved-recovery-complete")
                oracle.publish("ready-for-second-force-stop.json", oracle.snapshotMarker("saved-recovery-complete", saved))
                awaitCancellation()
            }
        }
    }
    @Test fun verifySavedRecoveryAfterSecondReopen(): Unit = runBlocking {
        withTimeout(150_000) {
            owned("verifySavedRecoveryAfterSecondReopen") { f, oracle ->
                val completed = oracle.verifyReopen("ready-for-second-force-stop.json")
                assertEquals("saved-recovery-complete", completed.getValue("stage").jsonPrimitive.content)
                val saved = completed.getValue("originalMarker").jsonObject
                assertNotEquals(saved.getValue("pid").jsonPrimitive.int, Process.myPid())
                oracle.verifySecondReopenAutomaticOpportunity(completed, saved)
                ui.mount(f); ui.captureUi("saved-second-reopen")
                oracle.verifySavedMaterial(saved)
            }
        }
    }

    private suspend fun owned(method: String, hold: NativeAutomaticHold = NativeAutomaticHold.NONE,
        block: suspend (ScopeSwitchFixture, NativeAutomaticRecoveryOracle) -> Unit) {
        ui.installContent()
        val context = automaticRecoveryContext(method)
        val probe = NativeAutomaticRecoveryProbe(context, hold)
        val fixture = ScopeSwitchFixture(context, nativeResolutionMode = true,
            nativeResolutionOrdinaryWake = method == "verifySavedRecoveryAfterSecondReopen", automaticRecoveryProbe = probe)
        probe.attach(fixture)
        val oracle = NativeAutomaticRecoveryOracle(fixture, probe)
        var primary: Throwable? = null
        var cleanupFailure: Throwable? = null
        try {
            block(fixture, oracle)
            check(method in setOf("verifyAutomaticPrefixAfterProcessDeath", "verifySecondReopenKeepsDeadlineAndRecovers",
                "verifySavedRecoveryAfterSecondReopen")) { "A preparation may only terminate by the owned OS kill" }
        } catch (failure: Throwable) {
            primary = failure
            runCatching { fixture.record("FAIL", buildJsonObject { put("method", method); put("type", failure::class.java.name) }) }
            throw failure
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        val prior = primary ?: cleanupFailure
                        if (prior == null) cleanupFailure = failure else if (prior !== failure) prior.addSuppressed(failure)
                    }
                }
                cleanup { oracle.cancelBeforeReleasingFailedHold() }
                cleanup { ui.unmount() }
                cleanup { fixture.close() }
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
        val verified = File(context.root, "verified.json"); check(!verified.exists())
        nativeDurableWrite(verified, buildJsonObject {
            put("protocol", NativeAutomaticRecoveryContract.PROTOCOL); put("runId", context.runId); put("scenario", context.scenario)
            put("pid", Process.myPid()); put("selector", method); put("status", "PASS_AFTER_OWNED_CLEANUP")
            put("normalApplicationOnCreate", false); put("normalAuthTEST", false)
            put("boundary", "actual production automatic recovery methods and Room under owned synthetic Application wiring")
        }.toString().encodeToByteArray())
    }
}

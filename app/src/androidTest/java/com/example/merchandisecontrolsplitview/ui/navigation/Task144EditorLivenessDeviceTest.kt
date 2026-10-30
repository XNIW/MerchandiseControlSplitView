package com.example.merchandisecontrolsplitview.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal object EditorLivenessContract {
    const val PROTOCOL = "task144-editor-liveness-v1"
    const val SCENARIO = "editor-ten-minutes"
    const val EDITING_MS = 600_000L
    const val QUIET_MS = 8_000L
    const val FIRST_PEER_MS = 6_000L
    const val EDIT_INTERVAL_MS = 30_000L
    const val READBACK_WATCHDOG_MS = 10_000L
    const val TAG = "T14Editor"
}

/** T14 historical duration + R23-T16/T19 long-editor slice. Actual root/editor/coordinator/
 * Room and qualified event reader, owned synthetic transport. No normal-login/TEST, TalkBack,
 * real-peer timing or critical-section acceptance is implied by this controlled test.
 */
@RunWith(AndroidJUnit4::class)
class Task144EditorLivenessDeviceTest {
    // Preserve the v1 test scheduler/clock baseline; v2 changes UnconfinedTestDispatcher to StandardTestDispatcher.
    @Suppress("DEPRECATION")
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf<ScopeSwitchFixture?>(null)
    private val activityView = AtomicReference<View?>()
    private var step = 0
    private var originalAuthority: Triple<Task126BusinessDataScopeState,
        Task126LocalPresentationStamp, Task126BusinessDataScopeSignalToken>? = null

    @Test fun tenMinutesOfEditingAndQuietGapKeepOrdinaryIndependentPollLive() = runBlocking {
        withTimeout(720_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
            check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
            check(android.os.Build.VERSION.SDK_INT >= 30)
            val args = InstrumentationRegistry.getArguments()
            check(args.getString("class") == javaClass.name + "#tenMinutesOfEditingAndQuietGapKeepOrdinaryIndependentPollLive")
            check(args.getString("editorLivenessProtocol") == EditorLivenessContract.PROTOCOL)
            check(args.getString("editorLivenessDurationMs") == EditorLivenessContract.EDITING_MS.toString())
            val context = ScopeSwitchContext(instrumentation.targetContext,
                requireNotNull(args.getString("editorLivenessRunId")), EditorLivenessContract.SCENARIO,
                fresh = true, protocol = EditorLivenessContract.PROTOCOL)
            val fixture = ScopeSwitchFixture(context, nativeResolutionMode = true, nativeResolutionOrdinaryWake = true)
            var primary: Throwable? = null
            var cleanupFailure: Throwable? = null
            var completed = false
            compose.setContent {
                mounted.value?.let { f ->
                    val auth by f.auth.state.collectAsState()
                    val identity by f.auth.localIdentity.collectAsState()
                    val shops by f.shops.state.collectAsState()
                    val business by f.tracker.businessDataScopeState.collectAsState()
                    val progress by f.tracker.state.collectAsState()
                    activityView.set(LocalView.current)
                    CompositionLocalProvider(LocalContext provides f.app, LocalViewModelStoreOwner provides f.viewModelOwner) {
                        MerchandiseControlTheme {
                            AppNavGraphContent(f.app, f.excelVM, f.databaseVM, true, auth, progress, shops,
                                business, localIdentity = identity,
                                localIdentityCurrent = identity?.let(f.auth::isCurrentLocalIdentity) == true)
                        }
                    }
                }
            }
            try {
                fixture.seed()
                val oracle = NativeResolutionOracle(fixture)
                NativeResolutionVariantOracle(fixture).prepareResidualSeed()
                oracle.qualifyCanonicalBase()
                oracle.startRuntime() // One real foreground entry; no later lifecycle/trigger call.
                fixture.mountViewModels()
                compose.runOnIdle { mounted.value = fixture }
                compose.waitForIdle()
                val remote = requireNotNull(fixture.resolutionBackend)
                val originalA = fixture.product()
                val originalRefA = requireNotNull(fixture.db.productRemoteRefDao().getByProductId(originalA.id))
                val independent = requireNotNull(fixture.db.productDao().findByBarcode(NativeResolutionContract.BARCODE_B))
                val independentID = requireNotNull(fixture.db.productRemoteRefDao().getByProductId(independent.id)).remoteId
                val seedCalls = remote.state().calls
                val generation = requireNotNull(fixture.db.syncRecoveryBaselineDao().get()).generationId
                assertEquals(2, fixture.db.productDao().count())
                assertEquals(2_000L, CatalogAutoSyncCoordinator.FOREGROUND_SYNC_EVENT_INTERVAL_MS)
                val scopeState = fixture.tracker.businessDataScopeState.value
                originalAuthority = Triple(scopeState,
                    requireNotNull(fixture.tracker.captureLocalPresentationStamp(scopeState)),
                    fixture.tracker.captureBusinessDataScopeSignal(NativeAckContract.OWNER, NativeAckContract.SHOP))
                openEditor()
                var draft = editAndCapture("T14 unsaved draft 00")
                val openedAt = SystemClock.elapsedRealtime()
                record(fixture, "editor_open", buildJsonObject {
                    put("editingMs", EditorLivenessContract.EDITING_MS); put("quietMs", EditorLivenessContract.QUIET_MS)
                    put("periodMs", CatalogAutoSyncCoordinator.FOREGROUND_SYNC_EVENT_INTERVAL_MS)
                    put("editorProductId", originalA.id); put("independentProductId", independent.id)
                })
                captureImage(fixture, "opened")
                waitUntilElapsed(openedAt + EditorLivenessContract.FIRST_PEER_MS) { assertDraft(fixture, draft) }
                assertTrue(SystemClock.elapsedRealtime() - openedAt > 4_000)
                independentChange(fixture, independent.id, independentID, draft, "initial")
                var nextEdit = openedAt + EditorLivenessContract.EDIT_INTERVAL_MS
                while (SystemClock.elapsedRealtime() - openedAt < EditorLivenessContract.EDITING_MS) {
                    waitUntilElapsed(minOf(nextEdit, openedAt + EditorLivenessContract.EDITING_MS)) {
                        assertDraft(fixture, draft)
                    }
                    if (SystemClock.elapsedRealtime() - openedAt >= EditorLivenessContract.EDITING_MS) break
                    step += 1
                    draft = editAndCapture("T14 unsaved draft " + step.toString().padStart(2, '0'))
                    recordDraft(fixture, "normal_edit", draft)
                    independentChange(fixture, independent.id, independentID, draft, "edit-$step")
                    nextEdit += EditorLivenessContract.EDIT_INTERVAL_MS
                }
                assertTrue("Full historical editing interval is mandatory", SystemClock.elapsedRealtime() - openedAt >= 600_000)
                assertTrue("Repeated real input, not an idle-only wait", step >= 18)
                val quietAt = SystemClock.elapsedRealtime()
                recordDraft(fixture, "quiet_gap_begin", draft)
                independentChange(fixture, independent.id, independentID, draft, "quiet")
                waitUntilElapsed(quietAt + EditorLivenessContract.QUIET_MS) { assertDraft(fixture, draft) }
                assertTrue(SystemClock.elapsedRealtime() - quietAt >= 8_000)
                assertDraft(fixture, draft)
                assertEquals(originalA, fixture.db.productDao().getById(originalA.id))
                assertEquals(originalRefA, fixture.db.productRemoteRefDao().getByProductId(originalA.id))
                assertEquals(generation, fixture.db.syncRecoveryBaselineDao().get()?.generationId)
                assertEquals(seedCalls, remote.state().calls) // No Save, new outgoing intent or hidden repair.
                assertFalse(fixture.repository.hasCatalogCloudPendingWorkInclusive())
                assertNull(fixture.db.syncRecoveryJournalDao().get())
                assertEquals(2, fixture.db.productDao().count())
                captureImage(fixture, "quiet-complete")
                record(fixture, "ten_minute_oracles_passed", buildJsonObject {
                    put("editorOpenElapsedMs", SystemClock.elapsedRealtime() - openedAt)
                    put("quietElapsedMs", SystemClock.elapsedRealtime() - quietAt); put("normalEdits", step)
                    put("nativeAcceptance", false)
                })
                completed = true
            } catch (failure: Throwable) {
                primary = failure
                runCatching { record(fixture, "FAIL", buildJsonObject { put("type", failure::class.java.name); put("step", step) }) }
                runCatching { captureImage(fixture, "first-failure") }
                throw failure
            } finally {
                withContext(NonCancellable) {
                    suspend fun cleanup(block: suspend () -> Unit) {
                        try { block() } catch (failure: Throwable) {
                            val prior = primary ?: cleanupFailure
                            if (prior == null) cleanupFailure = failure else if (prior !== failure) prior.addSuppressed(failure)
                        }
                    }
                    cleanup { compose.runOnIdle { mounted.value = null }; compose.waitForIdle() }
                    cleanup { fixture.close() }
                }
                if (primary == null) cleanupFailure?.let { throw it }
            }
            check(completed)
            nativeDurableWrite(File(context.root, "verified.json"), buildJsonObject {
                put("protocol", EditorLivenessContract.PROTOCOL); put("runId", context.runId)
                put("status", "PASS_AFTER_OWNED_CLEANUP"); put("nativeAcceptance", false)
                put("boundary", "actual native editor and ordinary coordinator; controlled owned transport; not normal-auth TEST")
            }.toString().encodeToByteArray())
        }
    }

    private fun openEditor() {
        compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement(NativeAckContract.BARCODE)
        val row = hasText(NativeAckContract.BARCODE, substring = true) and hasAnyAncestor(hasTestTag("database-product-list"))
        compose.waitUntil(10_000) { compose.onAllNodes(row).fetchSemanticsNodes().size == 1 }
        compose.onNode(row).assertIsDisplayed().performClick()
        compose.onNodeWithTag("task141.edit.dialog-root").assertIsDisplayed()
    }

    private data class Draft(val text: String, val selection: TextRange, val scroll: List<Float>, val window: View)
    private fun editorScroll() = compose.onAllNodes(hasScrollAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(hasTestTag("task141.edit.dialog-root")), useUnmergedTree = true)
    private fun scrollValues() = editorScroll().fetchSemanticsNodes().mapNotNull {
        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
    }
    private fun focusedDialog(): View = requireNotNull(WindowInspector.getGlobalWindowViews().map { it.rootView }
        .distinct().singleOrNull { it !== activityView.get()?.rootView && it.visibility == View.VISIBLE && it.hasWindowFocus() })
    private fun editAndCapture(value: String): Draft {
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement(value)
        compose.onNodeWithTag("product-editor-name").performSemanticsAction(SemanticsActions.SetSelection) { check(it(3, 9, false)) }
        // Real form scroll action; no ViewModel/state assignment. Preserve this position through polling.
        check(editorScroll().fetchSemanticsNodes().size == 1) { "Expected one real editor vertical scroll container" }
        editorScroll()[0].performSemanticsAction(SemanticsActions.ScrollBy) { check(it(0f, 40f)) }
        compose.waitForIdle()
        val window = focusedDialog()
        check(window.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) { "Actual dialog IME is a prerequisite" }
        val scroll = scrollValues(); check(scroll.any { it > 0f }) { "A non-zero real editor scroll is required" }
        return Draft(value, TextRange(3, 9), scroll, window).also { assertDraft(requireNotNull(mounted.value), it) }
    }
    private fun assertDraft(fixture: ScopeSwitchFixture, draft: Draft) {
        val authority = requireNotNull(originalAuthority)
        check(fixture.tracker.isLocalPresentationStampCurrent(authority.second, authority.first) &&
            fixture.tracker.isCurrentBusinessDataScopeSignal(authority.third)) { "Original owner/scope/generation changed" }
        compose.onNodeWithTag("task141.edit.dialog-root").assertExists()
        compose.onNodeWithTag("product-editor-name").assertIsFocused().assertTextContains(draft.text)
        assertEquals(draft.text, compose.onNodeWithTag("product-editor-name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(draft.selection, compose.onNodeWithTag("product-editor-name").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertEquals(draft.scroll, scrollValues())
        assertSame(draft.window, focusedDialog())
        assertTrue(draft.window.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
        assertNotNull(fixture.databaseVM.productEditorTarget.value)
        assertEquals(NativeAckContract.BARCODE, fixture.databaseVM.productEditorTarget.value?.barcode)
        compose.onNodeWithTag("root-tab-optionsScreen").assertIsNotSelected()
        compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
    }
    private suspend fun waitUntilElapsed(deadline: Long, verify: () -> Unit) {
        while (SystemClock.elapsedRealtime() < deadline) {
            verify(); delay(minOf(1_000L, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
        }
    }
    private suspend fun independentChange(fixture: ScopeSwitchFixture, localID: Long, remoteID: String, draft: Draft, phase: String) {
        val remote = requireNotNull(fixture.resolutionBackend)
        val before = remote.state()
        val watermark = requireNotNull(fixture.db.syncEventWatermarkDao().get(NativeAckContract.OWNER, NativeAckContract.scope.storeId))
        val previousOutcome = fixture.tracker.lastQualifiedOutcome.value
        val nonce = UUID.randomUUID().toString()
        val beginMarker = "BEGIN $nonce"
        val requested = "T14 independent $phase $nonce"
        // This fsync must succeed before the only peer mutation in this window.
        record(fixture, "independent_poll_attempt", buildJsonObject {
            put("phase", phase); put("nonce", nonce); put("localID", localID); put("remoteID", remoteID)
            put("value", requested); put("cursorBefore", watermark.lastSyncEventId)
            put("readerCallOffset", before.reads.size); put("beginMarker", beginMarker)
        })
        var stage = "before_peer_change"
        var begin: Long? = null
        var readbackNS: Long? = null
        var expectedFacts: JsonObject? = null
        var observedFacts: JsonObject? = null
        var readerCalls = JsonArray(emptyList())
        var logReadAttempted = false
        var lastRaw: String? = null
        var lastRawComplete = false
        var lastLogFailure: String? = null
        var passed = false
        var primary: Throwable? = null
        val retainRaw: (String, Boolean) -> Unit = { raw, complete ->
            lastRaw = raw; lastRawComplete = complete
        }
        val readLog: () -> String = {
            logReadAttempted = true // Includes command/descriptor construction failures before a raw callback.
            readOwnLog(retainRaw)
        }
        try {
            Log.i(EditorLivenessContract.TAG, beginMarker)
            begin = SystemClock.elapsedRealtimeNanos()
            remote.peerChange(remoteID) { it.copy(productName = requested) }
            stage = "peer_changed"
            val expected = remote.state().additionalProducts.single { it.id == remoteID }
            val targetCursor = remote.state().events.last().id
            val knownExpected = buildJsonObject {
                put("value", requested); put("remoteVersion", expected.updatedAt); put("cursor", targetCursor)
            }
            expectedFacts = knownExpected
            record(fixture, "independent_poll_expected", buildJsonObject {
                put("phase", phase); put("nonce", nonce); put("beginNS", begin)
                put("expected", knownExpected)
            })
            check(targetCursor > watermark.lastSyncEventId)
            stage = "readback"
            withTimeout(EditorLivenessContract.READBACK_WATCHDOG_MS) {
                while (true) {
                    assertDraft(fixture, draft)
                    val row = fixture.db.productDao().getById(localID)
                    val ref = fixture.db.productRemoteRefDao().getByProductId(localID)
                    val cursor = fixture.db.syncEventWatermarkDao().get(NativeAckContract.OWNER, NativeAckContract.scope.storeId)
                    val outcome = fixture.tracker.lastQualifiedOutcome.value
                    // Retain the exact last read, including a mismatch, without another DAO query.
                    observedFacts = buildJsonObject {
                        put("observedNS", SystemClock.elapsedRealtimeNanos()); put("value", row?.productName)
                        put("remoteVersion", ref?.remoteUpdatedAt); put("cursor", cursor?.lastSyncEventId)
                        put("newOutcome", outcome !== previousOutcome); put("outcomeSource", outcome?.source?.name)
                        put("outcomeCompletedAtMs", outcome?.completedAtMs)
                    }
                    if (row?.productName == requested && ref?.remoteUpdatedAt == expected.updatedAt &&
                        cursor?.lastSyncEventId == targetCursor && outcome !== previousOutcome &&
                        outcome?.source == CatalogSyncFlightOwner.SYNC_EVENTS && outcome.completedAtMs != null) break
                    delay(100)
                }
            }
            readbackNS = SystemClock.elapsedRealtimeNanos()
            assertDraft(fixture, draft)
            val reads = remote.state().reads.drop(before.reads.size)
            readerCalls = JsonArray(reads)
            stage = "reader_calls"
            assertTrue(reads.any { it["method"]?.jsonPrimitive?.content == "eventPage" })
            assertTrue(reads.any { it["method"]?.jsonPrimitive?.content == "rowsByIds" &&
                it["ids"]?.jsonArray?.any { id -> id.jsonPrimitive.content == remoteID } == true })
            stage = "ordinary_log"
            val raw = awaitOrdinaryLog(beginMarker, readLog)
            Log.i(EditorLivenessContract.TAG, "END $nonce")
            val completeRaw = readLog()
            val start = completeRaw.indexOf(beginMarker); val end = completeRaw.indexOf("END $nonce", start)
            check(start >= 0 && end > start) { "Logcat must retain the same owned marker window" }
            val window = completeRaw.substring(start, end)
            check(window.contains("cycle=sync_events_drain outcome=ok reason=foreground_interval "))
            check(raw.contains("targetedProductsFetched=1")) { "The ordinary cycle must fetch the independent target" }
            record(fixture, "independent_ordinary_poll_readback", buildJsonObject {
                put("phase", phase); put("beginNS", begin); put("readbackNS", readbackNS)
                put("cursorBefore", watermark.lastSyncEventId); put("cursorAfter", targetCursor)
                put("remoteID", remoteID); put("remoteVersion", expected.updatedAt); put("value", requested)
                put("readerCalls", JsonArray(reads)); put("reason", "foreground_interval")
                put("logSha256", nativeSha(completeRaw.encodeToByteArray()))
                put("timingBoundary", "controlled peer mutation to local durable readback; not B6 real-peer visible latency")
            })
            stage = "final_draft"
            recordDraft(fixture, "draft-preserved-$phase", draft)
            passed = true
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // Synchronous fsync survives coroutine timeout/cancellation. Evidence errors never
            // replace the original failed oracle; success cannot hide an evidence-write failure.
            var evidenceFailure: Throwable? = null
            fun preserve(block: () -> Unit) {
                try { block() } catch (failure: Throwable) {
                    val prior = primary ?: evidenceFailure
                    if (prior == null) evidenceFailure = failure else if (prior !== failure) prior.addSuppressed(failure)
                }
            }
            if (!passed) {
                preserve { readerCalls = JsonArray(remote.state().reads.drop(before.reads.size)) }
                // Never replace the dump used by a failed log oracle with a later dump.
                // If readback failed before any log attempt, capture once for diagnosis only.
                if (!logReadAttempted) preserve {
                    try { readLog() } catch (failure: Throwable) {
                        lastLogFailure = failure::class.java.name
                        throw failure
                    }
                }
            }
            preserve { lastRaw?.let { nativeDurableWrite(File(fixture.context.root, "poll-$phase.log"), it.encodeToByteArray()) } }
            preserve {
                record(fixture, "independent_poll_terminal", buildJsonObject {
                    put("phase", phase); put("nonce", nonce)
                    put("status", if (!passed) "FAIL" else if (evidenceFailure != null) "FAIL_EVIDENCE" else "PASS")
                    put("stage", stage); put("failureType", primary?.javaClass?.name)
                    put("beginNS", begin); put("readbackNS", readbackNS); put("localID", localID); put("remoteID", remoteID)
                    put("value", requested); put("cursorBefore", watermark.lastSyncEventId)
                    put("expected", expectedFacts ?: JsonNull); put("lastObserved", observedFacts ?: JsonNull)
                    put("readerCalls", readerCalls); put("logReadAttempted", logReadAttempted); put("rawLogAvailable", lastRaw != null)
                    put("rawLogComplete", lastRawComplete); put("failureCaptureLogError", lastLogFailure)
                    put("rawLogSha256", lastRaw?.let { nativeSha(it.encodeToByteArray()) })
                    put("evidenceFailureType", evidenceFailure?.javaClass?.name)
                })
            }
            if (primary == null) evidenceFailure?.let { throw it }
        }
    }
    private suspend fun awaitOrdinaryLog(beginMarker: String, readLog: () -> String): String = withTimeout(2_000) {
        while (true) {
            val raw = readLog(); val start = raw.indexOf(beginMarker)
            val window = if (start >= 0) raw.substring(start) else ""
            if (window.lineSequence().any { it.contains("cycle=sync_events_drain outcome=ok reason=foreground_interval ") &&
                    it.contains("targetedProductsFetched=1") }) return@withTimeout window
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun readOwnLog(retainRaw: (String, Boolean) -> Unit): String {
        val command = "logcat -d -v raw --pid=" + Process.myPid() + " -s T14Editor:I CatalogCloudSync:I"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8_192)
            try {
                while (true) { val n = input.read(buffer); if (n < 0) break; check(bytes.size() + n <= 2_000_000); bytes.write(buffer, 0, n) }
                bytes.toString(Charsets.UTF_8.name()).also { retainRaw(it, true) }
            } catch (failure: Throwable) {
                retainRaw(bytes.toString(Charsets.UTF_8.name()), false)
                throw failure
            }
        }
    }
    private fun record(fixture: ScopeSwitchFixture, kind: String, fields: JsonObject = buildJsonObject { }) =
        fixture.record(kind, buildJsonObject { put("elapsedRealtimeNS", SystemClock.elapsedRealtimeNanos()); put("fields", fields) })
    private fun recordDraft(fixture: ScopeSwitchFixture, phase: String, draft: Draft) = record(fixture, "editor_witness", buildJsonObject {
        put("phase", phase); put("text", draft.text); put("selectionStart", draft.selection.start); put("selectionEnd", draft.selection.end)
        put("scroll", JsonArray(draft.scroll.map(::JsonPrimitive))); put("windowIdentity", System.identityHashCode(draft.window))
        put("dialogFocused", draft.window.hasWindowFocus()); put("imeVisible", draft.window.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
    })
    private fun captureImage(fixture: ScopeSwitchFixture, phase: String) {
        val file = File(fixture.context.root, "ui-$phase.png");check(!file.exists())
        val bytes = ByteArrayOutputStream().use { stream ->
            check(compose.onNodeWithTag("task141.edit.dialog-root").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        nativeDurableWrite(file, bytes)
        record(fixture, "ui_image", buildJsonObject { put("phase", phase); put("sha256", nativeSha(bytes)) })
    }
}

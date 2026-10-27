package com.example.merchandisecontrolsplitview.ui.navigation

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.MobileSyncPerformanceTestRunner
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Real touch input + full-display capture, not accessibility actions or a TalkBack PASS oracle.
 * No OS setting, permission, service, global action, auth or lifecycle mutation is provided.
 * The current campaign controller remains the sole launcher/reaper/archiver.
 */
internal class Task144TalkBackCheckpoint private constructor(
    private val context: Context,
    private val directory: File,
    private val runId: String,
    private val selector: String,
    private val settingsReceipt: String
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var ui: UiAutomation
    private var originalUiFlags: Int? = null
    private var closed = false
    private val imePackage = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/')?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
    override fun close() {
        if (!closed) {
            originalUiFlags?.let { flags ->
                val info = ui.serviceInfo; info.flags = flags; ui.serviceInfo = info
            }
            closed = true
        }
    }
    private fun ownedWindowPresent() = ui.windows.any { it.root?.packageName?.toString() == PACKAGE }
    private fun allowedPackage(value: String?) = value == PACKAGE || (value != null && value == imePackage)
    private val nonce = UUID.randomUUID().toString()
    private data class Command(val sequence: Int, val operation: String, val expectedFocusSha: String)
    private val queue = ArrayBlockingQueue<Command>(1)
    private var lastFocusSha = ""
    private val manager = checkNotNull(context.getSystemService(AccessibilityManager::class.java))
    private var nextSequence = 1

    private fun serviceIds(): List<String> {
        check(manager.isEnabled && manager.isTouchExplorationEnabled) { "TalkBack touch exploration is not active" }
        val ids = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .map { it.id }.sorted()
        check(ids.any { it.startsWith("com.google.android.marvin.talkback/") }) { "Expected installed TalkBack is not enabled" }
        return ids
    }

    private fun durable(name: String, value: JsonObject) {
        val file = File(directory, name); check(file.createNewFile())
        FileOutputStream(file).use { stream -> stream.write((value.toString() + "\n").toByteArray()); stream.fd.sync() }
    }

    private fun hashBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String = hashBytes(file.readBytes())

    private fun focus(strict: Boolean = true): JsonElement {
        if (!ownedWindowPresent()) {
            check(!strict) { "Owned fixture window is absent" }
            return buildJsonObject { put("observation_error", "owned_window_absent") }
        }
        val nodes = ui.windows.mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }
        if (nodes.size > 1) {
            check(!strict) { "Ambiguous accessibility focus across windows" }
            return buildJsonObject { put("observation_error", "ambiguous_focus") }
        }
        val node = nodes.singleOrNull() ?: return JsonNull
        if (!allowedPackage(node.packageName?.toString())) {
            check(!strict) { "Focus left the owned fixture/its current keyboard" }
            return buildJsonObject { put("observation_error", "foreign_focus"); put("window_id", node.windowId) }
        }
        val rect = Rect(); node.getBoundsInScreen(rect)
        // Preserve the API-35 Boolean checked witness; getChecked() is API 36 and returns a tri-state int.
        @Suppress("DEPRECATION") val checked = node.isChecked
        return buildJsonObject {
            put("package", node.packageName.toString()); put("window_id", node.windowId)
            put("text", node.text?.toString()?.take(512)?.let(::JsonPrimitive) ?: JsonNull)
            put("content_description", node.contentDescription?.toString()?.take(512)?.let(::JsonPrimitive) ?: JsonNull)
            put("class", node.className?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("accessibility_focused", node.isAccessibilityFocused); put("input_focused", node.isFocused)
            put("enabled", node.isEnabled); put("selected", node.isSelected)
            put("checkable", node.isCheckable); put("checked", checked)
            put("editable", node.isEditable); put("visible", node.isVisibleToUser)
            put("bounds", JsonArray(listOf(rect.left, rect.top, rect.right, rect.bottom).map(::JsonPrimitive)))
            put("meaning", "focus observation only; node text is not spoken-output evidence")
        }
    }

    private fun activeWindowIdentity(strict: Boolean = true): JsonObject {
        if (!ownedWindowPresent()) {
            check(!strict) { "Owned fixture window is absent" }
            return buildJsonObject { put("observation_error", "owned_window_absent") }
        }
        val root = ui.rootInActiveWindow
        if (root == null || !allowedPackage(root.packageName?.toString())) {
            check(!strict) { "Active capture window is outside fixture/IME" }
            return buildJsonObject { put("observation_error", "active_window_changed") }
        }
        val bounds = Rect(); root.getBoundsInScreen(bounds)
        return buildJsonObject {
            put("window_id", root.windowId); put("package", root.packageName.toString())
            put("bounds", JsonArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map(::JsonPrimitive)))
        }
    }

    private fun capture(sequence: Int, operation: String, before: JsonElement, injected: Int, start: Long) {
        val services = serviceIds()
        val windowBeforeCapture = activeWindowIdentity()
        val focusBeforeCapture = focus()
        val captureStart = SystemClock.elapsedRealtimeNanos()
        val shot = checkNotNull(ui.takeScreenshot()) { "Full-display screenshot unavailable" }
        val captureEnd = SystemClock.elapsedRealtimeNanos()
        // Observation failure after acquisition is evidence of an unstable capture, not permission to discard the PNG.
        val focusAfterCapture = runCatching { focus(strict = false) }.getOrElse {
            buildJsonObject { put("observation_error", it.javaClass.simpleName) }
        }
        val windowAfterCapture = runCatching { activeWindowIdentity(strict = false) }.getOrElse {
            buildJsonObject { put("observation_error", it.javaClass.simpleName) }
        }
        // Both observations bracket the screen acquisition, before compression or filesystem work.
        val actionable = focusBeforeCapture == focusAfterCapture && windowBeforeCapture == windowAfterCapture
        val name = "step-%03d.png".format(sequence)
        val file = File(directory, name); check(file.createNewFile())
        try { FileOutputStream(file).use { stream ->
            check(shot.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync()
        } } finally { shot.recycle() }
        lastFocusSha = hashBytes(focusAfterCapture.toString().toByteArray())
        durable("step-%03d.json".format(sequence), buildJsonObject {
            put("protocol", PROTOCOL); put("run_id", runId); put("nonce", nonce); put("selector", selector)
            put("sequence", sequence); put("operation", operation)
            put("status", if (actionable) "OBSERVED_NOT_QUALIFIED" else "UNSTABLE_SCREENSHOT_FOCUS")
            put("actionable", actionable)
            put("started_ns", start); put("ended_ns", SystemClock.elapsedRealtimeNanos())
            put("capture_started_ns", captureStart); put("capture_ended_ns", captureEnd)
            put("injected_touch_events", injected); put("focus_before", before)
            put("capture_focus_before", focusBeforeCapture); put("focus_after", focusAfterCapture)
            put("capture_window_before", windowBeforeCapture); put("capture_window_after", windowAfterCapture)
            put("focus_after_sha256", lastFocusSha)
            put("enabled_spoken_services", JsonArray(services.map(::JsonPrimitive)))
            put("touch_exploration_enabled", manager.isTouchExplorationEnabled)
            put("full_display_png", name); put("full_display_png_sha256", hash(file))
            put("speech", "NOT_QUALIFIED_REQUIRES_OBSERVED_TALKBACK_OVERLAY_OR_AUDIO")
        })
        check(actionable) { "Focus/window changed during screenshot acquisition; evidence preserved, no further input admitted" }
        if (operation != "finish") stepMarker(sequence)
    }

    private fun stepMarker(sequence: Int) {
        println("T18_TALKBACK_STEP ${buildJsonObject {
            put("protocol", PROTOCOL); put("run_id", runId); put("nonce", nonce)
            put("sequence", sequence); put("root", directory.absolutePath)
        }}")
    }

    private fun touch(operation: String): Int {
        val root = checkNotNull(ui.rootInActiveWindow)
        check(ownedWindowPresent() && allowedPackage(root.packageName?.toString())) {
            "Active window is outside the owned fixture/its current keyboard"
        }
        val bounds = Rect(); root.getBoundsInScreen(bounds)
        check(bounds.width() > 200 && bounds.height() > 200)
        var down = SystemClock.uptimeMillis(); var injected = 0
        var activeTouch = false
        var lastPoints = emptyList<Pair<Float, Float>>()
        fun send(action: Int, points: List<Pair<Float, Float>>) {
            val props = points.indices.map { i -> MotionEvent.PointerProperties().apply {
                id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coords = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
                x = px; y = py; pressure = 1f; size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size,
                props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) activeTouch = true
                lastPoints = points
                check(ui.injectInputEvent(event, true)) { "Touch injection rejected" }; injected++
                if ((action and MotionEvent.ACTION_MASK) in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) activeTouch = false
            }
            finally { event.recycle() }
        }
        fun point(x: Float, y: Float) = (bounds.left + bounds.width() * x) to (bounds.top + bounds.height() * y)
        var failure: Throwable? = null
        try {
            if (operation == "activate") {
                check(focus() != JsonNull) { "No real TalkBack focus to activate" }
                repeat(2) {
                    down = SystemClock.uptimeMillis()
                    send(MotionEvent.ACTION_DOWN, listOf(point(.5f, .5f))); SystemClock.sleep(40)
                    send(MotionEvent.ACTION_UP, listOf(point(.5f, .5f)))
                    if (it == 0) SystemClock.sleep(80)
                }
            } else {
                val scroll = operation.startsWith("scroll_")
                fun points(f: Float): List<Pair<Float, Float>> {
                    if (scroll) {
                        val y = if (operation == "scroll_forward") .75f - .5f * f else .25f + .5f * f
                        return listOf(point(.4f, y), point(.6f, y))
                    }
                    val x = if (operation == "next") .3f + .4f * f else .7f - .4f * f
                    return listOf(point(x, .5f))
                }
                send(MotionEvent.ACTION_DOWN, points(0f).take(1))
                if (scroll) send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points(0f))
                repeat(8) { i -> SystemClock.sleep(if (scroll) 40 else 12); send(MotionEvent.ACTION_MOVE, points((i + 1) / 8f)) }
                if (scroll) send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points(1f))
                send(MotionEvent.ACTION_UP, points(1f).take(1))
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            if (activeTouch && lastPoints.isNotEmpty()) try { send(MotionEvent.ACTION_CANCEL, lastPoints) }
            catch (cleanup: Throwable) { val primary = failure; if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
        }
        return injected
    }

    /** Blocks the instrumentation test thread, never the UI thread, for at most180s. */
    fun hold(phase: String, route: String) {
        check(phase == "restored" && !closed)
        // Compose captureToImage can acquire default UiAutomation and suppress services.
        // Reconnect explicitly after all Compose setup/capture, then observe real TalkBack only here.
        ui = checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        val info = ui.serviceInfo; originalUiFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; ui.serviceInfo = info
        val serviceDeadline = SystemClock.elapsedRealtime() + 5_000L
        while (!(manager.isEnabled && manager.isTouchExplorationEnabled)) {
            check(SystemClock.elapsedRealtime() < serviceDeadline) { "TalkBack did not become active after observer connection" }
            SystemClock.sleep(100)
        }
        serviceIds()
        check(directory.mkdirs())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ACTION || intent.getStringExtra("protocol") != PROTOCOL ||
                    intent.getStringExtra("run_id") != runId || intent.getStringExtra("nonce") != nonce) return
                val sequence = intent.getIntExtra("sequence", -1)
                val operation = intent.getStringExtra("operation") ?: return
                val expectedFocus = intent.getStringExtra("expected_focus_sha256") ?: return
                if (sequence !in 1..MAX_STEPS || operation !in OPERATIONS || !expectedFocus.matches(Regex("[0-9a-f]{64}"))) return
                queue.offer(Command(sequence, operation, expectedFocus)) // Bounded; host must await the exact durable response.
            }
        }
        // The broadcaster must already possess DUMP (the normal shell does). No permission is granted.
        context.registerReceiver(receiver, IntentFilter(ACTION), Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        try {
            val ready = buildJsonObject {
                put("protocol", PROTOCOL); put("run_id", runId); put("nonce", nonce); put("selector", selector)
                put("phase", phase); put("route", route); put("pid", android.os.Process.myPid())
                put("root", directory.absolutePath); put("timeout_ms", TIMEOUT_MS); put("max_steps", MAX_STEPS)
                put("settings_receipt_sha256", settingsReceipt); put("status", "WAITING_FOR_REAL_INPUT")
            }
            durable("ready.json", ready); capture(0, "capture", JsonNull, 0, SystemClock.elapsedRealtimeNanos())
            println("T18_TALKBACK_CHECKPOINT $ready")
            val end = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (true) {
                val remaining = end - SystemClock.elapsedRealtime(); check(remaining > 0) { "TalkBack checkpoint timed out" }
                val command = queue.poll(minOf(remaining, 500L), TimeUnit.MILLISECONDS) ?: continue
                check(command.sequence == nextSequence && command.expectedFocusSha == lastFocusSha) { "TalkBack command replay or sequence gap" }
                serviceIds(); val before = focus(); val started = SystemClock.elapsedRealtimeNanos()
                val count = if (command.operation in setOf("capture", "finish")) 0 else {
                    check(hashBytes(before.toString().toByteArray()) == command.expectedFocusSha) {
                        "Accessibility focus changed since the reviewed screenshot; capture again before input"
                    }
                    touch(command.operation)
                }
                SystemClock.sleep(500)
                capture(nextSequence, command.operation, before, count, started); nextSequence++
                if (command.operation == "finish") {
                    durable("finished.json", buildJsonObject {
                        put("protocol", PROTOCOL); put("run_id", runId); put("nonce", nonce)
                        put("status", "CAPTURE_COMPLETE_NOT_ACCESSIBILITY_ACCEPTANCE"); put("last_sequence", nextSequence - 1)
                    }); stepMarker(nextSequence - 1); return
                }
            }
        } finally { context.unregisterReceiver(receiver) }
    }

    companion object {
        const val PROTOCOL = "task144-real-talkback-checkpoint-v1"
        const val PACKAGE = "com.example.merchandisecontrolsplitview"
        const val ACTION = "$PACKAGE.TASK144_TALKBACK_CHECKPOINT"
        const val TIMEOUT_MS = 180_000L
        const val MAX_STEPS = 60
        val OPERATIONS = setOf("capture", "next", "previous", "activate", "scroll_forward", "scroll_backward", "finish")
        fun fromArguments(context: Context, runRoot: File): Task144TalkBackCheckpoint? {
            val args = InstrumentationRegistry.getArguments()
            val keys = args.keySet().filter { it.startsWith("task144TalkBack") }.toSet()
            if (keys.isEmpty()) return null
            check(keys == setOf("task144TalkBackProtocol", "task144TalkBackRunId", "task144TalkBackSettingsReceiptSha256"))
            check(args.getString("task144TalkBackProtocol") == PROTOCOL)
            check(Build.VERSION.SDK_INT == 35 && context.applicationContext.javaClass == Application::class.java)
            check(InstrumentationRegistry.getInstrumentation().javaClass == MobileSyncPerformanceTestRunner::class.java)
            check(args.getString("task144A11yProtocol") == Task144AccessibilityConfiguration.PROTOCOL)
            val selector = checkNotNull(args.getString("class"))
            check(selector.substringBefore('#') == "$PACKAGE.ui.navigation.LocalAvailabilityRootDeviceTest")
            check(selector.substringAfter('#') in setOf("filePickerContextSurvivesSameScopeRequalification",
                "databaseContextSurvivesSameScopeRequalification", "historyContextSurvivesSameScopeRequalification",
                "preGenerateContextSurvivesSameScopeRequalification", "generatedContextSurvivesSameScopeRequalification",
                "importAnalysisContextSurvivesSameScopeRequalification", "databaseEditorWindowSuspendsAndRestoresDraftFocusAndSelection"))
            val run = checkNotNull(args.getString("task144TalkBackRunId"))
            check(run.matches(Regex("[a-z0-9][a-z0-9-]{7,79}")) && run == args.getString("task144A11yRunId"))
            val receipt = checkNotNull(args.getString("task144TalkBackSettingsReceiptSha256"))
            check(receipt.matches(Regex("[0-9a-f]{64}")))
            return Task144TalkBackCheckpoint(context, File(runRoot, "talkback-$run"), run, selector, receipt)
        }
    }
}

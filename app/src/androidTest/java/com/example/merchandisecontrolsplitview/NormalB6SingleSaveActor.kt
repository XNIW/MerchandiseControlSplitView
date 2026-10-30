package com.example.merchandisecontrolsplitview

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.JsonReader
import android.util.JsonToken
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.merchandisecontrolsplitview.data.CatalogProductSnapshot
import com.example.merchandisecontrolsplitview.data.CatalogTextCanonicalizer
import com.example.merchandisecontrolsplitview.data.PilotCommitTrace
import com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks
import com.example.merchandisecontrolsplitview.util.formatClPriceInput
import com.example.merchandisecontrolsplitview.util.formatClQuantityInput
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Opt-in G02-03: ordinary UI preparation followed by exactly one permit-gated real Save. */
internal class NormalB6SingleSaveActor(
    private val instrumentation: Instrumentation,
    private val binding: PilotCommitTrace.B6Binding,
    configRaw: String,
    private val directory: File,
    private val current: () -> Boolean
) {
    companion object {
        const val SCHEMA = "b6-source-save-permit-v1"
        private const val PACKAGE = "com.example.merchandisecontrolsplitview"
        private const val CAMPAIGN = "mobile-sync-execution-20261016-resume"
        private val IDENTITY_KEYS = setOf("clock_id", "participant_pid", "participant_boot_sha256",
            "artifact_sha256", "device_sha256", "ordinary_preflight_sha256")
        private val OWNER_KEYS = listOf("ownerID", "ownerPID", "hostBootIdentityHash", "clockStartedNS",
            "operationID", "manifestSHA256", "timing_method")
        internal fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        private fun strict(raw: String): JsonObject {
            val reader = JsonReader(StringReader(raw)); reader.isLenient = false
            fun value(depth: Int): JsonElement {
                check(depth <= 24) { "SAVE_JSON_DEPTH" }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val fields = linkedMapOf<String, JsonElement>()
                        while (reader.hasNext()) {
                            val key = reader.nextName(); check(!fields.containsKey(key)) { "SAVE_JSON_DUPLICATE" }
                            fields[key] = value(depth + 1)
                        }
                        reader.endObject(); JsonObject(fields)
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray(); val items = mutableListOf<JsonElement>()
                        while (reader.hasNext()) { check(items.size < 128); items.add(value(depth + 1)) }
                        reader.endArray(); JsonArray(items)
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> Json.parseToJsonElement(reader.nextString()).also {
                        check(it is JsonPrimitive && it.doubleOrNull?.isFinite() == true) { "SAVE_JSON_NUMBER" }
                    }
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull }
                    else -> error("SAVE_JSON_TOKEN")
                }
            }
            val result = reader.use { value(0).also { check(reader.peek() == JsonToken.END_DOCUMENT) } }
            return result as? JsonObject ?: error("SAVE_JSON_OBJECT")
        }
        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
            check(it.isString); it.content
        }
        private fun JsonObject.integer(key: String): Long = getValue(key).jsonPrimitive.let {
            check(!it.isString && it.content.matches(Regex("0|[1-9][0-9]*"))); requireNotNull(it.longOrNull)
        }
        private fun bytes(raw: String) = raw.toByteArray(Charsets.UTF_8)
        private fun decode(raw: ByteArray) = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()
        private fun read(file: File, maximum: Int): ByteArray {
            return B6PublishedFile.requireRead(file,maximum).also { check(it.isNotEmpty()) { "SAVE_FILE_SHAPE" } }
        }
        /** O_EXCL is the consume point. A partial file remains consumed even when write/fsync fails. */
        internal fun exclusive(file: File, raw: ByteArray) {
            check(raw.size in 1..65536 && file.parentFile?.canonicalPath == file.parentFile?.absolutePath)
            val descriptor = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
            FileOutputStream(descriptor).use { it.write(raw); it.flush(); it.fd.sync() }
            val parent = Os.open(requireNotNull(file.parent), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(parent).st_mode)) { "SAVE_PARENT_NOT_DIRECTORY" }
                Os.fsync(parent)
            } finally { Os.close(parent) }
        }
    }

    private val config = strict(configRaw)
    private val consumed = AtomicBoolean(false)
    private val permit = File(directory, "source-save-permit.json")
    private val latch = File(directory, "source-save-consumed.json")
    private val result = File(directory, "source-save-result.json")
    private val expected: CatalogProductSnapshot
    @Volatile private var editorOpens = 0
    @Volatile private var editorDraws = 0
    @Volatile private var setupInvalid = false
    @Volatile private var setupFrozen = false
    @Volatile private var activity: Activity? = null
    @Volatile private var editorWindow: Int? = null
    @Volatile private var saves = 0
    private var previousWindowFlags: Int? = null
    val setupObserver: (MobileSyncInteractionTestHooks.Event, Long, String?, Long) -> Unit = { event, id, _, _ ->
        observe(event, id)
    }

    init {
        check(bytes(configRaw).size <= 32768 && canonical(config).toString() == configRaw) { "SAVE_CONFIG_WIRE" }
        check(config.keys == setOf("schema", "campaign", "sample_id", "operation_id", "manifest_sha256",
            "owner_ready_sha256", "owner_binding_sha256", "expected_accept_control_sequence", "expected_local_body_raw"))
        check(binding.role == "source" && binding.category == "create" && binding.sampleID == "T20-G02-03" &&
            binding.localProductID == null && binding.timingMethod == PilotCommitTrace.B6_CAUSAL_METHOD)
        check(config.text("schema") == "b6-normal-single-save-config-v1" && config.text("campaign") == CAMPAIGN &&
            config.text("sample_id") == binding.sampleID && config.text("operation_id") == binding.operationID &&
            config.text("manifest_sha256") == binding.manifestHash && config.integer("expected_accept_control_sequence") == 9L)
        listOf("owner_ready_sha256", "owner_binding_sha256").forEach { check(config.text(it).matches(Regex("[0-9a-f]{64}"))) }
        val raw = config.text("expected_local_body_raw"); check(bytes(raw).size <= 16384 && sha(bytes(raw)) == binding.expectedLocalBodyHash)
        val body = strict(raw); check(!body.containsKey("id"))
        expected = Json.decodeFromJsonElement(CatalogProductSnapshot.serializer(), JsonObject(linkedMapOf("id" to JsonPrimitive(0L)) + body))
        val roundTrip = JsonObject(Json.encodeToJsonElement(CatalogProductSnapshot.serializer(), expected).jsonObject.filterKeys { it != "id" }).toString()
        check(roundTrip == raw && expected.barcode == binding.expectedBarcode && expected.barcode.isNotBlank()) { "SAVE_FULL_BODY_PIN" }
        check(expected.supplierId == null && expected.categoryId == null && expected.oldPurchasePrice == null &&
            expected.oldRetailPrice == null && expected.primaryImageVersionId == null && expected.primaryImageUpdatedAt == null) { "SAVE_CREATE_DEFAULTS" }
        check(CatalogProductSnapshot.from(CatalogTextCanonicalizer.product(expected.product()).product) == expected &&
            parseUserPriceInput(formatClPriceInput(expected.purchasePrice)) == expected.purchasePrice &&
            parseUserPriceInput(formatClPriceInput(expected.retailPrice)) == expected.retailPrice &&
            parseUserQuantityInput(formatClQuantityInput(expected.stockQuantity)) == expected.stockQuantity) { "SAVE_DRAFT_NOT_LOSSLESS" }
    }

    @Synchronized fun observe(event: MobileSyncInteractionTestHooks.Event, id: Long) {
        when (event) {
            MobileSyncInteractionTestHooks.Event.EDITOR_OPEN -> { editorOpens++; if (id != 0L || setupFrozen) setupInvalid = true }
            MobileSyncInteractionTestHooks.Event.EDITOR_DRAW -> {
                editorDraws++; if (id != 0L || editorOpens != 1) setupInvalid = true
            }
            MobileSyncInteractionTestHooks.Event.SAVE_CLICK -> { saves++; if (!consumed.get()) setupInvalid = true }
            else -> Unit
        }
    }
    fun beginSetup() {
        val ui = instrumentation.uiAutomation; val info = ui.serviceInfo
        previousWindowFlags = info.flags
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = info
        // A visible preexisting editor is not a CUA-owned new benchmark draft.
        val visible = ui.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == PACKAGE }
            .flatMap { nodes(it) }.filter { it.isVisibleToUser }
        val title = if (visible.isEmpty()) null else preparationLabel(R.string.edit_product_title)
        check(visible.none { it.text?.toString() == title }) { "SAVE_PREEXISTING_EDITOR" }
    }
    fun restoreWindowObservation() {
        previousWindowFlags?.let { flags ->
            val ui = instrumentation.uiAutomation; val info = ui.serviceInfo; info.flags = flags; ui.serviceInfo = info
            previousWindowFlags = null
        }
    }
    fun ready(): org.json.JSONObject = org.json.JSONObject().put("source_save_adapter", SCHEMA)
        .put("instrumentation_uid", Process.myUid()).put("source_save_permit_path", permit.path)
        .put("source_save_latch_path", latch.path).put("source_save_result_path", result.path)
        .put("native_ui_proof_path", File(directory,"native-ui-proof.json").path)
        .put("native_ui_png_path", File(directory,"native-ui.png").path)
        .put("native_ui_ax_path", File(directory,"native-ui.ax.txt").path)

    /** Executes only ordinary accessibility actions; the original full qualifier remains the oracle. */
    fun prepareNativeUI(deadlineNS: Long) {
        val begin = SystemClock.elapsedRealtimeNanos()
        fun guard() { check(SystemClock.elapsedRealtimeNanos() < deadlineNS && current() &&
            !setupFrozen && !setupInvalid && saves == 0 && !consumed.get()) { "SAVE_UI_PREP_GUARD" } }
        fun roots(): AccessibilityNodeInfo {
            guard()
            return requireNotNull(instrumentation.uiAutomation.rootInActiveWindow).also {
                check(it.packageName?.toString() == PACKAGE) { "SAVE_UI_PREP_PACKAGE" }
            }
        }
        fun clickLabel(resource: Int) {
            val root=roots(); val label=preparationLabel(resource)
            val candidates=nodes(root).filter { it.isVisibleToUser &&
                (it.text?.toString()==label || it.contentDescription?.toString()==label) }.mapNotNull { leaf ->
                var node: AccessibilityNodeInfo?=leaf; var depth=0
                while(node!=null && depth++<24 && !(node.isVisibleToUser && node.isClickable && node.isEnabled)) node=node.parent
                node
            }.distinct()
            val target=candidates.single(); val bounds=rect(target)
            val window=instrumentation.uiAutomation.windows.single { it.id==root.windowId && it.isActive && it.isFocused && it.type==AccessibilityWindowInfo.TYPE_APPLICATION }
            check(target.windowId==window.id && !bounds.isEmpty && rect(root).contains(bounds) &&
                instrumentation.uiAutomation.windows.none { other -> other.id!=window.id &&
                    (other.layer>window.layer || other.isActive || other.isFocused) && Rect.intersects(bounds,Rect().also(other::getBoundsInScreen)) }) { "SAVE_UI_PREP_OVERLAY" }
            guard(); check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "SAVE_UI_PREP_CLICK" }
        }
        fun waitForLabel(resource: Int) {
            val label=preparationLabel(resource)
            while(true) {
                guard()
                if(nodes(roots()).any { it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label) }) return
                Thread.sleep(20)
            }
        }
        fun set(resource: Int, value: String) {
            val title=preparationLabel(resource)
            fun find()=nodes(roots()).filter { it.className?.toString()=="android.widget.EditText" &&
                (it.hintText?.toString()==title || fieldLabels(it).any { label -> label.text?.toString()==title }) }.single()
            var node=find(); check(node.isEditable && node.isEnabled && node.error==null)
            if(!node.isVisibleToUser) {
                check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)); node=find()
                while(!node.isVisibleToUser) { guard(); Thread.sleep(20); node=find() }
            }
            check(node.isVisibleToUser && node.packageName?.toString()==PACKAGE)
            if(node.text?.toString().orEmpty()!=value) {
                guard(); check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
                })) { "SAVE_UI_PREP_SET_TEXT" }
            }
            while(find().text?.toString().orEmpty()!=value) { guard(); Thread.sleep(20) }
        }
        guard()
        var active: List<Activity> = emptyList()
        instrumentation.runOnMainSync { active=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList() }
        if(active.isEmpty()) {
            // Explicit ordinary launch only when the instrumentation has no foreground Activity.
            instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        activity=resumed()
        val editorTitle=preparationLabel(R.string.edit_product_title)
        check(editorOpens==0 && nodes(roots()).none { it.isVisibleToUser &&
            it.text?.toString()==editorTitle }) { "SAVE_UI_PREEXISTING_DRAFT" }
        clickLabel(R.string.database); waitForLabel(R.string.add_product)
        check(editorOpens==0); clickLabel(R.string.add_product); waitForLabel(R.string.edit_product_title)
        set(R.string.barcode_label,expected.barcode)
        set(R.string.product_name_label,expected.productName.orEmpty())
        set(R.string.second_product_name_label,expected.secondProductName.orEmpty())
        set(R.string.item_code_label,expected.itemNumber.orEmpty())
        set(R.string.purchase_price_label,formatClPriceInput(expected.purchasePrice))
        set(R.string.retail_price_label,formatClPriceInput(expected.retailPrice))
        set(R.string.header_stock_quantity,formatClQuantityInput(expected.stockQuantity))
        val first=org.json.JSONObject(); qualifiedSave(first)
        val png=ByteArrayOutputStream().also { bytes ->
            val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { check(image.compress(Bitmap.CompressFormat.PNG,100,bytes)) } finally { image.recycle() }
        }.toByteArray()
        val ax=nodes(roots()).joinToString("\n") { it.toString() }.toByteArray(Charsets.UTF_8)
        val second=org.json.JSONObject(); qualifiedSave(second)
        guard(); check(first.toString()==second.toString() && png.size in 1..524288 && ax.size in 1..524288 &&
            editorOpens==1 && editorDraws>0 && saves==0) { "SAVE_UI_CHANGED_DURING_CAPTURE" }
        B6PublishedFile.publish(File(directory,"native-ui.png"),png)
        B6PublishedFile.publish(File(directory,"native-ui.ax.txt"),ax)
        val proof=org.json.JSONObject().put("schema","b6-native-ui-observation-v1").put("role","source")
            .put("operation_id",binding.operationID).put("manifest_sha256",binding.manifestHash).put("nonce",binding.nonce)
            .put("participant_pid",Process.myPid()).put("participant_uid",Process.myUid())
            .put("artifact_sha256",sha(File(instrumentation.context.applicationInfo.sourceDir).readBytes()))
            .put("begin_ns",begin).put("end_ns",SystemClock.elapsedRealtimeNanos()).put("first",first).put("second",second)
            .put("editor_opens",editorOpens).put("save_count",saves).put("expected_local_body_sha256",binding.expectedLocalBodyHash)
            .put("png_sha256",sha(png)).put("ax_sha256",sha(ax))
        guard(); B6PublishedFile.publish(File(directory,"native-ui-proof.json"),bytes(proof.toString()+"\n")); guard()
    }

    fun cancelPreparationOnly(deadlineNS: Long) {
        check(SystemClock.elapsedRealtimeNanos()<deadlineNS && current() && saves==0 && !consumed.get()) { "PREPARATION_CANCEL_GUARD" }
        qualifiedSave()
        val ui=instrumentation.uiAutomation
        val root=requireNotNull(ui.rootInActiveWindow)
        val label=preparationLabel(R.string.cancel)
        val targets=nodes(root).filter { it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label) }.mapNotNull { leaf ->
            var node:AccessibilityNodeInfo?=leaf;var depth=0
            while(node!=null && depth++<24 && !(node.isVisibleToUser && node.isClickable && node.isEnabled)) node=node.parent
            node
        }.distinct()
        val target=targets.single();val area=rect(target)
        val window=ui.windows.single { it.id==root.windowId && it.isActive && it.isFocused && it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        check(target.packageName?.toString()==PACKAGE && target.windowId==window.id && !area.isEmpty && rect(root).contains(area) &&
            ui.windows.none { it.id!=window.id && it.layer>window.layer && Rect.intersects(area,Rect().also(it::getBoundsInScreen)) }) { "PREPARATION_CANCEL_TARGET" }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val title=preparationLabel(R.string.edit_product_title)
        while(nodes(requireNotNull(ui.rootInActiveWindow)).any { it.isVisibleToUser && it.text?.toString()==title }) {
            check(SystemClock.elapsedRealtimeNanos()<deadlineNS && current() && saves==0 && !consumed.get());Thread.sleep(20)
        }
        val restored=requireNotNull(ui.rootInActiveWindow)
        val products=preparationLabel(R.string.database_tab_products)
        check(restored.packageName?.toString()==PACKAGE && nodes(restored).count { it.isVisibleToUser && it.text?.toString()==products }==1 &&
            SystemClock.elapsedRealtimeNanos()<deadlineNS && current() && saves==0 && !consumed.get())
    }

    private fun preparationLabel(resource: Int): String {
        val currentActivity = resumed()
        check(current() && (activity == null || currentActivity === activity)) { "SAVE_RESOURCE_ACTIVITY_CHANGED" }
        return currentActivity.getString(resource)
    }

    private fun resumed(): Activity {
        var active: List<Activity> = emptyList()
        instrumentation.runOnMainSync {
            active = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return active.single().also { check(it.javaClass == MainActivity::class.java && !it.isFinishing && !it.isDestroyed) { "SAVE_ACTIVITY" } }
    }
    fun freezeSetup(deadlineNS: Long) {
        check(SystemClock.elapsedRealtimeNanos() < deadlineNS) { "SAVE_SETUP_DEADLINE" }
        synchronized(this) {
            check(!setupFrozen && !setupInvalid && editorOpens == 1 && editorDraws > 0 && saves == 0 && current()) { "SAVE_SETUP_NOT_FRESH_CREATE" }
        }
        // Never hold the hook lock while waiting for the main thread.
        activity = resumed(); editorWindow = qualifiedSave().windowId
        synchronized(this) { check(!setupInvalid && editorOpens == 1 && saves == 0 && SystemClock.elapsedRealtimeNanos() < deadlineNS); setupFrozen = true }
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root); val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            check(result.size < 5000) { "SAVE_AX_TREE_LIMIT" }; val node = queue.removeFirst(); result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return result
    }
    private fun rect(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun fieldLabels(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 36) return node.labeledByList
        // API31..35 has only the singular getter; do not call the API36 method on the normal API35 AVD.
        @Suppress("DEPRECATION") val legacy = node.labeledBy
        return listOfNotNull(legacy)
    }
    private fun qualifiedSave(observed: org.json.JSONObject? = null): AccessibilityNodeInfo {
        check(current() && resumed().let { activity == null || it === activity }) { "SAVE_AUTHORITY_OR_ACTIVITY_CHANGED" }
        val ui = instrumentation.uiAutomation
        val root = requireNotNull(ui.rootInActiveWindow); check(root.packageName?.toString() == PACKAGE)
        val windows = ui.windows
        val window = windows.single { it.id == root.windowId && it.isActive && it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        check(editorWindow == null || window.id == editorWindow) { "SAVE_WINDOW_CHANGED" }
        val all = nodes(root)
        fun label(resource: Int) = (activity ?: resumed()).getString(resource)
        fun exact(value: String) = all.filter { it.text?.toString() == value || it.contentDescription?.toString() == value }
        check(exact(label(R.string.edit_product_title)).count { it.isVisibleToUser } == 1) { "SAVE_EDITOR_NOT_UNIQUE" }
        val editorWindows = windows.filter { other -> other.type == AccessibilityWindowInfo.TYPE_APPLICATION && other.root?.let { r ->
            r.packageName?.toString() == PACKAGE && nodes(r).any { it.isVisibleToUser && it.text?.toString() == label(R.string.edit_product_title) }
        } == true }
        check(editorWindows.size == 1 && editorWindows.single().id == window.id) { "SAVE_MULTIPLE_EDITORS" }
        val actualFields = org.json.JSONObject(); val expectedFields = org.json.JSONObject()
        fun field(resource: Int, value: String, editable: Boolean = true) {
            val title = label(resource)
            val candidates = all.filter { it.className?.toString() == "android.widget.EditText" &&
                (it.hintText?.toString() == title || fieldLabels(it).any { labelled -> labelled.text?.toString() == title }) }
            val node = candidates.single(); check(node.windowId == window.id && node.packageName?.toString() == PACKAGE &&
                (!editable || node.isEditable && node.isEnabled) && node.text?.toString().orEmpty() == value && node.error == null) { "SAVE_DRAFT_FIELD" }
            actualFields.put(resource.toString(), node.text?.toString().orEmpty())
            expectedFields.put(resource.toString(), value)
        }
        field(R.string.barcode_label, expected.barcode)
        field(R.string.product_name_label, expected.productName.orEmpty())
        field(R.string.second_product_name_label, expected.secondProductName.orEmpty())
        field(R.string.item_code_label, expected.itemNumber.orEmpty())
        field(R.string.purchase_price_label, formatClPriceInput(expected.purchasePrice))
        field(R.string.retail_price_label, formatClPriceInput(expected.retailPrice))
        field(R.string.header_stock_quantity, formatClQuantityInput(expected.stockQuantity))
        field(R.string.supplier_label, label(R.string.no_supplier), false)
        field(R.string.category_label, label(R.string.no_category), false)
        check(all.none { it.error != null || it.className?.toString() == "android.widget.ProgressBar" && it.isVisibleToUser }) { "SAVE_ERROR_OR_LOADING" }
        val emptyImages = exact(label(R.string.product_image_main)).map { image ->
            var node: AccessibilityNodeInfo? = image; var depth = 0
            while (node != null && depth++ < 24 && node.stateDescription?.toString() != label(R.string.product_image_state_empty)) node = node.parent
            requireNotNull(node)
        }.distinct()
        check(emptyImages.size == 1) { "SAVE_IMAGE_NOT_EMPTY" }
        val clicks = exact(label(R.string.save)).filter { it.isVisibleToUser }.map { labelled ->
            var node: AccessibilityNodeInfo? = labelled; var depth = 0
            while (node != null && depth++ < 24 && !(node.isClickable && node.isVisibleToUser && node.isEnabled)) node = node.parent
            requireNotNull(node).also { check(it.packageName?.toString() == PACKAGE && it.windowId == window.id && it.isClickable && it.isEnabled && it.isVisibleToUser) }
        }.distinct()
        val save = clicks.single(); val bounds = rect(save)
        check(!bounds.isEmpty && rect(root).contains(bounds) && windows.none { other -> other.id != window.id &&
            (other.layer > window.layer || other.isActive || other.isFocused) && Rect.intersects(bounds, Rect().also(other::getBoundsInScreen)) }) { "SAVE_OVERLAY_OR_VIEWPORT" }
        check(all.none { it.isVisibleToUser && it.text?.toString() in listOf(label(R.string.product_editor_review_title), label(R.string.product_editor_saving)) }) { "SAVE_REVIEW_OR_LOADING" }
        observed?.put("actual_fields",actualFields)?.put("expected_fields",expectedFields)
            ?.put("window_id",window.id)?.put("actual_title",exact(label(R.string.edit_product_title)).single { it.isVisibleToUser }.text.toString())
            ?.put("save_bounds",org.json.JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
            ?.put("actual_image_state",emptyImages.single().stateDescription?.toString())
            ?.put("expected_image_state",label(R.string.product_image_state_empty))
        return save
    }

    private fun identity(reply: PilotCommitTrace.B6Reply) = buildJsonObject {
        put("clock_id", reply.clockID); put("participant_pid", reply.participantPID); put("participant_boot_sha256", reply.bootHash)
        put("artifact_sha256", reply.artifactHash); put("device_sha256", reply.deviceHash); put("ordinary_preflight_sha256", reply.preflightHash)
    }
    internal fun validatePermit(raw: ByteArray, reply: PilotCommitTrace.B6Reply, witness: File): JsonObject {
        val wire = decode(raw); check(wire.endsWith("\n") && wire.count { it == '\n' } == 1 && !wire.contains('\r'))
        val value = strict(wire); check(bytes(canonical(value).toString() + "\n").contentEquals(raw)) { "SAVE_PERMIT_WIRE" }
        check(value.keys == setOf("schema", "campaign", "sample_id", "operation_id", "manifest_sha256", "source_identity",
            "owner_ready_raw", "owner_ready_sha256", "owner_accept_raw", "owner_accept_sha256", "source_idle_witness_sha256"))
        check(value.text("schema") == SCHEMA && value.text("campaign") == CAMPAIGN && value.text("sample_id") == binding.sampleID &&
            value.text("operation_id") == binding.operationID && value.text("manifest_sha256") == binding.manifestHash)
        check(value.getValue("source_identity").jsonObject.keys == IDENTITY_KEYS && value.getValue("source_identity") == identity(reply) &&
            reply.participantPID == Process.myPid() && reply.operationID == binding.operationID) { "SAVE_SOURCE_IDENTITY" }
        fun owner(name: String): JsonObject {
            val frame = value.text("owner_" + name + "_raw")
            check(frame.endsWith("\n") && frame.count { it == '\n' } == 1 && !frame.contains('\r') &&
                sha(bytes(frame)) == value.text("owner_" + name + "_sha256")) { "SAVE_OWNER_FRAME" }
            return strict(frame)
        }
        val ready = owner("ready"); val accepted = owner("accept")
        check(value.text("owner_ready_sha256") == config.text("owner_ready_sha256") && ready.text("kind") == "B6_READY" &&
            ready.text("bindingSHA256") == config.text("owner_binding_sha256")) { "SAVE_OWNER_READY_PIN" }
        check(UUID.fromString(ready.text("ownerID")).toString() == ready.text("ownerID") && ready.integer("ownerPID") > 0L &&
            ready.text("hostBootIdentityHash").matches(Regex("[0-9a-f]{64}")) && ready.integer("clockStartedNS") > 0L)
        check(OWNER_KEYS.all { ready[it] == accepted[it] } && ready.text("operationID") == binding.operationID &&
            ready.text("manifestSHA256") == binding.manifestHash && ready.text("timing_method") == PilotCommitTrace.B6_CAUSAL_METHOD &&
            accepted.text("kind") == "B6_SOURCE_IDLE_WITNESS_ACCEPTED" &&
            accepted.integer("controlSequence") == config.integer("expected_accept_control_sequence") &&
            accepted.integer("monotonicNS") >= ready.integer("clockStartedNS")) { "SAVE_OWNER_ACCEPTANCE" }
        val witnessRaw = read(witness, 65536); val witnessHash = sha(witnessRaw); val own = strict(decode(witnessRaw))
        check(value.text("source_idle_witness_sha256") == witnessHash && accepted.text("sha256") == witnessHash &&
            own.text("kind") == "B6_SOURCE_IDLE_WITNESS" && own.text("operation_id") == binding.operationID &&
            own.text("manifest_sha256") == binding.manifestHash && own.text("nonce") == binding.sourceAdmissionNonce &&
            own.getValue("identity") == identity(reply)) { "SAVE_WITNESS_JOIN" }
        return value
    }

    fun poll(reply: PilotCommitTrace.B6Reply, witness: File, deadlineNS: Long) {
        if (consumed.get() || !B6PublishedFile.isPublished(permit)) return
        dispatch(reply, witness, deadlineNS)
    }
    fun requireAttempted(reply: PilotCommitTrace.B6Reply, witness: File, deadlineNS: Long) {
        if (!consumed.get()) dispatch(reply, witness, deadlineNS)
    }
    private fun dispatch(reply: PilotCommitTrace.B6Reply, witness: File, deadlineNS: Long) {
        check(consumed.compareAndSet(false, true)) { "SAVE_ALREADY_CONSUMED" }
        var permitHash: String? = null; var latchHash: String? = null; var attempts = 0
        var disposition = "consumed_before_dispatch_unknown"; var failure: Throwable? = null
        val key = buildJsonObject {
            put("schema", "b6-source-save-consumed-v1"); put("campaign", CAMPAIGN); put("sample_id", binding.sampleID)
            put("operation_id", binding.operationID); put("manifest_sha256", binding.manifestHash); put("source_identity", identity(reply))
        }.toMutableMap()
        try {
            val raw = read(permit, 65536); permitHash = sha(raw); key["permit_sha256"] = JsonPrimitive(permitHash)
            val accepted = validatePermit(raw, reply, witness); val owner = strict(accepted.text("owner_accept_raw"))
            key["ownerID"] = owner.getValue("ownerID"); key["controlSequence"] = owner.getValue("controlSequence")
            key["source_idle_witness_sha256"] = accepted.getValue("source_idle_witness_sha256")
            val marker = bytes(JsonObject(key).toString() + "\n"); exclusive(latch, marker); latchHash = sha(marker)
            check(SystemClock.elapsedRealtimeNanos() < deadlineNS && current() && !setupInvalid && saves == 0 && setupFrozen) { "SAVE_NATIVE_GUARD" }
            val snapshot = requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
            check(snapshot.invalidReason == null && snapshot.events.size == 1 && snapshot.events.single().stage == "causal_source_idle_witness") { "SAVE_NOT_IDLE" }
            val save = qualifiedSave()
            val actualBoot = File("/proc/sys/kernel/random/boot_id").readText().trim()
            UUID.fromString(actualBoot)
            val actualAPK = File(instrumentation.targetContext.applicationInfo.sourceDir)
            check(sha(bytes(actualBoot)) == reply.bootHash && actualAPK.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) }
            } == reply.artifactHash && read(permit,65536).contentEquals(raw) &&
                SystemClock.elapsedRealtimeNanos() < deadlineNS && current()) { "SAVE_DEADLINE_OR_IDENTITY_OR_AUTHORITY" }
            attempts = 1
            val acceptedClick = save.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            disposition = if (SystemClock.elapsedRealtimeNanos() >= deadlineNS) "timeout_unknown" else if (acceptedClick) "attempted_true" else "attempted_false"
            check(disposition == "attempted_true") { "SAVE_ACTION_NOT_ACCEPTED" }
        } catch (error: Throwable) {
            failure = error
            if (attempts == 1 && disposition == "consumed_before_dispatch_unknown") disposition = "threw"
            if (latchHash == null && !latch.exists()) try {
                key["rejected_before_dispatch"] = JsonPrimitive(true)
                val marker = bytes(JsonObject(key).toString() + "\n"); exclusive(latch, marker); latchHash = sha(marker)
            } catch (consumeFailure: Throwable) { error.addSuppressed(consumeFailure) }
        } finally {
            try {
                val receipt = buildJsonObject {
                    put("schema", "b6-source-save-result-v1"); put("operation_id", binding.operationID); put("manifest_sha256", binding.manifestHash)
                    put("source_identity", identity(reply)); put("permit_sha256", permitHash?.let(::JsonPrimitive) ?: JsonNull)
                    put("latch_sha256", latchHash?.let(::JsonPrimitive) ?: JsonNull); put("disposition", disposition); put("action_attempts", attempts)
                    failure?.let { put("error_code", it.javaClass.simpleName) }
                }
                B6PublishedFile.publish(result, bytes(receipt.toString() + "\n"))
            } catch (exportFailure: Throwable) {
                val original = failure; if (original == null) failure = exportFailure else original.addSuppressed(exportFailure)
            }
        }
        failure?.let { throw it }
    }
}

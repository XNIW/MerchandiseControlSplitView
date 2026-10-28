package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.GenerationOwnedSupabaseClient
import com.example.merchandisecontrolsplitview.data.InventoryProductPatch
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.PilotB6AxTargetJoin
import com.example.merchandisecontrolsplitview.data.nativeDurableWrite
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class Task103AuthPreflightTest {
    // Reuses the candidate03 PilotNameObserver window/ancestor oracle inside this ordinary harness.
    // No scrolling, clicks, activation or navigation: the prepared route must already be visible.
    private data class B6VisibleQuery(val beginNS: Long, val endNS: Long, val context: Boolean,
        val before: Boolean, val after: Boolean, val bounds: android.graphics.Rect?, val windowID: Int?,
        val windowBounds: android.graphics.Rect? = null, val actualLabels: List<String> = emptyList(),
        val foreground: Boolean = false, val uniqueRow: Boolean = false, val viewport: Boolean = false,
        val overlayAbsent: Boolean = false, val exactQuery: Boolean = false)
    private fun b6VisibleQuery(expected: JSONObject, causalFields: Boolean = false): B6VisibleQuery {
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        val begin=android.os.SystemClock.elapsedRealtimeNanos(); val root=ui.rootInActiveWindow
        fun nodes(start: android.view.accessibility.AccessibilityNodeInfo, limit: Int): List<android.view.accessibility.AccessibilityNodeInfo> {
            val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(); queue.add(start)
            val result=ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            while(queue.isNotEmpty()) {
                check(result.size<limit) { "AX_TREE_OVERFLOW" }; val node=queue.removeFirst(); result.add(node)
                for(i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
            return result
        }
        fun bounds(node: android.view.accessibility.AccessibilityNodeInfo)=android.graphics.Rect().also(node::getBoundsInScreen)
        var qualified=false; var before=false; var after=false; var rect:android.graphics.Rect?=null; var windowID:Int?=null
        var windowRect:android.graphics.Rect?=null; var actualLabels=emptyList<String>()
        var foreground=false; var uniqueRow=false; var inViewport=false; var overlayAbsent=false
        if(root!=null && root.packageName?.toString()=="com.example.merchandisecontrolsplitview") {
            val windows=ui.windows
            val window=windows.singleOrNull { it.id==root.windowId && it.isActive &&
                it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            val identity=nodes(root,512).filter { it.text?.toString()==expected.getString("barcodeAXLabel") }
            if(window!=null && identity.size==1) {
                var parent=identity.single().parent
                while(parent!=null) {
                    val subtree=nodes(parent,128)
                    if(parent.isClickable && subtree.count { it.text?.toString()==expected.getString("barcodeAXLabel") }==1) {
                        val r=bounds(parent); val viewport=bounds(root)
                        fun fields(name:String):Boolean {
                            val values=expected.getJSONArray(name)
                            return (0 until values.length()).all { index ->
                                val matched=subtree.filter { it.text?.toString()==values.getString(index) }
                                matched.size==1 && matched.single().isVisibleToUser && r.contains(bounds(matched.single()))
                            }
                        }
                        val clear=windows.none { it.id!=window.id && it.layer>window.layer &&
                            android.graphics.Rect().let { other -> it.getBoundsInScreen(other); android.graphics.Rect.intersects(r,other) } }
                        qualified=parent.isVisibleToUser && identity.single().isVisibleToUser && !r.isEmpty &&
                            viewport.contains(r) && r.height()<=viewport.height()*0.5 && clear
                        before=fields("beforeFields"); after=fields("afterFields"); rect=r; windowID=window.id; break
                    }
                    parent=parent.parent
                }
            }
        }
        if(!causalFields) return B6VisibleQuery(begin,android.os.SystemClock.elapsedRealtimeNanos(),qualified,before,after,rect,windowID)
        if (root!=null && qualified && rect!=null) {
            val qualifiedRect=requireNotNull(rect)
            val window=ui.windows.singleOrNull { it.id==windowID && it.isActive &&
                it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            windowRect=window?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
            foreground=window!=null; inViewport=bounds(root).contains(qualifiedRect); overlayAbsent=qualified
            // Read actual matching node values from the same qualified row, never expected strings as evidence.
            val expectedValues=expected.getJSONArray(if(after) "afterFields" else "beforeFields")
            val rowNodes=nodes(root,512).filter { it.isVisibleToUser && qualifiedRect.contains(bounds(it)) }
            actualLabels=(0 until expectedValues.length()).mapNotNull { index ->
                rowNodes.singleOrNull { it.text?.toString()==expectedValues.getString(index) }?.text?.toString()
            }
            uniqueRow=nodes(root,512).count { it.text?.toString()==expected.getString("barcodeAXLabel") }==1
        }
        return B6VisibleQuery(begin,android.os.SystemClock.elapsedRealtimeNanos(),qualified,before,after,rect,windowID,
            windowRect,actualLabels,foreground,uniqueRow,inViewport,overlayAbsent,if(after) after else before)
    }

    /** The two anchors name actual row identities, never a toolbar/panel title.
     * Create/delete may use different before/control and after/target rows. A price row
     * without an actual clickable/collection-item AX container remains unqualified. */
    private fun b6TypedVisibleQuery(expected: JSONObject, category: String, causalFields: Boolean = false): B6VisibleQuery {
        if(category=="update") return b6VisibleQuery(expected,causalFields)
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        val begin=android.os.SystemClock.elapsedRealtimeNanos()
        val root=ui.rootInActiveWindow ?: return B6VisibleQuery(begin,android.os.SystemClock.elapsedRealtimeNanos(),false,false,false,null,null)
        if(root.packageName?.toString()!="com.example.merchandisecontrolsplitview")
            return B6VisibleQuery(begin,android.os.SystemClock.elapsedRealtimeNanos(),false,false,false,null,null)
        val windows=ui.windows
        val window=windows.singleOrNull { it.id==root.windowId && it.isActive && it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        fun rect(n: android.view.accessibility.AccessibilityNodeInfo)=android.graphics.Rect().also(n::getBoundsInScreen)
        fun bounds(r: android.graphics.Rect)=PilotB6AxTargetJoin.Bounds(r.left,r.top,r.right,r.bottom)
        val viewport=rect(root)
        val nodes=ArrayList<PilotB6AxTargetJoin.Node>()
        val pending=java.util.ArrayDeque<Triple<android.view.accessibility.AccessibilityNodeInfo,Int,Int?>>()
        pending.add(Triple(root,0,null));var nextID=1
        while(pending.isNotEmpty()) {
            check(nodes.size<512) { "AX_TREE_OVERFLOW" }
            val (node,id,parent)=pending.removeFirst()
            nodes.add(PilotB6AxTargetJoin.Node(id,parent,node.text?.toString(),node.isVisibleToUser,
                bounds(rect(node)),node.isClickable,node.collectionItemInfo!=null,node.collectionInfo!=null))
            for(i in 0 until node.childCount) node.getChild(i)?.let { child ->
                check(nextID<512) { "AX_TREE_OVERFLOW" };pending.add(Triple(child,nextID++,id))
            }
        }
        fun labels(name: String): List<String> = expected.getJSONArray(name).let { values -> (0 until values.length()).map(values::getString) }
        val joined=PilotB6AxTargetJoin.evaluate(nodes,0,bounds(viewport),
            PilotB6AxTargetJoin.Target(expected.getString("beforeAnchorAXLabel"),labels("beforeFields")),
            PilotB6AxTargetJoin.Target(expected.getString("afterAnchorAXLabel"),labels("afterFields")),
            labels("absentAfterFields"))
        val area=joined.observedBounds?.let { android.graphics.Rect(it.left,it.top,it.right,it.bottom) }
        val clear=area!=null && windows.none { it.id!=root.windowId && it.layer>(window?.layer ?: -1) &&
            android.graphics.Rect().let { other -> it.getBoundsInScreen(other);android.graphics.Rect.intersects(area,other) } }
        val context=window!=null && root.isVisibleToUser && joined.context && clear
        if(!causalFields) return B6VisibleQuery(begin,android.os.SystemClock.elapsedRealtimeNanos(),context,
            joined.beforeMatches,joined.afterMatches,area,root.windowId)
        val byID=nodes.associateBy { it.id }
        fun inside(node: PilotB6AxTargetJoin.Node, ancestor: Int): Boolean {
            var current: PilotB6AxTargetJoin.Node?=node
            while(current!=null) { if(current.id==ancestor) return true; current=current.parentID?.let(byID::get) }
            return false
        }
        val selectedMatch=if(joined.afterMatches) joined.after else joined.before
        val selectedRow=selectedMatch.containerID
        val actualLabels=if(selectedRow==null) emptyList() else labels(if(joined.afterMatches) "afterFields" else "beforeFields").mapNotNull { label ->
            nodes.singleOrNull { it.visible && it.text==label && inside(it,selectedRow) }?.text
        }
        val windowRect=window?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
        return B6VisibleQuery(begin,android.os.SystemClock.elapsedRealtimeNanos(),context,
            joined.beforeMatches,joined.afterMatches,area,root.windowId,windowRect,actualLabels,
            window!=null && root.isVisibleToUser,selectedMatch.context,area?.let(viewport::contains)==true,clear,
            selectedMatch.fields && (!joined.afterMatches || joined.absentAfter))
    }

    private fun b6QueryJSON(query: B6VisibleQuery, sequence: Int, phase: String): JSONObject {
        fun frame(rect: android.graphics.Rect?): Any = rect?.let {
            org.json.JSONArray(listOf(it.left,it.top,it.width(),it.height()))
        } ?: JSONObject.NULL
        return JSONObject().put("sequence",sequence).put("phase",phase)
            .put("beginNS",query.beginNS).put("endNS",query.endNS).put("context",query.context)
            .put("before",query.before).put("after",query.after).put("foreground",query.foreground)
            .put("uniqueRow",query.uniqueRow).put("viewport",query.viewport).put("overlayAbsent",query.overlayAbsent)
            .put("exactQuery",query.exactQuery).put("frame",frame(query.bounds)).put("windowFrame",frame(query.windowBounds))
            .put("windowID",query.windowID ?: JSONObject.NULL)
            .put("labelsSHA256",liveHash(org.json.JSONArray(query.actualLabels).toString().encodeToByteArray()))
    }

    private fun b6FreshVisibleConfirmation(expected: JSONObject, category: String, dir: File,
        challengeNS: Long, originalDeadlineNS: Long, record: (B6VisibleQuery,String)->Unit): JSONObject {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun deadline()=check(android.os.SystemClock.elapsedRealtimeNanos()<originalDeadlineNS) { "B6_ORIGINAL_OBSERVATION_TIMEOUT" }
        fun fresh(phase: String): B6VisibleQuery {
            deadline(); val value=b6TypedVisibleQuery(expected,category,true); record(value,phase); deadline()
            check(value.beginNS>challengeNS && value.endNS>=value.beginNS && value.context && value.after &&
                (category in setOf("create","price") || !value.before) && value.foreground && value.uniqueRow &&
                value.viewport && value.overlayAbsent && value.exactQuery && value.actualLabels.isNotEmpty()) { "B6_FRESH_POSITIVE_UNQUALIFIED" }
            return value
        }
        fun capture(path: File,area: android.graphics.Rect): Pair<Long,Long> {
            deadline(); val begin=android.os.SystemClock.elapsedRealtimeNanos()
            check(begin>challengeNS && !path.exists())
            val full=requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "SCREENSHOT_UNAVAILABLE" }
            try {
                check(area.left>=0 && area.top>=0 && area.right<=full.width && area.bottom<=full.height && !area.isEmpty)
                val crop=android.graphics.Bitmap.createBitmap(full,area.left,area.top,area.width(),area.height())
                try { val bytes=java.io.ByteArrayOutputStream().use { stream ->
                    check(crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)); stream.toByteArray() }
                    B6PublishedFile.publish(path,bytes)
                } finally { if(crop!==full) crop.recycle() }
            } finally { full.recycle() }
            val end=android.os.SystemClock.elapsedRealtimeNanos(); deadline(); return begin to end
        }
        val first=fresh("causal_after_commit")
        val row=File(dir,"causal-peer-row.png"); val rowTimes=capture(row,requireNotNull(first.bounds))
        val rowConfirmation=fresh("confirm_after_row_capture")
        check(rowConfirmation.beginNS>rowTimes.second)
        val window=File(dir,"causal-peer-window.png"); val windowTimes=capture(window,requireNotNull(rowConfirmation.windowBounds))
        val windowConfirmation=fresh("confirm_after_window_capture")
        check(windowConfirmation.beginNS>windowTimes.second)
        check(listOf(rowConfirmation,windowConfirmation).all { it.bounds==first.bounds && it.windowID==first.windowID &&
            it.windowBounds==first.windowBounds && it.actualLabels==first.actualLabels }) { "UI_CHANGED_DURING_CONFIRMATION" }
        return JSONObject().put("outcome","POSITIVE_AFTER_COMMIT")
            .put("queries",org.json.JSONArray(listOf(b6QueryJSON(first,0,"causal_after_commit"),
                b6QueryJSON(rowConfirmation,1,"confirm_after_row_capture"),b6QueryJSON(windowConfirmation,2,"confirm_after_window_capture"))))
            .put("capture_proof",JSONObject().put("first_positive_sequence",0).put("row_confirmation_sequence",1)
                .put("window_confirmation_sequence",2).put("row_capture_begin_ns",rowTimes.first).put("row_capture_end_ns",rowTimes.second)
                .put("window_capture_begin_ns",windowTimes.first).put("window_capture_end_ns",windowTimes.second))
            .put("row_screenshot_sha256",liveHash(B6PublishedFile.requireRead(row,16_777_216)))
            .put("window_screenshot_sha256",liveHash(B6PublishedFile.requireRead(window,16_777_216)))
            .put("terminal_labels",org.json.JSONArray(windowConfirmation.actualLabels))
    }

    /** Ordinary observation; an additional explicit G02-03 config enables one guarded native UI Save. */
    @Test
    fun task144B6ObserveOrdinaryWindowWhenEnabled() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Ordinary B6 observation is explicitly gated",args.getString("task144B6Observe")=="true")
        require(instrumentation.javaClass.name=="androidx.test.runner.AndroidJUnitRunner")
        val app=instrumentation.targetContext.applicationContext as MerchandiseControlApplication
        require(app.javaClass==MerchandiseControlApplication::class.java && app.applicationInfo.splitSourceDirs.isNullOrEmpty())
        require(liveHash(BuildConfig.SUPABASE_URL.encodeToByteArray())==LIVE_TEST_PROJECT_HASH)
        val bindingRaw=requireNotNull(args.getString("task144B6Binding"))
        require(bindingRaw.toByteArray().size<=8192)
        val binding=kotlinx.serialization.json.Json.decodeFromString(
            com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Binding.serializer(),bindingRaw)
        val manifestFile=File(requireNotNull(args.getString("task144B6ManifestPath")))
        val bytes=B6PublishedFile.requireRead(manifestFile,131_072); require(bytes.isNotEmpty() && liveHash(bytes)==binding.manifestHash)
        val manifest=JSONObject(bytes.decodeToString()); require(manifest.getString("status")=="AUTHORIZED_FOR_B6_EVIDENCE")
        val causal=binding.timingMethod!=null
        val nativeJSON=if(causal) kotlinx.serialization.json.Json { encodeDefaults=true } else kotlinx.serialization.json.Json
        if(causal) {
            require(binding.timingMethod==com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6_CAUSAL_METHOD &&
                manifest.getString("timing_method")==binding.timingMethod &&
                manifest.getString("timing_method_review_sha256")=="eaa91da232992c7c27f0a3eb635c867cb0a8e40300c65ca56dec005b2f92ea80")
            val challenge=if(binding.role=="source") binding.sourceAdmissionNonce else binding.visibilityConfirmationNonce
            require(challenge!=null && challenge!=binding.nonce && challenge!=args.getString("task144B6FinalNonce") &&
                UUID.fromString(challenge).toString()==challenge)
        } else require(!manifest.has("timing_method") || manifest.isNull("timing_method"))
        val cas=manifest.getJSONObject("cas_prerequisite")
        require(cas.getString("planned_manifest_sha256")=="94ecbf580936f36a0aae569e85a3a5eb5508cdbc4d99a796185d3c18e37678c0")
        listOf("ios_native_receipt_sha256","android_native_receipt_sha256").forEach {
            require(cas.getString(it).matches(Regex("[0-9a-f]{64}"))) }
        val direction=if(binding.role=="source") "android_to_ios" else "ios_to_android"
        val pins=manifest.getJSONObject("directions").getJSONObject(direction)
        require(pins.getString("ordinary_preflight_sha256")==binding.preflightHash)
        val role=if(binding.role=="source") "source" else "peer"
        val apkHash=File(app.applicationInfo.sourceDir).inputStream().use { input ->
            val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        require(apkHash==pins.getString(role+"_artifact_sha256") && apkHash==binding.artifactHash)
        val signedIn=requireNotNull(signedInState(app)); require(requireReadyLocalStore(app, signedIn)) { "Ordinary app cloud scope is not ready" }
        val owner=requireNotNull(app.supabaseClient as? GenerationOwnedSupabaseClient)
        val sdk=owner.captureClient(); val auth=sdk.auth.sessionStatus.value as? SessionStatus.Authenticated ?: error("ORDINARY_AUTH_REQUIRED")
        val context=app.shopContextRepository.state.value; val shop=requireNotNull(context.selectedShop)
        val epoch=app.shopContextRepository.diagnosticShopEpoch(); val device=requireNotNull(app.deviceInstallIdProvider.currentId)
        val actualScope=liveHash((com.example.merchandisecontrolsplitview.data.task126OwnerHash(signedIn.userId)+"\nshop:"+shop.shopId).encodeToByteArray())
        val deviceHash=liveHash(device.encodeToByteArray())
        require(liveHash(signedIn.userId.lowercase().encodeToByteArray())==pins.getString("owner_sha256") &&
            liveHash(shop.shopId.lowercase().encodeToByteArray())==pins.getString("shop_sha256") &&
            deviceHash==pins.getString(role+"_device_sha256"))
        val tracker=app.catalogSyncStateTracker
        val observedState=tracker.businessDataScopeState.value
        val localStamp=requireNotNull(tracker.captureLocalPresentationStamp(observedState))
        val cloudSignal=tracker.captureBusinessDataScopeSignal(signedIn.userId,shop.shopId)
        val cloudLease=cloudSignal.lease
        require(!cloudLease.unmanaged && !cloudLease.localOnly)
        val scopeStamp=com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6ScopeStamp(requireNotNull(cloudLease.boundScope))
        val authority=com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Authority(
            com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6AuthorityStamp(
                scopeStamp,localStamp.localGeneration,cloudLease.generation)) {
            tracker.isLocalPresentationStampCurrent(localStamp,observedState) &&
                tracker.isCurrentBusinessDataScopeSignal(cloudSignal)
        }
        fun current() = authority.isCurrent() && owner.captureClientOrNull()===sdk && app.authManager.state.value==signedIn &&
            sdk.auth.sessionStatus.value===auth && auth.session.expiresAt>Clock.System.now()+5.seconds &&
            sdk.auth.currentUserOrNull()?.id==signedIn.userId && app.shopContextRepository.state.value==context &&
            app.shopContextRepository.diagnosticShopEpoch()==epoch && context.syncAllowed && !context.isLoading &&
            app.catalogSyncStateTracker.allowsBusinessDataScope(signedIn.userId,shop) &&
            app.deviceInstallIdProvider.currentId==device
        require(current() && auth.session.expiresAt>Clock.System.now()+65.seconds)
        val slots=manifest.getJSONArray("slots")
        val slot=(0 until slots.length()).map { slots.getJSONObject(it) }.single { it.getString("sample_id")==binding.sampleID }
        require(slot.getString("category")==binding.category)
        val fixture=slot.getJSONObject("fixture")
        fun fixtureID(key: String)=fixture.optString(key).takeIf { it.isNotBlank() && it!="null" }
        val targetID=fixtureID(when(binding.category) { "price" -> "price_id"; "history" -> "history_id"; else -> "product_id" })
        if(binding.category=="price") require(binding.expectedPriceValueJSON!=null &&
            liveHash(binding.expectedPriceValueJSON.encodeToByteArray())==fixture.getString("android_"+role+"_expected_price_values_sha256"))
        else require(binding.expectedLocalBodyHash != null && binding.expectedLocalBodyHash ==
            fixture.getString("android_" + role + "_expected_local_body_sha256")) { "EXPECTED_LOCAL_BODY_NOT_PINNED" }
        require(binding.entityHashes==targetID?.let { listOf(liveHash(it.lowercase().encodeToByteArray())) }.orEmpty())
        when(binding.category) {
            "update","delete","price" -> {
                val localID=requireNotNull(binding.localProductID)
                val ref=requireNotNull(app.database.productRemoteRefDao().getByProductId(localID))
                require(ref.remoteId==fixtureID("product_id") && app.database.productDao().getById(localID)!=null)
                if(binding.category=="price") require(binding.parentRemoteID==ref.remoteId)
            }
            "create" -> require(binding.localProductID==null && binding.expectedBarcode==fixture.getString("barcode") &&
                app.database.productDao().findByBarcode(fixture.getString("barcode"))==null)
            "history" -> {
                val uid=requireNotNull(binding.localEntityID)
                require(app.database.historyEntryDao().getByUid(uid)!=null)
                if(targetID!=null) require(app.database.historyEntryRemoteRefDao().getByHistoryEntryUid(uid)?.remoteId==targetID)
            }
            else -> error("CATEGORY_NOT_IMPLEMENTED")
        }
        require(current())
        val boot=File("/proc/sys/kernel/random/boot_id").readText().trim()
        UUID.fromString(boot) // If the app cannot read an actual boot identity, refuse; never synthesize one.
        val bootHash=liveHash(boot.encodeToByteArray())
        val dir=File(instrumentation.targetContext.cacheDir.canonicalFile,"codex-pilot-b6-${binding.operationID}")
        require(!dir.exists() && dir.mkdir()); val request=File(dir,"calibration-nonce.txt")
        val sourceAdmission=File(dir,"source-admission-nonce.txt")
        val sourceWitness=File(dir,"source-idle-witness.json")
        val tracePreview=File(dir,"causal-trace-preview.json")
        val visibilityConfirmation=File(dir,"visibility-confirmation-nonce.txt")
        val positivePreview=File(dir,"causal-positive-preview.json")
        val queryLog=File(dir,"causal-visibility-queries.jsonl")
        fun emit(value: JSONObject) { require(value.toString().toByteArray().size<=65536)
            instrumentation.sendStatus(42,android.os.Bundle().apply { putString("pilot",value.toString()) }) }
        val singleSave=args.getString("task144B6SingleSaveConfig")?.let { raw ->
            NormalB6SingleSaveActor(instrumentation,binding,raw,dir,::current)
        }
        val setupHooks=com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks
        require(setupHooks.observer==null && setupHooks.observationError==null)
        val ready=JSONObject().put("stage","READY_FOR_NONCE").put("operation_id",binding.operationID).put("request_path",request.path)
            .put("participant_pid",android.os.Process.myPid()).put("artifact_sha256",apkHash)
            .put("b6_publication_protocol",B6PublishedFile.PROTOCOL)
        singleSave?.ready()?.let { fields -> fields.keys().forEach { key -> ready.put(key,fields.get(key)) } }
        if(causal) ready.put("source_admission_request_path",sourceAdmission.path).put("source_idle_witness_path",sourceWitness.path)
            .put("causal_trace_preview_path",tracePreview.path).put("visibility_confirmation_request_path",visibilityConfirmation.path)
            .put("causal_positive_preview_path",positivePreview.path).put("causal_query_log_path",queryLog.path)
        // Read-only setup hook precedes CUA preparation. It never enters the armed trace.
        var setupComplete=false
        var setupFailure:Throwable?=null
        try {
            singleSave?.beginSetup()
            singleSave?.let { setupHooks.observer=it.setupObserver }
            emit(ready)
            val waitEnd=android.os.SystemClock.elapsedRealtimeNanos()+30_000_000_000L
            singleSave?.prepareNativeUI(waitEnd)
            while(!B6PublishedFile.isPublished(request) && android.os.SystemClock.elapsedRealtimeNanos()<waitEnd) Thread.sleep(20)
            require(B6PublishedFile.requireRead(request,64).contentEquals(binding.nonce.encodeToByteArray()) && current())
            singleSave?.freezeSetup(waitEnd)
            setupComplete=true
        } catch(error:Throwable) {
            setupFailure=error; throw error
        } finally {
            if(singleSave!=null && setupHooks.observer===singleSave.setupObserver) setupHooks.observer=null
            if(!setupComplete) try { singleSave?.restoreWindowObservation() } catch(restoreFailure:Throwable) {
                val original=setupFailure
                if(original==null) throw restoreFailure else original.addSuppressed(restoreFailure)
            }
        }
        val hooks=com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks
        require(hooks.observer==null && hooks.observationError==null)
        val visible=if(binding.role=="peer") {
            val raw=requireNotNull(args.getString("task144B6VisibleBinding")); require(raw.toByteArray().size<=8192)
            require(liveHash(raw.encodeToByteArray())==fixture.getString("visible_fields_sha256"))
            JSONObject(raw).also { value ->
                if(binding.category=="update") require(value.getString("barcodeAXLabel").contains(fixture.getString("barcode")))
                else {
                    listOf("beforeAnchorAXLabel","afterAnchorAXLabel").forEach { key ->
                        require(value.getString(key).length in 1..255) }
                    val absent=value.getJSONArray("absentAfterFields");require(absent.length() in 0..12)
                    if(binding.category=="price") require(binding.expectedPriceVisibleJSON!=null &&
                        liveHash(binding.expectedPriceVisibleJSON.encodeToByteArray())==fixture.getString("android_peer_price_visible_values_sha256"))
                    else if(binding.category!="create") require(binding.expectedVisibleProjectionHash==fixture.getString("android_peer_visible_projection_sha256"))
                }
                listOf("beforeFields","afterFields").forEach { key ->
                    val values=value.getJSONArray(key); require(values.length() in 1..12)
                    require((0 until values.length()).all { values.getString(it).length in 1..255 }) }
                val info=instrumentation.uiAutomation.serviceInfo
                info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                instrumentation.uiAutomation.serviceInfo=info
            }
        } else null
        var visibleOutcome="NOT_OBSERVED"; var lastNegative:B6VisibleQuery?=null; var firstPositive:B6VisibleQuery?=null
        var sourceWitnessHash:String?=null; var tracePreviewHash:String?=null; var visibilityPreviewHash:String?=null
        var visibilityChallengeNS:Long?=null; var causalConfirmation:JSONObject?=null; var queryCount=0
        var queryPending:B6PublishedFile.Pending?=null
        var querySealAttempted=false; var querySealed=false
        fun sealQueryLog():ByteArray? {
            if(!querySealAttempted) {
                querySealAttempted=true
                queryPending?.let { pending ->
                    try { pending.seal(); querySealed=true } finally { pending.close(); queryPending=null }
                }
            }
            return if(querySealed) B6PublishedFile.requireRead(queryLog,262_144) else null
        }
        fun recordQuery(query:B6VisibleQuery,phase:String) {
            if(!causal) return
            check(!querySealAttempted && queryCount<512) { "B6_QUERY_LOG_SEALED_OR_OVERFLOW" }
            val line=(b6QueryJSON(query,queryCount++,phase).toString()+"\n").encodeToByteArray()
            check(queryLog.length()+line.size<=262_144L) { "B6_QUERY_LOG_OVERFLOW" }
            val pending=queryPending ?: B6PublishedFile.pending(queryLog,262_144).also { queryPending=it }
            pending.append(line)
        }
        fun exactChallenge(file:File,nonce:String) {
            require(B6PublishedFile.requireRead(file,64).contentEquals(nonce.encodeToByteArray()) && current()) { "B6_CHALLENGE_IDENTITY_MISMATCH" }
        }
        fun exportEnvelope(file:File,value:JSONObject,stage:String):String {
            require(!file.exists() && current())
            val raw=value.toString().encodeToByteArray();require(raw.size<=524_288) { "B6_CAUSAL_PREVIEW_OVERFLOW" }
            B6PublishedFile.publish(file,raw)
            return liveHash(raw).also { digest -> emit(JSONObject().put("stage",stage).put("operation_id",binding.operationID)
                .put("path",file.path).put("sha256",digest)) }
        }
        val observer: (com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event,Long,String?,Long)->Unit = { event,id,detail,ns ->
            singleSave?.observe(event,id)
            val announced=if(causal && event==com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event.SAVE_CLICK)
                com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6AnnounceSourceUIActionFromTestOnly(ns) else null
            when(event) {
                com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event.SAVE_CLICK -> if(causal) {
                    val components=detail?.split(':')
                    val kind=if(components?.size==3 && components[0]=="b6-source") components[1]
                        else if(detail==null) if(id==0L) "create" else "update" else "unqualified"
                    val handler=if(components?.size==3) components[2] else if(detail==null) "product-editor-save" else "unqualified"
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6SourceUIActionFromTestOnly(kind,id,ns,handler,announced)
                }
                com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event.PRODUCT_DRAW ->
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6Rendered(id,detail,ns)
                com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event.HISTORY_DRAW ->
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6ProjectionRendered("history",id,detail,ns)
                com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event.PRICE_HISTORY_DRAW ->
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6ProjectionRendered("price",id,detail,ns)
                com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event.DELETE_DATASET_DRAW ->
                    binding.localProductID?.let { com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6ProjectionRendered("delete",it,detail,ns) }
                else -> Unit
            }
        }
        val reply=try { requireNotNull(com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6ArmFromTestOnly(
            binding,actualScope,deviceHash,apkHash,bootHash,authority)) } catch(armFailure:Throwable) {
            try { singleSave?.restoreWindowObservation() } catch(restoreFailure:Throwable) { armFailure.addSuppressed(restoreFailure) }
            throw armFailure
        }
        hooks.observer=observer
        var primaryFailure: Throwable?=null
        var observationComplete=false
        try {
            emit(JSONObject(kotlinx.serialization.json.Json.encodeToString(
                com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Reply.serializer(),reply)).put("stage","CALIBRATION_REPLY"))
            if(visible!=null) {
                val first=b6TypedVisibleQuery(visible,binding.category,causal)
                recordQuery(first,"baseline")
                check(first.context && first.before && !first.after) { "BASELINE_NOT_VISIBLE_OR_ALREADY_NEW" }
                lastNegative=first; visibleOutcome="BASELINE_READY"
                val baseline=JSONObject().put("stage","BASELINE_READY").put("operation_id",binding.operationID)
                    .put("clock_id",reply.clockID).put("negative_begin_ns",first.beginNS).put("negative_end_ns",first.endNS)
                if(causal) baseline.put("baseline_query",b6QueryJSON(first,0,"baseline"))
                emit(baseline)
            }
            val end=android.os.SystemClock.elapsedRealtimeNanos()+30_000_000_000L
            while(android.os.SystemClock.elapsedRealtimeNanos()<end && current()) {
                if(causal && binding.role=="source" && sourceWitnessHash==null && B6PublishedFile.isPublished(sourceAdmission)) {
                    exactChallenge(sourceAdmission,requireNotNull(binding.sourceAdmissionNonce))
                    val witness=requireNotNull(com.example.merchandisecontrolsplitview.data.PilotCommitTrace
                        .b6SourceIdleWitnessFromTestOnly(requireNotNull(binding.sourceAdmissionNonce))) { "B6_SOURCE_NOT_IDLE" }
                    sourceWitnessHash=exportEnvelope(sourceWitness,JSONObject(witness.toString()),"SOURCE_IDLE_WITNESS")
                }
                if(sourceWitnessHash!=null) singleSave?.poll(reply,sourceWitness,end)
                if(visible!=null) {
                    val query=b6TypedVisibleQuery(visible,binding.category,causal)
                    // Preserve ordinary UI guards; the published log is the immutable preview prefix.
                    if(!querySealAttempted) recordQuery(query,"ordinary_poll")
                    if(causal) check(query.context) { "OBSERVATION_CONTEXT_LOST" }
                    if(!query.context) { visibleOutcome="OBSERVATION_CONTEXT_LOST"; break }
                    if(query.after && (binding.category in setOf("create","price") || !query.before) && firstPositive==null) {
                        val full=requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "SCREENSHOT_UNAVAILABLE" }
                        try {
                            val confirmation=b6TypedVisibleQuery(visible,binding.category,causal)
                            recordQuery(confirmation,"original_positive_confirmation")
                            if(!confirmation.context || !confirmation.after || (binding.category !in setOf("create","price") && confirmation.before) ||
                                query.bounds!=confirmation.bounds || query.windowID!=confirmation.windowID) {
                                visibleOutcome="UI_CHANGED_DURING_CONFIRMATION"; break }
                            val rect=requireNotNull(query.bounds)
                            check(rect.left>=0 && rect.top>=0 && rect.right<=full.width && rect.bottom<=full.height)
                            val crop=android.graphics.Bitmap.createBitmap(full,rect.left,rect.top,rect.width(),rect.height())
                            try { val raw=java.io.ByteArrayOutputStream().use { stream ->
                                check(crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)); stream.toByteArray() }
                                B6PublishedFile.publish(File(dir,"peer-row.png"),raw) }
                            finally { if(crop!==full) crop.recycle() }
                            firstPositive=confirmation.copy(endNS=android.os.SystemClock.elapsedRealtimeNanos())
                            visibleOutcome="FIRST_POSITIVE_OBSERVED"
                        } finally { full.recycle() }
                    } else if(query.before && !query.after) lastNegative=query
                }
                val r=com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6SnapshotFromTestOnly()
                val terminal=if(binding.role=="source") "source_actual_ack_identity_persisted" else if(binding.category in setOf("create","update")) "peer_expected_product_draw" else "peer_expected_entity_draw"
                if(r?.events?.any { it.stage.endsWith("threw_unconfirmed") }==true || r?.invalidReason!=null) break
                if(causal && tracePreviewHash==null) {
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6CausalTracePreviewFromTestOnly()?.let { preview ->
                        tracePreviewHash=exportEnvelope(tracePreview,JSONObject(preview.toString()),"CAUSAL_TRACE_PREVIEW")
                    }
                }
                if(causal && binding.role=="peer" && firstPositive!=null && B6PublishedFile.isPublished(visibilityConfirmation) && visibilityChallengeNS==null) {
                    check(tracePreviewHash!=null) { "B6_VISIBILITY_CHALLENGE_BEFORE_COMMIT_PROOF" }
                    exactChallenge(visibilityConfirmation,requireNotNull(binding.visibilityConfirmationNonce))
                    visibilityChallengeNS=android.os.SystemClock.elapsedRealtimeNanos().also { observed ->
                        check(observed>requireNotNull(firstPositive).endNS) { "B6_VISIBILITY_CHALLENGE_NOT_AFTER_ORIGINAL_POSITIVE" }
                    }
                }
                if(causal && visible!=null && visibilityChallengeNS!=null && firstPositive!=null && visibilityPreviewHash==null) {
                    val confirmation=b6FreshVisibleConfirmation(visible,binding.category,dir,
                        requireNotNull(visibilityChallengeNS),end,::recordQuery)
                    causalConfirmation=JSONObject(confirmation.toString()).put("challenge_observed_ns",visibilityChallengeNS)
                        .put("nonce",binding.visibilityConfirmationNonce)
                    val snapshot=requireNotNull(com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6SnapshotFromTestOnly())
                    val original=JSONObject(nativeJSON.encodeToString(
                        com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Receipt.serializer(),snapshot))
                        .put("status","OBSERVATIONS_ONLY_CAUSAL_PREVIEW").put("authority_current_at_preview",current())
                        .put("visible_outcome",visibleOutcome).put("last_negative_begin_ns",lastNegative?.beginNS ?: JSONObject.NULL)
                        .put("last_negative_end_ns",lastNegative?.endNS ?: JSONObject.NULL)
                        .put("first_positive_begin_ns",firstPositive.beginNS)
                        .put("first_positive_end_ns",firstPositive.endNS)
                        .put("fixture_row_screenshot",File(dir,"peer-row.png").path)
                    val originalRaw=original.toString();val logRaw=requireNotNull(sealQueryLog()).toString(Charsets.UTF_8)
                    val preview=JSONObject().put("kind","B6_CAUSAL_POSITIVE_PREVIEW").put("timing_method",binding.timingMethod)
                        .put("operation_id",binding.operationID).put("manifest_sha256",binding.manifestHash)
                        .put("nonce",binding.visibilityConfirmationNonce).put("challenge_observed_ns",visibilityChallengeNS)
                        .put("identity",JSONObject().put("clock_id",reply.clockID).put("participant_pid",reply.participantPID)
                            .put("participant_boot_sha256",reply.bootHash).put("artifact_sha256",reply.artifactHash)
                            .put("device_sha256",reply.deviceHash).put("ordinary_preflight_sha256",reply.preflightHash))
                        .put("original_visibility_receipt_raw",originalRaw).put("original_visibility_receipt_sha256",liveHash(originalRaw.encodeToByteArray()))
                        .put("confirmation",confirmation).put("query_log_raw",logRaw).put("query_log_sha256",liveHash(logRaw.encodeToByteArray()))
                    visibilityPreviewHash=exportEnvelope(positivePreview,preview,"CAUSAL_POSITIVE_PREVIEW")
                }
                val causalReady=!causal || tracePreviewHash!=null &&
                    (if(binding.role=="source") sourceWitnessHash!=null else visibilityPreviewHash!=null)
                val completion=if(causal) com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6SnapshotFromTestOnly() else r
                if(completion?.events?.any { it.stage==terminal }==true && completion.invalidReason==null &&
                    (visible==null || firstPositive!=null) && causalReady) break
                Thread.sleep(if(visible==null) 20 else 100)
            }
            singleSave?.requireAttempted(reply,sourceWitness,end)
        } catch (error: Throwable) {
            primaryFailure=error
        } finally {
          try {
            if(hooks.observer===observer) hooks.observer=null
            val r=requireNotNull(com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6SnapshotFromTestOnly(disarm=true))
            // Preserve every raw event even when final calibration/auth/host exchange fails.
            sealQueryLog() // Also seal a failure prefix exactly once before any final receipt.
            B6PublishedFile.publish(File(dir,"observations-before-final-calibration.json"),
                nativeJSON.encodeToString(
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Receipt.serializer(),r).encodeToByteArray())
            val finalNonce=requireNotNull(args.getString("task144B6FinalNonce")).also {
                require(UUID.fromString(it).toString()==it && it!=binding.nonce)
            }
            val finalRequest=File(dir,"calibration-nonce-after.txt")
            emit(JSONObject().put("stage","READY_FOR_FINAL_NONCE").put("operation_id",binding.operationID)
                .put("request_path",finalRequest.path))
            val finalWaitEnd=android.os.SystemClock.elapsedRealtimeNanos()+30_000_000_000L
            while(!B6PublishedFile.isPublished(finalRequest) && android.os.SystemClock.elapsedRealtimeNanos()<finalWaitEnd) Thread.sleep(20)
            require(B6PublishedFile.requireRead(finalRequest,64).contentEquals(finalNonce.encodeToByteArray()) && current())
            val finalBoot=File("/proc/sys/kernel/random/boot_id").readText().trim()
            UUID.fromString(finalBoot)
            val finalReply=requireNotNull(com.example.merchandisecontrolsplitview.data.PilotCommitTrace.b6CalibrationFromTestOnly(
                finalNonce,liveHash(finalBoot.encodeToByteArray())))
            val finalReplyJSON=JSONObject(kotlinx.serialization.json.Json.encodeToString(
                com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Reply.serializer(),finalReply))
            emit(JSONObject(finalReplyJSON.toString()).put("stage","FINAL_CALIBRATION_REPLY"))
            val body=nativeJSON.encodeToString(
                com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Receipt.serializer(),r)
            val terminal=if(binding.role=="source") "source_actual_ack_identity_persisted" else if(binding.category in setOf("create","update")) "peer_expected_product_draw" else "peer_expected_entity_draw"
            fun causalPrefixMatches(file:File,expectedHash:String?):Boolean {
                if(expectedHash==null) return false
                val bytes=B6PublishedFile.read(file,524_288) ?: return false
                if(liveHash(bytes)!=expectedHash) return false
                val value=JSONObject(bytes.toString(Charsets.UTF_8))
                val count=value.getInt("trace_event_count")
                if(count !in 1..r.events.size) return false
                val prefixRaw=value.getString("trace_prefix_raw")
                val prefix=kotlinx.serialization.json.Json.parseToJsonElement(prefixRaw)
                val finalEvents=nativeJSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(
                    com.example.merchandisecontrolsplitview.data.PilotCommitTrace.B6Event.serializer()),r.events.take(count))
                return liveHash(prefixRaw.encodeToByteArray())==value.getString("trace_prefix_sha256") &&
                    prefix==kotlinx.serialization.json.Json.parseToJsonElement(finalEvents)
            }
            val publishedPositive=if(visibilityPreviewHash!=null) B6PublishedFile.read(positivePreview,524_288) else null
            val publishedQueries=sealQueryLog()
            val causalComplete=!causal || tracePreviewHash!=null && causalPrefixMatches(tracePreview,tracePreviewHash) &&
                (if(binding.role=="source") sourceWitnessHash!=null && causalPrefixMatches(sourceWitness,sourceWitnessHash) &&
                    r.events.count { it.stage=="source_ui_action_begin" }==1
                else visibilityPreviewHash!=null && publishedPositive!=null &&
                    liveHash(publishedPositive)==visibilityPreviewHash && causalConfirmation!=null && publishedQueries!=null &&
                    JSONObject(publishedPositive.toString(Charsets.UTF_8)).getString("query_log_sha256")==liveHash(publishedQueries))
            val complete=primaryFailure==null && hooks.observationError==null && current() && causalComplete &&
                r.invalidReason==null && r.events.any { it.stage==terminal } &&
                r.events.none { it.stage.endsWith("threw_unconfirmed") } && (visible==null || firstPositive!=null)
            val receipt=JSONObject(body).put("postCalibration",finalReplyJSON).put("stage","B6_NATIVE_OBSERVATIONS").put("authority_current_at_end",current())
                .put("status",if(complete) "OBSERVATION_ONLY_QUALIFIED_READBACK_AND_REVIEW_REQUIRED" else "FAIL_OBSERVATION_INCOMPLETE")
                .put("visible_outcome",visibleOutcome)
                .put("primary_error_type",primaryFailure?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("primary_error_code",primaryFailure?.message?.takeIf { it in setOf(
                    "AX_TREE_OVERFLOW","BASELINE_NOT_VISIBLE_OR_ALREADY_NEW","SCREENSHOT_UNAVAILABLE") } ?: JSONObject.NULL)
                .put("observer_error",hooks.observationError?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) } ?: JSONObject.NULL)
                .put("fixture_row_screenshot",File(dir,"peer-row.png").let { if(B6PublishedFile.isPublished(it)) it.path else JSONObject.NULL })
                .put("last_negative_begin_ns",lastNegative?.beginNS ?: JSONObject.NULL)
                .put("last_negative_end_ns",lastNegative?.endNS ?: JSONObject.NULL)
                .put("first_positive_begin_ns",firstPositive?.beginNS ?: JSONObject.NULL)
                .put("first_positive_end_ns",firstPositive?.endNS ?: JSONObject.NULL)
            if(causal) {
                receipt.put("timing_method",binding.timingMethod).put("causal_trace_preview_sha256",tracePreviewHash ?: JSONObject.NULL)
                if(binding.role=="source") receipt.put("causal_source_witness_sha256",sourceWitnessHash ?: JSONObject.NULL)
                else receipt.put("causal_visibility_preview_sha256",visibilityPreviewHash ?: JSONObject.NULL)
                    .put("causal_confirmation",causalConfirmation ?: JSONObject.NULL)
                    .put("causal_query_log_path",if(publishedQueries!=null) queryLog.path else JSONObject.NULL)
                    .put("causal_query_log_sha256",publishedQueries?.let(::liveHash) ?: JSONObject.NULL)
            }
            val receiptFile=File(dir,"receipt.json")
            val receiptBytes=receipt.toString().encodeToByteArray()
            if(causal) require(receiptBytes.size<=262_144) { "B6_CAUSAL_FINAL_OVERFLOW" }
            B6PublishedFile.publish(receiptFile,receiptBytes)
            if(causal) emit(JSONObject().put("stage","B6_NATIVE_OBSERVATIONS").put("operation_id",binding.operationID)
                .put("path",receiptFile.path).put("sha256",liveHash(receiptBytes)).put("status",receipt.getString("status")))
            else emit(receipt)
            observationComplete=complete
          } catch (exportFailure: Throwable) {
            val original=primaryFailure
            if(original==null) primaryFailure=exportFailure else if(original!==exportFailure) original.addSuppressed(exportFailure)
            // A failed receipt export must not replace the causal exception or imply a successful observation.
            val failure=JSONObject().put("stage","B6_NATIVE_OBSERVATION_EXPORT_FAILED")
                .put("primary_error_type",primaryFailure.javaClass.simpleName)
                .put("export_error_type",exportFailure.javaClass.simpleName).put("native_acceptance",false)
            try { emit(failure) } catch (emitFailure: Throwable) {
                primaryFailure.let { if(it!==emitFailure) it.addSuppressed(emitFailure) }
            }
          }
        }
        try { singleSave?.restoreWindowObservation() } catch (restoreFailure: Throwable) {
            val original=primaryFailure
            if(original==null) primaryFailure=restoreFailure else original.addSuppressed(restoreFailure)
        }
        primaryFailure?.let { throw it }
        check(observationComplete) { "B6_OBSERVATION_INCOMPLETE" }
    }

    /** Uses the ordinary target app session and guarded adapter. Default native runs skip before auth. */
    @Test
    fun task144R4ConditionalCatalogWriteWhenEnabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live CAS is explicitly gated", args.getString("task144LiveCas") == "true")
        val readOnlyAuthority = args.getString("task144CasReadOnlyAuthority") == "true"
        require(instrumentation.javaClass.name == "androidx.test.runner.AndroidJUnitRunner") {
            "Ordinary app instrumentation required; performance/controlled runner is not admitted"
        }
        val receipt = JSONObject().put("kind", if (readOnlyAuthority) "task144_read_only_premutation_authority" else "task144_live_catalog_cas")
            .put("status", if (readOnlyAuthority) "STARTED_READ_ONLY_PREMUTATION_AUTHORITY_ONLY" else "STARTED")
            .put("cleanup", "NOT_RUN").put("physical_residue", "NOT_ASSESSED")
        if (readOnlyAuthority) receipt.put("cas_mutations", "NOT_RUN_READ_ONLY_AUTHORITY_PROBE").put("cas_result", "NO_CAS_PASS")
        var evidenceFile: File? = null
        val operations = org.json.JSONArray()
        fun record(stage: String) {
            receipt.put("stage", stage)
            evidenceFile?.let { nativeDurableWrite(it, receipt.toString().encodeToByteArray()) }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("TASK144_LIVE_CAS", receipt.toString())
            })
        }
        suspend fun <T> observed(operation: String, phase: String, block: suspend () -> T): T {
            val started = android.os.SystemClock.elapsedRealtime()
            val entry = JSONObject().put("operation", operation).put("phase", phase)
                .put("attempt", operations.length() + 1).put("started_at_epoch_ms", System.currentTimeMillis())
            operations.put(entry)
            receipt.put("operations", operations)
            record("${phase}_dispatched")
            try {
                return block().also { entry.put("outcome", "returned") }
            } catch (error: Throwable) {
                entry.put("outcome", "failed")
                liveErrorFields(error, entry)
                throw error
            } finally {
                entry.put("ended_at_epoch_ms", System.currentTimeMillis())
                    .put("duration_ms", android.os.SystemClock.elapsedRealtime() - started)
                record("${phase}_terminal")
            }
        }
        try {
            val path = requireNotNull(args.getString("task144CasManifestPath"))
            val expectedHash = requireNotNull(args.getString("task144CasManifestSha256"))
            require(expectedHash.matches(Regex("[0-9a-f]{64}")))
            val manifestFile = File(path)
            require(manifestFile.length() in 1L..65_536L)
            val bytes = manifestFile.readBytes()
            require(liveHash(bytes) == expectedHash)
            val manifest = JSONObject(bytes.decodeToString())
            require(manifest.getString("status") == "AUTHORIZED_FOR_NATIVE_CAS")
            if (readOnlyAuthority) {
                val runNonce = manifest.getString("rootRunNonce")
                require(UUID.fromString(runNonce).toString() == runNonce)
                receipt.put("root_run_nonce", runNonce)
            }
            val target = manifest.getJSONObject("target")
            require(target.getString("projectRef") == "jpgoimipbothfgkokyvm")
            require(target.getString("exactProjectUrlSha256") == LIVE_TEST_PROJECT_HASH)
            require(liveHash(BuildConfig.SUPABASE_URL.encodeToByteArray()) == LIVE_TEST_PROJECT_HASH)
            val fixtures = manifest.getJSONArray("fixtures")
            val own = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
                .single { it.getString("platform") == "android" }
            val id = own.getString("productId").also { require(UUID.fromString(it).toString() == it) }
            val barcode = own.getString("barcode")
            require(barcode.startsWith("TASK144_R4_CAS_") && barcode.length < 100 &&
                barcode.matches(Regex("[A-Z0-9_a-f]+")))
            require(!own.getBoolean("created") && listOf("parents", "prices", "history").all { own.getJSONArray(it).length() == 0 })
            val initial = own.getJSONObject("initial")
            val chosen = own.getJSONObject("chosen")
            val chosenName = chosen.getString("productName")
            val staleName = own.getString("staleRejectedName")
            require(initial.get("secondProductName") is String && initial.getString("secondProductName").isNotEmpty())
            require(chosen.isNull("secondProductName") && chosenName != initial.getString("productName") && staleName != chosenName)
            val app = instrumentation.targetContext.applicationContext as MerchandiseControlApplication
            require(app.javaClass == MerchandiseControlApplication::class.java)
            require(app.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "Split artifact requires explicit qualification" }
            val apkHash = File(app.applicationInfo.sourceDir).inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1_024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            require(apkHash == target.getString("nativeArtifactHash"))
            val signedIn = requireNotNull(signedInState(app)) { "Existing ordinary app session required" }
            require(requireReadyLocalStore(app, signedIn)) { "Ordinary app cloud scope is not ready" }
            val owner = requireNotNull(app.supabaseClient as? GenerationOwnedSupabaseClient)
            val sdk = owner.captureClient()
            val sdkStatus = sdk.auth.sessionStatus.value as? SessionStatus.Authenticated ?: error("Session not authenticated")
            val context = app.shopContextRepository.state.value
            val selected = requireNotNull(context.selectedShop)
            val shopEpoch = app.shopContextRepository.diagnosticShopEpoch()
            val scope = app.catalogSyncStateTracker.businessDataScopeState.value
            val deviceId = requireNotNull(app.deviceInstallIdProvider.currentId)
            require(liveHash(signedIn.userId.lowercase().encodeToByteArray()) == target.getString("ownerHash"))
            require(liveHash(selected.shopId.lowercase().encodeToByteArray()) == target.getString("shopHash"))
            require(liveHash(deviceId.encodeToByteArray()) == target.getString("deviceHash"))
            val authorityReasons = linkedMapOf(
                "sdk_same" to "AUTHORITY_SDK_REFERENCE_CHANGED",
                "app_auth_same" to "AUTHORITY_APP_AUTH_CHANGED",
                "sdk_auth_same" to "AUTHORITY_SDK_AUTH_REFERENCE_CHANGED",
                "ttl95_ok" to "AUTHORITY_TTL95_INSUFFICIENT",
                "sdk_owner_same" to "AUTHORITY_SDK_OWNER_CHANGED",
                "shop_context_same" to "AUTHORITY_SHOP_CONTEXT_CHANGED",
                "shop_epoch_same" to "AUTHORITY_SHOP_EPOCH_CHANGED",
                "captured_shop_not_loading" to "AUTHORITY_CAPTURED_SHOP_LOADING",
                "captured_shop_sync_allowed" to "AUTHORITY_CAPTURED_SHOP_SYNC_DISALLOWED",
                "captured_shop_owner_same" to "AUTHORITY_CAPTURED_SHOP_OWNER_CHANGED",
                "scope_value_same" to "AUTHORITY_BUSINESS_SCOPE_VALUE_CHANGED",
                "captured_scope_allows_cloud" to "AUTHORITY_CAPTURED_SCOPE_CLOUD_DISALLOWED",
                "business_scope_same" to "AUTHORITY_BUSINESS_SCOPE_IDENTITY_CHANGED",
                "device_same" to "AUTHORITY_DEVICE_CHANGED")
            fun requireCurrent() {
                // Each term is evaluated once, at its original short-circuit position. Null means NOT_EVALUATED.
                val facts = JSONObject()
                authorityReasons.keys.forEach { facts.put(it, JSONObject.NULL) }
                var firstFalseStaticReason: String? = null
                fun fact(key: String, originalTerm: () -> Boolean): Boolean {
                    val actual = originalTerm()
                    facts.put(key, actual)
                    if (!actual && firstFalseStaticReason == null) firstFalseStaticReason = authorityReasons.getValue(key)
                    return actual
                }
                check(fact("sdk_same") { owner.captureClientOrNull() === sdk } &&
                    fact("app_auth_same") { app.authManager.state.value == signedIn } &&
                    fact("sdk_auth_same") { sdk.auth.sessionStatus.value === sdkStatus } &&
                    fact("ttl95_ok") { sdkStatus.session.expiresAt > Clock.System.now() + 95.seconds } &&
                    fact("sdk_owner_same") { sdk.auth.currentUserOrNull()?.id == signedIn.userId } &&
                    fact("shop_context_same") {
                        val currentContext = app.shopContextRepository.state.value
                        currentContext == context
                    } &&
                    fact("shop_epoch_same") { app.shopContextRepository.diagnosticShopEpoch() == shopEpoch } &&
                    fact("captured_shop_not_loading") { !context.isLoading } &&
                    fact("captured_shop_sync_allowed") { context.syncAllowed } &&
                    fact("captured_shop_owner_same") { context.ownerUserId == signedIn.userId } &&
                    fact("scope_value_same") {
                        val currentScope = app.catalogSyncStateTracker.businessDataScopeState.value
                        currentScope == scope
                    } &&
                    fact("captured_scope_allows_cloud") { scope.allowsCloudSync } &&
                    fact("business_scope_same") { app.catalogSyncStateTracker.allowsBusinessDataScope(signedIn.userId, selected) } &&
                    fact("device_same") { app.deviceInstallIdProvider.currentId == deviceId }) {
                    receipt.put("authority_last_evaluation", facts)
                        .put("first_false_static_reason", requireNotNull(firstFalseStaticReason))
                    "CAS_CURRENT_AUTHORITY_CHECK_FALSE"
                }
            }
            suspend fun read(column: String = "id", value: String = id, phase: String = "readback"): List<InventoryProductRow> {
                requireCurrent()
                val rows = observed("inventory_products_${column}_select", phase) {
                    sdk.postgrest["inventory_products"].select(Columns.raw(LIVE_PRODUCT_COLUMNS)) {
                        filter { eq(column, value); eq("owner_user_id", signedIn.userId); eq("shop_id", selected.shopId) }
                        limit(3)
                    }.decodeList<InventoryProductRow>()
                }
                // Record the actual SDK result before the original post-read authority guard can throw.
                receipt.put("last_read_row_count", rows.size).put("last_read_column", column).put("last_read_phase", phase)
                operations.getJSONObject(operations.length() - 1).put("returned_row_count", rows.size)
                requireCurrent()
                return rows
            }
            requireCurrent()
            val root = File(app.filesDir, "task144-native-cas-${expectedHash.take(16)}")
            require(root.mkdir()) { "Existing run evidence must be preserved" }
            evidenceFile = File(root, "receipt.json")
            receipt.put("manifest_sha256", expectedHash).put("artifact_sha256", apkHash)
                .put("project_sha256", LIVE_TEST_PROJECT_HASH).put("owner_sha256", target.getString("ownerHash"))
                .put("shop_sha256", target.getString("shopHash")).put("device_sha256", target.getString("deviceHash"))
                .put("fixture_id", id)
            record("collision_read")
            val collisionFacts = JSONObject().put("fixture_id_empty", JSONObject.NULL).put("fixture_barcode_empty", JSONObject.NULL)
            receipt.put("collision_last_evaluation", collisionFacts)
            suspend fun collisionFact(key: String, originalTerm: suspend () -> Boolean): Boolean {
                val actual = originalTerm()
                collisionFacts.put(key, actual)
                if (!actual) receipt.put("first_false_static_reason", when (key) {
                    "fixture_id_empty" -> "FIXTURE_ID_COLLISION"
                    "fixture_barcode_empty" -> "BARCODE_COLLISION"
                    else -> error("CAS_UNKNOWN_COLLISION_FACT")
                })
                return actual
            }
            check(collisionFact("fixture_id_empty") { read(phase = "collision_id").isEmpty() } &&
                collisionFact("fixture_barcode_empty") { read("barcode", barcode, phase = "collision_barcode").isEmpty() }) {
                "CAS_OWNED_FIXTURE_COLLISION"
            }
            if (readOnlyAuthority) {
                requireCurrent()
                receipt.put("status", "READ_ONLY_PREMUTATION_AUTHORITY_ONLY")
                    .put("cas_mutations", "NOT_RUN_READ_ONLY_AUTHORITY_PROBE").put("cas_result", "NO_CAS_PASS")
                record("readonly_collision_checks_completed_no_mutation")
                return@runBlocking
            }
            val remote = app.catalogRemoteDataSource
            val create = InventoryProductRow(id, signedIn.userId, selected.shopId, barcode,
                productName = initial.getString("productName"), secondProductName = initial.getString("secondProductName"))
            receipt.put("owned_remote_row_may_remain", true)
            record("create_dispatched")
            requireCurrent()
            observed("guarded_inventory_products_upsert", "fixture_create") {
                remote.upsertProducts(listOf(create), selected.shopId).getOrThrow()
            }
            val first = read().single()
            check(first.id == id && first.ownerUserId == signedIn.userId && first.shopId == selected.shopId &&
                first.barcode == barcode && first.productName == create.productName && first.secondProductName == create.secondProductName && first.deletedAt == null)
            val v0 = requireNotNull(first.updatedAt)
            java.time.OffsetDateTime.parse(v0)
            receipt.put("v0", v0)
            record("cas_one_dispatched")
            requireCurrent()
            val patch = InventoryProductPatch(setOf("productname", "secondproductname"), productName = chosenName)
            val accepted = observed("guarded_inventory_products_conditional_patch", "cas_one") {
                remote.patchProductConditionally(id, signedIn.userId, selected.shopId, patch, v0, null).getOrThrow()
            }.single()
            requireCurrent()
            check(accepted.id == id && accepted.ownerUserId == signedIn.userId && accepted.shopId == selected.shopId &&
                accepted.deletedAt == null && accepted.productName == chosenName && accepted.secondProductName == null && accepted.updatedAt != v0)
            java.time.OffsetDateTime.parse(requireNotNull(accepted.updatedAt))
            receipt.put("cas_one_rows", 1).put("v1", accepted.updatedAt)
            record("cas_stale_dispatched")
            requireCurrent()
            val rejected = observed("guarded_inventory_products_conditional_patch", "cas_stale") {
                remote.patchProductConditionally(id, signedIn.userId, selected.shopId,
                    InventoryProductPatch(setOf("productname"), productName = staleName), v0, null).getOrThrow()
            }
            requireCurrent()
            check(rejected.isEmpty())
            receipt.put("cas_stale_rows", 0)
            val after = read().single()
            check(after.updatedAt == accepted.updatedAt && after.deletedAt == null && after.productName == chosenName && after.secondProductName == null)
            receipt.put("readback_selected_fields_match", true)
            // A contradictory owner predicate, under the same ordinary session/device authorization,
            // can match no owned fixture. This is a scope-predicate test, not foreign-record RLS acceptance.
            record("scope_negative_dispatched")
            val foreignOwner = UUID.randomUUID().toString()
            val negative = observed("guarded_inventory_products_conditional_patch", "scope_negative") {
                remote.patchProductConditionally(id, foreignOwner, selected.shopId,
                    InventoryProductPatch(setOf("productname"), productName = staleName), requireNotNull(accepted.updatedAt), null).getOrThrow()
            }
            requireCurrent()
            check(negative.isEmpty())
            val finalRow = read().single()
            check(finalRow.updatedAt == accepted.updatedAt && finalRow.productName == chosenName && finalRow.secondProductName == null)
            receipt.put("scope_predicate_negative", true).put("rls_foreign_scope_acceptance", "NOT_RUN")
                .put("status", "PASS_CAS_BOUNDARY_ONLY")
            record("completed_retained_for_peer_readback")
        } catch (error: Throwable) {
            receipt.put("status", "FAIL")
            receipt.put("failure_reason", receipt.optString("first_false_static_reason", "OTHER_SANITIZED_UNCLASSIFIED"))
            liveErrorFields(error, receipt)
            record(receipt.optString("stage", "preflight"))
            throw AssertionError("TASK144 live CAS failed; sanitized stage/error type are in its receipt. No automatic cleanup or retry.")
        }
    }

    @Test
    fun authSessionOwnerHashWhenEnabled() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val task103Enabled = args.getString("task103AuthPreflight")?.lowercase()
        val task104Enabled = args.getString("task104Pass2AuthPreflight")?.lowercase()
        val task112Enabled = args.getString("task112AuthPreflight")?.lowercase()
        assumeTrue(
            "Live auth preflight is gated. Pass -e task103AuthPreflight true, -e task104Pass2AuthPreflight true or -e task112AuthPreflight true.",
            isEnabled(task103Enabled) || isEnabled(task104Enabled) || isEnabled(task112Enabled)
        )
        require(InstrumentationRegistry.getInstrumentation().javaClass.name == "androidx.test.runner.AndroidJUnitRunner") {
            "Ordinary app instrumentation required; controlled runner cannot qualify app authentication"
        }

        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("secret_key"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("sb_secret"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        require(app.javaClass == MerchandiseControlApplication::class.java)

        val signedIn = signedInState(app) ?: run {
            val sessionFile = args.getString("task072DSessionFile") ?: args.getString("task072SessionFile")
            if (!sessionFile.isNullOrBlank()) {
                importTemporarySession(app, sessionFile)
            }
            signedInState(app)
        } ?: throw AssertionError("TASK-103 Android auth preflight requires signed-in session")

        val ordinaryScopeReady = requireReadyLocalStore(app, signedIn)
        assertTrue(app.catalogRemoteDataSource.isConfigured)
        assertTrue(app.productPriceRemoteDataSource.isConfigured)

        val label = when {
            isEnabled(task112Enabled) -> "TASK112_ANDROID_AUTH_PREFLIGHT"
            isEnabled(task104Enabled) -> "TASK104_PASS2_ANDROID_AUTH_PREFLIGHT"
            else -> "TASK103_ANDROID_AUTH_PREFLIGHT"
        }
        println(
            "$label " +
                "project_hash=${task103Hash(BuildConfig.SUPABASE_URL)} " +
                "owner_hash=${task103Hash(signedIn.userId)} " +
                "signed_in=true"
        )
        if (args.getString("task144CasPreflightReceipt") == "true") {
            val owner = app.supabaseClient as? GenerationOwnedSupabaseClient
            val sdk = owner?.captureClientOrNull()
            val auth = sdk?.auth?.sessionStatus?.value as? SessionStatus.Authenticated
            val context = app.shopContextRepository.state.value
            val selected = context.selectedShop
            val epoch = app.shopContextRepository.diagnosticShopEpoch()
            val device = app.deviceInstallIdProvider.currentId
            fun currentIdentity() = owner != null && sdk != null && auth != null && device != null &&
                owner.captureClientOrNull() === sdk && app.authManager.state.value == signedIn &&
                sdk.auth.sessionStatus.value === auth && auth.session.expiresAt > Clock.System.now() + 5.seconds &&
                sdk.auth.currentUserOrNull()?.id == signedIn.userId && app.shopContextRepository.state.value == context &&
                app.shopContextRepository.diagnosticShopEpoch() == epoch && context.ownerUserId == signedIn.userId &&
                !context.isLoading && context.syncAllowed && selected != null &&
                app.catalogSyncStateTracker.allowsBusinessDataScope(signedIn.userId, selected) &&
                app.deviceInstallIdProvider.currentId == device
            val proof = JSONObject().put("kind", "task144_native_session_preflight").put("signed_in", true)
                .put("runner", InstrumentationRegistry.getInstrumentation().javaClass.name)
                .put("application", app.javaClass.name)
                .put("project_sha256", liveHash(BuildConfig.SUPABASE_URL.encodeToByteArray()))
                .put("owner_sha256", liveHash(signedIn.userId.lowercase().encodeToByteArray()))
                .put("scope_qualification", "NOT_READY")
            if (ordinaryScopeReady && selected != null && currentIdentity()) {
                val b6ScopeHash = liveHash((com.example.merchandisecontrolsplitview.data.task126OwnerHash(signedIn.userId)+"\nshop:"+selected.shopId).encodeToByteArray())
                proof.put("scope_qualification", "LOCAL_SCOPE_CAPTURED_REMOTE_DEVICE_NOT_CHECKED")
                    .put("shop_sha256", liveHash(selected.shopId.lowercase().encodeToByteArray()))
                    .put("device_sha256", liveHash(requireNotNull(device).encodeToByteArray()))
                    .put("b6_scope_sha256", b6ScopeHash)
            } else {
                proof.put("readiness", JSONObject()
                    .put("scope_ready_within_budget", ordinaryScopeReady)
                    .put("sdk_authenticated", auth != null)
                    .put("device_present", device != null)
                    .put("shop_selected", selected != null)
                    .put("shop_owner_matches", context.ownerUserId == signedIn.userId)
                    .put("shop_loading", context.isLoading)
                    .put("shop_sync_allowed", context.syncAllowed)
                    .put("cloud_scope_allowed", app.catalogSyncStateTracker.businessDataScopeState.value.allowsCloudSync)
                    .put("identity_current", currentIdentity()))
            }
            if (proof.has("b6_scope_sha256")) require(currentIdentity()) {
                "Ordinary app session scope changed before receipt"
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("TASK144_NATIVE_SESSION_PREFLIGHT", proof.toString())
            })
        }
    }

    private fun task103Hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private suspend fun requireReadyLocalStore(app: MerchandiseControlApplication, signedIn: AuthState.SignedIn): Boolean {
        // Setup totals 30s before READY; Room keeps its original 15s sub-budget.
        val ready = withTimeoutOrNull(30_000) {
            val availability = withTimeoutOrNull(15_000) {
                app.localStoreAvailability.first { it !is LocalStoreAvailability.Checking }
            } ?: app.localStoreAvailability.value
            require(availability is LocalStoreAvailability.Ready) { "Ordinary app local store is not ready; no business getter was accessed" }
            val tracker = app.catalogSyncStateTracker
            kotlinx.coroutines.flow.combine(app.authManager.state, app.shopContextRepository.state,
                tracker.businessDataScopeState) { _, _, _ ->
                // Recapture each identity; neither this wait nor a previous emission grants authority.
                val owner = app.supabaseClient as? GenerationOwnedSupabaseClient
                val sdk = owner?.captureClientOrNull()
                val auth = sdk?.auth?.sessionStatus?.value as? SessionStatus.Authenticated
                val context = app.shopContextRepository.state.value
                val selected = context.selectedShop
                val epoch = app.shopContextRepository.diagnosticShopEpoch()
                val scope = tracker.businessDataScopeState.value
                val device = app.deviceInstallIdProvider.currentId
                owner != null && sdk != null && auth != null && device != null && selected != null &&
                    owner.captureClientOrNull() === sdk && app.authManager.state.value == signedIn &&
                    sdk.auth.sessionStatus.value === auth && auth.session.expiresAt > Clock.System.now() + 5.seconds &&
                    sdk.auth.currentUserOrNull()?.id == signedIn.userId && app.shopContextRepository.state.value == context &&
                    app.shopContextRepository.diagnosticShopEpoch() == epoch && context.ownerUserId == signedIn.userId &&
                    !context.isLoading && context.syncAllowed && tracker.businessDataScopeState.value == scope &&
                    scope.allowsCloudSync && tracker.allowsBusinessDataScope(signedIn.userId, selected) &&
                    app.deviceInstallIdProvider.currentId == device
            }.first { it }
        }
        require(app.localStoreAvailability.value is LocalStoreAvailability.Ready) {
            "Ordinary app local store is not ready; no business getter was accessed"
        }
        return ready == true // Auth may still report SignedIn/NOT_READY; B6 and CAS require true.
    }

    private suspend fun signedInState(app: MerchandiseControlApplication): AuthState.SignedIn? {
        app.authManager.restoreSession()
        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        return authState as? AuthState.SignedIn
    }

    private suspend fun importTemporarySession(
        app: MerchandiseControlApplication,
        sessionFilePath: String
    ) {
        val file = File(sessionFilePath)
        if (!file.exists()) {
            throw AssertionError("TASK-072D task072DSessionFile does not exist")
        }

        val json = JSONObject(file.readText())
        val access = json.getString("access")
        val refresh = json.getString("refresh")
        if (access.isBlank() || refresh.isBlank()) {
            throw AssertionError("TASK-072D task072DSessionFile is malformed")
        }

        app.supabaseClient?.auth?.importAuthToken(
            accessToken = access,
            refreshToken = refresh,
            retrieveUser = true,
            autoRefresh = true
        ) ?: throw AssertionError("Supabase client missing")
        file.delete()
    }

    private fun isEnabled(value: String?): Boolean =
        value == "1" || value == "true"

    private fun liveHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun liveErrorFields(error: Throwable, target: JSONObject) {
        target.put("error_type", error.javaClass.simpleName).put("http_status", "UNKNOWN_NOT_EXPOSED")
            .put("sqlstate", "UNKNOWN_NOT_EXPOSED")
        if (error is RestException) target.put("http_status", error.statusCode)
        if (error is PostgrestRestException) error.code?.takeIf { it.matches(Regex("[0-9A-Z]{5}")) }
            ?.let { target.put("sqlstate", it) }
    }

    private companion object {
        const val LIVE_TEST_PROJECT_HASH = "42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842"
        const val LIVE_PRODUCT_COLUMNS = "id,owner_user_id,shop_id,barcode,item_number,product_name,second_product_name,purchase_price,retail_price,supplier_id,category_id,stock_quantity,updated_at,deleted_at,primary_image_version_id,primary_image_updated_at"
    }
}

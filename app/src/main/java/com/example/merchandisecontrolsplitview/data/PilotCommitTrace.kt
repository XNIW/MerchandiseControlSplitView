package com.example.merchandisecontrolsplitview.data

import android.os.SystemClock
import com.example.merchandisecontrolsplitview.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** External candidate. Default OFF; only the separately reviewed androidTest driver may arm it. */
internal object PilotCommitTrace {
    data class Binding(
        val enabled: Boolean, val runID: String, val sampleID: String,
        val operationID: String, val nonce: String, val scopeHash: String,
        val entityHash: String, val oldNameHash: String, val newNameHash: String,
        val mappingProofHash: String
    )
    data class Facts(
        val scopeHash: String, val entityHash: String, val oldNameHash: String,
        val newNameHash: String, val nameOnly: Boolean,
        val beforeRevision: Int?, val beforeSyncedRevision: Int?, val afterRevision: Int?,
        val baselinePresent: Boolean
    )
    data class Event(val stage: String, val ns: Long)
    data class Receipt(val binding: Binding, val clockID: String, val events: List<Event>,
                       val facts: Facts?, val invalidReason: String?, val overflow: Boolean)
    data class CalibrationReply(val nonce: String, val clockID: String, val remoteNS: Long)
    @ConsistentCopyVisibility
    data class Ticket internal constructor(val operationID: String)
    private const val CAPACITY = 8
    private val clockID = "android-app-${android.os.Process.myPid()}-${UUID.randomUUID()}"
    private var binding: Binding? = null
    private var facts: Facts? = null
    private var invalid: String? = null
    private var overflow = false
    private var begun = false
    private var ended = false
    private var exportReady = false
    private val events = ArrayList<Event>(CAPACITY)
    private val consumed = HashSet<String>()

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun now(): Long = SystemClock.elapsedRealtimeNanos()
    private fun isHash(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }
    fun valid(b: Binding): Boolean = b.enabled && b.runID.startsWith("R2-PILOT-")
        && b.runID.length <= 80 && b.sampleID == "PILOT-A2I-NAME"
        && runCatching { UUID.fromString(b.operationID); UUID.fromString(b.nonce) }.isSuccess
        && listOf(b.scopeHash,b.entityHash,b.oldNameHash,b.newNameHash,b.mappingProofHash).all(::isHash)
        && b.oldNameHash != b.newNameHash

    @Synchronized fun armFromTestOnly(b: Binding): CalibrationReply? {
        if (!BuildConfig.DEBUG || !valid(b) || binding != null || consumed.size >= 2 || b.operationID in consumed) return null
        consumed.add(b.operationID); binding = b
        return CalibrationReply(b.nonce, clockID, now())
    }
    @Synchronized fun currentTicket(): Ticket? =
        if (BuildConfig.DEBUG && binding != null && !ended) Ticket(binding!!.operationID) else null

    fun qualifies(f: Facts, b: Binding): Boolean = valid(b)
        && f.scopeHash == b.scopeHash && f.entityHash == b.entityHash
        && f.oldNameHash == b.oldNameHash && f.newNameHash == b.newNameHash
        && f.nameOnly && f.baselinePresent && f.oldNameHash != f.newNameHash
        && f.beforeRevision != null && f.beforeRevision == f.beforeSyncedRevision
        && f.afterRevision != null && f.afterRevision > f.beforeRevision

    /** Timestamp acquired immediately before withTransaction, inside Dispatchers.IO. */
    @Synchronized fun begin(ticket: Ticket) {
        if (binding?.operationID != ticket.operationID || begun || ended) { invalid = "INVALID_OR_DUPLICATE_BEGIN"; return }
        begun = true; append(Event("commit_call_begin", now()))
    }
    /** returnedNS is captured at the callsite immediately after withTransaction returns. */
    @Synchronized fun returned(ticket: Ticket, returnedNS: Long, didWrite: Boolean, value: Facts?) {
        val b = binding
        if (b == null || b.operationID != ticket.operationID || !begun || ended) {
            invalid = "MISSING_BINDING_OR_BEGIN"; return
        }
        ended = true; facts = value
        if (!didWrite || value == null || !qualifies(value,b)) { invalid = "NO_OP_OR_NAME_MUTATION_MISMATCH"; return }
        append(Event("commit_return", returnedNS))
    }
    @Synchronized fun failed(ticket: Ticket) {
        if (binding?.operationID == ticket.operationID) { ended = true; invalid = "SAVE_THROWN" }
    }
    private fun append(e: Event) {
        if (events.size >= CAPACITY) { overflow = true; invalid = "OVERFLOW"; return }
        events.add(e)
    }
    /** Called in finally only after withLocalBusinessMutation has unwound. */
    @Synchronized fun finishedOutsideFence(ticket: Ticket) {
        if (binding?.operationID == ticket.operationID) {
            if (!ended) { ended = true; invalid = invalid ?: "NO_COMPLETED_SAVE" }
            exportReady = true
        }
    }
    /** Test driver snapshots/serializes only after the transaction and writer scope finish. */
    @Synchronized fun snapshotFromTestOnly(): Receipt? = binding?.let {
        if (!BuildConfig.DEBUG || !ended || !exportReady) null
        else Receipt(it,clockID,events.toList(),facts,invalid,overflow)
    }

    // B6 extends this same external observation primitive. Legacy PILOT-* APIs above are unchanged.
    /** Observation values copied from the existing gate/lease; cannot admit any work. */
    @kotlinx.serialization.Serializable
    data class B6ScopeStamp(val ownerHash: String, val storeID: String, val localStoreID: String,
        val schema: Int, val protocol: Int, val storeEpoch: Int)
    @kotlinx.serialization.Serializable
    data class B6AuthorityStamp(val scope: B6ScopeStamp, val localGeneration: Long, val cloudGeneration: Long)
    @kotlinx.serialization.Serializable
    data class B6LeaseStamp(val scope: B6ScopeStamp, val generation: Long, val localOnly: Boolean)
    class B6Authority(val stamp: B6AuthorityStamp, val isCurrent: () -> Boolean)
    fun b6ScopeStamp(scope: Task126OwnerStoreScope): B6ScopeStamp = B6ScopeStamp(scope.ownerHash,
        scope.storeId,scope.localStoreId,scope.schemaVersion,scope.syncProtocolVersion,scope.storeEpoch)
    fun b6LeaseStamp(lease: Task126BusinessDataScopeLease?): B6LeaseStamp? {
        if(!b6Armed || lease==null || lease.unmanaged) return null
        return lease.boundScope?.let { B6LeaseStamp(b6ScopeStamp(it),lease.generation,lease.localOnly) }
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.Serializable
    data class B6Binding(val manifestHash: String, val preflightHash: String, val sampleID: String,
        val operationID: String, val nonce: String, val role: String, val category: String,
        val scopeHash: String, val deviceHash: String, val artifactHash: String,
        val entityHashes: List<String>, val localProductID: Long?, val expectedLocalBodyHash: String?,
        val localEntityID: Long? = null, val expectedVisibleProjectionHash: String? = null,
        val deleteQuery: String? = null, val deletedBarcode: String? = null, val controlBarcode: String? = null, val parentRemoteID: String? = null, val expectedBarcode: String? = null,
        val expectedPriceValueJSON: String? = null, val expectedPriceVisibleJSON: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val timingMethod: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val sourceAdmissionNonce: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val visibilityConfirmationNonce: String? = null)
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.Serializable
    data class B6Event(val stage: String, val ns: Long, val cycleID: String? = null,
        val entities: List<String> = emptyList(), val revision: Int? = null,
        val bodyHash: String? = null, val outcome: String? = null, val applyID: String? = null,
        val remoteVersionHash: String? = null, val localID: Long? = null,
        val remoteID: String? = null, val bodyValueJSON: String? = null, val versionRaw: String? = null,
        val originalIntentJSON: String? = null, val sealedAttemptJSON: String? = null,
        val operationKey: String? = null, val visibleProjectionHash: String? = null, val deletedAtRaw: String? = null, val visibleValueJSON: String? = null,
        val authorityStamp: B6AuthorityStamp? = null, val leaseStamp: B6LeaseStamp? = null,
        val preparedLocalBodyJSON: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val causalSequence: Int? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val causalEarliestHookNS: Long? = null)
    @kotlinx.serialization.Serializable
    data class B6Reply(val nonce: String, val clockID: String, val remoteNS: Long,
        val participantPID: Int, val bootHash: String, val artifactHash: String,
        val operationID: String, val deviceHash: String, val preflightHash: String, val manifestHash: String)
    @kotlinx.serialization.Serializable
    data class B6Receipt(val binding: B6Binding, val reply: B6Reply, val events: List<B6Event>,
        val invalidReason: String?, val nativeAcceptance: Boolean = false, val authorityStamp: B6AuthorityStamp? = null)
    data class B6Ticket(val operationID: String, val cycleID: String? = null, val applyID: String? = null, val leaseStamp: B6LeaseStamp? = null)
    class B6CycleContext(val ticket: B6Ticket) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<B6CycleContext>
    }
    private var b6Authority: B6Authority? = null
    private var b6AuthorityInvalid = false
    private var b6ObservedLease: B6LeaseStamp? = null
    private var b6Binding: B6Binding? = null
    private var b6Reply: B6Reply? = null
    private var b6Invalid: String? = null
    private var b6Local: B6Ticket? = null
    private var b6Applying: B6Ticket? = null
    private var b6Applied: B6Event? = null
    private var b6Committed: B6Event? = null
    private val b6Events = ArrayList<B6Event>(128)
    private val b6Consumed = HashSet<String>()
    @Volatile private var b6Armed = false
    const val B6_CAUSAL_METHOD = "HOST_CAUSAL_UPPER_BOUND_V1"
    @Volatile private var b6Causal = false
    private var b6SourceWitnessNS: Long? = null
    private val b6SourceHookEntries = java.util.concurrent.atomic.AtomicInteger(0)
    private val b6UITriggerEntries = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var b6CausalSourceOperation: String? = null
    private var b6SourceTriggerCount = 0
    private var b6CurrentSourceHookNS: Long? = null
    private var b6CausalPreview: JsonObject? = null

    /** No authority is created here. The ordinary Task103 host supplies independently checked facts. */
    @Synchronized fun b6ArmFromTestOnly(b: B6Binding, scope: String, device: String,
        artifact: String, bootHash: String, authority: B6Authority): B6Reply? = runCatching {
        val match = Regex("T20-G0([12])-([0-9]{2})").matchEntire(b.sampleID) ?: return null
        val n = match.groupValues[2].toInt()
        val group = match.groupValues[1].toInt()
        val kinds = listOf("create", "update", "delete", "price", "history")
        val expectedKind = kinds[Math.floorMod(n - 4 + group - 1, 5)]
        if (!BuildConfig.DEBUG || b6Armed || b6Consumed.size >= 66 || b.operationID in b6Consumed || n !in 1..33 ||
            b.category != expectedKind || b.role !in setOf("source", "peer") ||
            b.role != (if (group == 2) "source" else "peer") ||
            listOf(b.manifestHash,b.preflightHash,b.scopeHash,b.deviceHash,b.artifactHash,bootHash).any { !isHash(it) } ||
            b.scopeHash != scope || b.deviceHash != device || b.artifactHash != artifact ||
            b.entityHashes.size !in 0..1 || b.entityHashes.distinct().size != b.entityHashes.size ||
            b.entityHashes.any { !isHash(it) } || b.localProductID?.let { it <= 0 } == true ||
            b.category !in kinds ||
            (b.entityHashes.isEmpty() && b.category !in setOf("create", "price", "history")) ||
            (b.category in setOf("update", "delete", "price") && b.localProductID == null) ||
            (b.category=="price" && (b.parentRemoteID==null || runCatching { UUID.fromString(b.parentRemoteID) }.isFailure)) ||
            (b.category=="history" && b.localEntityID==null) ||
            (b.role=="peer" && b.category in setOf("history","delete") &&
                (b.expectedVisibleProjectionHash==null || !isHash(b.expectedVisibleProjectionHash))) ||
            (b.category!="price" && (b.expectedLocalBodyHash==null || !isHash(b.expectedLocalBodyHash))) ||
            (b.category=="price" && (b.expectedPriceValueJSON==null ||
                !validPriceExpectation(b.expectedPriceValueJSON) || (b.role=="peer" && b.expectedPriceVisibleJSON==null)))) return null
        val stamp=authority.stamp
        if(stamp.localGeneration<0 || stamp.cloudGeneration<0 ||
            hash(stamp.scope.ownerHash+"\n"+stamp.scope.storeID)!=scope || !authority.isCurrent()) return null
        UUID.fromString(b.operationID); UUID.fromString(b.nonce)
        if (b.timingMethod != null) {
            require(b.timingMethod == B6_CAUSAL_METHOD)
            val challenge = if (b.role == "source") b.sourceAdmissionNonce else b.visibilityConfirmationNonce
            require(challenge != null && UUID.fromString(challenge).toString() == challenge && challenge != b.nonce)
            require(if (b.role == "source") b.visibilityConfirmationNonce == null else b.sourceAdmissionNonce == null)
        } else require(b.sourceAdmissionNonce == null && b.visibilityConfirmationNonce == null)
        val reply = B6Reply(b.nonce, clockID, now(), android.os.Process.myPid(), bootHash, artifact,
            b.operationID,device,b.preflightHash,b.manifestHash)
        b6Authority=authority; b6AuthorityInvalid=false; b6ObservedLease=null
        b6Binding = b.copy(entityHashes=b.entityHashes.toList())
        b6Reply=reply; b6Invalid=null; b6Local=null; b6Applying=null; b6Applied=null; b6Committed=null; b6Events.clear(); b6PayloadBytes=0; b6Source=null; b6Prepared=null; b6HistoryRows=emptyList(); b6DeleteIntent=null; b6DeletePatch=null; b6ObservedRemoteID=null; b6OriginalRef=null
        b6Causal=b.timingMethod==B6_CAUSAL_METHOD; b6SourceWitnessNS=null; b6SourceHookEntries.set(0); b6UITriggerEntries.set(0)
        b6CausalSourceOperation=b.operationID.takeIf { b6Causal && b.role=="source" }
        b6SourceTriggerCount=0; b6CurrentSourceHookNS=null; b6CausalPreview=null
        b6Consumed.add(b.operationID); b6Armed=true
        reply
    }.getOrNull()

    fun b6IsArmed(): Boolean = b6Armed

    private fun b6Current(): B6Binding? {
        val b = b6Binding ?: return null
        if (!b6Armed) return null
        val reply = b6Reply ?: return null
        if(!b6AuthorityCurrent()) return null
        if (now() - reply.remoteNS > 60_000_000_000L) { b6Invalid="ARM_EXPIRED"; return null }
        return b
    }
    private fun b6AuthorityCurrent(): Boolean {
        if(b6AuthorityInvalid) return false
        if(runCatching { b6Authority?.isCurrent?.invoke()==true }.getOrDefault(false).not()) {
            b6AuthorityInvalid=true; b6Invalid=b6Invalid ?: "BUSINESS_GENERATION_CHANGED"; return false
        }
        return true
    }
    private fun b6RequireLease(lease: B6LeaseStamp?, cloudOnly: Boolean = false): Boolean {
        if(b6Current()==null) return false
        val stamp=b6Authority?.stamp ?: return false
        if(lease==null || (cloudOnly && lease.localOnly) || lease.scope!=stamp.scope ||
            lease.generation!=(if(lease.localOnly) stamp.localGeneration else stamp.cloudGeneration)) {
            b6AuthorityInvalid=true; b6Invalid=b6Invalid ?: "BUSINESS_LEASE_CHANGED"; return false
        }
        b6ObservedLease=lease
        return true
    }
    private var b6PayloadBytes = 0
    /** Observe the actual entry before acquiring this trace lock. Supplied boundary timestamps
     * (for example the already captured transaction return) remain the earlier raw witness.
     * This never changes business admission or invokes a writer/coordinator. */
    private inline fun <T> b6SourceHook(earliestBoundaryNS: Long? = null, body: () -> T): T {
        val entered = if (b6Armed && b6Causal) now().let { value -> earliestBoundaryNS?.let { minOf(value,it) } ?: value } else null
        if (b6Armed && b6CausalSourceOperation!=null) b6SourceHookEntries.incrementAndGet()
        return synchronized(this) {
            val previous = b6CurrentSourceHookNS
            if (b6Causal && b6Armed && b6Binding?.role == "source") {
                b6CurrentSourceHookNS = entered?.let { value -> previous?.let { minOf(it,value) } ?: value }
                if (b6SourceWitnessNS == null || entered == null || entered <= requireNotNull(b6SourceWitnessNS))
                    b6Invalid = b6Invalid ?: "SOURCE_HOOK_BEFORE_IDLE_WITNESS"
            }
            try { body() } finally { b6CurrentSourceHookNS = previous }
        }
    }

    class B6UIAnnouncement internal constructor(val operationID: String, val ns: Long, val ordinal: Int)
    /** Called first in the already installed SAVE_CLICK observer, before parsing a detail or
     * waiting for this trace lock. The later raw timestamp check covers an entry racing the witness. */
    fun b6AnnounceSourceUIActionFromTestOnly(enteredNS: Long): B6UIAnnouncement? {
        val operation = b6CausalSourceOperation ?: return null
        if (!b6Armed) return null
        return B6UIAnnouncement(operation,enteredNS,b6UITriggerEntries.incrementAndGet())
    }

    /** Existing UI callback supplies its actual entry timestamp before validation/launch.
     * A create still has no Room ID; zero is observed as absence, never a predicted identity. */
    fun b6SourceUIActionFromTestOnly(kind: String, localID: Long?, enteredNS: Long, handler: String,
        announcement: B6UIAnnouncement?) =
        b6SourceHook(enteredNS) {
            if (!b6Causal || !b6Armed || b6Binding?.role != "source") return@b6SourceHook
            val b = b6Current() ?: return@b6SourceHook
            if (announcement==null || announcement.operationID!=b.operationID || announcement.ns!=enteredNS ||
                announcement.ordinal!=1 || b6UITriggerEntries.get()!=1) {
                b6Invalid=b6Invalid ?: "MISSING_OR_DUPLICATE_UI_ENTRY_ANNOUNCEMENT"
                return@b6SourceHook
            }
            val expected = if (kind == "history") b.localEntityID else b.localProductID
            if (kind != b.category || handler.isBlank() || handler.length > 100 ||
                (kind == "create" && localID != null && localID != 0L) ||
                (kind != "create" && (expected == null || localID != expected))) {
                b6Invalid = b6Invalid ?: "FOREIGN_OR_UNQUALIFIED_SOURCE_UI_TRIGGER"
                return@b6SourceHook
            }
            b6SourceTriggerCount++
            if (b6SourceTriggerCount != 1) b6Invalid = b6Invalid ?: "DUPLICATE_SOURCE_UI_TRIGGER"
            b6Append(B6Event("source_ui_action_begin",enteredNS,localID=localID?.takeIf { it > 0 },
                outcome=kind+":"+handler))
        }

    private fun b6CausalIdentity(reply: B6Reply) = buildJsonObject {
        put("clock_id",reply.clockID); put("participant_pid",reply.participantPID)
        put("participant_boot_sha256",reply.bootHash); put("artifact_sha256",reply.artifactHash)
        put("device_sha256",reply.deviceHash); put("ordinary_preflight_sha256",reply.preflightHash)
    }
    private fun b6EventPrefixRaw(): String = b6Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(B6Event.serializer()),b6Events.toList())

    /** Snapshot under the SAME synchronization as source hooks. I/O is the ordinary harness's
     * responsibility after this method returns; no writer lock is held while exporting. */
    @Synchronized fun b6SourceIdleWitnessFromTestOnly(nonce: String): JsonObject? = runCatching {
        val b = b6Current() ?: return null
        val reply = b6Reply ?: return null
        require(b6Causal && b.role == "source" && nonce == b.sourceAdmissionNonce && nonce != b.nonce)
        val observedHookEntries=b6SourceHookEntries.get()
        val observedTriggerEntries=b6UITriggerEntries.get()
        require(b6SourceWitnessNS == null && b6Invalid == null && observedHookEntries == 0 && observedTriggerEntries == 0 &&
            b6SourceTriggerCount == 0 && b6Local == null && b6Source == null && b6Prepared == null &&
            b6OriginalRef == null && b6DeleteIntent == null && b6DeletePatch == null && b6Events.isEmpty())
        val witnessed = now()
        b6SourceWitnessNS = witnessed
        b6Append(B6Event("causal_source_idle_witness",witnessed,outcome=nonce))
        require(b6Invalid == null && b6Events.size == 1)
        val prefix = b6EventPrefixRaw()
        buildJsonObject {
            put("kind","B6_SOURCE_IDLE_WITNESS"); put("timing_method",B6_CAUSAL_METHOD)
            put("operation_id",b.operationID); put("manifest_sha256",b.manifestHash); put("nonce",nonce)
            put("identity",b6CausalIdentity(reply)); put("native_ns",witnessed)
            put("trace_prefix_raw",prefix); put("trace_prefix_sha256",hash(prefix)); put("trace_event_count",b6Events.size)
            put("source_idle_state",buildJsonObject {
                put("trigger_count",observedTriggerEntries); put("mutation_ticket_count",if(b6Local==null) 0 else 1)
                put("transaction_count",b6Events.count { it.stage=="local_transaction_begin" })
                put("seal_count",b6Events.count { it.stage=="source_intent_sealed" })
                put("source_result_count",b6Events.count { it.stage=="local_commit_return" || it.stage=="source_actual_ack_identity_persisted" })
            })
        }
    }.onFailure { if (b6Causal && b6Armed) b6Invalid=b6Invalid ?: "SOURCE_IDLE_WITNESS_REJECTED" }.getOrNull()

    /** Immutable pre-final proof only after the original actual ACK or peer commit+draw. */
    @Synchronized fun b6CausalTracePreviewFromTestOnly(): JsonObject? = runCatching {
        b6CausalPreview?.let { return it }
        val b = b6Current() ?: return null
        val reply = b6Reply ?: return null
        require(b6Causal && b6Invalid == null && b6AuthorityCurrent())
        val terminal = if (b.role=="source") "source_actual_ack_identity_persisted" else
            if (b.category in setOf("create","update")) "peer_expected_product_draw" else "peer_expected_entity_draw"
        if (b6Events.none { it.stage == terminal }) return null
        if (b.role=="source") require(b6SourceWitnessNS != null && b6SourceTriggerCount == 1 && b6UITriggerEntries.get() == 1)
        else require(b6Committed != null && b6Events.any { it.stage=="peer_commit_return" })
        val receipt = B6Receipt(b,reply,b6Events.toList(),b6Invalid,authorityStamp=b6Authority?.stamp)
        val raw = JsonObject(b6Json.parseToJsonElement(b6Json.encodeToString(B6Receipt.serializer(),receipt)).jsonObject+
            mapOf("status" to JsonPrimitive("OBSERVATIONS_ONLY_CAUSAL_PREVIEW"),
                "authority_current_at_preview" to JsonPrimitive(true))).toString()
        val prefix = b6EventPrefixRaw()
        buildJsonObject {
            put("kind","B6_CAUSAL_TRACE_PREVIEW"); put("timing_method",B6_CAUSAL_METHOD)
            put("operation_id",b.operationID); put("manifest_sha256",b.manifestHash); put("role",b.role)
            put("identity",b6CausalIdentity(reply)); put("receipt_raw",raw); put("receipt_sha256",hash(raw))
            put("trace_prefix_raw",prefix); put("trace_prefix_sha256",hash(prefix)); put("trace_event_count",b6Events.size)
        }.also { b6CausalPreview=it }
    }.getOrNull()

    private fun b6Append(event: B6Event) {
        if(b6Current()==null) return
        val bytes=listOf(event.bodyValueJSON,event.originalIntentJSON,event.sealedAttemptJSON,event.visibleValueJSON,event.preparedLocalBodyJSON).sumOf { it?.toByteArray()?.size ?: 0 }
        if (b6Events.size < 64 && b6PayloadBytes+bytes<=40_000) {
            val stageLease=when {
                event.stage.startsWith("local_") -> b6Local?.leaseStamp
                event.stage.startsWith("peer_") -> b6Applying?.leaseStamp ?: b6Committed?.leaseStamp
                else -> b6ObservedLease
            }
            b6PayloadBytes+=bytes
            val sourceEvent = event.stage.startsWith("source_") || event.stage.startsWith("local_")
            val earliest = if (b6Causal && sourceEvent) b6CurrentSourceHookNS else null
            if (b6Causal && sourceEvent && (earliest == null || b6SourceWitnessNS == null ||
                    earliest <= requireNotNull(b6SourceWitnessNS) || earliest > event.ns))
                b6Invalid = b6Invalid ?: "SOURCE_HOOK_NOT_STRICTLY_AFTER_IDLE_WITNESS"
            b6Events.add(event.copy(authorityStamp=b6Authority?.stamp,leaseStamp=event.leaseStamp ?: stageLease,
                causalSequence=if(b6Causal) b6Events.size else null,causalEarliestHookNS=earliest))
        } else b6Invalid="OVERFLOW"
    }
    fun b6BeginLocal(productID: Long, scope: String?, lease: B6LeaseStamp? = null): B6Ticket? = b6SourceHook() {
        runCatching {
        if(!b6RequireLease(lease)) return null
        val b=b6Current() ?: return null
        if (b.role != "source" || b.category != "update" || b.localProductID != productID || scope != b.scopeHash) return null
        if (b6Local != null) { b6Invalid="DUPLICATE_LOCAL_SAVE"; return null }
        B6Ticket(b.operationID,leaseStamp=lease).also { b6Local=it; b6Append(B6Event("local_transaction_begin",now())) }
    }.getOrNull()
    }
    fun b6LocalIntent(productID: Long, remoteID: String, revision: Int): Unit = b6SourceHook() {
        runCatching {
            val b=b6Current() ?: return
            if (b6Local?.operationID != b.operationID || b.category !in setOf("create", "update") ||
                (b.localProductID != null && b.localProductID != productID)) return
            val entity=hash(remoteID.lowercase())
            if (b.entityHashes.isNotEmpty() && entity !in b.entityHashes) { b6Invalid="LOCAL_INTENT_IDENTITY_MISMATCH"; return }
            b6Append(B6Event("local_intent_staged",now(),entities=listOf(entity),revision=revision))
        }

        Unit
    }
    fun b6LocalEnd(ticket: B6Ticket?, didWrite: Boolean?, bodyHash: String?, failed: Boolean=false, returnedNS: Long?=null, lease: B6LeaseStamp? = null): Unit = b6SourceHook(returnedNS) {
        runCatching {
            if(!b6RequireLease(lease)) return
            val b=b6Current() ?: return
            if (ticket == null || ticket != b6Local || ticket.operationID != b.operationID) return
            if(ticket.leaseStamp!=lease) { b6AuthorityInvalid=true; b6Invalid="LOCAL_FLIGHT_CHANGED"; return }
            b6Append(B6Event(if(failed) "local_commit_threw_unconfirmed" else "local_commit_return",returnedNS ?: now(),
                bodyHash=bodyHash,localID=b6Source?.localID,remoteID=b6Source?.remoteID,
                originalIntentJSON=b6Source?.tokenJSON,outcome=if(failed) "UNCONFIRMED" else if(didWrite==true) "WROTE" else "NO_OP"))
            if (!failed && (didWrite != true || b6Events.none { it.stage=="local_intent_staged" } ||
                    (b.entityHashes.isNotEmpty() && b6Events.filter { it.stage=="local_intent_staged" }.flatMap { it.entities }.toSet()!=b.entityHashes.toSet()) ||
                    b.expectedLocalBodyHash != bodyHash)) b6Invalid="LOCAL_COMMIT_FACTS_UNQUALIFIED"
        }

        Unit
    }
    fun b6ProductBodyHash(product: Product): String? = if (!b6Armed) null else runCatching {
        val raw=Json.encodeToString(CatalogProductSnapshot.serializer(), CatalogProductSnapshot.from(product))
        if(synchronized(this) { b6Current()?.category=="create" }) b6CreateProductBodyHash(product) else hash(raw)
    }.getOrNull()

    @Synchronized private fun b6Admit(scope: String, reason: String, lease: B6LeaseStamp?): B6Ticket? = runCatching {
        val b=b6Current() ?: return null
        if (b.role != "peer" || b.scopeHash != scope) return null
        if(!b6RequireLease(lease,cloudOnly=true) || lease?.localOnly!=false) return null
        if (reason != "foreground_interval") { b6Invalid="NON_ORDINARY_RECEIVER_TRIGGER"; return null }
        B6Ticket(b.operationID,UUID.randomUUID().toString(),leaseStamp=lease).also {
            b6Append(B6Event("receiver_admitted",now(),cycleID=it.cycleID,outcome=reason)) }
    }.getOrNull()
    /** Same existing flight; value-only correlation context, no scheduling or authority change. */
    suspend fun <T> b6ObserveCycle(scope: () -> String, reason: String, body: suspend () -> T): T {
        if (!b6Armed) return body()
        val actualScope=runCatching(scope).getOrNull() ?: return body()
        val ticket=b6Admit(actualScope,reason,b6LeaseStamp(kotlin.coroutines.coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease)) ?: return body()
        return try { kotlinx.coroutines.withContext(B6CycleContext(ticket)) { body() } }
        finally { b6CycleEnded(ticket) }
    }
    @Synchronized private fun b6CycleEnded(ticket: B6Ticket) {
        runCatching { if(b6Current()?.operationID==ticket.operationID) b6Append(B6Event("receiver_cycle_end",now(),ticket.cycleID)) }
    }
    @Synchronized fun b6BeginApply(ticket: B6Ticket?, scope: String, remoteIDs: List<String>, lease: B6LeaseStamp? = null): B6Ticket? = runCatching {
        if(!b6RequireLease(lease,cloudOnly=true)) return null
        val b=b6Current() ?: return null
        if(ticket==null || ticket.operationID!=b.operationID || ticket.cycleID==null ||
            b.role!="peer" || scope!=b.scopeHash) return null
        if(ticket.leaseStamp!=lease || lease?.localOnly!=false) { b6AuthorityInvalid=true; b6Invalid="APPLY_FLIGHT_CHANGED"; return null }
        val target=b6ObservedRemoteID?.let { listOf(hash(it.lowercase())) } ?: b.entityHashes
        val matched=remoteIDs.asSequence().map { hash(it.lowercase()) }.filter { target.isEmpty() || it in target }.distinct().take(33).toList()
        if (target.isNotEmpty() && matched.toSet()!=target.toSet()) return null
        if (target.isEmpty() && matched.isEmpty()) return null
        if(b6Applying!=null) { b6Invalid="OVERLAPPING_APPLY"; return null }
        // Preserve the old raw draw, but it can never certify a later target transaction.
        if(b6Events.any { it.stage in setOf("peer_expected_product_draw","peer_expected_entity_draw") }) b6Invalid="TARGET_CHANGED_AFTER_DRAW"
        b6Committed=null; b6Applied=null
        ticket.copy(applyID=UUID.randomUUID().toString(),leaseStamp=lease).also {
            b6Applying=it
            b6Append(B6Event("peer_transaction_begin",now(),it.cycleID,matched,applyID=it.applyID)) }
    }.getOrNull()
    /** Call only after the existing mapper actually wrote this Product; no read/query is added. */
    fun b6AppliedProduct(cycle: B6Ticket?, remoteID: String, product: Product, revision: Int, updatedAt: String?, row: InventoryProductRow? = null) {
        if (!b6Armed) return
        if(synchronized(this) { b6Current()?.let { it.category=="create" && it.expectedBarcode!=null && it.expectedBarcode!=product.barcode } }==true) return
        val body=b6ProductBodyHash(product) ?: return
        b6AppliedProductFacts(cycle,remoteID,product.id,revision,body,updatedAt)
        if(row!=null) b6AttachPortableProduct(cycle,row)
    }
    @Synchronized fun b6AppliedProductFacts(cycle: B6Ticket?, remoteID: String, localID: Long,
        revision: Int, body: String, updatedAt: String?) {
        runCatching {
            val b=b6Current() ?: return; val t=b6Applying ?: return
            if(b.category !in setOf("create","update") || (b.entityHashes.isEmpty() && body!=b.expectedLocalBodyHash)) return
            if(cycle==null || cycle.operationID!=t.operationID || cycle.cycleID!=t.cycleID || (b.localProductID!=null && b.localProductID!=localID) ||
                (b.entityHashes.isNotEmpty() && hash(remoteID.lowercase()) !in b.entityHashes)) return
            if(b6Applied!=null || updatedAt.isNullOrBlank() || revision<0 || !isHash(body)) {
                b6Invalid="APPLIED_FACTS_UNQUALIFIED"; return }
            b6Applied=B6Event("peer_product_applied",now(),t.cycleID,listOf(hash(remoteID.lowercase())),
                revision,body,applyID=t.applyID,remoteVersionHash=hash(updatedAt),
                localID=localID, remoteID=remoteID, versionRaw=updatedAt)
            b6Append(requireNotNull(b6Applied))
        }
    }
    @Synchronized fun b6ApplyEnd(ticket: B6Ticket?, failed: Boolean=false, returnedNS: Long?=null, lease: B6LeaseStamp? = null) {
        runCatching {
            if(!b6RequireLease(lease,cloudOnly=true)) return
            val b=b6Current() ?: return
            if(ticket==null || ticket!=b6Applying || b.operationID!=ticket.operationID) return
            if(ticket.leaseStamp!=lease) { b6AuthorityInvalid=true; b6Invalid="APPLY_FLIGHT_CHANGED"; return }
            val facts=b6Applied
            val event=B6Event(if(failed) "peer_commit_threw_unconfirmed" else "peer_commit_return",returnedNS ?: now(),ticket.cycleID,
                entities=facts?.entities.orEmpty(),revision=facts?.revision,bodyHash=facts?.bodyHash,
                outcome=if(failed) "UNCONFIRMED" else "RETURNED",applyID=ticket.applyID,
                remoteVersionHash=facts?.remoteVersionHash,localID=facts?.localID,remoteID=facts?.remoteID,
                bodyValueJSON=facts?.bodyValueJSON,versionRaw=facts?.versionRaw,deletedAtRaw=facts?.deletedAtRaw,
                authorityStamp=b6Authority?.stamp,leaseStamp=lease)
            b6Append(event)
            b6Committed=event.takeIf { !failed && (if(b.category=="price") it.bodyValueJSON?.let { value -> priceExpected(b,value) }==true else it.bodyHash==b.expectedLocalBodyHash) &&
                (b.entityHashes.isEmpty() || it.entities.toSet()==b.entityHashes.toSet()) && it.remoteVersionHash!=null }
            b6Committed?.remoteID?.let { b6ObservedRemoteID=it }; b6Applying=null; b6Applied=null
        }
    }
    /** Value witness only. AX/window proof is still mandatory before this is a qualified tV. */
    fun b6RenderDetail(product: Product, original: String?): String? {
        if (!b6Armed) return original
        val b=synchronized(this) {
            b6Current()?.takeIf { it.role=="peer" && it.category in setOf("create", "update") &&
                (it.localProductID==null || it.localProductID==product.id) &&
                (it.expectedBarcode==null || it.expectedBarcode==product.barcode) }
        } ?: return original
        val body=b6ProductBodyHash(product) ?: return original
        // The immutable node carries its body. Commit may return after composition; qualify at DRAW.
        return "b6:${b.operationID}:$body"
    }

    @Synchronized fun b6Rendered(productID: Long, detail: String?, ns: Long) {
        runCatching {
            val b=b6Current() ?: return; val committed=b6Committed ?: return
            if(b.role!="peer" || committed.localID!=productID || ns<committed.ns ||
                detail!="b6:${b.operationID}:${b.expectedLocalBodyHash}") return
            if(b6Events.none { it.stage=="peer_expected_product_draw" }) b6Append(
                B6Event("peer_expected_product_draw",ns,committed.cycleID,committed.entities,
                    committed.revision,committed.bodyHash,applyID=committed.applyID,
                    remoteVersionHash=committed.remoteVersionHash,localID=committed.localID,remoteID=committed.remoteID,
                    bodyValueJSON=committed.bodyValueJSON,versionRaw=committed.versionRaw,deletedAtRaw=committed.deletedAtRaw,
                    leaseStamp=committed.leaseStamp))
        }
    }
    private data class SourceIntent(val localID: Long, val localJSON: String, val bodyHash: String,
        val remoteID: String?, val revision: Long?, val tokenJSON: String,
        val authority: B6AuthorityStamp = requireNotNull(b6Authority).stamp)
    private data class PreparedIntent(val remoteID: String, val revision: Long, val bodyJSON: String,
        val fingerprint: String?, val wireKind: String, val localID: Long, val localJSON: String,
        var sealedJSON: String? = null, var key: String? = null,
        val authority: B6AuthorityStamp = requireNotNull(b6Authority).stamp)
    private var b6Source: SourceIntent? = null
    private var b6Prepared: PreparedIntent? = null
    private var b6OriginalRef: ProductRemoteRef? = null
    private var b6ObservedRemoteID: String? = null
    private val b6Json = Json { encodeDefaults=true }
    private fun bounded(value: String): String {
        require(value.toByteArray(Charsets.UTF_8).size<=16_384) { "B6_VALUE_TOO_LARGE" }
        return value
    }
    private inline fun b6Safely(body: () -> Unit) {
        if (!b6Armed) return
        try { if(b6Current()!=null) body() } catch (_: Exception) { b6Invalid="OBSERVATION_VALUE_FAILED" }
    }
    /** Creation identity is observed later; no predicted Room id is required in the body pin. */
    fun b6CreateProductBodyHash(p: Product): String = hash(JsonObject(Json.parseToJsonElement(
        Json.encodeToString(CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(p))).jsonObject.filterKeys { it!="id" }).toString())
    private fun localProductJSON(p: Product): String = bounded(b6Json.encodeToString(
        CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(p)))
    fun b6BeginKind(kind: String, localID: Long?, scope: String?, lease: B6LeaseStamp? = null): B6Ticket? = b6SourceHook() {
        runCatching {
        if(!b6RequireLease(lease)) return null
        val b=b6Current() ?: return null
        if(b.role!="source" || b.category!=kind || b.scopeHash!=scope) return null
        val expected=if(kind=="history") b.localEntityID else b.localProductID
        if(expected!=null && expected!=localID) return null
        if(b6Local!=null) { b6Invalid="DUPLICATE_LOCAL_SAVE"; return null }
        B6Ticket(b.operationID,leaseStamp=lease).also { b6Local=it; b6Append(B6Event("local_transaction_begin",now(),localID=localID)) }
    }.getOrNull()
    }
    fun b6CaptureProductRef(ref: ProductRemoteRef) = b6SourceHook() {
        b6Safely {
        val b=b6Current() ?: return@b6Safely
        if(b.role=="source" && b.category in setOf("create","update") && b6Local!=null &&
            (b.localProductID==null || b.localProductID==ref.productId)) b6OriginalRef=ref
    }
    }
    fun b6CaptureProduct(product: Product) = b6SourceHook() {
        b6Safely {
        val ref=b6OriginalRef ?: return@b6Safely
        val b=b6Current() ?: return@b6Safely
        if(b6Local==null || ref.productId!=product.id) return@b6Safely
        val local=localProductJSON(product)
        val token=bounded(buildJsonObject {
            put("localID",product.id); put("body",b6Json.parseToJsonElement(local))
            put("remoteID",ref.remoteId); put("revision",ref.localChangeRevision)
            put("syncedRevision",ref.lastSyncedLocalRevision); put("changedFields",ref.localChangedFields)
            put("priorFingerprint",ref.lastRemotePayloadFingerprint); put("priorVersion",ref.remoteUpdatedAt)
        }.toString())
        b6Source=SourceIntent(product.id,local,if(b.category=="create") b6CreateProductBodyHash(product) else hash(local),ref.remoteId,ref.localChangeRevision.toLong(),token)
    }
    }
    fun b6CapturePrice(row: ProductPrice) = b6SourceHook() {
        b6Safely {
        val b=b6Current() ?: return@b6Safely
        if(b.category!="price" || b6Local==null || b.localProductID!=row.productId) return@b6Safely
        val local=PilotB6Values.localPrice(row)
        b6Source=SourceIntent(row.id,local,hash(local),null,0,bounded(buildJsonObject {
            put("localID",row.id);put("parentLocalID",row.productId);put("body",b6Json.parseToJsonElement(local))
        }.toString()))
        b6Append(B6Event("local_intent_staged",now(),localID=row.id,originalIntentJSON=b6Source?.tokenJSON))
    }
    }
    fun b6CaptureHistory(entry: HistoryEntry, revision: Long?) = b6SourceHook() {
        b6Safely {
        val b=b6Current() ?: return@b6Safely
        if(b.category!="history" || b6Local==null || (b.localEntityID!=null && b.localEntityID!=entry.uid)) return@b6Safely
        val local=PilotB6Values.localHistory(entry)
        b6Source=SourceIntent(entry.uid,local,hash(local),null,revision,bounded(buildJsonObject {
            put("localID",entry.uid); put("revision",revision); put("body",b6Json.parseToJsonElement(local))
        }.toString()))
        b6Append(B6Event("local_intent_staged",now(),localID=entry.uid,originalIntentJSON=b6Source?.tokenJSON))
    }
    }
    fun b6KindEnd(ticket: B6Ticket?, returnedNS: Long, changed: Boolean, lease: B6LeaseStamp? = null) = b6SourceHook(returnedNS) {
        b6Safely {
        if(!b6RequireLease(lease)) return@b6Safely
        val b=b6Current() ?: return@b6Safely
        if(ticket==null || ticket!=b6Local) return@b6Safely
        if(ticket.leaseStamp!=lease) { b6AuthorityInvalid=true; b6Invalid="LOCAL_FLIGHT_CHANGED"; return@b6Safely }
        val source=b6Source
        b6Append(B6Event("local_commit_return",returnedNS,bodyHash=source?.bodyHash,
            localID=source?.localID,remoteID=source?.remoteID,originalIntentJSON=source?.tokenJSON,
            outcome=if(changed) "WROTE" else "NO_OP"))
        if(!changed || source==null || (if(b.category=="price")
                !priceExpected(b,JsonObject(b6Json.parseToJsonElement(source.localJSON).jsonObject+
                    ("productID" to JsonPrimitive(b.parentRemoteID))).toString())
                else source.bodyHash!=b.expectedLocalBodyHash)) b6Invalid="LOCAL_COMMIT_FACTS_UNQUALIFIED"
    }
    }
    fun b6PreparedProduct(product: Product, ref: ProductRemoteRef, row: InventoryProductRow, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val b=b6Current() ?: return@b6Safely
        if(b.category !in setOf("create","update") || b6Source?.localID!=product.id) return@b6Safely
        prepare(product.id,localProductJSON(product),ref.remoteId,ref.localChangeRevision.toLong(),
            PilotB6Values.product(row),fingerprintProductInbound(row),"PRODUCT")
    }
    }
    fun b6PreparedPrice(local: ProductPricePushRow, row: InventoryProductPriceRow, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val binding=b6Current() ?: return@b6Safely
        if(binding.category!="price") return@b6Safely
        if(local.productId!=binding.localProductID || row.productId!=binding.parentRemoteID) {
            b6Invalid="PRICE_PARENT_IDENTITY_MISMATCH";return@b6Safely
        }
        prepare(local.id,PilotB6Values.localPrice(local.toProductPrice()),row.id,0,PilotB6Values.price(row),null,"PRICE")
    }
    }
    fun b6PreparedHistory(local: HistoryEntry, ref: HistoryEntryRemoteRef, payload: SessionRemotePayload, owner: String, shop: String?, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        if(b6Current()?.category!="history" || b6Source?.localID!=local.uid) return@b6Safely
        val row=payload.toSharedSheetSessionUpsertRow(owner,shop)
        prepare(local.uid,PilotB6Values.localHistory(local),row.remoteId,ref.localChangeRevision.toLong(),
            PilotB6Values.history(row),null,"HISTORY")
    }
    }
    private fun prepare(localID: Long, local: String, remoteID: String, revision: Long, value: String,
        fingerprint: String?, kind: String) {
        val b=b6Current() ?: return; val s=b6Source ?: return
        if(b.role!="source" || localID!=s.localID) return
        if(s.authority!=b6Authority?.stamp) { b6AuthorityInvalid=true; b6Invalid="SOURCE_GENERATION_CHANGED"; return }
        if(local!=s.localJSON || (s.revision!=null && s.revision!=revision) ||
            (s.remoteID!=null && s.remoteID!=remoteID) ||
            (b.entityHashes.isNotEmpty() && hash(remoteID.lowercase()) !in b.entityHashes)) {
            b6Invalid="SOURCE_INTENT_CHANGED_BEFORE_SEAL"; return
        }
        val prepared=PreparedIntent(remoteID,revision,bounded(value),fingerprint,kind,localID,bounded(local))
        val old=b6Prepared
        if(old!=null && old.copy(sealedJSON=null,key=null)!=prepared) { b6Invalid="SOURCE_PREPARATION_CHANGED"; return }
        if(old==null) b6Prepared=prepared
    }
    private fun patchMatchesPrepared(patch: InventoryProductPatch, prepared: PreparedIntent): Boolean {
        val values=b6Json.parseToJsonElement(prepared.bodyJSON).jsonObject
        val all=PilotB6Values.product(InventoryProductRow(id=prepared.remoteID,ownerUserId="observer",
            barcode=patch.barcode.orEmpty(),itemNumber=patch.itemNumber,productName=patch.productName,
            secondProductName=patch.secondProductName,purchasePrice=patch.purchasePrice,retailPrice=patch.retailPrice,
            supplierId=patch.supplierId,categoryId=patch.categoryId,stockQuantity=patch.stockQuantity))
        val actual=b6Json.parseToJsonElement(all).jsonObject
        val keys=mapOf("barcode" to "barcode","itemnumber" to "itemNumber","productname" to "productName",
            "secondproductname" to "secondProductName","purchaseprice" to "purchasePrice","retailprice" to "retailPrice",
            "supplier" to "supplierID","category" to "categoryID","stockquantity" to "stockQuantity")
        val originalFields=b6OriginalRef?.localChangedFields?.split(',')?.filter { it.isNotBlank() }?.toSet()
        return patch.deletedAt==null && patch.changedFields.isNotEmpty() &&
            (originalFields==null || originalFields==patch.changedFields) &&
            patch.changedFields.all { field -> keys[field]?.let { values[it]==actual[it] } == true }
    }
    fun b6Sealed(entry: SyncEventOutboxEntry, attempt: BusinessWriteAttempt, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val b=b6Current() ?: return@b6Safely; val s=b6Source ?: return@b6Safely
        val p=b6Prepared ?: return@b6Safely
        if(s.authority!=p.authority || p.authority!=b6Authority?.stamp ||
            attempt.schema!=p.authority.scope.schema || attempt.protocol!=p.authority.scope.protocol ||
            attempt.epoch!=p.authority.scope.storeEpoch || attempt.localStore!=p.authority.scope.localStoreID) {
            b6AuthorityInvalid=true; b6Invalid="SEALED_SCOPE_CHANGED"; return@b6Safely
        }
        if(b.role!="source" || hash(task126OwnerHash(attempt.owner)+"\n"+attempt.store)!=b.scopeHash) return@b6Safely
        if(b6Json.encodeToString(BusinessWriteAttempt.serializer(),attempt)!=entry.metadataJson ||
            entry.clientEventId!="business-write-v1:"+hash(entry.metadataJson)) {
            b6Invalid="SEALED_RAW_IDENTITY_MISMATCH";return@b6Safely
        }
        val revision=attempt.payload.revisions.singleOrNull { it.remoteId==p.remoteID } ?: return@b6Safely
        if(revision.domain!=p.wireKind || revision.revision!=p.revision ||
            (p.wireKind=="PRICE" && revision.localPriceId!=s.localID)) { b6Invalid="SEALED_REVISION_MISMATCH"; return@b6Safely }
        val value=when(p.wireKind) {
            "PRODUCT" -> attempt.payload.products.singleOrNull { it.id==p.remoteID }?.let(PilotB6Values::product)
                ?: p.bodyJSON.takeIf { revision.fingerprint==p.fingerprint && attempt.payload.patchId==p.remoteID &&
                    attempt.payload.patch?.let { patchMatchesPrepared(it,p) }==true }
            "PRICE" -> attempt.payload.prices.singleOrNull { it.id==p.remoteID }?.let(PilotB6Values::price)
            "HISTORY" -> attempt.payload.history.singleOrNull { it.remoteId==p.remoteID }?.let(PilotB6Values::history)
            else -> null
        }
        if(value!=p.bodyJSON) { b6Invalid="SEALED_BODY_MISMATCH"; return@b6Safely }
        if(entry.metadataJson.toByteArray().size>64*1024) { b6Invalid="B6_SEAL_TOO_LARGE"; return@b6Safely }
        if(p.sealedJSON!=null && (p.sealedJSON!=entry.metadataJson || p.key!=entry.clientEventId)) {
            b6Invalid="SEALED_ATTEMPT_CHANGED"; return@b6Safely
        }
        p.sealedJSON=entry.metadataJson; p.key=entry.clientEventId
        b6Append(B6Event("source_intent_sealed",now(),entities=listOf(hash(p.remoteID.lowercase())),revision=p.revision.toInt(),
            localID=s.localID,remoteID=p.remoteID,bodyValueJSON=p.bodyJSON,originalIntentJSON=s.tokenJSON,
            sealedAttemptJSON=p.sealedJSON,operationKey=p.key,preparedLocalBodyJSON=p.localJSON))
    }
    }
    fun b6Dispatched(entry: SyncEventOutboxEntry, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val p=b6Prepared ?: return@b6Safely
        if(entry.clientEventId==p.key && entry.metadataJson==p.sealedJSON)
            b6Append(B6Event("source_exact_attempt_dispatched",now(),remoteID=p.remoteID,
                operationKey=p.key,sealedAttemptJSON=p.sealedJSON,outcome="attempt="+entry.attemptCount))
    }
    }
    fun b6Response(entry: SyncEventOutboxEntry, accepted: Boolean, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val p=b6Prepared ?: return@b6Safely
        if(entry.clientEventId==p.key && entry.metadataJson==p.sealedJSON)
            b6Append(B6Event("source_attempt_response_returned",now(),remoteID=p.remoteID,operationKey=p.key,
                outcome=if(accepted) "ACCEPTED_BEFORE_LOCAL_ACK" else "NOT_ACKNOWLEDGED"))
    }
    }
    /** Call after the actual ACK transaction returns, never from its acknowledgement callback. */
    fun b6OwnAck(entry: SyncEventOutboxEntry, confirmation: BusinessWriteConfirmation, returnedNS: Long, lease: B6LeaseStamp? = null) = b6SourceHook(returnedNS) {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val p=b6Prepared ?: return@b6Safely; val s=b6Source ?: return@b6Safely
        if(entry.clientEventId!=p.key || entry.metadataJson!=p.sealedJSON) return@b6Safely
        if(b6Events.none { it.stage=="source_exact_attempt_dispatched" && it.operationKey==p.key }) {
            b6Invalid="OWN_ACK_WITHOUT_EXACT_DISPATCH"; return@b6Safely
        }
        val version=(confirmation as? BusinessWriteConfirmation.ConditionalProduct)?.row?.updatedAt
        b6Append(B6Event("source_actual_ack_identity_persisted",returnedNS,entities=listOf(hash(p.remoteID.lowercase())),
            revision=p.revision.toInt(),localID=s.localID,remoteID=p.remoteID,bodyValueJSON=p.bodyJSON,
            originalIntentJSON=s.tokenJSON,sealedAttemptJSON=p.sealedJSON,operationKey=p.key,preparedLocalBodyJSON=p.localJSON,
            versionRaw=version,remoteVersionHash=version?.let(::hash),outcome=if(version==null) "VERSION_UNKNOWN" else "ACTUAL_RETURNED_VERSION"))
    }
    }

    private var b6HistoryRows: List<SharedSheetSessionRecord> = emptyList()
    @Synchronized fun b6RememberHistoryRows(rows: List<SharedSheetSessionRecord>) = b6Safely {
        if(b6Current()?.category=="history") b6HistoryRows=rows.take(501)
    }
    @Synchronized private fun b6AttachPortableProduct(cycle: B6Ticket?, row: InventoryProductRow) = b6Safely {
        val old=b6Applied ?: return@b6Safely
        if(b6Current()?.category !in setOf("create","update")) return@b6Safely
        if(cycle?.cycleID==old.cycleID && old.remoteID==row.id) {
            b6Applied=old.copy(bodyValueJSON=PilotB6Values.product(row),versionRaw=row.updatedAt)
            b6Append(requireNotNull(b6Applied).copy(stage="peer_actual_body_values"))
        }
    }
    @Synchronized fun b6AppliedPrice(cycle: B6Ticket?, row: InventoryProductPriceRow, local: ProductPrice) = b6Safely {
        val binding=b6Current() ?: return@b6Safely
        if(binding.category!="price") return@b6Safely
        if(local.productId!=binding.localProductID || row.productId!=binding.parentRemoteID) return@b6Safely
        b6AppliedEntity(cycle,row.id,local.id,hash(PilotB6Values.localPrice(local)),
            PilotB6Values.price(row),row.updatedAt,0)
    }
    @Synchronized fun b6AppliedHistory(cycle: B6Ticket?, remoteID: String, local: HistoryEntry, revision: Int) = b6Safely {
        if(b6Current()?.let { it.category!="history" || it.localEntityID!=local.uid } != false) return@b6Safely
        val row=b6HistoryRows.singleOrNull { it.remoteId==remoteID } ?: return@b6Safely
        // Only the actual written local payload can qualify; a skipped/link-only row never calls this.
        b6AppliedEntity(cycle,remoteID,local.uid,hash(PilotB6Values.localHistory(local)),
            PilotB6Values.history(row),row.updatedAt,revision)
    }
    private fun b6AppliedEntity(cycle: B6Ticket?, remoteID: String, localID: Long, localBody: String,
        value: String, version: String?, revision: Int) {
        val b=b6Current() ?: return; val t=b6Applying ?: return
        if(cycle?.operationID!=t.operationID || cycle.cycleID!=t.cycleID ||
            (b.entityHashes.isNotEmpty() && hash(remoteID.lowercase()) !in b.entityHashes) ||
            (if(b.category=="price") !priceExpected(b,value) else localBody!=b.expectedLocalBodyHash)) return
        if(b6Applied!=null || version.isNullOrBlank()) { b6Invalid="APPLIED_FACTS_UNQUALIFIED"; return }
        b6Applied=B6Event("peer_entity_applied",now(),t.cycleID,listOf(hash(remoteID.lowercase())),revision,
            localBody,applyID=t.applyID,remoteVersionHash=hash(version),localID=localID,
            remoteID=remoteID,bodyValueJSON=bounded(value),versionRaw=version)
        b6Append(requireNotNull(b6Applied))
    }
    fun b6HistoryRenderDetail(entry: HistoryDisplayEntry, displayTitle: String, metadataText: String): String? {
        if(!b6Armed) return null
        return runCatching {
            val b=synchronized(this) { b6Current()?.takeIf { it.role=="peer" && it.category=="history" } } ?: return null
            val projection=bounded(buildJsonObject {
                put("route","History");put("displayTitle",displayTitle);put("metadataText",metadataText)
                put("displayName",entry.displayName);put("timestamp",entry.timestamp)
                put("supplier",entry.supplier);put("category",entry.category)
            }.toString())
            "b6-projection:${b.operationID}:history:${entry.uid}:${hash(projection)}"
        }.getOrNull()
    }
    private fun validPriceExpectation(value: String): Boolean = runCatching {
        val obj=Json.parseToJsonElement(bounded(value)).jsonObject
        obj.keys==setOf("productID","type","price","source","note")
    }.getOrDefault(false)
    private fun priceExpected(b: B6Binding, actual: String): Boolean = runCatching {
        val expected=Json.parseToJsonElement(requireNotNull(b.expectedPriceValueJSON)).jsonObject
        val value=Json.parseToJsonElement(actual).jsonObject
        validPriceExpectation(requireNotNull(b.expectedPriceValueJSON)) && expected.all { (key,v) -> value[key]==v }
    }.getOrDefault(false)
    fun b6PriceHistoryRenderDetail(row: ProductPrice, displayedPrice: String, displayedDateTime: String, displayedSource: String): String? {
        if(!b6Armed) return null
        return runCatching {
            val b=synchronized(this) { b6Current()?.takeIf { it.role=="peer" && it.category=="price" && it.localProductID==row.productId } } ?: return null
            val projection=buildJsonObject {
                put("route","PriceHistory");put("price",displayedPrice);put("dateTime",displayedDateTime);put("source",displayedSource)
                put("type",row.type);put("effectiveAt",row.effectiveAt)
            }
            val value=buildJsonObject {
                put("operationID",b.operationID);put("localID",row.id)
                put("bodyHash",hash(PilotB6Values.localPrice(row)));put("values",projection)
            }
            "b6-price:"+bounded(value.toString())
        }.getOrNull()
    }
    @Synchronized fun b6ProjectionRendered(kind: String, localID: Long, detail: String?, ns: Long) = b6Safely {
        val b=b6Current() ?: return@b6Safely; val committed=b6Committed ?: return@b6Safely
        if(kind=="price") {
            if(b.category!="price" || b.role!="peer" || committed.localID!=localID || ns<committed.ns || detail?.startsWith("b6-price:")!=true) return@b6Safely
            val node=Json.parseToJsonElement(bounded(detail.removePrefix("b6-price:"))).jsonObject
            val values=node.getValue("values").jsonObject
            val expected=Json.parseToJsonElement(requireNotNull(b.expectedPriceVisibleJSON)).jsonObject
            if(expected.keys!=setOf("price","type","source") || !expected.all { (key,value) -> values[key]==value } ||
                node["operationID"]?.jsonPrimitive?.content!=b.operationID ||
                node["localID"]?.jsonPrimitive?.longOrNull!=localID || node["bodyHash"]?.jsonPrimitive?.content!=committed.bodyHash) return@b6Safely
            if(b6Events.none { it.stage=="peer_expected_entity_draw" }) b6Append(committed.copy(
                stage="peer_expected_entity_draw",ns=ns,visibleProjectionHash=hash(values.toString()),visibleValueJSON=values.toString()))
            return@b6Safely
        }
        val expected=b.expectedVisibleProjectionHash ?: return@b6Safely
        if(b.category!=kind || b.role!="peer" || committed.localID!=localID || ns<committed.ns ||
            detail!="b6-projection:${b.operationID}:$kind:$localID:$expected") return@b6Safely
        if(b6Events.none { it.stage=="peer_expected_entity_draw" }) b6Append(committed.copy(
            stage="peer_expected_entity_draw",ns=ns,visibleProjectionHash=expected))
    }

    private var b6DeleteIntent: PendingCatalogTombstone? = null
    private var b6DeletePatch: CatalogTombstonePatch? = null
    private fun tombstoneJSON(row: PendingCatalogTombstone): String = buildJsonObject {
        put("id",row.id);put("entityType",row.entityType);put("remoteID",row.remoteId)
        put("enqueuedAtMs",row.enqueuedAtMs);put("attemptCount",row.attemptCount)
    }.toString()
    fun b6CaptureDelete(product: Product, row: PendingCatalogTombstone) = b6SourceHook() {
        b6Safely {
        val b=b6Current() ?: return@b6Safely
        if(b.category!="delete" || b6Local==null || b.localProductID!=product.id) return@b6Safely
        if(row.id<=0 || row.entityType!=PendingCatalogTombstoneEntityTypes.PRODUCT ||
            hash(row.remoteId.lowercase()) !in b.entityHashes) { b6Invalid="DELETE_INTENT_UNQUALIFIED";return@b6Safely }
        b6DeleteIntent=row
        val deleted="{\"deleted\":true}"
        b6Source=SourceIntent(product.id,deleted,hash(deleted),row.remoteId,null,bounded(buildJsonObject {
            put("localID",product.id);put("baseline",b6Json.parseToJsonElement(localProductJSON(product)))
            put("tombstone",b6Json.parseToJsonElement(tombstoneJSON(row)))
        }.toString()))
        b6Append(B6Event("local_intent_staged",now(),entities=b.entityHashes,localID=product.id,
            remoteID=row.remoteId,originalIntentJSON=b6Source?.tokenJSON))
    }
    }
    fun b6DeleteDispatched(row: PendingCatalogTombstone, patch: CatalogTombstonePatch, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val b=b6Current() ?: return@b6Safely; val original=b6DeleteIntent ?: return@b6Safely
        if(row.id!=original.id) return@b6Safely
        if(row.copy(attemptCount=original.attemptCount)!=original || row.attemptCount<original.attemptCount ||
            patch.id!=original.remoteId || hash(task126OwnerHash(patch.ownerUserId)+"\n"+(patch.shopId?.let { "shop:$it" }.orEmpty()))!=b.scopeHash) {
            b6Invalid="DELETE_INTENT_CHANGED";return@b6Safely
        }
        b6DeletePatch=patch
        b6Append(B6Event("source_exact_attempt_dispatched",now(),remoteID=patch.id,
            originalIntentJSON=b6Source?.tokenJSON,sealedAttemptJSON=b6Json.encodeToString(CatalogTombstonePatch.serializer(),patch),
            operationKey="tombstone:"+row.id,outcome="attempt="+row.attemptCount))
    }
    }
    fun b6DeleteResponse(row: PendingCatalogTombstone, accepted: Boolean, lease: B6LeaseStamp? = null) = b6SourceHook() {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        if(row.id==b6DeleteIntent?.id) b6Append(B6Event("source_attempt_response_returned",now(),
            remoteID=row.remoteId,operationKey="tombstone:"+row.id,
            outcome=if(accepted) "ACCEPTED_BEFORE_LOCAL_ACK" else "NOT_ACKNOWLEDGED"))
    }
    }
    fun b6DeleteAcknowledged(row: PendingCatalogTombstone, returnedNS: Long, lease: B6LeaseStamp? = null) = b6SourceHook(returnedNS) {
        b6Safely {
        if(!b6RequireLease(lease,cloudOnly=true)) return@b6Safely
        val original=b6DeleteIntent ?: return@b6Safely;val patch=b6DeletePatch ?: return@b6Safely
        if(row.id!=original.id || row.remoteId!=patch.id) return@b6Safely
        b6Append(B6Event("source_actual_ack_identity_persisted",returnedNS,remoteID=row.remoteId,
            entities=listOf(hash(row.remoteId.lowercase())),localID=b6Source?.localID,bodyValueJSON="{\"deleted\":true}",
            originalIntentJSON=b6Source?.tokenJSON,sealedAttemptJSON=b6Json.encodeToString(CatalogTombstonePatch.serializer(),patch),
            operationKey="tombstone:"+row.id,outcome="VERSION_UNKNOWN",deletedAtRaw=patch.deletedAt))
    }
    }
    @Synchronized fun b6AppliedDelete(cycle: B6Ticket?, row: InventoryProductRow, localID: Long) = b6Safely {
        if(b6Current()?.category!="delete" || row.deletedAt==null) return@b6Safely
        val value="{\"deleted\":true}"
        b6AppliedEntity(cycle,row.id,localID,hash(value),value,row.updatedAt,0)
        b6Applied?.takeIf { it.remoteID==row.id && it.cycleID==cycle?.cycleID }?.let {
            b6Applied=it.copy(deletedAtRaw=row.deletedAt)
            b6Append(requireNotNull(b6Applied).copy(stage="peer_actual_tombstone_value"))
        }
    }
    /** Complete bounded Paging dataset only, never lazy viewport absence. No query is started. */
    fun b6DeleteRenderDetail(filter: String, appliedFilter: String?, rows: List<Product>, complete: Boolean,
        placeholders: Int, visibleIDs: Set<Long>): String? {
        if(!b6Armed) return null
        return runCatching {
            val b=synchronized(this) { b6Current()?.takeIf { it.role=="peer" && it.category=="delete" } } ?: return null
            if(!complete || placeholders!=0 || rows.size !in 1..100 ||
                b.deleteQuery==null || filter!=b.deleteQuery || appliedFilter!=b.deleteQuery ||
                b.deletedBarcode.isNullOrBlank() || b.controlBarcode.isNullOrBlank() ||
                rows.any { it.barcode==b.deletedBarcode } || rows.count { it.barcode==b.controlBarcode }!=1 ||
                rows.single { it.barcode==b.controlBarcode }.id !in visibleIDs) return null
            val projection=buildJsonObject {
                put("route","Database");put("query",filter);put("absentBarcode",b.deletedBarcode)
                put("controlBarcode",b.controlBarcode);put("count",rows.size)
            }.toString()
            "b6-projection:${b.operationID}:delete:${b.localProductID}:${hash(projection)}"
        }.getOrNull()
    }

    @Synchronized fun b6CalibrationFromTestOnly(nonce: String, bootHash: String): B6Reply? = runCatching {
        if(b6Binding==null) return null;val first=b6Reply ?: return null
        if(!b6AuthorityCurrent() || !BuildConfig.DEBUG || nonce==first.nonce || bootHash!=first.bootHash || android.os.Process.myPid()!=first.participantPID) return null
        UUID.fromString(nonce)
        first.copy(nonce=nonce,remoteNS=now())
    }.getOrNull()

    @Synchronized fun b6SnapshotFromTestOnly(disarm: Boolean=false): B6Receipt? = runCatching {
        val b=b6Binding ?: return null; val reply=b6Reply ?: return null
        if(!BuildConfig.DEBUG) return null
        if(b6Armed) b6AuthorityCurrent() // No new observation timeout at export/final calibration.
        if(disarm) b6Armed=false
        B6Receipt(b,reply,b6Events.toList(),b6Invalid,authorityStamp=b6Authority?.stamp)
    }.getOrNull()

}

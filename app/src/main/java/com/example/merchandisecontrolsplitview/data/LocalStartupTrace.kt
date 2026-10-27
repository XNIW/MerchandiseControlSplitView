package com.example.merchandisecontrolsplitview.data

import android.os.SystemClock
import android.util.Log
import com.example.merchandisecontrolsplitview.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Static cost labels only; bucket order is pinned by the startup diagnostic packet. */
internal enum class StartupCostStage {
    RECEIPT_HEADER, RECEIPT_RELATIONAL, RECEIPT_MANIFEST, RECEIPT_PRICE_PARENTS,
    MANIFEST_FETCH, MANIFEST_DIGEST, PRICE_PAGE_FETCH, PRICE_PARENT_FETCH, PRICE_PARENT_VALIDATE,
    PHYSICAL_EXPECTED, PHYSICAL_QUERY_OPEN, PHYSICAL_CURSOR_MOVE, PHYSICAL_PROJECTION, PHYSICAL_COMPARE,
    PHYSICAL_HISTORY_FETCH
}

/** One ordinary offline-scope attempt. Observation only; never changes authorization or cancellation. */
internal class LocalStartupTrace(
    scopeIdentityHash: String?,
    private val nowNS: () -> Long = SYSTEM_CLOCK,
    private val sink: (String) -> Unit = { Log.i("MerchandiseApp", it) }
) : AbstractCoroutineContextElement(Key) {
    private val attempt = nextAttempt.incrementAndGet()
    private val startedNS = nowNS()
    private val scopeHash = scopeIdentityHash?.takeIf { it.matches(SHA256) } ?: "unknown"
    private var dbGenerationHash = "unknown"
    private val activeSpans = linkedMapOf<String, Long>()
    private val spanNS = linkedMapOf<String, Long>()
    private val spanCounts = linkedMapOf<String, Long>()
    private val checkpointCounts = linkedMapOf<String, Long>()
    private var physicalReadStartedNS: Long? = null
    private var physicalReadNS = 0L
    private var firstReason: String? = null
    private var emittedLines = 0
    private var droppedLines = 0L
    private var finished = false
    private var diagnosticFailure = false
    // One fixed allocation per attempt; no row-level map, Pair, nullable-Long or bucket object.
    private val systemCostClock = nowNS === SYSTEM_CLOCK
    private val costNS = LongArray(StartupCostStage.entries.size * COST_DOMAIN_SLOTS)
    private val costCounts = LongArray(costNS.size)
    private var admissionCaller: String? = null

    fun event(stage: String, reason: String? = null) = observe {
        if (finished || stage !in EVENT_STAGES || (reason != null && reason !in REASONS)) return@observe
        if (reason != null && reason !in ACCESS_REASONS && firstReason == null) firstReason = reason
        emit("event", stage, reason)
    }

    /** Capture only a static caller label; a duplicate/unknown caller cannot leak identifiers. */
    fun admission(reason: String) = observe {
        if (finished || reason !in ADMISSION_REASONS || admissionCaller != null) return@observe
        admissionCaller = reason
        emit("admission", details = "caller=$reason")
    }

    /** Primitive sentinel keeps a failed diagnostic clock distinct from an inactive trace. */
    internal fun beginCost(): Long {
        if (finished) return COST_INACTIVE
        return try {
            val now = if (systemCostClock) SystemClock.elapsedRealtimeNanos() else nowNS()
            if (now >= 0L) now else { diagnosticFailure = true; COST_UNKNOWN }
        } catch (_: Throwable) { diagnosticFailure = true; COST_UNKNOWN }
    }

    internal fun endCost(stage: StartupCostStage, domain: ShopSyncRowDomain?, started: Long) = observe {
        if (started == COST_INACTIVE) return@observe
        // This is the distinct end boundary, not a retry of a failed begin read.
        val ended = try {
            if (systemCostClock) SystemClock.elapsedRealtimeNanos() else nowNS()
        } catch (_: Throwable) { diagnosticFailure = true; COST_UNKNOWN }
        if (finished) return@observe
        val bucket = stage.ordinal * COST_DOMAIN_SLOTS + if (domain == null) 0 else domain.ordinal + 1
        val count = costCounts[bucket]
        if (count == Long.MAX_VALUE) diagnosticFailure = true else costCounts[bucket] = count + 1L
        if (started < 0L || ended < started) { diagnosticFailure = true; return@observe }
        val duration = ended - started
        if (costNS[bucket] > Long.MAX_VALUE - duration) diagnosticFailure = true
        else costNS[bucket] += duration
    }

    fun begin(stage: String) = observe {
        if (finished || stage !in SPAN_STAGES || stage in activeSpans) return@observe
        val now = nowNS()
        activeSpans[stage] = now
        emit("span_begin", stage, atNS = now)
    }

    fun end(stage: String, outcome: String = "ok") = observe {
        if (finished || stage !in SPAN_STAGES || outcome !in OUTCOMES) return@observe
        val start = activeSpans.remove(stage) ?: return@observe
        val now = nowNS()
        val duration = duration(start, now)
        spanNS[stage] = (spanNS[stage] ?: 0L) + duration
        spanCounts[stage] = (spanCounts[stage] ?: 0L) + 1L
        emit("span_end", stage, details = "outcome=$outcome duration_ns=$duration", atNS = now)
    }

    /** This is the DB proof's generation, not a tracker generation or an authorization claim. */
    fun generation(rawGeneration: String) = observe {
        if (finished) return@observe
        val hash = hashIdentity(rawGeneration)
        if (hash == dbGenerationHash) return@observe
        dbGenerationHash = hash
        emit("db_generation", details = "db_generation_sha256=$hash")
    }

    /** Preserve the caller's lazy short circuit and the original exception object. */
    fun fact(reason: String, block: () -> Boolean): Boolean {
        val actual = block()
        if (!actual) observe {
            if (!finished && reason in REASONS) {
                if (firstReason == null) firstReason = reason
                emit("predicate_false", reason = reason)
            }
        }
        return actual
    }

    /** Compare the original immutable pair once; diagnostics never reread auth state or identifiers. */
    fun localIdentityEquals(captured: LocalAuthIdentity?, current: LocalAuthIdentity?): Boolean {
        val actual = current == captured
        if (!actual) observe {
            if (finished) return@observe
            val sameUser = if (captured != null && current != null) captured.userId == current.userId else null
            val sameGeneration = if (captured != null && current != null) captured.authGeneration == current.authGeneration else null
            val difference = when {
                captured == null -> "CAPTURED_NULL_CURRENT_PRESENT"
                current == null -> "CAPTURED_PRESENT_CURRENT_NULL"
                sameUser == false && sameGeneration == false -> "USER_AND_GENERATION_CHANGED"
                sameUser == false -> "USER_CHANGED"
                else -> "GENERATION_CHANGED"
            }
            emit("local_identity_mismatch", details =
                "captured_is_null=${captured == null} current_is_null=${current == null} " +
                "same_user=${sameUser?.toString() ?: "not_comparable"} " +
                "captured_auth_generation=${captured?.authGeneration?.toString() ?: "absent"} " +
                "current_auth_generation=${current?.authGeneration?.toString() ?: "absent"} " +
                "same_generation=${sameGeneration?.toString() ?: "not_comparable"} difference=$difference")
        }
        return actual
    }

    /** Existing proof callbacks only. No callback logs; row callbacks only increment counters. */
    fun checkpoint(stage: String) = observe {
        if (finished || stage !in CHECKPOINT_STAGES) return@observe
        checkpointCounts[stage] = (checkpointCounts[stage] ?: 0L) + 1L
        if (stage in ROW_CHECKPOINTS) return@observe
        if (stage == "physical_read_begin") physicalReadStartedNS = nowNS()
        if (stage == "physical_read_end") {
            val started = physicalReadStartedNS
            physicalReadStartedNS = null
            val now = nowNS()
            if (started != null) physicalReadNS += duration(started, now)
        }
    }

    fun finish(outcome: String) = observe {
        if (finished || outcome !in OUTCOMES) return@observe
        finished = true
        val now = nowNS()
        if (costCounts.any { it != 0L }) {
            // Separate bounded records preserve the original terminal even if a cost sink fails.
            observe { emit("cost_time_summary", details =
                "cost_ns=${if (diagnosticFailure) "UNKNOWN" else costBuckets(costNS)}", atNS = now, terminal = true) }
            observe { emit("cost_count_summary", details = "cost_count=${costBuckets(costCounts)}",
                atNS = now, terminal = true) }
        }
        val inFlightReadNS = physicalReadStartedNS?.let { duration(it, now) } ?: 0L
        val details = "outcome=$outcome first_reason=${firstReason ?: "none"} " +
            "db_generation_sha256=$dbGenerationHash physical_read_ns=${if (diagnosticFailure) "UNKNOWN" else physicalReadNS.toString()} " +
            "physical_read_inflight_ns=${if (diagnosticFailure) "UNKNOWN" else inFlightReadNS.toString()} " +
            "span_ns=${if (diagnosticFailure) "UNKNOWN" else counts(spanNS)} span_count=${counts(spanCounts)} " +
            "open_spans=${activeSpans.keys.joinToString(",").ifEmpty { "none" }} " +
            "checkpoint_count=${counts(checkpointCounts)} dropped_log_lines=$droppedLines" +
            (admissionCaller?.let { " caller=$it" } ?: "")
        emit("terminal", details = details, atNS = now, terminal = true)
    }

    /** Only diagnostic bodies enter this boundary; caller predicates and span blocks never do. */
    private inline fun observe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { diagnosticFailure = true /* No fallback clock or retry. */ }
    }

    /** Terminal-only formatting: stage ordinal.domain slot (0=all, domain.ordinal+1 otherwise). */
    private fun costBuckets(values: LongArray): String {
        val text = StringBuilder()
        for (bucket in values.indices) {
            if (costCounts[bucket] == 0L) continue
            if (text.isNotEmpty()) text.append(',')
            text.append(bucket / COST_DOMAIN_SLOTS).append('.').append(bucket % COST_DOMAIN_SLOTS)
                .append(':').append(values[bucket])
        }
        return if (text.isEmpty()) "none" else text.toString()
    }

    private fun counts(values: Map<String, Long>): String =
        values.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }

    private fun duration(start: Long, end: Long): Long = if (end >= start) end - start else 0L

    private fun emit(
        event: String, stage: String? = null, reason: String? = null, details: String? = null,
        atNS: Long? = null, terminal: Boolean = false
    ) {
        if (!terminal && emittedLines >= MAX_NONTERMINAL_LINES) { droppedLines += 1L; return }
        val now = atNS ?: nowNS()
        val elapsed = duration(startedNS, now)
        val line = "local_startup attempt=$attempt scope_sha256=$scopeHash event=$event at_ns=$now elapsed_ns=$elapsed " +
            "diagnostic_status=${if (diagnosticFailure) "INCOMPLETE_UNKNOWN" else "OBSERVED"}" +
            (stage?.let { " stage=$it" } ?: "") + (reason?.let { " reason=$it" } ?: "") +
            (details?.let { " $it" } ?: "")
        emittedLines += 1
        sink(line) // Called only inside observe; a diagnostic Throwable cannot alter the caller result.
    }

    companion object Key : CoroutineContext.Key<LocalStartupTrace> {
        private val SYSTEM_CLOCK: () -> Long = SystemClock::elapsedRealtimeNanos
        private val COST_DOMAIN_SLOTS = ShopSyncRowDomain.entries.size + 1
        private const val COST_INACTIVE = Long.MIN_VALUE
        private const val COST_UNKNOWN = Long.MIN_VALUE + 1L
        private val ADMISSION_REASONS = setOf(
            "direct", "auth_checking_restored", "auth_signed_in_restored", "auth_refresh_unavailable",
            "shop_context_offline", "auth_refresh_ready", "shop_context_ready")
        private val nextAttempt = AtomicLong()
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val MAX_NONTERMINAL_LINES = 63
        private val EVENT_STAGES = setOf(
            "mutex_requested", "mutex_acquired", "scope_resolved", "scope_published", "scope_discarded", "scope_unavailable",
            "app_on_create", "room_checking", "room_ready", "room_unavailable", "offline_scope", "cloud_align", "proof_joined")
        private val SPAN_STAGES = setOf(
            "resolve", "persisted_device", "transaction_wait", "binding", "counts", "journal", "quick_check",
            "foreign_key", "baseline", "watermark", "receipt", "physical",
            "auth_restore_dispatch", "room_open", "business_consumers_init")
        private val ACCESS_REASONS = setOf("local_read_write", "local_read_only", "local_access_unavailable")
        private val REASONS = setOf(
            "owner_unavailable", "device_unavailable", "auth_state_changed", "local_identity_changed",
            "local_identity_not_current", "auth_not_authorized", "shop_context_changed", "shop_epoch_changed",
            "device_changed", "context_owner_mismatch", "local_access_disallowed", "persisted_device_changed",
            "binding_missing", "owner_store_blocked", "journal_ineligible", "quick_check_failed", "foreign_key_failed",
            "baseline_scope_mismatch", "checkpoint_scope_mismatch", "watermark_invalid", "validation_failed",
            "local_read_write", "local_read_only", "local_access_unavailable", "stale_result",
            "room_open_failed", "application_terminated")
        private val OUTCOMES = setOf("ok", "cancelled", "failed", "discarded", "unavailable")
        private val ROW_CHECKPOINTS = setOf(
            "history_row", "history_manifest_row", "canonical_row", "relational_row", "physical_compare_row",
            "price_parent_row", "physical_sql_row", "active_partition_row")
        private val CHECKPOINT_STAGES = ROW_CHECKPOINTS + setOf(
            "receipt_begin", "receipt_end", "history_page", "history_query_end", "canonical_page", "canonical_page_end",
            "relational_begin", "physical_begin", "physical_page", "physical_read_begin", "physical_read_end",
            "overlay_or_ack_page", "overlay_query_end", "ack_canonical_page_end", "ack_page_end", "ack_query_end",
            "price_parents_begin", "price_page", "price_page_end", "price_count_page", "physical_sql_begin",
            "active_partition_shape", "active_partition_page")

        fun start(scopeIdentityHash: String?): LocalStartupTrace? = try {
            if (BuildConfig.DEBUG) LocalStartupTrace(scopeIdentityHash) else null
        } catch (_: Throwable) { null }

        fun hashIdentity(raw: String): String = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/** Inline observation around the original call, including suspension at its existing call site. */
internal inline fun <T> LocalStartupTrace?.span(stage: String, block: () -> T): T {
    if (this == null) return block()
    begin(stage)
    var outcome = "ok"
    try {
        return block()
    } catch (error: Throwable) {
        outcome = if (error is java.util.concurrent.CancellationException) "cancelled" else "failed"
        throw error
    } finally {
        end(stage, outcome)
    }
}

/** The original caller block is never inside a diagnostic catch and runs exactly once.
 * Inline, like span(), so an existing suspend call remains at its original call site. */
internal inline fun <T> LocalStartupTrace?.cost(
    stage: StartupCostStage, domain: ShopSyncRowDomain? = null, block: () -> T
): T {
    if (this == null) return block()
    val started = beginCost()
    try {
        return block()
    } finally {
        endCost(stage, domain, started)
    }
}

package com.example.merchandisecontrolsplitview.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import androidx.room.RoomDatabase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

@Serializable
internal data class NativeResolutionCrashMarker(
    val protocol: String = NativeResolutionContract.PROTOCOL,
    val markerType: String = "task144-native-resolution-crash-v1",
    val runId: String,
    val scenario: String,
    val preparePid: Int,
    val elapsedRealtimeMs: Long,
    val holdDeadlineElapsedRealtimeMs: Long,
    val boundary: String,
    val callbackThreadId: Long,
    val transactionOpen: Boolean,
    val decisionId: String,
    val conflict: NativeResolutionConflictSnapshot,
    val beforeDecisionImage: JsonObject,
    val dbImage: JsonObject,
    val stagedImage: JsonObject?,
    val conditionalKey: String?,
    val conditionalRaw: String?,
    val state: NativeResolutionServerState,
    val serverSha256: String,
    val sql: String?,
    val argumentIdentity: JsonObject
)

/** Existing Room query observer + existing controlled transport holds, no alternate writer.
 * Room 2.8.4 QueryInterceptorStatement launches its callback before delegate execution;
 * the fixture's Executor executes inline. A callback may inspect that thread's uncommitted
 * transaction, but only a distinct read-only SQLite connection supplies committed evidence.
 * Every held marker needs external OS force-stop. A timeout/release is failure, never PASS.
 */
internal class NativeResolutionCrashProbe(private val context: ScopeSwitchContext) : RoomDatabase.QueryCallback {
    private val json get() = NativeResolutionContract.json
    private lateinit var fixture: ScopeSwitchFixture
    private lateinit var conflict: NativeResolutionConflictSnapshot
    private lateinit var before: JsonObject
    private lateinit var decisionId: String
    private var decisionInsertSeen = false
    private val fired = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    @Volatile private var armed = false
    @Volatile var marker: NativeResolutionCrashMarker? = null
        private set
    @Volatile var failure: Throwable? = null
        private set

    fun arm(fixture: ScopeSwitchFixture, conflict: NativeResolutionConflictSnapshot,
        beforeDecisionImage: JsonObject, decisionId: String) {
        check(!armed && !fired.get() && context.scenario in NativeResolutionContract.crashMethods)
        this.fixture = fixture; this.conflict = conflict; before = beforeDecisionImage; this.decisionId = decisionId
        assertEquals(before, committedImage())
        armed = true
    }

    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        if (!armed || fired.get()) return
        try {
            val sql = normalize(sqlQuery)
            val raw = bindArgs.getOrNull(11) as? String
            val isEntryInsert = sql == INSERT_ENTRY
            val isConditional = (isEntryInsert || sql == UPDATE_ENTRY) && bindArgs.size == (if (isEntryInsert) 16 else 17) &&
                bindArgs[1] == NativeAckContract.OWNER && bindArgs[2] == NativeAckContract.scope.storeId &&
                bindArgs[3] == "PATCH_CONDITIONAL" && bindArgs[4] == BUSINESS_WRITE_EVENT &&
                bindArgs[6] == NativeAckContract.DEVICE && raw != null &&
                bindArgs[8] == "business-write-v1:" + nativeSha(raw.encodeToByteArray()) &&
                json.decodeFromString<BusinessWriteAttempt>(raw).let { attempt ->
                    attempt.payload.patchId == conflict.remoteId && attempt.payload.revisions.single().revision == conflict.revisionA + 1L &&
                        attempt.payload.patch?.changedFields == setOf("productname") &&
                        attempt.payload.expectedUpdatedAt == conflict.remoteAtComparison.updatedAt &&
                        attempt.generation == conflict.generation
                }
            val isDecision = isEntryInsert && bindArgs.size == 16 && bindArgs[4] == BUSINESS_RESOLUTION_EVENT &&
                bindArgs[1] == NativeAckContract.OWNER && bindArgs[2] == NativeAckContract.scope.storeId &&
                bindArgs[8] == "business-resolution-v1:$decisionId" && raw != null &&
                json.decodeFromString<BusinessResolutionRecord>(raw).let {
                    it.kind == "decision" && it.recordId == decisionId && it.remoteId == conflict.remoteId &&
                        it.decision?.conflictRevision == conflict.revisionA
                }
            if (isDecision) decisionInsertSeen = true
            val stage = when {
                context.scenario == "r4-crash-before-seal" && isConditional && isEntryInsert -> "before_successor_seal_insert"
                context.scenario == "r4-crash-before-decision" && isDecision -> "after_successor_seal_before_decision_insert"
                context.scenario == "r4-crash-before-commit" && decisionInsertSeen && sql == "TRANSACTION SUCCESSFUL" -> "after_decision_insert_before_transaction_success"
                context.scenario == "r4-crash-after-commit" && isConditional && sql == UPDATE_ENTRY &&
                    (bindArgs[13] as? Number)?.toLong() == 1L && bindArgs[14] != null && bindArgs[15] == null -> "after_decision_commit_before_dispatch_stamp"
                context.scenario == "r4-crash-before-ack" && sql == ACK_PRODUCT && bindArgs.size == 7 &&
                    (bindArgs[0] as? Number)?.toLong() == conflict.revisionA + 1L &&
                    (bindArgs[4] as? Number)?.toLong() == conflict.revisionA + 1L &&
                    (bindArgs[5] as? Number)?.toLong() == conflict.productId &&
                    (bindArgs[6] as? Number)?.toLong() == conflict.revisionA + 1L -> "after_response_before_ack_projection"
                context.scenario == "r4-crash-during-ack" && sql == DELETE_ENTRY && bindArgs.size == 1 &&
                    isSuccessorId((bindArgs[0] as? Number)?.toLong()) -> "after_ack_projection_before_receipt_delete"
                else -> null
            } ?: return
            publishAndHold(stage, sqlQuery, buildJsonObject {
                put("decisionId", decisionId); put("productId", conflict.productId); put("remoteId", conflict.remoteId)
                put("successorRevision", conflict.revisionA + 1)
                if (isConditional) { put("operationKey", bindArgs[8] as String); put("rawSha256", nativeSha(requireNotNull(raw).encodeToByteArray())) }
                if (isDecision) put("decisionRawSha256", nativeSha(requireNotNull(raw).encodeToByteArray()))
                if (sql == DELETE_ENTRY) put("entryId", (bindArgs[0] as Number).toLong())
            })
        } catch (problem: Throwable) {
            recordFailure(problem)
            throw problem
        }
    }

    private fun isSuccessorId(id: Long?): Boolean {
        if (id == null) return false
        val sql = fixture.db.openHelper.readableDatabase
        return sql.query("SELECT metadataJson, clientEventId FROM sync_event_outbox WHERE id=? AND eventType=? AND domain=?",
            arrayOf<Any?>(id, BUSINESS_WRITE_EVENT, "PATCH_CONDITIONAL")).use { c ->
            if (!c.moveToFirst()) false else {
                val raw = c.getString(0)
                val attempt = json.decodeFromString<BusinessWriteAttempt>(raw)
                c.getString(1) == "business-write-v1:" + nativeSha(raw.encodeToByteArray()) &&
                    attempt.payload.patchId == conflict.remoteId && attempt.payload.revisions.single().revision == conflict.revisionA + 1L
            }
        }
    }

    fun holdBeforeRemoteCommit() = publishAndHold("after_dispatch_before_remote_commit", null, buildJsonObject { })
    fun holdAfterRemoteCommit() = publishAndHold("after_remote_commit_before_response", null, buildJsonObject { })

    fun publishAfterAck() {
        check(context.scenario == "r4-crash-after-ack")
        publishAndHold("after_ack_transaction_committed", null, buildJsonObject { })
    }

    private fun publishAndHold(boundary: String, sql: String?, identity: JsonObject) {
        check(armed && fired.compareAndSet(false, true))
        val db = fixture.db.openHelper.readableDatabase
        val inTransaction = db.inTransaction()
        if (sql != null) check(inTransaction) { "crash_query_callback_not_inline_transaction" }
        val staged = if (inTransaction) image { db.query(it) } else null
        val committed = committedImage()
        val remote = requireNotNull(fixture.resolutionBackend)
        val state = remote.state()
        val current = (staged ?: committed)
        val successor = successor(current)
        val call = state.calls.drop(conflict.callsBeforeDecision).singleOrNull()
        val conditionalKey = successor?.first ?: call?.operationKey
        val conditionalRaw = successor?.second ?: call?.rawAttempt
        val now = SystemClock.elapsedRealtime()
        // Keep the API-35-compatible Java thread ID; threadId() starts at API 36.
        @Suppress("DEPRECATION") val callbackThreadId = Thread.currentThread().id
        val prepared = NativeResolutionCrashMarker(runId = context.runId, scenario = context.scenario,
            preparePid = Process.myPid(), elapsedRealtimeMs = now, holdDeadlineElapsedRealtimeMs = now + HOLD_MS,
            boundary = boundary, callbackThreadId = callbackThreadId, transactionOpen = inTransaction,
            decisionId = decisionId, conflict = conflict, beforeDecisionImage = before, dbImage = committed,
            stagedImage = staged, conditionalKey = conditionalKey, conditionalRaw = conditionalRaw, state = state,
            serverSha256 = nativeSha(remote.file.readBytes()), sql = sql, argumentIdentity = identity)
        assertBoundary(prepared)
        val file = File(context.root, "ready-for-force-stop.json")
        check(!file.exists())
        nativeDurableWrite(file, json.encodeToString(prepared).encodeToByteArray())
        marker = prepared
        try {
            val explicitlyReleased = released.await(HOLD_MS, TimeUnit.MILLISECONDS)
            throw AssertionError(if (explicitlyReleased) "crash_boundary_released_without_owned_OS_kill" else "crash_boundary_OS_kill_timeout")
        } catch (problem: Throwable) {
            recordFailure(problem)
            throw problem
        }
    }

    fun disarm() { armed = false; released.countDown() }

    private fun recordFailure(problem: Throwable) {
        failure = problem
        val file = File(context.root, "crash-boundary-failed.json")
        if (!file.exists()) nativeDurableWrite(file, buildJsonObject {
            put("protocol", NativeResolutionContract.PROTOCOL); put("runId", context.runId); put("scenario", context.scenario)
            put("pid", Process.myPid()); put("type", problem::class.java.name); put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        }.toString().encodeToByteArray())
    }

    /** Independent connection and a pinned read transaction; no Room repair/observer/callback. */
    private fun committedImage(): JsonObject {
        val path = context.getDatabasePath(ScopeSwitchContract.DB)
        check(path.isFile && path.canonicalFile == path.absoluteFile)
        return SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { connection ->
            connection.execSQL("BEGIN DEFERRED TRANSACTION")
            try { image { connection.rawQuery(it, null) } }
            finally { connection.execSQL("ROLLBACK") }
        }
    }

    companion object {
        const val HOLD_MS = 120_000L
        private fun normalize(sql: String) = sql.trim().replace(Regex("\\s+"), " ")
        private const val INSERT_ENTRY = "INSERT OR IGNORE INTO `sync_event_outbox` (`id`,`ownerUserId`,`storeScope`,`domain`,`eventType`,`source`,`sourceDeviceId`,`batchId`,`clientEventId`,`changedCount`,`entityIdsJson`,`metadataJson`,`createdAtMs`,`attemptCount`,`lastAttemptAtMs`,`lastErrorType`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        private const val UPDATE_ENTRY = "UPDATE OR ABORT `sync_event_outbox` SET `id` = ?,`ownerUserId` = ?,`storeScope` = ?,`domain` = ?,`eventType` = ?,`source` = ?,`sourceDeviceId` = ?,`batchId` = ?,`clientEventId` = ?,`changedCount` = ?,`entityIdsJson` = ?,`metadataJson` = ?,`createdAtMs` = ?,`attemptCount` = ?,`lastAttemptAtMs` = ?,`lastErrorType` = ? WHERE `id` = ?"
        private const val DELETE_ENTRY = "DELETE FROM sync_event_outbox WHERE id = ?"
        private const val ACK_PRODUCT = "UPDATE product_remote_refs SET lastSyncedLocalRevision = ?, lastRemoteAppliedAt = ?, lastRemotePayloadFingerprint = ?, remoteUpdatedAt = COALESCE(?, remoteUpdatedAt), localChangedFields = CASE WHEN localChangeRevision = ? THEN NULL ELSE localChangedFields END WHERE productId = ? AND lastSyncedLocalRevision <= ?"

        private fun image(query: (String) -> Cursor): JsonObject {
            val tables = query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            return buildJsonObject {
                for (table in tables) {
                    check(Regex("[A-Za-z0-9_]+").matches(table))
                    val rows = query("SELECT * FROM `$table`").use { c ->
                        buildList { while (c.moveToNext()) add(buildJsonObject {
                            c.columnNames.forEachIndexed { index, column -> put(column, when (c.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> "null"
                                Cursor.FIELD_TYPE_BLOB -> "blob:" + android.util.Base64.encodeToString(c.getBlob(index), android.util.Base64.NO_WRAP)
                                else -> "${c.getType(index)}:" + c.getString(index)
                            }) }
                        }.toString()) }.sorted()
                    }
                    put(table, JsonArray(rows.map(::JsonPrimitive)))
                }
            }
        }
        private fun rows(image: JsonObject, table: String): List<JsonObject> = image.getValue(table).jsonArray.map {
            NativeResolutionContract.json.parseToJsonElement(it.jsonPrimitive.content).jsonObject
        }
        private fun value(row: JsonObject, field: String): String? = row.getValue(field).jsonPrimitive.content.let {
            if (it == "null") null else it.substringAfter(':')
        }
        private fun decisions(image: JsonObject): List<BusinessResolutionRecord> = rows(image, "sync_event_outbox")
            .filter { value(it, "eventType") == BUSINESS_RESOLUTION_EVENT }
            .map { NativeResolutionContract.json.decodeFromString<BusinessResolutionRecord>(requireNotNull(value(it, "metadataJson"))) }
            .filter { it.kind == "decision" }
        private fun successor(image: JsonObject): Pair<String, String>? = rows(image, "sync_event_outbox")
            .singleOrNull { value(it, "eventType") == BUSINESS_WRITE_EVENT && value(it, "domain") == "PATCH_CONDITIONAL" }
            ?.let { requireNotNull(value(it, "clientEventId")) to requireNotNull(value(it, "metadataJson")) }
        private fun acknowledged(image: JsonObject, productId: Long): Int = requireNotNull(value(rows(image, "product_remote_refs")
            .single { value(it, "productId") == productId.toString() }, "lastSyncedLocalRevision")).toInt()
        private fun localRevision(image: JsonObject, productId: Long): Int = requireNotNull(value(rows(image, "product_remote_refs")
            .single { value(it, "productId") == productId.toString() }, "localChangeRevision")).toInt()

        fun assertBoundary(marker: NativeResolutionCrashMarker) {
            assertEquals("task144-native-resolution-crash-v1", marker.markerType)
            assertEquals(NativeResolutionContract.PROTOCOL, marker.protocol)
            assertEquals(mapOf(
                "r4-crash-before-seal" to "before_successor_seal_insert",
                "r4-crash-before-decision" to "after_successor_seal_before_decision_insert",
                "r4-crash-before-commit" to "after_decision_insert_before_transaction_success",
                "r4-crash-after-commit" to "after_decision_commit_before_dispatch_stamp",
                "r4-crash-before-remote" to "after_dispatch_before_remote_commit",
                "r4-crash-after-remote" to "after_remote_commit_before_response",
                "r4-crash-before-ack" to "after_response_before_ack_projection",
                "r4-crash-during-ack" to "after_ack_projection_before_receipt_delete",
                "r4-crash-after-ack" to "after_ack_transaction_committed").getValue(marker.scenario), marker.boundary)
            val before = marker.conflict
            val preCommit = marker.scenario in setOf("r4-crash-before-seal", "r4-crash-before-decision", "r4-crash-before-commit")
            val postAck = marker.scenario == "r4-crash-after-ack"
            val committedDecisions = decisions(marker.dbImage)
            if (preCommit) {
                assertEquals(marker.beforeDecisionImage, marker.dbImage)
                assertTrue(committedDecisions.isEmpty()); assertNull(successor(marker.dbImage))
                assertEquals(before.revisionA, localRevision(marker.dbImage, before.productId))
                val staged = requireNotNull(marker.stagedImage)
                assertEquals(before.revisionA + 1, localRevision(staged, before.productId))
                assertEquals(before.lastSyncedBeforeA, acknowledged(staged, before.productId))
                if (marker.scenario == "r4-crash-before-seal") assertNull(successor(staged)) else assertNotNull(successor(staged))
                assertEquals(if (marker.scenario == "r4-crash-before-commit") listOf(marker.decisionId) else emptyList<String>(),
                    decisions(staged).map { it.recordId })
            } else {
                assertEquals(marker.decisionId, committedDecisions.single().recordId)
                val decision = requireNotNull(committedDecisions.single().decision)
                assertEquals(before.revisionA + 1, decision.successorRevision)
                assertEquals(listOf("productname"), decision.selectedFields)
                assertEquals(before.remoteAtComparison, decision.remote.row)
                assertEquals(marker.conditionalKey, decision.successorOperationKey)
                assertEquals(before.lastSyncedBeforeA, decision.acknowledgedRevision)
                assertEquals(before.revisionA + 1, localRevision(marker.dbImage, before.productId))
                if (postAck) assertNull(successor(marker.dbImage)) else {
                    assertEquals(marker.conditionalKey to marker.conditionalRaw, successor(marker.dbImage))
                    val entry = rows(marker.dbImage, "sync_event_outbox").single { value(it, "clientEventId") == marker.conditionalKey }
                    assertEquals(if (marker.scenario == "r4-crash-after-commit") "0" else "1", value(entry, "attemptCount"))
                    assertNull(value(entry, "lastErrorType"))
                    assertEquals(marker.scenario == "r4-crash-after-commit", value(entry, "lastAttemptAtMs") == null)
                }
            }
            assertEquals(if (postAck) before.revisionA + 1 else before.lastSyncedBeforeA,
                acknowledged(marker.dbImage, before.productId))
            assertEquals(marker.beforeDecisionImage["product_prices"], marker.dbImage["product_prices"])
            assertEquals(marker.beforeDecisionImage["product_price_remote_refs"], marker.dbImage["product_price_remote_refs"])
            for (original in before.originals) {
                val old = rows(marker.beforeDecisionImage, "sync_event_outbox").single { value(it, "clientEventId") == original.key }
                assertEquals(original.raw, value(old, "metadataJson"))
                assertEquals(old, rows(marker.dbImage, "sync_event_outbox").single { value(it, "clientEventId") == original.key })
            }
            val calls = marker.state.calls.drop(before.callsBeforeDecision)
            val undispatched = preCommit || marker.scenario == "r4-crash-after-commit"
            if (undispatched) {
                assertTrue(calls.isEmpty()); assertEquals(before.remoteAtComparison, marker.state.product)
            } else {
                val call = calls.single()
                assertEquals(marker.conditionalKey, call.operationKey); assertEquals(marker.conditionalRaw, call.rawAttempt)
                assertEquals(before.remoteAtComparison.updatedAt, call.expectedUpdatedAt)
                assertEquals(before.remoteAtComparison.deletedAt, call.expectedDeletedAt)
                assertEquals(1, call.stampedAttemptCount)
                assertEquals(setOf("productname"), call.patch?.changedFields)
                assertEquals(when (marker.scenario) {
                    "r4-crash-before-remote" -> "captured"
                    "r4-crash-after-remote" -> "committed_ack_held"
                    else -> "committed_returned"
                }, call.outcome)
                if (marker.scenario == "r4-crash-before-remote") assertEquals(before.remoteAtComparison, marker.state.product)
                else { assertEquals("Saved A", marker.state.product?.productName); assertEquals("Peer item", marker.state.product?.itemNumber) }
            }
            if (marker.scenario == "r4-crash-during-ack") {
                val staged = requireNotNull(marker.stagedImage)
                assertEquals(before.revisionA + 1, acknowledged(staged, before.productId))
                assertEquals(marker.conditionalKey to marker.conditionalRaw, successor(staged))
            }
            marker.conditionalRaw?.let { raw ->
                assertEquals("business-write-v1:" + nativeSha(raw.encodeToByteArray()), marker.conditionalKey)
                val attempt = NativeResolutionContract.json.decodeFromString<BusinessWriteAttempt>(raw)
                validateBusinessWritePayload(attempt)
                assertEquals(before.generation, attempt.generation)
                assertEquals(before.remoteId, attempt.payload.patchId)
                assertEquals(before.revisionA + 1L, attempt.payload.revisions.single().revision)
            }
        }
    }
}

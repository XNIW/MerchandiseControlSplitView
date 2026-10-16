package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic proposal only. Not copied into the repository, compiled or run.
 * Reuses the existing T20 isolated native fixture and its unchanged validators.
 * Phase invocations are independent proofs: their timings must not be summed
 * or subtracted as if they instrumented a single production call.
 */
@RunWith(AndroidJUnit4::class)
class MobileSyncReceiptPhaseProfileTest {
    @Test fun profileReceiptWithHeldRecovery(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationContext.javaClass == Application::class.java)
        val runRoot = File(target.cacheDir, "mobile-sync-t20/profile-${UUID.randomUUID()}")
        check(runRoot.mkdirs())
        val context = T20IsolatedDatabaseContext(target, runRoot)
        val activeCapture = AtomicReference<QueryCapture?>(null)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "t20-active.db")
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
            .setQueryCallback({ sql, _ -> activeCapture.get()?.record(sql) }, Executor { it.run() })
            .build()
        val fixture = T20NativeRecoveryFixture(context, database)
        val output = File(runRoot, "phase-profile.jsonl")
        check(output.createNewFile())
        try {
            fixture.seedViaRecovery()
            val baseline = requireNotNull(database.syncRecoveryBaselineDao().get())
            val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
            val generation = baseline.generationId
            val ackCount = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sync_recovery_manifest WHERE generationId=? AND domain LIKE ?",
                arrayOf(generation, "$LOCAL_ACK_BODY_PREFIX%")
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
            check(ackCount == 0L) { "Diagnostic requires the unchanged no-ACK fixture" }
            fixture.beginHeldRecovery()
            profileKeysetQueries(database, generation, File(runRoot, "keyset-profile.jsonl"))
            val phases: List<Pair<String, suspend () -> Unit>> = listOf(
                "full_active_receipt" to { validateShopSyncActiveReceipt(database, generation, checkpoint) },
                "canonical_receipt" to { validateShopSyncCanonicalReceipt(database, generation, checkpoint) },
                "staging_counts_integrity" to {
                    validateStagingDatabase(database, generation, checkpoint,
                        activeStoreHistory = true, localAcknowledgements = true)
                },
                "physical_body" to {
                    validatePhysicalSnapshot(database, generation,
                        activeStoreHistory = true, localAcknowledgements = true)
                }
            )
            // One warm-up plus three diagnostic samples per phase. Rotating order
            // reduces one systematic cache/order bias, but this is not T20 acceptance.
            for (iteration in 0..3) {
                val ordered = phases.drop(iteration % phases.size) + phases.take(iteration % phases.size)
                for ((phase, operation) in ordered) {
                    check(fixture.isRecoveryHeld)
                    val capture = QueryCapture()
                    var bodyStarted = 0L
                    var bodyEnded = 0L
                    val started = SystemClock.elapsedRealtimeNanos()
                    var ended = started
                    var failure: Throwable? = null
                    check(activeCapture.compareAndSet(null, capture))
                    try {
                        withTimeout(60_000) {
                            database.withTransaction {
                                bodyStarted = SystemClock.elapsedRealtimeNanos()
                                try { operation() }
                                finally { bodyEnded = SystemClock.elapsedRealtimeNanos() }
                            }
                        }
                        ended = SystemClock.elapsedRealtimeNanos()
                        check(activeCapture.compareAndSet(capture, null))
                        // Postconditions are outside timing/capture, but part of
                        // the recorded verdict and checked before writing PASS.
                        check(fixture.isRecoveryHeld)
                        check(database.syncRecoveryBaselineDao().get() == baseline)
                        check(database.productDao().count() == 20_000)
                        check(database.productPriceDao().countAll() == 40_000)
                    } catch (error: Throwable) {
                        if (ended == started) ended = SystemClock.elapsedRealtimeNanos()
                        failure = error
                    } finally {
                        activeCapture.compareAndSet(capture, null)
                    }
                    val row = buildJsonObject {
                        put("schema", 1); put("phase", phase); put("iteration", iteration)
                        put("warmup", iteration == 0); put("timestamp_utc", Instant.now().toString())
                        put("status", if (failure == null) "PASS" else "FAIL")
                        put("error_type", failure?.javaClass?.simpleName.orEmpty())
                        put("elapsed_ms", (ended - started) / 1_000_000.0)
                        put("transaction_body_ms", if (bodyStarted > 0 && bodyEnded >= bodyStarted)
                            (bodyEnded - bodyStarted) / 1_000_000.0 else -1.0)
                        put("query_count", capture.total())
                        put("sql_histogram", capture.json())
                        put("ack_manifest_count", ackCount)
                        put("seed_sha256", fixture.remote.seedDigest)
                        put("boundary", "diagnostic independent phase calls; excludes no checks in full receipt")
                    }
                    FileOutputStream(output, true).use { stream ->
                        stream.write((row.toString() + "\n").toByteArray()); stream.fd.sync()
                    }
                    println("T20_RECEIPT_PROFILE $row")
                    failure?.let { throw it }
                }
            }
        } finally {
            activeCapture.set(null)
            fixture.stopHeldRecovery()
            database.close()
            println("T20_RECEIPT_PROFILE_ARTIFACT ${runRoot.absolutePath}")
        }
    }

    private suspend fun profileKeysetQueries(db: AppDatabase, generation: String, output: File) {
        check(output.createNewFile())
        val sql = db.openHelper.readableDatabase
        for ((domain, count) in listOf("products" to 20_000, "prices" to 40_000)) {
            for (offset in listOf(499, count / 2 - 1, count - 501, count - 1)) {
                val afterId = sql.query(
                    "SELECT remoteId FROM sync_recovery_manifest WHERE generationId=? AND domain=? ORDER BY remoteId LIMIT 1 OFFSET ?",
                    arrayOf<Any>(generation, domain, offset)
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
                for (activeOnly in listOf(false, true)) {
                    val prefix = "SELECT * FROM sync_recovery_manifest WHERE generationId=? AND domain=?" +
                        if (activeOnly) " AND active=1" else ""
                    val statements = listOf(
                        Triple("nullable_or", "$prefix AND (? IS NULL OR remoteId>?) ORDER BY remoteId LIMIT ?",
                            arrayOf<Any>(generation, domain, afterId, afterId, 500)),
                        Triple("nonnull_range", "$prefix AND remoteId>? ORDER BY remoteId LIMIT ?",
                            arrayOf<Any>(generation, domain, afterId, 500))
                    )
                    val plans = statements.associate { (variant, query, args) ->
                        variant to sql.query("EXPLAIN QUERY PLAN $query", args).use { cursor ->
                            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                        }
                    }
                    for (iteration in 0..3) {
                        val bodies = linkedMapOf<String, List<List<String?>>>()
                        for ((variant, query, args) in if (iteration % 2 == 0) statements else statements.reversed()) {
                            var duration = 0L
                            val rows = db.withTransaction {
                                val start = SystemClock.elapsedRealtimeNanos()
                                val result = sql.query(query, args).use { cursor -> buildList {
                                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                                        if (cursor.isNull(it)) null else cursor.getString(it)
                                    })
                                } }
                                duration = SystemClock.elapsedRealtimeNanos() - start
                                result
                            }
                            bodies[variant] = rows
                            val row = buildJsonObject {
                                put("domain", domain); put("cursor_offset", offset)
                                put("active_only", activeOnly); put("variant", variant)
                                put("iteration", iteration); put("warmup", iteration == 0)
                                put("elapsed_ms", duration / 1_000_000.0); put("rows", rows.size)
                                put("query_plan", JsonArray(plans.getValue(variant).map { JsonPrimitive(it) }))
                                put("boundary", "SQL read+cursor materialization only; identical bounded row bodies checked after both reads")
                            }
                            FileOutputStream(output, true).use { stream ->
                                stream.write((row.toString() + "\n").toByteArray()); stream.fd.sync()
                            }
                            println("T20_KEYSET_PROFILE $row")
                        }
                        val matches = bodies.getValue("nullable_or") == bodies.getValue("nonnull_range")
                        val verdict = buildJsonObject {
                            put("record_type", "ordered_body_pair_verdict")
                            put("domain", domain); put("cursor_offset", offset)
                            put("active_only", activeOnly); put("iteration", iteration)
                            put("warmup", iteration == 0); put("status", if (matches) "PASS" else "FAIL")
                            put("equal_ordered_full_row_values", matches)
                        }
                        FileOutputStream(output, true).use { stream ->
                            stream.write((verdict.toString() + "\n").toByteArray()); stream.fd.sync()
                        }
                        println("T20_KEYSET_PAIR $verdict")
                        check(matches) {
                            "Keyset candidate changed ordered row bodies"
                        }
                    }
                }
            }
        }
    }

    private class QueryCapture {
        private val statements = linkedMapOf<String, Int>()
        @Synchronized fun record(sql: String) {
            val normalized = sql.trim().replace(Regex("\\s+"), " ")
            // SQL only, never bind arguments. No rows or business bodies are logged.
            statements[normalized] = (statements[normalized] ?: 0) + 1
        }
        @Synchronized fun total(): Int = statements.values.sum()
        @Synchronized fun json(): JsonArray = JsonArray(statements.entries.map { (sql, count) ->
            buildJsonObject {
                put("sql_sha256", MessageDigest.getInstance("SHA-256")
                    .digest(sql.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
                put("sql", sql); put("count", count)
            }
        })
    }
}

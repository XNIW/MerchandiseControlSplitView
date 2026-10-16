package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith

/** One diagnostic sampled invocation, never a latency acceptance sample. */
@RunWith(AndroidJUnit4::class)
class MobileSyncReceiptCpuTraceTest {
    @Test fun sampleFullReceiptWithHeldRecovery(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationContext.javaClass == Application::class.java)
        val root = File(target.cacheDir, "mobile-sync-t20/cpu-${UUID.randomUUID()}")
        check(root.mkdirs())
        val context = T20IsolatedDatabaseContext(target, root)
        // No SQL callback: this trace observes unchanged validator work.
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "t20-active.db")
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray()).build()
        val fixture = T20NativeRecoveryFixture(context, database)
        val trace = File(root, "full-receipt.trace")
        check(!trace.exists())
        val ledger = File(root, "cpu-trace.jsonl")
        check(ledger.createNewFile())
        fun record(row: JsonObject) {
            FileOutputStream(ledger, true).use { stream ->
                stream.write((row.toString() + "\n").toByteArray()); stream.fd.sync()
            }
            println("T20_RECEIPT_CPU $row")
        }
        val traceGuard = Any()
        var traceStarted = false
        var traceStopped = false
        var traceStartNs = 0L
        var traceStopNs = 0L
        var stopCause = "not_started"
        var stopFailure: Throwable? = null
        fun stopTrace(cause: String) = synchronized(traceGuard) {
            if (traceStarted && !traceStopped) {
                // Only this owned test starts tracing in the neutral process.
                // Serialize the timer and finally stop to avoid stopping twice.
                traceStopped = true
                stopCause = cause
                try { Debug.stopMethodTracing() }
                catch (error: Throwable) { stopFailure = error }
                traceStopNs = SystemClock.elapsedRealtimeNanos()
            }
        }
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "T20-trace-limit").apply { isDaemon = true }
        }
        var failure: Throwable? = null
        var bodyStartNs = 0L
        var bodyEndNs = 0L
        var bodyThreadStartId = 0
        var bodyThreadEndId = 0
        var bodyCpuStartNs = 0L
        var bodyCpuEndNs = 0L
        var postconditionsPassed = false
        var recoveryStopped = false
        var databaseClosed = false
        try {
            fixture.seedViaRecovery()
            val baseline = requireNotNull(database.syncRecoveryBaselineDao().get())
            val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
            val generation = baseline.generationId
            val ackCount = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sync_recovery_manifest WHERE generationId=? AND domain LIKE ?",
                arrayOf(generation, "$LOCAL_ACK_BODY_PREFIX%")
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
            check(ackCount == 0L)
            fixture.beginHeldRecovery()
            check(fixture.isRecoveryHeld)
            record(buildJsonObject {
                put("event", "ready"); put("timestamp_utc", Instant.now().toString())
                put("products", database.productDao().count())
                put("prices", database.productPriceDao().countAll())
                put("seed_sha256", fixture.remote.seedDigest); put("ack_manifest_count", ackCount)
                put("interval_us", 2_000); put("buffer_bytes", 64 * 1024 * 1024)
                put("capture_limit_seconds", 60); put("outer_watchdog_seconds", 600)
                put("query_callback", false); put("boundary", "one full receipt inside transaction")
                put("trace_file", trace.name); put("latency_acceptance", false)
            })
            withTimeout(60_000) { // Cooperative only; external watchdog also required.
                database.withTransaction {
                    check(fixture.isRecoveryHeld)
                    synchronized(traceGuard) {
                        Debug.startMethodTracingSampling(trace.absolutePath, 64 * 1024 * 1024, 2_000)
                        traceStarted = true
                        traceStartNs = SystemClock.elapsedRealtimeNanos()
                    }
                    try {
                        timer.schedule({ stopTrace("capture_limit") }, 60, TimeUnit.SECONDS)
                        bodyThreadStartId = Process.myTid()
                        bodyCpuStartNs = Debug.threadCpuTimeNanos()
                        bodyStartNs = SystemClock.elapsedRealtimeNanos()
                        try { validateShopSyncActiveReceipt(database, generation, checkpoint) }
                        finally {
                            bodyEndNs = SystemClock.elapsedRealtimeNanos()
                            bodyCpuEndNs = Debug.threadCpuTimeNanos()
                            bodyThreadEndId = Process.myTid()
                        }
                    } finally { stopTrace("operation_finally") }
                }
            }
            check(fixture.isRecoveryHeld)
            check(database.syncRecoveryBaselineDao().get() == baseline)
            check(database.productDao().count() == 20_000)
            check(database.productPriceDao().countAll() == 40_000)
            postconditionsPassed = true
            synchronized(traceGuard) {
                check(traceStarted && traceStopped && stopFailure == null)
                check(stopCause == "operation_finally") { "Trace reached capture limit" }
            }
            check(trace.isFile && trace.length() > 32L)
        } catch (error: Throwable) { failure = error }
        finally {
            stopTrace("outer_finally")
            timer.shutdownNow()
            try { fixture.stopHeldRecovery(); recoveryStopped = !fixture.isRecoveryHeld }
            catch (error: Throwable) {
                if (failure == null) failure = error else requireNotNull(failure).addSuppressed(error)
            }
            try { database.close(); databaseClosed = true }
            catch (error: Throwable) {
                if (failure == null) failure = error else requireNotNull(failure).addSuppressed(error)
            }
            // Raw header later decides overflow/format/coverage; never infer them from size.
            record(buildJsonObject {
                put("event", "terminal"); put("timestamp_utc", Instant.now().toString())
                put("status", if (failure == null) "PASS" else "FAIL")
                put("error_type", failure?.javaClass?.simpleName.orEmpty())
                put("trace_started", traceStarted); put("trace_stop_attempted", traceStopped)
                put("trace_stop_error", stopFailure?.javaClass?.simpleName.orEmpty())
                put("trace_stop_cause", stopCause)
                put("trace_start_ns", traceStartNs); put("trace_stop_ns", traceStopNs)
                put("body_start_ns", bodyStartNs); put("body_end_ns", bodyEndNs)
                put("body_thread_start_tid", bodyThreadStartId)
                put("body_thread_end_tid", bodyThreadEndId)
                put("body_thread_cpu_start_ns", bodyCpuStartNs)
                put("body_thread_cpu_end_ns", bodyCpuEndNs)
                put("postconditions_passed", postconditionsPassed)
                put("recovery_stopped", recoveryStopped); put("database_closed", databaseClosed)
                put("trace_bytes", if (trace.isFile) trace.length() else 0L)
                put("clock_mode", "READ_FROM_TRACE_HEADER")
                put("overflow", "READ_FROM_TRACE_HEADER")
                put("coverage", "REQUIRES_OFFLINE_DECODING")
                put("latency_acceptance", false)
            })
            println("T20_RECEIPT_CPU_ARTIFACT ${root.absolutePath}")
        }
        failure?.let { throw it }
    }
}

package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Debug
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Native T20 local component metrics, isolated disk Room and in-process fake transport.
 * No opt-in skip. This suite must run with MobileSyncPerformanceTestRunner on a dedicated target.
 * The no-work metric is the production local receipt transaction component, not total cloud sync.
 */
@RunWith(AndroidJUnit4::class)
class MobileSyncPerformanceTest {
    @Test fun largeLocalSaveIdle(): Unit = runBlocking { runCase("save", false) }
    @Test fun largeLocalSaveDuringRecovery(): Unit = runBlocking { runCase("save", true) }
    @Test fun largeNoWorkReceiptIdle(): Unit = runBlocking { runCase("no_work", false) }
    @Test fun largeNoWorkReceiptDuringRecovery(): Unit = runBlocking { runCase("no_work", true) }

    /** Regression for the observed second cleanup boundary replacing the publication timestamps. */
    @Test fun activationTimingCaptureRetainsCleanupBoundary() {
        val capture = T20ActivationTimingCapture().also { it.install() }
        try {
            fun event(source: String, name: String, ms: Long) {
                val observer = when (source) {
                    "transition" -> ShopSyncRecoveryTestHooks.activationTransitionTiming
                    "boundary" -> ShopSyncRecoveryTestHooks.activationBoundaryTiming
                    else -> ShopSyncRecoveryTestHooks.activationWriterTiming
                }
                requireNotNull(observer).invoke(name, ms * 1_000_000L)
            }
            event("transition", "unavailable", 1); event("transition", "quiesced", 2)
            event("boundary", "acquired", 3); event("room", "acquired", 4)
            event("room", "released", 104); event("transition", "available", 105)
            event("boundary", "released", 106)
            event("transition", "unavailable", 200); event("transition", "quiesced", 201)
            event("boundary", "acquired", 202); event("transition", "available", 203)
            event("boundary", "released", 204)
            val result = capture.snapshot()
            assertEquals(null, result.error)
            assertEquals(0, result.transition?.ordinal)
            assertEquals(0, result.boundary?.ordinal)
            assertEquals(104.0, requireNotNull(result.maxUnavailableMs), 0.0)
            assertEquals(100.0, requireNotNull(result.room).elapsedMs, 0.0)
            assertEquals(5, (result.raw["intervals"] as JsonArray).size)
            // A missing ending is unknown, never replaced by an earlier short successful interval.
            event("transition", "unavailable", 300)
            check(capture.snapshot().error != null)
        } finally { ShopSyncRecoveryTestHooks.reset() }
    }

    /** Independent activation component; UI/cold-warm/frame-pause acceptance stays separate. */
    @Test fun largeActivationWriterComponent(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationContext.javaClass == Application::class.java)
        val runRoot = File(target.cacheDir, "mobile-sync-t20/cutover-${UUID.randomUUID()}")
        check(runRoot.mkdirs())
        val context = T20IsolatedDatabaseContext(target, runRoot)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "t20-cutover-active.db")
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray()).build()
        val output = File(runRoot, "samples.jsonl").also { check(it.createNewFile()) }
        val clockID = "android-cutover-process-${UUID.randomUUID()}"
        var tracker: CatalogSyncStateTracker? = null
        val fixture = T20NativeRecoveryFixture(context, db, activationBoundary = { block ->
            val current = tracker
            if (current == null) block() else {
                val observer = ShopSyncRecoveryTestHooks.activationBoundaryTiming
                try {
                    current.withBusinessDataScopeTransition {
                        ShopSyncRecoveryTestHooks.observeTiming(observer, "acquired")
                        block()
                    }
                } finally { ShopSyncRecoveryTestHooks.observeTiming(observer, "released") }
            }
        }, heldPageTimeoutMs = 90_000L)
        fun append(row: kotlinx.serialization.json.JsonObject) {
            FileOutputStream(output, true).use { stream ->
                stream.write((row.toString() + "\n").toByteArray()); stream.fd.sync()
            }
            println("T20_NATIVE_SAMPLE $row")
        }
        val times = mutableListOf<Double>()
        var index = 0
        try {
            fixture.seedViaRecovery()
            val repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = fixture.remote)
            tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(fixture.scope))
            val originalID = requireNotNull(db.productDao().findByBarcode(fixture.firstBarcode)).id
            repeat(33) { offset ->
                index = offset + 1
                if (offset > 0) fixture.prepareNextHeldRecovery()
                fixture.beginHeldRecovery()
                val timing = T20ActivationTimingCapture().also { it.install() }
                var failure: Throwable? = null
                var readbackVerified = false
                var failureGate = "held_recovery"
                try {
                    kotlinx.coroutines.delay(10_000L)
                    check(fixture.isRecoveryHeld)
                    val result = withTimeout(900_000L) { fixture.releaseHeldRecovery() }
                    check(result is ShopSyncRecoveryResult.Activated) { "Recovery did not activate: $result" }
                    check(ShopSyncRecoveryTestHooks.timingObservationError == null) { "Timing observer failed" }
                    failureGate = "timing_pairing"
                    val verifiedTiming = timing.snapshot()
                    check(verifiedTiming.error == null) { "Timing pairing failed: ${verifiedTiming.error}" }
                    failureGate = "data_identity_receipt_readback"
                    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
                    check(baseline.generationId == result.generationId)
                    db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId,
                        decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
                    check(db.productDao().count() == 20_000 && db.productPriceDao().countAll() == 40_000)
                    check(db.syncRecoveryJournalDao().get() == null)
                    check(db.productDao().findByBarcode(fixture.firstBarcode)?.id == originalID)
                    readbackVerified = true
                    val elapsed = requireNotNull(verifiedTiming.maxUnavailableMs)
                    if (index > 3) {
                        times += elapsed
                        failureGate = "performance_max"
                        check(elapsed <= 300.0) { "Writer unavailable max exceeded: $elapsed ms" }
                        failureGate = "performance_p95_fixed30"
                        check(times.count { it > 100.0 } < 2) { "Fixed 30-sample p95 cannot pass: two samples exceed 100 ms" }
                    }
                } catch (error: Throwable) { failure = error } finally {
                    if (failure != null) {
                        try { fixture.stopHeldRecovery() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    val observed = timing.snapshot()
                    append(buildJsonObject {
                        put("sample_id", "T20-cutover-$index"); put("kind", "cutover_component")
                        put("status", if (failure == null) "PASS" else "FAIL"); put("reason", failure?.message.orEmpty())
                        put("warmup", index <= 3); put("measurement_number", maxOf(0, index - 3))
                        put("clock_id", clockID); put("timestamp_utc", Instant.now().toString())
                        put("timing", observed.raw)
                        observed.maxUnavailableMs?.let { put("writer_unavailable_ms", it) }
                        observed.room?.let { put("room_transaction_ms", it.elapsedMs) }
                        observed.transition?.let { put("activation_transition_ms", it.elapsedMs) }
                        put("products", 20_000); put("prices", 40_000); put("row_verified", readbackVerified)
                        put("failure_gate", if (failure == null) "" else failureGate)
                        put("timing_observation_error", ShopSyncRecoveryTestHooks.timingObservationError.orEmpty())
                        put("acceptance_boundary", "native activation component; no UI pause/input, cold-warm, offline or live backend acceptance")
                        put("evidence", JsonArray(listOf(JsonPrimitive(output.absolutePath))))
                    })
                }
                failure?.let { throw it }
            }
            append(buildJsonObject {
                put("kind", "cutover_component_summary"); put("status", "PASS"); put("count", times.size)
                put("p95_ms", times.sorted()[28]); put("max_ms", times.max())
            })
        } catch (failure: Throwable) {
            append(buildJsonObject {
                put("kind", "sequence_stop"); put("status", "FAIL"); put("sample_index", index)
                put("reason", failure.message.orEmpty()); put("downstream_status", "NOT_TRAVERSED")
                put("automatic_retry", false)
            })
            throw failure
        } finally {
            ShopSyncRecoveryTestHooks.activationWriterTiming = null
            ShopSyncRecoveryTestHooks.activationBoundaryTiming = null
            ShopSyncRecoveryTestHooks.activationTransitionTiming = null
            fixture.stopHeldRecovery(); db.close()
            println("T20_NATIVE_ARTIFACT ${runRoot.absolutePath}")
        }
    }

    private suspend fun runCase(kind: String, recovery: Boolean) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationContext.javaClass == Application::class.java) {
            "T20 requires MobileSyncPerformanceTestRunner; production Application must never start"
        }
        val runRoot = File(target.cacheDir, "mobile-sync-t20/${kind}-${if (recovery) "recovery" else "idle"}-${UUID.randomUUID()}")
        check(runRoot.mkdirs())
        val context = T20IsolatedDatabaseContext(target, runRoot)
        val queryCount = AtomicInteger()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "t20-active.db")
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
            .setQueryCallback({ _, _ -> queryCount.incrementAndGet() }, Executor { it.run() }).build()
        val fixture = T20NativeRecoveryFixture(context, database)
        val output = File(runRoot, "samples.jsonl")
        check(output.createNewFile())
        val clockID = "android-process-${UUID.randomUUID()}"
        try {
            fixture.seedViaRecovery()
            assertEquals(20_000, database.productDao().count())
            assertEquals(40_000, database.productPriceDao().countAll())
            val initial = requireNotNull(database.syncRecoveryBaselineDao().get())
            val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(fixture.scope))
            val repository = DefaultInventoryRepository(database, tracker, fixture.remote)
            var current = requireNotNull(database.productDao().findByBarcode(fixture.firstBarcode))
            val initialRevision = requireNotNull(database.productRemoteRefDao().getByProductId(current.id)).localChangeRevision
            File(runRoot, "setup.json").writeText(buildJsonObject {
                put("schema", 1); put("seed", T20DeterministicRecoveryRemote.SEED)
                put("fixture_sha256", fixture.remote.seedDigest)
                put("products", 20_000); put("prices", 40_000)
                put("warmups", 3); put("samples", 30)
                put("native", true); put("disk_backed", true)
                put("network", "in_process_fake")
                put("metric_path", if (kind == "save") "updateProductFromEditor" else "validateShopSyncActiveReceipt_transaction")
                put("scope", "synthetic_task_owned")
                put("fixture_version_digest", fixture.checkpoint.catalog.products.versionDigest)
                put("price_version_digest", fixture.checkpoint.prices.versionDigest)
            }.toString())
            if (recovery) fixture.beginHeldRecovery()
            val group = if (kind == "save") (if (recovery) 6 else 5) else (if (recovery) 10 else 9)
            for (index in 1..33) {
                queryCount.set(0)
                var measuredQueries = 0
                val started = SystemClock.elapsedRealtimeNanos()
                var ended = started
                var lockEntered: Long? = null
                var lockEnded: Long? = null
                var error: Throwable? = null
                var observed = buildJsonObject { }
                try {
                    withTimeout(60_000) {
                        if (kind == "save") {
                            val edited = current.copy(productName = "T20 edited $index")
                            repository.updateProductFromEditor(current, edited)
                            current = edited
                        } else {
                            database.withTransaction {
                                lockEntered = SystemClock.elapsedRealtimeNanos()
                                try {
                                    validateShopSyncActiveReceipt(database, initial.generationId, fixture.checkpoint)
                                } finally {
                                    lockEnded = SystemClock.elapsedRealtimeNanos()
                                }
                            }
                        }
                    }
                    ended = SystemClock.elapsedRealtimeNanos()
                    measuredQueries = queryCount.get()
                    // Readback and evidence persistence are intentionally outside the measured operation.
                    val saved = requireNotNull(database.productDao().getById(current.id))
                    val ref = requireNotNull(database.productRemoteRefDao().getByProductId(current.id))
                    check(database.productDao().count() == 20_000)
                    check(database.productPriceDao().countAll() == 40_000)
                    check(database.syncRecoveryBaselineDao().get()?.generationId == initial.generationId)
                    check(saved == current)
                    if (kind == "save") check(ref.localChangeRevision > initialRevision)
                    else check(ref.localChangeRevision == initialRevision)
                    if (recovery) check(fixture.isRecoveryHeld)
                    observed = buildJsonObject {
                        put("product_id", current.id); put("product_name", saved.productName)
                        put("local_revision", ref.localChangeRevision)
                        put("products", 20_000); put("prices", 40_000)
                        put("recovery_held", recovery && fixture.isRecoveryHeld)
                        put("metric_path", if (kind == "save") "real_editor_save" else "local_no_work_receipt_component")
                    }
                } catch (failure: Throwable) {
                    ended = SystemClock.elapsedRealtimeNanos()
                    measuredQueries = queryCount.get()
                    error = failure
                }
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val beginKey = if (kind == "save") "save_start" else "no_work_start"
                val endKey = if (kind == "save") "save_end" else "no_work_end"
                val row = buildJsonObject {
                    put("sample_id", "T20-G${group.toString().padStart(2,'0')}-${index.toString().padStart(2,'0')}")
                    put("status", if (error == null) "PASS" else if (error is kotlinx.coroutines.TimeoutCancellationException) "TIMEOUT" else "FAIL")
                    put("reason", error?.javaClass?.simpleName.orEmpty())
                    put("environment", "native_android_room_disk_synthetic_transport")
                    put("kind", kind); put("platform", "android"); put("direction", ""); put("category", "")
                    put("measurement_number", maxOf(0, index - 3))
                    put("condition", if (recovery) "recovery" else "idle")
                    put("warmup", index <= 3); put("timestamp_utc", Instant.now().toString())
                    put("timestamps", buildJsonObject {
                        put(beginKey, buildJsonObject { put("clock_id", clockID); put("ns", started) })
                        put(endKey, buildJsonObject { put("clock_id", clockID); put("ns", ended) })
                    })
                    put("elapsed_ms", (ended-started)/1_000_000.0)
                    put("query_count", measuredQueries)
                    put("memory_bytes", memory.totalPss.toLong() * 1024)
                    put("memory_method", "process_total_pss_after_timing")
                    put("row_verified", error == null)
                    put("invariants_verified", kind == "no_work" && error == null)
                    put("evidence", JsonArray(listOf(JsonPrimitive(output.absolutePath))))
                    put("observed", observed)
                    if (lockEntered != null && lockEnded != null) {
                        put("writer_lock_ms", (lockEnded-lockEntered)/1_000_000.0)
                        put("writer_lock_network_wait_ms", 0)
                        put("lock_network_proof", "direct local receipt function contains no transport; transaction calls only DB validation")
                    }
                    put("acceptance_boundary", "local synthetic components; no end-to-end delta/UI/server acceptance")
                }
                FileOutputStream(output, true).use { stream ->
                    stream.write((row.toString()+"\n").toByteArray()); stream.fd.sync()
                }
                println("T20_NATIVE_SAMPLE $row")
                error?.let { throw it }
            }
        } finally {
            fixture.stopHeldRecovery()
            database.close()
            // Keep this run's store, journal and raw evidence, including failed samples.
            println("T20_NATIVE_ARTIFACT ${runRoot.absolutePath}")
        }
    }
}


/** Bounded primitive event buffer: callbacks copy only codes/timestamps; pairing happens after await. */
internal class T20ActivationTimingCapture {
    private val lock = Any()
    private val sources = IntArray(64)
    private val events = IntArray(64)
    private val nanos = LongArray(64)
    private var count = 0
    private var overflow = false
    private var invalidEvent = false

    fun install() {
        ShopSyncRecoveryTestHooks.activationTransitionTiming = { event, ns -> record(0, event, ns) }
        ShopSyncRecoveryTestHooks.activationBoundaryTiming = { event, ns -> record(1, event, ns) }
        ShopSyncRecoveryTestHooks.activationWriterTiming = { event, ns -> record(2, event, ns) }
    }

    private fun record(source: Int, event: String, ns: Long) {
        val code = when (event) {
            "unavailable", "acquired" -> 0
            "quiesced" -> 1
            "available", "released" -> 2
            else -> -1
        }
        synchronized(lock) {
            if (code < 0) invalidEvent = true
            if (count == nanos.size) { overflow = true; return }
            sources[count] = source; events[count] = code; nanos[count] = ns; count += 1
        }
    }

    internal data class Span(val source: Int, val ordinal: Int, val start: Long,
        val quiesced: Long?, val end: Long) {
        val elapsedMs: Double get() = (end - start) / 1_000_000.0
        fun json() = buildJsonObject {
            put("source", sourceName(source)); put("ordinal", ordinal); put("start_ns", start)
            quiesced?.let { put("quiesced_ns", it) }; put("end_ns", end); put("elapsed_ms", elapsedMs)
        }
    }

    internal data class Snapshot(
        val raw: kotlinx.serialization.json.JsonObject,
        val error: String?,
        val room: Span?, val boundary: Span?, val transition: Span?,
        val maxUnavailableMs: Double?
    )

    fun snapshot(): Snapshot {
        val rawCopy = synchronized(lock) {
            Triple(sources.copyOf(count), events.copyOf(count), nanos.copyOf(count)) to
                (overflow to invalidEvent)
        }
        val (sourceCodes, eventCodes, times) = rawCopy.first
        val (didOverflow, badEvent) = rawCopy.second
        var error: String? = when {
            didOverflow -> "timing_buffer_overflow"
            badEvent -> "unknown_timing_event"
            else -> null
        }
        fun fail(reason: String) { if (error == null) error = reason }
        val spans = mutableListOf<Span>()
        for (source in 0..2) {
            var start: Long? = null
            var quiesced: Long? = null
            var ordinal = 0
            for (i in times.indices.filter { sourceCodes[it] == source }) {
                val time = times[i]
                if (time <= 0L) fail("invalid_timestamp")
                when (eventCodes[i]) {
                    0 -> {
                        if (start != null) fail("overlapping_${sourceName(source)}_intervals")
                        start = time; quiesced = null
                    }
                    1 -> {
                        if (source != 0 || start == null || quiesced != null || time < start)
                            fail("invalid_quiescing_order")
                        quiesced = time
                    }
                    2 -> {
                        val began = start
                        if (began == null || time < began ||
                            (source == 0 && (quiesced == null || time < requireNotNull(quiesced)))) {
                            fail("incomplete_${sourceName(source)}_interval")
                        } else {
                            spans += Span(source, ordinal++, began, quiesced, time)
                        }
                        start = null; quiesced = null
                    }
                    else -> fail("unknown_timing_event")
                }
            }
            if (start != null) fail("unclosed_${sourceName(source)}_interval")
        }
        val rooms = spans.filter { it.source == 2 }
        if (rooms.size != 1) fail("expected_one_room_interval_got_${rooms.size}")
        val room = rooms.singleOrNull()
        fun enclosing(source: Int): Span? {
            val candidates = if (room == null) emptyList() else spans.filter {
                it.source == source && it.start <= room.start && room.end <= it.end
            }
            if (candidates.size != 1) fail("expected_unique_${sourceName(source)}_enclosing_room_got_${candidates.size}")
            return candidates.singleOrNull()
        }
        val boundary = enclosing(1)
        val transition = enclosing(0)
        if (room != null && boundary != null && transition != null) {
            val quiet = transition.quiesced
            if (quiet == null || quiet > boundary.start || boundary.start > room.start ||
                room.end > transition.end || transition.end > boundary.end) fail("inconsistent_nested_timing_order")
        }
        val maximum = spans.filter { it.source == 0 }.maxOfOrNull { it.elapsedMs }
        val raw = buildJsonObject {
            put("capacity", nanos.size); put("event_count", times.size); put("overflow", didOverflow)
            put("pairing_error", error?.let(::JsonPrimitive) ?: JsonNull)
            put("events", JsonArray(times.indices.map { i -> buildJsonObject {
                put("source", sourceName(sourceCodes[i])); put("event_code", eventCodes[i]); put("ns", times[i])
                put("event", when (eventCodes[i]) {
                    0 -> if (sourceCodes[i] == 0) "unavailable" else "acquired"
                    1 -> "quiesced"
                    2 -> if (sourceCodes[i] == 0) "available" else "released"
                    else -> "unknown"
                })
            } }))
            put("intervals", JsonArray(spans.map { it.json() }))
            put("selected_room_ordinal", room?.ordinal?.let(::JsonPrimitive) ?: JsonNull)
            put("selected_boundary_ordinal", boundary?.ordinal?.let(::JsonPrimitive) ?: JsonNull)
            put("selected_transition_ordinal", transition?.ordinal?.let(::JsonPrimitive) ?: JsonNull)
            put("max_writer_unavailable_ms", maximum?.let(::JsonPrimitive) ?: JsonNull)
        }
        return Snapshot(raw, error, room, boundary, transition, maximum)
    }

    companion object {
        private fun sourceName(source: Int): String = when (source) {
            0 -> "transition"; 1 -> "activation_boundary"; 2 -> "room_transaction"; else -> "unknown"
        }
    }
}


/**
 * Native Room and recovery staging share ONLY this fresh test-owned directory.
 * In particular, ContextWrapper's default openOrCreateDatabase delegates to the
 * base context, so both overloads must be overridden as well as getDatabasePath.
 * Cleanup is left to the caller after evidence collection and all DBs are closed.
 */
internal class T20IsolatedDatabaseContext(base: Context, runRoot: File) : ContextWrapper(base) {
    val root: File = runRoot.canonicalFile
    private val databases = File(root, "databases")

    init {
        val allowedParents = listOf(base.filesDir, base.cacheDir, base.noBackupFilesDir)
            .map { it.canonicalPath + File.separator }
        require(allowedParents.any(root.path::startsWith)) { "T20 root must be a private app subdirectory" }
        require(!root.exists() || (root.isDirectory && root.listFiles()?.isEmpty() == true)) {
            "T20 root must be new or empty; existing evidence must not be overwritten"
        }
        check(databases.mkdirs()) { "Cannot create isolated T20 database directory" }
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(Regex("[A-Za-z0-9_]+").matches(name))
        return baseContext.getSharedPreferences("t20_${root.name}_$name", mode)
    }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File {
        if (name == ".") return databases
        require(name.isNotBlank() && name != ".." && !name.contains('/') && !name.contains('\\'))
        return File(databases, name)
    }
    override fun databaseList(): Array<String> = (databases.list()?.toList() ?: emptyList()).toTypedArray()
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun openOrCreateDatabase(
        name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
    override fun openOrCreateDatabase(
        name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    override fun getFilesDir(): File = child("files")
    override fun getCacheDir(): File = child("cache")
    override fun getNoBackupFilesDir(): File = child("no_backup")
    private fun child(name: String): File = File(root, name).also {
        check(it.isDirectory || it.mkdirs()) { "Cannot create isolated T20 directory" }
    }
}

/**
 * Support code only. No @Test, Assume, real auth, SDK initialization or transport.
 * Use a distinct instance/DB for save-idle, save-recovery, proof-idle and proof-recovery.
 */
internal class T20NativeRecoveryFixture(
    val context: Context,
    val db: AppDatabase,
    private val activationBoundary: suspend (suspend () -> Unit) -> Unit = { it() },
    heldPageTimeoutMs: Long? = null
) {
    val owner = T20DeterministicRecoveryRemote.OWNER
    val shop = T20DeterministicRecoveryRemote.SHOP
    val device = T20DeterministicRecoveryRemote.DEVICE
    val selectedShop = SelectedShop(shopId = shop, code = "T20", name = "T20 synthetic",
        role = "shop_owner", status = "active", canWrite = true)
    val scope = task126ActiveOwnerStoreScope(owner, selectedShop)
    val remote = T20DeterministicRecoveryRemote(heldPageTimeoutMs)
    val checkpoint: ShopSyncRecoveryCheckpoint get() = remote.checkpoint
    val firstBarcode: String get() = remote.firstBarcode
    private val recoveryRepository = DefaultInventoryRepository(db)
    private val workerJob = SupervisorJob()
    private val workers = CoroutineScope(workerJob + Dispatchers.IO)
    private var heldRecovery: Deferred<ShopSyncRecoveryResult>? = null
    val isRecoveryHeld: Boolean
        get() = heldRecovery?.isActive == true && remote.heldProductsPageEntered?.isCompleted == true

    init {
        require(context is T20IsolatedDatabaseContext) { "Isolated context required for native recovery" }
        require(File(requireNotNull(db.openHelper.writableDatabase.path)).canonicalFile.parentFile ==
            context.getDatabasePath(".").canonicalFile) { "Active DB escaped the T20 root" }
    }

    /** Bootstrap through the actual production staging/validation/activation path. */
    suspend fun seedViaRecovery() {
        check(db.productDao().count() == 0 && db.productPriceDao().countAll() == 0)
        check(db.syncRecoveryBaselineDao().get() == null && db.syncRecoveryJournalDao().get() == null)
        check(db.businessDataScopeBindingDao().get() == null && db.syncEventDeviceStateDao().get() == null)
        db.withTransaction {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = device, createdAtMs = 1L))
            db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(scope, 1L))
            db.syncRecoveryJournalDao().upsert(requiredJournal())
        }
        val result = coordinator().recover(owner, selectedShop, scope)
        check(result is ShopSyncRecoveryResult.Activated) { "T20 seed was not activated: $result" }
        check(db.productDao().count() == T20DeterministicRecoveryRemote.PRODUCT_COUNT)
        check(db.productPriceDao().countAll() == T20DeterministicRecoveryRemote.PRICE_COUNT)
        check(db.syncRecoveryJournalDao().get() == null) { "T20 seed cleanup incomplete" }
        check(db.businessDataScopeBindingDao().get()?.ownerHash == scope.ownerHash)
        check(db.syncEventWatermarkDao().get(owner, scope.storeId)?.lastSyncEventId ==
            T20DeterministicRecoveryRemote.EVENT_FENCE)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        check(baseline.generationId == result.generationId)
        val persistedCheckpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        check(persistedCheckpoint.catalog == checkpoint.catalog && persistedCheckpoint.prices == checkpoint.prices)
        db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, persistedCheckpoint) }
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
            check(!it.moveToFirst()) { "T20 seed contains foreign key violations" }
        }
    }

    /** Returns only once a real recovery owns STAGING and awaits its first product page. */
    suspend fun beginHeldRecovery() {
        check(heldRecovery == null) { "T20 recovery already started" }
        check(db.syncRecoveryBaselineDao().get() != null)
        check(db.syncRecoveryJournalDao().get() == null) { "Existing recovery journal must not be replaced" }
        val entered = remote.holdFirstProductsPage()
        db.syncRecoveryJournalDao().upsert(requiredJournal())
        val job = workers.async { coordinator().recover(owner, selectedShop, scope) }
        heldRecovery = job
        try {
            withTimeout(120_000L) {
                select<Unit> {
                    entered.onAwait { }
                    job.onAwait { error("T20 recovery terminated before held PRODUCTS page: $it") }
                }
            }
            check(job.isActive) { "T20 held recovery unexpectedly terminated" }
            val journal = requireNotNull(db.syncRecoveryJournalDao().get())
            check(journal.phase == SyncRecoveryJournalPhases.STAGING)
            check(journal.ownerHash == scope.ownerHash && journal.storeScope == scope.storeId)
            check(journal.runId != null && journal.stagingDatabaseName != null)
            check(journal.checkpointADigest == checkpoint.checkpointDigest)
        } catch (failure: Throwable) {
            job.cancelAndJoin()
            throw failure
        }
    }

    /** Explicit next trial after successful cleanup. Never retries or overwrites a failed recovery. */
    suspend fun prepareNextHeldRecovery() {
        val previous = requireNotNull(heldRecovery)
        check(previous.isCompleted && !previous.isCancelled)
        check(previous.await() is ShopSyncRecoveryResult.Activated)
        check(db.syncRecoveryJournalDao().get() == null)
        remote.prepareNextHeldProductsPage()
        heldRecovery = null
    }

    /** Releases the held transport once; the caller supplies its explicit full-recovery watchdog. */
    suspend fun releaseHeldRecovery(): ShopSyncRecoveryResult {
        check(isRecoveryHeld) { "No live held recovery to release" }
        remote.releaseHeldProductsPage()
        return requireNotNull(heldRecovery).await()
    }

    /** Cancels and joins native recovery; its production retry journal is preserved. */
    suspend fun stopHeldRecovery(): Unit = withContext(NonCancellable) {
        heldRecovery?.cancelAndJoin()
        workerJob.cancelAndJoin()
        check(heldRecovery?.isCompleted != false)
        heldRecovery = null
    }

    private fun requiredJournal(): SyncRecoveryJournal {
        val now = System.currentTimeMillis()
        return SyncRecoveryJournal(ownerHash = scope.ownerHash, storeScope = scope.storeId,
            shopId = shop, deviceId = device, authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "t20_synthetic_recovery",
            blockingEventId = T20DeterministicRecoveryRemote.EVENT_FENCE, attemptCount = 1,
            createdAtMs = now, updatedAtMs = now, nextRetryAtMs = now)
    }

    private fun coordinator() = ShopSyncRecoveryCoordinator(context = context, activeDb = db,
        activeRepository = recoveryRepository, remote = remote,
        registerDeviceForRecovery = { requestedShop ->
            check(requestedShop == shop)
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = shop))
        },
        scopeStillValid = { account, selected -> account == owner && selected == shop },
        activationBoundary = activationBoundary)
}

/**
 * Deterministic synthetic V6 material. Every product has one purchase and one
 * retail price. Domain receipt lines and chain hashes use production functions.
 * The opaque checkpoint/marker tokens are synthetic and are not server receipts.
 */
internal class T20DeterministicRecoveryRemote(
    private val heldPageTimeoutMs: Long? = null
) : ShopSyncReadRemoteDataSource {
    override val isConfigured = true
    val firstBarcode = "T20-00000001"
    private val checkpointCallCounter = AtomicInteger()
    private val pageCallCounter = AtomicInteger()
    val checkpointCalls: Int get() = checkpointCallCounter.get()
    val pageCalls: Int get() = pageCallCounter.get()
    @Volatile var heldProductsPageEntered: CompletableDeferred<Unit>? = null
        private set
    private var releaseProductsPage = CompletableDeferred<Unit>()
    private val fixtureJson = Json { encodeDefaults = true }
    private val suppliers = listOf(InventorySupplierRow(uuid(1, 1), OWNER, SHOP, "T20 supplier", TIMESTAMP))
    private val categories = listOf(InventoryCategoryRow(uuid(2, 1), OWNER, SHOP, "T20 category", TIMESTAMP))
    private val products = (1..PRODUCT_COUNT).map { index ->
        InventoryProductRow(id = uuid(3, index), ownerUserId = OWNER, shopId = SHOP,
            barcode = "T20-${index.toString().padStart(8, '0')}", itemNumber = "T20ITEM$index",
            productName = "T20 product $index", purchasePrice = 100.0, retailPrice = 200.0,
            supplierId = suppliers.single().id, categoryId = categories.single().id,
            stockQuantity = 10.0, updatedAt = TIMESTAMP)
    }
    private val prices = (1..PRICE_COUNT).map { index ->
        val purchase = index % 2 == 1
        InventoryProductPriceRow(id = uuid(4, index), ownerUserId = OWNER, shopId = SHOP,
            productId = uuid(3, (index + 1) / 2), type = if (purchase) "PURCHASE" else "RETAIL",
            price = if (purchase) 100.0 else 200.0, priceCanonical = if (purchase) "100" else "200",
            effectiveAt = LEGACY_TIMESTAMP, source = "T20_SYNTHETIC", createdAt = LEGACY_TIMESTAMP,
            updatedAt = TIMESTAMP)
    }
    private val domains = linkedMapOf<ShopSyncRowDomain, ShopSyncRows>(
        ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(suppliers),
        ShopSyncRowDomain.CATEGORIES to ShopSyncRows.Categories(categories),
        ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(products),
        ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(prices),
        ShopSyncRowDomain.HISTORY to ShopSyncRows.History(emptyList()),
        ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(emptyList()))
    private val sortedIds = domains.mapValues { (_, rows) -> rows.ids().also { ids ->
        check(ids == ids.sorted() && ids.distinct().size == ids.size)
    } }
    private val receipts = domains.mapValues { (domain, rows) ->
        val manifest = rows.toManifestRows("t20-canonical-seed", domain).sortedBy { it.remoteId }
        ShopSyncDomainCheckpoint(activeCount = manifest.count { it.active }.toLong(),
            tombstoneCount = manifest.count { !it.active }.toLong(),
            idSetDigest = shopSyncCheckpointChainDigest(manifest.map { it.idLine }),
            versionDigest = shopSyncCheckpointChainDigest(manifest.map { it.versionLine }),
            identityDigest = if (domain == ShopSyncRowDomain.PRODUCTS)
                shopSyncCheckpointChainDigest(manifest.map { requireNotNull(it.identityLine) }) else null)
    }
    /** Stable local fixture fingerprint, distinct from a real backend receipt. */
    val seedDigest = t20Sha256(SEED + "\n" + ShopSyncRowDomain.entries.joinToString("\n") { domain ->
        val receipt = receipts.getValue(domain)
        "${domain.wireValue}|${receipt.activeCount}|${receipt.idSetDigest}|${receipt.versionDigest}|${receipt.identityDigest}"
    })
    val checkpoint = ShopSyncRecoveryCheckpoint(schemaVersion = "shop-sync-recovery-checkpoint-v1",
        status = "ready", shopId = SHOP,
        scope = ShopSyncScope(kind = ShopSyncScopeKinds.SHOP_SCOPED, key = t20Sha256("$SEED|scope"),
            historyKind = ShopSyncScopeKinds.SHOP_SCOPED, accountKey = t20Sha256(OWNER), deviceKey = t20Sha256(DEVICE)),
        syncEvents = ShopSyncEventCheckpoint(maxId = EVENT_FENCE.toString(), verifiedBaselineId = "0",
            requiresFullRecovery = false, domainMaxIds = mapOf(SyncEventDomains.CATALOG to EVENT_FENCE.toString(),
                SyncEventDomains.PRICES to EVENT_FENCE.toString(), SyncEventDomains.HISTORY to EVENT_FENCE.toString())),
        catalog = ShopSyncCatalogCheckpoint(suppliers = receipts.getValue(ShopSyncRowDomain.SUPPLIERS),
            categories = receipts.getValue(ShopSyncRowDomain.CATEGORIES), products = receipts.getValue(ShopSyncRowDomain.PRODUCTS),
            digest = t20Sha256(listOf(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS)
                .joinToString("\n") { receipts.getValue(it).versionDigest })),
        prices = receipts.getValue(ShopSyncRowDomain.PRICES), history = receipts.getValue(ShopSyncRowDomain.HISTORY),
        images = receipts.getValue(ShopSyncRowDomain.IMAGES), integrity = ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0),
        checkpointDigest = t20Sha256("$SEED|checkpoint|$seedDigest"))

    fun holdFirstProductsPage(): CompletableDeferred<Unit> {
        check(heldProductsPageEntered == null) { "Hold is single-use" }
        return CompletableDeferred<Unit>().also { heldProductsPageEntered = it }
    }

    fun prepareNextHeldProductsPage() {
        check(heldProductsPageEntered?.isCompleted == true && releaseProductsPage.isCompleted)
        heldProductsPageEntered = null
        releaseProductsPage = CompletableDeferred()
    }

    fun releaseHeldProductsPage() {
        check(heldProductsPageEntered?.isCompleted == true)
        check(releaseProductsPage.complete(Unit)) { "Held page already released" }
    }

    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
        requireContext(context)
        checkpointCallCounter.incrementAndGet()
        return Result.success(checkpoint.copy(syncEvents = checkpoint.syncEvents.copy(verifiedBaselineId = context.verifiedBaselineId)))
    }

    override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
        requireContext(context)
        return Result.success(ShopSyncConvergenceMarker(schemaVersion = "shop-sync-convergence-marker-v1", status = "ready",
            shopId = SHOP, scope = checkpoint.scope,
            syncEvents = checkpoint.syncEvents.copy(verifiedBaselineId = context.verifiedBaselineId, requiresFullRecovery = false),
            catalog = checkpoint.catalog, prices = checkpoint.prices, history = checkpoint.history, images = checkpoint.images,
            integrity = ShopSyncMarkerIntegrity(0), checkpointDigest = checkpoint.checkpointDigest,
            serverNoWorkEligible = true, markerDigest = t20Sha256("$SEED|marker|$seedDigest")))
    }

    override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
        requireContext(context)
        check(limit in 1..DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.pageRows(domain))
        check(context.expectedEventMaxId == EVENT_FENCE.toString())
        check(context.expectedDomainEventMaxId == EVENT_FENCE.toString())
        if (domain == ShopSyncRowDomain.PRODUCTS && afterId == null) {
            heldProductsPageEntered?.let { entered ->
                check(entered.complete(Unit)) { "First PRODUCTS page requested twice while held" }
                if (heldPageTimeoutMs == null) releaseProductsPage.await()
                else withTimeout(heldPageTimeoutMs) { releaseProductsPage.await() }
            }
        }
        pageCallCounter.incrementAndGet()
        val ids = sortedIds.getValue(domain)
        val start = if (afterId == null) 0 else {
            val found = ids.binarySearch(afterId)
            check(found >= 0) { "Unknown T20 keyset cursor" }
            found + 1
        }
        val end = minOf(start + limit, ids.size)
        val rows = domains.getValue(domain).slice(start, end)
        val more = end < ids.size
        val next = if (more) ids[end - 1] else null
        val body = buildJsonObject {
            put("schema_version", "shop-sync-recovery-page-v1"); put("shop_id", SHOP)
            put("scope", fixtureJson.parseToJsonElement(fixtureJson.encodeToString(checkpoint.scope)))
            put("domain", domain.wireValue); put("snapshot_event_max_id", EVENT_FENCE.toString())
            put("current_scope_event_max_id", EVENT_FENCE.toString()); put("baseline_domain_event_max_id", EVENT_FENCE.toString())
            put("page_domain_event_max_id", EVENT_FENCE.toString()); put("domain_scope", ShopSyncScopeKinds.SHOP_SCOPED)
            put("page_limit", limit); put("rows", fixtureJson.parseToJsonElement(encodeRows(rows)))
            if (next == null) put("next_after_id", JsonNull) else put("next_after_id", next)
            put("has_more", more)
        }.toString()
        return Result.success(ShopSyncRecoveryPage(schemaVersion = "shop-sync-recovery-page-v1", shopId = SHOP,
            scope = checkpoint.scope, domain = domain, snapshotEventMaxId = EVENT_FENCE.toString(),
            currentScopeEventMaxId = EVENT_FENCE.toString(), baselineDomainEventMaxId = EVENT_FENCE.toString(),
            pageDomainEventMaxId = EVENT_FENCE.toString(), domainScope = ShopSyncScopeKinds.SHOP_SCOPED,
            pageLimit = limit, rows = rows, nextAfterId = next, hasMore = more,
            responseBytes = body.toByteArray(Charsets.UTF_8).size.toLong()))
    }

    override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> =
        Result.failure(AssertionError("T20 unchanged snapshot must not request event tail"))
    override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, ids: List<String>): Result<ShopSyncTargetedRows> =
        Result.failure(AssertionError("T20 unchanged snapshot must not request targeted rows"))

    private fun requireContext(context: ShopSyncRpcContext) {
        check(context.accountId == OWNER && context.shopId == SHOP && context.deviceIdentifier == DEVICE)
        check(context.expectedScope == null || context.expectedScope == checkpoint.scope)
    }
    private fun ShopSyncRows.slice(start: Int, end: Int): ShopSyncRows = when (this) {
        is ShopSyncRows.Suppliers -> ShopSyncRows.Suppliers(values.subList(start, end))
        is ShopSyncRows.Categories -> ShopSyncRows.Categories(values.subList(start, end))
        is ShopSyncRows.Products -> ShopSyncRows.Products(values.subList(start, end))
        is ShopSyncRows.Prices -> ShopSyncRows.Prices(values.subList(start, end))
        is ShopSyncRows.History -> ShopSyncRows.History(values.subList(start, end))
        is ShopSyncRows.Images -> ShopSyncRows.Images(values.subList(start, end))
    }
    private fun encodeRows(rows: ShopSyncRows): String = when (rows) {
        is ShopSyncRows.Suppliers -> fixtureJson.encodeToString(rows.values)
        is ShopSyncRows.Categories -> fixtureJson.encodeToString(rows.values)
        is ShopSyncRows.Products -> fixtureJson.encodeToString(rows.values)
        is ShopSyncRows.Prices -> fixtureJson.encodeToString(rows.values)
        is ShopSyncRows.History -> fixtureJson.encodeToString(rows.values)
        is ShopSyncRows.Images -> fixtureJson.encodeToString(rows.values)
    }

    companion object {
        const val SEED = "task144-t20-native-20k-40k-v1"
        const val PRODUCT_COUNT = 20_000
        const val PRICE_COUNT = 40_000
        const val EVENT_FENCE = 42L
        const val OWNER = "14500000-0000-4000-8000-000000000001"
        const val SHOP = "14500000-0000-4000-8000-000000000002"
        const val DEVICE = "android-t20-native-synthetic-device"
        private const val TIMESTAMP = "2026-10-16T12:00:00.000000Z"
        private const val LEGACY_TIMESTAMP = "2026-10-16 12:00:00"
        private fun uuid(domain: Int, index: Int): String =
            "1450000$domain-0000-4000-8000-${index.toString().padStart(12, '0')}"
    }
}

private fun t20Sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

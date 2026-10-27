package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import android.os.Process

// TEST ONLY: real Room and recovery coordinator, deterministic synthetic read transport.
internal object RecoveryInterruptionContract {
    const val OWNER = "14412000-0000-4000-8000-000000000001"
    const val OLD_OWNER = "14412000-0000-4000-8000-000000000002"
    const val SHOP = "14412000-0000-4000-8000-000000000003"
    const val DEVICE = "android-task144-t12-owned"
    const val DB = "task144-t12-owned.db"
    const val OLD_BARCODE = "TASK144-T12-OLD"
    const val PROTOCOL = "task144-native-t12-candidate01"
    const val FENCE = 42L
    const val FIRST = "14412000-0000-4000-8000-000000000101"
    const val SECOND = "14412000-0000-4000-8000-000000000102"
    const val TIMESTAMP = "2026-07-21T10:00:00.000000Z"
    val json = Json { encodeDefaults = true }
    val shop = SelectedShop(SHOP, "T12OWNED", "Synthetic T12 fixture", "shop_owner", "active", true)
    val scope = task126ActiveOwnerStoreScope(OWNER, shop)
    val oldScope = task126ActiveOwnerStoreScope(OLD_OWNER, shop)
    val suppliers = listOf(
        InventorySupplierRow(FIRST, OWNER, SHOP, "T12 first accepted supplier", TIMESTAMP),
        InventorySupplierRow(SECOND, OWNER, SHOP, "T12 second supplier", TIMESTAMP)
    )
}

internal enum class RecoveryInterruptionBoundary(val wire: String, val phase: String) {
    ACCEPTED_PREFIX("accepted-prefix", SyncRecoveryJournalPhases.STAGING),
    READY("ready", SyncRecoveryJournalPhases.READY_TO_ACTIVATE),
    POST_COMMIT("post-commit", SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING)
}

internal fun interruptionSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** Atomic replacement and fsync of bytes AND containing directory, before boundary publication. */
internal fun interruptionDurableWrite(file: File, bytes: ByteArray) {
    check(file.parentFile!!.isDirectory)
    val temporary = File(file.parentFile, file.name + ".pending")
    check(!temporary.exists()) { "Preserve incomplete previous write; do not overwrite it" }
    FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
    Os.rename(temporary.path, file.path)
    val directory = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
    try {
        check(OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) { "Evidence descriptor is not a directory" }
        Os.fsync(directory)
    } finally { Os.close(directory) }
}

/** Adapts T20 isolation for a verified owned reopen. No generic DB/staging sweep or cleanup. */
internal class InterruptionOwnedContext(base: Context, val runId: String, val scenario: String, fresh: Boolean) : ContextWrapper(base) {
    val root: File
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        require(scenario in setOf("accepted-prefix", "ready", "post-commit"))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-t12-$runId-$scenario")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val ownership = "${RecoveryInterruptionContract.PROTOCOL}\n$runId\n$scenario\n"
        val sentinel = File(root, "ownership.txt")
        if (fresh) {
            check(!root.exists()) { "Fresh run ID required; existing evidence is preserved" }
            check(root.mkdir())
            interruptionDurableWrite(sentinel, ownership.encodeToByteArray())
            check(File(root, "databases").mkdir())
        } else {
            check(root.isDirectory && sentinel.isFile && sentinel.readText() == ownership)
            check(File(root, "databases/${RecoveryInterruptionContract.DB}").isFile) { "Owned Room store missing" }
        }
        databases = File(root, "databases")
        require(databases.canonicalFile == databases.absoluteFile)
    }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File {
        if (name == ".") return databases
        require(name.isNotBlank() && name != ".." && !name.contains('/') && !name.contains('\\'))
        return File(databases, name).also { require(it.canonicalFile == it.absoluteFile) }
    }
    override fun databaseList(): Array<String> = (databases.list()?.toList() ?: emptyList()).toTypedArray()
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    override fun getFilesDir(): File = child("files")
    override fun getCacheDir(): File = child("cache")
    override fun getNoBackupFilesDir(): File = child("no_backup")
    private fun child(name: String): File = File(root, name).also {
        require(it.canonicalFile == it.absoluteFile)
        check(it.isDirectory || it.mkdir())
    }
}

internal fun interruptionOwnedContext(scenario: String, fresh: Boolean): InterruptionOwnedContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name) {
        "Requires committed neutral runner (-PmobileSyncPerformance=true)"
    }
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    val method = requireNotNull(args.getString("class"))
    val expected = when (scenario) {
        "accepted-prefix" -> if (fresh) "prepareAtAcceptedPrefix" else "verifyAcceptedPrefixAfterProcessDeath"
        "ready" -> if (fresh) "prepareAtReadyJournal" else "verifyReadyJournalAfterProcessDeath"
        "post-commit" -> if (fresh) "prepareAfterActivationCommit" else "verifyActivationCommitAfterProcessDeath"
        else -> error("Unknown owned scenario")
    }
    check(method == Task144RecoveryInterruptionDeviceTest::class.java.name + "#" + expected)
    check(args.getString("nativeT12Protocol") == RecoveryInterruptionContract.PROTOCOL)
    return InterruptionOwnedContext(instrumentation.targetContext, requireNotNull(args.getString("nativeT12RunId")), scenario, fresh)
}

@Serializable
internal data class InterruptionProgress(
    val domain: String, val afterId: String?, val complete: Boolean, val pages: Long, val rows: Long,
    val bytes: Long, val largestRowBytes: Long
)
@Serializable
internal data class InterruptionStage(
    val progress: List<InterruptionProgress>, val manifestIds: List<String>, val supplierIds: List<String>
)
@Serializable
internal data class InterruptionMarker(
    val protocol: String, val runId: String, val scenario: String, val preparePid: Int,
    val generation: String, val phase: String, val stagingName: String,
    val checkpointADigest: String, val checkpointBDigest: String?,
    val stage: InterruptionStage, val ledgerSha256: String
)
@Serializable
internal data class InterruptionCall(
    val pid: Int, val kind: String, val domain: String? = null, val afterId: String? = null,
    val limit: Int? = null, val eventFence: String? = null, val domainFence: String? = null,
    val verifiedBaselineId: String
)
@Serializable
internal data class InterruptionLedger(val calls: List<InterruptionCall> = emptyList())
@Serializable
internal data class InterruptionVerified(
    val protocol: String, val runId: String, val scenario: String, val preparePid: Int, val verifyPid: Int,
    val generation: String, val preparedMarkerSha256: String,
    val verifyPageCalls: Int, val verifyCheckpointCalls: Int, val verifyMarkerCalls: Int,
    val totalPageCalls: Int, val firstSupplierPageCalls: Int, val secondSupplierPageCalls: Int,
    val finalSupplierIds: List<String>, val ledgerSha256: String
)

internal fun interruptionOpenDb(context: InterruptionOwnedContext, name: String = RecoveryInterruptionContract.DB): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .build().also {
            check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(name))
        }

internal fun interruptionCoordinator(context: InterruptionOwnedContext, db: AppDatabase, remote: InterruptionRemote) =
    ShopSyncRecoveryCoordinator(context, db, DefaultInventoryRepository(db), remote,
        registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
        scopeStillValid = { owner, shop -> owner == RecoveryInterruptionContract.OWNER && shop == RecoveryInterruptionContract.SHOP },
        activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })

/** No reset or cleanup: only the prepare selector may create these owned fixture rows. */
internal suspend fun interruptionSeed(db: AppDatabase) {
    val c = RecoveryInterruptionContract
    assertEquals(0, db.productDao().count())
    assertNull(db.syncRecoveryJournalDao().get())
    db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = c.DEVICE, createdAtMs = 144L))
    db.productDao().insert(Product(barcode = c.OLD_BARCODE, productName = "Old generation must remain atomic"))
    db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(c.oldScope, 144L))
    db.syncEventWatermarkDao().upsert(SyncEventWatermark(c.OLD_OWNER, c.oldScope.storeId, 7L))
    db.syncEventOutboxDao().insert(SyncEventOutboxEntry(
        ownerUserId = c.OLD_OWNER, storeScope = c.oldScope.storeId,
        domain = SyncEventDomains.CATALOG, eventType = SyncEventTypes.CATALOG_CHANGED,
        source = "task144-owned-interruption", sourceDeviceId = c.DEVICE, batchId = null,
        clientEventId = "task144-owned-old-event", changedCount = 1, entityIdsJson = "{}", metadataJson = "{}", createdAtMs = 1L))
    db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
        ownerHash = c.scope.ownerHash, storeScope = c.scope.storeId, shopId = c.SHOP, deviceId = c.DEVICE,
        authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
        phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
        blockingEventId = 40L, attemptCount = 1, createdAtMs = 100L, updatedAtMs = 100L, nextRetryAtMs = 100L))
}

internal suspend fun interruptionAssertSuppliers(db: AppDatabase, expectedIds: List<String>) {
    val rows = db.supplierDao().getAll()
    assertEquals(expectedIds.size, rows.size)
    val actual = rows.map { row ->
        val ref = requireNotNull(db.supplierRemoteRefDao().getBySupplierId(row.id))
        assertEquals(RecoveryInterruptionContract.suppliers.single { it.id == ref.remoteId }.name, row.name)
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        ref.remoteId
    }.sorted()
    assertEquals(expectedIds, actual)
}

/** Reads accepted progress AND physical rows using a second Room connection, after the first commit. */
internal suspend fun interruptionStage(context: InterruptionOwnedContext, db: AppDatabase, boundary: RecoveryInterruptionBoundary): InterruptionStage {
    val journal = requireNotNull(db.syncRecoveryJournalDao().get())
    val generation = requireNotNull(journal.runId)
    val name = requireNotNull(journal.stagingDatabaseName)
    assertEquals("sync_recovery_stage_$generation.db", name)
    assertTrue("Existing owned staging DB required; inspection must not create it", context.getDatabasePath(name).isFile)
    val stage = interruptionOpenDb(context, name)
    try {
        val progress = RecoveryPageProgress(stage.openHelper.writableDatabase)
        assertEquals(journal.checkpointADigest, progress.checkpoint(journal).checkpointDigest)
        val states = ShopSyncRowDomain.entries.mapNotNull { domain ->
            progress.accepted(domain)?.let { InterruptionProgress(domain.wireValue, it.afterId, it.complete, it.pages, it.rows, it.bytes, it.largestRowBytes) }
        }
        val prefix = boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX
        val expectedIds = if (prefix) listOf(RecoveryInterruptionContract.FIRST) else listOf(RecoveryInterruptionContract.FIRST, RecoveryInterruptionContract.SECOND)
        val supplier = states.single { it.domain == ShopSyncRowDomain.SUPPLIERS.wireValue }
        assertEquals(if (prefix) 1 else ShopSyncRowDomain.entries.size, states.size)
        assertEquals(if (prefix) 1L else 2L, supplier.pages)
        assertEquals(expectedIds.size.toLong(), supplier.rows)
        assertEquals(!prefix, supplier.complete)
        assertEquals(if (prefix) RecoveryInterruptionContract.FIRST else null, supplier.afterId)
        assertTrue(supplier.bytes > 0)
        if (!prefix) assertTrue(states.all { it.complete && it.afterId == null })
        interruptionAssertSuppliers(stage, expectedIds)
        val manifest = stage.openHelper.readableDatabase.query(
            "SELECT remoteId FROM sync_recovery_manifest WHERE generationId=? ORDER BY remoteId", arrayOf(generation)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertEquals(expectedIds, manifest)
        if (!prefix) assertEquals(generation, stage.syncRecoveryBaselineDao().get()?.generationId)
        stage.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        return InterruptionStage(states, manifest, expectedIds)
    } finally { stage.close() }
}

/** Persistent invocation ledger only: no write endpoint, no new recovery/authority implementation. */
internal class InterruptionRemote(val file: File, fresh: Boolean) : ShopSyncReadRemoteDataSource {
    override val isConfigured = true
    val checkpointValue = interruptionCheckpoint()
    var beforeSecondSupplierResponse: (() -> Unit)? = null
    init {
        if (fresh) {
            check(!file.exists())
            interruptionDurableWrite(file, RecoveryInterruptionContract.json.encodeToString(InterruptionLedger()).encodeToByteArray())
        } else check(file.isFile) { "Persisted synthetic RPC ledger must survive process death" }
    }
    fun ledger(): InterruptionLedger = RecoveryInterruptionContract.json.decodeFromString(file.readText())
    private fun record(context: ShopSyncRpcContext, kind: String, domain: ShopSyncRowDomain? = null, afterId: String? = null, limit: Int? = null) {
        val c = RecoveryInterruptionContract
        check(context.accountId == c.OWNER && context.shopId == c.SHOP && context.deviceIdentifier == c.DEVICE)
        val call = InterruptionCall(Process.myPid(), kind, domain?.wireValue, afterId, limit,
            context.expectedEventMaxId, context.expectedDomainEventMaxId, context.verifiedBaselineId)
        interruptionDurableWrite(file, c.json.encodeToString(InterruptionLedger(ledger().calls + call)).encodeToByteArray())
    }
    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
        record(context, "checkpoint")
        return Result.success(checkpointValue.copy(syncEvents = checkpointValue.syncEvents.copy(verifiedBaselineId = context.verifiedBaselineId)))
    }
    override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
        record(context, "marker")
        return Result.success(ShopSyncConvergenceMarker(
            schemaVersion = "shop-sync-convergence-marker-v1", status = "ready", shopId = RecoveryInterruptionContract.SHOP,
            scope = checkpointValue.scope, syncEvents = checkpointValue.syncEvents.copy(verifiedBaselineId = context.verifiedBaselineId, requiresFullRecovery = false),
            catalog = checkpointValue.catalog, prices = checkpointValue.prices, history = checkpointValue.history,
            images = checkpointValue.images, integrity = ShopSyncMarkerIntegrity(0), checkpointDigest = checkpointValue.checkpointDigest,
            serverNoWorkEligible = true, markerDigest = "e".repeat(64)))
    }
    override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
        record(context, "page", domain, afterId, limit)
        check(limit == ShopSyncRecoveryResourceLimits().pageRows(domain))
        val first = domain == ShopSyncRowDomain.SUPPLIERS && afterId == null
        val rows = if (domain == ShopSyncRowDomain.SUPPLIERS) {
            check(afterId == null || afterId == RecoveryInterruptionContract.FIRST)
            if (!first) beforeSecondSupplierResponse?.invoke() // Entry after accepted first prefix, before second response.
            ShopSyncRows.Suppliers(listOf(RecoveryInterruptionContract.suppliers[if (first) 0 else 1]))
        } else {
            check(afterId == null)
            when (domain) {
                ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(emptyList())
                ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(emptyList())
                ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(emptyList())
                ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(emptyList())
                ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(emptyList())
                ShopSyncRowDomain.SUPPLIERS -> error("Supplier branch handled above")
            }
        }
        return Result.success(ShopSyncRecoveryPage(
            schemaVersion = "shop-sync-recovery-page-v1", shopId = RecoveryInterruptionContract.SHOP,
            scope = checkpointValue.scope, domain = domain,
            snapshotEventMaxId = requireNotNull(context.expectedEventMaxId), currentScopeEventMaxId = requireNotNull(context.expectedEventMaxId),
            baselineDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId), pageDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
            domainScope = ShopSyncScopeKinds.SHOP_SCOPED, pageLimit = limit, rows = rows,
            nextAfterId = if (first) RecoveryInterruptionContract.FIRST else null, hasMore = first,
            responseBytes = if (domain == ShopSyncRowDomain.SUPPLIERS) 512L else 2L))
    }
    override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> =
        error("Unchanged synthetic checkpoint must not request an event tail")
    override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, ids: List<String>): Result<ShopSyncTargetedRows> =
        error("Unchanged synthetic checkpoint must not request targeted rows")
}

private fun interruptionCheckpoint(): ShopSyncRecoveryCheckpoint {
    fun hash(value: String) = interruptionSha(value.encodeToByteArray())
    fun digest(lines: List<String>) = lines.fold(hash("")) { previous, line -> hash("$previous\u001f${line.encodeToByteArray().size}:$line") }
    val c = RecoveryInterruptionContract
    val emptyHash = hash("")
    val empty = ShopSyncDomainCheckpoint(0, 0, emptyHash, emptyHash)
    val ids = listOf(c.FIRST, c.SECOND)
    val suppliers = ShopSyncDomainCheckpoint(2, 0, digest(ids), digest(ids.map { "$it\u001f${c.TIMESTAMP}\u001f-" }))
    return ShopSyncRecoveryCheckpoint(
        schemaVersion = "shop-sync-recovery-checkpoint-v1", status = "ready", shopId = c.SHOP,
        scope = ShopSyncScope(kind = ShopSyncScopeKinds.SHOP_SCOPED, key = "c".repeat(64), historyKind = ShopSyncScopeKinds.SHOP_SCOPED, accountKey = hash(c.OWNER.lowercase()), deviceKey = hash(c.DEVICE)),
        syncEvents = ShopSyncEventCheckpoint(c.FENCE.toString(), "0", false, mapOf(
            SyncEventDomains.CATALOG to c.FENCE.toString(), SyncEventDomains.PRICES to c.FENCE.toString(), SyncEventDomains.HISTORY to c.FENCE.toString())),
        catalog = ShopSyncCatalogCheckpoint(suppliers, empty, empty.copy(identityDigest = emptyHash), hash("${suppliers.versionDigest}\n$emptyHash\n$emptyHash")),
        prices = empty, history = empty, images = empty, integrity = ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0), checkpointDigest = "d".repeat(64))
}

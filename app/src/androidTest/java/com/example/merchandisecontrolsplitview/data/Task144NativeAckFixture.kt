package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.flow.first

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*

// TEST ONLY. Real Room/repository/ACK paths, synthetic transport; no auth or user DB.
internal object NativeAckContract {
    const val OWNER = "14400000-0000-4000-8000-000000000001"
    const val SHOP = "14400000-0000-4000-8000-000000000002"
    const val DEVICE = "14400000-0000-4000-8000-000000000003"
    const val DB = "native-ack.db"
    const val BARCODE = "TASK144-NATIVE-ACK-ONE"
    const val PROTOCOL = "task144-native-ack-candidate01"
    val json = Json { encodeDefaults = true }
    val shop = SelectedShop(SHOP, "NATIVE144", "Synthetic owned fixture", "shop_owner", "active", true)
    val scope = task126ActiveOwnerStoreScope(OWNER, shop)
}

internal fun nativeSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** Atomic replacement and fsync of bytes AND containing directory, before boundary publication. */
internal fun nativeDurableWrite(file: File, bytes: ByteArray) {
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
internal class NativeAckOwnedContext(base: Context, val runId: String, val scenario: String, fresh: Boolean) : ContextWrapper(base) {
    val root: File
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        require(scenario in setOf("save-b", "lost-ack"))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-gap-$runId-$scenario")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val ownership = "${NativeAckContract.PROTOCOL}\n$runId\n$scenario\n"
        val sentinel = File(root, "ownership.txt")
        if (fresh) {
            check(!root.exists()) { "Fresh run ID required; existing evidence is preserved" }
            check(root.mkdir())
            nativeDurableWrite(sentinel, ownership.encodeToByteArray())
            check(File(root, "databases").mkdir())
        } else {
            check(root.isDirectory && sentinel.isFile && sentinel.readText() == ownership)
            check(File(root, "databases/${NativeAckContract.DB}").isFile) { "Owned Room store missing" }
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

internal fun nativeOwnedContext(scenario: String, fresh: Boolean): NativeAckOwnedContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name) {
        "Requires committed neutral runner (-PmobileSyncPerformance=true)"
    }
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class")?.startsWith(Task144NativeAckDeviceTest::class.java.name + "#") == true)
    check(args.getString("nativeAckProtocol") == NativeAckContract.PROTOCOL)
    return NativeAckOwnedContext(instrumentation.targetContext, requireNotNull(args.getString("nativeAckRunId")), scenario, fresh)
}

internal class NativeAckFixture(val context: NativeAckOwnedContext) : AutoCloseable {
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NativeAckContract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build().also { check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(NativeAckContract.DB)) }
    private val reader = NativeAckEmptyRecoveryRemote()
    lateinit var repository: DefaultInventoryRepository
        private set
    lateinit var tracker: CatalogSyncStateTracker
        private set
    val backend = NativeAckPersistentCatalog(db, File(context.root, "synthetic-backend.json"))
    private val prices = object : ProductPriceRemoteDataSource {
        override val isConfigured = true
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> = error("No price write belongs to this fixture")
        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = error("No price pull belongs to this fixture")
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> = error("No targeted price pull belongs to this fixture")
    }
    suspend fun seed() {
        assertEquals(0, db.productDao().count())
        assertNull(db.syncRecoveryBaselineDao().get())
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = NativeAckContract.DEVICE, createdAtMs = 1))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
            ownerHash = NativeAckContract.scope.ownerHash, storeScope = NativeAckContract.scope.storeId,
            shopId = NativeAckContract.SHOP, deviceId = NativeAckContract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = 1, updatedAtMs = 1, nextRetryAtMs = 1))
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val recovered = ShopSyncRecoveryCoordinator(context, db, initial, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
            scopeStillValid = { owner, shop -> owner == NativeAckContract.OWNER && shop == NativeAckContract.SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
            .recover(NativeAckContract.OWNER, NativeAckContract.shop, NativeAckContract.scope)
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
        qualify()
        repository.addProduct(Product(barcode = NativeAckContract.BARCODE, productName = "Original", itemNumber = "Original item"))
        push()
        assertEquals(ref().localChangeRevision, ref().lastSyncedLocalRevision)
        assertEquals(0, db.syncEventOutboxDao().countAll())
    }
    suspend fun qualify() {
        val state = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
            .resolveBusinessDataScope(NativeAckContract.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        tracker = CatalogSyncStateTracker(state)
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
    }
    suspend fun push() = tracker.withBusinessDataScopeFlight(NativeAckContract.OWNER, NativeAckContract.shop) {
        repository.pushDirtyCatalogDeltaToRemote(backend, prices, NativeAckContract.OWNER,
            CatalogSyncProgressReporter { }, NativeAckContract.shop).getOrThrow()
    }
    suspend fun product(): Product = requireNotNull(db.productDao().findByBarcode(NativeAckContract.BARCODE))
    suspend fun ref(): ProductRemoteRef = requireNotNull(db.productRemoteRefDao().getByProductId(product().id))
    suspend fun save(name: String) { repository.updateProduct(product().copy(productName = name)) }
    suspend fun patchReceipt(): SyncEventOutboxEntry {
        val header = db.syncEventOutboxDao().listBusinessWriteHeaders(NativeAckContract.OWNER, NativeAckContract.scope.storeId, setOf("PATCH"), 10).single()
        return requireNotNull(db.syncEventOutboxDao().getById(header.id))
    }
    suspend fun assertScopedOutbox(expectedWrites: Int) {
        val entries = readLocalBusinessEntries(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)
        assertEquals(entries.size, db.syncEventOutboxDao().countAll())
        assertEquals(expectedWrites, entries.count { it.eventType == BUSINESS_WRITE_EVENT })
        assertTrue(entries.all { it.eventType == BUSINESS_WRITE_EVENT ||
            (it.eventType == BUSINESS_RESOLUTION_EVENT && BusinessResolutionLedger.decode(it).kind == "intent_provenance") })
        requireNotNull(loadBusinessResolutionSnapshot(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId, entries)).requireValid()
    }
    suspend fun assertClean(revision: Int, generation: String) {
        assertEquals("Save B", product().productName)
        assertEquals(revision, ref().localChangeRevision)
        assertEquals(revision, ref().lastSyncedLocalRevision)
        assertNull(ref().localChangedFields)
        assertFalse(db.productRemoteRefDao().hasPendingWork())
        assertScopedOutbox(expectedWrites = 0)
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
        assertTrue(repository.observeProductCloudConfirmed(product().id).first())
        assertEquals(1, db.productDao().count())
        assertEquals(0, db.productPriceDao().countAll())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertEquals(generation, db.syncRecoveryBaselineDao().get()?.generationId)
        assertEquals(NativeAckContract.scope.ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertEquals(NativeAckContract.scope.storeId, db.businessDataScopeBindingDao().get()?.storeId)
        assertNull(db.syncRecoveryJournalDao().get())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    override fun close() = db.close()
}

@Serializable
internal data class NativeAckWireCall(
    val kind: String, val operationKey: String, val rawAttempt: String, val stampedAttemptCount: Int,
    val products: List<InventoryProductRow> = emptyList(), val patchId: String? = null,
    val patch: InventoryProductPatch? = null
)
@Serializable
internal data class NativeAckServerState(
    val product: InventoryProductRow? = null,
    val calls: List<NativeAckWireCall> = emptyList(),
    val logicalMutations: Int = 0
)

/**
 * A narrow persistent transport fake, not a new sync engine or product authority.
 * Records EVERY wire invocation (including replay); applies the actual row/patch by ID.
 * Operation keys are observed from the durable local receipt, not sent over a new API,
 * and are NOT used to suppress a replay. Reapplying identical wire data changes no row.
 */
internal class NativeAckPersistentCatalog(private val db: AppDatabase, val file: File) : CatalogRemoteDataSource {
    override val isConfigured = true
    var afterPatchCommit: (suspend () -> Unit)? = null
    fun state(): NativeAckServerState = if (file.isFile) NativeAckContract.json.decodeFromString(file.readText()) else NativeAckServerState()
    private suspend fun capture(kind: String, products: List<InventoryProductRow> = emptyList(), id: String? = null, patch: InventoryProductPatch? = null): NativeAckWireCall {
        val headers = db.syncEventOutboxDao().listBusinessWriteHeaders(NativeAckContract.OWNER, NativeAckContract.scope.storeId, setOf(kind), 10)
        val entry = requireNotNull(db.syncEventOutboxDao().getById(headers.single().id))
        val attempt = NativeAckContract.json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
        validateBusinessWritePayload(attempt)
        check(entry.eventType == BUSINESS_WRITE_EVENT && entry.attemptCount > 0)
        check(entry.clientEventId == "business-write-v1:" + nativeSha(entry.metadataJson.encodeToByteArray()))
        check(attempt.owner == NativeAckContract.OWNER && attempt.shop == NativeAckContract.SHOP)
        check(attempt.store == NativeAckContract.scope.storeId && attempt.device == NativeAckContract.DEVICE)
        check(attempt.generation == db.syncRecoveryBaselineDao().get()?.generationId)
        check(attempt.payload.kind == kind && attempt.payload.products == products && attempt.payload.patchId == id && attempt.payload.patch == patch)
        return NativeAckWireCall(kind, entry.clientEventId, entry.metadataJson, entry.attemptCount, products, id, patch)
    }
    private fun commit(call: NativeAckWireCall) {
        val old = state()
        val product = if (call.kind == "PRODUCTS") call.products.single() else {
            val row = requireNotNull(old.product)
            check(call.patchId == row.id)
            val patch = requireNotNull(call.patch)
            check(patch.changedFields == setOf("productname")) { "Only the reviewed same-field Save scenario is accepted" }
            row.copy(productName = patch.productName)
        }
        check(product.ownerUserId == NativeAckContract.OWNER && product.shopId == NativeAckContract.SHOP)
        val next = NativeAckServerState(product, old.calls + call, old.logicalMutations + if (old.product != product) 1 else 0)
        nativeDurableWrite(file, NativeAckContract.json.encodeToString(next).encodeToByteArray())
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        check(rows.size == 1); commit(capture("PRODUCTS", products = rows)); return Result.success(Unit)
    }
    override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch): Result<Unit> {
        check(ownerUserId == NativeAckContract.OWNER && shopId == NativeAckContract.SHOP)
        commit(capture("PATCH", id = id, patch = patch))
        afterPatchCommit?.invoke() // Real repository ACK remains pending while this suspends.
        return Result.success(Unit)
    }
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = error("Unexpected supplier write")
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = error("Unexpected category write")
    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = error("Unexpected full catalog pull")
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>, categoryIds: Set<String>, productIds: Set<String>): Result<InventoryCatalogFetchBundle> = error("Unexpected targeted catalog pull")
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("Unexpected tombstone")
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("Unexpected tombstone")
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("Unexpected tombstone")
}

// Adapted from committed Task139 EmptyRecoveryRemote; no resetFixture or shared paths.
internal class NativeAckEmptyRecoveryRemote : ShopSyncReadRemoteDataSource {
    override val isConfigured = true
    val checkpoint = emptyCheckpoint()
    var checkpointCalls = 0
    var pageCalls = 0

    override suspend fun checkpoint(
        context: ShopSyncRpcContext
    ): Result<ShopSyncRecoveryCheckpoint> {
        checkpointCalls += 1
        return Result.success(
            checkpoint.copy(
                syncEvents = checkpoint.syncEvents.copy(
                    verifiedBaselineId = context.verifiedBaselineId
                )
            )
        )
    }

    override suspend fun convergenceMarker(
        context: ShopSyncRpcContext
    ): Result<ShopSyncConvergenceMarker> = Result.success(
        ShopSyncConvergenceMarker(
            schemaVersion = "shop-sync-convergence-marker-v1",
            status = "ready",
            shopId = NativeAckContract.SHOP,
            scope = checkpoint.scope,
            syncEvents = checkpoint.syncEvents.copy(
                verifiedBaselineId = context.verifiedBaselineId,
                requiresFullRecovery = false
            ),
            catalog = checkpoint.catalog,
            prices = checkpoint.prices,
            history = checkpoint.history,
            images = checkpoint.images,
            integrity = ShopSyncMarkerIntegrity(0),
            checkpointDigest = checkpoint.checkpointDigest,
            serverNoWorkEligible = true,
            markerDigest = "e".repeat(64)
        )
    )

    override suspend fun recoveryPage(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        afterId: String?,
        limit: Int
    ): Result<ShopSyncRecoveryPage> {
        check(afterId == null)
        pageCalls += 1
        return Result.success(
            ShopSyncRecoveryPage(
                schemaVersion = "shop-sync-recovery-page-v1",
                shopId = NativeAckContract.SHOP,
                scope = checkpoint.scope,
                domain = domain,
                snapshotEventMaxId = requireNotNull(context.expectedEventMaxId),
                currentScopeEventMaxId = requireNotNull(context.expectedEventMaxId),
                baselineDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                pageDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                domainScope = ShopSyncScopeKinds.SHOP_SCOPED,
                pageLimit = limit,
                rows = emptyRows(domain),
                nextAfterId = null,
                hasMore = false,
                responseBytes = 2L
            )
        )
    }

    override suspend fun eventPage(
        context: ShopSyncRpcContext,
        afterId: Long,
        limit: Int
    ): Result<ShopSyncEventPage> =
        Result.failure(AssertionError("unchanged checkpoint must not request an event tail"))

    override suspend fun rowsByIds(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        ids: List<String>
    ): Result<ShopSyncTargetedRows> =
        Result.failure(AssertionError("empty checkpoint must not request targeted rows"))
    private companion object {
        fun emptyCheckpoint(): ShopSyncRecoveryCheckpoint {
            val emptyDigest = sha256("")
            val empty = ShopSyncDomainCheckpoint(
                activeCount = 0,
                tombstoneCount = 0,
                idSetDigest = emptyDigest,
                versionDigest = emptyDigest
            )
            val products = empty.copy(identityDigest = emptyDigest)
            val catalog = ShopSyncCatalogCheckpoint(
                suppliers = empty,
                categories = empty,
                products = products,
                digest = sha256("$emptyDigest\n$emptyDigest\n$emptyDigest")
            )
            return ShopSyncRecoveryCheckpoint(
                schemaVersion = "shop-sync-recovery-checkpoint-v1",
                status = "ready",
                shopId = NativeAckContract.SHOP,
                scope = ShopSyncScope(
                    kind = ShopSyncScopeKinds.SHOP_SCOPED,
                    key = "c".repeat(64),
                    historyKind = ShopSyncScopeKinds.SHOP_SCOPED,
                    accountKey = sha256(NativeAckContract.OWNER.lowercase()),
                    deviceKey = sha256(NativeAckContract.DEVICE)
                ),
                syncEvents = ShopSyncEventCheckpoint(
                    maxId = "0",
                    verifiedBaselineId = "0",
                    requiresFullRecovery = false,
                    domainMaxIds = mapOf(
                        SyncEventDomains.CATALOG to "0",
                        SyncEventDomains.PRICES to "0",
                        SyncEventDomains.HISTORY to "0"
                    )
                ),
                catalog = catalog,
                prices = empty,
                history = empty,
                images = empty,
                integrity = ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0),
                checkpointDigest = "d".repeat(64)
            )
        }

        fun emptyRows(domain: ShopSyncRowDomain): ShopSyncRows = when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> ShopSyncRows.Suppliers(emptyList())
            ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(emptyList())
            ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(emptyList())
            ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(emptyList())
            ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(emptyList())
            ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(emptyList())
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.encodeToByteArray())
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
    }
}

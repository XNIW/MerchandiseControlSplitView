package com.example.merchandisecontrolsplitview.data

import android.os.Process
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Test-only dependency on reviewed native-gap02 helpers; exact shared APK binding is mandatory. */
internal object T11Contract {
    const val PROTOCOL = "task144-native-independent-progress-v1"
    const val DB = "native-independent.db"
    const val OWNER = NativeAckContract.OWNER
    const val SHOP = NativeAckContract.SHOP
    const val DEVICE = NativeAckContract.DEVICE
    val shop = NativeAckContract.shop
    val scope = NativeAckContract.scope
    val json = Json { encodeDefaults = true }
}

internal class T11OwnedContext(base: Context, val runId: String, val scenario: String, fresh: Boolean) : ContextWrapper(base) {
    val root: File
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        require(scenario in setOf("local-supplier", "malformed-event", "scope", "seal", "ledger-guard"))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-t11-$runId-$scenario")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val ownership = "${T11Contract.PROTOCOL}\n$runId\n$scenario\n"
        val sentinel = File(root, "ownership.txt")
        if (fresh) {
            check(!root.exists()) { "Fresh run ID required; existing evidence is preserved" }
            check(root.mkdir())
            nativeDurableWrite(sentinel, ownership.encodeToByteArray())
            check(File(root, "databases").mkdir())
        } else {
            check(root.isDirectory && sentinel.isFile && sentinel.readText() == ownership)
            check(File(root, "databases/${T11Contract.DB}").isFile) { "Owned Room store missing" }
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

internal fun t11Context(scenario: String, fresh: Boolean): T11OwnedContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class")?.startsWith(Task144IndependentProgressDeviceTest::class.java.name + "#") == true)
    check(args.getString("nativeT11Protocol") == T11Contract.PROTOCOL)
    return T11OwnedContext(instrumentation.targetContext, requireNotNull(args.getString("nativeT11RunId")), scenario, fresh)
}

internal class T11Fixture(val context: T11OwnedContext) : AutoCloseable {
    val db = Room.databaseBuilder(context, AppDatabase::class.java, T11Contract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also {
            check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(T11Contract.DB))
        }
    val backend = T11Backend(db, File(context.root, "per-item-ledger.json"))
    val reader: ShopSyncReadRemoteDataSource = backend.reader
    lateinit var repository: DefaultInventoryRepository
        private set
    lateinit var tracker: CatalogSyncStateTracker
        private set
    private val prices = SupabaseProductPriceRemoteDataSource(null)
    suspend fun seed() {
        assertEquals(0, db.productDao().count()); assertNull(db.syncRecoveryBaselineDao().get())
        val now = System.currentTimeMillis()
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = T11Contract.DEVICE, createdAtMs = now))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = T11Contract.scope.ownerHash,
            storeScope = T11Contract.scope.storeId, shopId = T11Contract.SHOP, deviceId = T11Contract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = now, updatedAtMs = now, nextRetryAtMs = now))
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val recovered = ShopSyncRecoveryCoordinator(context, db, initial, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
            scopeStillValid = { owner, shop -> owner == T11Contract.OWNER && shop == T11Contract.SHOP },
            activationBoundary = { it() }).recover(T11Contract.OWNER, T11Contract.shop, T11Contract.scope)
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
        qualify()
    }
    suspend fun qualify() {
        val state = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader).resolveBusinessDataScope(T11Contract.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        tracker = CatalogSyncStateTracker(state)
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
        assertNull(repository.onCatalogChanged); assertNull(repository.onProductCatalogChanged)
    }
    suspend fun push() = tracker.withBusinessDataScopeFlight(T11Contract.OWNER, T11Contract.shop) {
        repository.syncCatalogQuickWithEvents(backend, prices, backend.events, T11Contract.OWNER,
            progressReporter = CatalogSyncProgressReporter { }, selectedShop = T11Contract.shop)
    }
    suspend fun drain() = tracker.withBusinessDataScopeFlight(T11Contract.OWNER, T11Contract.shop) {
        repository.drainSyncEventsFromRemote(backend, prices, backend.events, T11Contract.OWNER,
            progressReporter = CatalogSyncProgressReporter { }, selectedShop = T11Contract.shop)
    }
    suspend fun addEditedProduct(barcode: String, name: String, supplier: Long? = null): Product {
        repository.addProduct(Product(barcode = barcode, productName = name + " draft", supplierId = supplier))
        val created = requireNotNull(repository.findProductByBarcode(barcode))
        val id = requireNotNull(db.productRemoteRefDao().getByProductId(created.id)).remoteId
        repository.updateProduct(created.copy(productName = name))
        val edited = requireNotNull(repository.findProductByBarcode(barcode))
        assertEquals(created.id, edited.id)
        assertEquals(id, db.productRemoteRefDao().getByProductId(edited.id)?.remoteId)
        return edited
    }
    fun snapshot(table: String): String {
        require(table in setOf("products", "product_remote_refs", "suppliers", "supplier_remote_refs", "sync_event_outbox", "sync_recovery_baseline", "business_data_scope_binding"))
        return db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY 1").use { c ->
            buildJsonArray { while (c.moveToNext()) add(buildJsonObject {
                c.columnNames.forEachIndexed { i, name -> put(name, if (c.isNull(i)) JsonNull else JsonPrimitive(c.getString(i))) }
            }) }.toString()
        }
    }
    fun saveEvidence(name: String, content: JsonObject) {
        require(name.matches(Regex("[a-z0-9-]+\\.json")))
        nativeDurableWrite(File(context.root, name), content.toString().encodeToByteArray())
    }
    fun snapshots(): Map<String, String> = listOf("products", "product_remote_refs", "suppliers", "supplier_remote_refs",
        "sync_event_outbox", "sync_recovery_baseline", "business_data_scope_binding").associateWith(::snapshot)
    fun evidence(note: String? = null): JsonObject = buildJsonObject {
        put("protocol", T11Contract.PROTOCOL); put("runId", context.runId); put("scenario", context.scenario)
        put("pid", Process.myPid()); put("atMs", System.currentTimeMillis()); put("note", note)
        put("tables", buildJsonObject { snapshots().forEach { (table, raw) -> put(table, raw) } })
        put("mutatingTransportEntries", JsonArray(backend.records()))
    }
    suspend fun assertUnrelatedDomainsUntouched() {
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(0, db.productPriceDao().countAll()); assertEquals(0, db.historyEntryDao().countUserVisible())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    override fun close() = db.close() // Never remove evidence, including FAIL or corruption preimages.
}

/** Ledger every transport entry before ACK/failure; no filtering of P or isolation logic in this fake. */
internal class T11Backend(private val db: AppDatabase, private val ledger: File) : CatalogRemoteDataSource by SupabaseCatalogRemoteDataSource(null) {
    override val isConfigured = true
    var failBusiness = false
    var failNotifications = false
    fun records(): List<JsonObject> = if (!ledger.isFile) emptyList() else
        T11Contract.json.parseToJsonElement(ledger.readText()).jsonArray.map { it.jsonObject }
    val reader: ShopSyncReadRemoteDataSource = T11CommittedReadRemote {
        check(!ledger.exists() || (ledger.isFile && ledger.canonicalFile == ledger.absoluteFile))
        records()
    }
    private fun append(value: JsonObject) = nativeDurableWrite(ledger, JsonArray(records() + value).toString().encodeToByteArray())
    private suspend fun recordBusiness(kind: String, ids: List<String>, wireRows: JsonElement,
        shopId: String? = null, explicitShopArgument: Boolean = false): Result<Unit> {
        append(buildJsonObject {
            put("kind", "BUSINESS_ENTRY"); put("domain", kind); put("wireRows", wireRows)
            put("explicitShopArgument", explicitShopArgument); put("observedShopArgument", shopId)
            put("ids", JsonArray(ids.map(::JsonPrimitive))); put("atMs", System.currentTimeMillis())
        }) // Record every mutating entry before even the fixture's own validation.
        if (explicitShopArgument) check(shopId == T11Contract.SHOP) { "fixture_shop_mismatch" }
        val headers = db.syncEventOutboxDao().listBusinessWriteHeaders(T11Contract.OWNER, T11Contract.scope.storeId, setOf(kind), 10)
        val entry = requireNotNull(db.syncEventOutboxDao().getById(headers.single().id))
        val attempt = T11Contract.json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
        validateBusinessWritePayload(attempt)
        check(entry.eventType == BUSINESS_WRITE_EVENT && entry.attemptCount > 0)
        check(entry.clientEventId == "business-write-v1:" + nativeSha(entry.metadataJson.encodeToByteArray()))
        check(attempt.owner == T11Contract.OWNER && attempt.shop == T11Contract.SHOP && attempt.store == T11Contract.scope.storeId)
        check(attempt.device == T11Contract.DEVICE && attempt.generation == db.syncRecoveryBaselineDao().get()?.generationId)
        check(attempt.payload.kind == kind && attempt.payload.revisions.map { it.remoteId } == ids)
        val sealedRows = when (kind) {
            "SUPPLIERS" -> T11Contract.json.encodeToJsonElement(attempt.payload.suppliers)
            "CATEGORIES" -> T11Contract.json.encodeToJsonElement(attempt.payload.categories)
            "PRODUCTS" -> T11Contract.json.encodeToJsonElement(attempt.payload.products)
            else -> error("Unexpected native T11 domain")
        }
        check(sealedRows == wireRows) { "Actual transport rows must equal the exact sealed payload" }
        append(buildJsonObject {
            put("kind", "BUSINESS_ATTEMPT"); put("domain", kind); put("operationKey", entry.clientEventId)
            put("rawAttempt", entry.metadataJson); put("wireRows", wireRows); put("attemptCount", entry.attemptCount)
            put("ids", JsonArray(ids.map(::JsonPrimitive))); put("atMs", System.currentTimeMillis())
        })
        if (failBusiness) return Result.failure(IOException("synthetic transport failure before local ACK"))
        append(buildJsonObject { put("kind", "BUSINESS_ACK"); put("operationKey", entry.clientEventId); put("ids", JsonArray(ids.map(::JsonPrimitive))) })
        return Result.success(Unit)
    }
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>) = recordBusiness("SUPPLIERS", rows.map { it.id }, T11Contract.json.encodeToJsonElement(rows))
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> {
        return recordBusiness("SUPPLIERS", rows.map { it.id }, T11Contract.json.encodeToJsonElement(rows), shopId, explicitShopArgument = true)
    }
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>) = recordBusiness("CATEGORIES", rows.map { it.id }, T11Contract.json.encodeToJsonElement(rows))
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>, shopId: String?): Result<Unit> {
        return recordBusiness("CATEGORIES", rows.map { it.id }, T11Contract.json.encodeToJsonElement(rows), shopId, explicitShopArgument = true)
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>) = recordBusiness("PRODUCTS", rows.map { it.id }, T11Contract.json.encodeToJsonElement(rows))
    override suspend fun upsertProducts(rows: List<InventoryProductRow>, shopId: String?): Result<Unit> {
        return recordBusiness("PRODUCTS", rows.map { it.id }, T11Contract.json.encodeToJsonElement(rows), shopId, explicitShopArgument = true)
    }
    val events = object : SyncEventRemoteDataSource {
        override val isConfigured = true
        override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> {
            check(ownerUserId == T11Contract.OWNER)
            return Result.success(SyncEventRemoteCapabilities(true, true, true))
        }
        override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
            val raw = T11Contract.json.encodeToString(params)
            append(buildJsonObject {
                put("kind", "EVENT_ATTEMPT"); put("clientEventId", params.clientEventId)
                put("rawParams", raw); put("atMs", System.currentTimeMillis())
            })
            check(params.shopId == T11Contract.SHOP && params.sourceDeviceId == T11Contract.DEVICE)
            if (failNotifications) return Result.failure(IOException("synthetic notification unavailable"))
            val existing = records().firstOrNull { it["kind"]?.jsonPrimitive?.content == "EVENT_ACK" && it["clientEventId"] == JsonPrimitive(params.clientEventId) }
            if (existing != null) {
                check(existing.getValue("rawParams").jsonPrimitive.content == raw)
                return Result.success(T11Contract.json.decodeFromString(existing.getValue("rawRow").jsonPrimitive.content))
            }
            val row = SyncEventRemoteRow(id = records().count { it["kind"]?.jsonPrimitive?.content == "EVENT_ACK" }.toLong() + 1,
                ownerUserId = T11Contract.OWNER, shopId = params.shopId, storeId = params.storeId,
                domain = params.domain, eventType = params.eventType, source = params.source,
                sourceDeviceId = params.sourceDeviceId, batchId = params.batchId, clientEventId = params.clientEventId,
                changedCount = params.changedCount, entityIds = params.entityIds, createdAt = "2026-10-16T12:00:00Z")
            append(buildJsonObject {
                put("kind", "EVENT_ACK"); put("clientEventId", params.clientEventId); put("rawParams", raw)
                put("rawRow", T11Contract.json.encodeToString(row))
            })
            return Result.success(row)
        }
        override suspend fun fetchSyncEventsAfter(ownerUserId: String, storeId: String?, afterId: Long, limit: Long): Result<List<SyncEventRemoteRow>> =
            error("Qualified V6 reader owns incremental reads; legacy event fetch must not be called")
    }
}

/** T11-only read model of durable transport ACKs. No Room access and no read-time clock. */
private class T11CommittedReadRemote(private val readLedger: () -> List<JsonObject>) : ShopSyncReadRemoteDataSource {
    override val isConfigured = true
    private val scope = NativeAckEmptyRecoveryRemote().checkpoint.scope
    private val limits = ShopSyncRecoveryResourceLimits()
    private data class Accepted(val key: String, val attempt: BusinessWriteAttempt, val atMs: Long)
    private data class Snapshot(
        val rows: Map<ShopSyncRowDomain, ShopSyncRows>, val events: List<SyncEventRemoteRow>,
        val generation: String, val businessAcks: Int
    ) {
        val max: Long get() = events.maxOfOrNull { it.id } ?: 0L
        val fences: Map<String, String> get() = listOf(SyncEventDomains.CATALOG, SyncEventDomains.PRICES,
            SyncEventDomains.HISTORY).associateWith { domain -> (events.filter { it.domain == domain }.maxOfOrNull { it.id } ?: 0L).toString() }
    }
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
        check(it.isString); it.content
    }
    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
        check(!it.isString); it.long.also { value -> check(value > 0L) }
    }
    private fun JsonObject.ids(): List<String> = getValue("ids").jsonArray.map {
        check(it.jsonPrimitive.isString); entityId(it.jsonPrimitive.content)
    }.also { check(it.isNotEmpty() && it.distinct().size == it.size) }
    private fun exactKeys(row: JsonObject, keys: String) { check(row.keys == keys.split(' ').toSet()) }
    private fun entityId(id: String): String = requireNotNull(canonicalShopSyncRecoveryEntityIdOrNull(id)).also { check(it == id) }
    private fun eventId(raw: String): Long {
        check(Regex("0|[1-9][0-9]*").matches(raw)); return requireNotNull(raw.toLongOrNull())
    }
    private fun timestamp(atMs: Long): String = java.time.format.DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(atMs))
    private fun encoded(rows: ShopSyncRows): JsonArray = when (rows) {
        is ShopSyncRows.Suppliers -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Categories -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Products -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Prices -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.History -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Images -> T11Contract.json.encodeToJsonElement(rows.values)
    }.jsonArray
    private fun payloadRows(attempt: BusinessWriteAttempt): ShopSyncRows = when (attempt.payload.kind) {
        "SUPPLIERS" -> ShopSyncRows.Suppliers(attempt.payload.suppliers)
        "CATEGORIES" -> ShopSyncRows.Categories(attempt.payload.categories)
        "PRODUCTS" -> ShopSyncRows.Products(attempt.payload.products)
        else -> error("t11_reader_unsupported_business_domain")
    }
    private fun entryRows(kind: String, wire: JsonElement): ShopSyncRows = when (kind) {
        "SUPPLIERS" -> ShopSyncRows.Suppliers(T11Contract.json.decodeFromJsonElement<List<InventorySupplierRow>>(wire).also { rows ->
            check(rows.all { it.ownerUserId == T11Contract.OWNER && it.shopId == T11Contract.SHOP })
        })
        "CATEGORIES" -> ShopSyncRows.Categories(T11Contract.json.decodeFromJsonElement<List<InventoryCategoryRow>>(wire).also { rows ->
            check(rows.all { it.ownerUserId == T11Contract.OWNER && it.shopId == T11Contract.SHOP })
        })
        "PRODUCTS" -> ShopSyncRows.Products(T11Contract.json.decodeFromJsonElement<List<InventoryProductRow>>(wire).also { rows ->
            check(rows.all { it.ownerUserId == T11Contract.OWNER && it.shopId == T11Contract.SHOP })
        })
        else -> error("t11_reader_unsupported_entry_domain")
    }
    private fun validateParams(params: SyncEventRecordRpcParams) {
        check(params.shopId == T11Contract.SHOP && params.storeId == T11Contract.SHOP && params.sourceDeviceId == T11Contract.DEVICE)
        check(!params.clientEventId.isNullOrBlank() && params.domain == SyncEventDomains.CATALOG)
        check(SyncEventContract.hasSupportedEventType(params.domain, params.eventType))
        val ids = requireNotNull(params.entityIds)
        check(SyncEventContract.hasCompletePrimaryIds(params.domain, params.changedCount, ids))
    }
    private fun snapshot(): Snapshot {
        // Exactly one immutable ledger read per operation. Failed/unacknowledged attempts remain unpublished.
        val records = readLedger()
        val suppliers = linkedMapOf<String, InventorySupplierRow>()
        val categories = linkedMapOf<String, InventoryCategoryRow>()
        val products = linkedMapOf<String, InventoryProductRow>()
        val events = mutableListOf<SyncEventRemoteRow>()
        var entry: JsonObject? = null
        var accepted: Accepted? = null
        var eventAttempt: Pair<String, String>? = null
        var generation: String? = null
        var businessAcks = 0
        records.forEach { record -> when (record.text("kind")) {
            "BUSINESS_ENTRY" -> {
                exactKeys(record, "kind domain wireRows explicitShopArgument observedShopArgument ids atMs")
                check(record.text("domain") in setOf("SUPPLIERS", "CATEGORIES", "PRODUCTS"))
                check(record.getValue("wireRows") is JsonArray)
                check(entryRows(record.text("domain"), record.getValue("wireRows")).ids() == record.ids()); record.number("atMs")
                val explicit = record.getValue("explicitShopArgument").jsonPrimitive.also { check(!it.isString) }.boolean
                check(if (explicit) record.text("observedShopArgument") == T11Contract.SHOP else record.getValue("observedShopArgument") == JsonNull)
                entry = record; accepted = null
            }
            "BUSINESS_ATTEMPT" -> {
                exactKeys(record, "kind domain operationKey rawAttempt wireRows attemptCount ids atMs")
                val before = requireNotNull(entry); check(accepted == null)
                val raw = record.text("rawAttempt")
                val attempt = T11Contract.json.decodeFromString<BusinessWriteAttempt>(raw)
                validateBusinessWritePayload(attempt)
                check(attempt.version == 1 && attempt.schema == Task126SyncPolicy.LOCAL_SCHEMA_VERSION &&
                    attempt.protocol == Task126SyncPolicy.SYNC_PROTOCOL_VERSION && attempt.epoch == Task126SyncPolicy.DEFAULT_STORE_EPOCH)
                check(attempt.owner == T11Contract.OWNER && attempt.shop == T11Contract.SHOP &&
                    attempt.store == T11Contract.scope.storeId && attempt.localStore == "local-" + T11Contract.scope.storeId && attempt.device == T11Contract.DEVICE)
                check(!attempt.generation.isNullOrBlank())
                val body = payloadRows(attempt); body.ids().forEach { entityId(it) }
                check(record.text("domain") == before.text("domain") && record.text("domain") == attempt.payload.kind)
                check(encoded(body) == record.getValue("wireRows") && record.getValue("wireRows") == before.getValue("wireRows"))
                check(record.ids() == body.ids() && record.ids() == before.ids() && attempt.payload.revisions.map { it.remoteId } == body.ids())
                val key = record.text("operationKey")
                check(key == "business-write-v1:" + nativeSha(raw.encodeToByteArray()))
                record.number("attemptCount")
                accepted = Accepted(key, attempt, record.number("atMs"))
            }
            "BUSINESS_ACK" -> {
                exactKeys(record, "kind operationKey ids")
                val committed = requireNotNull(accepted)
                val attempt = committed.attempt
                check(record.text("operationKey") == committed.key && record.ids() == payloadRows(attempt).ids())
                check(generation == null || generation == attempt.generation); generation = attempt.generation
                val stamp = timestamp(committed.atMs)
                attempt.payload.suppliers.forEach { suppliers[it.id] = it.copy(updatedAt = it.updatedAt ?: stamp) }
                attempt.payload.categories.forEach { categories[it.id] = it.copy(updatedAt = it.updatedAt ?: stamp) }
                attempt.payload.products.forEach { products[it.id] = it.copy(updatedAt = it.updatedAt ?: stamp) }
                businessAcks += 1; entry = null; accepted = null
            }
            "EVENT_ATTEMPT" -> {
                exactKeys(record, "kind clientEventId rawParams atMs")
                val raw = record.text("rawParams")
                val params = T11Contract.json.decodeFromString<SyncEventRecordRpcParams>(raw)
                validateParams(params); check(record.text("clientEventId") == params.clientEventId); record.number("atMs")
                eventAttempt = record.text("clientEventId") to raw
            }
            "EVENT_ACK" -> {
                exactKeys(record, "kind clientEventId rawParams rawRow")
                val pending = requireNotNull(eventAttempt)
                check(record.text("clientEventId") == pending.first && record.text("rawParams") == pending.second)
                val params = T11Contract.json.decodeFromString<SyncEventRecordRpcParams>(pending.second)
                val raw = record.text("rawRow")
                val event = T11Contract.json.decodeFromString<SyncEventRemoteRow>(raw)
                check(T11Contract.json.encodeToString(event) == raw)
                check(event.id > (events.lastOrNull()?.id ?: 0L) && events.none { it.clientEventId == event.clientEventId })
                check(event.ownerUserId == T11Contract.OWNER && event.shopId == T11Contract.SHOP &&
                    event.storeId == params.storeId && event.sourceDeviceId == T11Contract.DEVICE)
                check(event.clientEventId == params.clientEventId && event.domain == params.domain && event.eventType == params.eventType &&
                    event.source == params.source && event.batchId == params.batchId && event.changedCount == params.changedCount && event.entityIds == params.entityIds)
                check(!event.requiresFullRecovery && event.timestampValid)
                val ids = requireNotNull(event.entityIds)
                check(ids.supplierIds.all { it in suppliers } && ids.categoryIds.all { it in categories } && ids.productIds.all { it in products })
                events += event; eventAttempt = null
            }
            else -> error("t11_reader_unknown_ledger_record")
        } }
        return Snapshot(mapOf(
            ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(suppliers.values.sortedBy { it.id }),
            ShopSyncRowDomain.CATEGORIES to ShopSyncRows.Categories(categories.values.sortedBy { it.id }),
            ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(products.values.sortedBy { it.id }),
            ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(emptyList()),
            ShopSyncRowDomain.HISTORY to ShopSyncRows.History(emptyList()),
            ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(emptyList())), events.toList(), generation ?: "t11-empty-ledger", businessAcks).also { state ->
            state.rows.forEach { (domain, rows) -> rows.toManifestRows(state.generation, domain) }
        }
    }
    private fun validate(context: ShopSyncRpcContext, state: Snapshot, allowInitialScope: Boolean = false) {
        check(context.accountId == T11Contract.OWNER && context.shopId == T11Contract.SHOP && context.deviceIdentifier == T11Contract.DEVICE)
        check(context.expectedScope == scope || (allowInitialScope && context.expectedScope == null && state.businessAcks == 0 && state.events.isEmpty()))
        val baseline = eventId(context.verifiedBaselineId); check(baseline <= state.max)
        check(context.expectedBaselineScopeKey == null || context.expectedBaselineScopeKey == scope.key)
        check(baseline == 0L || context.expectedBaselineScopeKey == scope.key)
        context.expectedEventMaxId?.let { check(eventId(it) == state.max) }
    }
    private fun checkpoint(state: Snapshot, context: ShopSyncRpcContext): ShopSyncRecoveryCheckpoint {
        val domains = state.rows.mapValues { (domain, rows) ->
            val manifest = rows.toManifestRows(state.generation, domain).sortedBy { it.remoteId }
            ShopSyncDomainCheckpoint(manifest.count { it.active }.toLong(), manifest.count { !it.active }.toLong(),
                shopSyncCheckpointChainDigest(manifest.map { it.idLine }), shopSyncCheckpointChainDigest(manifest.map { it.versionLine }),
                shopSyncCheckpointChainDigest(manifest.mapNotNull { it.identityLine }).takeIf { domain == ShopSyncRowDomain.PRODUCTS })
        }
        val catalog = ShopSyncCatalogCheckpoint(domains.getValue(ShopSyncRowDomain.SUPPLIERS), domains.getValue(ShopSyncRowDomain.CATEGORIES),
            domains.getValue(ShopSyncRowDomain.PRODUCTS), nativeSha(listOf(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES,
                ShopSyncRowDomain.PRODUCTS).joinToString("\n") { domains.getValue(it).versionDigest }.encodeToByteArray()))
        val suppliers = (state.rows.getValue(ShopSyncRowDomain.SUPPLIERS) as ShopSyncRows.Suppliers).values.filter { it.deletedAt == null }.map { it.id }.toSet()
        val categories = (state.rows.getValue(ShopSyncRowDomain.CATEGORIES) as ShopSyncRows.Categories).values.filter { it.deletedAt == null }.map { it.id }.toSet()
        val live = (state.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.filter { it.deletedAt == null }
        val badCategory = live.count { it.categoryId != null && it.categoryId !in categories }.toLong()
        val badSupplier = live.count { it.supplierId != null && it.supplierId !in suppliers }.toLong()
        val badImage = live.count { it.primaryImageVersionId != null || it.primaryImageUpdatedAt != null }.toLong()
        val integrity = ShopSyncIntegrityCheckpoint(badCategory, badSupplier, 0, badImage, 0, badCategory + badSupplier + badImage)
        // Opaque fixture digest of the actual materials/fences, not an imitation of SQL JSONB hashing.
        val digest = nativeSha(buildJsonObject {
            put("scope", T11Contract.json.encodeToJsonElement(scope)); put("fences", T11Contract.json.encodeToJsonElement(state.fences))
            put("max", state.max.toString()); put("integrity", T11Contract.json.encodeToJsonElement(integrity))
            put("materials", buildJsonObject { state.rows.forEach { (domain, rows) -> put(domain.wireValue, encoded(rows)) } })
        }.toString().encodeToByteArray())
        return ShopSyncRecoveryCheckpoint("shop-sync-recovery-checkpoint-v1", "ready", T11Contract.SHOP, scope,
            ShopSyncEventCheckpoint(state.max.toString(), context.verifiedBaselineId, false, state.fences), catalog,
            domains.getValue(ShopSyncRowDomain.PRICES), domains.getValue(ShopSyncRowDomain.HISTORY), domains.getValue(ShopSyncRowDomain.IMAGES), integrity, digest)
    }
    private inline fun <T> read(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> = read {
        val state = snapshot(); validate(context, state, allowInitialScope = true)
        check(context.expectedDomainEventMaxId == null); checkpoint(state, context)
    }
    override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> = read {
        val state = snapshot(); validate(context, state); check(context.expectedDomainEventMaxId == null)
        val checkpoint = checkpoint(state, context)
        ShopSyncConvergenceMarker("shop-sync-convergence-marker-v1", "ready", checkpoint.shopId, scope, checkpoint.syncEvents,
            checkpoint.catalog, checkpoint.prices, checkpoint.history, checkpoint.images, ShopSyncMarkerIntegrity(checkpoint.integrity.totalViolationCount),
            checkpoint.checkpointDigest, state.businessAcks == 0 && state.events.isEmpty(),
            nativeSha(("t11-marker-v1|" + checkpoint.checkpointDigest + "|" + context.verifiedBaselineId).encodeToByteArray()))
    }
    private fun pageRows(rows: ShopSyncRows, after: String?, limit: Int): ShopSyncRows = when (rows) {
        is ShopSyncRows.Suppliers -> ShopSyncRows.Suppliers(rows.values.filter { after == null || it.id > after }.take(limit))
        is ShopSyncRows.Categories -> ShopSyncRows.Categories(rows.values.filter { after == null || it.id > after }.take(limit))
        is ShopSyncRows.Products -> ShopSyncRows.Products(rows.values.filter { after == null || it.id > after }.take(limit))
        is ShopSyncRows.Prices -> ShopSyncRows.Prices(rows.values.filter { after == null || it.id > after }.take(limit))
        is ShopSyncRows.History -> ShopSyncRows.History(rows.values.filter { after == null || it.remoteId > after }.take(limit))
        is ShopSyncRows.Images -> ShopSyncRows.Images(rows.values.filter { after == null || it.productId > after }.take(limit))
    }
    override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> = read {
        val state = snapshot(); validate(context, state)
        check(limit in 1..250 && context.expectedEventMaxId != null)
        val fence = state.fences.getValue(domain.syncEventDomain())
        check(context.expectedDomainEventMaxId == fence)
        val after = afterId?.let { requireNotNull(canonicalShopSyncRecoveryEntityIdOrNull(it)) }
        val effective = minOf(limit, limits.pageRows(domain)); val all = state.rows.getValue(domain)
        val rows = pageRows(all, after, effective)
        val hasMore = all.ids().count { after == null || it > after } > rows.size
        val next = rows.ids().lastOrNull().takeIf { hasMore }
        val envelope = buildJsonObject {
            put("schemaVersion", "shop-sync-recovery-page-v1"); put("shopId", T11Contract.SHOP); put("scope", T11Contract.json.encodeToJsonElement(scope))
            put("domain", domain.wireValue); put("snapshotEventMaxId", state.max.toString()); put("currentScopeEventMaxId", state.max.toString())
            put("baselineDomainEventMaxId", fence); put("pageDomainEventMaxId", fence); put("domainScope", ShopSyncScopeKinds.SHOP_SCOPED)
            put("pageLimit", effective); put("rows", encoded(rows)); put("nextAfterId", next); put("hasMore", hasMore)
        }
        val bytes = envelope.toString().encodeToByteArray().size.toLong()
        check(bytes <= limits.pageResponseBytes(domain))
        ShopSyncRecoveryPage("shop-sync-recovery-page-v1", T11Contract.SHOP, scope, domain, state.max.toString(), state.max.toString(),
            fence, fence, ShopSyncScopeKinds.SHOP_SCOPED, effective, rows, next, hasMore, bytes,
            encoded(rows).maxOfOrNull { it.toString().encodeToByteArray().size.toLong() } ?: 0L)
    }
    override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> = read {
        val state = snapshot(); validate(context, state)
        check(afterId >= 0L && afterId <= state.max && limit in 1..150 && context.expectedDomainEventMaxId == null)
        check(context.expectedEventMaxId != null || afterId == 0L)
        val remaining = state.events.filter { it.id > afterId && it.id <= state.max }.sortedBy { it.id }
        val rows = remaining.take(limit); val more = remaining.size > rows.size
        val next = rows.lastOrNull()?.id.takeIf { more }
        val envelope = buildJsonObject {
            put("schemaVersion", "shop-sync-event-page-v1"); put("shopId", T11Contract.SHOP); put("scope", T11Contract.json.encodeToJsonElement(scope))
            put("scopeEventMaxId", state.max.toString()); put("asOfEventMaxId", state.max.toString())
            put("asOfDomainEventMaxIds", T11Contract.json.encodeToJsonElement(state.fences)); put("pageLimit", limit)
            put("rows", T11Contract.json.encodeToJsonElement(rows)); put("nextAfterId", next); put("hasMore", more)
        }
        val bytes = envelope.toString().encodeToByteArray().size.toLong(); check(bytes <= limits.defaultPageResponseBytes)
        ShopSyncEventPage("shop-sync-event-page-v1", T11Contract.SHOP, scope, state.max.toString(), state.max.toString(), state.fences,
            limit, rows, next, more, bytes)
    }
    override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, ids: List<String>): Result<ShopSyncTargetedRows> =
        Result.failure(AssertionError("t11_reader_targeted_rows_not_supported"))
}

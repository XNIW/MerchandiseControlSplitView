package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*

internal object ScopeSafetyContract {
    const val PROTOCOL = "task144-scope-safety-candidate01"
    const val DB = "scope-safety.db"
    const val MEMBERSHIP_RPC = "mobile_linked_shops"
    const val DEVICE_RPC = "shop_device_status_for_shop"
    val json = Json { encodeDefaults = true }
}

/** Own files and real namespaced SharedPreferences. Nothing deletes even a failed fixture. */
internal class ScopeSafetyContext(base: Context, val runId: String, val scenario: String, fresh: Boolean) : ContextWrapper(base) {
    val root: File
    val preferencePrefix = "task144_scope_${runId}_${scenario}_"
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        require(scenario in setOf("membership", "device", "corrupt"))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-scope-$runId-$scenario")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val ownership = "${ScopeSafetyContract.PROTOCOL}\n$runId\n$scenario\n"
        val sentinel = File(root, "ownership.txt")
        databases = File(root, "databases")
        if (fresh) {
            check(!root.exists()) { "Preserve previous fixture; use a fresh run ID" }
            check(root.mkdir())
            nativeDurableWrite(sentinel, ownership.encodeToByteArray())
            check(databases.mkdir())
            // No normal app preferences can be read or written through this context.
            check(base.getSharedPreferences(preferencePrefix + "mobile_shop_context", Context.MODE_PRIVATE).all.isEmpty())
            check(base.getSharedPreferences(preferencePrefix + "settings", Context.MODE_PRIVATE).all.isEmpty())
        } else {
            check(sentinel.isFile && sentinel.readText() == ownership)
            check(File(databases, ScopeSafetyContract.DB).isFile)
        }
    }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File {
        if (name == ".") return databases
        require(name.isNotBlank() && name != ".." && !name.contains('/') && !name.contains('\\'))
        return File(databases, name).also { require(it.canonicalFile == it.absoluteFile) }
    }
    override fun databaseList(): Array<String> = (databases.list()?.toList() ?: emptyList()).toTypedArray()
    override fun deleteDatabase(name: String): Boolean = error("Fixture databases/evidence must be preserved: $name")
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(Regex("[A-Za-z0-9_]+").matches(name))
        return baseContext.getSharedPreferences(preferencePrefix + name, mode)
    }
    override fun getFilesDir(): File = child("files")
    override fun getCacheDir(): File = child("cache")
    override fun getNoBackupFilesDir(): File = child("no_backup")
    private fun child(name: String) = File(root, name).also {
        require(it.canonicalFile == it.absoluteFile)
        check(it.isDirectory || it.mkdir())
    }
}

internal fun scopeSafetyContext(scenario: String, fresh: Boolean): ScopeSafetyContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class")?.startsWith(Task144ScopeSafetyDeviceTest::class.java.name + "#") == true)
    check(args.getString("scopeSafetyProtocol") == ScopeSafetyContract.PROTOCOL)
    return ScopeSafetyContext(instrumentation.targetContext, requireNotNull(args.getString("scopeSafetyRunId")), scenario, fresh)
}

internal class ScopeSafetyApplication : MerchandiseControlApplication() {
    fun attachOwned(context: Context) = attachBaseContext(context)
}

@Suppress("UNCHECKED_CAST")
internal fun <T> scopeSafetyField(target: Any, name: String): T {
    val owner = if (target is MerchandiseControlApplication) MerchandiseControlApplication::class.java else target.javaClass
    return owner.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}

/** Candidate-local observer: one durable raw entry per overload before any delegate guard/commit. */
internal class ScopeSafetyObservedCatalog(
    private val delegate: CatalogRemoteDataSource,
    private val record: (String, JsonObject) -> Unit
) : CatalogRemoteDataSource by delegate {
    private fun entry(method: String, shopId: String?, ids: List<String>) = record("business_entry", buildJsonObject {
        put("method", method); put("shopId", shopId?.let(::JsonPrimitive) ?: JsonNull)
        put("ids", JsonArray(ids.map(::JsonPrimitive))); put("rowCount", ids.size)
    })
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> {
        entry("upsertSuppliers", null, rows.map { it.id })
        return delegate.upsertSuppliers(rows)
    }
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> {
        entry("upsertSuppliers_shop", shopId, rows.map { it.id })
        return delegate.upsertSuppliers(rows, shopId)
    }
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> {
        entry("upsertCategories", null, rows.map { it.id })
        return delegate.upsertCategories(rows)
    }
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>, shopId: String?): Result<Unit> {
        entry("upsertCategories_shop", shopId, rows.map { it.id })
        return delegate.upsertCategories(rows, shopId)
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        entry("upsertProducts", null, rows.map { it.id })
        return delegate.upsertProducts(rows)
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>, shopId: String?): Result<Unit> {
        entry("upsertProducts_shop", shopId, rows.map { it.id })
        return delegate.upsertProducts(rows, shopId)
    }
    override suspend fun patchProduct(id: String, ownerUserId: String, patch: InventoryProductPatch): Result<Unit> {
        entry("patchProduct", null, listOf(id))
        return delegate.patchProduct(id, ownerUserId, patch)
    }
    override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch): Result<Unit> {
        entry("patchProduct_shop", shopId, listOf(id))
        return delegate.patchProduct(id, ownerUserId, shopId, patch)
    }
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> {
        entry("markSupplierTombstoned", patch.shopId, listOf(patch.id))
        return delegate.markSupplierTombstoned(patch)
    }
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> {
        entry("markSupplierTombstoned_shop", shopId, listOf(patch.id))
        return delegate.markSupplierTombstoned(patch, shopId)
    }
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> {
        entry("markCategoryTombstoned", patch.shopId, listOf(patch.id))
        return delegate.markCategoryTombstoned(patch)
    }
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> {
        entry("markCategoryTombstoned_shop", shopId, listOf(patch.id))
        return delegate.markCategoryTombstoned(patch, shopId)
    }
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> {
        entry("markProductTombstoned", patch.shopId, listOf(patch.id))
        return delegate.markProductTombstoned(patch)
    }
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> {
        entry("markProductTombstoned_shop", shopId, listOf(patch.id))
        return delegate.markProductTombstoned(patch, shopId)
    }
}

/** Test adapter changes responses only. Actual SDK decode, ShopContext and Application callbacks execute. */
internal class ScopeSafetyFixture(val context: ScopeSafetyContext) {
    val db = Room.databaseBuilder(context, AppDatabase::class.java, ScopeSafetyContract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build().also { check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(ScopeSafetyContract.DB)) }
    val backend = NativeAckPersistentCatalog(db, File(context.root, "synthetic-backend.json"))
    val business = ScopeSafetyObservedCatalog(backend, ::record)
    val reader = NativeAckEmptyRecoveryRemote()
    val preferences = context.getSharedPreferences("mobile_shop_context", Context.MODE_PRIVATE)
    val selected = SharedPreferencesSelectedShopStore(preferences)
    @Volatile var membershipResponse = "active"
    @Volatile var deviceResponse = "active"
    private var sdk: SupabaseClient? = null
    lateinit var app: ScopeSafetyApplication
    lateinit var tracker: CatalogSyncStateTracker
    lateinit var repository: DefaultInventoryRepository
    lateinit var shops: ShopContextRepository
    lateinit var auth: SupabaseAuthManager
    lateinit var vm: CatalogSyncViewModel
    private val viewModels = ViewModelStore()
    private var closed = false
    private val closedPhases = mutableSetOf<String>()
    private var observationStarted = false
    private val prices = SupabaseProductPriceRemoteDataSource(null)

    @Synchronized fun record(kind: String, detail: JsonObject = buildJsonObject { }) {
        val row = buildJsonObject {
            put("kind", kind); put("pid", Process.myPid()); put("wallMs", System.currentTimeMillis())
            put("detail", detail)
        }
        FileOutputStream(File(context.root, "ledger.jsonl"), true).use {
            it.write((row.toString() + "\n").encodeToByteArray()); it.fd.sync()
        }
    }
    fun rpcRecords(): List<JsonObject> = File(context.root, "ledger.jsonl").takeIf { it.isFile }?.readLines()
        ?.map { ScopeSafetyContract.json.parseToJsonElement(it).jsonObject }
        ?.filter { it["kind"]?.jsonPrimitive?.content == "rpc_entry" }.orEmpty()

    @Synchronized fun businessRecords(): List<JsonObject> = File(context.root, "ledger.jsonl").takeIf { it.isFile }?.readLines()
        ?.map { ScopeSafetyContract.json.parseToJsonElement(it).jsonObject }
        ?.filter { it["kind"]?.jsonPrimitive?.content == "business_entry" }.orEmpty()

    suspend fun seed() {
        check(db.productDao().count() == 0 && db.syncRecoveryBaselineDao().get() == null)
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = NativeAckContract.DEVICE, createdAtMs = 1))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
            ownerHash = NativeAckContract.scope.ownerHash, storeScope = NativeAckContract.scope.storeId,
            shopId = NativeAckContract.SHOP, deviceId = NativeAckContract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = 1, updatedAtMs = 1, nextRetryAtMs = 1))
        val unguarded = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val recovery = ShopSyncRecoveryCoordinator(context, db, unguarded, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
            scopeStillValid = { owner, shop -> owner == NativeAckContract.OWNER && shop == NativeAckContract.SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
        assertTrue(recovery.recover(NativeAckContract.OWNER, NativeAckContract.shop, NativeAckContract.scope) is ShopSyncRecoveryResult.Activated)
        val state = unguarded.resolveBusinessDataScope(NativeAckContract.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        val seedTracker = CatalogSyncStateTracker(state)
        val seedRepository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = seedTracker)
        seedRepository.addProduct(Product(barcode = NativeAckContract.BARCODE, productName = "Original", itemNumber = "Original item"))
        seedTracker.withBusinessDataScopeFlight(NativeAckContract.OWNER, NativeAckContract.shop) {
            seedRepository.pushDirtyCatalogDeltaToRemote(business, prices, NativeAckContract.OWNER,
                CatalogSyncProgressReporter { }, NativeAckContract.shop).getOrThrow()
        }
        val product = product()
        val ref = requireNotNull(db.productRemoteRefDao().getByProductId(product.id))
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        assertEquals(0, db.syncEventOutboxDao().countAll())
        val offline = unguarded.resolveOfflineBusinessDataScope(NativeAckContract.scope, canWrite = true)
        assertTrue("Nonempty seed must really qualify", offline.allowsLocalOperations)
        record("qualified_nonempty_seed", buildJsonObject { put("generation", requireNotNull(db.syncRecoveryBaselineDao().get()).generationId); put("productId", product.id) })
    }

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    suspend fun openRuntime(restoreOnly: Boolean) {
        check(!::app.isInitialized)
        val client = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            httpEngine = OkHttp.create { config { addInterceptor { chain ->
                val request = chain.request()
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                val body = request.body?.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }.orEmpty()
                // Capture entry before checking method/scope; never log headers or session tokens.
                record("rpc_entry", buildJsonObject { put("endpoint", endpoint); put("requestSha256", nativeSha(body.encodeToByteArray())); put("method", request.method) })
                check(request.method == "POST" && endpoint in setOf(ScopeSafetyContract.MEMBERSHIP_RPC, ScopeSafetyContract.DEVICE_RPC))
                val mode = if (endpoint == ScopeSafetyContract.MEMBERSHIP_RPC) membershipResponse else deviceResponse
                if (endpoint == ScopeSafetyContract.DEVICE_RPC) {
                    val params = ScopeSafetyContract.json.parseToJsonElement(body).jsonObject
                    check(params["p_shop_id"]?.jsonPrimitive?.content == NativeAckContract.SHOP)
                    check(params["p_device_identifier"]?.jsonPrimitive?.content == NativeAckContract.DEVICE)
                }
                if (mode == "offline") throw IOException("task144_synthetic_authority_offline")
                val payload = if (endpoint == ScopeSafetyContract.MEMBERSHIP_RPC) {
                    if (mode == "rejected") """{"ok":false,"code":"membership_revoked","shops":[]}"""
                    else {
                        check(mode == "active")
                        """{"ok":true,"code":"success","shops":[{"shop_id":"${NativeAckContract.SHOP}","shop_code":"NATIVE144","shop_name":"Synthetic owned fixture","role_key":"shop_owner","membership_status":"active","shop_status":"active","can_select":true,"can_write":true}]}"""
                    }
                } else {
                    check(mode in setOf("active", "revoked"))
                    """{"ok":true,"code":"$mode","status":"$mode","can_write":${mode == "active"},"reason_code":"$mode","recommended_action":"contact_shop_admin"}"""
                }
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("Synthetic").body(payload.toResponseBody("application/json".toMediaType())).build()
                // No chain.proceed: no real network endpoint is reachable.
            } } }
            coroutineDispatcher = Dispatchers.IO; defaultLogLevel = LogLevel.NONE; requestTimeout = 3.seconds
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false; sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
            install(Postgrest) { timeout = 3.seconds }
        }
        sdk = client
        client.auth.importSession(UserSession(accessToken = "synthetic-access", refreshToken = "synthetic-refresh", expiresIn = 86_400,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = NativeAckContract.OWNER), expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
        app = ScopeSafetyApplication().apply { attachOwned(context) } // Deliberately no Application.onCreate or real login.
        val scope: CoroutineScope = scopeSafetyField(app, "appScope")
        auth = SupabaseAuthManager(client = null, googleWebClientId = "", scope = scope)
        scopeSafetyField<MutableStateFlow<AuthState>>(auth, "_state").value = AuthState.SignedIn(NativeAckContract.OWNER, "synthetic@example.test")
        val provider = DeviceInstallIdProvider(db.syncEventDeviceStateDao())
        assertEquals(NativeAckContract.DEVICE, provider.getOrCreate())
        shops = ShopContextRepository(SupabaseLinkedShopRemoteDataSource(client), selected,
            currentOwnerUserId = { (auth.state.value as? AuthState.SignedIn)?.userId }, currentDeviceIdentifier = { provider.currentId })
        if (restoreOnly) shops.restoreLocalAuthorization(NativeAckContract.OWNER) else shops.refresh(NativeAckContract.OWNER)
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val state = if (restoreOnly) initial.resolveOfflineBusinessDataScope(NativeAckContract.scope, canWrite = true)
            else initial.resolveBusinessDataScope(NativeAckContract.scope)
        tracker = CatalogSyncStateTracker(state)
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
        fun install(name: String, value: Any?) = MerchandiseControlApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(app, value)
        install("database\$delegate", lazyOf(db)); install("repository\$delegate", lazyOf(repository))
        install("authManager\$delegate", lazyOf(auth)); install("shopContextRepository\$delegate", lazyOf(shops))
        install("catalogSyncStateTracker\$delegate", lazyOf(tracker)); install("supabaseClient\$delegate", lazyOf(null))
        install("shopSyncReadRemoteDataSource\$delegate", lazyOf(reader)); install("deviceInstallIdProvider\$delegate", lazyOf(provider))
        install("shopDeviceRegistrationRemoteDataSource\$delegate", lazyOf(ShopDeviceRegistrationRemoteDataSource(client, provider)))
        // Keep the production authorization lazy property and its real callback unchanged.
    }

    fun beginOfflineObservation() {
        check(!observationStarted && !shops.state.value.syncAllowed)
        observationStarted = true
        MerchandiseControlApplication::class.java.getDeclaredMethod("observeShopContextForRemoteComponents")
            .apply { isAccessible = true }.invoke(app)
    }

    suspend fun mountViewModel() = withContext(Dispatchers.Main) {
        check(!::vm.isInitialized)
        vm = CatalogSyncViewModel(app, repository, business, prices, SupabaseSessionBackupRemoteDataSource(null), auth.state,
            syncStateTracker = tracker, shopContextFlow = shops.state)
        viewModels.put("scope-safety", vm)
        vm.onOptionsScreenVisible()
    }

    suspend fun awaitDenied() = withTimeout(5_000) {
        while (tracker.businessDataScopeState.value.allowsLocalOperations) delay(10)
    }

    suspend fun product(): Product = requireNotNull(db.productDao().findByBarcode(NativeAckContract.BARCODE))

    /** Full logical rows with types, including receipts/journals/proofs; no table subset can hide data loss. */
    suspend fun image(): JsonObject = db.withTransaction {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        buildJsonObject {
            for (table in tables) {
                check(Regex("[A-Za-z0-9_]+").matches(table))
                val rows = sql.query("SELECT * FROM `$table`").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(buildJsonObject {
                            cursor.columnNames.forEachIndexed { index, column ->
                                put(column, when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> "null"
                                    android.database.Cursor.FIELD_TYPE_BLOB -> "blob:" + android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                                    else -> "${cursor.getType(index)}:" + cursor.getString(index)
                                })
                            }
                        }.toString())
                    }.sorted()
                }
                put(table, JsonArray(rows.map(::JsonPrimitive)))
            }
        }
    }
    suspend fun saveImage(name: String): JsonObject = image().also {
        require(Regex("[a-z0-9-]+").matches(name))
        nativeDurableWrite(File(context.root, "$name.json"), it.toString().encodeToByteArray())
        record(name, buildJsonObject { put("imageSha256", nativeSha(it.toString().encodeToByteArray())) })
    }
    fun savePreferences(name: String) {
        val value = buildJsonObject { preferences.all.toSortedMap().forEach { (key, value) -> put(key, value.toString()) } }
        nativeDurableWrite(File(context.root, "$name-preferences.json"), value.toString().encodeToByteArray())
    }
    suspend fun assertSaveDenied() {
        val before = product()
        val failure = runCatching { repository.updateProductFromEditor(before, before.copy(productName = "DENIED foreign write")) }.exceptionOrNull()
        assertTrue("Exact local admission failure required: $failure", failure is Task126BusinessDataScopeChangedException)
        assertEquals(before, product())
        record("save_denied", buildJsonObject { put("exception", requireNotNull(failure)::class.java.name) })
    }
    suspend fun close() = withContext(NonCancellable) {
        if (closed) return@withContext
        var failure: Throwable? = null
        suspend fun attempt(name: String, block: suspend () -> Unit) {
            if (name in closedPhases) return
            try { block(); closedPhases.add(name) } catch (secondary: Throwable) {
                val first = failure
                if (first == null) failure = secondary else if (first !== secondary) first.addSuppressed(secondary)
            }
        }
        attempt("view_models") { withContext(Dispatchers.Main) { viewModels.clear() } }
        attempt("device_polling") { if (::app.isInitialized) scopeSafetyField<Job?>(app, "shopDeviceStatusPollingJob")?.cancelAndJoin() }
        attempt("realtime") {
            if (::app.isInitialized) {
                val realtime: Lazy<RealtimeRefreshCoordinator> = scopeSafetyField(app, "realtimeRefreshCoordinator\$delegate")
                if (realtime.isInitialized()) realtime.value.shutdown()
            }
        }
        attempt("application_scope") { if (::app.isInitialized) scopeSafetyField<CoroutineScope>(app, "appScope").coroutineContext[Job]?.cancelAndJoin() }
        attempt("sdk") { withTimeout(5_000) { sdk?.close() } }
        attempt("database") { db.close() } // Always attempted; never delete DB/WAL or evidence.
        failure?.let { throw it }
        // Do not emit quiescence or suppress a later close until all owned phases completed.
        record("runtime_quiescent")
        closed = true
    }
}

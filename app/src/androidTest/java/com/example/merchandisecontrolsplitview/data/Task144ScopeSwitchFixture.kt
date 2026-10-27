package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.productimage.*
import com.example.merchandisecontrolsplitview.ui.navigation.Task144ScopeSwitchDeviceTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

internal object ScopeSwitchContract {
    const val PROTOCOL = "task144-scope-switch-candidate01"
    const val DB = "scope-switch.db"
    const val SHOP_B = "14400000-0000-4000-8000-000000000012"
    const val REGISTER_RPC = "shop_device_register_for_shop"
    const val MEMBERSHIP_RPC = "mobile_linked_shops"
    const val DEVICE_RPC = "shop_device_status_for_shop"
    val json = Json { encodeDefaults = true }
}

/** Own files and real namespaced SharedPreferences. Nothing deletes even a failed fixture. */
internal class ScopeSwitchContext(base: Context, val runId: String, val scenario: String, fresh: Boolean,
    protocol: String = ScopeSwitchContract.PROTOCOL) : ContextWrapper(base) {
    val root: File
    val preferencePrefix = "task144_switch_${runId}_${scenario}_"
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        val permittedScenarios = when (protocol) {
            ScopeSwitchContract.PROTOCOL -> setOf("draft", "late-response")
            NativeResolutionContract.PROTOCOL -> NativeResolutionContract.scenarios
            NativeAutomaticRecoveryContract.PROTOCOL -> NativeAutomaticRecoveryContract.scenarios
            com.example.merchandisecontrolsplitview.ui.navigation.EditorLivenessContract.PROTOCOL ->
                setOf(com.example.merchandisecontrolsplitview.ui.navigation.EditorLivenessContract.SCENARIO)
            else -> error("Unknown owned native protocol")
        }
        require(scenario in permittedScenarios)
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-switch-$runId-$scenario")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val ownership = "$protocol\n$runId\n$scenario\n"
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
            check(File(databases, ScopeSwitchContract.DB).isFile)
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

internal fun scopeSwitchContext(scenario: String, fresh: Boolean): ScopeSwitchContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class")?.startsWith(Task144ScopeSwitchDeviceTest::class.java.name + "#") == true)
    check(args.getString("scopeSwitchProtocol") == ScopeSwitchContract.PROTOCOL)
    return ScopeSwitchContext(instrumentation.targetContext, requireNotNull(args.getString("scopeSwitchRunId")), scenario, fresh)
}

internal class ScopeSwitchApplication : MerchandiseControlApplication() {
    fun attachOwned(context: Context) = attachBaseContext(context)
}

@Suppress("UNCHECKED_CAST")
internal fun <T> scopeSwitchField(target: Any, name: String): T {
    val owner = if (target is MerchandiseControlApplication) MerchandiseControlApplication::class.java else target.javaClass
    return owner.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}

/** Candidate-local observer: one durable raw entry per overload before any delegate guard/commit. */
internal class ScopeSwitchObservedCatalog(
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
/** Readiness compares all normalized scope values, then the already captured local epoch. */
internal fun scopeSwitchReadyFor(
    expected: Task126OwnerStoreScope,
    tracker: CatalogSyncStateTracker,
    state: Task126BusinessDataScopeState,
    stamp: Task126LocalPresentationStamp?
): Boolean = state.status == Task126BusinessDataScopeStatus.READY && state.boundScope != null &&
    Task126OwnerStoreGate.validate(state.boundScope, expected) == Task126OwnerStoreGateDecision.Allowed &&
    stamp != null && tracker.isLocalPresentationStampCurrent(stamp, state)

internal class ScopeSwitchFixture(val context: ScopeSwitchContext, private val nativeResolutionMode: Boolean = false,
    private val nativeResolutionSeedPriceHistory: Boolean = true,
    private val nativeResolutionOrdinaryWake: Boolean = false,
    private val nativeResolutionCrashProbe: NativeResolutionCrashProbe? = null,
    private val automaticRecoveryProbe: NativeAutomaticRecoveryProbe? = null) {
    val db = Room.databaseBuilder(context, AppDatabase::class.java, ScopeSwitchContract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .apply { nativeResolutionCrashProbe?.let { probe ->
            check(nativeResolutionMode && context.scenario in NativeResolutionContract.crashMethods)
            setQueryCallback(probe, java.util.concurrent.Executor { command -> command.run() })
        } }
        .build().also { check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(ScopeSwitchContract.DB)) }
    val backend = NativeAckPersistentCatalog(db, File(context.root, "synthetic-backend.json"))
    val resolutionBackend = if (nativeResolutionMode)
        NativeResolutionPersistentRemote(db, File(context.root, "synthetic-resolution-backend.json")) else null
    private val seedBusiness = ScopeSwitchObservedCatalog(resolutionBackend ?: backend, ::record)
    // Unconfigured lateral business transports, still observed before their real guards.
    // Scope authority/recovery remain configured and execute through the actual Application.
    val business = ScopeSwitchObservedCatalog(resolutionBackend ?: SupabaseCatalogRemoteDataSource(null), ::record)
    val reader: ShopSyncReadRemoteDataSource = (resolutionBackend?.reader ?: ScopeSwitchReadRemote(::record)).let {
        automaticRecoveryProbe?.wrap(it) ?: it
    }
    val preferences = context.getSharedPreferences("mobile_shop_context", Context.MODE_PRIVATE)
    val selected = SharedPreferencesSelectedShopStore(preferences)
    private val holdNext = AtomicBoolean(false)
    val oldResponseEntered = CountDownLatch(1)
    private val releaseOld = CountDownLatch(1)
    private var oldRefresh: Deferred<Unit>? = null
    private var sdk: SupabaseClient? = null
    lateinit var app: ScopeSwitchApplication
    lateinit var tracker: CatalogSyncStateTracker
    lateinit var repository: DefaultInventoryRepository
    lateinit var shops: ShopContextRepository
    lateinit var auth: SupabaseAuthManager
    lateinit var databaseVM: DatabaseViewModel
    lateinit var excelVM: ExcelViewModel
    private var images: ProductImageService? = null
    private val viewModels = ViewModelStore()
    val viewModelOwner = object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = viewModels }
    private var closed = false
    private val closedPhases = mutableSetOf<String>()
    private var observationStarted = false
    private val prices: ProductPriceRemoteDataSource = resolutionBackend?.prices ?: SupabaseProductPriceRemoteDataSource(null)

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
        ?.map { ScopeSwitchContract.json.parseToJsonElement(it).jsonObject }
        ?.filter { it["kind"]?.jsonPrimitive?.content == "rpc_entry" }.orEmpty()

    @Synchronized fun businessRecords(): List<JsonObject> = File(context.root, "ledger.jsonl").takeIf { it.isFile }?.readLines()
        ?.map { ScopeSwitchContract.json.parseToJsonElement(it).jsonObject }
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
        val seedProduct = Product(barcode = NativeAckContract.BARCODE, productName = "Original", itemNumber = "Original item")
        seedRepository.addProduct(if (nativeResolutionMode && nativeResolutionSeedPriceHistory)
            seedProduct.copy(purchasePrice = 3.0, retailPrice = 5.0) else seedProduct)
        seedTracker.withBusinessDataScopeFlight(NativeAckContract.OWNER, NativeAckContract.shop) {
            seedRepository.pushDirtyCatalogDeltaToRemote(seedBusiness, prices, NativeAckContract.OWNER,
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
    suspend fun openRuntime(allowPendingRecovery: Boolean = false) {
        check(!::app.isInitialized)
        val client = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            httpEngine = OkHttp.create { config { addInterceptor { chain ->
                val request = chain.request()
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                val body = request.body?.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }.orEmpty()
                // Capture entry before checking method/scope; never log headers or session tokens.
                record("rpc_entry", buildJsonObject { put("endpoint", endpoint); put("requestSha256", nativeSha(body.encodeToByteArray())); put("method", request.method) })
                check(request.method == "POST" && endpoint in setOf(ScopeSwitchContract.MEMBERSHIP_RPC,
                    ScopeSwitchContract.DEVICE_RPC, ScopeSwitchContract.REGISTER_RPC))
                val payload = if (endpoint == ScopeSwitchContract.MEMBERSHIP_RPC) {
                    val held = holdNext.compareAndSet(true, false)
                    if (held) {
                        oldResponseEntered.countDown()
                        record("old_membership_response_held")
                        check(releaseOld.await(90, TimeUnit.SECONDS)) { "bounded late response was not released" }
                        record("old_membership_response_released")
                    }
                    val ids = if (held) listOf(NativeAckContract.SHOP) else listOf(NativeAckContract.SHOP, ScopeSwitchContract.SHOP_B)
                    buildJsonObject {
                        put("ok", true); put("code", "success")
                        put("shops", JsonArray(ids.map { shopId -> buildJsonObject {
                            put("shop_id", shopId); put("shop_code", if (shopId == NativeAckContract.SHOP) "SCOPE_A" else "SCOPE_B")
                            put("shop_name", if (shopId == NativeAckContract.SHOP) "Owned scope A" else "Owned scope B")
                            put("role_key", "shop_owner"); put("membership_status", "active"); put("shop_status", "active")
                            put("can_select", true); put("can_write", true)
                        } }))
                    }.toString()
                } else {
                    val params = ScopeSwitchContract.json.parseToJsonElement(body).jsonObject
                    val shopId = params["p_shop_id"]?.jsonPrimitive?.content
                    check(shopId in setOf(NativeAckContract.SHOP, ScopeSwitchContract.SHOP_B))
                    check(params["p_device_identifier"]?.jsonPrimitive?.content == NativeAckContract.DEVICE)
                    if (endpoint == ScopeSwitchContract.REGISTER_RPC)
                        """{"ok":true,"code":"success","shop_id":"$shopId","target_id":"${NativeAckContract.DEVICE}"}"""
                    else """{"ok":true,"code":"active","status":"active","can_write":true,"reason_code":"active"}"""
                }
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("Synthetic").body(payload.toResponseBody("application/json".toMediaType())).build()
                // No chain.proceed: no real network endpoint is reachable.
            } } }
            coroutineDispatcher = Dispatchers.IO; defaultLogLevel = LogLevel.NONE; requestTimeout = 100.seconds
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false; sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
            install(Postgrest) { timeout = 100.seconds }
        }
        sdk = client
        client.auth.importSession(UserSession(accessToken = "synthetic-access", refreshToken = "synthetic-refresh", expiresIn = 86_400,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = NativeAckContract.OWNER), expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
        app = ScopeSwitchApplication().apply { attachOwned(context) } // Deliberately no Application.onCreate or real login.
        val scope: CoroutineScope = scopeSwitchField(app, "appScope")
        auth = SupabaseAuthManager(client = null, googleWebClientId = "", scope = scope)
        scopeSwitchField<MutableStateFlow<AuthState>>(auth, "_state").value = AuthState.SignedIn(NativeAckContract.OWNER, "synthetic@example.test")
        val provider = DeviceInstallIdProvider(db.syncEventDeviceStateDao())
        assertEquals(NativeAckContract.DEVICE, provider.getOrCreate())
        shops = ShopContextRepository(SupabaseLinkedShopRemoteDataSource(client), selected,
            currentOwnerUserId = { (auth.state.value as? AuthState.SignedIn)?.userId }, currentDeviceIdentifier = { provider.currentId })
        selected.setSelectedShopId(NativeAckContract.OWNER, NativeAckContract.SHOP)
        shops.refresh(NativeAckContract.OWNER)
        assertEquals(NativeAckContract.SHOP, shops.state.value.activeShopId)
        assertEquals(2, shops.state.value.selectableShops.size)
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val state = initial.resolveBusinessDataScope(NativeAckContract.scope)
        if (allowPendingRecovery) {
            check(nativeResolutionMode && context.scenario in NativeAutomaticRecoveryContract.scenarios)
            assertEquals("sync_recovery_required", state.errorCode)
            assertTrue("Recovery must retain genuinely qualified local capability", state.allowsLocalOperations)
            assertEquals(Task126OwnerStoreGateDecision.Allowed, Task126OwnerStoreGate.validate(
                requireNotNull(state.localAccessScope ?: state.boundScope), NativeAckContract.scope))
            assertEquals(SyncRecoveryAuthorizationModes.SAME_SCOPE, db.syncRecoveryJournalDao().get()?.authorizationMode)
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        } else assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        tracker = CatalogSyncStateTracker(state).apply { updateNetworkAvailability(true) }
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
        fun install(name: String, value: Any?) = MerchandiseControlApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(app, value)
        install("database\$delegate", lazyOf(db)); install("repository\$delegate", lazyOf(repository))
        install("authManager\$delegate", lazyOf(auth)); install("shopContextRepository\$delegate", lazyOf(shops))
        install("catalogSyncStateTracker\$delegate", lazyOf(tracker)); install("supabaseClient\$delegate", lazyOf(null))
        install("shopSyncReadRemoteDataSource\$delegate", lazyOf(reader)); install("deviceInstallIdProvider\$delegate", lazyOf(provider))
        install("shopDeviceRegistrationRemoteDataSource\$delegate", lazyOf(ShopDeviceRegistrationRemoteDataSource(client, provider)))
        install("rawCatalogRemoteDataSource\$delegate", lazyOf(business))
        install("rawProductPriceRemoteDataSource\$delegate", lazyOf(prices))
        install("rawSessionBackupRemoteDataSource\$delegate", lazyOf(SupabaseSessionBackupRemoteDataSource(null)))
        install("rawSyncEventRemoteDataSource\$delegate", lazyOf(if (nativeResolutionOrdinaryWake)
            requireNotNull(resolutionBackend).ordinaryWakeEvents else SupabaseSyncEventRemoteDataSource(null)))
        // Actual device authorization, guarded wrappers, auto coordinators, recovery coordinator,
        // currentAuthAndShopMatch, activation mutex and transition stay production lazy properties.
        // No constructed READY, alternate recovery callback, clocks or manually forced admission.
        images = ProductImageService(context, db, ScopeSwitchDisabledImages,
            accountIdProvider = { (auth.state.value as? AuthState.SignedIn)?.userId },
            selectedShopProvider = { shops.state.value.selectedShop }, accessTokenProvider = { null },
            businessDataScopeAllowed = tracker::allowsBusinessDataScope, businessDataScopeRuntimeGuard = tracker)
        install("productImageServiceDelegate", lazyOf(requireNotNull(images)))
    }

    fun beginObservation() {
        check(!observationStarted && shops.state.value.syncAllowed)
        observationStarted = true
        MerchandiseControlApplication::class.java.getDeclaredMethod("observeShopContextForRemoteComponents")
            .apply { isAccessible = true }.invoke(app)
    }

    suspend fun mountViewModels() = withContext(Dispatchers.Main) {
        check(!::databaseVM.isInitialized)
        databaseVM = DatabaseViewModel(app, repository)
        excelVM = ExcelViewModel(app, repository)
        viewModels.put("database", databaseVM); viewModels.put("excel", excelVM)
    }

    suspend fun awaitScope(shopId: String, status: Task126BusinessDataScopeStatus) = withTimeout(45_000) {
        while (true) {
            val authority = shops.state.value
            val state = tracker.businessDataScopeState.value
            if (authority.activeShopId == shopId && !authority.isLoading && authority.syncAllowed &&
                state.status == status && (status != Task126BusinessDataScopeStatus.READY ||
                (scopeSwitchReadyFor(task126ActiveOwnerStoreScope(NativeAckContract.OWNER, authority.selectedShop),
                    tracker, state, tracker.captureLocalPresentationStamp(state)) &&
                    scopeSwitchField<Job?>(app, "businessRecoveryJob")?.isCompleted != false))) break
            delay(10)
        }
    }

    suspend fun verifyReady(shopId: String) {
        awaitScope(shopId, Task126BusinessDataScopeStatus.READY)
        val authority = shops.state.value
        assertEquals(NativeAckContract.OWNER, (auth.state.value as AuthState.SignedIn).userId)
        assertEquals(NativeAckContract.OWNER, authority.ownerUserId)
        assertTrue(authority.localAccessAllowed)
        val scope = task126ActiveOwnerStoreScope(NativeAckContract.OWNER, authority.selectedShop)
        assertEquals(Task126OwnerStoreGateDecision.Allowed,
            Task126OwnerStoreGate.validate(requireNotNull(db.businessDataScopeBindingDao().get()).toOwnerStoreScope(), scope))
        assertNull(db.syncRecoveryJournalDao().get())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(shopId, baseline.shopId)
        val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        assertEquals(shopId, checkpoint.shopId)
        validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint)
        assertTrue(repository.resolveOfflineBusinessDataScope(scope, canWrite = true).allowsLocalOperations)
        assertEquals(NativeAckContract.DEVICE, app.deviceInstallIdProvider.currentId)
        record("ready_qualified", buildJsonObject { put("shopId", shopId); put("generation", baseline.generationId) })
    }

    /** Full value qualification for the R4 opt-in, never reference identity of scope objects. */
    suspend fun verifyResolutionReady() {
        check(nativeResolutionMode)
        val authority = shops.state.value
        assertEquals(NativeAckContract.SHOP, authority.activeShopId)
        assertEquals(NativeAckContract.OWNER, authority.ownerUserId)
        assertTrue(authority.localAccessAllowed)
        val state = repository.resolveBusinessDataScope(NativeAckContract.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        assertEquals(Task126OwnerStoreGateDecision.Allowed,
            Task126OwnerStoreGate.validate(requireNotNull(state.boundScope), NativeAckContract.scope))
        assertEquals(Task126OwnerStoreGateDecision.Allowed,
            Task126OwnerStoreGate.validate(requireNotNull(db.businessDataScopeBindingDao().get()).toOwnerStoreScope(), NativeAckContract.scope))
        assertNull(db.syncRecoveryJournalDao().get())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        assertTrue(repository.resolveOfflineBusinessDataScope(NativeAckContract.scope, true).allowsLocalOperations)
        assertEquals(NativeAckContract.DEVICE, app.deviceInstallIdProvider.currentId)
        record("resolution_runtime_ready", buildJsonObject { put("generation", baseline.generationId) })
    }

    suspend fun startOldRefresh() {
        check(oldRefresh == null && holdNext.compareAndSet(false, true))
        val appScope: CoroutineScope = scopeSwitchField(app, "appScope")
        oldRefresh = appScope.async { shops.refresh(NativeAckContract.OWNER) }
        withContext(Dispatchers.IO) { check(oldResponseEntered.await(5, TimeUnit.SECONDS)) }
        assertTrue(shops.state.value.isLoading)
    }
    suspend fun publishNewAuthority() {
        shops.refresh(NativeAckContract.OWNER)
        assertFalse(shops.state.value.isLoading)
        assertTrue(shops.state.value.syncAllowed)
        assertEquals(2, shops.state.value.selectableShops.size)
    }
    suspend fun finishOldRefresh() {
        releaseOld.countDown()
        withTimeout(5_000) { requireNotNull(oldRefresh).await() }
        record("old_refresh_completed")
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
    fun savePreferences(name: String): JsonObject {
        val value = buildJsonObject { preferences.all.toSortedMap().forEach { (key, value) -> put(key, value.toString()) } }
        nativeDurableWrite(File(context.root, "$name-preferences.json"), value.toString().encodeToByteArray())
        return value
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
        attempt("release_old_callback") { releaseOld.countDown(); oldRefresh?.let { withTimeout(5_000) { it.join() } } }
        attempt("application_scope") { if (::app.isInitialized) withTimeout(5_000) { scopeSwitchField<CoroutineScope>(app, "appScope").coroutineContext[Job]?.cancelAndJoin() } }
        attempt("view_models") {
            val jobs = if (nativeResolutionMode && ::databaseVM.isInitialized && ::excelVM.isInitialized)
                listOfNotNull(databaseVM.viewModelScope.coroutineContext[Job], excelVM.viewModelScope.coroutineContext[Job])
            else emptyList()
            withContext(Dispatchers.Main) { viewModels.clear() }
            if (nativeResolutionMode) withTimeout(5_000) { jobs.joinAll() }
        }
        attempt("automatic_coordinators") {
            if (::app.isInitialized) {
                val catalog: Lazy<CatalogAutoSyncCoordinator> = scopeSwitchField(app, "catalogAutoSyncCoordinator\$delegate")
                if (catalog.isInitialized()) catalog.value.shutdown()
            }
        }
        attempt("history_coordinator") {
            if (::app.isInitialized) {
                val history: Lazy<HistorySessionPushCoordinator> = scopeSwitchField(app, "historySessionPushCoordinator\$delegate")
                if (history.isInitialized()) history.value.shutdown()
            }
        }
        // Each shutdown and join remains independent: one failure cannot skip another owner.
        for (field in listOf("catalogAutoSyncCoordinator\$delegate", "historySessionPushCoordinator\$delegate",
            "realtimeRefreshCoordinator\$delegate", "realtimeSessionSubscriber\$delegate")) {
            attempt("cancel_$field") {
                if (::app.isInitialized) {
                    val owner: Lazy<*> = scopeSwitchField(app, field)
                    if (owner.isInitialized()) scopeSwitchField<CoroutineScope>(requireNotNull(owner.value), "scope").cancel()
                }
            }
            attempt("join_$field") {
                if (::app.isInitialized) {
                    val owner: Lazy<*> = scopeSwitchField(app, field)
                    if (owner.isInitialized()) withTimeout(5_000) {
                        scopeSwitchField<CoroutineScope>(requireNotNull(owner.value), "scope").coroutineContext[Job]?.join()
                    }
                }
            }
        }
        attempt("images") { images?.close() }
        attempt("images_join") { images?.let { withTimeout(5_000) { scopeSwitchField<CoroutineScope>(it, "producerScope").coroutineContext[Job]?.join() } } }
        attempt("device_polling") { if (::app.isInitialized) withTimeout(5_000) { scopeSwitchField<Job?>(app, "shopDeviceStatusPollingJob")?.cancelAndJoin() } }
        attempt("realtime") {
            if (::app.isInitialized) {
                val realtime: Lazy<RealtimeRefreshCoordinator> = scopeSwitchField(app, "realtimeRefreshCoordinator\$delegate")
                if (realtime.isInitialized()) realtime.value.shutdown()
            }
        }
        attempt("sdk") { withTimeout(5_000) { sdk?.close() } }
        attempt("database") { db.close() } // Always attempted; never delete DB/WAL or evidence.
        failure?.let { throw it }
        // Do not emit quiescence or suppress a later close until all owned phases completed.
        record("runtime_quiescent")
        closed = true
    }
}

/** Parameterizes only synthetic scope envelopes; coordinator and proof validation are production. */
internal class ScopeSwitchReadRemote(private val record: (String, JsonObject) -> Unit) : ShopSyncReadRemoteDataSource {
    override val isConfigured = true
    private val empty = NativeAckEmptyRecoveryRemote()
    private fun checkContext(context: ShopSyncRpcContext) {
        check(context.accountId == NativeAckContract.OWNER && context.deviceIdentifier == NativeAckContract.DEVICE)
        check(context.shopId in setOf(NativeAckContract.SHOP, ScopeSwitchContract.SHOP_B))
    }
    private fun scope(shop: String): ShopSyncScope = empty.checkpoint.scope.copy(
        key = if (shop == NativeAckContract.SHOP) empty.checkpoint.scope.key else nativeSha("owned-scope-B".encodeToByteArray()))
    private fun entered(kind: String, context: ShopSyncRpcContext, domain: String? = null) {
        record("read_entry", buildJsonObject { put("method", kind); put("shopId", context.shopId)
            put("deviceId", context.deviceIdentifier); put("domain", domain?.let(::JsonPrimitive) ?: JsonNull) })
        checkContext(context)
    }
    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
        entered("checkpoint", context)
        return empty.checkpoint(context).map { it.copy(shopId = context.shopId, scope = scope(context.shopId)) }
    }
    override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
        entered("recoveryPage", context, domain.name)
        return empty.recoveryPage(context, domain, afterId, limit).map { it.copy(shopId = context.shopId, scope = scope(context.shopId)) }
    }
    override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
        entered("convergenceMarker", context)
        return empty.convergenceMarker(context).map { it.copy(shopId = context.shopId, scope = scope(context.shopId)) }
    }
    override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> {
        entered("eventPage", context)
        return empty.eventPage(context, afterId, limit)
    }
    override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, ids: List<String>): Result<ShopSyncTargetedRows> {
        entered("rowsByIds", context, domain.name)
        return empty.rowsByIds(context, domain, ids)
    }
}

/** Non-participating image transport; no SDK/network instance or override of scope admission. */
private object ScopeSwitchDisabledImages : ProductImageRemoteGateway {
    override val isConfigured = false
    override suspend fun createIntent(accessToken: String, body: ProductImageIntentBody): ProductImageIntentResponse = error("image operation outside scope")
    override suspend fun finalizeImage(accessToken: String, body: ProductImageFinalizeBody): ProductImageFinalizeResponse = error("image operation outside scope")
    override suspend fun removeImage(accessToken: String, body: ProductImageRemoveBody): ProductImageRemoveResponse = error("image operation outside scope")
    override suspend fun readUrls(accessToken: String, body: ProductImageReadBody): ProductImageReadResponse = error("image operation outside scope")
    override suspend fun putSignedJpeg(signedUrl: String, bytes: ByteArray, expectedReference: ProductImageReference): Unit = error("image operation outside scope")
    override suspend fun downloadSignedJpeg(signedUrl: String, expectedReference: ProductImageReference): ByteArray = error("image operation outside scope")
    override fun close() = Unit
}

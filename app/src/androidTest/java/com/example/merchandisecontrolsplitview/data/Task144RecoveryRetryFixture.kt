package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*

internal object T05Contract {
    const val OWNER = "10000000-0000-4000-8000-000000000001"
    const val SHOP = "10000000-0000-4000-8000-000000000003"
    const val DEVICE = "android-recovery-force-stop-device"
    const val DB = "native-retry.db"
    const val BARCODE = "TASK144-NATIVE-T05-PENDING"
    const val PROTOCOL = "task144-native-t05-mixed-v1"
    // Test-local values of private production RPC constants; production visibility is unchanged.
    const val CHECKPOINT_RPC = "shop_sync_recovery_checkpoint_v1"
    const val PAGE_RPC = "shop_sync_recovery_page_v1"
    const val MARKER_RPC = "shop_sync_convergence_marker_v1"
    val json = Json { encodeDefaults = true }
    val shop = SelectedShop(SHOP, "NATIVE144", "Synthetic owned retry fixture", "shop_owner", "active", true)
    val scope = task126ActiveOwnerStoreScope(OWNER, shop)
}

internal fun t05Sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** Atomic replacement and fsync of bytes AND containing directory, before boundary publication. */
internal fun t05DurableWrite(file: File, bytes: ByteArray) {
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

/** Adapts reviewed native ACK isolation for a verified owned reopen. No generic DB/staging sweep or cleanup. */
internal class T05OwnedContext(base: Context, val runId: String, val scenario: String, fresh: Boolean) : ContextWrapper(base) {
    val root: File
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        require(scenario in setOf("mixed", "stable"))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-t05-$runId-$scenario")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        val ownership = "${T05Contract.PROTOCOL}\n$runId\n$scenario\n"
        val sentinel = File(root, "ownership.txt")
        if (fresh) {
            check(!root.exists()) { "Fresh run ID required; existing evidence is preserved" }
            check(root.mkdir())
            t05DurableWrite(sentinel, ownership.encodeToByteArray())
            check(File(root, "databases").mkdir())
        } else {
            check(root.isDirectory && sentinel.isFile && sentinel.readText() == ownership)
            check(File(root, "databases/${T05Contract.DB}").isFile) { "Owned Room store missing" }
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


internal fun t05OwnedContext(scenario: String, fresh: Boolean): T05OwnedContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class")?.startsWith(Task144RecoveryRetryDeviceTest::class.java.name + "#") == true)
    check(args.getString("nativeRetryProtocol") == T05Contract.PROTOCOL)
    return T05OwnedContext(instrumentation.targetContext, requireNotNull(args.getString("nativeRetryRunId")), scenario, fresh)
}

/** New owned Application instance; onCreate is deliberately NOT invoked. */
internal class T05OwnedApplication : MerchandiseControlApplication() {
    fun attachOwned(context: Context) = attachBaseContext(context)
}

internal fun <T> t05Field(owner: Any, name: String): T {
    val declaring = if (owner is MerchandiseControlApplication) MerchandiseControlApplication::class.java else owner.javaClass
    @Suppress("UNCHECKED_CAST")
    return declaring.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
}

internal class T05Fixture(val context: T05OwnedContext) {
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, T05Contract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also {
            check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(T05Contract.DB))
        }
    private val checkpointTemplate = Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote().checkpoint
    private var sdk: SupabaseClient? = null
    lateinit var reader: ShopSyncReadRemoteDataSource
        private set
    lateinit var repository: DefaultInventoryRepository
        private set
    lateinit var tracker: CatalogSyncStateTracker
        private set
    lateinit var application: T05OwnedApplication
        private set
    private var auto: CatalogAutoSyncCoordinator? = null
    private var history: HistorySessionPushCoordinator? = null
    private val ledger = File(context.root, "transport.jsonl")
    private val ledgerLock = Any()
    var armed = false

    fun records(): List<JsonObject> = synchronized(ledgerLock) {
        if (!ledger.isFile) emptyList() else ledger.readLines().filter { it.isNotBlank() }.map { T05Contract.json.parseToJsonElement(it).jsonObject }
    }
    private fun record(value: JsonObject) = synchronized(ledgerLock) {
        FileOutputStream(ledger, true).use { it.write((value.toString() + "\n").encodeToByteArray()); it.fd.sync() }
    }
    fun snapshot(table: String): String {
        require(table in setOf("products", "product_remote_refs", "sync_recovery_journal", "sync_recovery_baseline"))
        return db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY 1").use { c ->
            buildJsonArray {
                while (c.moveToNext()) add(buildJsonObject {
                    c.columnNames.forEachIndexed { i, name ->
                        put(name, if (c.isNull(i)) JsonNull else JsonPrimitive(c.getString(i)))
                    }
                })
            }.toString()
        }
    }
    suspend fun product() = requireNotNull(db.productDao().findByBarcode(T05Contract.BARCODE))
    suspend fun ref() = requireNotNull(db.productRemoteRefDao().getByProductId(product().id))
    suspend fun journal() = requireNotNull(db.syncRecoveryJournalDao().get())
    fun mutex(): Mutex = t05Field(application, "businessRecoveryExecutionMutex")
    fun recoveryJob(): Job? = t05Field(application, "businessRecoveryJob")

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    suspend fun openTransport() {
        check(sdk == null)
        val client = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            // Existing UNIT seam: no chain.proceed, so no DNS/socket or real backend access.
            httpEngine = OkHttp.create { config { addInterceptor { chain ->
                val request = chain.request()
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                // Record the rejected request boundary as well as admitted HTTP calls. No headers/session data.
                record(buildJsonObject {
                    put("kind", "request_received"); put("pid", Process.myPid())
                    put("elapsedNs", SystemClock.elapsedRealtimeNanos())
                    put("method", request.method); put("path", request.url.encodedPath)
                })
                check(request.method == "POST") { "T05 fixture accepts only RPC POST" }
                check(endpoint in setOf(T05Contract.CHECKPOINT_RPC, T05Contract.PAGE_RPC, T05Contract.MARKER_RPC)) {
                    "T05 fixture received an unsupported RPC: $endpoint"
                }
                val body = okio.Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
                val params = T05Contract.json.parseToJsonElement(body).jsonObject
                check(params.getValue("p_shop_id").jsonPrimitive.content == T05Contract.SHOP)
                check(params.getValue("p_device_identifier").jsonPrimitive.content == T05Contract.DEVICE)
                val row = runBlocking { db.syncRecoveryJournalDao().get() }
                val claim = row?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
                if (armed) check(claim?.state == "in_flight")
                val attempt = if (armed) requireNotNull(row).attemptCount else 0
                val fault = if (!armed || endpoint != T05Contract.CHECKPOINT_RPC) "healthy"
                    else if (context.scenario == "stable") "403"
                    else when (attempt) { 1 -> "500"; 2 -> "dns"; 3 -> "503"; 4 -> "timeout"; 5 -> "429"; 6 -> "timeout"; 7 -> "healthy"; else -> error("Unexpected claim $attempt") }
                if (armed && fault != "healthy") check(records().none {
                    it["kind"]?.jsonPrimitive?.content == "http" && it["claim"]?.jsonPrimitive?.content == claim?.claimID
                }) { "One failed claim must not hide multiple interceptor transmissions" }
                record(buildJsonObject {
                    put("kind", "http"); put("pid", Process.myPid()); put("wallMs", System.currentTimeMillis())
                    put("elapsedNs", SystemClock.elapsedRealtimeNanos()); put("endpoint", endpoint)
                    put("attempt", attempt); put("window", claim?.windowAttempts ?: 0); put("claim", claim?.claimID ?: "setup")
                    put("fault", fault); put("requestBodySha256", t05Sha(body.encodeToByteArray()))
                    put("journalPhase", row?.phase ?: ""); put("generationId", row?.runId ?: "")
                    put("intentCreatedAtMs", row?.createdAtMs ?: -1); put("ownerHash", row?.ownerHash ?: "")
                    put("storeScope", row?.storeScope ?: ""); put("deviceId", row?.deviceId ?: "")
                    put("nextEligibleAtMs", claim?.nextEligibleAtMs ?: -1)
                })
                if (fault == "dns") throw UnknownHostException("task144_synthetic_dns")
                if (fault == "timeout") throw SocketTimeoutException("task144_synthetic_timeout")
                val status = fault.toIntOrNull() ?: 200
                val payload = if (status == 200) healthyPayload(endpoint, params) else "{}"
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(status).message("Synthetic")
                    .apply { if (status == 429) header("Retry-After", "310"); if (status == 503) header("Retry-After", "13") }
                    .body(payload.toResponseBody("application/json".toMediaType())).build()
            } } }
            coroutineDispatcher = Dispatchers.IO
            defaultLogLevel = LogLevel.NONE
            requestTimeout = 3.seconds
            install(Auth) {
                autoLoadFromStorage = false; autoSetupPlatform = false
                sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest) { timeout = 3.seconds }
        }
        sdk = client
        client.auth.importSession(UserSession(accessToken = "synthetic-access", refreshToken = "synthetic-refresh",
            expiresIn = 86_400, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = T05Contract.OWNER),
            expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
        val actual = SupabaseShopSyncReadRemoteDataSource(client)
        reader = object : ShopSyncReadRemoteDataSource by actual {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                val row = db.syncRecoveryJournalDao().get()
                val claim = row?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
                val start = System.currentTimeMillis()
                val mono = SystemClock.elapsedRealtimeNanos()
                val result = actual.checkpoint(context)
                val classifiedAt = System.currentTimeMillis()
                val classification = result.exceptionOrNull()?.let { SyncErrorClassifier.classify(it, classifiedAt) }
                record(buildJsonObject {
                    put("kind", "checkpoint_result"); put("pid", Process.myPid()); put("startMs", start)
                    put("startElapsedNs", mono); put("completedMs", classifiedAt); put("completedElapsedNs", SystemClock.elapsedRealtimeNanos())
                    put("attempt", if (armed) row?.attemptCount ?: -1 else 0); put("claim", claim?.claimID ?: "setup")
                    put("category", classification?.category?.name ?: "success")
                    put("status", classification?.httpStatus?.let(::JsonPrimitive) ?: JsonNull)
                    put("floor", classification?.retryAfterAtMs?.let(::JsonPrimitive) ?: JsonNull)
                    put("invalidRetryAfter", classification?.retryAfterInvalid ?: false)
                })
                return result
            }
        }
    }

    private fun healthyPayload(endpoint: String, params: JsonObject): String {
        if (endpoint == T05Contract.CHECKPOINT_RPC) return T05Contract.json.encodeToString(
            checkpointTemplate.copy(syncEvents = checkpointTemplate.syncEvents.copy(
                verifiedBaselineId = params.getValue("p_verified_baseline_id").jsonPrimitive.content)))
        if (endpoint == T05Contract.MARKER_RPC) {
            val verified = params.getValue("p_verified_baseline_id").jsonPrimitive.content
            check(verified == checkpointTemplate.syncEvents.maxId)
            check(params.getValue("p_expected_baseline_scope_key").jsonPrimitive.content == checkpointTemplate.scope.key)
            return T05Contract.json.encodeToString(ShopSyncConvergenceMarker(
                schemaVersion = "shop-sync-convergence-marker-v1", status = "ready", shopId = T05Contract.SHOP,
                scope = checkpointTemplate.scope,
                syncEvents = checkpointTemplate.syncEvents.copy(verifiedBaselineId = verified, requiresFullRecovery = false),
                catalog = checkpointTemplate.catalog, prices = checkpointTemplate.prices,
                history = checkpointTemplate.history, images = checkpointTemplate.images,
                integrity = ShopSyncMarkerIntegrity(0), checkpointDigest = checkpointTemplate.checkpointDigest,
                serverNoWorkEligible = true, markerDigest = "e".repeat(64)
            ))
        }
        check(endpoint == T05Contract.PAGE_RPC)
        check(params["p_after_id"] == JsonNull)
        check(params.getValue("p_expected_scope_key").jsonPrimitive.content == checkpointTemplate.scope.key)
        return buildJsonObject {
            put("schemaVersion", "shop-sync-recovery-page-v1"); put("shopId", T05Contract.SHOP)
            put("scope", T05Contract.json.encodeToJsonElement(checkpointTemplate.scope))
            put("domain", params.getValue("p_domain")); put("pageLimit", params.getValue("p_limit"))
            put("snapshotEventMaxId", params.getValue("p_expected_event_max_id"))
            put("currentScopeEventMaxId", params.getValue("p_expected_event_max_id"))
            put("baselineDomainEventMaxId", params.getValue("p_expected_domain_event_max_id"))
            put("pageDomainEventMaxId", params.getValue("p_expected_domain_event_max_id"))
            put("domainScope", ShopSyncScopeKinds.SHOP_SCOPED); put("rows", JsonArray(emptyList()))
            put("nextAfterId", JsonNull); put("hasMore", false)
        }.toString()
    }

    suspend fun seed() {
        check(!armed && db.productDao().count() == 0 && db.syncRecoveryBaselineDao().get() == null)
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = T05Contract.DEVICE, createdAtMs = System.currentTimeMillis()))
        val now = System.currentTimeMillis()
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = T05Contract.scope.ownerHash,
            storeScope = T05Contract.scope.storeId, shopId = T05Contract.SHOP, deviceId = T05Contract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = now, updatedAtMs = now, nextRetryAtMs = now))
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val outcome = recovery(initial, null).recover(T05Contract.OWNER, T05Contract.shop, T05Contract.scope)
        assertTrue(outcome.toString(), outcome is ShopSyncRecoveryResult.Activated)
        val setupMarkers = records().filter { it["kind"]?.jsonPrimitive?.content == "http" &&
            it["endpoint"]?.jsonPrimitive?.content == T05Contract.MARKER_RPC &&
            it["attempt"]?.jsonPrimitive?.int == 0 }
        assertEquals("Setup must traverse both actual SDK convergence markers", 2, setupMarkers.size)
        assertEquals(listOf(SyncRecoveryJournalPhases.STAGING, SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING),
            setupMarkers.map { it.getValue("journalPhase").jsonPrimitive.content })
        assertEquals(1, setupMarkers.map { it.getValue("generationId").jsonPrimitive.content }.distinct().size)
        assertTrue(setupMarkers.all { it.getValue("generationId").jsonPrimitive.content.isNotBlank() })
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
        val state = initial.resolveBusinessDataScope(T05Contract.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        val guard = CatalogSyncStateTracker(state)
        val writer = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = guard)
        writer.addProduct(Product(barcode = T05Contract.BARCODE, productName = "Before recovery"))
        writer.updateProduct(product().copy(productName = "Pending survives recovery"))
        assertTrue(ref().localChangeRevision > ref().lastSyncedLocalRevision)
        val intentAt = System.currentTimeMillis()
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = T05Contract.scope.ownerHash,
            storeScope = T05Contract.scope.storeId, shopId = T05Contract.SHOP, deviceId = T05Contract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "task144_native_mixed_retry",
            blockingEventId = null, attemptCount = 0, createdAtMs = intentAt, updatedAtMs = intentAt, nextRetryAtMs = null))
    }

    private fun recovery(repository: DefaultInventoryRepository, tracker: CatalogSyncStateTracker?) =
        ShopSyncRecoveryCoordinator(context, db, repository, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
            scopeStillValid = { owner, shop -> owner == T05Contract.OWNER && shop == T05Contract.SHOP },
            activationBoundary = { block -> if (tracker == null) block() else tracker.withBusinessDataScopeTransition { block() } })

    suspend fun attachApplication() {
        check(!::application.isInitialized)
        val state = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader).resolveBusinessDataScope(T05Contract.scope)
        assertEquals("sync_recovery_required", state.errorCode)
        tracker = CatalogSyncStateTracker(state).apply { updateNetworkAvailability(true) }
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
        application = T05OwnedApplication().apply { attachOwned(context) }
        // Reuse production scope; leave automaticBusinessRecoveryNowMs and retry jitter untouched.
        val appScope: CoroutineScope = t05Field(application, "appScope")
        val auth = SupabaseAuthManager(client = null, googleWebClientId = "", scope = appScope)
        t05Field<MutableStateFlow<AuthState>>(auth, "_state").value = AuthState.SignedIn(T05Contract.OWNER, "synthetic@example.test")
        val shops = ShopContextRepository(object : LinkedShopRemoteDataSource {
            override val isConfigured = true
            override suspend fun fetchLinkedShops() = Result.success(listOf(LinkedShop(T05Contract.SHOP, null,
                T05Contract.shop.name, T05Contract.shop.role, "active", selectable = true, canWrite = true)))
        }, object : SelectedShopStore {
            override fun getSelectedShopId(ownerUserId: String) = T05Contract.SHOP
            override fun setSelectedShopId(ownerUserId: String, shopId: String) = Unit
            override fun clearSelectedShopId(ownerUserId: String) = Unit
        }, currentOwnerUserId = { T05Contract.OWNER })
        shops.refresh(T05Contract.OWNER)
        val childScope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
        val catalog = CatalogAutoSyncCoordinator(repository, SupabaseCatalogRemoteDataSource(null), SupabaseProductPriceRemoteDataSource(null),
            authFlow = auth.state, selectedShopProvider = { T05Contract.shop }, syncStateTracker = tracker,
            scope = childScope, debounceMs = Long.MAX_VALUE)
        val sessions = HistorySessionPushCoordinator(repository, SupabaseSessionBackupRemoteDataSource(null),
            authFlow = auth.state, selectedShopProvider = { T05Contract.shop }, flightOwner = SessionCloudSessionFlightOwner(),
            syncStateTracker = tracker, scope = childScope, debounceMs = Long.MAX_VALUE)
        auto = catalog; history = sessions
        fun install(name: String, value: Any?) = MerchandiseControlApplication::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(application, value)
        install("database\$delegate", lazyOf(db)); install("repository\$delegate", lazyOf(repository))
        install("authManager\$delegate", lazyOf(auth)); install("shopContextRepository\$delegate", lazyOf(shops))
        install("catalogSyncStateTracker\$delegate", lazyOf(tracker)); install("supabaseClient\$delegate", lazyOf(null))
        install("shopSyncReadRemoteDataSource\$delegate", lazyOf(reader))
        install("catalogAutoSyncCoordinator\$delegate", lazyOf(catalog)); install("historySessionPushCoordinator\$delegate", lazyOf(sessions))
        install("syncEventRemoteDataSource\$delegate", lazyOf(SupabaseSyncEventRemoteDataSource(null)))
        install("shopDeviceRegistrationRemoteDataSource\$delegate", lazyOf(ShopDeviceRegistrationRemoteDataSource(null, DeviceInstallIdProvider(db.syncEventDeviceStateDao()))))
        install("shopSyncRecoveryCoordinator\$delegate", lazyOf(recovery(repository, tracker)))
        armed = true
    }

    suspend fun close() = withContext(NonCancellable) {
        if (::application.isInitialized) {
            recoveryJob()?.cancelAndJoin()
            t05Field<Job?>(application, "shopDeviceStatusPollingJob")?.cancelAndJoin()
            auto?.shutdown(); history?.shutdown()
            val realtime: Lazy<RealtimeRefreshCoordinator> = t05Field(application, "realtimeRefreshCoordinator\$delegate")
            if (realtime.isInitialized()) realtime.value.shutdown()
            t05Field<CoroutineScope>(application, "appScope").coroutineContext[Job]?.cancelAndJoin()
        }
        withTimeout(5_000) { sdk?.close() }
        db.close() // Preserve all files, including failures and interrupted writes.
    }
}

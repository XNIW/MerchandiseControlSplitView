package com.example.merchandisecontrolsplitview

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.os.SystemClock
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.data.BusinessDataScopeBinding
import com.example.merchandisecontrolsplitview.data.SyncEventDeviceState
import com.example.merchandisecontrolsplitview.data.SyncEventWatermark
import com.example.merchandisecontrolsplitview.data.SyncRecoveryBaseline
import com.example.merchandisecontrolsplitview.data.SyncRecoveryJournal
import com.example.merchandisecontrolsplitview.data.SyncRecoveryJournalPhases
import com.example.merchandisecontrolsplitview.data.SyncRecoveryAuthorizationModes
import com.example.merchandisecontrolsplitview.data.ShopSyncCheckpointTraceAttempt
import com.example.merchandisecontrolsplitview.data.ShopSyncRpcContext
import com.example.merchandisecontrolsplitview.data.CHECKPOINT_TRACE_VALUE
import com.example.merchandisecontrolsplitview.data.CHECKPOINT_TRACE_TIMEOUT_MS
import io.github.jan.supabase.auth.status.SessionStatus
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinator
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressState
import com.example.merchandisecontrolsplitview.data.CatalogSyncStage
import com.example.merchandisecontrolsplitview.data.CatalogSyncStateTracker
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.DeviceGuardedCatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.DeviceGuardedProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.DeviceGuardedSessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.DeviceGuardedSyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.DeviceInstallIdProvider
import com.example.merchandisecontrolsplitview.data.HistorySessionPushCoordinator
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.RealtimeRefreshCoordinator
import com.example.merchandisecontrolsplitview.data.SessionCloudSessionFlightOwner
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SharedPreferencesSelectedShopStore
import com.example.merchandisecontrolsplitview.data.ShopContextRepository
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationRepository
import com.example.merchandisecontrolsplitview.data.ShopDeviceRegistrationRemoteDataSource
import com.example.merchandisecontrolsplitview.data.ShopSyncReadRemoteDataSource
import com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinator
import com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryResult
import com.example.merchandisecontrolsplitview.data.SupabaseLinkedShopRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseCatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseStorefrontAuthoringRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseShopSyncReadRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseAuthManager
import com.example.merchandisecontrolsplitview.data.HttpWeChatAuthGateway
import com.example.merchandisecontrolsplitview.data.WeChatOpenSdkCodeProvider
import com.example.merchandisecontrolsplitview.data.SupabaseRealtimeSessionSubscriber
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeChangedException
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGate
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGateDecision
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import com.example.merchandisecontrolsplitview.data.parseLegacyBusinessDataScope
import com.example.merchandisecontrolsplitview.data.shopScopedStoreScope
import com.example.merchandisecontrolsplitview.data.task126ActiveOwnerStoreScope
import com.example.merchandisecontrolsplitview.productimage.ProductImageApiClient
import com.example.merchandisecontrolsplitview.productimage.ProductImageService
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.createDefaultSettingsKey
import io.github.jan.supabase.annotations.SupabaseInternal
import com.example.merchandisecontrolsplitview.data.GenerationOwnedSupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Application class singleton (task 010, task 011).
 *
 * Fornisce un owner unico per il repository, il [RealtimeRefreshCoordinator],
 * il [SupabaseAuthManager] e il [SupabaseRealtimeSessionSubscriber].
 * Garantisce un singolo repository, un singolo coordinator, un singolo auth manager
 * e un singolo subscriber per l'intera app.
 *
 * ## Aggancio lifecycle
 * Il [RealtimeRefreshCoordinator] è collegato al `ProcessLifecycleOwner`, quindi la
 * policy foreground-first è reale e non solo documentata: `onStart` abilita i drain,
 * `onStop` li sospende lasciando intatto il buffer per il resume.
 *
 * ## Adapter Supabase Realtime
 * [realtimeSessionSubscriber] possiede il canale Realtime e inoltra i payload
 * ricevuti al [RealtimeRefreshCoordinator]. Il subscriber resta separato dal repository:
 * il percorso dati rimane `Supabase event -> coordinator -> repository -> Room -> UI`.
 *
 * ## Auth Supabase (task 011)
 * [authManager] è l'owner unico del lifecycle auth. Espone [AuthState] come
 * source of truth per lo stato sessione. Se la configurazione è assente,
 * si auto-disabilita e l'app resta in puro offline-first.
 *
 * ## Wiring auth → componenti remoti (task 011 patch 5, task 012)
 * [observeAuthForRemoteComponents] è il punto architetturale unico dove i cambi
 * di stato auth controllano il lifecycle dei componenti remoti. Dopo il task 012
 * (RLS/ownership su `shared_sheet_sessions` con policy `auth.uid() = owner_user_id`)
 * il subscriber viene avviato solo in `SignedIn` e fermato in `SignedOut` /
 * `ErrorRecoverable`: il canale Realtime usa il JWT del client Supabase condiviso.
 */
class MerchandiseControlApplication : Application() {

    companion object {
        private const val TAG = "MerchandiseApp"
        private const val SHOP_DATA_SCOPE_PREFS = "mobile_shop_context_data_scope"
        private const val KEY_LAST_BUSINESS_DATA_SCOPE = "last_business_data_scope"
        // Exact UTF-8 TEST URL digest from the authorized target projection; no normalization.
        private const val CHECKPOINT_TRACE_TEST_URL_SHA256 = "42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842"
    }

    /** Scope applicativo per osservatori lifecycle (auth → componenti remoti). */
    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val validatedNetworks = mutableSetOf<Network>()
    private val networkLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val shopContextRecoveryLock = Any()
    private var shopContextRecoveryJob: Job? = null
    private var lastShopDeviceRegistrationScope: String? = null
    private var lastShopDeviceRegistrationAtMs: Long = 0L
    private var shopDeviceStatusPollingJob: Job? = null
    private val businessDataScopeMutex = Mutex()
    private val businessRecoveryLock = Any()
    private val businessRecoveryExecutionMutex = Mutex()
    private var businessRecoveryJob: Job? = null
    // Diagnostic-only state: unused, capturing, consumed. Never coordinates business work.
    private val localThreadSnapshotState = AtomicInteger(0)
    private var checkpointTraceConsumed = false
    private var checkpointTraceReservation: Any? = null

    private val shopDataScopePreferences by lazy {
        getSharedPreferences(SHOP_DATA_SCOPE_PREFS, Context.MODE_PRIVATE)
    }

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            realtimeRefreshCoordinator.onAppForeground()
            historySessionPushCoordinator.onAppForeground()
            catalogAutoSyncCoordinator.onAppForeground()
            retryShopContextIfNeeded("foreground")
            schedulePendingBusinessRecovery("foreground")
            registerShopDeviceBestEffort(authManager.state.value, "foreground")
            startShopDeviceStatusPolling()
        }

        override fun onStop(owner: LifecycleOwner) {
            realtimeRefreshCoordinator.onAppBackground()
            historySessionPushCoordinator.onAppBackground()
            catalogAutoSyncCoordinator.onAppBackground()
            stopShopDeviceStatusPolling()
        }
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val repository: DefaultInventoryRepository by lazy {
        DefaultInventoryRepository(
            db = database,
            businessDataScopeRuntimeGuard = catalogSyncStateTracker,
            shopSyncReadRemoteDataSource = shopSyncReadRemoteDataSource
        )
    }

    val realtimeRefreshCoordinator: RealtimeRefreshCoordinator by lazy {
        RealtimeRefreshCoordinator(
            repository = repository,
            sessionFlightOwner = sessionCloudSessionFlightOwner,
            businessDataScopeAllowed = { currentBusinessDataScopeAllowsSync() },
            businessDataScopeRuntimeGuard = catalogSyncStateTracker,
            logger = { message -> Log.i("RealtimeCoordinator", message) }
        )
    }

    /**
     * Signal condiviso "sync cloud in corso": aggiornato dal `CatalogSyncViewModel`
     * (refresh manuale + bootstrap automatico sessioni); letto dalla UI root
     * per mostrare l'icona sync in alto a destra (nessuna nuova orchestrazione).
     */
    val catalogSyncStateTracker: CatalogSyncStateTracker by lazy {
        CatalogSyncStateTracker(Task126BusinessDataScopeState.checking())
    }

    val sessionCloudSessionFlightOwner: SessionCloudSessionFlightOwner by lazy {
        SessionCloudSessionFlightOwner(
            logger = { message -> Log.i("HistorySessionSyncV2", message) }
        )
    }

    @OptIn(SupabaseInternal::class)
    val supabaseClient: SupabaseClient? by lazy {
        val configPresent = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
        if (configPresent) {
            try {
                val settings = com.example.merchandisecontrolsplitview.data.CheckedAuthSettings(
                    applicationContext.getSharedPreferences(
                        "${applicationContext.packageName}_preferences",
                        Context.MODE_PRIVATE
                    )
                )
                val settingsKey = createDefaultSettingsKey(BuildConfig.SUPABASE_URL.split("//").last())
                val sessionKey = "$settingsKey-${SettingsSessionManager.SETTINGS_KEY}"
                val verifierKey = "$settingsKey-${SettingsCodeVerifierCache.SETTINGS_KEY}"
                // Instantiate SDK migration once; every client receives a generation-bound lease.
                val sessionManager = com.example.merchandisecontrolsplitview.data.ProjectSessionPersistence(
                    settings = settings,
                    sessionKey = sessionKey,
                    projectUrl = BuildConfig.SUPABASE_URL
                )
                val verifierCache = SettingsCodeVerifierCache(settings = settings, key = verifierKey)
                GenerationOwnedSupabaseClient(
                    sessionManager = sessionManager,
                    codeVerifierCache = verifierCache,
                    isSessionStored = { sessionManager.isSessionStored() },
                    factory = { sessionLease, verifierLease ->
                        createSupabaseClient(
                            supabaseUrl = BuildConfig.SUPABASE_URL,
                            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
                        ) {
                            requestTimeout = 90.seconds
                            install(Auth) {
                                this.sessionManager = sessionLease
                                codeVerifierCache = verifierLease
                            }
                            install(Postgrest)
                            install(Realtime) {
                                reconnectDelay = 5.seconds
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Creazione client Supabase fallita", e)
                null
            }
        } else null
    }

    val authManager: SupabaseAuthManager by lazy {
        SupabaseAuthManager(
            client = supabaseClient,
            googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            wechatCodeProvider = if (BuildConfig.WECHAT_AUTH_ANDROID_ENABLED) {
                WeChatOpenSdkCodeProvider(this, BuildConfig.WECHAT_ANDROID_APP_ID)
            } else {
                null
            },
            wechatGateway = if (BuildConfig.WECHAT_AUTH_ANDROID_ENABLED) {
                HttpWeChatAuthGateway(BuildConfig.WECHAT_AUTH_GATEWAY_BASE_URL)
            } else {
                null
            },
            wechatDeviceIdProvider = if (BuildConfig.WECHAT_AUTH_ANDROID_ENABLED) {
                { deviceInstallIdProvider.getOrCreate() }
            } else {
                null
            }
        )
    }

    private val productImageServiceDelegate = lazy {
        ProductImageService(
            context = this,
            database = database,
            api = ProductImageApiClient(
                apiBaseUrl = BuildConfig.PRODUCT_IMAGE_API_BASE_URL,
                storageBaseUrl = BuildConfig.SUPABASE_URL,
                debugBuild = BuildConfig.DEBUG
            ),
            accountIdProvider = {
                (authManager.state.value as? AuthState.SignedIn)?.userId
            },
            selectedShopProvider = { shopContextRepository.state.value.selectedShop },
            accessTokenProvider = { supabaseClient?.auth?.currentAccessTokenOrNull() },
            businessDataScopeAllowed = { accountId, shop ->
                catalogSyncStateTracker.allowsBusinessDataScope(accountId, shop)
            },
            businessDataScopeRuntimeGuard = catalogSyncStateTracker,
            allowBoundCacheRead = {
                val signedIn = authManager.state.value as? AuthState.SignedIn
                val context = shopContextRepository.state.value
                signedIn != null &&
                    (
                        context.ownerUserId != signedIn.userId ||
                            context.isLoading ||
                            !context.syncAllowed
                        )
            }
        )
    }
    val productImageService: ProductImageService by productImageServiceDelegate

    val realtimeSessionSubscriber: SupabaseRealtimeSessionSubscriber by lazy {
        SupabaseRealtimeSessionSubscriber(
            client = supabaseClient,
            coordinator = realtimeRefreshCoordinator,
            businessDataScopeRuntimeGuard = catalogSyncStateTracker
        )
    }

    /** Transport PostgREST catalogo (task 013); null client → [CatalogRemoteDataSource.isConfigured] falso. */
    private val rawCatalogRemoteDataSource: CatalogRemoteDataSource by lazy {
        SupabaseCatalogRemoteDataSource(supabaseClient)
    }

    val catalogRemoteDataSource: CatalogRemoteDataSource by lazy {
        DeviceGuardedCatalogRemoteDataSource(
            delegate = rawCatalogRemoteDataSource,
            authorization = shopDeviceAuthorizationRepository
        )
    }

    /** Boundary Storefront condiviso con Admin; usa la sessione Supabase corrente. */
    val storefrontAuthoringRemoteDataSource by lazy {
        SupabaseStorefrontAuthoringRemoteDataSource(supabaseClient)
    }

    /** Transport PostgREST storico prezzi (task 016). */
    private val rawProductPriceRemoteDataSource: ProductPriceRemoteDataSource by lazy {
        SupabaseProductPriceRemoteDataSource(supabaseClient)
    }

    val productPriceRemoteDataSource: ProductPriceRemoteDataSource by lazy {
        DeviceGuardedProductPriceRemoteDataSource(
            delegate = rawProductPriceRemoteDataSource,
            authorization = shopDeviceAuthorizationRepository
        )
    }

    /** Transport PostgREST/RPC per `sync_events` (task 045). */
    private val rawSyncEventRemoteDataSource: SyncEventRemoteDataSource by lazy {
        SupabaseSyncEventRemoteDataSource(supabaseClient)
    }

    val syncEventRemoteDataSource: SyncEventRemoteDataSource by lazy {
        DeviceGuardedSyncEventRemoteDataSource(
            delegate = rawSyncEventRemoteDataSource,
            authorization = shopDeviceAuthorizationRepository
        )
    }

    val shopSyncReadRemoteDataSource: ShopSyncReadRemoteDataSource by lazy {
        SupabaseShopSyncReadRemoteDataSource(supabaseClient)
    }

    private val shopSyncRecoveryCoordinator: ShopSyncRecoveryCoordinator by lazy {
        ShopSyncRecoveryCoordinator(
            context = this,
            activeDb = database,
            activeRepository = repository,
            remote = shopSyncReadRemoteDataSource,
            registerDeviceForRecovery = { shopId ->
                shopDeviceRegistrationRemoteDataSource.registerShopDeviceForShop(
                    shopId = shopId,
                    reason = "mismatch_recovery"
                )
            },
            scopeStillValid = { accountId, shopId ->
                val context = shopContextRepository.state.value
                currentAuthAndShopMatch(accountId, context.selectedShop) &&
                    context.activeShopId?.lowercase() == shopId.lowercase()
            },
            activationBoundary = { block ->
                businessDataScopeMutex.withLock {
                    catalogSyncStateTracker.withBusinessDataScopeTransition { block() }
                }
            },
            onActivated = { accountId, shopId ->
                if (productImageServiceDelegate.isInitialized()) {
                    // The recovery coordinator invokes this inside the same
                    // owner/shop activation boundary that committed the new
                    // generation. Never evict another account or shop while
                    // auth/shop state may be changing.
                    productImageService.purgeScope(accountId, shopId)
                }
            },
            logger = { message -> Log.i(TAG, message) }
        )
    }

    val deviceInstallIdProvider: DeviceInstallIdProvider by lazy {
        DeviceInstallIdProvider(database.syncEventDeviceStateDao())
    }

    val shopDeviceRegistrationRemoteDataSource: ShopDeviceRegistrationRemoteDataSource by lazy {
        ShopDeviceRegistrationRemoteDataSource(
            client = supabaseClient,
            installIdProvider = deviceInstallIdProvider
        )
    }

    val shopDeviceAuthorizationRepository: ShopDeviceAuthorizationRepository by lazy {
        ShopDeviceAuthorizationRepository(
            remote = shopDeviceRegistrationRemoteDataSource,
            businessDataScopeRuntimeGuard = catalogSyncStateTracker,
            onConfirmedAuthorization = { shopId, snapshot ->
                val owner = (authManager.state.value as? AuthState.SignedIn)?.userId
                val device = snapshot.deviceIdentifier
                if (owner != null && device != null) {
                    shopContextRepository.recordDeviceAuthorization(owner, shopId, device, snapshot.status, snapshot.canWrite)
                    val context = shopContextRepository.state.value
                    if (context.ownerUserId == owner && context.activeShopId == shopId && !context.localAccessAllowed) {
                        val previous = catalogSyncStateTracker.businessDataScopeState.value
                        catalogSyncStateTracker.updateBusinessDataScopeState(previous.copy(
                            localReadsAllowed = false, localWritesAllowed = false))
                    }
                }
            }
        )
    }

    val shopContextRepository: ShopContextRepository by lazy {
        ShopContextRepository(
            remote = SupabaseLinkedShopRemoteDataSource(supabaseClient),
            selectedShopStore = SharedPreferencesSelectedShopStore(
                getSharedPreferences("mobile_shop_context", Context.MODE_PRIVATE)
            ),
            currentOwnerUserId = {
                (authManager.state.value as? AuthState.SignedIn)?.userId
            },
            currentDeviceIdentifier = { deviceInstallIdProvider.currentId }
        )
    }

    /** Transport PostgREST backup sessioni history / `shared_sheet_sessions` (task 023). */
    private val rawSessionBackupRemoteDataSource: SessionBackupRemoteDataSource by lazy {
        SupabaseSessionBackupRemoteDataSource(supabaseClient)
    }

    val sessionBackupRemoteDataSource: SessionBackupRemoteDataSource by lazy {
        DeviceGuardedSessionBackupRemoteDataSource(
            delegate = rawSessionBackupRemoteDataSource,
            authorization = shopDeviceAuthorizationRepository
        )
    }

    val historySessionPushCoordinator: HistorySessionPushCoordinator by lazy {
        HistorySessionPushCoordinator(
            repository = repository,
            remote = sessionBackupRemoteDataSource,
            syncEventRemote = syncEventRemoteDataSource,
            syncEventOutboxDao = database.syncEventOutboxDao(),
            deviceAuthorization = shopDeviceAuthorizationRepository,
            authFlow = authManager.state,
            selectedShopProvider = { shopContextRepository.state.value.selectedShop },
            flightOwner = sessionCloudSessionFlightOwner,
            syncStateTracker = catalogSyncStateTracker,
            logger = { message -> Log.i("HistorySessionSyncV2", message) }
        ).also { coordinator ->
            repository.onHistorySessionPayloadChanged = { uid ->
                coordinator.onLocalHistorySessionChanged(uid)
            }
        }
    }

    val catalogAutoSyncCoordinator: CatalogAutoSyncCoordinator by lazy {
        CatalogAutoSyncCoordinator(
            repository = repository,
            remote = catalogRemoteDataSource,
            priceRemote = productPriceRemoteDataSource,
            syncEventRemote = syncEventRemoteDataSource,
            sessionRemote = sessionBackupRemoteDataSource,
            deviceAuthorization = shopDeviceAuthorizationRepository,
            authFlow = authManager.state,
            selectedShopProvider = { shopContextRepository.state.value.selectedShop },
            syncStateTracker = catalogSyncStateTracker,
            onRecoveryRequired = { source ->
                schedulePendingBusinessRecovery(source)
            },
            logger = { message -> Log.i("CatalogCloudSync", message) }
        ).also { coordinator ->
            repository.onProductCatalogChanged = { productId ->
                coordinator.onLocalProductChanged(productId)
            }
            repository.onCatalogChanged = {
                coordinator.onLocalCatalogChanged()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Eager init: il coordinator deve essere realmente vivo a livello processo.
        realtimeRefreshCoordinator
        historySessionPushCoordinator
        catalogAutoSyncCoordinator
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        registerNetworkAutoSyncTrigger()
        // Auth bootstrap: restore sessione se presente, altrimenti SignedOut (task 011).
        authManager.restoreSession()
        // Subscriber lifecycle gestito dall'auth observer (task 011 patch 5).
        // Punto unico architetturale per il wiring auth → componenti remoti.
        observeAuthForRemoteComponents()
        observeShopContextForRemoteComponents()
    }

    override fun onTerminate() {
        appScope.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        realtimeSessionSubscriber.shutdown()
        unregisterNetworkAutoSyncTrigger()
        realtimeRefreshCoordinator.shutdown()
        historySessionPushCoordinator.shutdown()
        catalogAutoSyncCoordinator.shutdown()
        cancelBusinessRecovery()
        if (productImageServiceDelegate.isInitialized()) productImageService.close()
        authManager.shutdown()
        super.onTerminate()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (productImageServiceDelegate.isInitialized()) productImageService.trimMemory()
    }

    // --- Wiring auth → componenti remoti (task 011, patch 5) ---

    /**
     * Punto unico di riallineamento componenti remoti al cambio stato auth.
     *
     * Osserva [authManager].[state][SupabaseAuthManager.state] e controlla il
     * lifecycle del subscriber Realtime in funzione della sessione:
     * - `SignedIn` → `start()` del subscriber (il canale Realtime usa il JWT
     *   del client condiviso, coerente con la policy RLS `auth.uid() = owner_user_id`
     *   introdotta in task 012);
     * - `SignedOut` / `ErrorRecoverable` → `stop()` prudenziale per evitare che
     *   un socket Realtime orfano resti attivo senza sessione valida;
     * - `Checking` → no-op (stato transitorio durante bootstrap/sign-in).
     */
    private fun observeAuthForRemoteComponents() {
        appScope.launch {
            authManager.state.collect { state ->
                when (state) {
                    is AuthState.Checking -> {
                        cancelShopContextRecovery()
                        cancelBusinessRecovery()
                        Log.d(TAG, "Auth: verifica sessione in corso")
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        suspendRemoteComponentsForBusinessScope()
                    }
                    is AuthState.SignedIn -> {
                        cancelShopContextRecovery()
                        Log.i(TAG, "Auth: sessione attiva")
                        val previous = catalogSyncStateTracker.businessDataScopeState.value
                        val preservedScope = previous.boundScope?.takeIf {
                            previous.allowsLocalOperations && it.ownerHash == com.example.merchandisecontrolsplitview.data.task126OwnerHash(state.userId)
                        }
                        catalogSyncStateTracker.updateBusinessDataScopeState(previous.copy(
                            status = Task126BusinessDataScopeStatus.CHECKING,
                            localAccessScope = preservedScope))
                        suspendRemoteComponentsForBusinessScope()
                        withContext(Dispatchers.IO) {
                            deviceInstallIdProvider.getOrCreate()
                            shopContextRepository.restoreLocalAuthorization(state.userId)
                        }
                        publishOfflineBusinessScope(shopContextRepository.state.value)
                        withContext(Dispatchers.IO) {
                            shopContextRepository.refresh(state.userId)
                        }
                        val refreshedContext = shopContextRepository.state.value
                        val refreshedAuth = authManager.state.value as? AuthState.SignedIn
                        if (
                            refreshedAuth?.userId != state.userId ||
                            refreshedContext.ownerUserId != state.userId
                        ) {
                            Log.i(TAG, "Shop context: risposta ignorata dopo cambio account")
                            suspendRemoteComponentsForBusinessScope()
                            return@collect
                        }
                        if (!currentShopContextAllowsSync(state.userId)) {
                            Log.w(TAG, "Shop context: sync cloud sospesa per errore linked-shops")
                            publishOfflineBusinessScope(refreshedContext)
                            suspendRemoteComponentsForBusinessScope()
                            return@collect
                        }
                        if (!alignBusinessDataScope(state.userId, refreshedContext.selectedShop)) {
                            suspendRemoteComponentsForBusinessScope()
                            return@collect
                        }
                        activateRemoteComponentsForBoundScope(state, "auth")
                    }
                    is AuthState.SignedOut -> {
                        cancelShopContextRecovery()
                        cancelBusinessRecovery()
                        Log.i(TAG, "Auth: nessuna sessione, fermo realtime")
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        suspendRemoteComponentsForBusinessScope()
                        shopContextRepository.clear()
                        val localState = try { repository.resolveSignedOutBusinessDataScope() }
                            catch (_: Exception) { Task126BusinessDataScopeState.checking() }
                        if (authManager.state.value is AuthState.SignedOut) {
                            catalogSyncStateTracker.updateBusinessDataScopeState(localState)
                        }
                    }
                    is AuthState.ErrorRecoverable -> {
                        cancelShopContextRecovery()
                        cancelBusinessRecovery()
                        Log.w(TAG, "Auth: errore recuperabile, fermo realtime prudenzialmente")
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState(
                                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                                errorCode = "auth_recoverable"
                            )
                        )
                        suspendRemoteComponentsForBusinessScope()
                        shopContextRepository.clear()
                    }
                }
            }
        }
    }

    private fun observeShopContextForRemoteComponents() {
        appScope.launch {
            shopContextRepository.state.collect { context ->
                val signedIn = authManager.state.value as? AuthState.SignedIn ?: return@collect
                if (context.ownerUserId != signedIn.userId) {
                    Log.i(TAG, "Shop context: owner non corrente, sync cloud sospesa")
                    catalogSyncStateTracker.updateBusinessDataScopeState(
                        Task126BusinessDataScopeState.checking()
                    )
                    suspendRemoteComponentsForBusinessScope()
                    return@collect
                }
                if (context.isLoading || !context.syncAllowed) {
                    publishOfflineBusinessScope(context)
                    suspendRemoteComponentsForBusinessScope()
                    return@collect
                }
                if (!alignBusinessDataScope(signedIn.userId, context.selectedShop)) {
                    suspendRemoteComponentsForBusinessScope()
                    return@collect
                }
                activateRemoteComponentsForBoundScope(signedIn, "shop_context")
            }
        }
    }

    private suspend fun publishOfflineBusinessScope(context: com.example.merchandisecontrolsplitview.data.ShopContext) {
        businessDataScopeMutex.withLock {
            val signedIn = authManager.state.value as? AuthState.SignedIn ?: return@withLock
            if (shopContextRepository.state.value != context || context.ownerUserId != signedIn.userId) return@withLock
            val previous = catalogSyncStateTracker.businessDataScopeState.value
            if (!context.localAccessAllowed) {
                catalogSyncStateTracker.updateBusinessDataScopeState(previous.copy(
                    status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, localAccessScope = null,
                    errorCode = "shop_context_unavailable", localWritesAllowed = false, localReadsAllowed = false))
                return@withLock
            }
            val activeScope = task126ActiveOwnerStoreScope(signedIn.userId, context.selectedShop)
            val resolved = repository.resolveOfflineBusinessDataScope(activeScope, context.selectedShop?.canWrite != false)
            if (authManager.state.value == signedIn && shopContextRepository.state.value == context) {
                catalogSyncStateTracker.updateBusinessDataScopeState(resolved)
            }
        }
    }

    private suspend fun alignBusinessDataScope(
        ownerUserId: String,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop?
    ): Boolean = businessDataScopeMutex.withLock {
        val resolveScope: suspend () -> Boolean = resolveScope@ {
            if (!currentAuthAndShopMatch(ownerUserId, selectedShop)) {
                catalogSyncStateTracker.updateBusinessDataScopeState(
                    Task126BusinessDataScopeState.checking()
                )
                return@resolveScope false
            }
            val activeScope = task126ActiveOwnerStoreScope(ownerUserId, selectedShop)
            val legacyValue = shopDataScopePreferences.getString(KEY_LAST_BUSINESS_DATA_SCOPE, null)
            val legacyScope = parseLegacyBusinessDataScope(legacyValue)
            val state = try {
                withContext(Dispatchers.IO) {
                    repository.resolveBusinessDataScope(activeScope, legacyScope)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Business scope: risoluzione binding fallita", error)
                Task126BusinessDataScopeState(
                    status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    errorCode = "binding_resolution_failed"
                )
            }
            if (!currentAuthAndShopMatch(ownerUserId, selectedShop)) {
                catalogSyncStateTracker.updateBusinessDataScopeState(
                    Task126BusinessDataScopeState.checking()
                )
                return@resolveScope false
            }
            catalogSyncStateTracker.updateBusinessDataScopeState(state.copy(localWritesAllowed = selectedShop?.canWrite != false && shopContextRepository.state.value.localAccessAllowed,
                localReadsAllowed = shopContextRepository.state.value.localAccessAllowed))
            if (legacyValue != null && legacyScope != null && state.boundScope != null) {
                val removed = shopDataScopePreferences.edit()
                    .remove(KEY_LAST_BUSINESS_DATA_SCOPE)
                    .commit()
                if (!removed) {
                    Log.w(TAG, "Business scope: cleanup binding legacy non riuscito")
                }
            }
            Log.i(TAG, "Business scope: decision=${state.status}")
            if (state.errorCode == "sync_recovery_required") {
                schedulePendingBusinessRecovery("scope_align")
            }
            allowsResolvedBusinessDataScope(state, activeScope)
        }
        val previous = catalogSyncStateTracker.businessDataScopeState.value
        val active = task126ActiveOwnerStoreScope(ownerUserId, selectedShop)
        if (previous.allowsLocalOperations && previous.boundScope?.let {
            Task126OwnerStoreGate.validate(it, active) == Task126OwnerStoreGateDecision.Allowed
        } == true) resolveScope()
        else catalogSyncStateTracker.withBusinessDataScopeTransition { resolveScope() }
    }

    private suspend fun activateRemoteComponentsForBoundScope(
        signedIn: AuthState.SignedIn,
        reason: String
    ) {
        val currentAuth = authManager.state.value as? AuthState.SignedIn ?: return
        val context = shopContextRepository.state.value
        if (
            currentAuth.userId != signedIn.userId ||
            context.ownerUserId != signedIn.userId ||
            !currentBusinessDataScopeAllowsSync()
        ) return
        registerShopDeviceBestEffort(signedIn, reason)
        startShopDeviceStatusPolling()
        realtimeSessionSubscriber.start(
            ownerUserId = signedIn.userId,
            shopId = context.activeShopId
        )
        val capabilities = try {
            catalogSyncStateTracker.withBusinessDataScopeFlight(
                ownerUserId = signedIn.userId,
                selectedShop = context.selectedShop
            ) {
                syncEventRemoteDataSource.checkCapabilities(signedIn.userId).getOrNull()
            }
        } catch (_: Task126BusinessDataScopeChangedException) {
            return
        }
        Log.i(
            TAG,
            "sync_events read boundary=shop_sync_event_page_v1 " +
                "realtime=false boundedForegroundPoll=true capabilities=${capabilities != null}"
        )
        catalogAutoSyncCoordinator.onShopContextChanged()
        historySessionPushCoordinator.onShopContextChanged()
    }

    private fun suspendRemoteComponentsForBusinessScope() {
        realtimeSessionSubscriber.stop()
        realtimeRefreshCoordinator.clearPendingForBusinessScopeChange()
        stopShopDeviceStatusPolling()
    }

    fun discardUnboundLocalBusinessDataAndBind() {
        appScope.launch {
            val activation = businessDataScopeMutex.withLock {
                if (
                    catalogSyncStateTracker.businessDataScopeState.value.status !=
                    Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND
                ) {
                    return@withLock null
                }
                val signedIn = authManager.state.value as? AuthState.SignedIn ?: return@withLock null
                val context = shopContextRepository.state.value
                if (
                    context.ownerUserId != signedIn.userId ||
                    context.isLoading ||
                    !context.syncAllowed
                ) return@withLock null
                catalogSyncStateTracker.withBusinessDataScopeTransition {
                    suspendRemoteComponentsForBusinessScope()
                    if (
                        catalogSyncStateTracker.businessDataScopeState.value.status !=
                        Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND ||
                        !currentAuthAndShopMatch(signedIn.userId, context.selectedShop)
                    ) {
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        return@withBusinessDataScopeTransition null
                    }
                    val activeScope = task126ActiveOwnerStoreScope(signedIn.userId, context.selectedShop)
                    val state = try {
                        withContext(Dispatchers.IO) {
                            repository.discardUnboundBusinessDataAndBind(activeScope)
                        }
                    } catch (error: Throwable) {
                        Log.w(TAG, "Business scope: scarto unbound fallito con rollback Room", error)
                        Task126BusinessDataScopeState(
                            status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                            errorCode = "binding_discard_failed"
                        )
                    }
                    if (!currentAuthAndShopMatch(signedIn.userId, context.selectedShop)) {
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        return@withBusinessDataScopeTransition null
                    }
                    catalogSyncStateTracker.updateBusinessDataScopeState(state)
                    signedIn.takeIf { state.status == Task126BusinessDataScopeStatus.READY }
                }
            }
            activation?.let { activateRemoteComponentsForBoundScope(it, "unbound_discard_confirmed") }
        }
    }

    fun replaceMismatchedLocalBusinessDataAndBind() {
        appScope.launch {
            val recoveryRequested = businessDataScopeMutex.withLock {
                val status = catalogSyncStateTracker.businessDataScopeState.value.status
                if (
                    status != Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH &&
                    status != Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH
                ) {
                    return@withLock false
                }
                val signedIn = authManager.state.value as? AuthState.SignedIn ?: return@withLock false
                val context = shopContextRepository.state.value
                if (
                    context.ownerUserId != signedIn.userId ||
                    context.isLoading ||
                    !context.syncAllowed ||
                    context.selectedShop == null
                ) return@withLock false
                catalogSyncStateTracker.withBusinessDataScopeTransition {
                    suspendRemoteComponentsForBusinessScope()
                    val currentStatus = catalogSyncStateTracker.businessDataScopeState.value.status
                    if (
                        (
                            currentStatus != Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH &&
                                currentStatus != Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH
                            ) ||
                        !currentAuthAndShopMatch(signedIn.userId, context.selectedShop)
                    ) {
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        return@withBusinessDataScopeTransition false
                    }
                    val activeScope = task126ActiveOwnerStoreScope(signedIn.userId, context.selectedShop)
                    val state = try {
                        withContext(Dispatchers.IO) {
                            repository.replaceMismatchedBusinessDataAndBind(activeScope)
                        }
                    } catch (error: Throwable) {
                        Log.w(TAG, "Business scope: sostituzione mismatch fallita con rollback Room", error)
                        Task126BusinessDataScopeState(
                            status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                            errorCode = "binding_replace_failed"
                        )
                    }
                    if (!currentAuthAndShopMatch(signedIn.userId, context.selectedShop)) {
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        return@withBusinessDataScopeTransition false
                    }
                    catalogSyncStateTracker.updateBusinessDataScopeState(state)
                    state.errorCode == "sync_recovery_required"
                }
            }
            if (recoveryRequested) {
                schedulePendingBusinessRecovery("mismatch_replace_confirmed")
            }
        }
    }

    private fun registerNetworkAutoSyncTrigger() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        fun hasValidatedInternet(capabilities: NetworkCapabilities?): Boolean =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        fun publishNetworkAvailability() {
            val isOnline = synchronized(networkLock) { validatedNetworks.isNotEmpty() }
            catalogSyncStateTracker.updateNetworkAvailability(isOnline)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val validated = hasValidatedInternet(networkCapabilities)
                val becameOnline = synchronized(networkLock) {
                    val wasOffline = validatedNetworks.isEmpty()
                    if (validated) {
                        validatedNetworks.add(network)
                    } else {
                        validatedNetworks.remove(network)
                    }
                    validated && wasOffline
                }
                publishNetworkAvailability()
                if (becameOnline) {
                    Log.i(TAG, "Network: internet validato disponibile, pianifico sync cloud pending")
                    if (retryShopContextIfNeeded("network")) return
                    if (schedulePendingBusinessRecovery("network")) return
                    if (!currentBusinessDataScopeAllowsSync()) return
                    registerShopDeviceBestEffort(authManager.state.value, "network")
                    catalogAutoSyncCoordinator.onNetworkAvailable()
                    historySessionPushCoordinator.onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                synchronized(networkLock) {
                    validatedNetworks.remove(network)
                }
                publishNetworkAvailability()
            }

            override fun onUnavailable() {
                synchronized(networkLock) {
                    validatedNetworks.clear()
                }
                publishNetworkAvailability()
            }
        }
        runCatching {
            val activeNetwork = connectivityManager.activeNetwork
            val activeCapabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
            synchronized(networkLock) {
                validatedNetworks.clear()
                if (activeNetwork != null && hasValidatedInternet(activeCapabilities)) {
                    validatedNetworks.add(activeNetwork)
                }
            }
            publishNetworkAvailability()
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { throwable ->
            Log.w(TAG, "Network: registrazione callback auto-sync fallita", throwable)
        }
    }

    private fun retryShopContextIfNeeded(reason: String): Boolean {
        val auth = authManager.state.value
        val context = shopContextRepository.state.value
        if (!shouldRetryShopContext(auth, context, catalogSyncStateTracker.networkAvailable.value)) {
            return false
        }
        val signedIn = auth as AuthState.SignedIn
        val recoveryJob = synchronized(shopContextRecoveryLock) {
            if (shopContextRecoveryJob?.isActive == true) return true
            lateinit var scheduled: Job
            scheduled = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    Log.i(TAG, "Shop context: retry automatico reason=$reason")
                    withContext(Dispatchers.IO) {
                        shopContextRepository.refresh(signedIn.userId)
                    }
                } finally {
                    synchronized(shopContextRecoveryLock) {
                        if (shopContextRecoveryJob === scheduled) {
                            shopContextRecoveryJob = null
                        }
                    }
                }
            }
            shopContextRecoveryJob = scheduled
            scheduled
        }
        recoveryJob.start()
        return true
    }

    internal data class CheckpointTraceLocalSnapshot(
        val device: SyncEventDeviceState?,
        val binding: BusinessDataScopeBinding?,
        val baseline: SyncRecoveryBaseline?,
        val journal: SyncRecoveryJournal?,
        val watermark: SyncEventWatermark?
    ) {
        internal fun allowsCheckpointScope(activeScope: Task126OwnerStoreScope, shopId: String): Boolean {
            val currentJournal = journal ?: return false
            val currentDevice = device ?: return false
            if (currentJournal.ownerHash != activeScope.ownerHash ||
                currentJournal.storeScope != activeScope.storeId ||
                currentJournal.shopId?.lowercase() != shopId.lowercase() ||
                currentDevice.deviceId.isBlank() || currentJournal.deviceId != currentDevice.deviceId ||
                currentJournal.phase != SyncRecoveryJournalPhases.REQUIRED ||
                currentJournal.authorizationMode !in setOf(
                    SyncRecoveryAuthorizationModes.SAME_SCOPE,
                    SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED
                )) return false
            if (currentJournal.authorizationMode == SyncRecoveryAuthorizationModes.SAME_SCOPE &&
                (binding == null || Task126OwnerStoreGate.validate(
                    binding.toOwnerStoreScope(), activeScope
                ) != Task126OwnerStoreGateDecision.Allowed)) return false
            return currentJournal.authorizationMode != SyncRecoveryAuthorizationModes.SAME_SCOPE ||
                baseline?.let {
                    it.ownerHash == activeScope.ownerHash && it.storeScope == activeScope.storeId &&
                        it.shopId.lowercase() == shopId.lowercase() && it.deviceId == currentDevice.deviceId
                } != false
        }
    }

    internal suspend fun checkpointTraceSnapshot(owner: String, store: String) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                CheckpointTraceLocalSnapshot(
                    database.syncEventDeviceStateDao().get(),
                    database.businessDataScopeBindingDao().get(),
                    database.syncRecoveryBaselineDao().get(),
                    database.syncRecoveryJournalDao().get(),
                    database.syncEventWatermarkDao().get(owner, store)
                )
            }
        }

    @OptIn(SupabaseInternal::class)
    internal fun checkpointTraceAuthBudgetCurrent(client: SupabaseClient,
        status: SessionStatus.Authenticated, safeEnd: kotlin.time.Instant): Boolean {
        val session = status.session
        if (session.expiresIn <= 0L) return false
        val scheduled = client.auth.autoRefreshInformation()?.refreshingAt ?: return false
        return client.auth.sessionStatus.value === status &&
            checkpointTraceAuthWindowSafe(status, scheduled, safeEnd)
    }

    internal fun checkpointTraceOwnedAuthCurrent(
        owner: GenerationOwnedSupabaseClient,
        sdk: SupabaseClient,
        expectedAppAuth: AuthState.SignedIn,
        currentAppAuth: AuthState,
        sdkStatus: SessionStatus.Authenticated,
        safeEnd: kotlin.time.Instant
    ): Boolean = owner.captureClientOrNull() === sdk && currentAppAuth === expectedAppAuth &&
        sdk.auth.sessionStatus.value === sdkStatus && checkpointTraceAuthBudgetCurrent(sdk, sdkStatus, safeEnd)

    /** Read-only preflight facts; this never grants a managed lease or changes readiness. */
    internal fun checkpointTraceScopeIdle(scopeState: Task126BusinessDataScopeState): Boolean =
        scopeState.status == Task126BusinessDataScopeStatus.ERROR_RECOVERABLE &&
            scopeState.errorCode == "sync_recovery_required" && !scopeState.allowsCloudSync &&
            !catalogSyncStateTracker.isSyncing.value && !businessDataScopeMutex.isLocked &&
            synchronized(shopContextRecoveryLock) { shopContextRecoveryJob?.isCompleted != false }


    internal enum class CheckpointReadinessOutcome {
        BLOCKED_RELEASE, BLOCKED_TEST_TARGET, BLOCKED_NOT_WARM, BLOCKED_CONFLICT, BLOCKED_UNAVAILABLE,
        SNAPSHOT_INCOMPLETE, SNAPSHOT_ELIGIBLE, BLOCKED_LOCAL_OR_STALE, TIMEOUT_UNKNOWN
    }

    internal enum class CheckpointJournalPhase {
        REQUIRED, STAGING, READY_TO_ACTIVATE, ACTIVATED_CLEANUP_PENDING
    }

    internal enum class CheckpointJournalError {
        HTTP_SERVER, HTTP_OTHER, AUTH, FORBIDDEN, NETWORK_OR_TIMEOUT, LOCAL_PENDING,
        CANCELLED, CLEANUP_PENDING, READER_UNAVAILABLE, UNKNOWN
    }

    /** Scalars only. UNKNOWN is never a lease, READY, or permission to invoke the trace. */
    internal data class CheckpointReadinessSnapshot(
        val outcome: CheckpointReadinessOutcome,
        val targetTest: Boolean? = null,
        val activityWarm: Boolean? = null,
        val activityResumed: Boolean? = null,
        val traceObserverIdle: Boolean? = null,
        val sdkSession: Boolean? = null,
        val refreshBudget: Boolean? = null,
        val sdkClientSameCapture: Boolean? = null,
        val scopeIdle: Boolean? = null,
        val scopeCurrent: Boolean? = null,
        val generationStable: Boolean? = null,
        val businessGenerationQuiet: Boolean? = null,
        val recoveryIdle: Boolean? = null,
        val scopeMutexIdle: Boolean? = null,
        val shopRecoveryIdle: Boolean? = null,
        val oneUseUnconsumed: Boolean? = null,
        val localScopeGuard: Boolean? = null,
        val deviceJournalPresent: Boolean? = null,
        val journalRequired: Boolean? = null,
        val baselinePresent: Boolean? = null,
        val watermarkPresent: Boolean? = null,
        val localSnapshotUnchanged: Boolean? = null,
        val fenceCurrent: Boolean? = null,
        val preflightEligible: Boolean? = null,
        val journalPhase: CheckpointJournalPhase? = null,
        val journalAttempt: Int? = null,
        val journalNextRetryAtMs: Long? = null,
        val journalError: CheckpointJournalError? = null
    ) {
        internal fun hasRequiredMemoryFacts(): Boolean = listOf(
            activityWarm, activityResumed, traceObserverIdle, sdkSession, refreshBudget,
            sdkClientSameCapture, scopeIdle, scopeCurrent, generationStable, businessGenerationQuiet,
            recoveryIdle, scopeMutexIdle, shopRecoveryIdle, oneUseUnconsumed
        ).all { it == true }
        private fun flag(value: Boolean?) = value?.toString() ?: "UNKNOWN"
        fun safeLogLine(): String =
            "outcome=" + outcome.name + " business_READY=false atomic_attempt_rechecks=REQUIRED target_TEST=" + flag(targetTest) +
                " activity_warm=" + flag(activityWarm) + " activity_resumed=" + flag(activityResumed) +
                " trace_observer_idle=" + flag(traceObserverIdle) + " sdk_session=" + flag(sdkSession) +
                " refresh_budget=" + flag(refreshBudget) + " sdk_client_same_capture=" + flag(sdkClientSameCapture) +
                " scope_idle=" + flag(scopeIdle) + " scope_current=" + flag(scopeCurrent) +
                " generation_stable=" + flag(generationStable) +
                " business_generation_quiet=" + flag(businessGenerationQuiet) +
                " recovery_idle=" + flag(recoveryIdle) + " scope_mutex_idle=" + flag(scopeMutexIdle) +
                " shop_recovery_idle=" + flag(shopRecoveryIdle) + " one_use_unconsumed=" + flag(oneUseUnconsumed) +
                " local_scope_guard=" + flag(localScopeGuard) + " device_journal_present=" + flag(deviceJournalPresent) +
                " journal_required=" + flag(journalRequired) + " baseline_present=" + flag(baselinePresent) +
                " watermark_present=" + flag(watermarkPresent) + " local_snapshot_unchanged=" + flag(localSnapshotUnchanged) +
                " fence_current=" + flag(fenceCurrent) + " preflight_eligible=" + flag(preflightEligible) +
                " journal_phase=" + (journalPhase?.name ?: "UNKNOWN") +
                " journal_attempt=" + (journalAttempt?.toString() ?: "UNKNOWN") +
                " journal_next_retry_at_ms=" + (journalNextRetryAtMs?.toString() ?: "UNKNOWN") +
                " journal_error=" + (journalError?.name ?: "UNKNOWN")
    }

    // Only our own compiler-generated lazy fields, in the debug-only snapshot path.
    // Missing/renamed/uninitialized fields fail closed; never invoke a lazy initializer.
    private inline fun <reified T> checkpointReadinessExistingLazy(name: String): T? = try {
        val lazyValue = javaClass.getDeclaredField(name + "\$delegate")
            .apply { isAccessible = true }.get(this) as? Lazy<*>
        if (lazyValue?.isInitialized() == true) lazyValue.value as? T else null
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    @OptIn(SupabaseInternal::class)
    internal fun checkpointReadinessMemory(
        warmForeground: Boolean, activityResumed: Boolean, traceObserverIdle: Boolean
    ): CheckpointReadinessSnapshot {
        if (!warmForeground) return CheckpointReadinessSnapshot(
            CheckpointReadinessOutcome.BLOCKED_NOT_WARM, activityWarm = false,
            activityResumed = activityResumed, traceObserverIdle = traceObserverIdle)
        val oneUseAndRecovery = synchronized(businessRecoveryLock) {
            !checkpointTraceConsumed to (checkpointTraceReservation == null &&
                businessRecoveryJob?.isCompleted != false && !businessRecoveryExecutionMutex.isLocked)
        }
        val shopIdle = synchronized(shopContextRecoveryLock) { shopContextRecoveryJob?.isCompleted != false }
        val owner = checkpointReadinessExistingLazy<GenerationOwnedSupabaseClient>("supabaseClient")
        val sdk = owner?.captureClientOrNull()
        val auth = checkpointReadinessExistingLazy<SupabaseAuthManager>("authManager")
        val tracker = checkpointReadinessExistingLazy<CatalogSyncStateTracker>("catalogSyncStateTracker")
        val shop = checkpointReadinessExistingLazy<ShopContextRepository>("shopContextRepository")
        val shopEpoch = shop?.diagnosticShopEpoch()
        val shopContext = shop?.state?.value
        val authSafeEnd = Clock.System.now() + 10.seconds
        val appState = auth?.state?.value
        val status = sdk?.auth?.sessionStatus?.value
        val signedIn = appState as? AuthState.SignedIn
        val authenticated = status as? SessionStatus.Authenticated
        val sdkSession = if (sdk == null || auth == null) null else
            signedIn != null && authenticated != null && sdk.auth.currentUserOrNull()?.id == signedIn.userId
        val budget = if (sdk == null) null else authenticated?.let {
            checkpointTraceAuthBudgetCurrent(sdk, it, authSafeEnd)
        } ?: false
        val clientSame = sdk?.let {
            owner.captureClientOrNull() === it && it.auth.sessionStatus.value === status &&
                auth?.state?.value === appState
        }
        val scope = tracker?.businessDataScopeState?.value
        val scopeIdle = scope?.let { checkpointTraceScopeIdle(it) }
        val stamp = tracker?.captureDiagnosticQuietStamp()
        val quiet = tracker?.let { stamp != null && it.isDiagnosticQuietStampCurrent(stamp) }
        val scopeCurrent = if (auth == null || shop == null || tracker == null) null else
            signedIn != null && shopContext?.selectedShop != null &&
                currentAuthAndShopMatch(signedIn.userId, shopContext.selectedShop)
        // Same observable fences as the existing attempt, not a new generation lease.
        val generationCurrent = if (owner == null || sdk == null || auth == null ||
            tracker == null || shop == null) null else
            signedIn != null && authenticated != null && stamp != null &&
                checkpointTraceOwnedAuthCurrent(owner, sdk, signedIn, auth.state.value,
                    authenticated, authSafeEnd) &&
                shop.diagnosticShopEpoch() == shopEpoch && shop.state.value == shopContext &&
                shop.diagnosticShopEpoch() == shopEpoch &&
                tracker.businessDataScopeState.value == scope && tracker.isDiagnosticQuietStampCurrent(stamp)
        // This memory-only stage cannot attest local storage; the bounded read follows separately.
        return CheckpointReadinessSnapshot(
            CheckpointReadinessOutcome.SNAPSHOT_INCOMPLETE,
            activityWarm = true, activityResumed = activityResumed, traceObserverIdle = traceObserverIdle,
            sdkSession = sdkSession, refreshBudget = budget, sdkClientSameCapture = clientSame,
            scopeIdle = scopeIdle, scopeCurrent = scopeCurrent,
            generationStable = generationCurrent, businessGenerationQuiet = quiet,
            recoveryIdle = oneUseAndRecovery.second, scopeMutexIdle = !businessDataScopeMutex.isLocked,
            shopRecoveryIdle = shopIdle, oneUseUnconsumed = oneUseAndRecovery.first)
    }

    internal enum class LocalThreadSnapshotOutcome {
        CAPTURED, BLOCKED_RELEASE, BLOCKED_NOT_WARM, BLOCKED_CONFLICT, BLOCKED_TEST_TARGET,
        BLOCKED_ACTIVE, BLOCKED_USED, UNAVAILABLE
    }

    /** Memory-only: no auth, database, recovery state, business lock or diagnostic RPC access. */
    internal suspend fun reportLocalThreadSnapshot(
        warmForeground: Boolean, isActivityResumed: () -> Boolean, conflictingIntent: Boolean
    ): LocalThreadSnapshotOutcome {
        if (!BuildConfig.DEBUG) return LocalThreadSnapshotOutcome.BLOCKED_RELEASE
        fun report(outcome: LocalThreadSnapshotOutcome): LocalThreadSnapshotOutcome {
            Log.i("Task143LocalThreads", "outcome=${outcome.name} pid=${android.os.Process.myPid()} " +
                "uptimeMs=${SystemClock.uptimeMillis()}")
            return outcome
        }
        if (conflictingIntent) return report(LocalThreadSnapshotOutcome.BLOCKED_CONFLICT)
        if (!warmForeground || !isActivityResumed()) return report(LocalThreadSnapshotOutcome.BLOCKED_NOT_WARM)
        val targetDigest = MessageDigest.getInstance("SHA-256")
            .digest(BuildConfig.SUPABASE_URL.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        if (targetDigest != CHECKPOINT_TRACE_TEST_URL_SHA256) {
            return report(LocalThreadSnapshotOutcome.BLOCKED_TEST_TARGET)
        }
        if (!localThreadSnapshotState.compareAndSet(0, 1)) {
            return report(if (localThreadSnapshotState.get() == 1) LocalThreadSnapshotOutcome.BLOCKED_ACTIVE
                else LocalThreadSnapshotOutcome.BLOCKED_USED)
        }
        try {
            return withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                if (!isActivityResumed()) return@withContext report(LocalThreadSnapshotOutcome.BLOCKED_NOT_WARM)
                // One acquisition, not an atomic multi-thread snapshot or a debugger suspension.
                val capturedAt = SystemClock.uptimeMillis()
                val stacks = Thread.getAllStackTraces()
                formatLocalThreadSnapshot(stacks, localThreadId(android.os.Looper.getMainLooper().thread),
                    android.os.Process.myPid(), capturedAt).forEach { Log.i("Task143LocalThreads", it) }
                LocalThreadSnapshotOutcome.CAPTURED
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return report(LocalThreadSnapshotOutcome.UNAVAILABLE)
        } finally {
            localThreadSnapshotState.set(2)
        }
    }

    // Android 31–35 requires the legacy ID accessor; threadId() is available from API 36.
    @Suppress("DEPRECATION")
    private fun localThreadId(thread: Thread): Long =
        if (android.os.Build.VERSION.SDK_INT >= 36) thread.threadId() else thread.id

    /** Same export predicate used by the reporter; all unlisted symbols and filenames are omitted. */
    internal fun formatLocalThreadSnapshot(
        stacks: Map<Thread, Array<StackTraceElement>>, mainThreadId: Long, pid: Int, capturedAtMs: Long
    ): List<String> {
        val roots = setOf(
            "java.lang.Thread", "java.lang.Object", "java.util.concurrent.locks.LockSupport",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer", "java.util.concurrent.FutureTask",
            "java.util.concurrent.ThreadPoolExecutor", "android.os.Looper", "android.os.MessageQueue",
            "android.os.Handler", "android.database.sqlite.SQLiteConnectionPool", "android.database.sqlite.SQLiteSession",
            "android.database.sqlite.SQLiteConnection", "android.database.sqlite.SQLiteDatabase",
            "android.database.sqlite.SQLiteQuery", "android.database.sqlite.SQLiteCursor",
            "androidx.sqlite.db.framework.FrameworkSQLiteDatabase", "androidx.sqlite.db.framework.FrameworkSQLiteStatement",
            "androidx.room.RoomDatabase", "androidx.room.RoomDatabaseKt", "androidx.room.TransactionExecutor",
            "androidx.room.util.DBUtil", "androidx.room.util.DBUtilKt", "androidx.room.util.DBUtil__DBUtil_androidKt",
            "androidx.room.util.DBUtil__DBUtilKt", "androidx.room.coroutines.PassthroughConnectionPool",
            "androidx.room.coroutines.ConnectionPoolImpl", "androidx.room.coroutines.PooledConnectionImpl",
            "androidx.room.paging.CommonLimitOffsetImpl", "androidx.room.paging.LimitOffsetPagingSource",
            "androidx.room.paging.util.RoomPagingUtilKt", "androidx.paging.PageFetcherSnapshot",
            "androidx.paging.PageFetcher", "androidx.paging.PagingDataPresenter",
            "kotlinx.coroutines.BlockingCoroutine", "kotlinx.coroutines.EventLoopImplBase",
            "kotlinx.coroutines.BuildersKt", "kotlinx.coroutines.BuildersKt__Builders_commonKt",
            "kotlinx.coroutines.BuildersKt__BuildersKt", "kotlinx.coroutines.DispatchedTask",
            "kotlinx.coroutines.scheduling.CoroutineScheduler", "kotlinx.coroutines.sync.MutexImpl",
            "kotlinx.coroutines.sync.SemaphoreImpl", "kotlinx.coroutines.CancellableContinuationImpl",
            "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository",
            "com.example.merchandisecontrolsplitview.data.ProductDao_Impl",
            "com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinator",
            "com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinatorKt",
            "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel"
        )
        val methods = setOf(
            "wait", "sleep", "park", "parkNanos", "get", "await", "acquire", "acquireSharedInterruptibly",
            "runWorker", "getTask", "run", "invoke", "invokeSuspend", "resumeWith", "<init>",
            "waitForConnection", "acquireConnection", "beginTransaction", "beginTransactionUnchecked",
            "endTransaction", "query", "rawQuery", "rawQueryWithFactory", "execute", "executeForCursorWindow",
            "nativeExecuteForCursorWindow", "fillWindow", "useConnection", "withTransaction",
            "withTransactionContext", "startTransactionCoroutine", "compatTransactionCoroutineExecute",
            "performSuspending", "internalPerform", "queryItemCount", "queryDatabase", "load", "initialLoad",
            "nonInitialLoad", "joinBlocking", "processNextEvent", "loop", "loopOnce", "next", "nativePollOnce",
            "lock", "lockSuspend", "dispatchMessage", "drainSyncEventsFromRemote", "drainSyncEventsInternal",
            "drainVerifiedShopSyncWindow", "ordinaryShopSyncPendingCount", "requireCurrentBusinessDataScope",
            "getProductsWithDetailsPaged", "getAllWithDetailsPaged", "getAllWithDetailsPagedForProductIds",
            "convertRows", "validateShopSyncActiveReceipt", "validateShopSyncCanonicalReceipt",
            "validateManifest", "validateRelationalManifest", "validateStagingDatabase", "validatePhysicalSnapshot",
            "localAcknowledgedManifestPage", "materializablePriceCount", "readPhysicalPage", "requirePragmaOk", "queryCount"
        )
        val lines = mutableListOf<String>()
        var bytes = 0
        var emittedThreads = 0
        var emittedFrames = 0
        var omittedFrames = 0
        var truncated = false
        fun append(line: String): Boolean {
            val size = line.length + 1 // Export grammar is ASCII; reserve space for the fixed summary.
            if (bytes + size > 65_536 - 512) { truncated = true; return false }
            lines += line
            bytes += size
            return true
        }
        append("stage=BEGIN pid=$pid uptimeMs=$capturedAtMs temporal=PER_THREAD_NOT_ATOMIC " +
            "suspendedCaller=NOT_GUARANTEED capturedThreads=${stacks.size}")
        for ((thread, frames) in stacks.entries.sortedBy { localThreadId(it.key) }) {
            if (emittedThreads >= 128) { truncated = true; break }
            val kind = when {
                localThreadId(thread) == mainThreadId -> "MAIN"
                frames.any { it.className.startsWith("android.database.sqlite.") } -> "SQLITE"
                frames.any { it.className.startsWith("androidx.room.") } -> "ROOM"
                frames.any { it.className.startsWith("androidx.paging.") } -> "PAGING"
                frames.any { it.className.startsWith("kotlinx.coroutines.") } -> "COROUTINE"
                else -> "OTHER"
            }
            if (!append("thread=$emittedThreads threadId=${localThreadId(thread)} state=${thread.state.name} kind=$kind")) break
            emittedThreads++
            if (frames.size > 48) truncated = true
            for ((depth, frame) in frames.take(48).withIndex()) {
                val className = frame.className
                val allowedClass = className.length <= 200 && roots.any { root ->
                    className == root || className.startsWith(root + "$" ) &&
                        className.substring(root.length + 1).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '$' }
                }
                if (!allowedClass || frame.methodName !in methods) { omittedFrames++; continue }
                if (!append("frame=${emittedThreads - 1}:$depth class=$className method=${frame.methodName} " +
                        "line=${frame.lineNumber} native=${frame.isNativeMethod}")) break
                emittedFrames++
            }
        }
        lines += "outcome=CAPTURED end=true emittedThreads=$emittedThreads emittedFrames=$emittedFrames " +
            "omittedFrames=$omittedFrames capturedFrames=${stacks.values.sumOf { it.size }} truncated=$truncated capBytes=65536"
        return lines
    }

    internal suspend fun reportCheckpointReadiness(
        warmForeground: Boolean, isActivityResumed: () -> Boolean, isTraceObserverIdle: () -> Boolean,
        conflictingTrace: Boolean
    ): CheckpointReadinessSnapshot {
        if (!BuildConfig.DEBUG) return CheckpointReadinessSnapshot(CheckpointReadinessOutcome.BLOCKED_RELEASE)
        val startedAt = SystemClock.elapsedRealtime()
        val digest = MessageDigest.getInstance("SHA-256").digest(BuildConfig.SUPABASE_URL.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = digest == CHECKPOINT_TRACE_TEST_URL_SHA256
        val resumedNow = isActivityResumed()
        val observerIdleNow = isTraceObserverIdle()
        val snapshot = when {
            conflictingTrace -> CheckpointReadinessSnapshot(CheckpointReadinessOutcome.BLOCKED_CONFLICT)
            !target -> CheckpointReadinessSnapshot(CheckpointReadinessOutcome.BLOCKED_TEST_TARGET)
            !warmForeground -> CheckpointReadinessSnapshot(CheckpointReadinessOutcome.BLOCKED_NOT_WARM,
                activityWarm = false, activityResumed = resumedNow, traceObserverIdle = observerIdleNow)
            else -> try {
                checkpointReadinessPreflight(warmForeground, isActivityResumed, isTraceObserverIdle, startedAt)
            } catch (error: CancellationException) {
                throw error // Never cancel or touch an external job; propagate caller cancellation.
            } catch (_: Exception) {
                CheckpointReadinessSnapshot(CheckpointReadinessOutcome.BLOCKED_UNAVAILABLE)
            }
        }.let { it.copy(targetTest = target, activityWarm = it.activityWarm ?: warmForeground,
            activityResumed = it.activityResumed ?: resumedNow,
            traceObserverIdle = it.traceObserverIdle ?: observerIdleNow) }
        Log.i("Task143CheckpointReadiness", snapshot.safeLogLine())
        return snapshot
    }


    /** Read-only local-query coroutine only; original recovery/one-shot runners remain unchanged. */
    private suspend fun checkpointReadinessPreflight(
        warmForeground: Boolean, isActivityResumed: () -> Boolean, isTraceObserverIdle: () -> Boolean, startedAt: Long
    ): CheckpointReadinessSnapshot {
        val memory = checkpointReadinessMemory(warmForeground, isActivityResumed(), isTraceObserverIdle())
        // Causal observation may read an already-open journal while recovery is busy.
        // Qualification still requires every original memory fact and the original idle fences.
        val requireIdle = memory.hasRequiredMemoryFacts()
        val owner = checkpointReadinessExistingLazy<GenerationOwnedSupabaseClient>("supabaseClient")
            ?: return memory.copy(preflightEligible = false)
        val sdk = owner.captureClientOrNull() ?: return memory.copy(preflightEligible = false)
        val auth = checkpointReadinessExistingLazy<SupabaseAuthManager>("authManager")
            ?: return memory.copy(preflightEligible = false)
        val signedIn = auth.state.value as? AuthState.SignedIn ?: return memory.copy(preflightEligible = false)
        val status = sdk.auth.sessionStatus.value as? SessionStatus.Authenticated
            ?: return memory.copy(preflightEligible = false)
        if (sdk.auth.currentUserOrNull()?.id != signedIn.userId) return memory.copy(preflightEligible = false)
        val shop = checkpointReadinessExistingLazy<ShopContextRepository>("shopContextRepository")
            ?: return memory.copy(preflightEligible = false)
        val tracker = checkpointReadinessExistingLazy<CatalogSyncStateTracker>("catalogSyncStateTracker")
            ?: return memory.copy(preflightEligible = false)
        val epoch = shop.diagnosticShopEpoch()
        val context = shop.state.value
        val selected = context.selectedShop ?: return memory.copy(preflightEligible = false)
        val scopeState = tracker.businessDataScopeState.value
        val stamp = tracker.captureDiagnosticQuietStamp() ?: return memory.copy(preflightEligible = false)
        val authEnd = Clock.System.now() + 10.seconds
        val activeScope = task126ActiveOwnerStoreScope(signedIn.userId, selected)
        fun current(): Boolean {
            val recoveryIdle = synchronized(businessRecoveryLock) {
                !checkpointTraceConsumed && checkpointTraceReservation == null &&
                    businessRecoveryJob?.isCompleted != false && !businessRecoveryExecutionMutex.isLocked
            }
            return isActivityResumed() && isTraceObserverIdle() &&
                sdk.auth.currentUserOrNull()?.id == signedIn.userId &&
                checkpointTraceOwnedAuthCurrent(owner, sdk, signedIn,
                auth.state.value, status, authEnd) && shop.diagnosticShopEpoch() == epoch &&
                shop.state.value == context && shop.diagnosticShopEpoch() == epoch &&
                tracker.businessDataScopeState.value == scopeState &&
                currentAuthAndShopMatch(signedIn.userId, selected) &&
                tracker.isDiagnosticQuietStampCurrent(stamp) &&
                (!requireIdle || (recoveryIdle && checkpointTraceScopeIdle(scopeState)))
        }
        return checkpointReadinessLocalGuards(signedIn.userId, activeScope, selected.shopId,
            2_000 - (SystemClock.elapsedRealtime() - startedAt), ::current, memory)
    }

    internal suspend fun checkpointReadinessLocalGuards(
        ownerId: String, activeScope: Task126OwnerStoreScope, shopId: String, remainingMs: Long,
        current: () -> Boolean, memory: CheckpointReadinessSnapshot
    ): CheckpointReadinessSnapshot {
        if (remainingMs <= 0) return memory.copy(outcome = CheckpointReadinessOutcome.TIMEOUT_UNKNOWN,
            preflightEligible = false)
        // No Room opening/migration: reuse only the database that the normal app already initialized.
        val localDatabase = checkpointReadinessExistingLazy<AppDatabase>("database")
        if (localDatabase == null || !localDatabase.isOpen)
            return memory.copy(outcome = CheckpointReadinessOutcome.BLOCKED_UNAVAILABLE, preflightEligible = false)
        return withTimeoutOrNull(remainingMs) {
            if (!current()) return@withTimeoutOrNull memory.copy(
                outcome = CheckpointReadinessOutcome.BLOCKED_LOCAL_OR_STALE, fenceCurrent = false, preflightEligible = false)
            val before = checkpointTraceSnapshot(ownerId, activeScope.storeId)
            val scopeOk = before.allowsCheckpointScope(activeScope, shopId)
            val after = checkpointTraceSnapshot(ownerId, activeScope.storeId)
            val unchanged = after == before
            val fence = current()
            val localEligible = scopeOk && unchanged && fence
            val eligible = localEligible && memory.hasRequiredMemoryFacts()
            // Observe only this authenticated device/scope. Phase is not an observation gate:
            // staging/cleanup are useful evidence but never qualify the original trace.
            val journal = before.journal?.takeIf {
                unchanged && fence && it.ownerHash == activeScope.ownerHash &&
                    it.storeScope == activeScope.storeId && it.shopId?.lowercase() == shopId.lowercase() &&
                    before.device?.deviceId?.isNotBlank() == true && it.deviceId == before.device.deviceId &&
                    it.authorizationMode in setOf(SyncRecoveryAuthorizationModes.SAME_SCOPE,
                        SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED)
            }
            val phase = when (journal?.phase) {
                SyncRecoveryJournalPhases.REQUIRED -> CheckpointJournalPhase.REQUIRED
                SyncRecoveryJournalPhases.STAGING -> CheckpointJournalPhase.STAGING
                SyncRecoveryJournalPhases.READY_TO_ACTIVATE -> CheckpointJournalPhase.READY_TO_ACTIVATE
                SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING -> CheckpointJournalPhase.ACTIVATED_CLEANUP_PENDING
                else -> null
            }
            // reason is the durable retry code, not a raw exception. Never emit it verbatim.
            val code = journal?.reason
            val http = code?.takeIf { it.matches(Regex("shop_sync_rpc_http_[1-5][0-9]{2}")) }
                ?.takeLast(3)?.toIntOrNull()
            val error = if (journal == null) null else when {
                http == 401 -> CheckpointJournalError.AUTH
                http == 403 -> CheckpointJournalError.FORBIDDEN
                http == 408 || http == 504 -> CheckpointJournalError.NETWORK_OR_TIMEOUT
                http != null && http >= 500 -> CheckpointJournalError.HTTP_SERVER
                http != null -> CheckpointJournalError.HTTP_OTHER
                code == "recovery_local_pending" -> CheckpointJournalError.LOCAL_PENDING
                code == "recovery_cancelled" -> CheckpointJournalError.CANCELLED
                code in setOf("recovery_staging_cleanup_deferred", "recovery_orphan_cleanup_deferred") ->
                    CheckpointJournalError.CLEANUP_PENDING
                code == "shop_sync_reader_unavailable" -> CheckpointJournalError.READER_UNAVAILABLE
                else -> CheckpointJournalError.UNKNOWN
            }
            memory.copy(outcome = when {
                eligible -> CheckpointReadinessOutcome.SNAPSHOT_ELIGIBLE
                localEligible -> CheckpointReadinessOutcome.SNAPSHOT_INCOMPLETE
                else -> CheckpointReadinessOutcome.BLOCKED_LOCAL_OR_STALE
            },
                localScopeGuard = scopeOk, deviceJournalPresent = before.device != null && before.journal != null,
                journalRequired = before.journal?.phase == SyncRecoveryJournalPhases.REQUIRED,
                baselinePresent = before.baseline != null, watermarkPresent = before.watermark != null,
                localSnapshotUnchanged = unchanged, fenceCurrent = fence, preflightEligible = eligible,
                journalPhase = phase,
                journalAttempt = journal?.attemptCount?.takeIf { it in 0..1_000_000 },
                journalNextRetryAtMs = journal?.nextRetryAtMs?.takeIf { it >= 0 }, journalError = error)
        } ?: memory.copy(outcome = CheckpointReadinessOutcome.TIMEOUT_UNKNOWN, preflightEligible = false)
    }

    internal fun requestOneCheckpointTrace(warmForeground: Boolean) {
        if (!BuildConfig.DEBUG) return
        val startedAt = SystemClock.elapsedRealtime()
        val token = Any()
        var blocked: String? = null
        synchronized(businessRecoveryLock) {
            if (checkpointTraceConsumed) blocked = "BLOCKED_USED"
            else {
                checkpointTraceConsumed = true
                if (!warmForeground) blocked = "BLOCKED_COLD"
                else if (businessRecoveryJob?.isCompleted == false ||
                    checkpointTraceReservation != null ||
                    !businessRecoveryExecutionMutex.tryLock(token)) blocked = "BLOCKED_BUSY"
                else checkpointTraceReservation = token
            }
        }
        fun releaseOwnedReservation() {
            synchronized(businessRecoveryLock) {
                if (checkpointTraceReservation === token) {
                    checkpointTraceReservation = null
                    businessRecoveryExecutionMutex.unlock(token)
                }
            }
        }
        fun emit(outcome: String, attempt: ShopSyncCheckpointTraceAttempt?,
            targetTest: Boolean?, beforeOk: Boolean?, afterOk: Boolean?, cancelled: Boolean) {
            // All names and outcome strings are fixed in this runner. No Throwable or raw entity.
            Log.i("Task143CheckpointTrace",
                "trace=$CHECKPOINT_TRACE_VALUE target_TEST=$targetTest " +
                "localRpcAttemptCount=${attempt?.logicalAttemptCount ?: 0} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "HTTP=${attempt?.httpStatus} SQLSTATE=null before_ok=$beforeOk " +
                "after_ok=$afterOk cancelled=$cancelled outcome=$outcome")
        }
        blocked?.let { emit(it, null, null, null, null, false); return }
        try {
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                var outcome = "BLOCKED_PREFLIGHT"
                var targetTest: Boolean? = null
                var beforeOk: Boolean? = null
                var afterOk: Boolean? = null
                var cancelled = false
                var attempt: ShopSyncCheckpointTraceAttempt? = null
                try {
                    val remaining = CHECKPOINT_TRACE_TIMEOUT_MS -
                        (SystemClock.elapsedRealtime() - startedAt)
                    if (remaining <= 0) return@launch
                    withTimeout(remaining) {
                        val configuredUrlDigest = MessageDigest.getInstance("SHA-256")
                            .digest(BuildConfig.SUPABASE_URL.encodeToByteArray())
                            .joinToString("") { "%02x".format(it) }
                        targetTest = CHECKPOINT_TRACE_TEST_URL_SHA256.length == 64 &&
                            configuredUrlDigest == CHECKPOINT_TRACE_TEST_URL_SHA256
                        if (targetTest != true) { outcome = "BLOCKED_TEST_TARGET"; return@withTimeout }
                        val owner = supabaseClient as? GenerationOwnedSupabaseClient
                            ?: return@withTimeout
                        val sdk = owner.captureClientOrNull() ?: return@withTimeout
                        val appAuth = authManager.state.value as? AuthState.SignedIn
                            ?: return@withTimeout
                        val sdkStatus = sdk.auth.sessionStatus.value as? SessionStatus.Authenticated
                            ?: return@withTimeout
                        val authSafeEnd = Clock.System.now() + 10.seconds
                        if (sdk.auth.currentUserOrNull()?.id != appAuth.userId ||
                            !checkpointTraceAuthBudgetCurrent(sdk, sdkStatus, authSafeEnd)) return@withTimeout
                        val shopEpoch = shopContextRepository.diagnosticShopEpoch()
                        val shopContext = shopContextRepository.state.value
                        if (shopContextRepository.diagnosticShopEpoch() != shopEpoch) return@withTimeout
                        val selectedShop = shopContext.selectedShop ?: return@withTimeout
                        val scopeState = catalogSyncStateTracker.businessDataScopeState.value
                        if (!checkpointTraceScopeIdle(scopeState) ||
                            !currentAuthAndShopMatch(appAuth.userId, selectedShop)) return@withTimeout
                        val stamp = catalogSyncStateTracker.captureDiagnosticQuietStamp()
                            ?: return@withTimeout
                        val activeScope = task126ActiveOwnerStoreScope(appAuth.userId, selectedShop)
                        val before = checkpointTraceSnapshot(appAuth.userId, activeScope.storeId)
                        if (!before.allowsCheckpointScope(activeScope, selectedShop.shopId)) return@withTimeout
                        val device = requireNotNull(before.device)
                        fun current(): Boolean =
                            checkpointTraceOwnedAuthCurrent(
                                owner, sdk, appAuth, authManager.state.value, sdkStatus, authSafeEnd
                            ) &&
                            shopContextRepository.diagnosticShopEpoch() == shopEpoch &&
                            shopContextRepository.state.value == shopContext &&
                            shopContextRepository.diagnosticShopEpoch() == shopEpoch &&
                            catalogSyncStateTracker.businessDataScopeState.value == scopeState &&
                            !catalogSyncStateTracker.isSyncing.value && !businessDataScopeMutex.isLocked &&
                            currentAuthAndShopMatch(appAuth.userId, selectedShop) &&
                            catalogSyncStateTracker.isDiagnosticQuietStampCurrent(stamp)
                        if (SystemClock.elapsedRealtime() - startedAt > 2_000 || !current()) {
                            outcome = "BLOCKED_STALE"; return@withTimeout
                        }
                        currentCoroutineContext().ensureActive()
                        beforeOk = true
                        val trace = ShopSyncCheckpointTraceAttempt(::current)
                        attempt = trace
                        val result = SupabaseShopSyncReadRemoteDataSource(sdk, trace).checkpoint(
                            ShopSyncRpcContext(appAuth.userId, selectedShop.shopId, device.deviceId,
                                verifiedBaselineId = "0", expectedBaselineScopeKey = null)
                        )
                        currentCoroutineContext().ensureActive()
                        val after = checkpointTraceSnapshot(appAuth.userId, activeScope.storeId)
                        currentCoroutineContext().ensureActive()
                        afterOk = current() && after == before
                        outcome = when {
                            afterOk != true -> "REJECTED_AFTER_CHANGED"
                            result.isSuccess -> "CHECKPOINT_RETURNED"
                            else -> "CHECKPOINT_FAILED"
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    outcome = "TIMEOUT_AFTER_UNKNOWN"
                    cancelled = true
                } catch (error: CancellationException) {
                    outcome = "CANCELLED_AFTER_UNKNOWN"
                    cancelled = true
                    throw error
                } catch (_: Exception) {
                    outcome = "FAILED_AFTER_UNKNOWN"
                } finally {
                    emit(outcome, attempt, targetTest, beforeOk, afterOk, cancelled)
                    releaseOwnedReservation()
                }
            }
            // Also covers cancellation before the lazy body enters its try/finally.
            job.invokeOnCompletion { releaseOwnedReservation() }
            job.start()
        } catch (error: Exception) {
            releaseOwnedReservation()
            emit("BLOCKED_LAUNCH", null, null, null, null, error is CancellationException)
            if (error is CancellationException) throw error
        }
    }

    internal fun requestPendingBusinessRecovery(reason: String) {
        schedulePendingBusinessRecovery(reason)
    }

    private fun schedulePendingBusinessRecovery(reason: String): Boolean {
        if (
            catalogSyncStateTracker.businessDataScopeState.value.errorCode !=
            "sync_recovery_required"
        ) return false
        val scheduled = synchronized(businessRecoveryLock) {
            if (checkpointTraceReservation != null) return true
            if (businessRecoveryJob?.isActive == true) return true
            lateinit var job: Job
            job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    businessRecoveryExecutionMutex.withLock {
                        var attemptsInCurrentWindow = 0
                        while (true) {
                            val signedIn = authManager.state.value as? AuthState.SignedIn
                                ?: return@withLock
                            val context = shopContextRepository.state.value
                            val selectedShop = context.selectedShop ?: return@withLock
                            if (!currentAuthAndShopMatch(signedIn.userId, selectedShop)) {
                                return@withLock
                            }
                            val activeScope = task126ActiveOwnerStoreScope(signedIn.userId, selectedShop)
                            val journal = withContext(Dispatchers.IO) {
                                database.syncRecoveryJournalDao().getForScope(
                                    activeScope.ownerHash,
                                    activeScope.storeId
                                )
                            } ?: return@withLock
                            if (!shouldAttemptAutomaticBusinessRecovery(
                                    attemptsInCurrentWindow = attemptsInCurrentWindow,
                                    durableAttemptCount = journal.attemptCount
                                )
                            ) {
                                Log.w(
                                    TAG,
                                    "Business recovery: finestra retry esaurita reason=$reason"
                                )
                                return@withLock
                            }
                            val waitMs = (journal.nextRetryAtMs ?: 0L) - System.currentTimeMillis()
                            if (waitMs > 0L) delay(waitMs)
                            if (catalogSyncStateTracker.networkAvailable.value == false ||
                                shopContextRepository.state.value != context ||
                                !currentAuthAndShopMatch(signedIn.userId, selectedShop)
                            ) {
                                return@withLock
                            }
                            attemptsInCurrentWindow += 1
                            val result = shopSyncRecoveryCoordinator.recover(
                                accountId = signedIn.userId,
                                selectedShop = selectedShop,
                                activeScope = activeScope
                            )
                            when (result) {
                                is ShopSyncRecoveryResult.Activated -> {
                                    completeBusinessRecovery(signedIn, selectedShop, activeScope)
                                    return@withLock
                                }
                                is ShopSyncRecoveryResult.Rejected -> {
                                    Log.w(TAG, "Business recovery: rifiutato code=${result.code}")
                                    return@withLock
                                }
                                is ShopSyncRecoveryResult.RetryRequired -> {
                                    Log.w(TAG, "Business recovery: retry code=${result.code}")
                                }
                            }
                        }
                    }
                } finally {
                    synchronized(businessRecoveryLock) {
                        if (businessRecoveryJob === job) businessRecoveryJob = null
                    }
                }
            }
            businessRecoveryJob = job
            job
        }
        scheduled.start()
        return true
    }

    private suspend fun completeBusinessRecovery(
        signedIn: AuthState.SignedIn,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop,
        activeScope: Task126OwnerStoreScope
    ) {
        val activation = businessDataScopeMutex.withLock {
            val context = shopContextRepository.state.value
            val published = catalogSyncStateTracker.resolveAndPublishBusinessDataScope(
                stillAuthorized = {
                    currentAuthAndShopMatch(signedIn.userId, selectedShop) &&
                        shopContextRepository.state.value == context
                },
                resolve = { previous ->
                    val resolved = repository.resolveBusinessDataScope(activeScope)
                    resolved.copy(
                        localWritesAllowed = previous.localWritesAllowed && resolved.localWritesAllowed &&
                            context.localAccessAllowed && context.selectedShop?.canWrite != false,
                        localReadsAllowed = previous.localReadsAllowed && resolved.localReadsAllowed && context.localAccessAllowed
                    )
                }
            )
            signedIn.takeIf {
                published && allowsResolvedBusinessDataScope(catalogSyncStateTracker.businessDataScopeState.value, activeScope)
            }
        }
        activation?.let {
            activateRemoteComponentsForBoundScope(it, "sync_recovery_complete")
            val context = shopContextRepository.state.value
            if (currentAuthAndShopMatch(it.userId, selectedShop) && context.localAccessAllowed &&
                context.selectedShop?.canWrite != false &&
                catalogSyncStateTracker.businessDataScopeState.value.localWritesAllowed &&
                catalogSyncStateTracker.allowsBusinessDataScope(it.userId, selectedShop)
            ) {
                // Recovery may outlive the original Save tickle (or the process).
                // The existing push path rechecks the durable queue and current scope.
                catalogAutoSyncCoordinator.onLocalCatalogChanged()
            }
        }
    }

    private fun cancelBusinessRecovery() {
        val job = synchronized(businessRecoveryLock) {
            businessRecoveryJob.also { businessRecoveryJob = null }
        }
        job?.cancel()
    }

    private fun cancelShopContextRecovery() {
        val recoveryJob = synchronized(shopContextRecoveryLock) {
            shopContextRecoveryJob.also { shopContextRecoveryJob = null }
        }
        recoveryJob?.cancel()
    }

    private fun unregisterNetworkAutoSyncTrigger() {
        val callback = networkCallback ?: return
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }.onFailure { throwable ->
            Log.w(TAG, "Network: unregister callback auto-sync fallito", throwable)
        }
        networkCallback = null
        synchronized(networkLock) {
            validatedNetworks.clear()
        }
    }

    private fun registerShopDeviceBestEffort(state: AuthState, reason: String) {
        val signedIn = state as? AuthState.SignedIn ?: return
        if (!shopDeviceRegistrationRemoteDataSource.isConfigured) return
        if (!currentBusinessDataScopeAllowsSync()) return

        val now = System.currentTimeMillis()
        val context = shopContextRepository.state.value
        if (context.ownerUserId != signedIn.userId) return
        val selectedShop = context.selectedShop
        val shopId = context.activeShopId
        val registrationScope = "${signedIn.userId}:${shopId ?: "legacy"}"
        val sameRecentUser =
            lastShopDeviceRegistrationScope == registrationScope &&
                now - lastShopDeviceRegistrationAtMs < 60_000L
        if (sameRecentUser) return

        lastShopDeviceRegistrationScope = registrationScope
        lastShopDeviceRegistrationAtMs = now

        appScope.launch {
            try {
                catalogSyncStateTracker.withBusinessDataScopeFlight(
                    ownerUserId = signedIn.userId,
                    selectedShop = selectedShop
                ) {
                    val result = withContext(Dispatchers.IO) {
                        shopDeviceAuthorizationRepository.registerHeartbeatAndCheck(reason, shopId)
                    }
                    val currentAuth = authManager.state.value as? AuthState.SignedIn
                    val currentContext = shopContextRepository.state.value
                    if (
                        currentAuth?.userId != signedIn.userId ||
                        currentContext.ownerUserId != signedIn.userId ||
                        shopScopedStoreScope(currentContext.selectedShop) !=
                        shopScopedStoreScope(selectedShop)
                    ) return@withBusinessDataScopeFlight
                    result.getOrNull()?.let { response ->
                        Log.i(
                            TAG,
                            "Shop device status reason=$reason status=${response.status} code=${response.code} canWrite=${response.canWrite}"
                        )
                        if (!response.canWrite) {
                            catalogSyncStateTracker.update(
                                CatalogSyncProgressState.failed(CatalogSyncStage.DEVICE_STATUS)
                            )
                        } else {
                            catalogAutoSyncCoordinator.onDeviceStatusActive()
                        }
                    }
                }
            } catch (_: Task126BusinessDataScopeChangedException) {
                Log.i(TAG, "Shop device status ignorato dopo cambio business scope")
            }
        }
    }

    private fun startShopDeviceStatusPolling() {
        if (shopDeviceStatusPollingJob?.isActive == true) return
        shopDeviceStatusPollingJob = appScope.launch {
            while (true) {
                val signedIn = authManager.state.value as? AuthState.SignedIn
                if (
                    signedIn != null &&
                    shopDeviceRegistrationRemoteDataSource.isConfigured &&
                    currentBusinessDataScopeAllowsSync()
                ) {
                    val context = shopContextRepository.state.value
                    val selectedShop = context.selectedShop
                    val shopId = context.activeShopId
                    try {
                        catalogSyncStateTracker.withBusinessDataScopeFlight(
                            ownerUserId = signedIn.userId,
                            selectedShop = selectedShop
                        ) {
                            val result = withContext(Dispatchers.IO) {
                                shopDeviceAuthorizationRepository.checkStatus(
                                    reason = "foreground_poll",
                                    force = false,
                                    shopId = shopId
                                )
                            }
                            result.getOrNull()?.let { snapshot ->
                                if (!snapshot.canWrite) {
                                    catalogSyncStateTracker.update(
                                        CatalogSyncProgressState.failed(CatalogSyncStage.DEVICE_STATUS)
                                    )
                                    Log.w(
                                        TAG,
                                        "Shop device foreground poll blocked status=${snapshot.status} code=${snapshot.code}"
                                    )
                                } else {
                                    catalogAutoSyncCoordinator.onDeviceStatusActive()
                                }
                            }
                        }
                    } catch (_: Task126BusinessDataScopeChangedException) {
                        Log.i(TAG, "Shop device poll ignorato dopo cambio business scope")
                    }
                }
                delay(15_000L)
            }
        }
    }

    private fun currentShopContextAllowsSync(ownerUserId: String): Boolean =
        shopContextRepository.state.value.let { context ->
            context.ownerUserId == ownerUserId &&
                !context.isLoading &&
                context.syncAllowed
        }

    private fun currentAuthAndShopMatch(
        ownerUserId: String,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop?
    ): Boolean {
        val signedIn = authManager.state.value as? AuthState.SignedIn ?: return false
        val context = shopContextRepository.state.value
        return signedIn.userId == ownerUserId &&
            context.ownerUserId == ownerUserId &&
            !context.isLoading &&
            context.syncAllowed &&
            shopScopedStoreScope(context.selectedShop) == shopScopedStoreScope(selectedShop)
    }

    private fun currentBusinessDataScopeAllowsSync(): Boolean {
        val signedIn = authManager.state.value as? AuthState.SignedIn ?: return false
        val context = shopContextRepository.state.value
        return context.ownerUserId == signedIn.userId &&
            !context.isLoading &&
            context.syncAllowed &&
            catalogSyncStateTracker.allowsBusinessDataScope(signedIn.userId, context.selectedShop)
    }

    private fun stopShopDeviceStatusPolling() {
        shopDeviceStatusPollingJob?.cancel()
        shopDeviceStatusPollingJob = null
    }
}

internal const val MAX_AUTOMATIC_SYNC_RECOVERY_ATTEMPTS_PER_TRIGGER = 5

/**
 * Ogni trigger esterno (relaunch, foreground o reconnect) apre una finestra
 * bounded. Il contatore durevole governa il backoff diagnostico ma non puo'
 * rendere il journal irrecuperabile dopo che la causa transitoria e' sparita.
 */
internal fun shouldAttemptAutomaticBusinessRecovery(
    attemptsInCurrentWindow: Int,
    durableAttemptCount: Int
): Boolean = durableAttemptCount >= 0 &&
    attemptsInCurrentWindow in 0 until MAX_AUTOMATIC_SYNC_RECOVERY_ATTEMPTS_PER_TRIGGER

internal fun allowsResolvedBusinessDataScope(
    state: Task126BusinessDataScopeState,
    activeScope: Task126OwnerStoreScope
): Boolean {
    if (state.status != Task126BusinessDataScopeStatus.READY) return false
    val boundScope = state.boundScope ?: return false
    return Task126OwnerStoreGate.validate(boundScope, activeScope) ==
        Task126OwnerStoreGateDecision.Allowed
}

internal fun shouldRetryShopContext(
    auth: AuthState,
    context: com.example.merchandisecontrolsplitview.data.ShopContext,
    networkAvailable: Boolean?
): Boolean =
    auth is AuthState.SignedIn &&
        networkAvailable == true &&
        !context.isLoading &&
        (context.ownerUserId != auth.userId || !context.syncAllowed)

/** Same observable SDK budget used by the caller; no private HTTP-state inference. */
internal fun checkpointTraceAuthWindowSafe(
    status: SessionStatus.Authenticated,
    scheduled: kotlin.time.Instant?,
    safeEnd: kotlin.time.Instant
): Boolean {
    val session = status.session
    if (session.expiresIn <= 0L || scheduled == null) return false
    val threshold = session.expiresAt - session.expiresIn.seconds * 0.2
    return session.expiresAt > safeEnd && threshold > safeEnd && scheduled > safeEnd
}

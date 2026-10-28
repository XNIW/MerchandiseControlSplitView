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
import com.example.merchandisecontrolsplitview.data.LocalStoreProofFlights
import com.example.merchandisecontrolsplitview.data.LocalStoreProofTransactionContext
import com.example.merchandisecontrolsplitview.data.SyncEventDeviceState
import com.example.merchandisecontrolsplitview.data.SyncEventWatermark
import com.example.merchandisecontrolsplitview.data.SyncRecoveryBaseline
import com.example.merchandisecontrolsplitview.data.SyncRecoveryJournal
import com.example.merchandisecontrolsplitview.data.SyncRecoveryJournalPhases
import com.example.merchandisecontrolsplitview.data.SyncRecoveryAuthorizationModes
import com.example.merchandisecontrolsplitview.data.SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.LocalStartupTrace
import com.example.merchandisecontrolsplitview.data.span
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
import com.example.merchandisecontrolsplitview.data.AutomaticRecoveryRetryMetadata
import com.example.merchandisecontrolsplitview.data.SyncErrorCategory
import com.example.merchandisecontrolsplitview.data.nextSyncRecoveryAttemptCount
import com.example.merchandisecontrolsplitview.data.encodeRecoveryCheckpointJson
import com.example.merchandisecontrolsplitview.data.validateShopSyncActiveReceipt
import androidx.room.withTransaction
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
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

internal sealed interface LocalStoreAvailability {
    data object Checking : LocalStoreAvailability
    data object Ready : LocalStoreAvailability
    data class Unavailable(val code: String) : LocalStoreAvailability
}

internal class ApplicationSupabaseConfiguration(val url: String, val publishableKey: String)

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
open class MerchandiseControlApplication : Application() {

    /** JVM fixtures can supply configuration before onCreate; production reads the same BuildConfig values. */
    internal open val supabaseConfiguration: ApplicationSupabaseConfiguration
        get() = ApplicationSupabaseConfiguration(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)

    companion object {
        private const val TAG = "MerchandiseApp"
        private const val SHOP_DATA_SCOPE_PREFS = "mobile_shop_context_data_scope"
        private const val KEY_LAST_BUSINESS_DATA_SCOPE = "last_business_data_scope"
        // Exact UTF-8 TEST URL digest from the authorized target projection; no normalization.
        private const val CHECKPOINT_TRACE_TEST_URL_SHA256 = "42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842"
    }

    /** Scope applicativo per osservatori lifecycle (auth → componenti remoti). */
    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _localStoreAvailability = MutableStateFlow<LocalStoreAvailability>(LocalStoreAvailability.Checking)
    internal val localStoreAvailability: StateFlow<LocalStoreAvailability> = _localStoreAvailability.asStateFlow()
    private val localStoreOpenLock = Any()
    private var localStoreOpenJob: Job? = null
    private var businessRuntimeInitialized = false
    private var applicationTerminated = false
    private val validatedNetworks = mutableSetOf<Network>()
    private val networkLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val shopContextRecoveryLock = Any()
    private var shopContextRecoveryJob: Job? = null
    private var lastShopDeviceRegistrationScope: String? = null
    private var lastShopDeviceRegistrationAtMs: Long = 0L
    private var shopDeviceStatusPollingJob: Job? = null
    private val businessDataScopeMutex = Mutex()
    private enum class LocalProofMode { OFFLINE, CLOUD }
    private data class LocalProofKey(
        val mode: LocalProofMode,
        val database: AppDatabase,
        val auth: AuthState,
        val identity: com.example.merchandisecontrolsplitview.data.LocalAuthIdentity,
        val context: com.example.merchandisecontrolsplitview.data.ShopContext,
        val epoch: Long,
        val device: String,
        val selected: com.example.merchandisecontrolsplitview.data.SelectedShop?
    )
    /** Ownership is acquired by a cancellable caller and handed to the admitted
     * application proof without an unlock/relock gap. Release is idempotent for
     * body-finally and the already-cancelled-appScope completion fallback. */
    private class LocalProofAdmission(private val mutex: Mutex) {
        private val owner = Any()
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        suspend fun acquire() { mutex.lock(owner) }
        fun isHeldBy(candidate: Mutex): Boolean = candidate === mutex && mutex.holdsLock(owner)
        fun release() {
            if (released.compareAndSet(false, true)) mutex.unlock(owner)
        }
    }

    private val localProofFlights = LocalStoreProofFlights<LocalProofKey>()
    private val businessRecoveryLock = Any()
    private val businessRecoveryExecutionMutex = Mutex()
    private var businessRecoveryJob: Job? = null
    private data class BusinessRecoveryReservation(
        val ownerUserId: String,
        val context: com.example.merchandisecontrolsplitview.data.ShopContext,
        val shopEpoch: Long
    )
    private var businessRecoveryReservation: BusinessRecoveryReservation? = null
    /** Identifies the existing job while it claims/completes a remote invocation; carries no retry budget. */
    private var businessRecoveryExecutingJob: Job? = null
    /** Internal deterministic clock seam; production keeps the existing wall clock. */
    internal var automaticBusinessRecoveryNowMs: () -> Long = System::currentTimeMillis
    private val automaticBusinessRecoveryProcessEpoch = java.util.UUID.randomUUID().toString()
    // Diagnostic-only state: unused, capturing, consumed. Never coordinates business work.
    private val localThreadSnapshotState = AtomicInteger(0)
    private var checkpointTraceConsumed = false
    private var checkpointTraceReservation: Any? = null

    private val shopDataScopePreferences by lazy {
        getSharedPreferences(SHOP_DATA_SCOPE_PREFS, Context.MODE_PRIVATE)
    }

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (!businessRuntimeInitialized) return
            realtimeRefreshCoordinator.onAppForeground()
            historySessionPushCoordinator.onAppForeground()
            catalogAutoSyncCoordinator.onAppForeground()
            retryShopContextIfNeeded("foreground")
            schedulePendingBusinessRecovery("foreground")
            registerShopDeviceBestEffort(authManager.state.value, "foreground")
            startShopDeviceStatusPolling()
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!businessRuntimeInitialized) return
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
        val configuration = supabaseConfiguration
        val configPresent = configuration.url.isNotBlank() && configuration.publishableKey.isNotBlank()
        if (configPresent) {
            try {
                val settings = com.example.merchandisecontrolsplitview.data.CheckedAuthSettings(
                    applicationContext.getSharedPreferences(
                        "${applicationContext.packageName}_preferences",
                        Context.MODE_PRIVATE
                    )
                )
                val settingsKey = createDefaultSettingsKey(configuration.url.split("//").last())
                val sessionKey = "$settingsKey-${SettingsSessionManager.SETTINGS_KEY}"
                val verifierKey = "$settingsKey-${SettingsCodeVerifierCache.SETTINGS_KEY}"
                // Instantiate SDK migration once; every client receives a generation-bound lease.
                val sessionManager = com.example.merchandisecontrolsplitview.data.ProjectSessionPersistence(
                    settings = settings,
                    sessionKey = sessionKey,
                    projectUrl = configuration.url
                )
                val verifierCache = SettingsCodeVerifierCache(settings = settings, key = verifierKey)
                GenerationOwnedSupabaseClient(
                    sessionManager = sessionManager,
                    codeVerifierCache = verifierCache,
                    isSessionStored = { sessionManager.isSessionStored() },
                    factory = { sessionLease, verifierLease ->
                        createSupabaseClient(
                            supabaseUrl = configuration.url,
                            supabaseKey = configuration.publishableKey
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
                {
                    check(localStoreAvailability.value is LocalStoreAvailability.Ready) { "local_store_unavailable" }
                    deviceInstallIdProvider.getOrCreate()
                }
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
                storageBaseUrl = supabaseConfiguration.url,
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
                val observer = com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryTestHooks.activationBoundaryTiming
                var observed = false
                try {
                    businessDataScopeMutex.withLock {
                        catalogSyncStateTracker.withBusinessDataScopeTransition {
                            if (observer != null) {
                                observed = true
                                com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryTestHooks.observeTiming(observer, "acquired")
                            }
                            block()
                        }
                    }
                } finally {
                    if (observed) com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryTestHooks.observeTiming(observer, "released")
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
            currentOwnerUserId = { currentLocalOwnerUserId() },
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
        val trace = LocalStartupTrace.start(null)
        trace?.event("app_on_create")
        var traceOutcome = "ok"
        try {
            // This span measures dispatch/lazy initialization, not asynchronous session restoration.
            trace.span("auth_restore_dispatch") { authManager.restoreSession() }
            retryLocalStoreOpen()
        } catch (error: Throwable) {
            traceOutcome = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally { trace?.finish(traceOutcome) }
    }

    /** The first real Room open/migration occurs here, before any business consumer exists. */
    internal open suspend fun qualifyLocalStoreForBootstrap() {
        database.openHelper.writableDatabase
    }

    /** Explicit retry only. Concurrent taps join the same open attempt. */
    internal fun retryLocalStoreOpen() {
        synchronized(localStoreOpenLock) {
            if (applicationTerminated || businessRuntimeInitialized || localStoreOpenJob?.isActive == true) return
            val trace = LocalStartupTrace.start(null)
            _localStoreAvailability.value = LocalStoreAvailability.Checking
            trace?.event("room_checking")
            val opening = appScope.launch(start = CoroutineStart.LAZY) {
                var traceOutcome = "ok"
                try {
                    try {
                        trace.span("room_open") { withContext(Dispatchers.IO) { qualifyLocalStoreForBootstrap() } }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _localStoreAvailability.value = LocalStoreAvailability.Unavailable("local_store_open_failed")
                        trace?.event("room_unavailable", "room_open_failed")
                        traceOutcome = "unavailable"
                        return@launch
                    }
                    if (applicationTerminated) {
                        trace?.event("scope_discarded", "application_terminated")
                        traceOutcome = "discarded"
                        return@launch
                    }
                    trace.span("business_consumers_init") {
                        realtimeRefreshCoordinator
                        historySessionPushCoordinator
                        catalogAutoSyncCoordinator
                    }
                    businessRuntimeInitialized = true
                    ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
                    registerNetworkAutoSyncTrigger()
                    observeAuthForRemoteComponents()
                    observeShopContextForRemoteComponents()
                    _localStoreAvailability.value = LocalStoreAvailability.Ready
                    trace?.event("room_ready")
                } catch (error: Throwable) {
                    traceOutcome = if (error is CancellationException) "cancelled" else "failed"
                    throw error
                } finally { trace?.finish(traceOutcome) }
            }
            localStoreOpenJob = opening
            // Also observe a lazy job cancelled before its body can start; never change its outcome.
            trace?.let { observer -> opening.invokeOnCompletion { error ->
                if (error is CancellationException) observer.finish("cancelled")
            } }
            opening.start()
        }
    }

    override fun onTerminate() {
        applicationTerminated = true
        appScope.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        unregisterNetworkAutoSyncTrigger()
        if (businessRuntimeInitialized) {
            realtimeSessionSubscriber.shutdown()
            realtimeRefreshCoordinator.shutdown()
            historySessionPushCoordinator.shutdown()
            catalogAutoSyncCoordinator.shutdown()
            cancelBusinessRecovery()
        }
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
     * - `Checking` → cloud sospeso; identità persistita e binding locale qualificati separatamente.
     */
    private fun observeAuthForRemoteComponents() {
        appScope.launch {
            combine(authManager.state, authManager.localIdentity) { state, identity -> state to identity }
                .collectLatest { (state, identity) ->
                when (state) {
                    is AuthState.Checking -> {
                        cancelShopContextRecovery()
                        cancelBusinessRecovery()
                        Log.d(TAG, "Auth: verifica sessione in corso")
                        val previous = catalogSyncStateTracker.businessDataScopeState.value
                        val context = shopContextRepository.state.value
                        val preserved = previous.boundScope?.takeIf {
                            identity != null && authManager.isCurrentLocalIdentity(identity) &&
                                context.ownerUserId == identity.userId && context.localAccessAllowed &&
                                deviceInstallIdProvider.currentId != null && previous.allowsLocalOperations &&
                                Task126OwnerStoreGate.validate(it, task126ActiveOwnerStoreScope(identity.userId, context.selectedShop)) ==
                                    Task126OwnerStoreGateDecision.Allowed
                        }
                        catalogSyncStateTracker.updateBusinessDataScopeState(if (preserved == null)
                            Task126BusinessDataScopeState.checking() else previous.copy(
                                status = Task126BusinessDataScopeStatus.CHECKING, localAccessScope = preserved))
                        suspendRemoteComponentsForBusinessScope()
                        if (identity != null && authManager.isCurrentLocalIdentity(identity)) {
                            val restoredDevice = withContext(Dispatchers.IO) {
                                val existing = database.syncEventDeviceStateDao().get()?.deviceId
                                if (existing == null || !authManager.isCurrentLocalIdentity(identity)) null
                                else {
                                    val loaded = deviceInstallIdProvider.getOrCreate()
                                    if (loaded != existing || !authManager.isCurrentLocalIdentity(identity)) null
                                    else {
                                        shopContextRepository.restoreLocalAuthorization(identity.userId)
                                        existing
                                    }
                                }
                            }
                            if (restoredDevice != null && authManager.isCurrentLocalIdentity(identity)) {
                                publishOfflineBusinessScope(shopContextRepository.state.value, "auth_checking_restored")
                            }
                        }
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
                        publishOfflineBusinessScope(shopContextRepository.state.value, "auth_signed_in_restored")
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
                            return@collectLatest
                        }
                        if (!currentShopContextAllowsSync(state.userId)) {
                            Log.w(TAG, "Shop context: sync cloud sospesa per errore linked-shops")
                            publishOfflineBusinessScope(refreshedContext, "auth_refresh_unavailable")
                            suspendRemoteComponentsForBusinessScope()
                            return@collectLatest
                        }
                        if (!alignBusinessDataScope(state.userId, refreshedContext.selectedShop, "auth_refresh_ready")) {
                            suspendRemoteComponentsForBusinessScope()
                            return@collectLatest
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

    private fun currentLocalOwnerUserId(): String? = when (val state = authManager.state.value) {
        is AuthState.SignedIn -> state.userId
        is AuthState.Checking -> authManager.localIdentity.value?.takeIf(authManager::isCurrentLocalIdentity)?.userId
        else -> null
    }

    private fun observeShopContextForRemoteComponents() {
        appScope.launch {
            shopContextRepository.state.collect { context ->
                invalidateObsoleteBusinessRecoveryReservation()
                val owner = currentLocalOwnerUserId() ?: return@collect
                if (context.ownerUserId != owner) {
                    Log.i(TAG, "Shop context: owner non corrente, sync cloud sospesa")
                    catalogSyncStateTracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
                    suspendRemoteComponentsForBusinessScope()
                    return@collect
                }
                val signedIn = authManager.state.value as? AuthState.SignedIn
                if (signedIn == null || context.isLoading || !context.syncAllowed) {
                    publishOfflineBusinessScope(context, "shop_context_offline")
                    suspendRemoteComponentsForBusinessScope()
                    return@collect
                }
                if (!alignBusinessDataScope(signedIn.userId, context.selectedShop, "shop_context_ready")) {
                    suspendRemoteComponentsForBusinessScope()
                    return@collect
                }
                activateRemoteComponentsForBoundScope(signedIn, "shop_context")
            }
        }
    }

    // A SignedIn emission may precede its local-identity token; the existing combined observer retries.
    private fun canStartOfflinePublication(identity: com.example.merchandisecontrolsplitview.data.LocalAuthIdentity?): Boolean =
        identity != null

    /** Sharing is limited to callers registered before the owner's original transaction exits.
     * This is not a completed-proof cache; every later admission still runs the original resolver. */
    private fun localProofAuthKey(
        mode: LocalProofMode, auth: AuthState,
        identity: com.example.merchandisecontrolsplitview.data.LocalAuthIdentity
    ): AuthState? {
        if (auth !is AuthState.Checking && (auth !is AuthState.SignedIn || auth.userId != identity.userId)) return null
        // Local authority is the current identity generation, not its progress
        // from restore checking to cloud-ready. Cloud proofs retain exact auth.
        return if (mode == LocalProofMode.OFFLINE) AuthState.Checking else auth
    }

    private fun currentLocalProofKey(
        mode: LocalProofMode,
        context: com.example.merchandisecontrolsplitview.data.ShopContext,
        selected: com.example.merchandisecontrolsplitview.data.SelectedShop? = context.selectedShop
    ): LocalProofKey? {
        val auth = authManager.state.value
        val identity = authManager.localIdentity.value ?: return null
        val proofAuth = localProofAuthKey(mode, auth, identity) ?: return null
        val epoch = shopContextRepository.diagnosticShopEpoch()
        val device = deviceInstallIdProvider.currentId ?: return null
        val key = LocalProofKey(mode, database, proofAuth, identity, context, epoch, device, selected)
        return key.takeIf {
            authManager.state.value == auth && authManager.localIdentity.value == identity &&
                authManager.isCurrentLocalIdentity(identity) && shopContextRepository.state.value == context &&
                shopContextRepository.diagnosticShopEpoch() == epoch && deviceInstallIdProvider.currentId == device
        }
    }

    private fun capturedOfflineLocalProofKey(
        auth: AuthState,
        identity: com.example.merchandisecontrolsplitview.data.LocalAuthIdentity?,
        context: com.example.merchandisecontrolsplitview.data.ShopContext,
        epoch: Long,
        device: String
    ): LocalProofKey? {
        if (identity == null) return null
        val proofAuth = localProofAuthKey(LocalProofMode.OFFLINE, auth, identity) ?: return null
        val captured = LocalProofKey(LocalProofMode.OFFLINE, database, proofAuth, identity,
            context, epoch, device, context.selectedShop)
        // Never label an A-captured proof with a freshly observed replacement identity B.
        return captured.takeIf { currentLocalProofKey(LocalProofMode.OFFLINE, context) == it }
    }

    private fun joinCurrentLocalProof(
        mode: LocalProofMode,
        context: com.example.merchandisecontrolsplitview.data.ShopContext,
        selected: com.example.merchandisecontrolsplitview.data.SelectedShop? = context.selectedShop
    ): LocalStoreProofFlights.Entry<LocalProofKey>? {
        if (!localProofFlights.hasOpen()) return null
        val key = currentLocalProofKey(mode, context, selected) ?: return null
        return localProofFlights.tryJoin(key)
    }

    private suspend fun <T> withLocalProofTransaction(
        flight: LocalStoreProofFlights.Entry<LocalProofKey>?, block: suspend () -> T
    ): T = if (flight == null) block() else withContext(
        LocalStoreProofTransactionContext { localProofFlights.seal(flight) }
    ) { block() }

    private suspend fun publishOfflineBusinessScope(context: com.example.merchandisecontrolsplitview.data.ShopContext): Unit =
        publishOfflineBusinessScope(context, "direct")

    private suspend fun publishOfflineBusinessScope(
        context: com.example.merchandisecontrolsplitview.data.ShopContext, admissionReason: String
    ) {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val joined = joinCurrentLocalProof(LocalProofMode.OFFLINE, context)
        if (joined != null) {
            // Reuse the original join path and its diagnostics without creating
            // another application-owned job for a subscribing caller.
            publishOfflineBusinessScopeOwned(context, admissionReason, joined)
            return
        }
        // Waiting belongs to the caller. The acquired mutex remains held while
        // its exact owner token is handed to the application-owned proof.
        val admission = LocalProofAdmission(businessDataScopeMutex)
        admission.acquire()
        var handedOff = false
        try {
            callerContext.ensureActive()
            val proof = appScope.async(callerContext.minusKey(Job), start = CoroutineStart.UNDISPATCHED) {
                try { publishOfflineBusinessScopeOwned(context, admissionReason, admission = admission) }
                finally { admission.release() }
            }
            // An already-cancelled appScope may never enter the body.
            proof.invokeOnCompletion { admission.release() }
            handedOff = true
            proof.await()
        } finally {
            if (!handedOff) admission.release()
        }
    }

    private suspend fun withLocalProofAdmission(admission: LocalProofAdmission?, block: suspend () -> Unit) {
        if (admission == null) {
            businessDataScopeMutex.withLock { block() }
        } else {
            check(admission.isHeldBy(businessDataScopeMutex))
            try { block() } finally { admission.release() }
        }
    }

    private suspend fun publishOfflineBusinessScopeOwned(
        context: com.example.merchandisecontrolsplitview.data.ShopContext, admissionReason: String,
        joinedProof: LocalStoreProofFlights.Entry<LocalProofKey>? = null,
        admission: LocalProofAdmission? = null
    ) {
        val trace = if (BuildConfig.DEBUG) LocalStartupTrace.start(LocalStartupTrace.hashIdentity(
            "owner:${context.ownerUserId.orEmpty()}\nshop:${context.selectedShop?.shopId.orEmpty()}")) else null
        trace?.admission(admissionReason)
        trace?.event("offline_scope")
        var traceOutcome = "ok"
        var ownedFlight: LocalStoreProofFlights.Entry<LocalProofKey>? = null
        var ownedFailure: Throwable? = null
        try {
            val joined = joinedProof ?: joinCurrentLocalProof(LocalProofMode.OFFLINE, context)
            if (joined != null) {
                trace?.event("proof_joined")
                joined.completion.await()
                return
            }
            trace?.event("mutex_requested")
            withLocalProofAdmission(admission) {
                try {
                trace?.event("mutex_acquired")
                val authState = authManager.state.value
                val identity = authManager.localIdentity.value
                if (authState is AuthState.SignedIn && !canStartOfflinePublication(identity)) {
                    trace?.event("scope_discarded", "local_identity_not_current")
                    traceOutcome = "discarded"
                    return@withLocalProofAdmission
                }
                val owner = currentLocalOwnerUserId() ?: run {
                    trace?.event("scope_discarded", "owner_unavailable"); traceOutcome = "discarded"; return@withLocalProofAdmission
                }
                val shopEpoch = shopContextRepository.diagnosticShopEpoch()
                val device = deviceInstallIdProvider.currentId ?: run {
                    trace?.event("scope_discarded", "device_unavailable"); traceOutcome = "discarded"; return@withLocalProofAdmission
                }
                fun fact(reason: String, block: () -> Boolean): Boolean = trace?.fact(reason, block) ?: block()
                fun authorityCurrent(): Boolean = if (identity == null) {
                    fact("local_identity_not_current") { false }
                } else fact("auth_state_changed") {
                    localProofAuthKey(LocalProofMode.OFFLINE, authManager.state.value, identity) != null
                } && fact("local_identity_changed") {
                    val currentIdentity = authManager.localIdentity.value
                    trace?.localIdentityEquals(identity, currentIdentity) ?: (currentIdentity == identity)
                } && fact("local_identity_not_current") { authManager.isCurrentLocalIdentity(identity) }
                fun contextCurrent(): Boolean = authorityCurrent() &&
                    fact("shop_context_changed") { shopContextRepository.state.value == context } &&
                    fact("shop_epoch_changed") { shopContextRepository.diagnosticShopEpoch() == shopEpoch } &&
                    fact("device_changed") { deviceInstallIdProvider.currentId == device }
                // Preserve the original OR short circuit: no owner comparison when current authority fails.
                if (!contextCurrent()) {
                    trace?.event("scope_discarded", "stale_result"); traceOutcome = "discarded"; return@withLocalProofAdmission
                }
                if (context.ownerUserId != owner) {
                    trace?.event("scope_discarded", "context_owner_mismatch"); traceOutcome = "discarded"; return@withLocalProofAdmission
                }
                val previous = catalogSyncStateTracker.businessDataScopeState.value
                if (!context.localAccessAllowed) {
                    catalogSyncStateTracker.updateBusinessDataScopeState(previous.copy(
                        status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, localAccessScope = null,
                        errorCode = "shop_context_unavailable", localWritesAllowed = false, localReadsAllowed = false))
                    trace?.event("scope_unavailable", "local_access_disallowed"); traceOutcome = "unavailable"
                    return@withLocalProofAdmission
                }
                val activeScope = task126ActiveOwnerStoreScope(owner, context.selectedShop)
                val key = capturedOfflineLocalProofKey(authState, identity, context, shopEpoch, device)
                ownedFlight = key?.let(localProofFlights::begin)
                val resolved = trace.span("resolve") {
                    withLocalProofTransaction(ownedFlight) {
                        repository.resolveOfflineBusinessDataScope(activeScope, context.selectedShop?.canWrite != false, trace)
                    }
                }
                trace?.event("scope_resolved")
                val persistedDevice = trace.span("persisted_device") {
                    withContext(Dispatchers.IO) { database.syncEventDeviceStateDao().get()?.deviceId }
                }
                if (contextCurrent() && fact("persisted_device_changed") { persistedDevice == device }) {
                    catalogSyncStateTracker.updateBusinessDataScopeState(resolved)
                    trace?.event("scope_published", if (resolved.allowsLocalOperations) {
                        if (resolved.localWritesAllowed) "local_read_write" else "local_read_only"
                    } else "local_access_unavailable")
                } else {
                    trace?.event("scope_discarded", "stale_result"); traceOutcome = "discarded"
                }
                } catch (error: Throwable) {
                    ownedFailure = error
                    throw error
                } finally {
                    ownedFlight?.let { localProofFlights.finish(it, ownedFailure) }
                }
            }
        } catch (error: Throwable) {
            traceOutcome = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally { trace?.finish(traceOutcome) }
    }

    private suspend fun alignBusinessDataScope(
        ownerUserId: String,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop?
    ): Boolean = alignBusinessDataScope(ownerUserId, selectedShop, "direct")

    private suspend fun alignBusinessDataScope(
        ownerUserId: String,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop?, admissionReason: String
    ): Boolean {
        val trace = if (BuildConfig.DEBUG) LocalStartupTrace.start(LocalStartupTrace.hashIdentity(
            "owner:$ownerUserId\nshop:${selectedShop?.shopId.orEmpty()}")) else null
        trace?.admission(admissionReason)
        trace?.event("cloud_align")
        var traceOutcome = "ok"
        var ownedFlight: LocalStoreProofFlights.Entry<LocalProofKey>? = null
        var ownedFailure: Throwable? = null
        try {
            val currentContext = shopContextRepository.state.value
            val joined = joinCurrentLocalProof(LocalProofMode.CLOUD, currentContext, selectedShop)
            if (joined != null) {
                trace?.event("proof_joined")
                joined.completion.await()
                return currentLocalProofKey(LocalProofMode.CLOUD, currentContext, selectedShop) == joined.key &&
                    currentAuthAndShopMatch(ownerUserId, selectedShop) && allowsResolvedBusinessDataScope(
                        catalogSyncStateTracker.businessDataScopeState.value,
                        task126ActiveOwnerStoreScope(ownerUserId, selectedShop))
            }
            trace?.event("mutex_requested")
            return businessDataScopeMutex.withLock {
            try {
            trace?.event("mutex_acquired")
            var joiningPermitted = false
            val resolveScope: suspend () -> Boolean = resolveScope@ {
                if (!currentAuthAndShopMatch(ownerUserId, selectedShop)) {
                    catalogSyncStateTracker.updateBusinessDataScopeState(
                        Task126BusinessDataScopeState.checking()
                    )
                    trace?.event("scope_discarded", "stale_result"); traceOutcome = "discarded"
                    return@resolveScope false
                }
                val activeScope = task126ActiveOwnerStoreScope(ownerUserId, selectedShop)
                val legacyValue = shopDataScopePreferences.getString(KEY_LAST_BUSINESS_DATA_SCOPE, null)
                val legacyScope = parseLegacyBusinessDataScope(legacyValue)
                val key = if (joiningPermitted)
                    currentLocalProofKey(LocalProofMode.CLOUD, shopContextRepository.state.value, selectedShop) else null
                ownedFlight = key?.let(localProofFlights::begin)
                val state = try {
                    trace.span("resolve") {
                        withLocalProofTransaction(ownedFlight) {
                            withContext(Dispatchers.IO + (trace ?: kotlin.coroutines.EmptyCoroutineContext)) {
                                repository.resolveBusinessDataScope(activeScope, legacyScope)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Business scope: risoluzione binding fallita", error)
                    trace?.event("scope_unavailable", "validation_failed"); traceOutcome = "unavailable"
                    Task126BusinessDataScopeState(
                        status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                        errorCode = "binding_resolution_failed"
                    )
                }
                if (!currentAuthAndShopMatch(ownerUserId, selectedShop)) {
                    catalogSyncStateTracker.updateBusinessDataScopeState(
                        Task126BusinessDataScopeState.checking()
                    )
                    trace?.event("scope_discarded", "stale_result"); traceOutcome = "discarded"
                    return@resolveScope false
                }
                val published = state.copy(localWritesAllowed = selectedShop?.canWrite != false && shopContextRepository.state.value.localAccessAllowed,
                    localReadsAllowed = shopContextRepository.state.value.localAccessAllowed)
                catalogSyncStateTracker.updateBusinessDataScopeState(published)
                trace?.event("scope_published", if (published.allowsLocalOperations) {
                    if (published.localWritesAllowed) "local_read_write" else "local_read_only"
                } else "local_access_unavailable")
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
            joiningPermitted = previous.allowsLocalOperations && previous.boundScope?.let {
                Task126OwnerStoreGate.validate(it, active) == Task126OwnerStoreGateDecision.Allowed
            } == true
            if (joiningPermitted) resolveScope()
            else catalogSyncStateTracker.withBusinessDataScopeTransition { resolveScope() }
            } catch (error: Throwable) {
                ownedFailure = error
                throw error
            } finally {
                ownedFlight?.let { localProofFlights.finish(it, ownedFailure) }
            }
            }
        } catch (error: Throwable) {
            traceOutcome = if (error is CancellationException) "cancelled" else "failed"
            throw error
        } finally { trace?.finish(traceOutcome) }
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
        val lazyValue = MerchandiseControlApplication::class.java.getDeclaredField(name + "\$delegate")
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
            .digest(supabaseConfiguration.url.encodeToByteArray()).joinToString("") { "%02x".format(it) }
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
        val digest = MessageDigest.getInstance("SHA-256").digest(supabaseConfiguration.url.encodeToByteArray())
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
            val code = journal?.reason?.let { AutomaticRecoveryRetryMetadata.decode(it)?.code ?: it }
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
                            .digest(supabaseConfiguration.url.encodeToByteArray())
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
        val signedIn = authManager.state.value as? AuthState.SignedIn ?: return false
        val context = shopContextRepository.state.value
        val selectedShop = context.selectedShop ?: return false
        val shopEpoch = shopContextRepository.diagnosticShopEpoch()
        if (!currentAuthAndShopMatch(signedIn.userId, selectedShop)) return false
        val reservation = BusinessRecoveryReservation(signedIn.userId, context, shopEpoch)
        val scheduled = synchronized(businessRecoveryLock) {
            if (checkpointTraceReservation != null) return true
            if (shopContextRepository.state.value != context || shopContextRepository.diagnosticShopEpoch() != shopEpoch ||
                !currentAuthAndShopMatch(signedIn.userId, selectedShop)) return false
            if (businessRecoveryJob?.isCompleted == false &&
                (businessRecoveryReservation == reservation ||
                    (businessRecoveryExecutingJob === businessRecoveryJob && businessRecoveryReservation?.let {
                        sameBusinessRecoveryEntityCurrent(it)
                    } == true))) return true
            // Retire only the old reservation; its persisted deadline and consumed claim remain authoritative.
            businessRecoveryJob?.cancel()
            lateinit var job: Job
            job = appScope.launch(start = CoroutineStart.LAZY) {
                fun authorityCurrent() = shopContextRepository.state.value == context &&
                    shopContextRepository.diagnosticShopEpoch() == shopEpoch &&
                    currentAuthAndShopMatch(signedIn.userId, selectedShop)
                try {
                    val activeScope = task126ActiveOwnerStoreScope(signedIn.userId, selectedShop)
                    while (authorityCurrent()) {
                        val journal = withContext(Dispatchers.IO) {
                            database.syncRecoveryJournalDao().getForScope(activeScope.ownerHash, activeScope.storeId)
                        } ?: return@launch
                        val metadata = AutomaticRecoveryRetryMetadata.decode(journal.reason)
                        if (metadata?.state == "in_flight") {
                            // The execution mutex releases only after the previous owner has stopped.
                            // Its consumed claim remains counted even when the process/response was lost.
                            val qualified = businessRecoveryExecutionMutex.withLock {
                                if (!authorityCurrent()) return@withLock false
                                val now = automaticBusinessRecoveryNowMs()
                                if (now < maxOf(0L, journal.createdAtMs, metadata.lastAttemptAtMs) ||
                                    now > Long.MAX_VALUE - AUTOMATIC_SYNC_RECOVERY_COOLDOWN_MS) return@withLock false
                                withContext(Dispatchers.IO) {
                                    if (!shopSyncRecoveryCoordinator.qualifyInterruptedAutomaticRecovery(
                                            journal, signedIn.userId, selectedShop, activeScope)) return@withContext false
                                    database.withTransaction {
                                        if (!authorityCurrent() || database.syncRecoveryJournalDao().get() != journal ||
                                            database.syncEventDeviceStateDao().get()?.deviceId != journal.deviceId) false
                                        else {
                                            val due = maxOf(journal.nextRetryAtMs ?: 0L, metadata.nextEligibleAtMs,
                                                now + AUTOMATIC_SYNC_RECOVERY_COOLDOWN_MS)
                                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(journal.reason.encodeToByteArray()).joinToString("") { "%02x".format(it) }
                                            val waiting = metadata.copy(state = "requalifying", nextEligibleAtMs = due,
                                                processEpoch = automaticBusinessRecoveryProcessEpoch,
                                                requalificationObservedAtMs = now, previousClaimSha256 = digest)
                                            database.syncRecoveryJournalDao().upsert(journal.copy(reason = waiting.encode(),
                                                updatedAtMs = now, nextRetryAtMs = due))
                                            true
                                        }
                                    }
                                }
                            }
                            if (!qualified) return@launch
                            continue
                        }
                        val legacyResume = if (metadata?.legacyResume != null ||
                            (metadata == null && journal.reason == "recovery_failed" && journal.attemptCount == 5)) {
                            withContext(Dispatchers.IO) {
                                shopSyncRecoveryCoordinator.qualifyLegacyAutomaticRecovery(journal, signedIn.userId,
                                    selectedShop, activeScope, metadata?.legacyResume)
                            }
                        } else null
                        if (metadata?.legacyResume != null && legacyResume == null) return@launch
                        if (metadata == null && legacyResume == null) {
                            val confirmedIntent = journal.reason == SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED
                            val initialConfirmedIntent = confirmedIntent &&
                                journal.authorizationMode == SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED &&
                                journal.phase == SyncRecoveryJournalPhases.REQUIRED && journal.attemptCount == 1 &&
                                journal.shopId.equals(selectedShop.shopId, ignoreCase = true) &&
                                journal.runId == null && journal.stagingDatabaseName == null &&
                                journal.checkpointADigest == null && journal.checkpointBDigest == null &&
                                journal.blockingEventId == null && journal.createdAtMs >= 0L &&
                                journal.updatedAtMs == journal.createdAtMs && journal.nextRetryAtMs == journal.createdAtMs
                            // An existing unclassified failure cannot acquire a fresh budget at launch/reconnect.
                            if ((confirmedIntent && !initialConfirmedIntent) || (!confirmedIntent && journal.attemptCount != 0) ||
                                journal.reason.trimStart().startsWith("{") ||
                                journal.reason in setOf("recovery_failed", "Unknown", "unknown") ||
                                !journal.reason.matches(Regex("[A-Za-z0-9_]{1,160}"))) return@launch
                        } else if (metadata != null && metadata.state != "requalifying" &&
                            (!metadata.isTransient || metadata.state !in setOf("waiting", "cooldown"))) {
                            return@launch
                        }
                        val due = maxOf(journal.nextRetryAtMs ?: 0L, metadata?.nextEligibleAtMs ?: 0L,
                            if (metadata == null && legacyResume != null) journal.updatedAtMs + 300_000L else 0L)
                        val waitMs = due - automaticBusinessRecoveryNowMs()
                        if (waitMs > 0L) {
                            // The existing single-flight job owns one opportunity; no execution lease is held while waiting.
                            delay(waitMs)
                            continue
                        }
                        if (!authorityCurrent() || catalogSyncStateTracker.networkAvailable.value == false) return@launch
                        businessRecoveryExecutionMutex.withLock {
                            if (!authorityCurrent() || catalogSyncStateTracker.networkAvailable.value == false) return@launch
                            if (metadata?.state == "requalifying" && !withContext(Dispatchers.IO) {
                                    shopSyncRecoveryCoordinator.qualifyInterruptedAutomaticRecovery(journal, signedIn.userId, selectedShop, activeScope)
                                }) return@launch
                            val attempts = if (metadata == null && legacyResume != null) 4
                                else if (metadata?.state == "cooldown" || (metadata?.state == "requalifying" &&
                                    metadata.windowAttempts == MAX_AUTOMATIC_SYNC_RECOVERY_ATTEMPTS_PER_TRIGGER)) 0
                                else metadata?.windowAttempts ?: 0
                            if (!shouldAttemptAutomaticBusinessRecovery(attempts, journal.attemptCount)) return@launch
                            val now = automaticBusinessRecoveryNowMs()
                            if (now < 0L) return@launch
                            val claim = AutomaticRecoveryRetryMetadata(
                                code = metadata?.code ?: journal.reason,
                                category = metadata?.category ?: SyncErrorCategory.Unknown,
                                windowAttempts = attempts + 1, state = "in_flight",
                                nextEligibleAtMs = maxOf(due, now), claimID = java.util.UUID.randomUUID().toString(),
                                lastAttemptAtMs = now, lastCompletedAtMs = null, legacyResume = legacyResume,
                                processEpoch = automaticBusinessRecoveryProcessEpoch)
                            synchronized(businessRecoveryLock) { businessRecoveryExecutingJob = job }
                            try {
                                val claimed = withContext(Dispatchers.IO) {
                                    database.withTransaction {
                                        val current = database.syncRecoveryJournalDao().get()
                                        if (!authorityCurrent() || catalogSyncStateTracker.networkAvailable.value == false || current != journal ||
                                            !journal.shopId.equals(selectedShop.shopId, ignoreCase = true) || journal.deviceId.isBlank() ||
                                            database.syncEventDeviceStateDao().get()?.deviceId != journal.deviceId) false
                                        else {
                                            database.syncRecoveryJournalDao().upsert(journal.copy(
                                                reason = claim.encode(), attemptCount = nextSyncRecoveryAttemptCount(journal.attemptCount), updatedAtMs = now))
                                            true
                                        }
                                    }
                                }
                                if (!claimed) return@launch
                                val result = shopSyncRecoveryCoordinator.recover(signedIn.userId, selectedShop, activeScope,
                                    automaticClaimID = claim.claimID,
                                    awaitScopeResolution = { remainingMs ->
                                        awaitBusinessRecoveryScopeResolution(job, reservation, activeScope,
                                            claim.claimID, journal.deviceId, remainingMs)
                                    })
                                if (result is ShopSyncRecoveryResult.Activated) {
                                    if (sameBusinessRecoveryEntityCurrent(reservation) &&
                                        currentAuthAndShopMatch(signedIn.userId, selectedShop)) {
                                        completeBusinessRecovery(signedIn, selectedShop, activeScope, result)
                                    }
                                    return@launch
                                }
                                val retry = result as? ShopSyncRecoveryResult.RetryRequired
                                val code = retry?.code ?: (result as ShopSyncRecoveryResult.Rejected).code
                                val category = retry?.classification?.category ?: SyncErrorCategory.Unknown
                                val transient = category in setOf(SyncErrorCategory.NetworkOfflineOrTimeout,
                                    SyncErrorCategory.RateLimited, SyncErrorCategory.RemoteTransient) && retry?.classification?.retryAfterInvalid != true
                                val completedAt = automaticBusinessRecoveryNowMs()
                                if (completedAt < 0L || completedAt > Long.MAX_VALUE - AUTOMATIC_SYNC_RECOVERY_COOLDOWN_MS) return@launch
                                val completionAccepted = withContext(Dispatchers.IO) {
                                    database.withTransaction {
                                        val current = database.syncRecoveryJournalDao().getForScope(activeScope.ownerHash, activeScope.storeId)
                                        val currentMetadata = current?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
                                        if (!sameBusinessRecoveryEntityCurrent(reservation) || current == null || currentMetadata == null ||
                                            currentMetadata.claimID != claim.claimID || currentMetadata.state != "in_flight" ||
                                            (claim.legacyResume != null && (currentMetadata.legacyResume != claim.legacyResume ||
                                                current.runId != claim.legacyResume.runId ||
                                                current.checkpointADigest != claim.legacyResume.checkpointADigest ||
                                                current.stagingDatabaseName != journal.stagingDatabaseName ||
                                                current.authorizationMode != journal.authorizationMode || current.shopId != journal.shopId ||
                                                current.createdAtMs != journal.createdAtMs || current.blockingEventId != journal.blockingEventId ||
                                                current.attemptCount != nextSyncRecoveryAttemptCount(journal.attemptCount))) ||
                                            current.deviceId != journal.deviceId ||
                                            database.syncEventDeviceStateDao().get()?.deviceId != journal.deviceId) false
                                        else {
                                            val exhausted = claim.windowAttempts == MAX_AUTOMATIC_SYNC_RECOVERY_ATTEMPTS_PER_TRIGGER
                                            val next = maxOf(current.nextRetryAtMs ?: 0L, retry?.nextRetryAtMs ?: 0L,
                                                retry?.classification?.retryAfterAtMs ?: 0L,
                                                if (transient && exhausted) completedAt + AUTOMATIC_SYNC_RECOVERY_COOLDOWN_MS else completedAt)
                                            val completion = claim.copy(code = code.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,160}")) }
                                                ?: "recovery_failed", category = category, lastCompletedAtMs = completedAt,
                                                state = if (!transient) "blocked" else if (exhausted) "cooldown" else "waiting",
                                                nextEligibleAtMs = next)
                                            database.syncRecoveryJournalDao().upsert(current.copy(reason = completion.encode(),
                                                updatedAtMs = completedAt, nextRetryAtMs = next))
                                            true
                                        }
                                    }
                                }
                                if (!completionAccepted || !transient) return@launch
                            } finally {
                                synchronized(businessRecoveryLock) {
                                    if (businessRecoveryExecutingJob === job) businessRecoveryExecutingJob = null
                                }
                            }
                        }
                    }
                } finally {
                    var transferToCurrentAuthority = false
                    synchronized(businessRecoveryLock) {
                        if (businessRecoveryJob === job) {
                            businessRecoveryJob = null
                            businessRecoveryReservation = null
                            transferToCurrentAuthority = job.isActive && !authorityCurrent()
                        }
                    }
                    // A fast refresh may conflate loading and the identical final ShopContext in StateFlow.
                    // Hand off once through normal admission when this timer observes the new epoch.
                    if (transferToCurrentAuthority) schedulePendingBusinessRecovery("scope_epoch_handoff")
                }
            }
            businessRecoveryJob = job
            businessRecoveryReservation = reservation
            job
        }
        scheduled.start()
        return true
    }

    private suspend fun completeBusinessRecovery(
        signedIn: AuthState.SignedIn,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop,
        activeScope: Task126OwnerStoreScope
    ) = completeBusinessRecovery(signedIn, selectedShop, activeScope, null)

    private suspend fun completeBusinessRecovery(
        signedIn: AuthState.SignedIn,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop,
        activeScope: Task126OwnerStoreScope,
        expectedActivation: ShopSyncRecoveryResult.Activated?
    ) {
        val activation = businessDataScopeMutex.withLock {
            val context = shopContextRepository.state.value
            if (!currentAuthAndShopMatch(signedIn.userId, selectedShop) || !context.localAccessAllowed) return@withLock null
            if (expectedActivation != null) {
                val proofCurrent = withContext(Dispatchers.IO) {
                    database.withTransaction {
                        val baseline = database.syncRecoveryBaselineDao().get() ?: return@withTransaction false
                        val binding = database.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
                        if (database.syncRecoveryJournalDao().get() != null || binding == null ||
                            Task126OwnerStoreGate.validate(binding, activeScope) != Task126OwnerStoreGateDecision.Allowed ||
                            baseline.generationId != expectedActivation.generationId || baseline.ownerHash != activeScope.ownerHash ||
                            baseline.storeScope != activeScope.storeId || !baseline.shopId.equals(selectedShop.shopId, ignoreCase = true) ||
                            baseline.deviceId != database.syncEventDeviceStateDao().get()?.deviceId ||
                            baseline.checkpointJson != encodeRecoveryCheckpointJson(expectedActivation.checkpoint)) false
                        else {
                            validateShopSyncActiveReceipt(database, expectedActivation.generationId, expectedActivation.checkpoint)
                            true
                        }
                    }
                }
                if (!proofCurrent) return@withLock null
            }
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

    private suspend fun awaitBusinessRecoveryScopeResolution(
        originJob: Job,
        reservation: BusinessRecoveryReservation,
        activeScope: Task126OwnerStoreScope,
        claimID: String,
        deviceId: String,
        remainingMs: Long
    ): Boolean {
        fun invocationCurrent() = synchronized(businessRecoveryLock) {
            businessRecoveryJob === originJob && businessRecoveryExecutingJob === originJob && originJob.isActive
        } && sameBusinessRecoveryEntityCurrent(reservation)
        suspend fun claimCurrent(): Boolean = withContext(Dispatchers.IO) {
            val current = database.syncRecoveryJournalDao().getForScope(activeScope.ownerHash, activeScope.storeId)
            val retry = current?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
            current?.deviceId == deviceId && retry?.claimID == claimID && retry.state == "in_flight" &&
                database.syncEventDeviceStateDao().get()?.deviceId == deviceId
        }
        if (remainingMs <= 0L || !invocationCurrent() || !claimCurrent()) return false
        val expectedShopId = reservation.context.selectedShop?.shopId ?: return false
        // A single cancellable resolution wait, with the caller's remaining transport deadline.
        // Confirmed local access preserves the invocation; only the final cloud guard can authorize continuation.
        var reportedWaiting = false
        combine(authManager.state, shopContextRepository.state, database.syncEventDeviceStateDao().observe()) {
                auth, context, device ->
            val sameEntity = (auth as? AuthState.SignedIn)?.userId == reservation.ownerUserId &&
                context.ownerUserId == reservation.ownerUserId && context.localAccessAllowed &&
                context.selectedShop?.shopId.equals(expectedShopId, ignoreCase = true) && device?.deviceId == deviceId
            if (sameEntity && context.isLoading && !reportedWaiting) {
                reportedWaiting = true
                com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryTestHooks.onScopeResolutionWaiting?.invoke()
            }
            !sameEntity || !context.isLoading
        }.first { resolvedOrInvalid -> resolvedOrInvalid }
        return invocationCurrent() && claimCurrent() && currentAuthAndShopMatch(reservation.ownerUserId,
            reservation.context.selectedShop)
    }

    private fun cancelBusinessRecovery() {
        val job = synchronized(businessRecoveryLock) {
            businessRecoveryJob.also {
                businessRecoveryJob = null
                businessRecoveryReservation = null
            }
        }
        job?.cancel()
    }

    private fun invalidateObsoleteBusinessRecoveryReservation() {
        val retired = synchronized(businessRecoveryLock) {
            val reservation = businessRecoveryReservation ?: return
            if (reservation.ownerUserId == (authManager.state.value as? AuthState.SignedIn)?.userId &&
                reservation.context == shopContextRepository.state.value &&
                reservation.shopEpoch == shopContextRepository.diagnosticShopEpoch()) return
            // A refresh does not revoke the same entity's already-owned invocation. Its result still needs
            // the durable claim/device checks; the next timer is admitted with the newly confirmed epoch.
            if (businessRecoveryExecutingJob === businessRecoveryJob && sameBusinessRecoveryEntityCurrent(reservation)) return
            businessRecoveryJob.also {
                businessRecoveryJob = null
                businessRecoveryReservation = null
            }
        }
        retired?.cancel()
    }

    /** Preserves an owned invocation or its factual failure completion, never grants cloud admission. */
    private fun sameBusinessRecoveryEntityCurrent(reservation: BusinessRecoveryReservation): Boolean {
        val current = shopContextRepository.state.value
        val previousShop = reservation.context.selectedShop ?: return false
        return (authManager.state.value as? AuthState.SignedIn)?.userId == reservation.ownerUserId &&
            current.ownerUserId == reservation.ownerUserId && current.localAccessAllowed &&
            current.selectedShop?.shopId.equals(previousShop.shopId, ignoreCase = true)
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
internal const val AUTOMATIC_SYNC_RECOVERY_COOLDOWN_MS = 5 * 60_000L

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

package com.example.merchandisecontrolsplitview

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
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
import com.example.merchandisecontrolsplitview.data.SupabaseProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseShopSyncReadRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseAuthManager
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

    val supabaseClient: SupabaseClient? by lazy {
        val configPresent = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
        if (configPresent) {
            try {
                createSupabaseClient(
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                    supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
                ) {
                    requestTimeout = 90.seconds
                    install(Auth)
                    install(Postgrest)
                    install(Realtime) {
                        reconnectDelay = 5.seconds
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Creazione client Supabase fallita", e)
                null
            }
        } else null
    }

    val authManager: SupabaseAuthManager by lazy {
        SupabaseAuthManager(
            client = supabaseClient,
            googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
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
            businessDataScopeRuntimeGuard = catalogSyncStateTracker
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
            }
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
                        catalogSyncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.checking()
                        )
                        suspendRemoteComponentsForBusinessScope()
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
                            if (!refreshedContext.syncAllowed) {
                                catalogSyncStateTracker.updateBusinessDataScopeState(
                                    Task126BusinessDataScopeState(
                                        status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                                        errorCode = "shop_context_unavailable"
                                    )
                                )
                            }
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
                    Log.w(TAG, "Shop context: sync cloud sospesa finche il contesto non torna valido")
                    catalogSyncStateTracker.updateBusinessDataScopeState(
                        if (context.isLoading) {
                            Task126BusinessDataScopeState.checking()
                        } else {
                            Task126BusinessDataScopeState(
                                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                                errorCode = "shop_context_unavailable"
                            )
                        }
                    )
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

    private suspend fun alignBusinessDataScope(
        ownerUserId: String,
        selectedShop: com.example.merchandisecontrolsplitview.data.SelectedShop?
    ): Boolean = businessDataScopeMutex.withLock {
        catalogSyncStateTracker.withBusinessDataScopeTransition {
            if (!currentAuthAndShopMatch(ownerUserId, selectedShop)) {
                catalogSyncStateTracker.updateBusinessDataScopeState(
                    Task126BusinessDataScopeState.checking()
                )
                return@withBusinessDataScopeTransition false
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
                return@withBusinessDataScopeTransition false
            }
            catalogSyncStateTracker.updateBusinessDataScopeState(state)
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

    internal fun requestPendingBusinessRecovery(reason: String) {
        schedulePendingBusinessRecovery(reason)
    }

    private fun schedulePendingBusinessRecovery(reason: String): Boolean {
        if (
            catalogSyncStateTracker.businessDataScopeState.value.errorCode !=
            "sync_recovery_required"
        ) return false
        val scheduled = synchronized(businessRecoveryLock) {
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
                            if (catalogSyncStateTracker.networkAvailable.value == false) {
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
            catalogSyncStateTracker.withBusinessDataScopeTransition {
                if (!currentAuthAndShopMatch(signedIn.userId, selectedShop)) {
                    catalogSyncStateTracker.updateBusinessDataScopeState(
                        Task126BusinessDataScopeState.checking()
                    )
                    return@withBusinessDataScopeTransition null
                }
                val resolved = withContext(Dispatchers.IO) {
                    repository.resolveBusinessDataScope(activeScope)
                }
                catalogSyncStateTracker.updateBusinessDataScopeState(resolved)
                signedIn.takeIf { allowsResolvedBusinessDataScope(resolved, activeScope) }
            }
        }
        activation?.let { activateRemoteComponentsForBoundScope(it, "sync_recovery_complete") }
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

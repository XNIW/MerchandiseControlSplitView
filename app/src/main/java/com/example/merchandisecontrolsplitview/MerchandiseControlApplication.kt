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
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationRepository
import com.example.merchandisecontrolsplitview.data.ShopDeviceRegistrationRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseCatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSyncEventRealtimeSubscriber
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseSessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SupabaseAuthManager
import com.example.merchandisecontrolsplitview.data.SupabaseRealtimeSessionSubscriber
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    }

    /** Scope applicativo per osservatori lifecycle (auth → componenti remoti). */
    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val validatedNetworks = mutableSetOf<Network>()
    private val networkLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastShopDeviceRegistrationUserId: String? = null
    private var lastShopDeviceRegistrationAtMs: Long = 0L
    private var shopDeviceStatusPollingJob: Job? = null

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            realtimeRefreshCoordinator.onAppForeground()
            historySessionPushCoordinator.onAppForeground()
            catalogAutoSyncCoordinator.onAppForeground()
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
        DefaultInventoryRepository(database)
    }

    val realtimeRefreshCoordinator: RealtimeRefreshCoordinator by lazy {
        RealtimeRefreshCoordinator(
            repository = repository,
            sessionFlightOwner = sessionCloudSessionFlightOwner,
            logger = { message -> Log.i("RealtimeCoordinator", message) }
        )
    }

    /**
     * Signal condiviso "sync cloud in corso": aggiornato dal `CatalogSyncViewModel`
     * (refresh manuale + bootstrap automatico sessioni); letto dalla UI root
     * per mostrare l'icona sync in alto a destra (nessuna nuova orchestrazione).
     */
    val catalogSyncStateTracker: CatalogSyncStateTracker by lazy { CatalogSyncStateTracker() }

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

    val realtimeSessionSubscriber: SupabaseRealtimeSessionSubscriber by lazy {
        SupabaseRealtimeSessionSubscriber(
            client = supabaseClient,
            coordinator = realtimeRefreshCoordinator
        )
    }

    val syncEventRealtimeSubscriber: SupabaseSyncEventRealtimeSubscriber by lazy {
        SupabaseSyncEventRealtimeSubscriber(
            client = supabaseClient,
            coordinator = catalogAutoSyncCoordinator
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
        ShopDeviceAuthorizationRepository(shopDeviceRegistrationRemoteDataSource)
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
            flightOwner = sessionCloudSessionFlightOwner,
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
            syncStateTracker = catalogSyncStateTracker,
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
    }

    override fun onTerminate() {
        appScope.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        realtimeSessionSubscriber.shutdown()
        syncEventRealtimeSubscriber.shutdown()
        unregisterNetworkAutoSyncTrigger()
        realtimeRefreshCoordinator.shutdown()
        historySessionPushCoordinator.shutdown()
        catalogAutoSyncCoordinator.shutdown()
        authManager.shutdown()
        super.onTerminate()
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
                        Log.d(TAG, "Auth: verifica sessione in corso")
                    }
                    is AuthState.SignedIn -> {
                        Log.i(TAG, "Auth: sessione attiva")
                        registerShopDeviceBestEffort(state, "auth")
                        startShopDeviceStatusPolling()
                        realtimeSessionSubscriber.start()
                        val syncEventCapabilities = syncEventRemoteDataSource
                            .checkCapabilities(state.userId)
                            .getOrNull()
                        if (syncEventCapabilities?.realtimeSyncEventsAvailable == true) {
                            syncEventRealtimeSubscriber.start(state.userId)
                        } else {
                            Log.i(TAG, "sync_events realtime non disponibile: fallback catch-up watermark")
                            syncEventRealtimeSubscriber.stop()
                        }
                    }
                    is AuthState.SignedOut -> {
                        Log.i(TAG, "Auth: nessuna sessione, fermo realtime")
                        realtimeSessionSubscriber.stop()
                        syncEventRealtimeSubscriber.stop()
                        stopShopDeviceStatusPolling()
                    }
                    is AuthState.ErrorRecoverable -> {
                        Log.w(TAG, "Auth: errore recuperabile, fermo realtime prudenzialmente")
                        realtimeSessionSubscriber.stop()
                        syncEventRealtimeSubscriber.stop()
                        stopShopDeviceStatusPolling()
                    }
                }
            }
        }
    }

    private fun registerNetworkAutoSyncTrigger() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasValidatedInternet =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val becameOnline = synchronized(networkLock) {
                    val wasOffline = validatedNetworks.isEmpty()
                    if (hasValidatedInternet) {
                        validatedNetworks.add(network)
                    } else {
                        validatedNetworks.remove(network)
                    }
                    hasValidatedInternet && wasOffline
                }
                if (becameOnline) {
                    Log.i(TAG, "Network: internet validato disponibile, pianifico sync cloud pending")
                    registerShopDeviceBestEffort(authManager.state.value, "network")
                    catalogAutoSyncCoordinator.onNetworkAvailable()
                    historySessionPushCoordinator.onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                synchronized(networkLock) {
                    validatedNetworks.remove(network)
                }
            }

            override fun onUnavailable() {
                synchronized(networkLock) {
                    validatedNetworks.clear()
                }
            }
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { throwable ->
            Log.w(TAG, "Network: registrazione callback auto-sync fallita", throwable)
        }
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

        val now = System.currentTimeMillis()
        val sameRecentUser =
            lastShopDeviceRegistrationUserId == signedIn.userId &&
                now - lastShopDeviceRegistrationAtMs < 60_000L
        if (sameRecentUser) return

        lastShopDeviceRegistrationUserId = signedIn.userId
        lastShopDeviceRegistrationAtMs = now

        appScope.launch {
            val result = withContext(Dispatchers.IO) {
                shopDeviceAuthorizationRepository.registerHeartbeatAndCheck(reason)
            }
            result.getOrNull()?.let { response ->
                Log.i(
                    TAG,
                    "Shop device status reason=$reason status=${response.status} code=${response.code} canWrite=${response.canWrite}"
                )
                if (!response.canWrite) {
                    catalogSyncStateTracker.update(CatalogSyncProgressState.failed(CatalogSyncStage.DEVICE_STATUS))
                }
            }
        }
    }

    private fun startShopDeviceStatusPolling() {
        if (shopDeviceStatusPollingJob?.isActive == true) return
        shopDeviceStatusPollingJob = appScope.launch {
            while (true) {
                val signedIn = authManager.state.value as? AuthState.SignedIn
                if (signedIn != null && shopDeviceRegistrationRemoteDataSource.isConfigured) {
                    val result = withContext(Dispatchers.IO) {
                        shopDeviceAuthorizationRepository.checkStatus(
                            reason = "foreground_poll",
                            force = false
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
                        }
                    }
                }
                delay(15_000L)
            }
        }
    }

    private fun stopShopDeviceStatusPolling() {
        shopDeviceStatusPollingJob?.cancel()
        shopDeviceStatusPollingJob = null
    }
}

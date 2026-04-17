package com.example.merchandisecontrolsplitview

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.RealtimeRefreshCoordinator
import com.example.merchandisecontrolsplitview.data.SupabaseAuthManager
import com.example.merchandisecontrolsplitview.data.SupabaseRealtimeConfig
import com.example.merchandisecontrolsplitview.data.SupabaseRealtimeSessionSubscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 * ## Wiring auth → componenti remoti (task 011, patch 5)
 * [observeAuthForRemoteComponents] è il punto architetturale unico dove i cambi
 * di stato auth controllano il lifecycle dei componenti remoti. Attualmente il
 * subscriber usa anon key su tabella condivisa e viene avviato indipendentemente
 * dallo stato auth. Task 012 (RLS/ownership) aggiungerà qui la logica di
 * auth-gating quando le tabelle richiederanno JWT utente.
 */
class MerchandiseControlApplication : Application() {

    companion object {
        private const val TAG = "MerchandiseApp"
    }

    /** Scope applicativo per osservatori lifecycle (auth → componenti remoti). */
    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            realtimeRefreshCoordinator.onAppForeground()
        }

        override fun onStop(owner: LifecycleOwner) {
            realtimeRefreshCoordinator.onAppBackground()
        }
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val repository: DefaultInventoryRepository by lazy {
        DefaultInventoryRepository(database)
    }

    val realtimeRefreshCoordinator: RealtimeRefreshCoordinator by lazy {
        RealtimeRefreshCoordinator(repository)
    }

    val authManager: SupabaseAuthManager by lazy {
        SupabaseAuthManager(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        )
    }

    val realtimeSessionSubscriber: SupabaseRealtimeSessionSubscriber by lazy {
        SupabaseRealtimeSessionSubscriber(
            coordinator = realtimeRefreshCoordinator,
            config = SupabaseRealtimeConfig.fromBuildConfig()
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Eager init: il coordinator deve essere realmente vivo a livello processo.
        realtimeRefreshCoordinator
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
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
        realtimeRefreshCoordinator.shutdown()
        authManager.shutdown()
        super.onTerminate()
    }

    // --- Wiring auth → componenti remoti (task 011, patch 5) ---

    /**
     * Punto unico di riallineamento componenti remoti al cambio stato auth.
     *
     * Osserva [authManager].[state][SupabaseAuthManager.state] e avvia i componenti
     * remoti quando lo stato auth si stabilizza. Attualmente il subscriber usa anon key
     * su `shared_sheet_sessions` (tabella condivisa), quindi viene avviato
     * indipendentemente dallo stato auth.
     *
     * **Task 012 (RLS/ownership):** qui si aggiungerà la logica di auth-gating
     * (es. avviare il subscriber solo con sessione valida, fermarlo al logout).
     */
    private fun observeAuthForRemoteComponents() {
        appScope.launch {
            authManager.state.collect { state ->
                when (state) {
                    is AuthState.Checking -> {
                        Log.d(TAG, "Auth: verifica sessione in corso")
                    }
                    is AuthState.SignedIn -> {
                        Log.i(TAG, "Auth: sessione attiva (userId=${state.userId})")
                        ensureRealtimeStarted()
                    }
                    is AuthState.SignedOut -> {
                        Log.i(TAG, "Auth: nessuna sessione")
                        ensureRealtimeStarted()
                    }
                    is AuthState.ErrorRecoverable -> {
                        Log.w(TAG, "Auth: errore recuperabile")
                        ensureRealtimeStarted()
                    }
                }
            }
        }
    }

    /**
     * Avvia il subscriber Realtime se non già attivo.
     *
     * Idempotente: [SupabaseRealtimeSessionSubscriber.start] è no-op se già avviato.
     * Task 012: sostituire con logica condizionale basata su [AuthState].
     */
    private fun ensureRealtimeStarted() {
        realtimeSessionSubscriber.start()
    }
}

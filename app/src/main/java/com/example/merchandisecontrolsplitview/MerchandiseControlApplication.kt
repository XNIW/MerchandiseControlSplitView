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
import com.example.merchandisecontrolsplitview.data.SupabaseRealtimeSessionSubscriber
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        RealtimeRefreshCoordinator(
            repository = repository,
            logger = { message -> Log.i("RealtimeCoordinator", message) }
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
                    install(Auth)
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
                        Log.i(TAG, "Auth: sessione attiva (userId=${state.userId})")
                        realtimeSessionSubscriber.start()
                    }
                    is AuthState.SignedOut -> {
                        Log.i(TAG, "Auth: nessuna sessione, fermo realtime")
                        realtimeSessionSubscriber.stop()
                    }
                    is AuthState.ErrorRecoverable -> {
                        Log.w(TAG, "Auth: errore recuperabile, fermo realtime prudenzialmente")
                        realtimeSessionSubscriber.stop()
                    }
                }
            }
        }
    }
}

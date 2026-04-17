package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owner unico del lifecycle auth Supabase (task 011).
 *
 * Responsabilita':
 * - Possiede un [io.github.jan.supabase.SupabaseClient] dedicato con il modulo [Auth].
 * - Espone [state] come unica fonte di verita' per lo stato sessione.
 * - Gestisce bootstrap (restore), sign-in Google e sign-out.
 * - Protegge ogni operazione auth con single-flight ([authMutex]).
 *
 * Se la configurazione (URL Supabase, chiave o Google Web Client ID) e' assente,
 * il manager si auto-disabilita ([isEnabled] = false) e resta in [AuthState.SignedOut]:
 * l'app continua a funzionare in puro offline-first.
 *
 * Percorso dati: sign-in -> Credential Manager -> Google ID Token -> Supabase IDToken exchange.
 * Non scrive Room, non altera repository, non gestisce dati business.
 */
class SupabaseAuthManager(
    supabaseUrl: String,
    supabaseKey: String,
    private val googleWebClientId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SupabaseAuth"
        internal const val RESTORE_TIMEOUT_MS = 10_000L
    }

    private val configPresent = supabaseUrl.isNotBlank()
        && supabaseKey.isNotBlank()
        && googleWebClientId.isNotBlank()

    private val client = if (configPresent) {
        try {
            createSupabaseClient(supabaseUrl, supabaseKey) {
                install(Auth)
            }
        } catch (e: Throwable) {
            // Robolectric / ambienti senza SettingsSessionManager: degrada a disabilitato.
            Log.w(TAG, "Creazione client Supabase Auth fallita, auth disabilitato", e)
            null
        }
    } else null

    /** true se configurazione presente E client creato con successo. */
    val isEnabled: Boolean = client != null

    private val _state = MutableStateFlow<AuthState>(
        if (isEnabled) AuthState.Checking else AuthState.SignedOut
    )

    /** Stato sessione corrente. Source of truth unica per UI e componenti remoti. */
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Mutex single-flight: una sola operazione auth alla volta. */
    private val authMutex = Mutex()

    init {
        if (isEnabled) {
            observeSessionStatus()
        }
    }

    // --- API pubblica ---

    /**
     * Restore sessione al bootstrap dell'app.
     *
     * Attende che la libreria Supabase finisca di caricare la sessione da storage
     * (entro [RESTORE_TIMEOUT_MS]). Se la sessione e' valida -> [AuthState.SignedIn];
     * altrimenti -> [AuthState.SignedOut]. Non blocca il chiamante.
     *
     * Single-flight: se un restore e' gia' in corso, la chiamata e' ignorata.
     */
    fun restoreSession() {
        if (!isEnabled || client == null) {
            _state.value = AuthState.SignedOut
            Log.i(TAG, "restoreSession: disabled, going SignedOut")
            return
        }
        scope.launch {
            if (!authMutex.tryLock()) {
                Log.w(TAG, "restoreSession: mutex already locked, skipping")
                return@launch
            }
            try {
                Log.d(TAG, "restoreSession: waiting for session status (timeout ${RESTORE_TIMEOUT_MS}ms)")
                val status = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                    client.auth.sessionStatus.first { it !is SessionStatus.Initializing }
                }
                Log.d(TAG, "restoreSession: got status=$status")
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = client.auth.currentUserOrNull()
                        _state.value = AuthState.SignedIn(
                            userId = user?.id ?: "",
                            email = user?.email
                        )
                        Log.i(TAG, "Sessione ripristinata")
                    }
                    else -> {
                        _state.value = AuthState.SignedOut
                        Log.i(TAG, "Nessuna sessione valida al bootstrap (status=$status)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = AuthState.SignedOut
                Log.w(TAG, "Restore sessione fallito", e)
            } finally {
                authMutex.unlock()
            }
        }
    }

    /**
     * Sign-in con Google via Credential Manager + Supabase IDToken exchange.
     *
     * Richiede un [Context] di Activity per mostrare il picker account Google.
     * Single-flight: se un tentativo e' gia' in corso, ritorna false immediatamente.
     *
     * @param activityContext Context dell'Activity corrente (necessario per Credential Manager).
     * @return true se il login e' riuscito, false se annullato, gia' in corso o fallito.
     */
    suspend fun signInWithGoogle(activityContext: Context): Boolean {
        if (!isEnabled || client == null) return false
        if (!authMutex.tryLock()) return false
        try {
            _state.value = AuthState.Checking

            // 1. Google ID Token via Credential Manager
            val credentialManager = CredentialManager.create(activityContext)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(googleWebClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val credentialResponse = credentialManager.getCredential(
                context = activityContext,
                request = request
            )
            val googleIdToken = GoogleIdTokenCredential
                .createFrom(credentialResponse.credential.data)
                .idToken

            // 2. Scambio token con Supabase Auth
            client.auth.signInWith(IDToken) {
                provider = Google
                idToken = googleIdToken
            }

            val user = client.auth.currentUserOrNull()
            _state.value = AuthState.SignedIn(
                userId = user?.id ?: "",
                email = user?.email
            )
            Log.i(TAG, "Sign-in Google completato")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            // Cancel utente != errore tecnico (planning: esito neutro)
            _state.value = AuthState.SignedOut
            Log.i(TAG, "Sign-in annullato dall'utente")
            return false
        } catch (e: Throwable) {
            _state.value = AuthState.ErrorRecoverable(
                e.localizedMessage ?: "Errore durante il login"
            )
            Log.w(TAG, "Sign-in fallito", e)
            return false
        } finally {
            authMutex.unlock()
        }
    }

    /**
     * Logout: invalida la sessione Supabase lato client.
     * Non effettua wipe di Room ne' dei dati locali (DEC-014, DEC-015).
     */
    suspend fun signOut() {
        if (client == null) return
        authMutex.withLock {
            try {
                client.auth.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Errore durante signOut", e)
            }
            _state.value = AuthState.SignedOut
            Log.i(TAG, "Logout completato")
        }
    }

    /**
     * Transizione da [AuthState.ErrorRecoverable] a [AuthState.SignedOut].
     * No-op se lo stato corrente non e' ErrorRecoverable.
     */
    fun dismissError() {
        val current = _state.value
        if (current is AuthState.ErrorRecoverable) {
            _state.value = AuthState.SignedOut
        }
    }

    /** Chiude il manager e cancella il suo CoroutineScope. */
    fun shutdown() {
        scope.cancel()
    }

    // --- Interno ---

    /**
     * Osserva i cambi di stato sessione dalla libreria Supabase.
     * Reagisce solo a invalidazioni server-side (sessione era valida -> ora non lo e').
     * I problemi di rete non vengono trattati come logout (planning: stato recuperabile).
     */
    private fun observeSessionStatus() {
        scope.launch {
            client!!.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.NotAuthenticated -> {
                        if (_state.value is AuthState.SignedIn) {
                            _state.value = AuthState.SignedOut
                            Log.i(TAG, "Sessione invalidata (server-side o refresh non riuscito)")
                        }
                    }
                    else -> { /* Altre transizioni gestite dai metodi espliciti */ }
                }
            }
        }
    }
}

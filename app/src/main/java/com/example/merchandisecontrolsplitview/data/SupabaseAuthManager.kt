package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.SupabaseClient
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

internal data class SupabaseSessionUser(
    val id: String,
    val email: String?
)

internal sealed interface StoredSessionRefreshResult {
    data object Refreshed : StoredSessionRefreshResult
    data class Invalid(val code: String) : StoredSessionRefreshResult
    data object Deferred : StoredSessionRefreshResult
}

internal interface SupabaseAuthSessionController {
    val sessionStatus: StateFlow<SessionStatus>

    fun currentUserOrNull(): SupabaseSessionUser?
    suspend fun refreshStoredSession(): StoredSessionRefreshResult
    suspend fun clearSession()
    suspend fun signInWithGoogleIdToken(idToken: String)
    suspend fun importWeChatSession(accessToken: String, refreshToken: String)
    suspend fun signOut(scope: SignOutScope)
}

private class SupabaseClientAuthSessionController(
    private val client: SupabaseClient
) : SupabaseAuthSessionController {
    override val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    override fun currentUserOrNull(): SupabaseSessionUser? =
        client.auth.currentUserOrNull()?.let { user ->
            SupabaseSessionUser(id = user.id, email = user.email)
        }

    override suspend fun refreshStoredSession(): StoredSessionRefreshResult = try {
        client.auth.refreshCurrentSession()
        StoredSessionRefreshResult.Refreshed
    } catch (error: CancellationException) {
        throw error
    } catch (error: AuthRestException) {
        val code = error.errorCode?.value ?: error.error
        if (isDefinitiveStoredSessionFailure(code)) {
            StoredSessionRefreshResult.Invalid(code.lowercase())
        } else {
            StoredSessionRefreshResult.Deferred
        }
    } catch (_: Throwable) {
        StoredSessionRefreshResult.Deferred
    }

    override suspend fun clearSession() {
        client.auth.clearSession()
    }

    override suspend fun signInWithGoogleIdToken(idToken: String) {
        client.auth.signInWith(IDToken) {
            provider = Google
            this.idToken = idToken
        }
    }

    override suspend fun importWeChatSession(accessToken: String, refreshToken: String) {
        client.auth.importAuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            retrieveUser = true
        )
    }

    override suspend fun signOut(scope: SignOutScope) {
        client.auth.signOut(scope)
    }
}

internal fun isDefinitiveStoredSessionFailure(code: String?): Boolean =
    code?.lowercase() in setOf(
        "invalid_grant",
        AuthErrorCode.BadJwt.value,
        AuthErrorCode.InvalidCredentials.value,
        AuthErrorCode.NoAuthorization.value,
        AuthErrorCode.RefreshTokenAlreadyUsed.value,
        AuthErrorCode.RefreshTokenNotFound.value,
        AuthErrorCode.SessionExpired.value,
        AuthErrorCode.SessionNotFound.value,
        AuthErrorCode.UserBanned.value,
        AuthErrorCode.UserNotFound.value
    )

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
class SupabaseAuthManager private constructor(
    private val sessionController: SupabaseAuthSessionController?,
    private val googleWebClientId: String,
    private val wechatCodeProvider: WeChatCodeProvider?,
    private val wechatGateway: WeChatAuthGateway?,
    private val wechatDeviceIdProvider: (suspend () -> String)?,
    private val nowEpochMillis: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    constructor(
        client: SupabaseClient?,
        googleWebClientId: String,
        wechatCodeProvider: WeChatCodeProvider? = null,
        wechatGateway: WeChatAuthGateway? = null,
        wechatDeviceIdProvider: (suspend () -> String)? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) : this(
        sessionController = client?.let(::SupabaseClientAuthSessionController),
        googleWebClientId = googleWebClientId,
        wechatCodeProvider = wechatCodeProvider,
        wechatGateway = wechatGateway,
        wechatDeviceIdProvider = wechatDeviceIdProvider,
        nowEpochMillis = System::currentTimeMillis,
        scope = scope
    )

    companion object {
        private const val TAG = "SupabaseAuth"
        internal const val RESTORE_TIMEOUT_MS = 10_000L

        internal fun createForTest(
            sessionController: SupabaseAuthSessionController,
            scope: CoroutineScope,
            wechatCodeProvider: WeChatCodeProvider? = null,
            wechatGateway: WeChatAuthGateway? = null,
            wechatDeviceIdProvider: (suspend () -> String)? = null,
            nowEpochMillis: () -> Long = System::currentTimeMillis
        ): SupabaseAuthManager = SupabaseAuthManager(
            sessionController = sessionController,
            googleWebClientId = "test-client-id",
            wechatCodeProvider = wechatCodeProvider,
            wechatGateway = wechatGateway,
            wechatDeviceIdProvider = wechatDeviceIdProvider,
            nowEpochMillis = nowEpochMillis,
            scope = scope
        )
    }

    /** true se il client è stato iniettato con successo e ha il modulo Auth. */
    val isEnabled: Boolean = sessionController != null

    val isWeChatEnabled: Boolean =
        isEnabled &&
            wechatCodeProvider?.isConfigured == true &&
            wechatGateway?.isConfigured == true &&
            wechatDeviceIdProvider != null

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
        val controller = sessionController
        if (!isEnabled || controller == null) {
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
                    controller.sessionStatus.first { it !is SessionStatus.Initializing }
                }
                Log.d(TAG, "restoreSession: got status=${status.safeLogLabel()}")
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val localUser = controller.currentUserOrNull()
                        when (val refresh = controller.refreshStoredSession()) {
                            StoredSessionRefreshResult.Refreshed -> {
                                if (publishSignedIn(controller.currentUserOrNull() ?: localUser)) {
                                    Log.i(TAG, "Sessione ripristinata e validata")
                                } else {
                                    try {
                                        controller.clearSession()
                                    } catch (_: Throwable) {
                                        // Fail-closed applicativo anche se il cleanup storage fallisce.
                                    }
                                    Log.w(TAG, "Sessione validata senza identita account utilizzabile")
                                }
                            }

                            is StoredSessionRefreshResult.Invalid -> {
                                try {
                                    controller.clearSession()
                                } catch (_: Throwable) {
                                    // Lo stato applicativo viene comunque invalidato; clear locale best-effort.
                                }
                                _state.value = AuthState.SignedOut
                                Log.i(
                                    TAG,
                                    "Sessione persistita invalidata dal server code=${refresh.code}"
                                )
                            }

                            StoredSessionRefreshResult.Deferred -> {
                                // Offline-first: un errore rete/5xx non equivale a revoca della sessione.
                                if (publishSignedIn(localUser)) {
                                    Log.w(TAG, "Validazione sessione rinviata per errore transitorio")
                                } else {
                                    Log.w(TAG, "Validazione rinviata senza identita account locale utilizzabile")
                                }
                            }
                        }
                    }
                    else -> {
                        _state.value = AuthState.SignedOut
                        Log.i(TAG, "Nessuna sessione valida al bootstrap (status=${status.safeLogLabel()})")
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
        val controller = sessionController
        if (!isEnabled || controller == null) return false
        if (!authMutex.tryLock()) return false
        try {
            _state.value = AuthState.Checking

            val credentialManager = CredentialManager.create(activityContext)
            val googleIdToken = requestGoogleIdToken(
                credentialManager = credentialManager,
                activityContext = activityContext
            )

            // 2. Scambio token con Supabase Auth
            controller.signInWithGoogleIdToken(googleIdToken)

            if (!publishSignedIn(controller.currentUserOrNull())) {
                controller.clearSession()
                Log.w(TAG, "Sign-in completato senza identita account utilizzabile")
                return false
            }
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
     * WeChat adapter flow. This manager remains the sole owner of the Supabase session;
     * the platform adapter can only return a temporary code and the server gateway owns
     * every exchange. AppSecret, OpenID and session_key never enter this client.
     */
    suspend fun signInWithWeChat(activityContext: Context): Boolean {
        val controller = sessionController
        val codeProvider = wechatCodeProvider
        val gateway = wechatGateway
        val installId = wechatDeviceIdProvider
        if (!isWeChatEnabled || controller == null || codeProvider == null ||
            gateway == null || installId == null
        ) {
            _state.value = AuthState.ErrorRecoverable(
                activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_configured)
            )
            return false
        }
        if (!authMutex.tryLock()) return false

        try {
            _state.value = AuthState.Checking
            if (!codeProvider.isWeChatInstalled(activityContext)) {
                _state.value = AuthState.ErrorRecoverable(
                    activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_installed)
                )
                return false
            }

            val request = WeChatAuthRequest.create(nowEpochMillis())
            val deviceId = installId()
            val challenge = when (val issued = gateway.issueChallenge(deviceId, request)) {
                is WeChatGatewayResult.Success -> issued.value
                is WeChatGatewayResult.Failure -> {
                    _state.value = AuthState.ErrorRecoverable(
                        wechatErrorMessage(activityContext, issued.error)
                    )
                    return false
                }
            }
            val callback = when (val result = codeProvider.requestCode(activityContext, request.state)) {
                is WeChatCodeResult.Success -> result
                WeChatCodeResult.Cancelled -> {
                    _state.value = AuthState.SignedOut
                    return false
                }
                WeChatCodeResult.Denied -> {
                    _state.value = AuthState.ErrorRecoverable(
                        activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_denied)
                    )
                    return false
                }
                WeChatCodeResult.NotInstalled -> {
                    _state.value = AuthState.ErrorRecoverable(
                        activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_installed)
                    )
                    return false
                }
                is WeChatCodeResult.Failure -> {
                    _state.value = AuthState.ErrorRecoverable(
                        wechatErrorMessage(activityContext, result.error)
                    )
                    return false
                }
            }

            val callbackDecision = WeChatCallbackGuard(
                expectedState = request.state,
                createdAtEpochMillis = request.createdAtEpochMillis
            ).consume(callback.state, nowEpochMillis())
            if (callbackDecision != WeChatCallbackDecision.ACCEPT) {
                _state.value = AuthState.ErrorRecoverable(
                    activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_state_invalid)
                )
                return false
            }

            val session = when (val exchanged = gateway.exchange(challenge, callback.code, deviceId)) {
                is WeChatGatewayResult.Success -> exchanged.value
                is WeChatGatewayResult.Failure -> {
                    _state.value = AuthState.ErrorRecoverable(
                        wechatErrorMessage(activityContext, exchanged.error)
                    )
                    return false
                }
            }
            controller.importWeChatSession(session.accessToken, session.refreshToken)
            if (!publishSignedIn(controller.currentUserOrNull())) {
                controller.clearSession()
                _state.value = AuthState.ErrorRecoverable(
                    activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_backend_error)
                )
                return false
            }
            Log.i(TAG, "Sign-in WeChat completato")
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            _state.value = AuthState.ErrorRecoverable(
                activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_backend_error)
            )
            // Provider/transport exceptions can contain request metadata. Keep the
            // diagnostic categorical so codes and session tokens never reach logs.
            Log.w(TAG, "Sign-in WeChat fallito: errore redatto")
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
        val controller = sessionController ?: return
        authMutex.withLock {
            try {
                controller.signOut(SignOutScope.LOCAL)
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

    private fun wechatErrorMessage(context: Context, error: WeChatAuthError): String =
        context.getString(
            when (error) {
                WeChatAuthError.PROVIDER_NOT_CONFIGURED ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_configured
                WeChatAuthError.WECHAT_NOT_INSTALLED ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_installed
                WeChatAuthError.USER_CANCELLED ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_cancelled
                WeChatAuthError.USER_DENIED ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_denied
                WeChatAuthError.CODE_MISSING ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_code_missing
                WeChatAuthError.STATE_MISMATCH,
                WeChatAuthError.CALLBACK_DUPLICATE,
                WeChatAuthError.CALLBACK_EXPIRED ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_state_invalid
                WeChatAuthError.IDENTITY_CONFLICT ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_identity_conflict
                WeChatAuthError.BACKEND_ERROR ->
                    com.example.merchandisecontrolsplitview.R.string.wechat_auth_backend_error
            }
        )

    // --- Interno ---

    private suspend fun requestGoogleIdToken(
        credentialManager: CredentialManager,
        activityContext: Context
    ): String {
        val response = try {
            credentialManager.getCredential(
                context = activityContext,
                request = signInButtonRequest()
            )
        } catch (e: NoCredentialException) {
            Log.i(TAG, "Sign-in button flow found no Google credential, retrying account picker", e)
            credentialManager.getCredential(
                context = activityContext,
                request = googleAccountPickerRequest()
            )
        }

        return response.toGoogleIdToken()
    }

    private fun signInButtonRequest(): GetCredentialRequest {
        val googleSignInOption = GetSignInWithGoogleOption.Builder(googleWebClientId).build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(googleSignInOption)
            .build()
    }

    private fun googleAccountPickerRequest(): GetCredentialRequest {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(googleWebClientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
    }

    private fun GetCredentialResponse.toGoogleIdToken(): String =
        GoogleIdTokenCredential.createFrom(credential.data).idToken

    /**
     * Osserva i cambi di stato sessione dalla libreria Supabase.
     * Reagisce solo a invalidazioni server-side (sessione era valida -> ora non lo e').
     * I problemi di rete non vengono trattati come logout (planning: stato recuperabile).
     */
    private fun observeSessionStatus() {
        scope.launch {
            sessionController!!.sessionStatus.collect { status ->
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

    private fun SessionStatus?.safeLogLabel(): String = when (this) {
        null -> "Timeout"
        is SessionStatus.Authenticated -> "Authenticated"
        is SessionStatus.Initializing -> "Initializing"
        is SessionStatus.NotAuthenticated -> "NotAuthenticated"
        else -> this::class.java.simpleName
    }

    private fun publishSignedIn(user: SupabaseSessionUser?): Boolean {
        val userId = user?.id?.trim().orEmpty()
        if (userId.isEmpty()) {
            _state.value = AuthState.SignedOut
            return false
        }
        _state.value = AuthState.SignedIn(
            userId = userId,
            email = user?.email
        )
        return true
    }
}

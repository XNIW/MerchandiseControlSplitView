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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    val ownsGenerationBoundLifecycle: Boolean get() = false

    fun currentUserOrNull(): SupabaseSessionUser?
    suspend fun refreshStoredSession(): StoredSessionRefreshResult
    suspend fun clearSession()
    suspend fun clearSupersededInteractiveSession() = clearSession()
    suspend fun signInWithGoogleIdToken(idToken: String)
    suspend fun signInWithGoogleIdToken(idToken: String, isCurrentAttempt: () -> Boolean) = signInWithGoogleIdToken(idToken)
    suspend fun importWeChatSession(accessToken: String, refreshToken: String)
    suspend fun importWeChatSession(accessToken: String, refreshToken: String, isCurrentAttempt: () -> Boolean) = importWeChatSession(accessToken, refreshToken)
    suspend fun signOut(scope: SignOutScope)
    suspend fun signOut(scope: SignOutScope, isCurrentAttempt: () -> Boolean) = signOut(scope)
    fun localSessionCleared(): Boolean = false
}

private class SupabaseClientAuthSessionController(
    private val client: SupabaseClient
) : SupabaseAuthSessionController {
    private val owner: GenerationOwnedSupabaseClient? = client as? GenerationOwnedSupabaseClient
    private var interactiveClient: SupabaseClient? = null
    override val ownsGenerationBoundLifecycle: Boolean get() = owner != null

    override val sessionStatus: StateFlow<SessionStatus>
        get() = owner?.sessionStatus ?: client.auth.sessionStatus

    override fun currentUserOrNull(): SupabaseSessionUser? =
        owner?.captureClientOrNull()?.auth?.currentUserOrNull()?.let { user ->
            SupabaseSessionUser(id = user.id, email = user.email)
        } ?: if (owner == null) client.auth.currentUserOrNull()?.let { user ->
            SupabaseSessionUser(id = user.id, email = user.email)
        } else null

    override suspend fun refreshStoredSession(): StoredSessionRefreshResult = try {
        (owner?.captureClient() ?: client).auth.refreshCurrentSession()
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
        if (owner != null) owner.prepareForSignIn() else client.auth.clearSession()
    }

    override suspend fun signInWithGoogleIdToken(idToken: String) {
        signInWithGoogleIdToken(idToken) { true }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, isCurrentAttempt: () -> Boolean) {
        val operationClient = owner?.prepareForSignIn(isCurrentAttempt) ?: client
        interactiveClient = operationClient
        operationClient.auth.signInWith(IDToken) {
            provider = Google
            this.idToken = idToken
        }
    }

    override suspend fun importWeChatSession(accessToken: String, refreshToken: String) {
        importWeChatSession(accessToken, refreshToken) { true }
    }

    override suspend fun importWeChatSession(accessToken: String, refreshToken: String, isCurrentAttempt: () -> Boolean) {
        val operationClient = owner?.prepareForSignIn(isCurrentAttempt) ?: client
        interactiveClient = operationClient
        operationClient.auth.importAuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            retrieveUser = true
        )
    }

    override suspend fun signOut(scope: SignOutScope) {
        signOut(scope) { true }
    }

    override suspend fun signOut(scope: SignOutScope, isCurrentAttempt: () -> Boolean) {
        if (owner != null) {
            check(scope == SignOutScope.LOCAL)
            owner.signOut(isCurrentAttempt)
        } else {
            try {
                client.auth.signOut(scope)
            } finally {
                if (scope == SignOutScope.LOCAL) withContext(NonCancellable) { client.auth.clearSession() }
            }
        }
    }

    override fun localSessionCleared(): Boolean = owner?.isLocalSessionCleared == true

    override suspend fun clearSupersededInteractiveSession() {
        if (owner != null) {
            interactiveClient?.let { owner.prepareForSignIn(expectedClient = it) }
        } else client.auth.clearSession()
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
        internal const val SESSION_CLEANUP_ERROR = "auth_session_cleanup_failed"

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

    private data class BootstrapRestoreAttempt(val generation: Long, val awaitingSdk: Boolean = false)
    private val authGeneration = AtomicLong(0L)
    private val bootstrapRestore = AtomicReference<BootstrapRestoreAttempt?>(null)

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
        val generation = authGeneration.get()
        scope.launch {
            if (!authMutex.tryLock()) {
                Log.w(TAG, "restoreSession: mutex already locked, skipping")
                return@launch
            }
            val attempt = BootstrapRestoreAttempt(generation)
            try {
                if (authGeneration.get() != generation) return@launch
                bootstrapRestore.set(attempt)
                Log.d(TAG, "restoreSession: waiting for session status (timeout ${RESTORE_TIMEOUT_MS}ms)")
                val status = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                    controller.sessionStatus.first { it !is SessionStatus.Initializing }
                }
                Log.d(TAG, "restoreSession: got status=${status.safeLogLabel()}")
                if (!isCurrentBootstrap(attempt)) return@launch
                when (status) {
                    is SessionStatus.Authenticated -> {
                        validateStoredSession(controller, attempt)
                    }
                    null, is SessionStatus.RefreshFailure -> {
                        val waiting = attempt.copy(awaitingSdk = true)
                        if (bootstrapRestore.compareAndSet(attempt, waiting)) {
                            _state.value = AuthState.SignedOut
                            Log.i(TAG, "Restore in attesa del completamento SDK (status=${status.safeLogLabel()})")
                            // Covers an SDK success that raced with arming the latch.
                            resumePendingBootstrap(controller)
                        }
                    }
                    else -> {
                        bootstrapRestore.compareAndSet(attempt, null)
                        _state.value = AuthState.SignedOut
                        Log.i(TAG, "Nessuna sessione valida al bootstrap (status=${status.safeLogLabel()})")
                    }
                }
            } catch (e: CancellationException) {
                bootstrapRestore.compareAndSet(attempt, null)
                throw e
            } catch (e: Throwable) {
                if (isCurrentBootstrap(attempt)) {
                    bootstrapRestore.compareAndSet(attempt, null)
                    _state.value = AuthState.SignedOut
                    Log.w(TAG, "Restore sessione fallito", e)
                }
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
        val preserveCleanupError = hasUnverifiedCleanupError(controller)
        invalidateBootstrapRestore()
        if (!authMutex.tryLock()) return false
        val generation = authGeneration.get()
        var completedSignIn = false
        fun publishFailure(fallback: AuthState) {
            _state.value = if (preserveCleanupError && !controller.localSessionCleared()) {
                AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR)
            } else fallback
        }
        try {
            _state.value = AuthState.Checking

            val credentialManager = CredentialManager.create(activityContext)
            val googleIdToken = requestGoogleIdToken(
                credentialManager = credentialManager,
                activityContext = activityContext
            )
            if (interactiveAttemptSupersededBeforeExchange(generation)) return false

            // 2. Scambio token con Supabase Auth
            controller.signInWithGoogleIdToken(googleIdToken) { authGeneration.get() == generation }

            if (abandonSupersededInteractiveSession(controller, generation)) return false

            if (!publishSignedIn(controller.currentUserOrNull())) {
                controller.clearSession()
                Log.w(TAG, "Sign-in completato senza identita account utilizzabile")
                return false
            }
            Log.i(TAG, "Sign-in Google completato")
            completedSignIn = true
            return true
        } catch (e: CancellationException) {
            if (authGeneration.get() != generation) return false
            throw e
        } catch (e: GetCredentialCancellationException) {
            // Cancel utente != errore tecnico (planning: esito neutro)
            if (authGeneration.get() == generation) publishFailure(AuthState.SignedOut)
            Log.i(TAG, "Sign-in annullato dall'utente")
            return false
        } catch (e: Throwable) {
            if (authGeneration.get() != generation) return false
            publishFailure(AuthState.ErrorRecoverable(
                if (e is SupabaseSessionPersistenceException) SESSION_CLEANUP_ERROR
                else e.localizedMessage ?: "Errore durante il login"
            ))
            Log.w(TAG, "Sign-in fallito", e)
            return false
        } finally {
            if (preserveCleanupError && !completedSignIn && authGeneration.get() == generation && !controller.localSessionCleared()) {
                _state.value = AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR)
            }
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
        val preserveCleanupError = controller?.let(::hasUnverifiedCleanupError) == true
        if (!isWeChatEnabled || controller == null || codeProvider == null ||
            gateway == null || installId == null
        ) {
            _state.value = if (preserveCleanupError) AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR) else AuthState.ErrorRecoverable(
                activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_configured)
            )
            return false
        }
        invalidateBootstrapRestore()
        if (!authMutex.tryLock()) return false
        val generation = authGeneration.get()
        var completedSignIn = false
        fun publishFailure(fallback: AuthState) {
            _state.value = if (preserveCleanupError && !controller.localSessionCleared()) {
                AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR)
            } else fallback
        }

        try {
            _state.value = AuthState.Checking
            if (!codeProvider.isWeChatInstalled(activityContext)) {
                publishFailure(AuthState.ErrorRecoverable(
                    activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_installed)
                ))
                return false
            }

            val request = WeChatAuthRequest.create(nowEpochMillis())
            val deviceId = installId()
            if (interactiveAttemptSupersededBeforeExchange(generation)) return false
            val issued = gateway.issueChallenge(deviceId, request)
            if (interactiveAttemptSupersededBeforeExchange(generation)) return false
            val challenge = when (issued) {
                is WeChatGatewayResult.Success -> issued.value
                is WeChatGatewayResult.Failure -> {
                    publishFailure(AuthState.ErrorRecoverable(
                        wechatErrorMessage(activityContext, issued.error)
                    ))
                    return false
                }
            }
            val result = codeProvider.requestCode(activityContext, request.state)
            if (interactiveAttemptSupersededBeforeExchange(generation)) return false
            val callback = when (result) {
                is WeChatCodeResult.Success -> result
                WeChatCodeResult.Cancelled -> {
                    publishFailure(AuthState.SignedOut)
                    return false
                }
                WeChatCodeResult.Denied -> {
                    publishFailure(AuthState.ErrorRecoverable(
                        activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_denied)
                    ))
                    return false
                }
                WeChatCodeResult.NotInstalled -> {
                    publishFailure(AuthState.ErrorRecoverable(
                        activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_not_installed)
                    ))
                    return false
                }
                is WeChatCodeResult.Failure -> {
                    publishFailure(AuthState.ErrorRecoverable(
                        wechatErrorMessage(activityContext, result.error)
                    ))
                    return false
                }
            }

            val callbackDecision = WeChatCallbackGuard(
                expectedState = request.state,
                createdAtEpochMillis = request.createdAtEpochMillis
            ).consume(callback.state, nowEpochMillis())
            if (callbackDecision != WeChatCallbackDecision.ACCEPT) {
                publishFailure(AuthState.ErrorRecoverable(
                    activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_state_invalid)
                ))
                return false
            }

            val exchanged = gateway.exchange(challenge, callback.code, deviceId)
            if (interactiveAttemptSupersededBeforeExchange(generation)) return false
            val session = when (exchanged) {
                is WeChatGatewayResult.Success -> exchanged.value
                is WeChatGatewayResult.Failure -> {
                    publishFailure(AuthState.ErrorRecoverable(
                        wechatErrorMessage(activityContext, exchanged.error)
                    ))
                    return false
                }
            }
            controller.importWeChatSession(session.accessToken, session.refreshToken) { authGeneration.get() == generation }
            if (abandonSupersededInteractiveSession(controller, generation)) return false
            if (!publishSignedIn(controller.currentUserOrNull())) {
                controller.clearSession()
                publishFailure(AuthState.ErrorRecoverable(
                    activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_backend_error)
                ))
                return false
            }
            Log.i(TAG, "Sign-in WeChat completato")
            completedSignIn = true
            return true
        } catch (error: CancellationException) {
            if (authGeneration.get() != generation) return false
            throw error
        } catch (error: Throwable) {
            if (authGeneration.get() != generation) return false
            publishFailure(AuthState.ErrorRecoverable(
                if (error is SupabaseSessionPersistenceException) SESSION_CLEANUP_ERROR
                else activityContext.getString(com.example.merchandisecontrolsplitview.R.string.wechat_auth_backend_error)
            ))
            // Provider/transport exceptions can contain request metadata. Keep the
            // diagnostic categorical so codes and session tokens never reach logs.
            Log.w(TAG, "Sign-in WeChat fallito: errore redatto")
            return false
        } finally {
            if (preserveCleanupError && !completedSignIn && authGeneration.get() == generation && !controller.localSessionCleared()) {
                _state.value = AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR)
            }
            authMutex.unlock()
        }
    }

    /**
     * Logout: invalida la sessione Supabase lato client.
     * Non effettua wipe di Room ne' dei dati locali (DEC-014, DEC-015).
     */
    suspend fun signOut() {
        val controller = sessionController ?: return
        // A pending logout must fence a suspended bootstrap before waiting for its mutex.
        invalidateBootstrapRestore()
        val generation = authGeneration.get()
        _state.value = AuthState.Checking
        if (controller.ownsGenerationBoundLifecycle) {
            // SDK/storage retirement has its own lock and cannot depend on a suspended picker.
            completeLocalSignOut(controller, generation)
        } else authMutex.withLock { completeLocalSignOut(controller, generation) }
    }

    private suspend fun completeLocalSignOut(controller: SupabaseAuthSessionController, generation: Long) {
        if (authGeneration.get() != generation) return
        try {
            controller.signOut(SignOutScope.LOCAL) { authGeneration.get() == generation }
        } catch (cancelled: CancellationException) {
            if (authGeneration.get() == generation) {
                _state.value = if (controller.localSessionCleared()) AuthState.SignedOut
                    else AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR)
            }
            throw cancelled
        } catch (_: Throwable) {
            if (authGeneration.get() != generation) return
            if (!controller.localSessionCleared()) {
                _state.value = AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR)
                Log.w(TAG, "Logout locale non completato: persistenza non verificata")
                return
            }
        }
        if (authGeneration.get() == generation) {
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
        if (current is AuthState.ErrorRecoverable && current.message != SESSION_CLEANUP_ERROR) {
            _state.value = AuthState.SignedOut
        }
    }

    /** Chiude il manager e cancella il suo CoroutineScope. */
    fun shutdown() {
        invalidateBootstrapRestore()
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
     * Le autenticazioni SDK riprendono soltanto un bootstrap rimasto in attesa.
     * Un login esplicito o un'invalidazione chiudono definitivamente quel tentativo.
     */
    private fun observeSessionStatus() {
        scope.launch {
            sessionController!!.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.NotAuthenticated -> {
                        if (sessionController.sessionStatus.value != status) return@collect
                        val restoring = bootstrapRestore.get() != null
                        if (restoring) invalidateBootstrapRestore()
                        if (restoring || _state.value is AuthState.SignedIn) {
                            _state.value = AuthState.SignedOut
                            Log.i(TAG, "Sessione invalidata (server-side o refresh non riuscito)")
                        }
                    }
                    is SessionStatus.Authenticated -> resumePendingBootstrap(sessionController)
                    else -> { /* Altre transizioni gestite dai metodi espliciti */ }
                }
            }
        }
    }

    private fun invalidateBootstrapRestore() {
        val cancelledAttempt = bootstrapRestore.getAndSet(null)
        authGeneration.incrementAndGet()
        if (cancelledAttempt != null && _state.value == AuthState.Checking) {
            _state.value = AuthState.SignedOut
        }
    }

    private fun hasUnverifiedCleanupError(controller: SupabaseAuthSessionController): Boolean =
        _state.value == AuthState.ErrorRecoverable(SESSION_CLEANUP_ERROR) && !controller.localSessionCleared()

    private suspend fun abandonSupersededInteractiveSession(
        controller: SupabaseAuthSessionController,
        generation: Long
    ): Boolean {
        if (authGeneration.get() == generation) return false
        // The app mutex still owns this exchange; a later explicit operation has not entered yet.
        controller.clearSupersededInteractiveSession()
        return true
    }

    private fun interactiveAttemptSupersededBeforeExchange(generation: Long): Boolean {
        if (authGeneration.get() == generation) return false
        return true
    }

    private fun isCurrentBootstrap(attempt: BootstrapRestoreAttempt): Boolean =
        authGeneration.get() == attempt.generation && bootstrapRestore.get() == attempt

    private fun resumePendingBootstrap(controller: SupabaseAuthSessionController) {
        val pending = bootstrapRestore.get()?.takeIf { it.awaitingSdk } ?: return
        scope.launch {
            authMutex.withLock {
                // Read current SDK state after acquiring the lock; the triggering event may be stale.
                if (!isCurrentBootstrap(pending) || controller.sessionStatus.value !is SessionStatus.Authenticated) {
                    return@withLock
                }
                val validating = pending.copy(awaitingSdk = false)
                if (!bootstrapRestore.compareAndSet(pending, validating)) return@withLock
                validateStoredSession(controller, validating)
            }
        }
    }

    private suspend fun validateStoredSession(
        controller: SupabaseAuthSessionController,
        attempt: BootstrapRestoreAttempt
    ) {
        val localUser = controller.currentUserOrNull()
        try {
            val refresh = controller.refreshStoredSession()
            val currentUser = controller.currentUserOrNull()
            // No old validation result may publish or clear a superseding session.
            if (!isCurrentBootstrap(attempt)) return
            if (controller.sessionStatus.value is SessionStatus.NotAuthenticated ||
                (localUser != null && currentUser != null && currentUser.id != localUser.id)
            ) {
                invalidateBootstrapRestore()
                _state.value = AuthState.SignedOut
                return
            }
            when (refresh) {
                StoredSessionRefreshResult.Refreshed -> {
                    if (publishSignedIn(currentUser ?: localUser)) {
                        Log.i(TAG, "Sessione ripristinata e validata")
                    } else {
                        clearStoredSessionIfCurrent(controller, attempt, localUser)
                        Log.w(TAG, "Sessione validata senza identita account utilizzabile")
                    }
                }
                is StoredSessionRefreshResult.Invalid -> {
                    clearStoredSessionIfCurrent(controller, attempt, localUser)
                    if (isCurrentBootstrap(attempt)) _state.value = AuthState.SignedOut
                    Log.i(TAG, "Sessione persistita invalidata dal server code=${refresh.code}")
                }
                StoredSessionRefreshResult.Deferred -> {
                    // Offline-first remains available for an already identified stored session.
                    if (publishSignedIn(localUser)) {
                        Log.w(TAG, "Validazione sessione rinviata per errore transitorio")
                    } else {
                        Log.w(TAG, "Validazione rinviata senza identita account locale utilizzabile")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrentBootstrap(attempt)) {
                _state.value = AuthState.SignedOut
                Log.w(TAG, "Restore sessione fallito", error)
            }
        } finally {
            bootstrapRestore.compareAndSet(attempt, null)
        }
    }

    private suspend fun clearStoredSessionIfCurrent(
        controller: SupabaseAuthSessionController,
        attempt: BootstrapRestoreAttempt,
        expectedUser: SupabaseSessionUser?
    ) {
        if (!isCurrentBootstrap(attempt) || controller.currentUserOrNull()?.id != expectedUser?.id) return
        try {
            controller.clearSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Definitive denial stays fail-closed even if local cleanup fails.
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

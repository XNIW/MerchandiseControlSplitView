package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.AccessTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseClientConfig
import io.github.jan.supabase.SupabaseSerializer
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.SupabaseLogger
import io.github.jan.supabase.network.KtorSupabaseHttpClient
import io.github.jan.supabase.plugins.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal class SupabaseSessionPersistenceException(cause: Throwable? = null) :
    IllegalStateException("The local account session could not be cleared", cause)

internal class SupabaseClientLifecycleException(cause: Throwable? = null) :
    IllegalStateException("The account session is temporarily unavailable", cause)

/**
 * Keeps the existing client interface while retiring SDK work at explicit auth boundaries.
 * The canonical delegates retain the SDK's storage keys and serialization. Their deletion and
 * [isSessionStored] must include any legacy session key that could be migrated on next startup.
 * [isSessionStored] reads canonical storage directly and must propagate read failures.
 */
@OptIn(SupabaseInternal::class)
internal class GenerationOwnedSupabaseClient(
    private val sessionManager: SessionManager,
    private val codeVerifierCache: CodeVerifierCache,
    private val isSessionStored: suspend () -> Boolean,
    private val factory: (SessionManager, CodeVerifierCache) -> SupabaseClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : SupabaseClient {
    private enum class Mode { CONSTRUCTING, ACTIVE, TRANSITION, FAILED, CLOSED }

    private class Generation {
        var client: SupabaseClient? = null
        var relay: Job? = null
        var retired = false
        var closed = false
    }

    private class RetiredLeaseCancellationException :
        CancellationException("The account session generation was retired")

    private val stateLock = Any()
    private val lifecycleMutex = Mutex()
    private val storageMutex = Mutex()
    private var mode = Mode.CONSTRUCTING
    private var generation: Generation? = null
    private var localSessionCleared = false
    private val mutableSessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
    internal val sessionStatus: StateFlow<SessionStatus> = mutableSessionStatus.asStateFlow()
    internal val isLocalSessionCleared: Boolean
        get() = synchronized(stateLock) { localSessionCleared }

    init {
        installGeneration()
    }

    /** A single snapshot is used throughout each operation, including cached API construction. */
    internal fun captureClientOrNull(): SupabaseClient? = synchronized(stateLock) {
        generation?.client.takeIf { mode == Mode.ACTIVE }
    }

    internal fun captureClient(): SupabaseClient =
        captureClientOrNull() ?: throw SupabaseClientLifecycleException()

    /** Invoked only once credentials have been obtained for an explicit new login. */
    internal suspend fun prepareForSignIn(expectedClient: SupabaseClient? = captureBoundaryClient()): SupabaseClient = lifecycleMutex.withLock {
        requireBoundaryClient(expectedClient)
        replaceForSignIn()
    }

    internal suspend fun prepareForSignIn(isCurrentAttempt: () -> Boolean): SupabaseClient = lifecycleMutex.withLock {
        if (!isCurrentAttempt()) throw RetiredLeaseCancellationException()
        replaceForSignIn()
    }

    private suspend fun replaceForSignIn(): SupabaseClient {
        val outgoing = retireGeneration()
        finishRetirement(outgoing, clearSession = true)
        return captureClient()
    }

    /** Remote local-scope logout retains its normal SDK path; local cleanup is unconditional. */
    internal suspend fun signOut(expectedClient: SupabaseClient? = captureBoundaryClient()): Unit = lifecycleMutex.withLock {
        requireBoundaryClient(expectedClient)
        signOutCurrentGeneration()
    }

    internal suspend fun signOut(isCurrentAttempt: () -> Boolean): Unit = lifecycleMutex.withLock {
        if (!isCurrentAttempt()) throw RetiredLeaseCancellationException()
        signOutCurrentGeneration()
    }

    private suspend fun signOutCurrentGeneration() {
        val outgoing = retireGeneration()
        var cancellation: CancellationException? = null
        try {
            outgoing?.client?.auth?.signOut(SignOutScope.LOCAL)
        } catch (_: RetiredLeaseCancellationException) {
            // The SDK's final clear uses its outgoing lease. The canonical owner clears below.
        } catch (error: CancellationException) {
            // A closed retired HTTP client can cancel its own call without canceling this caller.
            if (!currentCoroutineContext().isActive) cancellation = error
        } catch (_: Exception) {
            // A failed remote request must not retain credentials after accepted local logout.
        } finally {
            finishRetirement(outgoing, clearSession = true)
        }
        cancellation?.let { throw it }
    }

    private fun captureBoundaryClient(): SupabaseClient? = synchronized(stateLock) {
        if (mode == Mode.CLOSED) throw SupabaseClientLifecycleException()
        generation?.client
    }

    private fun requireBoundaryClient(expectedClient: SupabaseClient?) = synchronized(stateLock) {
        if (generation?.client !== expectedClient) throw RetiredLeaseCancellationException()
    }

    /** Resource shutdown is distinct from logout: a normal restart keeps the stored session. */
    override suspend fun close(): Unit = lifecycleMutex.withLock {
        if (synchronized(stateLock) { mode == Mode.CLOSED }) return@withLock
        val outgoing = retireGeneration()
        synchronized(stateLock) { mode = Mode.CLOSED }
        withContext(NonCancellable) {
            closeGeneration(outgoing)
        }
    }

    private suspend fun retireGeneration(): Generation? = storageMutex.withLock {
        synchronized(stateLock) {
            if (mode == Mode.CLOSED) throw SupabaseClientLifecycleException()
            mode = Mode.TRANSITION
            val outgoing = generation
            outgoing?.also {
                it.retired = true
                it.relay?.cancel()
            }
            mutableSessionStatus.value = SessionStatus.Initializing
            outgoing
        }
    }

    private suspend fun finishRetirement(outgoing: Generation?, clearSession: Boolean) =
        withContext(NonCancellable) {
            var failure: Throwable? = null
            try {
                if (clearSession) {
                    storageMutex.withLock {
                        try {
                            synchronized(stateLock) { localSessionCleared = false }
                            sessionManager.deleteSession()
                            codeVerifierCache.deleteCodeVerifier()
                            if (isSessionStored() || codeVerifierCache.loadCodeVerifier() != null) {
                                throw SupabaseSessionPersistenceException()
                            }
                            synchronized(stateLock) { localSessionCleared = true }
                        } catch (error: SupabaseSessionPersistenceException) {
                            throw error
                        } catch (error: Throwable) {
                            throw SupabaseSessionPersistenceException(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            }
            try {
                closeGeneration(outgoing)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            if (failure != null) {
                synchronized(stateLock) { mode = Mode.FAILED }
                throw failure
            }
            installGeneration()
        }

    private suspend fun closeGeneration(outgoing: Generation?) {
        if (outgoing == null || outgoing.closed) return
        outgoing.relay?.cancel()
        val client = outgoing.client ?: return
        var failure: Throwable? = null
        try {
            // Ensure the retired auth refresher is canceled even if closing HTTP throws.
            client.auth.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            client.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        if (failure != null) throw SupabaseClientLifecycleException(failure)
        outgoing.closed = true
    }

    private fun installGeneration() {
        val incoming = Generation()
        synchronized(stateLock) {
            if (mode == Mode.CLOSED) throw SupabaseClientLifecycleException()
            // Auth initialization can read/save storage while the SDK constructor is running.
            generation = incoming
            mode = Mode.CONSTRUCTING
        }
        val client = try {
            factory(SessionLease(incoming), VerifierLease(incoming))
        } catch (error: Throwable) {
            synchronized(stateLock) {
                incoming.retired = true
                mode = Mode.FAILED
            }
            throw SupabaseClientLifecycleException(error)
        }
        synchronized(stateLock) {
            incoming.client = client
            mode = Mode.ACTIVE
        }
        val relay = scope.launch(start = CoroutineStart.LAZY) {
            client.auth.sessionStatus.collect { status ->
                synchronized(stateLock) {
                    if (generation === incoming && !incoming.retired && mode == Mode.ACTIVE) {
                        mutableSessionStatus.value = status
                    }
                }
            }
        }
        synchronized(stateLock) { incoming.relay = relay }
        relay.start()
    }

    private fun requireCurrentLease(lease: Generation) = synchronized(stateLock) {
        if (
            generation !== lease || lease.retired ||
            (mode != Mode.ACTIVE && mode != Mode.CONSTRUCTING)
        ) throw RetiredLeaseCancellationException()
    }

    private inner class SessionLease(private val lease: Generation) : SessionManager {
        override suspend fun saveSession(session: UserSession) = storageMutex.withLock {
            requireCurrentLease(lease)
            synchronized(stateLock) { localSessionCleared = false }
            sessionManager.saveSession(session)
        }

        override suspend fun loadSession(): UserSession = storageMutex.withLock {
            requireCurrentLease(lease)
            sessionManager.loadSession()
        }

        override suspend fun loadSessionOrNull(): UserSession? = storageMutex.withLock {
            requireCurrentLease(lease)
            sessionManager.loadSessionOrNull()
        }

        override suspend fun deleteSession() = storageMutex.withLock {
            requireCurrentLease(lease)
            sessionManager.deleteSession()
        }
    }

    private inner class VerifierLease(private val lease: Generation) : CodeVerifierCache {
        override suspend fun saveCodeVerifier(codeVerifier: String) = storageMutex.withLock {
            requireCurrentLease(lease)
            codeVerifierCache.saveCodeVerifier(codeVerifier)
        }

        override suspend fun loadCodeVerifier(): String? = storageMutex.withLock {
            requireCurrentLease(lease)
            codeVerifierCache.loadCodeVerifier()
        }

        override suspend fun deleteCodeVerifier() = storageMutex.withLock {
            requireCurrentLease(lease)
            codeVerifierCache.deleteCodeVerifier()
        }
    }

    override val config: SupabaseClientConfig get() = captureClient().config
    override val supabaseHttpUrl: String get() = captureClient().supabaseHttpUrl
    override val supabaseUrl: String get() = captureClient().supabaseUrl
    override val supabaseKey: String get() = captureClient().supabaseKey
    override val pluginManager: PluginManager get() = captureClient().pluginManager
    override val httpClient: KtorSupabaseHttpClient get() = captureClient().httpClient
    override val useHTTPS: Boolean get() = captureClient().useHTTPS
    override val defaultSerializer: SupabaseSerializer get() = captureClient().defaultSerializer
    override val accessToken: AccessTokenProvider? get() = captureClient().accessToken
    override val coroutineDispatcher: CoroutineDispatcher get() = captureClient().coroutineDispatcher
    override val logger: SupabaseLogger get() = captureClient().logger
}

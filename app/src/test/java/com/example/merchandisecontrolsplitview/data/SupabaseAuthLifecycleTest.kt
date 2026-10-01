package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Uses the pinned SDK and its normal cancellable HTTP pipeline, with no network or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, SupabaseInternal::class, InternalAPI::class)
class SupabaseAuthLifecycleTest {
    @Test
    fun `manager local logout HTTP500 cannot leave a session that restores on restart`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = ControlledSessionStorage()
        val old = syntheticSession("account-a", "old-a")
        val engine = SuspendedRefreshEngine(dispatcher, old, logoutStatus = HttpStatusCode.InternalServerError)
        val fixture = owned(dispatcher, storage, engine)
        val client = fixture.owner
        val manager = SupabaseAuthManager(client, "synthetic-google-client", scope = backgroundScope)
        var restarted: SupabaseClient? = null
        try {
            runCurrent()
            client.auth.importSession(old)
            manager.signOut()
            assertEquals(AuthState.SignedOut, manager.state.value)
            val storedAfterLogout = storage.loadSessionOrNull()
            client.close()
            restarted = client(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old), autoLoad = true)
            runCurrent()
            val restoredAfterRestart = restarted.auth.currentSessionOrNull()
            assertEquals("Local SignedOut must mean no stored/restarted session after logout HTTP500", listOf(null, null), listOf(storedAfterLogout?.accessToken, restoredAfterRestart?.accessToken))
        } finally {
            manager.shutdown()
            client.close()
            restarted?.close()
        }
    }

    @Test
    fun `logout cancels the SDK owned auto refresh job without resurrecting its session`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = ControlledSessionStorage()
        // Under the SDK's 80 percent refresh threshold, but still valid for the logout request.
        val old = syntheticSession("account-a", "old-a").copy(
            expiresIn = 3_600,
            expiresAt = Clock.System.now() + 100.seconds
        )
        val engine = SuspendedRefreshEngine(dispatcher, syntheticSession("account-a", "late-a"))
        val client = owned(dispatcher, storage, engine).owner
        try {
            runCurrent()
            val outgoing = client.captureClient()
            client.auth.importSession(old, autoRefresh = false)
            client.auth.startAutoRefreshForCurrentSession()
            engine.refreshStarted.await()
            assertTrue(client.auth.isAutoRefreshRunning)

            client.signOut()
            runCurrent()
            assertEquals(1, engine.logoutCalls)
            assertTrue("The controlled HTTP transport obeys normal coroutine cancellation", engine.refreshCancelled)
            assertTrue(!outgoing.auth.isAutoRefreshRunning)
            engine.releaseRefresh.complete(Unit)
            runCurrent()
            assertNull(client.auth.currentSessionOrNull())
            assertNull(storage.loadSessionOrNull())
        } finally {
            client.close()
        }
    }

    @Test
    fun `late direct SDK refresh after logout without new login stays signed out`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = ControlledSessionStorage()
        val old = syntheticSession("account-a", "old-a")
        val engine = SuspendedRefreshEngine(dispatcher, old.copy(accessToken = "late-a"))
        val client = owned(dispatcher, storage, engine).owner
        try {
            runCurrent()
            client.auth.importSession(old)
            val outgoing = client.captureClient()
            val refresh = async { runCatching { outgoing.auth.refreshCurrentSession() } }
            engine.refreshStarted.await()

            client.signOut()
            runCurrent()
            assertEquals(1, engine.logoutCalls)
            assertNull(client.auth.currentSessionOrNull())
            assertNull(storage.loadSessionOrNull())

            engine.releaseRefresh.complete(Unit)
            val result = refresh.await()
            assertTrue("Retirement cancels or fences the outgoing SDK request", result.isFailure)
            assertNull(client.auth.currentSessionOrNull())
            assertNull(storage.loadSessionOrNull())
        } finally {
            client.close()
        }
    }

    @Test
    fun `late direct SDK refresh cannot overwrite another account after logout and login`() = runTest(timeout = 15.seconds) {
        verifyLateRefreshPreservesNewLogin("account-b")
    }

    @Test
    fun `late direct SDK refresh cannot overwrite a new login of the same account`() = runTest(timeout = 15.seconds) {
        verifyLateRefreshPreservesNewLogin("account-a")
    }

    @Test
    fun `ordinary SDK bootstrap and manager validation retain the stored account`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "stored-a")
        val refreshed = old.copy(accessToken = "validated-a")
        val storage = ControlledSessionStorage(old)
        val engine = SuspendedRefreshEngine(dispatcher, refreshed).apply { releaseRefresh.complete(Unit) }
        val owner = owned(dispatcher, storage, engine).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        try {
            manager.restoreSession()
            runCurrent()
            assertEquals(AuthState.SignedIn("account-a", null), manager.state.value)
            assertEquals(refreshed, owner.auth.currentSessionOrNull())
            assertEquals(refreshed, storage.value)
        } finally {
            manager.shutdown()
            owner.close()
        }
    }

    @Test
    fun `a valid ordinary direct SDK refresh still saves and publishes its response`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val refreshed = old.copy(accessToken = "refreshed-a")
        val storage = ControlledSessionStorage()
        val engine = SuspendedRefreshEngine(dispatcher, refreshed)
        val owner = owned(dispatcher, storage, engine).owner
        try {
            runCurrent()
            owner.auth.importSession(old)
            val refresh = async { owner.captureClient().auth.refreshCurrentSession() }
            engine.refreshStarted.await()
            engine.releaseRefresh.complete(Unit)
            refresh.await()
            runCurrent()
            assertEquals(refreshed, owner.auth.currentSessionOrNull())
            assertEquals(refreshed, storage.value)
            assertTrue(owner.sessionStatus.value is SessionStatus.Authenticated)
        } finally { owner.close() }
    }

    @Test
    fun `an RPC data source constructed before rotation uses the current SDK and account on each call`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val new = syntheticSession("account-b", "new-login")
        val storage = ControlledSessionStorage(old)
        val initialEngine = SuspendedRefreshEngine(dispatcher, old)
        val fixture = owned(dispatcher, storage, initialEngine, new)
        val source = SupabaseShopSyncReadRemoteDataSource(fixture.owner)
        val manager = SupabaseAuthManager(fixture.owner, "synthetic-google-client", scope = backgroundScope)
        val context = ShopSyncRpcContext("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "synthetic-device")
        try {
            runCurrent()
            assertTrue(source.checkpoint(context).isFailure)
            assertEquals(listOf("Bearer old-a"), initialEngine.rpcAuthorizations)
            manager.signOut()
            withGoogleCredential { assertTrue(manager.signInWithGoogle(RuntimeEnvironment.getApplication())) }
            runCurrent()
            assertTrue(source.checkpoint(context).isFailure)
            assertEquals(listOf("Bearer new-login"), fixture.engines.last().rpcAuthorizations)
            assertEquals(1, initialEngine.rpcAuthorizations.size)
        } finally {
            manager.shutdown()
            fixture.owner.close()
        }
    }

    @Test
    fun `normal resource close preserves the canonical session for a restart`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "stored-a")
        val storage = ControlledSessionStorage()
        val owner = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old)).owner
        runCurrent()
        owner.auth.importSession(old)
        owner.close()
        assertEquals(old, storage.value)
        val restarted = client(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old), autoLoad = true)
        try {
            runCurrent()
            assertEquals(old, restarted.auth.currentSessionOrNull())
            assertTrue(runCatching { owner.captureClient() }.exceptionOrNull() is SupabaseClientLifecycleException)
        } finally { restarted.close() }
    }

    @Test
    fun `storage delete failure never reports a successful local logout`() = runTest(timeout = 15.seconds) {
        verifyFailedCleanup(deleteFails = true)
    }

    @Test
    fun `storage readback failure never reports a successful local logout`() = runTest(timeout = 15.seconds) {
        verifyFailedCleanup(deleteFails = false)
    }

    @Test
    fun `Google picker cancellation cannot dismiss an unverified cleanup error`() = runTest(timeout = 15.seconds) {
        verifyCleanupErrorSurvivesInteractive("google-cancel")
    }

    @Test
    fun `Google provider failure cannot replace an unverified cleanup error with a dismissible error`() = runTest(timeout = 15.seconds) {
        verifyCleanupErrorSurvivesInteractive("google-failure")
    }

    @Test
    fun `WeChat cancellation cannot dismiss an unverified cleanup error`() = runTest(timeout = 15.seconds) {
        verifyCleanupErrorSurvivesInteractive("wechat-cancel")
    }

    @Test
    fun `WeChat provider failure cannot replace an unverified cleanup error with a dismissible error`() = runTest(timeout = 15.seconds) {
        verifyCleanupErrorSurvivesInteractive("wechat-failure")
    }

    @Test
    fun `normal Google account switch cleanup failure cannot become dismissed SignedOut`() = runTest(timeout = 15.seconds) {
        verifyNewInteractiveCleanupFailure(google = true)
    }

    @Test
    fun `normal WeChat account switch cleanup failure cannot become dismissed SignedOut`() = runTest(timeout = 15.seconds) {
        verifyNewInteractiveCleanupFailure(google = false)
    }

    private suspend fun TestScope.verifyNewInteractiveCleanupFailure(google: Boolean) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val replacement = syntheticSession("account-b", "new-b")
        val storage = ControlledSessionStorage(old)
        val engine = SuspendedRefreshEngine(dispatcher, old)
        val fixture = owned(dispatcher, storage, engine, loginResponse = replacement)
        val provider = mockk<WeChatCodeProvider>()
        val gateway = mockk<WeChatAuthGateway>()
        every { provider.isConfigured } returns true
        every { provider.isWeChatInstalled(any()) } returns true
        every { gateway.isConfigured } returns true
        coEvery { gateway.issueChallenge(any(), any()) } coAnswers {
            WeChatGatewayResult.Success(WeChatChallenge(secondArg<WeChatAuthRequest>().state, "synthetic-nonce", "synthetic-correlation", 60))
        }
        coEvery { provider.requestCode(any(), any()) } coAnswers {
            WeChatCodeResult.Success("synthetic-code", secondArg<String>())
        }
        var exchangeCalls = 0
        coEvery { gateway.exchange(any(), any(), any()) } coAnswers {
            exchangeCalls++
            WeChatGatewayResult.Success(WeChatSupabaseSession(replacement.accessToken, replacement.refreshToken, 4_102_444_800, 86_400, "account-b"))
        }
        val manager = SupabaseAuthManager(fixture.owner, "synthetic-google-client", provider, gateway, { "synthetic-device" }, backgroundScope)
        val publications = mutableListOf<Pair<AuthState, Boolean>>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.state.collect { publications += it to fixture.owner.isLocalSessionCleared }
        }
        var restarted: SupabaseClient? = null
        try {
            runCurrent()
            engine.releaseRefresh.complete(Unit)
            manager.restoreSession()
            manager.state.first { it is AuthState.SignedIn }
            assertEquals(AuthState.SignedIn("account-a", null), manager.state.value)
            assertEquals(old, storage.value)
            storage.deleteFails = true
            if (google) withGoogleCredential {
                assertFalse(manager.signInWithGoogle(RuntimeEnvironment.getApplication()))
            } else {
                assertFalse(manager.signInWithWeChat(RuntimeEnvironment.getApplication()))
                assertEquals("The provider produced credentials before entering owner cleanup", 1, exchangeCalls)
            }
            val stateAfterFailure = manager.state.value
            manager.dismissError()
            val stateAfterDismiss = manager.state.value
            assertNull(fixture.owner.captureClientOrNull())
            assertFalse(fixture.owner.isLocalSessionCleared)
            assertEquals(old, storage.value)
            assertEquals("No replacement SDK may be exposed after failed canonical cleanup", 1, fixture.engines.size)
            restarted = client(dispatcher, ControlledSessionStorage(storage.value), SuspendedRefreshEngine(dispatcher, old), autoLoad = true)
            restarted.auth.awaitInitialization()
            val restored = restarted.auth.currentSessionOrNull()
            assertEquals(old, restored)
            val forbidden = publications.filter { (state, verifiedClear) ->
                !verifiedClear && (state == AuthState.SignedOut ||
                    state is AuthState.ErrorRecoverable && state.message != SupabaseAuthManager.SESSION_CLEANUP_ERROR)
            }
            assertEquals(
                "A newly failed account-switch cleanup must preserve its error through dismiss and every publication",
                listOf(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), emptyList<Pair<AuthState, Boolean>>()),
                listOf(stateAfterFailure, stateAfterDismiss, forbidden)
            )
        } finally {
            observer.cancel()
            manager.shutdown()
            fixture.owner.close()
            restarted?.close()
        }
    }

    private suspend fun TestScope.verifyCleanupErrorSurvivesInteractive(outcome: String) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old).apply { deleteFails = true }
        val owner = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old)).owner
        val provider = mockk<WeChatCodeProvider>()
        val gateway = mockk<WeChatAuthGateway>()
        every { provider.isConfigured } returns true
        every { provider.isWeChatInstalled(any()) } returns true
        every { gateway.isConfigured } returns true
        coEvery { gateway.issueChallenge(any(), any()) } coAnswers {
            WeChatGatewayResult.Success(WeChatChallenge(secondArg<WeChatAuthRequest>().state, "synthetic-nonce", "synthetic-correlation", 60))
        }
        coEvery { provider.requestCode(any(), any()) } returns if (outcome == "wechat-cancel") WeChatCodeResult.Cancelled else WeChatCodeResult.Failure(WeChatAuthError.BACKEND_ERROR)
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", provider, gateway, { "synthetic-device" }, backgroundScope)
        var restarted: SupabaseClient? = null
        val publications = mutableListOf<Pair<AuthState, Boolean>>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.state.collect { publications += it to owner.isLocalSessionCleared }
        }
        try {
            runCurrent()
            manager.signOut()
            assertEquals(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), manager.state.value)
            if (outcome.startsWith("google")) {
                withGoogleCredential(cancel = outcome == "google-cancel", providerFailure = if (outcome == "google-failure") IOException("Synthetic provider failure") else null) {
                    assertFalse(manager.signInWithGoogle(RuntimeEnvironment.getApplication()))
                }
            } else assertFalse(manager.signInWithWeChat(RuntimeEnvironment.getApplication()))
            val stateAfterAttempt = manager.state.value
            manager.dismissError()
            val stateAfterDismiss = manager.state.value
            restarted = client(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old), autoLoad = true)
            runCurrent()
            assertEquals(old, restarted.auth.currentSessionOrNull())
            assertEquals(listOf(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR)), listOf(stateAfterAttempt, stateAfterDismiss))
            assertEquals(old, storage.value)
            assertEquals(
                "Every observable publication must preserve unverified cleanup, including transient provider/cancel states",
                emptyList<Pair<AuthState, Boolean>>(),
                publications.filter { (state, verifiedClear) ->
                    !verifiedClear && (state == AuthState.SignedOut ||
                        state is AuthState.ErrorRecoverable && state.message != SupabaseAuthManager.SESSION_CLEANUP_ERROR)
                }
            )
        } finally {
            observer.cancel()
            manager.shutdown()
            owner.close()
            restarted?.close()
        }
    }

    @Test
    fun `PKCE deletion that silently retains its value cannot report completed local logout`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old)
        val verifier = object : CodeVerifierCache {
            override suspend fun saveCodeVerifier(codeVerifier: String) = Unit
            override suspend fun loadCodeVerifier(): String = "synthetic-retained-verifier"
            override suspend fun deleteCodeVerifier() = Unit
        }
        val owner = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old), verifier = verifier).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        try {
            runCurrent()
            manager.signOut()
            assertNull(storage.value)
            assertFalse(owner.isLocalSessionCleared)
            assertEquals(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), manager.state.value)
            assertTrue(runCatching { owner.captureClient() }.exceptionOrNull() is SupabaseClientLifecycleException)
        } finally {
            manager.shutdown()
            owner.close()
        }
    }

    @Test
    fun `offline logout removes persisted credentials and stays local scope`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old)
        val engine = SuspendedRefreshEngine(dispatcher, old, logoutFailure = IOException("Synthetic offline transport"))
        val owner = owned(dispatcher, storage, engine).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        try {
            runCurrent()
            manager.signOut()
            runCurrent()
            assertEquals(1, engine.logoutCalls)
            assertNull(storage.value)
            assertEquals(AuthState.SignedOut, manager.state.value)
        } finally {
            manager.shutdown()
            owner.close()
        }
    }

    @Test
    fun `logout caller cancellation still removes the canonical session before returning`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage()
        val engine = SuspendedRefreshEngine(dispatcher, old, suspendLogout = true)
        val owner = owned(dispatcher, storage, engine).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        try {
            runCurrent()
            owner.auth.importSession(old)
            val logout = launch { manager.signOut() }
            engine.logoutStarted.await()
            logout.cancel()
            logout.join()
            runCurrent()
            assertNull(storage.value)
            assertTrue(owner.isLocalSessionCleared)
            assertEquals(AuthState.SignedOut, manager.state.value)
        } finally {
            manager.shutdown()
            owner.close()
        }
    }

    @Test
    fun `a canceled local logout clears storage and restart before a suspended picker is released`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old)
        val engine = SuspendedRefreshEngine(dispatcher, old, suspendLogout = true)
        val owner = owned(dispatcher, storage, engine).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        val pickerStarted = CompletableDeferred<Unit>()
        val pickerRelease = CompletableDeferred<Unit>()
        try {
            runCurrent()
            withGoogleCredential(pickerStarted = pickerStarted, pickerRelease = pickerRelease) {
                val login = async { manager.signInWithGoogle(RuntimeEnvironment.getApplication()) }
                pickerStarted.await()
                val logout = launch { manager.signOut() }
                engine.logoutStarted.await()
                logout.cancel()
                logout.join()
                assertNull("Accepted logout must not leave an old account for the next restart", storage.value)
                assertEquals(AuthState.SignedOut, manager.state.value)
                assertFalse("Local cleanup must finish while the old picker is still suspended", pickerRelease.isCompleted)
                val restarted = client(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old), autoLoad = true)
                try {
                    assertFalse(restarted.auth.loadFromStorage())
                    assertNull(restarted.auth.currentSessionOrNull())
                } finally { restarted.close() }
                pickerRelease.complete(Unit)
                assertFalse(login.await())
                assertNull(storage.value)
                assertEquals(AuthState.SignedOut, manager.state.value)
            }
        } finally {
            pickerRelease.complete(Unit)
            manager.shutdown()
            owner.close()
        }
    }

    @Test
    fun `a superseded picker cannot report signed out before suspended canonical cleanup completes`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old).apply { blockDelete = true }
        val owner = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old)).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        val pickerStarted = CompletableDeferred<Unit>()
        val pickerRelease = CompletableDeferred<Unit>()
        try {
            runCurrent()
            withGoogleCredential(pickerStarted = pickerStarted, pickerRelease = pickerRelease) {
                val login = async { manager.signInWithGoogle(RuntimeEnvironment.getApplication()) }
                pickerStarted.await()
                val logout = launch { manager.signOut() }
                storage.deleteStarted.await()
                pickerRelease.complete(Unit)
                assertFalse(login.await())
                assertEquals(AuthState.Checking, manager.state.value)
                assertEquals(old, storage.value)
                storage.releaseDelete.complete(Unit)
                logout.join()
                assertNull(storage.value)
                assertEquals(AuthState.SignedOut, manager.state.value)
            }
        } finally {
            pickerRelease.complete(Unit)
            storage.releaseDelete.complete(Unit)
            manager.shutdown()
            owner.close()
        }
    }

    @Test
    fun `a new Google login during owner logout transition can sign in another account`() = runTest(timeout = 15.seconds) {
        verifyNewLoginDuringLogout("account-b")
    }

    @Test
    fun `a new Google login during owner logout transition can sign in the same account`() = runTest(timeout = 15.seconds) {
        verifyNewLoginDuringLogout("account-a")
    }

    private suspend fun TestScope.verifyNewLoginDuringLogout(account: String) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val new = syntheticSession(account, "new-login")
        val storage = ControlledSessionStorage(old)
        val engine = SuspendedRefreshEngine(dispatcher, old, suspendLogout = true)
        val owner = owned(dispatcher, storage, engine, new).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        var restarted: SupabaseClient? = null
        try {
            runCurrent()
            val logout = launch { manager.signOut() }
            engine.logoutStarted.await()
            withGoogleCredential {
                val login = async { manager.signInWithGoogle(RuntimeEnvironment.getApplication()) }
                runCurrent()
                assertFalse(login.isCompleted)
                engine.releaseLogout.complete(Unit)
                logout.join()
                assertTrue(login.await())
            }
            runCurrent()
            assertEquals(AuthState.SignedIn(account, null), manager.state.value)
            assertEquals(new, storage.value)
            assertEquals(new, owner.auth.currentSessionOrNull())
            owner.close()
            restarted = client(dispatcher, storage, SuspendedRefreshEngine(dispatcher, new), autoLoad = true)
            runCurrent()
            assertEquals(new, restarted.auth.currentSessionOrNull())
        } finally {
            engine.releaseLogout.complete(Unit)
            manager.shutdown()
            owner.close()
            restarted?.close()
        }
    }

    @Test
    fun `a late same account Google exchange cannot publish after a queued logout`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old)
        val fixture = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old), suspendLogin = true)
        val manager = SupabaseAuthManager(fixture.owner, "synthetic-google-client", scope = backgroundScope)
        try {
            runCurrent()
            withGoogleCredential {
                val login = async { manager.signInWithGoogle(RuntimeEnvironment.getApplication()) }
                runCurrent()
                val exchange = fixture.engines.last()
                exchange.loginStarted.await()
                val logout = launch { manager.signOut() }
                runCurrent()
                exchange.releaseLogin.complete(Unit)
                assertFalse(login.await())
                logout.join()
                runCurrent()
                assertNull(storage.value)
                assertNull(fixture.owner.auth.currentSessionOrNull())
                assertEquals(AuthState.SignedOut, manager.state.value)
            }
        } finally {
            manager.shutdown()
            fixture.owner.close()
        }
    }

    @Test
    fun `canceling the Google picker preserves a stored session until credentials are obtained`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage(old)
        val fixture = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old))
        val manager = SupabaseAuthManager(fixture.owner, "synthetic-google-client", scope = backgroundScope)
        try {
            runCurrent()
            withGoogleCredential(cancel = true) {
                assertFalse(manager.signInWithGoogle(RuntimeEnvironment.getApplication()))
            }
            assertEquals(old, storage.value)
            assertEquals(1, fixture.leases.size)
        } finally {
            manager.shutdown()
            fixture.owner.close()
        }
    }

    private suspend fun TestScope.verifyFailedCleanup(deleteFails: Boolean) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = syntheticSession("account-a", "old-a")
        val storage = ControlledSessionStorage()
        val owner = owned(dispatcher, storage, SuspendedRefreshEngine(dispatcher, old)).owner
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        try {
            runCurrent()
            owner.auth.importSession(old)
            if (deleteFails) storage.deleteFails = true else storage.readbackFails = true
            manager.signOut()
            runCurrent()
            assertEquals(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), manager.state.value)
            assertFalse(owner.isLocalSessionCleared)
            assertTrue(runCatching { owner.captureClient() }.exceptionOrNull() is SupabaseClientLifecycleException)
            assertTrue(owner.sessionStatus.value is SessionStatus.Initializing)
            manager.dismissError()
            assertEquals(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), manager.state.value)
            storage.deleteFails = false
            storage.readbackFails = false
            manager.signOut()
            runCurrent()
            assertEquals(AuthState.SignedOut, manager.state.value)
            assertTrue(owner.isLocalSessionCleared)
            assertNull(storage.value)
        } finally {
            manager.shutdown()
            owner.close()
        }
    }

    private data class OwnedFixture(val owner: GenerationOwnedSupabaseClient, val leases: List<SessionManager>, val engines: List<SuspendedRefreshEngine>)

    private fun TestScope.owned(
        dispatcher: TestDispatcher,
        storage: ControlledSessionStorage,
        initialEngine: SuspendedRefreshEngine,
        loginResponse: UserSession = syntheticSession("account-a", "new-login"),
        suspendLogin: Boolean = false,
        verifier: CodeVerifierCache = MemoryCodeVerifierCache()
    ): OwnedFixture {
        val leases = mutableListOf<SessionManager>()
        val engines = mutableListOf<SuspendedRefreshEngine>()
        val owner = GenerationOwnedSupabaseClient(
            sessionManager = storage,
            codeVerifierCache = verifier,
            isSessionStored = { storage.isSessionStored() },
            factory = { lease, verifier ->
                val engine = if (leases.isEmpty()) initialEngine else SuspendedRefreshEngine(dispatcher, loginResponse, loginResponse = loginResponse, suspendLogin = suspendLogin)
                leases += lease
                engines += engine
                client(dispatcher, lease, engine, autoLoad = true, verifier = verifier)
            },
            scope = backgroundScope
        )
        return OwnedFixture(owner, leases, engines)
    }

    private suspend fun withGoogleCredential(
        cancel: Boolean = false,
        pickerStarted: CompletableDeferred<Unit>? = null,
        pickerRelease: CompletableDeferred<Unit>? = null,
        providerFailure: Exception? = null,
        action: suspend () -> Unit
    ) {
        val credentials = mockk<CredentialManager>()
        mockkObject(CredentialManager.Companion)
        try {
            every { CredentialManager.create(any()) } returns credentials
            if (cancel) {
                coEvery { credentials.getCredential(any(), any<androidx.credentials.GetCredentialRequest>()) } throws GetCredentialCancellationException()
            } else if (providerFailure != null) {
                coEvery { credentials.getCredential(any(), any<androidx.credentials.GetCredentialRequest>()) } throws providerFailure
            } else {
                val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                val header = encoded.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray())
                val payload = encoded.encodeToString("{\"iss\":\"https://accounts.google.com\",\"sub\":\"synthetic-google\",\"aud\":\"synthetic-google-client\",\"email\":\"synthetic@example.test\",\"exp\":4102444800}".toByteArray())
                val credential = GoogleIdTokenCredential.Builder().setId("synthetic@example.test").setIdToken("$header.$payload.c3ludGhldGlj").build()
                coEvery { credentials.getCredential(any(), any<androidx.credentials.GetCredentialRequest>()) } coAnswers {
                    pickerStarted?.complete(Unit)
                    pickerRelease?.await()
                    GetCredentialResponse(credential)
                }
            }
            action()
        } finally { unmockkObject(CredentialManager.Companion) }
    }

    private suspend fun TestScope.verifyLateRefreshPreservesNewLogin(newAccount: String) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = ControlledSessionStorage()
        val old = syntheticSession("account-a", "old-a")
        val new = syntheticSession(newAccount, "new-login")
        val engine = SuspendedRefreshEngine(dispatcher, old.copy(accessToken = "late-old-a"))
        val fixture = owned(dispatcher, storage, engine, new)
        val client = fixture.owner
        val manager = SupabaseAuthManager(client, "synthetic-google-client", scope = backgroundScope)
        var restarted: SupabaseClient? = null
        try {
            runCurrent()
            client.auth.importSession(old)
            // This public SDK call also runs from AccessToken.checkAccessToken, outside the app auth mutex.
            val outgoing = client.captureClient()
            val outgoingLease = fixture.leases.first()
            val refresh = async { runCatching { outgoing.auth.refreshCurrentSession() } }
            engine.refreshStarted.await()

            manager.signOut()
            runCurrent()
            assertNull(client.auth.currentSessionOrNull())
            assertNull(storage.loadSessionOrNull())
            withGoogleCredential {
                assertTrue(manager.signInWithGoogle(RuntimeEnvironment.getApplication()))
            }
            runCurrent()
            assertEquals(new, client.auth.currentSessionOrNull())
            assertEquals(new, storage.loadSession())

            engine.releaseRefresh.complete(Unit)
            assertTrue(refresh.await().isFailure)
            assertTrue("Retired saves must throw rather than silently accept stale SDK publication",
                runCatching { outgoingLease.saveSession(old.copy(accessToken = "late-old-a")) }.exceptionOrNull() is CancellationException)
            assertTrue("Retired failure cleanup must not delete the new session",
                runCatching { outgoingLease.deleteSession() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { outgoingLease.loadSessionOrNull() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { client.prepareForSignIn(expectedClient = outgoing) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { client.signOut(expectedClient = outgoing) }.exceptionOrNull() is CancellationException)
            assertEquals(AuthState.SignedIn(newAccount, null), manager.state.value)
            val currentAfterResponse = client.auth.currentSessionOrNull()
            val storageAfterResponse = storage.loadSessionOrNull()
            client.close()

            restarted = client(dispatcher, storage, SuspendedRefreshEngine(dispatcher, new), autoLoad = true)
            runCurrent()
            assertTrue(restarted.auth.sessionStatus.value is SessionStatus.Authenticated)
            val afterRestart = restarted.auth.currentSessionOrNull()
            // Collect all three real SDK outcomes before asserting; the restart is not skipped on red.
            val actual = listOf(currentAfterResponse, storageAfterResponse, afterRestart)
                .map { it?.accessToken }
            assertEquals("current SDK, saved session and restarted SDK must retain the newer login", listOf(new.accessToken, new.accessToken, new.accessToken), actual)
            assertEquals(listOf(new.user?.id, new.user?.id, new.user?.id), listOf(currentAfterResponse?.user?.id, storageAfterResponse?.user?.id, afterRestart?.user?.id))
        } finally {
            manager.shutdown()
            client.close()
            restarted?.close()
        }
    }

    private fun client(
        dispatcher: TestDispatcher,
        storage: SessionManager,
        engine: SuspendedRefreshEngine,
        autoLoad: Boolean = false,
        verifier: CodeVerifierCache = MemoryCodeVerifierCache()
    ): SupabaseClient = createSupabaseClient("https://sdk-lifecycle.example.test", "synthetic-public-key") {
        httpEngine = engine
        coroutineDispatcher = dispatcher
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            sessionManager = storage
            codeVerifierCache = verifier
            autoLoadFromStorage = autoLoad
            autoSetupPlatform = false
        }
        install(Postgrest)
    }

    private fun syntheticSession(account: String, token: String) = UserSession(
        accessToken = token,
        refreshToken = "synthetic-refresh-$token",
        expiresIn = 86_400,
        tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = account),
        expiresAt = Clock.System.now() + 1.days
    )
}

private class ControlledSessionStorage(var value: UserSession? = null) : SessionManager {
    var deleteFails = false
    var readbackFails = false
    var blockDelete = false
    val deleteStarted = CompletableDeferred<Unit>()
    val releaseDelete = CompletableDeferred<Unit>()
    override suspend fun saveSession(session: UserSession) { value = session }
    override suspend fun loadSession(): UserSession = value ?: error("No synthetic session")
    override suspend fun deleteSession() {
        if (deleteFails) throw IOException("Synthetic session deletion failure")
        if (blockDelete) {
            deleteStarted.complete(Unit)
            releaseDelete.await()
        }
        value = null
    }
    fun isSessionStored(): Boolean {
        if (readbackFails) throw IOException("Synthetic canonical readback failure")
        return value != null
    }
}

@OptIn(InternalAPI::class)
internal class SuspendedRefreshEngine(
    override val dispatcher: TestDispatcher,
    private val refreshResponse: UserSession,
    private val logoutStatus: HttpStatusCode = HttpStatusCode.NoContent,
    private val loginResponse: UserSession = refreshResponse,
    private val suspendLogout: Boolean = false,
    private val suspendLogin: Boolean = false,
    private val logoutFailure: Exception? = null
) : HttpClientEngineBase("controlled-sdk-auth-lifecycle") {
    override val config = HttpClientEngineConfig()
    override val supportedCapabilities = setOf(HttpTimeoutCapability)
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    val logoutStarted = CompletableDeferred<Unit>()
    val releaseLogout = CompletableDeferred<Unit>()
    val loginStarted = CompletableDeferred<Unit>()
    val releaseLogin = CompletableDeferred<Unit>()
    var logoutCalls = 0
        private set
    var refreshCancelled = false
        private set
    val rpcAuthorizations = mutableListOf<String?>()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val (status, body) = when {
            data.url.encodedPath == "/auth/v1/token" && data.url.parameters["grant_type"] == "refresh_token" -> {
                refreshStarted.complete(Unit)
                try {
                    releaseRefresh.await()
                } catch (cancelled: CancellationException) {
                    refreshCancelled = true
                    throw cancelled
                }
                HttpStatusCode.OK to Json.encodeToString(refreshResponse)
            }
            data.url.encodedPath == "/auth/v1/logout" -> {
                assertEquals("local", data.url.parameters["scope"])
                logoutCalls += 1
                logoutStarted.complete(Unit)
                logoutFailure?.let { throw it }
                if (suspendLogout) releaseLogout.await()
                logoutStatus to if (logoutStatus == HttpStatusCode.NoContent) "" else "{\"error\":\"server_error\",\"error_description\":\"synthetic logout failure\"}"
            }
            data.url.encodedPath == "/auth/v1/token" && data.url.parameters["grant_type"] == "id_token" -> {
                loginStarted.complete(Unit)
                if (suspendLogin) releaseLogin.await()
                HttpStatusCode.OK to Json.encodeToString(loginResponse)
            }
            data.url.encodedPath.startsWith("/rest/v1/rpc/") -> {
                rpcAuthorizations += data.headers[HttpHeaders.Authorization]
                HttpStatusCode.BadRequest to "{\"synthetic\":\"intentional-rpc-denial\"}"
            }
            else -> error("Unexpected synthetic auth route")
        }
        return HttpResponseData(
            statusCode = status,
            requestTime = GMTDate(),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(body),
            callContext = callContext()
        )
    }
}

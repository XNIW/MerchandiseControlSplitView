package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseAuthManagerTest {
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `restore refreshes the persisted session before publishing signed in`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = authenticatedStatus(),
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)

        manager.restoreSession()
        advanceUntilIdle()

        assertEquals(1, controller.refreshCalls)
        assertEquals(0, controller.clearCalls)
        assertEquals(
            AuthState.SignedIn("00000000-0000-4000-8000-000000000139", "qa@example.test"),
            manager.state.value
        )
        managerScope.cancel()
    }

    @Test
    fun `restore clears a server-invalid persisted session and stays signed out`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = authenticatedStatus(),
            refreshResult = StoredSessionRefreshResult.Invalid("session_not_found")
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)

        manager.restoreSession()
        advanceUntilIdle()

        assertEquals(1, controller.refreshCalls)
        assertEquals(1, controller.clearCalls)
        assertEquals(AuthState.SignedOut, manager.state.value)
        managerScope.cancel()
    }

    @Test
    fun `restore preserves offline-first identity when remote validation is deferred`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = authenticatedStatus(),
            refreshResult = StoredSessionRefreshResult.Deferred
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)

        manager.restoreSession()
        advanceUntilIdle()

        assertEquals(1, controller.refreshCalls)
        assertEquals(0, controller.clearCalls)
        assertTrue(manager.state.value is AuthState.SignedIn)
        managerScope.cancel()
    }

    @Test
    fun `restore fails closed when authenticated storage has no usable account identity`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = authenticatedStatus(),
            refreshResult = StoredSessionRefreshResult.Refreshed,
            sessionUser = null
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)

        manager.restoreSession()
        advanceUntilIdle()

        assertEquals(1, controller.refreshCalls)
        assertEquals(1, controller.clearCalls)
        assertEquals(AuthState.SignedOut, manager.state.value)
        managerScope.cancel()
    }

    @Test
    fun `restore does not refresh when storage has no authenticated session`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.NotAuthenticated(),
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)

        manager.restoreSession()
        advanceUntilIdle()

        assertEquals(0, controller.refreshCalls)
        assertEquals(AuthState.SignedOut, manager.state.value)
        managerScope.cancel()
    }

    @Test
    fun `stored session authenticated after restore timeout still completes validated restore`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.Initializing,
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            runCurrent()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            assertFalse(manager.state.value is AuthState.SignedIn)
            assertEquals(0, controller.clearCalls)

            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertTrue(manager.state.value is AuthState.SignedIn)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `transient SDK refresh failure at bootstrap can later restore authenticated session`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.Initializing,
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            runCurrent()
            controller.sessionStatus.value = SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(IOException("synthetic offline"))
            )
            runCurrent()
            assertFalse(manager.state.value is AuthState.SignedIn)
            assertEquals(0, controller.clearCalls)

            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertTrue(manager.state.value is AuthState.SignedIn)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `explicit logout prevents delayed stored authentication from signing in again`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.Initializing,
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            manager.signOut()
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(SignOutScope.LOCAL, controller.lastSignOutScope)
            assertEquals(0, controller.refreshCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `definitively rejected session cannot recover from a late authenticated event`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = authenticatedStatus(),
            refreshResult = StoredSessionRefreshResult.Invalid("session_not_found")
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceUntilIdle()
            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(1, controller.clearCalls)
            assertEquals(1, controller.refreshCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `SDK definitive signout ends bootstrap recovery before a late authentication`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.Initializing,
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            runCurrent()
            controller.sessionStatus.value = SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(IOException("synthetic offline"))
            )
            runCurrent()
            controller.sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(0, controller.refreshCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `explicit account switch supersedes pending bootstrap and ignores old stored event`() = runTest {
        val originalUser = SupabaseSessionUser("00000000-0000-4000-8000-000000000139", "qa@example.test")
        val newUser = SupabaseSessionUser("00000000-0000-4000-8000-000000000140", "other@example.test")
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.Initializing,
            refreshResult = StoredSessionRefreshResult.Refreshed,
            sessionUser = originalUser
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(
            sessionController = controller,
            scope = managerScope,
            wechatCodeProvider = FakeWeChatCodeProvider(
                WeChatCodeResult.Success("temporary-code", "state-value")
            ),
            wechatGateway = FakeWeChatGateway(expectedState = "state-value"),
            wechatDeviceIdProvider = { "00000000-0000-4000-8000-000000000201" },
            nowEpochMillis = { 1_000L }
        )
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            controller.sessionUser = newUser
            assertTrue(manager.signInWithWeChat(stubContext()))
            controller.sessionUser = originalUser
            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertEquals(AuthState.SignedIn(newUser.id, newUser.email), manager.state.value)
            assertEquals(0, controller.refreshCalls)
            assertEquals(1, controller.importCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `logout request fences invalid bootstrap result while validation holds auth mutex`() = runTest {
        val result = CompletableDeferred<StoredSessionRefreshResult>()
        val controller = FakeSupabaseAuthSessionController(SessionStatus.Initializing, StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = { result.await() }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            runCurrent()
            assertEquals(1, controller.refreshCalls)
            val logout = launch { manager.signOut() }
            runCurrent()
            assertFalse(logout.isCompleted)
            result.complete(StoredSessionRefreshResult.Invalid("session_not_found"))
            advanceUntilIdle()

            assertTrue(logout.isCompleted)
            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(SignOutScope.LOCAL, controller.lastSignOutScope)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `explicit sign in request fences suspended bootstrap before its mutex becomes available`() = runTest {
        val result = CompletableDeferred<StoredSessionRefreshResult>()
        val controller = FakeSupabaseAuthSessionController(SessionStatus.Initializing, StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = { result.await() }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(
            sessionController = controller,
            scope = managerScope,
            wechatCodeProvider = FakeWeChatCodeProvider(WeChatCodeResult.Success("temporary-code", "state-value")),
            wechatGateway = FakeWeChatGateway(expectedState = "state-value"),
            wechatDeviceIdProvider = { "00000000-0000-4000-8000-000000000201" },
            nowEpochMillis = { 1_000L }
        )
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            runCurrent()
            // Existing single-flight contract rejects the busy operation, but its intent
            // must already prevent the old restore from publishing or clearing a session.
            assertFalse(manager.signInWithWeChat(stubContext()))
            result.complete(StoredSessionRefreshResult.Refreshed)
            advanceUntilIdle()
            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(0, controller.clearCalls)

            val newUser = SupabaseSessionUser("00000000-0000-4000-8000-000000000140", "other@example.test")
            controller.sessionUser = newUser
            assertTrue(manager.signInWithWeChat(stubContext()))
            assertEquals(AuthState.SignedIn(newUser.id, newUser.email), manager.state.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `changed SDK account during suspended validation is never cleared by old invalid result`() = runTest {
        val result = CompletableDeferred<StoredSessionRefreshResult>()
        val controller = FakeSupabaseAuthSessionController(SessionStatus.Initializing, StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = { result.await() }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            runCurrent()
            controller.sessionUser = SupabaseSessionUser("00000000-0000-4000-8000-000000000140", "other@example.test")
            result.complete(StoredSessionRefreshResult.Invalid("session_not_found"))
            advanceUntilIdle()

            assertEquals(0, controller.clearCalls)
            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(1, controller.refreshCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `authenticated event emitted by validation does not recursively refresh bootstrap`() = runTest {
        val controller = FakeSupabaseAuthSessionController(SessionStatus.Initializing, StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = {
            val previous = authenticatedStatus().session
            controller.sessionStatus.value = SessionStatus.Authenticated(previous, SessionSource.Refresh(previous))
            yield()
            StoredSessionRefreshResult.Refreshed
        }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()

            assertTrue(manager.state.value is AuthState.SignedIn)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `cancelled delayed validation cannot publish or be resumed by another SDK event`() = runTest {
        val controller = FakeSupabaseAuthSessionController(SessionStatus.Initializing, StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = { throw CancellationException("synthetic cancellation") }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1)
            runCurrent()
            controller.sessionStatus.value = authenticatedStatus()
            advanceUntilIdle()
            val session = authenticatedStatus().session
            controller.sessionStatus.value = SessionStatus.Authenticated(session, SessionSource.Refresh(session))
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `SDK invalidation during initial validation settles checking before queued observer`() = runTest {
        val controller = FakeSupabaseAuthSessionController(authenticatedStatus(), StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = {
            controller.sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
            StoredSessionRefreshResult.Invalid("session_not_found")
        }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            assertEquals(AuthState.Checking, manager.state.value)
            manager.restoreSession()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `explicit Google intent during initial validation cannot strand checking`() = runTest {
        val result = CompletableDeferred<StoredSessionRefreshResult>()
        val controller = FakeSupabaseAuthSessionController(authenticatedStatus(), StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = { result.await() }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            runCurrent()
            assertEquals(AuthState.Checking, manager.state.value)
            assertFalse(manager.signInWithGoogle(stubContext()))
            result.complete(StoredSessionRefreshResult.Refreshed)
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `SDK account change during initial validation settles checking without clearing replacement`() = runTest {
        val controller = FakeSupabaseAuthSessionController(authenticatedStatus(), StoredSessionRefreshResult.Refreshed)
        controller.refreshHandler = {
            controller.sessionUser = SupabaseSessionUser("00000000-0000-4000-8000-000000000140", "other@example.test")
            StoredSessionRefreshResult.Invalid("session_not_found")
        }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, manager.state.value)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `server refresh may populate missing stored account identity without being an account switch`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            authenticatedStatus(), StoredSessionRefreshResult.Refreshed, sessionUser = null
        )
        val validatedUser = SupabaseSessionUser("00000000-0000-4000-8000-000000000139", "qa@example.test")
        controller.refreshHandler = {
            controller.sessionUser = validatedUser
            StoredSessionRefreshResult.Refreshed
        }
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)
        try {
            manager.restoreSession()
            advanceUntilIdle()

            assertEquals(AuthState.SignedIn(validatedUser.id, validatedUser.email), manager.state.value)
            assertEquals(1, controller.refreshCalls)
            assertEquals(0, controller.clearCalls)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `only canonical revocation codes invalidate a stored session`() {
        listOf(
            "invalid_grant",
            "session_not_found",
            "session_expired",
            "refresh_token_not_found",
            "refresh_token_already_used",
            "bad_jwt",
            "invalid_credentials"
        ).forEach { code ->
            assertTrue(code, isDefinitiveStoredSessionFailure(code))
        }
        assertFalse(isDefinitiveStoredSessionFailure("request_timeout"))
        assertFalse(isDefinitiveStoredSessionFailure("unexpected_failure"))
        assertFalse(isDefinitiveStoredSessionFailure(null))
    }

    @Test
    fun `sign out is explicitly local scope and cannot revoke other devices`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = authenticatedStatus(),
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(controller, managerScope)

        manager.signOut()
        advanceUntilIdle()

        assertEquals(SignOutScope.LOCAL, controller.lastSignOutScope)
        assertEquals(AuthState.SignedOut, manager.state.value)
        managerScope.cancel()
    }

    @Test
    fun `wechat adapter success imports session into the existing Supabase owner`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.NotAuthenticated(),
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val codeProvider = FakeWeChatCodeProvider(
            result = WeChatCodeResult.Success("temporary-code", "state-value")
        )
        val gateway = FakeWeChatGateway(expectedState = "state-value")
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(
            sessionController = controller,
            scope = managerScope,
            wechatCodeProvider = codeProvider,
            wechatGateway = gateway,
            wechatDeviceIdProvider = { "00000000-0000-4000-8000-000000000201" },
            nowEpochMillis = { 1_000L }
        )

        assertTrue(manager.signInWithWeChat(stubContext()))
        assertEquals(1, controller.importCalls)
        assertEquals("fixture-access-token", controller.lastImportedAccessToken)
        assertTrue(manager.state.value is AuthState.SignedIn)
        managerScope.cancel()
    }

    @Test
    fun `wechat cancellation is neutral and does not import a session`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.NotAuthenticated(),
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(
            sessionController = controller,
            scope = managerScope,
            wechatCodeProvider = FakeWeChatCodeProvider(WeChatCodeResult.Cancelled),
            wechatGateway = FakeWeChatGateway(),
            wechatDeviceIdProvider = { "00000000-0000-4000-8000-000000000201" },
            nowEpochMillis = { 1_000L }
        )

        assertFalse(manager.signInWithWeChat(stubContext()))
        assertEquals(0, controller.importCalls)
        assertEquals(AuthState.SignedOut, manager.state.value)
        managerScope.cancel()
    }

    @Test
    fun `wechat callback state mismatch fails before backend exchange`() = runTest {
        val controller = FakeSupabaseAuthSessionController(
            initialStatus = SessionStatus.NotAuthenticated(),
            refreshResult = StoredSessionRefreshResult.Refreshed
        )
        val gateway = FakeWeChatGateway(expectedState = "expected-state")
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val manager = SupabaseAuthManager.createForTest(
            sessionController = controller,
            scope = managerScope,
            wechatCodeProvider = FakeWeChatCodeProvider(
                WeChatCodeResult.Success("temporary-code", "wrong-state")
            ),
            wechatGateway = gateway,
            wechatDeviceIdProvider = { "00000000-0000-4000-8000-000000000201" },
            nowEpochMillis = { 1_000L }
        )

        assertFalse(manager.signInWithWeChat(stubContext()))
        assertEquals(0, gateway.exchangeCalls)
        assertEquals(0, controller.importCalls)
        assertTrue(manager.state.value is AuthState.ErrorRecoverable)
        managerScope.cancel()
    }

    private fun stubContext(): Context = mockk {
        every { getString(any()) } returns "safe localized auth error"
    }

    private fun authenticatedStatus(): SessionStatus.Authenticated =
        SessionStatus.Authenticated(
            session = UserSession(
                accessToken = "synthetic-access-token",
                refreshToken = "synthetic-refresh-token",
                expiresIn = 3_600,
                tokenType = "bearer"
            ),
            source = SessionSource.Storage
        )
}

private class FakeSupabaseAuthSessionController(
    initialStatus: SessionStatus,
    private val refreshResult: StoredSessionRefreshResult,
    var sessionUser: SupabaseSessionUser? = SupabaseSessionUser(
        id = "00000000-0000-4000-8000-000000000139",
        email = "qa@example.test"
    )
) : SupabaseAuthSessionController {
    override val sessionStatus = MutableStateFlow(initialStatus)
    var refreshCalls = 0
        private set
    var refreshHandler: (suspend () -> StoredSessionRefreshResult)? = null
    var clearCalls = 0
        private set
    var lastSignOutScope: SignOutScope? = null
        private set
    var importCalls = 0
        private set
    var lastImportedAccessToken: String? = null
        private set

    override fun currentUserOrNull() = sessionUser

    override suspend fun refreshStoredSession(): StoredSessionRefreshResult {
        refreshCalls += 1
        return refreshHandler?.invoke() ?: refreshResult
    }

    override suspend fun clearSession() {
        clearCalls += 1
        sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
    }

    override suspend fun signInWithGoogleIdToken(idToken: String) = Unit

    override suspend fun importWeChatSession(accessToken: String, refreshToken: String) {
        importCalls += 1
        lastImportedAccessToken = accessToken
    }

    override suspend fun signOut(scope: SignOutScope) {
        lastSignOutScope = scope
        sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
    }
}

private class FakeWeChatCodeProvider(
    private val result: WeChatCodeResult,
    override val isConfigured: Boolean = true,
    private val installed: Boolean = true
) : WeChatCodeProvider {
    override fun isWeChatInstalled(context: Context) = installed
    override suspend fun requestCode(context: Context, state: String): WeChatCodeResult =
        when (val current = result) {
            is WeChatCodeResult.Success -> current.copy(
                state = if (current.state == "state-value") state else current.state
            )
            else -> current
        }
}

private class FakeWeChatGateway(
    private val expectedState: String? = null,
    override val isConfigured: Boolean = true
) : WeChatAuthGateway {
    var exchangeCalls = 0
        private set

    override suspend fun issueChallenge(
        deviceId: String,
        request: WeChatAuthRequest
    ): WeChatGatewayResult<WeChatChallenge> = WeChatGatewayResult.Success(
        WeChatChallenge(
            state = expectedState ?: request.state,
            nonce = request.nonce,
            correlationId = "90000000-0000-4000-8000-000000000201",
            expiresInSeconds = 300
        )
    )

    override suspend fun exchange(
        challenge: WeChatChallenge,
        code: String,
        deviceId: String
    ): WeChatGatewayResult<WeChatSupabaseSession> {
        exchangeCalls += 1
        return WeChatGatewayResult.Success(
            WeChatSupabaseSession(
                accessToken = "fixture-access-token",
                refreshToken = "fixture-refresh-token",
                expiresAt = 4_600L,
                expiresIn = 3_600L,
                userId = "00000000-0000-4000-8000-000000000201"
            )
        )
    }
}

package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    private val sessionUser: SupabaseSessionUser? = SupabaseSessionUser(
        id = "00000000-0000-4000-8000-000000000139",
        email = "qa@example.test"
    )
) : SupabaseAuthSessionController {
    override val sessionStatus = MutableStateFlow(initialStatus)
    var refreshCalls = 0
        private set
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
        return refreshResult
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

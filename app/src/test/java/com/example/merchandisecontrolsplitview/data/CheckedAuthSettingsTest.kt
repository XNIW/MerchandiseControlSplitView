package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.SharedPreferences
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.createDefaultSettingsKey
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Real SDK/manager boundary; only SharedPreferences' memory/disk outcome and HTTP are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@OptIn(SupabaseInternal::class, ExperimentalCoroutinesApi::class)
class CheckedAuthSettingsTest {
    private val projectUrl = "https://sdk-lifecycle.example.test"
    private val projectKey = "${createDefaultSettingsKey(projectUrl.split("//").last())}-${SettingsSessionManager.SETTINGS_KEY}"

    @Test
    fun `failed logout commit is fail closed even when memory clears and disk restores then retry clears disk`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = MemoryAndDiskPreferences()
        val old = session()
        val engine = SuspendedRefreshEngine(dispatcher, old)
        val owner = owned(preferences, dispatcher, engine)
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        var afterFailedCommit: SupabaseClient? = null
        var afterRetry: SupabaseClient? = null
        try {
            runCurrent()
            owner.auth.importSession(old)
            runCurrent()
            engine.releaseRefresh.complete(Unit)
            manager.restoreSession()
            manager.state.first { it is AuthState.SignedIn }
            assertEquals(AuthState.SignedIn("account-a", null), manager.state.value)
            assertTrue(preferences.diskContains(projectKey))

            preferences.commitsSucceed = false
            manager.signOut()
            runCurrent()
            val failureState = manager.state.value
            val clientUnavailable = owner.captureClientOrNull() == null
            val verifiedClear = owner.isLocalSessionCleared
            // Simulated process restart uses only persisted disk bytes, never the modified cache.
            afterFailedCommit = client(
                persistence(preferences.restartFromDisk()), dispatcher, SuspendedRefreshEngine(dispatcher, old)
            )
            runCurrent()
            afterFailedCommit.auth.awaitInitialization()
            val restoredAccount = afterFailedCommit.auth.currentSessionOrNull()?.user?.id
            assertTrue("The model exercised a false disk commit", preferences.failedCommits > 0)
            assertFalse("The library/model cache really changed before reporting commit failure", preferences.memoryContains(projectKey))
            assertTrue(preferences.diskContains(projectKey))

            preferences.commitsSucceed = true
            manager.signOut()
            runCurrent()
            assertEquals(AuthState.SignedOut, manager.state.value)
            assertTrue(owner.isLocalSessionCleared)
            assertFalse(preferences.diskContains(projectKey))
            afterRetry = client(
                persistence(preferences.restartFromDisk()), dispatcher, SuspendedRefreshEngine(dispatcher, old)
            )
            runCurrent()
            assertFalse(afterRetry.auth.loadFromStorage())
            assertNull(afterRetry.auth.currentSessionOrNull())

            // All durable restart and retry evidence is collected before the red assertion.
            assertEquals(
                "A false commit must remain a cleanup error even when hasKey reads an empty cache",
                listOf(AuthState.ErrorRecoverable(SupabaseAuthManager.SESSION_CLEANUP_ERROR), true, false, "account-a"),
                listOf(failureState, clientUnavailable, verifiedClear, restoredAccount)
            )
        } finally {
            manager.shutdown()
            owner.close()
            afterFailedCommit?.close()
            afterRetry?.close()
        }
    }

    @Test
    fun `failed session save commit cannot publish authenticated state and fresh SDK sees empty disk`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = MemoryAndDiskPreferences()
        val old = session()
        val engine = SuspendedRefreshEngine(dispatcher, old)
        val owner = owned(preferences, dispatcher, engine)
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        var restarted: SupabaseClient? = null
        try {
            runCurrent()
            manager.restoreSession()
            manager.state.first { it == AuthState.SignedOut }
            preferences.commitsSucceed = false
            val save = runCatching { owner.auth.importSession(old) }
            runCurrent()
            val published = owner.sessionStatus.value is SessionStatus.Authenticated
            val managerState = manager.state.value
            restarted = client(
                persistence(preferences.restartFromDisk()), dispatcher, SuspendedRefreshEngine(dispatcher, old)
            )
            runCurrent()
            assertFalse(restarted.auth.loadFromStorage())
            val restoredAccount = restarted.auth.currentSessionOrNull()?.user?.id
            assertTrue(preferences.failedCommits > 0)
            assertTrue("The failed write updated memory, unlike the durable snapshot", preferences.memoryContains(projectKey))
            assertFalse(preferences.diskContains(projectKey))

            preferences.commitsSucceed = true
            owner.auth.importSession(old)
            runCurrent()
            engine.releaseRefresh.complete(Unit)
            manager.restoreSession()
            manager.state.first { it is AuthState.SignedIn }
            assertEquals(AuthState.SignedIn("account-a", null), manager.state.value)
            assertTrue(preferences.diskContains(projectKey))

            assertEquals(
                "SDK import must fail before authenticated publication when its session commit fails",
                listOf(true, false, AuthState.SignedOut, null),
                listOf(save.isFailure, published, managerState, restoredAccount)
            )
        } finally {
            manager.shutdown()
            owner.close()
            restarted?.close()
        }
    }

    @Test
    fun `canceled logout verifies canonical commit before a disk only SDK restart`() = runTest(timeout = 15.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = MemoryAndDiskPreferences()
        val old = session()
        val engine = SuspendedRefreshEngine(dispatcher, old, suspendLogout = true)
        val owner = owned(preferences, dispatcher, engine)
        val manager = SupabaseAuthManager(owner, "synthetic-google-client", scope = backgroundScope)
        var restarted: SupabaseClient? = null
        try {
            runCurrent()
            owner.auth.importSession(old)
            runCurrent()
            engine.releaseRefresh.complete(Unit)
            manager.restoreSession()
            manager.state.first { it is AuthState.SignedIn }
            assertTrue(preferences.diskContains(projectKey))
            val commitsBeforeLogout = preferences.successfulCommits

            val logout = launch { manager.signOut() }
            engine.logoutStarted.await()
            logout.cancel()
            logout.join()
            runCurrent()

            assertTrue(logout.isCancelled)
            assertEquals(1, engine.logoutCalls)
            assertEquals(AuthState.SignedOut, manager.state.value)
            assertTrue(owner.isLocalSessionCleared)
            assertTrue(preferences.successfulCommits > commitsBeforeLogout)
            assertFalse(preferences.diskContains(projectKey))
            restarted = client(
                persistence(preferences.restartFromDisk()), dispatcher, SuspendedRefreshEngine(dispatcher, old)
            )
            runCurrent()
            assertFalse(restarted.auth.loadFromStorage())
            assertNull(restarted.auth.currentSessionOrNull())
        } finally {
            manager.shutdown()
            owner.close()
            restarted?.close()
        }
    }

    // The app adapter must turn a false commit into failure before the SDK can publish success.
    private fun settingsFor(preferences: SharedPreferences): Settings =
        CheckedAuthSettings(preferences)

    private fun persistence(preferences: MemoryAndDiskPreferences): ProjectSessionPersistence =
        ProjectSessionPersistence(settingsFor(preferences.preferences), projectKey, projectUrl)

    private fun TestScope.owned(
        preferences: MemoryAndDiskPreferences,
        dispatcher: TestDispatcher,
        firstEngine: SuspendedRefreshEngine
    ): GenerationOwnedSupabaseClient {
        val storage = persistence(preferences)
        var first = true
        return GenerationOwnedSupabaseClient(
            sessionManager = storage,
            codeVerifierCache = MemoryCodeVerifierCache(),
            isSessionStored = { storage.isSessionStored() },
            factory = { lease, verifier ->
                val engine = if (first) firstEngine else SuspendedRefreshEngine(dispatcher, session())
                first = false
                client(lease, dispatcher, engine, verifier)
            },
            scope = backgroundScope
        )
    }

    private fun client(
        storage: SessionManager,
        dispatcher: TestDispatcher,
        engine: SuspendedRefreshEngine,
        verifier: CodeVerifierCache = MemoryCodeVerifierCache()
    ): SupabaseClient = createSupabaseClient(projectUrl, "synthetic-public-key") {
        httpEngine = engine
        coroutineDispatcher = dispatcher
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            sessionManager = storage
            codeVerifierCache = verifier
            autoLoadFromStorage = true
            autoSetupPlatform = false
        }
    }

    private fun session() = UserSession(
        accessToken = "synthetic-account-a-access-token",
        refreshToken = "synthetic-account-a-refresh-token",
        expiresIn = 86_400,
        tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = "account-a"),
        expiresAt = Clock.System.now() + 1.days
    )
}

/** Editor.commit applies memory first, then separately succeeds or fails to persist its snapshot. */
private class MemoryAndDiskPreferences(initialDisk: Map<String, String> = emptyMap()) {
    private val memory = initialDisk.toMutableMap()
    private val disk = initialDisk.toMutableMap()
    var commitsSucceed = true
    var successfulCommits = 0
        private set
    var failedCommits = 0
        private set
    val preferences = mockk<SharedPreferences>()

    init {
        every { preferences.contains(any()) } answers { memory.containsKey(firstArg<String>()) }
        every { preferences.getString(any(), any()) } answers {
            memory[firstArg<String>()] ?: secondArg<String?>()
        }
        every { preferences.all } answers { memory.toMap() }
        every { preferences.edit() } answers { editor() }
    }

    fun memoryContains(key: String): Boolean = memory.containsKey(key)
    fun diskContains(key: String): Boolean = disk.containsKey(key)
    fun restartFromDisk(): MemoryAndDiskPreferences = MemoryAndDiskPreferences(disk.toMap())

    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, String?>()
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            pending[firstArg<String>()] = secondArg<String?>()
            editor
        }
        every { editor.remove(any()) } answers {
            pending[firstArg<String>()] = null
            editor
        }
        every { editor.commit() } answers {
            pending.forEach { (key, value) ->
                if (value == null) memory.remove(key) else memory[key] = value
            }
            if (commitsSucceed) {
                disk.clear()
                disk.putAll(memory)
                successfulCommits++
                true
            } else {
                failedCommits++
                false
            }
        }
        every { editor.apply() } answers { error("This proof requires a synchronous commit outcome") }
        return editor
    }
}

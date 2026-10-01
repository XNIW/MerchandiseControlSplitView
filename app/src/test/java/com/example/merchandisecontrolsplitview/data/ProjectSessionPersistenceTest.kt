package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.createDefaultSettingsKey
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@OptIn(SupabaseInternal::class, ExperimentalCoroutinesApi::class, io.ktor.utils.io.InternalAPI::class)
class ProjectSessionPersistenceTest {
    private val projectUrl = "https://sdk-lifecycle.example.test"
    private val projectKey = "${createDefaultSettingsKey(projectUrl.split("//").last())}-${SettingsSessionManager.SETTINGS_KEY}"
    private val json = Json { encodeDefaults = true }

    @Test
    fun `coexisting owned legacy cannot restore after current project logout and SDK restart`() = runTest(timeout = 15.seconds) {
        val settings = settings()
        settings.putString(projectKey, json.encodeToString(session("current", projectUrl)))
        settings.putString(SettingsSessionManager.SETTINGS_KEY, json.encodeToString(session("legacy", projectUrl)))
        val persistence = persistence(settings)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = client(persistence, dispatcher)
        try {
            runCurrent()
            first.auth.awaitInitialization()
            assertEquals("current", first.auth.currentSessionOrNull()?.user?.id)
            persistence.deleteSession()
            first.close()
            val reconstructed = persistence(settings)
            val restarted = client(reconstructed, dispatcher)
            try {
                runCurrent()
                assertFalse(restarted.auth.loadFromStorage())
                assertEquals("No owned legacy record may revive in a fresh SDK constructor", listOf(null, false, false), listOf(restarted.auth.currentSessionOrNull()?.user?.id, settings.hasKey(projectKey), settings.hasKey(SettingsSessionManager.SETTINGS_KEY)))
            } finally { restarted.close() }
        } finally { first.close() }
    }

    @Test
    fun `foreign legacy survives current project logout and is never migrated on SDK restart`() = runTest(timeout = 15.seconds) {
        val settings = settings()
        val foreign = json.encodeToString(session("foreign", "https://another-project.example.test"))
        settings.putString(projectKey, json.encodeToString(session("current", projectUrl)))
        settings.putString(SettingsSessionManager.SETTINGS_KEY, foreign)
        val persistence = persistence(settings)
        persistence.deleteSession()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = client(persistence(settings), dispatcher)
        try {
            runCurrent()
            assertFalse(restarted.auth.loadFromStorage())
            assertEquals("Other project legacy must survive and stay outside this SDK session", listOf(null, foreign, false), listOf(restarted.auth.currentSessionOrNull()?.user?.id, settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY), settings.hasKey(projectKey)))
        } finally { restarted.close() }
    }

    @Test
    fun `same project legacy still migrates using the unmodified SDK key and serialized session`() = runTest(timeout = 15.seconds) {
        val settings = settings()
        val legacy = session("legacy", projectUrl)
        val raw = json.encodeToString(legacy)
        settings.putString(SettingsSessionManager.SETTINGS_KEY, raw)
        val storage = persistence(settings)
        assertEquals(raw, settings.getStringOrNull(projectKey))
        assertFalse(settings.hasKey(SettingsSessionManager.SETTINGS_KEY))
        assertEquals(legacy, storage.loadSession())
        assertEquals(true, storage.isSessionStored())
        val client = client(storage, StandardTestDispatcher(testScheduler))
        try {
            client.auth.awaitInitialization()
            assertEquals(legacy, client.auth.currentSessionOrNull())
        } finally { client.close() }
    }

    @Test
    fun `unknown malformed and issuer-prefix legacy records stay untouched across reconstruction`() = runTest(timeout = 15.seconds) {
        val unknown = session("unknown", projectUrl).copy(accessToken = "opaque-synthetic-token")
        val payload = jwt("$projectUrl/auth/v1").split('.')[1]
        val legacyRecords = listOf(
            "malformed synthetic legacy",
            json.encodeToString(unknown),
            json.encodeToString(unknown.copy(accessToken = ".$payload.")),
            json.encodeToString(session("prefix", "$projectUrl/other"))
        )
        for (raw in legacyRecords) {
            val settings = settings()
            settings.putString(SettingsSessionManager.SETTINGS_KEY, raw)
            val storage = persistence(settings)
            storage.deleteSession()
            val client = client(persistence(settings), StandardTestDispatcher(testScheduler))
            try {
                assertFalse(client.auth.loadFromStorage())
                assertNull(client.auth.currentSessionOrNull())
                assertEquals(raw, settings.getStringOrNull(SettingsSessionManager.SETTINGS_KEY))
                assertFalse(settings.hasKey(projectKey))
                assertFalse(storage.isSessionStored())
            } finally { client.close() }
        }
    }

    @Test
    fun `strong readback propagates storage errors instead of interpreting them as no session`() {
        val underlying = settings()
        val storage = persistence(underlying)
        val failing = object : Settings by underlying {
            override fun hasKey(key: String): Boolean = throw java.io.IOException("Synthetic settings read failure")
        }
        val guarded = persistence(failing)
        assertEquals(false, storage.isSessionStored())
        assertEquals(java.io.IOException::class.java, runCatching { guarded.isSessionStored() }.exceptionOrNull()?.javaClass)
    }

    private fun persistence(settings: Settings): ProjectSessionPersistence = ProjectSessionPersistence(settings = settings, sessionKey = projectKey, projectUrl = projectUrl)

    private fun settings(): Settings {
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("synthetic-project-session-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return SharedPreferencesSettings(preferences)
    }

    private fun session(account: String, issuerProject: String) = UserSession(
        accessToken = jwt("$issuerProject/auth/v1"),
        refreshToken = "synthetic-refresh-$account",
        expiresIn = 86_400,
        tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = account),
        expiresAt = Clock.System.now() + 1.days
    )

    private fun jwt(issuer: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString("{\"alg\":\"RS256\"}".toByteArray()) + "." +
            encoder.encodeToString("{\"iss\":\"$issuer\"}".toByteArray()) + ".c3ludGhldGlj"
    }

    private fun client(storage: SessionManager, dispatcher: CoroutineDispatcher): SupabaseClient = createSupabaseClient(projectUrl, "synthetic-public-key") {
        httpEngine = object : HttpClientEngineBase("legacy-no-network") {
            override val config = HttpClientEngineConfig()
            override val dispatcher = dispatcher
            override suspend fun execute(data: HttpRequestData): HttpResponseData = error("Network is forbidden in this storage constructor test")
        }
        coroutineDispatcher = dispatcher
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            sessionManager = storage
            codeVerifierCache = MemoryCodeVerifierCache()
            autoSetupPlatform = false
            // Constructor/migration proof uses no HTTP or moving real SDK refresh clock.
            // Owned auto-refresh and its cancellation are covered by the lifecycle SDK control.
            alwaysAutoRefresh = false
        }
    }
}

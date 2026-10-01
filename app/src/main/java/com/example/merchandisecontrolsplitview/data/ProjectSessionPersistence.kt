package com.example.merchandisecontrolsplitview.data

import android.util.Base64
import android.content.SharedPreferences
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Auth retains the SDK's preference file and format, but requires durable String mutations. */
internal class CheckedAuthSettings(private val preferences: SharedPreferences) :
    Settings by SharedPreferencesSettings(preferences) {
    override fun putString(key: String, value: String) {
        if (!preferences.edit().putString(key, value).commit()) {
            throw SupabaseSessionPersistenceException()
        }
    }

    override fun remove(key: String) {
        if (!preferences.edit().remove(key).commit()) {
            throw SupabaseSessionPersistenceException()
        }
    }
}

/** Preserves the pinned SDK's project key/format and limits its old global-key migration. */
internal class ProjectSessionPersistence(
    private val settings: Settings,
    private val sessionKey: String,
    projectUrl: String
) : SessionManager {
    private val legacySettings = ProjectLegacySettings(settings, projectUrl.trimEnd('/') + "/auth/v1")
    private val sdkManager = SettingsSessionManager(settings = legacySettings, key = sessionKey)

    override suspend fun saveSession(session: UserSession) = sdkManager.saveSession(session)
    override suspend fun loadSession(): UserSession = sdkManager.loadSession()
    override suspend fun deleteSession() {
        sdkManager.deleteSession()
        legacySettings.removeOwnedLegacySession()
    }

    // Direct reads intentionally propagate storage errors rather than using loadSessionOrNull.
    internal fun isSessionStored(): Boolean =
        settings.hasKey(sessionKey) || legacySettings.hasOwnedLegacySession()
}

private class ProjectLegacySettings(
    private val delegate: Settings,
    private val expectedIssuer: String
) : Settings by delegate {
    private val legacyKey = SettingsSessionManager.SETTINGS_KEY
    private val sessionJson = Json { encodeDefaults = true }

    override fun getStringOrNull(key: String): String? {
        val value = delegate.getStringOrNull(key)
        return value.takeIf { key != legacyKey || isOwnedLegacySession(it) }
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun hasKey(key: String): Boolean =
        if (key == legacyKey) hasOwnedLegacySession() else delegate.hasKey(key)

    override fun remove(key: String) {
        if (key == legacyKey) removeOwnedLegacySession() else delegate.remove(key)
    }

    fun hasOwnedLegacySession(): Boolean = isOwnedLegacySession(delegate.getStringOrNull(legacyKey))

    fun removeOwnedLegacySession() {
        if (hasOwnedLegacySession()) delegate.remove(legacyKey)
    }

    private fun isOwnedLegacySession(value: String?): Boolean {
        if (value == null) return false
        return try {
            val session = sessionJson.decodeFromString<UserSession>(value)
            val parts = session.accessToken.split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() }) return false
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val claims = sessionJson.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject
            val issuer = claims?.get("iss") as? JsonPrimitive
            // This selects a storage record only. The SDK/server still authenticates the JWT.
            issuer?.isString == true && issuer.content == expectedIssuer
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationBlockedException
import io.github.jan.supabase.auth.auth
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task072DeviceAuthorizationLiveGateTest {

    @Test
    fun liveDeviceStatusGateMatchesExpectedCloudStatus() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "TASK-072 live device gate is opt-in. Pass -e task072DeviceLiveGate true.",
            isEnabled(args.getString("task072DeviceLiveGate"))
        )
        val expectedStatus = args.getString("task072ExpectedStatus")
            ?: throw AssertionError("task072ExpectedStatus must be active or revoked")
        assertTrue(expectedStatus == "active" || expectedStatus == "revoked")
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("secret_key"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("sb_secret"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        val client = app.supabaseClient ?: throw AssertionError("Supabase client missing")

        val signedIn = signedInState(app) ?: run {
            importTemporarySessionIfPresent(app, args.getString("task072SessionFile"))
            signedInState(app)
        } ?: throw AssertionError("TASK-072 Android live gate requires signed-in Supabase session")

        val deviceInstallId = app.deviceInstallIdProvider.getOrCreate()
        val localBefore = localCounts(app, signedIn.userId)
        val heartbeatSnapshot = app.shopDeviceAuthorizationRepository
            .registerHeartbeatAndCheck("task072_live_gate")
            .getOrThrow()
        val snapshot = app.shopDeviceAuthorizationRepository
            .checkStatus("task072_live_gate_verify", force = true)
            .getOrThrow()

        assertEquals(expectedStatus, snapshot.status)
        assertEquals(expectedStatus == "active", snapshot.canWrite)

        val manualBlocked = app.shopDeviceAuthorizationRepository
            .ensureActiveForCloudWrite("manual_quick_sync")
            .exceptionOrNull() is ShopDeviceAuthorizationBlockedException
        val automaticBlocked = app.shopDeviceAuthorizationRepository
            .ensureActiveForCloudWrite("automatic_sync")
            .exceptionOrNull() is ShopDeviceAuthorizationBlockedException
        val writeAllowed = app.shopDeviceAuthorizationRepository
            .ensureActiveForCloudWrite("task072_live_write_resume")
            .isSuccess

        if (expectedStatus == "revoked") {
            assertTrue(manualBlocked)
            assertTrue(automaticBlocked)
            assertFalse(writeAllowed)
        } else {
            assertFalse(manualBlocked)
            assertFalse(automaticBlocked)
            assertTrue(writeAllowed)
        }

        val localAfter = localCounts(app, signedIn.userId)
        assertEquals(localBefore, localAfter)
        assertTrue(client.auth.currentUserOrNull()?.id?.isNotBlank() == true)

        val summary =
            "TASK072_ANDROID_DEVICE_LIVE_GATE owner_hash=${hash(signedIn.userId)} " +
                "device=${redactId(deviceInstallId)} expected=$expectedStatus " +
                "status=${snapshot.status} code=${snapshot.code} can_write=${snapshot.canWrite} " +
                "heartbeat_status=${heartbeatSnapshot.status} heartbeat_can_write=${heartbeatSnapshot.canWrite} " +
                "manual_blocked=$manualBlocked automatic_blocked=$automaticBlocked " +
                "write_allowed=$writeAllowed local_counts_unchanged=${localBefore == localAfter}"
        println(summary)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("TASK072_ANDROID_DEVICE_LIVE_GATE", summary)
            }
        )
    }

    private suspend fun signedInState(app: MerchandiseControlApplication): AuthState.SignedIn? {
        app.authManager.restoreSession()
        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it is AuthState.SignedIn }
        } ?: app.authManager.state.value

        return authState as? AuthState.SignedIn
            ?: app.supabaseClient?.auth?.currentUserOrNull()?.let { user ->
                AuthState.SignedIn(userId = user.id, email = user.email)
            }
    }

    private suspend fun importTemporarySessionIfPresent(
        app: MerchandiseControlApplication,
        sessionFilePath: String?
    ) {
        val path = sessionFilePath?.takeIf { it.isNotBlank() }
            ?: throw AssertionError("task072SessionFile is required when emulator is signed out")
        val file = File(path)

        if (!file.exists()) {
            throw AssertionError("task072SessionFile does not exist")
        }

        val json = JSONObject(file.readText())
        val access = json.getString("access")
        val refresh = json.getString("refresh")

        if (access.isBlank() || refresh.isBlank()) {
            throw AssertionError("task072SessionFile is malformed")
        }

        app.supabaseClient?.auth?.importAuthToken(
            accessToken = access,
            refreshToken = refresh,
            retrieveUser = true,
            autoRefresh = true
        ) ?: throw AssertionError("Supabase client missing")
        file.delete()
    }

    private suspend fun localCounts(
        app: MerchandiseControlApplication,
        ownerUserId: String
    ): LocalCounts =
        LocalCounts(
            products = app.database.productDao().count(),
            history = app.database.historyEntryDao().countUserVisible(),
            outbox = app.database.syncEventOutboxDao().countPending(ownerUserId)
        )

    private fun isEnabled(value: String?): Boolean =
        value == "1" || value.equals("true", ignoreCase = true)

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun redactId(value: String): String =
        if (value.length <= 12) value else "${value.take(8)}...${value.takeLast(4)}"

    private data class LocalCounts(
        val products: Int,
        val history: Int,
        val outbox: Int
    )
}

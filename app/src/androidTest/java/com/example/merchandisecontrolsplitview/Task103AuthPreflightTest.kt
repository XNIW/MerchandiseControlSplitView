package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task103AuthPreflightTest {
    @Test
    fun authSessionOwnerHashWhenEnabled() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val task103Enabled = args.getString("task103AuthPreflight")?.lowercase()
        val task104Enabled = args.getString("task104Pass2AuthPreflight")?.lowercase()
        assumeTrue(
            "Live auth preflight is gated. Pass -e task103AuthPreflight true or -e task104Pass2AuthPreflight true.",
            isEnabled(task103Enabled) || isEnabled(task104Enabled)
        )

        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("secret_key"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("sb_secret"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication

        app.authManager.restoreSession()
        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value

        val signedIn = authState as? AuthState.SignedIn
            ?: throw AssertionError("TASK-103 Android auth preflight requires signed-in session: ${authState::class.java.simpleName}")

        assertTrue(app.catalogRemoteDataSource.isConfigured)
        assertTrue(app.productPriceRemoteDataSource.isConfigured)

        val label = if (isEnabled(task104Enabled)) {
            "TASK104_PASS2_ANDROID_AUTH_PREFLIGHT"
        } else {
            "TASK103_ANDROID_AUTH_PREFLIGHT"
        }
        println(
            "$label " +
                "project_hash=${task103Hash(BuildConfig.SUPABASE_URL)} " +
                "owner_hash=${task103Hash(signedIn.userId)} " +
                "signed_in=true"
        )
    }

    private fun task103Hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun isEnabled(value: String?): Boolean =
        value == "1" || value == "true"
}

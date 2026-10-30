package com.example.merchandisecontrolsplitview.ui.navigation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.LocalStoreAvailability
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.SignOutScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual AppNavGraph. This UI fixture never opens Room or starts the production Application. */
@RunWith(AndroidJUnit4::class)
class SafeLocalStoreRootDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkingAndUnavailableRootKeepOptionsAccountAndSafeDetailsWithoutBusinessGetters() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationContext.javaClass == Application::class.java) { "Dedicated neutral runner required" }
        val businessAccesses = AtomicInteger()
        val ownedApp = object : MerchandiseControlApplication() {
            fun attach(context: Context) = attachBaseContext(context)
        }
        val ownedContext = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = ownedApp
        }
        ownedApp.attach(ownedContext)
        val authJob = SupervisorJob()
        val authScope = CoroutineScope(Dispatchers.Default + authJob)
        val auth = SupabaseAuthManager.createForTest(object : SupabaseAuthSessionController {
            override val sessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated())
            override fun currentUserOrNull(): SupabaseSessionUser? = null
            override suspend fun refreshStoredSession() = StoredSessionRefreshResult.Deferred
            override suspend fun clearSession() = Unit
            override suspend fun signInWithGoogleIdToken(idToken: String) = Unit
            override suspend fun importWeChatSession(accessToken: String, refreshToken: String) = Unit
            override suspend fun signOut(scope: SignOutScope) = Unit
        }, authScope)
        val cls = MerchandiseControlApplication::class.java
        fun field(name: String) = cls.getDeclaredField(name).apply { isAccessible = true }
        field("authManager\$delegate").set(ownedApp, lazyOf(auth))
        val businessNames = listOf("database", "repository", "catalogSyncStateTracker", "shopContextRepository",
            "realtimeRefreshCoordinator", "historySessionPushCoordinator", "catalogAutoSyncCoordinator")
        businessNames.forEach { name ->
            field("$name\$delegate").set(ownedApp, lazy<Any> {
                businessAccesses.incrementAndGet()
                error("Safe root constructed business dependency: $name")
            })
        }
        @Suppress("UNCHECKED_CAST")
        val availability = field("_localStoreAvailability").get(ownedApp) as MutableStateFlow<LocalStoreAvailability>
        val appScope = field("appScope").get(ownedApp) as CoroutineScope
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides ownedContext) {
                    MerchandiseControlTheme(darkTheme = false) { AppNavGraph() }
                }
            }
            compose.onNodeWithText(target.getString(R.string.local_store_open_checking)).assertIsDisplayed()
            compose.onNodeWithTag("local-store-open-retry").assertDoesNotExist()
            assertEquals(0, businessAccesses.get())
            compose.runOnIdle { availability.value = LocalStoreAvailability.Unavailable("controlled") }
            compose.onNodeWithText(target.getString(R.string.local_store_open_unavailable)).assertIsDisplayed()
            compose.onNodeWithTag("local-store-open-retry").assertDoesNotExist()
            compose.onNodeWithTag("local-store-open-details").performClick()
            compose.onNodeWithTag("local-store-open-details-value").assertTextEquals(target.getString(
                R.string.local_store_open_details, target.getString(R.string.local_store_open_unknown_reason),
                com.example.merchandisecontrolsplitview.BuildConfig.VERSION_NAME))
            compose.onNodeWithText(target.getString(R.string.close)).performClick()
            compose.runOnIdle { availability.value = LocalStoreAvailability.Unavailable("local_store_open_failed") }
            compose.onNodeWithTag("local-store-open-details").performClick()
            compose.onNodeWithTag("local-store-open-details-value").assertTextEquals(target.getString(
                R.string.local_store_open_details, target.getString(R.string.local_store_open_failed_reason),
                com.example.merchandisecontrolsplitview.BuildConfig.VERSION_NAME))
            compose.onNodeWithText(target.getString(R.string.close)).performClick()
            compose.onNodeWithText(target.getString(R.string.select_theme)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(target.getString(R.string.select_language)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(target.getString(R.string.account_section_title)).performScrollTo().assertIsDisplayed()
            assertEquals(0, businessAccesses.get())
            businessNames.forEach { name -> assertFalse((field("$name\$delegate").get(ownedApp) as Lazy<*>).isInitialized()) }
        } finally {
            auth.shutdown()
            runBlocking { authJob.cancelAndJoin(); appScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }
}

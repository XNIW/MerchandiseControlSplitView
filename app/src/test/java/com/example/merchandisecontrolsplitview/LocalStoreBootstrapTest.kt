package com.example.merchandisecontrolsplitview

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteCantOpenDatabaseException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.merchandisecontrolsplitview.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An owned Application instance; the installed app database and auth are never opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalStoreBootstrapTest {
    @Test
    fun applicationBootstrapSurvivesUnavailableStoreWithoutConstructingBusinessConsumers() {
        val application = UnavailableStoreApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(application, RuntimeEnvironment.getApplication())
        val applicationClass = MerchandiseControlApplication::class.java
        applicationClass.getDeclaredField("database\$delegate").apply { isAccessible = true }
            .set(application, lazy<AppDatabase> { throw SQLiteCantOpenDatabaseException("owned test store unavailable") })
        var failure: Throwable? = null
        try {
            try { application.onCreate() } catch (error: Throwable) { failure = error }
            assertNull("R4-T14: unavailable storage must reach a value-only shell instead of crashing Application startup", failure)
            for (name in listOf("repository", "realtimeRefreshCoordinator", "historySessionPushCoordinator", "catalogAutoSyncCoordinator")) {
                val component = applicationClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }
                    .get(application) as Lazy<*>
                assertFalse("Unavailable storage must not initialize $name", component.isInitialized())
            }
        } finally {
            // Teardown must not instantiate any of the failed database/coordinator lazy values.
            val scope = applicationClass.getDeclaredField("appScope").apply { isAccessible = true }
                .get(application) as CoroutineScope
            scope.cancel()
            applicationClass.getDeclaredMethod("unregisterNetworkAutoSyncTrigger").apply { isAccessible = true }.invoke(application)
            val auth = applicationClass.getDeclaredField("authManager\$delegate").apply { isAccessible = true }.get(application) as Lazy<*>
            if (auth.isInitialized()) (auth.value as com.example.merchandisecontrolsplitview.data.SupabaseAuthManager).shutdown()
            val observer = applicationClass.getDeclaredField("processLifecycleObserver").apply { isAccessible = true }
                .get(application) as DefaultLifecycleObserver
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
    }
}

private class UnavailableStoreApplication : MerchandiseControlApplication() {
    internal override val supabaseConfiguration = ApplicationSupabaseConfiguration("", "")
}

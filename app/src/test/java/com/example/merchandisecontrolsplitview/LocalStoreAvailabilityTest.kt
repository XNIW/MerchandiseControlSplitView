package com.example.merchandisecontrolsplitview

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteCantOpenDatabaseException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.merchandisecontrolsplitview.data.AuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalStoreAvailabilityTest {
    @Test
    fun failedOpenPublishesOnlySafeCodeAndKeepsLifecycleAndSignOutAwayFromBusinessGetters() {
        val app = attachedApplication()
        try {
            app.onCreate()
            awaitState { app.localStoreAvailability.value is LocalStoreAvailability.Unavailable }
            assertEquals(LocalStoreAvailability.Unavailable("local_store_open_failed"), app.localStoreAvailability.value)
            assertEquals(1, app.opens.get())
            val observer = field(app, "processLifecycleObserver") as DefaultLifecycleObserver
            observer.onStart(ProcessLifecycleOwner.get())
            observer.onStop(ProcessLifecycleOwner.get())
            runBlocking { app.authManager.signOut() }
            assertTrue(app.authManager.state.value is AuthState.SignedOut)
            assertBusinessUninitialized(app)
        } finally { app.onTerminate() }
        assertBusinessUninitialized(app)
    }

    @Test
    fun explicitConcurrentRetrySharesOneAttemptAndDoesNotStartAutomaticRetryLoop() {
        val app = attachedApplication()
        try {
            app.onCreate()
            awaitState { app.localStoreAvailability.value is LocalStoreAvailability.Unavailable }
            val gate = CompletableDeferred<Unit>()
            app.openGate = gate
            app.retryLocalStoreOpen()
            repeat(20) { app.retryLocalStoreOpen() }
            awaitState { app.opens.get() == 2 }
            assertEquals(LocalStoreAvailability.Checking, app.localStoreAvailability.value)
            assertBusinessUninitialized(app)
            gate.complete(Unit)
            awaitState { app.localStoreAvailability.value is LocalStoreAvailability.Unavailable }
            repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(2) }
            assertEquals(2, app.opens.get())
            assertBusinessUninitialized(app)
        } finally { app.onTerminate() }
    }

    @Test
    fun terminationWhileOpeningDoesNotConstructConsumersOrRetry() {
        val app = attachedApplication()
        app.openGate = CompletableDeferred()
        app.onCreate()
        awaitState { app.opens.get() == 1 }
        app.onTerminate()
        app.retryLocalStoreOpen()
        app.openGate!!.complete(Unit)
        ShadowLooper.idleMainLooper()
        assertEquals(1, app.opens.get())
        assertBusinessUninitialized(app)
    }

    private fun attachedApplication() = OwnedUnavailableApplication().also {
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(it, RuntimeEnvironment.getApplication())
    }

    private fun awaitState(predicate: () -> Boolean) {
        repeat(2_000) {
            ShadowLooper.idleMainLooper()
            if (predicate()) return
            Thread.sleep(1)
        }
        fail("Owned Application state did not settle")
    }

    private fun field(app: MerchandiseControlApplication, name: String): Any? =
        MerchandiseControlApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.get(app)

    private fun assertBusinessUninitialized(app: MerchandiseControlApplication) {
        for (name in listOf("database", "repository", "realtimeRefreshCoordinator", "historySessionPushCoordinator", "catalogAutoSyncCoordinator", "shopContextRepository")) {
            val lazy = field(app, name + "\$delegate") as Lazy<*>
            assertFalse("Safe shell cannot initialize $name", lazy.isInitialized())
        }
    }
}

private class OwnedUnavailableApplication : MerchandiseControlApplication() {
    internal override val supabaseConfiguration = ApplicationSupabaseConfiguration("", "")
    val opens = AtomicInteger()
    @Volatile var openGate: CompletableDeferred<Unit>? = null
    internal override suspend fun qualifyLocalStoreForBootstrap() {
        opens.incrementAndGet()
        openGate?.await()
        throw SQLiteCantOpenDatabaseException("owned detail must never be exposed in the value-only state")
    }
}

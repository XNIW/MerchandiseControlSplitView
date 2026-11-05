package com.example.merchandisecontrolsplitview

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.SupabaseAuthManager
import com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.InternalAPI
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Real onCreate and pinned Android SDK callbacks; controlled IO/store, no cloud/UI acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, SupabaseInternal::class, InternalAPI::class)
class ApplicationSdkLifecycleOwnershipTest {
    private var startupProvider: ContentProviderController<InitializationProvider>? = null

    @After
    fun releaseProviderIfSetupFailedBeforeTheOwnedScopeTry() {
        startupProvider?.shutdown()
        startupProvider = null
    }

    @Test
    fun ownedApplicationBootstrapUsesRealSdkMainLifecycleAndDrainsAllOwnedScopes() = runTest(timeout = 15.seconds) {
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        val context = RuntimeEnvironment.getApplication()
        assertEquals("The ambient context must not start a second production owner", Application::class.java, context.javaClass)
        // Deterministic fixture-boundary regression: the old two annotations fail these checks.
        val localIdentityConfig = requireNotNull(ApplicationLocalIdentityTest::class.java.getAnnotation(Config::class.java))
        val importAnalyzerConfig = requireNotNull(ImportAnalyzerTest::class.java.getAnnotation(Config::class.java))
        assertEquals(Application::class.java, localIdentityConfig.application.java)
        assertEquals(Application::class.java, importAnalyzerConfig.application.java)

        // A neutral Robolectric Application does not install manifest ContentProviders automatically.
        // Use the real eager provider/metadata path, never a lazy ProcessLifecycleInitializer or registry reset.
        val startup = AppInitializer.getInstance(context)
        if (!startup.isEagerlyInitialized(ProcessLifecycleInitializer::class.java)) {
            val info = context.packageManager.getProviderInfo(
                ComponentName(context, InitializationProvider::class.java), PackageManager.GET_META_DATA)
            assertEquals("androidx.startup", info.metaData.getString(ProcessLifecycleInitializer::class.java.name))
            assertEquals("androidx.startup", info.metaData.getString(WorkManagerInitializer::class.java.name))
            // Other real application tests may already own WorkManager without Startup's result cache.
            // Adopt only that public singleton; the real provider still discovers all unchanged metadata.
            val existingWorkManager = if (WorkManager.isInitialized()) WorkManager.getInstance(context) else null
            if (existingWorkManager != null) {
                val startupLock = AppInitializer::class.java.getDeclaredField("sLock")
                    .apply { isAccessible = true }.get(null)
                synchronized(requireNotNull(startupLock)) {
                    @Suppress("UNCHECKED_CAST")
                    val initialized = AppInitializer::class.java.getDeclaredField("mInitialized")
                        .apply { isAccessible = true }.get(startup) as MutableMap<Class<*>, Any>
                    val before = initialized.toMap()
                    val key = WorkManagerInitializer::class.java
                    if (initialized.containsKey(key)) assertSame(existingWorkManager, initialized[key])
                    else initialized[key] = existingWorkManager
                    assertSame(existingWorkManager, initialized[key])
                    assertEquals(before.keys + key, initialized.keys)
                    before.forEach { (type, value) -> assertSame(value, initialized[type]) }
                }
            }
            val provider = Robolectric.buildContentProvider(InitializationProvider::class.java)
            startupProvider = provider
            provider.create(info)
            if (existingWorkManager != null) {
                assertSame(existingWorkManager, WorkManager.getInstance(context))
                assertSame(existingWorkManager, startup.initializeComponent(WorkManagerInitializer::class.java))
            }
        }
        assertTrue(startup.isEagerlyInitialized(ProcessLifecycleInitializer::class.java))
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        assertEquals("Unexpected process lifecycle is evidence, never forcibly reset", Lifecycle.State.CREATED, lifecycle.currentState)
        val baseline = observers(lifecycle)
        assertTrue("A neutral sandbox must not inherit an unowned SDK platform observer", baseline.none { it.javaClass.name == SDK_OBSERVER })
        // Only this exact captured AndroidX Startup owner may retire itself on ON_RESUME.
        // Pin the effective resource bytes/version before allowing that one identity to disappear.
        val emojiStartupObservers = baseline.filter { it.javaClass.name == EMOJI_STARTUP_OBSERVER }
        assertTrue("Duplicate EmojiCompat Startup owners are not admitted", emojiStartupObservers.size <= 1)
        val selfRemovingStartupObserver = emojiStartupObservers.singleOrNull()
        selfRemovingStartupObserver?.let { observer ->
            assertSame(lifecycle, captured(observer, "val\$lifecycle"))
            val owner = requireNotNull(captured(observer, "this\$0"))
            assertEquals("androidx.emoji2.text.EmojiCompatInitializer", owner.javaClass.name)
            assertEquals("1.4.0", resourceBytes(owner.javaClass, "/META-INF/androidx.emoji2_emoji2.version").toString(Charsets.UTF_8).trim())
            assertEquals("02d1283c2bac2164f8b64d6e93bb9705d45551e544fc5ac690e6555281fde137", classResourceSHA256(owner.javaClass))
            assertEquals("4973debe2c48286d0b70c6e4bae93b30c4feed9e0673fcd758ae90215b69daf9", classResourceSHA256(observer.javaClass))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sdkJob = SupervisorJob()
        val managerJob = SupervisorJob()
        val sdkScope = CoroutineScope(dispatcher + sdkJob)
        val managerScope = CoroutineScope(dispatcher + managerJob)
        val storage = OwnedStorage()
        val engine = NoHttpEngine(dispatcher)
        var application: OwnedBootstrapApplication? = null
        var applicationJob: Job? = null
        val activities = mutableListOf<OwnedActivity>()
        var client: SupabaseClient? = null
        var manager: SupabaseAuthManager? = null
        var applicationStarted = false
        var ownedObserver: LifecycleObserver? = null
        var primary: Throwable? = null
        var mainInstalled = false
        try {
            // Install Main before constructing the Application that captures its scope dispatcher.
            Dispatchers.setMain(dispatcher)
            mainInstalled = true
            val app = OwnedBootstrapApplication()
            application = app
            val appScope = field("appScope").get(app) as CoroutineScope
            val appJob = requireNotNull(appScope.coroutineContext[Job])
            applicationJob = appJob
            client = createSupabaseClient("https://sdk-lifecycle.example.test", "synthetic-public-key") {
                httpEngine = engine
                coroutineDispatcher = dispatcher
                defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    sessionManager = storage
                    codeVerifierCache = MemoryCodeVerifierCache()
                    authScope = sdkScope
                    autoLoadFromStorage = true
                    autoSetupPlatform = true
                    enableLifecycleCallbacks = true
                    alwaysAutoRefresh = true
                }
            }
            val sdk = requireNotNull(client)
            val appManager = SupabaseAuthManager(sdk, "synthetic-google-client", scope = managerScope)
            manager = appManager
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(app, context)
            field("supabaseClient\$delegate").set(app, lazyOf(sdk))
            field("authManager\$delegate").set(app, lazyOf(appManager))
            applicationStarted = true
            app.onCreate()
            // The real bootstrap crosses Dispatchers.IO; testScheduler.runCurrent alone cannot join it.
            // Await its owned entry signal inside this test's original 15s deadline, without faking IO.
            app.openStarted.await()
            runCurrent()
            assertTrue("Real onCreate dispatched its owned store-open coroutine", app.openStarted.isCompleted)
            assertTrue(appJob.children.any { it.isActive })
            assertEquals(AuthState.Checking, appManager.state.value)
            assertTrue(sdk.auth.config.autoSetupPlatform && sdk.auth.config.enableLifecycleCallbacks)
            val added = observers(lifecycle).filter { candidate -> baseline.none { it === candidate } }
            val mine = added.filter { it.javaClass.name == SDK_OBSERVER && captured(it, "\$auth") === sdk.auth }
            assertEquals(1, mine.size)
            val newObserver = mine.single()
            ownedObserver = newObserver
            assertSame(sdkScope, captured(newObserver, "\$scope"))
            assertEquals("No foreign observer may be hidden by this fixture", 1, added.size)

            val readsBeforeStart = storage.reads
            val first = OwnedActivity(Robolectric.buildActivity(Activity::class.java)).also(activities::add)
            first.start()
            runCurrent()
            assertTrue(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            assertTrue("Real SDK onStart loaded its session store", storage.reads > readsBeforeStart)
            assertEquals(AuthState.SignedOut, appManager.state.value)
            assertEquals(0, engine.calls)
            // The application's restore path is proved above. Isolate the real SDK lifecycle below.
            appManager.shutdown(); managerJob.cancelAndJoin()
            sdk.auth.importSession(UserSession(accessToken = "synthetic-platform-access",
                refreshToken = "synthetic-platform-refresh", expiresIn = 86_400, tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = "10000000-0000-4000-8000-000000000146"),
                expiresAt = Clock.System.now() + 1.days), autoRefresh = true)
            runCurrent()
            assertTrue(sdk.auth.isAutoRefreshRunning)
            first.stop()
            // ProcessLifecycleOwner2.10 schedules ON_STOP after exactly700ms; virtual looper advance, no sleep.
            ShadowLooper.idleMainLooper(701, TimeUnit.MILLISECONDS)
            runCurrent()
            assertEquals(Lifecycle.State.CREATED, lifecycle.currentState)
            assertFalse("Real SDK onStop cancels auto refresh", sdk.auth.isAutoRefreshRunning)
            assertTrue(sdk.auth.sessionStatus.value is SessionStatus.Initializing)

            app.onTerminate(); applicationStarted = false
            appJob.cancelAndJoin(); managerJob.cancelAndJoin()
            assertTrue("The real bootstrap's held store-open body was cancelled and joined", app.openCancelled.isCompleted)
            sdk.close(); sdkJob.cancelAndJoin()
            assertTrue(sdkJob.isCompleted && appJob.isCompleted && managerJob.isCompleted)
            assertTrue(sdkJob.children.none() && appJob.children.none() && managerJob.children.none())
            val readsAfterClose = storage.reads
            val second = OwnedActivity(Robolectric.buildActivity(Activity::class.java)).also(activities::add)
            second.start(); runCurrent()
            assertEquals("A closed SDK scope cannot leak new work into the next lifecycle", readsAfterClose, storage.reads)
            assertFalse(sdk.auth.isAutoRefreshRunning)
            second.stop(); ShadowLooper.idleMainLooper(701, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(0, engine.calls)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
          withContext(NonCancellable) {
            val cleanup = mutableListOf<Throwable>()
            suspend fun step(block: suspend () -> Unit) {
                try { withTimeout(1_000) { block() } } catch (failure: Throwable) { cleanup.add(failure) }
            }
            activities.forEach { activity -> step { activity.stop() } }
            step { ShadowLooper.idleMainLooper(701, TimeUnit.MILLISECONDS); runCurrent() }
            step { if (applicationStarted) requireNotNull(application).onTerminate() }
            step { applicationJob?.cancelAndJoin() }
            step { manager?.shutdown() }
            step { managerJob.cancelAndJoin() }
            step { client?.close() }
            step { sdkJob.cancelAndJoin() }
            step {
                // SDK3.6 close cancels its scope but does not remove this observer. Remove only our exact capture.
                val auth = client?.auth
                val candidates = observers(lifecycle).filter { it.javaClass.name == SDK_OBSERVER && auth != null && captured(it, "\$auth") === auth }
                assertTrue(candidates.size <= 1)
                candidates.forEach { lifecycle.removeObserver(it) }
                ownedObserver?.let { assertTrue(observers(lifecycle).none { remaining -> remaining === it }) }
                val remaining = observers(lifecycle)
                val stableBaseline = baseline.filterNot { it === selfRemovingStartupObserver }
                assertTrue("No foreign observer may replace an original owner", remaining.all { candidate -> baseline.any { it === candidate } })
                assertTrue("All other baseline owners remain unchanged", stableBaseline.all { original -> remaining.any { it === original } })
                assertTrue("No owned or foreign SDK observer may survive cleanup", remaining.none { it.javaClass.name == SDK_OBSERVER })
                assertEquals(Lifecycle.State.CREATED, lifecycle.currentState)
                assertEquals(0, engine.calls)
            }
            step { startupProvider?.shutdown(); startupProvider = null }
            // Last: no owned SDK/application job can resume under another test's replacement Main.
            step {
                val constructedJobs = listOfNotNull(sdkJob, managerJob, applicationJob)
                assertTrue("Never reset Main before actual owned Job completion", constructedJobs.all { it.isCompleted })
                assertTrue(constructedJobs.all { it.children.none() })
                if (mainInstalled) Dispatchers.resetMain()
            }
            if (cleanup.isNotEmpty()) {
                val aggregate = AssertionError("Owned lifecycle cleanup failed (${cleanup.size})")
                cleanup.forEach(aggregate::addSuppressed)
                val original = primary
                if (original != null) original.addSuppressed(aggregate) else throw aggregate
            }
          }
        }
    }

    private class OwnedBootstrapApplication : MerchandiseControlApplication() {
        internal override val supabaseConfiguration = ApplicationSupabaseConfiguration("", "")
        val openStarted = CompletableDeferred<Unit>()
        val openCancelled = CompletableDeferred<Unit>()
        internal override suspend fun qualifyLocalStoreForBootstrap() {
            openStarted.complete(Unit)
            try { awaitCancellation() } finally { openCancelled.complete(Unit) }
        }
    }
    private class OwnedStorage : SessionManager {
        var reads = 0
        var value: UserSession? = null
        override suspend fun saveSession(session: UserSession) { value = session }
        override suspend fun loadSession(): UserSession { reads++; return value ?: error("Owned empty session storage") }
        override suspend fun deleteSession() { value = null }
    }
    private class NoHttpEngine(override val dispatcher: CoroutineDispatcher) : HttpClientEngineBase("owned-platform-no-http") {
        override val config = HttpClientEngineConfig()
        var calls = 0
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            calls++
            throw AssertionError("HTTP is forbidden in the platform lifecycle ownership test")
        }
    }
    private class OwnedActivity(private val controller: ActivityController<Activity>) {
        private var stopped = false
        fun start() { controller.create().start().resume() }
        fun stop() { if (!stopped) { controller.pause().stop().destroy(); stopped = true } }
    }
    private fun field(name: String) = MerchandiseControlApplication::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun observers(registry: LifecycleRegistry): List<LifecycleObserver> {
        val map = LifecycleRegistry::class.java.getDeclaredField("observerMap").apply { isAccessible = true }.get(registry)
        return (map as Iterable<Map.Entry<LifecycleObserver, *>>).map { it.key }
    }
    private fun resourceBytes(type: Class<*>, path: String): ByteArray =
        requireNotNull(type.getResourceAsStream(path)) { "Missing effective classpath resource: $path" }.use { it.readBytes() }
    private fun classResourceSHA256(type: Class<*>): String =
        MessageDigest.getInstance("SHA-256").digest(resourceBytes(type, "/" + type.name.replace('.', '/') + ".class"))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun captured(observer: LifecycleObserver, name: String): Any? =
        observer.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(observer)
    companion object {
        private const val EMOJI_STARTUP_OBSERVER = "androidx.emoji2.text.EmojiCompatInitializer\$1"
        private const val SDK_OBSERVER = "io.github.jan.supabase.auth.SetupPlatformKt\$addLifecycleCallbacks\$1\$1"
    }
}

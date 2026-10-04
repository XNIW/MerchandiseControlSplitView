package com.example.merchandisecontrolsplitview

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.robolectric.Robolectric
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import androidx.room.Room
import com.example.merchandisecontrolsplitview.data.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.annotations.SupabaseInternal
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.robolectric.shadows.ShadowLog
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class MerchandiseControlApplicationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `checkpoint trace cold and repeated requests never reserve or dispatch`() {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        ShadowLog.clear()
        app.requestOneCheckpointTrace(false)
        app.requestOneCheckpointTrace(true)
        val messages = traceMessages()
        assertEquals(2, messages.size)
        assertTrue(messages.first().contains("outcome=BLOCKED_COLD"))
        assertTrue(messages.last().contains("outcome=BLOCKED_USED"))
        assertTrue(messages.all { it.contains("localRpcAttemptCount=0") && it.contains("SQLSTATE=null") })
        assertFalse(privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex").isLocked)
        assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
    }

    @Test
    fun `checkpoint trace never unlocks another recovery owner`() {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val mutex = privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex")
        val other = Any()
        assertTrue(mutex.tryLock(other))
        try {
            ShadowLog.clear()
            app.requestOneCheckpointTrace(true)
            assertTrue(mutex.holdsLock(other))
            assertTrue(traceMessages().single().contains("outcome=BLOCKED_BUSY"))
            assertTrue(traceMessages().single().contains("localRpcAttemptCount=0"))
        } finally { mutex.unlock(other) }
    }

    @Test
    fun `checkpoint trace refuses existing lazy recovery job`() = runTest {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val lazyJob = backgroundScope.launch(start = CoroutineStart.LAZY) { error("must_not_start") }
        setPrivateApplicationField(app, "businessRecoveryJob", lazyJob)
        try {
            ShadowLog.clear()
            app.requestOneCheckpointTrace(true)
            assertTrue(traceMessages().single().contains("outcome=BLOCKED_BUSY"))
            assertFalse(lazyJob.isActive)
            assertSame(lazyJob, privateApplicationField<Job?>(app, "businessRecoveryJob"))
        } finally { lazyJob.cancel(); setPrivateApplicationField(app, "businessRecoveryJob", null) }
    }

    @Test
    fun `checkpoint trace cancellation before lazy body releases only owned reservation`() = runTest {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val original = privateApplicationField<CoroutineScope>(app, "appScope")
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        setPrivateApplicationField(app, "appScope", scope)
        try {
            ShadowLog.clear()
            app.requestOneCheckpointTrace(true)
            assertNotNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
            assertTrue(privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex").isLocked)
            scope.cancel()
            testScheduler.runCurrent()
            assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
            assertFalse(privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex").isLocked)
            assertTrue(traceMessages().isEmpty())
        } finally { scope.cancel(); setPrivateApplicationField(app, "appScope", original) }
    }

    @Test
    fun `checkpoint trace reservation neither queues nor launches recovery on release`() = runTest {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val original = privateApplicationField<CoroutineScope>(app, "appScope")
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        setPrivateApplicationField(app, "appScope", scope)
        app.catalogSyncStateTracker.updateBusinessDataScopeState(Task126BusinessDataScopeState(
            status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "sync_recovery_required"))
        try {
            app.requestOneCheckpointTrace(true)
            app.requestPendingBusinessRecovery("synthetic_during_trace")
            assertNull(privateApplicationField<Job?>(app, "businessRecoveryJob"))
            testScheduler.runCurrent() // Synthetic build fails the fixed target pin.
            assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
            assertNull(privateApplicationField<Job?>(app, "businessRecoveryJob"))
            app.requestPendingBusinessRecovery("synthetic_later_trigger")
            assertNotNull(privateApplicationField<Job?>(app, "businessRecoveryJob"))
        } finally {
            privateApplicationField<Job?>(app, "businessRecoveryJob")?.cancel()
            scope.cancel(); testScheduler.runCurrent()
            setPrivateApplicationField(app, "appScope", original)
            app.catalogSyncStateTracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.unmanagedAllowed())
        }
    }

    @Test
    fun `checkpoint trace wrong target leaves durable snapshot unchanged and exports scalars only`() = runTest {
        withTraceDatabase { app, _ ->
            val before = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            ShadowLog.clear()
            app.requestOneCheckpointTrace(true)
            testScheduler.runCurrent()
            assertEquals(before, app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
            val message = traceMessages().single()
            assertTrue(message.contains("outcome=BLOCKED_TEST_TARGET"))
            assertTrue(message.contains("localRpcAttemptCount=0"))
            assertTrue(message.contains("HTTP=null SQLSTATE=null"))
            assertTrue(message.contains("before_ok=null after_ok=null"))
            assertFalse(message.contains(TRACE_OWNER) || message.contains(TRACE_SHOP) || message.contains("http://"))
            assertFalse(privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex").isLocked)
        }
    }

    @Test
    fun `checkpoint observable auth window rejects elapsed eighty percent threshold`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val good = SessionStatus.Authenticated(traceSession(now + 1.days))
        assertTrue(checkpointTraceAuthWindowSafe(good, now + 100.seconds, now + 10.seconds))
        val elapsedThreshold = SessionStatus.Authenticated(traceSession(now + 60.seconds))
        assertTrue(elapsedThreshold.session.expiresAt > now + 10.seconds)
        assertFalse(checkpointTraceAuthWindowSafe(elapsedThreshold, now + 100.seconds, now + 10.seconds))
        assertFalse(checkpointTraceAuthWindowSafe(good, null, now + 10.seconds))
        assertFalse(checkpointTraceAuthWindowSafe(good, now + 9.seconds, now + 10.seconds))
        assertFalse(checkpointTraceAuthWindowSafe(good, now + 100.seconds, good.session.expiresAt))
    }

    @Test
    @OptIn(SupabaseInternal::class)
    fun `checkpoint auth budget uses real sdk status and scheduled refresh without forcing refresh`() = runTest {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val sdk = traceSdk(StandardTestDispatcher(testScheduler))
        try {
            sdk.auth.importSession(traceSession(), autoRefresh = false)
            val unscheduled = sdk.auth.sessionStatus.value as SessionStatus.Authenticated
            assertFalse(app.checkpointTraceAuthBudgetCurrent(sdk, unscheduled, Clock.System.now() + 10.seconds))
            sdk.auth.importSession(traceSession(), autoRefresh = true)
            testScheduler.runCurrent()
            val scheduled = sdk.auth.sessionStatus.value as SessionStatus.Authenticated
            assertTrue(app.checkpointTraceAuthBudgetCurrent(sdk, scheduled, Clock.System.now() + 10.seconds))
            assertFalse(app.checkpointTraceAuthBudgetCurrent(sdk, scheduled.copy(), Clock.System.now() + 10.seconds))
            sdk.auth.importSession(traceSession().copy(user = UserInfo(aud = "authenticated", id = TRACE_OTHER_OWNER)), autoRefresh = false)
            assertFalse(app.checkpointTraceAuthBudgetCurrent(sdk, scheduled, Clock.System.now() + 10.seconds))
        } finally { sdk.close() }
    }

    @Test
    fun `checkpoint owned auth rejects logout account replacement and retired sdk generation`() = runTest {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val storage = MemorySessionManager()
        val owner = GenerationOwnedSupabaseClient(storage, MemoryCodeVerifierCache(),
            isSessionStored = { storage.loadSessionOrNull() != null },
            factory = { session, verifier -> traceSdk(StandardTestDispatcher(testScheduler), session, verifier) },
            scope = backgroundScope)
        try {
            val sdk = owner.captureClient()
            sdk.auth.importSession(traceSession(), autoRefresh = true)
            testScheduler.runCurrent()
            val status = sdk.auth.sessionStatus.value as SessionStatus.Authenticated
            val auth = AuthState.SignedIn(TRACE_OWNER, null)
            val end = Clock.System.now() + 10.seconds
            assertTrue(app.checkpointTraceOwnedAuthCurrent(owner, sdk, auth, auth, status, end))
            assertFalse(app.checkpointTraceOwnedAuthCurrent(owner, sdk, auth, AuthState.SignedOut, status, end))
            assertFalse(app.checkpointTraceOwnedAuthCurrent(owner, sdk, auth, AuthState.SignedIn(TRACE_OTHER_OWNER, null), status, end))
            owner.prepareForSignIn(sdk)
            assertFalse(app.checkpointTraceOwnedAuthCurrent(owner, sdk, auth, auth, status, end))
        } finally { owner.close() }
    }

    @Test
    fun `checkpoint scope snapshot rejects ready unmanaged busy and recovery transition`() = runTest {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val state = Task126BusinessDataScopeState(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
            errorCode = "sync_recovery_required")
        assertTrue(app.checkpointTraceScopeIdle(state))
        assertFalse(app.checkpointTraceScopeIdle(Task126BusinessDataScopeState.ready(traceActiveScope())))
        assertFalse(app.checkpointTraceScopeIdle(Task126BusinessDataScopeState.unmanagedAllowed()))
        assertFalse(app.checkpointTraceScopeIdle(state.copy(errorCode = "different")))
        app.catalogSyncStateTracker.setSyncing(true)
        try { assertFalse(app.checkpointTraceScopeIdle(state)) }
        finally { app.catalogSyncStateTracker.setSyncing(false) }
        val mutex = privateApplicationField<Mutex>(app, "businessDataScopeMutex")
        mutex.lock()
        try { assertFalse(app.checkpointTraceScopeIdle(state)) } finally { mutex.unlock() }
        val recovery = backgroundScope.launch(start = CoroutineStart.LAZY) { error("must_not_start") }
        setPrivateApplicationField(app, "shopContextRecoveryJob", recovery)
        try { assertFalse(app.checkpointTraceScopeIdle(state)) }
        finally { recovery.cancel(); setPrivateApplicationField(app, "shopContextRecoveryJob", null) }
        assertTrue(app.checkpointTraceScopeIdle(state))
    }

    @Test
    fun `checkpoint snapshot reads five real entities without creating missing identity`() = runTest {
        withTraceDatabase { app, db ->
            val empty = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            assertNull(empty.device); assertNull(empty.binding); assertNull(empty.baseline)
            assertNull(empty.journal); assertNull(empty.watermark)
            assertFalse(empty.allowsCheckpointScope(traceActiveScope(), TRACE_SHOP))
            assertNull(db.syncEventDeviceStateDao().get())
            assertNull(db.syncRecoveryJournalDao().get())
        }
    }

    @Test
    fun `checkpoint journal scope guard distinguishes same scope and explicit mismatch consent`() = runTest {
        withTraceDatabase { app, db ->
            seedTraceEntities(db)
            val snapshot = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            val scope = traceActiveScope()
            assertTrue(snapshot.allowsCheckpointScope(scope, TRACE_SHOP))
            val journal = requireNotNull(snapshot.journal)
            for (invalid in listOf(snapshot.copy(device = null), snapshot.copy(journal = null),
                snapshot.copy(binding = null), snapshot.copy(journal = journal.copy(phase = SyncRecoveryJournalPhases.STAGING)),
                snapshot.copy(journal = journal.copy(authorizationMode = "unknown")),
                snapshot.copy(journal = journal.copy(deviceId = "foreign")),
                snapshot.copy(journal = journal.copy(ownerHash = "foreign")),
                snapshot.copy(journal = journal.copy(storeScope = "foreign")),
                snapshot.copy(baseline = requireNotNull(snapshot.baseline).copy(deviceId = "foreign")))) {
                assertFalse("Invalid scope never authorizes checkpoint", invalid.allowsCheckpointScope(scope, TRACE_SHOP))
            }
            assertFalse(snapshot.allowsCheckpointScope(scope, "foreign"))
            val priorBinding = requireNotNull(snapshot.binding).copy(ownerHash = "previous-owner")
            val explicit = snapshot.copy(binding = priorBinding,
                journal = journal.copy(authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(explicit.allowsCheckpointScope(scope, TRACE_SHOP))
            assertEquals(priorBinding, explicit.binding)
        }
    }

    @Test
    fun `checkpoint post snapshot detects durable field changes and nullable watermark row loss`() = runTest {
        withTraceDatabase { app, db ->
            seedTraceEntities(db)
            val before = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            db.syncRecoveryJournalDao().upsert(requireNotNull(before.journal).copy(attemptCount = 1))
            assertFalse(before == app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
            db.syncRecoveryJournalDao().upsert(requireNotNull(before.journal))
            db.syncRecoveryBaselineDao().upsert(requireNotNull(before.baseline).copy(generationId = "next"))
            assertFalse(before == app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
            db.syncRecoveryBaselineDao().upsert(requireNotNull(before.baseline))
            db.businessDataScopeBindingDao().upsert(requireNotNull(before.binding).copy(boundAtMs = 2))
            assertFalse(before == app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
            db.businessDataScopeBindingDao().upsert(requireNotNull(before.binding))
            db.syncEventWatermarkDao().deleteAll()
            val after = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            assertFalse(before == after)
            assertNotNull(before.watermark); assertNull(after.watermark)
            assertEquals(0L, requireNotNull(before.watermark).lastSyncEventId)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_event_device_state SET createdAtMs = 2 WHERE id = 1")
            assertFalse(before.device == app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP").device)
        }
    }


    @Test fun checkpointReadinessLocalSnapshotReadsNoWritesOrNetworkAndDoesNotConsume() = runTest {
        var writes = 0
        withTraceDatabase(onQuery = { sql ->
            if (Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(sql)) writes++
        }) { app, db ->
            seedTraceEntities(db) // Synthetic fixture setup precedes measurement.
            val before = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            assertTrue("Synthetic local-guard fixture must satisfy the original scope predicate",
                before.allowsCheckpointScope(traceActiveScope(), TRACE_SHOP))
            val sdkDelegate = privateApplicationField<Lazy<Any?>>(app, "supabaseClient\$delegate")
            var sdkEffects = 0
            val forbiddenSdk = object : Lazy<Any?> {
                override val value: Any? get() { sdkEffects++; error("No SDK initialization/network") }
                override fun isInitialized() = false
            }
            setPrivateApplicationField(app, "supabaseClient\$delegate", forbiddenSdk)
            writes = 0
            try {
                val memory = MerchandiseControlApplication.CheckpointReadinessSnapshot(
                    MerchandiseControlApplication.CheckpointReadinessOutcome.SNAPSHOT_INCOMPLETE)
                var fences = 0
                val snapshot = withContext(Dispatchers.Default) {
                    app.checkpointReadinessLocalGuards(
                        TRACE_OWNER, traceActiveScope(), TRACE_SHOP, 2_000, { fences++; true }, memory)
                }
                assertTrue("Local scope guard actual: " + snapshot.safeLogLine(), snapshot.localScopeGuard == true)
                assertTrue(snapshot.localSnapshotUnchanged == true)
                assertFalse(snapshot.preflightEligible == true) // UNKNOWN memory cannot qualify the caller.
                assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.SNAPSHOT_INCOMPLETE, snapshot.outcome)
                val qualified = withContext(Dispatchers.Default) {
                    app.checkpointReadinessLocalGuards(
                        TRACE_OWNER, traceActiveScope(), TRACE_SHOP, 2_000, { fences++; true }, readinessMemoryFixture())
                }
                assertTrue("Qualified local guard actual: " + qualified.safeLogLine(), qualified.preflightEligible == true)
                var staleFences = 0
                val stale = withContext(Dispatchers.Default) {
                    app.checkpointReadinessLocalGuards(
                        TRACE_OWNER, traceActiveScope(), TRACE_SHOP, 2_000, { ++staleFences == 1 }, readinessMemoryFixture())
                }
                assertFalse(stale.preflightEligible == true)
                assertEquals(false, stale.fenceCurrent)
                assertTrue(fences >= 4)
                assertEquals(0, writes)
                assertEquals(0, sdkEffects)
                assertEquals(before, app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
                assertFalse(privateApplicationField<Boolean>(app, "checkpointTraceConsumed"))
                app.requestOneCheckpointTrace(true)
                app.requestOneCheckpointTrace(true)
                testScheduler.runCurrent()
                val traces = traceMessages()
                assertEquals(1, traces.count { it.contains("outcome=BLOCKED_TEST_TARGET") })
                assertEquals(1, traces.count { it.contains("outcome=BLOCKED_USED") })
                assertTrue(traces.all { it.contains("localRpcAttemptCount=0") })
                assertEquals(0, sdkEffects)
            } finally { setPrivateApplicationField(app, "supabaseClient\$delegate", sdkDelegate) }
        }
    }

    @Test fun checkpointReadinessNeverOpensConstructedDatabase() = runTest {
        var queries = 0
        withTraceDatabase(onQuery = { queries++ }) { app, db ->
            assertFalse(db.isOpen)
            val snapshot = app.checkpointReadinessLocalGuards(
                TRACE_OWNER, traceActiveScope(), TRACE_SHOP, 2_000, { true }, readinessMemoryFixture())
            assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.BLOCKED_UNAVAILABLE, snapshot.outcome)
            assertFalse(snapshot.preflightEligible == true)
            assertFalse(db.isOpen)
            assertEquals(0, queries)
            assertFalse(privateApplicationField<Boolean>(app, "checkpointTraceConsumed"))
        }
    }

    /** TEST profile proves positive intent/preflight; canonical profile proves target fail-closed. */
    @Test
    @OptIn(SupabaseInternal::class)
    fun checkpointReadinessAuthenticatedIntentIsEligibleWithoutEffects() = runBlocking {
        val targetTest = MessageDigest.getInstance("SHA-256")
            .digest(BuildConfig.SUPABASE_URL.encodeToByteArray())
            .joinToString("") { "%02x".format(it) } ==
            "42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842"
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        try { WorkManager.getInstance(app) }
        catch (_: IllegalStateException) { WorkManager.initialize(app, Configuration.Builder().build()) }
        fun ordinaryIntent() = Intent(Intent.ACTION_MAIN)
            .putExtra("task126_ui_smoke_kind", "checkpoint-lifecycle")
        // Ordinary Activity startup precedes fixture installation and effect measurement.
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        val queuedMain = StandardTestDispatcher()
        val httpCalls = AtomicInteger()
        val storageWrites = AtomicInteger()
        val roomWrites = AtomicInteger()
        val roomQueries = AtomicInteger()
        val writeSql = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER)\\b", RegexOption.IGNORE_CASE)
        try {
            assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
            withTraceDatabase(onQuery = { sql ->
                roomQueries.incrementAndGet()
                if (writeSql.containsMatchIn(sql)) roomWrites.incrementAndGet()
            }) { measuredApp, db ->
                assertSame(app, measuredApp)
                seedTraceEntities(db)
                val before = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
                assertTrue(before.allowsCheckpointScope(traceActiveScope(), TRACE_SHOP))
                assertTrue(db.isOpen)
                val fixtureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val memoryStorage = MemorySessionManager()
                val storage = object : io.github.jan.supabase.auth.SessionManager by memoryStorage {
                    override suspend fun saveSession(session: UserSession) {
                        storageWrites.incrementAndGet(); memoryStorage.saveSession(session)
                    }
                    override suspend fun deleteSession() {
                        storageWrites.incrementAndGet(); memoryStorage.deleteSession()
                    }
                }
                val owner = GenerationOwnedSupabaseClient(storage, MemoryCodeVerifierCache(),
                    isSessionStored = { memoryStorage.loadSessionOrNull() != null },
                    factory = { session, verifier -> traceSdk(Dispatchers.IO, session, verifier, httpCalls) },
                    scope = fixtureScope)
                val delegates = listOf("supabaseClient", "authManager", "catalogSyncStateTracker", "shopContextRepository")
                    .associateWith { privateApplicationField<Any>(app, it + "\$delegate") }
                try {
                    val sdk = owner.captureClient()
                    sdk.auth.importSession(traceSession(), autoRefresh = true)
                    val setupDeadline = System.nanoTime() + 2_000_000_000L
                    while (sdk.auth.autoRefreshInformation() == null || owner.sessionStatus.value !is SessionStatus.Authenticated) {
                        check(System.nanoTime() < setupDeadline) { "Synthetic SDK initialization exceeded its bounded setup" }
                        Thread.sleep(1)
                    }
                    val status = sdk.auth.sessionStatus.value as SessionStatus.Authenticated
                    val scheduled = requireNotNull(sdk.auth.autoRefreshInformation()).refreshingAt
                    assertTrue(app.checkpointTraceAuthBudgetCurrent(sdk, status, Clock.System.now() + 10.seconds))
                    val auth = SupabaseAuthManager(owner, "synthetic-client-id", scope = fixtureScope)
                    val signedIn = AuthState.SignedIn(TRACE_OWNER, null)
                    // Fixture setup only: restoreSession would force an unrelated HTTP refresh.
                    privateApplicationField<MutableStateFlow<AuthState>>(auth, "_state").value = signedIn
                    val shopFetches = AtomicInteger()
                    val shopWrites = AtomicInteger()
                    val shop = ShopContextRepository(
                        remote = object : LinkedShopRemoteDataSource {
                            override val isConfigured = true
                            override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> {
                                shopFetches.incrementAndGet()
                                return Result.success(listOf(LinkedShop(TRACE_SHOP, null, "synthetic-shop", "owner", "active",
                                    selectable = true, canWrite = true)))
                            }
                        },
                        selectedShopStore = object : SelectedShopStore {
                            private var selection: String? = null
                            override fun getSelectedShopId(ownerUserId: String) = selection
                            override fun setSelectedShopId(ownerUserId: String, shopId: String) {
                                shopWrites.incrementAndGet(); selection = shopId
                            }
                            override fun clearSelectedShopId(ownerUserId: String) {
                                shopWrites.incrementAndGet(); selection = null
                            }
                        }, currentOwnerUserId = { TRACE_OWNER })
                    shop.refresh(TRACE_OWNER)
                    val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState(
                        status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "sync_recovery_required"))
                    setPrivateApplicationField(app, "supabaseClient\$delegate", lazyOf(owner))
                    setPrivateApplicationField(app, "authManager\$delegate", lazyOf(auth))
                    setPrivateApplicationField(app, "catalogSyncStateTracker\$delegate", lazyOf(tracker))
                    setPrivateApplicationField(app, "shopContextRepository\$delegate", lazyOf(shop))
                    assertTrue("Initialized synthetic RAM facts must qualify before intent delivery",
                        app.checkpointReadinessMemory(true, true, true).hasRequiredMemoryFacts())
                    val epoch = shop.diagnosticShopEpoch()
                    val context = shop.state.value
                    val stamp = requireNotNull(tracker.captureDiagnosticQuietStamp())
                    assertEquals("Synthetic setup must not send HTTP", 0, httpCalls.get())
                    storageWrites.set(0); roomWrites.set(0); roomQueries.set(0)
                    shopFetches.set(0); shopWrites.set(0)
                    ShadowLog.clear()
                    Dispatchers.setMain(queuedMain)
                    val intent = ordinaryIntent().putExtra("task143_checkpoint_readiness", true)
                    activity.pause().newIntent(intent)
                    assertEquals(Lifecycle.State.STARTED, activity.get().lifecycle.currentState)
                    assertFalse(intent.hasExtra("task143_checkpoint_readiness"))
                    assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty())
                    activity.resume()
                    assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
                    // Only run currently queued Main work; never advance to the SDK refresh deadline.
                    val queryDeadline = System.nanoTime() + 5_000_000_000L
                    while (ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty() && System.nanoTime() < queryDeadline) {
                        queuedMain.scheduler.runCurrent()
                        Thread.sleep(1)
                    }
                    val messages = ShadowLog.getLogsForTag("Task143CheckpointReadiness")
                    assertEquals("Exactly one bounded readiness result is required", 1, messages.size)
                    val message = messages.single().msg
                    assertTrue("Observed readiness scalars: " + message, message.contains("target_TEST=$targetTest"))
                    assertTrue("Observed readiness scalars: " + message, message.contains("activity_resumed=true"))
                    if (targetTest) {
                        assertTrue("Expected positive TEST readiness: " + message, message.contains("outcome=SNAPSHOT_ELIGIBLE"))
                        assertTrue("Expected positive TEST readiness: " + message, message.contains("preflight_eligible=true"))
                        assertTrue("Positive preflight must read the opened local fixture", roomQueries.get() > 0)
                    } else {
                        assertTrue("Expected canonical target refusal: " + message, message.contains("outcome=BLOCKED_TEST_TARGET"))
                        assertTrue("Expected canonical target refusal: " + message, message.contains("preflight_eligible=UNKNOWN"))
                        assertEquals("Wrong-target query must not read Room", 0, roomQueries.get())
                    }
                    assertTrue(message.contains("business_READY=false"))
                    assertEquals(0, httpCalls.get()); assertEquals(0, storageWrites.get())
                    assertEquals(0, roomWrites.get()); assertEquals(0, shopFetches.get()); assertEquals(0, shopWrites.get())
                    assertTrue("Readonly local entities must be unchanged",
                        before == app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
                    assertFalse(privateApplicationField<Boolean>(app, "checkpointTraceConsumed"))
                    assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
                    assertNull(privateApplicationField<Job?>(app, "businessRecoveryJob"))
                    assertNull(privateApplicationField<Job?>(app, "shopContextRecoveryJob"))
                    assertNull(privateApplicationField<Any?>(activity.get(), "pendingCheckpointTraceObserver"))
                    assertFalse(privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex").isLocked)
                    assertFalse(privateApplicationField<Mutex>(app, "businessDataScopeMutex").isLocked)
                    assertTrue(traceMessages().isEmpty())
                    assertSame(sdk, owner.captureClientOrNull()); assertSame(status, sdk.auth.sessionStatus.value)
                    assertSame(signedIn, auth.state.value)
                    assertEquals(scheduled, requireNotNull(sdk.auth.autoRefreshInformation()).refreshingAt)
                    assertEquals(epoch, shop.diagnosticShopEpoch()); assertEquals(context, shop.state.value)
                    assertTrue(tracker.isDiagnosticQuietStampCurrent(stamp))
                } finally {
                    Dispatchers.setMain(mainDispatcherRule.dispatcher)
                    delegates.forEach { (name, value) -> setPrivateApplicationField(app, name + "\$delegate", value) }
                    try { owner.close() } finally { fixtureScope.cancel() }
                }
            }
        } finally {
            Dispatchers.setMain(mainDispatcherRule.dispatcher)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun checkpointReadinessBusyJournalPhasesAreReadonlyAndNeverQualify() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val writeSql = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER)\\b", RegexOption.IGNORE_CASE)
        withTraceDatabase(onQuery = { sql ->
            reads.incrementAndGet()
            if (writeSql.containsMatchIn(sql)) writes.incrementAndGet()
        }) { app, db ->
            seedTraceEntities(db)
            val initial = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
            val sdkDelegate = privateApplicationField<Lazy<Any?>>(app, "supabaseClient\$delegate")
            val sdkEffects = AtomicInteger()
            setPrivateApplicationField(app, "supabaseClient\$delegate", object : Lazy<Any?> {
                override val value: Any? get() { sdkEffects.incrementAndGet(); error("Forbidden SDK lazy initialization") }
                override fun isInitialized() = false
            })
            val ownedRecovery = Job()
            val previousRecovery = privateApplicationField<Job?>(app, "businessRecoveryJob")
            val executionMutex = privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex")
            val scopeMutex = privateApplicationField<Mutex>(app, "businessDataScopeMutex")
            var ownsExecutionMutex = false
            var ownsScopeMutex = false
            var installedRecovery = false
            val memory = readinessMemoryFixture().copy(recoveryIdle = false, scopeMutexIdle = false, scopeIdle = false)
            assertFalse(memory.hasRequiredMemoryFacts())
            try {
                assertNull(previousRecovery)
                ownsExecutionMutex = executionMutex.tryLock()
                assertTrue(ownsExecutionMutex)
                ownsScopeMutex = scopeMutex.tryLock()
                assertTrue(ownsScopeMutex)
                setPrivateApplicationField(app, "businessRecoveryJob", ownedRecovery)
                installedRecovery = true
                val phases = listOf(
                    SyncRecoveryJournalPhases.REQUIRED to MerchandiseControlApplication.CheckpointJournalPhase.REQUIRED,
                    SyncRecoveryJournalPhases.STAGING to MerchandiseControlApplication.CheckpointJournalPhase.STAGING,
                    SyncRecoveryJournalPhases.READY_TO_ACTIVATE to MerchandiseControlApplication.CheckpointJournalPhase.READY_TO_ACTIVATE,
                    SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING to MerchandiseControlApplication.CheckpointJournalPhase.ACTIVATED_CLEANUP_PENDING)
                for ((storedPhase, expectedPhase) in phases) {
                    db.syncRecoveryJournalDao().upsert(requireNotNull(initial.journal).copy(
                        phase = storedPhase, reason = "shop_sync_rpc_http_500", attemptCount = 17,
                        nextRetryAtMs = 1_700_000_000_143L))
                    val stored = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
                    writes.set(0); reads.set(0) // All synthetic setup is outside measurement.
                    val snapshot = withContext(Dispatchers.Default) {
                        app.checkpointReadinessLocalGuards(TRACE_OWNER, traceActiveScope(), TRACE_SHOP,
                            2_000, { true }, memory)
                    }
                    assertEquals(expectedPhase, snapshot.journalPhase)
                    assertEquals(17, snapshot.journalAttempt)
                    assertEquals(1_700_000_000_143L, snapshot.journalNextRetryAtMs)
                    assertEquals(MerchandiseControlApplication.CheckpointJournalError.HTTP_SERVER, snapshot.journalError)
                    assertEquals(stored.allowsCheckpointScope(traceActiveScope(), TRACE_SHOP), snapshot.localScopeGuard)
                    assertEquals(true, snapshot.localSnapshotUnchanged)
                    assertEquals(true, snapshot.fenceCurrent)
                    assertEquals(false, snapshot.preflightEligible)
                    val line = snapshot.safeLogLine()
                    assertTrue(line.contains("journal_phase=${expectedPhase.name}"))
                    assertTrue(line.contains("journal_attempt=17"))
                    assertTrue(line.contains("journal_next_retry_at_ms=1700000000143"))
                    assertTrue(line.contains("journal_error=HTTP_SERVER"))
                    assertFalse(line.contains("shop_sync_rpc_http_500")) // Category, never the raw reason.
                    assertTrue(reads.get() > 0)
                    assertEquals(0, writes.get()); assertEquals(0, sdkEffects.get())
                    assertEquals(stored, app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
                    assertSame(ownedRecovery, privateApplicationField<Job?>(app, "businessRecoveryJob"))
                    assertFalse(ownedRecovery.isCompleted)
                    assertTrue(executionMutex.isLocked); assertTrue(scopeMutex.isLocked)
                    assertFalse(privateApplicationField<Boolean>(app, "checkpointTraceConsumed"))
                    assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
                    assertTrue(traceMessages().isEmpty())
                }
            } finally {
                if (installedRecovery) setPrivateApplicationField(app, "businessRecoveryJob", previousRecovery)
                if (ownsExecutionMutex) executionMutex.unlock()
                if (ownsScopeMutex) scopeMutex.unlock()
                ownedRecovery.cancel()
                setPrivateApplicationField(app, "supabaseClient\$delegate", sdkDelegate)
            }
        }
    }

    @Test
    fun checkpointReadinessJournalRejectsUnscopedDriftInvalidAndUnavailableFacts() = runBlocking {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val deviceReads = AtomicInteger()
        var driftDatabase: AppDatabase? = null
        var injectDrift = false
        var delayDeviceRead = false
        val writeSql = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER)\\b", RegexOption.IGNORE_CASE)
        withTraceDatabase(onQuery = { sql ->
            reads.incrementAndGet()
            if (writeSql.containsMatchIn(sql)) writes.incrementAndGet()
            if (Regex("^\\s*SELECT\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql) &&
                sql.contains("FROM sync_event_device_state", ignoreCase = true)) {
                if (delayDeviceRead) Thread.sleep(100) // Real elapsed clock, no virtual scheduler.
                if (injectDrift && deviceReads.incrementAndGet() == 2) {
                    // Synthetic external writer between the two complete snapshots, not diagnostic code.
                    requireNotNull(driftDatabase).openHelper.writableDatabase.execSQL(
                        "UPDATE sync_recovery_journal SET attemptCount = attemptCount + 1 WHERE id = 1")
                }
            }
        }) { app, db ->
            driftDatabase = db
            seedTraceEntities(db)
            val original = requireNotNull(db.syncRecoveryJournalDao().get()).copy(
                reason = "shop_sync_rpc_http_500", attemptCount = 17, nextRetryAtMs = 1_700_000_000_143L)
            val memory = readinessMemoryFixture().copy(recoveryIdle = false)
            fun assertUnknown(snapshot: MerchandiseControlApplication.CheckpointReadinessSnapshot) {
                assertNull(snapshot.journalPhase); assertNull(snapshot.journalAttempt)
                assertNull(snapshot.journalNextRetryAtMs); assertNull(snapshot.journalError)
                assertFalse(snapshot.preflightEligible == true)
                val line = snapshot.safeLogLine()
                listOf("journal_phase", "journal_attempt", "journal_next_retry_at_ms", "journal_error")
                    .forEach { assertTrue(line.contains("$it=UNKNOWN")) }
                assertFalse(line.contains("synthetic-private"))
            }
            suspend fun observe(current: () -> Boolean = { true }, remainingMs: Long = 2_000) =
                withContext(Dispatchers.Default) {
                    app.checkpointReadinessLocalGuards(TRACE_OWNER, traceActiveScope(), TRACE_SHOP,
                        remainingMs, current, memory)
                }
            val foreign = listOf(
                original.copy(ownerHash = "synthetic-private-owner"),
                original.copy(storeScope = "synthetic-private-store"),
                original.copy(shopId = "synthetic-private-shop"),
                original.copy(deviceId = "synthetic-private-device"),
                original.copy(authorizationMode = "synthetic-private-authorization"))
            for (journal in foreign + listOf<SyncRecoveryJournal?>(null)) {
                if (journal == null) db.syncRecoveryJournalDao().deleteAll()
                else db.syncRecoveryJournalDao().upsert(journal)
                val stored = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
                writes.set(0); reads.set(0)
                assertUnknown(observe())
                assertTrue(reads.get() > 0); assertEquals(0, writes.get())
                assertEquals(stored, app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
            }
            for (journal in listOf(
                original.copy(phase = "synthetic-private-phase"),
                original.copy(reason = "synthetic-private-reason Authorization: Bearer do-not-emit"),
                original.copy(attemptCount = -1),
                original.copy(nextRetryAtMs = -1))) {
                db.syncRecoveryJournalDao().upsert(journal)
                val stored = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
                writes.set(0)
                val snapshot = observe()
                assertEquals(false, snapshot.preflightEligible)
                assertEquals(if (journal.phase == original.phase) MerchandiseControlApplication.CheckpointJournalPhase.REQUIRED else null,
                    snapshot.journalPhase)
                assertEquals(journal.attemptCount.takeIf { it >= 0 }, snapshot.journalAttempt)
                assertEquals(journal.nextRetryAtMs?.takeIf { it >= 0 }, snapshot.journalNextRetryAtMs)
                assertEquals(if (journal.reason == original.reason) MerchandiseControlApplication.CheckpointJournalError.HTTP_SERVER
                    else MerchandiseControlApplication.CheckpointJournalError.UNKNOWN, snapshot.journalError)
                assertFalse(snapshot.safeLogLine().contains("synthetic-private"))
                assertFalse(snapshot.safeLogLine().contains("Authorization"))
                assertFalse(snapshot.safeLogLine().contains("do-not-emit"))
                assertEquals(0, writes.get()); assertEquals(stored, app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
            }
            db.syncRecoveryJournalDao().upsert(original)
            deviceReads.set(0); writes.set(0); injectDrift = true
            val drifted = try { observe() } finally { injectDrift = false }
            assertEquals(2, deviceReads.get())
            assertEquals(false, drifted.localSnapshotUnchanged)
            assertUnknown(drifted)
            assertEquals("Exactly the synthetic writer mutation, no diagnostic mutation", 1, writes.get())
            assertEquals(18, requireNotNull(db.syncRecoveryJournalDao().get()).attemptCount)
            db.syncRecoveryJournalDao().upsert(original)
            writes.set(0)
            var fences = 0
            val stale = observe(current = { ++fences == 1 })
            assertEquals(false, stale.fenceCurrent); assertUnknown(stale)
            assertEquals(0, writes.get())
            val expired = observe(remainingMs = 0)
            assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.TIMEOUT_UNKNOWN, expired.outcome)
            assertUnknown(expired)
            delayDeviceRead = true
            val timedOut = try { observe(remainingMs = 20) } finally { delayDeviceRead = false }
            assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.TIMEOUT_UNKNOWN, timedOut.outcome)
            assertUnknown(timedOut)
            assertEquals(0, writes.get())
            assertFalse(privateApplicationField<Boolean>(app, "checkpointTraceConsumed"))
            assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
        }
        val queries = AtomicInteger()
        withTraceDatabase(onQuery = { queries.incrementAndGet() }) { app, db ->
            assertFalse(db.isOpen)
            val unopened = withContext(Dispatchers.Default) {
                app.checkpointReadinessLocalGuards(TRACE_OWNER, traceActiveScope(), TRACE_SHOP,
                    2_000, { true }, readinessMemoryFixture().copy(recoveryIdle = false))
            }
            assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.BLOCKED_UNAVAILABLE, unopened.outcome)
            assertNull(unopened.journalPhase); assertNull(unopened.journalAttempt)
            assertNull(unopened.journalNextRetryAtMs); assertNull(unopened.journalError)
            assertFalse(unopened.preflightEligible == true)
            assertFalse(db.isOpen); assertEquals(0, queries.get())
        }
    }

    /** TEST profile observes busy journal facts; canonical profile still refuses before any Room read. */
    @Test
    @OptIn(SupabaseInternal::class)
    fun checkpointReadinessAuthenticatedBusyIntentObservesJournalWithoutEffects() = runBlocking {
        val targetTest = MessageDigest.getInstance("SHA-256")
            .digest(BuildConfig.SUPABASE_URL.encodeToByteArray())
            .joinToString("") { "%02x".format(it) } ==
            "42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842"
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        try { WorkManager.getInstance(app) }
        catch (_: IllegalStateException) { WorkManager.initialize(app, Configuration.Builder().build()) }
        fun ordinaryIntent() = Intent(Intent.ACTION_MAIN)
            .putExtra("task126_ui_smoke_kind", "checkpoint-lifecycle")
        // Ordinary Activity startup precedes fixture installation and effect measurement.
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        val queuedMain = StandardTestDispatcher()
        val ownedRecoveryJob = Job()
        val previousRecoveryJob = privateApplicationField<Job?>(app, "businessRecoveryJob")
        val executionMutex = privateApplicationField<Mutex>(app, "businessRecoveryExecutionMutex")
        val scopeMutex = privateApplicationField<Mutex>(app, "businessDataScopeMutex")
        var installedRecovery = false
        var ownsExecutionMutex = false
        var ownsScopeMutex = false
        val httpCalls = AtomicInteger()
        val storageWrites = AtomicInteger()
        val roomWrites = AtomicInteger()
        val roomQueries = AtomicInteger()
        val writeSql = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER)\\b", RegexOption.IGNORE_CASE)
        try {
            assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
            withTraceDatabase(onQuery = { sql ->
                roomQueries.incrementAndGet()
                if (writeSql.containsMatchIn(sql)) roomWrites.incrementAndGet()
            }) { measuredApp, db ->
                assertSame(app, measuredApp)
                seedTraceEntities(db)
                db.syncRecoveryJournalDao().upsert(requireNotNull(db.syncRecoveryJournalDao().get()).copy(
                    reason = "shop_sync_rpc_http_500", attemptCount = 17, nextRetryAtMs = 1_700_000_000_143L))
                val before = app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP")
                assertTrue(before.allowsCheckpointScope(traceActiveScope(), TRACE_SHOP))
                assertTrue(db.isOpen)
                val fixtureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val memoryStorage = MemorySessionManager()
                val storage = object : io.github.jan.supabase.auth.SessionManager by memoryStorage {
                    override suspend fun saveSession(session: UserSession) {
                        storageWrites.incrementAndGet(); memoryStorage.saveSession(session)
                    }
                    override suspend fun deleteSession() {
                        storageWrites.incrementAndGet(); memoryStorage.deleteSession()
                    }
                }
                val owner = GenerationOwnedSupabaseClient(storage, MemoryCodeVerifierCache(),
                    isSessionStored = { memoryStorage.loadSessionOrNull() != null },
                    factory = { session, verifier -> traceSdk(Dispatchers.IO, session, verifier, httpCalls) },
                    scope = fixtureScope)
                val delegates = listOf("supabaseClient", "authManager", "catalogSyncStateTracker", "shopContextRepository")
                    .associateWith { privateApplicationField<Any>(app, it + "\$delegate") }
                try {
                    val sdk = owner.captureClient()
                    sdk.auth.importSession(traceSession(), autoRefresh = true)
                    val setupDeadline = System.nanoTime() + 2_000_000_000L
                    while (sdk.auth.autoRefreshInformation() == null || owner.sessionStatus.value !is SessionStatus.Authenticated) {
                        check(System.nanoTime() < setupDeadline) { "Synthetic SDK initialization exceeded its bounded setup" }
                        Thread.sleep(1)
                    }
                    val status = sdk.auth.sessionStatus.value as SessionStatus.Authenticated
                    val scheduled = requireNotNull(sdk.auth.autoRefreshInformation()).refreshingAt
                    assertTrue(app.checkpointTraceAuthBudgetCurrent(sdk, status, Clock.System.now() + 10.seconds))
                    val auth = SupabaseAuthManager(owner, "synthetic-client-id", scope = fixtureScope)
                    val signedIn = AuthState.SignedIn(TRACE_OWNER, null)
                    // Fixture setup only: restoreSession would force an unrelated HTTP refresh.
                    privateApplicationField<MutableStateFlow<AuthState>>(auth, "_state").value = signedIn
                    val shopFetches = AtomicInteger()
                    val shopWrites = AtomicInteger()
                    val shop = ShopContextRepository(
                        remote = object : LinkedShopRemoteDataSource {
                            override val isConfigured = true
                            override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> {
                                shopFetches.incrementAndGet()
                                return Result.success(listOf(LinkedShop(TRACE_SHOP, null, "synthetic-shop", "owner", "active",
                                    selectable = true, canWrite = true)))
                            }
                        },
                        selectedShopStore = object : SelectedShopStore {
                            private var selection: String? = null
                            override fun getSelectedShopId(ownerUserId: String) = selection
                            override fun setSelectedShopId(ownerUserId: String, shopId: String) {
                                shopWrites.incrementAndGet(); selection = shopId
                            }
                            override fun clearSelectedShopId(ownerUserId: String) {
                                shopWrites.incrementAndGet(); selection = null
                            }
                        }, currentOwnerUserId = { TRACE_OWNER })
                    shop.refresh(TRACE_OWNER)
                    val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState(
                        status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "sync_recovery_required"))
                    setPrivateApplicationField(app, "supabaseClient\$delegate", lazyOf(owner))
                    setPrivateApplicationField(app, "authManager\$delegate", lazyOf(auth))
                    setPrivateApplicationField(app, "catalogSyncStateTracker\$delegate", lazyOf(tracker))
                    setPrivateApplicationField(app, "shopContextRepository\$delegate", lazyOf(shop))
                    assertTrue("Initialized synthetic RAM facts must qualify before intent delivery",
                        app.checkpointReadinessMemory(true, true, true).hasRequiredMemoryFacts())
                    assertNull(previousRecoveryJob)
                    ownsExecutionMutex = executionMutex.tryLock()
                    assertTrue(ownsExecutionMutex)
                    ownsScopeMutex = scopeMutex.tryLock()
                    assertTrue(ownsScopeMutex)
                    setPrivateApplicationField(app, "businessRecoveryJob", ownedRecoveryJob)
                    installedRecovery = true
                    assertFalse("Busy fixture must not qualify the original trace predicate",
                        app.checkpointReadinessMemory(true, true, true).hasRequiredMemoryFacts())
                    val epoch = shop.diagnosticShopEpoch()
                    val context = shop.state.value
                    val stamp = requireNotNull(tracker.captureDiagnosticQuietStamp())
                    assertEquals("Synthetic setup must not send HTTP", 0, httpCalls.get())
                    storageWrites.set(0); roomWrites.set(0); roomQueries.set(0)
                    shopFetches.set(0); shopWrites.set(0)
                    ShadowLog.clear()
                    Dispatchers.setMain(queuedMain)
                    val intent = ordinaryIntent().putExtra("task143_checkpoint_readiness", true)
                    activity.pause().newIntent(intent)
                    assertEquals(Lifecycle.State.STARTED, activity.get().lifecycle.currentState)
                    assertFalse(intent.hasExtra("task143_checkpoint_readiness"))
                    assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty())
                    activity.resume()
                    assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
                    // Only run currently queued Main work; never advance to the SDK refresh deadline.
                    val queryDeadline = System.nanoTime() + 5_000_000_000L
                    while (ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty() && System.nanoTime() < queryDeadline) {
                        queuedMain.scheduler.runCurrent()
                        Thread.sleep(1)
                    }
                    val messages = ShadowLog.getLogsForTag("Task143CheckpointReadiness")
                    assertEquals("Exactly one bounded readiness result is required", 1, messages.size)
                    val message = messages.single().msg
                    assertTrue("Observed readiness scalars: " + message, message.contains("target_TEST=$targetTest"))
                    assertTrue("Observed readiness scalars: " + message, message.contains("activity_resumed=true"))
                    if (targetTest) {
                        assertTrue("Expected busy TEST observation: " + message, message.contains("outcome=SNAPSHOT_INCOMPLETE"))
                        assertTrue("Busy observation cannot authorize trace: " + message, message.contains("preflight_eligible=false"))
                        assertTrue("Busy preflight must read the opened local journal", roomQueries.get() > 0)
                        assertTrue(message.contains("recovery_idle=false"))
                        assertTrue(message.contains("scope_mutex_idle=false"))
                        assertTrue(message.contains("journal_phase=REQUIRED"))
                        assertTrue(message.contains("journal_attempt=17"))
                        assertTrue(message.contains("journal_next_retry_at_ms=1700000000143"))
                        assertTrue(message.contains("journal_error=HTTP_SERVER"))
                    } else {
                        assertTrue("Expected canonical target refusal: " + message, message.contains("outcome=BLOCKED_TEST_TARGET"))
                        assertTrue("Expected canonical target refusal: " + message, message.contains("preflight_eligible=UNKNOWN"))
                        assertEquals("Wrong-target query must not read Room", 0, roomQueries.get())
                        listOf("journal_phase", "journal_attempt", "journal_next_retry_at_ms", "journal_error")
                            .forEach { assertTrue(message.contains("$it=UNKNOWN")) }
                    }
                    assertTrue(message.contains("business_READY=false"))
                    assertEquals(0, httpCalls.get()); assertEquals(0, storageWrites.get())
                    assertEquals(0, roomWrites.get()); assertEquals(0, shopFetches.get()); assertEquals(0, shopWrites.get())
                    assertTrue("Readonly local entities must be unchanged",
                        before == app.checkpointTraceSnapshot(TRACE_OWNER, "shop:$TRACE_SHOP"))
                    assertFalse(privateApplicationField<Boolean>(app, "checkpointTraceConsumed"))
                    assertNull(privateApplicationField<Any?>(app, "checkpointTraceReservation"))
                    assertSame(ownedRecoveryJob, privateApplicationField<Job?>(app, "businessRecoveryJob"))
                    assertFalse(ownedRecoveryJob.isCompleted)
                    assertNull(privateApplicationField<Job?>(app, "shopContextRecoveryJob"))
                    assertNull(privateApplicationField<Any?>(activity.get(), "pendingCheckpointTraceObserver"))
                    assertTrue(executionMutex.isLocked)
                    assertTrue(scopeMutex.isLocked)
                    assertFalse(message.contains("shop_sync_rpc_http_500"))
                    assertFalse(message.contains(TRACE_OWNER)); assertFalse(message.contains(TRACE_SHOP))
                    assertTrue(traceMessages().isEmpty())
                    assertSame(sdk, owner.captureClientOrNull()); assertSame(status, sdk.auth.sessionStatus.value)
                    assertSame(signedIn, auth.state.value)
                    assertEquals(scheduled, requireNotNull(sdk.auth.autoRefreshInformation()).refreshingAt)
                    assertEquals(epoch, shop.diagnosticShopEpoch()); assertEquals(context, shop.state.value)
                    assertTrue(tracker.isDiagnosticQuietStampCurrent(stamp))
                } finally {
                    if (installedRecovery) setPrivateApplicationField(app, "businessRecoveryJob", previousRecoveryJob)
                    if (ownsExecutionMutex) executionMutex.unlock()
                    if (ownsScopeMutex) scopeMutex.unlock()
                    ownedRecoveryJob.cancel()
                    Dispatchers.setMain(mainDispatcherRule.dispatcher)
                    delegates.forEach { (name, value) -> setPrivateApplicationField(app, name + "\$delegate", value) }
                    try { owner.close() } finally { fixtureScope.cancel() }
                }
            }
        } finally {
            Dispatchers.setMain(mainDispatcherRule.dispatcher)
            activity.pause().stop().destroy()
        }
    }

    private fun readinessMemoryFixture() = MerchandiseControlApplication.CheckpointReadinessSnapshot(
        MerchandiseControlApplication.CheckpointReadinessOutcome.SNAPSHOT_INCOMPLETE,
        activityWarm = true, activityResumed = true, traceObserverIdle = true,
        sdkSession = true, refreshBudget = true, sdkClientSameCapture = true,
        scopeIdle = true, scopeCurrent = true, generationStable = true, businessGenerationQuiet = true,
        recoveryIdle = true, scopeMutexIdle = true, shopRecoveryIdle = true, oneUseUnconsumed = true)

    private fun traceMessages() = ShadowLog.getLogsForTag("Task143CheckpointTrace").map { it.msg }
    private fun setPrivateApplicationField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun traceActiveScope() = Task126OwnerStoreScope(task126OwnerHash(TRACE_OWNER), "shop:$TRACE_SHOP", null)
    private fun traceSession(expiresAt: Instant = Clock.System.now() + 1.days) = UserSession(
        accessToken = "synthetic-trace-access", refreshToken = "synthetic-trace-refresh", expiresIn = 86_400,
        tokenType = "bearer", user = UserInfo(aud = "authenticated", id = TRACE_OWNER), expiresAt = expiresAt)
    @OptIn(SupabaseInternal::class)
    private fun traceSdk(dispatcher: CoroutineDispatcher,
        storage: io.github.jan.supabase.auth.SessionManager = MemorySessionManager(),
        verifier: io.github.jan.supabase.auth.CodeVerifierCache = MemoryCodeVerifierCache(),
        requestCounter: AtomicInteger? = null): SupabaseClient =
        createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            httpEngine = OkHttp.create {
                if (requestCounter != null) config {
                    addInterceptor {
                        requestCounter.incrementAndGet()
                        throw AssertionError("readiness_preflight_forbidden_http")
                    }
                }
            }
            coroutineDispatcher = dispatcher; defaultLogLevel = LogLevel.NONE
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false
                sessionManager = storage; codeVerifierCache = verifier }
            install(Postgrest)
        }
    private suspend fun withTraceDatabase(
        onQuery: ((String) -> Unit)? = null,
        block: suspend (MerchandiseControlApplication, AppDatabase) -> Unit
    ) {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val previous = privateApplicationField<Lazy<AppDatabase>>(app, "database\$delegate")
        val builder = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries()
        if (onQuery != null) builder.setQueryCallback({ sql, _ -> onQuery(sql) },
            java.util.concurrent.Executor { it.run() })
        val db = builder.build()
        setPrivateApplicationField(app, "database\$delegate", lazyOf(db))
        try { block(app, db) }
        finally { setPrivateApplicationField(app, "database\$delegate", previous); db.close() }
    }
    private suspend fun seedTraceEntities(db: AppDatabase) {
        val scope = traceActiveScope()
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = "synthetic-device", createdAtMs = 1))
        db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(scope, 1))
        db.syncRecoveryBaselineDao().upsert(SyncRecoveryBaseline(generationId = "synthetic-generation",
            ownerHash = scope.ownerHash, storeScope = scope.storeId, shopId = TRACE_SHOP, deviceId = "synthetic-device",
            scopeKind = "synthetic-snapshot-only", scopeKey = "synthetic-snapshot-only", checkpointJson = "{}", activatedAtMs = 1))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = scope.ownerHash, storeScope = scope.storeId,
            shopId = TRACE_SHOP, deviceId = "synthetic-device", authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "synthetic", blockingEventId = null, attemptCount = 0,
            createdAtMs = 1, updatedAtMs = 1, nextRetryAtMs = null))
        db.syncEventWatermarkDao().upsert(SyncEventWatermark(ownerUserId = TRACE_OWNER, storeScope = scope.storeId, lastSyncEventId = 0))
    }
    private companion object {
        const val TRACE_OWNER = "00000000-0000-4000-8000-000000000143"
        const val TRACE_OTHER_OWNER = "00000000-0000-4000-8000-000000000243"
        const val TRACE_SHOP = "00000000-0000-4000-8000-000000000343"
    }

    @Test
    fun `139 resolved business scope alignment validates computed state during transition`() {
        val activeScope = Task126OwnerStoreScope(
            ownerHash = "owner-a",
            storeId = "shop:shop-a",
            localStoreId = null
        )
        val ready = Task126BusinessDataScopeState.ready(activeScope)
        val mismatched = Task126BusinessDataScopeState.ready(
            Task126OwnerStoreScope(
                ownerHash = "owner-b",
                storeId = "shop:shop-b",
                localStoreId = null
            )
        )

        assertTrue(allowsResolvedBusinessDataScope(ready, activeScope))
        assertFalse(allowsResolvedBusinessDataScope(mismatched, activeScope))
        assertFalse(
            allowsResolvedBusinessDataScope(
                Task126BusinessDataScopeState(
                    status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE
                ),
                activeScope
            )
        )
    }

    @Test
    fun `139 shop context retries only for signed-in online recoverable state`() {
        val signedIn = AuthState.SignedIn(
            userId = "13900000-0000-4000-8000-000000000001",
            email = null
        )
        val blocked = ShopContext.blocked(signedIn.userId, "offline")

        assertTrue(shouldRetryShopContext(signedIn, blocked, networkAvailable = true))
        assertFalse(shouldRetryShopContext(signedIn, blocked, networkAvailable = false))
        assertFalse(
            shouldRetryShopContext(
                signedIn,
                blocked.copy(isLoading = true),
                networkAvailable = true
            )
        )
        assertFalse(
            shouldRetryShopContext(
                signedIn,
                ShopContext.legacy(signedIn.userId),
                networkAvailable = true
            )
        )
        assertTrue(
            shouldRetryShopContext(
                signedIn,
                ShopContext.legacy("13900000-0000-4000-8000-000000000002"),
                networkAvailable = true
            )
        )
        assertFalse(shouldRetryShopContext(AuthState.SignedOut, blocked, networkAvailable = true))
    }

    @Test
    fun `139 recovery retry cap is bounded per trigger and not permanent`() {
        assertTrue(
            shouldAttemptAutomaticBusinessRecovery(
                attemptsInCurrentWindow = 0,
                durableAttemptCount = 5
            )
        )
        assertTrue(
            shouldAttemptAutomaticBusinessRecovery(
                attemptsInCurrentWindow = 4,
                durableAttemptCount = 1_000_000
            )
        )
        assertFalse(
            shouldAttemptAutomaticBusinessRecovery(
                attemptsInCurrentWindow = 5,
                durableAttemptCount = 5
            )
        )
        assertFalse(
            shouldAttemptAutomaticBusinessRecovery(
                attemptsInCurrentWindow = 0,
                durableAttemptCount = -1
            )
        )
    }

    @Test
    fun `139 viewmodel factory recovery callback reuses application single flight`() = runTest {
        val application = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        application.catalogSyncStateTracker.updateBusinessDataScopeState(
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                errorCode = "sync_recovery_required"
            )
        )
        val executionMutex = privateApplicationField<Mutex>(
            application,
            "businessRecoveryExecutionMutex"
        )
        executionMutex.lock()
        var scheduled: Job? = null
        try {
            val viewModel = CatalogSyncViewModel.factory(application)
                .create(CatalogSyncViewModel::class.java)
            val requester = privateApplicationField<(String) -> Unit>(
                viewModel,
                "onRecoveryRequired"
            )

            requester("manual_factory_test")
            scheduled = privateApplicationField(application, "businessRecoveryJob")
            assertNotNull(scheduled)
            assertTrue(requireNotNull(scheduled).isActive)

            application.requestPendingBusinessRecovery("network_test")
            assertSame(
                scheduled,
                privateApplicationField<Job?>(application, "businessRecoveryJob")
            )
        } finally {
            scheduled?.cancel()
            executionMutex.unlock()
            advanceUntilIdle()
            application.catalogSyncStateTracker.updateBusinessDataScopeState(
                Task126BusinessDataScopeState.unmanagedAllowed()
            )
        }
    }

    @Test
    fun `manifest wires MerchandiseControlApplication with singleton repository owner`() {
        val application = RuntimeEnvironment.getApplication()
        assertTrue(application is MerchandiseControlApplication)

        val typedApplication = application as MerchandiseControlApplication
        assertSame(typedApplication.repository, typedApplication.repository)
        assertSame(
            typedApplication.realtimeRefreshCoordinator,
            typedApplication.realtimeRefreshCoordinator
        )
        assertSame(
            typedApplication.realtimeSessionSubscriber,
            typedApplication.realtimeSessionSubscriber
        )
        assertTrue(typedApplication.realtimeRefreshCoordinator.isForeground)
    }

    @Test
    fun `authManager is singleton and auto-disables without config`() {
        val application = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        // Singleton: stessa istanza a ogni accesso.
        assertSame(application.authManager, application.authManager)
        // In test/CI le chiavi sono vuote: il manager si auto-disabilita.
        assertFalse(application.authManager.isEnabled)
        // Senza config, lo stato deve essere SignedOut (non Checking).
        assertTrue(application.authManager.state.value is AuthState.SignedOut)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateApplicationField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }
}

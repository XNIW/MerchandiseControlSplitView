package com.example.merchandisecontrolsplitview

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
        verifier: io.github.jan.supabase.auth.CodeVerifierCache = MemoryCodeVerifierCache()): SupabaseClient =
        createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            httpEngine = OkHttp.create(); coroutineDispatcher = dispatcher; defaultLogLevel = LogLevel.NONE
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false
                sessionManager = storage; codeVerifierCache = verifier }
            install(Postgrest)
        }
    private suspend fun withTraceDatabase(block: suspend (MerchandiseControlApplication, AppDatabase) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val previous = privateApplicationField<Lazy<AppDatabase>>(app, "database\$delegate")
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
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

package com.example.merchandisecontrolsplitview

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.data.*
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import io.mockk.mockk
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Actual Application local-publication consumer + Room; no onCreate/native/cloud acceptance claim. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApplicationLocalIdentityTest {
    @Test
    fun waitingPersistedIdentityPublishesOnlyMatchingStructurallyQualifiedLocalStore() = runTest {
        withOwnedFixture { app, auth, _, tracker, shops, _ ->
            assertTrue(auth.state.value is AuthState.Checking)
            assertNotNull(auth.localIdentity.value)
            publishOffline(app, shops.state.value)
            assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
        }
    }

    @Test
    fun ownerMismatchAndRevocationCannotUseHistoricalBoundScope() = runTest {
        withOwnedFixture { app, auth, controller, tracker, shops, db ->
            val foreign = task126ActiveOwnerStoreScope("20000000-0000-4000-8000-000000000144", shops.state.value.selectedShop)
            db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(foreign, 1L))
            publishOffline(app, shops.state.value)
            assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
            db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(
                task126ActiveOwnerStoreScope(OWNER, shops.state.value.selectedShop), 1L))
            controller.sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
            runCurrent()
            assertNull(auth.localIdentity.value)
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
            publishOffline(app, shops.state.value)
            assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
        }
    }

    @Test
    fun sameOwnerReauthenticationCannotPublishPriorGenerationHeldLocalRead() = runTest {
        withOwnedFixture { app, auth, controller, tracker, shops, db ->
            val before = requireNotNull(auth.localIdentity.value)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) {
                db.withTransaction { entered.complete(Unit); release.await() }
            }
            entered.await()
            val oldPublication = async(start = CoroutineStart.UNDISPATCHED) {
                publishOffline(app, shops.state.value)
            }
            try {
                assertFalse(oldPublication.isCompleted)
                auth.signOut()
                runCurrent()
                controller.sessionStatus.value = SessionStatus.Initializing
                auth.restoreSession()
                advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1L); runCurrent()
                val replacement = requireNotNull(auth.localIdentity.value)
                assertEquals(before.userId, replacement.userId)
                assertNotEquals(before.authGeneration, replacement.authGeneration)
                release.complete(Unit); holding.await(); oldPublication.await()
                assertFalse("A local read captured before same-owner logout/login cannot publish in the new generation",
                    tracker.businessDataScopeState.value.allowsLocalOperations)
                publishOffline(app, shops.state.value)
                assertTrue("A fresh publication under the replacement identity remains usable",
                    tracker.businessDataScopeState.value.allowsLocalOperations)
            } finally {
                release.complete(Unit); holding.cancel(); oldPublication.cancel()
            }
        }
    }

    @Test
    fun persistedDeviceDenialPreventsLocalCapabilityWhileAuthIsWaiting() = runTest {
        withOwnedFixture(denied = true) { app, _, _, tracker, shops, _ ->
            assertFalse(shops.state.value.localAccessAllowed)
            publishOffline(app, shops.state.value)
            assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
        }
    }

    @Test
    fun signedInWithoutLocalIdentityDoesNotWaitForHeldPhysicalStoreOrChangePublishedState() = runTest {
        withOwnedFixture { app, auth, _, tracker, shops, db ->
            authFlow<LocalAuthIdentity?>(auth, "_localIdentity").value = null
            authFlow<AuthState>(auth, "_state").value = AuthState.SignedIn(OWNER, null)
            val before = tracker.businessDataScopeState.value
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) {
                db.withTransaction { entered.complete(Unit); release.await() }
            }
            entered.await()
            val publication = async(start = CoroutineStart.UNDISPATCHED) {
                publishOffline(app, shops.state.value)
            }
            try {
                assertTrue("Pending local identity must return before any held Room proof read", publication.isCompleted)
                publication.await()
                assertEquals(before, tracker.businessDataScopeState.value)
                assertEquals(AuthState.SignedIn(OWNER, null), auth.state.value)
                assertNull(auth.localIdentity.value)
            } finally {
                publication.cancel()
                release.complete(Unit)
                holding.await()
                publication.join()
            }
        }
    }

    @Test
    fun existingAuthObserverRetriesLocalIdentityArrivalBeforeCloudReadCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val cloudStarted = CompletableDeferred<Unit>()
            withOwnedFixture(cloudRead = { cloudStarted.complete(Unit); awaitCancellation() }) {
                    app, auth, _, tracker, _, _ ->
                val identity = requireNotNull(auth.localIdentity.value)
                authFlow<LocalAuthIdentity?>(auth, "_localIdentity").value = null
                authFlow<AuthState>(auth, "_state").value = AuthState.SignedIn(OWNER, null)
                installLazy(app, "realtimeSessionSubscriber", mockk<SupabaseRealtimeSessionSubscriber>(relaxed = true))
                installLazy(app, "realtimeRefreshCoordinator", mockk<RealtimeRefreshCoordinator>(relaxed = true))
                val observerScope = MerchandiseControlApplication::class.java.getDeclaredField("appScope")
                    .apply { isAccessible = true }.get(app) as CoroutineScope
                try {
                    MerchandiseControlApplication::class.java.getDeclaredMethod("observeAuthForRemoteComponents")
                        .apply { isAccessible = true }.invoke(app)
                    runCurrent()
                    assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
                    authFlow<LocalAuthIdentity?>(auth, "_localIdentity").value = identity
                    val published = tracker.businessDataScopeState.first { it.allowsLocalOperations }
                    assertFalse(published.allowsCloudSync)
                    assertEquals(AuthState.SignedIn(OWNER, null), auth.state.value)
                    assertTrue(auth.isCurrentLocalIdentity(identity))
                    cloudStarted.await()
                    assertTrue("Local proof is already usable while cloud reply remains suspended",
                        tracker.businessDataScopeState.value.allowsLocalOperations)
                } finally {
                    observerScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }


    @Test
    fun equivalentOfflineCallsRegisteredDuringHeldRoomTransactionRunOneStructuralProof() = runTest {
        val checks = java.util.concurrent.atomic.AtomicInteger()
        withOwnedFixture(onQuery = { sql ->
            if (sql.trimStart().startsWith("PRAGMA quick_check", ignoreCase = true)) checks.incrementAndGet()
        }) { app, _, _, tracker, shops, db ->
            checks.set(0)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) {
                db.withTransaction { entered.complete(Unit); release.await() }
            }
            entered.await()
            val context = shops.state.value
            val leader = async(start = CoroutineStart.UNDISPATCHED) { publishOffline(app, context) }
            var follower: Deferred<Unit>? = null
            try {
                assertTrue(proofFlights(app).hasOpen())
                val original = requireNotNull(currentProofEntry(app))
                assertTrue(original.accepting); assertFalse(original.completion.isCompleted)
                follower = async(start = CoroutineStart.UNDISPATCHED) { publishOffline(app, context) }
                assertFalse(leader.isCompleted); assertFalse(follower.isCompleted)
                assertSame(original, currentProofEntry(app))
                assertEquals("The held transaction prevents the first physical proof from running", 0, checks.get())
                release.complete(Unit); holding.await(); leader.await(); follower.await()
                assertEquals("Equivalent callers must consume one actual Room quick_check", 1, checks.get())
                assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
                assertFalse(proofFlights(app).hasOpen()); assertNull(currentProofEntry(app))
                assertTrue(original.completion.isCompleted); assertFalse(original.accepting)
            } finally {
                release.complete(Unit)
                holding.cancelAndJoin(); leader.cancelAndJoin(); follower?.cancelAndJoin()
            }
        }
    }

    @Test
    fun completedLocalProofCannotHideDirectDaoBindingTamperBehindTheSameRamAuthority() = runTest {
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val bindingReads = java.util.concurrent.atomic.AtomicInteger()
        withOwnedFixture(onQuery = { sql ->
            if (sql.trimStart().startsWith("PRAGMA quick_check", ignoreCase = true)) checks.incrementAndGet()
            if (sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                sql.contains("business_data_scope_binding", ignoreCase = true)) bindingReads.incrementAndGet()
        }) { app, auth, _, tracker, shops, db ->
            checks.set(0); bindingReads.set(0)
            val context = shops.state.value; val identity = requireNotNull(auth.localIdentity.value)
            publishOffline(app, context)
            assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertEquals(1, checks.get()); assertEquals(1, bindingReads.get())
            assertFalse(proofFlights(app).hasOpen()); assertNull(currentProofEntry(app))
            // The Application's RAM authority/key inputs stay the same; this bypasses repository writers.
            val foreign = task126ActiveOwnerStoreScope("20000000-0000-4000-8000-000000000144", context.selectedShop)
            val expectedBinding = BusinessDataScopeBinding.from(foreign, 1L)
            db.businessDataScopeBindingDao().upsert(expectedBinding)
            assertEquals(context, shops.state.value); assertEquals(identity, auth.localIdentity.value)
            publishOffline(app, context)
            assertEquals("A completed flight must not suppress the fresh binding read", 2, bindingReads.get())
            assertEquals("The fresh mismatched binding is rejected before quick_check", 1, checks.get())
            assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertEquals(Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
                tracker.businessDataScopeState.value.status)
            assertEquals(expectedBinding, db.businessDataScopeBindingDao().get())
        }
    }

    @Test
    fun revocationWhileEquivalentCallersWaitCannotPublishTheEarlierRoomProof() = runTest {
        val checks = java.util.concurrent.atomic.AtomicInteger()
        withOwnedFixture(onQuery = { sql ->
            if (sql.trimStart().startsWith("PRAGMA quick_check", ignoreCase = true)) checks.incrementAndGet()
        }) { app, auth, controller, tracker, shops, db ->
            checks.set(0)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) {
                db.withTransaction { entered.complete(Unit); release.await() }
            }
            entered.await()
            val context = shops.state.value
            val leader = async(start = CoroutineStart.UNDISPATCHED) { publishOffline(app, context) }
            var follower: Deferred<Unit>? = null
            try {
                assertTrue(proofFlights(app).hasOpen())
                val original = requireNotNull(currentProofEntry(app))
                follower = async(start = CoroutineStart.UNDISPATCHED) { publishOffline(app, context) }
                assertFalse(leader.isCompleted); assertFalse(follower.isCompleted)
                assertSame(original, currentProofEntry(app))
                controller.sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
                runCurrent(); assertNull(auth.localIdentity.value)
                assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
                release.complete(Unit); holding.await(); leader.await(); follower.await()
                assertEquals(1, checks.get())
                assertFalse("Neither waiting caller may grant the revoked local identity",
                    tracker.businessDataScopeState.value.allowsLocalOperations)
                assertFalse(proofFlights(app).hasOpen()); assertNull(currentProofEntry(app))
                assertTrue(original.completion.isCompleted)
            } finally {
                release.complete(Unit)
                holding.cancelAndJoin(); leader.cancelAndJoin(); follower?.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingAnEquivalentApplicationFollowerLeavesTheRoomOwnerAndProofAlive() = runTest {
        val checks = java.util.concurrent.atomic.AtomicInteger()
        withOwnedFixture(onQuery = { sql ->
            if (sql.trimStart().startsWith("PRAGMA quick_check", ignoreCase = true)) checks.incrementAndGet()
        }) { app, _, _, tracker, shops, db ->
            checks.set(0)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) {
                db.withTransaction { entered.complete(Unit); release.await() }
            }
            entered.await()
            val context = shops.state.value
            val leader = async(start = CoroutineStart.UNDISPATCHED) { publishOffline(app, context) }
            var follower: Deferred<Unit>? = null
            try {
                assertTrue(proofFlights(app).hasOpen())
                val original = requireNotNull(currentProofEntry(app))
                follower = async(start = CoroutineStart.UNDISPATCHED) { publishOffline(app, context) }
                assertFalse(follower.isCompleted)
                follower.cancelAndJoin()
                assertTrue(follower.isCancelled); assertFalse(leader.isCompleted)
                assertFalse(original.completion.isCancelled); assertFalse(original.completion.isCompleted)
                assertSame(original, currentProofEntry(app))
                release.complete(Unit); holding.await(); leader.await()
                assertEquals(1, checks.get()); assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
                assertTrue(original.completion.isCompleted); assertFalse(proofFlights(app).hasOpen())
            } finally {
                release.complete(Unit)
                holding.cancelAndJoin(); leader.cancelAndJoin(); follower?.cancelAndJoin()
            }
        }
    }

    @Test
    fun proofKeyFactoryCannotRelabelCapturedIdentityAAsSameOwnerRestoredGenerationB() = runTest {
        withOwnedFixture { app, auth, controller, _, shops, _ ->
            val capturedAuth = auth.state.value
            val capturedIdentity = requireNotNull(auth.localIdentity.value)
            val capturedContext = shops.state.value
            val capturedEpoch = shops.diagnosticShopEpoch()
            val capturedDevice = requireNotNull(app.deviceInstallIdProvider.currentId)
            val factory = MerchandiseControlApplication::class.java.getDeclaredMethod(
                "capturedOfflineLocalProofKey", AuthState::class.java, LocalAuthIdentity::class.java,
                ShopContext::class.java, java.lang.Long.TYPE, String::class.java
            ).apply { isAccessible = true }
            assertNotNull(factory.invoke(app, capturedAuth, capturedIdentity, capturedContext,
                capturedEpoch, capturedDevice))
            auth.signOut(); runCurrent()
            controller.sessionStatus.value = SessionStatus.Initializing
            auth.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1L); runCurrent()
            val replacement = requireNotNull(auth.localIdentity.value)
            assertEquals(capturedIdentity.userId, replacement.userId)
            assertNotEquals(capturedIdentity.authGeneration, replacement.authGeneration)
            assertNull("A-captured proof inputs cannot acquire a freshly observed B key",
                factory.invoke(app, capturedAuth, capturedIdentity, capturedContext, capturedEpoch, capturedDevice))
            assertNotNull("A proof freshly captured under current B retains a valid key",
                factory.invoke(app, auth.state.value, replacement, shops.state.value,
                    shops.diagnosticShopEpoch(), requireNotNull(app.deviceInstallIdProvider.currentId)))
        }
    }

    private fun proofFlights(app: MerchandiseControlApplication): LocalStoreProofFlights<*> =
        MerchandiseControlApplication::class.java.getDeclaredField("localProofFlights")
            .apply { isAccessible = true }.get(app) as LocalStoreProofFlights<*>

    private fun currentProofEntry(app: MerchandiseControlApplication): LocalStoreProofFlights.Entry<*>? {
        val flights = proofFlights(app)
        val lock = requireNotNull(LocalStoreProofFlights::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(flights))
        return synchronized(lock) {
            LocalStoreProofFlights::class.java.getDeclaredField("currentEntry")
                .apply { isAccessible = true }.get(flights) as? LocalStoreProofFlights.Entry<*>
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> authFlow(auth: SupabaseAuthManager, name: String): MutableStateFlow<T> =
        SupabaseAuthManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(auth) as MutableStateFlow<T>

    private fun installLazy(app: MerchandiseControlApplication, name: String, value: Any) =
        MerchandiseControlApplication::class.java.getDeclaredField(name + "\$delegate")
            .apply { isAccessible = true }.set(app, lazyOf(value))

    private suspend fun TestScope.withOwnedFixture(denied: Boolean = false,
        cloudRead: suspend () -> Result<List<LinkedShop>> = { error("No cloud read permitted") },
        onQuery: ((String) -> Unit)? = null,
        block: suspend (MerchandiseControlApplication, SupabaseAuthManager, WaitingIdentityController,
            CatalogSyncStateTracker, ShopContextRepository, AppDatabase) -> Unit) {
        val app = MerchandiseControlApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(app, RuntimeEnvironment.getApplication())
        val builder = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
        if (onQuery != null) builder.setQueryCallback({ sql, _ -> onQuery(sql) },
            java.util.concurrent.Executor { it.run() })
        val db = builder.build()
        val controller = WaitingIdentityController()
        val ownedScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val auth = SupabaseAuthManager.createForTest(controller, ownedScope)
        val selected = SelectedShop(SHOP, null, "Owned shop", "owner", "active", true)
        val linked = LinkedShop(SHOP, null, "Owned shop", "owner", "active", selectable = true, canWrite = true)
        val confirmed = ShopContext(OWNER, listOf(linked), selected)
        val stores = object : SelectedShopStore {
            override fun getSelectedShopId(ownerUserId: String) = SHOP
            override fun setSelectedShopId(ownerUserId: String, shopId: String) = Unit
            override fun clearSelectedShopId(ownerUserId: String) = Unit
            override fun getConfirmedContext(ownerUserId: String) = confirmed
            override fun isDeviceDenied(ownerUserId: String, shopId: String?, deviceId: String) = denied
        }
        val devices = DeviceInstallIdProvider(db.syncEventDeviceStateDao())
        val shops = ShopContextRepository(object : LinkedShopRemoteDataSource {
            override val isConfigured = true
            override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> = cloudRead()
        }, stores, currentOwnerUserId = { auth.localIdentity.value?.takeIf(auth::isCurrentLocalIdentity)?.userId },
            currentDeviceIdentifier = { devices.currentId })
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.checking())
        val repository = DefaultInventoryRepository(db, tracker)
        fun install(name: String, value: Any) = MerchandiseControlApplication::class.java
            .getDeclaredField(name + "\$delegate").apply { isAccessible = true }.set(app, lazyOf(value))
        try {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 1L))
            devices.getOrCreate()
            db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(task126ActiveOwnerStoreScope(OWNER, selected), 1L))
            db.productDao().insert(Product(barcode = "owned-local", productName = "Owned local row"))
            auth.restoreSession()
            advanceTimeBy(SupabaseAuthManager.RESTORE_TIMEOUT_MS + 1L); runCurrent()
            shops.restoreLocalAuthorization(OWNER)
            install("database", db); install("repository", repository); install("authManager", auth)
            install("deviceInstallIdProvider", devices); install("shopContextRepository", shops)
            install("catalogSyncStateTracker", tracker)
            block(app, auth, controller, tracker, shops, db)
        } finally {
            auth.shutdown(); ownedScope.cancel(); db.close()
            val scope = MerchandiseControlApplication::class.java.getDeclaredField("appScope")
                .apply { isAccessible = true }.get(app) as CoroutineScope
            scope.cancel()
        }
    }

    private suspend fun publishOffline(app: MerchandiseControlApplication, context: ShopContext): Unit =
        suspendCoroutine { continuation ->
            try {
                val method = MerchandiseControlApplication::class.java.getDeclaredMethod(
                    "publishOfflineBusinessScope", ShopContext::class.java, Continuation::class.java)
                    .apply { isAccessible = true }
                if (method.invoke(app, context, continuation) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (error: java.lang.reflect.InvocationTargetException) {
                continuation.resumeWithException(error.cause ?: error)
            }
        }

    private class WaitingIdentityController : SupabaseAuthSessionController {
        override val sessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
        override fun currentUserOrNull(): SupabaseSessionUser? = null
        override suspend fun persistedUserOrNull() = SupabaseSessionUser(OWNER, null)
        override suspend fun refreshStoredSession() = StoredSessionRefreshResult.Deferred
        override suspend fun clearSession() { sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true) }
        override suspend fun signInWithGoogleIdToken(idToken: String) = Unit
        override suspend fun importWeChatSession(accessToken: String, refreshToken: String) = Unit
        override suspend fun signOut(scope: SignOutScope) { clearSession() }
    }

    companion object {
        private const val OWNER = "10000000-0000-4000-8000-000000000144"
        private const val SHOP = "30000000-0000-4000-8000-000000000144"
        private const val DEVICE = "40000000-0000-4000-8000-000000000144"
    }
}

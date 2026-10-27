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

    @Suppress("UNCHECKED_CAST")
    private fun <T> authFlow(auth: SupabaseAuthManager, name: String): MutableStateFlow<T> =
        SupabaseAuthManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(auth) as MutableStateFlow<T>

    private fun installLazy(app: MerchandiseControlApplication, name: String, value: Any) =
        MerchandiseControlApplication::class.java.getDeclaredField(name + "\$delegate")
            .apply { isAccessible = true }.set(app, lazyOf(value))

    private suspend fun TestScope.withOwnedFixture(denied: Boolean = false,
        cloudRead: suspend () -> Result<List<LinkedShop>> = { error("No cloud read permitted") },
        block: suspend (MerchandiseControlApplication, SupabaseAuthManager, WaitingIdentityController,
            CatalogSyncStateTracker, ShopContextRepository, AppDatabase) -> Unit) {
        val app = MerchandiseControlApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(app, RuntimeEnvironment.getApplication())
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
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

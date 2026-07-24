package com.example.merchandisecontrolsplitview

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

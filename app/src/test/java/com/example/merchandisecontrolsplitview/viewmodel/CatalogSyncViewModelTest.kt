package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogAutoSyncRepository
import com.example.merchandisecontrolsplitview.data.CatalogIncrementalRemoteContract044A
import com.example.merchandisecontrolsplitview.data.CatalogCloudPendingBreakdown
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressRepository
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressState
import com.example.merchandisecontrolsplitview.data.CatalogSyncStage
import com.example.merchandisecontrolsplitview.data.CatalogSyncStateTracker
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
import com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch
import com.example.merchandisecontrolsplitview.data.HistorySessionBackupPushSummary
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryCatalogFetchBundle
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.RemoteSessionBatchResult
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SharedSheetSessionRecord
import com.example.merchandisecontrolsplitview.data.SharedSheetSessionUpsertRow
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `021 fresh signed-in state waits for first manual refresh instead of claiming synced`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )

        val collectJob = launch { viewModel.uiState.collect {} }
        viewModel.onOptionsScreenVisible()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_pending),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(viewModel.uiState.value.canRefresh)
        coVerify(exactly = 1) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }

        collectJob.cancel()
    }

    @Test
    fun `021 fresh signed-in manual refresh bootstraps catalog and then reports synced`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1,
                pulledProductPrices = 1
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_synced),
            viewModel.uiState.value.primaryMessage
        )
        coVerify(exactly = 1) {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        }
        coVerify(exactly = 2) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }
        coVerify(exactly = 1) {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        }
        coVerify(exactly = 1) {
            repository.hasCatalogCloudPendingWorkInclusive()
        }
        coVerify(exactly = 1) {
            repository.getCatalogCloudPendingBreakdown()
        }

        collectJob.cancel()
    }

    @Test
    fun `024 manual refresh attempts session bootstrap and push even when catalog fails`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.failure(IOException("catalog unavailable"))
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(1, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(1, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_offline),
            viewModel.uiState.value.primaryMessage
        )
        assertEquals(
            app.getString(R.string.catalog_cloud_sessions_sync_hint, 1, 1),
            viewModel.uiState.value.sessionDetail
        )
        assertEquals(null, viewModel.uiState.value.catalogDetail)
        coVerify(exactly = 1) {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        }
        coVerify(exactly = 2) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }
        coVerify(exactly = 1) {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        }

        collectJob.cancel()
    }

    @Test
    fun `036 manual refresh clears session detail inherited from automatic bootstrap`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(1, 0, 0, 0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val sessionRemote = ViewModelSessionRemote024(configured = true)
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = sessionRemote,
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_sessions_sync_hint, 1, 0),
            viewModel.uiState.value.sessionDetail
        )

        sessionRemote.configured = false
        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_synced),
            viewModel.uiState.value.primaryMessage
        )
        assertNull(viewModel.uiState.value.sessionDetail)
        coVerify(exactly = 1) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }
        coVerify(exactly = 0) {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        }

        collectJob.cancel()
    }

    @Test
    fun `036 catalog failure does not reuse session detail from previous manual refresh`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returnsMany listOf(
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 0,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = 0
                )
            ),
            Result.failure(IOException("catalog unavailable"))
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(1, 0, 0, 0, 0))
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(1, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val sessionRemote = ViewModelSessionRemote024(configured = false)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = sessionRemote,
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        auth.value = AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        advanceUntilIdle()

        sessionRemote.configured = true
        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_synced),
            viewModel.uiState.value.primaryMessage
        )
        assertEquals(
            app.getString(R.string.catalog_cloud_sessions_sync_hint, 1, 1),
            viewModel.uiState.value.sessionDetail
        )

        sessionRemote.configured = false
        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_offline),
            viewModel.uiState.value.primaryMessage
        )
        assertNull(viewModel.uiState.value.catalogDetail)
        assertNull(viewModel.uiState.value.sessionDetail)
        coVerify(exactly = 1) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }
        coVerify(exactly = 1) {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        }

        collectJob.cancel()
    }

    @Test
    fun `031 catalog failure with reliable forbidden status uses permissions copy`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.failure(statusException(HttpStatusCode.Forbidden))
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_forbidden),
            viewModel.uiState.value.primaryMessage
        )
        assertNull(viewModel.uiState.value.catalogDetail)
        assertNull(viewModel.uiState.value.sessionDetail)

        collectJob.cancel()
    }

    @Test
    fun `031 catalog ok with price issue keeps price partial success state`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1,
                priceSyncFailed = true
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_prices_incomplete),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(viewModel.uiState.value.catalogDetail?.isNotBlank() == true)
        assertNull(viewModel.uiState.value.sessionDetail)

        collectJob.cancel()
    }

    @Test
    fun `031 catalog ok with session issue keeps session partial success state`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.failure(IOException("session push"))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_sessions_incomplete),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(
            viewModel.uiState.value.sessionDetail?.contains(
                app.getString(R.string.catalog_cloud_sessions_issue_hint, 1)
            ) == true
        )

        collectJob.cancel()
    }

    @Test
    fun `040 catalog failure keeps session permission and pending detail visible`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.failure(statusException(HttpStatusCode.Conflict))
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.failure(statusException(HttpStatusCode.Forbidden))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(40L)
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_last_failed),
            viewModel.uiState.value.primaryMessage
        )
        val sessionDetail = viewModel.uiState.value.sessionDetail.orEmpty()
        assertTrue(sessionDetail.contains(app.getString(R.string.catalog_cloud_sessions_permission_hint)))
        assertTrue(sessionDetail.contains(app.getString(R.string.catalog_cloud_sessions_pending_hint, 1)))

        collectJob.cancel()
    }

    @Test
    fun `040 options visible surfaces pending history sessions`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(40L)
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onOptionsScreenVisible()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_pending),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(
            viewModel.uiState.value.sessionDetail?.contains(
                app.getString(R.string.catalog_cloud_sessions_pending_hint, 1)
            ) == true
        )

        collectJob.cancel()
    }

    @Test
    fun `031 catalog ok with price and session issue prioritizes session partial state`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1,
                priceSyncFailed = true
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.failure(IOException("session push"))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "fresh@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_sessions_incomplete),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(viewModel.uiState.value.catalogDetail?.isNotBlank() == true)
        assertTrue(viewModel.uiState.value.sessionDetail?.isNotBlank() == true)

        collectJob.cancel()
    }

    @Test
    fun `026 skipped remote price rows surface in catalogDetail`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1,
                skippedProductPricesPullNoProductRef = 2
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "u@t.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        val detail = viewModel.uiState.value.catalogDetail
        assertTrue(
            detail?.contains(app.getString(R.string.catalog_cloud_prices_skipped_hint, 2)) == true
        )
        assertNull(viewModel.uiState.value.sessionDetail)

        collectJob.cancel()
    }

    @Test
    fun `039 refresh loads pending breakdown for structured log after sync`() = runTest {
        val repository = mockk<InventoryRepository>()
        val breakdown = CatalogCloudPendingBreakdown(
            pendingCatalogTombstones = 2,
            productPricesPendingPriceBridge = 5,
            productPricesBlockedWithoutProductRemote = 1,
            suppliersMissingRemoteRef = 0,
            categoriesMissingRemoteRef = 1,
            productsMissingRemoteRef = 0
        )
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns breakdown
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "039@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getCatalogCloudPendingBreakdown() }

        collectJob.cancel()
    }

    @Test
    fun `039 refresh guard paths do not load pending breakdown`() = runTest {
        val signedOutRepository = mockk<InventoryRepository>()
        val signedOutViewModel = CatalogSyncViewModel(
            application = app,
            repository = signedOutRepository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow<AuthState>(AuthState.SignedOut)
        )
        val signedOutCollectJob = launch { signedOutViewModel.uiState.collect {} }
        advanceUntilIdle()

        signedOutViewModel.refreshCatalog()
        advanceUntilIdle()

        coVerify(exactly = 0) { signedOutRepository.getCatalogCloudPendingBreakdown() }
        signedOutCollectJob.cancel()

        val remoteOffRepository = mockk<InventoryRepository>()
        val remoteOffViewModel = CatalogSyncViewModel(
            application = app,
            repository = remoteOffRepository,
            remote = ViewModelCatalogRemote021(
                bundle = bootstrapBundleVm021(OWNER_VM_021),
                configured = false
            ),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow<AuthState>(
                AuthState.SignedIn(userId = OWNER_VM_021, email = "039@example.test")
            )
        )
        val remoteOffCollectJob = launch { remoteOffViewModel.uiState.collect {} }
        advanceUntilIdle()

        remoteOffViewModel.refreshCatalog()
        advanceUntilIdle()

        coVerify(exactly = 0) { remoteOffRepository.getCatalogCloudPendingBreakdown() }
        remoteOffCollectJob.cancel()

        val busyRepository = mockk<InventoryRepository>()
        val bootstrapStarted = CompletableDeferred<Unit>()
        val bootstrapGate = CompletableDeferred<Result<RemoteSessionBatchResult>>()
        coEvery {
            busyRepository.bootstrapHistorySessionsFromRemote(any())
        } coAnswers {
            bootstrapStarted.complete(Unit)
            bootstrapGate.await()
        }
        val busyViewModel = CatalogSyncViewModel(
            application = app,
            repository = busyRepository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = true),
            authFlow = MutableStateFlow<AuthState>(
                AuthState.SignedIn(userId = OWNER_VM_021, email = "039@example.test")
            )
        )
        val busyCollectJob = launch { busyViewModel.uiState.collect {} }
        bootstrapStarted.await()

        busyViewModel.refreshCatalog()
        advanceUntilIdle()

        coVerify(exactly = 0) { busyRepository.getCatalogCloudPendingBreakdown() }
        bootstrapGate.complete(Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)))
        advanceUntilIdle()
        busyCollectJob.cancel()
    }

    @Test
    fun `039 pending breakdown failure keeps refresh result unchanged`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } throws IOException("breakdown")
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "039@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_synced),
            viewModel.uiState.value.primaryMessage
        )
        assertNull(viewModel.uiState.value.sessionDetail)
        coVerify(exactly = 1) { repository.getCatalogCloudPendingBreakdown() }

        collectJob.cancel()
    }

    @Test
    fun `041 manual refresh exposes structured phase and returns tracker to completed`() = runTest {
        val repository = mockk<InventoryRepository>(
            moreInterfaces = arrayOf(CatalogSyncProgressRepository::class)
        )
        val progressRepository = repository as CatalogSyncProgressRepository
        val progressSeen = CompletableDeferred<Unit>()
        val finishGate = CompletableDeferred<Unit>()
        coEvery {
            progressRepository.syncCatalogWithRemote(any(), any(), OWNER_VM_021, any())
        } coAnswers {
            val reporter = arg<CatalogSyncProgressReporter>(3)
            reporter.onProgress(
                CatalogSyncProgressState.running(
                    CatalogSyncStage.PUSH_PRODUCTS,
                    current = 123,
                    total = 18_854
                )
            )
            progressSeen.complete(Unit)
            finishGate.await()
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 1,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = 0
                )
            )
        }
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val tracker = CatalogSyncStateTracker()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "041@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            syncStateTracker = tracker
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        progressSeen.await()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSyncing)
        assertEquals(CatalogSyncStage.PUSH_PRODUCTS, viewModel.uiState.value.progress?.stage)
        assertEquals(
            app.getString(R.string.catalog_cloud_stage_push_products_count, 123, 18_854),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(tracker.state.value.isBusy)
        assertEquals(CatalogSyncStage.PUSH_PRODUCTS, tracker.state.value.stage)

        finishGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(CatalogSyncStage.COMPLETED, tracker.state.value.stage)
        assertFalse(tracker.state.value.isBusy)
        assertEquals(
            app.getString(R.string.catalog_cloud_state_synced),
            viewModel.uiState.value.primaryMessage
        )

        collectJob.cancel()
    }

    @Test
    fun `043 quick sync uses targeted catalog delta lane without full refresh`() = runTest {
        val repository = mockk<InventoryRepository>()
        val autoRepository = mockk<CatalogAutoSyncRepository>()
        coEvery {
            autoRepository.pushDirtyCatalogDeltaToRemote(any(), any(), OWNER_VM_021, any())
        } coAnswers {
            val reporter = arg<CatalogSyncProgressReporter>(3)
            reporter.onProgress(
                CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS, current = 1, total = 1)
            )
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 1,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = 0,
                    pushedProductPrices = 1,
                    pulledProductPrices = 0,
                    fullCatalogFetch = false,
                    fullPriceFetch = false,
                    incrementalRemoteSubsetVerifiable = false,
                    incrementalRemoteNotVerifiableReason =
                        CatalogIncrementalRemoteContract044A.INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES
                )
            )
        }
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "043@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            autoSyncRepository = autoRepository
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.syncCatalogQuick()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            autoRepository.pushDirtyCatalogDeltaToRemote(any(), any(), OWNER_VM_021, any())
        }
        coVerify(exactly = 0) {
            repository.syncCatalogWithRemote(any(), any(), any())
        }
        assertTrue(viewModel.uiState.value.canQuickSync)
        assertTrue(viewModel.uiState.value.catalogDetail?.isNotBlank() == true)
        assertTrue(
            viewModel.uiState.value.catalogDetail!!.contains(
                app.getString(R.string.catalog_cloud_quick_sync_locals_sent, 2)
            )
        )
        assertTrue(
            viewModel.uiState.value.catalogDetail!!.contains(
                app.getString(R.string.catalog_cloud_remote_incremental_not_verifiable_hint)
            )
        )

        collectJob.cancel()
    }

    @Test
    fun `044B full refresh clears quick-only remote not verifiable line from catalogDetail`() = runTest {
        val repository = mockk<InventoryRepository>()
        val autoRepository = mockk<CatalogAutoSyncRepository>()
        coEvery {
            autoRepository.pushDirtyCatalogDeltaToRemote(any(), any(), OWNER_VM_021, any())
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 1,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                pushedProductPrices = 0,
                pulledProductPrices = 0,
                fullCatalogFetch = false,
                fullPriceFetch = false,
                incrementalRemoteSubsetVerifiable = false,
                incrementalRemoteNotVerifiableReason =
                    CatalogIncrementalRemoteContract044A.INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES
            )
        )
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1,
                pushedProductPrices = 0,
                pulledProductPrices = 1,
                fullCatalogFetch = true,
                fullPriceFetch = true,
                incrementalRemoteSubsetVerifiable = false,
                incrementalRemoteNotVerifiableReason =
                    CatalogIncrementalRemoteContract044A.INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES
            )
        )
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), OWNER_VM_021)
        } returns Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "044b@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            autoSyncRepository = autoRepository
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val notVerifiable = app.getString(R.string.catalog_cloud_remote_incremental_not_verifiable_hint)
        viewModel.syncCatalogQuick()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.catalogDetail?.contains(notVerifiable) == true)

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.catalogDetail?.contains(notVerifiable) == true)

        collectJob.cancel()
    }

    @Test
    fun `044B second quick sync while first is in flight is ignored`() = runTest {
        val repository = mockk<InventoryRepository>()
        val autoRepository = mockk<CatalogAutoSyncRepository>()
        val gate = CompletableDeferred<Unit>()
        coEvery {
            autoRepository.pushDirtyCatalogDeltaToRemote(any(), any(), OWNER_VM_021, any())
        } coAnswers {
            gate.await()
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 1,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = 0,
                    fullCatalogFetch = false,
                    fullPriceFetch = false
                )
            )
        }
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "044b2@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            autoSyncRepository = autoRepository
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.syncCatalogQuick()
        advanceUntilIdle()
        viewModel.syncCatalogQuick()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            autoRepository.pushDirtyCatalogDeltaToRemote(any(), any(), OWNER_VM_021, any())
        }

        gate.complete(Unit)
        advanceUntilIdle()

        collectJob.cancel()
    }
}

private fun emptyViewModelPendingBreakdown(): CatalogCloudPendingBreakdown =
    CatalogCloudPendingBreakdown(
        pendingCatalogTombstones = 0,
        productPricesPendingPriceBridge = 0,
        productPricesBlockedWithoutProductRemote = 0
    )

private class ViewModelCatalogRemote021(
    private val bundle: InventoryCatalogFetchBundle,
    private val configured: Boolean = true
) : CatalogRemoteDataSource {
    override val isConfigured: Boolean get() = configured

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        Result.success(bundle)

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> =
        Result.success(
            InventoryCatalogFetchBundle(
                suppliers = bundle.suppliers.filter { it.id in supplierIds },
                categories = bundle.categories.filter { it.id in categoryIds },
                products = bundle.products.filter { it.id in productIds }
            )
        )

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)
}

private class ViewModelPriceRemote021(
    private val fetchRows: List<InventoryProductPriceRow> = emptyList()
) : ProductPriceRemoteDataSource {
    override val isConfigured: Boolean get() = true

    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
        Result.success(fetchRows)

    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
        Result.success(fetchRows.filter { it.id in remoteIds })
}

private class ViewModelSessionRemote024(
    var configured: Boolean = true
) : SessionBackupRemoteDataSource {
    override val isConfigured: Boolean get() = configured

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        Result.success(emptyList())

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        Result.success(Unit)
}

private fun statusException(status: HttpStatusCode): ClientRequestException {
    val response = mockk<HttpResponse>()
    val call = mockk<HttpClientCall>()
    val request = mockk<HttpRequest>()
    every { response.status } returns status
    every { response.call } returns call
    every { call.request } returns request
    every { request.url } returns Url("https://example.test")
    every { request.method } returns HttpMethod.Get
    return ClientRequestException(response, status.description)
}

private const val OWNER_VM_021 = "00000000-0000-4000-8000-000000000240"
private const val BOOTSTRAP_SUPPLIER_REMOTE_VM_021 = "00000000-0000-4000-8000-000000000241"
private const val BOOTSTRAP_CATEGORY_REMOTE_VM_021 = "00000000-0000-4000-8000-000000000242"
private const val BOOTSTRAP_PRODUCT_REMOTE_VM_021 = "00000000-0000-4000-8000-000000000243"
private const val BOOTSTRAP_BARCODE_VM_021 = "bootstrap-vm-021"

private fun bootstrapBundleVm021(owner: String): InventoryCatalogFetchBundle =
    InventoryCatalogFetchBundle(
        suppliers = listOf(
            InventorySupplierRow(
                id = BOOTSTRAP_SUPPLIER_REMOTE_VM_021,
                ownerUserId = owner,
                name = "Bootstrap VM Supplier 021"
            )
        ),
        categories = listOf(
            InventoryCategoryRow(
                id = BOOTSTRAP_CATEGORY_REMOTE_VM_021,
                ownerUserId = owner,
                name = "Bootstrap VM Category 021"
            )
        ),
        products = listOf(
            InventoryProductRow(
                id = BOOTSTRAP_PRODUCT_REMOTE_VM_021,
                ownerUserId = owner,
                barcode = BOOTSTRAP_BARCODE_VM_021,
                productName = "Bootstrap VM Product 021",
                supplierId = BOOTSTRAP_SUPPLIER_REMOTE_VM_021,
                categoryId = BOOTSTRAP_CATEGORY_REMOTE_VM_021,
                purchasePrice = 21.0,
                retailPrice = 31.0,
                stockQuantity = 3.0
            )
        )
    )

package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            viewModel.uiState.value.secondaryMessage
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

        collectJob.cancel()
    }
}

private class ViewModelCatalogRemote021(
    private val bundle: InventoryCatalogFetchBundle
) : CatalogRemoteDataSource {
    override val isConfigured: Boolean get() = true

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        Result.success(bundle)

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
}

private class ViewModelSessionRemote024 : SessionBackupRemoteDataSource {
    override val isConfigured: Boolean get() = true

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        Result.success(emptyList())

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        Result.success(Unit)
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

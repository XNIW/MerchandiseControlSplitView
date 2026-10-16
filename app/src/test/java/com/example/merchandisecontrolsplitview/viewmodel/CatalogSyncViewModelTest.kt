package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.ViewModelStore
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CanonicalCatalogInboundRequired
import com.example.merchandisecontrolsplitview.data.CatalogAutoSyncRepository
import com.example.merchandisecontrolsplitview.data.CatalogIncrementalRemoteContract044A
import com.example.merchandisecontrolsplitview.data.CatalogCloudPendingBreakdown
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncFlightOwner
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
import com.example.merchandisecontrolsplitview.data.LocalDatabaseStatusSnapshot
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.RemoteSessionBatchResult
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SelectedShop
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationRepository
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationSnapshot
import com.example.merchandisecontrolsplitview.data.ShopDeviceRegistrationRemote
import com.example.merchandisecontrolsplitview.data.ShopDeviceRegistrationResult
import com.example.merchandisecontrolsplitview.data.SharedSheetSessionRecord
import com.example.merchandisecontrolsplitview.data.SharedSheetSessionUpsertRow
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeChangedException
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import com.example.merchandisecontrolsplitview.data.task126ActiveOwnerStoreScope
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
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
@Config(application = Application::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        // All runtime collaborators are injected; process auth/coordinators belong to Application tests.
        assertEquals(Application::class.java, app.javaClass)
    }


    @Test
    fun offlineQualifiedCheckingShowsOfflineWithoutClaimingDatabaseWorkOrCloudAuthority() = runTest {
        withOfflineOptionsFixture(optionsSnapshot(1)) { f ->
            assertTrue(f.tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(f.tracker.businessDataScopeState.value.allowsCloudSync)
            val original = f.tracker.businessDataScopeState.value
            assertEquals(app.getString(R.string.catalog_cloud_state_offline), f.viewModel.uiState.value.primaryMessage)
            assertEquals(1, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            assertTrue(f.viewModel.localDatabaseStatusUi.value.localAccessAvailable)
            assertTrue(f.viewModel.localDatabaseStatusUi.value.showBusinessDataDetails)
            assertEquals(R.string.local_database_status_ready,
                com.example.merchandisecontrolsplitview.ui.screens.localDatabaseStatusSubtitleRes(
                    f.viewModel.localDatabaseStatusUi.value))
            assertFalse(f.viewModel.uiState.value.isSyncing)
            assertFalse(f.viewModel.uiState.value.canRefresh)
            assertFalse(f.viewModel.uiState.value.canQuickSync)
            assertFalse(f.viewModel.uiState.value.showAutomaticSyncDetail)
            assertNull(f.viewModel.localDatabaseStatusUi.value.lastSyncText)
            assertNull(f.tracker.lastQualifiedOutcome.value)
            assertEquals(original, f.tracker.businessDataScopeState.value)
        }
    }

    @Test
    fun offlineQualifiedSnapshotRefreshShowsNewPendingCountInTheSameScope() = runTest {
        withOfflineOptionsFixture(optionsSnapshot(0)) { f ->
            assertEquals(0, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            val original = f.tracker.businessDataScopeState.value
            f.nextSnapshot = optionsSnapshot(1)
            f.viewModel.onOptionsScreenVisible()
            advanceUntilIdle()
            assertEquals(1, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            assertTrue(f.viewModel.localDatabaseStatusUi.value.hasPendingLocalChanges)
            assertEquals(original, f.tracker.businessDataScopeState.value)
            assertFalse(f.tracker.businessDataScopeState.value.allowsCloudSync)
            assertNull(f.viewModel.localDatabaseStatusUi.value.lastSyncText)
        }
    }

    @Test
    fun offlineOlderSameScopeCountReadCannotReplaceTheNewerRefresh() = runTest {
        withOfflineOptionsFixture(null) { f ->
            f.nextSnapshot = optionsSnapshot(99)
            f.holdNextRead = true
            f.viewModel.onOptionsScreenVisible()
            advanceUntilIdle()
            assertTrue(f.readEntered.isCompleted)
            f.nextSnapshot = optionsSnapshot(1)
            f.viewModel.onOptionsScreenVisible()
            advanceUntilIdle()
            assertEquals(1, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            f.releaseRead.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
        }
    }

    @Test
    fun offlineCountReadCannotPublishAcrossAnActualLocalPermissionGenerationAba() = runTest {
        withOfflineOptionsFixture(null) { f ->
            val original = f.tracker.businessDataScopeState.value
            f.nextSnapshot = optionsSnapshot(99)
            f.holdNextRead = true
            f.viewModel.onOptionsScreenVisible()
            advanceUntilIdle()
            assertTrue(f.readEntered.isCompleted)
            // Both synchronous calls hit the existing gate's updateState. Its
            // localGeneration changes twice, while StateFlow consumers can see
            // only the identical final object. A UI key/requestId is insufficient.
            f.nextSnapshot = optionsSnapshot(1)
            f.tracker.updateBusinessDataScopeState(original.copy(localReadsAllowed = false))
            f.tracker.updateBusinessDataScopeState(original)
            assertEquals(original, f.tracker.businessDataScopeState.value)
            f.releaseRead.complete(Unit)
            advanceUntilIdle()
            assertFalse("A read from the revoked local generation must never be published",
                f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount == 99)
            assertEquals(original, f.tracker.businessDataScopeState.value)
            assertFalse(f.tracker.businessDataScopeState.value.allowsCloudSync)
        }
    }

    @Test
    fun offlineOldShopCountReadCannotReplaceTheCurrentShopSnapshot() = runTest {
        withOfflineOptionsFixture(null) { f ->
            f.nextSnapshot = optionsSnapshot(99)
            f.holdNextRead = true
            f.viewModel.onOptionsScreenVisible()
            advanceUntilIdle()
            assertTrue(f.readEntered.isCompleted)
            val secondShop = dateOutcomeShop("00000000-0000-4000-8000-000000000247")
            val secondScope = task126ActiveOwnerStoreScope(OWNER_VM_021, secondShop)
            f.nextSnapshot = optionsSnapshot(7)
            f.shops.value = ShopContext(OWNER_VM_021, emptyList(), secondShop,
                syncAllowed = false, localAccessAllowed = true)
            f.tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState(
                Task126BusinessDataScopeStatus.CHECKING, boundScope = secondScope,
                localAccessScope = secondScope))
            advanceUntilIdle()
            assertEquals(7, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            f.releaseRead.complete(Unit)
            advanceUntilIdle()
            assertEquals(7, f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            assertFalse(f.viewModel.uiState.value.canRefresh)
            assertNull(f.viewModel.localDatabaseStatusUi.value.lastSyncText)
        }
    }

    @Test
    fun offlineUnqualifiedOrRevokedScopeNeverExposesProtectedCounts() = runTest {
        withOfflineOptionsFixture(optionsSnapshot(99)) { f ->
            val original = f.tracker.businessDataScopeState.value
            val foreign = task126ActiveOwnerStoreScope("00000000-0000-4000-8000-000000000249", f.shops.value.selectedShop)
            val denied = listOf(
                original.copy(localAccessScope = null),
                original.copy(localAccessScope = foreign),
                original.copy(localReadsAllowed = false),
                original.copy(status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH),
                original.copy(status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH),
                original.copy(status = Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH)
            )
            for (state in denied) {
                assertFalse(state.allowsLocalOperations)
                f.tracker.updateBusinessDataScopeState(state)
                advanceUntilIdle()
                assertNull("Denied local access must hide protected counts: $state",
                    f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
                assertFalse(f.viewModel.localDatabaseStatusUi.value.localAccessAvailable)
                assertFalse(f.viewModel.localDatabaseStatusUi.value.showBusinessDataDetails)
                assertFalse(f.viewModel.uiState.value.canRefresh)
                assertFalse(f.viewModel.uiState.value.canQuickSync)
                assertFalse(f.viewModel.uiState.value.isSyncing)
            }
            f.tracker.updateBusinessDataScopeState(original)
            f.auth.value = AuthState.SignedOut
            advanceUntilIdle()
            assertNull(f.viewModel.localDatabaseStatusUi.value.pendingLocalChangesCount)
            assertEquals(app.getString(R.string.catalog_cloud_state_sign_in_required),
                f.viewModel.uiState.value.primaryMessage)
            assertFalse(f.viewModel.uiState.value.canRefresh)
        }
    }

    @Test
    fun offlineOptionsPendingHintCannotPublishAfterLocalGenerationWasRevoked() = runTest {
        withOfflineOptionsFixture(optionsSnapshot(0)) { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.hasCatalogCloudPendingWorkInclusive() } coAnswers {
                entered.complete(Unit)
                release.await()
                true
            }
            try {
                f.viewModel.onOptionsScreenVisible()
                advanceUntilIdle()
                assertTrue(entered.isCompleted)
                val original = f.tracker.businessDataScopeState.value
                f.tracker.updateBusinessDataScopeState(original.copy(localReadsAllowed = false))
                f.tracker.updateBusinessDataScopeState(original)
                release.complete(Unit)
                advanceUntilIdle()
                assertEquals(false, optionsStateField(f.viewModel, "pendingHint"))
            } finally { release.complete(Unit) }
        }
    }

    @Test
    fun offlineOptionsHistoryCountCannotPublishAfterShopChanges() = runTest {
        withOfflineOptionsFixture(optionsSnapshot(0), sessionConfigured = true) { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.getPendingHistorySessionPushUids() } coAnswers {
                entered.complete(Unit)
                release.await()
                listOf(40L)
            }
            try {
                f.viewModel.onOptionsScreenVisible()
                advanceUntilIdle()
                assertTrue(entered.isCompleted)
                val secondShop = dateOutcomeShop("00000000-0000-4000-8000-000000000247")
                val secondScope = task126ActiveOwnerStoreScope(OWNER_VM_021, secondShop)
                f.shops.value = ShopContext(OWNER_VM_021, emptyList(), secondShop,
                    syncAllowed = false, localAccessAllowed = true)
                f.tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState(
                    Task126BusinessDataScopeStatus.CHECKING, boundScope = secondScope,
                    localAccessScope = secondScope))
                advanceUntilIdle()
                release.complete(Unit)
                advanceUntilIdle()
                assertNull(optionsStateField(f.viewModel, "lastHistorySessionSyncSummary"))
                assertNull(f.viewModel.uiState.value.sessionDetail)
            } finally { release.complete(Unit) }
        }
    }

    private fun optionsStateField(viewModel: CatalogSyncViewModel, name: String): Any? =
        (viewModel.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(viewModel) as kotlinx.coroutines.flow.StateFlow<*>).value

    private fun optionsSnapshot(pending: Int) = LocalDatabaseStatusSnapshot(2, 1, 1, 3, 1, pending, 0)

    private class OfflineOptionsFixture(
        val tracker: CatalogSyncStateTracker,
        val auth: MutableStateFlow<AuthState>,
        val shops: MutableStateFlow<ShopContext>,
        val repository: InventoryRepository,
        var nextSnapshot: LocalDatabaseStatusSnapshot,
        var holdNextRead: Boolean = false,
        val readEntered: CompletableDeferred<Unit> = CompletableDeferred(),
        val releaseRead: CompletableDeferred<Unit> = CompletableDeferred()
    ) {
        lateinit var viewModel: CatalogSyncViewModel
    }

    private suspend fun TestScope.withOfflineOptionsFixture(
        qualifiedSnapshot: LocalDatabaseStatusSnapshot?,
        sessionConfigured: Boolean = false,
        block: suspend (OfflineOptionsFixture) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val shop = dateOutcomeShop()
        val scope = task126ActiveOwnerStoreScope(OWNER_VM_021, shop)
        val original = Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.CHECKING,
            boundScope = scope, localAccessScope = scope, localSnapshot = qualifiedSnapshot)
        val tracker = CatalogSyncStateTracker(original).apply { updateNetworkAvailability(false) }
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(OWNER_VM_021, "offline-options@example.test"))
        val shops = MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop,
            syncAllowed = false, localAccessAllowed = true))
        val repository = dateOutcomeRepository()
        val fixture = OfflineOptionsFixture(tracker, auth, shops, repository, qualifiedSnapshot ?: optionsSnapshot(0))
        coEvery { repository.getLocalDatabaseStatusSnapshot(any(), any()) } coAnswers {
            val captured = fixture.nextSnapshot
            if (fixture.holdNextRead) {
                fixture.holdNextRead = false
                fixture.readEntered.complete(Unit)
                fixture.releaseRead.await()
            }
            captured
        }
        tracker.syncOutcomeNowMs = { DATE_OUTCOME_T0_VM_144 }
        val viewModel = CatalogSyncViewModel(application = app, repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(), sessionRemote = ViewModelSessionRemote024(sessionConfigured),
            authFlow = auth, syncStateTracker = tracker, shopContextFlow = shops)
        fixture.viewModel = viewModel
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            advanceUntilIdle()
            assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
            block(fixture)
            coVerify(exactly = 0) { repository.bootstrapHistorySessionsFromRemote(any()) }
        } finally {
            fixture.releaseRead.complete(Unit)
            ui.cancel(); local.cancel(); store.clear()
            Dispatchers.setMain(mainDispatcherRule.dispatcher)
        }
    }

    @Test
    fun recoveryRequiredTrackerOutcomeDoesNotInventSuccessDate() = runTest {
        assertUnqualifiedTrackerOutcomeKeepsSuccessDateAbsent(
            CatalogSyncSummary(0, 0, 0, 0, 0, 0,
                manualFullSyncRequired = true, syncEventsGapDetected = true)
        )
    }

    @Test
    fun dirtyLocalBlockedTrackerOutcomeDoesNotInventSuccessDate() = runTest {
        assertUnqualifiedTrackerOutcomeKeepsSuccessDateAbsent(
            CatalogSyncSummary(0, 0, 0, 0, 0, 0, syncEventsSkippedDirtyLocal = 1)
        )
    }

    @Test
    fun recreatedViewModelKeepsOriginalOutcomeDateWithoutAnotherSync() = runTest {
        var nowMs = DATE_OUTCOME_T0_VM_144
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shop))
        )
        val shops = MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))
        val firstAuth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val first = dateOutcomeViewModel(repository, tracker, firstAuth, shops) { nowMs }
        val firstStore = ViewModelStore().apply { put("catalog", first) }
        val firstUi = launch { first.uiState.collect {} }
        val firstLocal = launch { first.localDatabaseStatusUi.collect {} }
        val completedAt: Long
        val completedText: String
        try {
            firstAuth.value = AuthState.SignedIn(OWNER_VM_021, "date-first@example.test")
            tracker.publishThroughDateFlight(OWNER_VM_021, shop, dateCompletedSummary())
            advanceUntilIdle()
            completedAt = requireNotNull(recordedSuccessDate(first))
            completedText = requireNotNull(first.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            firstUi.cancel()
            firstLocal.cancel()
            firstStore.clear()
        }
        val retainedOutcome = tracker.lastOutcome.value
        nowMs += 3_600_000L
        val secondAuth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val second = dateOutcomeViewModel(repository, tracker, secondAuth, shops) { nowMs }
        val secondStore = ViewModelStore().apply { put("catalog", second) }
        val secondUi = launch { second.uiState.collect {} }
        val secondLocal = launch { second.localDatabaseStatusUi.collect {} }
        try {
            secondAuth.value = AuthState.SignedIn(OWNER_VM_021, "date-recreated@example.test")
            advanceUntilIdle()
            assertEquals("Recreation must replay the retained outcome", retainedOutcome, tracker.lastOutcome.value)
            coVerify(exactly = 0) { repository.syncCatalogWithRemote(any(), any(), any()) }
            coVerify(exactly = 0) { repository.syncCatalogWithRemote(any(), any(), any(), any()) }
            assertEquals("Replaying an outcome must not create a newer success", completedAt, recordedSuccessDate(second))
            assertEquals(completedText, second.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            secondUi.cancel()
            secondLocal.cancel()
            secondStore.clear()
        }
    }

    @Test
    fun ownerChangeDoesNotShowPreviousOwnerSuccessDate() = runTest {
        assertSuccessDateDoesNotCrossScope("owner")
    }

    @Test
    fun shopChangeDoesNotShowPreviousShopSuccessDate() = runTest {
        assertSuccessDateDoesNotCrossScope("shop")
    }

    @Test
    fun storeEpochChangeDoesNotShowPreviousGenerationSuccessDate() = runTest {
        assertSuccessDateDoesNotCrossScope("storeEpoch")
    }

    @Test
    fun sameScopeBlockedAndDiagnosticOutcomesKeepOriginalQualifiedSuccessDate() = runTest {
        var nowMs = DATE_OUTCOME_T0_VM_144
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shop)))
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = dateOutcomeViewModel(repository, tracker, auth,
            MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))) { nowMs }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-history@example.test")
            tracker.publishThroughDateFlight(OWNER_VM_021, shop, dateCompletedSummary())
            advanceUntilIdle()
            assertEquals(DATE_OUTCOME_T0_VM_144, recordedSuccessDate(viewModel))
            val originalText = requireNotNull(viewModel.localDatabaseStatusUi.value.lastSyncText)
            val incomplete = listOf(
                dateCompletedSummary().copy(manualFullSyncRequired = true),
                dateCompletedSummary().copy(syncEventsSkippedDirtyLocal = 1)
            )
            for (summary in incomplete) {
                nowMs += 3_600_000L
                tracker.publishThroughDateFlight(OWNER_VM_021, shop, summary)
                advanceUntilIdle()
                assertNull(tracker.lastOutcome.value?.completedAtMs)
                assertEquals(DATE_OUTCOME_T0_VM_144, recordedSuccessDate(viewModel))
                assertEquals(originalText, viewModel.localDatabaseStatusUi.value.lastSyncText)
            }
            nowMs += 3_600_000L
            val qualifiedScope = task126ActiveOwnerStoreScope(OWNER_VM_021, shop)
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState(
                Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, boundScope = qualifiedScope,
                localAccessScope = qualifiedScope, errorCode = "sync_recovery_required"))
            tracker.publishSummary(OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, dateCompletedSummary())
            advanceUntilIdle()
            assertNull(tracker.lastOutcome.value?.completedAtMs)
            assertNull(tracker.lastOutcome.value?.scopeSignal)
            assertEquals(DATE_OUTCOME_T0_VM_144, recordedSuccessDate(viewModel))
            assertEquals(originalText, viewModel.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            ui.cancel(); local.cancel(); store.clear()
        }
    }

    @Test
    fun heldPreviousShopFlightCannotReplaceNewScopeCompletionDate() = runTest {
        var nowMs = DATE_OUTCOME_T0_VM_144
        val repository = dateOutcomeRepository()
        val shopA = dateOutcomeShop()
        val shopB = dateOutcomeShop("00000000-0000-4000-8000-000000000247")
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shopA)))
        val signalA = tracker.captureBusinessDataScopeSignal(OWNER_VM_021, shopA.shopId)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val shops = MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shopA))
        val viewModel = dateOutcomeViewModel(repository, tracker, auth, shops) { nowMs }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldPublicationAccepted: Boolean? = null
        val oldFlight = launch {
            try {
                tracker.withBusinessDataScopeFlight(OWNER_VM_021, shopA) {
                    withContext(NonCancellable) {
                        held.complete(Unit)
                        release.await()
                        oldPublicationAccepted = tracker.publishCurrentFlightSummary(
                            OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, dateCompletedSummary())
                    }
                }
            } catch (_: Task126BusinessDataScopeChangedException) {
                // The completed held result is from A; its cancellation must not touch B's outcome.
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Scope invalidation also cancels the registered original flight.
            }
        }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-held@example.test")
            held.await()
            shops.value = ShopContext(OWNER_VM_021, emptyList(), shopB)
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shopB)))
            assertFalse(tracker.isCurrentBusinessDataScopeSignal(signalA))
            nowMs += 3_600_000L
            tracker.publishThroughDateFlight(OWNER_VM_021, shopB, dateCompletedSummary())
            advanceUntilIdle()
            val outcomeB = requireNotNull(tracker.lastOutcome.value)
            val dateB = requireNotNull(viewModel.localDatabaseStatusUi.value.lastSyncText)
            assertEquals(nowMs, recordedSuccessDate(viewModel))
            release.complete(Unit)
            oldFlight.join()
            advanceUntilIdle()
            assertEquals(false, oldPublicationAccepted)
            assertEquals(outcomeB, tracker.lastOutcome.value)
            assertEquals(nowMs, recordedSuccessDate(viewModel))
            assertEquals(dateB, viewModel.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            release.complete(Unit)
            oldFlight.cancel()
            ui.cancel(); local.cancel(); store.clear()
        }
    }

    @Test
    fun qualifiedCatalogCompletionRetainsPriceFailureAndOriginalDateOnRecreation() = runTest {
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shop)))
        tracker.syncOutcomeNowMs = { DATE_OUTCOME_T0_VM_144 }
        tracker.publishThroughDateFlight(OWNER_VM_021, shop, dateCompletedSummary().copy(priceSyncFailed = true))
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = dateOutcomeViewModel(repository, tracker, auth,
            MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))) { DATE_OUTCOME_T0_VM_144 + 3_600_000L }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-prices@example.test")
            advanceUntilIdle()
            assertEquals(DATE_OUTCOME_T0_VM_144, recordedSuccessDate(viewModel))
            assertTrue(viewModel.localDatabaseStatusUi.value.lastSyncText != null)
            assertEquals(app.getString(R.string.catalog_cloud_state_prices_incomplete), viewModel.uiState.value.primaryMessage)
            assertTrue(tracker.lastOutcome.value?.summary?.priceSyncFailed == true)
        } finally {
            ui.cancel(); local.cancel(); store.clear()
        }
    }

    @Test
    fun diagnosticSummaryWithoutOriginalFlightDoesNotInventCompletionProof() = runTest {
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shop)))
        tracker.publishSummary(OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, dateCompletedSummary())
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = dateOutcomeViewModel(repository, tracker, auth,
            MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))) { DATE_OUTCOME_T0_VM_144 }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-unproved@example.test")
            advanceUntilIdle()
            assertNull(tracker.lastOutcome.value?.completedAtMs)
            assertNull(tracker.lastOutcome.value?.scopeSignal)
            assertNull(recordedSuccessDate(viewModel))
            assertNull(viewModel.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            ui.cancel(); local.cancel(); store.clear()
        }
    }

    @Test
    fun recreatedViewModelKeepsQualifiedDateAfterNewerDiagnosticSummary() = runTest {
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shop)))
        tracker.syncOutcomeNowMs = { DATE_OUTCOME_T0_VM_144 }
        tracker.publishThroughDateFlight(OWNER_VM_021, shop, dateCompletedSummary())
        val diagnostic = dateCompletedSummary().copy(manualFullSyncRequired = true)
        val qualifiedScope = task126ActiveOwnerStoreScope(OWNER_VM_021, shop)
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState(
            Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, boundScope = qualifiedScope,
            localAccessScope = qualifiedScope, errorCode = "sync_recovery_required"))
        tracker.publishSummary(OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, diagnostic)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = dateOutcomeViewModel(repository, tracker, auth,
            MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))) { DATE_OUTCOME_T0_VM_144 + 3_600_000L }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-replay-diagnostic@example.test")
            advanceUntilIdle()
            assertEquals(diagnostic, tracker.lastOutcome.value?.summary)
            assertNull(tracker.lastOutcome.value?.completedAtMs)
            assertEquals(DATE_OUTCOME_T0_VM_144, recordedSuccessDate(viewModel))
            assertTrue(viewModel.localDatabaseStatusUi.value.lastSyncText != null)
        } finally {
            ui.cancel(); local.cancel(); store.clear()
        }
    }

    @Test
    fun revokedLocalScopeCannotRestorePreviousCompletionWithoutNewFlight() = runTest {
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val scope = task126ActiveOwnerStoreScope(OWNER_VM_021, shop)
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(scope))
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = dateOutcomeViewModel(repository, tracker, auth,
            MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))) { DATE_OUTCOME_T0_VM_144 }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-revoked@example.test")
            tracker.publishThroughDateFlight(OWNER_VM_021, shop, dateCompletedSummary())
            advanceUntilIdle()
            assertEquals(DATE_OUTCOME_T0_VM_144, recordedSuccessDate(viewModel))
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState(
                Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, boundScope = scope,
                localAccessScope = scope, localReadsAllowed = false, localWritesAllowed = false))
            // Returning to the same binding cannot recreate the invalidated original proof.
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(scope))
            advanceUntilIdle()
            assertNull(tracker.lastQualifiedOutcome.value)
            assertNull(recordedSuccessDate(viewModel))
            assertNull(viewModel.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            ui.cancel(); local.cancel(); store.clear()
        }
    }

    @Test
    fun scopeTransitionCannotClearNewScopeCompletionPublishedBySynchronousSubscriber() = runTest {
        val shopA = dateOutcomeShop()
        val shopB = dateOutcomeShop("00000000-0000-4000-8000-000000000247")
        val scopeA = task126ActiveOwnerStoreScope(OWNER_VM_021, shopA)
        val scopeB = task126ActiveOwnerStoreScope(OWNER_VM_021, shopB)
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(scopeA))
        tracker.syncOutcomeNowMs = { DATE_OUTCOME_T0_VM_144 }
        tracker.publishThroughDateFlight(OWNER_VM_021, shopA, dateCompletedSummary())
        val order = mutableListOf<String>()
        var subscriberObservedA = false
        var setterReturned = false
        var subscriberAttemptedB = false
        var flightBCompletedBeforeSetterReturned = false
        var publishedB = tracker.lastQualifiedOutcome.value
        val subscriber = launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.businessDataScopeState.collect { state ->
                if (state.boundScope?.storeId == scopeA.storeId) subscriberObservedA = true
                if (state.boundScope?.storeId == scopeB.storeId && !subscriberAttemptedB) {
                    subscriberAttemptedB = true
                    order += "subscriber_b_enter"
                    val acceptedB = tracker.withBusinessDataScopeFlight(OWNER_VM_021, shopB) {
                        val accepted = tracker.publishCurrentFlightSummary(
                            OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, dateCompletedSummary())
                        publishedB = tracker.lastQualifiedOutcome.value
                        accepted
                    }
                    order += "flight_b_completed"
                    flightBCompletedBeforeSetterReturned = !setterReturned && acceptedB &&
                        publishedB?.completedAtMs == DATE_OUTCOME_T0_VM_144 + 3_600_000L &&
                        publishedB?.let { tracker.isOutcomeCurrent(it, OWNER_VM_021, shopB) } == true
                }
            }
        }
        try {
            assertTrue("NOT_TRAVERSED: the subscriber must be installed before the transition", subscriberObservedA)
            tracker.syncOutcomeNowMs = { DATE_OUTCOME_T0_VM_144 + 3_600_000L }
            order += "setter_enter"
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(scopeB))
            setterReturned = true
            order += "setter_return"
            assertTrue("NOT_TRAVERSED: B's real flight must finish during scope emission, order=$order",
                flightBCompletedBeforeSetterReturned)
            val expectedB = requireNotNull(publishedB)
            assertEquals("The previous transition must retain the new scope's qualified completion, order=$order",
                expectedB, tracker.lastQualifiedOutcome.value)
            assertEquals(expectedB, tracker.lastOutcome.value)
            assertTrue(tracker.isOutcomeCurrent(expectedB, OWNER_VM_021, shopB))

            val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
            val viewModel = dateOutcomeViewModel(dateOutcomeRepository(), tracker, auth,
                MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shopB))) { DATE_OUTCOME_T0_VM_144 + 7_200_000L }
            val store = ViewModelStore().apply { put("catalog", viewModel) }
            val local = launch { viewModel.localDatabaseStatusUi.collect {} }
            try {
                auth.value = AuthState.SignedIn(OWNER_VM_021, "date-reentrant@example.test")
                advanceUntilIdle()
                assertEquals(DATE_OUTCOME_T0_VM_144 + 3_600_000L, recordedSuccessDate(viewModel))
                assertTrue(viewModel.localDatabaseStatusUi.value.lastSyncText != null)
            } finally {
                local.cancel()
                store.clear()
            }
        } finally {
            subscriber.cancel()
        }
    }

    private suspend fun TestScope.assertUnqualifiedTrackerOutcomeKeepsSuccessDateAbsent(summary: CatalogSyncSummary) {
        val repository = dateOutcomeRepository()
        val shop = dateOutcomeShop()
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope(OWNER_VM_021, shop))
        )
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val viewModel = dateOutcomeViewModel(repository, tracker, auth,
            MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), shop))) { DATE_OUTCOME_T0_VM_144 }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-blocked@example.test")
            advanceUntilIdle()
            assertNull(recordedSuccessDate(viewModel))
            assertNull(viewModel.localDatabaseStatusUi.value.lastSyncText)
            tracker.publishSummary(OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, summary)
            advanceUntilIdle()
            assertEquals("The diagnostic outcome must remain available", summary, tracker.lastOutcome.value?.summary)
            assertNull("A blocked outcome is not a completed sync", recordedSuccessDate(viewModel))
            assertNull(viewModel.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            ui.cancel()
            local.cancel()
            store.clear()
        }
    }

    private suspend fun TestScope.assertSuccessDateDoesNotCrossScope(boundary: String) {
        val repository = dateOutcomeRepository()
        val firstShop = dateOutcomeShop()
        val firstScope = task126ActiveOwnerStoreScope(OWNER_VM_021, firstShop)
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(firstScope))
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val shops = MutableStateFlow(ShopContext(OWNER_VM_021, emptyList(), firstShop))
        val viewModel = dateOutcomeViewModel(repository, tracker, auth, shops) { DATE_OUTCOME_T0_VM_144 }
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val ui = launch { viewModel.uiState.collect {} }
        val local = launch { viewModel.localDatabaseStatusUi.collect {} }
        try {
            auth.value = AuthState.SignedIn(OWNER_VM_021, "date-scope-a@example.test")
            tracker.publishThroughDateFlight(OWNER_VM_021, firstShop, dateCompletedSummary())
            advanceUntilIdle()
            assertTrue("The original scope must have a visible date", viewModel.localDatabaseStatusUi.value.lastSyncText != null)
            val nextOwner = if (boundary == "owner") "00000000-0000-4000-8000-000000000249" else OWNER_VM_021
            val nextShop = if (boundary == "shop") dateOutcomeShop("00000000-0000-4000-8000-000000000247") else firstShop
            val nextScope = if (boundary == "storeEpoch") {
                Task126OwnerStoreScope(firstScope.ownerHash, firstScope.storeId, firstScope.localStoreId,
                    firstScope.syncProtocolVersion, firstScope.schemaVersion, firstScope.storeEpoch + 1)
            } else task126ActiveOwnerStoreScope(nextOwner, nextShop)
            auth.value = AuthState.SignedIn(nextOwner, "date-scope-b@example.test")
            shops.value = ShopContext(nextOwner, emptyList(), nextShop)
            tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(nextScope))
            advanceUntilIdle()
            assertNull("The tracker must invalidate the old outcome", tracker.lastOutcome.value)
            assertNull("The new $boundary scope has no completed sync", viewModel.localDatabaseStatusUi.value.lastSyncText)
        } finally {
            ui.cancel()
            local.cancel()
            store.clear()
        }
    }

    private fun dateOutcomeRepository(): InventoryRepository = mockk<InventoryRepository>().also { repository ->
        coEvery { repository.getLocalDatabaseStatusSnapshot(any(), any()) } returns
            LocalDatabaseStatusSnapshot(1, 1, 1, 1, 0, 0, 0)
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
    }

    private fun dateOutcomeViewModel(
        repository: InventoryRepository,
        tracker: CatalogSyncStateTracker,
        auth: MutableStateFlow<AuthState>,
        shops: MutableStateFlow<ShopContext>,
        nowMs: () -> Long
    ): CatalogSyncViewModel {
        tracker.syncOutcomeNowMs = nowMs
        return CatalogSyncViewModel(
            application = app, repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(), sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth, syncStateTracker = tracker, shopContextFlow = shops
        )
    }

    private suspend fun CatalogSyncStateTracker.publishThroughDateFlight(
        ownerUserId: String,
        selectedShop: SelectedShop?,
        summary: CatalogSyncSummary,
        source: CatalogSyncFlightOwner = CatalogSyncFlightOwner.SYNC_EVENTS
    ) = withBusinessDataScopeFlight(ownerUserId, selectedShop) {
        assertTrue("The fixture must publish from its admitted original flight",
            publishCurrentFlightSummary(ownerUserId, source, summary))
    }

    private fun dateOutcomeShop(shopId: String = "00000000-0000-4000-8000-000000000246") =
        SelectedShop(shopId, "date-fixture", "Date fixture", "owner", "active", true)

    private fun dateCompletedSummary() = CatalogSyncSummary(0, 0, 0, 1, 1, 1,
        pulledProductPrices = 1, fullCatalogFetch = true, fullPriceFetch = true,
        remoteActiveSuppliers = 1, remoteActiveCategories = 1, remoteActiveProducts = 1,
        remotePricesFetched = 1)

    private fun recordedSuccessDate(viewModel: CatalogSyncViewModel): Long? =
        (viewModel.javaClass.getDeclaredField("lastSuccessAt").apply { isAccessible = true }
            .get(viewModel) as kotlinx.coroutines.flow.StateFlow<*>).value as Long?

    @Test
    fun signedOutBoundDatabaseReportsAccountPrerequisiteWithoutEnablingSync() = runTest {
        val scope = task126ActiveOwnerStoreScope(OWNER_VM_021, null)
        val held = Task126BusinessDataScopeState(
            Task126BusinessDataScopeStatus.CHECKING, boundScope = scope
        )
        val tracker = CatalogSyncStateTracker().apply { updateBusinessDataScopeState(held) }
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = mockk<InventoryRepository>(),
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow<AuthState>(AuthState.SignedOut),
            syncStateTracker = tracker
        )
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        try {
            assertEquals(
                app.getString(R.string.catalog_cloud_state_sign_in_required),
                viewModel.uiState.value.primaryMessage
            )
            assertFalse(viewModel.uiState.value.isSyncing)
            assertFalse(viewModel.uiState.value.canRefresh)
            assertFalse(viewModel.uiState.value.canQuickSync)
            assertEquals(held, tracker.businessDataScopeState.value)
            assertFalse(com.example.merchandisecontrolsplitview.ui.navigation.businessContentAvailable(
                true, AuthState.SignedOut, held
            ))
        } finally {
            store.clear()
        }
    }

    @Test
    fun authChangesUpdateCheckingPresentationWithoutChangingBoundDatabaseFence() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery { repository.getLocalDatabaseStatusSnapshot(any(), any()) } returns
            LocalDatabaseStatusSnapshot(2, 1, 1, 3, 1, 0, 3)
        val held = Task126BusinessDataScopeState(
            Task126BusinessDataScopeStatus.CHECKING,
            boundScope = task126ActiveOwnerStoreScope(OWNER_VM_021, null)
        )
        val tracker = CatalogSyncStateTracker().apply { updateBusinessDataScopeState(held) }
        val auth = MutableStateFlow<AuthState>(AuthState.Checking)
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            syncStateTracker = tracker
        )
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val collectJob = launch { viewModel.uiState.collect {} }
        try {
            val transitions = listOf(
                AuthState.Checking to R.string.account_checking,
                AuthState.SignedOut to R.string.catalog_cloud_state_sign_in_required,
                AuthState.ErrorRecoverable("auth_refresh_failed") to R.string.catalog_cloud_state_session_required,
                AuthState.SignedIn(OWNER_VM_021, "prerequisite@example.test") to R.string.business_scope_checking
            )
            for ((state, messageRes) in transitions) {
                auth.value = state
                advanceUntilIdle()
                assertEquals(app.getString(messageRes), viewModel.uiState.value.primaryMessage)
                assertFalse(viewModel.uiState.value.isSyncing)
                assertFalse(viewModel.uiState.value.canRefresh)
                assertFalse(viewModel.uiState.value.canQuickSync)
                assertEquals(held, tracker.businessDataScopeState.value)
                assertFalse(com.example.merchandisecontrolsplitview.ui.navigation.businessContentAvailable(
                    true, state, held
                ))
            }
        } finally {
            collectJob.cancel()
            store.clear()
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun selectedAppLanguageLocalizesInitialSyncPresentation() = runTest {
        val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("lang", "zh").commit())
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = mockk<InventoryRepository>(),
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow<AuthState>(AuthState.SignedOut)
        )
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        try {
            assertEquals("en", app.resources.configuration.locales[0].language)
            val chinese = app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocale(Locale.CHINESE) }
            )
            assertEquals(
                chinese.getString(R.string.catalog_cloud_state_sign_in_required),
                viewModel.uiState.value.primaryMessage
            )
            assertFalse(viewModel.uiState.value.canRefresh)
            assertFalse(viewModel.uiState.value.isSyncing)
        } finally {
            store.clear()
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun selectedAppLanguageReformatsCachedSyncDetailsWithoutAnotherOutcome() = runTest {
        val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("lang", "en").commit())
        val repository = mockk<InventoryRepository>()
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getLocalDatabaseStatusSnapshot(any(), any()) } returns
            LocalDatabaseStatusSnapshot(2, 1, 1, 3, 1, 0, 3)
        val tracker = CatalogSyncStateTracker()
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow<AuthState>(
                AuthState.SignedIn(OWNER_VM_021, "locale@example.test")
            ),
            syncStateTracker = tracker
        )
        val store = ViewModelStore().apply { put("catalog", viewModel) }
        val collectJob = launch { viewModel.uiState.collect {} }
        try {
            advanceUntilIdle()
            tracker.publishThroughDateFlight(
                OWNER_VM_021,
                null,
                CatalogSyncSummary(0, 0, 0, 0, 0, 0, syncEventOutboxPending = 3),
                source = CatalogSyncFlightOwner.AUTO_PUSH
            )
            advanceUntilIdle()
            val initial = viewModel.uiState.value
            val outcome = tracker.lastOutcome.value
            val timestamp = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")
                .find(requireNotNull(initial.catalogDetail))!!.value
            clearMocks(repository, answers = false, recordedCalls = true)

            for (language in listOf("zh", "it", "es", "en")) {
                assertTrue(preferences.edit().putString("lang", language).commit())
                advanceUntilIdle()
                val localized = app.createConfigurationContext(
                    Configuration(app.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(language))
                    }
                )
                val current = viewModel.uiState.value
                val detail = requireNotNull(current.catalogDetail)
                assertTrue(
                    "$language last success must be retranslated without a new sync",
                    detail.contains(
                        localized.getString(R.string.catalog_cloud_last_ok, timestamp)
                    )
                )
                assertTrue(
                    "$language plural detail must use the selected resources",
                    detail.contains(
                        localized.resources.getQuantityString(
                            R.plurals.catalog_cloud_sync_event_outbox_hint, 3, 3
                        )
                    )
                )
                assertEquals(initial.canRefresh, current.canRefresh)
                assertEquals(initial.canQuickSync, current.canQuickSync)
                assertEquals(initial.isSyncing, current.isSyncing)
                assertEquals(initial.statusBadges, current.statusBadges)
                assertEquals(initial.businessDataScopeStatus, current.businessDataScopeStatus)
                assertEquals(outcome, tracker.lastOutcome.value)
                assertEquals("en", app.resources.configuration.locales[0].language)
            }
            // Locale changes re-render the current state; they do not refresh business data.
            coVerify(exactly = 0) { repository.getLocalDatabaseStatusSnapshot(any(), any()) }
            coVerify(exactly = 0) { repository.hasCatalogCloudPendingWorkInclusive() }
        } finally {
            collectJob.cancel()
            store.clear()
        }
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
            app.getString(R.string.catalog_cloud_auto_status_title),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(viewModel.uiState.value.showAutomaticSyncDetail)
        assertTrue(viewModel.uiState.value.canRefresh)
        coVerify(exactly = 1) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }

        collectJob.cancel()
    }

    @Test
    fun `139 checking review and mismatch states never claim automatic sync active`() = runTest {
        val repository = mockk<InventoryRepository>()
        val localSnapshot = LocalDatabaseStatusSnapshot(
            products = 2,
            suppliers = 1,
            categories = 1,
            priceHistoryRows = 3,
            historySessions = 1,
            pendingLocalChanges = 2,
            syncEventOutboxPending = 1
        )
        coEvery {
            repository.getLocalDatabaseStatusSnapshot(any(), any())
        } returns localSnapshot
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "scope@example.test")
        )
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.checking())
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = true),
            authFlow = auth,
            syncStateTracker = tracker
        )
        val uiCollectJob = launch { viewModel.uiState.collect {} }
        val localCollectJob = launch { viewModel.localDatabaseStatusUi.collect {} }
        val blockedStates = listOf(
            Task126BusinessDataScopeState.checking(),
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND,
                localSnapshot = localSnapshot
            ),
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH
            ),
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH
            )
        )

        blockedStates.forEach { scopeState ->
            tracker.updateBusinessDataScopeState(scopeState)
            advanceUntilIdle()

            val ui = viewModel.uiState.value
            assertEquals(scopeState.status, ui.businessDataScopeStatus)
            assertFalse(ui.isSyncing)
            assertFalse(ui.canRefresh)
            assertFalse(ui.canQuickSync)
            assertFalse(ui.showAutomaticSyncDetail)
            assertFalse(ui.primaryMessage == app.getString(R.string.catalog_cloud_auto_status_title))
            assertEquals(
                scopeState.status,
                viewModel.localDatabaseStatusUi.value.businessDataScopeStatus
            )
            if (scopeState.status == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND) {
                assertEquals(2, viewModel.localDatabaseStatusUi.value.productsCount)
                assertEquals(1, viewModel.localDatabaseStatusUi.value.syncEventOutboxPendingCount)
            }
        }

        coVerify(exactly = 0) {
            repository.bootstrapHistorySessionsFromRemote(any())
        }
        uiCollectJob.cancel()
        localCollectJob.cancel()
    }

    @Test
    fun `139 validated network loss surfaces offline immediately and reconnect clears it`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery {
            repository.getLocalDatabaseStatusSnapshot(any(), any())
        } returns LocalDatabaseStatusSnapshot(
            products = 1,
            suppliers = 0,
            categories = 0,
            priceHistoryRows = 1,
            historySessions = 0,
            pendingLocalChanges = 0,
            syncEventOutboxPending = 0
        )
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "network@example.test")
        )
        val tracker = CatalogSyncStateTracker()
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

        tracker.updateNetworkAvailability(true)
        advanceUntilIdle()
        assertEquals(
            app.getString(R.string.catalog_cloud_auto_status_title),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(viewModel.uiState.value.showAutomaticSyncDetail)

        tracker.updateNetworkAvailability(false)
        advanceUntilIdle()
        assertEquals(
            app.getString(R.string.catalog_cloud_state_offline),
            viewModel.uiState.value.primaryMessage
        )
        assertFalse(viewModel.uiState.value.isSyncing)
        assertFalse(viewModel.uiState.value.showAutomaticSyncDetail)
        assertFalse(
            viewModel.uiState.value.primaryMessage ==
                app.getString(R.string.catalog_cloud_auto_status_title)
        )

        tracker.updateNetworkAvailability(true)
        advanceUntilIdle()
        assertEquals(
            app.getString(R.string.catalog_cloud_auto_status_title),
            viewModel.uiState.value.primaryMessage
        )
        assertTrue(viewModel.uiState.value.showAutomaticSyncDetail)

        collectJob.cancel()
    }

    @Test
    fun `139 scope cancellation during manual device preflight releases manual owner`() = runTest {
        val repository = mockk<InventoryRepository>()
        val autoRepository = mockk<CatalogAutoSyncRepository>()
        val statusStarted = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(
                task126ActiveOwnerStoreScope(OWNER_VM_021, selectedShop = null)
            )
        )
        val deviceRemote = object : ShopDeviceRegistrationRemote {
            override val isConfigured: Boolean = true

            override suspend fun registerCurrentOwnerDevice(
                reason: String
            ): Result<ShopDeviceRegistrationResult> =
                Result.success(ShopDeviceRegistrationResult(ok = true, code = "success"))

            override suspend fun currentOwnerDeviceStatus(
                reason: String
            ): Result<ShopDeviceAuthorizationSnapshot> {
                statusStarted.complete(Unit)
                withContext(NonCancellable) { releaseStatus.await() }
                return Result.success(
                    ShopDeviceAuthorizationSnapshot(
                        status = "active",
                        code = "success",
                        canWrite = true,
                        serverTime = null,
                        lastSeenAt = null,
                        reasonCode = "active",
                        recommendedAction = "allow",
                        checkedAtMs = 0L
                    )
                )
            }
        }
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow(
                AuthState.SignedIn(OWNER_VM_021, "preflight@example.test")
            ),
            syncStateTracker = tracker,
            autoSyncRepository = autoRepository,
            deviceAuthorization = ShopDeviceAuthorizationRepository(
                remote = deviceRemote,
                businessDataScopeRuntimeGuard = tracker
            )
        )

        viewModel.syncCatalogQuick()
        statusStarted.await()
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
        releaseStatus.complete(Unit)
        advanceUntilIdle()

        assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.MANUAL))
        tracker.finish(CatalogSyncFlightOwner.MANUAL)
    }

    @Test
    fun `132D automatic bootstrap outcome marks signed-in UI synced`() = runTest {
        val repository = mockk<InventoryRepository>()
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery {
            repository.bootstrapHistorySessionsFromRemote(any())
        } returns Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "automatic@example.test")
        )
        val tracker = CatalogSyncStateTracker()
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(),
            authFlow = auth,
            syncStateTracker = tracker
        )

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(
            app.getString(R.string.catalog_cloud_auto_status_title),
            viewModel.uiState.value.primaryMessage
        )

        tracker.publishThroughDateFlight(
            OWNER_VM_021,
            null,
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 1,
                pulledCategories = 1,
                pulledProducts = 1,
                pulledProductPrices = 1
            ),
            source = CatalogSyncFlightOwner.BOOTSTRAP
        )
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_synced),
            viewModel.uiState.value.primaryMessage
        )
        coVerify(atLeast = 1) {
            repository.hasCatalogCloudPendingWorkInclusive()
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
            app.getString(R.string.catalog_cloud_state_sending_changes),
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
                app.resources.getQuantityString(R.plurals.catalog_cloud_quick_sync_locals_sent, 2, 2)
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
    fun `061 manual quick sync with events recommends full sync when required`() = runTest {
        val repository = mockk<InventoryRepository>()
        val autoRepository = mockk<CatalogAutoSyncRepository>()
        val syncEventRemote = mockk<SyncEventRemoteDataSource>()
        every { syncEventRemote.isConfigured } returns true
        coEvery {
            autoRepository.syncCatalogQuickWithEvents(any(), any(), syncEventRemote, OWNER_VM_021, any())
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                syncEventsAvailable = true,
                recordSyncEventAvailable = true,
                manualFullSyncRequired = true,
                syncEventsGapDetected = true
            )
        )
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns true
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "061@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            autoSyncRepository = autoRepository,
            syncEventRemote = syncEventRemote
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.syncCatalogQuick()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.fullSyncRecommended)
        assertFalse(viewModel.uiState.value.quickSyncRecommended)
        assertTrue(
            viewModel.uiState.value.statusBadges.any {
                it.labelRes == R.string.catalog_cloud_badge_full_required
            }
        )
        assertTrue(
            viewModel.uiState.value.catalogDetail!!.contains(
                app.getString(R.string.catalog_cloud_manual_full_sync_required_hint)
            )
        )
        collectJob.cancel()
    }

    @Test
    fun ordinaryAdvancedManualAndTrackerOutcomesNeverPromoteLastSuccess() = runTest {
        val repository = mockk<InventoryRepository>()
        val auto = mockk<CatalogAutoSyncRepository>()
        val eventRemote = mockk<SyncEventRemoteDataSource>()
        val tracker = CatalogSyncStateTracker()
        every { eventRemote.isConfigured } returns true
        val pending = CatalogSyncSummary(0, 0, 0, 0, 0, 0, syncEventsOrdinaryPending = true,
            incrementalRemoteSubsetVerifiable = false,
            incrementalRemoteNotVerifiableReason = CatalogIncrementalRemoteContract044A.INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES)
        coEvery { auto.syncCatalogQuickWithEvents(any(), any(), eventRemote, OWNER_VM_021, any()) } returns Result.success(pending)
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val viewModel = CatalogSyncViewModel(application = app, repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)), priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = MutableStateFlow(AuthState.SignedIn(OWNER_VM_021, "pending@example.test")),
            autoSyncRepository = auto, syncEventRemote = eventRemote, syncStateTracker = tracker)
        val collect = launch { viewModel.uiState.collect {} }
        fun field(name: String): Any? = viewModel.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(viewModel).let { (it as kotlinx.coroutines.flow.StateFlow<*>).value }
        try {
            advanceUntilIdle()
            val confirmed = CatalogSyncSummary(0, 0, 0, 1, 1, 1)
            tracker.publishThroughDateFlight(OWNER_VM_021, null, confirmed)
            advanceUntilIdle()
            val previousSuccess = field("lastSuccessAt")
            assertTrue(previousSuccess is Long)
            tracker.publishSummary(OWNER_VM_021, CatalogSyncFlightOwner.SYNC_EVENTS, pending)
            advanceUntilIdle()
            assertEquals(previousSuccess, field("lastSuccessAt"))
            assertEquals(confirmed, field("lastCatalogSyncSummary"))
            viewModel.syncCatalogQuick()
            advanceUntilIdle()
            coVerify(exactly = 1) { auto.syncCatalogQuickWithEvents(any(), any(), eventRemote, OWNER_VM_021, any()) }
            assertEquals(previousSuccess, field("lastSuccessAt"))
            assertEquals(confirmed, field("lastCatalogSyncSummary"))
            assertEquals("OTHER", field("incrementalDetailSurface").toString())
            assertEquals(CatalogSyncStage.IDLE, tracker.state.value.stage)
        } finally { collect.cancel() }
    }

    @Test
    fun canonicalHistoryBootstrapDefersWithoutErrorOrFalseCompletion() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
        coEvery { repository.bootstrapHistorySessionsFromRemote(any()) } returns Result.failure(CanonicalCatalogInboundRequired())
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        val viewModel = CatalogSyncViewModel(application = app, repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(), sessionRemote = ViewModelSessionRemote024(),
            authFlow = MutableStateFlow(AuthState.SignedIn(OWNER_VM_021, "canonical@example.test")),
            syncStateTracker = tracker)
        val collect = launch { viewModel.uiState.collect {} }
        try {
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.bootstrapHistorySessionsFromRemote(any()) }
            assertEquals(CatalogSyncStage.IDLE, tracker.state.value.stage)
            assertNull(tracker.lastOutcome.value)
            assertNull(viewModel.uiState.value.sessionDetail)
            assertFalse(tracker.isSyncing.value)
        } finally { collect.cancel() }
    }

    @Test
    fun canonicalHistoryDeferralNeverReplacesOriginalCatalogFailureOrPublishesSuccess() = runTest {
        val repository = mockk<InventoryRepository>()
        val auto = mockk<CatalogAutoSyncRepository>()
        val tracker = CatalogSyncStateTracker()
        coEvery { repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021) } returns
            Result.failure(IOException("actual catalog network failure"))
        coEvery { repository.bootstrapHistorySessionsFromRemote(any()) } returnsMany listOf(
            Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0)),
            Result.failure(CanonicalCatalogInboundRequired()))
        coEvery { repository.pushHistorySessionsToRemote(any(), OWNER_VM_021) } returns
            Result.success(HistorySessionBackupPushSummary(0, 0))
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns true
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val viewModel = CatalogSyncViewModel(application = app, repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(), sessionRemote = ViewModelSessionRemote024(),
            authFlow = MutableStateFlow(AuthState.SignedIn(OWNER_VM_021, "catalog-failure@example.test")),
            autoSyncRepository = auto, syncStateTracker = tracker)
        val collect = launch { viewModel.uiState.collect {} }
        fun field(name: String): Any? = viewModel.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(viewModel).let { (it as kotlinx.coroutines.flow.StateFlow<*>).value }
        try {
            advanceUntilIdle()
            val previousSuccess = field("lastSuccessAt")
            viewModel.refreshCatalog()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021) }
            coVerify(exactly = 2) { repository.bootstrapHistorySessionsFromRemote(any()) }
            coVerify(exactly = 1) { repository.pushHistorySessionsToRemote(any(), OWNER_VM_021) }
            coVerify(exactly = 0) { auto.drainSyncEventsFromRemote(any(), any(), any(), any(), any(), any(), any()) }
            assertEquals("CATALOG_FAILURE_DESIRED", "Offline", field("lastErrorKind").toString())
            assertEquals(previousSuccess, field("lastSuccessAt"))
            assertNull(tracker.lastOutcome.value)
            assertEquals(com.example.merchandisecontrolsplitview.data.CatalogSyncStatus.FAILED, tracker.state.value.status)
            assertFalse(tracker.isSyncing.value)
            assertEquals(true, field("pendingHint"))
        } finally { collect.cancel() }
    }

    @Test
    fun canonicalFullRefreshPreservesOutboundAndNeverPublishesOrdinaryPendingAsSuccess() = runTest {
        for (mode in listOf("pending", "history-error", "complete")) {
            val repository = mockk<InventoryRepository>()
            val auto = mockk<CatalogAutoSyncRepository>()
            val events = mockk<SyncEventRemoteDataSource>()
            val tracker = CatalogSyncStateTracker()
            every { events.isConfigured } returns true
            val outbound = CatalogSyncSummary(2, 3, 4, 0, 0, 0, pushedProductPrices = 5)
            val drained = CatalogSyncSummary(0, 0, 0, 0, 0, 0,
                syncEventsOrdinaryPending = mode != "complete", syncEventsWatermarkBefore = 42,
                syncEventsWatermarkAfter = 42, syncEventsAvailable = true)
            coEvery { repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021) } returns
                Result.failure(CanonicalCatalogInboundRequired(outbound))
            coEvery { repository.bootstrapHistorySessionsFromRemote(any()) } returns
                Result.success(RemoteSessionBatchResult(0, 0, 0, 0, 0))
            coEvery { repository.pushHistorySessionsToRemote(any(), OWNER_VM_021) } returns
                if (mode == "history-error") Result.failure(IOException("actual History push failed"))
                else Result.success(HistorySessionBackupPushSummary(1, 0))
            coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
            coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
            coEvery { auto.drainSyncEventsFromRemote(any(), any(), events, OWNER_VM_021, any(), any(), null) } returns Result.success(drained)
            val viewModel = CatalogSyncViewModel(application = app, repository = repository,
                remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
                priceRemote = ViewModelPriceRemote021(), sessionRemote = ViewModelSessionRemote024(),
                authFlow = MutableStateFlow(AuthState.SignedIn(OWNER_VM_021, "canonical@example.test")),
                autoSyncRepository = auto, syncEventRemote = events, syncStateTracker = tracker)
            val collect = launch { viewModel.uiState.collect {} }
            fun field(name: String): Any? = viewModel.javaClass.getDeclaredField(name).apply { isAccessible = true }
                .get(viewModel).let { (it as kotlinx.coroutines.flow.StateFlow<*>).value }
            try {
                advanceUntilIdle()
                val previousSuccess = field("lastSuccessAt")
                viewModel.refreshCatalog()
                advanceUntilIdle()
                coVerify(exactly = 1) { repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021) }
                coVerify(exactly = 1) { repository.bootstrapHistorySessionsFromRemote(any()) }
                coVerify(exactly = 1) { repository.pushHistorySessionsToRemote(any(), OWNER_VM_021) }
                coVerify(exactly = 1) { auto.drainSyncEventsFromRemote(any(), any(), events, OWNER_VM_021, any(), any(), null) }
                if (mode == "complete") {
                    assertEquals(5, tracker.lastOutcome.value?.summary?.pushedProductPrices)
                    assertEquals(2, tracker.lastOutcome.value?.summary?.pushedSuppliers)
                    assertEquals(CatalogSyncStage.COMPLETED, tracker.state.value.stage)
                } else {
                    assertEquals(previousSuccess, field("lastSuccessAt"))
                    assertNull(tracker.lastOutcome.value)
                    assertEquals(if (mode == "pending") com.example.merchandisecontrolsplitview.data.CatalogSyncStatus.IDLE
                        else com.example.merchandisecontrolsplitview.data.CatalogSyncStatus.FAILED, tracker.state.value.status)
                }
                assertFalse(tracker.isSyncing.value)
            } finally { collect.cancel() }
        }
    }

    @Test
    fun `139 manual quick sync requests recovery once after releasing manual flight`() = runTest {
        val repository = mockk<InventoryRepository>()
        val autoRepository = mockk<CatalogAutoSyncRepository>()
        val syncEventRemote = mockk<SyncEventRemoteDataSource>()
        val tracker = CatalogSyncStateTracker()
        val recoveryRequests = mutableListOf<String>()
        every { syncEventRemote.isConfigured } returns true
        coEvery {
            autoRepository.syncCatalogQuickWithEvents(any(), any(), syncEventRemote, OWNER_VM_021, any())
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                syncEventsAvailable = true,
                manualFullSyncRequired = true,
                syncEventsGapDetected = true
            )
        )
        coEvery { repository.hasCatalogCloudPendingWorkInclusive() } returns false
        coEvery { repository.getCatalogCloudPendingBreakdown() } returns emptyViewModelPendingBreakdown()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "139-quick-recovery@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            autoSyncRepository = autoRepository,
            syncEventRemote = syncEventRemote,
            syncStateTracker = tracker,
            onRecoveryRequired = { source ->
                assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.AUTO_PUSH))
                tracker.finish(CatalogSyncFlightOwner.AUTO_PUSH)
                recoveryRequests += source
            }
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.syncCatalogQuick()
        advanceUntilIdle()

        assertEquals(listOf("manual_quick_sync_recovery_required"), recoveryRequests)
        assertEquals(
            "sync_recovery_required",
            tracker.businessDataScopeState.value.errorCode
        )
        advanceUntilIdle()
        assertEquals(1, recoveryRequests.size)
        collectJob.cancel()
    }

    @Test
    fun `139 manual full sync requests recovery once after releasing manual flight`() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
        val recoveryRequests = mutableListOf<String>()
        coEvery {
            repository.syncCatalogWithRemote(any(), any(), OWNER_VM_021)
        } returns Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                manualFullSyncRequired = true,
                syncEventsGapDetected = true
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
            AuthState.SignedIn(userId = OWNER_VM_021, email = "139-recovery@example.test")
        )
        val viewModel = CatalogSyncViewModel(
            application = app,
            repository = repository,
            remote = ViewModelCatalogRemote021(bootstrapBundleVm021(OWNER_VM_021)),
            priceRemote = ViewModelPriceRemote021(),
            sessionRemote = ViewModelSessionRemote024(configured = false),
            authFlow = auth,
            syncStateTracker = tracker,
            onRecoveryRequired = { source ->
                assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.AUTO_PUSH))
                tracker.finish(CatalogSyncFlightOwner.AUTO_PUSH)
                recoveryRequests += source
            }
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertEquals(listOf("manual_refresh_recovery_required"), recoveryRequests)
        assertEquals(
            "sync_recovery_required",
            tracker.businessDataScopeState.value.errorCode
        )
        advanceUntilIdle()
        assertEquals(1, recoveryRequests.size)
        collectJob.cancel()
    }

    @Test
    fun `061 automatic sync event outcome recommends full sync without quick copy`() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "061-auto@example.test")
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

        tracker.publishSummary(
            ownerUserId = OWNER_VM_021,
            source = CatalogSyncFlightOwner.SYNC_EVENTS,
            summary = CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 2,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                manualFullSyncRequired = true,
                syncEventsAvailable = true,
                syncEventsGapDetected = true
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.fullSyncRecommended)
        assertTrue(
            viewModel.uiState.value.catalogDetail!!.contains(
                app.getString(R.string.catalog_cloud_manual_full_sync_required_hint)
            )
        )
        assertFalse(
            viewModel.uiState.value.catalogDetail!!.contains(
                app.resources.getQuantityString(R.plurals.catalog_cloud_quick_sync_locals_sent, 2, 2)
            )
        )

        collectJob.cancel()
    }

    @Test
    fun `061 automatic sync event outcome shows outbox pending hint`() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "061-outbox@example.test")
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

        tracker.publishSummary(
            ownerUserId = OWNER_VM_021,
            source = CatalogSyncFlightOwner.AUTO_PUSH,
            summary = CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                syncEventOutboxPending = 3
            )
        )
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.catalogDetail!!.contains(
                app.resources.getQuantityString(R.plurals.catalog_cloud_sync_event_outbox_hint, 3, 3)
            )
        )
        assertTrue(
            viewModel.uiState.value.statusBadges.any {
                it.labelRes == R.string.catalog_cloud_badge_upload
            }
        )

        collectJob.cancel()
    }

    @Test
    fun `061 full refresh clears previous automatic full sync recommendation`() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
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
                pulledProductPrices = 1,
                fullCatalogFetch = true,
                fullPriceFetch = true,
                manualFullSyncRequired = false
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
            AuthState.SignedIn(userId = OWNER_VM_021, email = "061-full@example.test")
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
        tracker.publishSummary(
            ownerUserId = OWNER_VM_021,
            source = CatalogSyncFlightOwner.SYNC_EVENTS,
            summary = CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                manualFullSyncRequired = true
            )
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.fullSyncRecommended)

        viewModel.refreshCatalog()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.fullSyncRecommended)
        assertFalse(
            viewModel.uiState.value.catalogDetail?.contains(
                app.getString(R.string.catalog_cloud_manual_full_sync_required_hint)
            ) == true
        )

        collectJob.cancel()
    }

    @Test
    fun `061 tracker outcome from another owner is ignored`() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "061-owner@example.test")
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

        tracker.publishSummary(
            ownerUserId = "00000000-0000-4000-8000-000000000999",
            source = CatalogSyncFlightOwner.SYNC_EVENTS,
            summary = CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                manualFullSyncRequired = true,
                syncEventOutboxPending = 4
            )
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.fullSyncRecommended)
        assertFalse(
            viewModel.uiState.value.catalogDetail?.contains(
                app.getString(R.string.catalog_cloud_manual_full_sync_required_hint)
            ) == true
        )
        assertFalse(
            viewModel.uiState.value.catalogDetail?.contains(
                app.resources.getQuantityString(R.plurals.catalog_cloud_sync_event_outbox_hint, 4, 4)
            ) == true
        )

        collectJob.cancel()
    }

    @Test
    fun `061 signed out hides tracker outcome after publish`() = runTest {
        val repository = mockk<InventoryRepository>()
        val tracker = CatalogSyncStateTracker()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = OWNER_VM_021, email = "061-signed-out@example.test")
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

        tracker.publishSummary(
            ownerUserId = OWNER_VM_021,
            source = CatalogSyncFlightOwner.SYNC_EVENTS,
            summary = CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                manualFullSyncRequired = true,
                syncEventOutboxPending = 5
            )
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.fullSyncRecommended)

        auth.value = AuthState.SignedOut
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.catalog_cloud_state_sign_in_required),
            viewModel.uiState.value.primaryMessage
        )
        assertFalse(viewModel.uiState.value.fullSyncRecommended)
        assertNull(viewModel.uiState.value.catalogDetail)
        assertFalse(
            viewModel.uiState.value.statusBadges.any {
                it.labelRes == R.string.catalog_cloud_badge_full_required
            }
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

    override suspend fun fetchProductPricesPage(afterId: String?, limit: Long): Result<List<InventoryProductPriceRow>> =
        Result.success(
            fetchRows.asSequence()
                .sortedBy { it.id }
                .filter { afterId == null || it.id > afterId }
                .take(limit.coerceAtLeast(1L).toInt())
                .toList()
        )

    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
        Result.success(fetchRows.filter { it.id in remoteIds })
}

private class ViewModelSessionRemote024(
    var configured: Boolean = true
) : SessionBackupRemoteDataSource {
    override val isConfigured: Boolean get() = configured

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        Result.success(emptyList())

    override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>> =
        Result.success(emptyList())

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        Result.success(Unit)
}

private fun statusException(status: HttpStatusCode): ClientRequestException {
    val response = mockk<HttpResponse>()
    val call = mockk<HttpClientCall>()
    val request = mockk<HttpRequest>()
    every { response.status } returns status
    every { response.headers } returns io.ktor.http.Headers.Empty
    every { response.call } returns call
    every { call.request } returns request
    every { request.url } returns Url("https://example.test")
    every { request.method } returns HttpMethod.Get
    return ClientRequestException(response, status.description)
}

private const val DATE_OUTCOME_T0_VM_144 = 1_800_000_000_000L
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

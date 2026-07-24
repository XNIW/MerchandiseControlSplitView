package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogAutoSyncRepository
import com.example.merchandisecontrolsplitview.data.CatalogCloudPendingBreakdown
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncFlightOwner
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressRepository
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressState
import com.example.merchandisecontrolsplitview.data.CatalogSyncStage
import com.example.merchandisecontrolsplitview.data.CatalogSyncOutcomeState
import com.example.merchandisecontrolsplitview.data.CatalogSyncStateTracker
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
import com.example.merchandisecontrolsplitview.data.HistorySessionBackupPushSummary
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.LocalDatabaseStatusSnapshot
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.RemoteSessionBatchResult
import com.example.merchandisecontrolsplitview.data.SessionCloudFlightOwner
import com.example.merchandisecontrolsplitview.data.SessionCloudSessionFlightOwner
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SelectedShop
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationBlockedException
import com.example.merchandisecontrolsplitview.data.ShopDeviceAuthorizationRepository
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SyncErrorClassification
import com.example.merchandisecontrolsplitview.data.SyncErrorCategory
import com.example.merchandisecontrolsplitview.data.SyncErrorClassifier
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeChangedException
import com.example.merchandisecontrolsplitview.data.shopScopedStoreScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogSyncUiState(
    val primaryMessage: String,
    /** Catalog, prices, last ok, pending-catalog note — excludes history session backup lines. */
    val catalogDetail: String?,
    /** History session cloud backup only; kept separate from catalog pending/detail. */
    val sessionDetail: String?,
    val isSyncing: Boolean,
    val canRefresh: Boolean,
    val canQuickSync: Boolean = canRefresh,
    @param:StringRes val quickSyncBodyRes: Int = R.string.catalog_cloud_sync_quick_body,
    val statusBadges: List<CatalogSyncBadgeUiState> = emptyList(),
    val fullSyncRecommended: Boolean = false,
    val quickSyncRecommended: Boolean = false,
    val showAutomaticSyncDetail: Boolean = false,
    val progress: CatalogSyncStageUiState? = null,
    val businessDataScopeStatus: Task126BusinessDataScopeStatus =
        Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
)

data class CatalogSyncBadgeUiState(
    @param:StringRes val labelRes: Int
)

/** Scope for incremental “remote not verifiable” copy: only after a successful quick sync, not after full sync. */
private enum class CatalogIncrementalDetailSurface {
    AFTER_QUICK_SUCCESS,
    OTHER
}

data class CatalogSyncStageUiState(
    val stage: CatalogSyncStage,
    val message: String,
    val current: Int?,
    val total: Int?
)

data class LocalDatabaseStatusUiState(
    val productsCount: Int? = null,
    val suppliersCount: Int? = null,
    val categoriesCount: Int? = null,
    val priceHistoryCount: Int? = null,
    val historySessionsCount: Int? = null,
    val pendingLocalChangesCount: Int? = null,
    val syncEventOutboxPendingCount: Int? = null,
    val lastSyncText: String? = null,
    val isLoading: Boolean = false,
    /** TASK-114: ultimo full sync ha rilevato drift locale vs bundle remoto. */
    val needsReconciliation: Boolean = false,
    val businessDataScopeStatus: Task126BusinessDataScopeStatus =
        Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
) {
    val isEmpty: Boolean
        get() = listOf(
            productsCount,
            suppliersCount,
            categoriesCount,
            priceHistoryCount,
            historySessionsCount,
            pendingLocalChangesCount,
            syncEventOutboxPendingCount
        ).all { it == null || it == 0 }

    val hasPendingLocalChanges: Boolean
        get() = (pendingLocalChangesCount ?: 0) > 0
}

class CatalogSyncViewModel(
    application: Application,
    private val repository: InventoryRepository,
    private val remote: CatalogRemoteDataSource,
    private val priceRemote: ProductPriceRemoteDataSource,
    private val sessionRemote: SessionBackupRemoteDataSource,
    private val authFlow: StateFlow<AuthState>,
    private val sessionFlightOwner: SessionCloudSessionFlightOwner = SessionCloudSessionFlightOwner(),
    private val syncStateTracker: CatalogSyncStateTracker? = null,
    private val autoSyncRepository: CatalogAutoSyncRepository? = repository as? CatalogAutoSyncRepository,
    private val syncEventRemote: SyncEventRemoteDataSource? = null,
    private val deviceAuthorization: ShopDeviceAuthorizationRepository? = null,
    private val shopContextFlow: StateFlow<ShopContext>? = null,
    private val onRecoveryRequired: (String) -> Unit = {}
) : AndroidViewModel(application) {

    private enum class ErrorKind {
        Offline,
        Session,
        Forbidden,
        DeviceBlocked,
        NotFoundOrConfig,
        /** Catalog cloud sync completed; price-history block failed (task 017 — partial success). */
        CatalogOkPricesIncomplete,
        /** Catalog cloud sync completed; history-session backup/restore block failed (task 024). */
        HistorySessionsIncomplete,
        Generic
    }

    private data class SyncInputs(
        val auth: AuthState,
        val isBusy: Boolean,
        val err: ErrorKind?,
        val successAt: Long?,
        val pending: Boolean
    )

    private data class HistorySessionCloudUiSummary(
        val restored: Int,
        val uploaded: Int,
        val issueCount: Int,
        val pendingCount: Int = 0,
        val failureCategory: SyncErrorCategory? = null
    ) {
        val hasVisibleWork: Boolean
            get() = restored > 0 || uploaded > 0 || issueCount > 0 || pendingCount > 0
        val hasPendingWork: Boolean get() = pendingCount > 0
    }

    private data class HistorySessionCloudOutcome(
        val bootstrap: RemoteSessionBatchResult?,
        val push: HistorySessionBackupPushSummary?,
        val failure: Throwable?
    ) {
        val issueCount: Int
            get() = (bootstrap?.failed ?: 0) +
                (bootstrap?.unsupported ?: 0) +
                if (failure != null) 1 else 0

        val hasIssues: Boolean get() = issueCount > 0

        fun toUiSummary(pendingCount: Int = 0): HistorySessionCloudUiSummary {
            val failureClassification = failure?.let(SyncErrorClassifier::classify)
            return HistorySessionCloudUiSummary(
                restored = (bootstrap?.inserted ?: 0) + (bootstrap?.updated ?: 0),
                uploaded = push?.uploaded ?: 0,
                issueCount = issueCount,
                pendingCount = pendingCount,
                failureCategory = failureClassification?.category
            )
        }
    }

    private val busy = MutableStateFlow(false)
    private val syncProgress = MutableStateFlow(CatalogSyncProgressState.idle())
    private val lastErrorKind = MutableStateFlow<ErrorKind?>(null)
    private val lastSuccessAt = MutableStateFlow<Long?>(null)
    private val pendingHint = MutableStateFlow(false)
    private val lastCatalogSyncSummary = MutableStateFlow<CatalogSyncSummary?>(null)
    private val incrementalDetailSurface = MutableStateFlow(CatalogIncrementalDetailSurface.OTHER)
    private val lastHistorySessionSyncSummary = MutableStateFlow<HistorySessionCloudUiSummary?>(null)
    private val localDatabaseStatusSnapshot = MutableStateFlow<LocalDatabaseStatusSnapshot?>(null)
    private val localDatabaseStatusLoading = MutableStateFlow(true)
    private var automaticSessionBootstrapScopeKey: String? = null
    private var lastLoggedStage: CatalogSyncStage? = null

    private val quickSyncLaneAvailable: Boolean get() = autoSyncRepository != null
    private val trackerOutcomeFlow = syncStateTracker?.lastOutcome ?: flowOf<CatalogSyncOutcomeState?>(null)
    private val businessDataScopeFlow = syncStateTracker?.businessDataScopeState
        ?: flowOf(Task126BusinessDataScopeState.unmanagedAllowed())
    private val networkAvailableFlow = syncStateTracker?.networkAvailable ?: flowOf<Boolean?>(null)
    private val catalogSyncSummaryForUi = combine(
        authFlow,
        lastCatalogSyncSummary,
        trackerOutcomeFlow
    ) { auth, localSummary, trackerOutcome ->
        when {
            syncStateTracker == null -> localSummary
            auth is AuthState.SignedIn && trackerOutcome?.ownerUserId == auth.userId -> trackerOutcome.summary
            else -> null
        }
    }

    val uiState: StateFlow<CatalogSyncUiState> = combine(
        combine(
            combine(authFlow, busy, lastErrorKind, lastSuccessAt, pendingHint) { auth, isBusy, err, successAt, pending ->
                SyncInputs(auth, isBusy, err, successAt, pending)
            },
            businessDataScopeFlow,
            networkAvailableFlow
        ) { inputs, businessDataScope, networkAvailable ->
            Triple(inputs, businessDataScope, networkAvailable)
        },
        syncProgress,
        catalogSyncSummaryForUi,
        lastHistorySessionSyncSummary,
        incrementalDetailSurface
    ) { scopedInputs, progress, summary, historySessionSummary, incrementalSurface ->
        buildUi(
            scopedInputs.first.auth,
            scopedInputs.first.isBusy,
            scopedInputs.first.err,
            scopedInputs.first.successAt,
            scopedInputs.first.pending,
            progress,
            summary,
            historySessionSummary,
            incrementalSurface,
            scopedInputs.second,
            scopedInputs.third
        ).copy(businessDataScopeStatus = scopedInputs.second.status)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildUi(
            authFlow.value,
            false,
            null,
            null,
            false,
            CatalogSyncProgressState.idle(),
            null,
            null,
            CatalogIncrementalDetailSurface.OTHER,
            syncStateTracker?.businessDataScopeState?.value
                ?: Task126BusinessDataScopeState.unmanagedAllowed(),
            syncStateTracker?.networkAvailable?.value
        ).copy(
            businessDataScopeStatus = syncStateTracker?.businessDataScopeState?.value?.status
                ?: Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
        )
    )

    val localDatabaseStatusUi: StateFlow<LocalDatabaseStatusUiState> = combine(
        localDatabaseStatusSnapshot,
        localDatabaseStatusLoading,
        lastSuccessAt,
        lastCatalogSyncSummary,
        businessDataScopeFlow
    ) { snapshot, loading, successAt, summary, businessDataScope ->
        val effectiveSnapshot = businessDataScope.localSnapshot ?: snapshot
        val needsReconciliation = effectiveSnapshot != null && summary != null &&
            (
                summary.hasCatalogCountDrift(effectiveSnapshot) ||
                    summary.hasPriceCountDrift(effectiveSnapshot.priceHistoryRows)
                )
        LocalDatabaseStatusUiState(
            productsCount = effectiveSnapshot?.products,
            suppliersCount = effectiveSnapshot?.suppliers,
            categoriesCount = effectiveSnapshot?.categories,
            priceHistoryCount = effectiveSnapshot?.priceHistoryRows,
            historySessionsCount = effectiveSnapshot?.historySessions,
            pendingLocalChangesCount = effectiveSnapshot?.pendingLocalChanges,
            syncEventOutboxPendingCount = effectiveSnapshot?.syncEventOutboxPending,
            lastSyncText = successAt?.let(::formatTime),
            isLoading = loading && effectiveSnapshot == null,
            needsReconciliation = needsReconciliation,
            businessDataScopeStatus = businessDataScope.status
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LocalDatabaseStatusUiState(isLoading = true)
    )

    init {
        viewModelScope.launch {
            combine(authFlow, businessDataScopeFlow) { auth, businessDataScope ->
                auth to businessDataScope
            }.collect { (state, businessDataScope) ->
                refreshLocalDatabaseStatus()
                if (
                    state is AuthState.SignedIn &&
                    businessDataScope.allowsCloudSync &&
                    isBusinessDataScopeAllowedFor(state)
                ) {
                    runAutomaticSessionBootstrapIfNeeded(state.userId)
                } else {
                    automaticSessionBootstrapScopeKey = null
                }
            }
        }
        viewModelScope.launch {
            combine(authFlow, trackerOutcomeFlow) { auth, outcome ->
                if (auth is AuthState.SignedIn && outcome?.ownerUserId == auth.userId) {
                    outcome
                } else {
                    null
                }
            }.collect { outcome ->
                if (outcome != null) {
                    applyTrackerOutcome(outcome)
                }
            }
        }
        refreshLocalDatabaseStatus()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)

    private fun quantityStr(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
        getApplication<Application>().resources.getQuantityString(id, quantity, *args)

    private fun buildProgressUi(progress: CatalogSyncProgressState?): CatalogSyncStageUiState? {
        if (progress == null || !progress.isBusy) return null
        val message = when (progress.stage) {
            CatalogSyncStage.REALIGN -> str(R.string.catalog_cloud_stage_realign)
            CatalogSyncStage.PUSH_SUPPLIERS -> stageMessage(
                progress,
                R.string.catalog_cloud_stage_push_suppliers,
                R.string.catalog_cloud_stage_push_suppliers_count
            )
            CatalogSyncStage.PUSH_CATEGORIES -> stageMessage(
                progress,
                R.string.catalog_cloud_stage_push_categories,
                R.string.catalog_cloud_stage_push_categories_count
            )
            CatalogSyncStage.PUSH_PRODUCTS -> stageMessage(
                progress,
                R.string.catalog_cloud_stage_push_products,
                R.string.catalog_cloud_stage_push_products_count
            )
            CatalogSyncStage.PULL_CATALOG -> str(R.string.catalog_cloud_stage_pull_catalog)
            CatalogSyncStage.SYNC_PRICES -> stageMessage(
                progress,
                R.string.catalog_cloud_stage_sync_prices,
                R.string.catalog_cloud_stage_sync_prices_count
            )
            CatalogSyncStage.SYNC_PRICES_PUSH -> stageMessage(
                progress,
                R.string.catalog_cloud_stage_sync_prices_push,
                R.string.catalog_cloud_stage_sync_prices_push_count
            )
            CatalogSyncStage.SYNC_PRICES_PULL -> stageMessage(
                progress,
                R.string.catalog_cloud_stage_sync_prices_pull,
                R.string.catalog_cloud_stage_sync_prices_pull_count
            )
            CatalogSyncStage.SYNC_EVENTS_DRAIN -> str(R.string.catalog_cloud_stage_sync_events_drain)
            CatalogSyncStage.SYNC_HISTORY -> str(R.string.catalog_cloud_stage_sync_history)
            CatalogSyncStage.DEVICE_STATUS -> str(R.string.catalog_cloud_stage_device_status)
            CatalogSyncStage.IDLE,
            CatalogSyncStage.COMPLETED -> str(R.string.catalog_cloud_state_syncing)
        }
        return CatalogSyncStageUiState(
            stage = progress.stage,
            message = message,
            current = progress.current,
            total = progress.total
        )
    }

    private fun stageMessage(
        progress: CatalogSyncProgressState,
        @StringRes defaultMessage: Int,
        @StringRes countedMessage: Int
    ): String {
        val current = progress.current
        val total = progress.total
        return if (current != null && total != null && total > 0) {
            str(countedMessage, current.coerceAtMost(total), total)
        } else {
            str(defaultMessage)
        }
    }

    private fun buildUi(
        auth: AuthState,
        isBusy: Boolean,
        err: ErrorKind?,
        successAt: Long?,
        pending: Boolean,
        progress: CatalogSyncProgressState,
        lastSummary: CatalogSyncSummary?,
        lastHistorySessionSummary: HistorySessionCloudUiSummary?,
        incrementalSurface: CatalogIncrementalDetailSurface,
        businessDataScope: Task126BusinessDataScopeState,
        networkAvailable: Boolean?
    ): CatalogSyncUiState {
        if (!remote.isConfigured) {
            return CatalogSyncUiState(
                primaryMessage = str(R.string.catalog_cloud_not_configured),
                catalogDetail = null,
                sessionDetail = null,
                isSyncing = false,
                canRefresh = false,
                canQuickSync = false
            )
        }

        when (businessDataScope.status) {
            Task126BusinessDataScopeStatus.CHECKING ->
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.business_scope_checking),
                    catalogDetail = null,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false,
                    businessDataScopeStatus = businessDataScope.status
                )
            Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND ->
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.business_scope_paused_title),
                    catalogDetail = str(R.string.business_scope_unbound_review_message),
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false,
                    businessDataScopeStatus = businessDataScope.status
                )
            Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH ->
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.business_scope_paused_title),
                    catalogDetail = str(R.string.business_scope_account_mismatch_message),
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false,
                    businessDataScopeStatus = businessDataScope.status
                )
            Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH ->
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.business_scope_paused_title),
                    catalogDetail = str(R.string.business_scope_shop_mismatch_message),
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false,
                    businessDataScopeStatus = businessDataScope.status
                )
            Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH ->
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.business_scope_paused_title),
                    catalogDetail = str(R.string.business_scope_schema_mismatch_message),
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false,
                    businessDataScopeStatus = businessDataScope.status
                )
            Task126BusinessDataScopeStatus.ERROR_RECOVERABLE ->
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.business_scope_recoverable_error),
                    catalogDetail = null,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false,
                    businessDataScopeStatus = businessDataScope.status
                )
            Task126BusinessDataScopeStatus.READY,
            Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED -> Unit
        }

        if (auth is AuthState.SignedIn && networkAvailable == false) {
            return CatalogSyncUiState(
                primaryMessage = str(R.string.catalog_cloud_state_offline),
                catalogDetail = null,
                sessionDetail = buildHistorySessionSecondary(lastHistorySessionSummary),
                isSyncing = false,
                canRefresh = true,
                canQuickSync = quickSyncLaneAvailable,
                quickSyncBodyRes = buildQuickSyncBodyRes(lastSummary),
                statusBadges = buildStatusBadges(
                    progress,
                    lastSummary,
                    lastHistorySessionSummary,
                    pending,
                    incrementalSurface
                ),
                fullSyncRecommended = shouldRecommendFullSync(err, lastSummary, incrementalSurface),
                quickSyncRecommended = false,
                businessDataScopeStatus = businessDataScope.status
            )
        }

        if (isBusy) {
            val stageUi = buildProgressUi(progress.takeIf { it.isBusy })
            return CatalogSyncUiState(
                primaryMessage = stageUi?.message ?: str(R.string.catalog_cloud_state_syncing),
                catalogDetail = null,
                sessionDetail = null,
                isSyncing = true,
                canRefresh = false,
                canQuickSync = false,
                statusBadges = buildStatusBadges(
                    progress,
                    lastSummary,
                    lastHistorySessionSummary,
                    pending,
                    incrementalSurface
                ),
                progress = stageUi
            )
        }

        when (auth) {
            is AuthState.Checking -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.account_checking),
                    catalogDetail = null,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false
                )
            }
            is AuthState.SignedOut -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_sign_in_required),
                    catalogDetail = null,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false
                )
            }
            is AuthState.ErrorRecoverable -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_session_required),
                    catalogDetail = auth.message,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false,
                    canQuickSync = false
                )
            }
            is AuthState.SignedIn -> {
                val sessionDetailOnly = buildHistorySessionSecondary(lastHistorySessionSummary)
                val canQuick = quickSyncLaneAvailable
                val quickBodyRes = buildQuickSyncBodyRes(lastSummary)
                val statusBadges = buildStatusBadges(
                    progress,
                    lastSummary,
                    lastHistorySessionSummary,
                    pending,
                    incrementalSurface
                )
                val fullSyncRecommended =
                    shouldRecommendFullSync(err, lastSummary, incrementalSurface)
                val quickSyncRecommended = pending && canQuick && !fullSyncRecommended
                when (err) {
                    ErrorKind.Offline -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_offline),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended
                        )
                    }
                    ErrorKind.Session -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_session_required),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended
                        )
                    }
                    ErrorKind.Forbidden -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_forbidden),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended
                        )
                    }
                    ErrorKind.DeviceBlocked -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_device_blocked),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = false,
                            quickSyncRecommended = false
                        )
                    }
                    ErrorKind.NotFoundOrConfig -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_config_problem),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended
                        )
                    }
                    ErrorKind.CatalogOkPricesIncomplete -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_prices_incomplete),
                            catalogDetail = buildCatalogDetail(
                                successAt,
                                lastSummary,
                                pending,
                                incrementalSurface
                            ),
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended,
                            showAutomaticSyncDetail = true
                        )
                    }
                    ErrorKind.HistorySessionsIncomplete -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_sessions_incomplete),
                            catalogDetail = buildCatalogDetail(
                                successAt,
                                lastSummary,
                                pending,
                                incrementalSurface
                            ),
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended,
                            showAutomaticSyncDetail = true
                        )
                    }
                    ErrorKind.Generic -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_last_failed),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true,
                            canQuickSync = canQuick,
                            quickSyncBodyRes = quickBodyRes,
                            statusBadges = statusBadges,
                            fullSyncRecommended = fullSyncRecommended,
                            quickSyncRecommended = quickSyncRecommended
                        )
                    }
                    null -> { /* below */ }
                }
                val catalogDetail =
                    buildCatalogDetail(successAt, lastSummary, pending, incrementalSurface)
                val sessionDetail = sessionDetailOnly
                if (pending || lastHistorySessionSummary?.hasPendingWork == true) {
                    return CatalogSyncUiState(
                        primaryMessage = str(R.string.catalog_cloud_state_sending_changes),
                        catalogDetail = catalogDetail,
                        sessionDetail = sessionDetail,
                        isSyncing = false,
                        canRefresh = true,
                        canQuickSync = canQuick,
                        quickSyncBodyRes = quickBodyRes,
                        statusBadges = statusBadges,
                        fullSyncRecommended = fullSyncRecommended,
                        quickSyncRecommended = quickSyncRecommended,
                        showAutomaticSyncDetail = true
                    )
                }
                if (successAt == null) {
                    return CatalogSyncUiState(
                        primaryMessage = str(R.string.catalog_cloud_auto_status_title),
                        catalogDetail = catalogDetail,
                        sessionDetail = sessionDetail,
                        isSyncing = false,
                        canRefresh = true,
                        canQuickSync = canQuick,
                        quickSyncBodyRes = quickBodyRes,
                        statusBadges = statusBadges,
                        fullSyncRecommended = fullSyncRecommended,
                        quickSyncRecommended = quickSyncRecommended,
                        showAutomaticSyncDetail = true
                    )
                }
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_synced),
                    catalogDetail = catalogDetail,
                    sessionDetail = sessionDetail,
                    isSyncing = false,
                    canRefresh = true,
                    canQuickSync = canQuick,
                    quickSyncBodyRes = quickBodyRes,
                    statusBadges = statusBadges,
                    fullSyncRecommended = fullSyncRecommended,
                    quickSyncRecommended = quickSyncRecommended,
                    showAutomaticSyncDetail = true
                )
            }
        }
    }

    private fun buildQuickSyncBodyRes(lastSummary: CatalogSyncSummary?): Int =
        if (lastSummary?.syncEventsAvailable == true && !lastSummary.syncEventsFallback044) {
            R.string.catalog_cloud_sync_quick_body_events
        } else {
            R.string.catalog_cloud_sync_quick_body
        }

    private fun shouldRecommendFullSync(
        err: ErrorKind?,
        lastSummary: CatalogSyncSummary?,
        incrementalSurface: CatalogIncrementalDetailSurface
    ): Boolean =
        err == ErrorKind.CatalogOkPricesIncomplete ||
            err == ErrorKind.HistorySessionsIncomplete ||
            lastSummary?.manualFullSyncRequired == true ||
            (
                incrementalSurface == CatalogIncrementalDetailSurface.AFTER_QUICK_SUCCESS &&
                    lastSummary?.incrementalRemoteSubsetVerifiable == false
                )

    private fun buildStatusBadges(
        progress: CatalogSyncProgressState,
        lastSummary: CatalogSyncSummary?,
        historySessionSummary: HistorySessionCloudUiSummary?,
        pendingCatalogWork: Boolean,
        incrementalSurface: CatalogIncrementalDetailSurface
    ): List<CatalogSyncBadgeUiState> {
        val badges = mutableListOf<CatalogSyncBadgeUiState>()
        fun add(@StringRes labelRes: Int) {
            if (badges.none { it.labelRes == labelRes }) {
                badges.add(CatalogSyncBadgeUiState(labelRes))
            }
        }

        if (progress.isBusy) {
            when (progress.stage) {
                CatalogSyncStage.REALIGN -> add(R.string.catalog_cloud_badge_complete)
                CatalogSyncStage.PUSH_SUPPLIERS,
                CatalogSyncStage.PUSH_CATEGORIES,
                CatalogSyncStage.PUSH_PRODUCTS -> add(R.string.catalog_cloud_badge_upload)
                CatalogSyncStage.SYNC_PRICES_PUSH -> add(R.string.catalog_cloud_badge_upload)
                CatalogSyncStage.PULL_CATALOG,
                CatalogSyncStage.SYNC_PRICES,
                CatalogSyncStage.SYNC_PRICES_PULL,
                CatalogSyncStage.SYNC_EVENTS_DRAIN -> add(R.string.catalog_cloud_badge_download)
                CatalogSyncStage.SYNC_HISTORY -> add(R.string.catalog_cloud_badge_sessions)
                CatalogSyncStage.DEVICE_STATUS -> add(R.string.catalog_cloud_badge_upload)
                CatalogSyncStage.IDLE,
                CatalogSyncStage.COMPLETED -> Unit
            }
        }

        lastSummary?.let { summary ->
            val fullMode = summary.fullCatalogFetch || summary.fullPriceFetch
            val pushedLocal = summary.pushedSuppliers + summary.pushedCategories +
                summary.pushedProducts + summary.pushedProductPrices
            val receivedRemote = summary.pulledSuppliers + summary.pulledCategories +
                summary.pulledProducts + summary.pulledProductPrices +
                summary.targetedProductsFetched + summary.targetedPricesFetched +
                summary.remoteUpdatesApplied

            if (summary.manualFullSyncRequired ||
                (
                    incrementalSurface == CatalogIncrementalDetailSurface.AFTER_QUICK_SUCCESS &&
                        !summary.incrementalRemoteSubsetVerifiable
                    )
            ) {
                add(R.string.catalog_cloud_badge_full_required)
            } else if (fullMode) {
                add(R.string.catalog_cloud_badge_complete)
            } else {
                add(R.string.catalog_cloud_badge_quick)
            }
            if (pendingCatalogWork || pushedLocal > 0 || summary.syncEventOutboxPending > 0) {
                add(R.string.catalog_cloud_badge_upload)
            }
            if (receivedRemote > 0) {
                add(R.string.catalog_cloud_badge_download)
            }
        }

        if (pendingCatalogWork) {
            add(R.string.catalog_cloud_badge_upload)
        }
        if (historySessionSummary?.hasVisibleWork == true) {
            add(R.string.catalog_cloud_badge_sessions)
        }

        return badges
    }

    private fun formatTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    /**
     * Catalog & prices detail only (last ok, pending-catalog hint, price stats including skipped
     * remote rows). History session backup lines live in [buildHistorySessionSecondary].
     */
    private fun buildCatalogDetail(
        successAt: Long?,
        lastSummary: CatalogSyncSummary?,
        pendingCatalogWork: Boolean,
        incrementalSurface: CatalogIncrementalDetailSurface
    ): String? {
        val parts = mutableListOf<String>()
        if (pendingCatalogWork) {
            parts.add(str(R.string.catalog_cloud_pending_catalog_hint))
        }
        lastSummary?.let { s ->
            if (s.manualFullSyncRequired) {
                parts.add(str(R.string.catalog_cloud_manual_full_sync_required_hint))
            } else if (!s.incrementalRemoteSubsetVerifiable &&
                incrementalSurface == CatalogIncrementalDetailSurface.AFTER_QUICK_SUCCESS
            ) {
                parts.add(str(R.string.catalog_cloud_remote_incremental_not_verifiable_hint))
            }
            if (incrementalSurface == CatalogIncrementalDetailSurface.AFTER_QUICK_SUCCESS) {
                val pushedLocal =
                    s.pushedSuppliers + s.pushedCategories + s.pushedProducts + s.pushedProductPrices
                if (pushedLocal > 0) {
                    parts.add(
                        quantityStr(
                            R.plurals.catalog_cloud_quick_sync_locals_sent,
                            pushedLocal,
                            pushedLocal
                        )
                    )
                }
            }
        }
        successAt?.let { parts.add(str(R.string.catalog_cloud_last_ok, formatTime(it))) }
        lastSummary?.let { s ->
            val hasPriceStats = s.pushedProductPrices > 0 || s.pulledProductPrices > 0 ||
                s.deferredProductPricesNoProductRef > 0
            if (hasPriceStats) {
                parts.add(
                    str(
                        R.string.catalog_cloud_prices_sync_hint,
                        s.pushedProductPrices,
                        s.pulledProductPrices,
                        s.deferredProductPricesNoProductRef
                    )
                )
            }
            if (s.skippedProductPricesPullNoProductRef > 0) {
                parts.add(
                    str(
                        R.string.catalog_cloud_prices_skipped_hint,
                        s.skippedProductPricesPullNoProductRef
                    )
                )
            }
            if (incrementalSurface == CatalogIncrementalDetailSurface.AFTER_QUICK_SUCCESS &&
                s.syncEventsAvailable &&
                !s.syncEventsFallback044 &&
                (s.syncEventsProcessed > 0 || s.remoteUpdatesApplied > 0)
            ) {
                parts.add(
                    quantityStr(
                        R.plurals.catalog_cloud_quick_sync_recent_updates,
                        s.remoteUpdatesApplied,
                        s.syncEventsProcessed,
                        s.remoteUpdatesApplied
                    )
                )
            }
            if (s.syncEventOutboxPending > 0) {
                parts.add(
                    quantityStr(
                        R.plurals.catalog_cloud_sync_event_outbox_hint,
                        s.syncEventOutboxPending,
                        s.syncEventOutboxPending
                    )
                )
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun buildHistorySessionSecondary(summary: HistorySessionCloudUiSummary?): String? {
        if (summary == null || !summary.hasVisibleWork) return null
        val parts = mutableListOf<String>()
        if (summary.restored > 0 || summary.uploaded > 0 || summary.issueCount > 0) {
            parts.add(
                str(
                    R.string.catalog_cloud_sessions_sync_hint,
                    summary.restored,
                    summary.uploaded
                )
            )
        }
        if (summary.failureCategory == SyncErrorCategory.RemoteForbiddenRls) {
            parts.add(str(R.string.catalog_cloud_sessions_permission_hint))
        } else if (summary.issueCount > 0) {
            parts.add(str(R.string.catalog_cloud_sessions_issue_hint, summary.issueCount))
        }
        if (summary.pendingCount > 0) {
            parts.add(str(R.string.catalog_cloud_sessions_pending_hint, summary.pendingCount))
        }
        return parts.joinToString("\n")
    }

    fun onOptionsScreenVisible() {
        refreshLocalDatabaseStatus()
        viewModelScope.launch {
            pendingHint.value = repository.hasCatalogCloudPendingWorkInclusive()
            val auth = authFlow.value
            if (auth is AuthState.SignedIn && sessionRemote.isConfigured) {
                val pendingSessionCount = readPendingHistorySessionCount()
                val pendingOnlySummary = pendingSessionCount
                    .takeIf { it > 0 }
                    ?.let { HistorySessionCloudUiSummary(0, 0, 0, pendingCount = it) }
                lastHistorySessionSyncSummary.value =
                    lastHistorySessionSyncSummary.value?.copy(pendingCount = pendingSessionCount)
                        ?: pendingOnlySummary
            }
        }
    }

    private fun refreshLocalDatabaseStatus() {
        viewModelScope.launch {
            localDatabaseStatusLoading.value = true
            val ownerUserId = (authFlow.value as? AuthState.SignedIn)?.userId
            try {
                localDatabaseStatusSnapshot.value =
                    repository.getLocalDatabaseStatusSnapshot(ownerUserId, currentSelectedShop())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "local_database_status outcome=fail", t)
            } finally {
                localDatabaseStatusLoading.value = false
            }
        }
    }

    private fun currentSelectedShop() = shopContextFlow?.value?.selectedShop

    private suspend fun applyTrackerOutcome(outcome: CatalogSyncOutcomeState) {
        lastCatalogSyncSummary.value = outcome.summary
        lastSuccessAt.value = System.currentTimeMillis()
        lastErrorKind.value = if (outcome.summary.priceSyncFailed) {
            ErrorKind.CatalogOkPricesIncomplete
        } else {
            null
        }
        pendingHint.value = runCatching {
            repository.hasCatalogCloudPendingWorkInclusive()
        }.getOrDefault(pendingHint.value)
        refreshLocalDatabaseStatus()
    }

    private fun startSyncProgress(source: String, firstStage: CatalogSyncStage): Long {
        lastLoggedStage = null
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "sync_start source=$source")
        setSyncProgress(CatalogSyncProgressState.running(firstStage))
        return startedAt
    }

    private fun setSyncProgress(progress: CatalogSyncProgressState) {
        syncProgress.value = progress
        syncStateTracker?.update(progress)
        if (progress.isBusy && progress.stage != lastLoggedStage) {
            Log.i(
                TAG,
                "sync_stage=${progress.stage} current=${progress.current} total=${progress.total}"
            )
            lastLoggedStage = progress.stage
        }
    }

    private fun finishSyncProgress(ok: Boolean, startedAt: Long) {
        val durationMs = System.currentTimeMillis() - startedAt
        val finalProgress = if (ok) {
            CatalogSyncProgressState.completed()
        } else {
            CatalogSyncProgressState.failed()
        }
        syncProgress.value = finalProgress
        syncStateTracker?.update(finalProgress)
        Log.i(TAG, "sync_finish ok=$ok durationMs=$durationMs")
        lastLoggedStage = null
        refreshLocalDatabaseStatus()
    }

    private suspend fun syncCatalogRepository(
        ownerUserId: String,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> {
        val progressAware = repository as? CatalogSyncProgressRepository
        return if (progressAware != null) {
            if (selectedShop == null) {
                progressAware.syncCatalogWithRemote(
                    remote = remote,
                    priceRemote = priceRemote,
                    ownerUserId = ownerUserId,
                    progressReporter = CatalogSyncProgressReporter { progress ->
                        setSyncProgress(progress)
                    }
                )
            } else {
                progressAware.syncCatalogWithRemote(
                    remote = remote,
                    priceRemote = priceRemote,
                    ownerUserId = ownerUserId,
                    selectedShop = selectedShop,
                    progressReporter = CatalogSyncProgressReporter { progress ->
                        setSyncProgress(progress)
                    }
                )
            }
        } else {
            if (selectedShop == null) {
                repository.syncCatalogWithRemote(remote, priceRemote, ownerUserId)
            } else {
                repository.syncCatalogWithRemote(remote, priceRemote, ownerUserId, selectedShop)
            }
        }
    }

    private fun publishCatalogSummary(
        ownerUserId: String,
        source: CatalogSyncFlightOwner,
        summary: CatalogSyncSummary
    ) {
        val tracker = syncStateTracker
        if (tracker != null) {
            tracker.publishSummary(ownerUserId, source, summary)
        } else {
            lastCatalogSyncSummary.value = summary
        }
    }

    private suspend fun <T> withBusinessDataScopeFlight(
        ownerUserId: String,
        selectedShop: SelectedShop?,
        block: suspend () -> T
    ): T = syncStateTracker?.withBusinessDataScopeFlight(ownerUserId, selectedShop, block) ?: block()

    private fun <T> Result<T>.throwIfBusinessDataScopeChanged(): Result<T> {
        val error = exceptionOrNull()
        if (error is Task126BusinessDataScopeChangedException) throw error
        return this
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            val auth = authFlow.value
            if (auth !is AuthState.SignedIn) return@launch
            val selectedShop = currentSelectedShop()
            if (syncStateTracker?.allowsBusinessDataScope(auth.userId, selectedShop) == false) {
                Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=business_scope_blocked")
                return@launch
            }
            if (!remote.isConfigured) return@launch
            if (busy.value) {
                Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=busy")
                return@launch
            }
            if (syncStateTracker?.tryBegin(CatalogSyncFlightOwner.MANUAL) == false) {
                Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=tracker_busy")
                return@launch
            }
            var preflightPassed = false
            try {
                if (!ensureDeviceActiveForManualSync("manual_refresh", auth.userId, selectedShop)) {
                    return@launch
                }
                if (!businessDataScopeStillAllows(auth.userId, selectedShop)) {
                    Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=business_scope_changed")
                    return@launch
                }
                preflightPassed = true
            } catch (_: Task126BusinessDataScopeChangedException) {
                Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=business_scope_changed_during_device_check")
                return@launch
            } finally {
                if (!preflightPassed) {
                    syncStateTracker?.finish(CatalogSyncFlightOwner.MANUAL)
                }
            }
            busy.value = true
            val startedAt = startSyncProgress("manual_refresh", CatalogSyncStage.REALIGN)
            lastErrorKind.value = null
            lastHistorySessionSyncSummary.value = null
            var logCatalogOk = false
            var logSummary: CatalogSyncSummary? = null
            var logErr: ErrorKind? = null
            var logFailureClassification: SyncErrorClassification? = null
            var logPendingAfter = false
            var recoveryRequired = false
            var logHistoryIssues = 0
            var logHistorySyncDurationMs: Long? = null
            var logHistoryFailureClassification: SyncErrorClassification? = null
            var requestRecoveryAfterManualFlight = false
            try {
                withBusinessDataScopeFlight(auth.userId, selectedShop) {
                try {
                val catalogResult = syncCatalogRepository(auth.userId, selectedShop)
                    .throwIfBusinessDataScopeChanged()
                setSyncProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_HISTORY))
                val historyStartedAt = System.currentTimeMillis()
                val historySessionOutcome = try {
                    runHistorySessionCloudRefresh(auth.userId, selectedShop)
                } finally {
                    logHistorySyncDurationMs = System.currentTimeMillis() - historyStartedAt
                }
                historySessionOutcome?.failure?.let { error ->
                    if (error is Task126BusinessDataScopeChangedException) throw error
                }
                logHistoryIssues = historySessionOutcome?.issueCount ?: 0
                logHistoryFailureClassification = historySessionOutcome?.failure?.let(SyncErrorClassifier::classify)
                catalogResult.fold(
                    onSuccess = { summary ->
                        logSummary = summary
                        recoveryRequired = summary.manualFullSyncRequired
                        logCatalogOk = !recoveryRequired
                        publishCatalogSummary(auth.userId, CatalogSyncFlightOwner.MANUAL, summary)
                        if (!recoveryRequired) {
                            lastSuccessAt.value = System.currentTimeMillis()
                        }
                        val err = when {
                            historySessionOutcome?.hasIssues == true -> ErrorKind.HistorySessionsIncomplete
                            summary.priceSyncFailed -> ErrorKind.CatalogOkPricesIncomplete
                            else -> null
                        }
                        lastErrorKind.value = err
                        logErr = err
                        val pendingAfter = repository.hasCatalogCloudPendingWorkInclusive()
                        pendingHint.value = pendingAfter
                        logPendingAfter = pendingAfter
                    },
                    onFailure = { e ->
                        val classification = SyncErrorClassifier.classify(e)
                        val err = classification.toErrorKind()
                        lastErrorKind.value = err
                        logErr = err
                        logFailureClassification = classification
                        val pendingAfter = repository.hasCatalogCloudPendingWorkInclusive()
                        pendingHint.value = pendingAfter
                        logPendingAfter = pendingAfter
                    }
                )
            } finally {
                busy.value = false
                incrementalDetailSurface.value = CatalogIncrementalDetailSurface.OTHER
                val ok = logCatalogOk && logErr == null && !recoveryRequired
                finishSyncProgress(ok, startedAt)
                val pendingBreakdown: CatalogCloudPendingBreakdown? =
                    try {
                        repository.getCatalogCloudPendingBreakdown()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                val pendingBreakdownSuffix = pendingBreakdown?.let { breakdown ->
                    " pendingCatalogTombstones=${breakdown.pendingCatalogTombstones} " +
                        "productPricesPendingPriceBridge=${breakdown.productPricesPendingPriceBridge} " +
                        "productPricesBlockedWithoutProductRemote=${breakdown.productPricesBlockedWithoutProductRemote} " +
                        "suppliersMissingRemoteRef=${breakdown.suppliersMissingRemoteRef} " +
                        "categoriesMissingRemoteRef=${breakdown.categoriesMissingRemoteRef} " +
                        "productsMissingRemoteRef=${breakdown.productsMissingRemoteRef} " +
                        "hasCatalogBridgeGaps=${breakdown.hasCatalogBridgeGaps}"
                }.orEmpty()
                Log.i(
                    TAG,
                    "sync_phase_durations ok=$ok syncDomain=HISTORY syncHistoryMs=$logHistorySyncDurationMs " +
                        "historyIssues=$logHistoryIssues " +
                        "sessionErrCategory=${logHistoryFailureClassification?.category}"
                )
                Log.i(
                    TAG,
                    "refresh catalogOk=$logCatalogOk errKind=$logErr priceSyncFailed=${logSummary?.priceSyncFailed} " +
                        "errCategory=${logFailureClassification?.category} httpStatus=${logFailureClassification?.httpStatus} " +
                        "postgrestCode=${logFailureClassification?.postgrestCode} " +
                        "pendingAfter=$logPendingAfter sessionIssues=$logHistoryIssues " +
                        "historySyncMs=$logHistorySyncDurationMs " +
                        "sessionErrCategory=${logHistoryFailureClassification?.category} " +
                        "sessionHttpStatus=${logHistoryFailureClassification?.httpStatus} " +
                        "pricesPushed=${logSummary?.pushedProductPrices} pricesPulled=${logSummary?.pulledProductPrices} " +
                        "pricesSkipped=${logSummary?.skippedProductPricesPullNoProductRef}" +
                        pendingBreakdownSuffix
                )
                }
                }
                if (recoveryRequired) {
                    syncStateTracker?.withBusinessDataScopeTransition {
                        val previousScope = syncStateTracker.businessDataScopeState.value.boundScope
                        syncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState(
                                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                                boundScope = previousScope,
                                errorCode = "sync_recovery_required"
                            )
                        )
                    }
                    logSummary?.let { summary ->
                        publishCatalogSummary(auth.userId, CatalogSyncFlightOwner.MANUAL, summary)
                    }
                    requestRecoveryAfterManualFlight = true
                }
            } catch (_: Task126BusinessDataScopeChangedException) {
                if (busy.value) {
                    busy.value = false
                }
                Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=business_scope_changed_during_remote")
            } finally {
                syncStateTracker?.finish(CatalogSyncFlightOwner.MANUAL)
                if (requestRecoveryAfterManualFlight) {
                    onRecoveryRequired("manual_refresh_recovery_required")
                }
            }
        }
    }

    fun syncCatalogQuick() {
        viewModelScope.launch {
            val auth = authFlow.value
            val autoRepository = autoSyncRepository
            if (auth !is AuthState.SignedIn) return@launch
            val selectedShop = currentSelectedShop()
            if (syncStateTracker?.allowsBusinessDataScope(auth.userId, selectedShop) == false) {
                Log.i(TAG, "sync_request source=manual_quick_sync outcome=ignored reason=business_scope_blocked")
                return@launch
            }
            if (!remote.isConfigured) return@launch
            if (autoRepository == null) return@launch
            if (busy.value) {
                Log.i(TAG, "sync_request source=manual_quick_sync outcome=ignored reason=busy")
                return@launch
            }
            if (syncStateTracker?.tryBegin(CatalogSyncFlightOwner.MANUAL) == false) {
                Log.i(TAG, "sync_request source=manual_quick_sync outcome=ignored reason=tracker_busy")
                return@launch
            }
            var preflightPassed = false
            try {
                if (!ensureDeviceActiveForManualSync("manual_quick_sync", auth.userId, selectedShop)) {
                    return@launch
                }
                if (!businessDataScopeStillAllows(auth.userId, selectedShop)) {
                    Log.i(TAG, "sync_request source=manual_quick_sync outcome=ignored reason=business_scope_changed")
                    return@launch
                }
                preflightPassed = true
            } catch (_: Task126BusinessDataScopeChangedException) {
                Log.i(TAG, "sync_request source=manual_quick_sync outcome=ignored reason=business_scope_changed_during_device_check")
                return@launch
            } finally {
                if (!preflightPassed) {
                    syncStateTracker?.finish(CatalogSyncFlightOwner.MANUAL)
                }
            }
            busy.value = true
            val startedAt = startSyncProgress("manual_quick_sync", CatalogSyncStage.PUSH_PRODUCTS)
            lastErrorKind.value = null
            var ok = false
            var logSummary: CatalogSyncSummary? = null
            var logErr: ErrorKind? = null
            var logFailureClassification: SyncErrorClassification? = null
            var logPendingAfter = false
            var recoveryRequired = false
            var requestRecoveryAfterManualFlight = false
            try {
                withBusinessDataScopeFlight(auth.userId, selectedShop) {
                try {
                val result = (if (syncEventRemote?.isConfigured == true) {
                    if (selectedShop == null) {
                        autoRepository.syncCatalogQuickWithEvents(
                            remote = remote,
                            priceRemote = priceRemote,
                            syncEventRemote = syncEventRemote,
                            ownerUserId = auth.userId,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                setSyncProgress(progress)
                            }
                        )
                    } else {
                        autoRepository.syncCatalogQuickWithEvents(
                            remote = remote,
                            priceRemote = priceRemote,
                            syncEventRemote = syncEventRemote,
                            ownerUserId = auth.userId,
                            selectedShop = selectedShop,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                setSyncProgress(progress)
                            }
                        )
                    }
                } else {
                    if (selectedShop == null) {
                        autoRepository.pushDirtyCatalogDeltaToRemote(
                            remote = remote,
                            priceRemote = priceRemote,
                            ownerUserId = auth.userId,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                setSyncProgress(progress)
                            }
                        )
                    } else {
                        autoRepository.pushDirtyCatalogDeltaToRemote(
                            remote = remote,
                            priceRemote = priceRemote,
                            ownerUserId = auth.userId,
                            selectedShop = selectedShop,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                setSyncProgress(progress)
                            }
                        )
                    }
                }).throwIfBusinessDataScopeChanged()
                result.fold(
                    onSuccess = { summary ->
                        logSummary = summary
                        recoveryRequired = summary.manualFullSyncRequired
                        publishCatalogSummary(auth.userId, CatalogSyncFlightOwner.MANUAL, summary)
                        incrementalDetailSurface.value = if (recoveryRequired) {
                            CatalogIncrementalDetailSurface.OTHER
                        } else {
                            CatalogIncrementalDetailSurface.AFTER_QUICK_SUCCESS
                        }
                        val err = if (summary.priceSyncFailed) ErrorKind.CatalogOkPricesIncomplete else null
                        lastErrorKind.value = err
                        logErr = err
                        val pendingAfter = repository.hasCatalogCloudPendingWorkInclusive()
                        pendingHint.value = pendingAfter
                        logPendingAfter = pendingAfter
                        ok = err == null &&
                            !recoveryRequired &&
                            summary.syncEventsSkippedDirtyLocal == 0
                    },
                    onFailure = { e ->
                        incrementalDetailSurface.value = CatalogIncrementalDetailSurface.OTHER
                        val classification = SyncErrorClassifier.classify(e)
                        val err = classification.toErrorKind()
                        lastErrorKind.value = err
                        logErr = err
                        logFailureClassification = classification
                        val pendingAfter = repository.hasCatalogCloudPendingWorkInclusive()
                        pendingHint.value = pendingAfter
                        logPendingAfter = pendingAfter
                    }
                )
            } finally {
                busy.value = false
                finishSyncProgress(ok, startedAt)
                Log.i(
                    TAG,
                    "quick_sync ok=$ok errKind=$logErr errCategory=${logFailureClassification?.category} " +
                        "httpStatus=${logFailureClassification?.httpStatus} " +
                        "postgrestCode=${logFailureClassification?.postgrestCode} pendingAfter=$logPendingAfter " +
                        "productsPushed=${logSummary?.pushedProducts} pricesPushed=${logSummary?.pushedProductPrices} " +
                        "priceSyncFailed=${logSummary?.priceSyncFailed} " +
                        "fullCatalogFetch=${logSummary?.fullCatalogFetch} fullPriceFetch=${logSummary?.fullPriceFetch} " +
                        "remoteSubsetVerifiable=${logSummary?.incrementalRemoteSubsetVerifiable} " +
                        "remoteNotVerifiableReason=${logSummary?.incrementalRemoteNotVerifiableReason} " +
                        "syncEventsAvailable=${logSummary?.syncEventsAvailable} " +
                        "syncEventsProcessed=${logSummary?.syncEventsProcessed} " +
                        "syncEventOutboxPending=${logSummary?.syncEventOutboxPending} " +
                        "targetedProductsFetched=${logSummary?.targetedProductsFetched} " +
                        "targetedPricesFetched=${logSummary?.targetedPricesFetched}"
                )
                }
                }
                if (recoveryRequired) {
                    syncStateTracker?.withBusinessDataScopeTransition {
                        val previousScope = syncStateTracker.businessDataScopeState.value.boundScope
                        syncStateTracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState(
                                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                                boundScope = previousScope,
                                errorCode = "sync_recovery_required"
                            )
                        )
                    }
                    logSummary?.let { summary ->
                        publishCatalogSummary(auth.userId, CatalogSyncFlightOwner.MANUAL, summary)
                    }
                    requestRecoveryAfterManualFlight = true
                }
            } catch (_: Task126BusinessDataScopeChangedException) {
                if (busy.value) {
                    busy.value = false
                }
                Log.i(TAG, "sync_request source=manual_quick_sync outcome=ignored reason=business_scope_changed_during_remote")
            } finally {
                syncStateTracker?.finish(CatalogSyncFlightOwner.MANUAL)
                if (requestRecoveryAfterManualFlight) {
                    onRecoveryRequired("manual_quick_sync_recovery_required")
                }
            }
        }
    }

    private suspend fun runAutomaticSessionBootstrapIfNeeded(userId: String) {
        val auth = authFlow.value as? AuthState.SignedIn ?: return
        val selectedShop = currentSelectedShop()
        if (
            auth.userId != userId ||
            syncStateTracker?.allowsBusinessDataScope(auth.userId, selectedShop) == false
        ) return
        val scopeKey = "$userId:${shopScopedStoreScope(selectedShop)}"
        if (automaticSessionBootstrapScopeKey == scopeKey) return
        if (!sessionRemote.isConfigured) return
        if (busy.value) return
        if (syncStateTracker?.tryBegin(CatalogSyncFlightOwner.BOOTSTRAP) == false) return
        if (!businessDataScopeStillAllows(auth.userId, selectedShop)) {
            syncStateTracker?.finish(CatalogSyncFlightOwner.BOOTSTRAP)
            return
        }
        automaticSessionBootstrapScopeKey = scopeKey
        busy.value = true
        val startedAt = startSyncProgress("automatic_session_bootstrap", CatalogSyncStage.SYNC_HISTORY)
        lastErrorKind.value = null
        var ok = false
        try {
            withBusinessDataScopeFlight(auth.userId, selectedShop) scopeFlight@{
            try {
            val outcome = sessionFlightOwner.withSessionFlight(SessionCloudFlightOwner.Refresh) {
                if (!businessDataScopeStillAllows(auth.userId, selectedShop)) {
                    null
                } else {
                    runHistorySessionBootstrap(selectedShop)
                }
            } ?: run {
                automaticSessionBootstrapScopeKey = null
                return@scopeFlight
            }
            ok = !outcome.hasIssues
            if (outcome.hasIssues) {
                lastErrorKind.value = outcome.failure
                    ?.let(SyncErrorClassifier::classify)
                    ?.toErrorKind()
                    ?: ErrorKind.HistorySessionsIncomplete
            } else {
                lastErrorKind.value = null
            }
        } finally {
            busy.value = false
            finishSyncProgress(ok, startedAt)
            syncStateTracker?.finish(CatalogSyncFlightOwner.BOOTSTRAP)
            }
            }
        } catch (_: Task126BusinessDataScopeChangedException) {
            automaticSessionBootstrapScopeKey = null
            if (busy.value) {
                busy.value = false
                syncStateTracker?.finish(CatalogSyncFlightOwner.BOOTSTRAP)
            }
            Log.i(TAG, "sync_request source=automatic_session_bootstrap outcome=ignored reason=business_scope_changed_during_remote")
        }
    }

    private suspend fun runHistorySessionCloudRefresh(
        ownerUserId: String,
        selectedShop: SelectedShop?
    ): HistorySessionCloudOutcome? {
        if (!sessionRemote.isConfigured) return null
        return sessionFlightOwner.withSessionFlight(SessionCloudFlightOwner.Refresh) {
            if (!businessDataScopeStillAllows(ownerUserId, selectedShop)) {
                return@withSessionFlight null
            }
            val bootstrapOutcome = runHistorySessionBootstrap(selectedShop)
            if (bootstrapOutcome.bootstrap == null) return@withSessionFlight bootstrapOutcome
            if (!businessDataScopeStillAllows(ownerUserId, selectedShop)) {
                return@withSessionFlight bootstrapOutcome
            }
            val push = if (selectedShop == null) {
                repository.pushHistorySessionsToRemote(sessionRemote, ownerUserId)
            } else {
                repository.pushHistorySessionsToRemote(
                    sessionRemote,
                    ownerUserId,
                    selectedShop = selectedShop
                )
            }
            val outcome = HistorySessionCloudOutcome(
                bootstrap = bootstrapOutcome.bootstrap,
                push = push.getOrNull(),
                failure = push.exceptionOrNull()
            )
            lastHistorySessionSyncSummary.value =
                outcome.toUiSummary(pendingCount = readPendingHistorySessionCount())
            outcome
        }
    }

    private suspend fun runHistorySessionBootstrap(
        selectedShop: SelectedShop?
    ): HistorySessionCloudOutcome {
        val bootstrap = if (selectedShop == null) {
            repository.bootstrapHistorySessionsFromRemote(sessionRemote)
        } else {
            repository.bootstrapHistorySessionsFromRemote(
                sessionRemote,
                selectedShop = selectedShop
            )
        }
        val outcome = HistorySessionCloudOutcome(
            bootstrap = bootstrap.getOrNull(),
            push = null,
            failure = bootstrap.exceptionOrNull()
        )
        lastHistorySessionSyncSummary.value =
            outcome.toUiSummary(pendingCount = readPendingHistorySessionCount())
        return outcome
    }

    private suspend fun readPendingHistorySessionCount(): Int =
        try {
            repository.getPendingHistorySessionPushUids().size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            0
        }

    private suspend fun ensureDeviceActiveForManualSync(
        reason: String,
        ownerUserId: String,
        selectedShop: SelectedShop?
    ): Boolean = withBusinessDataScopeFlight(ownerUserId, selectedShop) {
        val authorization = deviceAuthorization ?: return@withBusinessDataScopeFlight true
        val result = authorization.ensureActiveForCloudWrite(reason, selectedShop?.shopId)
        syncStateTracker?.requireCurrentBusinessDataScope()
        if (result.isSuccess) return@withBusinessDataScopeFlight true

        val snapshot = (result.exceptionOrNull() as? ShopDeviceAuthorizationBlockedException)?.snapshot
        lastErrorKind.value = ErrorKind.DeviceBlocked
        setSyncProgress(CatalogSyncProgressState.failed(CatalogSyncStage.DEVICE_STATUS))
        pendingHint.value = runCatching {
            repository.hasCatalogCloudPendingWorkInclusive()
        }.getOrDefault(pendingHint.value)
        Log.w(
            TAG,
            "sync_request source=$reason outcome=blocked_by_device_status " +
                "status=${snapshot?.status ?: "unknown"} code=${snapshot?.code ?: "unknown"}"
        )
        false
    }

    private fun isBusinessDataScopeAllowedFor(auth: AuthState.SignedIn): Boolean =
        syncStateTracker?.allowsBusinessDataScope(auth.userId, currentSelectedShop()) ?: true

    private fun businessDataScopeStillAllows(
        ownerUserId: String,
        capturedShop: SelectedShop?
    ): Boolean {
        val currentAuth = authFlow.value as? AuthState.SignedIn ?: return false
        val currentShop = currentSelectedShop()
        return currentAuth.userId == ownerUserId &&
            shopScopedStoreScope(currentShop) == shopScopedStoreScope(capturedShop) &&
            syncStateTracker?.allowsBusinessDataScope(ownerUserId, currentShop) != false
    }

    private fun SyncErrorClassification.toErrorKind(): ErrorKind =
        when (category) {
            SyncErrorCategory.NetworkOfflineOrTimeout -> ErrorKind.Offline
            SyncErrorCategory.AuthSession -> ErrorKind.Session
            SyncErrorCategory.RemoteForbiddenRls -> ErrorKind.Forbidden
            SyncErrorCategory.RemoteNotFoundOrConfig,
            SyncErrorCategory.RemoteSchemaUnexpected -> ErrorKind.NotFoundOrConfig
            SyncErrorCategory.PayloadValidation,
            SyncErrorCategory.Unknown -> ErrorKind.Generic
        }

    companion object {
        private const val TAG = "CatalogCloudSync"

        fun factory(app: MerchandiseControlApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CatalogSyncViewModel(
                        app,
                        app.repository,
                        app.catalogRemoteDataSource,
                        app.productPriceRemoteDataSource,
                        app.sessionBackupRemoteDataSource,
                        app.authManager.state,
                        app.sessionCloudSessionFlightOwner,
                        app.catalogSyncStateTracker,
                        syncEventRemote = app.syncEventRemoteDataSource,
                        deviceAuthorization = app.shopDeviceAuthorizationRepository,
                        shopContextFlow = app.shopContextRepository.state,
                        onRecoveryRequired = app::requestPendingBusinessRecovery
                    ) as T
            }
    }
}

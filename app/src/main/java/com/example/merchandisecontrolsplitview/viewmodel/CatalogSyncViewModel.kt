package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogCloudPendingBreakdown
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressRepository
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressState
import com.example.merchandisecontrolsplitview.data.CatalogSyncStage
import com.example.merchandisecontrolsplitview.data.CatalogSyncStateTracker
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
import com.example.merchandisecontrolsplitview.data.HistorySessionBackupPushSummary
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.RemoteSessionBatchResult
import com.example.merchandisecontrolsplitview.data.SessionCloudFlightOwner
import com.example.merchandisecontrolsplitview.data.SessionCloudSessionFlightOwner
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SyncErrorClassification
import com.example.merchandisecontrolsplitview.data.SyncErrorCategory
import com.example.merchandisecontrolsplitview.data.SyncErrorClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val progress: CatalogSyncStageUiState? = null
)

data class CatalogSyncStageUiState(
    val stage: CatalogSyncStage,
    val message: String,
    val current: Int?,
    val total: Int?
)

class CatalogSyncViewModel(
    application: Application,
    private val repository: InventoryRepository,
    private val remote: CatalogRemoteDataSource,
    private val priceRemote: ProductPriceRemoteDataSource,
    private val sessionRemote: SessionBackupRemoteDataSource,
    private val authFlow: StateFlow<AuthState>,
    private val sessionFlightOwner: SessionCloudSessionFlightOwner = SessionCloudSessionFlightOwner(),
    private val syncStateTracker: CatalogSyncStateTracker? = null
) : AndroidViewModel(application) {

    private enum class ErrorKind {
        Offline,
        Session,
        Forbidden,
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
    private val lastHistorySessionSyncSummary = MutableStateFlow<HistorySessionCloudUiSummary?>(null)
    private var automaticSessionBootstrapUserId: String? = null
    private var lastLoggedStage: CatalogSyncStage? = null

    val uiState: StateFlow<CatalogSyncUiState> = combine(
        combine(authFlow, busy, lastErrorKind, lastSuccessAt, pendingHint) { auth, isBusy, err, successAt, pending ->
            SyncInputs(auth, isBusy, err, successAt, pending)
        },
        syncProgress,
        lastCatalogSyncSummary,
        lastHistorySessionSyncSummary
    ) { inputs, progress, summary, historySessionSummary ->
        buildUi(
            inputs.auth,
            inputs.isBusy,
            inputs.err,
            inputs.successAt,
            inputs.pending,
            progress,
            summary,
            historySessionSummary
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildUi(authFlow.value, false, null, null, false, CatalogSyncProgressState.idle(), null, null)
    )

    init {
        viewModelScope.launch {
            authFlow.collect { state ->
                when (state) {
                    is AuthState.SignedIn -> runAutomaticSessionBootstrapIfNeeded(state.userId)
                    else -> automaticSessionBootstrapUserId = null
                }
            }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)

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
            CatalogSyncStage.SYNC_HISTORY -> str(R.string.catalog_cloud_stage_sync_history)
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
        lastHistorySessionSummary: HistorySessionCloudUiSummary?
    ): CatalogSyncUiState {
        if (!remote.isConfigured) {
            return CatalogSyncUiState(
                primaryMessage = str(R.string.catalog_cloud_not_configured),
                catalogDetail = null,
                sessionDetail = null,
                isSyncing = false,
                canRefresh = false
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
                    canRefresh = false
                )
            }
            is AuthState.SignedOut -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_sign_in_required),
                    catalogDetail = null,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false
                )
            }
            is AuthState.ErrorRecoverable -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_session_required),
                    catalogDetail = auth.message,
                    sessionDetail = null,
                    isSyncing = false,
                    canRefresh = false
                )
            }
            is AuthState.SignedIn -> {
                val sessionDetailOnly = buildHistorySessionSecondary(lastHistorySessionSummary)
                when (err) {
                    ErrorKind.Offline -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_offline),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Session -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_session_required),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Forbidden -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_forbidden),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.NotFoundOrConfig -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_config_problem),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.CatalogOkPricesIncomplete -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_prices_incomplete),
                            catalogDetail = buildCatalogDetail(successAt, lastSummary, pending),
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.HistorySessionsIncomplete -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_sessions_incomplete),
                            catalogDetail = buildCatalogDetail(successAt, lastSummary, pending),
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Generic -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_last_failed),
                            catalogDetail = null,
                            sessionDetail = sessionDetailOnly,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    null -> { /* below */ }
                }
                val catalogDetail =
                    buildCatalogDetail(successAt, lastSummary, pending)
                val sessionDetail = sessionDetailOnly
                if (pending || lastHistorySessionSummary?.hasPendingWork == true) {
                    return CatalogSyncUiState(
                        primaryMessage = str(R.string.catalog_cloud_state_pending),
                        catalogDetail = catalogDetail,
                        sessionDetail = sessionDetail,
                        isSyncing = false,
                        canRefresh = true
                    )
                }
                if (successAt == null) {
                    return CatalogSyncUiState(
                        primaryMessage = str(R.string.catalog_cloud_state_pending),
                        catalogDetail = catalogDetail,
                        sessionDetail = sessionDetail,
                        isSyncing = false,
                        canRefresh = true
                    )
                }
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_synced),
                    catalogDetail = catalogDetail,
                    sessionDetail = sessionDetail,
                    isSyncing = false,
                    canRefresh = true
                )
            }
        }
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
        pendingCatalogWork: Boolean
    ): String? {
        val parts = mutableListOf<String>()
        if (pendingCatalogWork) {
            parts.add(str(R.string.catalog_cloud_pending_catalog_hint))
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
    }

    private suspend fun syncCatalogRepository(ownerUserId: String): Result<CatalogSyncSummary> {
        val progressAware = repository as? CatalogSyncProgressRepository
        return if (progressAware != null) {
            progressAware.syncCatalogWithRemote(
                remote = remote,
                priceRemote = priceRemote,
                ownerUserId = ownerUserId,
                progressReporter = CatalogSyncProgressReporter { progress ->
                    setSyncProgress(progress)
                }
            )
        } else {
            repository.syncCatalogWithRemote(remote, priceRemote, ownerUserId)
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            val auth = authFlow.value
            if (auth !is AuthState.SignedIn) return@launch
            if (!remote.isConfigured) return@launch
            if (busy.value) {
                Log.i(TAG, "sync_request source=manual_refresh outcome=ignored reason=busy")
                return@launch
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
            var logHistoryIssues = 0
            var logHistorySyncDurationMs: Long? = null
            var logHistoryFailureClassification: SyncErrorClassification? = null
            try {
                val catalogResult = syncCatalogRepository(auth.userId)
                setSyncProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_HISTORY))
                val historyStartedAt = System.currentTimeMillis()
                val historySessionOutcome = try {
                    runHistorySessionCloudRefresh(auth.userId)
                } finally {
                    logHistorySyncDurationMs = System.currentTimeMillis() - historyStartedAt
                }
                logHistoryIssues = historySessionOutcome?.issueCount ?: 0
                logHistoryFailureClassification = historySessionOutcome?.failure?.let(SyncErrorClassifier::classify)
                catalogResult.fold(
                    onSuccess = { summary ->
                        logCatalogOk = true
                        logSummary = summary
                        lastCatalogSyncSummary.value = summary
                        lastSuccessAt.value = System.currentTimeMillis()
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
                val ok = logCatalogOk && logErr == null
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
    }

    private suspend fun runAutomaticSessionBootstrapIfNeeded(userId: String) {
        if (automaticSessionBootstrapUserId == userId) return
        automaticSessionBootstrapUserId = userId
        if (!sessionRemote.isConfigured) return
        if (busy.value) return
        busy.value = true
        val startedAt = startSyncProgress("automatic_session_bootstrap", CatalogSyncStage.SYNC_HISTORY)
        lastErrorKind.value = null
        var ok = false
        try {
            val outcome = sessionFlightOwner.withSessionFlight(SessionCloudFlightOwner.Refresh) {
                runHistorySessionBootstrap()
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
        }
    }

    private suspend fun runHistorySessionCloudRefresh(ownerUserId: String): HistorySessionCloudOutcome? {
        if (!sessionRemote.isConfigured) return null
        return sessionFlightOwner.withSessionFlight(SessionCloudFlightOwner.Refresh) {
            val bootstrapOutcome = runHistorySessionBootstrap()
            if (bootstrapOutcome.bootstrap == null) return@withSessionFlight bootstrapOutcome
            val push = repository.pushHistorySessionsToRemote(sessionRemote, ownerUserId)
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

    private suspend fun runHistorySessionBootstrap(): HistorySessionCloudOutcome {
        val bootstrap = repository.bootstrapHistorySessionsFromRemote(sessionRemote)
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
                        app.catalogSyncStateTracker
                    ) as T
            }
    }
}

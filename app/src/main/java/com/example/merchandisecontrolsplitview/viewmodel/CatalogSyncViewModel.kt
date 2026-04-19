package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
import com.example.merchandisecontrolsplitview.data.HistorySessionBackupPushSummary
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.RemoteSessionBatchResult
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogSyncUiState(
    val primaryMessage: String,
    val secondaryMessage: String?,
    val isSyncing: Boolean,
    val canRefresh: Boolean
)

class CatalogSyncViewModel(
    application: Application,
    private val repository: InventoryRepository,
    private val remote: CatalogRemoteDataSource,
    private val priceRemote: ProductPriceRemoteDataSource,
    private val sessionRemote: SessionBackupRemoteDataSource,
    private val authFlow: StateFlow<AuthState>
) : AndroidViewModel(application) {

    private enum class ErrorKind {
        Offline,
        Session,
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
        val issueCount: Int
    ) {
        val hasVisibleWork: Boolean get() = restored > 0 || uploaded > 0 || issueCount > 0
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

        fun toUiSummary(): HistorySessionCloudUiSummary =
            HistorySessionCloudUiSummary(
                restored = (bootstrap?.inserted ?: 0) + (bootstrap?.updated ?: 0),
                uploaded = push?.uploaded ?: 0,
                issueCount = issueCount
            )
    }

    private val busy = MutableStateFlow(false)
    private val lastErrorKind = MutableStateFlow<ErrorKind?>(null)
    private val lastSuccessAt = MutableStateFlow<Long?>(null)
    private val pendingHint = MutableStateFlow(false)
    private val lastCatalogSyncSummary = MutableStateFlow<CatalogSyncSummary?>(null)
    private val lastHistorySessionSyncSummary = MutableStateFlow<HistorySessionCloudUiSummary?>(null)
    private var automaticSessionBootstrapUserId: String? = null

    val uiState: StateFlow<CatalogSyncUiState> = combine(
        combine(authFlow, busy, lastErrorKind, lastSuccessAt, pendingHint) { auth, isBusy, err, successAt, pending ->
            SyncInputs(auth, isBusy, err, successAt, pending)
        },
        lastCatalogSyncSummary,
        lastHistorySessionSyncSummary
    ) { inputs, summary, historySessionSummary ->
        buildUi(
            inputs.auth,
            inputs.isBusy,
            inputs.err,
            inputs.successAt,
            inputs.pending,
            summary,
            historySessionSummary
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildUi(authFlow.value, false, null, null, false, null, null)
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

    private fun buildUi(
        auth: AuthState,
        isBusy: Boolean,
        err: ErrorKind?,
        successAt: Long?,
        pending: Boolean,
        lastSummary: CatalogSyncSummary?,
        lastHistorySessionSummary: HistorySessionCloudUiSummary?
    ): CatalogSyncUiState {
        if (!remote.isConfigured) {
            return CatalogSyncUiState(
                primaryMessage = str(R.string.catalog_cloud_not_configured),
                secondaryMessage = null,
                isSyncing = false,
                canRefresh = false
            )
        }

        if (isBusy) {
            return CatalogSyncUiState(
                primaryMessage = str(R.string.catalog_cloud_state_syncing),
                secondaryMessage = null,
                isSyncing = true,
                canRefresh = false
            )
        }

        when (auth) {
            is AuthState.Checking -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.account_checking),
                    secondaryMessage = null,
                    isSyncing = false,
                    canRefresh = false
                )
            }
            is AuthState.SignedOut -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_sign_in_required),
                    secondaryMessage = null,
                    isSyncing = false,
                    canRefresh = false
                )
            }
            is AuthState.ErrorRecoverable -> {
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_session_required),
                    secondaryMessage = auth.message,
                    isSyncing = false,
                    canRefresh = false
                )
            }
            is AuthState.SignedIn -> {
                when (err) {
                    ErrorKind.Offline -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_offline),
                            secondaryMessage = buildHistorySessionSecondary(lastHistorySessionSummary),
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Session -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_session_required),
                            secondaryMessage = buildHistorySessionSecondary(lastHistorySessionSummary),
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.CatalogOkPricesIncomplete -> {
                        val secondary = buildSecondaryAfterSync(successAt, lastSummary, lastHistorySessionSummary)
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_prices_incomplete),
                            secondaryMessage = secondary,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.HistorySessionsIncomplete -> {
                        val secondary = buildSecondaryAfterSync(successAt, lastSummary, lastHistorySessionSummary)
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_sessions_incomplete),
                            secondaryMessage = secondary,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Generic -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_last_failed),
                            secondaryMessage = buildHistorySessionSecondary(lastHistorySessionSummary),
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    null -> { /* below */ }
                }
                val secondary = buildSecondaryAfterSync(successAt, lastSummary, lastHistorySessionSummary)
                if (pending) {
                    return CatalogSyncUiState(
                        primaryMessage = str(R.string.catalog_cloud_state_pending),
                        secondaryMessage = secondary,
                        isSyncing = false,
                        canRefresh = true
                    )
                }
                if (successAt == null) {
                    return CatalogSyncUiState(
                        primaryMessage = str(R.string.catalog_cloud_state_pending),
                        secondaryMessage = secondary,
                        isSyncing = false,
                        canRefresh = true
                    )
                }
                return CatalogSyncUiState(
                    primaryMessage = str(R.string.catalog_cloud_state_synced),
                    secondaryMessage = secondary,
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

    /** Secondary lines after a sync attempt: last-ok time + optional price stats from summary. */
    private fun buildSecondaryAfterSync(
        successAt: Long?,
        lastSummary: CatalogSyncSummary?,
        lastHistorySessionSummary: HistorySessionCloudUiSummary?
    ): String? {
        val parts = mutableListOf<String>()
        successAt?.let { parts.add(str(R.string.catalog_cloud_last_ok, formatTime(it))) }
        lastSummary?.let { s ->
            if (s.pushedProductPrices > 0 || s.pulledProductPrices > 0 ||
                s.deferredProductPricesNoProductRef > 0 || s.skippedProductPricesPullNoProductRef > 0
            ) {
                parts.add(
                    str(
                        R.string.catalog_cloud_prices_sync_hint,
                        s.pushedProductPrices,
                        s.pulledProductPrices,
                        s.deferredProductPricesNoProductRef
                    )
                )
            }
        }
        buildHistorySessionSecondary(lastHistorySessionSummary)?.let(parts::add)
        return parts.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun buildHistorySessionSecondary(summary: HistorySessionCloudUiSummary?): String? {
        if (summary == null || !summary.hasVisibleWork) return null
        val parts = mutableListOf(
            str(
                R.string.catalog_cloud_sessions_sync_hint,
                summary.restored,
                summary.uploaded
            )
        )
        if (summary.issueCount > 0) {
            parts.add(str(R.string.catalog_cloud_sessions_issue_hint, summary.issueCount))
        }
        return parts.joinToString("\n")
    }

    fun onOptionsScreenVisible() {
        viewModelScope.launch {
            pendingHint.value = repository.hasCatalogCloudPendingWorkInclusive()
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            val auth = authFlow.value
            if (auth !is AuthState.SignedIn) return@launch
            if (!remote.isConfigured) return@launch
            if (busy.value) return@launch
            busy.value = true
            lastErrorKind.value = null
            try {
                val catalogResult = repository.syncCatalogWithRemote(remote, priceRemote, auth.userId)
                val historySessionOutcome = runHistorySessionCloudRefresh(auth.userId)
                catalogResult.fold(
                    onSuccess = { summary ->
                        lastCatalogSyncSummary.value = summary
                        lastSuccessAt.value = System.currentTimeMillis()
                        lastErrorKind.value = when {
                            historySessionOutcome?.hasIssues == true -> ErrorKind.HistorySessionsIncomplete
                            summary.priceSyncFailed -> ErrorKind.CatalogOkPricesIncomplete
                            else -> null
                        }
                        pendingHint.value = repository.hasCatalogCloudPendingWorkInclusive()
                    },
                    onFailure = { e ->
                        lastErrorKind.value = classifyError(e)
                        pendingHint.value = repository.hasCatalogCloudPendingWorkInclusive()
                    }
                )
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun runAutomaticSessionBootstrapIfNeeded(userId: String) {
        if (automaticSessionBootstrapUserId == userId) return
        automaticSessionBootstrapUserId = userId
        if (!sessionRemote.isConfigured) return
        if (busy.value) return
        busy.value = true
        lastErrorKind.value = null
        try {
            val outcome = runHistorySessionBootstrap()
            if (outcome.hasIssues) {
                lastErrorKind.value = outcome.failure?.let(::classifyError)
                    ?: ErrorKind.HistorySessionsIncomplete
            } else {
                lastErrorKind.value = null
            }
        } finally {
            busy.value = false
        }
    }

    private suspend fun runHistorySessionCloudRefresh(ownerUserId: String): HistorySessionCloudOutcome? {
        if (!sessionRemote.isConfigured) return null
        val bootstrapOutcome = runHistorySessionBootstrap()
        if (bootstrapOutcome.bootstrap == null) return bootstrapOutcome
        val push = repository.pushHistorySessionsToRemote(sessionRemote, ownerUserId)
        val outcome = HistorySessionCloudOutcome(
            bootstrap = bootstrapOutcome.bootstrap,
            push = push.getOrNull(),
            failure = push.exceptionOrNull()
        )
        lastHistorySessionSyncSummary.value = outcome.toUiSummary()
        return outcome
    }

    private suspend fun runHistorySessionBootstrap(): HistorySessionCloudOutcome {
        val bootstrap = repository.bootstrapHistorySessionsFromRemote(sessionRemote)
        val outcome = HistorySessionCloudOutcome(
            bootstrap = bootstrap.getOrNull(),
            push = null,
            failure = bootstrap.exceptionOrNull()
        )
        lastHistorySessionSyncSummary.value = outcome.toUiSummary()
        return outcome
    }

    private fun classifyError(t: Throwable): ErrorKind {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                is UnknownHostException, is IOException -> return ErrorKind.Offline
                is HttpRequestTimeoutException, is SSLException -> return ErrorKind.Offline
            }
            cur = cur.cause
        }
        val msg = t.message?.lowercase().orEmpty()
        if (msg.contains("401") || msg.contains("jwt") || msg.contains("unauthorized")) {
            return ErrorKind.Session
        }
        return ErrorKind.Generic
    }

    companion object {
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
                        app.authManager.state
                    ) as T
            }
    }
}

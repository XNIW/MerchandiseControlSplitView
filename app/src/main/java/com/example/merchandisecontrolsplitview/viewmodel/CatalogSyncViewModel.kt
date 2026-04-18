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
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
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
    private val authFlow: StateFlow<AuthState>
) : AndroidViewModel(application) {

    private enum class ErrorKind {
        Offline,
        Session,
        Generic
    }

    private data class SyncInputs(
        val auth: AuthState,
        val isBusy: Boolean,
        val err: ErrorKind?,
        val successAt: Long?,
        val pending: Boolean
    )

    private val busy = MutableStateFlow(false)
    private val lastErrorKind = MutableStateFlow<ErrorKind?>(null)
    private val lastSuccessAt = MutableStateFlow<Long?>(null)
    private val pendingHint = MutableStateFlow(false)
    private val lastCatalogSyncSummary = MutableStateFlow<CatalogSyncSummary?>(null)

    val uiState: StateFlow<CatalogSyncUiState> = combine(
        combine(authFlow, busy, lastErrorKind, lastSuccessAt, pendingHint) { auth, isBusy, err, successAt, pending ->
            SyncInputs(auth, isBusy, err, successAt, pending)
        },
        lastCatalogSyncSummary
    ) { inputs, summary ->
        buildUi(inputs.auth, inputs.isBusy, inputs.err, inputs.successAt, inputs.pending, summary)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildUi(authFlow.value, false, null, null, false, null)
    )

    private fun str(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)

    private fun buildUi(
        auth: AuthState,
        isBusy: Boolean,
        err: ErrorKind?,
        successAt: Long?,
        pending: Boolean,
        lastSummary: CatalogSyncSummary?
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
                            secondaryMessage = null,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Session -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_session_required),
                            secondaryMessage = null,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    ErrorKind.Generic -> {
                        return CatalogSyncUiState(
                            primaryMessage = str(R.string.catalog_cloud_state_last_failed),
                            secondaryMessage = null,
                            isSyncing = false,
                            canRefresh = true
                        )
                    }
                    null -> { /* below */ }
                }
                val secondary = buildSecondarySuccessLine(successAt, lastSummary)
                if (pending) {
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

    private fun buildSecondarySuccessLine(successAt: Long?, lastSummary: CatalogSyncSummary?): String? {
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
        return parts.joinToString("\n").takeIf { it.isNotEmpty() }
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
            busy.value = true
            lastErrorKind.value = null
            try {
                val result = repository.syncCatalogWithRemote(remote, priceRemote, auth.userId)
                result.fold(
                    onSuccess = { summary ->
                        lastCatalogSyncSummary.value = summary
                        if (summary.priceSyncFailed) {
                            lastErrorKind.value = ErrorKind.Generic
                        } else {
                            lastSuccessAt.value = System.currentTimeMillis()
                            lastErrorKind.value = null
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
                        app.authManager.state
                    ) as T
            }
    }
}

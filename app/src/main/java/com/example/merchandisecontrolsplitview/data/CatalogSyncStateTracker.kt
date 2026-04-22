package com.example.merchandisecontrolsplitview.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CatalogSyncStage {
    IDLE,
    REALIGN,
    PUSH_SUPPLIERS,
    PUSH_CATEGORIES,
    PUSH_PRODUCTS,
    PULL_CATALOG,
    SYNC_PRICES,
    SYNC_HISTORY,
    COMPLETED
}

data class CatalogSyncProgressState(
    val stage: CatalogSyncStage = CatalogSyncStage.IDLE,
    val current: Int? = null,
    val total: Int? = null,
    val isBusy: Boolean = false
) {
    companion object {
        fun idle(): CatalogSyncProgressState =
            CatalogSyncProgressState(stage = CatalogSyncStage.IDLE, isBusy = false)

        fun running(
            stage: CatalogSyncStage,
            current: Int? = null,
            total: Int? = null
        ): CatalogSyncProgressState =
            CatalogSyncProgressState(stage = stage, current = current, total = total, isBusy = true)

        fun completed(): CatalogSyncProgressState =
            CatalogSyncProgressState(stage = CatalogSyncStage.COMPLETED, isBusy = false)
    }
}

fun interface CatalogSyncProgressReporter {
    fun onProgress(state: CatalogSyncProgressState)
}

interface CatalogSyncProgressRepository {
    suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary>
}

/**
 * Traccia se c'e una sync cloud (catalogo / sessioni) in corso, application-scoped.
 *
 * E un osservabile sottile: scritto dal `CatalogSyncViewModel`, letto dalla UI root.
 * Mantiene anche [isSyncing] per i call site esistenti, ma la fonte piu ricca e [state].
 */
class CatalogSyncStateTracker {

    private val _state = MutableStateFlow(CatalogSyncProgressState.idle())
    val state: StateFlow<CatalogSyncProgressState> = _state.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun setSyncing(syncing: Boolean) {
        update(
            if (syncing) {
                CatalogSyncProgressState.running(CatalogSyncStage.REALIGN)
            } else {
                CatalogSyncProgressState.idle()
            }
        )
    }

    fun update(next: CatalogSyncProgressState) {
        val previous = _state.value
        _state.value = next
        _isSyncing.value = next.isBusy
        if (previous.isBusy != next.isBusy) {
            Log.i("CatalogCloudSync", "tracker busy=${next.isBusy} stage=${next.stage}")
        }
    }
}

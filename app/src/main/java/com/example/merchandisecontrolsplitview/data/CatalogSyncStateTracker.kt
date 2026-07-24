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
    SYNC_PRICES_PUSH,
    SYNC_PRICES_PULL,
    SYNC_EVENTS_DRAIN,
    SYNC_HISTORY,
    DEVICE_STATUS,
    COMPLETED
}

enum class CatalogSyncStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class CatalogSyncStageGroup {
    IDLE,
    LOCAL_ANALYSIS,
    SEND_CHANGES,
    UPDATE_FROM_CLOUD,
    PRICES,
    HISTORY,
    COMPLETED
}

val CatalogSyncStage.group: CatalogSyncStageGroup
    get() = when (this) {
        CatalogSyncStage.IDLE -> CatalogSyncStageGroup.IDLE
        CatalogSyncStage.REALIGN -> CatalogSyncStageGroup.LOCAL_ANALYSIS
        CatalogSyncStage.PUSH_SUPPLIERS,
        CatalogSyncStage.PUSH_CATEGORIES,
        CatalogSyncStage.PUSH_PRODUCTS -> CatalogSyncStageGroup.SEND_CHANGES
        CatalogSyncStage.PULL_CATALOG -> CatalogSyncStageGroup.UPDATE_FROM_CLOUD
        CatalogSyncStage.SYNC_PRICES,
        CatalogSyncStage.SYNC_PRICES_PUSH,
        CatalogSyncStage.SYNC_PRICES_PULL -> CatalogSyncStageGroup.PRICES
        CatalogSyncStage.SYNC_EVENTS_DRAIN -> CatalogSyncStageGroup.UPDATE_FROM_CLOUD
        CatalogSyncStage.SYNC_HISTORY -> CatalogSyncStageGroup.HISTORY
        CatalogSyncStage.DEVICE_STATUS -> CatalogSyncStageGroup.SEND_CHANGES
        CatalogSyncStage.COMPLETED -> CatalogSyncStageGroup.COMPLETED
    }

data class CatalogSyncProgressState(
    val stage: CatalogSyncStage = CatalogSyncStage.IDLE,
    val current: Int? = null,
    val total: Int? = null,
    val isBusy: Boolean = false,
    val status: CatalogSyncStatus = when {
        isBusy -> CatalogSyncStatus.RUNNING
        stage == CatalogSyncStage.COMPLETED -> CatalogSyncStatus.COMPLETED
        else -> CatalogSyncStatus.IDLE
    }
) {
    companion object {
        fun idle(): CatalogSyncProgressState =
            CatalogSyncProgressState(
                stage = CatalogSyncStage.IDLE,
                isBusy = false,
                status = CatalogSyncStatus.IDLE
            )

        fun running(
            stage: CatalogSyncStage,
            current: Int? = null,
            total: Int? = null
        ): CatalogSyncProgressState =
            CatalogSyncProgressState(
                stage = stage,
                current = current,
                total = total,
                isBusy = true,
                status = CatalogSyncStatus.RUNNING
            )

        fun completed(): CatalogSyncProgressState =
            CatalogSyncProgressState(
                stage = CatalogSyncStage.COMPLETED,
                isBusy = false,
                status = CatalogSyncStatus.COMPLETED
            )

        fun failed(stage: CatalogSyncStage = CatalogSyncStage.COMPLETED): CatalogSyncProgressState =
            CatalogSyncProgressState(
                stage = stage,
                isBusy = false,
                status = CatalogSyncStatus.FAILED
            )
    }
}

fun interface CatalogSyncProgressReporter {
    fun onProgress(state: CatalogSyncProgressState)
}

enum class CatalogSyncFlightOwner {
    MANUAL,
    AUTO_PUSH,
    BOOTSTRAP,
    SYNC_EVENTS
}

data class CatalogSyncOutcomeState(
    val ownerUserId: String,
    val source: CatalogSyncFlightOwner,
    val summary: CatalogSyncSummary
)

interface CatalogSyncProgressRepository {
    suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary>

    suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        syncCatalogWithRemote(remote, priceRemote, ownerUserId, progressReporter)
}

interface CatalogAutoSyncRepository {
    suspend fun shouldRunCatalogBootstrap(ownerUserId: String): Boolean = true

    suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = true

    suspend fun pushDirtyCatalogDeltaToRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary>

    suspend fun pushDirtyCatalogDeltaToRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        pushDirtyCatalogDeltaToRemote(remote, priceRemote, ownerUserId, progressReporter)

    suspend fun syncCatalogQuickWithEvents(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource? = null
    ): Result<CatalogSyncSummary> =
        pushDirtyCatalogDeltaToRemote(remote, priceRemote, ownerUserId, progressReporter).map {
            it.copy(syncEventsDisabled = true, syncEventsFallback044 = true)
        }

    suspend fun syncCatalogQuickWithEvents(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource? = null,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        syncCatalogQuickWithEvents(remote, priceRemote, syncEventRemote, ownerUserId, progressReporter, sessionRemote)

    suspend fun drainSyncEventsFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource? = null
    ): Result<CatalogSyncSummary> =
        Result.success(
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = 0,
                syncEventsDisabled = true,
                syncEventsFallback044 = true
            )
        )

    suspend fun drainSyncEventsFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource? = null,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        drainSyncEventsFromRemote(remote, priceRemote, syncEventRemote, ownerUserId, progressReporter, sessionRemote)

    suspend fun pullCatalogBootstrapFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary>

    suspend fun pullCatalogBootstrapFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        pullCatalogBootstrapFromRemote(remote, priceRemote, progressReporter)
}

/**
 * Traccia se c'e una sync cloud (catalogo / sessioni) in corso, application-scoped.
 *
 * E un osservabile sottile: scritto dal `CatalogSyncViewModel`, letto dalla UI root.
 * Mantiene anche [isSyncing] per i call site esistenti, ma la fonte piu ricca e [state].
 */
class CatalogSyncStateTracker(
    initialBusinessDataScopeState: Task126BusinessDataScopeState =
        Task126BusinessDataScopeState.unmanagedAllowed()
) : Task126BusinessDataScopeRuntimeGuard {

    private val businessDataScopeFlightGate =
        Task126BusinessDataScopeFlightGate(initialBusinessDataScopeState)

    private val _state = MutableStateFlow(CatalogSyncProgressState.idle())
    val state: StateFlow<CatalogSyncProgressState> = _state.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastOutcome = MutableStateFlow<CatalogSyncOutcomeState?>(null)
    val lastOutcome: StateFlow<CatalogSyncOutcomeState?> = _lastOutcome.asStateFlow()

    private val _businessDataScopeState = MutableStateFlow(initialBusinessDataScopeState)
    val businessDataScopeState: StateFlow<Task126BusinessDataScopeState> =
        _businessDataScopeState.asStateFlow()

    /**
     * `null` finche Android non consegna il primo snapshot di rete. In questo
     * modo la UI non inventa uno stato offline durante il bootstrap, ma passa
     * immediatamente a offline quando il default network perde la capability
     * VALIDATED.
     */
    private val _networkAvailable = MutableStateFlow<Boolean?>(null)
    val networkAvailable: StateFlow<Boolean?> = _networkAvailable.asStateFlow()

    private val ownerLock = Any()
    private var activeOwner: CatalogSyncFlightOwner? = null

    fun tryBegin(owner: CatalogSyncFlightOwner): Boolean =
        synchronized(ownerLock) {
            if (activeOwner != null) return@synchronized false
            activeOwner = owner
            true
        }

    fun finish(owner: CatalogSyncFlightOwner) {
        synchronized(ownerLock) {
            if (activeOwner == owner) {
                activeOwner = null
            } else {
                Log.w("CatalogCloudSync", "tracker finish ignored owner=$owner activeOwner=$activeOwner")
            }
        }
    }

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
        if (previous.isBusy != next.isBusy || previous.status != next.status) {
            Log.i("CatalogCloudSync", "tracker busy=${next.isBusy} stage=${next.stage} status=${next.status}")
        }
    }

    fun publishSummary(
        ownerUserId: String,
        source: CatalogSyncFlightOwner,
        summary: CatalogSyncSummary
    ) {
        _lastOutcome.value = CatalogSyncOutcomeState(
            ownerUserId = ownerUserId,
            source = source,
            summary = summary
        )
    }

    fun updateBusinessDataScopeState(next: Task126BusinessDataScopeState) {
        val previous = _businessDataScopeState.value
        val scopeChanged = scopeSignature(previous) != scopeSignature(next)
        businessDataScopeFlightGate.updateState(next)
        _businessDataScopeState.value = next
        if (scopeChanged || !next.allowsCloudSync) {
            _lastOutcome.value = null
        }
        if (previous.status != next.status) {
            Log.i("CatalogCloudSync", "business_scope status=${next.status}")
        }
    }

    fun updateNetworkAvailability(available: Boolean) {
        if (_networkAvailable.value == available) return
        _networkAvailable.value = available
        Log.i("CatalogCloudSync", "network_available=$available")
    }

    fun allowsBusinessDataScope(ownerUserId: String, selectedShop: SelectedShop?): Boolean {
        return businessDataScopeFlightGate.allowsBusinessDataScope(ownerUserId, selectedShop)
    }

    override suspend fun <T> withBusinessDataScopeFlight(
        ownerUserId: String,
        selectedShop: SelectedShop?,
        block: suspend () -> T
    ): T = businessDataScopeFlightGate.withBusinessDataScopeFlight(
        ownerUserId = ownerUserId,
        selectedShop = selectedShop,
        block = block
    )

    override suspend fun <T> withCurrentBusinessDataScopeFlight(
        block: suspend () -> T
    ): T = businessDataScopeFlightGate.withCurrentBusinessDataScopeFlight(block)

    override suspend fun requireCurrentBusinessDataScope() {
        businessDataScopeFlightGate.requireCurrentBusinessDataScope()
    }

    override fun captureBusinessDataScopeSignal(
        ownerUserId: String,
        shopId: String?
    ): Task126BusinessDataScopeSignalToken =
        businessDataScopeFlightGate.captureBusinessDataScopeSignal(ownerUserId, shopId)

    override fun isCurrentBusinessDataScopeSignal(
        token: Task126BusinessDataScopeSignalToken
    ): Boolean = businessDataScopeFlightGate.isCurrentBusinessDataScopeSignal(token)

    override suspend fun cancelAndJoinBusinessDataScopeFlights() {
        businessDataScopeFlightGate.cancelAndJoinBusinessDataScopeFlights()
    }

    override suspend fun <T> withBusinessDataScopeTransition(
        block: suspend () -> T
    ): T = businessDataScopeFlightGate.withBusinessDataScopeTransition(block)

    private fun scopeSignature(state: Task126BusinessDataScopeState): String? =
        state.boundScope?.let { scope ->
            listOf(
                scope.ownerHash,
                scope.storeId,
                scope.localStoreId,
                scope.syncProtocolVersion.toString(),
                scope.schemaVersion.toString(),
                scope.storeEpoch.toString()
            ).joinToString("|")
        }
}

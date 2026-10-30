package com.example.merchandisecontrolsplitview.data

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
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
    val summary: CatalogSyncSummary,
    /** Process-local completion evidence; a diagnostic replay has no completion time. */
    val completedAtMs: Long? = null,
    /** The original admitted flight, never a scope captured after the work returned. */
    val scopeSignal: Task126BusinessDataScopeSignalToken? = null
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
    private val _lastQualifiedOutcome = MutableStateFlow<CatalogSyncOutcomeState?>(null)
    internal val lastQualifiedOutcome: StateFlow<CatalogSyncOutcomeState?> = _lastQualifiedOutcome.asStateFlow()
    internal var syncOutcomeNowMs: () -> Long = System::currentTimeMillis

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
        if (summary.syncEventsOrdinaryPending && !summary.priceSyncFailed) return
        _lastOutcome.value = CatalogSyncOutcomeState(
            ownerUserId = ownerUserId,
            source = source,
            summary = summary
        )
    }

    internal suspend fun publishCurrentFlightSummary(
        ownerUserId: String,
        source: CatalogSyncFlightOwner,
        summary: CatalogSyncSummary
    ): Boolean {
        if (summary.syncEventsOrdinaryPending && !summary.priceSyncFailed) return false
        val lease = currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease ?: return false
        if (lease.localOnly || ownerUserId.isBlank()) return false
        if (!lease.unmanaged && lease.boundScope?.ownerHash != task126OwnerHash(ownerUserId)) return false
        val signal = Task126BusinessDataScopeSignalToken(lease)
        if (!isCurrentBusinessDataScopeSignal(signal)) return false
        val previousOutcome = _lastOutcome.value
        val previousQualified = _lastQualifiedOutcome.value
        val qualified = !summary.manualFullSyncRequired &&
            summary.syncEventsSkippedDirtyLocal == 0 && !summary.syncEventsOrdinaryPending
        val outcome = CatalogSyncOutcomeState(
            ownerUserId = ownerUserId,
            source = source,
            summary = summary,
            completedAtMs = if (qualified) syncOutcomeNowMs().takeIf { it > 0L } else null,
            scopeSignal = signal
        )
        if (!isCurrentBusinessDataScopeSignal(signal)) return false
        if (outcome.completedAtMs != null &&
            !_lastQualifiedOutcome.compareAndSet(previousQualified, outcome)) return false
        if (!isCurrentBusinessDataScopeSignal(signal)) {
            _lastQualifiedOutcome.compareAndSet(outcome, null)
            return false
        }
        _lastOutcome.compareAndSet(previousOutcome, outcome)
        if (!isCurrentBusinessDataScopeSignal(signal)) {
            // A scope change or synchronous subscriber may have invalidated this publication.
            // Never remove a newer outcome while retracting this one.
            _lastOutcome.compareAndSet(outcome, null)
            _lastQualifiedOutcome.compareAndSet(outcome, null)
            return false
        }
        return true
    }

    internal fun isOutcomeCurrent(
        outcome: CatalogSyncOutcomeState,
        ownerUserId: String,
        selectedShop: SelectedShop?
    ): Boolean {
        if (outcome.ownerUserId != ownerUserId || outcome.completedAtMs == null ||
            _lastQualifiedOutcome.value !== outcome) return false
        val signal = outcome.scopeSignal ?: return false
        if (signal.lease.localOnly) return false
        val current = _businessDataScopeState.value
        if (!current.allowsLocalOperations) return false
        if (signal.lease.unmanaged) return current.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
        val scope = signal.lease.boundScope ?: return false
        val currentScope = current.boundScope ?: return false
        return scope.ownerHash == task126OwnerHash(ownerUserId) &&
            scope.storeId == shopScopedStoreScope(selectedShop) &&
            Task126OwnerStoreGate.validate(scope, currentScope) == Task126OwnerStoreGateDecision.Allowed
    }

    internal suspend fun resolveAndPublishBusinessDataScope(
        stillAuthorized: () -> Boolean,
        resolve: suspend (Task126BusinessDataScopeState) -> Task126BusinessDataScopeState
    ): Boolean = businessDataScopeFlightGate.publishIfScopeUnchanged(
        stillAuthorized, resolve, ::updateBusinessDataScopeState
    )

    fun updateBusinessDataScopeState(next: Task126BusinessDataScopeState) {
        val previous = _businessDataScopeState.value
        // Scope emission can synchronously publish a new outcome; retract only the previous one.
        val previousOutcome = _lastOutcome.value
        val previousQualified = _lastQualifiedOutcome.value
        val scopeChanged = scopeSignature(previous) != scopeSignature(next)
        businessDataScopeFlightGate.updateState(next)
        _businessDataScopeState.value = next
        if (scopeChanged || !next.allowsCloudSync) {
            _lastOutcome.compareAndSet(previousOutcome, null)
        }
        if (scopeChanged || !next.allowsLocalOperations) {
            _lastQualifiedOutcome.compareAndSet(previousQualified, null)
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

    internal fun captureDiagnosticQuietStamp(): Task126DiagnosticQuietStamp? =
        businessDataScopeFlightGate.captureDiagnosticQuietStamp()

    internal fun isDiagnosticQuietStampCurrent(stamp: Task126DiagnosticQuietStamp): Boolean =
        businessDataScopeFlightGate.isDiagnosticQuietStampCurrent(stamp)

    internal val localPresentationChanges: StateFlow<Long>
        get() = businessDataScopeFlightGate.localPresentationChanges

    internal fun captureLocalPresentationStamp(
        expectedState: Task126BusinessDataScopeState
    ): Task126LocalPresentationStamp? = businessDataScopeFlightGate.captureLocalPresentationStamp(expectedState)

    internal fun isLocalPresentationStampCurrent(
        stamp: Task126LocalPresentationStamp,
        expectedState: Task126BusinessDataScopeState
    ): Boolean = businessDataScopeFlightGate.isLocalPresentationStampCurrent(stamp, expectedState)

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

    override suspend fun <T> withLocalBusinessDataScopeFlight(block: suspend () -> T): T =
        businessDataScopeFlightGate.withLocalBusinessDataScopeFlight(block)

    override suspend fun requireCloudBusinessDataScope() =
        businessDataScopeFlightGate.requireCloudBusinessDataScope()

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

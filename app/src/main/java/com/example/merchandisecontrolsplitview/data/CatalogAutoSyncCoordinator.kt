package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

class CatalogAutoSyncCoordinator(
    private val repository: CatalogAutoSyncRepository,
    private val remote: CatalogRemoteDataSource,
    private val priceRemote: ProductPriceRemoteDataSource,
    private val syncEventRemote: SyncEventRemoteDataSource = DisabledSyncEventRemoteDataSource,
    private val authFlow: StateFlow<AuthState>,
    private val syncStateTracker: CatalogSyncStateTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val bootstrapStalenessMs: Long = BOOTSTRAP_STALENESS_MS,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val DEBOUNCE_MS = 2_000L
        const val BOOTSTRAP_STALENESS_MS = 30L * 60L * 1_000L
        private const val LOG_SAMPLE_LIMIT = 8
    }

    private val dirtyHints = LinkedHashSet<Long>()
    private val dirtyLock = Any()

    @Volatile
    private var isForeground = true

    @Volatile
    private var lastBootstrapUserId: String? = null

    @Volatile
    private var lastBootstrapOkAtMs: Long = 0L

    private val pushTickle = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val bootstrapTickle = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val syncEventTickle = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        startDebouncers()
        scope.launch {
            authFlow.collect { state ->
                if (state is AuthState.SignedIn) {
                    scheduleBootstrap("auth_signed_in")
                    schedulePush("auth_signed_in")
                } else {
                    synchronized(dirtyLock) { dirtyHints.clear() }
                    lastBootstrapUserId = null
                    logger("cycle=catalog_auto outcome=skip reason=signed_out")
                }
            }
        }
    }

    fun onLocalProductChanged(productId: Long) {
        if (productId <= 0L) return
        synchronized(dirtyLock) { dirtyHints.add(productId) }
        schedulePush("local_commit")
    }

    fun onLocalCatalogChanged() {
        schedulePush("local_catalog_commit")
    }

    fun onAppForeground() {
        isForeground = true
        scheduleBootstrap("foreground")
        schedulePush("foreground")
        scheduleSyncEventDrain("foreground")
    }

    fun onAppBackground() {
        isForeground = false
    }

    fun onNetworkAvailable() {
        scheduleBootstrap("network_available")
        schedulePush("network_available")
        scheduleSyncEventDrain("network_available")
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun schedulePush(reason: String) {
        pushTickle.tryEmit(reason)
    }

    private fun scheduleBootstrap(reason: String) {
        bootstrapTickle.tryEmit(reason)
    }

    fun onRemoteSyncEventSignal() {
        scheduleSyncEventDrain("realtime_signal")
    }

    private fun scheduleSyncEventDrain(reason: String) {
        syncEventTickle.tryEmit(reason)
    }

    @OptIn(FlowPreview::class)
    private fun startDebouncers() {
        scope.launch {
            pushTickle
                .debounce(debounceMs)
                .collect { reason -> runPushCycle(reason) }
        }
        scope.launch {
            bootstrapTickle
                .debounce(debounceMs)
                .collect { reason -> runBootstrapCycle(reason) }
        }
        scope.launch {
            syncEventTickle
                .debounce(debounceMs)
                .collect { reason -> runSyncEventDrainCycle(reason) }
        }
    }

    internal suspend fun runPushCycle(reason: String) {
        val auth = authFlow.value
        if (auth !is AuthState.SignedIn) {
            logger("cycle=catalog_push outcome=skip reason=no_auth debounceMs=$debounceMs")
            return
        }
        if (!remote.isConfigured) {
            logger("cycle=catalog_push outcome=skip reason=remote_unconfigured debounceMs=$debounceMs")
            return
        }
        if (!isForeground) {
            logger("cycle=catalog_push outcome=skip reason=background_policy debounceMs=$debounceMs")
            return
        }
        val hinted = synchronized(dirtyLock) {
            val copy = dirtyHints.toSet()
            dirtyHints.clear()
            copy
        }
        if (!syncStateTracker.tryBegin(CatalogSyncFlightOwner.AUTO_PUSH)) {
            synchronized(dirtyLock) { dirtyHints.addAll(hinted) }
            logger("cycle=catalog_push outcome=skip reason=sync_busy dirtyHints=${hinted.size}")
            return
        }
        var ok = false
        val startedAt = System.currentTimeMillis()
        try {
            syncStateTracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            var summary: CatalogSyncSummary? = null
            val durationMs = measureTimeMillis {
                summary = if (syncEventRemote.isConfigured) {
                    repository.syncCatalogQuickWithEvents(
                        remote = remote,
                        priceRemote = priceRemote,
                        syncEventRemote = syncEventRemote,
                        ownerUserId = auth.userId,
                        progressReporter = CatalogSyncProgressReporter { progress ->
                            syncStateTracker.update(progress)
                        }
                    )
                } else {
                    repository.pushDirtyCatalogDeltaToRemote(
                        remote = remote,
                        priceRemote = priceRemote,
                        ownerUserId = auth.userId,
                        progressReporter = CatalogSyncProgressReporter { progress ->
                            syncStateTracker.update(progress)
                        }
                    )
                }
                    .getOrThrow()
            }
            ok = true
            val s = summary
            logger(
                "cycle=catalog_push outcome=ok reason=$reason durationMs=$durationMs " +
                    "dirtyHints=${hinted.size} dirtySample=${hinted.take(LOG_SAMPLE_LIMIT).joinToString(",")} " +
                    "productsPushed=${s?.pushedProducts ?: 0} pricesPushed=${s?.pushedProductPrices ?: 0} " +
                    "syncEventsProcessed=${s?.syncEventsProcessed ?: 0} " +
                    "syncEventOutboxPending=${s?.syncEventOutboxPending ?: 0} " +
                    "priceSyncFailed=${s?.priceSyncFailed ?: false}"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val classification = SyncErrorClassifier.classify(t)
            logger(
                "cycle=catalog_push outcome=fail reason=$reason durationMs=${System.currentTimeMillis() - startedAt} " +
                    "dirtyHints=${hinted.size} errCategory=${classification.category} " +
                    "httpStatus=${classification.httpStatus} postgrestCode=${classification.postgrestCode}"
            )
        } finally {
            syncStateTracker.update(
                if (ok) CatalogSyncProgressState.completed() else CatalogSyncProgressState.failed()
            )
            syncStateTracker.finish(CatalogSyncFlightOwner.AUTO_PUSH)
        }
    }

    internal suspend fun runBootstrapCycle(reason: String) {
        val auth = authFlow.value
        if (auth !is AuthState.SignedIn) {
            logger("cycle=catalog_bootstrap outcome=skip reason=no_auth")
            return
        }
        if (!remote.isConfigured) {
            logger("cycle=catalog_bootstrap outcome=skip reason=remote_unconfigured")
            return
        }
        if (!isForeground) {
            logger("cycle=catalog_bootstrap outcome=skip reason=background_policy")
            return
        }
        val now = System.currentTimeMillis()
        val freshForUser = lastBootstrapUserId == auth.userId &&
            lastBootstrapOkAtMs > 0L &&
            now - lastBootstrapOkAtMs < bootstrapStalenessMs
        if (freshForUser) {
            logger("cycle=catalog_bootstrap outcome=skip reason=staleness_guard")
            return
        }
        if (!syncStateTracker.tryBegin(CatalogSyncFlightOwner.BOOTSTRAP)) {
            logger("cycle=catalog_bootstrap outcome=skip reason=sync_busy")
            return
        }
        var ok = false
        val startedAt = System.currentTimeMillis()
        try {
            syncStateTracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            var summary: CatalogSyncSummary? = null
            val durationMs = measureTimeMillis {
                summary = repository.pullCatalogBootstrapFromRemote(
                    remote = remote,
                    priceRemote = priceRemote,
                    progressReporter = CatalogSyncProgressReporter { progress ->
                        syncStateTracker.update(progress)
                    }
                ).getOrThrow()
            }
            ok = true
            lastBootstrapUserId = auth.userId
            lastBootstrapOkAtMs = System.currentTimeMillis()
            val s = summary
            logger(
                "cycle=catalog_bootstrap outcome=ok reason=$reason durationMs=$durationMs " +
                    "productsPulled=${s?.pulledProducts ?: 0} suppliersPulled=${s?.pulledSuppliers ?: 0} " +
                    "categoriesPulled=${s?.pulledCategories ?: 0} pricesPulled=${s?.pulledProductPrices ?: 0} " +
                    "priceSyncFailed=${s?.priceSyncFailed ?: false}"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val classification = SyncErrorClassifier.classify(t)
            logger(
                "cycle=catalog_bootstrap outcome=fail reason=$reason durationMs=${System.currentTimeMillis() - startedAt} " +
                    "errCategory=${classification.category} httpStatus=${classification.httpStatus} " +
                    "postgrestCode=${classification.postgrestCode}"
            )
        } finally {
            syncStateTracker.update(
                if (ok) CatalogSyncProgressState.completed() else CatalogSyncProgressState.failed()
            )
            syncStateTracker.finish(CatalogSyncFlightOwner.BOOTSTRAP)
        }
    }

    internal suspend fun runSyncEventDrainCycle(reason: String) {
        val auth = authFlow.value
        if (auth !is AuthState.SignedIn) {
            logger("cycle=sync_events_drain outcome=skip reason=no_auth")
            return
        }
        if (!remote.isConfigured || !syncEventRemote.isConfigured) {
            logger("cycle=sync_events_drain outcome=skip reason=remote_unconfigured")
            return
        }
        if (!isForeground) {
            logger("cycle=sync_events_drain outcome=skip reason=background_policy")
            return
        }
        if (!syncStateTracker.tryBegin(CatalogSyncFlightOwner.SYNC_EVENTS)) {
            logger("cycle=sync_events_drain outcome=skip reason=sync_busy")
            return
        }
        var ok = false
        val startedAt = System.currentTimeMillis()
        try {
            syncStateTracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            var summary: CatalogSyncSummary? = null
            val durationMs = measureTimeMillis {
                summary = repository.drainSyncEventsFromRemote(
                    remote = remote,
                    priceRemote = priceRemote,
                    syncEventRemote = syncEventRemote,
                    ownerUserId = auth.userId,
                    progressReporter = CatalogSyncProgressReporter { progress ->
                        syncStateTracker.update(progress)
                    }
                ).getOrThrow()
            }
            ok = true
            val s = summary
            logger(
                "cycle=sync_events_drain outcome=ok reason=$reason durationMs=$durationMs " +
                    "eventsFetched=${s?.syncEventsFetched ?: 0} eventsProcessed=${s?.syncEventsProcessed ?: 0} " +
                    "skippedSelf=${s?.syncEventsSkippedSelf ?: 0} outboxPending=${s?.syncEventOutboxPending ?: 0} " +
                    "targetedProductsFetched=${s?.targetedProductsFetched ?: 0} " +
                    "targetedPricesFetched=${s?.targetedPricesFetched ?: 0} " +
                    "fullCatalogFetch=${s?.fullCatalogFetch ?: false} fullPriceFetch=${s?.fullPriceFetch ?: false}"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val classification = SyncErrorClassifier.classify(t)
            logger(
                "cycle=sync_events_drain outcome=fail reason=$reason durationMs=${System.currentTimeMillis() - startedAt} " +
                    "errCategory=${classification.category} httpStatus=${classification.httpStatus} " +
                    "postgrestCode=${classification.postgrestCode}"
            )
        } finally {
            syncStateTracker.update(
                if (ok) CatalogSyncProgressState.completed() else CatalogSyncProgressState.failed()
            )
            syncStateTracker.finish(CatalogSyncFlightOwner.SYNC_EVENTS)
        }
    }
}

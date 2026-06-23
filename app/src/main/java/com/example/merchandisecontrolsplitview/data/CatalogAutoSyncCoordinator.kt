package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
    private val sessionRemote: SessionBackupRemoteDataSource? = null,
    private val deviceAuthorization: ShopDeviceAuthorizationRepository? = null,
    private val authFlow: StateFlow<AuthState>,
    private val selectedShopProvider: () -> SelectedShop? = { null },
    private val syncStateTracker: CatalogSyncStateTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val bootstrapRetryGuardMs: Long = BOOTSTRAP_RETRY_GUARD_MS,
    private val foregroundSyncEventIntervalMs: Long = FOREGROUND_SYNC_EVENT_INTERVAL_MS,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val DEBOUNCE_MS = 500L
        const val BOOTSTRAP_RETRY_GUARD_MS = 5L * 60L * 1_000L
        const val FOREGROUND_SYNC_EVENT_INTERVAL_MS = 15_000L
        const val RETRY_AFTER_BUSY_MS = 250L
        const val RETRY_AFTER_DEVICE_STATUS_MAX_ATTEMPTS = 4
        const val RETRY_AFTER_DEVICE_STATUS_MAX_MS = 2_000L
        const val LOCAL_PUSH_BOOTSTRAP_QUIET_MS = 5_000L
        private const val LOG_SAMPLE_LIMIT = 8
        private const val BOOTSTRAP_REASON_SYNC_EVENT_GAP = "sync_event_gap"
    }

    private val dirtyHints = LinkedHashSet<Long>()
    private val dirtyLock = Any()
    private val retryLock = Any()

    @Volatile
    private var pushRetryAfterBusyScheduled = false

    @Volatile
    private var bootstrapRetryAfterBusyScheduled = false

    @Volatile
    private var syncEventRetryAfterBusyScheduled = false

    @Volatile
    private var deviceStatusPushRetryScheduled = false

    @Volatile
    private var deviceStatusPushRetryAttempts = 0

    @Volatile
    private var pendingCatalogBootstrapAfterBusy = false

    @Volatile
    private var pendingSyncEventDrainAfterBusy = false

    @Volatile
    private var pendingLocalCatalogPushSignal = false

    @Volatile
    private var lastLocalPushCompletedAtMs: Long = 0L

    @Volatile
    private var isForeground = true

    @Volatile
    private var lastBootstrapUserId: String? = null

    @Volatile
    private var lastBootstrapOkAtMs: Long = 0L

    private var foregroundSyncEventJob: Job? = null

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
                    scheduleSyncEventDrain("auth_signed_in")
                } else {
                    synchronized(dirtyLock) { dirtyHints.clear() }
                    pendingLocalCatalogPushSignal = false
                    resetDeviceStatusPushRetry()
                    lastBootstrapUserId = null
                    logger("cycle=catalog_auto outcome=skip reason=signed_out")
                }
            }
        }
    }

    fun onLocalProductChanged(productId: Long) {
        if (productId <= 0L) return
        synchronized(dirtyLock) { dirtyHints.add(productId) }
        pendingLocalCatalogPushSignal = true
        schedulePush("local_commit")
        schedulePushAfterBusy("local_commit")
    }

    fun onLocalCatalogChanged() {
        pendingLocalCatalogPushSignal = true
        schedulePush("local_catalog_commit")
        schedulePushAfterBusy("local_catalog_commit")
    }

    fun onAppForeground() {
        isForeground = true
        scheduleBootstrap("foreground")
        schedulePush("foreground")
        scheduleSyncEventDrain("foreground")
        startForegroundSyncEventLoop()
    }

    fun onAppBackground() {
        isForeground = false
        foregroundSyncEventJob?.cancel()
        foregroundSyncEventJob = null
    }

    fun onNetworkAvailable() {
        resetDeviceStatusPushRetry()
        scheduleBootstrap("network_available")
        schedulePush("network_available")
        scheduleSyncEventDrain("network_available")
    }

    fun onDeviceStatusActive() {
        logger("cycle=device_status_active outcome=schedule")
        resetDeviceStatusPushRetry()
        scheduleBootstrap("device_active")
        scheduleSyncEventDrain("device_active")
        scope.launch {
            delay(RETRY_AFTER_BUSY_MS)
            runBootstrapCycle("device_active_direct")
            runSyncEventDrainCycle("device_active_direct")
        }
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

    fun onShopContextChanged() {
        scheduleBootstrap("shop_context_changed")
        scheduleSyncEventDrain("shop_context_changed")
    }

    private fun scheduleSyncEventDrain(reason: String) {
        syncEventTickle.tryEmit(reason)
    }

    private fun schedulePushAfterBusy(reason: String) {
        val retryReason = compactBusyRetryReason(reason)
        val shouldSchedule = synchronized(retryLock) {
            if (pushRetryAfterBusyScheduled) {
                false
            } else {
                pushRetryAfterBusyScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        scope.launch {
            delay(RETRY_AFTER_BUSY_MS)
            synchronized(retryLock) { pushRetryAfterBusyScheduled = false }
            runPushCycle(retryReason)
        }
    }

    private fun schedulePushAfterRetryableDeviceStatus(reason: String) {
        val retryReason = compactBusyRetryReason(reason)
        val retry = synchronized(retryLock) {
            if (deviceStatusPushRetryScheduled) {
                null
            } else if (deviceStatusPushRetryAttempts >= RETRY_AFTER_DEVICE_STATUS_MAX_ATTEMPTS) {
                DeviceStatusRetryPlan.Suppressed(deviceStatusPushRetryAttempts)
            } else {
                deviceStatusPushRetryScheduled = true
                deviceStatusPushRetryAttempts += 1
                DeviceStatusRetryPlan.Scheduled(
                    attempt = deviceStatusPushRetryAttempts,
                    delayMs = deviceStatusRetryDelayMs(deviceStatusPushRetryAttempts)
                )
            }
        }
        when (retry) {
            null -> return
            is DeviceStatusRetryPlan.Suppressed -> {
                logger(
                    "cycle=catalog_push outcome=device_status_retry_suppressed reason=$reason " +
                        "attempts=${retry.attempts} nextSignal=network_or_device_active"
                )
                return
            }
            is DeviceStatusRetryPlan.Scheduled -> {
                logger(
                    "cycle=catalog_push outcome=queued_after_device_status reason=$reason " +
                        "retryDelayMs=${retry.delayMs} retryAttempt=${retry.attempt}"
                )
                scope.launch {
                    delay(retry.delayMs)
                    synchronized(retryLock) { deviceStatusPushRetryScheduled = false }
                    runPushCycle(retryReason)
                }
            }
        }
    }

    private fun resetDeviceStatusPushRetry() {
        synchronized(retryLock) {
            deviceStatusPushRetryScheduled = false
            deviceStatusPushRetryAttempts = 0
        }
    }

    private fun deviceStatusRetryDelayMs(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceAtMost(8)
        return minOf(RETRY_AFTER_BUSY_MS * multiplier, RETRY_AFTER_DEVICE_STATUS_MAX_MS)
    }

    private fun scheduleBootstrapAfterBusy(reason: String) {
        val retryReason = compactBusyRetryReason(reason)
        val shouldSchedule = synchronized(retryLock) {
            pendingCatalogBootstrapAfterBusy = true
            if (bootstrapRetryAfterBusyScheduled) {
                false
            } else {
                bootstrapRetryAfterBusyScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        scope.launch {
            delay(RETRY_AFTER_BUSY_MS * 4)
            synchronized(retryLock) { bootstrapRetryAfterBusyScheduled = false }
            if (pendingCatalogBootstrapAfterBusy) {
                runBootstrapCycle(retryReason)
            }
        }
    }

    private fun scheduleSyncEventDrainAfterBusy(reason: String) {
        val retryReason = compactBusyRetryReason(reason)
        val shouldSchedule = synchronized(retryLock) {
            pendingSyncEventDrainAfterBusy = true
            if (syncEventRetryAfterBusyScheduled) {
                false
            } else {
                syncEventRetryAfterBusyScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        scope.launch {
            delay(RETRY_AFTER_BUSY_MS * 4)
            synchronized(retryLock) { syncEventRetryAfterBusyScheduled = false }
            if (pendingSyncEventDrainAfterBusy) {
                runSyncEventDrainCycle(retryReason)
            }
        }
    }

    private fun compactBusyRetryReason(reason: String): String {
        val marker = "_retry_after_busy"
        val base = reason.substringBefore(marker)
        return "${base}${marker}"
    }

    private fun consumePendingCatalogBootstrapAfterBusy(): Boolean = synchronized(retryLock) {
        if (!pendingCatalogBootstrapAfterBusy) {
            false
        } else {
            pendingCatalogBootstrapAfterBusy = false
            true
        }
    }

    private fun consumePendingSyncEventDrainAfterBusy(): Boolean = synchronized(retryLock) {
        if (!pendingSyncEventDrainAfterBusy) {
            false
        } else {
            pendingSyncEventDrainAfterBusy = false
            true
        }
    }

    private fun startForegroundSyncEventLoop() {
        if (foregroundSyncEventIntervalMs <= 0L) return
        if (foregroundSyncEventJob?.isActive == true) return
        foregroundSyncEventJob = scope.launch {
            while (true) {
                delay(foregroundSyncEventIntervalMs)
                if (isForeground) {
                    scheduleSyncEventDrain("foreground_interval")
                }
            }
        }
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
        if (!isLocalMutationPushReason(reason)) {
            logger(
                "cycle=catalog_push outcome=skip reason=automatic_push_safety_guard " +
                    "originalReason=$reason policy=non_local_trigger debounceMs=$debounceMs"
            )
            return
        }
        if (repository.shouldRunCatalogBootstrap(auth.userId)) {
            logger(
                "cycle=catalog_push outcome=skip reason=automatic_push_safety_guard " +
                    "originalReason=$reason policy=bootstrap_required debounceMs=$debounceMs"
            )
            return
        }
        if (!repository.hasCatalogCloudPendingWorkInclusive()) {
            pendingLocalCatalogPushSignal = false
            logger(
                "cycle=catalog_push outcome=skip reason=no_pending_catalog_work " +
                    "originalReason=$reason debounceMs=$debounceMs"
            )
            return
        }
        val selectedShop = selectedShopProvider()
        if (!ensureDeviceActiveForSync("catalog_push", reason, selectedShop)) return
        val hinted = synchronized(dirtyLock) {
            val copy = dirtyHints.toSet()
            dirtyHints.clear()
            copy
        }
        if (!syncStateTracker.tryBegin(CatalogSyncFlightOwner.AUTO_PUSH)) {
            synchronized(dirtyLock) { dirtyHints.addAll(hinted) }
            pendingLocalCatalogPushSignal = true
            logger("cycle=catalog_push outcome=skip reason=sync_busy dirtyHints=${hinted.size}")
            schedulePushAfterBusy(reason)
            return
        }
        var ok = false
        val startedAt = System.currentTimeMillis()
        try {
            syncStateTracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            var summary: CatalogSyncSummary? = null
            val durationMs = measureTimeMillis {
                summary = if (syncEventRemote.isConfigured) {
                    if (selectedShop == null) {
                        repository.syncCatalogQuickWithEvents(
                            remote = remote,
                            priceRemote = priceRemote,
                            syncEventRemote = syncEventRemote,
                            sessionRemote = sessionRemote,
                            ownerUserId = auth.userId,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                syncStateTracker.update(progress)
                            }
                        )
                    } else {
                        repository.syncCatalogQuickWithEvents(
                            remote = remote,
                            priceRemote = priceRemote,
                            syncEventRemote = syncEventRemote,
                            sessionRemote = sessionRemote,
                            ownerUserId = auth.userId,
                            selectedShop = selectedShop,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                syncStateTracker.update(progress)
                            }
                        )
                    }
                } else {
                    if (selectedShop == null) {
                        repository.pushDirtyCatalogDeltaToRemote(
                            remote = remote,
                            priceRemote = priceRemote,
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
                            selectedShop = selectedShop,
                            progressReporter = CatalogSyncProgressReporter { progress ->
                                syncStateTracker.update(progress)
                            }
                        )
                    }
                }
                    .getOrThrow()
            }
            ok = true
            pendingLocalCatalogPushSignal = false
            lastLocalPushCompletedAtMs = System.currentTimeMillis()
            val s = summary
            s?.let {
                syncStateTracker.publishSummary(auth.userId, CatalogSyncFlightOwner.AUTO_PUSH, it)
            }
            logger(
                "cycle=catalog_push outcome=ok reason=$reason durationMs=$durationMs " +
                    "dirtyHints=${hinted.size} dirtySample=${hinted.take(LOG_SAMPLE_LIMIT).joinToString(",")} " +
                    "productsPushed=${s?.pushedProducts ?: 0} pricesPushed=${s?.pushedProductPrices ?: 0} " +
                    "syncEventsProcessed=${s?.syncEventsProcessed ?: 0} " +
                    "manualFullSyncRequired=${s?.manualFullSyncRequired ?: false} " +
                    "syncEventsGapDetected=${s?.syncEventsGapDetected ?: false} " +
                    "syncEventsTooLarge=${s?.syncEventsTooLarge ?: false} " +
                    "syncEventOutboxRetried=${s?.syncEventOutboxRetried ?: 0} " +
                    "syncEventOutboxPending=${s?.syncEventOutboxPending ?: 0} " +
                    "priceSyncFailed=${s?.priceSyncFailed ?: false}"
            )
            scheduleSyncEventDrain("local_push_completed")
        } catch (cancelled: CancellationException) {
            if (scope.coroutineContext[Job]?.isActive != true || !isLocalMutationPushReason(reason)) {
                throw cancelled
            }
            synchronized(dirtyLock) { dirtyHints.addAll(hinted) }
            pendingLocalCatalogPushSignal = true
            logger(
                "cycle=catalog_push outcome=queued_after_retryable_cancellation reason=$reason " +
                    "durationMs=${System.currentTimeMillis() - startedAt} dirtyHints=${hinted.size} " +
                    "errClass=${cancelled::class.java.simpleName} retryDelayMs=$RETRY_AFTER_BUSY_MS"
            )
            schedulePushAfterBusy(reason)
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

    private fun isLocalMutationPushReason(reason: String): Boolean =
        reason == "local_commit" ||
            reason == "local_catalog_commit" ||
            reason.startsWith("local_commit_") ||
            reason.startsWith("local_catalog_commit_")

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
        val forcedByRemoteGap = reason == BOOTSTRAP_REASON_SYNC_EVENT_GAP
        val bootstrapRequired = repository.shouldRunCatalogBootstrap(auth.userId)
        val automaticPullReconcile = isAutomaticPullReconcileReason(reason)
        if (!forcedByRemoteGap && !bootstrapRequired && !automaticPullReconcile) {
            logger("cycle=catalog_bootstrap outcome=skip reason=not_needed")
            return
        }
        val recentlyBootstrappedForUser = lastBootstrapUserId == auth.userId &&
            lastBootstrapOkAtMs > 0L &&
            now - lastBootstrapOkAtMs < bootstrapRetryGuardMs
        if (!forcedByRemoteGap && recentlyBootstrappedForUser) {
            logger("cycle=catalog_bootstrap outcome=skip reason=bootstrap_retry_guard")
            return
        }
        if (
            !forcedByRemoteGap &&
            !bootstrapRequired &&
            hasPendingLocalPushSignal()
        ) {
            logger(
                "cycle=catalog_bootstrap outcome=yield_to_local_push " +
                    "reason=pending_local_catalog_work originalReason=$reason"
            )
            schedulePushAfterBusy("local_catalog_commit")
            scheduleBootstrapAfterBusy(reason)
            return
        }
        val localPushQuietRemainingMs = localPushBootstrapQuietRemainingMs(now)
        if (
            !forcedByRemoteGap &&
            !bootstrapRequired &&
            localPushQuietRemainingMs > 0L
        ) {
            logger(
                "cycle=catalog_bootstrap outcome=deferred_after_recent_local_push " +
                    "quietRemainingMs=$localPushQuietRemainingMs originalReason=$reason"
            )
            scheduleBootstrapAfterBusy(reason)
            return
        }
        val selectedShop = selectedShopProvider()
        if (!ensureDeviceActiveForSync("catalog_bootstrap", reason, selectedShop)) return
        if (!syncStateTracker.tryBegin(CatalogSyncFlightOwner.BOOTSTRAP)) {
            logger(
                "cycle=catalog_bootstrap outcome=queued_after_busy " +
                    "reason=sync_busy originalReason=$reason"
            )
            scheduleBootstrapAfterBusy(reason)
            return
        }
        if (consumePendingCatalogBootstrapAfterBusy()) {
            logger("cycle=catalog_bootstrap outcome=drained_after_busy reason=$reason")
        }
        var ok = false
        val startedAt = System.currentTimeMillis()
        try {
            syncStateTracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            var summary: CatalogSyncSummary? = null
            val durationMs = measureTimeMillis {
                summary = if (selectedShop == null) {
                    repository.pullCatalogBootstrapFromRemote(
                        remote = remote,
                        priceRemote = priceRemote,
                        progressReporter = CatalogSyncProgressReporter { progress ->
                            syncStateTracker.update(progress)
                        }
                    )
                } else {
                    repository.pullCatalogBootstrapFromRemote(
                        remote = remote,
                        priceRemote = priceRemote,
                        selectedShop = selectedShop,
                        progressReporter = CatalogSyncProgressReporter { progress ->
                            syncStateTracker.update(progress)
                        }
                    )
                }.getOrThrow()
            }
            ok = true
            lastBootstrapUserId = auth.userId
            lastBootstrapOkAtMs = System.currentTimeMillis()
            val s = summary
            s?.let {
                syncStateTracker.publishSummary(auth.userId, CatalogSyncFlightOwner.BOOTSTRAP, it)
            }
            logger(
                "cycle=catalog_bootstrap outcome=ok reason=$reason durationMs=$durationMs " +
                    "bootstrapRequired=$bootstrapRequired pullOnly=true " +
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

    private fun isAutomaticPullReconcileReason(reason: String): Boolean =
        reason == "auth_signed_in" ||
            reason == "foreground" ||
            reason == "network_available" ||
            reason == "device_active" ||
            reason.startsWith("auth_signed_in_") ||
            reason.startsWith("foreground_") ||
            reason.startsWith("network_available_") ||
            reason.startsWith("device_active_")

    private fun hasPendingLocalPushSignal(): Boolean =
        pendingLocalCatalogPushSignal ||
            pushRetryAfterBusyScheduled ||
            synchronized(dirtyLock) { dirtyHints.isNotEmpty() }

    private fun localPushBootstrapQuietRemainingMs(now: Long): Long {
        val completedAt = lastLocalPushCompletedAtMs
        if (completedAt <= 0L) return 0L
        val elapsed = now - completedAt
        return (LOCAL_PUSH_BOOTSTRAP_QUIET_MS - elapsed).coerceAtLeast(0L)
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
        if (repository.shouldRunCatalogBootstrap(auth.userId)) {
            logger(
                "cycle=sync_events_drain outcome=skip reason=bootstrap_required " +
                    "originalReason=$reason"
            )
            scheduleBootstrap(BOOTSTRAP_REASON_SYNC_EVENT_GAP)
            return
        }
        val selectedShop = selectedShopProvider()
        if (!ensureDeviceActiveForSync("sync_events_drain", reason, selectedShop)) return
        if (!syncStateTracker.tryBegin(CatalogSyncFlightOwner.SYNC_EVENTS)) {
            logger(
                "cycle=sync_events_drain outcome=queued_after_busy " +
                    "reason=sync_busy originalReason=$reason"
            )
            scheduleSyncEventDrainAfterBusy(reason)
            return
        }
        if (consumePendingSyncEventDrainAfterBusy()) {
            logger("cycle=sync_events_drain outcome=drained_after_busy reason=$reason")
        }
        var ok = false
        val startedAt = System.currentTimeMillis()
        try {
            syncStateTracker.update(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN))
            var summary: CatalogSyncSummary? = null
            val durationMs = measureTimeMillis {
                summary = if (selectedShop == null) {
                    repository.drainSyncEventsFromRemote(
                        remote = remote,
                        priceRemote = priceRemote,
                        syncEventRemote = syncEventRemote,
                        sessionRemote = sessionRemote,
                        ownerUserId = auth.userId,
                        progressReporter = CatalogSyncProgressReporter { progress ->
                            syncStateTracker.update(progress)
                        }
                    )
                } else {
                    repository.drainSyncEventsFromRemote(
                        remote = remote,
                        priceRemote = priceRemote,
                        syncEventRemote = syncEventRemote,
                        sessionRemote = sessionRemote,
                        ownerUserId = auth.userId,
                        selectedShop = selectedShop,
                        progressReporter = CatalogSyncProgressReporter { progress ->
                            syncStateTracker.update(progress)
                        }
                    )
                }.getOrThrow()
            }
            ok = true
            val s = summary
            s?.let {
                syncStateTracker.publishSummary(auth.userId, CatalogSyncFlightOwner.SYNC_EVENTS, it)
            }
            logger(
                "cycle=sync_events_drain outcome=ok reason=$reason durationMs=$durationMs " +
                    "eventsFetched=${s?.syncEventsFetched ?: 0} eventsProcessed=${s?.syncEventsProcessed ?: 0} " +
                    "skippedSelf=${s?.syncEventsSkippedSelf ?: 0} outboxPending=${s?.syncEventOutboxPending ?: 0} " +
                    "manualFullSyncRequired=${s?.manualFullSyncRequired ?: false} " +
                    "syncEventsGapDetected=${s?.syncEventsGapDetected ?: false} " +
                    "syncEventsTooLarge=${s?.syncEventsTooLarge ?: false} " +
                    "syncEventOutboxRetried=${s?.syncEventOutboxRetried ?: 0} " +
                    "targetedProductsFetched=${s?.targetedProductsFetched ?: 0} " +
                    "targetedPricesFetched=${s?.targetedPricesFetched ?: 0} " +
                    "targetedHistoryFetched=${s?.targetedHistoryFetched ?: 0} " +
                    "remoteHistoryUpdatesApplied=${s?.remoteHistoryUpdatesApplied ?: 0} " +
                    "fullCatalogFetch=${s?.fullCatalogFetch ?: false} fullPriceFetch=${s?.fullPriceFetch ?: false}"
            )
            if (s?.manualFullSyncRequired == true) {
                scheduleBootstrap(BOOTSTRAP_REASON_SYNC_EVENT_GAP)
            }
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

    private suspend fun ensureDeviceActiveForSync(
        cycle: String,
        reason: String,
        selectedShop: SelectedShop?
    ): Boolean {
        val authorization = deviceAuthorization ?: return true
        val result = authorization.ensureActiveForCloudWrite("$cycle:$reason", selectedShop?.shopId)
        if (result.isSuccess) {
            if (cycle == "catalog_push") {
                resetDeviceStatusPushRetry()
            }
            return true
        }

        val snapshot = (result.exceptionOrNull() as? ShopDeviceAuthorizationBlockedException)?.snapshot
        logger(
            "cycle=$cycle outcome=blocked_by_device_status reason=$reason " +
                "status=${snapshot?.status ?: "unknown"} code=${snapshot?.code ?: "unknown"} " +
                "recommendedAction=${snapshot?.recommendedAction ?: "contact_shop_admin"}"
        )
        if (
            cycle == "catalog_push" &&
            isLocalMutationPushReason(reason) &&
            snapshot.isRetryableDeviceStatus()
        ) {
            pendingLocalCatalogPushSignal = true
            schedulePushAfterRetryableDeviceStatus(reason)
        }
        syncStateTracker.update(CatalogSyncProgressState.failed(CatalogSyncStage.DEVICE_STATUS))
        return false
    }

    private sealed interface DeviceStatusRetryPlan {
        data class Scheduled(val attempt: Int, val delayMs: Long) : DeviceStatusRetryPlan
        data class Suppressed(val attempts: Int) : DeviceStatusRetryPlan
    }

    private fun ShopDeviceAuthorizationSnapshot?.isRetryableDeviceStatus(): Boolean =
        this != null &&
            (
                status == "network_error" ||
                    reasonCode == "network_error" ||
                    recommendedAction == "retry_when_online"
                )
}

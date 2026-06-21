package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.measureTimeMillis

class HistorySessionPushCoordinator(
    private val repository: InventoryRepository,
    private val remote: SessionBackupRemoteDataSource,
    private val syncEventRemote: SyncEventRemoteDataSource = DisabledSyncEventRemoteDataSource,
    private val syncEventOutboxDao: SyncEventOutboxDao? = null,
    private val deviceAuthorization: ShopDeviceAuthorizationRepository? = null,
    private val authFlow: StateFlow<AuthState>,
    private val flightOwner: SessionCloudSessionFlightOwner,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val DEBOUNCE_MS = 500L
        const val RETRY_AFTER_BUSY_MS = 250L
        const val RETRY_AFTER_DEVICE_STATUS_MAX_ATTEMPTS = 4
        const val RETRY_AFTER_DEVICE_STATUS_MAX_MS = 2_000L
        private const val LOG_SAMPLE_LIMIT = 5
        private const val REASON_LOGIN_FRESH_TICK = "login_fresh_tick"
        private const val HISTORY_SYNC_EVENT_RECORD_ATTEMPTS = 3
        private const val HISTORY_SYNC_EVENT_RETRY_DELAY_MS = 500L
    }

    private val syncEventJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val dirtyHints = LinkedHashSet<Long>()
    private val dirtyLock = Any()
    private val retryLock = Any()

    @Volatile
    private var pushRetryAfterBusyScheduled = false

    @Volatile
    private var deviceStatusPushRetryScheduled = false

    @Volatile
    private var deviceStatusPushRetryAttempts = 0

    @Volatile
    private var pendingLocalHistoryPushSignal = false

    @Volatile
    private var isForeground = true

    private val tickle = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        startDebouncer()
        scope.launch {
            authFlow.collect { state ->
                if (state !is AuthState.SignedIn) {
                    synchronized(dirtyLock) { dirtyHints.clear() }
                    pendingLocalHistoryPushSignal = false
                    resetDeviceStatusPushRetry()
                    logger("cycle=push outcome=skip reason=skipped_signed_out")
                } else {
                    schedule(REASON_LOGIN_FRESH_TICK)
                }
            }
        }
    }

    fun onLocalHistorySessionChanged(uid: Long) {
        if (uid <= 0L) return
        synchronized(dirtyLock) { dirtyHints.add(uid) }
        pendingLocalHistoryPushSignal = true
        schedule("local_commit")
        schedulePushAfterBusy("local_commit")
    }

    fun onAppForeground() {
        isForeground = true
        schedule("resume_tick")
    }

    fun onAppBackground() {
        isForeground = false
    }

    fun onNetworkAvailable() {
        resetDeviceStatusPushRetry()
        schedule("network_available")
    }

    fun onDeviceStatusActive() {
        resetDeviceStatusPushRetry()
        schedule("device_active")
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun schedule(reason: String) {
        tickle.tryEmit(reason)
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
            synchronized(retryLock) {
                pushRetryAfterBusyScheduled = false
            }
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
                    "cycle=push outcome=device_status_retry_suppressed reason=$reason " +
                        "attempts=${retry.attempts} nextSignal=network_or_device_active"
                )
                return
            }
            is DeviceStatusRetryPlan.Scheduled -> {
                logger(
                    "cycle=push outcome=queued_after_device_status reason=$reason " +
                        "retryDelayMs=${retry.delayMs} retryAttempt=${retry.attempt}"
                )
                scope.launch {
                    delay(retry.delayMs)
                    synchronized(retryLock) {
                        deviceStatusPushRetryScheduled = false
                    }
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

    @OptIn(FlowPreview::class)
    private fun startDebouncer() {
        scope.launch {
            tickle
                .debounce(debounceMs)
                .collect { reason -> runPushCycle(reason) }
        }
    }

    internal suspend fun runPushCycle(reason: String) {
        val auth = authFlow.value
        if (auth !is AuthState.SignedIn) {
            logger("cycle=push outcome=skip reason=skipped_no_auth debounceMs=$debounceMs dirtySetMode=precise")
            return
        }
        if (!remote.isConfigured) {
            logger("cycle=push outcome=skip reason=skipped_remote_unconfigured debounceMs=$debounceMs dirtySetMode=precise")
            return
        }
        if (!isForeground) {
            logger("cycle=push outcome=skip reason=skipped_background_policy debounceMs=$debounceMs dirtySetMode=precise")
            return
        }
        if (!ensureDeviceActiveForSync(reason)) return

        val hinted = synchronized(dirtyLock) {
            val copy = dirtyHints.toSet()
            dirtyHints.clear()
            copy
        }
        var pendingSize = 0
        var pendingUidSample = ""
        var coalesced = hinted.size > 1
        val fullReconciliation = isFullReconciliationReason(reason)
        val dirtySetMode = if (fullReconciliation) "full_reconcile" else "precise"
        try {
            var summary: HistorySessionBackupPushSummary? = null
            var bootstrap: RemoteSessionBatchResult? = null
            var emptyPending = false
            var durationMs = 0L
            flightOwner.withSessionFlight(SessionCloudFlightOwner.AutoPush) {
                durationMs = measureTimeMillis {
                    if (fullReconciliation) {
                        bootstrap = repository.bootstrapHistorySessionsFromRemote(remote).getOrThrow()
                        val pending = repository.getPendingHistorySessionPushUids().toSet()
                        pendingSize = pending.size
                        pendingUidSample = pending.take(LOG_SAMPLE_LIMIT).joinToString(",")
                        coalesced = coalesced || pending.size > 1
                        if (pending.isEmpty()) {
                            emptyPending = true
                            pendingLocalHistoryPushSignal = false
                        } else {
                            summary = repository
                                .pushHistorySessionsToRemote(remote, auth.userId, pending)
                                .getOrThrow()
                        }
                    } else {
                        val pending = repository.getPendingHistorySessionPushUids().toSet()
                        pendingSize = pending.size
                        pendingUidSample = pending.take(LOG_SAMPLE_LIMIT).joinToString(",")
                        coalesced = coalesced || pending.size > 1
                        if (pending.isEmpty()) {
                            emptyPending = true
                            pendingLocalHistoryPushSignal = false
                        } else {
                            summary = repository
                                .pushHistorySessionsToRemote(remote, auth.userId, pending)
                                .getOrThrow()
                        }
                    }
                }
            }
            val b = bootstrap
            val bootstrapSummary = if (b != null) {
                " bootstrapInserted=${b.inserted} bootstrapUpdated=${b.updated} " +
                    "bootstrapSkipped=${b.skipped} bootstrapFailed=${b.failed} bootstrapUnsupported=${b.unsupported}"
            } else {
                ""
            }
            if (emptyPending) {
                logger(
                    "cycle=push outcome=ok reason=$reason sessionsAttempted=0 sessionsUploaded=0 " +
                        "skippedDirtyLocal=0 coalesced=$coalesced dirtySetMode=$dirtySetMode owner=auto_push" +
                        bootstrapSummary
                )
                return
            }
            val s = summary
            recordHistorySyncEventIfNeeded(auth.userId, s)
            pendingLocalHistoryPushSignal = false
            logger(
                "cycle=push outcome=ok reason=$reason durationMs=$durationMs " +
                    "sessionsAttempted=${s?.attempted ?: pendingSize} sessionsUploaded=${s?.uploaded ?: 0} " +
                    "skippedDirtyLocal=0 coalesced=$coalesced dirtySetMode=$dirtySetMode owner=auto_push" +
                    bootstrapSummary
            )
        } catch (cancelled: CancellationException) {
            if (scope.coroutineContext[Job]?.isActive != true) {
                throw cancelled
            }
            synchronized(dirtyLock) { dirtyHints.addAll(hinted) }
            if (isLocalMutationPushReason(reason) || pendingLocalHistoryPushSignal) {
                pendingLocalHistoryPushSignal = true
                logger(
                    "cycle=push outcome=queued_after_retryable_cancellation reason=$reason " +
                        "durationMs=0 dirtyHints=${hinted.size} errClass=${cancelled::class.java.simpleName} " +
                        "retryDelayMs=$RETRY_AFTER_BUSY_MS"
                )
                schedulePushAfterBusy(reason)
            } else {
                logger(
                    "cycle=push outcome=cancelled reason=$reason dirtyHints=${hinted.size} " +
                        "errClass=${cancelled::class.java.simpleName}"
                )
            }
        } catch (t: Throwable) {
            val classification = SyncErrorClassifier.classify(t)
            if (
                isLocalMutationPushReason(reason) &&
                classification.category == SyncErrorCategory.NetworkOfflineOrTimeout
            ) {
                synchronized(dirtyLock) { dirtyHints.addAll(hinted) }
                pendingLocalHistoryPushSignal = true
                schedulePushAfterBusy(reason)
            }
            logger(
                "cycle=push outcome=fail reason=$reason durationMs=0 sessionsAttempted=$pendingSize " +
                    "sessionsUploaded=0 skippedDirtyLocal=0 coalesced=$coalesced dirtySetMode=$dirtySetMode " +
                    "owner=auto_push errKind=${classification.category} httpStatus=${classification.httpStatus} " +
                    "postgrestCode=${classification.postgrestCode} pendingUidSample=$pendingUidSample"
            )
        }
    }

    private suspend fun recordHistorySyncEventIfNeeded(
        ownerUserId: String,
        summary: HistorySessionBackupPushSummary?
    ) {
        val remoteIds = summary?.remoteIds.orEmpty()
            .map(::canonicalSessionRemoteId)
            .distinct()
            .sorted()
        if (remoteIds.isEmpty() || !syncEventRemote.isConfigured) return
        val batchId = java.util.UUID.randomUUID().toString()
        val params = SyncEventRecordRpcParams(
            domain = SyncEventDomains.HISTORY,
            eventType = SyncEventTypes.HISTORY_CHANGED,
            changedCount = remoteIds.size,
            entityIds = SyncEventEntityIds(sessionIds = remoteIds),
            storeId = null,
            source = "android_history_session_push",
            sourceDeviceId = null,
            batchId = batchId,
            clientEventId = "android-$batchId-history-${remoteIds.joinToString(",").hashCode().toUInt().toString(16)}"
        )
        withContext(NonCancellable) {
            var recorded: Result<SyncEventRemoteRow>? = null
            var attempts = 0
            for (index in 0 until HISTORY_SYNC_EVENT_RECORD_ATTEMPTS) {
                attempts = index + 1
                val result = syncEventRemote.recordSyncEvent(params)
                recorded = result
                if (result.isSuccess) break
                if (index < HISTORY_SYNC_EVENT_RECORD_ATTEMPTS - 1) {
                    delay(HISTORY_SYNC_EVENT_RETRY_DELAY_MS)
                }
            }
            val recordedResult = recorded
            val outboxInserted = if (recordedResult?.isSuccess == true) {
                0
            } else {
                enqueueHistorySyncEvent(ownerUserId, params, recordedResult?.exceptionOrNull())
            }
            val outcome = when {
                recordedResult?.isSuccess == true -> "ok"
                outboxInserted > 0 -> "enqueued"
                else -> "fail"
            }
            logger(
                "cycle=push syncEvent=history outcome=$outcome sessions=${remoteIds.size} " +
                    "syncType=EVENT_INCREMENTAL fullPull=false attempts=$attempts outboxInserted=$outboxInserted"
            )
        }
    }

    private suspend fun enqueueHistorySyncEvent(
        ownerUserId: String,
        params: SyncEventRecordRpcParams,
        error: Throwable?
    ): Int {
        val outboxDao = syncEventOutboxDao ?: return 0
        val ids = params.entityIds ?: SyncEventEntityIds()
        val metadata = params.metadata
        val errorType = error?.let { SyncErrorClassifier.classify(it).category.name } ?: "unknown"
        val inserted = outboxDao.insert(
            SyncEventOutboxEntry(
                ownerUserId = ownerUserId,
                storeScope = params.storeId.orEmpty(),
                domain = params.domain,
                eventType = params.eventType,
                source = params.source,
                sourceDeviceId = params.sourceDeviceId,
                batchId = params.batchId,
                clientEventId = params.clientEventId
                    ?: "android-history-${params.batchId ?: java.util.UUID.randomUUID()}",
                changedCount = params.changedCount,
                entityIdsJson = syncEventJson.encodeToString(ids),
                metadataJson = syncEventJson.encodeToString(metadata),
                createdAtMs = System.currentTimeMillis(),
                lastAttemptAtMs = System.currentTimeMillis(),
                lastErrorType = errorType
            )
        )
        return if (inserted != -1L) 1 else 0
    }

    private suspend fun ensureDeviceActiveForSync(reason: String): Boolean {
        val authorization = deviceAuthorization ?: return true
        val result = authorization.ensureActiveForCloudWrite("history_push:$reason")
        if (result.isSuccess) {
            resetDeviceStatusPushRetry()
            return true
        }

        val snapshot = (result.exceptionOrNull() as? ShopDeviceAuthorizationBlockedException)?.snapshot
        logger(
            "cycle=push outcome=blocked_by_device_status reason=$reason " +
                "status=${snapshot?.status ?: "unknown"} code=${snapshot?.code ?: "unknown"} " +
                "recommendedAction=${snapshot?.recommendedAction ?: "contact_shop_admin"}"
        )
        if (isLocalMutationPushReason(reason) && snapshot.isRetryableDeviceStatus()) {
            pendingLocalHistoryPushSignal = true
            schedulePushAfterRetryableDeviceStatus(reason)
        }
        return false
    }

    private sealed interface DeviceStatusRetryPlan {
        data class Scheduled(val attempt: Int, val delayMs: Long) : DeviceStatusRetryPlan
        data class Suppressed(val attempts: Int) : DeviceStatusRetryPlan
    }

    private fun isFullReconciliationReason(reason: String): Boolean =
        reason == REASON_LOGIN_FRESH_TICK ||
            reason == "retry_after_busy_$REASON_LOGIN_FRESH_TICK"

    private fun isLocalMutationPushReason(reason: String): Boolean =
        reason == "local_commit" ||
            reason.startsWith("local_commit_") ||
            reason.startsWith("retry_after_busy_local_commit")

    private fun compactBusyRetryReason(reason: String): String =
        if (reason.startsWith("retry_after_busy_")) reason else "retry_after_busy_$reason"

    private fun ShopDeviceAuthorizationSnapshot?.isRetryableDeviceStatus(): Boolean =
        this != null &&
            (
                status == "network_error" ||
                    reasonCode == "network_error" ||
                    recommendedAction == "retry_when_online"
                )
}

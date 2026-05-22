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

class HistorySessionPushCoordinator(
    private val repository: InventoryRepository,
    private val remote: SessionBackupRemoteDataSource,
    private val syncEventRemote: SyncEventRemoteDataSource = DisabledSyncEventRemoteDataSource,
    private val authFlow: StateFlow<AuthState>,
    private val flightOwner: SessionCloudSessionFlightOwner,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val DEBOUNCE_MS = 2_000L
        private const val LOG_SAMPLE_LIMIT = 5
        private const val REASON_LOGIN_FRESH_TICK = "login_fresh_tick"
    }

    private val dirtyHints = LinkedHashSet<Long>()
    private val dirtyLock = Any()

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
        schedule("local_commit")
    }

    fun onAppForeground() {
        isForeground = true
        schedule("resume_tick")
    }

    fun onAppBackground() {
        isForeground = false
    }

    fun onNetworkAvailable() {
        schedule("network_available")
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun schedule(reason: String) {
        tickle.tryEmit(reason)
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

        val hinted = synchronized(dirtyLock) {
            val copy = dirtyHints.toSet()
            dirtyHints.clear()
            copy
        }
        var pendingSize = 0
        var pendingUidSample = ""
        var coalesced = hinted.size > 1
        val fullReconciliation = reason == REASON_LOGIN_FRESH_TICK
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
                        summary = repository
                            .pushHistorySessionsToRemote(remote, auth.userId, candidateUids = null)
                            .getOrThrow()
                        pendingSize = summary?.attempted ?: 0
                        coalesced = coalesced || pendingSize > 1
                    } else {
                        val pending = repository.getPendingHistorySessionPushUids().toSet()
                        pendingSize = pending.size
                        pendingUidSample = pending.take(LOG_SAMPLE_LIMIT).joinToString(",")
                        coalesced = coalesced || pending.size > 1
                        if (pending.isEmpty()) {
                            emptyPending = true
                        } else {
                            summary = repository
                                .pushHistorySessionsToRemote(remote, auth.userId, pending)
                                .getOrThrow()
                        }
                    }
                }
            }
            if (emptyPending) {
                logger(
                    "cycle=push outcome=ok reason=$reason sessionsAttempted=0 sessionsUploaded=0 " +
                        "skippedDirtyLocal=0 coalesced=$coalesced dirtySetMode=$dirtySetMode owner=auto_push"
                )
                return
            }
            val s = summary
            recordHistorySyncEventIfNeeded(auth.userId, s)
            val b = bootstrap
            val bootstrapSummary = if (b != null) {
                " bootstrapInserted=${b.inserted} bootstrapUpdated=${b.updated} " +
                    "bootstrapSkipped=${b.skipped} bootstrapFailed=${b.failed} bootstrapUnsupported=${b.unsupported}"
            } else {
                ""
            }
            logger(
                "cycle=push outcome=ok reason=$reason durationMs=$durationMs " +
                    "sessionsAttempted=${s?.attempted ?: pendingSize} sessionsUploaded=${s?.uploaded ?: 0} " +
                    "skippedDirtyLocal=0 coalesced=$coalesced dirtySetMode=$dirtySetMode owner=auto_push" +
                    bootstrapSummary
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val classification = SyncErrorClassifier.classify(t)
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
        val capabilities = syncEventRemote.checkCapabilities(ownerUserId).getOrNull() ?: return
        if (!capabilities.recordSyncEventAvailable) return
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
        val recorded = syncEventRemote.recordSyncEvent(params)
        logger(
            "cycle=push syncEvent=history outcome=${if (recorded.isSuccess) "ok" else "fail"} " +
                "sessions=${remoteIds.size} syncType=EVENT_INCREMENTAL fullPull=false"
        )
    }
}

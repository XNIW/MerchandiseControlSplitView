package com.example.merchandisecontrolsplitview.data

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SupabaseSyncEventRealtimeSubscriber(
    private val client: SupabaseClient?,
    private val coordinator: CatalogAutoSyncCoordinator,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val SCHEMA = "public"
        private const val TABLE_NAME = "sync_events"
        private const val CHANNEL_PREFIX = "sync-events-v1"
        private const val STARTUP_RETRY_MS = 5_000L
        private const val TAG = "SyncEventsRealtime"
    }

    private val stateLock = Any()

    @Volatile
    private var startedForOwner: String? = null

    private var channel: RealtimeChannel? = null
    private var collectorJob: Job? = null
    private var subscribeJob: Job? = null

    fun start(ownerUserId: String) {
        if (client == null) {
            Log.i(TAG, "sync_events realtime disabled: client missing")
            return
        }
        synchronized(stateLock) {
            if (startedForOwner == ownerUserId) return
        }
        stop()
        synchronized(stateLock) {
            val realtimeChannel = client.channel("$CHANNEL_PREFIX-$ownerUserId")
            collectorJob = realtimeChannel
                .postgresChangeFlow<PostgresAction.Insert>(schema = SCHEMA) {
                    table = TABLE_NAME
                    filter("owner_user_id", FilterOperator.EQ, ownerUserId)
                }
                .onEach {
                    coordinator.onRemoteSyncEventSignal()
                }
                .launchIn(scope)
            subscribeJob = scope.launch {
                subscribeLoop(realtimeChannel)
            }
            channel = realtimeChannel
            startedForOwner = ownerUserId
        }
    }

    internal suspend fun awaitSubscribed(timeoutMs: Long = 10_000L): Boolean {
        val realtimeChannel = synchronized(stateLock) { channel } ?: return false
        return withTimeoutOrNull(timeoutMs) {
            realtimeChannel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
            true
        } ?: false
    }

    fun stop() {
        val realtimeChannel = synchronized(stateLock) {
            if (startedForOwner == null) return
            startedForOwner = null
            collectorJob?.cancel()
            subscribeJob?.cancel()
            channel.also { channel = null }
        }
        scope.launch {
            try {
                realtimeChannel?.unsubscribe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "sync_events realtime unsubscribe failed", error)
            }
        }
    }

    fun shutdown() {
        stop()
        try {
            client?.realtime?.disconnect()
        } catch (error: Throwable) {
            Log.w(TAG, "sync_events realtime disconnect failed", error)
        }
        scope.cancel()
    }

    private suspend fun subscribeLoop(realtimeChannel: RealtimeChannel) {
        while (true) {
            try {
                realtimeChannel.subscribe(blockUntilSubscribed = true)
                Log.i(TAG, "sync_events realtime subscribed")
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "sync_events realtime subscribe failed; retry in ${STARTUP_RETRY_MS}ms", error)
                delay(STARTUP_RETRY_MS)
            }
        }
    }
}

package com.example.merchandisecontrolsplitview.data

import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
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
import kotlin.time.Duration.Companion.seconds

/**
 * Owner unico della subscription Realtime lato app.
 *
 * Responsabilità deliberate:
 * - apre un solo canale `postgres_changes` per `shared_sheet_sessions`;
 * - normalizza il record remoto in [SessionRemotePayload];
 * - inoltra solo [RemoteSignal.PayloadAvailable] al coordinator;
 * - non scrive mai Room o UI direttamente.
 *
 * Se la config Supabase manca, il subscriber si auto-disabilita senza alterare i flussi
 * locali: l'app resta pienamente offline-first.
 */
class SupabaseRealtimeSessionSubscriber(
    private val client: io.github.jan.supabase.SupabaseClient?,
    private val coordinator: RealtimeRefreshCoordinator,
    private val businessDataScopeRuntimeGuard: Task126BusinessDataScopeRuntimeGuard =
        Task126UnmanagedBusinessDataScopeRuntimeGuard,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        internal const val SCHEMA = "public"
        internal const val TABLE_NAME = "shared_sheet_sessions"
        private const val CHANNEL_NAME = "shared-sheet-sessions-v1-v2"
        private const val STARTUP_RETRY_MS = 5_000L
        private const val TAG = "SupabaseRealtime"
    }

    private val stateLock = Any()

    @Volatile
    private var startedForScope: RemoteSignal.SourceScope? = null
    private var subscriptionGeneration = 0L

    private var channel: RealtimeChannel? = null
    private var collectorJob: Job? = null
    private var subscribeJob: Job? = null

    fun start(ownerUserId: String, shopId: String? = null) {
        if (client == null) {
            Log.i(TAG, "Supabase Realtime disabilitato: client assente")
            return
        }
        val normalizedScope = RemoteSignal.SourceScope(
            ownerUserId = ownerUserId.trim(),
            shopId = shopId?.trim()?.takeIf { it.isNotEmpty() }
        )
        val admissionToken = try {
            businessDataScopeRuntimeGuard.captureBusinessDataScopeSignal(
                normalizedScope.ownerUserId,
                normalizedScope.shopId
            )
        } catch (_: Task126BusinessDataScopeChangedException) {
            Log.i(TAG, "Supabase Realtime non avviato: business scope non corrente")
            return
        }

        synchronized(stateLock) {
            if (startedForScope == normalizedScope) return
        }
        stop()

        synchronized(stateLock) {
            if (!businessDataScopeRuntimeGuard.isCurrentBusinessDataScopeSignal(admissionToken)) {
                Log.i(TAG, "Supabase Realtime non avviato: business scope cambiato")
                return
            }
            subscriptionGeneration += 1L
            val collectorGeneration = subscriptionGeneration
            val realtimeChannel = client.channel("$CHANNEL_NAME-$collectorGeneration")
            collectorJob = realtimeChannel
                .postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
                    table = TABLE_NAME
                    filter("owner_user_id", FilterOperator.EQ, normalizedScope.ownerUserId)
                }
                .onEach { action ->
                    if (action is HasRecord) {
                        forwardPayload(action, normalizedScope, collectorGeneration)
                    }
                }
                .launchIn(scope)

            subscribeJob = scope.launch {
                subscribeLoop(realtimeChannel)
            }
            channel = realtimeChannel
            startedForScope = normalizedScope
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
            subscriptionGeneration += 1L
            startedForScope = null
            collectorJob?.cancel()
            subscribeJob?.cancel()
            collectorJob = null
            subscribeJob = null
            channel.also { channel = null }
        }

        scope.launch {
            try {
                realtimeChannel?.let { oldChannel ->
                    client?.realtime?.removeChannel(oldChannel)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Errore durante unsubscribe realtime", error)
            }
        }
    }

    fun shutdown() {
        stop()
        try {
            client?.realtime?.disconnect()
        } catch (error: Throwable) {
            Log.w(TAG, "Errore durante disconnect realtime", error)
        }
        scope.cancel()
    }

    private suspend fun subscribeLoop(realtimeChannel: RealtimeChannel) {
        while (true) {
            try {
                realtimeChannel.subscribe(blockUntilSubscribed = true)
                Log.i(TAG, "Supabase Realtime subscribed su $SCHEMA.$TABLE_NAME")
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Subscribe realtime fallita, retry tra ${STARTUP_RETRY_MS}ms",
                    error
                )
                delay(STARTUP_RETRY_MS)
            }
        }
    }

    private fun forwardPayload(
        action: HasRecord,
        expectedScope: RemoteSignal.SourceScope,
        collectorGeneration: Long
    ) {
        val actionKind = action::class.simpleName ?: "UnknownAction"
        val record = action.decodeRecordOrNull<SharedSheetSessionRecord>()
        if (record == null) {
            Log.w(TAG, "Evento realtime ignorato: record non decodificabile (action=$actionKind)")
            return
        }
        val recordScope = RemoteSignal.SourceScope(
            ownerUserId = record.ownerUserId?.trim().orEmpty(),
            shopId = record.shopId?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (recordScope != expectedScope) {
            Log.w(TAG, "Evento realtime ignorato: source scope non corrente")
            return
        }
        Log.i(
            TAG,
            "Evento realtime ricevuto: action=$actionKind " +
                "payloadVersion=${record.payloadVersion} → forward a coordinator"
        )
        synchronized(stateLock) {
            if (
                subscriptionGeneration != collectorGeneration ||
                startedForScope != expectedScope
            ) {
                Log.i(TAG, "Evento realtime ignorato: subscription generation stale")
                return
            }
            coordinator.onRemoteSignal(
                RemoteSignal.PayloadAvailable(
                    payload = record.toSessionRemotePayload(),
                    sourceScope = recordScope
                )
            )
        }
    }
}

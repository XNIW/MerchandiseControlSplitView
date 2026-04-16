package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Coordinator unico per l'invalidazione remota e il refresh controllato (task 010).
 *
 * ## Percorso garantito
 * Segnale remoto → [onRemoteSignal] → buffer coalescing → debounce →
 * [InventoryRepository.applyRemoteSessionPayloadBatch] → DAO → Room → Flow UI.
 *
 * Nessun composable, ViewModel o stato UI viene scritto direttamente da questo coordinator.
 * La UI aggiorna automaticamente via Flow Room già esistenti non appena Room viene scritto.
 *
 * ## Policy operative
 *
 * - **Coalescing per remoteId:** eventi multipli sullo stesso [SessionRemotePayload.remoteId]
 *   nella stessa finestra di debounce producono un solo apply (l'ultimo payload vince).
 * - **Debounce ([DEBOUNCE_MS]):** nessun apply viene eseguito finché non trascorrono
 *   [DEBOUNCE_MS] ms dall'ultimo segnale ricevuto. Reset ad ogni nuovo segnale.
 * - **Single-flight naturale:** il debouncer serializza i drain; nessuna catena
 *   fetch/apply concorrente per la stessa finestra di segnali.
 * - **Foreground-first:** [onAppForeground] / [onAppBackground] controllano se il drain
 *   può essere eseguito. Il buffer cresce durante il background; al ritorno in foreground
 *   il tickle viene rilanciato e il drain eseguito entro [DEBOUNCE_MS] ms.
 *
 * ## Garanzie di non-interferenza
 * - Non tocca UI, ViewModel o composable state.
 * - Non intercetta [InventoryRepository.applyImport] o altri path locali.
 * - Non amplia il contratto oltre la baseline 009: remote-wins inbound, no merge granulare.
 * - L'assenza di connettività o segnali non cambia il comportamento locale.
 *
 * ## Integrazione Supabase Realtime
 * [SupabaseRealtimeSessionSubscriber] chiama [onRemoteSignal] per ogni payload remoto
 * ricevuto dal canale `postgres_changes`. Il coordinator resta agnostico sulla fonte
 * del segnale e continua a materializzare solo via repository.
 *
 * ## Aggancio lifecycle
 * [MerchandiseControlApplication] collega il coordinator al `ProcessLifecycleOwner`
 * dell'app. Il default resta [isForeground] = true per evitare blocchi durante il
 * bootstrap prima del primo evento lifecycle.
 *
 * @param repository Il repository locale, unico punto di materializzazione in Room.
 * @param scope CoroutineScope dell'owner (default: IO + SupervisorJob).
 *              Iniettare un TestScope nei test per controllare il timing.
 * @param debounceMs Finestra di debounce in millisecondi (default: [DEBOUNCE_MS]).
 */
class RealtimeRefreshCoordinator(
    private val repository: InventoryRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    val debounceMs: Long = DEBOUNCE_MS
) {
    companion object {
        /** Finestra di debounce: nessun apply prima che trascorrano 2s dall'ultimo segnale. */
        const val DEBOUNCE_MS = 2_000L
    }

    // Buffer coalescing: remoteId → payload (l'ultimo payload vince per ogni remoteId).
    // Accesso diretto da onRemoteSignal (chiamato dall'adapter Realtime) e da runDrain (coroutine).
    private val pendingBuffer = LinkedHashMap<String, SessionRemotePayload>()
    private val bufferLock = Any()

    /**
     * true se l'app è in foreground e i drain possono essere eseguiti.
     * Default true: nessun blocco finché il lifecycle non è agganciato esplicitamente.
     */
    @Volatile
    var isForeground: Boolean = true
        private set

    // Tickle: ogni segnale inbound sveglia il debouncer. Buffer=1 + DROP_OLDEST: tickle multipli
    // in rapida successione si riducono a un singolo evento — il debounce gestisce il resto.
    private val _tickle = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        startDebouncer()
    }

    // --- API pubblica ---

    /**
     * Riceve un segnale remoto normalizzato e lo accoda nel buffer coalescing.
     * Thread-safe. Non blocca il chiamante.
     */
    fun onRemoteSignal(signal: RemoteSignal) {
        when (signal) {
            is RemoteSignal.PayloadAvailable -> {
                synchronized(bufferLock) {
                    pendingBuffer[signal.payload.remoteId] = signal.payload // coalescing: l'ultimo vince
                }
                _tickle.tryEmit(Unit)
            }
        }
    }

    /**
     * Informa il coordinator che l'app è tornata in foreground.
     * Se il buffer ha payload pending, sveglia il debouncer per eseguire il drain.
     */
    fun onAppForeground() {
        isForeground = true
        val hasPending = synchronized(bufferLock) { pendingBuffer.isNotEmpty() }
        if (hasPending) _tickle.tryEmit(Unit)
    }

    /**
     * Informa il coordinator che l'app è andata in background.
     * I drain successivi vengono saltati finché non torna in foreground.
     * Il buffer rimane intatto per il riallineamento al resume.
     */
    fun onAppBackground() {
        isForeground = false
    }

    /**
     * Chiude il coordinator e cancella il suo CoroutineScope.
     * Chiamare al teardown del componente owner (es. Application.onTerminate nei test).
     */
    fun shutdown() {
        scope.cancel()
    }

    // --- Internal (internal per testabilità diretta) ---

    /**
     * Drena il buffer e applica il batch al repository.
     * Skippa se l'app è in background. No-op se il buffer è vuoto.
     *
     * Single-flight naturale: il debouncer chiama questa funzione in modo serializzato
     * (il collect blocca finché runDrain non ritorna), quindi non ci sono catene concorrenti.
     */
    internal suspend fun runDrain() {
        if (!isForeground) return
        val toApply: List<SessionRemotePayload>
        synchronized(bufferLock) {
            if (pendingBuffer.isEmpty()) return
            toApply = pendingBuffer.values.toList()
            pendingBuffer.clear()
        }
        applyBatch(toApply)
    }

    @OptIn(FlowPreview::class)
    private fun startDebouncer() {
        // Single-flight naturale: collect è sequenziale, runDrain blocca il debouncer
        // finché l'apply non termina. Segnali in arrivo durante l'apply vengono buffered
        // e processati nel drain successivo (dopo il debounce dell'ultimo segnale ricevuto).
        scope.launch {
            _tickle
                .debounce(debounceMs)
                .collect { runDrain() }
        }
    }

    private suspend fun applyBatch(payloads: List<SessionRemotePayload>) {
        try {
            repository.applyRemoteSessionPayloadBatch(payloads)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Swallow non-cancellation errors: a transient apply failure must not crash the coordinator.
        }
    }
}

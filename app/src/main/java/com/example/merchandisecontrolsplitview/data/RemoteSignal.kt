package com.example.merchandisecontrolsplitview.data

/**
 * Segnale remoto normalizzato ricevuto dall'adapter Realtime (task 010).
 *
 * L'adapter Supabase (ancora da implementare) traduce gli eventi del canale
 * in uno di questi tipi prima di passarli a [RealtimeRefreshCoordinator.onRemoteSignal].
 *
 * Solo [PayloadAvailable] è supportato nella baseline 010: il payload completo
 * arriva nel segnale (es. postgres_changes con campo `new`), senza fetch separato.
 */
sealed class RemoteSignal {
    /**
     * Il payload remoto completo è disponibile nel segnale.
     * Il coordinator può applicarlo direttamente via repository senza round-trip aggiuntivi.
     */
    data class PayloadAvailable(val payload: SessionRemotePayload) : RemoteSignal()
}

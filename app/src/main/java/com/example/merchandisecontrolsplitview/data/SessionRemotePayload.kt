package com.example.merchandisecontrolsplitview.data

/**
 * Contratto minimo del payload remoto per una sessione persistita condivisibile.
 *
 * NON è una replica 1:1 di [HistoryEntry]: è un modello logico autosufficiente.
 *
 * [payloadVersion] permette evoluzione futura senza rompere Room-first.
 * Il payload deve poter essere riletto senza dipendenze obbligatorie da catalogo
 * remoto, FK cloud o lookup runtime (baseline DEC-017, CA-11).
 *
 * Campi esclusi deliberatamente in v1:
 * - [HistoryEntry.wasExported] — stato locale/export
 * - [HistoryEntry.syncStatus] — enum locale/UI, non semantica cloud
 * - [HistoryEntry.uid] — chiave locale di navigazione, non identità remota
 * - [HistoryEntry.id] — stringa rinominabile, non stabile
 * - [HistoryEntry.editable] / [HistoryEntry.complete] — opzionali; esclusi nel v1
 *   perché il payload v1 è read-only condivisibile, non edit-resumable
 */
data class SessionRemotePayload(
    val remoteId: String,
    val payloadVersion: Int,
    val timestamp: String,
    val supplier: String,
    val category: String,
    val isManualEntry: Boolean,
    val data: List<List<String>>
)

/** Versione corrente del contratto payload. Incrementare quando il contratto cambia. */
const val SESSION_PAYLOAD_VERSION = 1

/**
 * Fingerprint deterministico dei campi payload-rilevanti (task 009 / baseline conflitti).
 *
 * Usato per la fast-path di skip inbound: se il fingerprint coincide con
 * [HistoryEntryRemoteRef.lastRemotePayloadFingerprint] e l'entry è allineata
 * ([HistoryEntryRemoteRef.localChangeRevision] == [HistoryEntryRemoteRef.lastSyncedLocalRevision]),
 * l'apply può essere skippato senza caricare [HistoryEntry] dal DB.
 *
 * La funzione è deterministica: stesso payload → stesso fingerprint.
 * Basata su hashCode: sufficiente per la baseline; in caso di collisione il fallback
 * field-by-field nel repository produce comunque il risultato corretto.
 */
fun SessionRemotePayload.payloadFingerprint(): String =
    "$timestamp|$supplier|$category|$isManualEntry|${data.flatten().joinToString(",")}"
        .hashCode().toString()

/**
 * Costruisce il [SessionRemotePayload] da questa entry e dal suo [remoteId] già persistito.
 * Non leggere [remoteId] da [HistoryEntry.uid] o [HistoryEntry.id].
 */
fun HistoryEntry.toRemotePayload(remoteId: String): SessionRemotePayload =
    SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = SESSION_PAYLOAD_VERSION,
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data
    )

package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

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
 * - [HistoryEntry.id] — stringa legacy/export, non identità remota
 * - [HistoryEntry.editable] / [HistoryEntry.complete] — esclusi nel v1
 *   perché il payload v1 è read-only condivisibile, non edit-resumable
 *
 * In v2 [displayName] e [sessionOverlay] ripristinano titolo user-facing e stato
 * operativo della sessione senza rendere il cloud source of truth del modello Room.
 */
data class SessionRemotePayload(
    val remoteId: String,
    val payloadVersion: Int,
    val timestamp: String,
    val supplier: String,
    val category: String,
    val isManualEntry: Boolean,
    val data: List<List<String>>,
    val displayName: String? = null,
    val sessionOverlay: SessionOverlay? = null
)

@Serializable
data class SessionOverlay(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("overlay_schema")
    val overlaySchema: Int = SESSION_OVERLAY_SCHEMA,
    val editable: List<List<String>>,
    val complete: List<Boolean>
)

/** Versione corrente del contratto payload. Incrementare quando il contratto cambia. */
const val SESSION_PAYLOAD_VERSION = 2
const val SESSION_PAYLOAD_VERSION_LEGACY_V1 = 1
const val SESSION_OVERLAY_SCHEMA = 1
const val SESSION_OVERLAY_MAX_BYTES = 512 * 1024

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
    if (payloadVersion == SESSION_PAYLOAD_VERSION_LEGACY_V1) {
        legacyV1CanonicalPayloadString()
    } else {
        canonicalSessionPayloadString()
    }
        .hashCode().toString()

internal fun SessionRemotePayload.legacyV1CanonicalPayloadString(): String =
    "$timestamp|$supplier|$category|$isManualEntry|${data.flatten().joinToString(",")}"

internal fun SessionRemotePayload.canonicalSessionPayloadString(): String =
    buildString {
        append("v=").append(payloadVersion)
        append("|rid=").append(remoteId.length).append(':').append(remoteId)
        append("|display=").append(canonicalNullable(displayName))
        append("|ts=").append(timestamp.length).append(':').append(timestamp)
        append("|supplier=").append(supplier.length).append(':').append(supplier)
        append("|category=").append(category.length).append(':').append(category)
        append("|manual=").append(isManualEntry)
        append("|data=").append(canonicalNestedStrings(data))
        append("|overlay=").append(sessionOverlay?.canonicalString() ?: "null")
    }

internal fun SessionOverlay.canonicalString(): String =
    buildString {
        append("schema=").append(overlaySchema)
        append("|editable=").append(canonicalNestedStrings(editable))
        append("|complete=")
        complete.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(if (value) '1' else '0')
        }
    }

private fun canonicalNullable(value: String?): String =
    value?.let { "${it.length}:$it" } ?: "null"

private fun canonicalNestedStrings(rows: List<List<String>>): String =
    rows.joinToString(separator = ";", prefix = "[", postfix = "]") { row ->
        row.joinToString(separator = ",", prefix = "[", postfix = "]") { cell ->
            "${cell.length}:$cell"
        }
    }

/**
 * Costruisce il [SessionRemotePayload] da questa entry e dal suo [remoteId] già persistito.
 * Non leggere [remoteId] da [HistoryEntry.uid] o [HistoryEntry.id].
 */
fun HistoryEntry.toRemotePayload(remoteId: String): SessionRemotePayload =
    SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = SESSION_PAYLOAD_VERSION,
        displayName = displayName,
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data,
        sessionOverlay = SessionOverlay(
            overlaySchema = SESSION_OVERLAY_SCHEMA,
            editable = editable,
            complete = complete
        )
    )

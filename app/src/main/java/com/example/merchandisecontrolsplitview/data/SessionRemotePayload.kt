package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val sessionOverlay: SessionOverlay? = null,
    val deletedAt: String? = null
)

internal fun canonicalSessionRemoteId(remoteId: String): String =
    remoteId.trim().lowercase()

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
 * Fingerprint deterministico dei campi logici payload-rilevanti (task 009 / baseline conflitti).
 *
 * Usato per la fast-path di skip inbound: se il fingerprint coincide con
 * [HistoryEntryRemoteRef.lastRemotePayloadFingerprint] e l'entry è allineata
 * ([HistoryEntryRemoteRef.localChangeRevision] == [HistoryEntryRemoteRef.lastSyncedLocalRevision]),
 * l'apply può essere skippato senza caricare [HistoryEntry] dal DB.
 *
 * TASK-135: il fingerprint non include [remoteId], così Android e iOS possono
 * riconoscere e collegare una sessione local-only equivalente a una row remota
 * senza duplicarla.
 */
fun SessionRemotePayload.payloadFingerprint(): String =
    sha256Hex(canonicalLogicalSessionPayloadString())

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
        append("|deleted=").append(canonicalNullable(deletedAt))
    }

internal fun SessionRemotePayload.canonicalLogicalSessionPayloadString(): String =
    listOf(
        payloadVersion.toString(),
        canonicalTrimmed(payloadDisplayNameOrBlank(displayName)),
        normalizedHistoryTimestamp(timestamp),
        canonicalTrimmed(supplier),
        canonicalTrimmed(category),
        if (isManualEntry) "1" else "0",
        canonicalNestedStringJson(data),
        canonicalNestedStringJson(sessionOverlay?.editable ?: emptyList()),
        canonicalBooleanJson(sessionOverlay?.complete ?: emptyList()),
        deletedAt?.let(::normalizedHistoryTimestamp).orEmpty()
    ).joinToString("|")

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

private val historyLogicalFingerprintJson = Json { encodeDefaults = true }
private val historyTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val historyUuidDisplayNamePattern = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

private fun canonicalTrimmed(value: String): String = value.trim()

internal fun payloadDisplayNameOrBlank(value: String?): String {
    val raw = value.orEmpty()
    return if (historyUuidDisplayNamePattern.matches(raw.trim())) "" else raw
}

private fun canonicalNestedStringJson(value: List<List<String>>): String =
    historyLogicalFingerprintJson.encodeToString(value)

private fun canonicalBooleanJson(value: List<Boolean>): String =
    historyLogicalFingerprintJson.encodeToString(value)

private fun normalizedHistoryTimestamp(rawValue: String): String {
    val trimmed = rawValue.trim()
    if (trimmed.isBlank()) {
        return formatHistoryInstant(Instant.EPOCH)
    }

    runCatching {
        LocalDateTime.parse(trimmed, historyTimestampFormatter)
            .toInstant(ZoneOffset.UTC)
    }.getOrNull()?.let { return formatHistoryInstant(it) }

    runCatching { Instant.parse(trimmed) }
        .getOrNull()
        ?.let { return formatHistoryInstant(it) }

    runCatching { OffsetDateTime.parse(trimmed).toInstant() }
        .getOrNull()
        ?.let { return formatHistoryInstant(it) }

    runCatching {
        LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .toInstant(ZoneOffset.UTC)
    }.getOrNull()?.let { return formatHistoryInstant(it) }

    return formatHistoryInstant(Instant.EPOCH)
}

private fun formatHistoryInstant(instant: Instant): String =
    historyTimestampFormatter.format(instant.atOffset(ZoneOffset.UTC))

private fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * Costruisce il [SessionRemotePayload] da questa entry e dal suo [remoteId] già persistito.
 * Non leggere [remoteId] da [HistoryEntry.uid] o [HistoryEntry.id].
 */
fun HistoryEntry.toRemotePayload(remoteId: String): SessionRemotePayload =
    SessionRemotePayload(
        remoteId = canonicalSessionRemoteId(remoteId),
        payloadVersion = SESSION_PAYLOAD_VERSION,
        displayName = payloadDisplayNameOrBlank(displayName),
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data,
        sessionOverlay = SessionOverlay(
            overlaySchema = SESSION_OVERLAY_SCHEMA,
            editable = editable,
            complete = complete
        ),
        deletedAt = deletedAt
    )

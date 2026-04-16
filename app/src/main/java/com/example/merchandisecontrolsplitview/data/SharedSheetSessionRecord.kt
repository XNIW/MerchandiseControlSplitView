package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Record remoto della tabella `public.shared_sheet_sessions`.
 *
 * È un artifact cloud dedicato al payload v1: non mirror 1:1 di `history_entries`,
 * nessuna chiave locale Room e nessun campo UI-only.
 */
@Serializable
data class SharedSheetSessionRecord(
    @SerialName("remote_id")
    val remoteId: String,
    @SerialName("payload_version")
    val payloadVersion: Int,
    val timestamp: String,
    val supplier: String,
    val category: String,
    @SerialName("is_manual_entry")
    val isManualEntry: Boolean,
    val data: List<List<String>>
)

fun SharedSheetSessionRecord.toSessionRemotePayload(): SessionRemotePayload =
    SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = payloadVersion,
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data
    )

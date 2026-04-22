package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Record remoto della tabella `public.shared_sheet_sessions`.
 *
 * È un artifact cloud dedicato al payload sessione: non mirror 1:1 di `history_entries`,
 * nessuna chiave locale Room e nessun campo UI-only.
 */
@Serializable
data class SharedSheetSessionRecord(
    @SerialName("remote_id")
    val remoteId: String,
    @SerialName("payload_version")
    val payloadVersion: Int,
    @SerialName("display_name")
    val displayName: String? = null,
    val timestamp: String,
    val supplier: String,
    val category: String,
    @SerialName("is_manual_entry")
    val isManualEntry: Boolean,
    val data: List<List<String>>,
    @SerialName("session_overlay")
    val sessionOverlay: SessionOverlay? = null,
    /** Presente nelle SELECT PostgREST; ignorato nel mapping verso [SessionRemotePayload]. */
    @SerialName("owner_user_id")
    val ownerUserId: String? = null,
    /** Opzionale: column server-side (task 010/012). */
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * Riga upsert PostgREST verso [shared_sheet_sessions].
 * Il writer nuovo emette sempre payload v2 + ownership (DEC-040).
 */
@Serializable
data class SharedSheetSessionUpsertRow(
    @SerialName("remote_id") val remoteId: String,
    @SerialName("payload_version") val payloadVersion: Int,
    @SerialName("display_name") val displayName: String,
    val timestamp: String,
    val supplier: String,
    val category: String,
    @SerialName("is_manual_entry") val isManualEntry: Boolean,
    val data: List<List<String>>,
    @SerialName("session_overlay") val sessionOverlay: SessionOverlay,
    @SerialName("owner_user_id") val ownerUserId: String
)

fun SessionRemotePayload.toSharedSheetSessionUpsertRow(ownerUserId: String): SharedSheetSessionUpsertRow =
    SharedSheetSessionUpsertRow(
        remoteId = remoteId,
        payloadVersion = payloadVersion,
        displayName = displayName.orEmpty(),
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data,
        sessionOverlay = requireNotNull(sessionOverlay) {
            "payload v2 writer requires session_overlay"
        },
        ownerUserId = ownerUserId
    )

fun SharedSheetSessionRecord.toSessionRemotePayload(): SessionRemotePayload =
    SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = payloadVersion,
        displayName = displayName,
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data,
        sessionOverlay = sessionOverlay
    )

package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tabella bridge locale: mappa history_entry_uid <-> remote_id.
 *
 * Invarianti da rispettare (baseline DEC-017):
 * - [remoteId] è un UUID generato lato client una sola volta.
 * - [remoteId] non viene rigenerato su rename di [HistoryEntry].
 * - [remoteId] NON deve essere usato in route, navigation args o composable state.
 * - [historyEntryUid] rimane la chiave di navigazione locale (cfr. Screen.kt).
 *
 * Questa tabella viene popolata on-demand (lazy) tramite
 * [InventoryRepository.getOrCreateRemoteId], non al momento dell'insert dell'entry.
 */
@Entity(
    tableName = "history_entry_remote_refs",
    foreignKeys = [
        ForeignKey(
            entity = HistoryEntry::class,
            parentColumns = ["uid"],
            childColumns = ["historyEntryUid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["historyEntryUid"], unique = true),
        Index(value = ["remoteId"], unique = true)
    ]
)
data class HistoryEntryRemoteRef(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val historyEntryUid: Long,
    /** UUID v4 generato client-side; stabile per tutta la vita dell'entry locale. */
    val remoteId: String,

    // --- Sync state locale minimo (task 009 / baseline conflitti) ---

    /**
     * Revisione locale: incrementata ogni volta che un campo payload-rilevante
     * di [HistoryEntry] viene modificato localmente dopo la creazione del bridge.
     * Campi rilevanti: [HistoryEntry.timestamp], [HistoryEntry.supplier],
     * [HistoryEntry.category], [HistoryEntry.isManualEntry], [HistoryEntry.data].
     *
     * Non è un contatore assoluto di modifiche: misura la divergenza rispetto a
     * [lastSyncedLocalRevision]. Se uguale → entry allineata; se maggiore → dirty.
     */
    val localChangeRevision: Int = 0,

    /**
     * Revisione locale al momento dell'ultimo apply/push remoto riuscito.
     *
     * State machine per-record derivata:
     * - [localChangeRevision] == [lastSyncedLocalRevision] → allineato
     * - [localChangeRevision] > [lastSyncedLocalRevision] → dirty locale
     */
    val lastSyncedLocalRevision: Int = 0,

    /**
     * Epoch milliseconds dell'ultimo payload remoto applicato con successo
     * ([RemoteSessionApplyOutcome.Inserted] o [RemoteSessionApplyOutcome.Updated]).
     * Null se il bridge è stato creato localmente e nessun apply remoto è ancora avvenuto.
     */
    val lastRemoteAppliedAt: Long? = null,

    /**
     * Fingerprint dell'ultimo payload remoto applicato con successo.
     * Calcolato con [SessionRemotePayload.payloadFingerprint].
     *
     * Fast-path di skip inbound: se il fingerprint coincide con il payload in arrivo
     * E l'entry è allineata ([localChangeRevision] == [lastSyncedLocalRevision]),
     * l'apply può essere skippato senza caricare [HistoryEntry] dal DB.
     * Null se nessun apply remoto è ancora avvenuto.
     */
    val lastRemotePayloadFingerprint: String? = null
)

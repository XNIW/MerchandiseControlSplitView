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
    indices = [Index(value = ["historyEntryUid"], unique = true)]
)
data class HistoryEntryRemoteRef(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val historyEntryUid: Long,
    /** UUID v4 generato client-side; stabile per tutta la vita dell'entry locale. */
    val remoteId: String
)

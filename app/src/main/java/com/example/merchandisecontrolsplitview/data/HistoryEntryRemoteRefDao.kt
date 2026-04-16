package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryEntryRemoteRefDao {

    /**
     * Inserisce un nuovo ref. Usa IGNORE per gestire race condition:
     * se la riga esiste già (unique su historyEntryUid), il secondo insert viene ignorato.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: HistoryEntryRemoteRef): Long

    @Query("SELECT * FROM history_entry_remote_refs WHERE historyEntryUid = :uid LIMIT 1")
    suspend fun getByHistoryEntryUid(uid: Long): HistoryEntryRemoteRef?

    /** Cerca un ref per remoteId remoto. Usato per dedup pull (task 008). */
    @Query("SELECT * FROM history_entry_remote_refs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): HistoryEntryRemoteRef?

    @Query("DELETE FROM history_entry_remote_refs WHERE historyEntryUid = :uid")
    suspend fun deleteByHistoryEntryUid(uid: Long)

    // --- Sync state locale minimo (task 009 / baseline conflitti) ---

    /**
     * Incrementa [HistoryEntryRemoteRef.localChangeRevision] di 1.
     * Chiamato da [DefaultInventoryRepository.updateHistoryEntry] in modo centralizzato
     * quando un campo payload-rilevante di [HistoryEntry] viene modificato localmente.
     */
    @Query("UPDATE history_entry_remote_refs SET localChangeRevision = localChangeRevision + 1 WHERE historyEntryUid = :uid")
    suspend fun incrementLocalRevision(uid: Long)

    /**
     * Aggiorna lo stato di sync dopo un apply remoto riuscito (Inserted o Updated).
     *
     * Imposta [HistoryEntryRemoteRef.lastSyncedLocalRevision] alla revisione locale corrente
     * così che [localChangeRevision] == [lastSyncedLocalRevision] → entry allineata.
     */
    @Query("UPDATE history_entry_remote_refs SET lastSyncedLocalRevision = :rev, lastRemoteAppliedAt = :appliedAt, lastRemotePayloadFingerprint = :fingerprint WHERE historyEntryUid = :uid")
    suspend fun updateRemoteApplyState(uid: Long, rev: Int, appliedAt: Long, fingerprint: String)
}

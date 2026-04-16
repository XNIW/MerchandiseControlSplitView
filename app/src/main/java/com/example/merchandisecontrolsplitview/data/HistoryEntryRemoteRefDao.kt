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

    @Query("DELETE FROM history_entry_remote_refs WHERE historyEntryUid = :uid")
    suspend fun deleteByHistoryEntryUid(uid: Long)
}

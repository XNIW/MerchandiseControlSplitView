package com.example.merchandisecontrolsplitview.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryEntryDao {
    /**
     * NUOVO: Restituisce tutte le voci come un Flow, permettendo alla UI di reagire ai cambiamenti.
     * Sostituisce il vecchio metodo getAll() che era una suspend function.
     */
    @Query("SELECT * FROM HistoryEntry ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<HistoryEntry>>

    /**
     * NUOVO: Restituisce tutte le voci comprese tra due date (stringhe) come un Flow.
     * SQLite può confrontare le stringhe di data se sono nel formato AAAA-MM-GG.
     */
    @Query("SELECT * FROM HistoryEntry WHERE timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp DESC")
    fun getEntriesBetweenDatesFlow(startDate: String, endDate: String): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Update
    suspend fun update(entry: HistoryEntry)

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("SELECT * FROM HistoryEntry WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HistoryEntry?
}
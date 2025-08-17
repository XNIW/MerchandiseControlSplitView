package com.example.merchandisecontrolsplitview.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryEntryDao {
    @Query("SELECT * FROM history_entries ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history_entries WHERE timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp DESC")
    fun getEntriesBetweenDatesFlow(startDate: String, endDate: String): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry): Long

    @Update
    suspend fun update(entry: HistoryEntry)

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("SELECT * FROM history_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HistoryEntry?

    @Query("SELECT * FROM history_entries WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: Long): HistoryEntry?
}
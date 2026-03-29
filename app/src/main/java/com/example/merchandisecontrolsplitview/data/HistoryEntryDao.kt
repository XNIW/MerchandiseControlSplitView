package com.example.merchandisecontrolsplitview.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryEntryDao {
    @Query("SELECT * FROM history_entries ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<HistoryEntry>>

    @Query(
        """
        SELECT uid, id, timestamp, supplier, category, wasExported, syncStatus,
               orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
        FROM history_entries
        ORDER BY timestamp DESC
        """
    )
    fun getAllListItemsFlow(): Flow<List<HistoryEntryListItem>>

    @Query("SELECT * FROM history_entries WHERE timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp DESC")
    fun getEntriesBetweenDatesFlow(startDate: String, endDate: String): Flow<List<HistoryEntry>>

    @Query(
        """
        SELECT uid, id, timestamp, supplier, category, wasExported, syncStatus,
               orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
        FROM history_entries
        WHERE timestamp >= :startDate AND timestamp <= :endDate
        ORDER BY timestamp DESC
        """
    )
    fun getListItemsBetweenDatesFlow(startDate: String, endDate: String): Flow<List<HistoryEntryListItem>>

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

    @Query("SELECT timestamp FROM history_entries ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTimestamp(): String?

    @Query("SELECT EXISTS(SELECT 1 FROM history_entries)")
    fun hasEntriesFlow(): Flow<Boolean>
}

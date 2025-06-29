package com.example.merchandisecontrolsplitview.data

import androidx.room.*

@Dao
interface HistoryEntryDao {
    @Query("SELECT * FROM HistoryEntry ORDER BY uid DESC")
    suspend fun getAll(): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Update
    suspend fun update(entry: HistoryEntry)

    @Delete
    suspend fun delete(entry: HistoryEntry)
}
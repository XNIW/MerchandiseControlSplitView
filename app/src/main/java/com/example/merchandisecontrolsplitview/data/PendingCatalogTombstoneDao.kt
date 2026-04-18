package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingCatalogTombstoneDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PendingCatalogTombstone): Long

    @Query("SELECT * FROM pending_catalog_tombstones ORDER BY enqueuedAtMs ASC")
    suspend fun listPendingOrdered(): List<PendingCatalogTombstone>

    @Query("SELECT COUNT(*) FROM pending_catalog_tombstones")
    suspend fun count(): Int

    @Query("DELETE FROM pending_catalog_tombstones WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_catalog_tombstones SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttempt(id: Long)
}

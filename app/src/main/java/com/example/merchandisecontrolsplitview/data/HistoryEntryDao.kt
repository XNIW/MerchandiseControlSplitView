package com.example.merchandisecontrolsplitview.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Single source of truth for what the user-visible History screen should hide.
internal const val USER_VISIBLE_HISTORY_WHERE_CLAUSE = """
    id NOT LIKE 'APPLY_IMPORT_%'
    AND id NOT LIKE 'FULL_IMPORT_%'
"""

@Dao
interface HistoryEntryDao {
    @Query(
        "SELECT * FROM history_entries WHERE " +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            " ORDER BY timestamp DESC"
    )
    fun getAllUserVisibleFlow(): Flow<List<HistoryEntry>>

    @Query(
        """
        SELECT uid, id, displayName, timestamp, supplier, category, wasExported, syncStatus,
               orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
        FROM history_entries
        WHERE """ +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            """
        ORDER BY timestamp DESC
        """
    )
    fun getAllUserVisibleListItemsFlow(): Flow<List<HistoryEntryListItem>>

    @Query(
        "SELECT * FROM history_entries WHERE timestamp >= :startDate AND timestamp <= :endDate AND " +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            " ORDER BY timestamp DESC"
    )
    fun getUserVisibleEntriesBetweenDatesFlow(
        startDate: String,
        endDate: String
    ): Flow<List<HistoryEntry>>

    @Query(
        """
        SELECT uid, id, displayName, timestamp, supplier, category, wasExported, syncStatus,
               orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
        FROM history_entries
        WHERE timestamp >= :startDate AND timestamp <= :endDate
          AND """ +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            """
        ORDER BY timestamp DESC
        """
    )
    fun getUserVisibleListItemsBetweenDatesFlow(
        startDate: String,
        endDate: String
    ): Flow<List<HistoryEntryListItem>>

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

    @Query("SELECT * FROM history_entries WHERE uid = :uid LIMIT 1")
    fun observeByUid(uid: Long): Flow<HistoryEntry?>

    @Query("SELECT timestamp FROM history_entries ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTimestamp(): String?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM history_entries WHERE " +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            ")"
    )
    fun hasUserVisibleEntriesFlow(): Flow<Boolean>

    /** Snapshot per backup cloud sessioni (task 023): stesso filtro della UI History. */
    @Query(
        "SELECT * FROM history_entries WHERE " +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            " ORDER BY timestamp DESC"
    )
    suspend fun getAllUserVisibleSnapshot(): List<HistoryEntry>

    @Query(
        "SELECT * FROM history_entries WHERE uid IN (:uids) AND " +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE +
            " ORDER BY timestamp DESC"
    )
    suspend fun getUserVisibleSnapshotByUids(uids: List<Long>): List<HistoryEntry>

    @Query(
        """
        SELECT h.uid
        FROM history_entries h
        LEFT JOIN history_entry_remote_refs r ON r.historyEntryUid = h.uid
        WHERE h.id NOT LIKE 'APPLY_IMPORT_%'
          AND h.id NOT LIKE 'FULL_IMPORT_%'
          AND (
            r.historyEntryUid IS NULL
            OR r.lastRemoteAppliedAt IS NULL
            OR r.localChangeRevision > r.lastSyncedLocalRevision
          )
        ORDER BY h.timestamp DESC
        """
    )
    suspend fun getUserVisibleSessionPushCandidateUids(): List<Long>

    @Query(
        "SELECT COUNT(*) FROM history_entries WHERE " +
            USER_VISIBLE_HISTORY_WHERE_CLAUSE
    )
    suspend fun countUserVisible(): Int
}

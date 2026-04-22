package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CategoryRemoteRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: CategoryRemoteRef): Long

    @Query("SELECT * FROM category_remote_refs WHERE categoryId = :categoryId LIMIT 1")
    suspend fun getByCategoryId(categoryId: Long): CategoryRemoteRef?

    @Query("SELECT * FROM category_remote_refs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): CategoryRemoteRef?

    @Query("UPDATE category_remote_refs SET remoteId = :remoteId WHERE categoryId = :categoryId")
    suspend fun updateRemoteId(categoryId: Long, remoteId: String): Int

    @Query(
        """
        UPDATE category_remote_refs SET localChangeRevision = localChangeRevision + 1
        WHERE categoryId = :categoryId
        """
    )
    suspend fun incrementLocalRevision(categoryId: Long)

    @Query(
        """
        UPDATE category_remote_refs SET lastSyncedLocalRevision = :rev,
        lastRemoteAppliedAt = :appliedAt, lastRemotePayloadFingerprint = :fingerprint
        WHERE categoryId = :categoryId
        """
    )
    suspend fun updateRemoteApplyState(categoryId: Long, rev: Int, appliedAt: Long, fingerprint: String)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM category_remote_refs
            WHERE (lastRemoteAppliedAt IS NULL OR localChangeRevision > lastSyncedLocalRevision)
        )
        """
    )
    suspend fun hasPendingWork(): Boolean

    @Query("SELECT COUNT(*) FROM category_remote_refs")
    suspend fun countRows(): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM categories c
        LEFT JOIN category_remote_refs r ON r.categoryId = c.id
        WHERE r.id IS NULL
        """
    )
    suspend fun countLocalRowsMissingRemoteRef(): Int
}

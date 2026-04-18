package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProductRemoteRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: ProductRemoteRef): Long

    @Query("SELECT * FROM product_remote_refs WHERE productId = :productId LIMIT 1")
    suspend fun getByProductId(productId: Long): ProductRemoteRef?

    @Query("SELECT * FROM product_remote_refs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): ProductRemoteRef?

    @Query(
        """
        UPDATE product_remote_refs SET localChangeRevision = localChangeRevision + 1
        WHERE productId = :productId
        """
    )
    suspend fun incrementLocalRevision(productId: Long)

    @Query(
        """
        UPDATE product_remote_refs SET lastSyncedLocalRevision = :rev,
        lastRemoteAppliedAt = :appliedAt, lastRemotePayloadFingerprint = :fingerprint
        WHERE productId = :productId
        """
    )
    suspend fun updateRemoteApplyState(productId: Long, rev: Int, appliedAt: Long, fingerprint: String)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM product_remote_refs
            WHERE (lastRemoteAppliedAt IS NULL OR localChangeRevision > lastSyncedLocalRevision)
        )
        """
    )
    suspend fun hasPendingWork(): Boolean

    @Query("SELECT COUNT(*) FROM product_remote_refs")
    suspend fun countRows(): Int
}

package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SupplierRemoteRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: SupplierRemoteRef): Long

    @Query("SELECT * FROM supplier_remote_refs WHERE supplierId = :supplierId LIMIT 1")
    suspend fun getBySupplierId(supplierId: Long): SupplierRemoteRef?

    @Query("SELECT * FROM supplier_remote_refs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): SupplierRemoteRef?

    @Query("UPDATE supplier_remote_refs SET remoteId = :remoteId WHERE supplierId = :supplierId")
    suspend fun updateRemoteId(supplierId: Long, remoteId: String): Int

    @Query(
        """
        UPDATE supplier_remote_refs SET localChangeRevision = localChangeRevision + 1
        WHERE supplierId = :supplierId
        """
    )
    suspend fun incrementLocalRevision(supplierId: Long)

    @Query(
        """
        UPDATE supplier_remote_refs SET lastSyncedLocalRevision = :rev,
        lastRemoteAppliedAt = :appliedAt, lastRemotePayloadFingerprint = :fingerprint
        WHERE supplierId = :supplierId
        """
    )
    suspend fun updateRemoteApplyState(supplierId: Long, rev: Int, appliedAt: Long, fingerprint: String)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM supplier_remote_refs
            WHERE (lastRemoteAppliedAt IS NULL OR localChangeRevision > lastSyncedLocalRevision)
        )
        """
    )
    suspend fun hasPendingWork(): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM supplier_remote_refs
            WHERE lastRemoteAppliedAt IS NULL
        )
        """
    )
    suspend fun hasNeverAppliedRemoteRef(): Boolean

    @Query("SELECT COUNT(*) FROM supplier_remote_refs")
    suspend fun countRows(): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM suppliers s
        LEFT JOIN supplier_remote_refs r ON r.supplierId = s.id
        WHERE r.id IS NULL
        """
    )
    suspend fun countLocalRowsMissingRemoteRef(): Int
}

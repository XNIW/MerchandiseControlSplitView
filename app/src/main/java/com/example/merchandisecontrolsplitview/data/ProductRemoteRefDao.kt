package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProductRemoteRefDao {
    /** Current record only: actual ACK/inbound revision, every price bridge, no unconfirmed original call.
     * A quoted identity match is deliberately conservative; an ambiguous receipt can only delay confirmation.
     */
    @Query("""
        SELECT EXISTS(SELECT 1 FROM products p JOIN product_remote_refs r ON r.productId=p.id
            WHERE p.id=:productId AND r.lastRemoteAppliedAt IS NOT NULL
              AND r.localChangeRevision=r.lastSyncedLocalRevision
              AND NOT EXISTS(SELECT 1 FROM product_prices v LEFT JOIN product_price_remote_refs b ON b.productPriceId=v.id
                  WHERE v.productId=p.id AND b.id IS NULL)
              AND NOT EXISTS(SELECT 1 FROM sync_event_outbox o WHERE o.eventType='LOCAL_BUSINESS_WRITE_V1'
                  AND o.domain IN ('PRODUCTS','PATCH','PRICES') AND instr(o.metadataJson,'"'||r.remoteId||'"')>0))
    """)
    fun observeProductCloudConfirmed(productId: Long): kotlinx.coroutines.flow.Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: ProductRemoteRef): Long

    @Query("SELECT * FROM product_remote_refs WHERE productId = :productId LIMIT 1")
    suspend fun getByProductId(productId: Long): ProductRemoteRef?

    @Query("SELECT * FROM product_remote_refs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): ProductRemoteRef?

    @Query("SELECT * FROM product_remote_refs WHERE remoteId IN (:remoteIds)")
    suspend fun getByRemoteIds(remoteIds: List<String>): List<ProductRemoteRef>

    @Query("SELECT * FROM product_remote_refs WHERE productId IN (:productIds)")
    suspend fun getByProductIds(productIds: List<Long>): List<ProductRemoteRef>

    @Query("SELECT productId FROM product_remote_refs")
    suspend fun getAllProductIds(): List<Long>

    @Query("UPDATE product_remote_refs SET remoteId = :remoteId WHERE productId = :productId")
    suspend fun updateRemoteId(productId: Long, remoteId: String): Int

    @Query(
        """
        UPDATE product_remote_refs SET localChangeRevision = localChangeRevision + 1
        , localChangedFields = '__all__'
        WHERE productId = :productId
        """
    )
    suspend fun incrementLocalRevision(productId: Long)

    @Query(
        """
        UPDATE product_remote_refs SET localChangeRevision = localChangeRevision + 1,
        localChangedFields = :changedFields
        WHERE productId = :productId
        """
    )
    suspend fun markLocalChanged(productId: Long, changedFields: String)

    @Query(
        """
        UPDATE product_remote_refs SET lastSyncedLocalRevision = :rev,
        lastRemoteAppliedAt = :appliedAt,
        lastRemotePayloadFingerprint = :fingerprint,
        remoteUpdatedAt = COALESCE(:remoteUpdatedAt, remoteUpdatedAt),
        localChangedFields = CASE WHEN localChangeRevision = :rev THEN NULL ELSE localChangedFields END
        WHERE productId = :productId AND lastSyncedLocalRevision <= :rev
        """
    )
    suspend fun updateRemoteApplyState(
        productId: Long,
        rev: Int,
        appliedAt: Long,
        fingerprint: String,
        remoteUpdatedAt: String?
    )

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM product_remote_refs
            WHERE (lastRemoteAppliedAt IS NULL OR localChangeRevision > lastSyncedLocalRevision)
        )
        """
    )
    suspend fun hasPendingWork(): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM product_remote_refs
            WHERE lastRemoteAppliedAt IS NULL
        )
        """
    )
    suspend fun hasNeverAppliedRemoteRef(): Boolean

    @Query("SELECT COUNT(*) FROM product_remote_refs")
    suspend fun countRows(): Int

    @Query("DELETE FROM product_remote_refs")
    suspend fun deleteAll()

    @Query(
        """
        SELECT COUNT(*)
        FROM products p
        LEFT JOIN product_remote_refs r ON r.productId = p.id
        WHERE r.id IS NULL
        """
    )
    suspend fun countLocalRowsMissingRemoteRef(): Int

    /** TASK-114: refs senza modifiche locali pendenti, idonee a prune post-pull. */
    @Query(
        """
        SELECT * FROM product_remote_refs
        WHERE lastRemoteAppliedAt IS NOT NULL
          AND localChangeRevision <= lastSyncedLocalRevision
        """
    )
    suspend fun getCleanRefs(): List<ProductRemoteRef>
}

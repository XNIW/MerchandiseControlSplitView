// com.example.merchandisecontrolsplitview.data.SupplierDao.kt
package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(supplier: Supplier): Long

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    suspend fun getAll(): List<Supplier>

    @Query(
        """
        SELECT s.id AS id,
               s.name AS name,
               r.id AS ref_id,
               r.supplierId AS ref_supplierId,
               r.remoteId AS ref_remoteId,
               r.localChangeRevision AS ref_localChangeRevision,
               r.lastSyncedLocalRevision AS ref_lastSyncedLocalRevision,
               r.lastRemoteAppliedAt AS ref_lastRemoteAppliedAt,
               r.lastRemotePayloadFingerprint AS ref_lastRemotePayloadFingerprint
        FROM suppliers s
        LEFT JOIN supplier_remote_refs r ON r.supplierId = s.id
        WHERE r.id IS NULL
           OR r.lastRemoteAppliedAt IS NULL
           OR r.localChangeRevision > r.lastSyncedLocalRevision
        ORDER BY s.name ASC
        """
    )
    suspend fun getCatalogPushCandidates(): List<SupplierCatalogPushCandidate>

    @Query("SELECT * FROM suppliers WHERE name LIKE '%' || :query || '%'")
    suspend fun searchByName(query: String): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Supplier?

    @Query("SELECT * FROM suppliers WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByNameIgnoreCase(name: String): Supplier?

    /**
     * Task 041 (hardening): match tollerante a whitespace/case sul lato locale.
     * Usato dal pre-push realign catalogo per riallineare bridge su righe importate da
     * Excel con spazi accidentali: senza questo, remote=`"X "` e local=`"X "` (stesso
     * whitespace) non match­erebbero mai in realign pur collidendo sulla partial UNIQUE
     * `(owner_user_id, lower(name)) WHERE deleted_at IS NULL` → 23505/409.
     *
     * Il chiamante normalizza il parametro con `trim().lowercase()` (Kotlin, più
     * aggressivo su unicode rispetto a `TRIM`/`LOWER` SQLite).
     */
    @Query("SELECT * FROM suppliers WHERE LOWER(TRIM(name)) = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): Supplier?

    // --- QUESTA E' LA FUNZIONE MANCANTE ---
    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Supplier?

    @Query(
        """
        SELECT s.id AS id,
               s.name AS name,
               COUNT(p.id) AS productCount
        FROM suppliers s
        LEFT JOIN products p ON p.supplierId = s.id
        WHERE (:query IS NULL OR :query = '' OR s.name LIKE '%' || :query || '%')
        GROUP BY s.id, s.name
        ORDER BY s.name COLLATE NOCASE ASC
        """
    )
    suspend fun getCatalogItems(query: String?): List<CatalogListItem>

    @Query(
        """
        SELECT s.id AS id,
               s.name AS name,
               COUNT(p.id) AS productCount
        FROM suppliers s
        LEFT JOIN products p ON p.supplierId = s.id
        WHERE (:query IS NULL OR :query = '' OR s.name LIKE '%' || :query || '%')
        GROUP BY s.id, s.name
        ORDER BY s.name COLLATE NOCASE ASC
        """
    )
    fun getCatalogItemsFlow(query: String?): Flow<List<CatalogListItem>>

    @Query("SELECT * FROM suppliers WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchByNameFlow(query: String): Flow<List<Supplier>>

    @Query("UPDATE suppliers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("DELETE FROM suppliers WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM suppliers")
    suspend fun count(): Int

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllFlow(): Flow<List<Supplier>>
}

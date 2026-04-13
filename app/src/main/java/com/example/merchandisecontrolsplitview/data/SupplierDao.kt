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

    @Query("SELECT * FROM suppliers WHERE name LIKE '%' || :query || '%'")
    suspend fun searchByName(query: String): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Supplier?

    @Query("SELECT * FROM suppliers WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByNameIgnoreCase(name: String): Supplier?

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

    @Query("UPDATE suppliers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("DELETE FROM suppliers WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllFlow(): Flow<List<Supplier>>
}

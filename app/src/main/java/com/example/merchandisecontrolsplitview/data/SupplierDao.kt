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

    // --- QUESTA E' LA FUNZIONE MANCANTE ---
    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Supplier?

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllFlow(): Flow<List<Supplier>>

}
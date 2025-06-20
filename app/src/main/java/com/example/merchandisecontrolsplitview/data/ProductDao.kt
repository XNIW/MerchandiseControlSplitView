package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ProductDao {
    @Query("""
    SELECT * FROM products
    WHERE (:filter IS NULL OR 
           barcode LIKE '%' || :filter || '%' OR 
           productName LIKE '%' || :filter || '%' OR 
           supplier LIKE '%' || :filter || '%' OR
           itemNumber LIKE '%' || :filter || '%')
    ORDER BY id ASC
""")
    fun getAllPaged(filter: String?): PagingSource<Int, Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: Product): Long

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?
}
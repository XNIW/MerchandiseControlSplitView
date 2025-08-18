package com.example.merchandisecontrolsplitview.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductPriceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: ProductPrice): Long

    @Transaction
    suspend fun insertIfChanged(
        productId: Long, type: String, newPrice: Double, at: String, src: String?
    ) {
        val last = getLast(productId, type)
        val changed = last?.let { kotlin.math.abs((it.price - newPrice)) > 0.005 } ?: true // stesso epsilon della tua equalProducts
        if (changed) insert(ProductPrice(productId = productId, type = type, price = newPrice, effectiveAt = at, source = src))
    }

    @Query("""
    SELECT * FROM product_prices 
    WHERE productId = :productId AND type = :type
    ORDER BY effectiveAt DESC
    LIMIT 1
  """)
    suspend fun getLast(productId: Long, type: String): ProductPrice?

    @Query("""
    SELECT * FROM product_prices 
    WHERE productId = :productId AND type = :type
    ORDER BY effectiveAt DESC
  """)
    fun getSeries(productId: Long, type: String): Flow<List<ProductPrice>>

    // Batch: precedente a una certa data (per "old price" contestuale a un import)
    @Query("""
    SELECT pr.*
    FROM product_prices pr
    WHERE pr.productId = :productId AND pr.type = :type 
      AND pr.effectiveAt < :before
    ORDER BY pr.effectiveAt DESC
    LIMIT 1
  """)
    suspend fun getLastBefore(productId: Long, type: String, before: String): ProductPrice?

    @Query("SELECT DISTINCT productId FROM product_prices")
    suspend fun getProductIdsWithAnyPrice(): List<Long>
}
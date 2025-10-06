package com.example.merchandisecontrolsplitview.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

data class PriceHistoryExportRowDb(
    val barcode: String,
    val effectiveAt: String,
    val type: String,
    val price: Double,
    val source: String?
)

data class LatestPriceRow(
    val productId: Long,
    val type: String,            // "PURCHASE" | "RETAIL"
    val effectiveAt: String,
    val price: Double
)

@Dao
interface ProductPriceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: ProductPrice): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<ProductPrice>)

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

    @Query("""
        SELECT price FROM product_prices
        WHERE productId = :productId AND type = :type
        ORDER BY effectiveAt DESC, id DESC
        LIMIT 1
    """)
    suspend fun getLastPrice(productId: Long, type: String): Double?

    @Query("SELECT DISTINCT productId FROM product_prices")
    suspend fun getProductIdsWithAnyPrice(): List<Long>

    @Query("""
        SELECT pr.productId   AS productId,
               pr.type        AS type,
               pr.effectiveAt AS effectiveAt,
               pr.price       AS price
        FROM product_prices pr
        JOIN (
            SELECT productId, type, MAX(effectiveAt) AS maxEff
            FROM product_prices
            WHERE type IN (:types)
            GROUP BY productId, type
        ) x ON x.productId = pr.productId AND x.type = pr.type AND x.maxEff = pr.effectiveAt
    """)
    suspend fun getLatestPerProductAndType(types: List<String>): List<LatestPriceRow>

    @Query("""
SELECT pr.productId, pr.type, pr.effectiveAt, pr.price
FROM product_prices pr
JOIN (
  SELECT productId, type, MAX(effectiveAt) AS maxEff
  FROM product_prices
  WHERE productId IN (:productIds)
  GROUP BY productId, type
) x ON x.productId = pr.productId AND x.type = pr.type AND x.maxEff = pr.effectiveAt
""")
    suspend fun getLatestForProducts(productIds: List<Long>): List<LatestPriceRow>

    @Query("""
        SELECT p.barcode      AS barcode,
               pr.effectiveAt AS effectiveAt,
               pr.type        AS type,
               pr.price       AS price,
               pr.source      AS source
        FROM product_prices pr
        JOIN products p ON p.id = pr.productId
        ORDER BY p.barcode ASC, pr.type ASC, pr.effectiveAt ASC
    """)
    suspend fun getAllWithBarcode(): List<PriceHistoryExportRowDb>

    @Transaction
    suspend fun insertIfChanged(
        productId: Long,
        type: String,             // "PURCHASE" | "RETAIL"
        price: Double,
        effectiveAt: String,      // "yyyy-MM-dd HH:mm:ss"
        source: String? = null
    ) {
        val last = getLastPrice(productId, type)
        if (last == null || abs(last - price) > 0.0005) {
            insert(
                ProductPrice(
                    productId = productId,
                    type = type,
                    price = price,
                    effectiveAt = effectiveAt,
                    source = source
                )
            )
        }
    }

    // Convenience wrapper (nessuna @Query qui, chiama quella sopra)
    suspend fun getLatestPerProductAndType(): List<LatestPriceRow> =
        getLatestPerProductAndType(listOf("PURCHASE", "RETAIL"))
}
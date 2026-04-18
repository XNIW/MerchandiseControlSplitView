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

/**
 * Una riga `product_prices` con `product_remote_refs.remoteId` e opzionale bridge prezzo già noto.
 * Usata per push bulk senza N+1 (task 016).
 */
data class ProductPricePushRow(
    val id: Long,
    val productId: Long,
    val type: String,
    val price: Double,
    val effectiveAt: String,
    val source: String?,
    val note: String?,
    val createdAt: String,
    val productRemoteId: String,
    val existingPriceRemoteId: String?
) {
    fun toProductPrice(): ProductPrice = ProductPrice(
        id = id,
        productId = productId,
        type = type,
        price = price,
        effectiveAt = effectiveAt,
        source = source,
        note = note,
        createdAt = createdAt
    )
}

@Dao
interface ProductPriceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: ProductPrice): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<ProductPrice>)

    /**
     * Candidati push: solo righe con `product_remote_refs` gia' noto (INNER JOIN) e **senza** bridge prezzo.
     * Lo storico prezzi e' immutabile per design (`insertIfChanged` crea righe nuove, non aggiorna), quindi
     * l'esistenza del bridge e' evidenza robusta di "gia' pushed" -> evita re-upsert rumorosi ad ogni sync
     * e allinea naturalmente il candidato push a [countPriceRowsPendingPriceBridge].
     */
    @Query(
        """
        SELECT pr.id AS id, pr.productId AS productId, pr.type AS type, pr.price AS price,
               pr.effectiveAt AS effectiveAt, pr.source AS source, pr.note AS note, pr.createdAt AS createdAt,
               pref.remoteId AS productRemoteId, NULL AS existingPriceRemoteId
        FROM product_prices pr
        INNER JOIN product_remote_refs pref ON pref.productId = pr.productId
        LEFT JOIN product_price_remote_refs pprf ON pprf.productPriceId = pr.id
        WHERE pprf.id IS NULL
        """
    )
    suspend fun getAllForCloudPush(): List<ProductPricePushRow>

    @Query(
        """
        SELECT COUNT(*) FROM product_prices pr
        LEFT JOIN product_remote_refs pref ON pref.productId = pr.productId
        WHERE pref.productId IS NULL
        """
    )
    suspend fun countPriceRowsWithoutProductRemote(): Int

    @Query(
        """
        SELECT COUNT(*) FROM product_prices pr
        INNER JOIN product_remote_refs pref ON pref.productId = pr.productId
        LEFT JOIN product_price_remote_refs pprf ON pprf.productPriceId = pr.id
        WHERE pprf.id IS NULL
        """
    )
    suspend fun countPriceRowsPendingPriceBridge(): Int

    @Query(
        """
        SELECT * FROM product_prices
        WHERE productId = :productId AND type = :type AND effectiveAt = :effectiveAt
        LIMIT 1
        """
    )
    suspend fun findByBusinessKey(productId: Long, type: String, effectiveAt: String): ProductPrice?

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

    /** Stesso ordinamento di [getAllWithBarcode] per export chunked. */
    @Query("""
        SELECT p.barcode      AS barcode,
               pr.effectiveAt AS effectiveAt,
               pr.type        AS type,
               pr.price       AS price,
               pr.source      AS source
        FROM product_prices pr
        JOIN products p ON p.id = pr.productId
        ORDER BY p.barcode ASC, pr.type ASC, pr.effectiveAt ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAllWithBarcodePage(limit: Int, offset: Int): List<PriceHistoryExportRowDb>

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
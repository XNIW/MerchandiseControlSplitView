package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProductPriceRemoteRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: ProductPriceRemoteRef): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(refs: List<ProductPriceRemoteRef>): List<Long>

    @Query("SELECT * FROM product_price_remote_refs WHERE productPriceId = :productPriceId LIMIT 1")
    suspend fun getByProductPriceId(productPriceId: Long): ProductPriceRemoteRef?

    @Query("SELECT * FROM product_price_remote_refs WHERE productPriceId IN (:productPriceIds)")
    suspend fun getByProductPriceIds(productPriceIds: List<Long>): List<ProductPriceRemoteRef>

    @Query("SELECT * FROM product_price_remote_refs WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): ProductPriceRemoteRef?

    @Query("SELECT * FROM product_price_remote_refs WHERE remoteId IN (:remoteIds)")
    suspend fun getByRemoteIds(remoteIds: List<String>): List<ProductPriceRemoteRef>
}

package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bridge leggero task 016 / DEC-021: riga `product_prices` ↔ uuid riga remota `inventory_product_prices`.
 * Popolamento lazy in repository; nessun backfill SQL nella migration Room.
 */
@Entity(
    tableName = "product_price_remote_refs",
    foreignKeys = [
        ForeignKey(
            entity = ProductPrice::class,
            parentColumns = ["id"],
            childColumns = ["productPriceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productPriceId"], unique = true),
        Index(value = ["remoteId"], unique = true)
    ]
)
data class ProductPriceRemoteRef(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val productPriceId: Long,
    val remoteId: String
)

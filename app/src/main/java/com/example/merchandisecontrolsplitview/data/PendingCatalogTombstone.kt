package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PendingCatalogTombstoneEntityTypes {
    const val SUPPLIER = "SUPPLIER"
    const val CATEGORY = "CATEGORY"
    const val PRODUCT = "PRODUCT"
}

@Entity(
    tableName = "pending_catalog_tombstones",
    indices = [
        Index(value = ["entityType", "remoteId"], unique = true)
    ]
)
data class PendingCatalogTombstone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val remoteId: String,
    val enqueuedAtMs: Long,
    val attemptCount: Int = 0
)

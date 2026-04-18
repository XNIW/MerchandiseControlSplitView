package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_remote_refs",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"], unique = true),
        Index(value = ["remoteId"], unique = true)
    ]
)
data class ProductRemoteRef(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productId: Long,
    val remoteId: String,
    val localChangeRevision: Int = 0,
    val lastSyncedLocalRevision: Int = 0,
    val lastRemoteAppliedAt: Long? = null,
    val lastRemotePayloadFingerprint: String? = null
)

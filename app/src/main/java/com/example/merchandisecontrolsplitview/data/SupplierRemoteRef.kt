package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "supplier_remote_refs",
    foreignKeys = [
        ForeignKey(
            entity = Supplier::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["supplierId"], unique = true),
        Index(value = ["remoteId"], unique = true)
    ]
)
data class SupplierRemoteRef(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val supplierId: Long,
    val remoteId: String,
    val localChangeRevision: Int = 0,
    val lastSyncedLocalRevision: Int = 0,
    val lastRemoteAppliedAt: Long? = null,
    val lastRemotePayloadFingerprint: String? = null
)

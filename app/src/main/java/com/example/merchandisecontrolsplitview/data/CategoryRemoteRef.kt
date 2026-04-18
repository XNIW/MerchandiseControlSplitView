package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category_remote_refs",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["categoryId"], unique = true),
        Index(value = ["remoteId"], unique = true)
    ]
)
data class CategoryRemoteRef(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val categoryId: Long,
    val remoteId: String,
    val localChangeRevision: Int = 0,
    val lastSyncedLocalRevision: Int = 0,
    val lastRemoteAppliedAt: Long? = null,
    val lastRemotePayloadFingerprint: String? = null
)

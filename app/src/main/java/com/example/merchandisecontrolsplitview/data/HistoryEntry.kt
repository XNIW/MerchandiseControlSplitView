package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_entries")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val uid: Long = 0,

    val id: String, // Il vecchio ID (nome file)
    val displayName: String = "",
    val timestamp: String,
    val data: List<List<String>>,
    val editable: List<List<String>>,
    val complete: List<Boolean>,
    val supplier: String = "",
    val category: String = "",
    val wasExported: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NOT_ATTEMPTED,
    val orderTotal: Double = 0.0,
    val paymentTotal: Double = 0.0,
    val missingItems: Int = 0,
    val totalItems: Int = 0,
    val isManualEntry: Boolean = false,
    val deletedAt: String? = null
)

data class HistoryEntryListItem(
    val uid: Long,
    val id: String,
    val displayName: String = "",
    val timestamp: String,
    val supplier: String = "",
    val category: String = "",
    val wasExported: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NOT_ATTEMPTED,
    val orderTotal: Double = 0.0,
    val paymentTotal: Double = 0.0,
    val missingItems: Int = 0,
    val totalItems: Int = 0,
    val isManualEntry: Boolean = false,
    val deletedAt: String? = null
)

data class HistoryDisplayEntry(
    val listItem: HistoryEntryListItem,
    val totalQuantity: Double? = null
) {
    val uid: Long get() = listItem.uid
    val id: String get() = listItem.id
    val displayName: String get() = listItem.displayName
    val timestamp: String get() = listItem.timestamp
    val supplier: String get() = listItem.supplier
    val category: String get() = listItem.category
    val wasExported: Boolean get() = listItem.wasExported
    val syncStatus: SyncStatus get() = listItem.syncStatus
    val orderTotal: Double get() = listItem.orderTotal
    val paymentTotal: Double get() = listItem.paymentTotal
    val missingItems: Int get() = listItem.missingItems
    val totalItems: Int get() = listItem.totalItems
    val isManualEntry: Boolean get() = listItem.isManualEntry
    val deletedAt: String? get() = listItem.deletedAt
}

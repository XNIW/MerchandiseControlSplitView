package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_entries")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val uid: Long = 0,

    val id: String, // Il vecchio ID (nome file)
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
    val isManualEntry: Boolean = false
)

data class HistoryEntryListItem(
    val uid: Long,
    val id: String,
    val timestamp: String,
    val supplier: String = "",
    val category: String = "",
    val wasExported: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NOT_ATTEMPTED,
    val orderTotal: Double = 0.0,
    val paymentTotal: Double = 0.0,
    val missingItems: Int = 0,
    val totalItems: Int = 0,
    val isManualEntry: Boolean = false
)

package com.example.merchandisecontrolsplitview.data
data class HistoryEntry(
    val id: String,
    val timestamp: String,
    val data: List<List<String>>,
    val editable: List<List<String>>,
    val complete: List<Boolean>,
    val supplier: String = "",
    val wasExported: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NOT_ATTEMPTED // <-- Assicurati che sia così
)
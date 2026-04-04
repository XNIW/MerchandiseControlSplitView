package com.example.merchandisecontrolsplitview.data

data class ImportPriceHistoryEntry(
    val barcode: String,
    val type: String,
    val timestamp: String,
    val price: Double,
    val source: String?
)

data class ImportApplyRequest(
    val newProducts: List<Product>,
    val updatedProducts: List<ProductUpdate>,
    val pendingSupplierNames: Set<String> = emptySet(),
    val pendingCategoryNames: Set<String> = emptySet(),
    val pendingTempSuppliers: Map<Long, String> = emptyMap(),
    val pendingTempCategories: Map<Long, String> = emptyMap(),
    val pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList()
)

sealed interface ImportApplyResult {
    data object Success : ImportApplyResult
    data object AlreadyRunning : ImportApplyResult
    data class Failure(val cause: Throwable) : ImportApplyResult
}

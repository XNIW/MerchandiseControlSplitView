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
    val pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList(),
    val diagnostics: ImportApplyDiagnostics? = null
)

sealed interface ImportApplyResult {
    data object Success : ImportApplyResult
    data object AlreadyRunning : ImportApplyResult
    data class Failure(val cause: Throwable) : ImportApplyResult
}

data class ImportApplyDiagnostics(
    val fileProductCount: Int,
    val fileSupplierCount: Int,
    val fileCategoryCount: Int,
    val filePriceHistoryCount: Int,
    val dbProductCountBefore: Int,
    val dbSupplierCountBefore: Int,
    val dbCategoryCountBefore: Int,
    val dbPriceHistoryCountBefore: Int,
    val importFingerprintShort: String,
    val dbSnapshotFingerprintShort: String
) {
    val resultClassification: String
        get() = if (
            fileProductCount == dbProductCountBefore &&
            fileSupplierCount == dbSupplierCountBefore &&
            fileCategoryCount == dbCategoryCountBefore &&
            filePriceHistoryCount == dbPriceHistoryCountBefore &&
            importFingerprintShort == dbSnapshotFingerprintShort
        ) {
            "no_op_candidate_same_dataset"
        } else {
            "delta_reale_dataset_diverso"
        }
}

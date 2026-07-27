// com.example.merchandisecontrolsplitview.data.ImportAnalysis.kt
package com.example.merchandisecontrolsplitview.data

import com.example.merchandisecontrolsplitview.util.CatalogTextField

// Assicurati che Product, ProductUpdate e RowImportError siano anch'essi nel package 'data'
// e correttamente importati qui se necessario, anche se di solito non serve se sono nello stesso package.
data class DuplicateWarning(
    val barcode: String,
    val rowNumbers: List<Int>,
    /** Totale righe con questo barcode; se maggiore di [rowNumbers], la lista è campionata per limitare memoria/UI. */
    val totalOccurrences: Int = rowNumbers.size
)

data class CatalogTextNormalizationWarning(
    val rowNumber: Int,
    val fields: Set<CatalogTextField>,
    val source: ImportRowSource = ImportRowSource.PRODUCTS
)

data class ImportAnalysis(
    val newProducts: List<Product>,
    val updatedProducts: List<ProductUpdate>,
    val errors: List<RowImportError>,
    val warnings: List<DuplicateWarning>,
    val textNormalizationWarnings: List<CatalogTextNormalizationWarning> = emptyList(),
    val totalErrorCount: Int = errors.size,
    val totalTextNormalizationRowCount: Int =
        textNormalizationWarnings.map { it.source to it.rowNumber }.distinct().size,
    val totalTextNormalizationFieldCount: Int =
        textNormalizationWarnings.sumOf { it.fields.size }
) {
    val hasValidRowsToApply: Boolean
        get() = newProducts.isNotEmpty() || updatedProducts.isNotEmpty()

    val normalizedRowCount: Int
        get() = totalTextNormalizationRowCount

    val normalizedFieldCount: Int
        get() = totalTextNormalizationFieldCount
}

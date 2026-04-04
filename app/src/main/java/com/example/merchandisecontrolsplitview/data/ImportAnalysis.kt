// com.example.merchandisecontrolsplitview.data.ImportAnalysis.kt
package com.example.merchandisecontrolsplitview.data

// Assicurati che Product, ProductUpdate e RowImportError siano anch'essi nel package 'data'
// e correttamente importati qui se necessario, anche se di solito non serve se sono nello stesso package.
data class DuplicateWarning(
    val barcode: String,
    val rowNumbers: List<Int>,
    /** Totale righe con questo barcode; se maggiore di [rowNumbers], la lista è campionata per limitare memoria/UI. */
    val totalOccurrences: Int = rowNumbers.size
)
data class ImportAnalysis(
    val newProducts: List<Product>,
    val updatedProducts: List<ProductUpdate>,
    val errors: List<RowImportError>,
    val warnings: List<DuplicateWarning>
)
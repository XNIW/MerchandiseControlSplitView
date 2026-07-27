package com.example.merchandisecontrolsplitview.data

enum class ImportRowSource {
    PRODUCTS,
    SUPPLIERS,
    CATEGORIES,
    PRICE_HISTORY
}

data class RowImportError(
    val rowNumber: Int,
    val rowContent: Map<String, String>,
    val errorReasonResId: Int,
    val formatArgs: List<Any> = emptyList(),
    /**
     * Keys whose source value was rejected and deliberately not retained in [rowContent].
     * UI and exports must resolve values through [valueForPresentation].
     */
    val redactedFields: Set<String> = emptySet(),
    val source: ImportRowSource = ImportRowSource.PRODUCTS
) {
    fun valueForPresentation(field: String, redactedMarker: String): String =
        if (field in redactedFields) redactedMarker else rowContent[field].orEmpty()
}

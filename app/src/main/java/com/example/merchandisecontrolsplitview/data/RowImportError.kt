package com.example.merchandisecontrolsplitview.data

data class RowImportError(
    val rowNumber: Int,
    val rowContent: Map<String, String>, // Modificato da List<String> a Map<String, String>
    val errorReason: String
)
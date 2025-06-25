package com.example.merchandisecontrolsplitview.data

data class RowImportError(
    val rowNumber: Int,
    val rowContent: List<String>,
    val errorReason: String
)
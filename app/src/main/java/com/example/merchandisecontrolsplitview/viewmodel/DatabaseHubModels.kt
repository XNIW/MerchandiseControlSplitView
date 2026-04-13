package com.example.merchandisecontrolsplitview.viewmodel

import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.CatalogListItem

enum class DatabaseHubTab(
    val catalogKind: CatalogEntityKind?
) {
    PRODUCTS(null),
    SUPPLIERS(CatalogEntityKind.SUPPLIER),
    CATEGORIES(CatalogEntityKind.CATEGORY)
}

data class CatalogSectionUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val items: List<CatalogListItem> = emptyList(),
    val errorMessage: String? = null
)

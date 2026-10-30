package com.example.merchandisecontrolsplitview.data

/** An edited field changed concurrently, or the edited row no longer exists. No write was applied. */
class ProductEditConflictException(
    val currentProduct: Product? = null,
    val missingTarget: Boolean = false,
    val conflictingFields: Set<String> = emptySet()
) : IllegalStateException("Product changed while the editor was open")

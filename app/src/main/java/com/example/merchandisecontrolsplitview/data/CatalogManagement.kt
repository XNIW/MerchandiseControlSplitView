package com.example.merchandisecontrolsplitview.data

enum class CatalogEntityKind {
    SUPPLIER,
    CATEGORY
}

data class CatalogListItem(
    val id: Long,
    val name: String,
    val productCount: Int
)

sealed interface CatalogDeleteStrategy {
    data object DeleteIfUnused : CatalogDeleteStrategy
    data class ReplaceWithExisting(val replacementId: Long) : CatalogDeleteStrategy
    data class CreateNewAndReplace(val replacementName: String) : CatalogDeleteStrategy
    data object ClearAssignments : CatalogDeleteStrategy
}

data class CatalogDeleteResult(
    val affectedProducts: Int,
    val strategy: CatalogDeleteStrategy,
    val replacementName: String? = null
)

sealed class CatalogMutationException : IllegalStateException()

data object CatalogBlankNameException : CatalogMutationException()

data class CatalogNameConflictException(
    val conflictingName: String
) : CatalogMutationException()

data class CatalogNotFoundException(
    val kind: CatalogEntityKind,
    val entityId: Long
) : CatalogMutationException()

data class CatalogEntityInUseException(
    val productCount: Int
) : CatalogMutationException()

data object CatalogInvalidReplacementException : CatalogMutationException()

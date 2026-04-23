package com.example.merchandisecontrolsplitview.data

import androidx.room.Embedded

data class SupplierCatalogPushCandidate(
    @Embedded val supplier: Supplier,
    @Embedded(prefix = "ref_") val remoteRef: SupplierRemoteRef?
)

data class CategoryCatalogPushCandidate(
    @Embedded val category: Category,
    @Embedded(prefix = "ref_") val remoteRef: CategoryRemoteRef?
)

data class ProductCatalogPushCandidate(
    @Embedded val product: Product,
    @Embedded(prefix = "ref_") val remoteRef: ProductRemoteRef?,
    val lastPurchase: Double?,
    val lastRetail: Double?
)

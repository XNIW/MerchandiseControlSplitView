package com.example.merchandisecontrolsplitview.ui.screens

import com.example.merchandisecontrolsplitview.data.Product
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogConflictReviewSelectionTest {
    @Test fun selectedLocalNameKeepsDisjointRemoteFieldsAndProjectionIdentity() {
        val remote = Product(id = 144L, barcode = "R4-REMOTE", productName = "Remote name",
            itemNumber = "Peer item", purchasePrice = 10.0, retailPrice = 20.0,
            supplierId = 10L, categoryId = 20L, stockQuantity = 4.0,
            primaryImageVersionId = "remote-image")
        val staleLocal = remote.copy(id = 999L, barcode = "OLD", productName = "Chosen local name",
            itemNumber = "Stale item", retailPrice = 99.0, supplierId = 99L,
            stockQuantity = 99.0, primaryImageVersionId = "stale-image")
        val selected = selectCatalogReviewFields(remote, staleLocal, setOf("productname"))
        assertEquals(remote.copy(productName = "Chosen local name"), selected)
    }
}

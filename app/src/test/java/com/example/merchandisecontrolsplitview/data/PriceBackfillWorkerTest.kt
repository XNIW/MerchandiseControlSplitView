package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceBackfillWorkerTest {

    @Test
    fun `plan batches both current prices for an eligible local product`() {
        val plan = planPriceBackfill(
            products = listOf(
                Product(
                    id = 10L,
                    barcode = "local",
                    productName = "Local",
                    purchasePrice = 4.5,
                    retailPrice = 7.5
                )
            ),
            productIdsWithHistory = emptySet(),
            cloudLinkedProductIds = emptySet(),
            effectiveAt = "2026-07-13 12:00:00"
        )

        assertEquals(1, plan.eligibleProductCount)
        assertEquals(listOf("PURCHASE", "RETAIL"), plan.points.map { it.type })
        assertTrue(plan.points.all { it.source == "BACKFILL_CURR" })
    }

    @Test
    fun `plan excludes products with history or a cloud bridge without per-product lookups`() {
        val products = listOf(
            Product(id = 10L, barcode = "history", productName = "History", purchasePrice = 1.0),
            Product(id = 11L, barcode = "cloud", productName = "Cloud", retailPrice = 2.0),
            Product(id = 12L, barcode = "eligible", productName = "Eligible", retailPrice = 3.0)
        )

        val plan = planPriceBackfill(
            products = products,
            productIdsWithHistory = setOf(10L),
            cloudLinkedProductIds = setOf(11L),
            effectiveAt = "2026-07-13 12:00:00"
        )

        assertEquals(1, plan.eligibleProductCount)
        assertEquals(listOf(12L), plan.points.map { it.productId })
    }

    @Test
    fun `plan counts an eligible product even when it has no current prices`() {
        val plan = planPriceBackfill(
            products = listOf(Product(id = 10L, barcode = "empty", productName = "Empty")),
            productIdsWithHistory = emptySet(),
            cloudLinkedProductIds = emptySet(),
            effectiveAt = "2026-07-13 12:00:00"
        )

        assertEquals(1, plan.eligibleProductCount)
        assertTrue(plan.points.isEmpty())
    }
}

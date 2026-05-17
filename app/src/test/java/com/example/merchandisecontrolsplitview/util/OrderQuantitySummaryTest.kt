package com.example.merchandisecontrolsplitview.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderQuantitySummaryTest {

    @Test
    fun `calculateTotalQuantityFromRows sums integer quantities`() {
        val rows = listOf(
            listOf("barcode", "productName", "quantity"),
            listOf("10000001", "A", "2"),
            listOf("10000002", "B", "3")
        )

        assertEquals(5.0, calculateTotalQuantityFromRows(rows)!!, 0.0001)
    }

    @Test
    fun `calculateTotalQuantityFromRows sums decimal quantities`() {
        val rows = listOf(
            listOf("barcode", "productName", "quantity"),
            listOf("10000001", "A", "1,5"),
            listOf("10000002", "B", "2.5")
        )

        assertEquals(4.0, calculateTotalQuantityFromRows(rows)!!, 0.0001)
    }

    @Test
    fun `calculateTotalQuantityFromRows ignores non parsable quantities`() {
        val rows = listOf(
            listOf("barcode", "productName", "quantity"),
            listOf("10000001", "A", "bad"),
            listOf("10000002", "B", "4")
        )

        assertEquals(4.0, calculateTotalQuantityFromRows(rows)!!, 0.0001)
    }

    @Test
    fun `calculateTotalQuantityFromRows returns null when quantity header is missing`() {
        val rows = listOf(
            listOf("barcode", "productName"),
            listOf("10000001", "A")
        )

        assertNull(calculateTotalQuantityFromRows(rows))
    }

    @Test
    fun `calculateTotalQuantityFromRows returns null when no quantity value is parsable`() {
        val rows = listOf(
            listOf("barcode", "productName", "quantity"),
            listOf("10000001", "A", "bad"),
            listOf("10000002", "B", "")
        )

        assertNull(calculateTotalQuantityFromRows(rows))
    }

    @Test
    fun `calculateTotalQuantityFromRows ignores barcode and item number values`() {
        val rows = listOf(
            listOf("barcode", "itemNumber", "productName", "quantity"),
            listOf("6988888075607", "075607", "A", ""),
            listOf("6988235529791", "529791", "B", "bad")
        )

        assertNull(calculateTotalQuantityFromRows(rows))
    }
}

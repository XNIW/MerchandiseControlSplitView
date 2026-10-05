package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProductBaselineFingerprintTest {
    private val row = InventoryProductRow("00000000-0000-4000-8000-000000001443", "owner",
        barcode="fingerprint-fixture", productName="First", secondProductName="Second")

    @Test fun `143 distinct delimited fields cannot share the acknowledged base identity`() {
        val first = row.copy(productName="a|b", secondProductName="c")
        val second = row.copy(productName="a", secondProductName="b|c")
        assertEquals(fingerprintProductInboundLegacy(first), fingerprintProductInboundLegacy(second))
        assertNotEquals(fingerprintProductInbound(first), fingerprintProductInbound(second))
    }

    @Test fun `143 nullable text and literal null remain different acknowledged bases`() {
        val absent = row.copy(itemNumber=null)
        val literal = row.copy(itemNumber="null")
        assertEquals(fingerprintProductInboundLegacy(absent), fingerprintProductInboundLegacy(literal))
        assertNotEquals(fingerprintProductInbound(absent), fingerprintProductInbound(literal))
    }
}

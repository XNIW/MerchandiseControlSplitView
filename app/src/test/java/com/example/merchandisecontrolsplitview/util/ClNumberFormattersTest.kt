package com.example.merchandisecontrolsplitview.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClNumberFormattersTest {

    @Test
    fun `formatClPricePlainDisplay uses fixed CL grouping without currency symbol`() {
        assertEquals("47.100", formatClPricePlainDisplay(47100.0))
    }

    @Test
    fun `formatClPriceInput keeps legacy prefill behavior without grouping`() {
        assertEquals("", formatClPriceInput(null))
        assertEquals("4", formatClPriceInput(3.6))
        assertEquals("0", formatClPriceInput(0.0))
    }

    @Test
    fun `formatClSummaryMoney adds peso prefix`() {
        assertEquals("\$ 125.000", formatClSummaryMoney(125000.0))
    }

    @Test
    fun `formatClQuantityDisplayReadOnly keeps up to three decimals without trailing zeros`() {
        assertEquals("1.234,5", formatClQuantityDisplayReadOnly(1234.5))
        assertEquals("1.234,568", formatClQuantityDisplayReadOnly(1234.5678))
        assertEquals("1.000", formatClQuantityDisplayReadOnly(1000.0))
    }

    @Test
    fun `formatClPercentDisplay keeps up to two decimals without grouping`() {
        assertEquals("10,5%", formatClPercentDisplay(10.50))
    }

    @Test
    fun `parseUserNumericInput accepts comma and dot decimal input`() {
        assertEquals(1234.5, parseUserNumericInput("1234,5")!!, 0.0001)
        assertEquals(1234.5, parseUserNumericInput("1234.5")!!, 0.0001)
        assertEquals(1.234, parseUserNumericInput("1.234")!!, 0.0001)
    }

    @Test
    fun `parseUserPriceInput accepts pasted grouped prices without breaking decimal input`() {
        assertEquals(47100.0, parseUserPriceInput("47.100")!!, 0.0001)
        assertEquals(47100.0, parseUserPriceInput("47,100")!!, 0.0001)
        assertEquals(1234.567, parseUserPriceInput("1234.567")!!, 0.0001)
    }

    @Test
    fun `parseUserQuantityInput accepts pasted grouped integers from CL display`() {
        assertEquals(1234.0, parseUserQuantityInput("1.234")!!, 0.0001)
        assertEquals(1234.0, parseUserQuantityInput("1,234")!!, 0.0001)
    }

    @Test
    fun `normalizeClPriceInput removes grouping and decimals from price text fields`() {
        assertEquals("47100", normalizeClPriceInput("47.100"))
        assertEquals("1250", normalizeClPriceInput("1.250,4"))
    }

    @Test
    fun `normalizeClQuantityInput keeps decimal editing without grouping and collapses pasted grouped integers`() {
        assertEquals("1234,5", normalizeClQuantityInput("1234.5"))
        assertEquals("1234", normalizeClQuantityInput("1.234"))
    }

    @Test
    fun `formatGridNumericDisplay applies whitelist and preserves identity columns`() {
        assertEquals("47.100", formatGridNumericDisplay("47100", "purchasePrice"))
        assertEquals("1.234,5", formatGridNumericDisplay("1234.5", "quantity"))
        assertEquals("10,5%", formatGridNumericDisplay("10.5", "discount"))
        assertEquals("7800123456789", formatGridNumericDisplay("7800123456789", "barcode"))
        assertEquals("42", formatGridNumericDisplay("42", "rowNumber"))
    }

    @Test
    fun `parseUserNumericInput returns null for blank input`() {
        assertNull(parseUserNumericInput("   "))
    }
}

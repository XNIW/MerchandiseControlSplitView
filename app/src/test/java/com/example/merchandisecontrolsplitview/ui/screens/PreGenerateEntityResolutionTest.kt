package com.example.merchandisecontrolsplitview.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreGenerateEntityResolutionTest {
    @Test
    fun `blank input is not valid for generation`() {
        val resolution = resolvePreGenerateEntityResolution(
            inputText = "   ",
            selectedName = null,
            existingNames = listOf("Prova Fornitore")
        )

        assertEquals(PreGenerateEntityResolutionKind.EMPTY, resolution.kind)
        assertEquals(null, resolution.displayName)
        assertTrue(!resolution.isValid)
    }

    @Test
    fun `existing input is recognized case and whitespace insensitively`() {
        val resolution = resolvePreGenerateEntityResolution(
            inputText = " prova fornitore ",
            selectedName = null,
            existingNames = listOf("Prova Fornitore")
        )

        assertEquals(PreGenerateEntityResolutionKind.EXISTING, resolution.kind)
        assertEquals("Prova Fornitore", resolution.displayName)
        assertTrue(resolution.isValid)
    }

    @Test
    fun `new input becomes pending create and stays valid for generation`() {
        val resolution = resolvePreGenerateEntityResolution(
            inputText = " prova fornitore nuovo ",
            selectedName = null,
            existingNames = listOf("Prova Fornitore")
        )

        assertEquals(PreGenerateEntityResolutionKind.PENDING_CREATE, resolution.kind)
        assertEquals("prova fornitore nuovo", resolution.displayName)
        assertTrue(resolution.isValid)
        assertTrue(resolution.isPendingCreate)
    }
}

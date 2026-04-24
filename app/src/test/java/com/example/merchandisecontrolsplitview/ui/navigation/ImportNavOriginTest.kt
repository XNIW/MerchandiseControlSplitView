package com.example.merchandisecontrolsplitview.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportNavOriginTest {

    @Test
    fun parse_blank_falls_back_to_home() {
        assertEquals(ImportNavOrigin.HOME, ImportNavOrigin.parse(null))
        assertEquals(ImportNavOrigin.HOME, ImportNavOrigin.parse(""))
        assertEquals(ImportNavOrigin.HOME, ImportNavOrigin.parse("   "))
    }

    @Test
    fun parse_known_lowercase() {
        assertEquals(ImportNavOrigin.HISTORY, ImportNavOrigin.parse("history"))
        assertEquals(ImportNavOrigin.DATABASE, ImportNavOrigin.parse("database"))
        assertEquals(ImportNavOrigin.GENERATED, ImportNavOrigin.parse("generated"))
        assertEquals(ImportNavOrigin.HOME, ImportNavOrigin.parse("home"))
    }

    @Test
    fun parse_known_trimmed_and_mixed_case() {
        assertEquals(ImportNavOrigin.HISTORY, ImportNavOrigin.parse(" History "))
        assertEquals(ImportNavOrigin.DATABASE, ImportNavOrigin.parse("DATABASE"))
    }

    @Test
    fun parse_unknown_falls_back_to_home() {
        assertEquals(ImportNavOrigin.HOME, ImportNavOrigin.parse("not-a-real-origin"))
    }
}

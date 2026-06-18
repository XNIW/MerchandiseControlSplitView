package com.example.merchandisecontrolsplitview.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsScreenPublicUxTest {
    @Test
    fun `options status cards hide implementation-only pending and account rows`() {
        val source = optionsScreenSource().readText()

        assertFalse(source.contains("local_database_status_pending_changes"))
        assertFalse(source.contains("local_database_status_cloud_account"))
    }

    @Test
    fun `account and automatic sync are rendered in one options card`() {
        val source = optionsScreenSource().readText()

        assertTrue(source.contains("AccountCloudSyncSection("))
        assertTrue(source.contains("showHeader = authState !is AuthState.SignedIn"))
        assertFalse(source.contains("CatalogCloudSection(state = sync)"))
        assertFalse(source.contains("ConnectedAccountCard("))
    }

    @Test
    fun `account email is masked before rendering in options`() {
        assertEquals("x***@example.com", maskEmailForOptions("xuser@example.com"))
        assertEquals("m***@example.test", maskEmailForOptions(" min@example.test "))
        assertNull(maskEmailForOptions("not-an-email"))
    }

    @Test
    fun `history display title treats uuid-only displayName as blank`() {
        val title = formatHistorySessionDisplayTitle(
            displayName = "038aed8a-299c-489a-9c21-d1d70828a4ab",
            supplier = "",
            timestamp = "2026-05-12 20:12:10",
            contextFallback = "Supplier fallback",
            genericFallback = "History fallback"
        )

        assertEquals("History fallback", title)
    }

    private fun optionsScreenSource(): File {
        val candidates = listOf(
            File("app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt"),
            File("src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt")
        )
        return candidates.first { it.exists() }
    }
}

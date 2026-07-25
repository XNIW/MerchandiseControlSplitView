package com.example.merchandisecontrolsplitview.ui.navigation

import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessContentGateTest {
    private val signedIn = AuthState.SignedIn(
        userId = "00000000-0000-4000-8000-000000001399",
        email = "qa@example.test"
    )

    @Test
    fun `139 authenticated business content is visible only for a ready scope`() {
        Task126BusinessDataScopeStatus.entries.forEach { status ->
            val expected = status == Task126BusinessDataScopeStatus.READY ||
                status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
            assertTrue(
                "Unexpected projection for $status",
                businessContentAvailable(true, signedIn, status) == expected
            )
        }
    }

    @Test
    fun `139 checking auth and recoverable auth are fail closed`() {
        assertFalse(
            businessContentAvailable(
                true,
                AuthState.Checking,
                Task126BusinessDataScopeStatus.READY
            )
        )
        assertFalse(
            businessContentAvailable(
                true,
                AuthState.ErrorRecoverable("auth_refresh_failed"),
                Task126BusinessDataScopeStatus.READY
            )
        )
    }

    @Test
    fun `139 signed out and disabled cloud preserve local only use`() {
        assertTrue(
            businessContentAvailable(
                true,
                AuthState.SignedOut,
                Task126BusinessDataScopeStatus.CHECKING
            )
        )
        assertTrue(
            businessContentAvailable(
                false,
                AuthState.Checking,
                Task126BusinessDataScopeStatus.CHECKING
            )
        )
    }
}

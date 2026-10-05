package com.example.merchandisecontrolsplitview.ui.navigation

import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import com.example.merchandisecontrolsplitview.data.task126OwnerHash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessContentGateTest {
    private val signedIn = AuthState.SignedIn(
        userId = "00000000-0000-4000-8000-000000001399",
        email = "qa@example.test"
    )

    private val scope = Task126OwnerStoreScope(task126OwnerHash(signedIn.userId), "shop:fixture-a", null)

    @Test
    fun `139 authenticated business content is visible only for a ready scope`() {
        Task126BusinessDataScopeStatus.entries.forEach { status ->
            val expected = status == Task126BusinessDataScopeStatus.READY
            assertTrue(
                "Unexpected projection for $status",
                businessContentAvailable(true, signedIn, Task126BusinessDataScopeState(status, boundScope = scope)) == expected
            )
        }
    }

    @Test
    fun `139 checking auth and recoverable auth are fail closed`() {
        assertFalse(
            businessContentAvailable(
                true,
                AuthState.Checking,
                Task126BusinessDataScopeState.ready(scope)
            )
        )
        assertFalse(
            businessContentAvailable(
                true,
                AuthState.ErrorRecoverable("auth_refresh_failed"),
                Task126BusinessDataScopeState.ready(scope)
            )
        )
    }

    @Test
    fun `139 signed out and disabled cloud preserve local only use`() {
        assertTrue(
            businessContentAvailable(
                true,
                AuthState.SignedOut,
                Task126BusinessDataScopeState.unmanagedAllowed()
            )
        )
        assertTrue(
            businessContentAvailable(
                false,
                AuthState.Checking,
                Task126BusinessDataScopeState.unmanagedAllowed()
            )
        )
    }
    @Test
    fun `143 managed data remains private after logout or shop change`() {
        val scope = com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope(
            com.example.merchandisecontrolsplitview.data.task126OwnerHash(signedIn.userId), "shop:fixture-a", null)
        val state = com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState.ready(scope)
        assertFalse(businessContentAvailable(true, AuthState.SignedOut, state))
        assertFalse(businessContentAvailable(false, AuthState.SignedOut, state))
        assertFalse(businessContentAvailable(true, signedIn, state,
            com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope(scope.ownerHash, "shop:fixture-b", null)))
        assertFalse(businessContentAvailable(true, signedIn, state, activeScope=null))
        assertTrue(businessContentAvailable(true, signedIn, state))
    }

}

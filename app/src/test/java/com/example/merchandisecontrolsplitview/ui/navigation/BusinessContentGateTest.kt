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

    @Test
    fun `checking admits only current local identity and the selected persisted scope`() {
        val selected = com.example.merchandisecontrolsplitview.data.SelectedShop("fixture-a", null, "A", "owner", "active", true)
        val shop = com.example.merchandisecontrolsplitview.data.ShopContext(signedIn.userId, emptyList(), selected,
            syncAllowed = false, localAccessAllowed = true)
        val state = Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.CHECKING,
            boundScope = scope, localAccessScope = scope)
        val identity = com.example.merchandisecontrolsplitview.data.LocalAuthIdentity(signedIn.userId, 4)
        fun admitted(candidate: com.example.merchandisecontrolsplitview.data.LocalAuthIdentity?, current: Boolean,
            selectedContext: com.example.merchandisecontrolsplitview.data.ShopContext = shop,
            currentState: Task126BusinessDataScopeState = state): Boolean {
            val owner = com.example.merchandisecontrolsplitview.viewmodel.qualifiedLocalBusinessOwner(
                AuthState.Checking, candidate, current, selectedContext, currentState)
            return businessContentAvailable(true, AuthState.Checking, currentState,
                activeScope = owner?.let { com.example.merchandisecontrolsplitview.data.task126ActiveOwnerStoreScope(it, selectedContext.selectedShop) },
                locallyQualifiedOwner = owner)
        }
        assertTrue(admitted(identity, true))
        assertFalse(state.allowsCloudSync)
        assertFalse(admitted(null, false))
        assertFalse(admitted(identity, false))
        assertFalse(admitted(identity.copy(userId = "foreign-owner"), true))
        assertFalse(admitted(identity, true, shop.copy(localAccessAllowed = false)))
        assertFalse(admitted(identity, true, shop.copy(selectedShop = selected.copy(shopId = "fixture-b"))))
        assertFalse(admitted(identity, true, currentState = state.copy(localAccessScope = null)))
    }

    @Test
    fun `same identity requalification preserves only a presentation key without granting reads`() {
        val selected = com.example.merchandisecontrolsplitview.data.SelectedShop("fixture-a", null, "A", "owner", "active", true)
        val shop = com.example.merchandisecontrolsplitview.data.ShopContext(signedIn.userId, emptyList(), selected,
            syncAllowed = false, localAccessAllowed = true)
        val identity = com.example.merchandisecontrolsplitview.data.LocalAuthIdentity(signedIn.userId, 7)
        val ready = Task126BusinessDataScopeState.ready(scope)
        val expected = com.example.merchandisecontrolsplitview.viewmodel.localBusinessPresentationKey(
            signedIn, identity, true, shop, ready)
        org.junit.Assert.assertNotNull(expected)
        for (status in listOf(Task126BusinessDataScopeStatus.CHECKING, Task126BusinessDataScopeStatus.ERROR_RECOVERABLE)) {
            val waiting = ready.copy(status = status, localReadsAllowed = false, localWritesAllowed = false)
            org.junit.Assert.assertEquals(expected,
                com.example.merchandisecontrolsplitview.viewmodel.localBusinessPresentationKey(
                    AuthState.Checking, identity, true, shop, waiting))
            assertFalse(waiting.allowsLocalOperations)
            assertFalse(businessContentAvailable(true, signedIn, waiting))
        }
    }

    @Test
    fun `presentation key is withdrawn on logout stale identity revocation mismatch and unbound state`() {
        val selected = com.example.merchandisecontrolsplitview.data.SelectedShop("fixture-a", null, "A", "owner", "active", true)
        val shop = com.example.merchandisecontrolsplitview.data.ShopContext(signedIn.userId, emptyList(), selected,
            localAccessAllowed = true)
        val identity = com.example.merchandisecontrolsplitview.data.LocalAuthIdentity(signedIn.userId, 7)
        val ready = Task126BusinessDataScopeState.ready(scope)
        fun key(auth: AuthState = signedIn, proof: com.example.merchandisecontrolsplitview.data.LocalAuthIdentity? = identity,
            current: Boolean = true, context: com.example.merchandisecontrolsplitview.data.ShopContext = shop,
            state: Task126BusinessDataScopeState = ready) =
            com.example.merchandisecontrolsplitview.viewmodel.localBusinessPresentationKey(auth, proof, current, context, state)
        org.junit.Assert.assertNotNull(key())
        org.junit.Assert.assertNull(key(auth = AuthState.SignedOut))
        org.junit.Assert.assertNull(key(auth = AuthState.Checking, proof = null))
        org.junit.Assert.assertNull(key(current = false))
        org.junit.Assert.assertNull(key(proof = identity.copy(userId = "foreign")))
        org.junit.Assert.assertNull(key(context = shop.copy(localAccessAllowed = false)))
        org.junit.Assert.assertNull(key(context = shop.copy(selectedShop = selected.copy(shopId = "fixture-b"))))
        org.junit.Assert.assertNull(key(state = ready.copy(boundScope = null)))
        org.junit.Assert.assertNull(key(state = ready.copy(status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH)))
        org.junit.Assert.assertNotEquals("Logout followed by a new auth generation must not reopen the old session body",
            key(), key(proof = identity.copy(authGeneration = 8)))
    }

}

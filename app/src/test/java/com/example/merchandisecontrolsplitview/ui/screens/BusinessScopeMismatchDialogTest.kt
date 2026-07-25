package com.example.merchandisecontrolsplitview.ui.screens

import com.example.merchandisecontrolsplitview.data.LocalDatabaseStatusSnapshot
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessScopeMismatchDialogTest {
    @Test
    fun `account and shop mismatch produce a verified privacy safe identity`() {
        val account = businessScopeMismatchDialogEligibility(
            state = mismatchState(
                status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
                boundScope = scope(BOUND_OWNER, SHOP_A)
            ),
            verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
        )
        val shop = businessScopeMismatchDialogEligibility(
            state = mismatchState(
                status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH,
                boundScope = scope(BOUND_OWNER, SHOP_A)
            ),
            verifiedActiveScope = scope(BOUND_OWNER, SHOP_B)
        )

        listOf(account, shop).forEach { result ->
            assertTrue(result.canReplace)
            assertNotNull(result.identity)
            assertTrue(result.identity!!.matches(Regex("[0-9a-f]{64}")))
            assertFalse(result.identity.contains(BOUND_OWNER))
            assertFalse(result.identity.contains(ACTIVE_OWNER))
            assertFalse(result.identity.contains(SHOP_A))
            assertFalse(result.identity.contains(SHOP_B))
        }
        assertNotEquals(account.identity, shop.identity)
    }

    @Test
    fun `replace stays disabled until mismatch shop and local snapshot are verified`() {
        val state = mismatchState(
            status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
            boundScope = scope(BOUND_OWNER, SHOP_A)
        )

        val noVerifiedShop = businessScopeMismatchDialogEligibility(state, null)
        assertNull(noVerifiedShop.identity)
        assertFalse(noVerifiedShop.canReplace)

        val noVerifiedSnapshot = businessScopeMismatchDialogEligibility(
            state = state.copy(localSnapshot = null),
            verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
        )
        assertNotNull(noVerifiedSnapshot.identity)
        assertFalse(noVerifiedSnapshot.canReplace)

        val checking = businessScopeMismatchDialogEligibility(
            state = state.copy(status = Task126BusinessDataScopeStatus.CHECKING),
            verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
        )
        assertNull(checking.identity)
        assertFalse(checking.canReplace)

        val schemaMismatch = businessScopeMismatchDialogEligibility(
            state = state.copy(status = Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH),
            verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
        )
        assertNull(schemaMismatch.identity)
        assertFalse(schemaMismatch.canReplace)

        val missingBinding = businessScopeMismatchDialogEligibility(
            state = state.copy(boundScope = null),
            verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
        )
        assertNull(missingBinding.identity)
        assertFalse(missingBinding.canReplace)

        val defaultStoreFallback = businessScopeMismatchDialogEligibility(
            state = state,
            verifiedActiveScope = Task126OwnerStoreScope(
                ownerHash = ACTIVE_OWNER,
                storeId = "",
                localStoreId = null
            )
        )
        assertNull(defaultStoreFallback.identity)
        assertFalse(defaultStoreFallback.canReplace)

        listOf("default", "shop-a-fixture", "00000000-0000-0000-0000-00000000000x").forEach {
            val malformed = businessScopeMismatchDialogEligibility(
                state = state,
                verifiedActiveScope = scope(ACTIVE_OWNER, it)
            )
            assertNull(malformed.identity)
            assertFalse(malformed.canReplace)
        }
    }

    @Test
    fun `verified PostgreSQL UUIDv7 shop enables the destructive choice`() {
        listOf(
            "018f0ad4-77f2-7c9d-a8be-4f6b9d234567",
            "00000000-0000-0000-0000-000000000000"
        ).forEach { postgresUuid ->
            val eligibility = businessScopeMismatchDialogEligibility(
                state = mismatchState(
                    status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH,
                    boundScope = scope(BOUND_OWNER, SHOP_A)
                ),
                verifiedActiveScope = scope(BOUND_OWNER, postgresUuid)
            )

            assertNotNull(eligibility.identity)
            assertTrue(eligibility.canReplace)
        }
    }

    @Test
    fun `auto presentation is once per identity across recomposition relaunch and reconnect`() {
        val identityA = requireNotNull(
            businessScopeMismatchDialogEligibility(
                state = mismatchState(
                    status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
                    boundScope = scope(BOUND_OWNER, SHOP_A)
                ),
                verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
            ).identity
        )
        val identityAAfterReconnect = requireNotNull(
            businessScopeMismatchDialogEligibility(
                state = mismatchState(
                    status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
                    boundScope = scope(BOUND_OWNER, SHOP_A)
                ),
                verifiedActiveScope = scope(ACTIVE_OWNER, SHOP_A)
            ).identity
        )
        val identityB = requireNotNull(
            businessScopeMismatchDialogEligibility(
                state = mismatchState(
                    status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH,
                    boundScope = scope(BOUND_OWNER, SHOP_A)
                ),
                verifiedActiveScope = scope(BOUND_OWNER, SHOP_B)
            ).identity
        )

        assertEquals(identityA, identityAAfterReconnect)
        assertTrue(shouldAutoShowBusinessScopeMismatchDialog(identityA, emptySet()))

        val afterFirstPresentation = businessScopeMismatchAutoShownIdentitiesAfterPresentation(
            current = emptySet(),
            identity = identityA
        )
        assertFalse(shouldAutoShowBusinessScopeMismatchDialog(identityA, afterFirstPresentation))
        assertFalse(
            shouldAutoShowBusinessScopeMismatchDialog(
                identityAAfterReconnect,
                afterFirstPresentation.toSet()
            )
        )
        assertFalse(shouldAutoShowBusinessScopeMismatchDialog(null, afterFirstPresentation))
        assertTrue(shouldAutoShowBusinessScopeMismatchDialog(identityB, afterFirstPresentation))

        val afterSecondPresentation = businessScopeMismatchAutoShownIdentitiesAfterPresentation(
            current = afterFirstPresentation,
            identity = identityB
        )
        assertFalse(shouldAutoShowBusinessScopeMismatchDialog(identityA, afterSecondPresentation))
        assertFalse(shouldAutoShowBusinessScopeMismatchDialog(identityB, afterSecondPresentation))
    }

    @Test
    fun `manual presentation records identity without changing null sets`() {
        val identity = "a".repeat(64)
        assertEquals(
            setOf(identity),
            businessScopeMismatchAutoShownIdentitiesAfterPresentation(emptySet(), identity)
        )
        assertEquals(
            setOf(identity),
            businessScopeMismatchAutoShownIdentitiesAfterPresentation(setOf(identity), identity)
        )
        assertEquals(
            setOf(identity),
            businessScopeMismatchAutoShownIdentitiesAfterPresentation(setOf(identity), null)
        )
    }

    @Test
    fun `remembered mismatch identities remain deterministically bounded`() {
        val identities = (1..100).map { index ->
            index.toString(16).padStart(64, '0')
        }
        val remembered = identities.fold(emptySet<String>()) { current, identity ->
            businessScopeMismatchAutoShownIdentitiesAfterPresentation(current, identity)
        }

        assertEquals(BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_MAX, remembered.size)
        assertTrue(BUSINESS_SCOPE_MISMATCH_AUTO_SHOW_DISABLED_MARKER in remembered)
        assertFalse(identities.first() in remembered)
        assertFalse(identities.last() in remembered)
        assertFalse(
            shouldAutoShowBusinessScopeMismatchDialog(identities.first(), remembered)
        )
        assertFalse(
            shouldAutoShowBusinessScopeMismatchDialog(identities.last(), remembered)
        )
        assertEquals(
            remembered,
            businessScopeMismatchAutoShownIdentitiesAfterPresentation(remembered, null)
        )
    }

    private fun mismatchState(
        status: Task126BusinessDataScopeStatus,
        boundScope: Task126OwnerStoreScope
    ): Task126BusinessDataScopeState = Task126BusinessDataScopeState(
        status = status,
        boundScope = boundScope,
        localSnapshot = LocalDatabaseStatusSnapshot(
            products = 1,
            suppliers = 2,
            categories = 3,
            priceHistoryRows = 4,
            historySessions = 5,
            pendingLocalChanges = 6,
            syncEventOutboxPending = 7
        )
    )

    private fun scope(ownerHash: String, shopId: String): Task126OwnerStoreScope =
        Task126OwnerStoreScope(
            ownerHash = ownerHash,
            storeId = "shop:$shopId",
            localStoreId = "local-shop:$shopId"
        )

    private companion object {
        val BOUND_OWNER = "b".repeat(64)
        val ACTIVE_OWNER = "a".repeat(64)
        const val SHOP_A = "10000000-0000-4000-8000-000000000001"
        const val SHOP_B = "10000000-0000-4000-8000-000000000002"
    }
}

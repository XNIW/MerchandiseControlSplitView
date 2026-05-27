package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Task126ConflictReviewUiTest {
    @Test
    fun `same field review exposes all user choices and resolves use local`() {
        val state = Task126ReviewInteractionFixtures.conflictReviewSameField()

        assertEquals(Task126ReviewSurface.ConflictReview, state.surface)
        assertEquals(
            listOf(
                Task126UserChoice.UseLocal,
                Task126UserChoice.UseRemote,
                Task126UserChoice.EditManually,
                Task126UserChoice.ApplyToSimilar,
                Task126UserChoice.PostponeReview
            ),
            state.visibleChoices
        )
        assertTrue(state.isDialogVisible)
        assertFalse(state.isApplying)

        val outcome = Task126ReviewInteractionReducer.apply(Task126UserChoice.UseLocal, state)

        assertEquals("productName=MX", outcome.observedLocalResult)
        assertEquals("pending=0;review=0;synced=local", outcome.observedSyncResult)
        assertEquals(0, outcome.pendingAfter)
        assertEquals(0, outcome.conflictCountAfter)
        assertEquals(0, outcome.reviewRemainingCount)
        assertTrue(outcome.timeToReviewShownMs >= 1)
        assertTrue(outcome.timeToApplyChoiceMs >= 1)
        assertTrue(outcome.timeToFinalStateMs >= outcome.timeToApplyChoiceMs)
    }

    @Test
    fun `same field choice outcomes cover every review button`() {
        val state = Task126ReviewInteractionFixtures.conflictReviewSameFieldReverse()
        val expectations = listOf(
            ChoiceExpectation(
                choice = Task126UserChoice.UseLocal,
                local = "productName=MX",
                sync = "pending=0;review=0;synced=local",
                pendingAfter = 0,
                reviewAfter = 0
            ),
            ChoiceExpectation(
                choice = Task126UserChoice.UseRemote,
                local = "productName=X",
                sync = "pending=0;review=0;synced=remote",
                pendingAfter = 0,
                reviewAfter = 0
            ),
            ChoiceExpectation(
                choice = Task126UserChoice.EditManually,
                local = "productName=manual-review",
                sync = "pending=0;review=0;synced=manual",
                pendingAfter = 0,
                reviewAfter = 0
            ),
            ChoiceExpectation(
                choice = Task126UserChoice.ApplyToSimilar,
                local = "productName=MX;appliedSimilar=true",
                sync = "pending=0;review=0;synced=bulk-local",
                pendingAfter = 0,
                reviewAfter = 0
            ),
            ChoiceExpectation(
                choice = Task126UserChoice.PostponeReview,
                local = "productName unresolved",
                sync = "pending=1;review=1;synced=pending",
                pendingAfter = 1,
                reviewAfter = 1
            )
        )

        expectations.forEach { expected ->
            val outcome = Task126ReviewInteractionReducer.apply(expected.choice, state)

            assertEquals(expected.local, outcome.observedLocalResult)
            assertEquals(expected.sync, outcome.observedSyncResult)
            assertEquals(expected.pendingAfter, outcome.pendingAfter)
            assertEquals(expected.reviewAfter, outcome.reviewRemainingCount)
            assertTrue(outcome.timeToReviewShownMs >= 1)
            assertTrue(outcome.timeToApplyChoiceMs >= 1)
        }
    }

    @Test
    fun `different field directions auto merge without review popup`() {
        listOf(
            Task126ReviewInteractionFixtures.conflictReviewDifferentFieldsIosOffline(),
            Task126ReviewInteractionFixtures.conflictReviewDifferentFieldsAndroidOffline()
        ).forEach { state ->
            assertFalse(state.isDialogVisible)
            assertTrue(state.visibleChoices.isEmpty())

            val outcome = Task126ReviewInteractionReducer.apply(Task126UserChoice.PostponeReview, state)

            assertEquals("fieldA=local;fieldB=remote;merged=1", outcome.observedLocalResult)
            assertEquals("pending=0;review=0;synced=merged", outcome.observedSyncResult)
            assertEquals(0, outcome.pendingAfter)
            assertEquals(0, outcome.conflictCountAfter)
            assertEquals(0, outcome.reviewRemainingCount)
        }
    }

    @Test
    fun `mixed batch auto merges non conflicts and leaves only conflicts for review`() {
        val state = Task126ReviewInteractionFixtures.conflictReviewMixedBatch()

        assertEquals(1, state.mergedCount)
        assertEquals(1, state.conflictCountBefore)
        assertEquals(1, state.reviewRemainingCount)

        val outcome = Task126ReviewInteractionReducer.apply(Task126UserChoice.PostponeReview, state)

        assertEquals("stock=12 auto-merged; productName unresolved", outcome.observedLocalResult)
        assertEquals(1, outcome.pendingAfter)
        assertEquals(1, outcome.conflictCountAfter)
        assertEquals(1, outcome.reviewRemainingCount)
        assertEquals(1, outcome.mergedCount)
    }

    @Test
    fun `delete edit and product price stale require review choices`() {
        listOf(
            Task126ReviewInteractionFixtures.conflictReviewDeleteVsEdit(),
            Task126ReviewInteractionFixtures.conflictReviewProductPriceStale()
        ).forEach { state ->
            assertTrue(state.isDialogVisible)
            assertTrue(state.visibleChoices.contains(Task126UserChoice.UseLocal))
            assertTrue(state.visibleChoices.contains(Task126UserChoice.UseRemote))
            assertTrue(state.visibleChoices.contains(Task126UserChoice.PostponeReview))
            assertTrue(state.conflictCountBefore > 0)
        }
    }

    private data class ChoiceExpectation(
        val choice: Task126UserChoice,
        val local: String,
        val sync: String,
        val pendingAfter: Int,
        val reviewAfter: Int
    )
}

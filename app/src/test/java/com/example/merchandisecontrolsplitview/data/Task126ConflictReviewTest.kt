package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Task126ConflictReviewTest {
    @Test
    fun `update requires changed fields`() {
        assertFalse(Task126ChangedFieldsContract.isValid("update", emptyList()))
        assertTrue(Task126ChangedFieldsContract.isValid("update", listOf("productName")))
    }

    @Test
    fun `delete versus edit routes to review`() {
        assertEquals(
            Task126ConflictDecision.Review(Task126ReviewReason.DeleteVsEdit),
            Task126ConflictResolver.resolve(
                localChangedFields = listOf("delete"),
                remoteChangedFields = listOf("productName"),
                remoteDeleted = true
            )
        )
    }

    @Test
    fun `batch review separates mergeable rows from review rows`() {
        val summary = Task126ConflictBatchReview.summarize(
            listOf(
                Task126ConflictBatchReview.Item(
                    localChangedFields = listOf("name"),
                    remoteChangedFields = listOf("price")
                ),
                Task126ConflictBatchReview.Item(
                    localChangedFields = listOf("barcode"),
                    remoteChangedFields = listOf("barcode")
                ),
                Task126ConflictBatchReview.Item(
                    localChangedFields = listOf("delete"),
                    remoteChangedFields = listOf("name"),
                    remoteDeleted = true
                )
            )
        )

        assertEquals(1, summary.autoMergeCount)
        assertEquals(2, summary.reviewCount)
        assertEquals(listOf(Task126ReviewReason.SameField, Task126ReviewReason.DeleteVsEdit), summary.reasons)
    }

    @Test
    fun `product price append dedupe and stale review policy`() {
        assertEquals(
            Task126ProductPriceDecision.Append,
            Task126ProductPriceHistoryPolicy.resolve(null, "12.50")
        )
        assertEquals(
            Task126ProductPriceDecision.Dedupe,
            Task126ProductPriceHistoryPolicy.resolve("12.50", "12.50")
        )
        assertEquals(
            Task126ProductPriceDecision.ReviewStale,
            Task126ProductPriceHistoryPolicy.resolve("12.50", "13.00")
        )
    }
}

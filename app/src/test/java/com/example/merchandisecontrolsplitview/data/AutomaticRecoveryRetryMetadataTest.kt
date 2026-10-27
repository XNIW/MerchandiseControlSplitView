package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticRecoveryRetryMetadataTest {
    private val claim = AutomaticRecoveryRetryMetadata("recovery_required", SyncErrorCategory.Unknown,
        1, "in_flight", 100, "f4220fc8-6fe9-471a-9d46-cb39bf73b851", 100, null)
    private val epoch = "a424c139-1d73-46cf-8853-04ba04bbd0da"

    @Test
    fun legacyInFlightIsStrictlyReadableWithoutInventingProcessLiveness() {
        assertEquals(claim, AutomaticRecoveryRetryMetadata.decode(claim.encode()))
        assertNull(AutomaticRecoveryRetryMetadata.decode(claim.encode().replaceFirst("\"v\":1", "\"v\":3")))
        assertNull(AutomaticRecoveryRetryMetadata.decode(claim.encode().replaceFirst("{", "{\"v\":1,")))
    }

    @Test
    fun orphanQualificationPreservesClaimAndBudgetAndNeedsDurableDueAndOrigin() {
        val waiting = claim.copy(state = "requalifying", nextEligibleAtMs = 301_000,
            processEpoch = epoch, requalificationObservedAtMs = 1_000, previousClaimSha256 = "a".repeat(64))
        assertEquals(waiting, AutomaticRecoveryRetryMetadata.decode(waiting.encode()))
        assertEquals(claim.claimID, waiting.claimID)
        assertEquals(claim.windowAttempts, waiting.windowAttempts)
        assertEquals(claim.category, waiting.category)
        listOf(waiting.copy(nextEligibleAtMs = 300_999), waiting.copy(previousClaimSha256 = null),
            waiting.copy(processEpoch = "not-an-epoch"), waiting.copy(lastCompletedAtMs = 1_000),
            waiting.copy(requalificationObservedAtMs = Long.MAX_VALUE)).forEach {
            assertNull(AutomaticRecoveryRetryMetadata.decode(it.encode()))
        }
    }

    @Test
    fun freshClaimAndCompletionCannotCarryStaleQualificationEvidence() {
        val fresh = claim.copy(processEpoch = epoch)
        assertEquals(fresh, AutomaticRecoveryRetryMetadata.decode(fresh.encode()))
        assertNull(AutomaticRecoveryRetryMetadata.decode(fresh.copy(previousClaimSha256 = "a".repeat(64)).encode()))
        val completed = fresh.copy(category = SyncErrorCategory.NetworkOfflineOrTimeout, state = "waiting",
            lastCompletedAtMs = 200, nextEligibleAtMs = 2_200)
        assertEquals(completed, AutomaticRecoveryRetryMetadata.decode(completed.encode()))
        assertNull(AutomaticRecoveryRetryMetadata.decode(completed.copy(requalificationObservedAtMs = 200).encode()))
    }
}

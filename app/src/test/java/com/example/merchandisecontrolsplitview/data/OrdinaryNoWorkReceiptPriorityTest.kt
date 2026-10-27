package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdinaryNoWorkReceiptPriorityTest {
    private val scope = Task126OwnerStoreScope(task126OwnerHash("owner"), "shop:one", null)
    private val guard = Task126BusinessDataScopeFlightGate(Task126BusinessDataScopeState.ready(scope))
    private val lease = Task126BusinessDataScopeLease(0L, scope, unmanaged = false)

    @Test
    fun pendingWriterBeforeRegistrationDefersUntilEveryReservationLeaves() {
        val priority = OrdinaryNoWorkReceiptPriority()
        val a = priority.reserveWriter(lease.copy(localOnly = true, generation = 7L))
        val b = priority.reserveWriter(lease.copy(localOnly = true, generation = 8L))
        val first = priority.register(lease, guard, Job())
        assertTrue(first.requested)
        priority.releaseWriter(a)
        val second = priority.register(lease, guard, Job())
        assertTrue(second.requested)
        priority.releaseWriter(b)
        val third = priority.register(lease, guard, Job())
        priority.accept(third)
        priority.retire(first)
        priority.retire(second)
        priority.retire(third)
    }

    @Test
    fun requestIsStickyAfterSaveOrAckAndOldCleanupCannotRetireNewAttempt() {
        val priority = OrdinaryNoWorkReceiptPriority()
        val old = priority.register(lease, guard, Job())
        val writer = priority.reserveWriter(lease.copy(localOnly = true))
        priority.releaseWriter(writer)
        val current = priority.register(lease, guard, Job())
        priority.retire(old)
        assertTrue(runCatching { old.checkRequested() }.exceptionOrNull() is OrdinaryNoWorkReceiptDeferred)
        assertFalse(current.requested)
        priority.accept(current)
        priority.retire(current)
    }

    @Test
    fun sameValueScopeAcrossRepositoryLeasesMatchesButOtherDatabaseAndScopeDoNot() {
        val firstDb = OrdinaryNoWorkReceiptPriority()
        val secondDb = OrdinaryNoWorkReceiptPriority()
        val attempt = firstDb.register(lease, guard, Job())
        val otherDbWriter = secondDb.reserveWriter(lease.copy(localOnly = true))
        val otherScope = Task126OwnerStoreScope(scope.ownerHash, "shop:two", null)
        val otherScopeWriter = firstDb.reserveWriter(lease.copy(boundScope = otherScope, localOnly = true))
        assertFalse(attempt.requested)
        val equalValues = Task126OwnerStoreScope(scope.ownerHash, scope.storeId, scope.localStoreId)
        val matching = firstDb.reserveWriter(lease.copy(boundScope = equalValues, localOnly = true, generation = 99L))
        assertTrue(attempt.requested)
        firstDb.releaseWriter(matching)
        firstDb.releaseWriter(otherScopeWriter)
        secondDb.releaseWriter(otherDbWriter)
        firstDb.retire(attempt)
    }

    @Test
    fun acceptanceWinsBeforeLaterSaveAndNoAttemptIsReused() {
        val priority = OrdinaryNoWorkReceiptPriority()
        val accepted = priority.register(lease, guard, Job())
        priority.accept(accepted)
        val writer = priority.reserveWriter(lease.copy(localOnly = true))
        assertFalse(accepted.requested)
        val next = priority.register(lease, guard, Job())
        assertTrue(next.requested)
        assertTrue(runCatching { priority.accept(next) }.exceptionOrNull() is OrdinaryNoWorkReceiptDeferred)
        priority.releaseWriter(writer)
        priority.retire(next)
        priority.retire(accepted)
    }

    @Test
    fun allConcurrentProofTokensAreSignalledWithoutCancellingTheirJobs() {
        val priority = OrdinaryNoWorkReceiptPriority()
        val jobs = List(2) { Job() }
        val attempts = jobs.map { priority.register(lease, guard, it) }
        val writer = priority.reserveWriter(lease.copy(localOnly = true))
        assertTrue(attempts.all { it.requested })
        assertTrue(jobs.all { it.isActive })
        priority.releaseWriter(writer)
        attempts.forEach(priority::retire)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun capturedAuthorityCannotSurviveScopeAba() = runBlocking {
        val priority = OrdinaryNoWorkReceiptPriority()
        val attempt = priority.register(lease, guard, Job())
        val writer = priority.reserveWriter(lease.copy(localOnly = true))
        val deferred = runCatching { attempt.checkRequested() }.exceptionOrNull() as OrdinaryNoWorkReceiptDeferred
        assertTrue(deferred.scopeStillCurrent())
        guard.withBusinessDataScopeTransition { }
        assertFalse(deferred.scopeStillCurrent())
        priority.releaseWriter(writer)
        priority.retire(attempt)
    }
}

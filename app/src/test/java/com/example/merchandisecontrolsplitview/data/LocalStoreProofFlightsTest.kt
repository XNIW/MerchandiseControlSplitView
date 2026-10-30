package com.example.merchandisecontrolsplitview.data

import java.util.concurrent.CancellationException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalStoreProofFlightsTest {
    private data class ProofKey(val ownerStore: String, val generation: Long)
    // Identity markers make these original owner exceptions non-copyable by coroutine stack recovery.
    private class OwnerFailure : IllegalStateException("owner failure") { val identity = Any() }
    private class OwnerCancellation : CancellationException("owner cancellation") { val identity = Any() }

    @Test fun structurallyEqualKeyJoinsOnlyTheSameOpenEntry() = runTest {
        val flights = LocalStoreProofFlights<ProofKey>()
        assertFalse(flights.hasOpen()); assertNull(flights.tryJoin(ProofKey("scope", 1L)))
        val entry = flights.begin(ProofKey("scope", 1L))
        assertTrue(flights.hasOpen()); assertTrue(entry.accepting)
        assertSame(entry, flights.tryJoin(ProofKey("scope", 1L)))
        assertThrows(IllegalStateException::class.java) { flights.begin(ProofKey("scope", 1L)) }
        assertFalse(entry.completion.isCompleted)
        flights.finish(entry, null)
        assertEquals(Unit, entry.completion.await())
        assertFalse(flights.hasOpen()); assertNull(flights.tryJoin(ProofKey("scope", 1L)))
    }

    @Test fun transactionSealRejectsNewJoinBeforeCompletionAndNeverReusesCompletedProof() = runTest {
        val flights = LocalStoreProofFlights<ProofKey>(); val key = ProofKey("scope", 2L)
        val entry = flights.begin(key)
        val context = LocalStoreProofTransactionContext { flights.seal(entry) }
        assertSame(context, (EmptyCoroutineContext + context)[LocalStoreProofTransactionContext])
        context.sealBeforeTransactionExit(); context.sealBeforeTransactionExit()
        assertFalse(entry.accepting); assertFalse(flights.hasOpen())
        assertNull(flights.tryJoin(key)); assertFalse(entry.completion.isCompleted)
        flights.finish(entry, null)
        assertEquals(Unit, entry.completion.await()); assertNull(flights.tryJoin(key))
        val fresh = flights.begin(key)
        assertNotSame(entry, fresh); assertFalse(fresh.completion.isCompleted)
        assertSame(fresh, flights.tryJoin(key))
        fresh.completion.complete(Unit) // A completed promise is not reusable, even before explicit seal.
        assertTrue(fresh.accepting); assertFalse(flights.hasOpen()); assertNull(flights.tryJoin(key))
        assertThrows(IllegalStateException::class.java) { flights.begin(key) }
        flights.seal(fresh); flights.finish(fresh, null)
    }

    @Test fun differentKeyCannotReplaceOpenEntryAndOldFinishCannotClearSameKeyReplacement() = runTest {
        val flights = LocalStoreProofFlights<ProofKey>()
        val firstKey = ProofKey("scope", 3L); val differentKey = ProofKey("scope", 4L)
        val first = flights.begin(firstKey)
        assertNull(flights.tryJoin(differentKey))
        assertThrows(IllegalStateException::class.java) { flights.begin(differentKey) }
        assertSame(first, flights.tryJoin(ProofKey("scope", 3L)))
        flights.seal(first)
        val replacement = flights.begin(ProofKey("scope", 3L)) // Same key, different entry identity (ABA).
        flights.seal(first); flights.finish(first, null)
        assertEquals(Unit, first.completion.await())
        assertTrue(replacement.accepting); assertFalse(replacement.completion.isCompleted)
        assertSame(replacement, flights.tryJoin(firstKey)); assertTrue(flights.hasOpen())
        flights.finish(first, IllegalStateException("late old finish"))
        assertSame(replacement, flights.tryJoin(firstKey))
        flights.finish(replacement, null); assertFalse(flights.hasOpen())
    }

    @Test fun cancelledFollowerDoesNotCancelOrCompleteTheOwnersPromise() = runTest {
        val flights = LocalStoreProofFlights<ProofKey>(); val key = ProofKey("scope", 5L)
        val entry = flights.begin(key)
        val follower = backgroundScope.async { requireNotNull(flights.tryJoin(key)).completion.await() }
        runCurrent(); assertFalse(follower.isCompleted)
        follower.cancel(CancellationException("follower only")); follower.join()
        assertTrue(follower.isCancelled)
        assertFalse(entry.completion.isCancelled); assertFalse(entry.completion.isCompleted)
        assertSame(entry, flights.tryJoin(key))
        flights.finish(entry, null)
        assertEquals(Unit, entry.completion.await()); assertFalse(flights.hasOpen())
    }

    @Test fun ownerFailureAndCancellationReachEveryWaiterUnwrappedWithoutCreatingAnotherWorker() = runTest {
        val failure = OwnerFailure(); val cancellation = OwnerCancellation()
        assertNotNull(failure.identity); assertNotNull(cancellation.identity)
        for (original in listOf<Throwable>(failure, cancellation)) {
            val flights = LocalStoreProofFlights<ProofKey>()
            val backgroundJob = requireNotNull(backgroundScope.coroutineContext[Job])
            val childrenBefore = backgroundJob.children.count()
            val key = ProofKey("scope", 6L); val entry = flights.begin(key)
            assertEquals(childrenBefore, backgroundJob.children.count())
            val followers = List(2) {
                backgroundScope.async {
                    runCatching { requireNotNull(flights.tryJoin(key)).completion.await() }.exceptionOrNull()
                }
            }
            runCurrent(); assertTrue(followers.none { it.isCompleted })
            flights.finish(entry, original)
            runCurrent()
            for (follower in followers) assertSame(original, follower.await())
            assertFalse(flights.hasOpen()); assertNull(flights.tryJoin(key))
            assertEquals(childrenBefore, backgroundJob.children.count())
        }
    }
}

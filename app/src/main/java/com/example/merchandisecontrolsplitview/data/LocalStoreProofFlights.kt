package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only callers overlapping the same still-open proof transaction may join.
 * A sealed/completed entry is never an admission cache or a persisted content proof.
 * Leaders remain serialized by the application's existing business mutex. */
internal class LocalStoreProofFlights<K> {
    internal class Entry<K> internal constructor(val key: K) {
        // No parent: cancelling an awaiting follower must not cancel the owner's completion.
        val completion = CompletableDeferred<Unit>()
        @Volatile internal var accepting: Boolean = true
    }

    private val lock = Any()
    private var currentEntry: Entry<K>? = null

    fun hasOpen(): Boolean = synchronized(lock) {
        val entry = currentEntry
        entry != null && entry.accepting && !entry.completion.isCompleted
    }

    fun tryJoin(key: K): Entry<K>? = synchronized(lock) {
        currentEntry?.takeIf { it.key == key && it.accepting && !it.completion.isCompleted }
    }

    fun begin(key: K): Entry<K> = synchronized(lock) {
        check(currentEntry?.accepting != true) { "The current proof must be sealed before replacement" }
        Entry(key).also { currentEntry = it }
    }

    /** Synchronous boundary inside the original Room transaction's block finally. */
    fun seal(entry: Entry<K>) {
        synchronized(lock) {
            if (currentEntry === entry) entry.accepting = false
        }
    }

    fun finish(entry: Entry<K>, error: Throwable?) {
        synchronized(lock) {
            if (currentEntry === entry) {
                entry.accepting = false
                currentEntry = null
            }
        }
        // Old waiters still need their own outcome. Completing outside the lock also lets
        // inline completion handlers safely observe/begin the next independently admitted entry.
        if (error == null) entry.completion.complete(Unit)
        else entry.completion.completeExceptionally(error)
    }
}

/** The callback only seals joins; no database operation or suspension belongs here. */
internal class LocalStoreProofTransactionContext(
    private val onBeforeExit: () -> Unit
) : AbstractCoroutineContextElement(Key) {
    fun sealBeforeTransactionExit() = onBeforeExit()

    companion object Key : CoroutineContext.Key<LocalStoreProofTransactionContext>
}

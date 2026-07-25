package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Lease process-local di uno scope business già validato.
 *
 * La generation cambia quando account, shop o stato fail-closed cambiano. Il lease
 * viaggia nel CoroutineContext del flight e viene ricontrollato nei boundary rete/Room.
 */
internal data class Task126BusinessDataScopeLease(
    val generation: Long,
    val boundScope: Task126OwnerStoreScope?,
    val unmanaged: Boolean
)

class Task126BusinessDataScopeSignalToken internal constructor(
    internal val lease: Task126BusinessDataScopeLease
)

internal class Task126BusinessDataScopeLeaseContext(
    val lease: Task126BusinessDataScopeLease
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Task126BusinessDataScopeLeaseContext>
}

class Task126BusinessDataScopeChangedException(
    message: String = "business_data_scope_changed"
) : CancellationException(message)

interface Task126BusinessDataScopeRuntimeGuard {
    suspend fun <T> withBusinessDataScopeFlight(
        ownerUserId: String,
        selectedShop: SelectedShop?,
        block: suspend () -> T
    ): T

    suspend fun <T> withCurrentBusinessDataScopeFlight(
        block: suspend () -> T
    ): T

    suspend fun requireCurrentBusinessDataScope()

    fun captureBusinessDataScopeSignal(
        ownerUserId: String,
        shopId: String?
    ): Task126BusinessDataScopeSignalToken

    fun isCurrentBusinessDataScopeSignal(
        token: Task126BusinessDataScopeSignalToken
    ): Boolean

    suspend fun cancelAndJoinBusinessDataScopeFlights()

    suspend fun <T> withBusinessDataScopeTransition(block: suspend () -> T): T
}

object Task126UnmanagedBusinessDataScopeRuntimeGuard : Task126BusinessDataScopeRuntimeGuard {
    override suspend fun <T> withBusinessDataScopeFlight(
        ownerUserId: String,
        selectedShop: SelectedShop?,
        block: suspend () -> T
    ): T = block()

    override suspend fun <T> withCurrentBusinessDataScopeFlight(
        block: suspend () -> T
    ): T = block()

    override suspend fun requireCurrentBusinessDataScope() = Unit

    override fun captureBusinessDataScopeSignal(
        ownerUserId: String,
        shopId: String?
    ): Task126BusinessDataScopeSignalToken =
        Task126BusinessDataScopeSignalToken(
            Task126BusinessDataScopeLease(
                generation = 0L,
                boundScope = null,
                unmanaged = true
            )
        )

    override fun isCurrentBusinessDataScopeSignal(
        token: Task126BusinessDataScopeSignalToken
    ): Boolean = token.lease.unmanaged

    override suspend fun cancelAndJoinBusinessDataScopeFlights() = Unit

    override suspend fun <T> withBusinessDataScopeTransition(block: suspend () -> T): T = block()
}

internal class Task126BusinessDataScopeFlightGate(
    initialState: Task126BusinessDataScopeState
) : Task126BusinessDataScopeRuntimeGuard {
    private val lock = Any()
    private val transitionMutex = Mutex()
    private var state = initialState
    private var generation = 0L
    private var nextFlightId = 1L
    private var transitioning = false
    /**
     * A cancelled [Deferred] can complete before a non-cooperative child has
     * left its `finally`/`NonCancellable` work.  A scope transition must wait
     * for that work as well, otherwise an old-scope writer can cross the
     * replacement activation boundary.
     */
    private data class ActiveFlight(
        val deferred: Deferred<*>,
        val quiesced: CompletableDeferred<Unit>
    )

    private val activeFlights = linkedMapOf<Long, ActiveFlight>()

    fun updateState(next: Task126BusinessDataScopeState) {
        val toCancel = synchronized(lock) {
            val boundaryChanged = boundarySignature(state) != boundarySignature(next)
            state = next
            if (!boundaryChanged) {
                emptyList()
            } else {
                generation += 1L
                activeFlights.values.map { it.deferred }
            }
        }
        if (toCancel.isNotEmpty()) {
            val cause = CancellationException("business_data_scope_invalidated")
            toCancel.forEach { flight -> flight.cancel(cause) }
        }
    }

    fun allowsBusinessDataScope(ownerUserId: String, selectedShop: SelectedShop?): Boolean =
        synchronized(lock) {
            if (transitioning) return@synchronized false
            runCatching {
                captureLeaseLocked(ownerUserId, selectedShop)
            }.isSuccess
        }

    override suspend fun <T> withBusinessDataScopeFlight(
        ownerUserId: String,
        selectedShop: SelectedShop?,
        block: suspend () -> T
    ): T {
        val inherited = currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease
        if (inherited != null) {
            requireLeaseCurrent(inherited)
            requireLeaseMatches(inherited, ownerUserId, selectedShop)
            return block()
        }
        val lease = synchronized(lock) {
            captureLeaseLocked(ownerUserId, selectedShop)
        }
        return runRegisteredFlight(lease, block)
    }

    override suspend fun <T> withCurrentBusinessDataScopeFlight(
        block: suspend () -> T
    ): T {
        val inherited = currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease
        if (inherited != null) {
            requireLeaseCurrent(inherited)
            return block()
        }
        val lease = synchronized(lock) {
            captureLeaseLocked(ownerUserId = null, selectedShop = null)
        }
        return runRegisteredFlight(lease, block)
    }

    override suspend fun requireCurrentBusinessDataScope() {
        val lease = currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease
            ?: throw Task126BusinessDataScopeChangedException("business_data_scope_lease_missing")
        requireLeaseCurrent(lease)
    }

    override fun captureBusinessDataScopeSignal(
        ownerUserId: String,
        shopId: String?
    ): Task126BusinessDataScopeSignalToken {
        if (ownerUserId.isBlank()) {
            throw Task126BusinessDataScopeChangedException("business_data_scope_signal_owner_missing")
        }
        val activeScope = Task126OwnerStoreScope(
            ownerHash = task126OwnerHash(ownerUserId),
            storeId = shopId?.trim()?.takeIf { it.isNotEmpty() }?.let { "shop:$it" },
            localStoreId = null
        )
        return Task126BusinessDataScopeSignalToken(
            synchronized(lock) {
                captureLeaseForActiveScopeLocked(activeScope)
            }
        )
    }

    override fun isCurrentBusinessDataScopeSignal(
        token: Task126BusinessDataScopeSignalToken
    ): Boolean = isLeaseCurrent(token.lease)

    override suspend fun cancelAndJoinBusinessDataScopeFlights() {
        withBusinessDataScopeTransition { Unit }
    }

    override suspend fun <T> withBusinessDataScopeTransition(
        block: suspend () -> T
    ): T {
        if (currentCoroutineContext()[Task126BusinessDataScopeLeaseContext] != null) {
            throw Task126BusinessDataScopeChangedException(
                "business_data_scope_transition_inside_flight"
            )
        }
        return transitionMutex.withLock {
            withContext(NonCancellable) {
                val flights = synchronized(lock) {
                    transitioning = true
                    generation += 1L
                    activeFlights.values.toList()
                }
                try {
                    val cause = Task126BusinessDataScopeChangedException(
                        "business_data_scope_quiescing"
                    )
                    flights.forEach { flight -> flight.deferred.cancel(cause) }
                    // Await the coroutine's real finally boundary, not just
                    // Deferred cancellation delivery. See ActiveFlight.
                    flights.map { it.quiesced }.joinAll()
                    block()
                } finally {
                    synchronized(lock) {
                        transitioning = false
                    }
                }
            }
        }
    }

    private suspend fun <T> runRegisteredFlight(
        lease: Task126BusinessDataScopeLease,
        block: suspend () -> T
    ): T = supervisorScope {
        val quiesced = CompletableDeferred<Unit>()
        val flight = async(
            context = Task126BusinessDataScopeLeaseContext(lease),
            start = CoroutineStart.LAZY
        ) {
            try {
                block()
            } finally {
                quiesced.complete(Unit)
            }
        }
        val flightId = synchronized(lock) {
            requireLeaseCurrentLocked(lease)
            nextFlightId.also { id ->
                nextFlightId += 1L
                activeFlights[id] = ActiveFlight(
                    deferred = flight,
                    quiesced = quiesced
                )
            }
        }
        try {
            flight.start()
            val result = flight.await()
            requireLeaseCurrent(lease)
            result
        } catch (cancelled: CancellationException) {
            if (!isLeaseCurrent(lease)) {
                throw Task126BusinessDataScopeChangedException()
            }
            throw cancelled
        } finally {
            synchronized(lock) {
                activeFlights.remove(flightId)
            }
        }
    }

    private fun captureLeaseLocked(
        ownerUserId: String?,
        selectedShop: SelectedShop?
    ): Task126BusinessDataScopeLease =
        captureLeaseForActiveScopeLocked(
            ownerUserId?.let { task126ActiveOwnerStoreScope(it, selectedShop) }
        )

    private fun captureLeaseForActiveScopeLocked(
        activeScope: Task126OwnerStoreScope?
    ): Task126BusinessDataScopeLease {
        if (transitioning) {
            throw Task126BusinessDataScopeChangedException("business_data_scope_transitioning")
        }
        if (state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED) {
            return Task126BusinessDataScopeLease(
                generation = generation,
                boundScope = null,
                unmanaged = true
            )
        }
        if (state.status != Task126BusinessDataScopeStatus.READY) {
            throw Task126BusinessDataScopeChangedException("business_data_scope_not_ready")
        }
        val boundScope = state.boundScope
            ?: throw Task126BusinessDataScopeChangedException("business_data_scope_binding_missing")
        if (activeScope != null) {
            if (
                Task126OwnerStoreGate.validate(boundScope, activeScope) !=
                Task126OwnerStoreGateDecision.Allowed
            ) {
                throw Task126BusinessDataScopeChangedException("business_data_scope_mismatch")
            }
        }
        return Task126BusinessDataScopeLease(
            generation = generation,
            boundScope = boundScope,
            unmanaged = false
        )
    }

    private fun requireLeaseMatches(
        lease: Task126BusinessDataScopeLease,
        ownerUserId: String,
        selectedShop: SelectedShop?
    ) {
        if (lease.unmanaged) return
        val boundScope = lease.boundScope
            ?: throw Task126BusinessDataScopeChangedException("business_data_scope_binding_missing")
        val activeScope = task126ActiveOwnerStoreScope(ownerUserId, selectedShop)
        if (
            Task126OwnerStoreGate.validate(boundScope, activeScope) !=
            Task126OwnerStoreGateDecision.Allowed
        ) {
            throw Task126BusinessDataScopeChangedException("business_data_scope_nested_mismatch")
        }
    }

    private fun requireLeaseCurrent(lease: Task126BusinessDataScopeLease) {
        synchronized(lock) {
            requireLeaseCurrentLocked(lease)
        }
    }

    private fun requireLeaseCurrentLocked(lease: Task126BusinessDataScopeLease) {
        if (!isLeaseCurrentLocked(lease)) {
            throw Task126BusinessDataScopeChangedException()
        }
    }

    private fun isLeaseCurrent(lease: Task126BusinessDataScopeLease): Boolean =
        synchronized(lock) { isLeaseCurrentLocked(lease) }

    private fun isLeaseCurrentLocked(lease: Task126BusinessDataScopeLease): Boolean {
        if (generation != lease.generation) return false
        if (lease.unmanaged) {
            return state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
        }
        if (state.status != Task126BusinessDataScopeStatus.READY) return false
        val current = state.boundScope ?: return false
        val captured = lease.boundScope ?: return false
        return Task126OwnerStoreGate.validate(captured, current) ==
            Task126OwnerStoreGateDecision.Allowed
    }

    private fun boundarySignature(value: Task126BusinessDataScopeState): String {
        val scope = value.boundScope
        return listOf(
            value.status.name,
            scope?.ownerHash.orEmpty(),
            scope?.storeId.orEmpty(),
            scope?.localStoreId.orEmpty(),
            scope?.syncProtocolVersion?.toString().orEmpty(),
            scope?.schemaVersion?.toString().orEmpty(),
            scope?.storeEpoch?.toString().orEmpty()
        ).joinToString("|")
    }
}

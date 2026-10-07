package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val unmanaged: Boolean,
    val localOnly: Boolean = false
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

    suspend fun <T> withLocalBusinessDataScopeFlight(block: suspend () -> T): T =
        withCurrentBusinessDataScopeFlight(block)

    suspend fun requireCloudBusinessDataScope() = requireCurrentBusinessDataScope()

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

/** Observation only: does not grant a business or recovery lease. */
internal data class Task126DiagnosticQuietStamp(val generation: Long)

internal class Task126BusinessDataScopeFlightGate(
    initialState: Task126BusinessDataScopeState
) : Task126BusinessDataScopeRuntimeGuard {
    private val lock = Any()
    private val transitionMutex = Mutex()
    private var state = initialState
    private var generation = 0L
    private var localGeneration = 0L
    private var nextFlightId = 1L
    private var transitioning = false
    /**
     * Cancellation can resume a Deferred awaiter before the flight and its
     * children finish `finally`/`NonCancellable` work. A scope transition and
     * quiet observation must wait for actual completion, including cancellation
     * before a lazy flight starts.
     */
    private data class ActiveFlight(
        val deferred: Deferred<*>,
        val quiesced: CompletableDeferred<Unit>,
        val lease: Task126BusinessDataScopeLease
    )

    private val activeFlights = linkedMapOf<Long, ActiveFlight>()

    internal fun captureDiagnosticQuietStamp(): Task126DiagnosticQuietStamp? =
        synchronized(lock) {
            if (transitioning || activeFlights.isNotEmpty()) null
            else Task126DiagnosticQuietStamp(generation)
        }

    internal fun isDiagnosticQuietStampCurrent(stamp: Task126DiagnosticQuietStamp): Boolean =
        synchronized(lock) {
            !transitioning && activeFlights.isEmpty() && generation == stamp.generation
        }

    /** Requalify the same local generation without cancelling its admitted local writers.
     * Real scope changes/activation still use the strong transition below. Both epochs
     * fence authority ABA while the repository read suspends.
     */
    internal suspend fun publishIfScopeUnchanged(
        stillAuthorized: () -> Boolean,
        resolve: suspend (Task126BusinessDataScopeState) -> Task126BusinessDataScopeState,
        publish: (Task126BusinessDataScopeState) -> Unit
    ): Boolean {
        check(currentCoroutineContext()[Task126BusinessDataScopeLeaseContext] == null)
        return transitionMutex.withLock {
            val captured = synchronized(lock) { Triple(generation, localGeneration, state) }
            if (!stillAuthorized()) return@withLock false
            val next = resolve(captured.third)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (generation != captured.first || localGeneration != captured.second ||
                    state != captured.third || !stillAuthorized()) false
                else {
                    publish(next)
                    true
                }
            }
        }
    }

    fun updateState(next: Task126BusinessDataScopeState) {
        val toCancel = synchronized(lock) {
            val boundaryChanged = boundarySignature(state) != boundarySignature(next)
            val localChanged = localBoundarySignature(state) != localBoundarySignature(next)
            state = next
            if (boundaryChanged) generation += 1L
            if (localChanged) localGeneration += 1L
            activeFlights.values.filter { !isLeaseCurrentLocked(it.lease) }.map { it.deferred }
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
            if (inherited.localOnly) throw Task126BusinessDataScopeChangedException("cloud_scope_lease_required")
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

    override suspend fun <T> withLocalBusinessDataScopeFlight(block: suspend () -> T): T {
        val inherited = currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease
        if (inherited != null) {
            synchronized(lock) {
                if (!state.localWritesAllowed) throw Task126BusinessDataScopeChangedException("local_business_write_denied")
                requireLeaseCurrentLocked(inherited)
            }
            return block()
        }
        val lease = synchronized(lock) {
            if (transitioning || !state.allowsLocalOperations || !state.localWritesAllowed) {
                throw Task126BusinessDataScopeChangedException("local_business_scope_unavailable")
            }
            if (state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED) {
                Task126BusinessDataScopeLease(localGeneration, null, unmanaged = true, localOnly = true)
            } else {
                val scope = state.localAccessScope ?: state.boundScope
                    ?: throw Task126BusinessDataScopeChangedException("local_business_scope_binding_missing")
                Task126BusinessDataScopeLease(localGeneration, scope, unmanaged = false, localOnly = true)
            }
        }
        return runRegisteredFlight(lease, block)
    }

    override suspend fun requireCloudBusinessDataScope() {
        val lease = currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease
            ?: throw Task126BusinessDataScopeChangedException("business_data_scope_lease_missing")
        requireLeaseCurrent(lease)
        if (lease.localOnly) throw Task126BusinessDataScopeChangedException("cloud_scope_lease_required")
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
                    localGeneration += 1L
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
            block()
        }
        flight.invokeOnCompletion { quiesced.complete(Unit) }
        val flightId = synchronized(lock) {
            requireLeaseCurrentLocked(lease)
            nextFlightId.also { id ->
                nextFlightId += 1L
                activeFlights[id] = ActiveFlight(
                    deferred = flight,
                    quiesced = quiesced,
                    lease = lease
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
            withContext(NonCancellable) {
                flight.join()
                synchronized(lock) {
                    activeFlights.remove(flightId)
                }
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
        if ((if (lease.localOnly) localGeneration else generation) != lease.generation) return false
        if (lease.unmanaged) {
            return state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
        }
        if (lease.localOnly) {
            if (!state.allowsLocalOperations || !state.localWritesAllowed) return false
        } else if (state.status != Task126BusinessDataScopeStatus.READY) return false
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

    private fun localBoundarySignature(value: Task126BusinessDataScopeState): String =
        boundarySignature(value.copy(
            status = if (value.allowsLocalOperations) Task126BusinessDataScopeStatus.READY
                else Task126BusinessDataScopeStatus.CHECKING,
            boundScope = value.localAccessScope ?: value.boundScope
        )) + ":${value.localWritesAllowed}:${value.localReadsAllowed}"
}

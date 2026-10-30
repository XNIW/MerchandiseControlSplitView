package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** RAM presentation identity only. It never admits a read, write or cloud operation. */
internal data class LocalBusinessPresentationKey(
    val owner: String, val authGeneration: Long?, val store: String, val localStore: String,
    val protocol: Int, val schema: Int, val epoch: Int
)

internal fun localBusinessPresentationKey(
    auth: AuthState, identity: LocalAuthIdentity?, identityCurrent: Boolean,
    shop: ShopContext, state: Task126BusinessDataScopeState
): LocalBusinessPresentationKey? {
    if (state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED && state.boundScope == null)
        return LocalBusinessPresentationKey("unmanaged", null, "", "", 0, 0, 0)
    if (state.status !in setOf(Task126BusinessDataScopeStatus.READY, Task126BusinessDataScopeStatus.CHECKING,
            Task126BusinessDataScopeStatus.ERROR_RECOVERABLE)) return null
    val owner = when (auth) {
        is AuthState.SignedIn -> auth.userId.takeIf { identity == null || (identityCurrent && identity.userId == it) }
        AuthState.Checking -> identity?.takeIf { identityCurrent }?.userId
        else -> null
    }?.takeIf(String::isNotBlank) ?: return null
    if (shop.ownerUserId != owner || !shop.localAccessAllowed) return null
    val bound = state.boundScope ?: return null
    if (Task126OwnerStoreGate.validate(bound, task126ActiveOwnerStoreScope(owner, shop.selectedShop)) !=
        Task126OwnerStoreGateDecision.Allowed) return null
    return LocalBusinessPresentationKey(owner, identity?.authGeneration, bound.storeId, bound.localStoreId,
        bound.syncProtocolVersion, bound.schemaVersion, bound.storeEpoch)
}

internal data class LocalBusinessReadSnapshot(
    val key: LocalBusinessPresentationKey?, val state: Task126BusinessDataScopeState?,
    val stamp: Task126LocalPresentationStamp?, val allowed: Boolean
)

/** Cancels DAO subscriptions on withdrawal; a retained snapshot is presentation only. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LocalBusinessReadGate private constructor(
    private val changes: Flow<Unit>, private val readSnapshot: () -> LocalBusinessReadSnapshot,
    private val scopeGuard: Task126BusinessDataScopeRuntimeGuard? = null
) {
    constructor(application: Application) : this(
        (application as? MerchandiseControlApplication)?.let { app ->
            combine(app.authManager.state, app.authManager.localIdentity, app.shopContextRepository.state,
                app.catalogSyncStateTracker.businessDataScopeState,
                app.catalogSyncStateTracker.localPresentationChanges) { _, _, _, _, _ -> Unit }
        } ?: flowOf(Unit),
        {
            val app = application as? MerchandiseControlApplication
            if (app == null) LocalBusinessReadSnapshot(null, null, null, true) // Neutral unit Application only.
            else {
                val auth = app.authManager.state.value
                val identity = app.authManager.localIdentity.value
                val current = identity?.let(app.authManager::isCurrentLocalIdentity) == true
                val shop = app.shopContextRepository.state.value
                val state = app.catalogSyncStateTracker.businessDataScopeState.value
                val key = localBusinessPresentationKey(auth, identity, current, shop, state)
                val stamp = app.catalogSyncStateTracker.captureLocalPresentationStamp(state)
                val allowed = state.allowsLocalOperations && stamp != null &&
                    (state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED ||
                        qualifiedLocalBusinessOwner(auth, identity, current, shop, state) != null)
                LocalBusinessReadSnapshot(key, state, stamp, allowed)
            }
        },
        (application as? MerchandiseControlApplication)?.catalogSyncStateTracker
    )

    internal constructor(states: StateFlow<LocalBusinessReadSnapshot>) : this(states.map { Unit }, { states.value })
    fun snapshot(): LocalBusinessReadSnapshot = readSnapshot()
    fun isCurrent(expected: LocalBusinessReadSnapshot): Boolean = expected.allowed && readSnapshot() == expected

    /** One-shot callbacks keep their original stamp across dispatch, reads and writes. */
    suspend fun <T> runIfCurrent(expected: LocalBusinessReadSnapshot, block: suspend () -> T): T? {
        if (!isCurrent(expected)) return null
        val qualified: suspend () -> T? = {
            if (!isCurrent(expected)) null
            else {
                val result = block()
                if (isCurrent(expected)) result else null
            }
        }
        return if (scopeGuard != null) scopeGuard.withLocalBusinessDataScopeFlight(qualified) else qualified()
    }

    fun <T> observe(empty: T, source: () -> Flow<T>): Flow<T> = flow {
        var lastAdmittedKey: LocalBusinessPresentationKey? = null
        var admittedOnce = false
        emitAll(changes.onStart { emit(Unit) }.map { readSnapshot() }.distinctUntilChanged().flatMapLatest { expected ->
            if (!expected.allowed) {
                if (!admittedOnce || expected.key == null || expected.key != lastAdmittedKey) flowOf(empty)
                else emptyFlow() // Keep only the last value for the same hidden RAM presentation.
            } else flow admitted@ {
                if (!isCurrent(expected)) return@admitted
                if (admittedOnce && lastAdmittedKey != expected.key) emit(empty)
                admittedOnce = true
                lastAdmittedKey = expected.key
                source().collect { value -> if (isCurrent(expected)) emit(value) }
            }
        }.buffer(0))
    }

    /** A previously emitted PagingData can still request loads after its flow is cancelled. */
    fun <Key : Any, Value : Any> pagingSource(
        expected: LocalBusinessReadSnapshot, source: () -> PagingSource<Key, Value>
    ): PagingSource<Key, Value> = object : PagingSource<Key, Value>() {
        private val delegate: PagingSource<Key, Value>? = if (isCurrent(expected)) source() else null
        init {
            registerInvalidatedCallback { delegate?.invalidate() }
            delegate?.registerInvalidatedCallback { invalidate() }
        }
        override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> {
            if (!isCurrent(expected)) return LoadResult.Error(LocalReadUnavailable())
            val actual = delegate ?: return LoadResult.Error(LocalReadUnavailable())
            if (!isCurrent(expected)) return LoadResult.Error(LocalReadUnavailable())
            val result = actual.load(params)
            return if (isCurrent(expected)) result else LoadResult.Error(LocalReadUnavailable())
        }
        override fun getRefreshKey(state: PagingState<Key, Value>): Key? = delegate?.getRefreshKey(state)
        override val jumpingSupported: Boolean get() = delegate?.jumpingSupported ?: false
        override val keyReuseSupported: Boolean get() = delegate?.keyReuseSupported ?: false
    }

    private class LocalReadUnavailable : IllegalStateException("Local business read is not admitted")
}

package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LocalBusinessReadGateTest {
    private val key = LocalBusinessPresentationKey("owner", 1, "shop:a", "local-shop:a", 126, 2, 1)
    private fun ready(generation: Long = 1, key: LocalBusinessPresentationKey? = this.key) =
        LocalBusinessReadSnapshot(key, Task126BusinessDataScopeState.unmanagedAllowed(),
            Task126LocalPresentationStamp(generation), true)
    private fun paused(key: LocalBusinessPresentationKey? = this.key) =
        LocalBusinessReadSnapshot(key, Task126BusinessDataScopeState.checking(), null, false)

    @Test fun coldDeniedConsumerDoesNotConstructOrSubscribeToDaoFlow() = runTest {
        val state = MutableStateFlow(paused())
        val gate = LocalBusinessReadGate(state)
        var constructed = 0
        val values = mutableListOf<List<String>>()
        backgroundScope.launch { gate.observe(emptyList<String>()) {
            constructed++; flow { emit(listOf("private")) }
        }.collect { values += it } }
        runCurrent()
        assertEquals(0, constructed)
        assertEquals(listOf(emptyList<String>()), values)
    }

    @Test fun sameIdentityWithdrawalCancelsDaoKeepsLastValueAndResubscribesOnlyAfterAdmission() = runTest {
        val state = MutableStateFlow(ready())
        val gate = LocalBusinessReadGate(state)
        val source = MutableStateFlow(listOf("last qualified row"))
        val values = mutableListOf<List<String>>()
        var subscriptions = 0; var cancellations = 0
        backgroundScope.launch { gate.observe(emptyList<String>()) {
            flow {
                subscriptions++
                try { source.collect { emit(it) } } finally { cancellations++ }
            }
        }.collect { values += it } }
        runCurrent()
        state.value = paused(); runCurrent()
        assertEquals(1, subscriptions); assertEquals(1, cancellations)
        source.value = listOf("not yet admitted"); runCurrent()
        assertEquals(listOf(listOf("last qualified row")), values)
        state.value = ready(generation = 2); runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(listOf("not yet admitted"), values.last())
    }

    @Test fun ownerShopAuthGenerationOrRevocationClearsRetainedValueWithoutNewDaoRead() = runTest {
        for (changed in listOf(key.copy(owner = "other"), key.copy(store = "shop:b"),
            key.copy(authGeneration = 2), null)) {
            val state = MutableStateFlow(ready())
            val gate = LocalBusinessReadGate(state)
            var reads = 0
            val values = mutableListOf<List<String>>()
            val job = backgroundScope.launch { gate.observe(emptyList<String>()) {
                flow { reads++; emit(listOf("scope A")); awaitCancellation() }
            }.collect { values += it } }
            runCurrent(); state.value = paused(changed); runCurrent()
            assertEquals(1, reads)
            assertEquals(emptyList<String>(), values.last())
            job.cancel(); runCurrent()
        }
    }

    @Test fun delayedOldPublicationCannotCrossGenerationFence() = runTest {
        val state = MutableStateFlow(ready())
        val gate = LocalBusinessReadGate(state)
        val values = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        backgroundScope.launch { gate.observe("") {
            flow {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                emit("late old result")
            }
        }.collect { values += it } }
        runCurrent(); assertTrue(started.isCompleted)
        state.value = paused(); runCurrent()
        release.complete(Unit); runCurrent()
        assertFalse(values.contains("late old result"))
    }

    @Test fun pagingDenialDoesNotConstructDelegateOrExecuteLoad() = runTest {
        val state = MutableStateFlow(paused())
        val gate = LocalBusinessReadGate(state)
        var factories = 0
        val source = gate.pagingSource<Int, String>(state.value) {
            factories++; error("No DAO PagingSource may be constructed")
        }
        val result = source.load(PagingSource.LoadParams.Refresh(null, 20, true))
        assertTrue(result is PagingSource.LoadResult.Error)
        assertEquals(0, factories)
    }

    @Test fun existingPagingDataCannotLoadAfterWithdrawalAndFreshGenerationDelegatesNormally() = runTest {
        val state = MutableStateFlow(ready())
        val gate = LocalBusinessReadGate(state)
        var loads = 0
        val delegate = testPagingSource { loads++; PagingSource.LoadResult.Page(listOf("row"), null, null) }
        val old = gate.pagingSource(state.value) { delegate }
        assertTrue(old.jumpingSupported); assertTrue(old.keyReuseSupported)
        assertTrue(old.load(PagingSource.LoadParams.Refresh(null, 20, true)) is PagingSource.LoadResult.Page)
        state.value = paused()
        assertTrue(old.load(PagingSource.LoadParams.Append(20, 20, true)) is PagingSource.LoadResult.Error)
        assertEquals(1, loads)
        state.value = ready(generation = 2)
        assertTrue(old.load(PagingSource.LoadParams.Refresh(null, 20, true)) is PagingSource.LoadResult.Error)
        val fresh = gate.pagingSource(state.value) {
            testPagingSource { loads++; PagingSource.LoadResult.Page(listOf("new row"), null, null) }
        }
        assertTrue(fresh.load(PagingSource.LoadParams.Refresh(null, 20, true)) is PagingSource.LoadResult.Page)
        assertEquals(2, loads)
    }

    @Test fun pagingResultHeldAcrossGenerationChangeIsDiscardedAfterRealLoadReturns() = runTest {
        val state = MutableStateFlow(ready())
        val gate = LocalBusinessReadGate(state)
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val source = gate.pagingSource(state.value) { testPagingSource {
            loads++; withContext(NonCancellable) { release.await() }
            PagingSource.LoadResult.Page(listOf("old body"), null, null)
        } }
        val result = async { source.load(PagingSource.LoadParams.Refresh(null, 20, true)) }
        runCurrent(); assertEquals(1, loads)
        state.value = ready(generation = 2)
        release.complete(Unit)
        assertTrue(result.await() is PagingSource.LoadResult.Error)
    }

    @Test fun pagingInvalidationPropagatesBothWays() {
        val state = MutableStateFlow(ready())
        val gate = LocalBusinessReadGate(state)
        val delegate = testPagingSource { error("No load") }
        val wrapped = gate.pagingSource(state.value) { delegate }
        delegate.invalidate(); assertTrue(wrapped.invalid)
        val second = testPagingSource { error("No load") }
        gate.pagingSource(state.value) { second }.invalidate()
        assertTrue(second.invalid)
    }

    @Test fun realTrackerDataEqualTransitionCancelsReadAndReopensOnlyAfterRelease() = runTest {
        val fixture = managedFixture()
        val gate = LocalBusinessReadGate(fixture.application)
        val initialState = fixture.tracker.businessDataScopeState.value
        val initial = gate.snapshot()
        assertTrue(initial.allowed)
        var subscriptions = 0
        var cancellations = 0
        backgroundScope.launch {
            gate.observe("") { flow {
                subscriptions++
                try { emit("qualified body"); awaitCancellation() } finally { cancellations++ }
            } }.collect { }
        }
        runCurrent()
        assertEquals(1, subscriptions)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transition = async {
            fixture.tracker.withBusinessDataScopeTransition { entered.complete(Unit); release.await() }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(initialState, fixture.tracker.businessDataScopeState.value)
        assertFalse(gate.snapshot().allowed)
        assertFalse(gate.isCurrent(initial))
        assertEquals(1, cancellations)
        assertEquals(1, subscriptions)
        val unavailableEpoch = fixture.tracker.localPresentationChanges.value
        release.complete(Unit)
        transition.await()
        runCurrent()
        val reopened = gate.snapshot()
        assertEquals(initialState, fixture.tracker.businessDataScopeState.value)
        assertTrue(reopened.allowed)
        assertNotEquals(initial.stamp, reopened.stamp)
        assertTrue(fixture.tracker.localPresentationChanges.value > unavailableEpoch)
        assertEquals(2, subscriptions)
    }

    @Test fun realTrackerReleaseDoesNotReopenRevokedOrForeignIdentity() = runTest {
        for (revoke in listOf(true, false)) {
            val fixture = managedFixture()
            val gate = LocalBusinessReadGate(fixture.application)
            var subscriptions = 0
            var activeSources = 0
            val values = mutableListOf<String>()
            val collector = backgroundScope.launch {
                gate.observe("") { flow {
                    subscriptions++; activeSources++
                    try { emit("owner A"); awaitCancellation() } finally { activeSources-- }
                } }.collect { values += it }
            }
            runCurrent()
            assertEquals(1, activeSources)
            val release = CompletableDeferred<Unit>()
            val transition = async { fixture.tracker.withBusinessDataScopeTransition { release.await() } }
            runCurrent()
            assertEquals(0, activeSources)
            if (revoke) fixture.identity.value = null
            else fixture.shop.value = fixture.shop.value.copy(
                selectedShop = fixture.shop.value.selectedShop!!.copy(shopId = "other-shop"))
            runCurrent()
            release.complete(Unit); transition.await(); runCurrent()
            assertFalse(gate.snapshot().allowed)
            assertEquals(1, subscriptions)
            assertEquals(0, activeSources)
            assertEquals("", values.last())
            collector.cancel(); runCurrent()
        }
    }

    @Test fun oneShotNeverReacquiresAuthorityForOldStampOrDeniedEntry() = runTest {
        val fixture = managedFixture()
        val gate = LocalBusinessReadGate(fixture.application)
        val old = gate.snapshot()
        fixture.tracker.withBusinessDataScopeTransition { }
        var actions = 0
        assertNull(gate.runIfCurrent(old) { actions++; "old" })
        fixture.identity.value = null
        assertNull(gate.runIfCurrent(gate.snapshot()) { actions++; "denied" })
        assertEquals(0, actions)
    }

    @Test fun oneShotUsesRealLeaseAndDoesNotPublishSuccessAfterCancellation() = runTest {
        val fixture = managedFixture()
        val gate = LocalBusinessReadGate(fixture.application)
        val initial = gate.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val operation = async {
            runCatching { gate.runIfCurrent(initial) {
                fixture.tracker.requireCurrentBusinessDataScope()
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (gate.isCurrent(initial)) writes++
                "must not be success"
            } }
        }
        runCurrent(); assertTrue(entered.isCompleted)
        val transition = async { fixture.tracker.withBusinessDataScopeTransition { } }
        runCurrent(); assertFalse(transition.isCompleted)
        release.complete(Unit)
        transition.await()
        assertTrue(operation.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, writes)
    }

    private data class ManagedFixture(
        val application: MerchandiseControlApplication,
        val tracker: CatalogSyncStateTracker,
        val identity: MutableStateFlow<LocalAuthIdentity?>,
        val shop: MutableStateFlow<ShopContext>
    )

    private fun managedFixture(): ManagedFixture {
        val owner = "local-read-owner"
        val selected = SelectedShop("local-read-shop", null, "Local shop", "owner", "active", true)
        val identity = MutableStateFlow<LocalAuthIdentity?>(LocalAuthIdentity(owner, 1))
        val auth = MutableStateFlow<AuthState>(AuthState.Checking)
        val shop = MutableStateFlow(ShopContext(owner, emptyList(), selected,
            syncAllowed = false, localAccessAllowed = true))
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(
            task126ActiveOwnerStoreScope(owner, selected)))
        val manager = mockk<SupabaseAuthManager>()
        every { manager.state } returns auth
        every { manager.localIdentity } returns identity
        every { manager.isCurrentLocalIdentity(any()) } answers { firstArg<LocalAuthIdentity>() == identity.value }
        val shops = mockk<ShopContextRepository>()
        every { shops.state } returns shop
        val app = mockk<MerchandiseControlApplication>()
        every { app.authManager } returns manager
        every { app.shopContextRepository } returns shops
        every { app.catalogSyncStateTracker } returns tracker
        return ManagedFixture(app, tracker, identity, shop)
    }

    private fun testPagingSource(load: suspend () -> PagingSource.LoadResult<Int, String>) =
        object : PagingSource<Int, String>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> = load()
            override fun getRefreshKey(state: PagingState<Int, String>): Int? = state.anchorPosition
            override val jumpingSupported = true
            override val keyReuseSupported = true
        }
}

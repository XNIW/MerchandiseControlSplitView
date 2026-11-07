package com.example.merchandisecontrolsplitview.pos

import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Pure owner tests: no manifest Application, DB, HTTP or native acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class PosSalesCoordinatorTest {
    @Test fun unknownAuthorityExposesNoAmountsAndStartsNoTransport() = runTest {
        val f = fixture()
        f.coordinator.environment(null, true, true, PosRoute.OVERVIEW, true); runCurrent()
        assertTrue(f.transport.calls.isEmpty()); assertNull(f.coordinator.state.value.overview)
        assertEquals(PosAccess.VERIFYING, f.coordinator.state.value.access)
    }
    @Test fun overviewPublishesAuthoritativeSummaryAndFirstPageAtomically() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        assertNull(f.coordinator.state.value.overview)
        val day = day(net = -91)
        f.transport.calls.single().succeed(data = PosReadData.Overview(listOf(day), listOf(sale(1, 900)), null)); runCurrent()
        val actual = requireNotNull(f.coordinator.state.value.overview)
        assertEquals(-91L, actual.summaryDays.single().netRevenueClp)
        assertEquals(900L, actual.sales.single().netAmountClp) // Never derive summary from the latest list.
    }
    @Test fun emptyRequiresAnAuthorizedEnvelope() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        assertEquals(PosAccess.LOADING, f.coordinator.state.value.access)
        f.transport.calls.single().succeed(); runCurrent()
        assertEquals(PosAccess.AVAILABLE, f.coordinator.state.value.access)
        assertTrue(requireNotNull(f.coordinator.state.value.overview).sales.isEmpty())
    }
    @Test fun offlinePurgesFinancialDataMetadataSelectionAndDoesNotRenewFetchedTime() = runTest {
        val f = fixture(); f.ready(listOf(sale(1))); val epoch = f.coordinator.state.value.presentationEpoch
        f.coordinator.openDetail(id(1)); runCurrent()
        f.coordinator.environment(f.current, false, true, PosRoute.DETAIL, true); runCurrent()
        val actual = f.coordinator.state.value
        assertEquals(PosAccess.OFFLINE, actual.access); assertNull(actual.overview); assertNull(actual.history)
        assertNull(actual.detail); assertNull(actual.filterOptions); assertNull(actual.selectedSaleId)
        assertFalse(actual.metadataRead); assertTrue(actual.presentationEpoch > epoch)
    }
    @Test fun stableDenialPurgesAndStopsPolling() = runTest {
        val f = fixture(); f.ready(listOf(sale(1)))
        advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().deny("device_not_authorized"); runCurrent()
        assertEquals(PosAccess.DENIED, f.coordinator.state.value.access)
        assertNull(f.coordinator.state.value.overview)
        val before = f.transport.calls.size; advanceTimeBy(30_000); runCurrent()
        assertEquals(before, f.transport.calls.size)
    }
    @Test fun repeatedEnvironmentWithSameDeniedScopeDoesNotRestartTheReader() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        f.transport.calls.single().deny("permission_denied"); runCurrent()
        repeat(4) { f.enter(); runCurrent() }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, f.transport.calls.size)
        assertEquals(PosAccess.DENIED, f.coordinator.state.value.access)
        f.coordinator.retry(); runCurrent(); assertEquals(2, f.transport.calls.size)
    }
    @Test fun queuedCancellationCannotSkipTheFinallyOwnedTerminalDrain() = runTest {
        val f = fixture()
        f.transport.onStart = { call ->
            if (f.transport.calls.size == 1) backgroundScope.launch {
                f.current = f.current.copy(authGeneration = 2)
                f.coordinator.environment(f.current, true, true, PosRoute.OVERVIEW, true)
                call.terminal.complete(Unit)
            }
        }
        f.enter(); runCurrent()
        assertEquals(1, f.transport.calls.size)
        assertTrue(f.coordinator.observations.value.single().drainedWithinOneSecond)
        assertNull(f.coordinator.state.value.overview)
        advanceTimeBy(2_000); runCurrent(); assertEquals(2, f.transport.calls.size)
    }
    @Test fun viewerAuthorizationDoesNotEnableOperationalMetadataFilters() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        f.transport.calls.single().succeed(metadata = false); runCurrent()
        f.coordinator.openHistory(PERIOD, PosFilters(staffId = id(55))); runCurrent()
        assertFalse(f.coordinator.state.value.metadataRead)
        assertEquals(1, f.transport.calls.size)
    }
    @Test fun principalMismatchCannotPublishEvenAWellFormedFinancialDto() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        val call = f.transport.calls.single()
        call.succeed(serverPrincipal = id(999), data = PosReadData.Overview(listOf(day()), listOf(sale(1)), null)); runCurrent()
        assertEquals(PosAccess.DENIED, f.coordinator.state.value.access)
        assertNull(f.coordinator.state.value.overview)
    }
    @Test fun authGenerationSwitchFencesTheLatePriorResponse() = runTest {
        val f = fixture(); f.enter(); runCurrent(); val old = f.transport.calls.single()
        f.current = f.current.copy(authGeneration = 2)
        f.coordinator.environment(f.current, true, true, PosRoute.OVERVIEW, true)
        old.succeed(data = PosReadData.Overview(listOf(day()), listOf(sale(1)), null)); runCurrent()
        assertNull(f.coordinator.state.value.overview)
        advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(data = PosReadData.Overview(listOf(day()), listOf(sale(2)), null)); runCurrent()
        assertEquals(id(2), f.coordinator.state.value.overview!!.sales.single().id)
    }
    @Test fun callbackAfterScopePredicateRevocationCannotPublish() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        f.current = f.current.copy(deviceEpoch = 88)
        f.transport.calls.single().succeed(data = PosReadData.Overview(listOf(day()), listOf(sale(1)), null)); runCurrent()
        assertNull(f.coordinator.state.value.overview)
    }
    @Test fun pollingHasMinimumTwoSecondStartsAndNoCatchUpWhileLive() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        advanceTimeBy(1_999); runCurrent(); assertEquals(1, f.transport.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(1, f.transport.calls.size) // Live transport owns the lane.
        assertEquals(1, f.transport.calls.first().cancels)
        f.transport.calls.first().terminal.complete(Unit); runCurrent()
        advanceTimeBy(3_999); runCurrent(); assertEquals(1, f.transport.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, f.transport.calls.size)
    }
    @Test fun timeoutDoesNotReleaseTheLaneUntilActualTransportTerminal() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        advanceTimeBy(3_001); runCurrent()
        assertEquals(PosAccess.STALLED, f.coordinator.state.value.access)
        assertEquals(1, f.transport.calls.size)
        f.coordinator.retry(); runCurrent(); assertEquals(1, f.transport.calls.size)
        assertFalse(f.coordinator.observations.value.single().drainedWithinOneSecond)
        f.transport.calls.single().terminal.complete(Unit); runCurrent()
        assertEquals(1, f.transport.calls.size) // Late terminal alone never silently restarts.
        f.coordinator.retry(); runCurrent(); assertEquals(2, f.transport.calls.size)
    }
    @Test fun lateSuccessBeforeTerminalCannotExtendTheOverviewDeadline() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        advanceTimeBy(2_100); runCurrent()
        f.transport.calls.single().succeed(data = PosReadData.Overview(listOf(day()), listOf(sale(1)), null)); runCurrent()
        assertNull(f.coordinator.state.value.overview)
        assertEquals("pos_timeout", f.coordinator.state.value.errorCode)
    }
    @Test fun responseAtOnePointNineSecondsStillCancelsAtTwoIfTransportIsNotTerminal() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        advanceTimeBy(1_900); runCurrent()
        f.transport.calls.single().succeed(terminalNow = false); runCurrent()
        advanceTimeBy(99); runCurrent(); assertEquals(0, f.transport.calls.single().cancels)
        advanceTimeBy(1); runCurrent(); assertEquals(1, f.transport.calls.single().cancels)
        advanceTimeBy(999); runCurrent()
        f.transport.calls.single().terminal.complete(Unit); runCurrent()
        assertNull(f.coordinator.state.value.overview)
        assertEquals("pos_timeout", f.coordinator.state.value.errorCode)
        assertTrue(f.coordinator.observations.value.single().drainedWithinOneSecond)
    }
    @Test fun transientFailureKeepsOnlyPreviouslyAuthorizedCopyAndItsOriginalFetchedTime() = runTest {
        val f = fixture(); f.ready(listOf(sale(1))); val before = f.coordinator.state.value.overview
        advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().fail(IOException("synthetic")); runCurrent()
        assertSame(before, f.coordinator.state.value.overview)
        assertEquals(PosAccess.AVAILABLE, f.coordinator.state.value.access)
        assertNotNull(f.coordinator.state.value.errorCode)
        advanceTimeBy(3_999); runCurrent(); assertEquals(2, f.transport.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(3, f.transport.calls.size)
    }
    @Test fun retryAfterTakesPrecedenceOverTheShorterBackoff() = runTest {
        val f = fixture(); f.enter(); runCurrent()
        f.transport.calls.single().fail(PosHttpException(429, 10)); runCurrent()
        f.coordinator.retry(); runCurrent() // User retry does not bypass the server's Retry-After.
        advanceTimeBy(9_999); runCurrent(); assertEquals(1, f.transport.calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, f.transport.calls.size)
    }
    @Test fun historyAndDetailsAreNotPolledEveryTwoSeconds() = runTest {
        val f = fixture(); f.ready(); f.coordinator.openHistory(PERIOD); advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(data = PosReadData.Overview(listOf(day()), listOf(sale(1)), null)); runCurrent()
        val before = f.transport.calls.size; advanceTimeBy(20_000); runCurrent()
        assertEquals(before, f.transport.calls.size)
    }
    @Test fun passiveHistoryRefreshStagesDataWithoutChangingTheVisibleAnchor() = runTest {
        val f = fixture(); f.history(listOf(sale(1)), cursor(1))
        f.coordinator.rememberAnchor(id(1)); val before = f.coordinator.state.value.history
        f.coordinator.environment(f.current, true, true, PosRoute.HISTORY, true)
        advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(data = PosReadData.Overview(listOf(day(net = 42)), listOf(sale(2)), null)); runCurrent()
        assertSame(before, f.coordinator.state.value.history)
        assertEquals(id(1), f.coordinator.state.value.anchorSaleId)
        assertTrue(f.coordinator.state.value.newDataAvailable)
    }
    @Test fun deepPageDeduplicatesIdsAndAdvancesOnlyTheMatchingCursor() = runTest {
        val f = fixture(); f.history(listOf(sale(1)), cursor(1))
        f.coordinator.loadMore(); advanceTimeBy(2_000); runCurrent()
        assertEquals(cursor(1), f.transport.calls.last().request.cursor)
        f.transport.calls.last().succeed(data = PosReadData.Page(listOf(sale(1), sale(2)), cursor(2))); runCurrent()
        assertEquals(listOf(id(1), id(2)), f.coordinator.state.value.history!!.sales.map { it.id })
        assertEquals(cursor(2), f.coordinator.state.value.history!!.nextCursor)
    }
    @Test fun filterChangeDiscardsLateOldPageAndItsCursor() = runTest {
        val f = fixture(); f.history(listOf(sale(1)), cursor(1))
        f.coordinator.loadMore(); advanceTimeBy(2_000); runCurrent(); val old = f.transport.calls.last()
        f.coordinator.openHistory(PERIOD, PosFilters(status = PosSaleStatus.REJECTED))
        old.succeed(data = PosReadData.Page(listOf(sale(99)), cursor(99))); runCurrent()
        assertNull(f.coordinator.state.value.history)
        advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(); runCurrent()
        assertEquals(PosSaleStatus.REJECTED, f.coordinator.state.value.history!!.filters.status)
        assertTrue(f.coordinator.state.value.history!!.sales.isEmpty())
    }
    @Test fun explicitRefreshRestoresAnchorIfItExistsInTheNewBoundedChain() = runTest {
        val f = fixture(); f.history(listOf(sale(1), sale(2)), null)
        f.coordinator.rememberAnchor(id(2))
        f.coordinator.environment(f.current, true, true, PosRoute.HISTORY, true)
        advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(data = PosReadData.Overview(listOf(day(net = 99)), listOf(sale(3)), cursor(3))); runCurrent()
        f.coordinator.refreshHistory(); advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(data = PosReadData.Page(listOf(sale(2)), null)); runCurrent()
        assertEquals(id(2), f.coordinator.state.value.anchorSaleId)
        assertFalse(f.coordinator.state.value.anchorRelocated)
        assertEquals(99L, f.coordinator.state.value.history!!.summaryDays.single().netRevenueClp)
    }
    @Test fun detailIsReadOnEachOpenAndReleasedOnClose() = runTest {
        val f = fixture(); f.history(listOf(sale(1)), null)
        f.coordinator.openDetail(id(1)); advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().succeed(data = PosReadData.Detail(sale(1), emptyList())); runCurrent()
        assertNotNull(f.coordinator.state.value.detail)
        f.coordinator.closeDetail(); assertNull(f.coordinator.state.value.detail)
        f.coordinator.openDetail(id(1)); advanceTimeBy(2_000); runCurrent()
        assertEquals(PosReadAction.DETAIL, f.transport.calls.last().request.action)
        assertNull(f.coordinator.state.value.detail)
    }
    @Test fun cacheEvictsAtFiveKeysAndAtFiveHundredSales() {
        val cache = PosMemoryCache()
        repeat(6) { index -> assertTrue(cache.put(snapshot(index, listOf(sale(index + 1))))) }
        assertEquals(5, cache.retainedPeriods()); assertNull(cache.get(snapshot(0).period, PosFilters()))
        assertFalse(cache.put(snapshot(7, (1..501).map { sale(it) })))
        assertTrue(cache.retainedSales() <= 500)
    }
    @Test fun cacheRejectsAVisibleSetBeyondFiveMiBWithoutDroppingItsExistingCopy() {
        val cache = PosMemoryCache(); val old = snapshot(1).copy(accountedBytes = 3L * 1024 * 1024)
        assertTrue(cache.put(old))
        assertFalse(cache.put(snapshot(2).copy(accountedBytes = 3L * 1024 * 1024), listOf(old)))
        assertSame(old, cache.get(old.period, old.filters)); assertEquals(1, cache.retainedPeriods())
    }
    @Test fun nativeMoneyFormattingPreservesIntegersAboveTwoToTheFiftyThird() {
        val first = posMoney(9_007_199_254_740_991, Locale.US)
        val next = posMoney(9_007_199_254_740_993, Locale.US)
        assertTrue(first.contains("9,007,199,254,740,991")); assertTrue(next.contains("9,007,199,254,740,993"))
        assertNotEquals(first, next); assertTrue(posMoney(Long.MIN_VALUE, Locale.US).contains("9,223,372,036,854,775,808"))
    }
    @Test fun nativeQuantityFormattingDoesNotTruncateFractionalPrecision() {
        val value = BigDecimal("0.123456789012345678901234567890")
        assertEquals("0.12345678901234567890123456789", posQuantity(value, Locale.US))
    }

    @Test fun first401KeepsReadOwnershipUntilOwnerRefreshCompletesAndSecond401StopsPolling() = runTest {
        val release = CompletableDeferred<Boolean>()
        var refreshes = 0
        val f = fixture { scope -> assertEquals(SCOPE, scope); refreshes++; release.await() }
        f.enter(); runCurrent()
        advanceTimeBy(1_500); runCurrent()
        f.transport.calls.single().fail(PosHttpException(401, null)); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(PosAccess.VERIFYING, f.coordinator.state.value.access)
        advanceTimeBy(500); runCurrent() // The ordinary t=2s tick cannot start during refresh.
        assertEquals(1, f.transport.calls.size)
        advanceTimeBy(250); runCurrent()
        release.complete(true); runCurrent()
        assertEquals(2, f.transport.calls.size)
        assertEquals(SCOPE, f.transport.calls.last().request.scope)
        f.transport.calls.last().fail(PosHttpException(401, null)); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(PosAccess.DENIED, f.coordinator.state.value.access)
        assertNull(f.coordinator.state.value.overview)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, f.transport.calls.size)
    }

    @Test fun queued401RetryKeepsItsOnceFlagWhenTheOverviewTickMeetsTheTwoSecondCadence() = runTest {
        var refreshes = 0
        val f = fixture { refreshes++; true }
        f.enter(); runCurrent()
        advanceTimeBy(250); runCurrent()
        f.transport.calls.single().deny("unauthenticated"); runCurrent()
        assertEquals(1, refreshes)
        advanceTimeBy(1_750); runCurrent() // Poll was scheduled before the delayed retry pump.
        assertEquals(2, f.transport.calls.size)
        f.transport.calls.last().deny("unauthenticated"); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(PosAccess.DENIED, f.coordinator.state.value.access)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, f.transport.calls.size)
    }

    @Test fun scopeReplacementCancelsHeld401RefreshAndNeverRetriesThePriorAuthority() = runTest {
        val release = CompletableDeferred<Boolean>()
        var refreshes = 0
        val f = fixture { refreshes++; release.await() }
        f.enter(); runCurrent()
        f.transport.calls.single().fail(PosHttpException(401, null)); runCurrent()
        f.current = f.current.copy(authGeneration = 2)
        f.enter(); runCurrent()
        release.complete(true); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(2, f.transport.calls.size)
        assertEquals(f.current, f.transport.calls.last().request.scope)
        assertNull(f.coordinator.state.value.overview)
        f.transport.calls.last().succeed(); runCurrent()
        assertEquals(PosAccess.AVAILABLE, f.coordinator.state.value.access)
    }

    @Test fun successful401RefreshRestoresOnlyTheRequestedDetailIdentityBeforeItsAuthorizedRead() = runTest {
        var refreshes = 0
        val f = fixture { refreshes++; true }
        f.ready(listOf(sale(1)))
        f.coordinator.openDetail(id(1)); advanceTimeBy(2_000); runCurrent()
        f.transport.calls.last().fail(PosHttpException(401, null)); runCurrent()
        assertNull(f.coordinator.state.value.detail)
        advanceTimeBy(2_000); runCurrent()
        val retry = f.transport.calls.last()
        assertEquals(3, f.transport.calls.size)
        assertEquals(PosReadAction.DETAIL, retry.request.action)
        assertEquals(id(1), retry.request.saleId)
        retry.succeed(data = PosReadData.Detail(sale(1), emptyList())); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(id(1), f.coordinator.state.value.detail?.sale?.id)
        assertEquals(id(1), f.coordinator.state.value.selectedSaleId)
        f.coordinator.close()
    }

    @Test fun pageAndFilter401RefreshRestartAnAuthorizedFirstEnvelopeWithoutTheRetiredCursor() = runTest {
        for (fromFilter in listOf(false, true)) {
            var refreshes = 0
            val f = fixture { refreshes++; true }
            f.history(listOf(sale(3)), cursor(3))
            if (fromFilter) f.coordinator.openFilters() else f.coordinator.loadMore()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(if (fromFilter) PosReadAction.FILTER else PosReadAction.PAGE,
                f.transport.calls.last().request.action)
            f.transport.calls.last().fail(PosHttpException(401, null)); runCurrent()
            assertNull(f.coordinator.state.value.history)
            advanceTimeBy(2_000); runCurrent()
            val retry = f.transport.calls.last()
            assertEquals(4, f.transport.calls.size)
            assertEquals(PosReadAction.OVERVIEW, retry.request.action)
            assertEquals(PERIOD, retry.request.period)
            assertNull(retry.request.cursor)
            retry.succeed(data = PosReadData.Overview(listOf(day()), listOf(sale(9)), null)); runCurrent()
            assertEquals(1, refreshes)
            assertEquals(PosRoute.HISTORY, f.coordinator.state.value.route)
            assertEquals(listOf(id(9)), f.coordinator.state.value.history?.sales?.map { it.id })
            f.coordinator.close()
        }
    }

    private fun TestScope.fixture(refresh: suspend (PosScope) -> Boolean = { false }) = Fixture(this, refresh)
    private class Fixture(private val test: TestScope, refresh: suspend (PosScope) -> Boolean) {
        var current = SCOPE
        val transport = FakeTransport()
        val coordinator = PosSalesCoordinator(test.backgroundScope, transport, { it == current },
            monotonicMs = { test.testScheduler.currentTime }, jitter = { 0 }, refreshExistingAuthOnce = refresh)
        fun enter() = coordinator.environment(current, true, true, PosRoute.OVERVIEW, true)
        fun ready(sales: List<PosSale> = emptyList()) {
            enter(); test.runCurrent(); transport.calls.single().succeed(data = PosReadData.Overview(listOf(day()), sales, null)); test.runCurrent()
        }
        fun history(sales: List<PosSale>, cursor: PosCursor?) {
            ready(); coordinator.openHistory(PERIOD); test.advanceTimeBy(2_000); test.runCurrent()
            transport.calls.last().succeed(data = PosReadData.Overview(listOf(day()), sales, cursor)); test.runCurrent()
        }
    }
    private class FakeTransport : PosReadTransport {
        val calls = mutableListOf<FakeCall>()
        var onStart: ((FakeCall) -> Unit)? = null
        override fun start(request: PosReadRequest): PosReadCall = FakeCall(request).also {
            calls.add(it); onStart?.invoke(it)
        }
    }
    private class FakeCall(val request: PosReadRequest) : PosReadCall {
        val response = CompletableDeferred<PosEnvelope>()
        val terminal = CompletableDeferred<Unit>()
        var cancels = 0
        override suspend fun await() = response.await()
        override fun cancel() { cancels++ }
        override suspend fun awaitTerminal() { terminal.await() }
        fun succeed(data: PosReadData = PosReadData.Overview(listOf(day()), emptyList(), null),
            metadata: Boolean = true, serverPrincipal: String = request.scope.principalId, terminalNow: Boolean = true) {
            response.complete(PosEnvelope.Authorized(PosServerScope(serverPrincipal, request.scope.shopId, DEVICE),
                PosCapability(true, metadata), request.action,
                if (request.action == PosReadAction.DETAIL) null else request.period ?: PERIOD,
                "CLP", "America/Santiago", TIME, data, 1024)); if (terminalNow) terminal.complete(Unit)
        }
        fun deny(code: String) { response.complete(PosEnvelope.Denied(code, TIME)); terminal.complete(Unit) }
        fun fail(error: Exception) { response.completeExceptionally(error); terminal.complete(Unit) }
    }
    companion object {
        private val PERIOD = PosPeriod("2026-11-05", "2026-11-05")
        private val TIME = PosInstant.parse("2026-11-05T15:00:00.123456Z")
        private const val DEVICE = "30000000-0000-4000-8000-000000000146"
        private val SCOPE = PosScope("10000000-0000-4000-8000-000000000146", "20000000-0000-4000-8000-000000000146", "synthetic-pos", 1, 1, DEVICE)
        private fun id(index: Int) = UUID(0, index.toLong()).toString()
        private fun sale(index: Int, net: Long = 100): PosSale = PosSale(id(index), "SALE-$index", TIME,
            "2026-11-05", PosBusinessKind.SALE, PosSaleStatus.ACCEPTED, "pending", net, 0, net,
            listOf("cash"), null, null, TIME)
        private fun day(net: Long = 100) = PosSummaryDay("2026-11-05", 100, 0, -10, net, 1, 1, 0, 2, TIME)
        private fun cursor(index: Int) = PosCursor(TIME, id(index))
        private fun snapshot(index: Int, sales: List<PosSale> = emptyList()): PosPeriodSnapshot {
            val date = java.time.LocalDate.parse("2026-11-05").minusDays(index.toLong()).toString()
            return PosPeriodSnapshot(PosPeriod(date, date), PosFilters(), "CLP", "America/Santiago",
                emptyList(), sales, null, TIME, 1024)
        }
    }
}

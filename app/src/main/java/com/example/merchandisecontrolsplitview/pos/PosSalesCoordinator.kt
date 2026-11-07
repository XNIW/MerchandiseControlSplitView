package com.example.merchandisecontrolsplitview.pos

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PosRoute { OVERVIEW, HISTORY, DETAIL, FILTER }
internal enum class PosAccess { VERIFYING, LOADING, AVAILABLE, OFFLINE, DENIED, UNAVAILABLE, STALLED }

internal data class PosPeriodSnapshot(
    val period: PosPeriod,
    val filters: PosFilters,
    val currencyCode: String,
    val timeZone: String,
    val summaryDays: List<PosSummaryDay>,
    val sales: List<PosSale>,
    val nextCursor: PosCursor?,
    val fetchedAt: PosInstant,
    val accountedBytes: Long
)

/** Presentation values have no disk persistence and are purged with financial scope. */
internal data class PosSalesState(
    val presentationEpoch: Long = 0,
    val access: PosAccess = PosAccess.VERIFYING,
    val route: PosRoute = PosRoute.OVERVIEW,
    val overview: PosPeriodSnapshot? = null,
    val history: PosPeriodSnapshot? = null,
    val detail: PosReadData.Detail? = null,
    val detailTimeZone: String? = null,
    val detailFetchedAt: PosInstant? = null,
    val filterOptions: PosReadData.Filter? = null,
    val metadataRead: Boolean = false,
    val refreshing: Boolean = false,
    val newDataAvailable: Boolean = false,
    val anchorSaleId: String? = null,
    val selectedSaleId: String? = null,
    val anchorRelocated: Boolean = false,
    val cacheLimitReached: Boolean = false,
    val errorCode: String? = null
)

internal data class PosFlightObservation(
    val startedAtMs: Long,
    val terminalAtMs: Long?,
    val cancellationRequestedAtMs: Long?,
    val drainedWithinOneSecond: Boolean,
    val outcome: String
)

private data class PosCacheKey(val period: PosPeriod, val filters: PosFilters, val currency: String, val zone: String)

/** The byte budget is conservative retained-DTO accounting, not a claim about measured process RSS. */
internal class PosMemoryCache {
    private val entries = LinkedHashMap<PosCacheKey, PosPeriodSnapshot>(8, .75f, true)
    internal fun get(period: PosPeriod, filters: PosFilters): PosPeriodSnapshot? {
        val key = entries.keys.firstOrNull { it.period == period && it.filters == filters } ?: return null
        return entries[key]
    }
    internal fun clear() = entries.clear()
    internal fun retainedPeriods(): Int = entries.size
    internal fun retainedSales(): Int = entries.values.sumOf { it.sales.size }
    internal fun retainedBytes(): Long = entries.values.sumOf { it.accountedBytes }
    internal fun put(value: PosPeriodSnapshot, visible: List<PosPeriodSnapshot> = emptyList(), additionalBytes: Long = 0): Boolean {
        val key = PosCacheKey(value.period, value.filters, value.currencyCode, value.timeZone)
        val old = entries.put(key, value)
        val protected = visible.map { PosCacheKey(it.period, it.filters, it.currencyCode, it.timeZone) }.toSet() + key
        while (!fits(visible, additionalBytes)) {
            val victim = entries.keys.firstOrNull { it !in protected }
            if (victim == null) {
                if (old == null) entries.remove(key) else entries[key] = old
                return false
            }
            entries.remove(victim)
        }
        return true
    }
    internal fun fits(extra: List<PosPeriodSnapshot>, detailBytes: Long = 0): Boolean {
        // Identity avoids counting references twice while still charging two different staged snapshots.
        val all = (entries.values + extra).fold(mutableListOf<PosPeriodSnapshot>()) { list, item ->
            if (list.none { it === item }) list.add(item)
            list
        }
        return entries.size <= 5 && all.map { it.period }.distinct().size <= 5 && all.sumOf { it.sales.size } <= 500 &&
            all.sumOf { it.accountedBytes } + detailBytes <= 5L * 1024 * 1024
    }
}

/**
 * One foreground POS read owner. The injected scope is feature-owned; no catalog readiness,
 * Room, staff identity, persisted sales or independent auth refresh loop is introduced.
 * The host must call environment() for every auth/device/network/foreground transition.
 */
internal class PosSalesCoordinator(
    private val owner: CoroutineScope,
    private val transport: PosReadTransport,
    private val isScopeCurrent: (PosScope) -> Boolean,
    private val monotonicMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val jitter: (Int) -> Long = { bound -> kotlin.random.Random.nextLong(bound.toLong() + 1) },
    private val refreshExistingAuthOnce: suspend (PosScope) -> Boolean = { false }
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(PosSalesState())
    val state: StateFlow<PosSalesState> = mutableState.asStateFlow()
    private val mutableObservations = MutableStateFlow<List<PosFlightObservation>>(emptyList())
    val observations: StateFlow<List<PosFlightObservation>> = mutableObservations.asStateFlow()
    private val cache = PosMemoryCache()
    private var scope: PosScope? = null
    private var online = false
    private var foreground = false
    private var visible = false
    private var generation = 0L
    private var lastStart = Long.MIN_VALUE
    private var notBefore = 0L
    private var failures = 0
    private var active: Flight? = null
    private var pending: Intent? = null
    private var pump: Job? = null
    private var poll: Job? = null
    private var stalled = false
    private var deniedFor: PosScope? = null
    private var historyPeriod: PosPeriod? = null
    private var historyFilters = PosFilters()
    private var staged: PosPeriodSnapshot? = null
    private var rebuild: Rebuild? = null
    private var detailBytes = 0L
    private var optionsBytes = 0L
    private var detailReturnRoute = PosRoute.OVERVIEW

    private enum class Kind { OVERVIEW, HISTORY, STAGE, REFRESH, APPEND, REBUILD, DETAIL, FILTER }
    private data class Intent(val kind: Kind, val period: PosPeriod? = null,
        val filters: PosFilters = PosFilters(), val cursor: PosCursor? = null,
        val saleId: String? = null, val authRetried: Boolean = false)
    private class Flight(val epoch: Long, val scope: PosScope, val intent: Intent,
        val started: Long, val budgetMs: Long, val call: PosReadCall) {
        var worker: Job? = null
        var cancellationAt: Long? = null
        var obsolete = false
    }
    private data class Rebuild(val anchor: String?, val selected: String?, val desiredCount: Int,
        val deadline: Long, var snapshot: PosPeriodSnapshot)

    fun environment(authority: PosScope?, isOnline: Boolean, isForeground: Boolean,
        route: PosRoute, isVisible: Boolean) {
        synchronized(lock) {
            val authorityChanged = scope != authority
            val admissionChanged = authorityChanged || online != isOnline || foreground != isForeground
            val routeChanged = mutableState.value.route != route || visible != isVisible
            scope = authority; online = isOnline; foreground = isForeground; visible = isVisible
            if (admissionChanged) {
                generation++; pending = null; rebuild = null; staged = null
                active?.let(::cancel)
                // ONLINE_ONLY: no offline copy, financial metadata, selection or staged detail remains exposed.
                if (authorityChanged) { deniedFor = null; failures = 0; notBefore = 0 }
                purge(if (!online) PosAccess.OFFLINE else if (deniedFor == scope && scope != null) PosAccess.DENIED else PosAccess.VERIFYING, route)
                if (authorityChanged) { historyPeriod = null; historyFilters = PosFilters() }
            } else if (routeChanged) {
                active?.let(::cancel); pending = null; rebuild = null
                mutableState.value = mutableState.value.copy(route = route, detail = null,
                    refreshing = false, errorCode = null)
                detailBytes = 0
            }
            poll?.cancel(); poll = null
            if (!admitted()) return
            when (route) {
                PosRoute.OVERVIEW -> {
                    request(Intent(Kind.OVERVIEW)); startPolling()
                }
                PosRoute.HISTORY -> historyPeriod?.let {
                    request(Intent(if (mutableState.value.history == null) Kind.HISTORY else Kind.STAGE, it, historyFilters))
                }
                PosRoute.DETAIL -> mutableState.value.selectedSaleId?.let { request(Intent(Kind.DETAIL, saleId = it)) }
                PosRoute.FILTER -> historyPeriod?.let { request(Intent(Kind.FILTER, it)) }
            }
        }
    }

    fun openHistory(period: PosPeriod, filters: PosFilters = PosFilters()): Unit = synchronized(lock) {
        if ((filters.staffId != null || filters.deviceId != null) && !mutableState.value.metadataRead) return@synchronized
        historyPeriod = period; historyFilters = filters; staged = null; rebuild = null
        val cached = if (mutableState.value.access == PosAccess.AVAILABLE && admitted()) cache.get(period, filters) else null
        mutableState.value = mutableState.value.copy(route = PosRoute.HISTORY, history = cached,
            detail = null, filterOptions = null, anchorSaleId = null, selectedSaleId = null,
            anchorRelocated = false, newDataAvailable = false, cacheLimitReached = false)
        detailBytes = 0; optionsBytes = 0; poll?.cancel(); poll = null
        active?.let(::cancel)
        // On explicit entry the cached copy is only a placeholder until the first page returns.
        request(Intent(Kind.HISTORY, period, filters))
    }

    fun rememberAnchor(id: String?): Unit = synchronized(lock) {
        if (id == null || mutableState.value.history?.sales?.any { it.id == id } == true)
            mutableState.value = mutableState.value.copy(anchorSaleId = id)
    }

    fun openDetail(id: String): Unit = synchronized(lock) {
        PosWire.uuid(id)
        if (mutableState.value.access != PosAccess.AVAILABLE || !admitted()) return@synchronized
        detailReturnRoute = if (mutableState.value.route == PosRoute.HISTORY) PosRoute.HISTORY else PosRoute.OVERVIEW
        rebuild = null
        mutableState.value = mutableState.value.copy(route = PosRoute.DETAIL, selectedSaleId = id, detail = null)
        poll?.cancel(); poll = null; active?.let(::cancel)
        request(Intent(Kind.DETAIL, saleId = id))
    }

    fun closeDetail(): Unit = synchronized(lock) {
        detailBytes = 0
        mutableState.value = mutableState.value.copy(detail = null, detailTimeZone = null,
            detailFetchedAt = null, selectedSaleId = null, route = detailReturnRoute)
        active?.let(::cancel)
        if (detailReturnRoute == PosRoute.OVERVIEW) { request(Intent(Kind.OVERVIEW)); startPolling() }
        else historyPeriod?.let { request(Intent(Kind.STAGE, it, historyFilters)) }
    }

    fun showOverview(): Unit = synchronized(lock) {
        environment(scope, online, foreground, PosRoute.OVERVIEW, visible)
    }

    fun closeFilters(): Unit = synchronized(lock) {
        mutableState.value = mutableState.value.copy(route = PosRoute.HISTORY)
        active?.let(::cancel)
        historyPeriod?.let { request(Intent(Kind.STAGE, it, historyFilters)) }
    }

    fun loadMore(): Unit = synchronized(lock) {
        val value = mutableState.value.history ?: return@synchronized
        if (active != null || rebuild != null || value.sales.size >= 500) return@synchronized
        value.nextCursor?.let { request(Intent(Kind.APPEND, value.period, value.filters, it)) }
    }

    fun openFilters(): Unit = synchronized(lock) {
        val period = historyPeriod ?: return@synchronized
        rebuild = null
        mutableState.value = mutableState.value.copy(route = PosRoute.FILTER)
        active?.let(::cancel); request(Intent(Kind.FILTER, period))
    }

    /** Explicit refresh may replace the deep chain; passive refresh never moves it. */
    fun refreshHistory(): Unit = synchronized(lock) {
        if (mutableState.value.route != PosRoute.HISTORY || active != null || pending != null || rebuild != null) return@synchronized
        if (!admitted() || stalled) return@synchronized
        val current = mutableState.value.history
        if (current == null) {
            historyPeriod?.let { request(Intent(Kind.HISTORY, it, historyFilters)) }
            return@synchronized
        }
        val next = staged
        if (next == null) {
            // Fetch a fresh first envelope without retiring the currently visible deep chain.
            request(Intent(Kind.REFRESH, current.period, current.filters))
        } else {
            beginHistoryRebuild(current, next, monotonicMs() + 8_000)
        }
    }

    fun retry(): Unit = synchronized(lock) {
        // A late terminal does not silently restart a failed cancellation lane.
        if (active != null || !admitted(ignoreStableDenial = true)) return@synchronized
        deniedFor = null
        stalled = false // Explicit retry still respects the current backoff and Retry-After.
        mutableState.value = mutableState.value.copy(errorCode = null)
        when (mutableState.value.route) {
            PosRoute.OVERVIEW -> { request(Intent(Kind.OVERVIEW)); startPolling() }
            PosRoute.HISTORY -> historyPeriod?.let { request(Intent(Kind.STAGE, it, historyFilters)) }
            PosRoute.DETAIL -> mutableState.value.selectedSaleId?.let { request(Intent(Kind.DETAIL, saleId = it)) }
            PosRoute.FILTER -> historyPeriod?.let { request(Intent(Kind.FILTER, it)) }
        }
    }

    fun close(): Unit = synchronized(lock) {
        generation++; online = false; visible = false; pending = null
        active?.let(::cancel); pump?.cancel(); poll?.cancel()
        purge(PosAccess.VERIFYING, mutableState.value.route)
    }

    private fun admitted(ignoreStableDenial: Boolean = false): Boolean = online && foreground && visible &&
        scope?.let(isScopeCurrent) == true && (ignoreStableDenial || deniedFor != scope)
    private fun cancel(flight: Flight) {
        flight.obsolete = true
        if (flight.cancellationAt == null) flight.cancellationAt = monotonicMs()
        flight.call.cancel(); flight.worker?.cancel()
    }
    private fun purge(access: PosAccess, route: PosRoute) {
        cache.clear(); staged = null; rebuild = null; detailBytes = 0; optionsBytes = 0
        mutableState.value = PosSalesState(presentationEpoch = generation, access = access, route = route)
    }
    private fun request(intent: Intent) {
        if (!admitted() || stalled) return
        pending = intent; schedule()
    }
    private fun schedule() {
        if (active != null || pending == null || !admitted() || stalled || pump?.isActive == true) return
        pump = owner.launch {
            val wait = synchronized(lock) {
                val cadence = if (lastStart == Long.MIN_VALUE) 0 else lastStart + 2_000
                (maxOf(cadence, notBefore) - monotonicMs()).coerceAtLeast(0)
            }
            delay(wait)
            synchronized(lock) {
                pump = null
                if (active != null || !admitted() || stalled) return@synchronized
                val intent = pending ?: return@synchronized
                pending = null
                val captured = scope ?: return@synchronized
                val budget = when (intent.kind) {
                    Kind.OVERVIEW -> 2_000L
                    Kind.REBUILD -> ((rebuild?.deadline ?: monotonicMs()) - monotonicMs()).coerceAtMost(8_000)
                    else -> 8_000L
                }
                if (budget <= 0) { finishRebuild(); return@synchronized }
                val request = PosReadRequest(captured,
                    when (intent.kind) {
                        Kind.APPEND, Kind.REBUILD -> PosReadAction.PAGE
                        Kind.DETAIL -> PosReadAction.DETAIL
                        Kind.FILTER -> PosReadAction.FILTER
                        else -> PosReadAction.OVERVIEW
                    }, intent.period, intent.filters, intent.cursor, saleId = intent.saleId)
                lastStart = monotonicMs()
                val call = try { transport.start(request) } catch (error: Exception) {
                    transient(error); return@synchronized
                }
                val flight = Flight(generation, captured, intent, lastStart, budget, call)
                active = flight
                mutableState.value = mutableState.value.copy(refreshing = true,
                    access = if (mutableState.value.access == PosAccess.AVAILABLE) PosAccess.AVAILABLE else PosAccess.LOADING)
                // Enter the finally-owned drain before a queued cancellation can prevent the body from starting.
                flight.worker = owner.launch(start = CoroutineStart.UNDISPATCHED) { run(flight) }
            }
        }
    }

    private suspend fun run(flight: Flight) {
        var outcome = "cancelled"
        var envelope: PosEnvelope? = null
        var error: Exception? = null
        var authRetry = false
        val deadline = flight.budgetMs
        try {
            envelope = withTimeout(deadline) {
                val response = flight.call.await()
                flight.call.awaitTerminal()
                response
            }
            if (monotonicMs() - flight.started > deadline) throw PosContractException("pos_deadline_exceeded")
            outcome = "response"
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) { error = cancelled; outcome = "timeout" }
            else outcome = "cancelled"
        } catch (failure: Exception) { error = failure; outcome = "failed" }
        finally {
            if (error != null || flight.obsolete || envelope == null) {
                synchronized(lock) {
                    if (flight.cancellationAt == null) flight.cancellationAt = monotonicMs()
                    flight.call.cancel()
                }
            }
            val drained = withContext(NonCancellable) {
                withTimeoutOrNull(1_000) { flight.call.awaitTerminal(); true } == true
            }
            synchronized(lock) {
                mutableObservations.value = (mutableObservations.value + PosFlightObservation(
                    flight.started, if (drained) monotonicMs() else null,
                    flight.cancellationAt, drained, if (drained) outcome else "transport_stalled")).takeLast(100)
                if (!drained) {
                    stalled = true; pending = null
                    purge(PosAccess.STALLED, mutableState.value.route)
                    mutableState.value = mutableState.value.copy(errorCode = "pos_transport_stalled")
                } else {
                    if (current(flight)) {
                        // Publication, decoding and the observed terminal all belong to the total read deadline.
                        if (monotonicMs() - flight.started > deadline) {
                            error = PosContractException("pos_deadline_exceeded"); outcome = "timeout"
                        }
                        val status401 = error is PosHttpException && (error as PosHttpException).status == 401
                        val denied401 = (envelope as? PosEnvelope.Denied)?.code == "unauthenticated"
                        authRetry = (status401 || denied401) && !flight.intent.authRetried
                        if (authRetry) purge(PosAccess.VERIFYING, mutableState.value.route)
                        else {
                            when {
                                status401 || denied401 -> deny("unauthenticated")
                                error is PosHttpException && (error as PosHttpException).status == 403 -> deny("permission_denied")
                                error != null -> transient(error!!)
                                else -> envelope?.let { publish(flight, it) }
                            }
                        }
                    }
                    // A 401 refresh is still owned by this same logical read. Route/scope
                    // cancellation must reach it and polling must not start a competing read.
                    if (!authRetry) {
                        if (active === flight) active = null
                        schedule()
                    }
                }
            }
            if (!drained) {
                // Keep ownership until the actual per-call terminal receipt eventually arrives.
                owner.launch {
                    flight.call.awaitTerminal()
                    synchronized(lock) { if (active === flight) active = null }
                }
            }
        }
        if (authRetry) {
            val refreshed = try { withTimeout(1_000) { refreshExistingAuthOnce(flight.scope) } }
                catch (_: Exception) { false }
            synchronized(lock) {
                if (active === flight) active = null
                if (current(flight)) {
                    if (refreshed) {
                        // The 401 purged financial state. A deep-page cursor has no
                        // retained base; reauthorize a first envelope for that period.
                        val retry = when (flight.intent.kind) {
                            Kind.APPEND, Kind.REBUILD -> Intent(Kind.HISTORY,
                                flight.intent.period, flight.intent.filters, authRetried = true)
                            Kind.FILTER -> {
                                mutableState.value = mutableState.value.copy(route = PosRoute.HISTORY)
                                Intent(Kind.HISTORY, flight.intent.period, historyFilters, authRetried = true)
                            }
                            else -> flight.intent.copy(authRetried = true)
                        }
                        if (retry.kind == Kind.DETAIL) {
                            mutableState.value = mutableState.value.copy(selectedSaleId = retry.saleId)
                        }
                        request(retry)
                    } else deny("unauthenticated")
                }
                // A canceled old refresh may only release its own flight, then admit
                // a separately captured current intent. It cannot publish its old scope.
                schedule()
            }
        }
    }

    private fun current(flight: Flight): Boolean = !flight.obsolete && flight.epoch == generation &&
        scope == flight.scope && admitted()

    private fun publish(flight: Flight, envelope: PosEnvelope) {
        if (envelope is PosEnvelope.Denied) {
            when (envelope.code) {
                "not_found" -> mutableState.value = mutableState.value.copy(refreshing = false, detail = null,
                    errorCode = "pos_not_found")
                "invalid_request" -> transient(PosContractException("pos_invalid_request"))
                "currency_unsupported" -> {
                    purge(PosAccess.UNAVAILABLE, mutableState.value.route)
                    mutableState.value = mutableState.value.copy(errorCode = "pos_currency_unsupported")
                    poll?.cancel(); poll = null; pending = null
                }
                else -> deny(envelope.code)
            }
            return
        }
        envelope as PosEnvelope.Authorized
        if (envelope.serverScope.principalId != flight.scope.principalId ||
            envelope.serverScope.shopId != flight.scope.shopId ||
            envelope.serverScope.principalKind != "personal_account" || !envelope.capability.salesRead ||
            envelope.capability.accessPolicy != "online_only" ||
            envelope.currencyCode != "CLP" || envelope.action != when (flight.intent.kind) {
                Kind.APPEND, Kind.REBUILD -> PosReadAction.PAGE
                Kind.DETAIL -> PosReadAction.DETAIL
                Kind.FILTER -> PosReadAction.FILTER
                else -> PosReadAction.OVERVIEW
            } ||
            (flight.scope.authorizedShopDeviceId != null &&
                envelope.serverScope.shopDeviceId != flight.scope.authorizedShopDeviceId)) {
            deny("pos_scope_mismatch"); return
        }
        if (mutableState.value.metadataRead && !envelope.capability.metadataRead)
            purge(PosAccess.VERIFYING, mutableState.value.route)
        failures = 0; notBefore = 0
        mutableState.value = mutableState.value.copy(access = PosAccess.AVAILABLE,
            metadataRead = envelope.capability.metadataRead, refreshing = false, errorCode = null)
        val intent = flight.intent
        when (val data = envelope.data) {
            is PosReadData.Overview -> {
                val period = requireNotNull(envelope.period)
                val value = PosPeriodSnapshot(period, intent.filters, envelope.currencyCode, envelope.timeZone,
                    data.summaryDays, data.sales.distinctBy { it.id }, data.nextCursor, envelope.serverTime,
                    weight(envelope.bodyBytes, data.sales.size + data.summaryDays.size))
                val currentHistory = mutableState.value.history
                if (intent.kind == Kind.REFRESH && currentHistory != null) {
                    if (fits(listOf(value))) {
                        // The fresh envelope and remaining pages share this read's original budget.
                        beginHistoryRebuild(currentHistory, value, flight.started + flight.budgetMs)
                    } else limited()
                } else if (intent.kind == Kind.STAGE && currentHistory != null) {
                    if (fits(listOf(value))) { staged = value; mutableState.value = mutableState.value.copy(newDataAvailable = true) }
                    else limited()
                } else if (store(value, visibleSnapshots().filterNot { it.period == period && it.filters == intent.filters })) {
                    if (intent.kind == Kind.OVERVIEW) mutableState.value = mutableState.value.copy(overview = value)
                    else mutableState.value = mutableState.value.copy(history = value, anchorSaleId = null,
                        anchorRelocated = false, newDataAvailable = false)
                } else limited()
            }
            is PosReadData.Page -> {
                val base = if (intent.kind == Kind.REBUILD) rebuild?.snapshot else mutableState.value.history
                if (base == null || base.period != intent.period || base.filters != intent.filters || base.nextCursor != intent.cursor) return
                val merged = base.copy(sales = (base.sales + data.sales).distinctBy { it.id },
                    nextCursor = data.nextCursor, fetchedAt = envelope.serverTime,
                    accountedBytes = base.accountedBytes + weight(envelope.bodyBytes, data.sales.size))
                if (intent.kind == Kind.REBUILD) {
                    if (fits(listOf(merged))) { rebuild?.snapshot = merged; continueRebuild() }
                    else { limited(); finishRebuild() }
                } else if (store(merged, visibleSnapshots().filterNot { it === base }))
                    mutableState.value = mutableState.value.copy(history = merged)
                else limited()
            }
            is PosReadData.Detail -> {
                if (data.sale.id != intent.saleId || mutableState.value.selectedSaleId != intent.saleId) return
                val bytes = weight(envelope.bodyBytes, data.lines.size + 1)
                if (fits(emptyList(), bytes)) { detailBytes = bytes; mutableState.value = mutableState.value.copy(
                    detail = data, detailTimeZone = envelope.timeZone, detailFetchedAt = envelope.serverTime) }
                else limited()
            }
            is PosReadData.Filter -> {
                val bytes = weight(envelope.bodyBytes, data.paymentMethods.size + data.staff.size + data.devices.size)
                if (fits(emptyList(), bytes)) { optionsBytes = bytes; mutableState.value = mutableState.value.copy(filterOptions = data) }
                else limited()
            }
        }
    }

    private fun beginHistoryRebuild(current: PosPeriodSnapshot, next: PosPeriodSnapshot, deadline: Long) {
        staged = null
        rebuild = Rebuild(mutableState.value.anchorSaleId, mutableState.value.selectedSaleId,
            current.sales.size, deadline, next)
        mutableState.value = mutableState.value.copy(refreshing = true, newDataAvailable = false)
        continueRebuild()
    }

    private fun continueRebuild() {
        val value = rebuild ?: return
        val anchorPresent = value.anchor == null || value.snapshot.sales.any { it.id == value.anchor }
        val done = (anchorPresent && value.snapshot.sales.size >= value.desiredCount) ||
            value.snapshot.nextCursor == null || value.snapshot.sales.size >= 500 ||
            monotonicMs() + 2_000 >= value.deadline
        if (done) finishRebuild()
        else request(Intent(Kind.REBUILD, value.snapshot.period, value.snapshot.filters, value.snapshot.nextCursor))
    }
    private fun finishRebuild() {
        val value = rebuild ?: return
        rebuild = null
        if (!store(value.snapshot, listOfNotNull(mutableState.value.overview))) { limited(); return }
        val anchor = value.anchor?.takeIf { id -> value.snapshot.sales.any { it.id == id } }
        val selected = value.selected?.takeIf { id -> value.snapshot.sales.any { it.id == id } }
        mutableState.value = mutableState.value.copy(history = value.snapshot, anchorSaleId = anchor,
            selectedSaleId = selected, anchorRelocated = value.anchor != null && anchor == null,
            refreshing = false, newDataAvailable = false)
    }
    private fun visibleSnapshots() = listOfNotNull(mutableState.value.overview, mutableState.value.history)
    private fun store(value: PosPeriodSnapshot, visible: List<PosPeriodSnapshot>): Boolean =
        cache.put(value, visible + listOfNotNull(staged, rebuild?.snapshot), detailBytes + optionsBytes)
    private fun fits(extra: List<PosPeriodSnapshot>, additionalBytes: Long = 0): Boolean =
        cache.fits(visibleSnapshots() + listOfNotNull(staged, rebuild?.snapshot) + extra,
            detailBytes + optionsBytes + additionalBytes)
    private fun weight(rawBytes: Int, records: Int): Long = rawBytes.toLong() * 4 + records.toLong() * 1024
    private fun limited() { mutableState.value = mutableState.value.copy(cacheLimitReached = true, refreshing = false) }
    private fun deny(code: String) {
        deniedFor = scope
        generation++; pending = null; poll?.cancel(); poll = null
        purge(PosAccess.DENIED, mutableState.value.route)
        mutableState.value = mutableState.value.copy(errorCode = code)
    }
    private fun transient(error: Exception) {
        failures++
        val base = listOf(4_000L, 8_000L, 16_000L, 30_000L)[(failures - 1).coerceAtMost(3)]
        val seconds = (error as? PosHttpException)?.retryAfterSeconds?.coerceAtLeast(0) ?: 0
        val retryAfter = if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
        val wait = maxOf(base + jitter((base / 5).toInt()), retryAfter)
        val now = monotonicMs()
        notBefore = if (wait > Long.MAX_VALUE - now) Long.MAX_VALUE else now + wait
        mutableState.value = mutableState.value.copy(refreshing = false,
            access = if (mutableState.value.access == PosAccess.AVAILABLE) PosAccess.AVAILABLE else PosAccess.UNAVAILABLE,
            errorCode = if (error is TimeoutCancellationException ||
                (error is PosContractException && error.code == "pos_deadline_exceeded")) "pos_timeout" else "pos_unavailable")
    }
    private fun startPolling() {
        poll?.cancel()
        poll = owner.launch {
            var next = monotonicMs() + 2_000 + jitter(250)
            while (isActive) {
                delay((next - monotonicMs()).coerceAtLeast(0))
                synchronized(lock) {
                    if (!admitted() || mutableState.value.route != PosRoute.OVERVIEW || stalled) return@launch
                    // Lost ticks are discarded, never queued behind a live transport.
                    if (active == null && pending == null) request(Intent(Kind.OVERVIEW))
                }
                do { next += 2_000 + jitter(250) } while (next <= monotonicMs())
            }
        }
    }
}

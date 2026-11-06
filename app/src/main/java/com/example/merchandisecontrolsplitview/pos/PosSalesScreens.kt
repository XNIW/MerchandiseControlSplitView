package com.example.merchandisecontrolsplitview.pos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

/** No side-effecting environment binding: the host supplies auth/network/lifecycle via the coordinator. */
@Composable
internal fun PosSalesRoute(
    coordinator: PosSalesCoordinator,
    onMerchandiseControl: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val state by coordinator.state.collectAsState()
    val config = LocalConfiguration.current
    val locale = config.locales[0] ?: Locale.ROOT
    val historyList = remember(state.presentationEpoch) { LazyListState() }
    val draft = remember(state.presentationEpoch, state.history?.period, state.history?.filters) {
        PosFilterDraft(state.history)
    }
    when (state.route) {
        PosRoute.OVERVIEW -> PosOverviewScreen(state, locale, onMerchandiseControl,
            onHistory = {
                state.overview?.period?.toDate?.let {
                    val today = PosWire.date(it)
                    coordinator.openHistory(PosPeriod(today.minusDays(6).toString(), today.toString()))
                }
            }, onRetry = coordinator::retry, onSale = coordinator::openDetail, padding = contentPadding)
        PosRoute.HISTORY -> PosHistoryScreen(state, locale, coordinator, historyList, onMerchandiseControl,
            { coordinator.showOverview() }, contentPadding)
        PosRoute.DETAIL -> PosDetailScreen(state, locale, coordinator::closeDetail,
            coordinator::retry, onMerchandiseControl, contentPadding)
        PosRoute.FILTER -> PosFilterScreen(state, draft, coordinator, coordinator::closeFilters, onMerchandiseControl, contentPadding)
    }
}

@Composable
private fun PosOverviewScreen(state: PosSalesState, locale: Locale, onMerchandise: () -> Unit,
    onHistory: () -> Unit, onRetry: () -> Unit, onSale: (String) -> Unit, padding: PaddingValues) {
    val qualified = state.access == PosAccess.AVAILABLE
    val snapshot = state.overview.takeIf { qualified }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") { PosHeading(stringResource(R.string.task146_pos_today)) }
        item("merchandise") { Button(onClick = onMerchandise) { Text(stringResource(R.string.task146_pos_merchandise)) } }
        item("access") { PosAccessMessage(state, onRetry) }
        if (snapshot != null) {
            item("fetched") { PosFetched(snapshot, locale) }
            items(snapshot.summaryDays, key = { "summary:${it.businessDate}" }) { day -> PosSummary(day, locale) }
            item("latest-heading") { PosHeading(stringResource(R.string.task146_pos_latest)) }
            if (snapshot.sales.isEmpty()) item("empty") { Text(stringResource(R.string.task146_pos_empty)) }
            items(snapshot.sales, key = { "sale:${it.id}" }) { sale ->
                PosSaleCard(sale, snapshot.timeZone, locale, state.metadataRead) { onSale(sale.id) }
            }
            item("history") { Button(onClick = onHistory) { Text(stringResource(R.string.task146_pos_history)) } }
        }
    }
}

@Composable
private fun PosHistoryScreen(state: PosSalesState, locale: Locale, coordinator: PosSalesCoordinator,
    list: LazyListState, onMerchandise: () -> Unit, onBack: () -> Unit, padding: PaddingValues) {
    val snapshot = state.history.takeIf { state.access == PosAccess.AVAILABLE }
    LaunchedEffect(list, state.presentationEpoch, snapshot) {
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("sale:") == true }
                ?.key?.toString()?.removePrefix("sale:")
        }.distinctUntilChanged().collect { coordinator.rememberAnchor(it) }
    }
    // Stable sale keys retain the same visible item across append/rebuild. A lost bounded anchor is explicit.
    LaunchedEffect(state.presentationEpoch, state.anchorRelocated) {
        if (state.anchorRelocated) list.scrollToItem(0)
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") { PosHeading(stringResource(R.string.task146_pos_history)) }
        item("navigation") {
            Column {
                TextButton(onClick = onBack) { Text(stringResource(R.string.task146_pos_back)) }
                TextButton(onClick = onMerchandise) { Text(stringResource(R.string.task146_pos_merchandise)) }
            }
        }
        item("access") { PosAccessMessage(state, coordinator::retry) }
        if (snapshot != null) {
            item("fetched") {
                PosFetched(snapshot, locale)
                Text(stringResource(R.string.task146_pos_period, snapshot.period.fromDate, snapshot.period.toDate))
                TextButton(onClick = coordinator::openFilters) { Text(stringResource(R.string.task146_pos_filters)) }
            }
            item("summary-label") { PosHeading(stringResource(R.string.task146_pos_period_summary)) }
            items(snapshot.summaryDays, key = { "summary:${it.businessDate}" }) { PosSummary(it, locale) }
            item("filter-label") { Text(stringResource(R.string.task146_pos_filters_list_only)) }
            if (state.newDataAvailable) item("staged") {
                Button(onClick = coordinator::refreshHistory) { Text(stringResource(R.string.task146_pos_new_data)) }
            }
            if (state.anchorRelocated) item("relocated") { Text(stringResource(R.string.task146_pos_repositioned)) }
            if (snapshot.sales.isEmpty()) item("empty") { Text(stringResource(R.string.task146_pos_empty)) }
            items(snapshot.sales, key = { "sale:${it.id}" }) { sale ->
                PosSaleCard(sale, snapshot.timeZone, locale, state.metadataRead) { coordinator.openDetail(sale.id) }
            }
            if (snapshot.nextCursor != null && !state.cacheLimitReached) item("more") {
                Button(onClick = coordinator::loadMore, enabled = !state.refreshing) { Text(stringResource(R.string.task146_pos_more)) }
            }
            if (state.cacheLimitReached) item("bounded") { Text(stringResource(R.string.task146_pos_cache_limit)) }
        }
    }
}

@Composable
private fun PosDetailScreen(state: PosSalesState, locale: Locale, onBack: () -> Unit,
    onRetry: () -> Unit, onMerchandise: () -> Unit, padding: PaddingValues) {
    val detail = state.detail.takeIf { state.access == PosAccess.AVAILABLE }
    val zone = state.detailTimeZone
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") { PosHeading(stringResource(R.string.task146_pos_detail)) }
        item("back") { TextButton(onClick = onBack) { Text(stringResource(R.string.task146_pos_back)) } }
        item("merchandise") { TextButton(onClick = onMerchandise) { Text(stringResource(R.string.task146_pos_merchandise)) } }
        item("access") { PosAccessMessage(state, onRetry) }
        if (detail != null && zone != null) {
            state.detailFetchedAt?.let { fetched -> item("fetched") {
                Text(stringResource(R.string.task146_pos_updated, posTime(fetched, zone, locale), zone))
            } }
            item("sale") { PosSaleCard(detail.sale, zone, locale, state.metadataRead) {} }
            item("lines-heading") { PosHeading(stringResource(R.string.task146_pos_lines)) }
            items(detail.lines, key = { "line:${it.position}" }) { line ->
                Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line.productName ?: line.itemNumber ?: line.barcode ?: line.type)
                        Text(stringResource(R.string.task146_pos_quantity, posQuantity(line.quantity, locale)))
                        Text(stringResource(R.string.task146_pos_unit_amount, posMoney(line.unitAmountClp, locale)))
                        Text(stringResource(R.string.task146_pos_line_amount, posMoney(line.lineAmountClp, locale)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PosFilterScreen(state: PosSalesState, draft: PosFilterDraft, coordinator: PosSalesCoordinator,
    onBack: () -> Unit, onMerchandise: () -> Unit, padding: PaddingValues) {
    val snapshot = state.history.takeIf { state.access == PosAccess.AVAILABLE }
    // Drafts are scoped values. They survive a freshness tick, never an authority transition.
    var from by draft.from
    var to by draft.to
    var number by draft.number
    var payment by draft.payment
    var kind by draft.kind
    var status by draft.status
    var staff by draft.staff
    var device by draft.device
    var invalid by draft.invalid
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") { PosHeading(stringResource(R.string.task146_pos_filters)) }
        item("back") { TextButton(onClick = onBack) { Text(stringResource(R.string.task146_pos_back)) } }
        item("merchandise") { TextButton(onClick = onMerchandise) { Text(stringResource(R.string.task146_pos_merchandise)) } }
        item("access") { PosAccessMessage(state, coordinator::retry) }
        if (snapshot != null) {
            item("period") {
                OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.task146_pos_from)) }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text(stringResource(R.string.task146_pos_to)) }, singleLine = true)
                Text(stringResource(R.string.task146_pos_date_hint))
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.task146_pos_sale_number)) }, singleLine = true)
            }
            item("all") {
                TextButton(onClick = { payment = null; kind = null; status = null; staff = null; device = null; number = "" }) {
                    Text(stringResource(R.string.task146_pos_all))
                }
            }
            items(PosBusinessKind.entries, key = { "kind:${it.name}" }) { value ->
                FilterChip(selected = kind == value.name, onClick = { kind = if (kind == value.name) null else value.name },
                    label = { Text(posKindLabel(value)) })
            }
            items(PosSaleStatus.entries, key = { "status:${it.name}" }) { value ->
                FilterChip(selected = status == value.name, onClick = { status = if (status == value.name) null else value.name },
                    label = { Text(posStatusLabel(value)) })
            }
            items(state.filterOptions?.paymentMethods.orEmpty(), key = { "payment:$it" }) { value ->
                FilterChip(selected = payment == value, onClick = { payment = if (payment == value) null else value }, label = { Text(value) })
            }
            if (state.metadataRead) {
                item("staff-label") { PosHeading(stringResource(R.string.task146_pos_staff)) }
                items(state.filterOptions?.staff.orEmpty(), key = { "staff:${it.id}" }) { value ->
                    FilterChip(selected = staff == value.id, onClick = { staff = if (staff == value.id) null else value.id }, label = { Text(value.name) })
                }
                item("device-label") { PosHeading(stringResource(R.string.task146_pos_device)) }
                items(state.filterOptions?.devices.orEmpty(), key = { "device:${it.id}" }) { value ->
                    FilterChip(selected = device == value.id, onClick = { device = if (device == value.id) null else value.id }, label = { Text(value.name) })
                }
            }
            if (invalid) item("invalid") { Text(stringResource(R.string.task146_pos_invalid_period), color = MaterialTheme.colorScheme.error) }
            item("apply") {
                Button(onClick = {
                    val period = runCatching { PosPeriod(from, to) }.getOrNull()
                    invalid = period == null || number.length > 80
                    if (!invalid && period != null) coordinator.openHistory(period, PosFilters(
                        status?.let(PosSaleStatus::valueOf), kind?.let(PosBusinessKind::valueOf), payment,
                        staff.takeIf { state.metadataRead }, device.takeIf { state.metadataRead }, number.takeIf { it.isNotEmpty() }))
                }) { Text(stringResource(R.string.task146_pos_apply)) }
            }
        }
    }
}

/** RAM only: filter identifiers never enter SavedStateHandle, Bundle, Room or preferences. */
private class PosFilterDraft(snapshot: PosPeriodSnapshot?) {
    val from = mutableStateOf(snapshot?.period?.fromDate.orEmpty())
    val to = mutableStateOf(snapshot?.period?.toDate.orEmpty())
    val number = mutableStateOf(snapshot?.filters?.saleNumber.orEmpty())
    val payment = mutableStateOf(snapshot?.filters?.paymentMethod)
    val kind = mutableStateOf(snapshot?.filters?.businessKind?.name)
    val status = mutableStateOf(snapshot?.filters?.status?.name)
    val staff = mutableStateOf(snapshot?.filters?.staffId)
    val device = mutableStateOf(snapshot?.filters?.deviceId)
    val invalid = mutableStateOf(false)
}

@Composable
private fun PosAccessMessage(state: PosSalesState, onRetry: () -> Unit) {
    val label = when (state.access) {
        PosAccess.VERIFYING -> R.string.task146_pos_verifying
        PosAccess.LOADING -> R.string.task146_pos_loading
        PosAccess.AVAILABLE -> if (state.errorCode == null) null else R.string.task146_pos_unavailable
        PosAccess.OFFLINE -> R.string.task146_pos_offline
        PosAccess.DENIED -> R.string.task146_pos_denied
        PosAccess.UNAVAILABLE -> if (state.errorCode == "pos_currency_unsupported") R.string.task146_pos_currency else R.string.task146_pos_unavailable
        PosAccess.STALLED -> R.string.task146_pos_stalled
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(stringResource(it)) }
        if (state.refreshing) CircularProgressIndicator()
        if (state.access == PosAccess.UNAVAILABLE || state.access == PosAccess.VERIFYING || state.access == PosAccess.STALLED || state.errorCode != null)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.task146_pos_retry)) }
        if (state.refreshing && state.access == PosAccess.AVAILABLE) Text(stringResource(R.string.task146_pos_updating))
    }
}

@Composable private fun PosHeading(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
@Composable private fun PosFetched(value: PosPeriodSnapshot, locale: Locale) {
    Text(stringResource(R.string.task146_pos_updated, posTime(value.fetchedAt, value.timeZone, locale), value.timeZone))
}
@Composable private fun PosSummary(day: PosSummaryDay, locale: Locale) {
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(day.businessDate, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.task146_pos_gross, posMoney(day.grossSalesClp, locale)))
            Text(stringResource(R.string.task146_pos_discount, posMoney(day.discountsClp, locale)))
            Text(stringResource(R.string.task146_pos_refunds, posMoney(day.refundsClp, locale)))
            Text(stringResource(R.string.task146_pos_net, posMoney(day.netRevenueClp, locale)))
        }
    }
}
@Composable private fun PosSaleCard(sale: PosSale, zone: String, locale: Locale, metadata: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sale.number, style = MaterialTheme.typography.titleMedium)
            Text(posKindLabel(sale.kind) + " · " + posStatusLabel(sale.status))
            Text(posTime(sale.occurredAt, zone, locale) + " (" + zone + ")")
            Text(stringResource(R.string.task146_pos_gross, posMoney(sale.grossAmountClp, locale)))
            Text(stringResource(R.string.task146_pos_discount, posMoney(sale.discountAmountClp, locale)))
            Text(stringResource(R.string.task146_pos_net, posMoney(sale.netAmountClp, locale)))
            if (metadata) {
                sale.staffName?.let { Text(stringResource(R.string.task146_pos_staff_name, it)) }
                sale.deviceName?.let { Text(stringResource(R.string.task146_pos_device_name, it)) }
            }
        }
    }
}
@Composable private fun posKindLabel(value: PosBusinessKind) = stringResource(when (value) {
    PosBusinessKind.SALE -> R.string.task146_pos_sale
    PosBusinessKind.REFUND -> R.string.task146_pos_refund
    PosBusinessKind.VOID -> R.string.task146_pos_void
})
@Composable private fun posStatusLabel(value: PosSaleStatus) = stringResource(when (value) {
    PosSaleStatus.ACCEPTED -> R.string.task146_pos_accepted
    PosSaleStatus.DUPLICATE -> R.string.task146_pos_duplicate
    PosSaleStatus.CONFLICT -> R.string.task146_pos_conflict
    PosSaleStatus.REJECTED -> R.string.task146_pos_rejected
})
internal fun posMoney(value: Long, locale: Locale): String = NumberFormat.getCurrencyInstance(locale).apply {
    currency = Currency.getInstance("CLP"); minimumFractionDigits = 0; maximumFractionDigits = 0
}.format(value)
internal fun posQuantity(value: BigDecimal, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 0; maximumFractionDigits = value.scale().coerceIn(0, 1024)
}.format(value)
private fun posTime(value: PosInstant, zone: String, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        .format(value.instant.atZone(ZoneId.of(zone)))

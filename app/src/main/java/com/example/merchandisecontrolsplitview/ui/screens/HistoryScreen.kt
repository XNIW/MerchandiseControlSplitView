package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.HistoryEntryListItem
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.ui.theme.appColors
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.util.formatClCount
import com.example.merchandisecontrolsplitview.util.formatClSummaryMoney
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter
import com.example.merchandisecontrolsplitview.viewmodel.HistoryFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val historyCompactSummarySpacing = 2.dp

private sealed interface HistoryListRow {
    data class MonthHeader(
        val key: String,
        val label: String
    ) : HistoryListRow

    data class EntryItem(
        val entry: HistoryEntryListItem
    ) : HistoryListRow
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    contentPadding: PaddingValues = PaddingValues(),
    historyList: List<HistoryEntryListItem>,
    currentFilter: HistoryFilter,
    hasAnyHistoryEntries: Boolean,
    historyActionMessage: String?,
    availableSuppliers: List<String> = emptyList(),
    availableCategories: List<String> = emptyList(),
    onSelect: (HistoryEntryListItem) -> Unit,
    onRename: (HistoryEntryListItem, String) -> Unit,
    onDelete: (HistoryEntryListItem) -> Unit,
    onHistoryActionMessageConsumed: () -> Unit,
    onSetFilter: (HistoryFilter) -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val currentConfiguration = LocalConfiguration.current
    val currentLocale = remember(currentConfiguration) { Locale.getDefault() }
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // `innerPadding` already includes the floating bottom bar height. Keep that clearance
    // separate from the device inset so the snackbar sits just above the bar without magic numbers.
    val snackbarBottomOffset = (contentPadding.calculateBottomPadding() - navigationBarInset)
        .coerceAtLeast(0.dp) + spacing.xxl
    var showRenameDialog by remember { mutableStateOf(false) }
    var entryToRename by remember { mutableStateOf<HistoryEntryListItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<HistoryEntryListItem?>(null) }

    var showFilterSheet by remember { mutableStateOf(false) }
    var draftFilter by remember(currentFilter) { mutableStateOf(currentFilter) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var datePickerTargetIsStart by remember { mutableStateOf(true) }
    var customStartDate by remember { mutableStateOf(LocalDate.now()) }
    var customEndDate by remember { mutableStateOf(LocalDate.now()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormatter = remember(currentLocale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale)
    }

    fun resetCustomRangeDraft() {
        val today = LocalDate.now()
        datePickerTargetIsStart = true
        customStartDate = today
        customEndDate = today
    }

    fun dismissCustomRangePicker() {
        showDatePickerDialog = false
        resetCustomRangeDraft()
    }

    LaunchedEffect(historyActionMessage) {
        historyActionMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            onHistoryActionMessageConsumed()
        }
    }

    val dateFilterLabel = when (val df = currentFilter.dateFilter) {
        is DateFilter.All -> stringResource(R.string.filter_all)
        is DateFilter.LastMonth -> stringResource(R.string.filter_current_month)
        is DateFilter.PreviousMonth -> stringResource(R.string.filter_previous_month)
        is DateFilter.CustomRange -> stringResource(
            R.string.history_filter_custom_range_value,
            df.startDate.format(dateFormatter),
            df.endDate.format(dateFormatter)
        )
    }
    val detailSep = stringResource(R.string.history_details_separator)
    val filterSummary = buildString {
        append(dateFilterLabel)
        if (currentFilter.supplier.isNotBlank()) { append(detailSep); append(currentFilter.supplier) }
        if (currentFilter.category.isNotBlank()) { append(detailSep); append(currentFilter.category) }
    }
    val isFilterActive = currentFilter.hasAnyActiveFilter
    val historyRows = remember(historyList, currentLocale) {
        val monthKeys = historyList.map { historyMonthKey(it.timestamp) }
        val showMonthHeaders = monthKeys.distinct().size > 1

        buildList<HistoryListRow> {
            var previousMonthKey: String? = null

            historyList.forEach { entry ->
                val monthKey = historyMonthKey(entry.timestamp)
                if (showMonthHeaders && monthKey != previousMonthKey) {
                    add(
                        HistoryListRow.MonthHeader(
                            key = "month-$monthKey-${entry.uid}",
                            label = formatHistoryMonthLabel(entry.timestamp, currentLocale)
                        )
                    )
                }

                previousMonthKey = monthKey

                add(HistoryListRow.EntryItem(entry))
            }
        }
    }
    val renameEntryTimestampText = remember(entryToRename?.timestamp, currentLocale) {
        entryToRename?.timestamp
            ?.takeIf { it.isNotBlank() }
            ?.let { formatHistoryEntryContextTimestamp(it, currentLocale) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .statusBarsPadding()
                .padding(horizontal = spacing.xl, vertical = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.history_root_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                onClick = {
                    draftFilter = currentFilter
                    showFilterSheet = true
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
                    ) {
                        Text(
                            text = stringResource(R.string.filter),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = filterSummary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isFilterActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isFilterActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isFilterActive) {
                        IconButton(
                            onClick = { onSetFilter(HistoryFilter()) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.history_filter_reset),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.filter),
                        tint = if (isFilterActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = spacing.xs)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (historyList.isEmpty()) {
                    HistoryEmptyState(
                        modifier = Modifier.fillMaxSize(),
                        title = stringResource(
                            if (hasAnyHistoryEntries) {
                                R.string.history_filtered_empty_title
                            } else {
                                R.string.history_empty_title
                            }
                        ),
                        message = stringResource(
                            if (hasAnyHistoryEntries) {
                                R.string.history_filtered_empty_message
                            } else {
                                R.string.history_empty_message
                            }
                        )
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = spacing.sm,
                            bottom = spacing.xxl
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        items(
                            items = historyRows,
                            key = { row ->
                                when (row) {
                                    is HistoryListRow.EntryItem -> row.entry.uid
                                    is HistoryListRow.MonthHeader -> row.key
                                }
                            }
                        ) { row ->
                            when (row) {
                                is HistoryListRow.EntryItem -> {
                                    val entry = row.entry
                                    HistoryRow(
                                        entry = entry,
                                        onClick = { onSelect(entry) },
                                        onRenameClick = {
                                            entryToRename = entry
                                            renameText = entry.displayName
                                            showRenameDialog = true
                                        },
                                        onDeleteClick = {
                                            entryToDelete = entry
                                            showDeleteDialog = true
                                        }
                                    )
                                }

                                is HistoryListRow.MonthHeader -> {
                                    HistoryMonthDivider(label = row.label)
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = spacing.md,
                    end = spacing.md,
                    bottom = snackbarBottomOffset
                )
        )
    }

    if (showDatePickerDialog) {
        val dateToSelect = if (datePickerTargetIsStart) customStartDate else customEndDate

        val datePickerState = key(datePickerTargetIsStart, dateToSelect) {
            rememberDatePickerState(
                initialSelectedDateMillis = dateToSelect
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            )
        }

        DatePickerDialog(
            onDismissRequest = { dismissCustomRangePicker() },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            if (datePickerTargetIsStart) {
                                customStartDate = selectedDate
                                customEndDate = selectedDate
                                datePickerTargetIsStart = false
                            } else {
                                val rangeStart = minOf(customStartDate, selectedDate)
                                val rangeEnd = maxOf(customStartDate, selectedDate)
                                draftFilter = draftFilter.copy(
                                    dateFilter = DateFilter.CustomRange(rangeStart, rangeEnd)
                                )
                                dismissCustomRangePicker()
                            }
                        }
                    }
                ) {
                    Text(if (datePickerTargetIsStart) stringResource(R.string.next) else stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissCustomRangePicker() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = if(datePickerTargetIsStart) stringResource(R.string.select_start_date) else stringResource(R.string.select_end_date),
                        modifier = Modifier.padding(
                            start = spacing.xxl,
                            top = spacing.lg,
                            end = spacing.xxl
                        )
                    )
                }
            )
        }
    }

    if (showRenameDialog && entryToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_file)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    renameEntryTimestampText?.let { createdAt ->
                        Text(
                            text = stringResource(R.string.history_entry_created_at, createdAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.new_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    entryToRename?.let { onRename(it, renameText) }
                    showRenameDialog = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDeleteDialog && entryToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    entryToDelete?.let(onDelete)
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showFilterSheet) {
        HistoryFilterSheet(
            draftFilter = draftFilter,
            availableSuppliers = availableSuppliers,
            availableCategories = availableCategories,
            dateFormatter = dateFormatter,
            onDraftChange = { draftFilter = it },
            onCustomRangeRequest = {
                resetCustomRangeDraft()
                showDatePickerDialog = true
            },
            onDismiss = { showFilterSheet = false },
            onReset = { draftFilter = HistoryFilter() },
            onApply = {
                onSetFilter(draftFilter)
                showFilterSheet = false
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HistoryFilterSheet(
    draftFilter: HistoryFilter,
    availableSuppliers: List<String>,
    availableCategories: List<String>,
    dateFormatter: DateTimeFormatter,
    onDraftChange: (HistoryFilter) -> Unit,
    onCustomRangeRequest: () -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showSupplierPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl)
                .padding(bottom = navBarPadding + spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.history_filter_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            // --- PERIODO ---
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = stringResource(R.string.history_filter_period_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    FilterChip(
                        selected = draftFilter.dateFilter is DateFilter.All,
                        onClick = { onDraftChange(draftFilter.copy(dateFilter = DateFilter.All)) },
                        label = { Text(stringResource(R.string.filter_all)) }
                    )
                    FilterChip(
                        selected = draftFilter.dateFilter is DateFilter.LastMonth,
                        onClick = { onDraftChange(draftFilter.copy(dateFilter = DateFilter.LastMonth)) },
                        label = { Text(stringResource(R.string.filter_current_month)) }
                    )
                    FilterChip(
                        selected = draftFilter.dateFilter is DateFilter.PreviousMonth,
                        onClick = { onDraftChange(draftFilter.copy(dateFilter = DateFilter.PreviousMonth)) },
                        label = { Text(stringResource(R.string.filter_previous_month)) }
                    )
                    val customRangeLabel = (draftFilter.dateFilter as? DateFilter.CustomRange)?.let {
                        stringResource(R.string.history_filter_custom_range_value, it.startDate.format(dateFormatter), it.endDate.format(dateFormatter))
                    } ?: stringResource(R.string.filter_custom_range)
                    FilterChip(
                        selected = draftFilter.dateFilter is DateFilter.CustomRange,
                        onClick = { onCustomRangeRequest() },
                        label = { Text(customRangeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            // --- FORNITORE ---
            if (availableSuppliers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = stringResource(R.string.supplier_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HistoryFilterSelector(
                        selectedValue = draftFilter.supplier,
                        allOptionLabel = stringResource(R.string.history_filter_all_option),
                        onClick = { showSupplierPicker = true }
                    )
                }
            }

            // --- CATEGORIA ---
            if (availableCategories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = stringResource(R.string.category_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HistoryFilterSelector(
                        selectedValue = draftFilter.category,
                        allOptionLabel = stringResource(R.string.history_filter_all_option),
                        onClick = { showCategoryPicker = true }
                    )
                }
            }

            // --- AZIONI ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.history_filter_reset))
                }
                Button(onClick = onApply) {
                    Text(stringResource(R.string.history_filter_apply))
                }
            }
        }
    }

    if (showSupplierPicker) {
        HistoryValuePickerDialog(
            title = stringResource(R.string.supplier_label),
            options = availableSuppliers,
            selectedValue = draftFilter.supplier,
            allOptionLabel = stringResource(R.string.history_filter_all_option),
            searchHint = stringResource(R.string.history_filter_search_hint),
            onSelect = { value ->
                onDraftChange(draftFilter.copy(supplier = value))
                showSupplierPicker = false
            },
            onDismiss = { showSupplierPicker = false }
        )
    }

    if (showCategoryPicker) {
        HistoryValuePickerDialog(
            title = stringResource(R.string.category_label),
            options = availableCategories,
            selectedValue = draftFilter.category,
            allOptionLabel = stringResource(R.string.history_filter_all_option),
            searchHint = stringResource(R.string.history_filter_search_hint),
            onSelect = { value ->
                onDraftChange(draftFilter.copy(category = value))
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterSelector(
    selectedValue: String,
    allOptionLabel: String,
    onClick: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val isActive = selectedValue.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isActive) selectedValue else allOptionLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HistoryValuePickerDialog(
    title: String,
    options: List<String>,
    selectedValue: String,
    allOptionLabel: String,
    searchHint: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredOptions = remember(options, searchQuery) {
        if (searchQuery.isBlank()) options
        else options.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(searchHint) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    item(key = "_all") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect("") }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedValue.isBlank(),
                                onClick = { onSelect("") }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = allOptionLabel,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    items(filteredOptions, key = { it }) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedValue == option,
                                onClick = { onSelect(option) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryMonthDivider(label: String) {
    val spacing = MaterialTheme.appSpacing

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntryListItem,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val statusIconsBottomInset = spacing.xxl + spacing.sm
    val statusIconsEndInset = spacing.xxl + spacing.xxl + spacing.sm
    val currentConfiguration = LocalConfiguration.current
    val currentLocale = remember(currentConfiguration) { Locale.getDefault() }
    val detailsSeparator = stringResource(R.string.history_details_separator)
    val displayTimestamp = remember(entry.timestamp, currentLocale) {
        formatHistoryTimestamp(entry.timestamp, currentLocale)
    }
    val genericTitleFallback = stringResource(R.string.history_session_title_fallback_generic)
    val titleTimestampText = remember(entry.timestamp, currentLocale) {
        formatHistoryEntryContextTimestamp(entry.timestamp, currentLocale)
    }
    val contextTitleFallback = stringResource(
        R.string.history_session_title_fallback_context,
        entry.supplier.trim(),
        titleTimestampText
    )
    val displayTitle = remember(
        entry.displayName,
        entry.supplier,
        entry.timestamp,
        currentLocale,
        contextTitleFallback,
        genericTitleFallback
    ) {
        formatHistorySessionDisplayTitle(
            displayName = entry.displayName,
            supplier = entry.supplier,
            timestamp = entry.timestamp,
            locale = currentLocale,
            contextFallback = contextTitleFallback,
            genericFallback = genericTitleFallback
        )
    }
    val normalizedDisplayTitle = remember(displayTitle) {
        normalizeHistoryComparisonText(displayTitle)
    }
    val metadataSegments = remember(
        displayTimestamp,
        entry.supplier,
        entry.category,
        normalizedDisplayTitle
    ) {
        buildList {
            add(displayTimestamp)

            entry.supplier.trim()
                .takeIf {
                    it.isNotBlank() &&
                        !normalizedDisplayTitle.contains(normalizeHistoryComparisonText(it))
                }
                ?.let(::add)

            entry.category.trim()
                .takeIf {
                    it.isNotBlank() &&
                        !normalizedDisplayTitle.contains(normalizeHistoryComparisonText(it))
                }
                ?.let(::add)
        }
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteClick()
                    false
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onRenameClick()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.targetValue
            if (direction != SwipeToDismissBoxValue.Settled) {
                val containerColor = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.inverseSurface
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                }
                val contentColor = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.inverseOnSurface
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onError
                    SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.onSurface
                }
                val icon = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit to stringResource(R.string.rename_file)
                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete to stringResource(R.string.delete)
                    SwipeToDismissBoxValue.Settled -> null
                }
                val align = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    SwipeToDismissBoxValue.Settled -> Alignment.Center
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(containerColor)
                        .padding(horizontal = spacing.xl),
                    contentAlignment = align
                ) {
                    icon?.let { (img, desc) -> Icon(img, contentDescription = desc, tint = contentColor) }
                }
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = spacing.md,
                            bottom = statusIconsBottomInset,
                            start = spacing.lg,
                            end = statusIconsEndInset
                        ),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (entry.isManualEntry) {
                            Spacer(Modifier.width(spacing.sm))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.manual_entry_indicator),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    if (metadataSegments.isNotEmpty()) {
                        Text(
                            text = metadataSegments.joinToString(detailsSeparator),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (entry.totalItems > 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(historyCompactSummarySpacing)) {
                            Text(
                                text = stringResource(
                                    R.string.count_label_format,
                                    stringResource(R.string.label_value_format, stringResource(R.string.summary_label), formatClCount(entry.totalItems)),
                                    stringResource(R.string.products_label)
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.label_value_format, stringResource(R.string.order_value_label), formatClSummaryMoney(entry.orderTotal)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (entry.missingItems > 0) {
                                Text(
                                    text = stringResource(R.string.label_value_format, stringResource(R.string.missing_items_label), formatClCount(entry.missingItems)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                text = stringResource(R.string.label_value_format, stringResource(R.string.payment_total_label), formatClSummaryMoney(entry.paymentTotal)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = spacing.md, bottom = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    StatusIcon(
                        baseIcon = Icons.Default.Sync,
                        badgeType = when (entry.syncStatus) {
                            SyncStatus.SYNCED_SUCCESSFULLY -> BadgeType.SUCCESS
                            SyncStatus.ATTEMPTED_WITH_ERRORS -> BadgeType.WARNING
                            SyncStatus.NOT_ATTEMPTED -> BadgeType.NONE
                        },
                        contentDescription = stringResource(R.string.sync_status)
                    )
                    StatusIcon(
                        baseIcon = Icons.Default.FileDownload,
                        badgeType = if (entry.wasExported) BadgeType.SUCCESS else BadgeType.NONE,
                        contentDescription = stringResource(R.string.export_status)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(
    modifier: Modifier = Modifier,
    title: String,
    message: String
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}


enum class BadgeType {
    NONE, SUCCESS, WARNING
}

@Composable
private fun StatusIcon(
    baseIcon: ImageVector,
    badgeType: BadgeType,
    contentDescription: String
) {
    val appColors = MaterialTheme.appColors
    val spacing = MaterialTheme.appSpacing
    Box {
        Icon(
            imageVector = baseIcon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (badgeType) {
            BadgeType.SUCCESS -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.status_completed),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = spacing.xxs, y = -spacing.xxs),
                    tint = appColors.success
                )
            }
            BadgeType.WARNING -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.status_warning),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = spacing.xxs, y = -spacing.xxs),
                    tint = appColors.warning
                )
            }
            BadgeType.NONE -> { /* Non mostrare nulla */ }
        }
    }
}

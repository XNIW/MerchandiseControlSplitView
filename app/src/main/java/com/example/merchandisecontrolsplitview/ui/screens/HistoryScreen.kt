package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val historyTimestampParser: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private val historyCompactSummarySpacing = 2.dp

private fun formatHistoryTimestamp(timestamp: String, locale: Locale = Locale.getDefault()): String {
    val outputFormatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)

    return runCatching {
        LocalDateTime.parse(timestamp, historyTimestampParser).format(outputFormatter)
    }.getOrElse { timestamp }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyList: List<HistoryEntryListItem>,
    currentFilter: DateFilter,
    hasAnyHistoryEntries: Boolean,
    historyActionMessage: String?,
    onSelect: (HistoryEntryListItem) -> Unit,
    onRename: (HistoryEntryListItem, String) -> Unit,
    onDelete: (HistoryEntryListItem) -> Unit,
    onHistoryActionMessageConsumed: () -> Unit,
    onSetFilter: (DateFilter) -> Unit,
    onBack: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    var showRenameDialog by remember { mutableStateOf(false) }
    var entryToRename by remember { mutableStateOf<HistoryEntryListItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<HistoryEntryListItem?>(null) }

    var showFilterMenu by remember { mutableStateOf(false) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var datePickerTargetIsStart by remember { mutableStateOf(true) }
    var customStartDate by remember { mutableStateOf(LocalDate.now()) }
    var customEndDate by remember { mutableStateOf(LocalDate.now()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
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

    val filterLabel = when (val filter = currentFilter) {
        is DateFilter.All -> stringResource(R.string.filter_all)
        is DateFilter.LastMonth -> stringResource(R.string.filter_current_month)
        is DateFilter.PreviousMonth -> stringResource(R.string.filter_previous_month)
        is DateFilter.CustomRange -> stringResource(
            R.string.history_filter_custom_range_value,
            filter.startDate.format(dateFormatter),
            filter.endDate.format(dateFormatter)
        )
    }
    val isFilterActive = currentFilter !is DateFilter.All

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.history_title))
                        if (isFilterActive) {
                            Text(
                                text = stringResource(R.string.history_filter_active, filterLabel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.filter),
                                tint = if (isFilterActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_all)) },
                                onClick = {
                                    onSetFilter(DateFilter.All)
                                    showFilterMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_current_month)) },
                                onClick = {
                                    onSetFilter(DateFilter.LastMonth)
                                    showFilterMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_previous_month)) },
                                onClick = {
                                    onSetFilter(DateFilter.PreviousMonth)
                                    showFilterMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_custom_range)) },
                                onClick = {
                                    resetCustomRangeDraft()
                                    showDatePickerDialog = true
                                    showFilterMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (historyList.isEmpty()) {
                HistoryEmptyState(
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = spacing.sm)
                ) {
                    items(historyList, key = { it.uid }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onClick = { onSelect(entry) },
                            onRenameClick = {
                                entryToRename = entry
                                renameText = entry.id
                                showRenameDialog = true
                            },
                            onDeleteClick = {
                                entryToDelete = entry
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }
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
                                onSetFilter(DateFilter.CustomRange(rangeStart, rangeEnd))
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
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
    val currentLocale = Locale.getDefault()
    val displayTimestamp = remember(entry.timestamp, currentLocale) {
        formatHistoryTimestamp(entry.timestamp, currentLocale)
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
                .padding(vertical = spacing.xxs)
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
                            text = entry.id,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // Controlla se è un'entry manuale basandosi sul nome
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
                    Text(
                        text = displayTimestamp,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val details = mutableListOf<String>()
                    if (entry.supplier.isNotBlank()) details.add(entry.supplier)
                    if (entry.category.isNotBlank()) details.add(entry.category)

                    if(details.isNotEmpty()){
                        Text(
                            text = details.joinToString(stringResource(R.string.history_details_separator)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    title: String,
    message: String
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = Modifier
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

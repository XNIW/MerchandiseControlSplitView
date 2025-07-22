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
import androidx.compose.material.icons.filled.FilterList // NUOVA ICONA
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter // NUOVO IMPORT
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyList: List<HistoryEntry>,
    onSelect: (HistoryEntry) -> Unit,
    onRename: (HistoryEntry, String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onSetFilter: (DateFilter) -> Unit, // <-- NUOVO: Callback per impostare il filtro
    onBack: () -> Unit
) {
    // Stati per i dialog di rename/delete (invariati)
    var showRenameDialog by remember { mutableStateOf(false) }
    var entryToRename by remember { mutableStateOf<HistoryEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    // --- NUOVA GESTIONE STATI PER FILTRI ---
    var showFilterMenu by remember { mutableStateOf(false) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var datePickerTargetIsStart by remember { mutableStateOf(true) } // true = data inizio, false = data fine
    var customStartDate by remember { mutableStateOf<LocalDate?>(null) }
    var customEndDate by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                // --- NUOVA SEZIONE ACTIONS PER IL FILTRO ---
                actions = {
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.filter))
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
                                    customStartDate = LocalDate.now()
                                    customEndDate = LocalDate.now()
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
        // LazyColumn e Dialog sono invariati
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
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

    // --- NUOVO DIALOG PER LA SELEZIONE DELLA DATA ---
    if (showDatePickerDialog) {
        val dateToSelect = if (datePickerTargetIsStart) customStartDate else customEndDate

        // SOLUZIONE CORRETTA: Usa il Composable `key` per resettare lo stato.
        val datePickerState = key(datePickerTargetIsStart) {
            rememberDatePickerState(
                initialSelectedDateMillis = dateToSelect
                    ?.atStartOfDay(ZoneId.systemDefault())
                    ?.toInstant()
                    ?.toEpochMilli()
            )
        }

        // Il resto del codice del DatePickerDialog rimane invariato...
        DatePickerDialog(
            onDismissRequest = { showDatePickerDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (datePickerTargetIsStart) {
                            customStartDate = selectedDate
                            // Passa automaticamente alla selezione della data di fine
                            datePickerTargetIsStart = false
                        } else {
                            customEndDate = selectedDate
                            // Una volta selezionata anche la data di fine, applica il filtro
                            showDatePickerDialog = false
                            if (customStartDate != null) {
                                onSetFilter(DateFilter.CustomRange(customStartDate!!, customEndDate!!))
                            }
                        }
                    }
                }) {
                    // Il testo del bottone cambia correttamente in base a `datePickerTargetIsStart`
                    Text(if (datePickerTargetIsStart) stringResource(R.string.next) else stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = if(datePickerTargetIsStart) stringResource(R.string.select_start_date) else stringResource(R.string.select_end_date),
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp)
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
    entry: HistoryEntry,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    // Il codice di HistoryRow rimane invariato
    val currencyFormat = remember {
        val chileLocale = Locale.Builder().setLanguage("es").setRegion("CL").build()
        NumberFormat.getCurrencyInstance(chileLocale).apply {
            maximumFractionDigits = 0
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
                val color = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Color.DarkGray
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFB00020)
                    else -> Color.Transparent
                }
                val icon = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit to stringResource(R.string.rename_file)
                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete to stringResource(R.string.delete)
                    else -> null
                }
                val align = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    else -> Alignment.Center
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 20.dp),
                    contentAlignment = align
                ) {
                    icon?.let { (img, desc) -> Icon(img, contentDescription = desc, tint = Color.White) }
                }
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 32.dp, start = 16.dp, end = 56.dp)
                ) {
                    Text(
                        text = entry.id,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${stringResource(R.string.date_label)}: ${entry.timestamp}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val details = mutableListOf<String>()
                    if (entry.supplier.isNotBlank()) details.add(entry.supplier)
                    if (entry.category.isNotBlank()) details.add(entry.category)

                    if(details.isNotEmpty()){
                        Text(
                            text = details.joinToString(" | "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (entry.totalItems > 0) {
                        Text(
                            text = "${stringResource(R.string.summary_label)}: ${entry.totalItems} ${stringResource(R.string.products_label)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${stringResource(R.string.order_value_label)}: ${currencyFormat.format(entry.orderTotal)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // ✅ NUOVA RIGA PER I PRODOTTI MANCANTI
                        if (entry.missingItems > 0) {
                            Text(
                                text = "${stringResource(R.string.missing_items_label)}: ${entry.missingItems}", // Aggiungi stringa "Prodotti mancanti"
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error // Evidenzia in rosso
                            )
                        }
                        Text(
                            text = "${stringResource(R.string.payment_total_label)}: ${currencyFormat.format(entry.paymentTotal)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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


enum class BadgeType {
    NONE, SUCCESS, WARNING
}

@Composable
private fun StatusIcon(
    baseIcon: ImageVector,
    badgeType: BadgeType,
    contentDescription: String
) {
    // Il codice di StatusIcon rimane invariato
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
                        .offset(x = 4.dp, y = (-4).dp),
                    tint = Color(0xFF00C853)
                )
            }
            BadgeType.WARNING -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.status_warning),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp),
                    tint = Color(0xFFFFA000)
                )
            }
            BadgeType.NONE -> { /* Non mostrare nulla */ }
        }
    }
}
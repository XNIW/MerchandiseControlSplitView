package com.example.merchandisecontrolsplitview.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader

/**
 * Screen for selecting columns before generating the filtered sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreGenerateScreen(
    viewModel: ExcelViewModel,
    onGenerate: (String) -> Unit,
    onBack: () -> Unit
) {
    // State from ViewModel
    val excelData = viewModel.excelData
    val selectedColumns = viewModel.selectedColumns
    val editableValues = viewModel.editableValues
    val completeStates = viewModel.completeStates
    val isLoading by viewModel.isLoading
    val loadError by viewModel.loadError
    val context = LocalContext.current
    val headerTypes = viewModel.headerTypes

    // Local UI state
    var editMode by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    var showSupplierDialog by remember { mutableStateOf(false) }
    var supplierName by remember { mutableStateOf("") }

    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }

    val possibleKeys = listOf(
        "barcode", "quantity", "purchasePrice", "retailPrice", "totalPrice",
        "productName", "secondProductName", "itemNumber", "supplier", "rowNumber",
        "discount", "discountedPrice"
    )
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }

    // Intercept back gesture to confirm exit
    BackHandler {
        showExitDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pre-Genera") },
                navigationIcon = {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Gestisci la sincronizzazione qui */ }) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = "Sincronizza"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                loadError != null -> {
                    Text(
                        text = loadError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                excelData.isNotEmpty() -> {
                    val localizedData = listOf(excelData[0].map { getLocalizedHeader(context, it) }) + excelData.drop(1)
                    ZoomableExcelGrid(
                        data = localizedData,
                        cellWidth = 120.dp,
                        cellHeight = 48.dp,
                        selectedColumns = selectedColumns,
                        editableValues = editableValues,
                        completeStates = completeStates,
                        searchMatches = emptySet(),
                        generated = false,
                        editMode = editMode,
                        onCompleteToggle = {},
                        onCellEditRequest = { _, _ -> },
                        onQuantityCellClick = {},
                        onPriceCellClick = {},
                        onRowCellClick = { },
                        onHeaderClick = { colIdx -> headerDialogIndex = colIdx },
                        headerTypes = headerTypes
                    )
                }
            }

            // FABs: Seleziona Tutto / Modifica / Genera
            if (excelData.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    if (!editMode) {
                        FloatingActionButton(onClick = {
                            val anyUnselected = selectedColumns.any { !it }
                            selectedColumns.forEachIndexed { idx, _ ->
                                selectedColumns[idx] = anyUnselected
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = stringResource(R.string.select_all)
                            )
                        }
                    }
                    FloatingActionButton(onClick = {
                        editMode = !editMode
                    }) {
                        Icon(
                            imageVector = if (editMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (editMode) stringResource(R.string.exit_edit) else stringResource(R.string.edit)
                        )
                    }
                    if (!editMode) {
                        FloatingActionButton(onClick = {
                            supplierName = ""
                            showSupplierDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.generate_filtered_sheet)
                            )
                        }
                    }
                }
            }

            // Dialog per conferma di uscita
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text(stringResource(R.string.exit_confirm_title)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showExitDialog = false
                            onBack()
                        }) {
                            Text(stringResource(R.string.exit))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            if (showSupplierDialog) {
                AlertDialog(
                    onDismissRequest = { showSupplierDialog = false },
                    title = { Text(stringResource(R.string.supplier_dialog_title)) },
                    text = {
                        OutlinedTextField(
                            value = supplierName,
                            onValueChange = { supplierName = it },
                            label = { Text(stringResource(R.string.supplier_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (supplierName.isNotBlank()) {
                                    showSupplierDialog = false
                                    onGenerate(supplierName) // passa il fornitore!
                                }
                            }
                        ) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSupplierDialog = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            if (showCustomHeaderDialog && headerDialogIndex != null) {
                AlertDialog(
                    onDismissRequest = {
                        showCustomHeaderDialog = false
                        customHeader = ""
                        headerDialogIndex = null
                    },
                    title = { Text(stringResource(R.string.custom_header_dialog_title)) },
                    text = {
                        OutlinedTextField(
                            value = customHeader,
                            onValueChange = { customHeader = it },
                            label = { Text(stringResource(R.string.custom_header_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val idx = headerDialogIndex!!
                                if (customHeader.isNotBlank()) {
                                    viewModel.setHeaderType(idx, customHeader.trim())
                                    showCustomHeaderDialog = false
                                    customHeader = ""
                                    headerDialogIndex = null
                                }
                            }
                        ) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showCustomHeaderDialog = false
                                customHeader = ""
                            }
                        ) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            headerDialogIndex?.let { colIdx ->
                AlertDialog(
                    onDismissRequest = { headerDialogIndex = null },
                    title = { Text(stringResource(R.string.select_column_type)) },
                    text = {
                        Column {
                            possibleKeys.forEach { key ->
                                TextButton(
                                    onClick = {
                                        viewModel.setHeaderType(colIdx, key)
                                        headerDialogIndex = null
                                    }
                                ) { Text(getLocalizedHeader(context, key)) }
                            }
                            TextButton(
                                onClick = {
                                    showCustomHeaderDialog = true
                                    headerDialogIndex = colIdx // (importante per sapere a che colonna applicare)
                                }
                            ) {
                                Text(stringResource(R.string.custom_column_type))
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { headerDialogIndex = null }) { Text(stringResource(R.string.close)) }

                    }
                )
            }
        }
    }
}
package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState

/**
 * Schermata di anteprima di un file Excel.
 * Funge da punto di partenza sia per la generazione di un nuovo foglio filtrato
 * sia per avviare il flusso di importazione/sincronizzazione con il database.
 *
 * @param excelViewModel ViewModel per la gestione dello stato del foglio Excel (dati, selezioni).
 * @param databaseUiState Lo stato della UI dal DatabaseViewModel, per mostrare feedback durante l'analisi.
 * @param onGenerate Callback per generare un nuovo foglio Excel filtrato.
 * @param onBack Callback per tornare alla schermata precedente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreGenerateScreen(
    excelViewModel: ExcelViewModel,
    databaseUiState: UiState, // <-- MODIFICA: Aggiunto per reagire allo stato di analisi
    onGenerate: (String) -> Unit,
    onBack: () -> Unit
) {
    // State from ExcelViewModel
    val excelData = excelViewModel.excelData
    val selectedColumns = excelViewModel.selectedColumns
    val editableValues = excelViewModel.editableValues
    val completeStates = excelViewModel.completeStates
    val isExcelLoading by excelViewModel.isLoading
    val excelLoadError by excelViewModel.loadError
    val headerTypes = excelViewModel.headerTypes

    // Local UI state
    val context = LocalContext.current
    var editMode by remember { mutableStateOf(false) }
    var showSupplierDialog by remember { mutableStateOf(false) }
    var supplierName by remember { mutableStateOf("") }
    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // Quando un file viene scelto, ricarica i dati nel ViewModel.
        // La UI si aggiornerà automaticamente.
        uri?.let { excelViewModel.loadFromUri(context, it) }
    }

    val possibleKeys = listOf(
        "barcode", "quantity", "purchasePrice", "retailPrice", "totalPrice",
        "productName", "secondProductName", "itemNumber", "supplier", "rowNumber",
        "discount", "discountedPrice"
    )

    // Intercept back gesture to confirm exit
    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anteprima File") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                // --- INIZIO NUOVO CODICE ---
                actions = {
                    IconButton(onClick = {
                        // Lancia il selettore di file
                        launcher.launch(arrayOf(
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        ))
                    }) {
                        Icon(
                            Icons.Default.Refresh, // O Icons.Default.FolderOpen
                            contentDescription = stringResource(R.string.reload_file)
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
            // --- MODIFICA: Gestione unificata degli stati di caricamento ed errore ---
            val isAnalysisInProgress = databaseUiState is UiState.Loading
            val analysisError = (databaseUiState as? UiState.Error)?.message

            when {
                // Mostra il caricamento se sta leggendo l'Excel O se sta analizzando per l'import
                isExcelLoading || isAnalysisInProgress -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(if(isAnalysisInProgress) "Analisi in corso..." else "Caricamento file...")
                    }
                }
                // Mostra l'errore se si è verificato durante il caricamento O durante l'analisi
                excelLoadError != null || analysisError != null -> {
                    Text(
                        text = excelLoadError ?: analysisError ?: "Errore sconosciuto",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
                    )
                }
                // Se non ci sono caricamenti o errori, e ci sono dati, mostra la griglia
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
                        errorRowIndexes = emptySet(),
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

            // FABs per il flusso di "Generazione"
            if (excelData.isNotEmpty() && !isAnalysisInProgress) {
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
                            selectedColumns.forEachIndexed { idx, _ -> selectedColumns[idx] = anyUnselected }
                        }) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.select_all))
                        }
                    }
                    FloatingActionButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (editMode) stringResource(R.string.exit_edit) else stringResource(R.string.edit)
                        )
                    }
                    if (!editMode) {
                        FloatingActionButton(onClick = {
                            supplierName = ""
                            showSupplierDialog = true
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.generate_filtered_sheet))
                        }
                    }
                }
            }

            if (showSupplierDialog) {
                AlertDialog(
                    onDismissRequest = { showSupplierDialog = false },
                    title = { Text(stringResource(R.string.supplier_dialog_title)) },
                    text = {
                        OutlinedTextField(
                            value = supplierName, onValueChange = { supplierName = it },
                            label = { Text(stringResource(R.string.supplier_label)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (supplierName.isNotBlank()) { showSupplierDialog = false; onGenerate(supplierName) }
                        }) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = { TextButton(onClick = { showSupplierDialog = false }) { Text(stringResource(R.string.cancel)) } }
                )
            }

            if (showCustomHeaderDialog && headerDialogIndex != null) {
                AlertDialog(
                    onDismissRequest = { showCustomHeaderDialog = false; customHeader = ""; headerDialogIndex = null },
                    title = { Text(stringResource(R.string.custom_header_dialog_title)) },
                    text = {
                        OutlinedTextField(
                            value = customHeader, onValueChange = { customHeader = it },
                            label = { Text(stringResource(R.string.custom_header_label)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val idx = headerDialogIndex!!
                            if (customHeader.isNotBlank()) {
                                excelViewModel.setHeaderType(idx, customHeader.trim())
                                showCustomHeaderDialog = false; customHeader = ""; headerDialogIndex = null
                            }
                        }) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = { TextButton(onClick = { showCustomHeaderDialog = false; customHeader = "" }) { Text(stringResource(R.string.cancel)) } }
                )
            }

            headerDialogIndex?.let { colIdx ->
                AlertDialog(
                    onDismissRequest = { headerDialogIndex = null },
                    title = { Text(stringResource(R.string.select_column_type)) },
                    text = {
                        Column {
                            possibleKeys.forEach { key ->
                                TextButton(onClick = { excelViewModel.setHeaderType(colIdx, key); headerDialogIndex = null }) { Text(getLocalizedHeader(context, key)) }
                            }
                            TextButton(onClick = { showCustomHeaderDialog = true; headerDialogIndex = colIdx }) { Text(stringResource(R.string.custom_column_type)) }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { headerDialogIndex = null }) { Text(stringResource(R.string.close)) } }
                )
            }
        }
    }
}

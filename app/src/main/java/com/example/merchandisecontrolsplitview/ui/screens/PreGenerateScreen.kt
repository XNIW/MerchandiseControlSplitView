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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import kotlinx.coroutines.launch

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
    databaseUiState: UiState,
    databaseViewModel: DatabaseViewModel,
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
    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }

    // --- STATO PER IL NUOVO DIALOGO FORNITORE ---
    val supplierInputText by databaseViewModel.supplierInputText.collectAsState()
    var showSupplierDialog by remember { mutableStateOf(false) }
    var selectedSupplier by remember { mutableStateOf<Supplier?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // Raccogli la lista di fornitori dal ViewModel
    val supplierSuggestions by databaseViewModel.suppliers.collectAsState()
    val scope = rememberCoroutineScope()

    val reloadLauncher = rememberLauncherForActivityResult(
        // 1. Cambia il contratto in OpenMultipleDocuments
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> // 2. La callback ora riceve una lista di Uri
        if (uris.isNotEmpty()) {
            // 3. Chiama la stessa funzione usata per il caricamento iniziale.
            //    Questa funzione resetta già lo stato prima di caricare i nuovi file.
            excelViewModel.loadFromMultipleUris(context, uris)
        }
    }

    val appendLauncher = rememberLauncherForActivityResult(
        // 1. Usa il contratto per la selezione multipla
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> // 2. Il risultato è una lista di Uri
        // 3. Se la lista non è vuota, passala alla nuova funzione nel ViewModel
        if (uris.isNotEmpty()) {
            excelViewModel.appendFromMultipleUris(context, uris)
        }
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
                actions = {
                    // Bottone per accodare un nuovo file
                    IconButton(onClick = {
                        appendLauncher.launch(arrayOf(
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        ))
                    }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.append_file)
                        )
                    }
                    IconButton(onClick = {
                        reloadLauncher.launch(arrayOf(
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        ))
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reload_file) // Assicurati di avere questa stringa
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
            val isAnalysisInProgress = databaseUiState is UiState.Loading
            val analysisError = (databaseUiState as? UiState.Error)?.message

            when {
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
                excelLoadError != null || analysisError != null -> {
                    Text(
                        text = excelLoadError ?: analysisError ?: "Errore sconosciuto",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
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
                        // --- MODIFICA ONCLICK ---
                        FloatingActionButton(onClick = {
                            // Resetta lo stato nel ViewModel prima di aprire il dialogo
                            databaseViewModel.onSupplierSearchQueryChanged("") // <-- CORREZIONE
                            selectedSupplier = null
                            isDropdownExpanded = false
                            showSupplierDialog = true
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.generate_filtered_sheet))
                        }
                    }
                }
            }

            // --- BLOCCO DIALOGO COMPLETAMENTE SOSTITUITO ---
            if (showSupplierDialog) {
                AlertDialog(
                    onDismissRequest = { showSupplierDialog = false },
                    title = { Text(stringResource(R.string.supplier_dialog_title)) },
                    text = {
                        ExposedDropdownMenuBox(
                            // --- MODIFICA 1: La visibilità dipende solo dallo stato booleano ---
                            expanded = isDropdownExpanded,
                            // --- MODIFICA 2: Cliccando si inverte lo stato ---
                            onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = supplierInputText,
                                onValueChange = { text ->
                                    databaseViewModel.onSupplierSearchQueryChanged(text)
                                    isDropdownExpanded = true
                                },
                                label = { Text(stringResource(R.string.supplier_label)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                trailingIcon = {
                                    Row(
                                        // Aggiungiamo l'allineamento verticale per centrare le icone
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. Mostriamo la X (se il testo non è vuoto) PRIMA del triangolo
                                        if (supplierInputText.isNotBlank()) {
                                            IconButton(onClick = {
                                                databaseViewModel.onSupplierSearchQueryChanged("")
                                                isDropdownExpanded = true // riapre la lista completa!
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Cancella"
                                                )
                                            }
                                        }
                                        // 2. Mostriamo il triangolo del dropdown DOPO la X
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded)
                                    }
                                }
                            )

                            ExposedDropdownMenu(
                                // --- MODIFICA 3: Anche qui la visibilità dipende solo dallo stato ---
                                expanded = isDropdownExpanded,
                                onDismissRequest = { isDropdownExpanded = false }
                            ) {
                                // Mostra un indicatore se la lista è vuota (es. durante il caricamento iniziale)
                                if (supplierSuggestions.isEmpty() && supplierInputText.isBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Nessun fornitore trovato") },
                                        onClick = {},
                                        enabled = false
                                    )
                                }

                                supplierSuggestions.forEach { suggestion ->
                                    DropdownMenuItem(
                                        text = { Text(suggestion.name) },
                                        onClick = {
                                            databaseViewModel.onSupplierSearchQueryChanged(suggestion.name)
                                            selectedSupplier = suggestion
                                            isDropdownExpanded = false
                                        }
                                    )
                                }

                                // Logica per aggiungere un nuovo fornitore (invariata)
                                if (supplierSuggestions.none { it.name.equals(supplierInputText, true) } && supplierInputText.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Aggiungi \"$supplierInputText\"") },
                                        onClick = {
                                            scope.launch {
                                                val newSupplier = databaseViewModel.addSupplier(supplierInputText)
                                                newSupplier?.let {
                                                    databaseViewModel.onSupplierSearchQueryChanged(it.name)
                                                    selectedSupplier = it
                                                    isDropdownExpanded = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                selectedSupplier?.let {
                                    showSupplierDialog = false
                                    onGenerate(it.name)
                                }
                            },
                            enabled = selectedSupplier != null && selectedSupplier?.name == supplierInputText
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSupplierDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
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
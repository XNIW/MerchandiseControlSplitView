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
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Category // <-- IMPORT AGGIUNTO
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreGenerateScreen(
    excelViewModel: ExcelViewModel,
    databaseUiState: UiState,
    databaseViewModel: DatabaseViewModel,
    onGenerate: (String, String) -> Unit,
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
    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // --- STATO PER DIALOGO UNIFICATO ---
    var showSelectionDialog by remember { mutableStateOf(false) }

    // Stato Fornitore
    val supplierInputText by databaseViewModel.supplierInputText.collectAsState()
    var selectedSupplier by remember { mutableStateOf<Supplier?>(null) }
    var isSupplierDropdownExpanded by remember { mutableStateOf(false) }
    val supplierSuggestions by databaseViewModel.suppliers.collectAsState()

    // Stato Categoria
    val categoryInputText by databaseViewModel.categoryInputText.collectAsState()
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }
    val categorySuggestions by databaseViewModel.categories.collectAsState()


    val reloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            excelViewModel.loadFromMultipleUris(context, uris)
        }
    }

    val appendLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            excelViewModel.appendFromMultipleUris(context, uris)
        }
    }

    val possibleKeys = listOf(
        "barcode", "quantity", "purchasePrice", "retailPrice", "totalPrice",
        "productName", "secondProductName", "itemNumber", "supplier", "rowNumber",
        "discount", "discountedPrice"
    )

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_file_title)) },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        appendLauncher.launch(arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.append_file))
                    }
                    IconButton(onClick = {
                        reloadLauncher.launch(arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reload_file))
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
                        val statusText = if(isAnalysisInProgress) stringResource(R.string.analysis_in_progress) else stringResource(R.string.loading_file)
                        Text(statusText)
                    }
                }
                excelLoadError != null || analysisError != null -> {
                    Text(
                        text = excelLoadError ?: analysisError ?: stringResource(R.string.unknown_error),
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
                        editMode = false,
                        onCompleteToggle = {},
                        onCellEditRequest = { _, _ -> },
                        onQuantityCellClick = {},
                        onPriceCellClick = {},
                        onRowCellClick = { },
                        headerTypes = headerTypes,
                        // --- MODIFICHE E AGGIUNTE ---
                        // Passa la funzione per controllare se una colonna è essenziale
                        isColumnEssential = { colIdx -> excelViewModel.isColumnEssential(colIdx) },
                        // Il click sulla cella ora gestisce la selezione/deselezione protetta
                        onHeaderClick = { colIdx -> excelViewModel.toggleColumnSelection(colIdx) },
                        // Il click sull'icona di modifica apre il dialogo per cambiare tipo
                        onHeaderEditClick = { colIdx -> headerDialogIndex = colIdx },
                        isManualEntry = false
                    )
                }
            }

            if (excelData.isNotEmpty() && !isAnalysisInProgress) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    FloatingActionButton(onClick = {
                        // MODIFICA: Chiama la nuova funzione protetta del ViewModel
                        excelViewModel.toggleSelectAll()
                    }) {
                        Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.select_all))
                    }
                    FloatingActionButton(onClick = {
                        // Resetta lo stato nel ViewModel prima di aprire il dialogo
                        databaseViewModel.onSupplierSearchQueryChanged("")
                        databaseViewModel.onCategorySearchQueryChanged("")
                        selectedSupplier = null
                        selectedCategory = null
                        isSupplierDropdownExpanded = false
                        isCategoryDropdownExpanded = false
                        showSelectionDialog = true
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.generate_filtered_sheet))
                    }
                }
            }

            // --- DIALOGO UNICO PER FORNITORE E CATEGORIA ---
            if (showSelectionDialog) {
                // --- NUOVO: Logica di validazione ---
                val headers = excelViewModel.excelData.firstOrNull() ?: emptyList()
                val missingEssentialColumns = setOf("barcode", "productName")
                    .filterNot { headers.contains(it) }

                AlertDialog(
                    onDismissRequest = { showSelectionDialog = false },
                    title = { Text(stringResource(R.string.supplier_and_category_dialog_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                            // --- NUOVO: Messaggio di errore se mancano colonne essenziali ---
                            if (missingEssentialColumns.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.error_missing_essential_columns_prompt,
                                        missingEssentialColumns.joinToString { getLocalizedHeader(context, it) }
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                            // Menu a discesa per il Fornitore
                            ExposedDropdownMenuBox(
                                expanded = isSupplierDropdownExpanded,
                                onExpandedChange = { isSupplierDropdownExpanded = !isSupplierDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = supplierInputText,
                                    onValueChange = {
                                        databaseViewModel.onSupplierSearchQueryChanged(it)
                                        isSupplierDropdownExpanded = true
                                    },
                                    label = { Text(stringResource(R.string.supplier_label)) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = isSupplierDropdownExpanded,
                                    onDismissRequest = { isSupplierDropdownExpanded = false }
                                ) {
                                    supplierSuggestions.forEach { suggestion ->
                                        DropdownMenuItem(
                                            text = { Text(suggestion.name) },
                                            onClick = {
                                                databaseViewModel.onSupplierSearchQueryChanged(suggestion.name)
                                                selectedSupplier = suggestion
                                                isSupplierDropdownExpanded = false
                                            }
                                        )
                                    }
                                    if (supplierSuggestions.none { it.name.equals(supplierInputText, true) } && supplierInputText.isNotBlank()) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.add_new_supplier_prompt, supplierInputText)) },
                                            onClick = {
                                                scope.launch {
                                                    databaseViewModel.addSupplier(supplierInputText)?.let {
                                                        databaseViewModel.onSupplierSearchQueryChanged(it.name)
                                                        selectedSupplier = it
                                                        isSupplierDropdownExpanded = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Menu a discesa per la Categoria
                            ExposedDropdownMenuBox(
                                expanded = isCategoryDropdownExpanded,
                                onExpandedChange = { isCategoryDropdownExpanded = !isCategoryDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = categoryInputText,
                                    onValueChange = {
                                        databaseViewModel.onCategorySearchQueryChanged(it)
                                        isCategoryDropdownExpanded = true
                                    },
                                    label = { Text(stringResource(R.string.category_label)) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = isCategoryDropdownExpanded,
                                    onDismissRequest = { isCategoryDropdownExpanded = false }
                                ) {
                                    categorySuggestions.forEach { suggestion ->
                                        DropdownMenuItem(
                                            text = { Text(suggestion.name) },
                                            onClick = {
                                                databaseViewModel.onCategorySearchQueryChanged(suggestion.name)
                                                selectedCategory = suggestion
                                                isCategoryDropdownExpanded = false
                                            }
                                        )
                                    }
                                    if (categorySuggestions.none { it.name.equals(categoryInputText, true) } && categoryInputText.isNotBlank()) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.add_new_category_prompt, categoryInputText)) },
                                            onClick = {
                                                scope.launch {
                                                    databaseViewModel.addCategory(categoryInputText)?.let {
                                                        databaseViewModel.onCategorySearchQueryChanged(it.name)
                                                        selectedCategory = it
                                                        isCategoryDropdownExpanded = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                selectedSupplier?.let { supplier ->
                                    selectedCategory?.let { category ->
                                        showSelectionDialog = false
                                        onGenerate(supplier.name, category.name)
                                    }
                                }
                            },
                            // --- MODIFICA: Il pulsante è abilitato solo se tutti i requisiti sono soddisfatti ---
                            enabled = selectedSupplier != null && selectedSupplier?.name == supplierInputText &&
                                    selectedCategory != null && selectedCategory?.name == categoryInputText &&
                                    missingEssentialColumns.isEmpty() // <-- CONTROLLO FONDAMENTALE
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSelectionDialog = false }) {
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
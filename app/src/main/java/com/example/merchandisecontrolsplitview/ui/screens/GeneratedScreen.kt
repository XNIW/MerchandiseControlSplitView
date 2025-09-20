package com.example.merchandisecontrolsplitview.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity // se hai questa classe nel tuo package
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedStringForInput
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import java.util.Locale
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.AnimatedVisibility // <-- Per risolvere l'errore di riferimento non risolto
import kotlinx.coroutines.delay // <-- Per il debounce nella ricerca
import androidx.compose.ui.platform.LocalFocusManager
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import android.content.Intent
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratedScreen(
    excelViewModel: ExcelViewModel,
    databaseViewModel: DatabaseViewModel,
    onBackToStart: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToDatabase: () -> Unit,   // <--- AGGIUNTO
    entryUid: Long,
    isNewEntry: Boolean,
    isManualEntry: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            excelViewModel.clearOriginalState()
        }
    }

    val excelData by remember { derivedStateOf { excelViewModel.excelData } }
    val editableValues by remember { derivedStateOf { excelViewModel.editableValues } }
    val completeStates by remember { derivedStateOf { excelViewModel.completeStates } }
    val generated by excelViewModel.generated
    val (syncStatus, wasExported, _) = excelViewModel.currentEntryStatus.value
    val errorIndexes by excelViewModel.errorRowIndexes

    // Dialog & search state
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf(setOf<Pair<Int, Int>>()) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var infoRowIndex by remember { mutableIntStateOf(-1) }
    var infoDialogFocusField by remember { mutableIntStateOf(0) }
    var showCalcDialog by remember { mutableStateOf(false) }
    var calcInput by remember { mutableStateOf("") }
    var calcRowIndex by remember { mutableIntStateOf(-1) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    var supplierNameForRename by remember { mutableStateOf(excelViewModel.supplierName) }
    var categoryNameForRename by remember { mutableStateOf(excelViewModel.categoryName) }
    var supplierExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    // Precarica le liste quando apro il dialog
    LaunchedEffect(showRenameDialog) {
        if (showRenameDialog) {
            databaseViewModel.onSupplierSearchQueryChanged("")
            databaseViewModel.onCategorySearchQueryChanged("")
        }
    }

    val historyEntries by excelViewModel.historyEntries.collectAsState()

    var showManualEntryDialog by remember { mutableStateOf(false) }
    var productToEditIndex by remember { mutableStateOf<Int?>(null) }

    var productDataToPrefill by remember { mutableStateOf<com.example.merchandisecontrolsplitview.data.Product?>(null) }
// Modifica il LaunchedEffect per dipendere anche da 'historyEntries'
    LaunchedEffect(entryUid, historyEntries) { // <-- AGGIUNGI historyEntries QUI
        val entry = historyEntries.find { it.uid == entryUid }
        if (entry != null) {
            titleText = entry.id
        }
    }

    var showGenericCalcDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    // Stato per il nuovo dialogo a 3 opzioni (quando si esce da una voce di cronologia)
    var showExitFromHistoryDialog by remember { mutableStateOf(false) }
    // Stato per mostrare il caricamento durante il salvataggio o il ripristino
    var isSavingOrReverting by remember { mutableStateOf(false) }

    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }

    // --- NUOVO STATO PER LA MODALITÀ EDIT DEL DIALOGO ---
    var isInfoDialogInEditMode by remember { mutableStateOf(false) }

    var showExitToHomeDialog by remember { mutableStateOf(false) }

    val secondProductNameState = remember { mutableStateOf(TextFieldValue()) }
    val barcodeState = remember { mutableStateOf(TextFieldValue()) }
    val itemNumberState = remember { mutableStateOf(TextFieldValue()) }
    val quantityState = remember { mutableStateOf(TextFieldValue()) }
    val totalPriceState = remember { mutableStateOf(TextFieldValue()) }
    val purchasePriceState = remember { mutableStateOf(TextFieldValue()) }
    val oldPurchasePriceState = remember { mutableStateOf(TextFieldValue()) }
    val oldRetailPriceState = remember { mutableStateOf(TextFieldValue()) }
    val productNameState = remember { mutableStateOf(TextFieldValue()) }

    var scannedBarcodeForManualAdd by remember { mutableStateOf<String?>(null) }

    val possibleKeys = listOf(
        "barcode", "quantity", "purchasePrice", "retailPrice", "totalPrice",
        "productName", "secondProductName", "itemNumber", "supplier", "rowNumber",
        "discount", "discountedPrice"
    )

    val handleBackPress = {
        // La bozza è considerata vuota se siamo in modalità manuale
        // e la griglia contiene solo la riga dell'intestazione (o nessuna riga).
        val isManualDraftEmpty = isManualEntry && excelViewModel.excelData.size <= 1

        if (isManualDraftEmpty) {
            showExitDialog = true // Mostra dialogo "Elimina bozza?"
        } else if (isNewEntry) {
            // Se è una nuova entry (da file) con dati, esce e basta
            onBackToStart()
        } else {
            // Se è una entry esistente (da cronologia) con modifiche,
            // mostra il dialogo "Salva/Esci/Annulla"
            showExitFromHistoryDialog = true
        }
    }

    val dbUiState by databaseViewModel.uiState.collectAsState()
    val isExporting by excelViewModel.isExporting
    val exportProgress by excelViewModel.exportProgress

    BackHandler {
        handleBackPress()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            if (isManualEntry) {
                val header = excelData.firstOrNull() ?: return@let
                val barcodeIndex = header.indexOf("barcode")
                if (barcodeIndex == -1) return@let

                val rowIndex = excelData.drop(1).indexOfFirst { it.getOrNull(barcodeIndex) == code }

                if (rowIndex != -1) {
                    // Caso 1: Trovato nella griglia corrente -> Apri in modifica
                    productToEditIndex = rowIndex
                    productDataToPrefill = null // Assicurati che non ci siano dati da pre-compilare
                    Toast.makeText(context, context.getString(R.string.product_already_in_list), Toast.LENGTH_SHORT).show()
                    showManualEntryDialog = true
                } else {
                    // Caso 2: Non trovato nella griglia, cerca nel DB principale
                    scope.launch {
                        val productFromDb = databaseViewModel.findProductByBarcode(code)
                        if (productFromDb != null) {
                            // Trovato nel DB -> Apri in aggiunta con dati pre-compilati
                            productToEditIndex = null
                            productDataToPrefill = productFromDb
                            Toast.makeText(context, context.getString(R.string.product_found_in_db), Toast.LENGTH_SHORT).show()
                        } else {
                            // Non trovato da nessuna parte -> Apri in aggiunta con solo il barcode
                            productToEditIndex = null
                            productDataToPrefill = null
                            scannedBarcodeForManualAdd = code
                        }
                        showManualEntryDialog = true
                    }
                }
            } else {
                val matches =
                    excelData.flatMapIndexed { r, row -> row.mapIndexedNotNull { c, v -> if (v == code) r to c else null } }
                        .toSet()
                if (matches.isNotEmpty()) {
                    searchMatches = matches
                    val (r, c) = matches.first()
                    infoRowIndex = r
                    infoDialogFocusField =
                        if (c >= excelData[0].size - 2) c - (excelData[0].size - 2) else 0
                    showInfoDialog = true
                    showSearchDialog = false
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.not_found),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } ?: Toast.makeText(context, context.getString(R.string.no_scanner_result), Toast.LENGTH_SHORT).show()
    }

    val dialogScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            // Aggiorna direttamente lo stato del TextField del codice a barre
            barcodeState.value = TextFieldValue(code)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let {
            scope.launch {
                excelViewModel.exportToUri(context, it)           // <--- nuovo
                excelViewModel.markCurrentEntryAsExported(entryUid)
                Toast.makeText(context, context.getString(R.string.file_exported_successfully), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shareXlsx() {
        scope.launch {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val file = File(dir, "Database_${ts}.xlsx")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            excelViewModel.exportToUri(context, uri)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Inventario")
                putExtra(Intent.EXTRA_TEXT, "File generato dall’app 对货")
            }
            context.startActivity(Intent.createChooser(share, context.getString(R.string.share_xlsx)))
            excelViewModel.markCurrentEntryAsExported(entryUid)
        }
    }

    fun performSearch() {
        val query = searchText.trim()
        if (query.isBlank()) {
            Toast.makeText(context, context.getString(R.string.not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val matches = excelData.flatMapIndexed { r, row ->
            row.mapIndexedNotNull { c, v ->
                val cell = v.trim()
                if (cell.contains(query, ignoreCase = true)) r to c else null
            }
        }.toSet()

        if (matches.isNotEmpty()) {
            searchMatches = matches
            val (r, c) = matches.first()
            infoRowIndex = r
            infoDialogFocusField = if (c >= excelData[0].size - 2) c - (excelData[0].size - 2) else 0
            showInfoDialog = true
            showSearchDialog = false
        } else {
            searchText = ""
            Toast.makeText(context, context.getString(R.string.not_found), Toast.LENGTH_SHORT).show()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        onDispose { snackbarHostState.currentSnackbarData?.dismiss() }
    }

    LaunchedEffect(dbUiState) {
        when (val s = dbUiState) {
            is UiState.Success -> {
                val msg = s.message
                // 1. MOSTRA la snackbar (operazione sospensiva)
                val res = snackbarHostState.showSnackbar(
                    message = msg,
                    actionLabel = context.getString(R.string.open_database),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                // 2. DOPO, consuma lo stato
                databaseViewModel.consumeUiState()

                // La logica successiva resta invariata
                if (res == SnackbarResult.ActionPerformed) onNavigateToDatabase()
            }
            is UiState.Error -> {
                val msg = s.message
                // 1. MOSTRA la snackbar
                snackbarHostState.showSnackbar(
                    message = msg,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                // 2. DOPO, consuma lo stato
                databaseViewModel.consumeUiState()
            }
            is UiState.Idle, is UiState.Loading -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    ),
                    navigationIcon = {
                        IconButton(onClick = { handleBackPress() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    title = {
                        // ogni tap incrementa la chiave e riavvia l'animazione
                        var tapKey by remember { mutableIntStateOf(0) }

                        key(tapKey) {
                            Text(
                                text = titleText.ifBlank { stringResource(R.string.untitled) },
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .clickable { tapKey++ } // ← tap: fai ripartire la marquee
                                    .basicMarquee(
                                        animationMode = MarqueeAnimationMode.Immediately, // parte senza focus
                                        iterations = 1                                     // una sola volta
                                    )
                            )
                        }
                    },
                    actions = {
                        if (excelData.isNotEmpty() && generated) {
                            IconButton(onClick = { showExitToHomeDialog = true }) {
                                Icon(
                                    Icons.Default.Home,
                                    contentDescription = stringResource(R.string.go_to_home)
                                )
                            }
                            IconButton(onClick = {
                                // (tua logica sync/analisi invariata)
                                excelViewModel.errorRowIndexes.value = emptySet()
                                val header = excelData.firstOrNull() ?: return@IconButton
                                val barcodeIdx = header.indexOf("barcode")
                                val productNameIdx = header.indexOf("productName")
                                val quantityIdx = header.indexOf("quantity")
                                val retailPriceIdx = header.indexOf("retailPrice")
                                val categoryIdx = header.indexOf("category")
                                val purchaseIdx = header.indexOf("purchasePrice")

                                val dataRows = excelData.drop(1)
                                val gridDataForAnalysis = if (isManualEntry) {
                                    dataRows.map { rowData ->
                                        mapOf(
                                            "barcode" to (rowData.getOrNull(barcodeIdx) ?: ""),
                                            "productName" to (rowData.getOrNull(productNameIdx) ?: ""),
                                            "quantity" to (rowData.getOrNull(quantityIdx) ?: ""),
                                            "purchasePrice" to (rowData.getOrNull(purchaseIdx) ?: ""),
                                            "retailPrice" to (rowData.getOrNull(retailPriceIdx) ?: ""),
                                            "category" to (rowData.getOrNull(categoryIdx) ?: ""),
                                            "supplier" to context.getString(R.string.supplier_manual)
                                        )
                                    }
                                } else {
                                    dataRows.mapIndexed { rowIndex, rowData ->
                                        val actualRowIndex = rowIndex + 1
                                        val finalQuantityStr = editableValues.getOrNull(actualRowIndex)
                                            ?.getOrNull(0)?.value ?: ""
                                        val finalPriceStr = editableValues.getOrNull(actualRowIndex)
                                            ?.getOrNull(1)?.value ?: ""
                                        val map = header.mapIndexed { colIndex, headerKey ->
                                            headerKey to (rowData.getOrNull(colIndex) ?: "")
                                        }.toMap().toMutableMap()

                                        map["realQuantity"] = finalQuantityStr
                                        map["retailPrice"] = finalPriceStr
                                        map["supplier"] =
                                            excelViewModel.supplierName.ifBlank { "unknown" }
                                        map["category"] =
                                            excelViewModel.categoryName.ifBlank { "unknown" }
                                        map.toMap()
                                    }
                                }

                                if (gridDataForAnalysis.isNotEmpty()) {
                                    databaseViewModel.analyzeGridData(gridDataForAnalysis)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.sync_analysis_started),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.no_valid_rows_to_sync),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }) {
                                StatusIcon(
                                    baseIcon = Icons.Default.Sync,
                                    badgeType = when (syncStatus) {
                                        com.example.merchandisecontrolsplitview.data.SyncStatus.SYNCED_SUCCESSFULLY -> BadgeType.SUCCESS
                                        com.example.merchandisecontrolsplitview.data.SyncStatus.ATTEMPTED_WITH_ERRORS -> BadgeType.WARNING
                                        com.example.merchandisecontrolsplitview.data.SyncStatus.NOT_ATTEMPTED -> BadgeType.NONE
                                    },
                                    contentDescription = stringResource(R.string.sync_with_database)
                                )
                            }

                            // Menu overflow (include "Rinomina" qui)
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.more_actions)
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.export_file)) },
                                        leadingIcon = {
                                            MenuIconWithTick(
                                                base = Icons.Default.FileDownload,
                                                showTick = wasExported
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            saveLauncher.launch(titleText)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.share_xlsx)) },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick = {
                                            menuOpen = false
                                            shareXlsx()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rename_file)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = {
                                            menuOpen = false
                                            renameText = titleText
                                            showRenameDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
                TopInfoChipsBar(
                    supplier = excelViewModel.supplierName.ifBlank { null },
                    category = excelViewModel.categoryName.ifBlank { null },
                    completed = completeStates.count { it },
                    total = (excelData.size - 1).coerceAtLeast(0),
                    exported = wasExported,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )

                HorizontalDivider()
            }
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (isManualEntry && excelData.size <= 1) {
                        // Mostra "Empty State" se siamo in modalità manuale e non ci sono dati
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Text(
                                text = stringResource(R.string.no_products_add_new), // Aggiungi in strings.xml
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    } else if (excelData.isNotEmpty()) {
                        val localizedHeader = excelData[0].map { getLocalizedHeader(context, it) }
                        val localizedData = listOf(localizedHeader) + excelData.drop(1)
                        ZoomableExcelGrid(
                            data = localizedData,
                            cellWidth = 120.dp,
                            cellHeight = 48.dp,
                            selectedColumns = excelViewModel.selectedColumns,
                            editableValues = editableValues,
                            completeStates = completeStates,
                            searchMatches = searchMatches,
                            errorRowIndexes = errorIndexes,
                            generated = true,
                            editMode = false,
                            onCompleteToggle = { row ->
                                completeStates[row] = !completeStates[row]
                                excelViewModel.updateHistoryEntry(entryUid)
                            },
                            onCellEditRequest = { _, _ -> },
                            onQuantityCellClick = { r ->
                                // 👇 Solo se NON è un'entry manuale
                                if (!isManualEntry) {
                                    infoRowIndex = r; infoDialogFocusField = 0; showInfoDialog =
                                        true
                                }
                            },
                            onPriceCellClick = { r ->
                                // 👇 Solo se NON è un'entry manuale
                                if (!isManualEntry) {
                                    infoRowIndex = r; infoDialogFocusField = 1; showInfoDialog =
                                        true
                                }
                            },
                            onRowCellClick = { r ->
                                // 👇 Solo se NON è un'entry manuale
                                if (!isManualEntry) {
                                    infoRowIndex = r; infoDialogFocusField = 0; showInfoDialog =
                                        true
                                } else {
                                    // Per le entry manuali, apriamo il dialog di modifica specifico
                                    productToEditIndex =
                                        r - 1 // r è basato su 1, l'indice è basato su 0
                                    showManualEntryDialog = true
                                }
                            },
                            onHeaderClick = { colIdx -> headerDialogIndex = colIdx },
                            // --- RIGHE AGGIUNTE PER RISOLVERE L'ERRORE ---
                            isColumnEssential = { false }, // In questa schermata nessuna colonna ha la logica "essenziale".
                            onHeaderEditClick = { colIdx ->
                                headerDialogIndex = colIdx
                            }, // Il click sull'icona apre lo stesso dialogo di prima.
                            isManualEntry = isManualEntry
                        )
                    }
                }

                // 1. Dialogo per le NUOVE voci (quando isNewEntry = true)
                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!isSavingOrReverting) showExitDialog = false },
                        title = { Text(stringResource(R.string.discard_and_exit_title)) },
                        text = { Text(stringResource(R.string.discard_and_exit_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    // 1. Nascondi subito il dialogo
                                    showExitDialog = false
                                    // 2. Avvia l'azione completa
                                    scope.launch {
                                        isSavingOrReverting = true
                                        excelViewModel.historyEntries.value.find { it.uid == entryUid }
                                            ?.let { entryToDelete ->
                                                excelViewModel.deleteHistoryEntry(entryToDelete)
                                            }
                                        // L'AGGIUNTA FONDAMENTALE: ripristina lo stato di PreGenerate
                                        excelViewModel.revertToPreGenerateState()
                                        isSavingOrReverting = false
                                        onBackToStart() // Torna indietro
                                    }
                                },
                                enabled = !isSavingOrReverting
                            ) {
                                Text(
                                    stringResource(R.string.discard),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { if (!isSavingOrReverting) showExitDialog = false },
                                enabled = !isSavingOrReverting
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                // 2. NUOVO Dialogo per le voci dalla CRONOLOGIA (quando isNewEntry = false)
                if (showExitFromHistoryDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!isSavingOrReverting) showExitFromHistoryDialog = false
                        },
                        title = { Text(stringResource(R.string.exit_confirmation_title)) },
                        text = {
                            if (isSavingOrReverting) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(stringResource(R.string.saving_changes))
                                }
                            } else {
                                Text(stringResource(R.string.exit_changes_question))
                            }
                        },
                        // --- FIX COMPLETO PER LAYOUT E LOGICA ---
                        confirmButton = {
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Bottone "Esci senza Salvare"
                                TextButton(
                                    onClick = {
                                        showExitFromHistoryDialog = false
                                        scope.launch {
                                            isSavingOrReverting = true
                                            // 1. Ripristina il DB allo stato originale
                                            excelViewModel.revertDatabaseToOriginalState()

                                            // 2. RICARICA lo stato nel ViewModel per coerenza immediata
                                            excelViewModel.historyEntries.value.find { it.uid == entryUid }
                                                ?.let {
                                                    excelViewModel.loadHistoryEntry(it)
                                                }

                                            isSavingOrReverting = false
                                            onBackToStart()
                                        }
                                    },
                                    enabled = !isSavingOrReverting
                                ) {
                                    Text(
                                        stringResource(R.string.exit_without_saving),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // Bottone "Salva ed Esci"
                                Button(
                                    onClick = {
                                        showExitFromHistoryDialog = false
                                        scope.launch {
                                            isSavingOrReverting = true
                                            // La funzione di salvataggio è già corretta
                                            excelViewModel.saveCurrentStateToHistory(entryUid)
                                            isSavingOrReverting = false
                                            onBackToStart()
                                        }
                                    },
                                    enabled = !isSavingOrReverting
                                ) {
                                    Text(stringResource(R.string.save_and_exit))
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    if (!isSavingOrReverting) showExitFromHistoryDialog = false
                                },
                                enabled = !isSavingOrReverting
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                if (excelData.isNotEmpty() && generated) {
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (isManualEntry) {
                            // FAB per la modalità manuale
                            FloatingActionButton(onClick = {
                                val opts = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                    setCaptureActivity(PortraitCaptureActivity::class.java)
                                    setOrientationLocked(true)
                                    setBeepEnabled(true)
                                    setPrompt(context.getString(R.string.scan_prompt))
                                }
                                scanLauncher.launch(opts)
                            }) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = "Scan Barcode")
                            }
                        } else {
                            FloatingActionButton(onClick = {
                                val opts = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                    setCaptureActivity(PortraitCaptureActivity::class.java)
                                    setOrientationLocked(true)
                                    setBeepEnabled(true)
                                    setPrompt(context.getString(R.string.scan_prompt))
                                }
                                scanLauncher.launch(opts)
                            }) {
                                Icon(
                                    Icons.Filled.CameraAlt,
                                    contentDescription = stringResource(R.string.scan_icon_desc)
                                )
                            }

                            FloatingActionButton(onClick = {
                                searchText = ""; showSearchDialog = true
                            }) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.search_icon_desc)
                                )
                            }
                        }
                    }
                }

                if (showManualEntryDialog) {
                    ManualEntryDialog(
                        viewModel = excelViewModel,
                        databaseViewModel = databaseViewModel,
                        rowIndexToEdit = productToEditIndex,
                        entryUid = entryUid,
                        initialBarcode = scannedBarcodeForManualAdd,
                        productToPrefill = productDataToPrefill,
                        onDismiss = {
                            showManualEntryDialog = false
                            scannedBarcodeForManualAdd = null
                            productDataToPrefill = null
                        },
                        onScanNext = {
                            showManualEntryDialog = false
                            // **3. OPZIONI SCANNER CORRETTE.** Anche "Aggiungi e Successivo"
                            // ora lancia lo scanner in modalità verticale.
                            val portraitScanOptions = ScanOptions().apply {
                                setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                setBeepEnabled(true)
                                setPrompt(context.getString(R.string.scan_prompt))
                                setOrientationLocked(true)
                                setCaptureActivity(PortraitCaptureActivity::class.java)
                            }
                            scanLauncher.launch(portraitScanOptions)
                        }
                    )
                }

                if (showSearchDialog) {
                    // Focus + tastiera per il TextField "Inserisci numero"
                    val searchFocusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current

                    LaunchedEffect(showSearchDialog) {
                        if (showSearchDialog) {
                            delay(50)                      // piccolo ritardo per far montare il dialogo
                            searchFocusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    }

                    AlertDialog(
                        onDismissRequest = {
                            keyboardController?.hide()
                            showSearchDialog = false
                        },
                        title = { Text(stringResource(R.string.search_number)) },
                        text = {
                            Column {
                                Button(onClick = {
                                    scanLauncher.launch(
                                        ScanOptions().apply {
                                            setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                            setCaptureActivity(PortraitCaptureActivity::class.java)
                                            setOrientationLocked(true)
                                            setBeepEnabled(true)
                                            setPrompt(context.getString(R.string.scan_prompt))
                                        }
                                    )
                                }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.scanner))
                                }
                                TextField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    label = { Text(stringResource(R.string.insert_number)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii,           // lettere + numeri
                                        imeAction = ImeAction.Search,
                                        capitalization = KeyboardCapitalization.None,
                                        autoCorrectEnabled = false                   // <-- nuovo parametro
                                    ),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        keyboardController?.hide()
                                        performSearch()
                                    }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                keyboardController?.hide()
                                performSearch()
                            }) { Text(stringResource(R.string.search_number)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                keyboardController?.hide()
                                showSearchDialog = false
                            }) { Text(stringResource(R.string.clear)) }
                        }
                    )
                }

                if (showExitToHomeDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!isSavingOrReverting) showExitToHomeDialog = false
                        },
                        title = { Text(stringResource(R.string.dialog_title_return_home)) },
                        text = { Text(stringResource(R.string.dialog_message_save_and_return_home)) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showExitToHomeDialog = false
                                    scope.launch {
                                        isSavingOrReverting = true
                                        // Salva sempre lo stato attuale prima di uscire
                                        excelViewModel.saveCurrentStateToHistory(entryUid)
                                        isSavingOrReverting = false
                                        // Esegui la navigazione verso la Home (FilePicker)
                                        // La logica di popUpTo va gestita nel NavGraph
                                        onNavigateToHome()
                                    }
                                },
                                enabled = !isSavingOrReverting
                            ) {
                                Text(stringResource(R.string.save_and_exit))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    if (!isSavingOrReverting) showExitToHomeDialog = false
                                },
                                enabled = !isSavingOrReverting
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                if (showInfoDialog && infoRowIndex in excelData.indices) {

                    val header = excelData.first()
                    val row = excelData[infoRowIndex]


                    // Inizializza gli stati quando il dialogo viene mostrato
                    LaunchedEffect(infoRowIndex) {
                        fun getValue(key: String): String = row.getOrNull(header.indexOf(key)) ?: ""
                        productNameState.value = TextFieldValue(getValue("productName"))
                        secondProductNameState.value = TextFieldValue(getValue("secondProductName"))
                        barcodeState.value = TextFieldValue(getValue("barcode"))
                        itemNumberState.value = TextFieldValue(getValue("itemNumber"))
                        quantityState.value = TextFieldValue(getValue("quantity"))
                        totalPriceState.value = TextFieldValue(getValue("totalPrice"))
                        purchasePriceState.value = TextFieldValue(getValue("purchasePrice"))
                        oldPurchasePriceState.value = TextFieldValue(getValue("oldPurchasePrice"))
                        oldRetailPriceState.value = TextFieldValue(getValue("oldRetailPrice"))
                    }


                    val qtyReq = remember { FocusRequester() }
                    val priceReq = remember { FocusRequester() }
                    val backgroundFocusRequester = remember { FocusRequester() }

                    LaunchedEffect(showInfoDialog, infoDialogFocusField) {
                        if (infoDialogFocusField == 0) qtyReq.requestFocus() else priceReq.requestFocus()
                    }

                    AlertDialog(
                        onDismissRequest = {
                            showInfoDialog = false
                            isInfoDialogInEditMode = false // Resetta lo stato in uscita
                        },
                        // --- TITOLO MODIFICATO CON PULSANTE EDIT/SAVE ---
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.row_info))
                                IconButton(onClick = {
                                    // Se stiamo salvando (da modalità edit a view)
                                    if (isInfoDialogInEditMode) {
                                        val updatedRow = excelData[infoRowIndex].toMutableList()
                                        fun setValue(key: String, value: String) {
                                            val idx = header.indexOf(key)
                                            if (idx != -1) updatedRow[idx] = value
                                        }
                                        setValue(
                                            "secondProductName",
                                            secondProductNameState.value.text
                                        )
                                        setValue("barcode", barcodeState.value.text)
                                        setValue("itemNumber", itemNumberState.value.text)
                                        setValue("quantity", quantityState.value.text)
                                        setValue("totalPrice", totalPriceState.value.text)
                                        setValue("purchasePrice", purchasePriceState.value.text)
                                        // Non rendiamo i prezzi vecchi editabili di solito, ma è possibile se necessario

                                        excelViewModel.excelData[infoRowIndex] = updatedRow
                                        excelViewModel.updateHistoryEntry(entryUid)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.row_updated),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    isInfoDialogInEditMode = !isInfoDialogInEditMode
                                }) {
                                    if (isInfoDialogInEditMode) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.save_changes),
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(28.dp) // <-- Aumenta la dimensione totale
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    shape = CircleShape
                                                )
                                                .padding(2.dp) // <-- Riduci il padding per ingrandire il tick
                                        )
                                    } else {
                                        // Altrimenti, mostra la normale icona "Modifica"
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.edit_row),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .fillMaxWidth()
                                    // --- INIZIO MODIFICA ---
                                    .focusRequester(backgroundFocusRequester) // 1. Associa il requester
                                    .focusable() // 2. Rendi il componente "focalizzabile"
                                    .clickable( // 3. Al click, richiedi il focus
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null // Per non mostrare l'effetto ripple
                                    ) {
                                        backgroundFocusRequester.requestFocus() // Nasconde la tastiera togliendo il focus ai TextField
                                    },
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // --- COMPOSABLE LOCALE PER RIGA EDITABILE ---
                                @Composable
                                fun EditableInfoRow(
                                    label: String,
                                    state: MutableState<TextFieldValue>,
                                    kType: KeyboardType = KeyboardType.Text
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "$label:",
                                            Modifier.weight(0.8f), // Più spazio per il label
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (isInfoDialogInEditMode) {
                                            OutlinedTextField(
                                                value = state.value,
                                                onValueChange = { state.value = it },
                                                modifier = Modifier.weight(1.2f)
                                                    .heightIn(min = 48.dp),
                                                textStyle = MaterialTheme.typography.bodyMedium,
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = kType)
                                            )
                                        } else {
                                            Text(
                                                state.value.text,
                                                Modifier.weight(1.2f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                if (header.contains("productName")) {
                                    EditableInfoRow(
                                        stringResource(R.string.header_product_name),
                                        productNameState
                                    )
                                }

                                if (header.contains("secondProductName")) {
                                    EditableInfoRow(
                                        stringResource(R.string.header_second_product_name),
                                        secondProductNameState
                                    )
                                }
                                //                            EditableInfoRow(
                                //                                stringResource(R.string.header_barcode),
                                //                                barcodeState,
                                //                                kType = KeyboardType.Number
                                //                            )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Etichetta "Codice a barre:" (sempre visibile)
                                    Text(
                                        text = "${stringResource(R.string.header_barcode)}:",
                                        modifier = Modifier.weight(0.8f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // Contenuto a destra: cambia in base alla modalità
                                    if (isInfoDialogInEditMode) {
                                        // Modalità di modifica: Campo di testo con bottone
                                        OutlinedTextField(
                                            value = barcodeState.value,
                                            onValueChange = { barcodeState.value = it },
                                            modifier = Modifier.weight(1.2f),
                                            textStyle = MaterialTheme.typography.bodyMedium,
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    val opts = ScanOptions().apply {
                                                        setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                                        setCaptureActivity(PortraitCaptureActivity::class.java)
                                                        setOrientationLocked(true)
                                                        setBeepEnabled(true)
                                                        setPrompt(context.getString(R.string.scan_prompt))
                                                    }
                                                    dialogScanLauncher.launch(opts)
                                                }) {
                                                    Icon(
                                                        imageVector = Icons.Filled.CameraAlt,
                                                        contentDescription = stringResource(R.string.scan_barcode_for_editing),
                                                        tint = MaterialTheme.colorScheme.primary // <-- RIGA AGGIUNTA
                                                    )
                                                }
                                            }
                                        )
                                    } else {
                                        // Modalità di sola visualizzazione: Testo semplice
                                        Text(
                                            text = barcodeState.value.text,
                                            modifier = Modifier.weight(1.2f),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                if (header.contains("itemNumber")) {
                                    EditableInfoRow(
                                        stringResource(R.string.header_item_number),
                                        itemNumberState,
                                        kType = KeyboardType.Number
                                    )
                                }
                                EditableInfoRow(
                                    stringResource(R.string.header_quantity),
                                    quantityState,
                                    kType = KeyboardType.Number
                                )
                                EditableInfoRow(
                                    stringResource(R.string.header_total_price),
                                    totalPriceState,
                                    kType = KeyboardType.Number
                                )

                                HorizontalDivider()

                                // --- LAYOUT PREZZI ---
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (oldPurchasePriceState.value.text.isNotBlank()) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            Text(
                                                stringResource(R.string.header_old_purchase_price_short),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Text(
                                                text = oldPurchasePriceState.value.text,
                                                style = MaterialTheme.typography.bodyLarge,
                                                textDecoration = TextDecoration.LineThrough,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            stringResource(R.string.header_purchase_price),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isInfoDialogInEditMode) {
                                                OutlinedTextField(
                                                    value = purchasePriceState.value,
                                                    onValueChange = {
                                                        purchasePriceState.value = it
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    textStyle = MaterialTheme.typography.bodyLarge,
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    singleLine = true
                                                )
                                            } else {
                                                Text(
                                                    text = purchasePriceState.value.text,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                                        .padding(4.dp)
                                                )
                                            }
                                            // Calcolatrice
                                            IconButton(
                                                onClick = {
                                                    calcInput = purchasePriceState.value.text
                                                    calcRowIndex = infoRowIndex
                                                    showCalcDialog = true
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Calculate,
                                                    contentDescription = stringResource(R.string.calculate_new_value),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }

                                if (oldRetailPriceState.value.text.isNotBlank()) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${stringResource(R.string.header_old_retail_price)}:",
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            oldRetailPriceState.value.text,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                HorizontalDivider()

                                // Campi editabili (Quantità contata e Prezzo vendita) rimangono invariati
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${getLocalizedHeader(context, "realQuantity")}:",
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    var qtyTf by remember {
                                        mutableStateOf(
                                            TextFieldValue(
                                                editableValues[infoRowIndex][0].value,
                                                TextRange(editableValues[infoRowIndex][0].value.length)
                                            )
                                        )
                                    }
                                    TextField(
                                        value = qtyTf,
                                        onValueChange = { nv ->
                                            qtyTf = nv; editableValues[infoRowIndex][0].value =
                                            nv.text; excelViewModel.updateHistoryEntry(entryUid)
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Next
                                        ),
                                        keyboardActions = KeyboardActions(onNext = { priceReq.requestFocus() }),
                                        modifier = Modifier.weight(1f).height(48.dp)
                                            .focusRequester(qtyReq)
                                    )
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${getLocalizedHeader(context, "retailPrice")}:",
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    var priceTf by remember {
                                        mutableStateOf(
                                            TextFieldValue(
                                                editableValues[infoRowIndex][1].value,
                                                TextRange(editableValues[infoRowIndex][1].value.length)
                                            )
                                        )
                                    }
                                    TextField(
                                        value = priceTf,
                                        onValueChange = { nv ->
                                            priceTf = nv; editableValues[infoRowIndex][1].value =
                                            nv.text; excelViewModel.updateHistoryEntry(entryUid)
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(onDone = {
                                            // 1. Recupera i valori delle quantità come stringhe
                                            val countedQtyString =
                                                editableValues[infoRowIndex][0].value
                                            val originalQtyString = quantityState.value.text

                                            // 2. Converti in numeri in modo sicuro (restituisce null se non è un numero)
                                            val countedQty = countedQtyString.toDoubleOrNull()
                                            val originalQty = originalQtyString.toDoubleOrNull()

                                            // 3. Controlla se i valori sono validi e uguali
                                            if (countedQty != null && countedQty == originalQty) {
                                                // 4. Se sono uguali, esegui l'azione "Completo"
                                                completeStates[infoRowIndex] = true
                                                excelViewModel.updateHistoryEntry(entryUid)
                                            }
                                            // 5. In ogni caso (sia che siano uguali o diversi), chiudi il dialogo
                                            showInfoDialog = false
                                            isInfoDialogInEditMode = false
                                        }),
                                        modifier = Modifier.weight(1f).height(48.dp)
                                            .focusRequester(priceReq)
                                    )
                                }
                            }
                        },
                        dismissButton = {},
                        confirmButton = {
                            if (!isInfoDialogInEditMode) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { showGenericCalcDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.Calculate,
                                            contentDescription = stringResource(R.string.fast_calculator_desc)
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { showInfoDialog = false }) {
                                        Text(stringResource(R.string.confirm))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // 1. Determina lo stato attuale della riga
                                    val isCurrentlyComplete =
                                        completeStates.getOrNull(infoRowIndex) == true

                                    // 2. Scegli il testo e i colori del pulsante in base allo stato
                                    val buttonText = if (isCurrentlyComplete) {
                                        stringResource(R.string.mark_as_incomplete) // Testo per annullare: "Annulla Completo"
                                    } else {
                                        stringResource(R.string.mark_as_complete)   // Testo per confermare: "Segna Completo"
                                    }

                                    val buttonColors = if (isCurrentlyComplete) {
                                        // Usa colori meno evidenti per l'azione di "annullamento" per una migliore UX
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    } else {
                                        // Colori primari standard per l'azione principale
                                        ButtonDefaults.buttonColors()
                                    }

                                    // 3. Crea il pulsante dinamico
                                    Button(
                                        onClick = {
                                            // La logica di toggle rimane la stessa
                                            completeStates[infoRowIndex] =
                                                !completeStates[infoRowIndex]
                                            excelViewModel.updateHistoryEntry(entryUid)
                                            showInfoDialog = false
                                        },
                                        colors = buttonColors // Applica i colori dinamici
                                    ) {
                                        Text(buttonText) // Applica il testo dinamico
                                    }
                                }
                            }
                        }
                    )
                }

                if (showCalcDialog && calcRowIndex == infoRowIndex) {
                    CalculatorDialog(
                        title = stringResource(R.string.calc_title),
                        value = calcInput,
                        onValueChange = { calcInput = it },
                        onResult = { res ->
                            val formattedRes = formatDecimal(res)
                            // Aggiorna sia lo stato del dialogo che i dati reali del ViewModel
                            purchasePriceState.value = TextFieldValue(formattedRes)
                            val idx = excelViewModel.excelData.first().indexOf("purchasePrice")
                            if (idx > -1) {
                                excelViewModel.excelData[infoRowIndex] =
                                    excelViewModel.excelData[infoRowIndex].toMutableList()
                                        .also { it[idx] = formattedRes }
                                excelViewModel.updateHistoryEntry(entryUid)
                            }
                        },
                        onDismiss = { showCalcDialog = false }
                    )
                }
            }
        }
    }

    // in fondo allo screen (fuori dallo Scaffold)
    if (isExporting) {
        LoadingDialog(UiState.Loading(message = stringResource(R.string.export_in_progress), progress = exportProgress))
    }
    if (dbUiState is UiState.Loading) {
        (dbUiState as? UiState.Loading)?.let { LoadingDialog(it) }
    }

    // Aggiungo alcune stringhe mancanti per completezza (da aggiungere in strings.xml)
    // R.string.edit_row = "Modifica riga"
    // R.string.save_changes = "Salva modifiche"
    // R.string.row_updated = "Riga aggiornata"

    if (showRenameDialog) {
        val suppliers by databaseViewModel.suppliers.collectAsState()
        val categories by databaseViewModel.categories.collectAsState()

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_file)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.new_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // FORNITORE
                    ExposedDropdownMenuBox(
                        expanded = supplierExpanded,
                        onExpandedChange = { supplierExpanded = !supplierExpanded }
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = supplierNameForRename.ifBlank { stringResource(R.string.no_supplier) },
                            onValueChange = {},
                            label = { Text(stringResource(R.string.supplier_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = supplierExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = supplierExpanded,
                            onDismissRequest = { supplierExpanded = false }
                        ) {
                            suppliers.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.name) },
                                    onClick = {
                                        supplierNameForRename = s.name
                                        supplierExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // CATEGORIA
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = !categoryExpanded }
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = categoryNameForRename.ifBlank { stringResource(R.string.no_category) },
                            onValueChange = {},
                            label = { Text(stringResource(R.string.category_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            categories.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = {
                                        categoryNameForRename = c.name
                                        categoryExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = excelViewModel.historyEntries.value.find { it.uid == entryUid }
                    if (entry != null && renameText.isNotBlank()) {
                        excelViewModel.renameHistoryEntry(
                            entry = entry,
                            newName = renameText,
                            newSupplier = supplierNameForRename,
                            newCategory = categoryNameForRename
                        )
                        titleText = renameText
                        showRenameDialog = false
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showGenericCalcDialog) {
        var genericCalcInput by remember { mutableStateOf("") }
        CalculatorDialog(
            title = stringResource(R.string.generic_calculator_title),
            value = genericCalcInput,
            onValueChange = { genericCalcInput = it },
            onResult = { /* Non fa nulla con il risultato */ },
            onDismiss = { showGenericCalcDialog = false }
        )
    }

    // Dialog per la selezione del tipo di colonna
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

fun evalSimpleExpr(expr: String): Double {
    val clean = expr.replace(",", ".").replace(" ", "")
    return object : Any() {
        var pos = -1; var ch = 0
        fun nextChar() { ch = if (++pos < clean.length) clean[pos].code else -1 }
        fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) { nextChar(); return true }
            return false
        }
        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < clean.length) throw RuntimeException("Unexpected: " + clean[pos])
            return x
        }
        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }
        fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> x /= parseFactor()
                    else -> return x
                }
            }
        }
        fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()
            val x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                eat(')'.code)
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = clean.substring(startPos, pos).toDouble()
            } else throw RuntimeException("Unexpected: " + ch.toChar())
            return x
        }
    }.parse()
}

@Composable
fun CalculatorDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(value) }
    var result by remember { mutableStateOf("") }

    val errorText = stringResource(R.string.error_label)

    fun updateResult(str: String) {
        result = try {
            if (str.trim().endsWith("=")) {
                evalSimpleExpr(str.trim().removeSuffix("=")).toString()
            } else ""
        } catch (_: Exception) { errorText }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        updateResult(it)
                        onValueChange(it)
                    },
                    label = { Text(stringResource(R.string.calc_label)) },
                    singleLine = true,
                    readOnly = true, // SOLO tramite tastiera custom
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                )
                // --- RISULTATO appena sotto il campo input ---
                val errorText = stringResource(R.string.error_label)
                Text(
                    text = if (result.isNotBlank() && result != errorText) {
                        stringResource(R.string.result) + " ${result.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: result}"
                    } else stringResource(R.string.result),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(8.dp)
                )
                // --- PIÙ SPAZIO SOPRA LA TASTIERA ---
                Spacer(Modifier.height(8.dp))
                // --- TASTIERA con tasti più GRANDI e distanziati ---
                CalculatorKeyboard(
                    onKey = { key ->
                        when (key) {
                            "C" -> { input = ""; result = ""; onValueChange("") }
                            "<" -> {
                                if (input.isNotEmpty()) {
                                    input = input.dropLast(1)
                                    updateResult(input)
                                    onValueChange(input)
                                }
                            }
                            "=" -> {
                                if (input.isNotBlank()) {
                                    val toEval = if (input.trim().endsWith("=")) input.trim() else input.trim() + "="
                                    updateResult(toEval)
                                    input = toEval
                                    onValueChange(toEval)
                                }
                            }
                            else -> {
                                input += key
                                updateResult(input)
                                onValueChange(input)
                            }
                        }
                    },
                    buttonSize = 68.dp,    // <--- PIÙ GRANDE!
                    buttonFontSize = MaterialTheme.typography.headlineSmall.fontSize
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (result.isNotBlank() && result != errorText) {
                    onResult(result)
                } else {
                    onResult(input)
                }
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun CalculatorKeyboard(
    onKey: (String) -> Unit,
    buttonSize: Dp = 56.dp,
    buttonFontSize: TextUnit = MaterialTheme.typography.titleLarge.fontSize
) {
    val buttons = listOf(
        listOf("C", "<"),
        listOf("+", "7", "8", "9"),
        listOf("-", "4", "5", "6"),
        listOf("*", "1", "2", "3"),
        listOf("/", ".", "0", "="),

        )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        buttons.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp), // più spazio tra righe
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { label ->
                    Button(
                        onClick = { onKey(label) },
                        modifier = Modifier
                            .size(buttonSize)
                            .padding(2.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(label, fontWeight = FontWeight.Bold, fontSize = buttonFontSize)
                    }
                }
                // Per le righe più corte, aggiungi spaziatori
                repeat(4 - row.size) { Spacer(Modifier.size(buttonSize)) }
            }
        }
    }
}

@Composable
private fun StatusIcon(
    baseIcon: ImageVector,
    badgeType: BadgeType, // <-- Usa il nuovo enum invece di un Boolean
    contentDescription: String
) {
    Box {
        Icon(
            imageVector = baseIcon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Usa un when per decidere quale badge mostrare
        when (badgeType) {
            BadgeType.SUCCESS -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.status_completed),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp),
                    tint = Color(0xFF00C853) // Verde
                )
            }
            BadgeType.WARNING -> {
                Icon(
                    imageVector = Icons.Default.Error, // Icona di errore/avviso
                    contentDescription = stringResource(R.string.status_warning),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp),
                    tint = Color(0xFFFFA000) // Arancione/Ambra
                )
            }
            BadgeType.NONE -> { /* Non mostrare nulla */ }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryDialog(
    viewModel: ExcelViewModel,
    databaseViewModel: DatabaseViewModel,
    rowIndexToEdit: Int?,
    entryUid: Long,
    initialBarcode: String?,
    productToPrefill: com.example.merchandisecontrolsplitview.data.Product?,
    onDismiss: () -> Unit,
    onScanNext: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val quantityFocusRequester = remember { FocusRequester() }
    val priceFocusRequester = remember { FocusRequester() }
    val nameFocusRequester = remember { FocusRequester() }

    val categoryInputText by databaseViewModel.categoryInputText.collectAsState()
    var selectedCategory by remember { mutableStateOf<com.example.merchandisecontrolsplitview.data.Category?>(null) }
    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }
    val categorySuggestions by databaseViewModel.categories.collectAsState()

    var barcode by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf(TextFieldValue("1")) }
    var purchasePrice by remember { mutableStateOf("") }
    var retailPrice by remember { mutableStateOf(TextFieldValue("")) }
    var productName by remember { mutableStateOf("") }

    var originalProductData by remember { mutableStateOf<com.example.merchandisecontrolsplitview.data.Product?>(null) }

    var productFromDb by remember { mutableStateOf<com.example.merchandisecontrolsplitview.data.Product?>(null) }
    var dbLookupJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var barcodeError by remember { mutableStateOf<String?>(null) }
    var barcodeInfo by remember { mutableStateOf<String?>(null) }
    var originalBarcodeForEdit by remember { mutableStateOf<String?>(null) }

    val isEditMode = rowIndexToEdit != null
    val header = viewModel.excelData.firstOrNull() ?: return
    val catName = selectedCategory?.name ?: "variedades"

    val (barIdx, nameIdx, priceIdx, qtyIdx, catIdx) = remember(header) {
        listOf("barcode", "productName", "retailPrice", "quantity", "category").map { header.indexOf(it) }
    }

    val context = LocalContext.current
    val dialogScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            barcode = code
        } ?: Toast.makeText(context, context.getString(R.string.no_scanner_result), Toast.LENGTH_SHORT).show()
    }

    val purchIdx = remember(header) { header.indexOf("purchasePrice") }
    val currentNormalized = remember(barcode, productName, retailPrice, quantity, selectedCategory) {
        toNormalizedProduct(
            barcode = barcode,
            name = productName,
            retailPriceStr = retailPrice.text,
            qtyStr = quantity.text,
            categoryId = selectedCategory?.id
        )
    }

    val hasChangesAgainstDb = remember(currentNormalized, productFromDb) {
        !equalProductsForDb(currentNormalized, productFromDb)   // vedi funzione al punto 2
    }
    val hasChangesAgainstOriginal = remember(currentNormalized, originalProductData) {
        !equalProducts(currentNormalized, originalProductData)
    }

    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // Lascia assestare il dialog un attimo
        delay(50)
        priceFocusRequester.requestFocus()
        keyboardController?.show()
    }

    // ✅ *** CRITICAL FIX: This effect now correctly re-initializes the dialog's state
    // every time it's shown for a new item or in a new mode.
    // It's keyed to the inputs, so it re-runs when they change.
    LaunchedEffect(rowIndexToEdit, productToPrefill, initialBarcode) {
        // First, reset all fields to a clean state to prevent stale data
        productName = ""
        retailPrice = TextFieldValue("")
        quantity = TextFieldValue("1")
        selectedCategory = null
        databaseViewModel.onCategorySearchQueryChanged("")

        barcodeError = null
        originalBarcodeForEdit = null
        originalProductData = null // --- Aggiunto: Resetta lo stato originale

        if (isEditMode) {
            // --- 1. EDIT MODE ---
            val rowData = viewModel.excelData[rowIndexToEdit + 1]
            val catName = if (catIdx != -1) rowData.getOrNull(catIdx) ?: "" else ""
            barcode = if (barIdx != -1) rowData.getOrNull(barIdx) ?: "" else ""
            originalBarcodeForEdit = barcode
            productName = if (nameIdx != -1) rowData.getOrNull(nameIdx) ?: "" else ""
            purchasePrice = if (purchIdx != -1) rowData.getOrNull(purchIdx) ?: "" else ""
            val priceFromRow = if (priceIdx != -1) rowData.getOrNull(priceIdx) ?: "" else ""
            retailPrice = TextFieldValue(priceFromRow, TextRange(priceFromRow.length))
            val qtyText = if (qtyIdx != -1) rowData.getOrNull(qtyIdx).takeIf { !it.isNullOrBlank() } ?: "1" else "1"
            quantity = TextFieldValue(qtyText)

            databaseViewModel.onCategorySearchQueryChanged(catName)

            // --- Logica di selezione robusta ---
            scope.launch {
                // Prova a cercare fino a 5 volte (1 secondo totale) se la lista non è pronta
                repeat(5) {
                    val foundCategory = databaseViewModel.categories.value.find { it.name.equals(catName, true) }
                    if (foundCategory != null) {
                        selectedCategory = foundCategory // Imposta la categoria trovata

                        // Costruisci e salva lo stato originale CON l'ID della categoria corretto
                        originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                            barcode = barcode,
                            productName = productName,
                            retailPrice = retailPrice.text.replace(",", ".").toDoubleOrNull(),
                            stockQuantity = quantity.text.replace(",", ".").toDoubleOrNull(),
                            categoryId = foundCategory.id // <-- Usa l'ID corretto
                        )
                        return@launch // Esci dalla coroutine
                    }
                    delay(200) // Aspetta un po' prima di riprovare
                }
                // Se anche dopo i tentativi non trova nulla, costruisci lo stato originale senza ID
                originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                    barcode = barcode,
                    productName = productName,
                    retailPrice = retailPrice.text.replace(",", ".").toDoubleOrNull(),
                    stockQuantity = quantity.text.replace(",", ".").toDoubleOrNull(),
                    categoryId = null
                )
            }

        } else if (productToPrefill != null) {
            // --- 2. ADD MODE with DB PRE-FILL ---
            val initialBarcode = productToPrefill.barcode
            val initialProductName = productToPrefill.productName ?: ""
            val initialRetailPrice = formatNumberAsRoundedStringForInput(productToPrefill.retailPrice)
            val initialQuantity = "1" // Default per una nuova riga

            // Popola la UI
            barcode = initialBarcode
            productName = initialProductName
            retailPrice = TextFieldValue(initialRetailPrice, TextRange(initialRetailPrice.length))
            quantity = TextFieldValue(initialQuantity)

            // Gestione asincrona della categoria
            productToPrefill.categoryId?.let { catId ->
                scope.launch {
                    val category = databaseViewModel.getCategoryById(catId)
                    selectedCategory = category
                    databaseViewModel.onCategorySearchQueryChanged(category?.name ?: "")

                    // --- Logica Corretta: Costruisci lo stato originale in modo che corrisponda alla UI iniziale ---
                    originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                        barcode = initialBarcode,
                        productName = initialProductName,
                        retailPrice = initialRetailPrice.replace(",", ".").toDoubleOrNull(),
                        stockQuantity = initialQuantity.replace(",", ".").toDoubleOrNull(),
                        categoryId = category?.id,
                        // Copia l'ID originale per coerenza nel confronto
                        id = productToPrefill.id
                    )
                }
            } ?: run {
                // Se non c'è categoryId, costruisci subito lo stato originale
                originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                    barcode = initialBarcode,
                    productName = initialProductName,
                    retailPrice = initialRetailPrice.replace(",", ".").toDoubleOrNull(),
                    stockQuantity = initialQuantity.replace(",", ".").toDoubleOrNull(),
                    categoryId = null,
                    id = productToPrefill.id
                )
            }

        } else {
            // --- 3. ADD MODE (from new scan or '+' button) ---
            barcode = initialBarcode ?: ""
            viewModel.lastUsedCategory.value?.let {
                databaseViewModel.onCategorySearchQueryChanged(it)
                selectedCategory = databaseViewModel.categories.value.find { c -> c.name.equals(it, true) }
            }
            // --- Aggiunto: Lo stato originale è null perché è una nuova entry ---
            originalProductData = null
        }
    }

    // This separate effect correctly handles moving focus after a scan
    LaunchedEffect(initialBarcode, productToPrefill) {
        if (initialBarcode != null || productToPrefill != null) {
            delay(50)
            priceFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // This effect for debounced DB lookup remains unchanged
    LaunchedEffect(barcode) {
        // Reset stato quando vuoto o (in edit) è l'originale
        if (barcode.isBlank() || (isEditMode && barcode == originalBarcodeForEdit)) {
            barcodeError = null
            barcodeInfo = null
            productFromDb = null
            dbLookupJob?.cancel()
            return@LaunchedEffect
        }

        delay(350) // debounce

        // 1) DUPLICATO NELLA GRIGLIA (vero errore: blocca i bottoni)
        val isDuplicateInGrid = viewModel.excelData.drop(1).any { row ->
            val rowBarcode = if (barIdx != -1) row.getOrNull(barIdx) else null
            val idxBeingChecked = viewModel.excelData.indexOf(row) - 1
            rowBarcode == barcode && (rowIndexToEdit == null || rowIndexToEdit != idxBeingChecked)
        }
        if (isDuplicateInGrid) {
            barcodeError = context.getString(R.string.product_already_in_list)
            barcodeInfo = null
            productFromDb = null
            dbLookupJob?.cancel()
            return@LaunchedEffect
        }

        // 2) LOOKUP NEL DB (info, NON errore)
        barcodeError = null
        barcodeInfo = null
        productFromDb = null
        dbLookupJob?.cancel()
        dbLookupJob = if (barcode.length > 5) {
            scope.launch {
                val inDb = databaseViewModel.findProductByBarcode(barcode)
                productFromDb = inDb
                barcodeInfo = inDb?.let { context.getString(R.string.product_found_in_db) }
            }
        } else {
            null
        }
    }

    LaunchedEffect(productFromDb) {
        val dbCatId = productFromDb?.categoryId
        if (dbCatId != null && selectedCategory?.id != dbCatId) {
            val cat = databaseViewModel.getCategoryById(dbCatId)
            selectedCategory = cat
            databaseViewModel.onCategorySearchQueryChanged(cat?.name ?: "")
        }
    }

    val basicValid = barcode.isNotBlank() &&
            barcodeError == null &&
            (retailPrice.text.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0

    val passChangesGate = when {
        // Prodotto esiste in DB → consenti solo se diverso dal DB
        productFromDb != null -> hasChangesAgainstDb
        // Edit di riga o prefill iniziale dal DB → consenti solo se diverso dalla baseline originale
        isEditMode || productToPrefill != null -> hasChangesAgainstOriginal
        // Nuova riga “vergine” → basta che i campi siano validi
        else -> true
    }

    val isConfirmEnabled = basicValid && passChangesGate

    fun resetFieldsForNext() {
        barcode = ""
        quantity = TextFieldValue("1")
        retailPrice = TextFieldValue("")
        productName = ""
        productFromDb = null
        // The category is kept for the next entry
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditMode) stringResource(R.string.edit_product) else stringResource(R.string.add_product)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = isCategoryDropdownExpanded,
                    onExpandedChange = { isCategoryDropdownExpanded = !isCategoryDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = categoryInputText,
                        onValueChange = {
                            databaseViewModel.onCategorySearchQueryChanged(it)
                            isCategoryDropdownExpanded = true
                            selectedCategory = null
                        },
                        label = { Text(stringResource(R.string.category_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                    )
                    ExposedDropdownMenu(
                        expanded = isCategoryDropdownExpanded,
                        onDismissRequest = { isCategoryDropdownExpanded = false }
                    ) {
                        categorySuggestions.forEach { suggestion ->
                            DropdownMenuItem(text = { Text(suggestion.name) }, onClick = {
                                databaseViewModel.onCategorySearchQueryChanged(suggestion.name)
                                selectedCategory = suggestion
                                isCategoryDropdownExpanded = false
                            })
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

                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text(stringResource(R.string.header_barcode) + "*") },
                    trailingIcon = {
                        IconButton(onClick = {
                            // --- INIZIO MODIFICA ---
                            // 1. Azzera tutti i campi del dialogo
                            productName = ""
                            retailPrice = TextFieldValue("")
                            quantity = TextFieldValue("1") // Resetta la quantità al valore di default
                            productFromDb = null          // Rimuove i dati pre-compilati dal DB
                            barcodeError = null           // Rimuove eventuali messaggi di errore
                            // Lasciamo 'barcode' così com'è, perché verrà sovrascritto dalla nuova scansione.
                            // --- FINE MODIFICA ---

                            // 2. Lancia lo scanner
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                setBeepEnabled(true)
                                setPrompt(context.getString(R.string.scan_prompt))
                                setCaptureActivity(PortraitCaptureActivity::class.java)
                            }
                            dialogScanLauncher.launch(options)
                        }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Scan Barcode")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { priceFocusRequester.requestFocus() }),
                    isError = barcodeError != null,
                    supportingText = {
                        if (barcodeError != null) {
                            Text(text = barcodeError!!, color = MaterialTheme.colorScheme.error)
                        } else if (barcodeInfo != null) {
                            Text(text = barcodeInfo!!, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = purchasePrice,
                        onValueChange = { purchasePrice = it },
                        label = { Text(stringResource(R.string.header_purchase_price)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { quantityFocusRequester.requestFocus() })
                    )
                    OutlinedTextField(
                        value = retailPrice,
                        onValueChange = { retailPrice = it },
                        label = { Text(stringResource(R.string.header_retail_price) + "*") },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(priceFocusRequester),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { quantityFocusRequester.requestFocus() })
                    )
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text(stringResource(R.string.header_quantity)) },
                        modifier = Modifier.weight(1f).focusRequester(quantityFocusRequester),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { nameFocusRequester.requestFocus() })
                    )
                }

                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = { Text(stringResource(R.string.header_product_name)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )

                AnimatedVisibility(visible = productFromDb != null) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.data_from_database), style = MaterialTheme.typography.labelMedium)
                            // Usa la funzione di formattazione per il prezzo
                            val formattedPrice = formatNumberAsRoundedStringForInput(productFromDb?.retailPrice)
                            Text("${productFromDb?.productName} - Prezzo: $formattedPrice")
                            TextButton(onClick = {
                                retailPrice = TextFieldValue(formattedPrice, TextRange(formattedPrice.length))
                                productName = productFromDb?.productName ?: ""
                            }) { Text(stringResource(R.string.copy_data)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val newRowData = header.map { key ->
                when (key) {
                    "barcode" -> barcode
                    "productName" -> productName.ifBlank { selectedCategory?.name ?: "variedades" }
                    "purchasePrice" -> {
                        val rp = retailPrice.text.replace(",", ".").toDoubleOrNull()
                        val pp = purchasePrice.replace(",", ".").toDoubleOrNull() ?: (rp?.div(2.0))
                        pp?.let { formatNumberAsRoundedStringForInput(it) }.orEmpty()
                    }
                    "retailPrice" -> retailPrice.text
                    "quantity" -> quantity.text
                    "category" -> selectedCategory?.name ?: "variedades"
                    else -> ""
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (isEditMode) {
                    TextButton(
                        onClick = {
                            viewModel.deleteManualRow(entryUid, rowIndexToEdit)
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.delete)) }
                }

                if (!isEditMode) {
                    TextButton(
                        onClick = {
                            viewModel.addManualRow(entryUid, newRowData, catName)
                            focusManager.clearFocus(true)
                            resetFieldsForNext()
                            onScanNext()
                        },
                        enabled = isConfirmEnabled
                    ) { Text(stringResource(R.string.add_and_next)) }
                }

                Button(
                    onClick = {
                        if (isEditMode) {
                            viewModel.updateManualRow(entryUid, rowIndexToEdit, newRowData, catName)
                        } else {
                            viewModel.addManualRow(entryUid, newRowData, catName)
                        }
                        onDismiss()
                    },
                    enabled = isConfirmEnabled
                ) { Text(stringResource(R.string.confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

fun toNormalizedProduct(
    barcode: String,
    name: String,
    retailPriceStr: String,
    qtyStr: String,
    categoryId: Long?
): com.example.merchandisecontrolsplitview.data.Product {
    fun strToD(s: String) = s.replace(",", ".").trim().toDoubleOrNull()
    return com.example.merchandisecontrolsplitview.data.Product(
        barcode = barcode,
        productName = name.ifBlank { null },
        retailPrice = strToD(retailPriceStr),
        stockQuantity = strToD(qtyStr),
        categoryId = categoryId
    )
}

fun almostEqual(a: Double?, b: Double?, eps: Double = 0.005): Boolean =
    if (a == null && b == null) true
    else if (a == null || b == null) false
    else kotlin.math.abs(a - b) <= eps

fun equalProducts(
    a: com.example.merchandisecontrolsplitview.data.Product?,
    b: com.example.merchandisecontrolsplitview.data.Product?
): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return a.barcode == b.barcode &&
            (a.productName.orEmpty() == b.productName.orEmpty()) &&
            almostEqual(a.retailPrice, b.retailPrice) &&
            almostEqual(a.stockQuantity, b.stockQuantity) &&
            a.categoryId == b.categoryId
}
private fun equalProductsForDb(
    a: com.example.merchandisecontrolsplitview.data.Product?,
    b: com.example.merchandisecontrolsplitview.data.Product?
): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    // come equalProducts ma IGNORA stockQuantity
    return a.barcode == b.barcode &&
            (a.productName.orEmpty() == b.productName.orEmpty()) &&
            almostEqual(a.retailPrice, b.retailPrice) &&
            a.categoryId == b.categoryId
}
fun formatDecimal(num: Double, digits: Int = 2): String =
    String.format(Locale.US, "%.${digits}f", num)

fun formatDecimal(num: String, digits: Int = 2): String =
    num.toDoubleOrNull()?.let { formatDecimal(it, digits) } ?: num

@Composable
private fun MenuIconWithTick(
    base: ImageVector,
    showTick: Boolean
) {
    Box(Modifier.size(24.dp)) {
        Icon(base, contentDescription = null)
        if (showTick) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF00C853),
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
            )
        }
    }
}

@Composable
private fun TopInfoChipsBar(
    supplier: String?,
    category: String?,
    completed: Int,
    total: Int,
    exported: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 2.dp) // ↓ da 6dp a 2dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        supplier?.let { InfoChip(it) }
        category?.let { InfoChip(it) }
        InfoChip("$completed/$total")
        if (exported) InfoChip(stringResource(R.string.exported_short), tonal = true)
    }
}

@Composable
private fun InfoChip(text: String, tonal: Boolean = false) {
    AssistChip(
        onClick = { /* no-op */ },
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        colors = if (tonal)
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        else AssistChipDefaults.assistChipColors()
    )
}
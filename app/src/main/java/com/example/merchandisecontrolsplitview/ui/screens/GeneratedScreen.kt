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
import androidx.compose.ui.focus.onFocusChanged
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
import com.example.merchandisecontrolsplitview.ui.theme.appColors
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
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
import com.example.merchandisecontrolsplitview.util.formatClCount
import com.example.merchandisecontrolsplitview.util.formatClPriceInput
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityDisplayReadOnly
import com.example.merchandisecontrolsplitview.util.normalizeClPriceInput
import com.example.merchandisecontrolsplitview.util.normalizeClQuantityInput
import com.example.merchandisecontrolsplitview.util.parseUserNumericInput
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalFocusManager
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.text.style.TextOverflow

/**
 * Whether auxiliary "old purchase price" UI should show: old is non-blank and not equal to current
 * (trimmed string match; if both parse as numbers, numeric equality hides the old value).
 */
private fun oldPurchasePriceDiffersFromCurrent(current: String, old: String): Boolean {
    val c = current.trim()
    val o = old.trim()
    if (o.isEmpty()) return false
    if (c == o) return false
    val cd = parseUserPriceInput(c)
    val od = parseUserPriceInput(o)
    return !(cd != null && od != null && cd == od)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratedScreen(
    excelViewModel: ExcelViewModel,
    databaseViewModel: DatabaseViewModel,
    onBackToStart: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToDatabase: () -> Unit,
    entryUid: Long,
    isNewEntry: Boolean,
    isManualEntry: Boolean
) {
    val spacing = MaterialTheme.appSpacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val productAlreadyInListText = stringResource(R.string.product_already_in_list)
    val productFoundInDbText = stringResource(R.string.product_found_in_db)
    val notFoundText = stringResource(R.string.not_found)
    val noScannerResultText = stringResource(R.string.no_scanner_result)
    val fileExportedSuccessfullyText = stringResource(R.string.file_exported_successfully)
    val exportFailedText = stringResource(R.string.error_export_generic)
    val appNameText = stringResource(R.string.app_name)
    val shareXlsxText = stringResource(R.string.share_xlsx)
    val shareExportMessageText = stringResource(
        R.string.share_export_message,
        appNameText
    )
    val scanPromptText = stringResource(R.string.scan_prompt)
    val supplierManualText = stringResource(R.string.supplier_manual)
    val syncAnalysisStartedText = stringResource(R.string.sync_analysis_started)
    val noValidRowsToSyncText = stringResource(R.string.no_valid_rows_to_sync)
    val openDatabaseText = stringResource(R.string.open_database)
    val exportDatabaseFilenamePrefix = stringResource(R.string.export_database_filename_prefix)
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
    val historyActionMessage by excelViewModel.historyActionMessage

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

    val currentEntryName by excelViewModel.currentEntryName

    var showManualEntryDialog by remember { mutableStateOf(false) }
    var productToEditIndex by remember { mutableStateOf<Int?>(null) }

    var productDataToPrefill by remember { mutableStateOf<com.example.merchandisecontrolsplitview.data.Product?>(null) }
    LaunchedEffect(entryUid, currentEntryName) {
        if (currentEntryName.isNotBlank()) {
            titleText = currentEntryName
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
                    Toast.makeText(context, productAlreadyInListText, Toast.LENGTH_SHORT).show()
                    showManualEntryDialog = true
                } else {
                    // Caso 2: Non trovato nella griglia, cerca nel DB principale
                    scope.launch {
                        val productFromDb = databaseViewModel.findProductByBarcode(code)
                        if (productFromDb != null) {
                            // Trovato nel DB -> Apri in aggiunta con dati pre-compilati
                            productToEditIndex = null
                            productDataToPrefill = productFromDb
                            Toast.makeText(context, productFoundInDbText, Toast.LENGTH_SHORT).show()
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
                        notFoundText,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } ?: Toast.makeText(context, noScannerResultText, Toast.LENGTH_SHORT).show()
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
                try {
                    excelViewModel.exportToUri(context, it)
                    excelViewModel.markCurrentEntryAsExported(entryUid)
                    Toast.makeText(context, fileExportedSuccessfullyText, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, exportFailedText, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun shareXlsx() {
        scope.launch {
            try {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                val file = File(dir, "${exportDatabaseFilenamePrefix}${ts}.xlsx")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                excelViewModel.exportToUri(context, uri)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_SUBJECT, appNameText)
                    putExtra(Intent.EXTRA_TEXT, shareExportMessageText)
                }
                context.startActivity(Intent.createChooser(share, shareXlsxText))
                excelViewModel.markCurrentEntryAsExported(entryUid)
            } catch (_: Exception) {
                Toast.makeText(context, exportFailedText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchMainScanner() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ALL_CODE_TYPES)
                setCaptureActivity(PortraitCaptureActivity::class.java)
                setOrientationLocked(true)
                setBeepEnabled(true)
                setPrompt(scanPromptText)
            }
        )
    }

    fun openRenameDialog() {
        renameText = titleText
        supplierNameForRename = excelViewModel.supplierName
        categoryNameForRename = excelViewModel.categoryName
        supplierExpanded = false
        categoryExpanded = false
        showRenameDialog = true
    }

    fun analyzeCurrentGrid() {
        excelViewModel.errorRowIndexes.value = emptySet()
        val header = excelData.firstOrNull() ?: return
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
                    "supplier" to supplierManualText
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
                map["supplier"] = excelViewModel.supplierName.trim()
                map["category"] = excelViewModel.categoryName.trim()
                map.toMap()
            }
        }

        if (gridDataForAnalysis.isNotEmpty()) {
            databaseViewModel.analyzeGridData(gridDataForAnalysis)
            Toast.makeText(
                context,
                syncAnalysisStartedText,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                noValidRowsToSyncText,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun performSearch() {
        val query = searchText.trim()
        if (query.isBlank()) {
            Toast.makeText(context, notFoundText, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, notFoundText, Toast.LENGTH_SHORT).show()
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
                    actionLabel = openDatabaseText,
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

    LaunchedEffect(historyActionMessage) {
        historyActionMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            excelViewModel.consumeHistoryActionMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GeneratedScreenTopBar(
                titleText = titleText,
                excelData = excelData,
                generated = generated,
                supplierName = excelViewModel.supplierName.ifBlank { null },
                categoryName = excelViewModel.categoryName.ifBlank { null },
                completedCount = completeStates.count { it },
                totalCount = (excelData.size - 1).coerceAtLeast(0),
                wasExported = wasExported,
                syncStatus = syncStatus,
                onNavigateBack = handleBackPress,
                onNavigateHome = { showExitToHomeDialog = true },
                onAnalyzeSync = { analyzeCurrentGrid() },
                onExport = { saveLauncher.launch(titleText) },
                onShare = { shareXlsx() },
                onRename = { openRenameDialog() }
            )
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
                GeneratedScreenGridHost(
                    excelData = excelData,
                    selectedColumns = excelViewModel.selectedColumns,
                    editableValues = editableValues,
                    completeStates = completeStates,
                    searchMatches = searchMatches,
                    errorIndexes = errorIndexes,
                    isManualEntry = isManualEntry,
                    onCompleteToggle = { row ->
                        completeStates[row] = !completeStates[row]
                        excelViewModel.updateHistoryEntry(entryUid)
                    },
                    onQuantityClick = { row ->
                        infoRowIndex = row
                        infoDialogFocusField = 0
                        showInfoDialog = true
                    },
                    onPriceClick = { row ->
                        infoRowIndex = row
                        infoDialogFocusField = 1
                        showInfoDialog = true
                    },
                    onGeneratedRowClick = { row ->
                        infoRowIndex = row
                        infoDialogFocusField = 0
                        showInfoDialog = true
                    },
                    onManualRowClick = { row ->
                        productToEditIndex = row - 1
                        showManualEntryDialog = true
                    },
                    onHeaderDialogRequest = { colIdx -> headerDialogIndex = colIdx },
                )

                GeneratedScreenDiscardDraftDialog(
                    visible = showExitDialog,
                    isSavingOrReverting = isSavingOrReverting,
                    onDismissRequest = { if (!isSavingOrReverting) showExitDialog = false },
                    onConfirmDiscard = {
                        showExitDialog = false
                        scope.launch {
                            isSavingOrReverting = true
                            excelViewModel.deleteHistoryEntry(entryUid)
                            excelViewModel.revertToPreGenerateState()
                            isSavingOrReverting = false
                            onBackToStart()
                        }
                    },
                    onCancel = { if (!isSavingOrReverting) showExitDialog = false }
                )

                GeneratedScreenExitFromHistoryDialog(
                    visible = showExitFromHistoryDialog,
                    isSavingOrReverting = isSavingOrReverting,
                    onDismissRequest = {
                        if (!isSavingOrReverting) showExitFromHistoryDialog = false
                    },
                    onExitWithoutSaving = {
                        showExitFromHistoryDialog = false
                        scope.launch {
                            isSavingOrReverting = true
                            excelViewModel.revertDatabaseToOriginalState()
                            excelViewModel.loadHistoryEntry(entryUid)
                            isSavingOrReverting = false
                            onBackToStart()
                        }
                    },
                    onSaveAndExit = {
                        showExitFromHistoryDialog = false
                        scope.launch {
                            isSavingOrReverting = true
                            excelViewModel.saveCurrentStateToHistory(entryUid)
                            isSavingOrReverting = false
                            onBackToStart()
                        }
                    },
                    onCancel = {
                        if (!isSavingOrReverting) showExitFromHistoryDialog = false
                    }
                )

                GeneratedScreenFabArea(
                    visible = excelData.isNotEmpty() && generated,
                    isManualEntry = isManualEntry,
                    onLaunchScanner = { launchMainScanner() },
                    onOpenSearch = {
                        searchText = ""
                        showSearchDialog = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = spacing.lg, bottom = 88.dp)
                )

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
                            val portraitScanOptions = ScanOptions().apply {
                                setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                setBeepEnabled(true)
                                setPrompt(scanPromptText)
                                setOrientationLocked(true)
                                setCaptureActivity(PortraitCaptureActivity::class.java)
                            }
                            scanLauncher.launch(portraitScanOptions)
                        }
                    )
                }

                GeneratedScreenSearchDialog(
                    visible = showSearchDialog,
                    searchText = searchText,
                    onSearchTextChange = { searchText = it },
                    onDismiss = { showSearchDialog = false },
                    onPerformSearch = { performSearch() },
                    onLaunchScanner = { launchMainScanner() }
                )

                GeneratedScreenExitToHomeDialog(
                    visible = showExitToHomeDialog,
                    isSavingOrReverting = isSavingOrReverting,
                    onDismissRequest = {
                        if (!isSavingOrReverting) showExitToHomeDialog = false
                    },
                    onSaveAndExitToHome = {
                        showExitToHomeDialog = false
                        scope.launch {
                            isSavingOrReverting = true
                            excelViewModel.saveCurrentStateToHistory(entryUid)
                            isSavingOrReverting = false
                            onNavigateToHome()
                        }
                    },
                    onCancel = {
                        if (!isSavingOrReverting) showExitToHomeDialog = false
                    }
                )

                if (showInfoDialog && infoRowIndex in excelData.indices) {
                    GeneratedScreenInfoDialog(
                        excelViewModel = excelViewModel,
                        entryUid = entryUid,
                        infoRowIndex = infoRowIndex,
                        infoDialogFocusField = infoDialogFocusField,
                        editableValues = editableValues,
                        completeStates = completeStates,
                        productNameState = productNameState,
                        secondProductNameState = secondProductNameState,
                        barcodeState = barcodeState,
                        itemNumberState = itemNumberState,
                        quantityState = quantityState,
                        totalPriceState = totalPriceState,
                        purchasePriceState = purchasePriceState,
                        oldPurchasePriceState = oldPurchasePriceState,
                        oldRetailPriceState = oldRetailPriceState,
                        isInfoDialogInEditMode = isInfoDialogInEditMode,
                        onInfoDialogEditModeChange = { isInfoDialogInEditMode = it },
                        onDismiss = {
                            showInfoDialog = false
                            isInfoDialogInEditMode = false
                        },
                        onLaunchBarcodeScanner = {
                            dialogScanLauncher.launch(
                                ScanOptions().apply {
                                    setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                    setCaptureActivity(PortraitCaptureActivity::class.java)
                                    setOrientationLocked(true)
                                    setBeepEnabled(true)
                                    setPrompt(scanPromptText)
                                }
                            )
                        },
                        onOpenGenericCalculator = { showGenericCalcDialog = true },
                        onOpenPurchaseCalculator = { value ->
                            calcInput = value
                            calcRowIndex = infoRowIndex
                            showCalcDialog = true
                        }
                    )
                }

                if (showCalcDialog && calcRowIndex == infoRowIndex) {
                    CalculatorDialog(
                        title = stringResource(R.string.calc_title),
                        value = calcInput,
                        onValueChange = { calcInput = it },
                        onResult = { res ->
                            val formattedRes = normalizeClPriceInput(res)
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

    if (showRenameDialog) {
        val suppliers by databaseViewModel.suppliers.collectAsState()
        val categories by databaseViewModel.categories.collectAsState()

        GeneratedScreenRenameDialog(
            renameText = renameText,
            onRenameTextChange = { renameText = it },
            supplierName = supplierNameForRename,
            onSupplierNameChange = { supplierNameForRename = it },
            categoryName = categoryNameForRename,
            onCategoryNameChange = { categoryNameForRename = it },
            supplierExpanded = supplierExpanded,
            onSupplierExpandedChange = { supplierExpanded = it },
            categoryExpanded = categoryExpanded,
            onCategoryExpandedChange = { categoryExpanded = it },
            suppliers = suppliers,
            categories = categories,
            onConfirm = {
                supplierExpanded = false
                categoryExpanded = false
                if (renameText.isNotBlank()) {
                    excelViewModel.renameHistoryEntry(
                        entryUid = entryUid,
                        newName = renameText,
                        newSupplier = supplierNameForRename,
                        newCategory = categoryNameForRename
                    )
                    showRenameDialog = false
                }
            },
            onDismiss = {
                supplierExpanded = false
                categoryExpanded = false
                showRenameDialog = false
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
        GeneratedScreenCustomHeaderDialog(
            customHeader = customHeader,
            onCustomHeaderChange = { customHeader = it },
            onDismissRequest = {
                showCustomHeaderDialog = false
                customHeader = ""
                headerDialogIndex = null
            },
            onConfirm = {
                headerDialogIndex?.let { idx ->
                    if (customHeader.isNotBlank()) {
                        excelViewModel.setHeaderType(idx, customHeader.trim())
                        showCustomHeaderDialog = false
                        customHeader = ""
                        headerDialogIndex = null
                    }
                }
            },
            onCancel = {
                showCustomHeaderDialog = false
                customHeader = ""
            }
        )
    }

    if (!showCustomHeaderDialog) {
        headerDialogIndex?.let { colIdx ->
            GeneratedScreenHeaderTypeDialog(
                possibleKeys = possibleKeys,
                onSelectHeaderType = { key ->
                    excelViewModel.setHeaderType(colIdx, key)
                    headerDialogIndex = null
                },
                onOpenCustomHeader = {
                    showCustomHeaderDialog = true
                    headerDialogIndex = colIdx
                },
                onDismiss = { headerDialogIndex = null }
            )
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratedScreenTopBar(
    titleText: String,
    excelData: List<List<String>>,
    generated: Boolean,
    supplierName: String?,
    categoryName: String?,
    completedCount: Int,
    totalCount: Int,
    wasExported: Boolean,
    syncStatus: com.example.merchandisecontrolsplitview.data.SyncStatus,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onAnalyzeSync: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
    ) {
        CenterAlignedTopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            ),
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            },
            title = {
                var tapKey by remember { mutableIntStateOf(0) }
                key(tapKey) {
                    Text(
                        text = titleText.ifBlank { stringResource(R.string.untitled) },
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clickable { tapKey++ }
                            .basicMarquee(
                                animationMode = MarqueeAnimationMode.Immediately,
                                iterations = 1
                            )
                    )
                }
            },
            actions = {
                if (excelData.isNotEmpty() && generated) {
                    IconButton(onClick = onNavigateHome) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = stringResource(R.string.go_to_home)
                        )
                    }
                    IconButton(onClick = onAnalyzeSync) {
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
                            onDismissRequest = { menuOpen = false }
                        ) {
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
                                    onExport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_xlsx)) },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    menuOpen = false
                                    onShare()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename_file)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                    }
                }
            }
        )
        TopInfoChipsBar(
            supplier = supplierName,
            category = categoryName,
            completed = completedCount,
            total = totalCount,
            exported = wasExported,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun GeneratedScreenGridHost(
    excelData: List<List<String>>,
    selectedColumns: androidx.compose.runtime.snapshots.SnapshotStateList<Boolean>,
    editableValues: List<List<MutableState<String>>>,
    completeStates: androidx.compose.runtime.snapshots.SnapshotStateList<Boolean>,
    searchMatches: Set<Pair<Int, Int>>,
    errorIndexes: Set<Int>,
    isManualEntry: Boolean,
    onCompleteToggle: (Int) -> Unit,
    onQuantityClick: (Int) -> Unit,
    onPriceClick: (Int) -> Unit,
    onGeneratedRowClick: (Int) -> Unit,
    onManualRowClick: (Int) -> Unit,
    onHeaderDialogRequest: (Int) -> Unit,
) {
    val spacing = MaterialTheme.appSpacing
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        if (isManualEntry && excelData.size <= 1) {
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
                    text = stringResource(R.string.no_products_add_new),
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
                selectedColumns = selectedColumns,
                editableValues = editableValues,
                completeStates = completeStates,
                searchMatches = searchMatches,
                errorRowIndexes = errorIndexes,
                generated = true,
                editMode = false,
                onCompleteToggle = onCompleteToggle,
                onCellEditRequest = { _, _ -> },
                onQuantityCellClick = onQuantityClick,
                onPriceCellClick = onPriceClick,
                onRowCellClick = { row ->
                    if (isManualEntry) onManualRowClick(row) else onGeneratedRowClick(row)
                },
                columnKeys = excelData.firstOrNull(),
                onHeaderClick = onHeaderDialogRequest,
                isColumnEssential = { false },
                onHeaderEditClick = onHeaderDialogRequest,
                isManualEntry = isManualEntry
            )
        }
    }
}

@Composable
private fun GeneratedScreenFabArea(
    visible: Boolean,
    isManualEntry: Boolean,
    onLaunchScanner: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val spacing = MaterialTheme.appSpacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        horizontalAlignment = Alignment.End
    ) {
        if (!isManualEntry) {
            SmallFloatingActionButton(
                onClick = onOpenSearch,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search_icon_desc)
                )
            }
        }

        FloatingActionButton(
            onClick = onLaunchScanner,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = stringResource(
                    if (isManualEntry) R.string.scanner else R.string.scan_icon_desc
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratedScreenInfoDialog(
    excelViewModel: ExcelViewModel,
    entryUid: Long,
    infoRowIndex: Int,
    infoDialogFocusField: Int,
    editableValues: List<List<MutableState<String>>>,
    completeStates: androidx.compose.runtime.snapshots.SnapshotStateList<Boolean>,
    productNameState: MutableState<TextFieldValue>,
    secondProductNameState: MutableState<TextFieldValue>,
    barcodeState: MutableState<TextFieldValue>,
    itemNumberState: MutableState<TextFieldValue>,
    quantityState: MutableState<TextFieldValue>,
    totalPriceState: MutableState<TextFieldValue>,
    purchasePriceState: MutableState<TextFieldValue>,
    oldPurchasePriceState: MutableState<TextFieldValue>,
    oldRetailPriceState: MutableState<TextFieldValue>,
    isInfoDialogInEditMode: Boolean,
    onInfoDialogEditModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onLaunchBarcodeScanner: () -> Unit,
    onOpenGenericCalculator: () -> Unit,
    onOpenPurchaseCalculator: (String) -> Unit,
) {
    val spacing = MaterialTheme.appSpacing
    val context = LocalContext.current
    val rowUpdatedText = stringResource(R.string.row_updated)
    val header = excelViewModel.excelData.firstOrNull() ?: return
    val row = excelViewModel.excelData.getOrNull(infoRowIndex) ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    LaunchedEffect(infoRowIndex, infoDialogFocusField) {
        delay(150)
        if (infoDialogFocusField == 0) qtyReq.requestFocus() else priceReq.requestFocus()
    }

    fun persistRowChanges() {
        val updatedRow = excelViewModel.excelData[infoRowIndex].toMutableList()
        fun setValue(key: String, value: String) {
            val idx = header.indexOf(key)
            if (idx != -1) updatedRow[idx] = value
        }

        setValue("productName", productNameState.value.text)
        setValue("secondProductName", secondProductNameState.value.text)
        setValue("barcode", barcodeState.value.text)
        setValue("itemNumber", itemNumberState.value.text)
        setValue("quantity", quantityState.value.text)
        setValue("totalPrice", totalPriceState.value.text)
        setValue("purchasePrice", purchasePriceState.value.text)

        excelViewModel.excelData[infoRowIndex] = updatedRow
        excelViewModel.updateHistoryEntry(entryUid)
        Toast.makeText(
            context,
            rowUpdatedText,
            Toast.LENGTH_SHORT
        ).show()
    }

    var qtyTf by remember(infoRowIndex) {
        mutableStateOf(
            TextFieldValue(
                editableValues[infoRowIndex][0].value,
                TextRange(editableValues[infoRowIndex][0].value.length)
            )
        )
    }
    var priceTf by remember(infoRowIndex) {
        mutableStateOf(
            TextFieldValue(
                editableValues[infoRowIndex][1].value,
                TextRange(editableValues[infoRowIndex][1].value.length)
            )
        )
    }

    val isCurrentlyComplete = completeStates.getOrNull(infoRowIndex) == true
    val completionStatusText = if (isCurrentlyComplete) {
        stringResource(R.string.status_completed)
    } else {
        stringResource(R.string.status_incomplete)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (isInfoDialogInEditMode) {
                                persistRowChanges()
                            }
                            onInfoDialogEditModeChange(!isInfoDialogInEditMode)
                        }
                    ) {
                        Icon(
                            imageVector = if (isInfoDialogInEditMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (isInfoDialogInEditMode) {
                                stringResource(R.string.save_changes)
                            } else {
                                stringResource(R.string.edit_row)
                            }
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.row_info),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    GeneratedScreenStatusToggleButton(
                        text = completionStatusText,
                        isComplete = isCurrentlyComplete,
                        onClick = {
                            completeStates[infoRowIndex] = !completeStates[infoRowIndex]
                            excelViewModel.updateHistoryEntry(entryUid)
                            onDismiss()
                        }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.sm)
                    .focusRequester(backgroundFocusRequester)
                    .focusable()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        backgroundFocusRequester.requestFocus()
                    },
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                GeneratedScreenDetailCard(
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.md),
                    verticalSpacing = spacing.sm
                ) {
                    if (isInfoDialogInEditMode) {
                        if (header.contains("productName")) {
                            GeneratedScreenEditableInfoRow(
                                label = stringResource(R.string.header_product_name),
                                state = productNameState,
                                isEditMode = true
                            )
                        }

                        if (header.contains("secondProductName")) {
                            GeneratedScreenEditableInfoRow(
                                label = stringResource(R.string.header_second_product_name),
                                state = secondProductNameState,
                                isEditMode = true
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            Text(
                                text = stringResource(R.string.header_barcode),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = barcodeState.value,
                                onValueChange = { barcodeState.value = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                trailingIcon = {
                                    IconButton(onClick = onLaunchBarcodeScanner) {
                                        Icon(
                                            imageVector = Icons.Filled.CameraAlt,
                                            contentDescription = stringResource(R.string.scan_barcode_for_editing),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }

                        if (header.contains("itemNumber")) {
                            GeneratedScreenEditableInfoRow(
                                label = stringResource(R.string.header_item_number),
                                state = itemNumberState,
                                isEditMode = true,
                                keyboardType = KeyboardType.Number
                            )
                        }
                    } else {
                        val productName = productNameState.value.text.ifBlank { "—" }
                        val secondName = secondProductNameState.value.text
                            .takeIf { it.isNotBlank() && !it.equals(productNameState.value.text, ignoreCase = true) }
                        val barcode = barcodeState.value.text
                        val itemNumber = itemNumberState.value.text

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = productName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                secondName?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        BoxWithConstraints {
                            val useInlineMeta = maxWidth >= 260.dp && barcode.isNotBlank() && itemNumber.isNotBlank()

                            if (useInlineMeta) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.md)
                                ) {
                                    GeneratedScreenCompactMetaBlock(
                                        label = stringResource(R.string.header_barcode),
                                        value = barcode,
                                        modifier = Modifier.weight(1f),
                                        monospaced = true
                                    )
                                    GeneratedScreenCompactMetaBlock(
                                        label = stringResource(R.string.header_item_number),
                                        value = itemNumber,
                                        modifier = Modifier.weight(1f),
                                        monospaced = true
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                    if (barcode.isNotBlank()) {
                                        GeneratedScreenCompactMetaBlock(
                                            label = stringResource(R.string.header_barcode),
                                            value = barcode,
                                            monospaced = true
                                        )
                                    }
                                    if (itemNumber.isNotBlank()) {
                                        GeneratedScreenCompactMetaBlock(
                                            label = stringResource(R.string.header_item_number),
                                            value = itemNumber,
                                            monospaced = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                GeneratedScreenDetailCard(
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.md),
                    verticalSpacing = 10.dp
                ) {
                    BoxWithConstraints {
                        val useSideBySideEditors = maxWidth >= 260.dp

                        val purchaseBlock: @Composable () -> Unit = {
                            if (isInfoDialogInEditMode) {
                                GeneratedScreenCompactInputField(
                                    label = stringResource(R.string.header_purchase_price),
                                    value = purchasePriceState.value,
                                    onValueChange = { purchasePriceState.value = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    normalizeOnBlur = ::normalizeClPriceInput,
                                    supportingText = {
                                        if (oldPurchasePriceDiffersFromCurrent(
                                                purchasePriceState.value.text,
                                                oldPurchasePriceState.value.text
                                            )
                                        ) {
                                            Text(
                                                text = "${stringResource(R.string.header_old_purchase_price_short)}: ${formatClPricePlainDisplay(parseUserPriceInput(oldPurchasePriceState.value.text))}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textDecoration = TextDecoration.LineThrough
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { onOpenPurchaseCalculator(purchasePriceState.value.text) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Calculate,
                                                contentDescription = stringResource(R.string.calculate_new_value)
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.large,
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(
                                                start = spacing.md,
                                                end = spacing.xxs,
                                                top = 10.dp,
                                                bottom = 10.dp
                                            ),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.header_purchase_price),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                                        alpha = 0.75f
                                                    )
                                                )
                                                Text(
                                                    text = parseUserPriceInput(purchasePriceState.value.text)
                                                        ?.let(::formatClPricePlainDisplay)
                                                        ?: purchasePriceState.value.text.ifBlank { "—" },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            IconButton(
                                                onClick = { onOpenPurchaseCalculator(purchasePriceState.value.text) }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Calculate,
                                                    contentDescription = stringResource(R.string.calculate_new_value),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    if (oldPurchasePriceDiffersFromCurrent(
                                            purchasePriceState.value.text,
                                            oldPurchasePriceState.value.text
                                        )
                                    ) {
                                        Text(
                                            text = "${stringResource(R.string.header_old_purchase_price_short)}: ${formatClPricePlainDisplay(parseUserPriceInput(oldPurchasePriceState.value.text))}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textDecoration = TextDecoration.LineThrough
                                        )
                                    }
                                }
                            }
                        }

                        val countedField: @Composable (Modifier) -> Unit = { modifier ->
                            GeneratedScreenCompactInputField(
                                label = getLocalizedHeader(context, "realQuantity"),
                                value = qtyTf,
                                onValueChange = { newValue ->
                                    qtyTf = newValue
                                    editableValues[infoRowIndex][0].value = newValue.text
                                    excelViewModel.updateHistoryEntry(entryUid)
                                },
                                modifier = modifier,
                                fieldModifier = Modifier.focusRequester(qtyReq),
                                normalizeOnBlur = ::normalizeClQuantityInput,
                                supportingText = {
                                    if (quantityState.value.text.isNotBlank()) {
                                        Text("${stringResource(R.string.header_quantity)}: ${formatClQuantityDisplayReadOnly(parseUserQuantityInput(quantityState.value.text))}")
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(onNext = { priceReq.requestFocus() })
                            )
                        }

                        val retailField: @Composable (Modifier) -> Unit = { modifier ->
                            GeneratedScreenCompactInputField(
                                label = getLocalizedHeader(context, "retailPrice"),
                                value = priceTf,
                                onValueChange = { newValue ->
                                    priceTf = newValue
                                    editableValues[infoRowIndex][1].value = newValue.text
                                    excelViewModel.updateHistoryEntry(entryUid)
                                },
                                modifier = modifier,
                                normalizeOnBlur = ::normalizeClPriceInput,
                                supportingText = {
                                    if (oldRetailPriceState.value.text.isNotBlank()) {
                                        Text("${stringResource(R.string.header_old_retail_price)}: ${formatClPricePlainDisplay(parseUserPriceInput(oldRetailPriceState.value.text))}")
                                    }
                                },
                                fieldModifier = Modifier.focusRequester(priceReq),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val normalizedCountedQty = normalizeClQuantityInput(editableValues[infoRowIndex][0].value)
                                        qtyTf = TextFieldValue(normalizedCountedQty, TextRange(normalizedCountedQty.length))
                                        editableValues[infoRowIndex][0].value = normalizedCountedQty

                                        val normalizedRetailPrice = normalizeClPriceInput(editableValues[infoRowIndex][1].value)
                                        priceTf = TextFieldValue(normalizedRetailPrice, TextRange(normalizedRetailPrice.length))
                                        editableValues[infoRowIndex][1].value = normalizedRetailPrice

                                        val originalQtyString = quantityState.value.text
                                        val countedQty = parseUserQuantityInput(normalizedCountedQty)
                                        val originalQty = parseUserQuantityInput(originalQtyString)

                                        if (countedQty != null && countedQty == originalQty) {
                                            completeStates[infoRowIndex] = true
                                            excelViewModel.updateHistoryEntry(entryUid)
                                        }
                                        onDismiss()
                                    }
                                )
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            purchaseBlock()
                            if (useSideBySideEditors) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    countedField(Modifier.weight(1f))
                                    retailField(Modifier.weight(1f))
                                }
                            } else {
                                countedField(Modifier.fillMaxWidth())
                                retailField(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onOpenGenericCalculator,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Calculate,
                        contentDescription = stringResource(R.string.fast_calculator_desc)
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text(text = stringResource(R.string.generic_calculator_title))
                }

                GeneratedScreenDetailCard(
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.md),
                    verticalSpacing = spacing.sm
                ) {
                    GeneratedScreenDetailValueRow(
                        label = stringResource(R.string.header_quantity),
                        value = formatClQuantityDisplayReadOnly(parseUserQuantityInput(quantityState.value.text))
                    )
                    GeneratedScreenDetailValueRow(
                        label = stringResource(R.string.header_total_price),
                        value = formatClPricePlainDisplay(parseUserPriceInput(totalPriceState.value.text))
                    )
                }

                Spacer(modifier = Modifier.height(spacing.sm))
            }
        }
    }
}

@Composable
private fun GeneratedScreenEditableInfoRow(
    label: String,
    state: MutableState<TextFieldValue>,
    isEditMode: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isEditMode) {
            OutlinedTextField(
                value = state.value,
                onValueChange = { state.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = keyboardType
                                )
            )
        } else {
            Text(
                text = state.value.text.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GeneratedScreenDetailCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalSpacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content
        )
    }
}

@Composable
private fun GeneratedScreenCompactInputField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    normalizeOnBlur: ((String) -> String)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused && normalizeOnBlur != null) {
                        val normalized = normalizeOnBlur(value.text)
                        if (normalized != value.text) {
                            onValueChange(TextFieldValue(normalized, TextRange(normalized.length)))
                        }
                    }
                },
            placeholder = { Text("—") },
            supportingText = supportingText,
            trailingIcon = trailingIcon,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
    }
}

@Composable
private fun GeneratedScreenStatusToggleButton(
    text: String,
    isComplete: Boolean,
    onClick: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isComplete) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            },
            contentColor = if (isComplete) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            }
        ),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GeneratedScreenCompactMetaBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospaced: Boolean = false
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "—" },
            style = if (monospaced) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GeneratedScreenDetailValueRow(
    label: String,
    value: String,
    strikeThrough: Boolean = false
) {
    val spacing = MaterialTheme.appSpacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "—" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            textDecoration = if (strikeThrough) TextDecoration.LineThrough else TextDecoration.None,
            color = if (strikeThrough) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratedScreenRenameDialog(
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    supplierName: String,
    onSupplierNameChange: (String) -> Unit,
    categoryName: String,
    onCategoryNameChange: (String) -> Unit,
    supplierExpanded: Boolean,
    onSupplierExpandedChange: (Boolean) -> Unit,
    categoryExpanded: Boolean,
    onCategoryExpandedChange: (Boolean) -> Unit,
    suppliers: List<com.example.merchandisecontrolsplitview.data.Supplier>,
    categories: List<com.example.merchandisecontrolsplitview.data.Category>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.appSpacing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = onRenameTextChange,
                    label = { Text(stringResource(R.string.new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = supplierExpanded,
                    onExpandedChange = { onSupplierExpandedChange(!supplierExpanded) }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = supplierName.ifBlank { stringResource(R.string.no_supplier) },
                        onValueChange = {},
                        label = { Text(stringResource(R.string.supplier_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = supplierExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = supplierExpanded,
                        onDismissRequest = { onSupplierExpandedChange(false) }
                    ) {
                        suppliers.forEach { supplier ->
                            DropdownMenuItem(
                                text = { Text(supplier.name) },
                                onClick = {
                                    onSupplierNameChange(supplier.name)
                                    onSupplierExpandedChange(false)
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { onCategoryExpandedChange(!categoryExpanded) }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = categoryName.ifBlank { stringResource(R.string.no_category) },
                        onValueChange = {},
                        label = { Text(stringResource(R.string.category_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { onCategoryExpandedChange(false) }
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    onCategoryNameChange(category.name)
                                    onCategoryExpandedChange(false)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun GeneratedScreenCustomHeaderDialog(
    customHeader: String,
    onCustomHeaderChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.custom_header_dialog_title)) },
        text = {
            OutlinedTextField(
                value = customHeader,
                onValueChange = onCustomHeaderChange,
                label = { Text(stringResource(R.string.custom_header_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun GeneratedScreenHeaderTypeDialog(
    possibleKeys: List<String>,
    onSelectHeaderType: (String) -> Unit,
    onOpenCustomHeader: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_column_type)) },
        text = {
            Column {
                possibleKeys.forEach { key ->
                    TextButton(onClick = { onSelectHeaderType(key) }) {
                        Text(getLocalizedHeader(context, key))
                    }
                }
                TextButton(onClick = onOpenCustomHeader) {
                    Text(stringResource(R.string.custom_column_type))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
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
    val spacing = MaterialTheme.appSpacing
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
                        .padding(bottom = spacing.xs)
                )
                val errorText = stringResource(R.string.error_label)
                Text(
                    text = if (result.isNotBlank() && result != errorText) {
                        stringResource(R.string.result) + " ${parseUserNumericInput(result)?.let(::formatClQuantityDisplayReadOnly) ?: result}"
                    } else stringResource(R.string.result),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = spacing.xxl)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(spacing.sm)
                )
                Spacer(Modifier.height(spacing.sm))
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
                    buttonSize = 68.dp,
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
    badgeType: BadgeType,
    contentDescription: String
) {
    val appColors = MaterialTheme.appColors
    val spacing = MaterialTheme.appSpacing
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
                        .offset(x = spacing.xxs, y = -spacing.xxs),
                    tint = appColors.success
                )
            }
            BadgeType.WARNING -> {
                Icon(
                    imageVector = Icons.Default.Error, // Icona di errore/avviso
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
    val spacing = MaterialTheme.appSpacing
    val focusManager = LocalFocusManager.current
    val noScannerResultText = stringResource(R.string.no_scanner_result)
    val productAlreadyInListText = stringResource(R.string.product_already_in_list)
    val productFoundInDbText = stringResource(R.string.product_found_in_db)
    val scanPromptText = stringResource(R.string.scan_prompt)

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
    val manualEntryDefaultCategoryText = stringResource(R.string.manual_entry_default_category)
    val header = viewModel.excelData.firstOrNull() ?: return
    val catName = selectedCategory?.name ?: manualEntryDefaultCategoryText

    val (barIdx, nameIdx, priceIdx, qtyIdx, catIdx) = remember(header) {
        listOf("barcode", "productName", "retailPrice", "quantity", "category").map { header.indexOf(it) }
    }

    val context = LocalContext.current
    val dialogScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            barcode = code
        } ?: Toast.makeText(context, noScannerResultText, Toast.LENGTH_SHORT).show()
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
        !equalProductsForDb(currentNormalized, productFromDb)
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

    LaunchedEffect(rowIndexToEdit, productToPrefill, initialBarcode) {
        productName = ""
        retailPrice = TextFieldValue("")
        quantity = TextFieldValue("1")
        selectedCategory = null
        databaseViewModel.onCategorySearchQueryChanged("")

        barcodeError = null
        originalBarcodeForEdit = null
        originalProductData = null

        if (isEditMode) {
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

            scope.launch {
                repeat(5) {
                    val foundCategory = databaseViewModel.categories.value.find { it.name.equals(catName, true) }
                    if (foundCategory != null) {
                        selectedCategory = foundCategory

                        originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                            barcode = barcode,
                            productName = productName,
                            retailPrice = parseUserPriceInput(retailPrice.text),
                            stockQuantity = parseUserQuantityInput(quantity.text),
                            categoryId = foundCategory.id
                        )
                        return@launch
                    }
                    delay(200)
                }
                originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                    barcode = barcode,
                    productName = productName,
                    retailPrice = parseUserPriceInput(retailPrice.text),
                    stockQuantity = parseUserQuantityInput(quantity.text),
                    categoryId = null
                )
            }

        } else if (productToPrefill != null) {
            val initialBarcode = productToPrefill.barcode
            val initialProductName = productToPrefill.productName ?: ""
            val initialRetailPrice =
                formatClPriceInput(productToPrefill.retailPrice)
            val initialQuantity = "1"

            barcode = initialBarcode
            productName = initialProductName
            retailPrice = TextFieldValue(initialRetailPrice, TextRange(initialRetailPrice.length))
            quantity = TextFieldValue(initialQuantity)

            productToPrefill.categoryId?.let { catId ->
                scope.launch {
                    val category = databaseViewModel.getCategoryById(catId)
                    selectedCategory = category
                    databaseViewModel.onCategorySearchQueryChanged(category?.name ?: "")

                    originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                        barcode = initialBarcode,
                        productName = initialProductName,
                        retailPrice = parseUserPriceInput(initialRetailPrice),
                        stockQuantity = parseUserQuantityInput(initialQuantity),
                        categoryId = category?.id,
                        id = productToPrefill.id
                    )
                }
            } ?: run {
                originalProductData = com.example.merchandisecontrolsplitview.data.Product(
                    barcode = initialBarcode,
                    productName = initialProductName,
                    retailPrice = parseUserPriceInput(initialRetailPrice),
                    stockQuantity = parseUserQuantityInput(initialQuantity),
                    categoryId = null,
                    id = productToPrefill.id
                )
            }

        } else {
            barcode = initialBarcode ?: ""
            viewModel.lastUsedCategory.value?.let {
                databaseViewModel.onCategorySearchQueryChanged(it)
                selectedCategory = databaseViewModel.categories.value.find { c -> c.name.equals(it, true) }
            }
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
            barcodeError = productAlreadyInListText
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
                barcodeInfo = inDb?.let { productFoundInDbText }
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
            (parseUserPriceInput(retailPrice.text) ?: 0.0) > 0.0

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
                verticalArrangement = Arrangement.spacedBy(spacing.md)
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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
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
                            productName = ""
                            retailPrice = TextFieldValue("")
                            quantity = TextFieldValue("1")
                            productFromDb = null
                            barcodeError = null

                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                setBeepEnabled(true)
                                setPrompt(scanPromptText)
                                setCaptureActivity(PortraitCaptureActivity::class.java)
                            }
                            dialogScanLauncher.launch(options)
                        }) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = stringResource(R.string.scan_barcode)
                            )
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

                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedTextField(
                        value = purchasePrice,
                        onValueChange = { purchasePrice = it },
                        label = { Text(stringResource(R.string.header_purchase_price)) },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    purchasePrice = normalizeClPriceInput(purchasePrice)
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { quantityFocusRequester.requestFocus() })
                    )
                    OutlinedTextField(
                        value = retailPrice,
                        onValueChange = { retailPrice = it },
                        label = { Text(stringResource(R.string.header_retail_price) + "*") },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(priceFocusRequester)
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    val normalized = normalizeClPriceInput(retailPrice.text)
                                    retailPrice = TextFieldValue(normalized, TextRange(normalized.length))
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { quantityFocusRequester.requestFocus() })
                    )
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text(stringResource(R.string.header_quantity)) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(quantityFocusRequester)
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    val normalized = normalizeClQuantityInput(quantity.text)
                                    quantity = TextFieldValue(normalized, TextRange(normalized.length))
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
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
                        Column(Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                            Text(stringResource(R.string.data_from_database), style = MaterialTheme.typography.labelMedium)
                            val formattedPrice = formatClPricePlainDisplay(productFromDb?.retailPrice)
                            val retailPriceInput = formatClPriceInput(productFromDb?.retailPrice)
                            val databaseProductName = productFromDb?.productName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.untitled)
                            Text(
                                stringResource(
                                    R.string.database_lookup_price_summary,
                                    databaseProductName,
                                    formattedPrice
                                )
                            )
                            TextButton(onClick = {
                                retailPrice = TextFieldValue(retailPriceInput, TextRange(retailPriceInput.length))
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
                    "productName" -> productName.ifBlank { selectedCategory?.name ?: manualEntryDefaultCategoryText }
                    "purchasePrice" -> {
                        val rp = parseUserPriceInput(retailPrice.text)
                        val pp = parseUserPriceInput(purchasePrice) ?: (rp?.div(2.0))
                        pp?.let(::formatClPriceInput).orEmpty()
                    }
                    "retailPrice" -> normalizeClPriceInput(retailPrice.text)
                    "quantity" -> normalizeClQuantityInput(quantity.text)
                    "category" -> selectedCategory?.name ?: manualEntryDefaultCategoryText
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
    return com.example.merchandisecontrolsplitview.data.Product(
        barcode = barcode,
        productName = name.ifBlank { null },
        retailPrice = parseUserPriceInput(retailPriceStr),
        stockQuantity = parseUserQuantityInput(qtyStr),
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
@Composable
private fun MenuIconWithTick(
    base: ImageVector,
    showTick: Boolean
) {
    val appColors = MaterialTheme.appColors
    Box(Modifier.size(24.dp)) {
        Icon(base, contentDescription = null)
        if (showTick) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = appColors.success,
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
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
) {
    val spacing = MaterialTheme.appSpacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        supplier?.let { InfoChip(it) }
        category?.let { InfoChip(it) }
        InfoChip("${formatClCount(completed)}/${formatClCount(total)}")
        if (exported) InfoChip(stringResource(R.string.exported_short), tonal = true)
    }
}

@Composable
private fun InfoChip(text: String, tonal: Boolean = false) {
    val spacing = MaterialTheme.appSpacing
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (tonal) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        },
        contentColor = if (tonal) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)
        )
    }
}

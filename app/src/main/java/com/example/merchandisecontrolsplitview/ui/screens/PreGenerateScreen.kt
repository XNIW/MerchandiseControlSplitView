package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import kotlinx.coroutines.launch

private val preGenerateMimeTypes = arrayOf(
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/html",
    "application/octet-stream"
)
private val preGenerateFabEdgePadding = 16.dp
private val preGenerateFabSpacing = 12.dp
private val preGeneratePrimaryFabHeight = 56.dp
private val preGenerateSecondaryFabHeight = 40.dp
private val preGenerateErrorMaxWidth = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreGenerateScreen(
    excelViewModel: ExcelViewModel,
    databaseUiState: UiState,
    databaseViewModel: DatabaseViewModel,
    onGenerate: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val excelData = excelViewModel.excelData
    val selectedColumns = excelViewModel.selectedColumns
    val editableValues = excelViewModel.editableValues
    val completeStates = excelViewModel.completeStates
    val isExcelLoading by excelViewModel.isLoading
    val excelProgress by excelViewModel.loadingProgress
    val excelLoadError by excelViewModel.loadError
    val headerTypes = excelViewModel.headerTypes

    val context = LocalContext.current
    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var excelLoadingTimedOut by remember { mutableStateOf(false) }
    var databaseLoadingTimedOut by remember { mutableStateOf(false) }
    var resetInlineSelectionsOnNextDataset by remember { mutableStateOf(true) }

    val supplierInputText by databaseViewModel.supplierInputText.collectAsState()
    var selectedSupplier by remember { mutableStateOf<Supplier?>(null) }
    var isSupplierDropdownExpanded by remember { mutableStateOf(false) }
    val supplierSuggestions by databaseViewModel.suppliers.collectAsState()

    val categoryInputText by databaseViewModel.categoryInputText.collectAsState()
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }
    val categorySuggestions by databaseViewModel.categories.collectAsState()

    fun clearInlineSelections() {
        databaseViewModel.onSupplierSearchQueryChanged("")
        databaseViewModel.onCategorySearchQueryChanged("")
        selectedSupplier = null
        selectedCategory = null
        isSupplierDropdownExpanded = false
        isCategoryDropdownExpanded = false
    }

    val reloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            resetInlineSelectionsOnNextDataset = true
            excelViewModel.resetState()
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
    val launchAppendPicker = { appendLauncher.launch(preGenerateMimeTypes) }
    val launchReloadPicker = { reloadLauncher.launch(preGenerateMimeTypes) }
    val navigationBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Keep the preview clear of the full FAB stack and the current navigation bar inset.
    val previewBottomPadding =
        navigationBarBottomInset +
            (preGenerateFabEdgePadding * 2) +
            preGeneratePrimaryFabHeight +
            preGenerateSecondaryFabHeight +
            preGenerateFabSpacing

    val possibleKeys = listOf(
        "barcode", "quantity", "purchasePrice", "retailPrice", "totalPrice",
        "productName", "secondProductName", "itemNumber", "supplier", "rowNumber",
        "discount", "discountedPrice"
    )
    val isDatabaseLoading = databaseUiState is UiState.Loading
    val showExcelLoadingDialog = isExcelLoading && !excelLoadingTimedOut
    val showDatabaseLoadingDialog = isDatabaseLoading && !databaseLoadingTimedOut

    LaunchedEffect(isExcelLoading) {
        if (!isExcelLoading) {
            excelLoadingTimedOut = false
        }
    }

    LaunchedEffect(isDatabaseLoading) {
        if (!isDatabaseLoading) {
            databaseLoadingTimedOut = false
        }
    }

    LaunchedEffect(excelData.size) {
        when {
            excelData.isEmpty() -> resetInlineSelectionsOnNextDataset = true
            resetInlineSelectionsOnNextDataset -> {
                clearInlineSelections()
                resetInlineSelectionsOnNextDataset = false
            }
        }
    }

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
                    IconButton(onClick = launchAppendPicker) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.append_file))
                    }
                    IconButton(onClick = launchReloadPicker) {
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
            val isAnalysisInProgress = showDatabaseLoadingDialog
            val analysisError = (databaseUiState as? UiState.Error)?.message

            val headers = excelData.firstOrNull() ?: emptyList()
            val missingEssentialColumns = setOf("barcode", "productName", "purchasePrice")
                .filterNot { headers.contains(it) }
            val isSupplierSelectionValid =
                selectedSupplier != null && selectedSupplier?.name == supplierInputText
            val isCategorySelectionValid =
                selectedCategory != null && selectedCategory?.name == categoryInputText
            val isGenerateEnabled =
                isSupplierSelectionValid &&
                    isCategorySelectionValid &&
                    missingEssentialColumns.isEmpty()

            when {
                showExcelLoadingDialog || showDatabaseLoadingDialog -> {
                    when {
                        showExcelLoadingDialog -> LoadingDialog(
                            loading = UiState.Loading(
                                message = stringResource(R.string.loading_file),
                                progress = excelProgress
                            ),
                            onSafetyTimeout = {
                                excelLoadingTimedOut = true
                                excelViewModel.resetState()
                            }
                        )
                        showDatabaseLoadingDialog -> LoadingDialog(
                            loading = databaseUiState,
                            onSafetyTimeout = {
                                databaseLoadingTimedOut = true
                                databaseViewModel.consumeUiState()
                            }
                        )
                    }
                }
                excelLoadError != null || analysisError != null -> {
                    PreGenerateErrorState(
                        message = excelLoadError ?: analysisError ?: stringResource(R.string.unknown_error),
                        onChooseAgain = launchReloadPicker,
                        onBack = onBack,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                excelData.isNotEmpty() -> {
                    val dataQualitySummary by remember {
                        derivedStateOf { excelViewModel.getPreGenerateDataQualitySummary() }
                    }
                    val blockingMessage = when {
                        missingEssentialColumns.isNotEmpty() -> stringResource(
                            R.string.error_missing_essential_columns_prompt,
                            missingEssentialColumns.joinToString { getLocalizedHeader(context, it) }
                        )
                        !isSupplierSelectionValid || !isCategorySelectionValid ->
                            stringResource(R.string.pre_generate_generate_requirements)
                        else -> null
                    }
                    val duplicateWarningMessage =
                        if (dataQualitySummary.duplicateBarcodeCount > 0) {
                            val sampleBarcodes = dataQualitySummary.duplicateBarcodeSamples.joinToString(", ")
                            if (sampleBarcodes.isNotBlank()) {
                                stringResource(
                                    R.string.pre_generate_warning_duplicate_barcodes_with_sample,
                                    dataQualitySummary.duplicateBarcodeCount,
                                    sampleBarcodes
                                )
                            } else {
                                stringResource(
                                    R.string.pre_generate_warning_duplicate_barcodes,
                                    dataQualitySummary.duplicateBarcodeCount
                                )
                            }
                        } else {
                            null
                        }
                    val missingPurchasePriceWarningMessage =
                        if (dataQualitySummary.missingPurchasePriceCount > 0) {
                            stringResource(
                                R.string.pre_generate_warning_missing_purchase_prices,
                                dataQualitySummary.missingPurchasePriceCount
                            )
                        } else {
                            null
                        }
                    val warningMessages = listOfNotNull(
                        duplicateWarningMessage,
                        missingPurchasePriceWarningMessage
                    )
                    val localizedData = listOf(excelData[0].map { getLocalizedHeader(context, it) }) + excelData.drop(1)
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(24.dp),
                            tonalElevation = 2.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
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
                                        placeholder = { Text(stringResource(R.string.search_or_add_supplier)) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(
                                                ExposedDropdownMenuAnchorType.PrimaryEditable,
                                                enabled = true
                                            )
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
                                        if (
                                            supplierSuggestions.none {
                                                it.name.equals(supplierInputText, true)
                                            } && supplierInputText.isNotBlank()
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(
                                                            R.string.add_new_supplier_prompt,
                                                            supplierInputText
                                                        )
                                                    )
                                                },
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
                                        placeholder = { Text(stringResource(R.string.search_or_add_category)) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(
                                                ExposedDropdownMenuAnchorType.PrimaryEditable,
                                                enabled = true
                                            )
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
                                        if (
                                            categorySuggestions.none {
                                                it.name.equals(categoryInputText, true)
                                            } && categoryInputText.isNotBlank()
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(
                                                            R.string.add_new_category_prompt,
                                                            categoryInputText
                                                        )
                                                    )
                                                },
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

                                if (blockingMessage != null || warningMessages.isNotEmpty()) {
                                    PreGenerateStatusCard(
                                        blockingMessage = blockingMessage,
                                        warningMessages = warningMessages,
                                        isError = missingEssentialColumns.isNotEmpty()
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(bottom = previewBottomPadding)
                        ) {
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
                                columnKeys = excelData.firstOrNull(),
                                isColumnEssential = { colIdx -> excelViewModel.isColumnEssential(colIdx) },
                                onHeaderClick = { colIdx -> excelViewModel.toggleColumnSelection(colIdx) },
                                onHeaderEditClick = { colIdx -> headerDialogIndex = colIdx },
                                isManualEntry = false
                            )
                        }
                    }
                }
            }

            if (excelData.isNotEmpty() && !isExcelLoading && !isAnalysisInProgress) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(preGenerateFabSpacing),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(preGenerateFabEdgePadding)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            excelViewModel.toggleSelectAll()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.select_all))
                    }
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(R.string.generate_action)) },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.generate_filtered_sheet)
                            )
                        },
                        onClick = {
                            val supplier = selectedSupplier
                            val category = selectedCategory
                            if (isGenerateEnabled && supplier != null && category != null) {
                                onGenerate(supplier.name, category.name)
                            }
                        },
                        modifier = Modifier.widthIn(min = 160.dp),
                        expanded = true,
                        containerColor = if (isGenerateEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (isGenerateEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
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

@Composable
private fun PreGenerateErrorState(
    message: String,
    onChooseAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(max = preGenerateErrorMaxWidth),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.pre_generate_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onChooseAgain) {
                    Text(stringResource(R.string.choose_again))
                }
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}

@Composable
private fun PreGenerateStatusCard(
    blockingMessage: String?,
    warningMessages: List<String>,
    isError: Boolean
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blockingMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                warningMessages.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingColor
                    )
                }
            }
        }
    }
}

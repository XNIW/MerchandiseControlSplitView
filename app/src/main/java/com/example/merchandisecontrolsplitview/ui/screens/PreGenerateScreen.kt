package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.ui.theme.appColors
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.util.canonicalExcelHeaderKey
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import kotlinx.coroutines.launch
import kotlin.math.min

private val preGenerateMimeTypes = arrayOf(
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/html",
    "application/octet-stream"
)

private val preGeneratePossibleKeys = listOf(
    "barcode",
    "quantity",
    "purchasePrice",
    "retailPrice",
    "totalPrice",
    "productName",
    "secondProductName",
    "itemNumber",
    "supplier",
    "rowNumber",
    "discount",
    "discountedPrice"
)

private const val PRE_GENERATE_PREVIEW_ROW_LIMIT = 20
private const val PRE_GENERATE_COLUMN_EXAMPLE_LIMIT = 3

private val preGenerateErrorMaxWidth = 480.dp
private val preGeneratePreviewCompactColumnWidth = 76.dp
private val preGeneratePreviewRegularColumnWidth = 104.dp
private val preGeneratePreviewWideColumnWidth = 136.dp
private val preGeneratePreviewExtraWideColumnWidth = 176.dp
private val preGeneratePreviewTableMaxHeight = 304.dp
private val preGenerateKnownHeaderKeys = preGeneratePossibleKeys.toSet() + setOf(
    "oldPurchasePrice",
    "oldRetailPrice",
    "realQuantity",
    "complete",
    "category",
    "supplierId",
    "categoryId",
    "stockQuantity"
)

private enum class PreGenerateColumnStatus {
    IDENTIFIED,
    MANUAL,
    GENERATED,
    UNIDENTIFIED
}

private enum class PreGenerateColumnFilter {
    ALL,
    NEEDS_REVIEW,
    IDENTIFIED
}

private data class PreGenerateColumnUiModel(
    val index: Int,
    val title: String,
    val fileHeader: String,
    val examples: List<String>,
    val isSelected: Boolean,
    val isEssential: Boolean,
    val status: PreGenerateColumnStatus
)

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
    val isExcelLoading by excelViewModel.isLoading
    val excelProgress by excelViewModel.loadingProgress
    val excelLoadError by excelViewModel.loadError

    val context = LocalContext.current
    val density = LocalDensity.current
    val spacing = MaterialTheme.appSpacing

    var headerDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCustomHeaderDialog by remember { mutableStateOf(false) }
    var customHeader by remember { mutableStateOf("") }
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
    val navigationBarBottomInset = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }

    val isDatabaseLoading = databaseUiState is UiState.Loading
    val showExcelLoadingDialog = isExcelLoading && !excelLoadingTimedOut
    val showDatabaseLoadingDialog = isDatabaseLoading && !databaseLoadingTimedOut
    val analysisError = (databaseUiState as? UiState.Error)?.message

    val headers = excelData.firstOrNull()?.toList().orEmpty()
    val headerTypes = excelViewModel.headerTypes.toList()
    val originalHeaders = excelViewModel.originalHeaders.toList()
    val dataRows = excelData.drop(1)
    val totalDataRows = (excelData.size - 1).coerceAtLeast(0)
    val previewVisibleRows = min(totalDataRows, PRE_GENERATE_PREVIEW_ROW_LIMIT)
    val unidentifiedColumnTitle = stringResource(R.string.pre_generate_unidentified_column_title)
    val generatedColumnLabel = stringResource(R.string.pre_generate_generated_column_label)
    val previewData = if (headers.isEmpty()) {
        emptyList()
    } else {
        listOf(headers.map { getLocalizedHeader(context, it) }) +
            dataRows.take(PRE_GENERATE_PREVIEW_ROW_LIMIT).map { it.toList() }
    }
    val columnUiModels = headers.mapIndexed { index, headerKey ->
        val status = resolvePreGenerateColumnStatus(
            headerKey = headerKey,
            headerType = headerTypes.getOrNull(index)
        )
        val title = when {
            status == PreGenerateColumnStatus.UNIDENTIFIED ->
                unidentifiedColumnTitle
            isPreGenerateKnownHeaderKey(headerKey) ->
                getLocalizedHeader(context, headerKey)
            else -> headerKey
        }
        val fileHeader = originalHeaders.getOrNull(index)
            ?.takeIf { it.isNotBlank() }
            ?: when (status) {
                PreGenerateColumnStatus.GENERATED ->
                    generatedColumnLabel
                else -> headerKey
            }

        PreGenerateColumnUiModel(
            index = index,
            title = title,
            fileHeader = fileHeader,
            examples = buildColumnExamples(dataRows, index),
            isSelected = selectedColumns.getOrNull(index) == true,
            isEssential = excelViewModel.isColumnEssential(index),
            status = status
        )
    }
    val identifiedColumnCount = columnUiModels.count { it.status != PreGenerateColumnStatus.UNIDENTIFIED }
    val unidentifiedColumnCount = columnUiModels.count { it.status == PreGenerateColumnStatus.UNIDENTIFIED }
    val selectedColumnCount by remember {
        derivedStateOf { selectedColumns.count { it } }
    }
    val canSelectAllOptionalColumns by remember {
        derivedStateOf {
            selectedColumns.indices.any { index ->
                !selectedColumns[index] && !excelViewModel.isColumnEssential(index)
            }
        }
    }
    val hasOptionalColumnsSelected by remember {
        derivedStateOf {
            selectedColumns.indices.any { index ->
                selectedColumns[index] && !excelViewModel.isColumnEssential(index)
            }
        }
    }

    val dataQualitySummary by remember {
        derivedStateOf { excelViewModel.getPreGenerateDataQualitySummary() }
    }

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

    val generateDisabledReason = when {
        missingEssentialColumns.isNotEmpty() -> stringResource(
            R.string.error_missing_essential_columns_prompt,
            missingEssentialColumns.joinToString { getLocalizedHeader(context, it) }
        )
        !isSupplierSelectionValid && !isCategorySelectionValid ->
            stringResource(R.string.pre_generate_generate_requirements)
        !isSupplierSelectionValid ->
            stringResource(R.string.pre_generate_supplier_required)
        !isCategorySelectionValid ->
            stringResource(R.string.pre_generate_category_required)
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PreGenerateTopBar(
                onBack = onBack,
                onAppend = launchAppendPicker,
                onReload = launchReloadPicker
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding(),
                        contentPadding = PaddingValues(
                            start = spacing.xl,
                            top = spacing.lg,
                            end = spacing.xl,
                            bottom = spacing.xxl + navigationBarBottomInset
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.lg)
                    ) {
                        item(key = "preview") {
                            PreGeneratePreviewSection(
                                previewData = previewData,
                                visibleRows = previewVisibleRows,
                                totalRows = totalDataRows
                            )
                        }

                        if (generateDisabledReason != null) {
                            item(key = "blocking-message") {
                                PreGenerateNoticeCard(
                                    title = stringResource(R.string.pre_generate_action_needed_title),
                                    messages = listOf(generateDisabledReason),
                                    isError = true
                                )
                            }
                        }

                        if (warningMessages.isNotEmpty()) {
                            item(key = "warning-messages") {
                                PreGenerateNoticeCard(
                                    title = stringResource(R.string.status_warning),
                                    messages = warningMessages,
                                    isError = false
                                )
                            }
                        }

                        item(key = "columns") {
                            PreGenerateColumnsSection(
                                selectedCount = selectedColumnCount,
                                totalCount = columnUiModels.size,
                                identifiedCount = identifiedColumnCount,
                                needsReviewCount = unidentifiedColumnCount,
                                columns = columnUiModels,
                                canSelectAll = canSelectAllOptionalColumns,
                                canKeepOnlyRequired = hasOptionalColumnsSelected,
                                onSelectAll = { excelViewModel.toggleSelectAll() },
                                onKeepOnlyRequired = {
                                    if (hasOptionalColumnsSelected) {
                                        // Reuse the existing VM toggle semantics instead of adding
                                        // another source of truth for bulk selection.
                                        if (canSelectAllOptionalColumns) {
                                            excelViewModel.toggleSelectAll()
                                        }
                                        excelViewModel.toggleSelectAll()
                                    }
                                },
                                onToggleSelection = { index ->
                                    excelViewModel.toggleColumnSelection(index)
                                },
                                onEditType = { index -> headerDialogIndex = index }
                            )
                        }

                        item(key = "supplier") {
                            PreGenerateEntitySection(
                                title = stringResource(R.string.supplier_label),
                                supportingText = stringResource(R.string.pre_generate_supplier_section_supporting),
                                placeholder = stringResource(R.string.search_or_add_supplier),
                                inputText = supplierInputText,
                                expanded = isSupplierDropdownExpanded,
                                suggestions = supplierSuggestions,
                                selectedName = selectedSupplier?.name,
                                isSelectionValid = isSupplierSelectionValid,
                                pendingSelectionMessage = stringResource(R.string.pre_generate_selection_pending),
                                createPrompt = if (
                                    supplierSuggestions.none { it.name.equals(supplierInputText, ignoreCase = true) } &&
                                    supplierInputText.isNotBlank()
                                ) {
                                    stringResource(
                                        R.string.add_new_supplier_prompt,
                                        supplierInputText
                                    )
                                } else {
                                    null
                                },
                                onExpandedChange = { isSupplierDropdownExpanded = it },
                                onInputChange = {
                                    databaseViewModel.onSupplierSearchQueryChanged(it)
                                },
                                onSuggestionSelected = { suggestion ->
                                    databaseViewModel.onSupplierSearchQueryChanged(suggestion.name)
                                    selectedSupplier = suggestion
                                    isSupplierDropdownExpanded = false
                                },
                                onCreateRequested = {
                                    databaseViewModel.addSupplier(supplierInputText)
                                },
                                itemLabel = { it.name }
                            )
                        }

                        item(key = "category") {
                            PreGenerateEntitySection(
                                title = stringResource(R.string.category_label),
                                supportingText = stringResource(R.string.pre_generate_category_section_supporting),
                                placeholder = stringResource(R.string.search_or_add_category),
                                inputText = categoryInputText,
                                expanded = isCategoryDropdownExpanded,
                                suggestions = categorySuggestions,
                                selectedName = selectedCategory?.name,
                                isSelectionValid = isCategorySelectionValid,
                                pendingSelectionMessage = stringResource(R.string.pre_generate_selection_pending),
                                createPrompt = if (
                                    categorySuggestions.none { it.name.equals(categoryInputText, ignoreCase = true) } &&
                                    categoryInputText.isNotBlank()
                                ) {
                                    stringResource(
                                        R.string.add_new_category_prompt,
                                        categoryInputText
                                    )
                                } else {
                                    null
                                },
                                onExpandedChange = { isCategoryDropdownExpanded = it },
                                onInputChange = {
                                    databaseViewModel.onCategorySearchQueryChanged(it)
                                },
                                onSuggestionSelected = { suggestion ->
                                    databaseViewModel.onCategorySearchQueryChanged(suggestion.name)
                                    selectedCategory = suggestion
                                    isCategoryDropdownExpanded = false
                                },
                                onCreateRequested = {
                                    databaseViewModel.addCategory(categoryInputText)
                                },
                                itemLabel = { it.name }
                            )
                        }

                        item(key = "generate") {
                            PreGenerateGenerateSection(
                                selectedSupplierName = selectedSupplier?.name,
                                selectedCategoryName = selectedCategory?.name,
                                selectedColumnCount = selectedColumnCount,
                                totalColumnCount = columnUiModels.size,
                                totalRows = totalDataRows,
                                isGenerateEnabled = isGenerateEnabled,
                                generateDisabledReason = generateDisabledReason,
                                onGenerate = {
                                    val supplier = selectedSupplier
                                    val category = selectedCategory
                                    if (isGenerateEnabled && supplier != null && category != null) {
                                        onGenerate(supplier.name, category.name)
                                    }
                                }
                            )
                        }
                    }
                }

                else -> {
                    PreGenerateEmptyState(
                        onChooseFile = launchReloadPicker,
                        onBack = onBack,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
            }

            if (showCustomHeaderDialog && headerDialogIndex != null) {
                androidx.compose.material3.AlertDialog(
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
                                val index = headerDialogIndex ?: return@TextButton
                                if (customHeader.isNotBlank()) {
                                    excelViewModel.setHeaderType(index, customHeader.trim())
                                    showCustomHeaderDialog = false
                                    customHeader = ""
                                    headerDialogIndex = null
                                }
                            }
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showCustomHeaderDialog = false
                                customHeader = ""
                            }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            headerDialogIndex?.let { columnIndex ->
                val dialogColumn = columnUiModels.getOrNull(columnIndex)
                val canRestoreOriginalHeader = originalHeaders.getOrNull(columnIndex)?.isNotBlank() == true
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { headerDialogIndex = null },
                    title = { Text(stringResource(R.string.select_column_type)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            dialogColumn?.let { column ->
                                Text(
                                    text = stringResource(
                                        R.string.pre_generate_file_column_label,
                                        column.fileHeader
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = columnStatusLabel(column.status),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            preGeneratePossibleKeys.forEach { key ->
                                TextButton(
                                    onClick = {
                                        excelViewModel.setHeaderType(columnIndex, key)
                                        headerDialogIndex = null
                                    }
                                ) {
                                    Text(getLocalizedHeader(context, key))
                                }
                            }
                            TextButton(
                                onClick = {
                                    showCustomHeaderDialog = true
                                    headerDialogIndex = columnIndex
                                }
                            ) {
                                Text(stringResource(R.string.custom_column_type))
                            }
                            if (canRestoreOriginalHeader) {
                                TextButton(
                                    onClick = {
                                        excelViewModel.restoreOriginalHeader(columnIndex)
                                        headerDialogIndex = null
                                    }
                                ) {
                                    Text(stringResource(R.string.pre_generate_restore_original_header))
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { headerDialogIndex = null }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreGenerateTopBar(
    onBack: () -> Unit,
    onAppend: () -> Unit,
    onReload: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background
    ) {
        CenterAlignedTopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            ),
            navigationIcon = {
                PreGenerateTopBarCircleButton(
                    onClick = onBack,
                    contentDescription = stringResource(R.string.back)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.preview_file_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            actions = {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onAppend) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.append_file)
                            )
                        }
                        IconButton(onClick = onReload) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.reload_file)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun PreGenerateTopBarCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .semantics { this.contentDescription = contentDescription }
        ) {
            Box(contentAlignment = Alignment.Center, content = content)
        }
    }
}

@Composable
private fun PreGeneratePreviewSection(
    previewData: List<List<String>>,
    visibleRows: Int,
    totalRows: Int
) {
    PreGenerateSectionCard(
        title = stringResource(R.string.pre_generate_preview_section_title),
        supportingText = stringResource(R.string.pre_generate_preview_section_supporting)
    ) {
        if (previewData.isNotEmpty()) {
            PreGeneratePreviewTable(data = previewData)
        } else {
            Text(
                text = stringResource(R.string.pre_generate_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        Text(
            text = stringResource(
                R.string.pre_generate_preview_rows_count,
                visibleRows,
                totalRows
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(R.string.pre_generate_preview_uses_all_rows),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreGenerateColumnsSection(
    selectedCount: Int,
    totalCount: Int,
    identifiedCount: Int,
    needsReviewCount: Int,
    columns: List<PreGenerateColumnUiModel>,
    canSelectAll: Boolean,
    canKeepOnlyRequired: Boolean,
    onSelectAll: () -> Unit,
    onKeepOnlyRequired: () -> Unit,
    onToggleSelection: (Int) -> Unit,
    onEditType: (Int) -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    var activeFilter by remember { mutableStateOf(PreGenerateColumnFilter.ALL) }
    LaunchedEffect(needsReviewCount, identifiedCount) {
        if (activeFilter == PreGenerateColumnFilter.NEEDS_REVIEW && needsReviewCount == 0) {
            activeFilter = PreGenerateColumnFilter.ALL
        }
        if (activeFilter == PreGenerateColumnFilter.IDENTIFIED && identifiedCount == 0) {
            activeFilter = PreGenerateColumnFilter.ALL
        }
    }
    val visibleColumns = when (activeFilter) {
        PreGenerateColumnFilter.ALL -> columns
        PreGenerateColumnFilter.NEEDS_REVIEW ->
            columns.filter { it.status == PreGenerateColumnStatus.UNIDENTIFIED }
        PreGenerateColumnFilter.IDENTIFIED ->
            columns.filter { it.status != PreGenerateColumnStatus.UNIDENTIFIED }
    }

    PreGenerateSectionCard(
        title = stringResource(R.string.pre_generate_columns_section_title),
        supportingText = stringResource(R.string.pre_generate_columns_section_supporting)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            PreGeneratePill(
                text = stringResource(
                    R.string.pre_generate_columns_selected_count,
                    selectedCount,
                    totalCount
                )
            )
            PreGeneratePill(
                text = stringResource(
                    R.string.pre_generate_columns_identified_count,
                    identifiedCount
                ),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (needsReviewCount > 0) {
                PreGeneratePill(
                    text = stringResource(
                        R.string.pre_generate_columns_needs_review_count,
                        needsReviewCount
                    ),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                TextButton(onClick = onSelectAll, enabled = canSelectAll) {
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.select_all))
                }
                TextButton(onClick = onKeepOnlyRequired, enabled = canKeepOnlyRequired) {
                    Text(stringResource(R.string.pre_generate_keep_required_only))
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            PreGenerateFilterChip(
                text = stringResource(R.string.pre_generate_filter_all_columns),
                selected = activeFilter == PreGenerateColumnFilter.ALL,
                onClick = { activeFilter = PreGenerateColumnFilter.ALL }
            )
            if (needsReviewCount > 0) {
                PreGenerateFilterChip(
                    text = stringResource(R.string.pre_generate_filter_needs_review),
                    selected = activeFilter == PreGenerateColumnFilter.NEEDS_REVIEW,
                    onClick = { activeFilter = PreGenerateColumnFilter.NEEDS_REVIEW }
                )
            }
            if (identifiedCount > 0) {
                PreGenerateFilterChip(
                    text = stringResource(R.string.pre_generate_filter_identified),
                    selected = activeFilter == PreGenerateColumnFilter.IDENTIFIED,
                    onClick = { activeFilter = PreGenerateColumnFilter.IDENTIFIED }
                )
            }
        }

        if (needsReviewCount > 0) {
            Text(
                text = stringResource(R.string.pre_generate_columns_review_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (visibleColumns.isEmpty()) {
                Text(
                    text = stringResource(R.string.pre_generate_columns_filter_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                visibleColumns.forEachIndexed { index, column ->
                    PreGenerateColumnRow(
                        column = column,
                        onToggleSelection = { onToggleSelection(column.index) },
                        onEditType = { onEditType(column.index) }
                    )

                    if (index != visibleColumns.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.pre_generate_required_columns_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PreGenerateFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun PreGenerateColumnRow(
    column: PreGenerateColumnUiModel,
    onToggleSelection: () -> Unit,
    onEditType: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val successColor = MaterialTheme.appColors.success
    val isUnidentified = column.status == PreGenerateColumnStatus.UNIDENTIFIED
    val examples = if (column.examples.isNotEmpty()) {
        stringResource(
            R.string.pre_generate_column_examples,
            column.examples.joinToString(", ")
        )
    } else {
        stringResource(R.string.pre_generate_column_examples_empty)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = if (isUnidentified) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            },
            contentColor = if (isUnidentified) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.primary
            }
        ) {
            Icon(
                imageVector = if (isUnidentified) {
                    Icons.Default.Warning
                } else {
                    Icons.AutoMirrored.Filled.ArrowForward
                },
                contentDescription = null,
                modifier = Modifier.padding(10.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = column.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (column.isEssential) {
                    PreGeneratePill(
                        text = stringResource(R.string.pre_generate_required_badge),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                PreGeneratePill(
                    text = columnStatusLabel(column.status),
                    containerColor = when (column.status) {
                        PreGenerateColumnStatus.IDENTIFIED ->
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        PreGenerateColumnStatus.MANUAL ->
                            MaterialTheme.colorScheme.primaryContainer
                        PreGenerateColumnStatus.GENERATED ->
                            MaterialTheme.colorScheme.secondaryContainer
                        PreGenerateColumnStatus.UNIDENTIFIED ->
                            MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = when (column.status) {
                        PreGenerateColumnStatus.IDENTIFIED ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        PreGenerateColumnStatus.MANUAL ->
                            MaterialTheme.colorScheme.onPrimaryContainer
                        PreGenerateColumnStatus.GENERATED ->
                            MaterialTheme.colorScheme.onSecondaryContainer
                        PreGenerateColumnStatus.UNIDENTIFIED ->
                            MaterialTheme.colorScheme.onErrorContainer
                    }
                )

                IconButton(
                    onClick = onEditType,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.edit_column_type),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = stringResource(R.string.pre_generate_file_column_label, column.fileHeader),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.pre_generate_column_position,
                    column.index + 1
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = examples,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = column.isSelected,
            onCheckedChange = { onToggleSelection() },
            enabled = !column.isEssential,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = successColor,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                disabledCheckedThumbColor = MaterialTheme.colorScheme.surface,
                disabledCheckedTrackColor = successColor.copy(alpha = 0.45f)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PreGenerateEntitySection(
    title: String,
    supportingText: String,
    placeholder: String,
    inputText: String,
    expanded: Boolean,
    suggestions: List<T>,
    selectedName: String?,
    isSelectionValid: Boolean,
    pendingSelectionMessage: String,
    createPrompt: String?,
    onExpandedChange: (Boolean) -> Unit,
    onInputChange: (String) -> Unit,
    onSuggestionSelected: (T) -> Unit,
    onCreateRequested: suspend () -> T?,
    itemLabel: (T) -> String
) {
    val scope = rememberCoroutineScope()

    PreGenerateSectionCard(
        title = title,
        supportingText = supportingText
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { onExpandedChange(!expanded) }
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    onInputChange(it)
                    onExpandedChange(true)
                },
                placeholder = { Text(placeholder) },
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                        enabled = true
                    )
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(suggestion)) },
                        onClick = {
                            onSuggestionSelected(suggestion)
                            onExpandedChange(false)
                        }
                    )
                }

                createPrompt?.let { prompt ->
                    DropdownMenuItem(
                        text = { Text(prompt) },
                        onClick = {
                            scope.launch {
                                onCreateRequested()?.let { created ->
                                    onSuggestionSelected(created)
                                    onExpandedChange(false)
                                }
                            }
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        TextButton(
            onClick = {
                onInputChange("")
                onExpandedChange(true)
            },
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.pre_generate_show_all_options))
        }

        Text(
            text = if (isSelectionValid && !selectedName.isNullOrBlank()) {
                stringResource(R.string.pre_generate_selection_ready, selectedName)
            } else {
                pendingSelectionMessage
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelectionValid) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun PreGenerateGenerateSection(
    selectedSupplierName: String?,
    selectedCategoryName: String?,
    selectedColumnCount: Int,
    totalColumnCount: Int,
    totalRows: Int,
    isGenerateEnabled: Boolean,
    generateDisabledReason: String?,
    onGenerate: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        PreGenerateSectionLabel(stringResource(R.string.pre_generate_generate_section_title))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xl, vertical = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                Text(
                    text = if (isGenerateEnabled) {
                        stringResource(
                            R.string.pre_generate_generate_ready,
                            totalRows
                        )
                    } else {
                        stringResource(R.string.pre_generate_generate_section_supporting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    PreGenerateSummaryRow(
                        label = stringResource(R.string.supplier_label),
                        value = selectedSupplierName ?: stringResource(R.string.pre_generate_not_selected)
                    )
                    PreGenerateSummaryRow(
                        label = stringResource(R.string.category_label),
                        value = selectedCategoryName ?: stringResource(R.string.pre_generate_not_selected)
                    )
                    PreGenerateSummaryRow(
                        label = stringResource(R.string.pre_generate_summary_columns_label),
                        value = stringResource(
                            R.string.pre_generate_columns_selected_count,
                            selectedColumnCount,
                            totalColumnCount
                        )
                    )
                    PreGenerateSummaryRow(
                        label = stringResource(R.string.pre_generate_preview_section_title),
                        value = stringResource(
                            R.string.pre_generate_preview_rows_count,
                            totalRows.coerceAtMost(PRE_GENERATE_PREVIEW_ROW_LIMIT),
                            totalRows
                        )
                    )
                }

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.pre_generate_generate_history_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onGenerate,
                    enabled = isGenerateEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.lg)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(stringResource(R.string.pre_generate_generate_button))
                }

                if (!isGenerateEnabled && generateDisabledReason != null) {
                    Text(
                        text = generateDisabledReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PreGenerateSummaryRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PreGenerateSectionCard(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = MaterialTheme.appSpacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        PreGenerateSectionLabel(title)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xl, vertical = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                content()
            }
        }
    }
}

@Composable
private fun PreGenerateSectionLabel(
    title: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun columnStatusLabel(status: PreGenerateColumnStatus): String {
    return when (status) {
        PreGenerateColumnStatus.IDENTIFIED ->
            stringResource(R.string.pre_generate_status_identified)
        PreGenerateColumnStatus.MANUAL ->
            stringResource(R.string.pre_generate_status_manual)
        PreGenerateColumnStatus.GENERATED ->
            stringResource(R.string.pre_generate_status_generated)
        PreGenerateColumnStatus.UNIDENTIFIED ->
            stringResource(R.string.pre_generate_status_unidentified)
    }
}

private fun isPreGenerateKnownHeaderKey(headerKey: String): Boolean {
    val canonicalKey = canonicalExcelHeaderKey(headerKey) ?: headerKey
    return canonicalKey in preGenerateKnownHeaderKeys
}

private fun resolvePreGenerateColumnStatus(
    headerKey: String,
    headerType: String?
): PreGenerateColumnStatus {
    return when {
        headerType == "generated" -> PreGenerateColumnStatus.GENERATED
        headerType == "alias" || headerType == "pattern" -> PreGenerateColumnStatus.IDENTIFIED
        headerType.isNullOrBlank() || headerType == "unknown" -> {
            if (isPreGenerateKnownHeaderKey(headerKey)) {
                PreGenerateColumnStatus.IDENTIFIED
            } else {
                PreGenerateColumnStatus.UNIDENTIFIED
            }
        }
        else -> PreGenerateColumnStatus.MANUAL
    }
}

@Composable
private fun PreGenerateNoticeCard(
    title: String,
    messages: List<String>,
    isError: Boolean
) {
    val spacing = MaterialTheme.appSpacing
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Transparent,
                contentColor = contentColor
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(2.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                messages.forEach { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingColor
                    )
                }
            }
        }
    }
}

@Composable
private fun PreGeneratePreviewTable(
    data: List<List<String>>
) {
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    val columnWidths = remember(data) { buildPreviewColumnWidths(data) }
    val tableWidth = remember(columnWidths) {
        columnWidths.fold(0.dp) { total, width -> total + width }
    }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val headerDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, dividerColor),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScrollState)
        ) {
            Column(
                modifier = Modifier
                    .width(tableWidth)
                    .heightIn(max = preGeneratePreviewTableMaxHeight)
                    .verticalScroll(verticalScrollState)
            ) {
                data.forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.background(
                            if (rowIndex == 0) {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        row.forEachIndexed { columnIndex, cell ->
                            PreviewCell(
                                text = cell,
                                width = columnWidths.getOrElse(columnIndex) {
                                    preGeneratePreviewRegularColumnWidth
                                },
                                isHeader = rowIndex == 0,
                                dividerColor = dividerColor,
                                showTrailingDivider = columnIndex != row.lastIndex
                            )
                        }
                    }

                    if (rowIndex != data.lastIndex) {
                        HorizontalDivider(
                            color = if (rowIndex == 0) headerDividerColor else dividerColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCell(
    text: String,
    width: Dp,
    isHeader: Boolean,
    dividerColor: Color,
    showTrailingDivider: Boolean
) {
    Box(
        modifier = Modifier
            .width(width)
            .defaultMinSize(minHeight = if (isHeader) 42.dp else 38.dp)
            .drawBehind {
                if (showTrailingDivider) {
                    val strokeWidth = 1.dp.toPx()
                    val x = size.width - strokeWidth / 2f
                    drawLine(
                        color = dividerColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokeWidth
                    )
                }
            }
            .padding(horizontal = 12.dp, vertical = if (isHeader) 10.dp else 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text.ifBlank { "—" },
            style = if (isHeader) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = if (isHeader) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = if (isHeader) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildPreviewColumnWidths(data: List<List<String>>): List<Dp> {
    val columnCount = data.maxOfOrNull { it.size } ?: return emptyList()
    return List(columnCount) { columnIndex ->
        val samples = data.asSequence()
            .mapNotNull { row ->
                row.getOrNull(columnIndex)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.substringBefore('\n')
            }
            .take(PRE_GENERATE_PREVIEW_ROW_LIMIT + 1)
            .toList()
        resolvePreviewColumnWidth(samples)
    }
}

private fun resolvePreviewColumnWidth(samples: List<String>): Dp {
    val maxLength = samples.maxOfOrNull { sample ->
        sample.length.coerceAtMost(28)
    } ?: 0
    return when {
        maxLength <= 4 -> preGeneratePreviewCompactColumnWidth
        maxLength <= 8 -> preGeneratePreviewRegularColumnWidth
        maxLength <= 16 -> preGeneratePreviewWideColumnWidth
        else -> preGeneratePreviewExtraWideColumnWidth
    }
}

@Composable
private fun PreGeneratePill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PreGenerateEmptyState(
    onChooseFile: () -> Unit,
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
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.preview_file_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.pre_generate_empty_state_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onChooseFile) {
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

private fun buildColumnExamples(
    rows: List<List<String>>,
    columnIndex: Int
): List<String> = rows.asSequence()
    .mapNotNull { row ->
        row.getOrNull(columnIndex)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    .distinct()
    .take(PRE_GENERATE_COLUMN_EXAMPLE_LIMIT)
    .toList()

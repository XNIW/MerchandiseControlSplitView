package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.CatalogDeleteStrategy
import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.CatalogListItem
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import com.example.merchandisecontrolsplitview.util.buildDatabaseExportDisplayName
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseHubTab
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ScannedBarcodeLookupResult
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import com.example.merchandisecontrolsplitview.viewmodel.StorefrontListFilter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES
import kotlinx.coroutines.launch

private val RootBottomClearance = 104.dp

private data class CatalogDialogTarget(
    val kind: CatalogEntityKind,
    val item: CatalogListItem
)

internal sealed interface ScannedBarcodeDestination {
    data class ExistingProduct(val product: Product) : ScannedBarcodeDestination
    data class NewProduct(val barcode: String) : ScannedBarcodeDestination
}

internal fun scannedBarcodeDestination(
    barcode: String,
    existingProduct: Product?
): ScannedBarcodeDestination = existingProduct
    ?.let { ScannedBarcodeDestination.ExistingProduct(it) }
    ?: ScannedBarcodeDestination.NewProduct(barcode)

private sealed interface CatalogNameRequest {
    val kind: CatalogEntityKind
    val initialName: String

    data class Create(
        override val kind: CatalogEntityKind,
        override val initialName: String = ""
    ) : CatalogNameRequest

    data class Rename(
        val target: CatalogDialogTarget
    ) : CatalogNameRequest {
        override val kind: CatalogEntityKind = target.kind
        override val initialName: String = target.item.name
    }

    data class CreateReplacement(
        val target: CatalogDialogTarget,
        override val initialName: String = ""
    ) : CatalogNameRequest {
        override val kind: CatalogEntityKind = target.kind
    }
}

@Composable
fun DatabaseScreen(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: DatabaseViewModel
) {
    val spacing = androidx.compose.material3.MaterialTheme.appSpacing
    val uiState by viewModel.uiState.collectAsState()
    val exportUiState by viewModel.exportUiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val appliedProductFilter by viewModel.appliedProductFilter.collectAsState()
    val selectedHubTab by viewModel.selectedHubTab.collectAsState()
    val supplierCatalogSection by viewModel.supplierCatalogSection.collectAsState()
    val categoryCatalogSection by viewModel.categoryCatalogSection.collectAsState()
    val supplierCatalogQuery by viewModel.supplierCatalogQuery.collectAsState()
    val categoryCatalogQuery by viewModel.categoryCatalogQuery.collectAsState()
    val supplierOptions by viewModel.suppliers.collectAsState()
    val categoryOptions by viewModel.categories.collectAsState()
    val productDetailsOverrides by viewModel.productDetailsOverrides.collectAsState()
    val productImageStates by viewModel.productImageStates.collectAsState()
    val storefrontSummaries by viewModel.storefrontSummaries.collectAsState()
    val storefrontFilter by viewModel.storefrontListFilter.collectAsState()
    val storefrontFilterLoading by viewModel.storefrontFilterLoading.collectAsState()
    val storefrontImportSummary by viewModel.storefrontImportSummary.collectAsState()
    val productEditorTarget by viewModel.productEditorTarget.collectAsState()
    val productEditorSessionId by viewModel.productEditorSessionId.collectAsState()
    val products = viewModel.pager.collectAsLazyPagingItems()
    val visibleStorefrontProducts = products.itemSnapshotList.items.map { details ->
        (productDetailsOverrides[details.product.id] ?: details).productWithCurrentPrices()
    }
    LaunchedEffect(
        visibleStorefrontProducts.map { Triple(it.id, it.productName, it.retailPrice) }
    ) {
        viewModel.loadStorefrontSummaries(visibleStorefrontProducts)
    }
    val productListState = key(appliedProductFilter.orEmpty()) { rememberLazyListState() }
    val supplierListState = key(supplierCatalogQuery) { rememberLazyListState() }
    val categoryListState = key(categoryCatalogQuery) { rememberLazyListState() }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scanPromptText = stringResource(R.string.scan_prompt)

    var itemToDelete by remember { mutableStateOf<Product?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var requestedExportSelection by remember { mutableStateOf(ExportSheetSelection.full()) }
    var exportLoadingTimedOut by remember { mutableStateOf(false) }
    var uiLoadingTimedOut by remember { mutableStateOf(false) }
    var showHistoryFor by remember { mutableStateOf<Product?>(null) }
    var imagePreviewProduct by remember { mutableStateOf<Product?>(null) }

    var catalogActionTarget by remember { mutableStateOf<CatalogDialogTarget?>(null) }
    var catalogNameRequest by remember { mutableStateOf<CatalogNameRequest?>(null) }
    var catalogSimpleDeleteTarget by remember { mutableStateOf<CatalogDialogTarget?>(null) }
    var catalogGuidedDeleteTarget by remember { mutableStateOf<CatalogDialogTarget?>(null) }
    var catalogReplacementTarget by remember { mutableStateOf<CatalogDialogTarget?>(null) }
    var catalogClearAssignmentsTarget by remember { mutableStateOf<CatalogDialogTarget?>(null) }

    fun openNewProductEditor(barcode: String = "") {
        viewModel.openProductEditor(Product(
            id = 0L,
            barcode = barcode,
            productName = ""
        ))
    }

    fun openCatalogCreate(kind: CatalogEntityKind, initialName: String = "") {
        catalogNameRequest = CatalogNameRequest.Create(kind = kind, initialName = initialName)
    }

    fun openCatalogDelete(target: CatalogDialogTarget) {
        if (target.item.productCount == 0) {
            catalogSimpleDeleteTarget = target
        } else {
            catalogGuidedDeleteTarget = target
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, flags)
            } catch (_: SecurityException) {
            }
            viewModel.startSmartImport(context, it)
        }
    }

    val isUiLoading = uiState is UiState.Loading
    val showExportLoadingDialog = exportUiState.inProgress && !exportLoadingTimedOut
    val showUiLoadingDialog = isUiLoading && !uiLoadingTimedOut
    val isLoading = showExportLoadingDialog || showUiLoadingDialog

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        onResult = { uri: Uri? ->
            uri?.let { viewModel.exportDatabase(context, it, requestedExportSelection) }
        }
    )

    val scannerLookupFailedMessage = stringResource(R.string.scanner_product_lookup_failed)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            val expectedScopeEpoch = viewModel.currentProductImageScopeEpoch()
            scope.launch {
                when (val lookup = viewModel.lookupScannedBarcode(code, expectedScopeEpoch)) {
                    is ScannedBarcodeLookupResult.Resolved -> {
                        when (val destination = scannedBarcodeDestination(code, lookup.destination)) {
                            is ScannedBarcodeDestination.ExistingProduct -> {
                                viewModel.openProductEditor(destination.product)
                            }
                            is ScannedBarcodeDestination.NewProduct -> {
                                openNewProductEditor(destination.barcode)
                            }
                        }
                    }
                    ScannedBarcodeLookupResult.Failed -> {
                        snackbarHostState.showSnackbar(
                            message = scannerLookupFailedMessage,
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                    }
                    ScannedBarcodeLookupResult.StaleScope -> Unit
                }
            }
        }
    }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is UiState.Success -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                viewModel.consumeUiState()
            }

            is UiState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                viewModel.consumeUiState()
            }

            is UiState.Idle, is UiState.Loading -> Unit
        }
    }

    val storefrontImportVerifyLabel = stringResource(R.string.storefront_import_verify)
    val storefrontImportMessage = storefrontImportSummary?.let { summary ->
        stringResource(
            R.string.storefront_import_summary,
            summary.internalProductsUpdated,
            summary.publicProductsNowDifferent
        )
    }
    LaunchedEffect(storefrontImportSummary, storefrontImportMessage) {
        if (storefrontImportSummary == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = storefrontImportMessage ?: return@LaunchedEffect,
            actionLabel = storefrontImportVerifyLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.setStorefrontListFilter(StorefrontListFilter.NEEDS_UPDATE)
        }
        viewModel.consumeStorefrontImportSummary()
    }

    LaunchedEffect(exportUiState.inProgress) {
        if (!exportUiState.inProgress) {
            exportLoadingTimedOut = false
        }
    }

    LaunchedEffect(isUiLoading) {
        if (!isUiLoading) {
            uiLoadingTimedOut = false
        }
    }

    LaunchedEffect(catalogReplacementTarget?.kind) {
        when (catalogReplacementTarget?.kind) {
            CatalogEntityKind.SUPPLIER -> viewModel.onSupplierSearchQueryChanged("")
            CatalogEntityKind.CATEGORY -> viewModel.onCategorySearchQueryChanged("")
            null -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .statusBarsPadding()
                .blur(if (isLoading) 10.dp else 0.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DatabaseRootHeader(
                    selectedTab = selectedHubTab,
                    onTabSelected = viewModel::selectHubTab,
                    onImportClick = {
                        uploadLauncher.launch(
                            arrayOf(
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            )
                        )
                    },
                    onExportClick = { showExportDialog = true },
                    exportEnabled = !exportUiState.inProgress,
                    modifier = Modifier.padding(
                        start = spacing.xl,
                        top = spacing.lg,
                        end = spacing.xl,
                        bottom = spacing.xs
                    )
                )

                DatabaseSearchField(
                    value = when (selectedHubTab) {
                        DatabaseHubTab.PRODUCTS -> filter.orEmpty()
                        DatabaseHubTab.SUPPLIERS -> supplierCatalogQuery
                        DatabaseHubTab.CATEGORIES -> categoryCatalogQuery
                    },
                    placeholder = stringResource(
                        when (selectedHubTab) {
                            DatabaseHubTab.PRODUCTS -> R.string.database_products_search_hint
                            DatabaseHubTab.SUPPLIERS -> R.string.database_suppliers_search_hint
                            DatabaseHubTab.CATEGORIES -> R.string.database_categories_search_hint
                        }
                    ),
                    onValueChange = { query ->
                        when (selectedHubTab) {
                            DatabaseHubTab.PRODUCTS -> viewModel.setFilter(query)
                            DatabaseHubTab.SUPPLIERS -> viewModel.onCatalogQueryChanged(
                                CatalogEntityKind.SUPPLIER,
                                query
                            )
                            DatabaseHubTab.CATEGORIES -> viewModel.onCatalogQueryChanged(
                                CatalogEntityKind.CATEGORY,
                                query
                            )
                        }
                    },
                    onClear = {
                        when (selectedHubTab) {
                            DatabaseHubTab.PRODUCTS -> viewModel.setFilter("")
                            DatabaseHubTab.SUPPLIERS -> viewModel.onCatalogQueryChanged(
                                CatalogEntityKind.SUPPLIER,
                                ""
                            )
                            DatabaseHubTab.CATEGORIES -> viewModel.onCatalogQueryChanged(
                                CatalogEntityKind.CATEGORY,
                                ""
                            )
                        }
                    },
                    modifier = Modifier.padding(
                        start = spacing.xl,
                        end = spacing.xl,
                        bottom = spacing.xs
                    )
                )

                when (selectedHubTab) {
                    DatabaseHubTab.PRODUCTS -> {
                        DatabaseProductListSection(
                            filter = filter.orEmpty(),
                            products = products,
                            productDetailsOverrides = productDetailsOverrides,
                            productImageStates = productImageStates,
                            storefrontSummaries = storefrontSummaries,
                            storefrontFilter = storefrontFilter,
                            onStorefrontFilterChange = viewModel::setStorefrontListFilter,
                            storefrontFilterLoading = storefrontFilterLoading,
                            listState = productListState,
                            onProductImageVisibilityChanged = { product, visible ->
                                viewModel.setProductImageVisible(
                                    productId = product.id,
                                    expectedVersionId = product.primaryImageVersionId,
                                    visible = visible
                                )
                            },
                            onProductClick = viewModel::openProductEditor,
                            onProductImageClick = { product ->
                                imagePreviewProduct = product
                                viewModel.loadProductImageProgressively(
                                    productId = product.id,
                                    expectedVersionId = product.primaryImageVersionId
                                )
                            },
                            onRetry = products::retry,
                            onDeleteRequest = {
                                itemToDelete = it
                                showDeleteDialog = true
                            },
                            onShowHistory = { showHistoryFor = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    DatabaseHubTab.SUPPLIERS -> {
                        DatabaseCatalogListSection(
                            kind = CatalogEntityKind.SUPPLIER,
                            sectionState = supplierCatalogSection,
                            listState = supplierListState,
                            onItemClick = {
                                catalogActionTarget = CatalogDialogTarget(
                                    kind = CatalogEntityKind.SUPPLIER,
                                    item = it
                                )
                            },
                            onRetry = { viewModel.retryCatalogSection(CatalogEntityKind.SUPPLIER) },
                            onQuickCreate = { initialName ->
                                openCatalogCreate(CatalogEntityKind.SUPPLIER, initialName)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    DatabaseHubTab.CATEGORIES -> {
                        DatabaseCatalogListSection(
                            kind = CatalogEntityKind.CATEGORY,
                            sectionState = categoryCatalogSection,
                            listState = categoryListState,
                            onItemClick = {
                                catalogActionTarget = CatalogDialogTarget(
                                    kind = CatalogEntityKind.CATEGORY,
                                    item = it
                                )
                            },
                            onRetry = { viewModel.retryCatalogSection(CatalogEntityKind.CATEGORY) },
                            onQuickCreate = { initialName ->
                                openCatalogCreate(CatalogEntityKind.CATEGORY, initialName)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            DatabaseHubFab(
                selectedTab = selectedHubTab,
                onScan = {
                    val options = ScanOptions().apply {
                        setDesiredBarcodeFormats(ALL_CODE_TYPES)
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                        setBeepEnabled(true)
                        setPrompt(scanPromptText)
                    }
                    scanLauncher.launch(options)
                },
                onAddProduct = { openNewProductEditor() },
                onAddCatalog = { kind -> openCatalogCreate(kind) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 24.dp,
                        bottom = 16.dp
                    )
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = RootBottomClearance + 64.dp
                )
        )
    }

    when {
        showExportLoadingDialog -> {
            LoadingDialog(
                message = exportUiState.message,
                progress = exportUiState.progress,
                onSafetyTimeout = { exportLoadingTimedOut = true }
            )
        }

        showUiLoadingDialog -> {
            LoadingDialog(
                loading = uiState as UiState.Loading,
                onSafetyTimeout = {
                    uiLoadingTimedOut = true
                    viewModel.consumeUiState()
                }
            )
        }
    }

    if (showExportDialog) {
        DatabaseExportDialog(
            selection = requestedExportSelection,
            exportInProgress = exportUiState.inProgress,
            onSelectionChange = { requestedExportSelection = it },
            onConfirm = {
                showExportDialog = false
                exportLauncher.launch(buildDatabaseExportDisplayName(requestedExportSelection))
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (productEditorTarget != null) {
        val editorProduct = productEditorTarget!!
        val isNewProduct = editorProduct.id == 0L
        EditProductDialog(
            product = editorProduct,
            sessionId = productEditorSessionId,
            viewModel = viewModel,
            enablePriceHistory = !isNewProduct,
            onDismiss = viewModel::dismissProductEditor,
            onSave = viewModel::saveProductFromEditor
        )
    }

    imagePreviewProduct?.let { previewProduct ->
        val mainState = productImageStates[
            com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiKey(
                previewProduct.id,
                com.example.merchandisecontrolsplitview.productimage.ProductImageVariant.MAIN
            )
        ]
        val thumbState = productImageStates[
            com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiKey(
                previewProduct.id,
                com.example.merchandisecontrolsplitview.productimage.ProductImageVariant.THUMB
            )
        ]
        ProductImageFullscreenDialog(
            state = productImagePreviewState(mainState, thumbState),
            contentDescription = stringResource(R.string.product_image_main),
            onDismiss = { imagePreviewProduct = null },
            onRetry = {
                viewModel.loadProductImageProgressively(
                    productId = previewProduct.id,
                    expectedVersionId = previewProduct.primaryImageVersionId,
                    force = true
                )
            }
        )
    }

    if (showDeleteDialog && itemToDelete != null) {
        val productToDelete = itemToDelete!!
        DeleteProductConfirmationDialog(
            product = productToDelete,
            onConfirm = {
                viewModel.deleteProduct(productToDelete)
                showDeleteDialog = false
                itemToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                itemToDelete = null
            }
        )
    }

    catalogActionTarget?.let { target ->
        CatalogActionBottomSheet(
            kind = target.kind,
            item = target.item,
            onRename = {
                catalogActionTarget = null
                catalogNameRequest = CatalogNameRequest.Rename(target)
            },
            onDelete = {
                catalogActionTarget = null
                openCatalogDelete(target)
            },
            onDismiss = { catalogActionTarget = null }
        )
    }

    catalogNameRequest?.let { request ->
        CatalogNameDialog(
            title = when (request) {
                is CatalogNameRequest.Create -> stringResource(
                    R.string.database_catalog_create_title,
                    catalogEntityLabel(request.kind)
                )

                is CatalogNameRequest.Rename -> stringResource(
                    R.string.database_catalog_rename_title,
                    catalogEntityLabel(request.kind)
                )

                is CatalogNameRequest.CreateReplacement -> stringResource(
                    R.string.database_catalog_create_replacement_title,
                    catalogEntityLabel(request.kind)
                )
            },
            fieldLabel = stringResource(
                R.string.database_catalog_name_field_label,
                catalogEntityLabel(request.kind)
            ),
            confirmLabel = stringResource(
                when (request) {
                    is CatalogNameRequest.Rename -> R.string.save
                    is CatalogNameRequest.Create,
                    is CatalogNameRequest.CreateReplacement -> R.string.add
                }
            ),
            initialValue = request.initialName,
            supportingText = (request as? CatalogNameRequest.CreateReplacement)?.let {
                stringResource(
                    R.string.database_catalog_create_replacement_body,
                    catalogEntityLabel(it.kind),
                    it.target.item.name
                )
            },
            onConfirm = { enteredName ->
                scope.launch {
                    val success = when (request) {
                        is CatalogNameRequest.Create -> {
                            viewModel.createCatalogEntry(request.kind, enteredName) != null
                        }

                        is CatalogNameRequest.Rename -> {
                            viewModel.renameCatalogEntry(
                                kind = request.kind,
                                id = request.target.item.id,
                                newName = enteredName
                            ) != null
                        }

                        is CatalogNameRequest.CreateReplacement -> {
                            viewModel.deleteCatalogEntry(
                                kind = request.kind,
                                id = request.target.item.id,
                                strategy = CatalogDeleteStrategy.CreateNewAndReplace(enteredName)
                            ) != null
                        }
                    }

                    if (success) {
                        catalogNameRequest = null
                        catalogGuidedDeleteTarget = null
                        catalogReplacementTarget = null
                        catalogClearAssignmentsTarget = null
                    }
                }
            },
            onDismiss = { catalogNameRequest = null }
        )
    }

    catalogSimpleDeleteTarget?.let { target ->
        CatalogDeleteConfirmationDialog(
            kind = target.kind,
            item = target.item,
            onConfirm = {
                scope.launch {
                    val success = viewModel.deleteCatalogEntry(
                        kind = target.kind,
                        id = target.item.id,
                        strategy = CatalogDeleteStrategy.DeleteIfUnused
                    ) != null
                    if (success) {
                        catalogSimpleDeleteTarget = null
                    }
                }
            },
            onDismiss = { catalogSimpleDeleteTarget = null }
        )
    }

    catalogGuidedDeleteTarget?.let { target ->
        CatalogDeleteStrategyDialog(
            kind = target.kind,
            item = target.item,
            onReplaceWithExisting = {
                catalogGuidedDeleteTarget = null
                catalogReplacementTarget = target
            },
            onCreateReplacement = {
                catalogGuidedDeleteTarget = null
                catalogNameRequest = CatalogNameRequest.CreateReplacement(target)
            },
            onClearAssignments = {
                catalogGuidedDeleteTarget = null
                catalogClearAssignmentsTarget = target
            },
            onDismiss = { catalogGuidedDeleteTarget = null }
        )
    }

    catalogClearAssignmentsTarget?.let { target ->
        CatalogClearAssignmentsConfirmationDialog(
            kind = target.kind,
            item = target.item,
            onConfirm = {
                scope.launch {
                    val success = viewModel.deleteCatalogEntry(
                        kind = target.kind,
                        id = target.item.id,
                        strategy = CatalogDeleteStrategy.ClearAssignments
                    ) != null
                    if (success) {
                        catalogClearAssignmentsTarget = null
                    }
                }
            },
            onDismiss = { catalogClearAssignmentsTarget = null }
        )
    }

    catalogReplacementTarget?.let { target ->
        val options = when (target.kind) {
            CatalogEntityKind.SUPPLIER -> supplierOptions.map {
                CatalogPickerOption(id = it.id, name = it.name)
            }

            CatalogEntityKind.CATEGORY -> categoryOptions.map {
                CatalogPickerOption(id = it.id, name = it.name)
            }
        }.filter { it.id != target.item.id }

        CatalogReplacementPickerDialog(
            kind = target.kind,
            options = options,
            onSelect = { option ->
                scope.launch {
                    val success = viewModel.deleteCatalogEntry(
                        kind = target.kind,
                        id = target.item.id,
                        strategy = CatalogDeleteStrategy.ReplaceWithExisting(option.id)
                    ) != null
                    if (success) {
                        catalogReplacementTarget = null
                    }
                }
            },
            onDismiss = { catalogReplacementTarget = null },
            onCreateNew = { initialName ->
                catalogReplacementTarget = null
                catalogNameRequest = CatalogNameRequest.CreateReplacement(
                    target = target,
                    initialName = initialName
                )
            }
        )
    }

    showHistoryFor?.let { product ->
        PriceHistoryBottomSheetHost(
            product = product,
            viewModel = viewModel,
            onDismiss = { showHistoryFor = null }
        )
    }
}

@Composable
private fun catalogEntityLabel(kind: CatalogEntityKind): String = stringResource(
    when (kind) {
        CatalogEntityKind.SUPPLIER -> R.string.database_catalog_entity_supplier
        CatalogEntityKind.CATEGORY -> R.string.database_catalog_entity_category
    }
)

@Composable
private fun PriceHistoryBottomSheetHost(
    product: Product,
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sheetProduct by remember(product.id) { mutableStateOf(product) }
    val purchase by viewModel.getPriceSeries(product.id, "PURCHASE").collectAsState(emptyList())
    val retail by viewModel.getPriceSeries(product.id, "RETAIL").collectAsState(emptyList())

    key(product.id) {
        PriceHistoryBottomSheet(
            product = sheetProduct,
            purchase = purchase,
            retail = retail,
            onDismiss = onDismiss,
            onUpdateCurrentPrice = { type, price ->
                scope.launch {
                    viewModel.updateCurrentPriceFromHistory(
                        productId = sheetProduct.id,
                        type = type,
                        price = price
                    )?.let { updated ->
                        sheetProduct = updated
                    }
                }
            }
        )
    }
}

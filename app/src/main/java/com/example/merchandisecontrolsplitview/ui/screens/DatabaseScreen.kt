package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.navigation.NavHostController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import com.example.merchandisecontrolsplitview.util.buildDatabaseExportDisplayName
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES
import kotlinx.coroutines.launch

@Composable
fun DatabaseScreen(
    navController: NavHostController,
    viewModel: DatabaseViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val exportUiState by viewModel.exportUiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val products = viewModel.pager.collectAsLazyPagingItems()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scanPromptText = stringResource(R.string.scan_prompt)

    var itemToEdit by remember { mutableStateOf<Product?>(null) }
    var itemToDelete by remember { mutableStateOf<Product?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var requestedExportSelection by remember { mutableStateOf(ExportSheetSelection.full()) }
    var exportLoadingTimedOut by remember { mutableStateOf(false) }
    var uiLoadingTimedOut by remember { mutableStateOf(false) }

    var showHistoryFor by remember { mutableStateOf<Product?>(null) }

    fun openNewProductEditor(barcode: String = "") {
        itemToEdit = Product(
            id = 0L,
            barcode = barcode,
            productName = ""
        )
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // ✅ SOLO READ (o READ|WRITE se ti serve scrivere)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(it, flags) } catch (_: SecurityException) {}
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

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            scope.launch {
                val existing = viewModel.findProductByBarcode(code)
                if (existing != null) {
                    // Prodotto trovato: applico il filtro per mostrarlo in lista
                    viewModel.setFilter(code)
                } else {
                    // Non trovato: apro dialog Nuovo Prodotto con barcode già compilato
                    openNewProductEditor(code)
                }
            }
        }
    }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is UiState.Success -> {
                val msg = s.message
                // 1. MOSTRA la snackbar (operazione sospensiva)
                snackbarHostState.showSnackbar(
                    message = msg,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                // 2. DOPO che è apparsa, consuma lo stato
                viewModel.consumeUiState()
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
                viewModel.consumeUiState()
            }
            is UiState.Idle, is UiState.Loading -> Unit
        }
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

    Scaffold(
        topBar = {
            DatabaseScreenTopBar(
                onNavigateBack = { navController.popBackStack() },
                onImportClick = {
                    uploadLauncher.launch(
                        arrayOf(
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    )
                },
                onExportClick = {
                    showExportDialog = true
                },
                exportEnabled = !exportUiState.inProgress
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .blur(if (isLoading) 10.dp else 0.dp)
        ) {
            DatabaseProductListSection(
                filter = filter,
                products = products,
                onFilterChange = { viewModel.setFilter(it) },
                onClearFilter = { viewModel.setFilter("") },
                onProductClick = { itemToEdit = it },
                onDeleteRequest = {
                    itemToDelete = it
                    showDeleteDialog = true
                },
                onShowHistory = { showHistoryFor = it },
                modifier = Modifier.fillMaxSize()
            )

            DatabaseScreenFabColumn(
                onScan = {
                    val opts = ScanOptions().apply {
                        setDesiredBarcodeFormats(ALL_CODE_TYPES)
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                        setBeepEnabled(true)
                        setPrompt(scanPromptText)
                    }
                    scanLauncher.launch(opts)
                },
                onAdd = { openNewProductEditor() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
            )
        }
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

    if (itemToEdit != null) {
        val isNewProduct = itemToEdit!!.id == 0L
        EditProductDialog(
            product = itemToEdit!!,
            viewModel = viewModel,
            onDismiss = { itemToEdit = null },
            onSave = { productToSave ->
                if (isNewProduct) viewModel.addProduct(productToSave) else viewModel.updateProduct(productToSave)
                itemToEdit = null
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

    showHistoryFor?.let { product ->
        PriceHistoryBottomSheetHost(
            product = product,
            viewModel = viewModel,
            onDismiss = { showHistoryFor = null }
        )
    }
}

@Composable
private fun PriceHistoryBottomSheetHost(
    product: Product,
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit
) {
    val purchase by viewModel.getPriceSeries(product.id, "PURCHASE").collectAsState(emptyList())
    val retail by viewModel.getPriceSeries(product.id, "RETAIL").collectAsState(emptyList())

    key(product.id) {
        PriceHistoryBottomSheet(
            product = product,
            purchase = purchase,
            retail = retail,
            onDismiss = onDismiss
        )
    }
}

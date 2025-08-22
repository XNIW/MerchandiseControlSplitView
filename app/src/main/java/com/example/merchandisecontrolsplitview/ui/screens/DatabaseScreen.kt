package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.data.Product
import androidx.navigation.NavHostController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import androidx.paging.LoadState
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedString
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedStringForInput
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.blur
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseScreen(
    navController: NavHostController,
    viewModel: DatabaseViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val products = viewModel.pager.collectAsLazyPagingItems()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var itemToEdit by remember { mutableStateOf<Product?>(null) }
    var itemToDelete by remember { mutableStateOf<Product?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var showHistoryFor by remember { mutableStateOf<Product?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.startImportAnalysis(context, it) }
    }
    val isLoading = uiState is UiState.Loading

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        onResult = { uri: Uri? -> uri?.let { viewModel.exportToExcel(context, it) } }
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
                    itemToEdit = Product(
                        id = 0L,
                        barcode = code,
                        productName = "" // resta il comportamento attuale del + (campo editabile)
                    )
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.database_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        uploadLauncher.launch(arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    }) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.import_file))
                    }
                    IconButton(onClick = {
                        val timestamp = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH-mm-ss")
                            .format(LocalDateTime.now())
                        val base = context.getString(R.string.sheet_name_products) // es. "Prodotti/Products/Productos/产品"
                        val safeBase = base.replace(Regex("""[\\/:*?"<>|]"""), "_")
                        val fileName = "${safeBase}_${timestamp}.xlsx"
                        downloadLauncher.launch(fileName)
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.export_file))
                    }
                }
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
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = filter ?: "",
                    onValueChange = { viewModel.setFilter(it) },
                    label = { Text(stringResource(R.string.barcode_filter_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (filter?.isNotEmpty() == true) {
                            IconButton(onClick = { viewModel.setFilter("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.clear_text))
                            }
                        }
                    }
                )

                val loadState = products.loadState
                Box(modifier = Modifier.fillMaxSize()) {
                    if (loadState.refresh is LoadState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (products.itemCount == 0) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = stringResource(R.string.no_products_found),
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = if (filter.isNullOrEmpty()) stringResource(R.string.no_products_in_db) else stringResource(R.string.no_results_for, filter ?: ""),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                if (filter.isNullOrEmpty()) {
                                    Text(
                                        text = stringResource(R.string.add_first_product_prompt),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(products.itemCount, key = { products[it]?.product?.id ?: -1 }) { idx ->
                                products[idx]?.let { details -> // 'details' è il nostro oggetto ProductWithDetails
                                    // SOLUZIONE: Nessuna LaunchedEffect. I nomi sono già pronti.

                                    // Estraiamo l'oggetto 'Product' originale per il dialog
                                    val product = details.product

                                    val dismissState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = {
                                            if (it == SwipeToDismissBoxValue.EndToStart) {
                                                itemToDelete = product // Usiamo 'product' come prima
                                                showDeleteDialog = true
                                            }
                                            false
                                        }
                                    )
                                    SwipeToDismissBox(state = dismissState, backgroundContent = { /* ... */ }
                                    ) {
                                        ProductRow(
                                            // Passiamo l'intero oggetto 'details' per la visualizzazione
                                            productDetails = details,
                                            // Al click, salviamo l'oggetto 'product' estratto.
                                            // IL DIALOG RICEVE ESATTAMENTE GLI STESSI DATI DI PRIMA.
                                            onClick = { itemToEdit = product },
                                            onShowHistory = { showHistoryFor = product }
                                        )
                                    }
                                }
                            }

                            if (loadState.append is LoadState.Loading) {
                                item {
                                    Box(modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.End
            ) {
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
                    Icon(Icons.Filled.CameraAlt, contentDescription = stringResource(R.string.scan_barcode))
                }
                FloatingActionButton(onClick = {
                    itemToEdit = Product(id = 0L, barcode = "", productName = "")
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_product))
                }
            }
        }
    }
    if (uiState is UiState.Loading) {
        LoadingDialog(uiState as UiState.Loading)
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_confirmation_title)) },
            text = { Text(stringResource(R.string.delete_confirmation_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        itemToDelete?.let { viewModel.deleteProduct(it) }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showHistoryFor != null) {
        val product = showHistoryFor!!
        val purchase by viewModel.getPriceSeries(product.id, "PURCHASE").collectAsState(emptyList())
        val retail   by viewModel.getPriceSeries(product.id, "RETAIL").collectAsState(emptyList())
        var tab by remember { mutableIntStateOf(0) }   // evita il warning

        ModalBottomSheet(onDismissRequest = { showHistoryFor = null }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(product.productName ?: stringResource(R.string.unnamed_product), style = MaterialTheme.typography.titleMedium)
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_purchase)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_retail)) })
                }
                val list = if (tab == 0) purchase else retail
                LazyColumn {
                    items(list) { pt ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(pt.effectiveAt)   // "yyyy-MM-dd HH:mm:ss"
                            Text(formatNumberAsRoundedString(pt.price))
                        }
                        HorizontalDivider() // niente deprecazioni
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingDialog(loading: UiState.Loading) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false // consente full-screen
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 280.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Spinner più grande e spesso
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 6.dp
                    )

                    Text(
                        text = loading.message ?: stringResource(R.string.operation_in_progress),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val target = ((loading.progress ?: 0).coerceIn(0, 100)) / 100f
                    val animated by animateFloatAsState(targetValue = target, label = "importProgress")

                    if (loading.progress != null) {
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp), // facoltativo: un po’ più sottile
                            // opzionale: personalizza leggermente il track per più contrasto
                             trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = "${(animated * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceColumn(
    labelNew: String,
    priceNew: String?,
    labelOld: String,
    priceOldValue: Double?,
    horizontalAlignment: Alignment.Horizontal
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(text = labelNew, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = priceNew ?: "-",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (priceOldValue != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = labelOld, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = formatNumberAsRoundedString(priceOldValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough
            )
        }
    }
}

@Composable
fun ProductRow(
    productDetails: ProductWithDetails,
    onClick: () -> Unit,
    onShowHistory: () -> Unit = {} // default → non rompe le call esistenti
) {
    val product = productDetails.product // Estraiamo il prodotto per comodità
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = product.productName ?: stringResource(R.string.unnamed_product),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                product.secondProductName?.takeIf { it.isNotBlank() }?.let { second ->
                    Text(
                        text = second,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "${stringResource(R.string.barcode_prefix)} ${product.barcode}  |  ${stringResource(R.string.item_number_prefix)} ${product.itemNumber ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PriceColumn(
                    labelNew = stringResource(R.string.product_purchase_price_new_short),
                    // se il campo corrente manca, ripiega sul lastPurchase della view
                    priceNew = formatNumberAsRoundedString(product.purchasePrice ?: productDetails.lastPurchase),
                    labelOld = stringResource(R.string.product_purchase_price_old_short),
                    priceOldValue = productDetails.prevPurchase,
                    horizontalAlignment = Alignment.Start
                )
                PriceColumn(
                    labelNew = stringResource(R.string.product_retail_price_new_short),
                    priceNew = formatNumberAsRoundedString(product.retailPrice ?: productDetails.lastRetail),
                    labelOld = stringResource(R.string.product_retail_price_old_short),
                    priceOldValue = productDetails.prevRetail,
                    horizontalAlignment = Alignment.End
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onShowHistory) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.price_history))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.price_history))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(text = "${stringResource(R.string.product_supplier_full)}: ")
                // Usa il nome direttamente da 'productDetails'
                Text(text = productDetails.supplierName ?: "-")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (productDetails.categoryName != null) {
                    Row {
                        Text(text = "${stringResource(R.string.header_category)}: ")
                        // Usa il nome direttamente da 'productDetails'
                        Text(text = productDetails.categoryName)
                    }
                } else {
                    Spacer(modifier = Modifier)
                }

                Row {
                    Text(text = "${stringResource(R.string.header_stock_quantity)}: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = formatNumberAsRoundedString(product.stockQuantity), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun EditProductDialog(
    product: Product,
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit
) {
    var barcode by remember { mutableStateOf(product.barcode) }
    var productName by remember { mutableStateOf(product.productName ?: "") }
    var secondProductName by remember { mutableStateOf(product.secondProductName ?: "") }
    var itemNumber by remember { mutableStateOf(product.itemNumber ?: "") }
    var purchasePrice by remember { mutableStateOf(formatNumberAsRoundedStringForInput(product.purchasePrice)) }
    var retailPrice by remember { mutableStateOf(formatNumberAsRoundedStringForInput(product.retailPrice)) }
    var stockQuantity by remember { mutableStateOf(formatNumberAsRoundedStringForInput(product.stockQuantity)) }

    var barcodeError by remember { mutableStateOf<String?>(null) }
    var productNameError by remember { mutableStateOf<String?>(null) }
    var retailPriceError by remember { mutableStateOf<String?>(null) } // NUOVA RIGA
    val barcodeRequiredErrorText = stringResource(id = R.string.error_barcode_required)
    val productNameRequiredAtLeastOneErrorText = stringResource(id = R.string.error_productname_required_at_least_one)
    // NUOVA RIGA: Assicurati di aggiungere questa stringa in res/values/strings.xml
    val retailPriceErrorText = stringResource(id = R.string.error_invalid_or_missing_retail_price)

    var showSecondNameField by remember(product) { mutableStateOf(!product.secondProductName.isNullOrBlank()) }
    var showItemNumberField by remember(product) { mutableStateOf(!product.itemNumber.isNullOrBlank()) }

    val purchaseSeries by viewModel.getPriceSeries(product.id, "PURCHASE").collectAsState(emptyList())
    val retailSeries   by viewModel.getPriceSeries(product.id, "RETAIL").collectAsState(emptyList())

    val lastPurchase = purchaseSeries.getOrNull(0)?.price
    val prevPurchase = purchaseSeries.getOrNull(1)?.price
    val lastRetail   = retailSeries.getOrNull(0)?.price
    val prevRetail   = retailSeries.getOrNull(1)?.price

    fun validate(): Boolean {
        barcodeError = if (barcode.isBlank()) barcodeRequiredErrorText else null
        productNameError = if (productName.isBlank() && secondProductName.isBlank()) productNameRequiredAtLeastOneErrorText else null

        // NUOVA LOGICA DI VALIDAZIONE PER IL PREZZO DI VENDITA
        val retailPriceValue = retailPrice.replace(',', '.').toDoubleOrNull()
        retailPriceError = if (retailPriceValue == null || retailPriceValue <= 0) {
            retailPriceErrorText
        } else {
            null
        }

        return barcodeError == null && productNameError == null && retailPriceError == null
    }

    val scope = rememberCoroutineScope()

    val fieldScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result?.contents
        if (!scanned.isNullOrBlank()) {
            barcode = scanned
            validate() // richiama la tua validazione per mostrare eventuale duplicato
        }
    }

    var supplierId by remember { mutableStateOf(product.supplierId) }
    val noSupplierText = stringResource(R.string.no_supplier)
    var supplierName by remember { mutableStateOf(noSupplierText) }
    var showSupplierSelectionDialog by remember { mutableStateOf(false) }
    val supplierIdPrefix = stringResource(id = R.string.supplier_id_prefix)
    val scanPromptText = stringResource(R.string.scan_prompt)
    LaunchedEffect(supplierId) {
        supplierName = if (supplierId != null) {
            viewModel.getSupplierById(supplierId!!)?.name ?: "$supplierIdPrefix $supplierId"
        } else {
            noSupplierText
        }
    }

    var categoryId by remember { mutableStateOf(product.categoryId) }
    val noCategoryText = stringResource(R.string.no_category)
    var categoryName by remember { mutableStateOf(noCategoryText) }
    var showCategorySelectionDialog by remember { mutableStateOf(false) }
    val categoryIdPrefix = stringResource(id = R.string.category_id_prefix)
    LaunchedEffect(categoryId) {
        categoryName = if (categoryId != null) {
            viewModel.getCategoryById(categoryId!!)?.name ?: "$categoryIdPrefix $categoryId"
        } else {
            noCategoryText
        }
    }

    if (showSupplierSelectionDialog) {
        SupplierSelectionDialog(
            viewModel = viewModel,
            onDismiss = { showSupplierSelectionDialog = false },
            onSupplierSelected = { selectedSupplier ->
                supplierId = selectedSupplier.id
                showSupplierSelectionDialog = false
            },
            onAddNewSupplier = { name ->
                scope.launch {
                    viewModel.addSupplier(name)?.let { supplierId = it.id }
                    showSupplierSelectionDialog = false
                }
            }
        )
    }

    if (showCategorySelectionDialog) {
        CategorySelectionDialog(
            viewModel = viewModel,
            onDismiss = { showCategorySelectionDialog = false },
            onCategorySelected = { selectedCategory ->
                categoryId = selectedCategory.id
                showCategorySelectionDialog = false
            },
            onAddNewCategory = { name ->
                scope.launch {
                    viewModel.addCategory(name)?.let { categoryId = it.id }
                    showCategorySelectionDialog = false
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.edit_product_title), style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it; validate() },
                    label = { Text(stringResource(R.string.barcode_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = barcodeError != null,
                    supportingText = { barcodeError?.let { Text(it) } },
                    trailingIcon = {
                        IconButton(onClick = {
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                setPrompt(scanPromptText)
                                setBeepEnabled(true)
                                setBarcodeImageEnabled(false)
                                setCaptureActivity(PortraitCaptureActivity::class.java) // già presente nel tuo progetto
                            }
                            fieldScanLauncher.launch(options)
                        }) {
                            Icon(
                                imageVector = Icons.Filled.CameraAlt,
                                contentDescription = stringResource(R.string.scan_barcode)
                            )
                        }
                    }
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = productName, onValueChange = { productName = it; validate() }, label = { Text(stringResource(R.string.product_name_label)) }, modifier = Modifier.fillMaxWidth(), isError = productNameError != null, supportingText = { productNameError?.let { Text(it) } })
                    if (showSecondNameField) {
                        OutlinedTextField(value = secondProductName, onValueChange = { secondProductName = it; validate() }, label = { Text(stringResource(R.string.second_product_name_label)) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        TextButton(onClick = { showSecondNameField = true }) { Text(stringResource(R.string.add_second_name)) }
                    }
                }

                OutlinedTextField(
                    value = purchasePrice,
                    onValueChange = { purchasePrice = it },
                    label = { Text(stringResource(R.string.purchase_price_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        if (lastPurchase != null || prevPurchase != null) {
                            Text(
                                listOfNotNull(
                                    lastPurchase?.let { stringResource(R.string.price_last, formatNumberAsRoundedString(it)) },
                                    prevPurchase?.let { stringResource(R.string.price_previous, formatNumberAsRoundedString(it)) }
                                ).joinToString("  •  ")
                            )
                        }
                    }
                )

                OutlinedTextField(
                    value = retailPrice,
                    onValueChange = { retailPrice = it },
                    label = { Text(stringResource(R.string.retail_price_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        if (lastRetail != null || prevRetail != null) {
                            Text(
                                listOfNotNull(
                                    lastRetail?.let { stringResource(R.string.price_last, formatNumberAsRoundedString(it)) },
                                    prevRetail?.let { stringResource(R.string.price_previous, formatNumberAsRoundedString(it)) }
                                ).joinToString("  •  ")
                            )
                        }
                    }
                )

                if (showItemNumberField) {
                    OutlinedTextField(value = itemNumber, onValueChange = { itemNumber = it }, label = { Text(stringResource(R.string.item_code_label)) }, modifier = Modifier.fillMaxWidth())
                } else {
                    TextButton(onClick = { showItemNumberField = true }) { Text(stringResource(R.string.add_item_code)) }
                }

                OutlinedTextField(value = stockQuantity, onValueChange = { stockQuantity = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text(stringResource(R.string.header_stock_quantity)) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

                Box(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSupplierSelectionDialog = true }) {
                    OutlinedTextField(value = supplierName, onValueChange = {}, label = { Text(stringResource(R.string.supplier_label)) }, enabled = false, modifier = Modifier.fillMaxWidth(), trailingIcon = { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.select_supplier)) }, colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledBorderColor = MaterialTheme.colorScheme.outline, disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant, disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }

                Box(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCategorySelectionDialog = true }) {
                    OutlinedTextField(
                        value = categoryName,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.category_label)) },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.select_category)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }

                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (validate()) {
                            val productToSave = product.copy(
                                barcode = barcode.trim(),
                                productName = productName.trim(),
                                secondProductName = secondProductName.trim().takeIf { it.isNotBlank() },
                                itemNumber = itemNumber.trim().takeIf { it.isNotBlank() },
                                purchasePrice = purchasePrice.replace(',', '.').toDoubleOrNull(),
                                retailPrice = retailPrice.replace(',', '.').toDoubleOrNull(),
                                supplierId = supplierId,
                                categoryId = categoryId,
                                stockQuantity = stockQuantity.replace(',', '.').toDoubleOrNull()
                            )
                            onSave(productToSave)
                        }
                    }) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

@Composable
private fun SupplierSelectionDialog(
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit,
    onSupplierSelected: (Supplier) -> Unit,
    onAddNewSupplier: (String) -> Unit
) {
    val suppliers by viewModel.suppliers.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.onSupplierSearchQueryChanged("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_supplier_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it; viewModel.onSupplierSearchQueryChanged(it) },
                    label = { Text(stringResource(R.string.search_or_add_supplier)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = { searchText = ""; viewModel.onSupplierSearchQueryChanged("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.clear_text))
                            }
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(suppliers) { supplier ->
                        Text(
                            text = supplier.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSupplierSelected(supplier) }
                                .padding(vertical = 14.dp)
                        )
                    }
                    if (suppliers.none { it.name.equals(searchText, ignoreCase = true) } && searchText.isNotBlank()) {
                        item {
                            Text(
                                text = stringResource(R.string.add_new_supplier_prompt, searchText),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddNewSupplier(searchText) }
                                    .padding(vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton =         { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun CategorySelectionDialog(
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit,
    onCategorySelected: (Category) -> Unit,
    onAddNewCategory: (String) -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.onCategorySearchQueryChanged("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_category_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        viewModel.onCategorySearchQueryChanged(it)
                    },
                    label = { Text(stringResource(R.string.search_or_add_category)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = {
                                searchText = ""
                                viewModel.onCategorySearchQueryChanged("")
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear_text)
                                )
                            }
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(categories) { category ->
                        Text(
                            text = category.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCategorySelected(category) }
                                .padding(vertical = 14.dp)
                        )
                    }

                    if (categories.none { it.name.equals(searchText, ignoreCase = true) } && searchText.isNotBlank()) {
                        item {
                            Text(
                                text = stringResource(R.string.add_new_category_prompt, searchText),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddNewCategory(searchText) }
                                    .padding(vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

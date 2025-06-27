package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import androidx.paging.LoadState

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

    var itemToEdit by remember { mutableStateOf<Product?>(null) }
    var itemToDelete by remember { mutableStateOf<Product?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.startImportAnalysis(context, it) }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        onResult = { uri: Uri? -> uri?.let { viewModel.exportToExcel(context, it) } }
    )

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code -> viewModel.setFilter(code) }
    }

    LaunchedEffect(uiState) {
        when (val currentState = uiState) {
            is UiState.Success -> snackbarHostState.showSnackbar(currentState.message)
            is UiState.Error -> snackbarHostState.showSnackbar(currentState.message)
            else -> {}
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
                    IconButton(onClick = { downloadLauncher.launch("prodotti.xlsx") }) {
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
        ) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = filter ?: "",
                    onValueChange = { viewModel.setFilter(it) },
                    label = { Text(stringResource(R.string.barcode_filter_label)) },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (filter?.isNotEmpty() == true) {
                            IconButton(onClick = { viewModel.setFilter("") }) {
                                // --- CORREZIONE ---
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
                            items(products.itemCount, key = { products[it]?.id ?: -1 }) { idx ->
                                products[idx]?.let { product ->
                                    val dismissState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = {
                                            if (it == SwipeToDismissBoxValue.EndToStart) {
                                                itemToDelete = product
                                                showDeleteDialog = true
                                            }
                                            false
                                        }
                                    )
                                    SwipeToDismissBox(
                                        state = dismissState,
                                        backgroundContent = {
                                            val color = when (dismissState.targetValue) {
                                                SwipeToDismissBoxValue.EndToStart -> Color(0xFFB00020)
                                                else -> Color.Transparent
                                            }
                                            Box(
                                                Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Color.White)
                                            }
                                        }
                                    ) {
                                        ProductRow(
                                            product = product,
                                            viewModel = viewModel,
                                            onClick = { itemToEdit = product }
                                        )
                                    }
                                }
                            }

                            if (loadState.append is LoadState.Loading) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp),
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
                    itemToEdit = Product(barcode = "", productName = "")
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_product))
                }
            }
        }
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
}

@Composable
private fun PriceColumn(
    labelNew: String,
    priceNew: String?,
    labelOld: String,
    priceOld: String?,
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
        if (priceOld != null && priceOld.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text = labelOld, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = priceOld,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough
            )
        }
    }
}


@Composable
fun ProductRow(product: Product, viewModel: DatabaseViewModel, onClick: () -> Unit) {
    var supplierName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(product.supplierId) {
        supplierName = if (product.supplierId != null) {
            viewModel.getSupplierById(product.supplierId)?.name ?: "ID: ${product.supplierId}"
        } else {
            "-"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = product.productName ?: stringResource(R.string.unnamed_product), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text = "Barcode: ${product.barcode}  |  Cod. Art.: ${product.itemNumber ?: "-"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // --- CORREZIONE ---
                PriceColumn(
                    labelNew = stringResource(R.string.product_purchase_price_new_short),
                    priceNew = formatPriceAsInteger(product.newPurchasePrice),
                    labelOld = stringResource(R.string.product_purchase_price_old_short),
                    priceOld = formatPriceAsInteger(product.oldPurchasePrice),
                    horizontalAlignment = Alignment.Start
                )
                PriceColumn(
                    labelNew = stringResource(R.string.product_retail_price_new_short),
                    priceNew = formatPriceAsInteger(product.newRetailPrice),
                    labelOld = stringResource(R.string.product_retail_price_old_short),
                    priceOld = formatPriceAsInteger(product.oldRetailPrice),
                    horizontalAlignment = Alignment.End
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                // --- CORREZIONE ---
                Text(text = "${stringResource(R.string.product_supplier_full)}: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = supplierName ?: "...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditProductDialog(
    product: Product,
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit
) {
    var barcode by remember { mutableStateOf(product.barcode) }
    var productName by remember { mutableStateOf(product.productName ?: "") }
    var itemNumber by remember { mutableStateOf(product.itemNumber ?: "") }
    var newPurchasePrice by remember { mutableStateOf(formatPriceAsInteger(product.newPurchasePrice)) }
    var newRetailPrice by remember { mutableStateOf(formatPriceAsInteger(product.newRetailPrice)) }
    var oldPurchasePrice by remember { mutableStateOf(formatPriceAsInteger(product.oldPurchasePrice)) }
    var oldRetailPrice by remember { mutableStateOf(formatPriceAsInteger(product.oldRetailPrice)) }

    var barcodeError by remember { mutableStateOf<String?>(null) }
    var productNameError by remember { mutableStateOf<String?>(null) }

    // --- CORREZIONE: Otteniamo le stringhe di errore qui, nel contesto @Composable ---
    val barcodeRequiredErrorText = stringResource(id = R.string.error_barcode_required)
    val productNameRequiredErrorText = stringResource(id = R.string.error_productname_required)

    // La funzione 'validate' ora non ha chiamate a funzioni Composable o di Context.
    fun validate(): Boolean {
        barcodeError = if (barcode.isBlank()) barcodeRequiredErrorText else null
        productNameError = if (productName.isBlank()) productNameRequiredErrorText else null
        return barcodeError == null && productNameError == null
    }

    var supplierId by remember { mutableStateOf(product.supplierId) }
    val noSupplierText = stringResource(R.string.no_supplier)
    // 2. Usa la stringa (non la funzione) per inizializzare lo stato
    var supplierName by remember { mutableStateOf(noSupplierText) }

    var showSupplierSelectionDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(supplierId) {
        supplierName = if (supplierId != null) {
            viewModel.getSupplierById(supplierId!!)?.name ?: "ID: $supplierId"
        } else {
            context.getString(R.string.no_supplier)
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
                    val newSupplier = viewModel.addSupplier(name)
                    if (newSupplier != null) {
                        supplierId = newSupplier.id
                    }
                    showSupplierSelectionDialog = false
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
                    onValueChange = { barcode = it; barcodeError = null },
                    label = { Text(stringResource(R.string.barcode_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = barcodeError != null,
                    supportingText = { barcodeError?.let { Text(it) } }
                )
                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it; productNameError = null },
                    label = { Text(stringResource(R.string.product_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = productNameError != null,
                    supportingText = { productNameError?.let { Text(it) } }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newPurchasePrice, onValueChange = { newPurchasePrice = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.purchase_price_label)) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = newRetailPrice, onValueChange = { newRetailPrice = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.retail_price_label)) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                var showOldPrices by remember(product) {
                    mutableStateOf(product.oldPurchasePrice != null || product.oldRetailPrice != null)
                }

                if (showOldPrices) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = oldPurchasePrice, onValueChange = { oldPurchasePrice = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.old_purchase_price_label)) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = oldRetailPrice, onValueChange = { oldRetailPrice = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.old_retail_price_label)) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                } else {
                    TextButton(onClick = { showOldPrices = true }) {
                        Text(stringResource(R.string.add_previous_prices))
                    }
                }

                OutlinedTextField(value = itemNumber, onValueChange = { itemNumber = it }, label = { Text(stringResource(R.string.item_code_label)) }, modifier = Modifier.fillMaxWidth())

                Box(modifier = Modifier.fillMaxWidth().clickable { showSupplierSelectionDialog = true }) {
                    OutlinedTextField(
                        value = supplierName,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.supplier_label)) },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.select_supplier)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (validate()) {
                            val productToSave = product.copy(
                                barcode = barcode,
                                productName = productName,
                                itemNumber = itemNumber,
                                newPurchasePrice = newPurchasePrice.toDoubleOrNull(),
                                newRetailPrice = newRetailPrice.toDoubleOrNull(),
                                oldPurchasePrice = oldPurchasePrice.toDoubleOrNull(),
                                oldRetailPrice = oldRetailPrice.toDoubleOrNull(),
                                supplierId = supplierId
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

    LaunchedEffect(Unit) {
        viewModel.onSupplierSearchQueryChanged("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_supplier_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        viewModel.onSupplierSearchQueryChanged(it)
                    },
                    label = { Text(stringResource(R.string.search_or_add_supplier)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = {
                                searchText = ""
                                viewModel.onSupplierSearchQueryChanged("")
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

// --- RIMOSSO --- Funzione non più utilizzata
// @Composable
// fun PriceInfo(...)

private fun formatPriceAsInteger(price: Double?): String {
    return price?.toLong()?.toString() ?: ""
}
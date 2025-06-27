package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.launch

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
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Cancella ricerca")
                            }
                        }
                    }
                )
                if (uiState is UiState.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
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
                                        Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = Color.White)
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
                    Icon(Icons.Default.Add, contentDescription = "Aggiungi Prodotto")
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
                if (productToSave.barcode.isNotBlank() && productToSave.productName?.isNotBlank() == true) {
                    if (isNewProduct) viewModel.addProduct(productToSave) else viewModel.updateProduct(productToSave)
                    itemToEdit = null
                } else {
                    Toast.makeText(context, "Barcode e Nome Prodotto sono obbligatori.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Conferma Eliminazione") },
            text = { Text("Sei sicuro di voler eliminare questo prodotto? L'azione è irreversibile.") },
            confirmButton = {
                Button(
                    onClick = {
                        itemToDelete?.let { viewModel.deleteProduct(it) }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Annulla") } }
        )
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
            Text(text = product.productName ?: "Prodotto senza nome", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text = "Barcode: ${product.barcode}  |  Cod. Art.: ${product.itemNumber ?: "-"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.Start) {
                    // --- MODIFICA QUI ---
                    PriceInfo(label = stringResource(R.string.new_purchase_price), value = formatPriceAsInteger(product.newPurchasePrice))
                    if (product.oldPurchasePrice != null) {
                        Spacer(Modifier.height(8.dp))
                        PriceInfo(label = stringResource(R.string.old_purchase_price), value = formatPriceAsInteger(product.oldPurchasePrice))
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    // --- MODIFICA QUI ---
                    PriceInfo(label = stringResource(R.string.new_retail_price), value = formatPriceAsInteger(product.newRetailPrice))
                    if (product.oldRetailPrice != null) {
                        Spacer(Modifier.height(8.dp))
                        PriceInfo(label = stringResource(R.string.old_retail_price), value = formatPriceAsInteger(product.oldRetailPrice))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(text = "${stringResource(R.string.supplier)}: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // --- MODIFICA QUI: usiamo la nuova funzione di formattazione ---
    var barcode by remember { mutableStateOf(product.barcode) }
    var productName by remember { mutableStateOf(product.productName ?: "") }
    var itemNumber by remember { mutableStateOf(product.itemNumber ?: "") }
    var newPurchasePrice by remember { mutableStateOf(formatPriceAsInteger(product.newPurchasePrice)) }
    var newRetailPrice by remember { mutableStateOf(formatPriceAsInteger(product.newRetailPrice)) }
    var oldPurchasePrice by remember { mutableStateOf(formatPriceAsInteger(product.oldPurchasePrice)) }
    var oldRetailPrice by remember { mutableStateOf(formatPriceAsInteger(product.oldRetailPrice)) }

    var supplierId by remember { mutableStateOf(product.supplierId) }
    var supplierName by remember { mutableStateOf("Nessuno") }
    var showSupplierSelectionDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(supplierId) {
        supplierName = if (supplierId != null) {
            viewModel.getSupplierById(supplierId!!)?.name ?: "ID: $supplierId"
        } else {
            "Nessuno"
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
                Text("Modifica Prodotto", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(value = barcode, onValueChange = { barcode = it }, label = { Text("Barcode") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = productName, onValueChange = { productName = it }, label = { Text("Nome Prodotto") }, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // --- MODIFICA QUI: Filtriamo l'input per accettare solo cifre ---
                    OutlinedTextField(value = newPurchasePrice, onValueChange = { newPurchasePrice = it.filter { c -> c.isDigit() } }, label = { Text("Prezzo Acquisto") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = newRetailPrice, onValueChange = { newRetailPrice = it.filter { c -> c.isDigit() } }, label = { Text("Prezzo Vendita") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                var showOldPrices by remember(product) {
                    mutableStateOf(product.oldPurchasePrice != null || product.oldRetailPrice != null)
                }

                if (showOldPrices) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = oldPurchasePrice, onValueChange = { oldPurchasePrice = it.filter { c -> c.isDigit() } }, label = { Text("Prezzo Acquisto Vecchio") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = oldRetailPrice, onValueChange = { oldRetailPrice = it.filter { c -> c.isDigit() } }, label = { Text("Prezzo Vendita Vecchio") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                } else {
                    TextButton(onClick = { showOldPrices = true }) {
                        Text("Aggiungi prezzi precedenti")
                    }
                }

                OutlinedTextField(value = itemNumber, onValueChange = { itemNumber = it }, label = { Text("Cod. Art.") }, modifier = Modifier.fillMaxWidth())

                Box(modifier = Modifier.fillMaxWidth().clickable { showSupplierSelectionDialog = true }) {
                    OutlinedTextField(
                        value = supplierName,
                        onValueChange = {},
                        label = { Text("Fornitore") },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, "Seleziona Fornitore") },
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
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
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
                    }) { Text("Salva") }
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
        title = { Text("Seleziona Fornitore") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        viewModel.onSupplierSearchQueryChanged(it)
                    },
                    label = { Text("Cerca o aggiungi fornitore") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, // Buona pratica per i campi di ricerca
                    // --- MODIFICA QUI: Aggiungiamo l'icona per cancellare ---
                    trailingIcon = {
                        if (searchText.isNotBlank()) { // Mostra l'icona solo se c'è testo
                            IconButton(onClick = {
                                // Pulisce sia lo stato locale sia quello nel ViewModel
                                searchText = ""
                                viewModel.onSupplierSearchQueryChanged("")
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancella testo"
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
                                text = "Aggiungi \"$searchText\"",
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
                Text("Chiudi")
            }
        }
    )
}

@Composable
fun PriceInfo(label: String, value: String?, modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value ?: "-", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatPriceAsInteger(price: Double?): String {
    // Se il prezzo è nullo, restituisce una stringa vuota.
    // Altrimenti, lo formatta come un numero intero (senza decimali).
    return price?.toLong()?.toString() ?: ""
}
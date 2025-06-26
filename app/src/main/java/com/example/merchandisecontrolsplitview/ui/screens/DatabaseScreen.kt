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
import androidx.compose.ui.res.stringResource
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType

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

    // --- STATO PER LA GESTIONE DEI DIALOGHI ---
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
                        uploadLauncher.launch(arrayOf(
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        ))
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    singleLine = true, // Migliora l'aspetto per una barra di ricerca
                    // --- INIZIO NUOVO CODICE ---
                    trailingIcon = {
                        // Mostra l'icona solo se il campo di testo non è vuoto
                        if (filter?.isNotEmpty() == true) {
                            IconButton(onClick = { viewModel.setFilter("") }) { // Svuota il filtro al click
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancella ricerca"
                                )
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
                    items(products.itemCount, key = { products[it]?.id ?: -1 } ) { idx ->
                        products[idx]?.let { product ->
                            // --- RIGA PRODOTTO INTERATTIVA CON SWIPE ---
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
                                    onClick = { itemToEdit = product } // Clic per modificare
                                )
                            }
                        }
                    }
                }
            }

            // --- NUOVA COLONNA PER I FAB ---
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                // FAB per scanner
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
                // FAB per aggiungere un nuovo prodotto
                FloatingActionButton(onClick = {
                    itemToEdit = Product(barcode = "", productName = "") // Apri dialogo vuoto
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Aggiungi Prodotto")
                }
            }
        }
    }

    // --- DIALOGO PER MODIFICARE O AGGIUNGERE UN PRODOTTO ---
    if (itemToEdit != null) {
        val isNewProduct = itemToEdit!!.id == 0L // Controlla se è un nuovo prodotto
        EditProductDialog(
            product = itemToEdit!!,
            onDismiss = { itemToEdit = null },
            onSave = { productToSave ->
                if (productToSave.barcode.isNotBlank() && productToSave.productName?.isNotBlank() == true) {
                    if (isNewProduct) {
                        viewModel.addProduct(productToSave)
                    } else {
                        viewModel.updateProduct(productToSave)
                    }
                    itemToEdit = null
                } else {
                    Toast.makeText(context, "Barcode e Nome Prodotto sono obbligatori.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // --- DIALOGO PER CONFERMARE LA CANCELLAZIONE ---
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
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Annulla") }
            }
        )
    }
}

/**
 * Composable per una riga prodotto (ora con `onClick`).
 */
@Composable
fun ProductRow(product: Product, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = product.productName ?: "Prodotto senza nome",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Barcode: ${product.barcode}  |  Cod. Art.: ${product.itemNumber ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    PriceInfo(label = stringResource(R.string.new_purchase_price), value = product.newPurchasePrice?.let { "%.2f".format(it) }, modifier = Modifier.weight(1f))
                    PriceInfo(label = stringResource(R.string.old_purchase_price), value = product.oldPurchasePrice?.let { "%.2f".format(it) }, modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    PriceInfo(label = stringResource(R.string.new_retail_price), value = product.newRetailPrice?.let { "%.2f".format(it) }, modifier = Modifier.weight(1f))
                    PriceInfo(label = stringResource(R.string.old_retail_price), value = product.oldRetailPrice?.let { "%.2f".format(it) }, modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(text = "${stringResource(R.string.supplier)}: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = product.supplier ?: "-", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
            }
        }
    }
}

/**
 * Dialog per modificare i campi di un prodotto.
 */
@Composable
private fun EditProductDialog(
    product: Product,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit
) {
    var tempProduct by remember(product) { mutableStateOf(product) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            // Aggiunto lo scorrimento verticale per quando appare la tastiera
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp) // Aumentato lo spazio
            ) {
                Text("Modifica Prodotto", style = MaterialTheme.typography.titleLarge)

                // Barcode a larghezza piena
                OutlinedTextField(
                    value = tempProduct.barcode,
                    onValueChange = { tempProduct = tempProduct.copy(barcode = it) },
                    label = { Text("Barcode") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Nome Prodotto a larghezza piena
                OutlinedTextField(
                    value = tempProduct.productName ?: "",
                    onValueChange = { tempProduct = tempProduct.copy(productName = it) },
                    label = { Text("Nome Prodotto") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Prezzi su due colonne
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tempProduct.newPurchasePrice?.toString() ?: "",
                        onValueChange = { v -> tempProduct = tempProduct.copy(newPurchasePrice = v.replace(",", ".").toDoubleOrNull()) },
                        label = { Text("Prezzo Acquisto") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = tempProduct.newRetailPrice?.toString() ?: "",
                        onValueChange = { v -> tempProduct = tempProduct.copy(newRetailPrice = v.replace(",", ".").toDoubleOrNull()) },
                        label = { Text("Prezzo Vendita") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                // Codice Articolo e Fornitore su due colonne
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tempProduct.itemNumber ?: "",
                        onValueChange = { tempProduct = tempProduct.copy(itemNumber = it) },
                        label = { Text("Cod. Art.") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tempProduct.supplier ?: "",
                        onValueChange = { tempProduct = tempProduct.copy(supplier = it) },
                        label = { Text("Fornitore") },
                        modifier = Modifier.weight(1f)
                    )
                }


                // Pulsanti Salva e Annulla
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(tempProduct) }) { Text("Salva") }
                }
            }
        }
    }
}

/**
 * Composable per visualizzare un'etichetta e il suo valore.
 */
@Composable
fun PriceInfo(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: "-",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
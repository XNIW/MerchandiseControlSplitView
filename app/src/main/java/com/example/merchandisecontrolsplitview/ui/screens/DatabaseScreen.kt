package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
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
import androidx.compose.ui.res.stringResource
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.example.merchandisecontrolsplitview.viewmodel.UiState

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
                        .padding(8.dp)
                )
                if (uiState is UiState.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(products.itemCount) { idx ->
                        products[idx]?.let { ProductRow(it) }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    val opts = ScanOptions().apply {
                        setDesiredBarcodeFormats(ALL_CODE_TYPES)
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                        setBeepEnabled(true)
                        setPrompt(context.getString(R.string.scan_prompt))
                    }
                    scanLauncher.launch(opts)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = stringResource(R.string.scan_barcode))
            }
        }
    }
}
/**
 * Composable per una riga prodotto con un layout corretto e migliorato.
 */
@Composable
fun ProductRow(product: Product) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // --- Sezione Titolo e Identificativi (invariata) ---
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

            // --- NUOVA Sezione Prezzi ---
            // Layout a griglia per i prezzi per evitare sovrapposizioni
            Column(modifier = Modifier.fillMaxWidth()) {
                // Riga per i prezzi di acquisto
                Row(modifier = Modifier.fillMaxWidth()) {
                    PriceInfo(
                        label = stringResource(R.string.new_purchase_price),
                        value = product.newPurchasePrice?.let { "%.2f".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                    PriceInfo(
                        label = stringResource(R.string.old_purchase_price),
                        value = product.oldPurchasePrice?.let { "%.2f".format(it) },
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    )
                }
                Spacer(Modifier.height(8.dp)) // Spazio tra le righe di prezzo
                // Riga per i prezzi di vendita
                Row(modifier = Modifier.fillMaxWidth()) {
                    PriceInfo(
                        label = stringResource(R.string.new_retail_price),
                        value = product.newRetailPrice?.let { "%.2f".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                    PriceInfo(
                        label = stringResource(R.string.old_retail_price),
                        value = product.oldRetailPrice?.let { "%.2f".format(it) },
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    )
                }
            }
            // --- Fine NUOVA Sezione Prezzi ---

            Spacer(Modifier.height(8.dp))

            // --- Sezione Fornitore (spostata in un Composable dedicato per coerenza) ---
            Row {
                Text(
                    text = "${stringResource(R.string.supplier)}: ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = product.supplier ?: "-",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

/**
 * NUOVO Composable per visualizzare un'etichetta e il suo valore.
 * L'etichetta è sopra il valore per evitare problemi di larghezza.
 *
 * NOTA: Questo sostituisce il vecchio `ProductInfoLine`.
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
            style = MaterialTheme.typography.labelMedium, // Etichetta più piccola
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: "-",
            style = MaterialTheme.typography.bodyLarge, // Valore più grande
            fontWeight = FontWeight.SemiBold
        )
    }
}


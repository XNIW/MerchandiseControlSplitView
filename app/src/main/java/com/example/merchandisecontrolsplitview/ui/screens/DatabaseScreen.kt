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
import androidx.compose.foundation.layout.Box
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseScreen(
    navController: NavHostController,
    viewModel: DatabaseViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val products = viewModel.pager.collectAsLazyPagingItems()

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromExcel(it) }
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? -> uri?.let { viewModel.exportToExcel(it) } }
    )

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result?.contents?.let { code ->
            viewModel.setFilter(code)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestione Prodotti") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Torna indietro")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        uploadLauncher.launch(arrayOf(
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        ))
                    }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Importa")
                    }
                    IconButton(onClick = { downloadLauncher.launch("prodotti.csv") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Esporta")
                    }
                }
            )
        }
    ) { paddingValues -> // Ho rinominato 'padding' in 'paddingValues' per chiarezza, non è obbligatorio
        // ***** INIZIO DELLA MODIFICA *****
        // Avvolgi il contenuto della schermata e il FAB in una Box
        Box(
            modifier = Modifier
                .padding(paddingValues) // Applica il padding dello Scaffold all'intera Box
                .fillMaxSize() // Fai in modo che la Box occupi tutto lo spazio rimanente
        ) {
            Column(Modifier.fillMaxSize()) { // Questa Column gestisce il layout principale della tua UI
                OutlinedTextField(
                    value = filter ?: "",
                    onValueChange = { viewModel.setFilter(it) },
                    label = { Text("barcode, nome, item number, fornitore") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
                if (uiState is com.example.merchandisecontrolsplitview.viewmodel.UiState.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(products.itemCount) { idx ->
                        products[idx]?.let { ProductRow(it) }
                        HorizontalDivider()
                    }
                }
            }
            if (uiState is com.example.merchandisecontrolsplitview.viewmodel.UiState.Error) {
                val msg = (uiState as com.example.merchandisecontrolsplitview.viewmodel.UiState.Error).message
                Snackbar(
                    action = {
                        TextButton(onClick = { /* retry? */ }) { Text("Riprova") }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter) // Puoi allineare la Snackbar se vuoi
                ) { Text(msg) }
            }
            if (uiState is com.example.merchandisecontrolsplitview.viewmodel.UiState.Success) {
                val msg = (uiState as com.example.merchandisecontrolsplitview.viewmodel.UiState.Success).message
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter) // Puoi allineare la Snackbar se vuoi
                ) { Text(msg) }
            }

            FloatingActionButton(
                onClick = {
                    val opts = ScanOptions().apply {
                        setDesiredBarcodeFormats(ALL_CODE_TYPES)
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                        setBeepEnabled(true)
                        setPrompt("Inquadra un barcode")
                    }
                    scanLauncher.launch(opts)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd) // <-- Ora questo funziona!
                    .padding(end = 24.dp, bottom = 24.dp)
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Scansiona barcode")
            }
        }
        // ***** FINE DELLA MODIFICA *****
    }
}

@Composable
fun ProductRow(product: Product) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Barcode:", style = MaterialTheme.typography.labelMedium)
                Text(product.barcode, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Item Number:", style = MaterialTheme.typography.labelMedium)
                Text(product.itemNumber ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Product Name:", style = MaterialTheme.typography.labelMedium)
                Text(product.productName ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("New Purchase Price:", style = MaterialTheme.typography.labelMedium)
                Text(product.newPurchasePrice?.let { "%.2f".format(it) } ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("New Retail Price:", style = MaterialTheme.typography.labelMedium)
                Text(product.newRetailPrice?.let { "%.2f".format(it) } ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Old Purchase Price:", style = MaterialTheme.typography.labelMedium)
                Text(product.oldPurchasePrice?.let { "%.2f".format(it) } ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Old Retail Price:", style = MaterialTheme.typography.labelMedium)
                Text(product.oldRetailPrice?.let { "%.2f".format(it) } ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Supplier:", style = MaterialTheme.typography.labelMedium)
                Text(product.supplier ?: "-", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
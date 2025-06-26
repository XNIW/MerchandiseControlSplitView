package com.example.merchandisecontrolsplitview.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.util.ErrorExporter
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportAnalysisScreen(
    excelViewModel: ExcelViewModel,
    importAnalysis: ImportAnalysis,
    onConfirm: (List<Product>, List<ProductUpdate>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val editableNewProducts = remember { importAnalysis.newProducts.map { it.copy() }.toMutableStateList() }
    val editableUpdatedProducts = remember { importAnalysis.updatedProducts.map { it.copy(newProduct = it.newProduct.copy()) }.toMutableStateList() }

    var newProductsExpanded by remember { mutableStateOf(true) }
    var updatedProductsExpanded by remember { mutableStateOf(true) }
    var errorsExpanded by remember { mutableStateOf(true) }

    var itemToEdit by remember { mutableStateOf<Pair<Int, Product>?>(null) }
    var updateToEdit by remember { mutableStateOf<Pair<Int, ProductUpdate>?>(null) }


    val exportErrorsLauncher = rememberLauncherForActivityResult(
        // Cambia il tipo di file in XLSX
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let {
            // Chiama la nuova funzione di esportazione per XLSX
            ErrorExporter.exportErrorsToXlsx(importAnalysis.errors, context, it)
            Toast.makeText(context, "File errori esportato.", Toast.LENGTH_SHORT).show()
        }
    }

    if (itemToEdit != null) {
        val (index, product) = itemToEdit!!
        EditProductDialog(
            product = product,
            onDismiss = { itemToEdit = null },
            onSave = { updatedProduct ->
                editableNewProducts[index] = updatedProduct
                itemToEdit = null
            }
        )
    }

    if (updateToEdit != null) {
        val (index, productUpdate) = updateToEdit!!
        EditProductDialog(
            product = productUpdate.newProduct,
            onDismiss = { updateToEdit = null },
            onSave = { updatedProduct ->
                editableUpdatedProducts[index] = productUpdate.copy(newProduct = updatedProduct)
                updateToEdit = null
            }
        )
    }


    Scaffold(
        topBar = { TopAppBar(title = { Text("Revisione Importazione") }) },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                Button(
                    onClick = { onConfirm(editableNewProducts, editableUpdatedProducts) },
                    enabled = editableNewProducts.isNotEmpty() || editableUpdatedProducts.isNotEmpty()
                ) { Text("Conferma Importazione") }
                OutlinedButton(onClick = onCancel) { Text("Annulla") }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ExpandableSection(
                    title = "Nuovi prodotti da aggiungere: ${editableNewProducts.size}",
                    isExpanded = newProductsExpanded,
                    onToggle = { newProductsExpanded = !newProductsExpanded }
                ) {
                    if (editableNewProducts.isEmpty()) {
                        Text("Nessun nuovo prodotto.", modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (newProductsExpanded && editableNewProducts.isNotEmpty()) {
                itemsIndexed(
                    editableNewProducts,
                    // FIX: Key is made unique by combining barcode and index
                    key = { index, p -> "new-${p.barcode}-$index" }
                ) { index, product ->
                    DisplayProductRow(
                        product = product,
                        onEditClick = { itemToEdit = index to product }
                    )
                }
            }

            item {
                ExpandableSection(
                    title = "Prodotti da aggiornare: ${editableUpdatedProducts.size}",
                    isExpanded = updatedProductsExpanded,
                    onToggle = { updatedProductsExpanded = !updatedProductsExpanded }
                ) {
                    if (editableUpdatedProducts.isEmpty()) {
                        Text("Nessun prodotto da aggiornare.", modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (updatedProductsExpanded && editableUpdatedProducts.isNotEmpty()) {
                itemsIndexed(
                    editableUpdatedProducts,
                    // FIX: Key is made unique by combining product ID and index
                    key = { index, u -> "update-${u.oldProduct.id}-$index" }
                ) { index, update ->
                    DisplayProductUpdateRow(
                        productUpdate = update,
                        onEditClick = { updateToEdit = index to update }
                    )
                }
            }

            item {
                ExpandableSection(
                    title = "Errori trovati: ${importAnalysis.errors.size}",
                    isExpanded = errorsExpanded,
                    onToggle = { errorsExpanded = !errorsExpanded }
                ) {
                    if (importAnalysis.errors.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Pulsante Esporta esistente
                            Button(onClick = { exportErrorsLauncher.launch("errori_importazione.xlsx") }) {
                                Text("Esporta Errori")
                            }
                            OutlinedButton(onClick = {
                                // Imposta gli indici nel ViewModel
                                excelViewModel.errorRowIndexes.value = importAnalysis.errors.map { it.rowNumber }.toSet()
                                // E poi torna indietro
                                onCancel()
                            }) {
                                Text("Correggi")
                            }
                        }
                    } else {
                        Text("Nessun errore critico trovato.", modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (errorsExpanded && importAnalysis.errors.isNotEmpty()) {
                itemsIndexed(
                    importAnalysis.errors,
                    // FIX: Key is made unique by combining row number and index for absolute safety
                    key = { index, e -> "error-${e.rowNumber}-$index" }
                ) { _, err ->
                    ErrorRow(error = err)
                }
            }
        }
    }
}

@Composable
private fun DisplayProductRow(product: Product, onEditClick: () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(product.productName ?: "Senza Nome", fontWeight = FontWeight.Bold)
                Text("Barcode: ${product.barcode}", style = MaterialTheme.typography.bodySmall)
                Text("Cod. Art.: ${product.itemNumber ?: "-"}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Acq: ${product.newPurchasePrice?.let { "%.2f".format(it) } ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                Text("Ven: ${product.newRetailPrice?.let { "%.2f".format(it) } ?: "-"}", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onEditClick) {
                Icon(Icons.Default.Edit, contentDescription = "Modifica Prodotto")
            }
        }
    }
}

@Composable
private fun DisplayProductUpdateRow(productUpdate: ProductUpdate, onEditClick: () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    productUpdate.oldProduct.productName ?: "Senza Nome",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifica Aggiornamento")
                }
            }
            Text("Barcode: ${productUpdate.oldProduct.barcode}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Campo", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                Text("Precedente", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Nuovo", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            productUpdate.changedFields.forEach { field ->
                CompareRow(field = field, old = productUpdate.oldProduct, new = productUpdate.newProduct)
            }
        }
    }
}

@Composable
private fun CompareRow(field: String, old: Product, new: Product) {
    val (oldValue, newValue) = when (field) {
        "Nome Prodotto" -> old.productName to new.productName
        "Codice Articolo" -> old.itemNumber to new.itemNumber
        "Prezzo Acquisto" -> old.newPurchasePrice?.toString() to new.newPurchasePrice?.toString()
        "Prezzo Vendita" -> old.newRetailPrice?.toString() to new.newRetailPrice?.toString()
        "Fornitore" -> old.supplier to new.supplier
        else -> "" to ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(field, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = oldValue ?: "-",
            modifier = Modifier.weight(1f),
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = newValue ?: "-",
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF006400) // Verde scuro più leggibile
        )
    }
}


@Composable
private fun EditProductDialog(
    product: Product,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit
) {
    var tempProduct by remember { mutableStateOf(product) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Modifica Prodotto", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = tempProduct.barcode, onValueChange = { tempProduct = tempProduct.copy(barcode = it) }, label = { Text("Barcode") })
                OutlinedTextField(value = tempProduct.itemNumber ?: "", onValueChange = { tempProduct = tempProduct.copy(itemNumber = it) }, label = { Text("Cod. Art.") })
                OutlinedTextField(value = tempProduct.productName ?: "", onValueChange = { tempProduct = tempProduct.copy(productName = it) }, label = { Text("Nome Prodotto") })
                OutlinedTextField(value = tempProduct.newPurchasePrice?.toString() ?: "", onValueChange = { v -> tempProduct = tempProduct.copy(newPurchasePrice = v.replace(",", ".").toDoubleOrNull()) }, label = { Text("Prezzo Acquisto") })
                OutlinedTextField(value = tempProduct.newRetailPrice?.toString() ?: "", onValueChange = { v -> tempProduct = tempProduct.copy(newRetailPrice = v.replace(",", ".").toDoubleOrNull()) }, label = { Text("Prezzo Vendita") })
                OutlinedTextField(value = tempProduct.supplier ?: "", onValueChange = { tempProduct = tempProduct.copy(supplier = it) }, label = { Text("Fornitore") })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(tempProduct) }) { Text("Salva") }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "rotation")
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = if (isExpanded) "Comprimi" else "Espandi",
                    modifier = Modifier.rotate(rotationAngle)
                )
            }
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(error: RowImportError) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // Motivo dell'errore in evidenza
            Text(
                "Riga ${error.rowNumber}: ${error.errorReason}",
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onError.copy(alpha = 0.5f))

            // --- INIZIO LOGICA DI EVIDENZIAZIONE ---
            // 1. Determina la chiave del campo problematico in base al messaggio di errore
            val problematicKey = when {
                error.errorReason.contains("prezzo di vendita") -> "newRetailPrice"
                error.errorReason.contains("quantità") -> "quantity"
                error.errorReason.contains("barcode") -> "barcode"
                error.errorReason.contains("nome del prodotto") -> "productName"
                // Aggiungi altri casi se necessario
                else -> null
            }

            // Dettagli principali per identificare il prodotto
            val barcode = error.rowContent["barcode"] ?: "-"
            val productName = error.rowContent["productName"] ?: "Prodotto non identificato"
            val quantity = error.rowContent["quantity"] ?: "-"
            val retailPrice = error.rowContent["newRetailPrice"] ?: "-"

            // 2. Mostra i campi, applicando uno stile diverso se la chiave corrisponde
            //    a quella del campo problematico.
            ErrorDetailText(
                label = "Barcode",
                value = barcode,
                isHighlighted = problematicKey == "barcode"
            )
            ErrorDetailText(
                label = "Nome Prodotto",
                value = productName,
                isHighlighted = problematicKey == "productName"
            )

            Spacer(Modifier.height(4.dp))

            ErrorDetailText(
                label = "Quantità contata",
                value = quantity,
                isHighlighted = problematicKey == "quantity"
            )
            ErrorDetailText(
                label = "Nuovo prezzo v.",
                value = retailPrice,
                isHighlighted = problematicKey == "newRetailPrice"
            )
            // --- FINE LOGICA DI EVIDENZIAZIONE ---
        }
    }
}

/**
 * NUOVO Composable helper per mostrare una riga di dettaglio all'interno della ErrorRow.
 * Evidenzia il valore se `isHighlighted` è true.
 */
@Composable
private fun ErrorDetailText(label: String, value: String, isHighlighted: Boolean) {
    // 1. Definisce un modificatore condizionale per lo sfondo.
    //    Viene applicato solo se isHighlighted è true.
    val rowModifier = if (isHighlighted) {
        Modifier
            .background(
                color = Color.Red.copy(alpha = 0.25f), // Un rosso vivido ma semi-trasparente
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp) // Un po' di spazio interno
    } else {
        Modifier // Nessun modificatore se non è il campo con l'errore
    }

    // 2. Applica il modificatore all'intera riga
    Row(modifier = rowModifier) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onError
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            // Manteniamo il grassetto per un'ulteriore enfasi
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onError
        )
    }
}
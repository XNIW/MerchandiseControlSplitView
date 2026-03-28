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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.DuplicateWarning
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.util.ErrorExporter
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedString
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportAnalysisScreen(
    excelViewModel: ExcelViewModel,
    databaseViewModel: DatabaseViewModel,
    importAnalysis: ImportAnalysis,
    onConfirm: (List<Product>, List<ProductUpdate>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val errorFileExportedText = stringResource(R.string.error_file_exported)

    val editableNewProducts = remember { importAnalysis.newProducts.map { it.copy() }.toMutableStateList() }
    val editableUpdatedProducts = remember { importAnalysis.updatedProducts.map { it.copy(newProduct = it.newProduct.copy()) }.toMutableStateList() }

    var newProductsExpanded by remember { mutableStateOf(true) }
    var updatedProductsExpanded by remember { mutableStateOf(true) }
    var errorsExpanded by remember { mutableStateOf(true) }
    var warningsExpanded by remember { mutableStateOf(true) }

    var itemToEdit by remember { mutableStateOf<Pair<Int, Product>?>(null) }
    var updateToEdit by remember { mutableStateOf<Pair<Int, ProductUpdate>?>(null) }

    val exportErrorsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri?.let {
            ErrorExporter.exportErrorsToXlsx(importAnalysis.errors, context, it)
            Toast.makeText(context, errorFileExportedText, Toast.LENGTH_SHORT).show()
        }
    }

    if (itemToEdit != null) {
        val (index, product) = itemToEdit!!
        EditProductDialog(
            product = product,
            viewModel = databaseViewModel,
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
            viewModel = databaseViewModel,
            onDismiss = { updateToEdit = null },
            onSave = { updatedProduct ->
                editableUpdatedProducts[index] = productUpdate.copy(newProduct = updatedProduct)
                updateToEdit = null
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.import_analysis_title)) }) },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                Button(
                    onClick = { onConfirm(editableNewProducts, editableUpdatedProducts) },
                    enabled = editableNewProducts.isNotEmpty() || editableUpdatedProducts.isNotEmpty()
                ) { Text(stringResource(R.string.confirm_import)) }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (importAnalysis.warnings.isNotEmpty()) {
                item {
                    ExpandableSection(
                        title = stringResource(R.string.duplicate_warnings_found, importAnalysis.warnings.size),
                        isExpanded = warningsExpanded,
                        onToggle = { warningsExpanded = !warningsExpanded }
                    ) {
                        // Questo content non è necessario se la lista è sempre mostrata sotto
                    }
                }
                if (warningsExpanded) {
                    items(importAnalysis.warnings, key = { "warning-${it.barcode}" }) { warning ->
                        WarningRow(warning = warning)
                    }
                }
            }
            item {
                ExpandableSection(
                    title = stringResource(R.string.new_products_to_add, editableNewProducts.size),
                    isExpanded = newProductsExpanded,
                    onToggle = { newProductsExpanded = !newProductsExpanded }
                ) {
                    if (editableNewProducts.isEmpty()) {
                        Text(stringResource(R.string.no_new_products), modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (newProductsExpanded && editableNewProducts.isNotEmpty()) {
                itemsIndexed(editableNewProducts, key = { index, p -> "new-${p.barcode}-$index" }) { index, product ->
                    DisplayProductRow(
                        product = product,
                        databaseViewModel = databaseViewModel, // <-- AGGIUNGI QUESTO PARAMETRO
                        onEditClick = { itemToEdit = index to product }
                    )
                }
            }

            item {
                ExpandableSection(
                    title = stringResource(R.string.products_to_update, editableUpdatedProducts.size),
                    isExpanded = updatedProductsExpanded,
                    onToggle = { updatedProductsExpanded = !updatedProductsExpanded }
                ) {
                    if (editableUpdatedProducts.isEmpty()) {
                        Text(stringResource(R.string.no_products_to_update), modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (updatedProductsExpanded && editableUpdatedProducts.isNotEmpty()) {
                itemsIndexed(editableUpdatedProducts, key = { index, u -> "update-${u.oldProduct.id}-$index" }) { index, update ->
                    DisplayProductUpdateRow(
                        productUpdate = update,
                        onEditClick = { updateToEdit = index to update }
                    )
                }
            }

            item {
                ExpandableSection(
                    title = stringResource(R.string.errors_found, importAnalysis.errors.size),
                    isExpanded = errorsExpanded,
                    onToggle = { errorsExpanded = !errorsExpanded }
                ) {
                    if (importAnalysis.errors.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val exportErrorsFilename = stringResource(R.string.default_error_export_filename)
                            Button(onClick = { exportErrorsLauncher.launch(exportErrorsFilename) }) {
                                Text(stringResource(R.string.export_errors))
                            }
                            OutlinedButton(onClick = {
                                excelViewModel.errorRowIndexes.value = importAnalysis.errors.map { it.rowNumber }.toSet()
                                onCancel()
                            }) {
                                Text(stringResource(R.string.correct_errors))
                            }
                        }
                    } else {
                        Text(stringResource(R.string.no_critical_errors_found), modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (errorsExpanded && importAnalysis.errors.isNotEmpty()) {
                itemsIndexed(importAnalysis.errors, key = { index, e -> "error-${e.rowNumber}-$index" }) { _, err ->
                    ErrorRow(error = err)
                }
            }
        }
    }
}

@Composable
private fun WarningRow(warning: DuplicateWarning) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.warning_duplicate_barcode, warning.barcode),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
            )
            Text(
                text = stringResource(R.string.warning_duplicate_found_in_rows, warning.rowNumbers.joinToString(", ")),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.warning_duplicate_resolution),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
@Composable
private fun DisplayProductRow(
    product: Product,
    databaseViewModel: DatabaseViewModel, // <-- 1. AGGIUNGI IL VIEWMODEL
    onEditClick: () -> Unit
) {
    // --- 2. STATI PER CONSERVARE I NOMI RECUPERATI ---
    var supplierName by remember { mutableStateOf<String?>(null) }
    var categoryName by remember { mutableStateOf<String?>(null) }
    val loadingEllipsisText = stringResource(R.string.loading_ellipsis)
    val notFoundShortText = stringResource(R.string.not_found_short)

    // --- 3. EFFETTO PER CARICARE I NOMI QUANDO GLI ID CAMBIANO ---
    LaunchedEffect(product.supplierId) {
        if (product.supplierId != null) {
            // Se non è già stato caricato, impostiamo un testo temporaneo
            if (supplierName == null) supplierName = loadingEllipsisText

            // Chiamata asincrona per ottenere il nome
            val supplier = databaseViewModel.getSupplierById(product.supplierId)
            supplierName = supplier?.name ?: notFoundShortText
        }
    }

    LaunchedEffect(product.categoryId) {
        if (product.categoryId != null) {
            if (categoryName == null) categoryName = loadingEllipsisText

            val category = databaseViewModel.getCategoryById(product.categoryId)
            categoryName = category?.name ?: notFoundShortText
        }
    }

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(product.productName ?: stringResource(R.string.unnamed_product), fontWeight = FontWeight.Bold)

                if (!product.secondProductName.isNullOrBlank()) {
                    Text(
                        text = product.secondProductName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("${stringResource(R.string.barcode_prefix)} ${product.barcode}", style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(R.string.item_number_prefix)} ${product.itemNumber ?: "-"}", style = MaterialTheme.typography.bodySmall)

                // --- 4. VISUALIZZA I NOMI RECUPERATI ---
                if (supplierName != null) {
                    Text("${stringResource(R.string.supplier_label)}: $supplierName", style = MaterialTheme.typography.bodySmall)
                }
                if (categoryName != null) {
                    Text("${stringResource(R.string.category_label)}: $categoryName", style = MaterialTheme.typography.bodySmall)
                }

                Text("${stringResource(R.string.counted_quantity_label)}: ${formatNumberAsRoundedString(product.stockQuantity)}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${stringResource(R.string.purchase_prefix)} ${formatNumberAsRoundedString(product.purchasePrice)}", style = MaterialTheme.typography.bodyMedium)
                Text("${stringResource(R.string.sell_prefix)} ${formatNumberAsRoundedString(product.retailPrice)}", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onEditClick) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_product))
            }
        }
    }
}

@Composable
private fun DisplayProductUpdateRow(productUpdate: ProductUpdate, onEditClick: () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(productUpdate.oldProduct.productName ?: stringResource(R.string.unnamed_product), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_update))
                }
            }

            if (!productUpdate.oldProduct.secondProductName.isNullOrBlank()) {
                Text(
                    text = productUpdate.oldProduct.secondProductName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("${stringResource(R.string.barcode_prefix)} ${productUpdate.oldProduct.barcode}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.compare_field), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                Text(stringResource(R.string.compare_previous), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.compare_new), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            productUpdate.changedFields.forEach { fieldResId ->
                CompareRow(fieldResId = fieldResId, old = productUpdate.oldProduct, new = productUpdate.newProduct)
            }
        }
    }
}

@Composable
private fun CompareRow(fieldResId: Int, old: Product, new: Product) {
    // --- CORREZIONE: Assicura che tutti i rami del 'when' restituiscano Pair<String?, String?> ---
    val (oldValue, newValue) = when (fieldResId) {
        R.string.field_product_name -> old.productName to new.productName
        R.string.field_second_product_name -> old.secondProductName to new.secondProductName
        R.string.header_item_number -> old.itemNumber to new.itemNumber
        R.string.purchase_price_label -> formatNumberAsRoundedString(old.purchasePrice) to formatNumberAsRoundedString(new.purchasePrice)
        R.string.retail_price_label -> formatNumberAsRoundedString(old.retailPrice) to formatNumberAsRoundedString(new.retailPrice)
        R.string.field_supplier -> old.supplierId?.toString() to new.supplierId?.toString()
        R.string.field_category -> old.categoryId?.toString() to new.categoryId?.toString() // Ora usa categoryId e lo converte in String
        R.string.field_stock_quantity -> formatNumberAsRoundedString(old.stockQuantity) to formatNumberAsRoundedString(new.stockQuantity)
        else -> "" to ""
    }

    val fieldName = stringResource(fieldResId)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fieldName, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        Text(text = oldValue ?: "-", modifier = Modifier.weight(1f), textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = newValue ?: "-", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Color(0xFF006400))
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "rotation")
                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand), modifier = Modifier.rotate(rotationAngle))
            }
            AnimatedVisibility(visible = isExpanded) {
                Column { content() }
            }
        }
    }
}

@Composable
private fun ErrorRow(error: RowImportError) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)) {
            val errorReasonText = stringResource(error.errorReasonResId, *error.formatArgs.toTypedArray())
            Text("${stringResource(R.string.row_prefix)} ${error.rowNumber}: $errorReasonText", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onError.copy(alpha = 0.5f))

            val problematicKey = when (error.errorReasonResId) {
                R.string.error_invalid_retail_price -> "RetailPrice"
                R.string.error_barcode_required -> "barcode"
                R.string.error_productname_required_at_least_one -> "productName"
                R.string.error_productname_required -> "productName"
                R.string.error_invalid_quantity -> "quantity"
                else -> null
            }

            val barcode = error.rowContent["barcode"] ?: "-"
            val productName = error.rowContent["productName"] ?: "-"
            val secondProductName = error.rowContent["secondProductName"] ?: "-"
            val quantity = error.rowContent["quantity"] ?: "-"
            val retailPrice = error.rowContent["RetailPrice"] ?: "-"

            val highlightNames = problematicKey == "productName"

            ErrorDetailText(label = stringResource(R.string.header_barcode), value = barcode, isHighlighted = problematicKey == "barcode")
            ErrorDetailText(label = stringResource(R.string.header_product_name), value = productName, isHighlighted = highlightNames)
            ErrorDetailText(label = stringResource(R.string.header_second_product_name), value = secondProductName, isHighlighted = highlightNames)
            Spacer(Modifier.height(4.dp))
            ErrorDetailText(label = stringResource(R.string.counted_quantity_label), value = quantity, isHighlighted = problematicKey == "quantity")
            ErrorDetailText(label = stringResource(R.string.new_retail_price_short_label), value = retailPrice, isHighlighted = problematicKey == "RetailPrice")
        }
    }
}

@Composable
private fun ErrorDetailText(label: String, value: String, isHighlighted: Boolean) {
    val rowModifier = if (isHighlighted) {
        Modifier
            .background(color = Color.Red.copy(alpha = 0.25f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    } else {
        Modifier
    }
    Row(modifier = rowModifier) {
        Text(text = "$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onError)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onError)
    }
}

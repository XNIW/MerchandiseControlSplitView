package com.example.merchandisecontrolsplitview.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.DuplicateWarning
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.util.ErrorExporter
import com.example.merchandisecontrolsplitview.util.formatClCount
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityDisplayReadOnly
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ImportFlowState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportAnalysisScreen(
    excelViewModel: ExcelViewModel,
    databaseViewModel: DatabaseViewModel,
    importAnalysis: ImportAnalysis,
    importFlowState: ImportFlowState,
    onConfirm: (Long, List<Product>, List<ProductUpdate>) -> Unit,
    onClose: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val context = LocalContext.current
    val errorFileExportedText = stringResource(R.string.error_file_exported)
    val errorExportFailedText = stringResource(R.string.error_export_generic)
    val isApplying = importFlowState is ImportFlowState.Applying
    val previewId = when (importFlowState) {
        is ImportFlowState.PreviewReady -> importFlowState.previewId
        is ImportFlowState.Error -> importFlowState.previewId
        else -> null
    }
    val importErrorMessage = (importFlowState as? ImportFlowState.Error)?.message
    val closeActionText = stringResource(
        if (importErrorMessage == null) R.string.cancel else R.string.close
    )

    val editableNewProducts = remember(importAnalysis) {
        importAnalysis.newProducts.map { it.copy() }.toMutableStateList()
    }
    val editableUpdatedProducts = remember(importAnalysis) {
        importAnalysis.updatedProducts.map { it.copy(newProduct = it.newProduct.copy()) }.toMutableStateList()
    }

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
            val exported = ErrorExporter.exportErrorsToXlsx(importAnalysis.errors, context, it)
            val message = if (exported) errorFileExportedText else errorExportFailedText
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        if (!isApplying) {
            onClose()
        }
    }

    if (itemToEdit != null) {
        val (index, product) = itemToEdit!!
        EditProductDialog(
            product = product,
            viewModel = databaseViewModel,
            onResolveSupplierId = { databaseViewModel.resolveImportPreviewSupplierId(it) },
            onResolveCategoryId = { databaseViewModel.resolveImportPreviewCategoryId(it) },
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
            onResolveSupplierId = { databaseViewModel.resolveImportPreviewSupplierId(it) },
            onResolveCategoryId = { databaseViewModel.resolveImportPreviewCategoryId(it) },
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
                    .padding(spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(spacing.md, Alignment.End)
            ) {
                Button(
                    onClick = {
                        previewId?.let {
                            onConfirm(it, editableNewProducts, editableUpdatedProducts)
                        }
                    },
                    enabled = !isApplying &&
                        previewId != null &&
                        (editableNewProducts.isNotEmpty() || editableUpdatedProducts.isNotEmpty())
                ) { Text(stringResource(R.string.confirm_import)) }
                OutlinedButton(
                    onClick = onClose,
                    enabled = !isApplying
                ) { Text(closeActionText) }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.preview_file_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.import_preview_not_saved_message),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        AnimatedVisibility(visible = isApplying) {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = stringResource(R.string.import_applying_changes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        importErrorMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            if (importAnalysis.warnings.isNotEmpty()) {
                item {
                    ExpandableSection(
                        title = stringResource(
                            R.string.duplicate_warnings_found,
                            formatClCount(importAnalysis.warnings.size)
                        ),
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
                    title = stringResource(
                        R.string.new_products_to_add,
                        formatClCount(editableNewProducts.size)
                    ),
                    isExpanded = newProductsExpanded,
                    onToggle = { newProductsExpanded = !newProductsExpanded }
                ) {
                    if (editableNewProducts.isEmpty()) {
                        Text(stringResource(R.string.no_new_products), modifier = Modifier.padding(spacing.md))
                    }
                }
            }
            if (newProductsExpanded && editableNewProducts.isNotEmpty()) {
                    itemsIndexed(editableNewProducts, key = { index, p -> "new-${p.barcode}-$index" }) { index, product ->
                        DisplayProductRow(
                            product = product,
                            databaseViewModel = databaseViewModel,
                            editEnabled = !isApplying,
                            onEditClick = { itemToEdit = index to product }
                        )
                    }
                }

            item {
                ExpandableSection(
                    title = stringResource(
                        R.string.products_to_update,
                        formatClCount(editableUpdatedProducts.size)
                    ),
                    isExpanded = updatedProductsExpanded,
                    onToggle = { updatedProductsExpanded = !updatedProductsExpanded }
                ) {
                    if (editableUpdatedProducts.isEmpty()) {
                        Text(stringResource(R.string.no_products_to_update), modifier = Modifier.padding(spacing.md))
                    }
                }
            }
            if (updatedProductsExpanded && editableUpdatedProducts.isNotEmpty()) {
                    itemsIndexed(editableUpdatedProducts, key = { index, u -> "update-${u.oldProduct.id}-$index" }) { index, update ->
                        DisplayProductUpdateRow(
                            productUpdate = update,
                            databaseViewModel = databaseViewModel,
                            editEnabled = !isApplying,
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
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            val exportErrorsFilename = stringResource(R.string.default_error_export_filename)
                            Button(
                                onClick = { exportErrorsLauncher.launch(exportErrorsFilename) },
                                enabled = !isApplying
                            ) {
                                Text(stringResource(R.string.export_errors))
                            }
                            OutlinedButton(onClick = {
                                excelViewModel.errorRowIndexes.value = importAnalysis.errors.map { it.rowNumber }.toSet()
                                onClose()
                            }, enabled = !isApplying) {
                                Text(stringResource(R.string.correct_errors))
                            }
                        }
                    } else {
                        Text(stringResource(R.string.no_critical_errors_found), modifier = Modifier.padding(spacing.md))
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
    val spacing = MaterialTheme.appSpacing
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.padding(bottom = spacing.sm)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs)
        ) {
            Text(
                text = stringResource(R.string.warning_duplicate_barcode, warning.barcode),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = spacing.xxs),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
            )
            Text(
                text = stringResource(R.string.warning_duplicate_found_in_rows, warning.rowNumbers.joinToString(", ")),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            if (warning.totalOccurrences > warning.rowNumbers.size) {
                Text(
                    text = stringResource(
                        R.string.warning_duplicate_rows_truncated,
                        warning.totalOccurrences - warning.rowNumbers.size,
                        warning.totalOccurrences
                    ),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
    databaseViewModel: DatabaseViewModel,
    editEnabled: Boolean,
    onEditClick: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val noSupplierText = stringResource(R.string.no_supplier)
    val noCategoryText = stringResource(R.string.no_category)
    var supplierName by remember { mutableStateOf<String?>(null) }
    var categoryName by remember { mutableStateOf<String?>(null) }
    val loadingEllipsisText = stringResource(R.string.loading_ellipsis)
    val notFoundShortText = stringResource(R.string.not_found_short)

    LaunchedEffect(product.supplierId) {
        if (product.supplierId == null) {
            supplierName = noSupplierText
        } else {
            supplierName = loadingEllipsisText
            supplierName = databaseViewModel.getSupplierDisplayName(product.supplierId) ?: notFoundShortText
        }
    }

    LaunchedEffect(product.categoryId) {
        if (product.categoryId == null) {
            categoryName = noCategoryText
        } else {
            categoryName = loadingEllipsisText
            categoryName = databaseViewModel.getCategoryDisplayName(product.categoryId) ?: notFoundShortText
        }
    }

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
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

                Spacer(Modifier.height(spacing.xxs))
                Text("${stringResource(R.string.barcode_prefix)} ${product.barcode}", style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(R.string.item_number_prefix)} ${product.itemNumber ?: "-"}", style = MaterialTheme.typography.bodySmall)

                Text("${stringResource(R.string.supplier_label)}: ${supplierName ?: noSupplierText}", style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(R.string.category_label)}: ${categoryName ?: noCategoryText}", style = MaterialTheme.typography.bodySmall)

                Text("${stringResource(R.string.counted_quantity_label)}: ${formatClQuantityDisplayReadOnly(product.stockQuantity)}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${stringResource(R.string.purchase_prefix)} ${formatClPricePlainDisplay(product.purchasePrice)}", style = MaterialTheme.typography.bodyMedium)
                Text("${stringResource(R.string.sell_prefix)} ${formatClPricePlainDisplay(product.retailPrice)}", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onEditClick, enabled = editEnabled) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_product))
            }
        }
    }
}

@Composable
private fun DisplayProductUpdateRow(
    productUpdate: ProductUpdate,
    databaseViewModel: DatabaseViewModel,
    editEnabled: Boolean,
    onEditClick: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(productUpdate.oldProduct.productName ?: stringResource(R.string.unnamed_product), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEditClick, enabled = editEnabled) {
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
            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xxs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.compare_field),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = stringResource(R.string.compare_previous),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.compare_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            productUpdate.changedFields.forEach { fieldResId ->
                CompareRow(
                    fieldResId = fieldResId,
                    old = productUpdate.oldProduct,
                    new = productUpdate.newProduct,
                    databaseViewModel = databaseViewModel
                )
            }
        }
    }
}

@Composable
private fun CompareRow(
    fieldResId: Int,
    old: Product,
    new: Product,
    databaseViewModel: DatabaseViewModel
) {
    val spacing = MaterialTheme.appSpacing
    val noSupplierText = stringResource(R.string.no_supplier)
    val noCategoryText = stringResource(R.string.no_category)
    val supplierIdPrefix = stringResource(R.string.supplier_id_prefix)
    val categoryIdPrefix = stringResource(R.string.category_id_prefix)

    val (oldValue, newValue) = when (fieldResId) {
        R.string.field_product_name -> old.productName to new.productName
        R.string.field_second_product_name -> old.secondProductName to new.secondProductName
        R.string.header_item_number -> old.itemNumber to new.itemNumber
        R.string.purchase_price_label -> formatClPricePlainDisplay(old.purchasePrice) to formatClPricePlainDisplay(new.purchasePrice)
        R.string.retail_price_label -> formatClPricePlainDisplay(old.retailPrice) to formatClPricePlainDisplay(new.retailPrice)
        R.string.field_supplier -> {
            val oldSupplier = rememberRelationDisplayValue(
                relationId = old.supplierId,
                emptyValue = noSupplierText,
                technicalIdPrefix = supplierIdPrefix,
                resolveName = { databaseViewModel.getSupplierDisplayName(it) }
            )
            val newSupplier = rememberRelationDisplayValue(
                relationId = new.supplierId,
                emptyValue = noSupplierText,
                technicalIdPrefix = supplierIdPrefix,
                resolveName = { databaseViewModel.getSupplierDisplayName(it) }
            )
            oldSupplier to newSupplier
        }
        R.string.field_category -> {
            val oldCategory = rememberRelationDisplayValue(
                relationId = old.categoryId,
                emptyValue = noCategoryText,
                technicalIdPrefix = categoryIdPrefix,
                resolveName = { databaseViewModel.getCategoryDisplayName(it) }
            )
            val newCategory = rememberRelationDisplayValue(
                relationId = new.categoryId,
                emptyValue = noCategoryText,
                technicalIdPrefix = categoryIdPrefix,
                resolveName = { databaseViewModel.getCategoryDisplayName(it) }
            )
            oldCategory to newCategory
        }
        R.string.field_stock_quantity -> formatClQuantityDisplayReadOnly(old.stockQuantity) to formatClQuantityDisplayReadOnly(new.stockQuantity)
        else -> "" to ""
    }

    val fieldName = stringResource(fieldResId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xxs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Text(
            text = fieldName,
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = oldValue ?: "-",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = newValue ?: "-",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun rememberRelationDisplayValue(
    relationId: Long?,
    emptyValue: String,
    technicalIdPrefix: String,
    resolveName: suspend (Long?) -> String?
): String {
    val loadingEllipsisText = stringResource(R.string.loading_ellipsis)
    val notFoundShortText = stringResource(R.string.not_found_short)
    val displayValue by produceState(
        initialValue = if (relationId == null) emptyValue else loadingEllipsisText,
        relationId,
        emptyValue,
        technicalIdPrefix
    ) {
        value = if (relationId == null) {
            emptyValue
        } else {
            resolveName(relationId) ?: "$notFoundShortText ($technicalIdPrefix $relationId)"
        }
    }
    return displayValue
}

@Composable
private fun ExpandableSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(modifier = Modifier.padding(vertical = spacing.xxs)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = spacing.sm),
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
    val spacing = MaterialTheme.appSpacing
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.md)) {
            val errorReasonText = stringResource(error.errorReasonResId, *error.formatArgs.toTypedArray())
            Text("${stringResource(R.string.row_prefix)} ${error.rowNumber}: $errorReasonText", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f))

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
            Spacer(Modifier.height(spacing.xxs))
            ErrorDetailText(label = stringResource(R.string.counted_quantity_label), value = quantity, isHighlighted = problematicKey == "quantity")
            ErrorDetailText(label = stringResource(R.string.new_retail_price_short_label), value = retailPrice, isHighlighted = problematicKey == "RetailPrice")
        }
    }
}

@Composable
private fun ErrorDetailText(label: String, value: String, isHighlighted: Boolean) {
    val spacing = MaterialTheme.appSpacing
    val rowModifier = if (isHighlighted) {
        Modifier
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                shape = MaterialTheme.shapes.extraSmall
            )
            .padding(horizontal = spacing.xs, vertical = 2.dp)
    } else {
        Modifier
    }
    Row(modifier = rowModifier) {
        Text(text = "$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

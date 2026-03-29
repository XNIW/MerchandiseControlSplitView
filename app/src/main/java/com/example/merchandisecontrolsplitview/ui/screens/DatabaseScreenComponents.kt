package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseScreenTopBar(
    onNavigateBack: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    exportEnabled: Boolean
) {
    TopAppBar(
        title = { Text(stringResource(R.string.database_screen_title)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(onClick = onImportClick) {
                Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.import_file))
            }

            Box {
                IconButton(
                    enabled = exportEnabled,
                    onClick = onExportClick
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.export_file))
                }
            }
        }
    )
}

@Composable
internal fun DatabaseScreenFabColumn(
    onScan: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.End
    ) {
        FloatingActionButton(onClick = onScan) {
            Icon(Icons.Filled.CameraAlt, contentDescription = stringResource(R.string.scan_barcode))
        }
        FloatingActionButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_product))
        }
    }
}

@Composable
internal fun DatabaseProductListSection(
    filter: String?,
    products: LazyPagingItems<ProductWithDetails>,
    onFilterChange: (String) -> Unit,
    onClearFilter: () -> Unit,
    onProductClick: (Product) -> Unit,
    onDeleteRequest: (Product) -> Unit,
    onShowHistory: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = filter ?: "",
            onValueChange = onFilterChange,
            label = { Text(stringResource(R.string.barcode_filter_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            singleLine = true,
            trailingIcon = {
                if (filter?.isNotEmpty() == true) {
                    IconButton(onClick = onClearFilter) {
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
                            text = if (filter.isNullOrEmpty()) stringResource(R.string.no_products_in_db) else stringResource(R.string.no_results_for, filter),
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
                    items(products.itemCount, key = { idx -> products[idx]?.product?.id ?: "placeholder-$idx" }) { idx ->
                        products[idx]?.let { details ->
                            val product = details.product

                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        onDeleteRequest(product)
                                        false
                                    } else {
                                        false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                enableDismissFromEndToStart = true,
                                backgroundContent = { DismissBackground(dismissState) },
                                content = {
                                    ProductRow(
                                        productDetails = details,
                                        onClick = { onProductClick(product) },
                                        onShowHistory = { onShowHistory(product) }
                                    )
                                }
                            )
                        }
                    }

                    if (loadState.append is LoadState.Loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                        }
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
internal fun ProductRow(
    productDetails: ProductWithDetails,
    onClick: () -> Unit,
    onShowHistory: () -> Unit = {}
) {
    val product = productDetails.product
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
                Text(text = productDetails.supplierName ?: "-")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (productDetails.categoryName != null) {
                    Row {
                        Text(text = "${stringResource(R.string.header_category)}: ")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DismissBackground(state: SwipeToDismissBoxState) {
    val color =
        if (state.targetValue == SwipeToDismissBoxValue.EndToStart)
            MaterialTheme.colorScheme.errorContainer
        else
            Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = stringResource(R.string.delete),
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

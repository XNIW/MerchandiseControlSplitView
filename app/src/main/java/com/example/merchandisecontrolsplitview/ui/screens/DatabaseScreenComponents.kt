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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityDisplayReadOnly

private val DatabaseListContentPadding = PaddingValues(
    start = 20.dp,
    top = 8.dp,
    end = 20.dp,
    bottom = 152.dp
)

@Composable
internal fun DatabaseRootHeader(
    filter: String,
    onFilterChange: (String) -> Unit,
    onClearFilter: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    exportEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.database),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.database_screen_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = onImportClick) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = stringResource(R.string.import_file)
                    )
                }
                FilledTonalIconButton(
                    enabled = exportEnabled,
                    onClick = onExportClick
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.export_file)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilterChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.barcode_filter_label))
                },
                trailingIcon = {
                    if (filter.isNotEmpty()) {
                        TextButton(onClick = onClearFilter) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }
            )
        }
    }
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
    filter: String,
    products: LazyPagingItems<ProductWithDetails>,
    onProductClick: (Product) -> Unit,
    onDeleteRequest: (Product) -> Unit,
    onShowHistory: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    val loadState = products.loadState
    Box(modifier = modifier.fillMaxSize()) {
        if (loadState.refresh is LoadState.Loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (products.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = stringResource(R.string.no_products_found),
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (filter.isEmpty()) {
                            stringResource(R.string.no_products_in_db)
                        } else {
                            stringResource(R.string.no_results_for, filter)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    if (filter.isEmpty()) {
                        Text(
                            text = stringResource(R.string.add_first_product_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = DatabaseListContentPadding,
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

@Composable
private fun PriceColumn(
    labelNew: String,
    currentPriceValue: Double?,
    priceNew: String?,
    labelOld: String,
    priceOldValue: Double?,
    horizontalAlignment: Alignment.Horizontal
) {
    val visibleOldPrice = priceOldValue?.takeIf { shouldShowPreviousPrice(currentPriceValue, it) }

    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            text = labelNew,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = priceNew ?: "-",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (visibleOldPrice != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = labelOld,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatClPricePlainDisplay(visibleOldPrice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough
            )
        }
    }
}

private fun shouldShowPreviousPrice(
    currentPriceValue: Double?,
    previousPriceValue: Double
): Boolean = currentPriceValue == null || currentPriceValue.compareTo(previousPriceValue) != 0

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val currentPurchasePrice = product.purchasePrice ?: productDetails.lastPurchase
                val currentRetailPrice = product.retailPrice ?: productDetails.lastRetail

                PriceColumn(
                    labelNew = stringResource(R.string.product_purchase_price_new_short),
                    currentPriceValue = currentPurchasePrice,
                    priceNew = formatClPricePlainDisplay(currentPurchasePrice),
                    labelOld = stringResource(R.string.product_purchase_price_old_short),
                    priceOldValue = productDetails.prevPurchase,
                    horizontalAlignment = Alignment.Start
                )
                PriceColumn(
                    labelNew = stringResource(R.string.product_retail_price_new_short),
                    currentPriceValue = currentRetailPrice,
                    priceNew = formatClPricePlainDisplay(currentRetailPrice),
                    labelOld = stringResource(R.string.product_retail_price_old_short),
                    priceOldValue = productDetails.prevRetail,
                    horizontalAlignment = Alignment.End
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(R.string.product_supplier_full),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = productDetails.supplierName ?: "-",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (productDetails.categoryName != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = stringResource(R.string.header_category),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = productDetails.categoryName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row {
                        Text(
                            text = "${stringResource(R.string.header_stock_quantity)}: ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatClQuantityDisplayReadOnly(product.stockQuantity),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(onClick = onShowHistory),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.price_history),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
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

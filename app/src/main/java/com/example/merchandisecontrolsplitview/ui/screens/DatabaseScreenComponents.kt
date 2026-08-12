package com.example.merchandisecontrolsplitview.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image as ProductImageIcon
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.CatalogListItem
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityDisplayReadOnly
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSectionUiState
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseHubTab
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiKey
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.Crossfade

private val DatabaseListContentPadding = PaddingValues(
    start = 20.dp,
    top = 8.dp,
    end = 20.dp,
    bottom = 152.dp
)

internal enum class ProductPagingPrimaryState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
}

internal data class ProductPagingPresentation(
    val primaryState: ProductPagingPrimaryState,
    val showRefreshError: Boolean,
    val showAppendError: Boolean
)

internal fun productPagingPresentation(
    itemCount: Int,
    refreshState: LoadState,
    appendState: LoadState
): ProductPagingPresentation {
    val primaryState = when {
        itemCount > 0 -> ProductPagingPrimaryState.CONTENT
        refreshState is LoadState.Loading -> ProductPagingPrimaryState.LOADING
        refreshState is LoadState.Error -> ProductPagingPrimaryState.ERROR
        else -> ProductPagingPrimaryState.EMPTY
    }
    return ProductPagingPresentation(
        primaryState = primaryState,
        showRefreshError = itemCount > 0 && refreshState is LoadState.Error,
        showAppendError = itemCount > 0 && appendState is LoadState.Error
    )
}

@Composable
internal fun DatabaseRootHeader(
    selectedTab: DatabaseHubTab,
    onTabSelected: (DatabaseHubTab) -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    exportEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.database),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

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

        SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            DatabaseHubTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            text = stringResource(tab.labelRes()),
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@Composable
internal fun DatabaseSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(placeholder)
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.clear))
                }
            }
        }
    )
}

@Composable
internal fun DatabaseHubFab(
    selectedTab: DatabaseHubTab,
    onScan: () -> Unit,
    onAddProduct: () -> Unit,
    onAddCatalog: (CatalogEntityKind) -> Unit,
    modifier: Modifier = Modifier
) {
    when (selectedTab) {
        DatabaseHubTab.PRODUCTS -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                FloatingActionButton(onClick = onScan) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = stringResource(R.string.scan_barcode))
                }
                FloatingActionButton(onClick = onAddProduct) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_product))
                }
            }
        }

        DatabaseHubTab.SUPPLIERS,
        DatabaseHubTab.CATEGORIES -> {
            val kind = selectedTab.catalogKind ?: return
            ExtendedFloatingActionButton(
                onClick = { onAddCatalog(kind) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        text = stringResource(
                            R.string.database_catalog_add_fab,
                            stringResource(kind.entityLabelRes())
                        )
                    )
                },
                modifier = modifier
            )
        }
    }
}

@Composable
internal fun DatabaseCatalogListSection(
    kind: CatalogEntityKind,
    sectionState: CatalogSectionUiState,
    listState: LazyListState,
    onItemClick: (CatalogListItem) -> Unit,
    onRetry: () -> Unit,
    onQuickCreate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val trimmedQuery = sectionState.query.trim()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            sectionState.isLoading && sectionState.items.isEmpty() -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            sectionState.errorMessage != null -> {
                DatabaseCatalogFeedbackState(
                    icon = Icons.Default.SearchOff,
                    title = sectionState.errorMessage,
                    body = stringResource(R.string.database_catalog_retry_body),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                    announce = true,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            sectionState.items.isEmpty() && trimmedQuery.isNotEmpty() -> {
                DatabaseCatalogFeedbackState(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(R.string.no_results_for, trimmedQuery),
                    body = stringResource(
                        R.string.database_catalog_no_results_body,
                        stringResource(kind.entityLabelRes())
                    ),
                    actionLabel = stringResource(
                        R.string.database_catalog_quick_create,
                        trimmedQuery
                    ),
                    onAction = { onQuickCreate(trimmedQuery) },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            sectionState.items.isEmpty() -> {
                DatabaseCatalogFeedbackState(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(
                        R.string.database_catalog_empty_title,
                        stringResource(kind.entityLabelRes())
                    ),
                    body = stringResource(
                        R.string.database_catalog_empty_body,
                        stringResource(kind.entityLabelRes())
                    ),
                    actionLabel = stringResource(
                        R.string.database_catalog_add_first,
                        stringResource(kind.entityLabelRes())
                    ),
                    onAction = { onQuickCreate("") },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = DatabaseListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        count = sectionState.items.size,
                        key = { index -> sectionState.items[index].id }
                    ) { index ->
                        DatabaseCatalogRow(
                            item = sectionState.items[index],
                            onClick = { onItemClick(sectionState.items[index]) }
                        )
                    }
                }

                if (sectionState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun DatabaseCatalogFeedbackState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    announce: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .then(
                if (announce) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun DatabaseCatalogRow(
    item: CatalogListItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.database_catalog_linked_products,
                            item.productCount,
                            item.productCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.database_catalog_manage_actions),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun DatabaseProductListSection(
    filter: String,
    products: LazyPagingItems<ProductWithDetails>,
    listState: LazyListState,
    onProductClick: (Product) -> Unit,
    onProductImageClick: (Product) -> Unit = {},
    onDeleteRequest: (Product) -> Unit,
    onShowHistory: (Product) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    productDetailsOverrides: Map<Long, ProductWithDetails> = emptyMap(),
    productImageStates: Map<ProductImageUiKey, ProductImageUiState> = emptyMap(),
    onProductImageVisibilityChanged: (Product, Boolean) -> Unit = { _, _ -> }
) {
    val loadState = products.loadState
    val isRefreshing = loadState.refresh is LoadState.Loading
    val presentation = productPagingPresentation(
        itemCount = products.itemCount,
        refreshState = loadState.refresh,
        appendState = loadState.append
    )
    Box(modifier = modifier.fillMaxSize()) {
        if (presentation.primaryState == ProductPagingPrimaryState.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else if (presentation.primaryState == ProductPagingPrimaryState.ERROR) {
            DatabaseCatalogFeedbackState(
                icon = Icons.Default.SearchOff,
                title = stringResource(R.string.database_products_load_failed_title),
                body = stringResource(R.string.database_products_load_failed_body),
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
                announce = true,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (presentation.primaryState == ProductPagingPrimaryState.EMPTY) {
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
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = DatabaseListContentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (presentation.showRefreshError) {
                    item(key = "refresh-error") {
                        ProductPagingErrorCard(
                            message = stringResource(R.string.database_products_refresh_failed),
                            onRetry = onRetry
                        )
                    }
                }

                items(products.itemCount, key = { idx -> products[idx]?.product?.id ?: "placeholder-$idx" }) { idx ->
                    products[idx]?.let { details ->
                        val effectiveDetails = productDetailsOverrides[details.product.id] ?: details
                        val currentProduct = effectiveDetails.productWithCurrentPrices()

                        @Suppress("DEPRECATION")
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onDeleteRequest(currentProduct)
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
                                    productDetails = effectiveDetails,
                                    imageState = productImageStates[
                                        ProductImageUiKey(
                                            currentProduct.id,
                                            ProductImageVariant.THUMB
                                        )
                                    ],
                                    onImageVisibilityChanged = { visible ->
                                        onProductImageVisibilityChanged(currentProduct, visible)
                                    },
                                    onImageClick = { onProductImageClick(currentProduct) },
                                    onClick = { onProductClick(currentProduct) },
                                    onShowHistory = { onShowHistory(currentProduct) }
                                )
                            }
                        )
                    }
                }

                if (loadState.append is LoadState.Loading) {
                    item(key = "append-loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }

                if (presentation.showAppendError) {
                    item(key = "append-error") {
                        ProductPagingErrorCard(
                            message = stringResource(R.string.database_products_append_failed),
                            onRetry = onRetry
                        )
                    }
                }
            }

            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun ProductPagingErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
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
    horizontalAlignment: Alignment.Horizontal,
    modifier: Modifier = Modifier
) {
    val visibleOldPrice = priceOldValue?.takeIf {
        shouldShowOldPrice(oldPrice = it, currentPrice = currentPriceValue)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment
    ) {
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

private fun shouldShowOldPrice(
    oldPrice: Double?,
    currentPrice: Double?
): Boolean {
    val old = oldPrice ?: return false
    if (old.isZeroPriceForDisplay()) return false
    return currentPrice == null || !currentPrice.matchesPriceForDisplay(old)
}

private fun Double.isZeroPriceForDisplay(): Boolean =
    this == 0.0 || formatClPricePlainDisplay(this) == formatClPricePlainDisplay(0.0)

private fun Double.matchesPriceForDisplay(other: Double): Boolean =
    compareTo(other) == 0 || formatClPricePlainDisplay(this) == formatClPricePlainDisplay(other)

@Composable
private fun DatabaseHubTab.labelRes(): Int = when (this) {
    DatabaseHubTab.PRODUCTS -> R.string.database_tab_products
    DatabaseHubTab.SUPPLIERS -> R.string.database_tab_suppliers
    DatabaseHubTab.CATEGORIES -> R.string.database_tab_categories
}

private fun CatalogEntityKind.entityLabelRes(): Int = when (this) {
    CatalogEntityKind.SUPPLIER -> R.string.database_catalog_entity_supplier
    CatalogEntityKind.CATEGORY -> R.string.database_catalog_entity_category
}

private fun CatalogEntityKind.searchHintRes(): Int = when (this) {
    CatalogEntityKind.SUPPLIER -> R.string.database_suppliers_search_hint
    CatalogEntityKind.CATEGORY -> R.string.database_categories_search_hint
}


@Composable
internal fun ProductRow(
    productDetails: ProductWithDetails,
    imageState: ProductImageUiState? = null,
    onImageVisibilityChanged: (Boolean) -> Unit = {},
    onImageClick: () -> Unit = {},
    onClick: () -> Unit,
    onShowHistory: () -> Unit = {}
) {
    val product = productDetails.product
    DisposableEffect(product.id, product.primaryImageVersionId) {
        onImageVisibilityChanged(true)
        onDispose { onImageVisibilityChanged(false) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProductImagePreview(
                state = imageState,
                contentDescription = stringResource(R.string.product_image_thumbnail),
                modifier = Modifier
                    .size(80.dp)
                    .clickable(onClick = onImageClick)
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column {
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
                Text(
                    text = "${stringResource(R.string.barcode_prefix)} ${product.barcode}  |  ${stringResource(R.string.item_number_prefix)} ${product.itemNumber ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                val currentPurchasePrice = productDetails.currentPurchasePrice
                val currentRetailPrice = productDetails.currentRetailPrice

                PriceColumn(
                    labelNew = stringResource(R.string.product_purchase_price_new_short),
                    currentPriceValue = currentPurchasePrice,
                    priceNew = formatClPricePlainDisplay(currentPurchasePrice),
                    labelOld = stringResource(R.string.product_purchase_price_old_short),
                    priceOldValue = productDetails.prevPurchase,
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(1f)
                )
                PriceColumn(
                    labelNew = stringResource(R.string.product_retail_price_new_short),
                    currentPriceValue = currentRetailPrice,
                    priceNew = formatClPricePlainDisplay(currentRetailPrice),
                    labelOld = stringResource(R.string.product_retail_price_old_short),
                    priceOldValue = productDetails.prevRetail,
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1f)
                )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
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
                            style = MaterialTheme.typography.bodyMedium
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
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(R.string.header_stock_quantity),
                            style = MaterialTheme.typography.labelSmall,
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
}

@Composable
internal fun ProductImagePreview(
    state: ProductImageUiState?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val bytes = state?.bytes
    val semanticState = when {
        state?.status == ProductImageUiStatus.ERROR ->
            stringResource(R.string.product_image_state_error)
        state?.status in setOf(
            ProductImageUiStatus.LOADING,
            ProductImageUiStatus.UPLOADING,
            ProductImageUiStatus.REMOVING
        ) -> stringResource(R.string.product_image_state_loading)
        bytes != null -> stringResource(R.string.product_image_state_ready)
        else -> stringResource(R.string.product_image_state_empty)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { stateDescription = semanticState },
        contentAlignment = Alignment.Center
    ) {
        if (bytes != null) {
            Crossfade(targetState = bytes, label = "product-image") { imageBytes ->
                DecodedProductImage(
                    bytes = imageBytes,
                    contentDescription = contentDescription,
                    contentScale = contentScale
                )
            }
        } else {
            Icon(
                imageVector = if (state?.status == ProductImageUiStatus.ERROR) {
                    Icons.Default.BrokenImage
                } else {
                    Icons.Default.ProductImageIcon
                },
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
        if (state?.status in setOf(
                ProductImageUiStatus.LOADING,
                ProductImageUiStatus.UPLOADING,
                ProductImageUiStatus.REMOVING
            )
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
internal fun ProductImageFullscreenDialog(
    state: ProductImageUiState?,
    contentDescription: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val imageKey = state?.bytes?.contentHashCode() ?: state?.versionId
    var scale by remember(imageKey) { mutableFloatStateOf(1f) }
    var offsetX by remember(imageKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(imageKey) { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    fun updateScale(requestedScale: Float, panX: Float = 0f, panY: Float = 0f) {
        val nextScale = requestedScale.coerceIn(1f, 5f)
        scale = nextScale
        if (nextScale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            val maxX = viewportWidth * (nextScale - 1f) / 2f
            val maxY = viewportHeight * (nextScale - 1f) / 2f
            offsetX = (offsetX + panX).coerceIn(-maxX, maxX)
            offsetY = (offsetY + panY).coerceIn(-maxY, maxY)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics { stateDescription = contentDescription },
            contentAlignment = Alignment.Center
        ) {
            ProductImagePreview(
                state = state,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        viewportWidth = size.width.toFloat()
                        viewportHeight = size.height.toFloat()
                    }
                    .pointerInput(imageKey) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            updateScale(scale * zoom, pan.x, pan.y)
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = state?.bytes != null && scale > 1f,
                    onClick = { updateScale(scale - 0.5f) }
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOut,
                        contentDescription = stringResource(R.string.product_image_zoom_out),
                        tint = Color.White
                    )
                }
                IconButton(
                    enabled = state?.bytes != null && scale < 5f,
                    onClick = { updateScale(scale + 0.5f) }
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = stringResource(R.string.product_image_zoom_in),
                        tint = Color.White
                    )
                }
                if (state?.status == ProductImageUiStatus.ERROR && onRetry != null) {
                    TextButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.retry), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun DecodedProductImage(
    bytes: ByteArray,
    contentDescription: String,
    contentScale: ContentScale
) {
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, bytes) {
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val bitmap = bitmapState.value
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )
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

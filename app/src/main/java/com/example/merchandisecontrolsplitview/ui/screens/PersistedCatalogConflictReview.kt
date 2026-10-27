package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.PersistedCatalogConflictChoice
import com.example.merchandisecontrolsplitview.data.PersistedCatalogConflictContext
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityDisplayReadOnly

/** Review of an already durable catalog revision. Decisions always use the repository command. */
@Composable
internal fun PersistedCatalogConflictReview(
    context: PersistedCatalogConflictContext?,
    loading: Boolean,
    resolving: Boolean,
    resolved: Boolean,
    requiresRefresh: Boolean,
    message: String?,
    suppliers: List<Supplier>,
    categories: List<Category>,
    onRefresh: () -> Unit,
    onResolve: (PersistedCatalogConflictChoice) -> Unit,
    onDismiss: () -> Unit
) {
    // A changed revision retains the user's proposal, but never applies it automatically.
    var proposals by remember(context?.productId) { mutableStateOf(emptyMap<String, Product>()) }
    val fields = context?.conflictingFields.orEmpty().let { disputed ->
        catalogReviewFieldKeys.filter { it in disputed || "__all__" in disputed }
    }
    val selectedCurrentFields = proposals.keys intersect fields.toSet()
    val busy = loading || resolving
    val canResolve = context?.canResolve == true && !busy && !resolved && !requiresRefresh
    if (com.example.merchandisecontrolsplitview.ui.LocalBusinessRouteVisible.current) AlertDialog(
        modifier = Modifier.testTag("catalog-conflict-review"),
        onDismissRequest = { if (!resolving) onDismiss() },
        title = { Text(stringResource(R.string.catalog_conflict_review_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!resolved) Text(stringResource(R.string.catalog_conflict_saved_local))
                context?.takeUnless { resolved }?.let { current ->
                    Text(current.local.barcode, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.catalog_conflict_verified_versions))
                    if (current.remoteDeleted) {
                        Text(stringResource(R.string.catalog_conflict_remote_deleted))
                    } else if (current.remote != null) {
                        fields.forEach { field ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        modifier = Modifier.testTag("catalog-conflict-field-$field"),
                                        checked = field in proposals,
                                        enabled = canResolve,
                                        onCheckedChange = { checked ->
                                            proposals = if (checked) proposals + (field to current.local) else proposals - field
                                        }
                                    )
                                    Text(stringResource(catalogReviewFieldLabel(field)))
                                }
                                Text(stringResource(R.string.catalog_conflict_local_value,
                                    catalogReviewFieldValue(current.local, field, suppliers, categories)))
                                Text(stringResource(R.string.catalog_conflict_remote_value,
                                    catalogReviewFieldValue(current.remote, field, suppliers, categories)))
                                proposals[field]?.let { proposed ->
                                    Text(stringResource(R.string.catalog_conflict_previous_choice,
                                        catalogReviewFieldValue(proposed, field, suppliers, categories)))
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.catalog_conflict_preserve_price_history))
                    if (!current.canResolve && message == null) {
                        Text(stringResource(R.string.catalog_conflict_blocked))
                    }
                }
                message?.let { Text(it, modifier = Modifier.testTag("catalog-conflict-message")) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!resolved && context?.remoteDeleted == true) {
                    TextButton(
                        modifier = Modifier.testTag("catalog-conflict-adopt-tombstone"),
                        enabled = canResolve,
                        onClick = { onResolve(PersistedCatalogConflictChoice.AdoptRemoteTombstone) }
                    ) { Text(stringResource(R.string.catalog_conflict_accept_remote_deletion)) }
                } else if (!resolved && context?.remote != null) {
                    TextButton(
                        modifier = Modifier.testTag("catalog-conflict-adopt-remote"),
                        enabled = canResolve,
                        onClick = { onResolve(PersistedCatalogConflictChoice.AdoptRemote) }
                    ) { Text(stringResource(R.string.catalog_conflict_adopt_remote)) }
                    TextButton(
                        modifier = Modifier.testTag("catalog-conflict-apply-selected"),
                        enabled = canResolve && selectedCurrentFields.isNotEmpty(),
                        onClick = {
                            onResolve(PersistedCatalogConflictChoice.SelectedFields(selectedCurrentFields,
                                selectedCurrentFields.fold(requireNotNull(context.remote)) { base, field ->
                                    selectCatalogReviewFields(base, requireNotNull(proposals[field]), setOf(field))
                                }))
                        }
                    ) { Text(stringResource(R.string.catalog_conflict_keep_selected)) }
                }
                if (!resolved) TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(stringResource(R.string.catalog_conflict_refresh_comparison))
                }
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("catalog-conflict-dismiss"),
                enabled = !resolving,
                onClick = onDismiss
            ) { Text(stringResource(if (resolved) R.string.close else R.string.catalog_conflict_postpone)) }
        }
    )
}

internal val catalogReviewFieldKeys = listOf("barcode", "itemnumber", "productname", "secondproductname",
    "purchaseprice", "retailprice", "supplier", "category", "stockquantity")

internal fun catalogReviewFieldLabel(field: String): Int = when (field) {
    "barcode" -> R.string.barcode_label
    "itemnumber" -> R.string.item_code_label
    "productname" -> R.string.product_name_label
    "secondproductname" -> R.string.second_product_name_label
    "purchaseprice" -> R.string.purchase_price_label
    "retailprice" -> R.string.retail_price_label
    "supplier" -> R.string.supplier_label
    "category" -> R.string.category_label
    else -> R.string.header_stock_quantity
}

@Composable
internal fun catalogReviewFieldValue(product: Product, field: String,
    suppliers: List<Supplier>, categories: List<Category>): String = when (field) {
    "barcode" -> product.barcode
    "itemnumber" -> product.itemNumber.orEmpty()
    "productname" -> product.productName.orEmpty()
    "secondproductname" -> product.secondProductName.orEmpty()
    "purchaseprice" -> formatClPricePlainDisplay(product.purchasePrice)
    "retailprice" -> formatClPricePlainDisplay(product.retailPrice)
    "stockquantity" -> formatClQuantityDisplayReadOnly(product.stockQuantity)
    "supplier" -> if (product.supplierId == null) stringResource(R.string.no_supplier) else
        suppliers.firstOrNull { it.id == product.supplierId }?.name ?: stringResource(R.string.catalog_conflict_relation_unavailable)
    "category" -> if (product.categoryId == null) stringResource(R.string.no_category) else
        categories.firstOrNull { it.id == product.categoryId }?.name ?: stringResource(R.string.catalog_conflict_relation_unavailable)
    else -> ""
}

/** Explicit field selection, not an automatic merge policy. Unselected values stay on the qualified base. */
internal fun selectCatalogReviewFields(base: Product, desired: Product, fields: Set<String>): Product = base.copy(
    barcode = if ("barcode" in fields) desired.barcode else base.barcode,
    itemNumber = if ("itemnumber" in fields) desired.itemNumber else base.itemNumber,
    productName = if ("productname" in fields) desired.productName else base.productName,
    secondProductName = if ("secondproductname" in fields) desired.secondProductName else base.secondProductName,
    purchasePrice = if ("purchaseprice" in fields) desired.purchasePrice else base.purchasePrice,
    retailPrice = if ("retailprice" in fields) desired.retailPrice else base.retailPrice,
    supplierId = if ("supplier" in fields) desired.supplierId else base.supplierId,
    categoryId = if ("category" in fields) desired.categoryId else base.categoryId,
    stockQuantity = if ("stockquantity" in fields) desired.stockQuantity else base.stockQuantity
)

package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.viewmodel.DraftProductConflictReviewState

/** Explicit draft preparation. The repository repeats comparison/fencing on the next Save. */
@Composable
internal fun DraftProductConflictReview(
    review: DraftProductConflictReviewState,
    suppliers: List<Supplier>,
    categories: List<Category>,
    onPrepare: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(review.sessionId, review.current, review.desired) {
        mutableStateOf(emptySet<String>())
    }
    val current = review.current
    val available = !review.loading && current != null && !review.missingTarget && review.message == null
    if (com.example.merchandisecontrolsplitview.ui.LocalBusinessRouteVisible.current) AlertDialog(
        modifier = Modifier.testTag("product-editor-draft-review"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.product_editor_review_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(review.desired.barcode, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.product_editor_review_explanation))
                if (review.loading) CircularProgressIndicator()
                review.message?.let { Text(it) }
                if (available) catalogReviewFieldKeys.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = field in selected,
                                modifier = Modifier.testTag("product-editor-draft-field-$field"),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + field else selected - field
                                })
                            Text(stringResource(catalogReviewFieldLabel(field)))
                        }
                        if (field in review.conflictingFields) Text(
                            stringResource(R.string.product_editor_disputed_field),
                            color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.product_editor_opening_value,
                            catalogReviewFieldValue(review.baseline, field, suppliers, categories)))
                        Text(stringResource(R.string.product_editor_draft_value,
                            catalogReviewFieldValue(review.desired, field, suppliers, categories)))
                        Text(stringResource(R.string.product_editor_current_value,
                            catalogReviewFieldValue(requireNotNull(current), field, suppliers, categories)))
                    }
                }
            }
        },
        confirmButton = {
            if (available) Column(horizontalAlignment = Alignment.End) {
                TextButton(modifier = Modifier.testTag("product-editor-draft-use-current"),
                    onClick = { onPrepare(emptySet()) }) {
                    Text(stringResource(R.string.product_editor_use_current))
                }
                TextButton(modifier = Modifier.testTag("product-editor-draft-use-selected"),
                    enabled = selected.isNotEmpty(), onClick = { onPrepare(selected) }) {
                    Text(stringResource(R.string.product_editor_prepare_selected))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("product-editor-draft-back")) {
                Text(stringResource(R.string.product_editor_return_to_edit))
            }
        }
    )
}

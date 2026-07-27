package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.ImportRowSource
import com.example.merchandisecontrolsplitview.data.RowImportError

private data class RedactedImportRow(
    val content: Map<String, String>,
    val redactedFields: Set<String>
)

/**
 * Creates a catalog-text import error without retaining prohibited raw text.
 *
 * Every cell is passed through the text policy. Catalog identity columns use the
 * strict policy; display columns and auxiliary cells use the display policy.
 * Rejected values are replaced by an empty value and tracked separately so UI
 * and XLSX export can show a localized marker.
 */
internal fun catalogTextRowError(
    context: Context,
    rowNumber: Int,
    row: Map<String, String>,
    field: CatalogTextField,
    reason: CatalogTextPolicy.RejectionReason,
    source: ImportRowSource = ImportRowSource.PRODUCTS
): RowImportError {
    val redacted = redactImportRow(row)
    return RowImportError(
        rowNumber = rowNumber,
        rowContent = redacted.content,
        errorReasonResId = R.string.error_catalog_text_rejected,
        formatArgs = listOf(
            context.getString(field.labelResource()),
            context.getString(reason.messageResource())
        ),
        redactedFields = redacted.redactedFields,
        source = source
    )
}

private fun redactImportRow(row: Map<String, String>): RedactedImportRow {
    val safeContent = linkedMapOf<String, String>()
    val redactedFields = linkedSetOf<String>()

    row.forEach { (key, raw) ->
        val outcome = when (key) {
            "barcode" -> CatalogTextPolicy.strict(
                raw = raw,
                required = false,
                maxLength = CatalogTextPolicy.Limits.BARCODE
            )
            "itemNumber" -> CatalogTextPolicy.strict(
                raw = raw,
                required = false,
                maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
            )
            "productName" -> CatalogTextPolicy.display(
                raw = raw,
                required = false,
                maxLength = CatalogTextPolicy.Limits.PRODUCT_NAME
            )
            "secondProductName" -> CatalogTextPolicy.display(
                raw = raw,
                required = false,
                maxLength = CatalogTextPolicy.Limits.SECOND_PRODUCT_NAME
            )
            "supplier" -> CatalogTextPolicy.display(
                raw = raw,
                required = false,
                maxLength = CatalogTextPolicy.Limits.SUPPLIER_NAME
            )
            "category" -> CatalogTextPolicy.display(
                raw = raw,
                required = false,
                maxLength = CatalogTextPolicy.Limits.CATEGORY_NAME
            )
            else -> CatalogTextPolicy.display(
                raw = raw,
                required = false,
                maxLength = Int.MAX_VALUE
            )
        }

        when (outcome) {
            is CatalogTextPolicy.Outcome.Unchanged -> safeContent[key] = outcome.value
            is CatalogTextPolicy.Outcome.Normalized -> safeContent[key] = outcome.value
            is CatalogTextPolicy.Outcome.Rejected -> {
                safeContent[key] = ""
                redactedFields += key
            }
        }
    }

    return RedactedImportRow(
        content = safeContent,
        redactedFields = redactedFields
    )
}

package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.R

internal fun CatalogTextField.labelResource(): Int = when (this) {
    CatalogTextField.BARCODE -> R.string.header_barcode
    CatalogTextField.ITEM_NUMBER -> R.string.header_item_number
    CatalogTextField.PRODUCT_NAME -> R.string.field_product_name
    CatalogTextField.SECOND_PRODUCT_NAME -> R.string.field_second_product_name
    CatalogTextField.SUPPLIER_NAME -> R.string.field_supplier
    CatalogTextField.CATEGORY_NAME -> R.string.field_category
    CatalogTextField.REMOTE_ID -> R.string.catalog_text_field_remote_id
}

internal fun CatalogTextPolicy.RejectionReason.messageResource(): Int = when (this) {
    CatalogTextPolicy.RejectionReason.EMPTY_REQUIRED ->
        R.string.catalog_text_error_empty_required
    CatalogTextPolicy.RejectionReason.PROHIBITED_CONTROL ->
        R.string.catalog_text_error_prohibited_control
    CatalogTextPolicy.RejectionReason.PROHIBITED_LINE_SEPARATOR ->
        R.string.catalog_text_error_prohibited_line_separator
    CatalogTextPolicy.RejectionReason.PROHIBITED_ZERO_WIDTH ->
        R.string.catalog_text_error_prohibited_zero_width
    CatalogTextPolicy.RejectionReason.PROHIBITED_BOM ->
        R.string.catalog_text_error_prohibited_bom
    CatalogTextPolicy.RejectionReason.PROHIBITED_BIDI ->
        R.string.catalog_text_error_prohibited_bidi
    CatalogTextPolicy.RejectionReason.INVALID_UTF16,
    CatalogTextPolicy.RejectionReason.INVALID_UTF8 ->
        R.string.catalog_text_error_invalid_encoding
    CatalogTextPolicy.RejectionReason.TOO_LONG ->
        R.string.catalog_text_error_too_long
    CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM ->
        R.string.catalog_text_error_identity_collision
}

internal fun Context.catalogTextErrorMessage(
    rejection: CatalogTextPolicy.FieldRejection
): String = getString(
    R.string.error_catalog_text_rejected,
    getString(rejection.field.labelResource()),
    getString(rejection.reason.messageResource())
)

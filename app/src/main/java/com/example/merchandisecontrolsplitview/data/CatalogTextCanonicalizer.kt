package com.example.merchandisecontrolsplitview.data

import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.CatalogTextPolicy
import com.example.merchandisecontrolsplitview.util.CatalogTextValidationException

data class CanonicalProductText(
    val product: Product,
    val normalizedFields: Set<CatalogTextField>
)

internal object CatalogTextCanonicalizer {
    fun barcode(raw: String): String = accepted(
        outcome = CatalogTextPolicy.strict(
            raw = raw,
            required = true,
            maxLength = CatalogTextPolicy.Limits.BARCODE
        ),
        field = CatalogTextField.BARCODE
    )

    fun itemNumber(raw: String): String = accepted(
        outcome = CatalogTextPolicy.strict(
            raw = raw,
            required = false,
            maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
        ),
        field = CatalogTextField.ITEM_NUMBER
    )

    fun productName(raw: String, required: Boolean = false): String = accepted(
        outcome = CatalogTextPolicy.display(
            raw = raw,
            required = required,
            maxLength = CatalogTextPolicy.Limits.PRODUCT_NAME
        ),
        field = CatalogTextField.PRODUCT_NAME
    )

    fun secondProductName(raw: String, required: Boolean = false): String = accepted(
        outcome = CatalogTextPolicy.display(
            raw = raw,
            required = required,
            maxLength = CatalogTextPolicy.Limits.SECOND_PRODUCT_NAME
        ),
        field = CatalogTextField.SECOND_PRODUCT_NAME
    )

    fun product(product: Product): CanonicalProductText {
        val normalizedFields = linkedSetOf<CatalogTextField>()
        val barcode = accepted(
            outcome = CatalogTextPolicy.strict(
                raw = product.barcode,
                required = true,
                maxLength = CatalogTextPolicy.Limits.BARCODE
            ),
            field = CatalogTextField.BARCODE,
            normalizedFields = normalizedFields
        )
        val itemNumber = product.itemNumber?.let { raw ->
            accepted(
                outcome = CatalogTextPolicy.strict(
                    raw = raw,
                    required = false,
                    maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
                ),
                field = CatalogTextField.ITEM_NUMBER,
                normalizedFields = normalizedFields
            ).takeIf { it.isNotEmpty() }
        }
        val productNameCandidate = product.productName?.let { raw ->
            accepted(
                outcome = CatalogTextPolicy.display(
                    raw = raw,
                    required = false,
                    maxLength = CatalogTextPolicy.Limits.PRODUCT_NAME
                ),
                field = CatalogTextField.PRODUCT_NAME,
                normalizedFields = normalizedFields
            ).takeIf { it.isNotEmpty() }
        }
        val secondProductName = product.secondProductName?.let { raw ->
            accepted(
                outcome = CatalogTextPolicy.display(
                    raw = raw,
                    required = false,
                    maxLength = CatalogTextPolicy.Limits.SECOND_PRODUCT_NAME
                ),
                field = CatalogTextField.SECOND_PRODUCT_NAME,
                normalizedFields = normalizedFields
            ).takeIf { it.isNotEmpty() }
        }
        val productName = productNameCandidate ?: secondProductName ?: itemNumber
            ?: throw rejection(
                CatalogTextField.PRODUCT_NAME,
                CatalogTextPolicy.RejectionReason.EMPTY_REQUIRED
            )
        if (productName != product.productName) normalizedFields += CatalogTextField.PRODUCT_NAME

        return CanonicalProductText(
            product = product.copy(
                barcode = barcode,
                itemNumber = itemNumber,
                productName = productName,
                secondProductName = secondProductName
            ),
            normalizedFields = normalizedFields
        )
    }

    fun supplierName(raw: String): String = accepted(
        outcome = CatalogTextPolicy.display(
            raw = raw,
            required = true,
            maxLength = CatalogTextPolicy.Limits.SUPPLIER_NAME
        ),
        field = CatalogTextField.SUPPLIER_NAME
    )

    fun categoryName(raw: String): String = accepted(
        outcome = CatalogTextPolicy.display(
            raw = raw,
            required = true,
            maxLength = CatalogTextPolicy.Limits.CATEGORY_NAME
        ),
        field = CatalogTextField.CATEGORY_NAME
    )

    fun remoteId(raw: String): String = accepted(
        outcome = CatalogTextPolicy.strict(
            raw = raw,
            required = true,
            maxLength = CatalogTextPolicy.Limits.REMOTE_ID
        ),
        field = CatalogTextField.REMOTE_ID
    )

    fun optionalRemoteId(raw: String?): String? = raw?.let(::remoteId)

    private fun accepted(
        outcome: CatalogTextPolicy.Outcome,
        field: CatalogTextField,
        normalizedFields: MutableSet<CatalogTextField>? = null
    ): String = when (outcome) {
        is CatalogTextPolicy.Outcome.Unchanged -> outcome.value
        is CatalogTextPolicy.Outcome.Normalized -> {
            normalizedFields?.add(field)
            outcome.value
        }
        is CatalogTextPolicy.Outcome.Rejected -> throw rejection(field, outcome.reason)
    }

    private fun rejection(
        field: CatalogTextField,
        reason: CatalogTextPolicy.RejectionReason
    ) = CatalogTextValidationException(
        CatalogTextPolicy.FieldRejection(field = field, reason = reason)
    )
}

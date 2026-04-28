package com.example.merchandisecontrolsplitview.util

import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.PriceHistoryExportRow
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.data.Supplier
import java.math.BigDecimal
import java.security.MessageDigest

data class ImportDatasetFingerprint(
    val productCount: Int,
    val supplierCount: Int,
    val categoryCount: Int,
    val priceHistoryCount: Int,
    val fingerprintShort: String
)

internal class ImportDatasetFingerprintBuilder {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun addProductRow(row: Map<String, String>) {
        addRecord(
            sheet = "Products",
            fields = PRODUCT_KEYS.map { key -> key to normalizeFingerprintValue(key, row[key]) }
        )
    }

    fun addSupplierName(name: String) {
        addRecord("Suppliers", listOf("name" to normalizeFingerprintValue("name", name)))
    }

    fun addCategoryName(name: String) {
        addRecord("Categories", listOf("name" to normalizeFingerprintValue("name", name)))
    }

    fun addPriceHistoryEntry(
        barcode: String,
        timestamp: String,
        type: String,
        price: Double,
        source: String?
    ) {
        addRecord(
            sheet = "PriceHistory",
            fields = listOf(
                "barcode" to normalizeFingerprintValue("barcode", barcode),
                "timestamp" to normalizeFingerprintValue("timestamp", timestamp),
                "type" to normalizeFingerprintValue("type", type),
                "price" to fingerprintNumber(price),
                "source" to normalizeFingerprintValue("source", source)
            )
        )
    }

    fun build(
        productCount: Int,
        supplierCount: Int,
        categoryCount: Int,
        priceHistoryCount: Int
    ): ImportDatasetFingerprint {
        addRecord(
            sheet = "Counts",
            fields = listOf(
                "products" to productCount.toString(),
                "suppliers" to supplierCount.toString(),
                "categories" to categoryCount.toString(),
                "priceHistory" to priceHistoryCount.toString()
            )
        )
        return ImportDatasetFingerprint(
            productCount = productCount,
            supplierCount = supplierCount,
            categoryCount = categoryCount,
            priceHistoryCount = priceHistoryCount,
            fingerprintShort = digest.digest().joinToString("") { "%02x".format(it) }.take(FINGERPRINT_SHORT_LENGTH)
        )
    }

    private fun addRecord(sheet: String, fields: List<Pair<String, String>>) {
        digest.update(sheet.toByteArray())
        digest.update(RECORD_SEPARATOR)
        fields.forEach { (key, value) ->
            digest.update(key.toByteArray())
            digest.update(KEY_VALUE_SEPARATOR)
            digest.update(value.toByteArray())
            digest.update(FIELD_SEPARATOR)
        }
        digest.update(RECORD_SEPARATOR)
    }
}

fun buildDatabaseSnapshotFingerprint(
    products: List<ProductWithDetails>,
    suppliers: List<Supplier>,
    categories: List<Category>,
    priceHistoryRows: List<PriceHistoryExportRow>
): ImportDatasetFingerprint {
    val builder = ImportDatasetFingerprintBuilder()
    products.forEach { details ->
        val product = details.product
        builder.addProductRow(
            mapOf(
                "barcode" to product.barcode,
                "itemNumber" to product.itemNumber.orEmpty(),
                "productName" to product.productName.orEmpty(),
                "secondProductName" to product.secondProductName.orEmpty(),
                "purchasePrice" to fingerprintNumber(details.currentPurchasePrice),
                "retailPrice" to fingerprintNumber(details.currentRetailPrice),
                "oldPurchasePrice" to fingerprintNumber(details.prevPurchase),
                "oldRetailPrice" to fingerprintNumber(details.prevRetail),
                "supplier" to details.supplierName.orEmpty(),
                "category" to details.categoryName.orEmpty(),
                "quantity" to fingerprintNumber(product.stockQuantity)
            )
        )
    }
    suppliers.forEach { builder.addSupplierName(it.name) }
    categories.forEach { builder.addCategoryName(it.name) }
    priceHistoryRows.forEach { row ->
        builder.addPriceHistoryEntry(
            barcode = row.barcode,
            timestamp = row.timestamp,
            type = row.type,
            price = row.price,
            source = row.source
        )
    }
    return builder.build(
        productCount = products.size,
        supplierCount = suppliers.size,
        categoryCount = categories.size,
        priceHistoryCount = priceHistoryRows.size
    )
}

private fun normalizeFingerprintValue(key: String, value: String?): String {
    val normalized = value
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
    return if (key in NUMERIC_KEYS) {
        normalized.replace(",", ".").toDoubleOrNull()?.let(::fingerprintNumber) ?: normalized
    } else {
        normalized
    }
}

private fun fingerprintNumber(value: Double?): String =
    value?.takeIf { it.isFinite() }?.let(::fingerprintNumber).orEmpty()

private fun fingerprintNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private val PRODUCT_KEYS = listOf(
    "barcode",
    "itemNumber",
    "productName",
    "secondProductName",
    "purchasePrice",
    "retailPrice",
    "oldPurchasePrice",
    "oldRetailPrice",
    "supplier",
    "category",
    "quantity"
)

private val NUMERIC_KEYS = setOf(
    "purchasePrice",
    "retailPrice",
    "oldPurchasePrice",
    "oldRetailPrice",
    "quantity",
    "price"
)

private const val FINGERPRINT_SHORT_LENGTH = 16
private val RECORD_SEPARATOR = byteArrayOf(0x1e)
private val FIELD_SEPARATOR = byteArrayOf(0x1f)
private val KEY_VALUE_SEPARATOR = byteArrayOf(0x1d)

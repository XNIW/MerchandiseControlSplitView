package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.data.*
import kotlin.math.abs
import kotlin.math.round
import com.example.merchandisecontrolsplitview.R

object ImportAnalyzer {

    private const val MAX_PRODUCT_NAME_LENGTH = 100
    private const val PRICE_COMPARISON_TOLERANCE = 0.001

    private fun parseDouble(s: String?): Double? = s?.trim()?.replace(",", ".")?.toDoubleOrNull()

    private fun round3(x: Double?) = x?.let { round(it * 1000.0) / 1000.0 }

    suspend fun analyze(
        context: Context,
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product>,
        supplierDao: SupplierDao,
        categoryDao: CategoryDao
    ): ImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()
        val warnings = mutableListOf<DuplicateWarning>()

        val allSuppliers = supplierDao.getAll().associateBy { it.id }
        val allCategories = categoryDao.getAll().associateBy { it.id }
        val supplierCacheByName = allSuppliers.values
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()
        val categoryCacheByName = allCategories.values
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()

        suspend fun getOrCreateSupplierId(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isBlank()) return null

            // 1) cache
            supplierCacheByName[key]?.let { return it.id }

            // 2) già in DB?
            supplierDao.findByName(name.trim())?.let {
                supplierCacheByName[key] = it
                return it.id
            }

            // 3) crea e poi rileggi
            supplierDao.insert(Supplier(name = name.trim())) // può tornare Unit o Long: ignoriamo
            val created = supplierDao.findByName(name.trim()) ?: return null
            supplierCacheByName[key] = created
            return created.id
        }

        suspend fun getOrCreateCategoryId(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isBlank()) return null

            categoryCacheByName[key]?.let { return it.id }

            categoryDao.findByName(name.trim())?.let {
                categoryCacheByName[key] = it
                return it.id
            }

            categoryDao.insert(Category(name = name.trim()))
            val created = categoryDao.findByName(name.trim()) ?: return null
            categoryCacheByName[key] = created
            return created.id
        }

        val rowsWithIndex = importedRows.withIndex()
        val groupedByBarcode = rowsWithIndex.groupBy { (_, row) -> row["barcode"]?.trim() ?: "" }

        for ((barcode, group) in groupedByBarcode) {
            if (barcode.isBlank()) {
                group.forEach { (rowIndex, row) -> errors.add(RowImportError(rowIndex + 1, row, R.string.error_barcode_required)) }
                continue
            }

            val finalRow: Map<String, String>
            val originalRowIndex: Int

            if (group.size > 1) {
                warnings.add(DuplicateWarning(barcode = barcode, rowNumbers = group.map { it.index + 1 }))
                val lastRowInfo = group.last()
                originalRowIndex = lastRowInfo.index
                val mergedRow = lastRowInfo.value.toMutableMap()

                val totalQuantity = group.sumOf { (_, row) ->
                    val supplierQuantity = parseDouble(row["quantity"]) ?: 0.0
                    val realQty = parseDouble(row["realQuantity"]) ?: 0.0
                    realQty.takeIf { it > 0 } ?: supplierQuantity
                }
                mergedRow["quantity"] = totalQuantity.toString()
                finalRow = mergedRow.toMap()
            } else {
                val singleRowInfo = group.first()
                originalRowIndex = singleRowInfo.index
                finalRow = singleRowInfo.value
            }

            try {
                val itemNumber = finalRow["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName = finalRow["productName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val secondProductName = finalRow["secondProductName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val supplierName = finalRow["supplier"]?.trim()?.takeIf { it.isNotBlank() }
                val categoryName = finalRow["category"]?.trim()?.takeIf { it.isNotBlank() }

                val quantityToUse = parseDouble(finalRow["quantity"])
                val purchasePriceFromFile = parseDouble(finalRow["purchasePrice"])
                val retailPriceFromFile = round3(parseDouble(finalRow["retailPrice"]))
                val discountFromFile = parseDouble(finalRow["discount"])
                if (discountFromFile != null && (discountFromFile < 0 || discountFromFile > 100)) {
                    errors.add(RowImportError(originalRowIndex + 1, finalRow, R.string.error_invalid_discount))
                    continue
                }

                val discountedPriceFromFile = parseDouble(finalRow["discountedPrice"])

                val finalPurchasePrice = round3(when {
                    discountedPriceFromFile != null -> discountedPriceFromFile
                    purchasePriceFromFile != null && discountFromFile != null ->
                        purchasePriceFromFile * (1 - (discountFromFile / 100))
                    else -> purchasePriceFromFile
                })

                val validationError = validateRow(originalRowIndex, finalRow, barcode, productName, secondProductName, finalPurchasePrice)
                if (validationError != null) {
                    errors.add(validationError)
                    continue
                }

                val existingProduct = dbProductByBarcode[barcode]

                if (existingProduct != null && retailPriceFromFile != null && retailPriceFromFile <= 0.0) {
                    errors.add(RowImportError(originalRowIndex + 1, finalRow, R.string.error_invalid_or_missing_retail_price))
                    continue
                }

                if (existingProduct == null && (retailPriceFromFile == null || retailPriceFromFile <= 0)) {
                    errors.add(RowImportError(originalRowIndex + 1, finalRow, R.string.error_invalid_or_missing_retail_price))
                    continue
                }

                if (quantityToUse != null && quantityToUse < 0) {
                    // CORREZIONE 3: Assicurati di avere R.string.error_negative_quantity in strings.xml
                    errors.add(RowImportError(originalRowIndex + 1, finalRow, R.string.error_negative_quantity))
                    continue
                }

                val supplierId = supplierName?.let { getOrCreateSupplierId(it) }
                val categoryId = categoryName?.let { getOrCreateCategoryId(it) }

                if (existingProduct == null) {
                    val newProduct = Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName!!,
                        secondProductName = secondProductName,
                        purchasePrice = finalPurchasePrice,
                        retailPrice = retailPriceFromFile,
                        supplierId = supplierId,
                        categoryId = categoryId,
                        stockQuantity = quantityToUse ?: 0.0
                    )
                    newProducts.add(newProduct)
                } else {
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        secondProductName = secondProductName ?: existingProduct.secondProductName,
                        supplierId = supplierId ?: existingProduct.supplierId,
                        categoryId = categoryId ?: existingProduct.categoryId,

                        oldPurchasePrice = if (finalPurchasePrice != null &&
                            existingProduct.purchasePrice != null &&
                            abs(finalPurchasePrice - existingProduct.purchasePrice) > PRICE_COMPARISON_TOLERANCE)
                            existingProduct.purchasePrice else existingProduct.oldPurchasePrice,

                        oldRetailPrice = if (retailPriceFromFile != null &&
                            existingProduct.retailPrice != null &&
                            abs(retailPriceFromFile - existingProduct.retailPrice) > PRICE_COMPARISON_TOLERANCE)
                            existingProduct.retailPrice else existingProduct.oldRetailPrice,

                        purchasePrice = finalPurchasePrice ?: existingProduct.purchasePrice,
                        retailPrice = retailPriceFromFile ?: existingProduct.retailPrice,
                        stockQuantity = quantityToUse ?: existingProduct.stockQuantity
                    )

                    val changedFields = getChangedFields(existingProduct, updatedProduct, allSuppliers, allCategories)
                    if (changedFields.isNotEmpty()) {
                        updatedProducts.add(ProductUpdate(existingProduct, updatedProduct, changedFields))
                    }


                }
            } catch (ex: Exception) {
                errors.add(
                    RowImportError(
                        rowNumber = originalRowIndex + 1,
                        rowContent = finalRow,
                        errorReasonResId = R.string.error_unexpected_parsing,
                        formatArgs = listOf(ex.message ?: context.getString(R.string.unknown))
                    )
                )
            }
        }
        return ImportAnalysis(newProducts, updatedProducts, errors, warnings)
    }

    private fun validateRow(
        rowIndex: Int, row: Map<String, String>, barcode: String,
        productName: String?, secondProductName: String?,
        purchasePrice: Double?
    ): RowImportError? {
        return when {
            barcode.isBlank() -> RowImportError(rowIndex + 1, row, R.string.error_barcode_required)
            productName.isNullOrBlank() && secondProductName.isNullOrBlank() -> RowImportError(rowIndex + 1, row, R.string.error_productname_required_at_least_one)
            purchasePrice != null && purchasePrice < 0 -> RowImportError(rowIndex + 1, row, R.string.error_negative_prices)
            else -> null
        }
    }

    private fun getChangedFields(
        old: Product,
        new: Product,
        suppliersById: Map<Long, Supplier>,
        categoriesById: Map<Long, Category>
    ): List<Int> {
        val fields = mutableListOf<Int>()

        if (!old.productName.orEmpty().equals(new.productName.orEmpty(), ignoreCase = true)) fields.add(R.string.field_product_name)
        if (old.secondProductName.orEmpty() != new.secondProductName.orEmpty()) fields.add(R.string.field_second_product_name)
        if (!old.itemNumber.orEmpty().equals(new.itemNumber.orEmpty(), ignoreCase = true)) fields.add(R.string.header_item_number)

        if (abs((old.purchasePrice ?: 0.0) - (new.purchasePrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.purchase_price_label)
        if (abs((old.retailPrice ?: 0.0) - (new.retailPrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.retail_price_label)
        if (abs((old.stockQuantity ?: 0.0) - (new.stockQuantity ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.field_stock_quantity)

        if (old.supplierId != new.supplierId) {
            val oldSupplierName = old.supplierId?.let { suppliersById[it]?.name }
            val newSupplierName = new.supplierId?.let { suppliersById[it]?.name }
            if (!oldSupplierName.orEmpty().equals(newSupplierName.orEmpty(), ignoreCase = true)) {
                fields.add(R.string.field_supplier)
            }
        }

        if (old.categoryId != new.categoryId) {
            val oldCategoryName = old.categoryId?.let { categoriesById[it]?.name }
            val newCategoryName = new.categoryId?.let { categoriesById[it]?.name }
            if (!oldCategoryName.orEmpty().equals(newCategoryName.orEmpty(), ignoreCase = true)) {
                fields.add(R.string.field_category)
            }
        }
        return fields
    }
}
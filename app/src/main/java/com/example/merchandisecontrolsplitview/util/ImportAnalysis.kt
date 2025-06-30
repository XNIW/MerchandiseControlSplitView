package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.data.*
import kotlin.math.abs
import com.example.merchandisecontrolsplitview.R

object ImportAnalyzer {

    private const val MAX_PRODUCT_NAME_LENGTH = 100
    private const val PRICE_COMPARISON_TOLERANCE = 0.001

    suspend fun analyze(
        context: Context,
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product>,
        supplierDao: SupplierDao,
        // --- MODIFICA 1: Aggiungi categoryDao come parametro ---
        categoryDao: CategoryDao
    ): ImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()

        for ((rowIndex, row) in importedRows.withIndex()) {
            try {
                val barcode = row["barcode"]?.trim() ?: ""
                val itemNumber = row["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName = row["productName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val secondProductName = row["secondProductName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val supplierName = row["supplier"]?.trim()?.takeIf { it.isNotBlank() }

                // --- MODIFICA 2: Leggi il nome della categoria dal file ---
                val categoryName = row["category"]?.trim()?.takeIf { it.isNotBlank() }

                val stockQuantityFromFile = row["stockQuantity"]?.replace(",", ".")?.toDoubleOrNull()
                val realQuantityFromFile = row["realQuantity"]?.replace(",", ".")?.toDoubleOrNull()
                val quantityToUse = realQuantityFromFile ?: stockQuantityFromFile

                val purchasePriceFromFile = row["purchasePrice"]?.replace(",", ".")?.toDoubleOrNull()
                val retailPriceFromFile = row["retailPrice"]?.replace(",", ".")?.toDoubleOrNull()
                val discountFromFile = row["discount"]?.replace(",", ".")?.toDoubleOrNull()
                val discountedPriceFromFile = row["discountedPrice"]?.replace(",", ".")?.toDoubleOrNull()

                val finalPurchasePrice = when {
                    discountedPriceFromFile != null -> discountedPriceFromFile
                    purchasePriceFromFile != null && discountFromFile != null -> purchasePriceFromFile * (1 - (discountFromFile / 100))
                    else -> purchasePriceFromFile
                }

                val validationError = validateRow(rowIndex, row, barcode, productName, secondProductName, finalPurchasePrice, retailPriceFromFile)
                if (validationError != null) {
                    errors.add(validationError)
                    continue
                }

                val supplierId: Long? = if (supplierName != null) {
                    var supplier = supplierDao.findByName(supplierName)
                    if (supplier == null) {
                        supplierDao.insert(Supplier(name = supplierName))
                        supplier = supplierDao.findByName(supplierName)
                    }
                    supplier?.id
                } else { null }

                // --- MODIFICA 3: Logica per trovare/creare la categoria e ottenere l'ID ---
                val categoryId: Long? = if (categoryName != null) {
                    var category = categoryDao.findByName(categoryName)
                    if (category == null) {
                        categoryDao.insert(Category(name = categoryName))
                        category = categoryDao.findByName(categoryName)
                    }
                    category?.id
                } else { null }

                val existingProduct = dbProductByBarcode[barcode]

                if (existingProduct == null) {
                    // --- MODIFICA 4: Crea il nuovo prodotto usando categoryId ---
                    val newProduct = Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName!!,
                        secondProductName = secondProductName,
                        purchasePrice = finalPurchasePrice,
                        retailPrice = retailPriceFromFile,
                        supplierId = supplierId,
                        categoryId = categoryId, // Assegna l'ID della categoria
                        stockQuantity = quantityToUse ?: 0.0
                    )
                    newProducts.add(newProduct)
                } else {
                    // --- MODIFICA 5: Aggiorna il prodotto esistente usando categoryId ---
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        secondProductName = secondProductName ?: existingProduct.secondProductName,
                        supplierId = supplierId ?: existingProduct.supplierId,
                        categoryId = categoryId ?: existingProduct.categoryId, // Aggiorna l'ID della categoria

                        oldPurchasePrice = if(finalPurchasePrice != existingProduct.purchasePrice) existingProduct.purchasePrice else existingProduct.oldPurchasePrice,
                        oldRetailPrice = if(retailPriceFromFile != existingProduct.retailPrice) existingProduct.retailPrice else existingProduct.oldRetailPrice,
                        purchasePrice = finalPurchasePrice ?: existingProduct.purchasePrice,
                        retailPrice = retailPriceFromFile ?: existingProduct.retailPrice,
                        stockQuantity = (existingProduct.stockQuantity ?: 0.0) + (quantityToUse ?: 0.0)
                    )

                    // Passa anche categoryDao a getChangedFields
                    val changedFields = getChangedFields(existingProduct, updatedProduct, supplierDao, categoryDao)
                    if (changedFields.isNotEmpty()) {
                        updatedProducts.add(ProductUpdate(existingProduct, updatedProduct, changedFields))
                    }
                }
            } catch (ex: Exception) {
                errors.add(
                    RowImportError(
                        rowNumber = rowIndex + 1,
                        rowContent = row,
                        errorReasonResId = R.string.error_unexpected_parsing,
                        formatArgs = listOf(ex.message ?: context.getString(R.string.unknown))
                    )
                )
            }
        }
        return ImportAnalysis(newProducts, updatedProducts, errors)
    }

    private fun validateRow(
        rowIndex: Int, row: Map<String, String>, barcode: String,
        productName: String?, secondProductName: String?,
        purchasePrice: Double?, retailPrice: Double?
    ): RowImportError? {
        return when {
            barcode.isBlank() ->
                RowImportError(rowIndex + 1, row, R.string.error_barcode_required)

            productName.isNullOrBlank() && secondProductName.isNullOrBlank() ->
                RowImportError(rowIndex + 1, row, R.string.error_productname_required_at_least_one)

            retailPrice == null || retailPrice <= 0 ->
                RowImportError(rowIndex + 1, row, R.string.error_invalid_or_missing_retail_price)

            purchasePrice != null && purchasePrice < 0 ->
                RowImportError(rowIndex + 1, row, R.string.error_negative_prices)

            else -> null
        }
    }

    // --- MODIFICA 6: Aggiungi categoryDao come parametro anche qui ---
    private suspend fun getChangedFields(
        old: Product,
        new: Product,
        supplierDao: SupplierDao,
        categoryDao: CategoryDao
    ): List<Int> {
        val fields = mutableListOf<Int>()
        if (!old.productName.equals(new.productName, ignoreCase = true)) fields.add(R.string.field_product_name)
        if (old.secondProductName != new.secondProductName) fields.add(R.string.field_second_product_name)
        if (!old.itemNumber.equals(new.itemNumber, ignoreCase = true)) fields.add(R.string.header_item_number)
        if (abs((old.purchasePrice ?: 0.0) - (new.purchasePrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.purchase_price_label)
        if (abs((old.retailPrice ?: 0.0) - (new.retailPrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.retail_price_label)
        if (abs((old.stockQuantity ?: 0.0) - (new.stockQuantity ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.field_stock_quantity)

        if (old.supplierId != new.supplierId) {
            val oldSupplierName = old.supplierId?.let { supplierDao.getById(it)?.name }
            val newSupplierName = new.supplierId?.let { supplierDao.getById(it)?.name }
            if (!oldSupplierName.equals(newSupplierName, ignoreCase = true)) {
                fields.add(R.string.field_supplier)
            }
        }

        // --- MODIFICA 7: Logica per confrontare le categorie tramite ID e nome ---
        if (old.categoryId != new.categoryId) {
            val oldCategoryName = old.categoryId?.let { categoryDao.getById(it)?.name }
            val newCategoryName = new.categoryId?.let { categoryDao.getById(it)?.name }
            if (!oldCategoryName.equals(newCategoryName, ignoreCase = true)) {
                fields.add(R.string.field_category)
            }
        }
        return fields
    }
}
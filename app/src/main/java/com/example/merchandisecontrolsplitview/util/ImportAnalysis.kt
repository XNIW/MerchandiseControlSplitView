// in util/ImportAnalysis.kt
package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.data.SupplierDao
import kotlin.math.abs
import com.example.merchandisecontrolsplitview.R

object ImportAnalyzer {

    private const val MAX_PRODUCT_NAME_LENGTH = 100
    private const val PRICE_COMPARISON_TOLERANCE = 0.001

    suspend fun analyze(
        context: Context,
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product>,
        supplierDao: SupplierDao
    ): ImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()

        for ((rowIndex, row) in importedRows.withIndex()) {
            try {
                // --- 1. LETTURA DEL NUOVO CAMPO ---
                val barcode = row["barcode"]?.trim() ?: ""
                val itemNumber = row["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName = row["productName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val secondProductName = row["secondProductName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() } // <-- AGGIUNTO
                val purchasePrice = row["purchasePrice"]?.replace(",", ".")?.toDoubleOrNull()
                val newRetailPrice = row["newRetailPrice"]?.replace(",", ".")?.toDoubleOrNull()
                val supplierName = row["supplier"]?.trim()?.takeIf { it.isNotBlank() }

                val validationError = validateRow(rowIndex, row, barcode, productName, secondProductName, purchasePrice, newRetailPrice)
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
                } else {
                    null
                }


                val existingProduct = dbProductByBarcode[barcode]

                if (existingProduct == null) {
                    // --- 2. ASSEGNAZIONE AL NUOVO PRODOTTO ---
                    val newProduct = Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName!!,
                        secondProductName = secondProductName, // <-- AGGIUNTO
                        newPurchasePrice = purchasePrice ?: 0.0,
                        newRetailPrice = newRetailPrice!!,
                        supplierId = supplierId
                    )
                    newProducts.add(newProduct)
                } else {
                    // --- 3. ASSEGNAZIONE AL PRODOTTO AGGIORNATO ---
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        secondProductName = secondProductName ?: existingProduct.secondProductName, // <-- AGGIUNTO
                        supplierId = supplierId ?: existingProduct.supplierId,
                        oldPurchasePrice = existingProduct.newPurchasePrice,
                        oldRetailPrice = existingProduct.newRetailPrice,
                        newPurchasePrice = purchasePrice ?: 0.0,
                        newRetailPrice = newRetailPrice!!
                    )
                    val changedFields = getChangedFields(existingProduct, updatedProduct, supplierDao)
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
        productName: String?, secondProductName: String?, // <-- AGGIUNTO
        purchasePrice: Double?, retailPrice: Double?
    ): RowImportError? {
        return when {
            barcode.isBlank() -> RowImportError(rowIndex + 1, row, R.string.error_barcode_required)
            // Nuova regola: almeno uno dei due nomi deve essere presente
            productName.isNullOrBlank() && secondProductName.isNullOrBlank() -> RowImportError(rowIndex + 1, row, R.string.error_productname_required_at_least_one)
            retailPrice == null -> RowImportError(rowIndex + 1, row, R.string.error_invalid_retail_price)
            (purchasePrice ?: 0.0) < 0 || retailPrice < 0 -> RowImportError(rowIndex + 1, row, R.string.error_negative_prices)
            else -> null
        }
    }
    private suspend fun getChangedFields(old: Product, new: Product, supplierDao: SupplierDao): List<Int> {
        val fields = mutableListOf<Int>()
        if (!old.productName.equals(new.productName, ignoreCase = true)) fields.add(R.string.field_product_name)
        // Aggiunto controllo sul nuovo campo
        if (old.secondProductName != new.secondProductName) fields.add(R.string.second_product_name_label)

        if (!old.itemNumber.equals(new.itemNumber, ignoreCase = true)) fields.add(R.string.header_item_number)
        if (abs((old.newPurchasePrice ?: 0.0) - (new.newPurchasePrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.purchase_price_label)
        if (abs((old.newRetailPrice ?: 0.0) - (new.newRetailPrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.retail_price_label)

        if (old.supplierId != new.supplierId) {
            val oldSupplierName = old.supplierId?.let { supplierDao.getById(it)?.name }
            val newSupplierName = new.supplierId?.let { supplierDao.getById(it)?.name }
            if (!oldSupplierName.equals(newSupplierName, ignoreCase = true)) {
                fields.add(R.string.field_supplier)
            }
        }
        return fields
    }
}
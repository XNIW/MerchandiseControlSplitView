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

    suspend fun analyze( // <-- MODIFICA: La funzione ora è suspend
        context: Context,
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product>,
        supplierDao: SupplierDao // <-- MODIFICA: Riceve il SupplierDao
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
                val purchasePrice = row["purchasePrice"]?.replace(",", ".")?.toDoubleOrNull()
                val newRetailPrice = row["newRetailPrice"]?.replace(",", ".")?.toDoubleOrNull()
                val supplierName = row["supplier"]?.trim()?.takeIf { it.isNotBlank() }

                val validationError = validateRow(rowIndex, row, barcode, productName, purchasePrice, newRetailPrice)
                if (validationError != null) {
                    errors.add(validationError)
                    continue
                }

                // --- LOGICA FORNITORE AGGIORNATA ---
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
                // --- FINE LOGICA FORNITORE ---


                val existingProduct = dbProductByBarcode[barcode]

                if (existingProduct == null) {
                    val newProduct = Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName!!,
                        newPurchasePrice = purchasePrice ?: 0.0,
                        newRetailPrice = newRetailPrice!!,
                        supplierId = supplierId // <-- USA supplierId
                    )
                    newProducts.add(newProduct)
                } else {
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        supplierId = supplierId ?: existingProduct.supplierId, // <-- USA supplierId
                        oldPurchasePrice = existingProduct.newPurchasePrice,
                        oldRetailPrice = existingProduct.newRetailPrice,
                        newPurchasePrice = purchasePrice ?: 0.0,
                        newRetailPrice = newRetailPrice!!
                    )
                    val changedFields = getChangedFields(existingProduct, updatedProduct, supplierDao) // <-- Passa DAO
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
                        formatArgs = listOf(ex.message ?: context.getString(R.string.unknown)) // Passa il messaggio come argomento
                    )
                )
            }
        }
        return ImportAnalysis(newProducts, updatedProducts, errors)
    }

    private fun validateRow(
        rowIndex: Int, row: Map<String, String>, barcode: String, productName: String?,
        purchasePrice: Double?, retailPrice: Double?
    ): RowImportError? {
        return when {
            barcode.isBlank() -> RowImportError(rowIndex + 1, row, R.string.error_barcode_required)
            productName.isNullOrBlank() -> RowImportError(rowIndex + 1, row, R.string.error_productname_required)
            retailPrice == null -> RowImportError(rowIndex + 1, row, R.string.error_invalid_retail_price)
            (purchasePrice ?: 0.0) < 0 || retailPrice < 0 -> RowImportError(rowIndex + 1, row, R.string.error_negative_prices)
            else -> null
        }
    }

    private suspend fun getChangedFields(old: Product, new: Product, supplierDao: SupplierDao): List<Int> { // <-- suspend
        val fields = mutableListOf<Int>()
        if (!old.productName.equals(new.productName, ignoreCase = true)) fields.add(R.string.field_product_name)
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
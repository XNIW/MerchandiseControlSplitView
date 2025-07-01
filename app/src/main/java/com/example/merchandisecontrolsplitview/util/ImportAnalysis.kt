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
        val warnings = mutableListOf<DuplicateWarning>() // <-- Lista per i nuovi avvisi

        // --- INIZIO LOGICA DI RILEVAMENTO E UNIONE DUPLICATI ---

        // 1. Raggruppa le righe per barcode, mantenendo l'indice originale (basato su 0)
        val rowsWithIndex = importedRows.withIndex()
        val groupedByBarcode = rowsWithIndex.groupBy { (_, row) -> row["barcode"]?.trim() ?: "" }


        for ((barcode, group) in groupedByBarcode) {
            if (barcode.isBlank()) {
                // Gestisci le righe senza barcode come errori individuali
                group.forEach { (rowIndex, row) ->
                    errors.add(RowImportError(rowIndex + 1, row, R.string.error_barcode_required))
                }
                continue // Salta al prossimo gruppo
            }

            val finalRow: Map<String, String>
            val originalRowIndex: Int // L'indice della riga che useremo per i messaggi di errore

            if (group.size > 1) {
                // 3. SE CI SONO DUPLICATI, UNISCI I DATI
                warnings.add(DuplicateWarning(
                    barcode = barcode,
                    rowNumbers = group.map { it.index + 1 } // Numeri di riga basati su 1 per l'utente
                ))

                // Prendi l'ultima riga come base per la maggior parte dei dati
                val lastRowInfo = group.last()
                originalRowIndex = lastRowInfo.index
                val mergedRow = lastRowInfo.value.toMutableMap()

                // Somma le quantità da tutte le righe del gruppo
                val totalStockQuantity = group.sumOf { (_, row) ->
                    val stockQty = row["stockQuantity"]?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                    val realQty = row["realQuantity"]?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                    realQty.takeIf { it > 0 } ?: stockQty
                }
                mergedRow["stockQuantity"] = totalStockQuantity.toString()

                finalRow = mergedRow.toMap()

            } else {
                // 4. SE NON CI SONO DUPLICATI, USA LA RIGA ORIGINALE
                val singleRowInfo = group.first()
                originalRowIndex = singleRowInfo.index
                finalRow = singleRowInfo.value
            }

            // 5. ELABORA LA RIGA FINALE (ORIGINALE O UNITA)
            try {
                // Usa 'finalRow' e 'originalRowIndex' per il resto della logica
                val itemNumber = finalRow["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName = finalRow["productName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val secondProductName = finalRow["secondProductName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val supplierName = finalRow["supplier"]?.trim()?.takeIf { it.isNotBlank() }
                val categoryName = finalRow["category"]?.trim()?.takeIf { it.isNotBlank() }

                // La quantità è già aggregata per i duplicati
                val quantityToUse = finalRow["stockQuantity"]?.replace(",", ".")?.toDoubleOrNull()

                val purchasePriceFromFile = finalRow["purchasePrice"]?.replace(",", ".")?.toDoubleOrNull()
                val retailPriceFromFile = finalRow["retailPrice"]?.replace(",", ".")?.toDoubleOrNull()
                val discountFromFile = finalRow["discount"]?.replace(",", ".")?.toDoubleOrNull()
                val discountedPriceFromFile = finalRow["discountedPrice"]?.replace(",", ".")?.toDoubleOrNull()

                val finalPurchasePrice = when {
                    discountedPriceFromFile != null -> discountedPriceFromFile
                    purchasePriceFromFile != null && discountFromFile != null -> purchasePriceFromFile * (1 - (discountFromFile / 100))
                    else -> purchasePriceFromFile
                }

                val validationError = validateRow(originalRowIndex, finalRow, barcode, productName, secondProductName, finalPurchasePrice, retailPriceFromFile)
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
                    // Per gli aggiornamenti, la quantità non è più additiva qui, perché è già stata aggregata.
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        secondProductName = secondProductName ?: existingProduct.secondProductName,
                        supplierId = supplierId ?: existingProduct.supplierId,
                        categoryId = categoryId ?: existingProduct.categoryId,
                        oldPurchasePrice = if(finalPurchasePrice != existingProduct.purchasePrice) existingProduct.purchasePrice else existingProduct.oldPurchasePrice,
                        oldRetailPrice = if(retailPriceFromFile != existingProduct.retailPrice) existingProduct.retailPrice else existingProduct.oldRetailPrice,
                        purchasePrice = finalPurchasePrice ?: existingProduct.purchasePrice,
                        retailPrice = retailPriceFromFile ?: existingProduct.retailPrice,
                        stockQuantity = quantityToUse ?: existingProduct.stockQuantity // Usa la quantità aggregata
                    )

                    val changedFields = getChangedFields(existingProduct, updatedProduct, supplierDao, categoryDao)
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

        return ImportAnalysis(newProducts, updatedProducts, errors, warnings) // <-- Restituisci anche gli avvisi
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
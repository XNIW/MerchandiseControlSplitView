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
        supplierDao: SupplierDao
    ): ImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()

        for ((rowIndex, row) in importedRows.withIndex()) {
            try {
                // --- 1. LETTURA DI TUTTI I CAMPI, INCLUSI I NUOVI OPZIONALI ---
                val barcode = row["barcode"]?.trim() ?: ""
                val itemNumber = row["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName = row["productName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val secondProductName = row["secondProductName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val supplierName = row["supplier"]?.trim()?.takeIf { it.isNotBlank() }

                // Nuovi campi per categoria e giacenza
                val category = row["category"]?.trim()?.takeIf { it.isNotBlank() }
                val stockQuantityFromFile = row["stockQuantity"]?.replace(",", ".")?.toDoubleOrNull()
                val realQuantityFromFile = row["realQuantity"]?.replace(",", ".")?.toDoubleOrNull()
                val quantityToUse = realQuantityFromFile ?: stockQuantityFromFile // Diamo priorità alla quantità contata

                // Campi per calcolo prezzo
                val purchasePriceFromFile = row["purchasePrice"]?.replace(",", ".")?.toDoubleOrNull()
                val retailPriceFromFile = row["retailPrice"]?.replace(",", ".")?.toDoubleOrNull()
                val discountFromFile = row["discount"]?.replace(",", ".")?.toDoubleOrNull()
                val discountedPriceFromFile = row["discountedPrice"]?.replace(",", ".")?.toDoubleOrNull()

                // Calcoliamo il prezzo di acquisto finale
                val finalPurchasePrice = when {
                    discountedPriceFromFile != null -> discountedPriceFromFile
                    purchasePriceFromFile != null && discountFromFile != null -> purchasePriceFromFile * (1 - (discountFromFile / 100)) // Assumendo che lo sconto sia in %, es. 20 per 20%
                    else -> purchasePriceFromFile
                }

                // Validazione (ora usa `finalPurchasePrice`)
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

                val existingProduct = dbProductByBarcode[barcode]

                if (existingProduct == null) {
                    // --- 2. CREAZIONE DI UN NUOVO PRODOTTO CON I NUOVI CAMPI ---
                    val newProduct = Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName!!,
                        secondProductName = secondProductName,
                        purchasePrice = finalPurchasePrice,
                        retailPrice = retailPriceFromFile,
                        supplierId = supplierId,
                        category = category, // Assegna la categoria
                        stockQuantity = quantityToUse ?: 0.0 // Assegna la giacenza iniziale
                    )
                    newProducts.add(newProduct)
                } else {
                    // --- 3. AGGIORNAMENTO DI UN PRODOTTO ESISTENTE (LOGICA CHIAVE) ---
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        secondProductName = secondProductName ?: existingProduct.secondProductName,
                        supplierId = supplierId ?: existingProduct.supplierId,
                        category = category ?: existingProduct.category, // Aggiorna la categoria se fornita

                        // Aggiorniamo i prezzi
                        oldPurchasePrice = if(finalPurchasePrice != existingProduct.purchasePrice) existingProduct.purchasePrice else existingProduct.oldPurchasePrice,
                        oldRetailPrice = if(retailPriceFromFile != existingProduct.retailPrice) existingProduct.retailPrice else existingProduct.oldRetailPrice,
                        purchasePrice = finalPurchasePrice ?: existingProduct.purchasePrice,
                        retailPrice = retailPriceFromFile ?: existingProduct.retailPrice,

                        // AGGIORNIAMO LO STOCK: sommiamo la nuova quantità a quella esistente
                        stockQuantity = (existingProduct.stockQuantity ?: 0.0) + (quantityToUse ?: 0.0)
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
        productName: String?, secondProductName: String?,
        purchasePrice: Double?, retailPrice: Double?
    ): RowImportError? {
        // Non abbiamo più bisogno di 'originalRetailPriceString' per questa validazione

        return when {
            // Errore: Barcode mancante (invariato)
            barcode.isBlank() ->
                RowImportError(rowIndex + 1, row, R.string.error_barcode_required)

            // Errore: Nessun nome prodotto fornito (invariato)
            productName.isNullOrBlank() && secondProductName.isNullOrBlank() ->
                RowImportError(rowIndex + 1, row, R.string.error_productname_required_at_least_one)

            // --- LOGICA DI VALIDAZIONE CORRETTA E UNIFICATA ---
            // Se il prezzo di vendita è nullo (perché vuoto o non numerico) O è un numero <= 0,
            // allora è un errore.
            retailPrice == null || retailPrice <= 0 ->
                RowImportError(rowIndex + 1, row, R.string.error_invalid_or_missing_retail_price)

            // Errore: Il prezzo di acquisto è negativo (invariato)
            purchasePrice != null && purchasePrice < 0 ->
                RowImportError(rowIndex + 1, row, R.string.error_negative_prices)

            else -> null // La riga è valida
        }
    }

    // --- MODIFICA QUI ---
    private suspend fun getChangedFields(old: Product, new: Product, supplierDao: SupplierDao): List<Int> {
        val fields = mutableListOf<Int>()
        if (!old.productName.equals(new.productName, ignoreCase = true)) fields.add(R.string.field_product_name)

        // CORREZIONE: usiamo R.string.field_second_product_name
        if (old.secondProductName != new.secondProductName) fields.add(R.string.field_second_product_name)

        if (!old.itemNumber.equals(new.itemNumber, ignoreCase = true)) fields.add(R.string.header_item_number)
        if (abs((old.purchasePrice ?: 0.0) - (new.purchasePrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.purchase_price_label)
        if (abs((old.retailPrice ?: 0.0) - (new.retailPrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.retail_price_label)
        if (old.category != new.category) fields.add(R.string.field_category)
        if (abs((old.stockQuantity ?: 0.0) - (new.stockQuantity ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.field_stock_quantity)

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
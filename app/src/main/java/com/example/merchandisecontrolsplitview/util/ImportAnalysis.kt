package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.data.*
import kotlin.math.abs
import kotlin.math.round
import com.example.merchandisecontrolsplitview.R

object ImportAnalyzer {

    private const val MAX_PRODUCT_NAME_LENGTH = 100
    private const val PRICE_COMPARISON_TOLERANCE = 0.001
    private val DEFERRED_RELATION_ROW_KEYS = setOf(
        "barcode",
        "itemNumber",
        "productName",
        "secondProductName",
        "supplier",
        "category",
        "quantity",
        "realQuantity",
        "purchasePrice",
        "retailPrice",
        "discount",
        "discountedPrice",
        "oldPurchasePrice",
        "oldRetailPrice",
        "prevPurchase",
        "prevRetail"
    )

    data class DeferredRelationImportAnalysis(
        val analysis: ImportAnalysis,
        val pendingSuppliers: Map<Long, String>,
        val pendingCategories: Map<Long, String>
    )

    private fun compactDeferredRelationRow(row: Map<String, String>, barcode: String): MutableMap<String, String> {
        val compactRow = linkedMapOf<String, String>()
        compactRow["barcode"] = barcode
        DEFERRED_RELATION_ROW_KEYS.forEach { key ->
            if (key == "barcode") return@forEach
            row[key]
                ?.takeIf { it.isNotEmpty() }
                ?.let { compactRow[key] = it }
        }
        return compactRow
    }

    private fun parseDouble(s: String?): Double? = parseNumber(s)

    private fun round3(x: Double?) = x?.let { round(it * 1000.0) / 1000.0 }

    @Suppress("UNUSED_PARAMETER")
    suspend fun analyze(
        context: Context,
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product>,
        repository: InventoryRepository
    ): ImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()
        val warnings = mutableListOf<DuplicateWarning>()

        val allSuppliers = repository.getAllSuppliers().associateBy { it.id }
        val allCategories = repository.getAllCategories().associateBy { it.id }

        val supplierCacheByName = allSuppliers.values
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()
        val categoryCacheByName = allCategories.values
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()

        suspend fun getOrCreateSupplierId(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isBlank()) return null
            supplierCacheByName[key]?.let { return it.id }
            repository.findSupplierByName(name.trim())?.let {
                supplierCacheByName[key] = it; return it.id
            }
            val created = repository.addSupplier(name.trim()) ?: return null
            supplierCacheByName[key] = created
            return created.id
        }


        suspend fun getOrCreateCategoryId(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isBlank()) return null
            categoryCacheByName[key]?.let { return it.id }
            repository.findCategoryByName(name.trim())?.let {
                categoryCacheByName[key] = it; return it.id
            }
            val created = repository.addCategory(name.trim()) ?: return null
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

                val prevPurchaseFromFile = round3(parseDouble(finalRow["oldPurchasePrice"] ?: finalRow["prevPurchase"]))
                val prevRetailFromFile   = round3(parseDouble(finalRow["oldRetailPrice"]  ?: finalRow["prevRetail"]))

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
                        stockQuantity = quantityToUse ?: 0.0,
                        oldPurchasePrice = prevPurchaseFromFile,
                        oldRetailPrice   = prevRetailFromFile
                    )
                    newProducts.add(newProduct)
                } else {
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        secondProductName = secondProductName ?: existingProduct.secondProductName,
                        supplierId = supplierId ?: existingProduct.supplierId,
                        categoryId = categoryId ?: existingProduct.categoryId,
                        purchasePrice = finalPurchasePrice ?: existingProduct.purchasePrice,
                        retailPrice = retailPriceFromFile ?: existingProduct.retailPrice,
                        stockQuantity = quantityToUse ?: existingProduct.stockQuantity,
                        oldPurchasePrice = prevPurchaseFromFile ?: existingProduct.oldPurchasePrice,
                        oldRetailPrice   = prevRetailFromFile   ?: existingProduct.oldRetailPrice
                    )

                    val changedFields = getChangedFields(existingProduct, updatedProduct, allSuppliers, allCategories)
                    if (changedFields.isNotEmpty()) {
                        updatedProducts.add(ProductUpdate(existingProduct, updatedProduct, changedFields))
                    }


                }
            } catch (ex: Exception) {
                errors.add(unexpectedRowProcessingError(originalRowIndex + 1, finalRow))
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

    /** Analisi "streaming" a chunk: non materializza tutte le righe in RAM. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun analyzeStreaming(
        context: Context,
        chunks: Sequence<List<Map<String, String>>>,
        currentDbProducts: List<Product>,
        repository: InventoryRepository
    ): ImportAnalysis {
        // Cache DB esistente
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }

        // Cache fornitori/categorie: per nome (lowercase) -> entity
        val allSuppliers = repository.getAllSuppliers().associateBy { it.id }
        val allCategories = repository.getAllCategories().associateBy { it.id }
        val supplierCacheByName = allSuppliers.values.associateBy { it.name.trim().lowercase() }.toMutableMap()
        val categoryCacheByName = allCategories.values.associateBy { it.name.trim().lowercase() }.toMutableMap()

        suspend fun getOrCreateSupplierId(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isBlank()) return null
            supplierCacheByName[key]?.let { return it.id }
            repository.findSupplierByName(name.trim())?.let { supplierCacheByName[key] = it; return it.id }
            val created = repository.addSupplier(name.trim()) ?: return null
            supplierCacheByName[key] = created
            return created.id
        }

        suspend fun getOrCreateCategoryId(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isBlank()) return null
            categoryCacheByName[key]?.let { return it.id }
            repository.findCategoryByName(name.trim())?.let { categoryCacheByName[key] = it; return it.id }
            val created = repository.addCategory(name.trim()) ?: return null
            categoryCacheByName[key] = created
            return created.id
        }

        // Accumulatori risultato
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()
        val warnings = mutableListOf<DuplicateWarning>()

        // Per gestire duplicati come nella versione "groupBy":
        // conserviamo SOLO l'ultima riga vista per barcode + somma delle quantità
        data class Pending(
            val lastRow: MutableMap<String, String>,
            val rowNumbers: MutableList<Int>,
            var qtySum: Double
        )
        val pendingByBarcode = LinkedHashMap<String, Pending>()

        var rowIndex = 0
        for (chunk in chunks) {
            for (row in chunk) {
                rowIndex++
                val barcode = row["barcode"]?.trim().orEmpty()
                if (barcode.isBlank()) {
                    errors += RowImportError(rowIndex, row, R.string.error_barcode_required)
                    continue
                }

                val supplierQty = parseDouble(row["quantity"]) ?: 0.0
                val realQty = parseDouble(row["realQuantity"]) ?: 0.0
                val qtyForRow = if (realQty > 0) realQty else supplierQty

                val p = pendingByBarcode[barcode]
                if (p == null) {
                    pendingByBarcode[barcode] = Pending(row.toMutableMap(), mutableListOf(rowIndex), qtyForRow)
                } else {
                    p.lastRow.clear()
                    p.lastRow.putAll(row)          // mantieni l'ULTIMA riga come base
                    p.rowNumbers.add(rowIndex)     // traccia duplicati per warning
                    p.qtySum += qtyForRow          // somma quantità
                }
            }
        }

        // Ora processiamo ogni barcode con la sua riga finale + quantità totale
        for ((barcode, p) in pendingByBarcode) {
            val finalRow = p.lastRow
            finalRow["quantity"] = p.qtySum.toString()
            if (p.rowNumbers.size > 1) {
                warnings += DuplicateWarning(barcode, p.rowNumbers)
            }

            try {
                val itemNumber        = finalRow["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName       = finalRow["productName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val secondProductName = finalRow["secondProductName"]?.trim()?.take(MAX_PRODUCT_NAME_LENGTH)?.takeIf { it.isNotBlank() }
                val supplierName      = finalRow["supplier"]?.trim()?.takeIf { it.isNotBlank() }
                val categoryName      = finalRow["category"]?.trim()?.takeIf { it.isNotBlank() }

                val quantityToUse           = parseDouble(finalRow["quantity"])
                val purchasePriceFromFile   = parseDouble(finalRow["purchasePrice"])
                val retailPriceFromFile     = round3(parseDouble(finalRow["retailPrice"]))
                val discountFromFile        = parseDouble(finalRow["discount"])
                val discountedPriceFromFile = parseDouble(finalRow["discountedPrice"])

                if (discountFromFile != null && (discountFromFile < 0 || discountFromFile > 100)) {
                    errors += RowImportError(p.rowNumbers.last(), finalRow, R.string.error_invalid_discount)
                    continue
                }

                val finalPurchasePrice = round3(
                    when {
                        discountedPriceFromFile != null -> discountedPriceFromFile
                        purchasePriceFromFile != null && discountFromFile != null ->
                            purchasePriceFromFile * (1 - (discountFromFile / 100))
                        else -> purchasePriceFromFile
                    }
                )

                val prevPurchaseFromFile = round3(parseDouble(finalRow["oldPurchasePrice"] ?: finalRow["prevPurchase"]))
                val prevRetailFromFile   = round3(parseDouble(finalRow["oldRetailPrice"]  ?: finalRow["prevRetail"]))

                // Validazione di base (riusa la tua funzione privata)
                val validationError = validateRow(
                    p.rowNumbers.last() - 1, finalRow, barcode, productName, secondProductName, finalPurchasePrice
                )
                if (validationError != null) {
                    errors += validationError
                    continue
                }

                val existing = dbProductByBarcode[barcode]

                // Prezzo vendita richiesto (come nella tua analyze)
                if (existing != null && retailPriceFromFile != null && retailPriceFromFile <= 0.0) {
                    errors += RowImportError(p.rowNumbers.last(), finalRow, R.string.error_invalid_or_missing_retail_price)
                    continue
                }
                if (existing == null && (retailPriceFromFile == null || retailPriceFromFile <= 0.0)) {
                    errors += RowImportError(p.rowNumbers.last(), finalRow, R.string.error_invalid_or_missing_retail_price)
                    continue
                }

                if (quantityToUse != null && quantityToUse < 0) {
                    errors += RowImportError(p.rowNumbers.last(), finalRow, R.string.error_negative_quantity)
                    continue
                }

                val supplierId = supplierName?.let { getOrCreateSupplierId(it) }
                val categoryId = categoryName?.let { getOrCreateCategoryId(it) }

                if (existing == null) {
                    newProducts += Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName!!,
                        secondProductName = secondProductName,
                        purchasePrice = finalPurchasePrice,
                        retailPrice = retailPriceFromFile,
                        supplierId = supplierId,
                        categoryId = categoryId,
                        stockQuantity = quantityToUse ?: 0.0,
                        oldPurchasePrice = prevPurchaseFromFile,
                        oldRetailPrice   = prevRetailFromFile
                    )
                } else {
                    val updated = existing.copy(
                        itemNumber        = itemNumber        ?: existing.itemNumber,
                        productName       = productName       ?: existing.productName,
                        secondProductName = secondProductName ?: existing.secondProductName,
                        supplierId        = supplierId        ?: existing.supplierId,
                        categoryId        = categoryId        ?: existing.categoryId,
                        purchasePrice     = finalPurchasePrice ?: existing.purchasePrice,
                        retailPrice       = retailPriceFromFile ?: existing.retailPrice,
                        stockQuantity     = quantityToUse     ?: existing.stockQuantity,
                        oldPurchasePrice  = prevPurchaseFromFile ?: existing.oldPurchasePrice,
                        oldRetailPrice    = prevRetailFromFile   ?: existing.oldRetailPrice
                    )

                    val changed = getChangedFields(
                        old = existing,
                        new = updated,
                        suppliersById = allSuppliers,
                        categoriesById = allCategories
                    )
                    if (changed.isNotEmpty()) {
                        updatedProducts += ProductUpdate(existing, updated, changed)
                    }
                }
            } catch (ex: Exception) {
                errors += unexpectedRowProcessingError(p.rowNumbers.last(), p.lastRow)
            }
        }

        return ImportAnalysis(newProducts, updatedProducts, errors, warnings)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun analyzeStreamingDeferredRelations(
        context: Context,
        currentDbProducts: List<Product>,
        repository: InventoryRepository,
        rowProducer: ((Map<String, String>) -> Unit) -> Unit
    ): DeferredRelationImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }

        val initialSuppliersById = repository.getAllSuppliers().associateBy { it.id }
        val initialCategoriesById = repository.getAllCategories().associateBy { it.id }
        val suppliersById = initialSuppliersById.toMutableMap()
        val categoriesById = initialCategoriesById.toMutableMap()
        val supplierCacheByName = initialSuppliersById.values
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()
        val categoryCacheByName = initialCategoriesById.values
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()
        val pendingSuppliers = linkedMapOf<Long, String>()
        val pendingCategories = linkedMapOf<Long, String>()
        var nextTempSupplierId = -1L
        var nextTempCategoryId = -1L

        suspend fun resolveSupplierId(name: String): Long? {
            val normalizedName = name.trim()
            val key = normalizedName.lowercase()
            if (key.isBlank()) return null
            supplierCacheByName[key]?.let { return it.id }
            repository.findSupplierByName(normalizedName)?.let {
                supplierCacheByName[key] = it
                suppliersById[it.id] = it
                return it.id
            }

            val tempId = nextTempSupplierId--
            val tempSupplier = Supplier(id = tempId, name = normalizedName)
            supplierCacheByName[key] = tempSupplier
            suppliersById[tempId] = tempSupplier
            pendingSuppliers[tempId] = normalizedName
            return tempId
        }

        suspend fun resolveCategoryId(name: String): Long? {
            val normalizedName = name.trim()
            val key = normalizedName.lowercase()
            if (key.isBlank()) return null
            categoryCacheByName[key]?.let { return it.id }
            repository.findCategoryByName(normalizedName)?.let {
                categoryCacheByName[key] = it
                categoriesById[it.id] = it
                return it.id
            }

            val tempId = nextTempCategoryId--
            val tempCategory = Category(id = tempId, name = normalizedName)
            categoryCacheByName[key] = tempCategory
            categoriesById[tempId] = tempCategory
            pendingCategories[tempId] = normalizedName
            return tempId
        }

        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()
        val warnings = mutableListOf<DuplicateWarning>()

        data class Pending(
            val lastRow: MutableMap<String, String>,
            val rowNumbers: MutableList<Int>,
            var qtySum: Double
        )

        val pendingByBarcode = LinkedHashMap<String, Pending>()
        var rowIndex = 0

        rowProducer { row ->
            rowIndex++
            val barcode = row["barcode"]?.trim().orEmpty()
            if (barcode.isBlank()) {
                errors += RowImportError(rowIndex, row, R.string.error_barcode_required)
                return@rowProducer
            }

            val supplierQty = parseDouble(row["quantity"]) ?: 0.0
            val realQty = parseDouble(row["realQuantity"]) ?: 0.0
            val qtyForRow = if (realQty > 0) realQty else supplierQty

            val pending = pendingByBarcode[barcode]
            if (pending == null) {
                pendingByBarcode[barcode] = Pending(
                    lastRow = compactDeferredRelationRow(row, barcode),
                    rowNumbers = mutableListOf(rowIndex),
                    qtySum = qtyForRow
                )
            } else {
                pending.lastRow.clear()
                pending.lastRow.putAll(compactDeferredRelationRow(row, barcode))
                pending.rowNumbers.add(rowIndex)
                pending.qtySum += qtyForRow
            }
        }

        for ((barcode, pending) in pendingByBarcode) {
            val finalRow = pending.lastRow
            finalRow["quantity"] = pending.qtySum.toString()
            if (pending.rowNumbers.size > 1) {
                warnings += DuplicateWarning(barcode, pending.rowNumbers)
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
                val discountedPriceFromFile = parseDouble(finalRow["discountedPrice"])

                if (discountFromFile != null && (discountFromFile < 0 || discountFromFile > 100)) {
                    errors += RowImportError(pending.rowNumbers.last(), finalRow, R.string.error_invalid_discount)
                    continue
                }

                val finalPurchasePrice = round3(
                    when {
                        discountedPriceFromFile != null -> discountedPriceFromFile
                        purchasePriceFromFile != null && discountFromFile != null ->
                            purchasePriceFromFile * (1 - (discountFromFile / 100))
                        else -> purchasePriceFromFile
                    }
                )

                val prevPurchaseFromFile = round3(parseDouble(finalRow["oldPurchasePrice"] ?: finalRow["prevPurchase"]))
                val prevRetailFromFile = round3(parseDouble(finalRow["oldRetailPrice"] ?: finalRow["prevRetail"]))

                val validationError = validateRow(
                    pending.rowNumbers.last() - 1,
                    finalRow,
                    barcode,
                    productName,
                    secondProductName,
                    finalPurchasePrice
                )
                if (validationError != null) {
                    errors += validationError
                    continue
                }

                val existing = dbProductByBarcode[barcode]
                if (existing != null && retailPriceFromFile != null && retailPriceFromFile <= 0.0) {
                    errors += RowImportError(pending.rowNumbers.last(), finalRow, R.string.error_invalid_or_missing_retail_price)
                    continue
                }
                if (existing == null && (retailPriceFromFile == null || retailPriceFromFile <= 0.0)) {
                    errors += RowImportError(pending.rowNumbers.last(), finalRow, R.string.error_invalid_or_missing_retail_price)
                    continue
                }
                if (quantityToUse != null && quantityToUse < 0) {
                    errors += RowImportError(pending.rowNumbers.last(), finalRow, R.string.error_negative_quantity)
                    continue
                }

                val supplierId = supplierName?.let { resolveSupplierId(it) }
                val categoryId = categoryName?.let { resolveCategoryId(it) }

                if (existing == null) {
                    newProducts += Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName!!,
                        secondProductName = secondProductName,
                        purchasePrice = finalPurchasePrice,
                        retailPrice = retailPriceFromFile,
                        supplierId = supplierId,
                        categoryId = categoryId,
                        stockQuantity = quantityToUse ?: 0.0,
                        oldPurchasePrice = prevPurchaseFromFile,
                        oldRetailPrice = prevRetailFromFile
                    )
                } else {
                    val updated = existing.copy(
                        itemNumber = itemNumber ?: existing.itemNumber,
                        productName = productName ?: existing.productName,
                        secondProductName = secondProductName ?: existing.secondProductName,
                        supplierId = supplierId ?: existing.supplierId,
                        categoryId = categoryId ?: existing.categoryId,
                        purchasePrice = finalPurchasePrice ?: existing.purchasePrice,
                        retailPrice = retailPriceFromFile ?: existing.retailPrice,
                        stockQuantity = quantityToUse ?: existing.stockQuantity,
                        oldPurchasePrice = prevPurchaseFromFile ?: existing.oldPurchasePrice,
                        oldRetailPrice = prevRetailFromFile ?: existing.oldRetailPrice
                    )

                    val changed = getChangedFields(
                        old = existing,
                        new = updated,
                        suppliersById = suppliersById,
                        categoriesById = categoriesById
                    )
                    if (changed.isNotEmpty()) {
                        updatedProducts += ProductUpdate(existing, updated, changed)
                    }
                }
            } catch (ex: Exception) {
                errors += unexpectedRowProcessingError(pending.rowNumbers.last(), pending.lastRow)
            }
        }

        return DeferredRelationImportAnalysis(
            analysis = ImportAnalysis(newProducts, updatedProducts, errors, warnings),
            pendingSuppliers = pendingSuppliers,
            pendingCategories = pendingCategories
        )
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

    private fun unexpectedRowProcessingError(
        rowNumber: Int,
        rowContent: Map<String, String>
    ) = RowImportError(
        rowNumber = rowNumber,
        rowContent = rowContent,
        errorReasonResId = R.string.error_import_row_processing_failed
    )
}

package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.*
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object ImportAnalyzer {

    /** Massimo numeri riga elencati per barcode duplicato (il totale resta in [DuplicateWarning.totalOccurrences]). */
    private const val MAX_DUPLICATE_ROW_NUMBERS_LISTED = 50
    private const val PRICE_COMPARISON_TOLERANCE = 0.001
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val DEFERRED_RELATION_ROW_KEYS = setOf(
        "barcode",
        "itemNumber",
        "productName",
        "secondProductName",
        "supplier",
        "category",
        "rowNumber",
        "quantity",
        "realQuantity",
        "purchasePrice",
        "retailPrice",
        "discount",
        "discountedPrice",
        "oldPurchasePrice",
        "oldRetailPrice"
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
        return analyzeStreamingDeferredRelations(
            context = context,
            currentDbProducts = currentDbProducts,
            repository = repository
        ) { consumer ->
            importedRows.forEach(consumer)
        }.analysis
    }

    private fun validateRow(
        rowIndex: Int, row: Map<String, String>, barcode: String,
        itemNumber: String?, productName: String?, secondProductName: String?,
        purchasePrice: Double?
    ): RowImportError? {
        return when {
            barcode.isBlank() -> RowImportError(rowIndex + 1, row, R.string.error_barcode_required)
            itemNumber.isNullOrBlank() && productName.isNullOrBlank() && secondProductName.isNullOrBlank() -> RowImportError(rowIndex + 1, row, R.string.error_productname_required_at_least_one)
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
        return analyzeStreamingDeferredRelations(
            context = context,
            currentDbProducts = currentDbProducts,
            repository = repository
        ) { consumer ->
            chunks.forEach { chunk -> chunk.forEach(consumer) }
        }.analysis
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun analyzeStreamingDeferredRelations(
        context: Context,
        currentDbProducts: List<Product>,
        repository: InventoryRepository,
        rowProducer: ((Map<String, String>) -> Unit) -> Unit
    ): DeferredRelationImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { normalizedImportKey(it.barcode) }

        val initialSuppliersById = repository.getAllSuppliers().associateBy { it.id }
        val initialCategoriesById = repository.getAllCategories().associateBy { it.id }
        val suppliersById = initialSuppliersById.toMutableMap()
        val categoriesById = initialCategoriesById.toMutableMap()
        val supplierCacheByName = initialSuppliersById.values
            .associateBy { normalizedRelationKey(it.name) }
            .toMutableMap()
        val categoryCacheByName = initialCategoriesById.values
            .associateBy { normalizedRelationKey(it.name) }
            .toMutableMap()
        val pendingSuppliers = linkedMapOf<Long, String>()
        val pendingCategories = linkedMapOf<Long, String>()
        var nextTempSupplierId = -1L
        var nextTempCategoryId = -1L

        suspend fun resolveSupplierId(name: String): Long? {
            val normalizedName = name.trim()
            val key = normalizedRelationKey(normalizedName)
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
            val key = normalizedRelationKey(normalizedName)
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

        suspend fun resolveSupplierIdForExisting(existing: Product, name: String): Long? {
            val currentName = existing.supplierId?.let { suppliersById[it]?.name }
            if (existing.supplierId != null && semanticRelationNameEquals(currentName, name)) {
                return existing.supplierId
            }
            return resolveSupplierId(name)
        }

        suspend fun resolveCategoryIdForExisting(existing: Product, name: String): Long? {
            val currentName = existing.categoryId?.let { categoriesById[it]?.name }
            if (existing.categoryId != null && semanticRelationNameEquals(currentName, name)) {
                return existing.categoryId
            }
            return resolveCategoryId(name)
        }

        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()
        val warnings = mutableListOf<DuplicateWarning>()
        val textNormalizationWarnings = mutableListOf<CatalogTextNormalizationWarning>()

        data class Pending(
            val lastRow: MutableMap<String, String>,
            val sampledRowNumbers: MutableList<Int>,
            val normalizedFields: MutableSet<CatalogTextField>,
            val firstRawBarcode: String,
            var totalOccurrences: Int,
            var lastRowNumber: Int,
            var hasBarcodeTrimCollision: Boolean = false,
            var itemNumberCollisionError: RowImportError? = null
        )

        fun appendSampleRowNumber(sampledRowNumbers: MutableList<Int>, rowNumber: Int) {
            if (sampledRowNumbers.size < MAX_DUPLICATE_ROW_NUMBERS_LISTED) {
                sampledRowNumbers.add(rowNumber)
            } else {
                // Keep the newest row visible in the compact sample: the last row wins semantically.
                sampledRowNumbers[MAX_DUPLICATE_ROW_NUMBERS_LISTED - 1] = rowNumber
            }
        }

        val pendingByBarcode = LinkedHashMap<String, Pending>()
        data class ItemIdentityGroup(
            val firstRaw: String,
            val barcodes: MutableSet<String>,
            var collided: Boolean = false
        )

        val itemIdentityGroups = linkedMapOf<String, ItemIdentityGroup>()
        val itemCollisionBarcodes = linkedSetOf<String>()
        var rowIndex = 0

        rowProducer { row ->
            rowIndex++
            val sourceRowNumber = parseDouble(row["rowNumber"])?.toInt() ?: rowIndex
            val rawBarcode = row["barcode"].orEmpty()
            val barcodeFields = linkedSetOf<CatalogTextField>()
            val barcode = when (
                val outcome = CatalogTextPolicy.strict(
                    raw = rawBarcode,
                    required = true,
                    maxLength = CatalogTextPolicy.Limits.BARCODE
                )
            ) {
                is CatalogTextPolicy.Outcome.Unchanged -> outcome.value
                is CatalogTextPolicy.Outcome.Normalized -> {
                    barcodeFields += CatalogTextField.BARCODE
                    outcome.value
                }
                is CatalogTextPolicy.Outcome.Rejected -> {
                    errors += catalogTextRowError(
                        context = context,
                        rowNumber = sourceRowNumber,
                        row = row,
                        field = CatalogTextField.BARCODE,
                        reason = outcome.reason
                    )
                    return@rowProducer
                }
            }

            val pending = pendingByBarcode[barcode]
            if (pending == null) {
                pendingByBarcode[barcode] = Pending(
                    lastRow = compactDeferredRelationRow(row, barcode),
                    sampledRowNumbers = mutableListOf(sourceRowNumber),
                    normalizedFields = barcodeFields,
                    firstRawBarcode = rawBarcode,
                    totalOccurrences = 1,
                    lastRowNumber = sourceRowNumber
                )
            } else {
                if (pending.firstRawBarcode != rawBarcode) {
                    pending.hasBarcodeTrimCollision = true
                }
                pending.lastRow.clear()
                pending.lastRow.putAll(compactDeferredRelationRow(row, barcode))
                pending.normalizedFields.clear()
                pending.normalizedFields.addAll(barcodeFields)
                pending.totalOccurrences += 1
                pending.lastRowNumber = sourceRowNumber
                appendSampleRowNumber(pending.sampledRowNumbers, sourceRowNumber)
            }

            val rawItemNumber = row["itemNumber"] ?: return@rowProducer
            val itemNumber = when (
                val outcome = CatalogTextPolicy.strict(
                    raw = rawItemNumber,
                    required = false,
                    maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
                )
            ) {
                is CatalogTextPolicy.Outcome.Unchanged -> outcome.value
                is CatalogTextPolicy.Outcome.Normalized -> outcome.value
                is CatalogTextPolicy.Outcome.Rejected -> return@rowProducer
            }
            if (itemNumber.isEmpty()) return@rowProducer

            val group = itemIdentityGroups[itemNumber]
            if (group == null) {
                itemIdentityGroups[itemNumber] = ItemIdentityGroup(
                    firstRaw = rawItemNumber,
                    barcodes = linkedSetOf(barcode)
                )
            } else {
                group.barcodes += barcode
                if (group.firstRaw != rawItemNumber) {
                    group.collided = true
                }
                if (group.collided) {
                    group.barcodes.forEach { collidedBarcode ->
                        itemCollisionBarcodes += collidedBarcode
                        val collidedPending = pendingByBarcode[collidedBarcode]
                            ?: return@forEach
                        if (collidedPending.itemNumberCollisionError == null) {
                            collidedPending.itemNumberCollisionError = catalogTextRowError(
                                context = context,
                                rowNumber = collidedPending.lastRowNumber,
                                row = collidedPending.lastRow,
                                field = CatalogTextField.ITEM_NUMBER,
                                reason = CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM
                            )
                        }
                    }
                }
            }
        }

        for ((barcode, pending) in pendingByBarcode) {
            val finalRow = pending.lastRow
            if (pending.hasBarcodeTrimCollision) {
                errors += catalogTextRowError(
                    context = context,
                    rowNumber = pending.lastRowNumber,
                    row = finalRow,
                    field = CatalogTextField.BARCODE,
                    reason = CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM
                )
                continue
            }
            if (barcode in itemCollisionBarcodes) {
                errors += pending.itemNumberCollisionError ?: catalogTextRowError(
                    context = context,
                    rowNumber = pending.lastRowNumber,
                    row = finalRow,
                    field = CatalogTextField.ITEM_NUMBER,
                    reason = CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM
                )
                continue
            }
            if (pending.totalOccurrences > 1) {
                warnings += DuplicateWarning(
                    barcode = barcode,
                    rowNumbers = pending.sampledRowNumbers.toList(),
                    totalOccurrences = pending.totalOccurrences
                )
            }

            try {
                var rejectedText = false
                val normalizedFields = pending.normalizedFields.toMutableSet()

                fun acceptedText(
                    raw: String?,
                    field: CatalogTextField,
                    outcome: (String) -> CatalogTextPolicy.Outcome
                ): String? {
                    if (raw == null) return null
                    return when (val result = outcome(raw)) {
                        is CatalogTextPolicy.Outcome.Unchanged -> result.value.takeIf { it.isNotEmpty() }
                        is CatalogTextPolicy.Outcome.Normalized -> {
                            normalizedFields += field
                            result.value.takeIf { it.isNotEmpty() }
                        }
                        is CatalogTextPolicy.Outcome.Rejected -> {
                            errors += catalogTextRowError(
                                context = context,
                                rowNumber = pending.lastRowNumber,
                                row = finalRow,
                                field = field,
                                reason = result.reason
                            )
                            rejectedText = true
                            null
                        }
                    }
                }

                val itemNumber = acceptedText(finalRow["itemNumber"], CatalogTextField.ITEM_NUMBER) {
                    CatalogTextPolicy.strict(
                        it,
                        required = false,
                        maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
                    )
                }
                val productName = acceptedText(finalRow["productName"], CatalogTextField.PRODUCT_NAME) {
                    CatalogTextPolicy.display(
                        it,
                        required = false,
                        maxLength = CatalogTextPolicy.Limits.PRODUCT_NAME
                    )
                }
                val secondProductName = acceptedText(
                    finalRow["secondProductName"],
                    CatalogTextField.SECOND_PRODUCT_NAME
                ) {
                    CatalogTextPolicy.display(
                        it,
                        required = false,
                        maxLength = CatalogTextPolicy.Limits.SECOND_PRODUCT_NAME
                    )
                }
                val supplierName = acceptedText(finalRow["supplier"], CatalogTextField.SUPPLIER_NAME) {
                    CatalogTextPolicy.display(
                        it,
                        required = false,
                        maxLength = CatalogTextPolicy.Limits.SUPPLIER_NAME
                    )
                }
                val categoryName = acceptedText(finalRow["category"], CatalogTextField.CATEGORY_NAME) {
                    CatalogTextPolicy.display(
                        it,
                        required = false,
                        maxLength = CatalogTextPolicy.Limits.CATEGORY_NAME
                    )
                }
                if (rejectedText) continue

                val quantityToUse = parseDouble(finalRow["realQuantity"]) ?: parseDouble(finalRow["quantity"])
                val purchasePriceFromFile = parseDouble(finalRow["purchasePrice"])
                val retailPriceFromFile = round3(parseDouble(finalRow["retailPrice"]))
                val discountFromFile = parseDouble(finalRow["discount"])
                val discountedPriceFromFile = parseDouble(finalRow["discountedPrice"])

                if (discountFromFile != null && (discountFromFile < 0 || discountFromFile > 100)) {
                    errors += RowImportError(pending.lastRowNumber, finalRow, R.string.error_invalid_discount)
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

                val prevPurchaseFromFile = round3(parseDouble(finalRow["oldPurchasePrice"]))
                val prevRetailFromFile = round3(parseDouble(finalRow["oldRetailPrice"]))

                val validationError = validateRow(
                    pending.lastRowNumber - 1,
                    finalRow,
                    barcode,
                    itemNumber,
                    productName,
                    secondProductName,
                    finalPurchasePrice
                )
                if (validationError != null) {
                    errors += validationError
                    continue
                }

                if (normalizedFields.isNotEmpty()) {
                    textNormalizationWarnings += CatalogTextNormalizationWarning(
                        rowNumber = pending.lastRowNumber,
                        fields = normalizedFields
                    )
                }

                val existing = dbProductByBarcode[normalizedImportKey(barcode)]
                if (existing != null && retailPriceFromFile != null && retailPriceFromFile <= 0.0) {
                    errors += RowImportError(pending.lastRowNumber, finalRow, R.string.error_invalid_or_missing_retail_price)
                    continue
                }
                if (existing == null && (retailPriceFromFile == null || retailPriceFromFile <= 0.0)) {
                    errors += RowImportError(pending.lastRowNumber, finalRow, R.string.error_invalid_or_missing_retail_price)
                    continue
                }
                if (quantityToUse != null && quantityToUse < 0) {
                    errors += RowImportError(pending.lastRowNumber, finalRow, R.string.error_negative_quantity)
                    continue
                }

                if (existing == null) {
                    val supplierId = supplierName?.let { resolveSupplierId(it) }
                    val categoryId = categoryName?.let { resolveCategoryId(it) }
                    newProducts += Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName ?: secondProductName ?: itemNumber.orEmpty(),
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
                    val supplierId = supplierName?.let { resolveSupplierIdForExisting(existing, it) }
                    val categoryId = categoryName?.let { resolveCategoryIdForExisting(existing, it) }
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
                errors += unexpectedRowProcessingError(pending.lastRowNumber, pending.lastRow)
            }
        }

        return DeferredRelationImportAnalysis(
            analysis = ImportAnalysis(
                newProducts = newProducts,
                updatedProducts = updatedProducts,
                errors = errors,
                warnings = warnings,
                textNormalizationWarnings = textNormalizationWarnings
            ),
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

        if (!semanticImportTextEquals(old.productName, new.productName)) fields.add(R.string.field_product_name)
        if (!semanticImportTextEquals(old.secondProductName, new.secondProductName)) fields.add(R.string.field_second_product_name)
        if (!semanticImportTextEquals(old.itemNumber, new.itemNumber)) fields.add(R.string.header_item_number)

        if (abs((old.purchasePrice ?: 0.0) - (new.purchasePrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.purchase_price_label)
        if (abs((old.retailPrice ?: 0.0) - (new.retailPrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.retail_price_label)
        if (abs((old.stockQuantity ?: 0.0) - (new.stockQuantity ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add(R.string.field_stock_quantity)

        if (old.supplierId != new.supplierId) {
            val oldSupplierName = old.supplierId?.let { suppliersById[it]?.name }
            val newSupplierName = new.supplierId?.let { suppliersById[it]?.name }
            if (!semanticRelationNameEquals(oldSupplierName, newSupplierName)) {
                fields.add(R.string.field_supplier)
            }
        }

        if (old.categoryId != new.categoryId) {
            val oldCategoryName = old.categoryId?.let { categoriesById[it]?.name }
            val newCategoryName = new.categoryId?.let { categoriesById[it]?.name }
            if (!semanticRelationNameEquals(oldCategoryName, newCategoryName)) {
                fields.add(R.string.field_category)
            }
        }
        return fields
    }

    private fun semanticImportTextEquals(old: String?, new: String?): Boolean =
        old?.trim().orEmpty().equals(new?.trim().orEmpty(), ignoreCase = true)

    private fun semanticRelationNameEquals(old: String?, new: String?): Boolean =
        normalizedRelationKey(old) == normalizedRelationKey(new)

    private fun normalizedImportKey(value: String?): String =
        value?.trim().orEmpty().lowercase()

    private fun normalizedRelationKey(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        return COMBINING_MARKS.replace(decomposed, "").lowercase(Locale.ROOT)
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

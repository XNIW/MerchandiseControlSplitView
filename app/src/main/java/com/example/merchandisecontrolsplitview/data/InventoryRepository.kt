package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

// ⬇️ aggiungi subito sotto gli import esistenti (prima dell'interfaccia)
data class PriceHistoryExportRow(
    val barcode: String,
    val timestamp: String, // "yyyy-MM-dd HH:mm:ss"
    val type: String,      // "PURCHASE" | "RETAIL"
    val price: Double,
    val source: String?
)
data class CurrentPriceRow(
    val productId: Long,
    val barcode: String,
    val purchasePrice: Double?,
    val retailPrice: Double?
)
interface InventoryRepository {
    // Product methods
    fun getProductsWithDetailsPaged(filter: String?): PagingSource<Int, ProductWithDetails>
    suspend fun findProductByBarcode(barcode: String): Product?
    suspend fun findProductsByBarcodes(barcodes: List<String>): List<Product>
    suspend fun getAllProducts(): List<Product>
    suspend fun addProduct(product: Product)
    suspend fun updateProduct(product: Product)
    suspend fun deleteProduct(product: Product)
    suspend fun applyImport(request: ImportApplyRequest): ImportApplyResult

    // Supplier methods
    suspend fun getSupplierById(id: Long): Supplier?
    suspend fun findSupplierByName(name: String): Supplier?
    suspend fun getAllSuppliers(): List<Supplier>
    suspend fun searchSuppliersByName(query: String): List<Supplier>
    suspend fun addSupplier(name: String): Supplier?

    // Category methods
    suspend fun getCategoryById(id: Long): Category?
    suspend fun findCategoryByName(name: String): Category?
    suspend fun getAllCategories(): List<Category>
    suspend fun searchCategoriesByName(query: String): List<Category>
    suspend fun addCategory(name: String): Category?

    // History methods
    fun getFilteredHistoryFlow(filter: DateFilter): Flow<List<HistoryEntry>>
    fun getFilteredHistoryListFlow(filter: DateFilter): Flow<List<HistoryEntryListItem>>
    fun hasHistoryEntriesFlow(): Flow<Boolean>
    suspend fun getHistoryEntryByUid(uid: Long): HistoryEntry?
    suspend fun insertHistoryEntry(entry: HistoryEntry): Long
    suspend fun updateHistoryEntry(entry: HistoryEntry)
    suspend fun deleteHistoryEntry(entry: HistoryEntry)
    suspend fun recordPriceIfChanged(productId: Long, type: String, price: Double, at: String, source: String?)
    suspend fun getLastPrice(productId: Long, type: String): Double?
    suspend fun getLastPriceBefore(productId: Long, type: String, before: String): Double?
    fun getPriceSeries(productId: Long, type: String): Flow<List<ProductPrice>>
    suspend fun getPreviousPricesForBarcodes(barcodes: List<String>, at: String): Map<String, Pair<Double?, Double?>>
    suspend fun getAllProductsWithDetails(): List<ProductWithDetails>
    // ⬇️ nell'interfaccia InventoryRepository, aggiungi:
    // PriceHistory export
    suspend fun getAllPriceHistoryRows(): List<PriceHistoryExportRow>
    suspend fun getAllProductsLite(): List<ProductDao.ProductLite>
    suspend fun recordPriceHistoryByBarcodeBatch(
        rows: List<Triple<String /*barcode*/, String /*type*/, Pair<String /*ts*/, Double /*price*/>>>,
        source: String = "IMPORT_SHEET"
    )
    /** Mappa “barcode → (purchase?, retail?)” con i prezzi correnti (1 sola query) */
    suspend fun getCurrentPricesForBarcodes(barcodes: List<String>): Map<String, Pair<Double?, Double?>>

    /** Snapshot “tutto il listino attuale” (utile per export/listino) */
    suspend fun getCurrentPriceSnapshot(): List<CurrentPriceRow>
}

internal object DefaultInventoryRepositoryTestHooks {
    @Volatile
    var afterProductsPersisted: (suspend () -> Unit)? = null
}

class DefaultInventoryRepository(private val db: AppDatabase) : InventoryRepository {
    private val productDao: ProductDao = db.productDao()
    private val supplierDao: SupplierDao = db.supplierDao()
    private val categoryDao: CategoryDao = db.categoryDao()
    private val historyDao: HistoryEntryDao = db.historyEntryDao()
    private val priceDao: ProductPriceDao = db.productPriceDao()
    private val tSFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val applyImportMutex = Mutex()
    // --- Product Implementations ---
    override fun getProductsWithDetailsPaged(filter: String?) = productDao.getAllWithDetailsPaged(filter)
    override suspend fun findProductByBarcode(barcode: String) = withContext(Dispatchers.IO) { productDao.findByBarcode(barcode) }
    override suspend fun findProductsByBarcodes(barcodes: List<String>) = withContext(Dispatchers.IO) { productDao.findByBarcodes(barcodes) }
    override suspend fun getAllProducts(): List<Product> = withContext(Dispatchers.IO) { productDao.getAll() }
    override suspend fun addProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.insert(product)
            val persisted = productDao.findByBarcode(product.barcode) ?: return@withContext

            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let { priceDao.insertIfChanged(persisted.id, "PURCHASE", it, now, "MANUAL") }
            product.retailPrice  ?.let { priceDao.insertIfChanged(persisted.id, "RETAIL",   it, now, "MANUAL") }
        }
    }
    override suspend fun updateProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.update(product)

            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let { priceDao.insertIfChanged(product.id, "PURCHASE", it, now, "MANUAL") }
            product.retailPrice  ?.let { priceDao.insertIfChanged(product.id, "RETAIL",   it, now, "MANUAL") }
        }
    }
    override suspend fun getAllProductsWithDetails(): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getAllWithDetailsOnce() }
    override suspend fun deleteProduct(product: Product) = withContext(Dispatchers.IO) { productDao.delete(product) }
    override suspend fun applyImport(request: ImportApplyRequest): ImportApplyResult =
        withContext(Dispatchers.IO) {
            if (!applyImportMutex.tryLock()) {
                return@withContext ImportApplyResult.AlreadyRunning
            }

            try {
                db.withTransaction {
                    applyImportAtomically(request)
                }
                ImportApplyResult.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                ImportApplyResult.Failure(throwable)
            } finally {
                applyImportMutex.unlock()
            }
        }

    // --- Supplier Implementations ---
    override suspend fun getSupplierById(id: Long) =
        withContext(Dispatchers.IO) { supplierDao.getById(id) }
    override suspend fun findSupplierByName(name: String): Supplier? =
        withContext(Dispatchers.IO) { supplierDao.findByName(name) }
    override suspend fun getAllSuppliers(): List<Supplier> =
        withContext(Dispatchers.IO) { supplierDao.getAll() }
    override suspend fun searchSuppliersByName(query: String) = withContext(Dispatchers.IO) { supplierDao.searchByName(query) }

    private val supplierMutex = Mutex()
    override suspend fun addSupplier(name: String): Supplier? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        supplierMutex.withLock {
            supplierDao.findByName(name) ?: run {
                val newSupplier = Supplier(name = name)
                supplierDao.insert(newSupplier)
                supplierDao.findByName(name)
            }
        }
    }
    override suspend fun recordPriceIfChanged(
        productId: Long,
        type: String,
        price: Double,
        at: String,
        source: String?
    ) = withContext(Dispatchers.IO) {
        priceDao.insertIfChanged(productId, type, price, at, source)
    }

    override suspend fun getLastPrice(productId: Long, type: String): Double? =
        withContext(Dispatchers.IO) { priceDao.getLast(productId, type)?.price }

    override suspend fun getLastPriceBefore(productId: Long, type: String, before: String): Double? =
        withContext(Dispatchers.IO) { priceDao.getLastBefore(productId, type, before)?.price }

    override fun getPriceSeries(productId: Long, type: String): Flow<List<ProductPrice>> =
        priceDao.getSeries(productId, type)

    override suspend fun getPreviousPricesForBarcodes(
        barcodes: List<String>,
        at: String
    ): Map<String, Pair<Double?, Double?>> = withContext(Dispatchers.IO) {
        if (barcodes.isEmpty()) return@withContext emptyMap()

        // Explicitly define the type here -> row: ProductDao.PrevPricesRow
        productDao.getPreviousPricesForBarcodes(barcodes, at)
            .associate { row: ProductDao.PrevPricesRow ->
                row.barcode to (row.prevPurchase to row.prevRetail)
            }
    }

    // --- Category Implementations ---
    override suspend fun getCategoryById(id: Long) = withContext(Dispatchers.IO) { categoryDao.getById(id) }
    override suspend fun findCategoryByName(name: String): Category? = withContext(Dispatchers.IO) { categoryDao.findByName(name) }
    override suspend fun getAllCategories(): List<Category> = withContext(Dispatchers.IO) { categoryDao.getAll() }
    override suspend fun searchCategoriesByName(query: String) = withContext(Dispatchers.IO) { categoryDao.searchByName(query) }

    private val categoryMutex = Mutex()
    override suspend fun addCategory(name: String): Category? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        categoryMutex.withLock {
            categoryDao.findByName(name) ?: run {
                val newCategory = Category(name = name)
                categoryDao.insert(newCategory)
                categoryDao.findByName(name)
            }
        }
    }

    // --- History Implementations ---
    private fun historyRangeFor(filter: DateFilter): Pair<String, String>? {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return when (filter) {
            is DateFilter.All -> null
            is DateFilter.LastMonth -> {
                val today = LocalDate.now()
                val startOfMonth = today.withDayOfMonth(1).atStartOfDay().format(formatter)
                val endOfMonth = today.withDayOfMonth(today.lengthOfMonth()).atTime(23, 59, 59).format(formatter)
                startOfMonth to endOfMonth
            }
            is DateFilter.PreviousMonth -> {
                val previousMonth = YearMonth.from(LocalDate.now()).minusMonths(1)
                val startOfPreviousMonth = previousMonth.atDay(1).atStartOfDay().format(formatter)
                val endOfPreviousMonth = previousMonth.atEndOfMonth().atTime(23, 59, 59).format(formatter)
                startOfPreviousMonth to endOfPreviousMonth
            }
            is DateFilter.CustomRange -> {
                val startDateString = filter.startDate.atStartOfDay().format(formatter)
                val endDateString = filter.endDate.atTime(23, 59, 59).format(formatter)
                startDateString to endDateString
            }
        }
    }

    override fun getFilteredHistoryFlow(filter: DateFilter): Flow<List<HistoryEntry>> {
        val range = historyRangeFor(filter)
        return if (range == null) {
            historyDao.getAllFlow()
        } else {
            historyDao.getEntriesBetweenDatesFlow(range.first, range.second)
        }
    }

    override fun getFilteredHistoryListFlow(filter: DateFilter): Flow<List<HistoryEntryListItem>> {
        val range = historyRangeFor(filter)
        return if (range == null) {
            historyDao.getAllListItemsFlow()
        } else {
            historyDao.getListItemsBetweenDatesFlow(range.first, range.second)
        }
    }

    override fun hasHistoryEntriesFlow(): Flow<Boolean> = historyDao.hasEntriesFlow()

    override suspend fun getHistoryEntryByUid(uid: Long) = withContext(Dispatchers.IO) { historyDao.getByUid(uid) }
    override suspend fun insertHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) { historyDao.insert(entry) }
    override suspend fun updateHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) { historyDao.update(entry) }
    override suspend fun deleteHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) { historyDao.delete(entry) }
    // ⬇️ in DefaultInventoryRepository, aggiungi l'implementazione:
    override suspend fun getAllPriceHistoryRows(): List<PriceHistoryExportRow> =
        withContext(Dispatchers.IO) {
            val rows = priceDao.getAllWithBarcode()  // vedi DAO al punto 3
            rows.map { r ->
                PriceHistoryExportRow(
                    barcode = r.barcode,
                    timestamp = r.effectiveAt,
                    type = r.type,
                    price = r.price,
                    source = r.source
                )
            }
        }

    override suspend fun getAllProductsLite(): List<ProductDao.ProductLite> =
        withContext(Dispatchers.IO) { productDao.getAllLite() }
    override suspend fun recordPriceHistoryByBarcodeBatch(
        rows: List<Triple<String, String, Pair<String, Double>>>,
        source: String
    ) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val barcodes = rows.map { it.first }.distinct()
        val products = productDao.findByBarcodes(barcodes).associateBy { it.barcode }
        val points = rows.mapNotNull { (barcode, type, tsPrice) ->
            val p = products[barcode] ?: return@mapNotNull null
            ProductPrice(
                productId = p.id,
                type = type,
                price = tsPrice.second,
                effectiveAt = tsPrice.first,
                source = source
            )
        }
        if (points.isNotEmpty()) priceDao.insertAll(points)
    }
    override suspend fun getCurrentPricesForBarcodes(
        barcodes: List<String>
    ): Map<String, Pair<Double?, Double?>> = withContext(Dispatchers.IO) {
        if (barcodes.isEmpty()) return@withContext emptyMap()

        // 1) Prendo i prodotti e mappo id↔barcode
        val products = productDao.findByBarcodes(barcodes)
        if (products.isEmpty()) return@withContext emptyMap()

        val barcodeById = products.associate { it.id to it.barcode }

        // 2) UNA SOLA QUERY per tutti gli ID toccati
        val latest = priceDao.getLatestForProducts(products.map { it.id })

        // 3) Compongo la mappa barcode → (purchase?, retail?)
        val out = mutableMapOf<String, Pair<Double?, Double?>>()
        latest.forEach { row ->
            val bc = barcodeById[row.productId] ?: return@forEach
            val cur = out[bc] ?: (null to null)
            out[bc] = when (row.type) {
                "PURCHASE" -> row.price to cur.second
                "RETAIL"   -> cur.first to row.price
                else       -> cur
            }
        }
        // Garantisco key per tutti i barcodes richiesti
        barcodes.forEach { bc -> out.putIfAbsent(bc, null to null) }
        out
    }

    override suspend fun getCurrentPriceSnapshot(): List<CurrentPriceRow> = withContext(Dispatchers.IO) {
        // 1) Ultimi prezzi per tutti i prodotti (una query)
        val latest = priceDao.getLatestPerProductAndType(listOf("PURCHASE", "RETAIL"))

        // 2) Per barcode serve una sola lettura dei prodotti (no N+1)
        val allProducts = productDao.getAll()
        val bcById = allProducts.associate { it.id to it.barcode }

        // 3) Aggrego per prodotto
        val grouped = latest.groupBy { it.productId }
        grouped.map { (pid, rows) ->
            val purchase = rows.find { it.type == "PURCHASE" }?.price
            val retail   = rows.find { it.type == "RETAIL" }?.price
            CurrentPriceRow(
                productId = pid,
                barcode = bcById[pid].orEmpty(),
                purchasePrice = purchase,
                retailPrice = retail
            )
        }
    }

    private suspend fun applyImportAtomically(request: ImportApplyRequest) {
        val supplierIdsByName = supplierDao.getAll()
            .associate { it.name.trim().lowercase() to it.id }
            .toMutableMap()
        val categoryIdsByName = categoryDao.getAll()
            .associate { it.name.trim().lowercase() to it.id }
            .toMutableMap()

        suspend fun resolveSupplierIdByName(name: String): Long? {
            val normalizedName = name.trim()
            val key = normalizedName.lowercase()
            if (key.isBlank()) return null
            supplierIdsByName[key]?.let { return it }

            supplierDao.findByName(normalizedName)?.id?.let { existingId ->
                supplierIdsByName[key] = existingId
                return existingId
            }

            val insertedId = supplierDao.insert(Supplier(name = normalizedName))
            val resolvedId = when {
                insertedId > 0L -> insertedId
                else -> supplierDao.findByName(normalizedName)?.id
            } ?: return null

            supplierIdsByName[key] = resolvedId
            return resolvedId
        }

        suspend fun resolveCategoryIdByName(name: String): Long? {
            val normalizedName = name.trim()
            val key = normalizedName.lowercase()
            if (key.isBlank()) return null
            categoryIdsByName[key]?.let { return it }

            categoryDao.findByName(normalizedName)?.id?.let { existingId ->
                categoryIdsByName[key] = existingId
                return existingId
            }

            categoryDao.insert(Category(name = normalizedName))
            val resolvedId = categoryDao.findByName(normalizedName)?.id ?: return null
            categoryIdsByName[key] = resolvedId
            return resolvedId
        }

        suspend fun resolveProduct(product: Product): Product {
            val resolvedSupplierId = when {
                product.supplierId == null -> null
                product.supplierId >= 0L -> product.supplierId
                else -> request.pendingTempSuppliers[product.supplierId]?.let { name ->
                    resolveSupplierIdByName(name)
                }
            }
            val resolvedCategoryId = when {
                product.categoryId == null -> null
                product.categoryId >= 0L -> product.categoryId
                else -> request.pendingTempCategories[product.categoryId]?.let { name ->
                    resolveCategoryIdByName(name)
                }
            }
            return product.copy(
                supplierId = resolvedSupplierId,
                categoryId = resolvedCategoryId
            )
        }

        request.pendingSupplierNames.forEach { resolveSupplierIdByName(it) }
        request.pendingCategoryNames.forEach { resolveCategoryIdByName(it) }

        val resolvedNewProducts = request.newProducts.map { resolveProduct(it) }
        val resolvedUpdatedProducts = request.updatedProducts.map { update ->
            resolveProduct(update.newProduct).copy(id = update.oldProduct.id)
        }

        if (resolvedNewProducts.isNotEmpty()) {
            productDao.insertAll(resolvedNewProducts)
        }
        if (resolvedUpdatedProducts.isNotEmpty()) {
            productDao.updateAll(resolvedUpdatedProducts)
        }

        DefaultInventoryRepositoryTestHooks.afterProductsPersisted?.invoke()

        val now = LocalDateTime.now()
        val prevTs = now.minusSeconds(1).format(tSFMT)
        val nowTs = now.format(tSFMT)

        val allBarcodes = (
            resolvedNewProducts.map { it.barcode } +
                resolvedUpdatedProducts.map { it.barcode } +
                request.pendingPriceHistory.map { it.barcode }
            ).distinct()
        val persistedProducts = if (allBarcodes.isEmpty()) {
            emptyList()
        } else {
            productDao.findByBarcodes(allBarcodes)
        }
        val productIdsByBarcode = persistedProducts.associate { it.barcode to it.id }

        suspend fun recordImportedCurrentAndPreviousPrices(productId: Long, product: Product) {
            product.oldPurchasePrice?.let {
                priceDao.insertIfChanged(productId, "PURCHASE", it, prevTs, "IMPORT_PREV")
            }
            product.oldRetailPrice?.let {
                priceDao.insertIfChanged(productId, "RETAIL", it, prevTs, "IMPORT_PREV")
            }
            product.purchasePrice?.let {
                priceDao.insertIfChanged(productId, "PURCHASE", it, nowTs, "IMPORT")
            }
            product.retailPrice?.let {
                priceDao.insertIfChanged(productId, "RETAIL", it, nowTs, "IMPORT")
            }
        }

        resolvedNewProducts.forEach { product ->
            productIdsByBarcode[product.barcode]?.let { productId ->
                recordImportedCurrentAndPreviousPrices(productId, product)
            }
        }
        resolvedUpdatedProducts.forEach { product ->
            recordImportedCurrentAndPreviousPrices(product.id, product)
        }

        val pendingPriceHistoryPoints = request.pendingPriceHistory.mapNotNull { entry ->
            val productId = productIdsByBarcode[entry.barcode] ?: return@mapNotNull null
            ProductPrice(
                productId = productId,
                type = entry.type,
                price = entry.price,
                effectiveAt = entry.timestamp,
                source = entry.source ?: "IMPORT_SHEET"
            )
        }
        if (pendingPriceHistoryPoints.isNotEmpty()) {
            priceDao.insertAll(pendingPriceHistoryPoints)
        }
    }
}

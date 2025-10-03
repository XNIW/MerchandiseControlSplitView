package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import com.example.merchandisecontrolsplitview.data.remote.CloudStore
import com.example.merchandisecontrolsplitview.data.auth.AuthManager

// ⬇️ aggiungi subito sotto gli import esistenti (prima dell'interfaccia)
data class PriceHistoryExportRow(
    val barcode: String,
    val timestamp: String, // "yyyy-MM-dd HH:mm:ss"
    val type: String,      // "PURCHASE" | "RETAIL"
    val price: Double,
    val source: String?
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
    suspend fun applyImport(newProducts: List<Product>, updatedProducts: List<Product>)

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
}

class DefaultInventoryRepository(db: AppDatabase) : InventoryRepository {
    private val productDao: ProductDao = db.productDao()
    private val supplierDao: SupplierDao = db.supplierDao()
    private val categoryDao: CategoryDao = db.categoryDao()
    private val historyDao: HistoryEntryDao = db.historyEntryDao()
    private val priceDao: ProductPriceDao = db.productPriceDao()
    private val tSFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    // --- Product Implementations ---
    override fun getProductsWithDetailsPaged(filter: String?) = productDao.getAllWithDetailsPaged(filter)
    override suspend fun findProductByBarcode(barcode: String) = withContext(Dispatchers.IO) { productDao.findByBarcode(barcode) }
    override suspend fun findProductsByBarcodes(barcodes: List<String>) = withContext(Dispatchers.IO) { productDao.findByBarcodes(barcodes) }
    override suspend fun getAllProducts(): List<Product> = withContext(Dispatchers.IO) { productDao.getAll() }

    private fun cloudOrNull(): CloudStore? =
        AuthManager.currentUid()?.let { CloudStore(it) }

    override suspend fun addProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.insert(product)
            val persisted = productDao.findByBarcode(product.barcode) ?: return@withContext

            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let {
                priceDao.insertIfChanged(persisted.id, "PURCHASE", it, now, "MANUAL")
                cloudOrNull()?.upsertPriceRow(product.barcode, "PURCHASE", now, it, "MANUAL", now)
            }
            product.retailPrice?.let {
                priceDao.insertIfChanged(persisted.id, "RETAIL", it, now, "MANUAL")
                cloudOrNull()?.upsertPriceRow(product.barcode, "RETAIL", now, it, "MANUAL", now)
            }

            cloudOrNull()?.upsertProduct(persisted)
        }
    }
    override suspend fun updateProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.update(product)
            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let {
                priceDao.insertIfChanged(product.id, "PURCHASE", it, now, "MANUAL")
                cloudOrNull()?.upsertPriceRow(product.barcode, "PURCHASE", now, it, "MANUAL", now)
            }
            product.retailPrice?.let {
                priceDao.insertIfChanged(product.id, "RETAIL", it, now, "MANUAL")
                cloudOrNull()?.upsertPriceRow(product.barcode, "RETAIL", now, it, "MANUAL", now)
            }

            cloudOrNull()?.upsertProduct(product)
        }
    }
    override suspend fun getAllProductsWithDetails(): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getAllWithDetailsOnce() }

    override suspend fun deleteProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.delete(product)
            cloudOrNull()?.deleteProduct(product)
            cloudOrNull()?.deletePricesOfProduct(product.barcode)
        }
    }

    override suspend fun applyImport(
        newProducts: List<Product>,
        updatedProducts: List<Product>
    ) = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now()
        val prevTs = now.minusSeconds(1).format(tSFMT)
        val nowTs  = now.format(tSFMT)

        if (newProducts.isNotEmpty()) productDao.insertAll(newProducts)
        if (updatedProducts.isNotEmpty()) productDao.updateAll(updatedProducts)

        // ⬇️ deve essere 'suspend'
        suspend fun recordPricesFor(productId: Long, p: Product) {
            // prezzi precedenti (snapshot pre-import)
            p.oldPurchasePrice?.let {
                priceDao.insertIfChanged(productId, "PURCHASE", it, prevTs, "IMPORT_PREV")
                cloudOrNull()?.upsertPriceRow(p.barcode, "PURCHASE", prevTs, it, "IMPORT_PREV", prevTs)
            }
            p.oldRetailPrice?.let {
                priceDao.insertIfChanged(productId, "RETAIL", it, prevTs, "IMPORT_PREV")
                cloudOrNull()?.upsertPriceRow(p.barcode, "RETAIL", prevTs, it, "IMPORT_PREV", prevTs)
            }
            // prezzi attuali (post-import)
            p.purchasePrice?.let {
                priceDao.insertIfChanged(productId, "PURCHASE", it, nowTs, "IMPORT")
                cloudOrNull()?.upsertPriceRow(p.barcode, "PURCHASE", nowTs, it, "IMPORT", nowTs)
            }
            p.retailPrice?.let {
                priceDao.insertIfChanged(productId, "RETAIL", it, nowTs, "IMPORT")
                cloudOrNull()?.upsertPriceRow(p.barcode, "RETAIL", nowTs, it, "IMPORT", nowTs)
            }
        }

        if (newProducts.isNotEmpty()) {
            val idsByBarcode = productDao
                .findByBarcodes(newProducts.map { it.barcode }.distinct())
                .associate { it.barcode to it.id }
            for (p in newProducts) {
                val id = idsByBarcode[p.barcode] ?: continue
                recordPricesFor(id, p)
            }
        }

        for (p in updatedProducts) {
            recordPricesFor(p.id, p)
        }
    }

    // --- Supplier Implementations ---
    override suspend fun getSupplierById(id: Long) = withContext(Dispatchers.IO) { supplierDao.getById(id) }
    override suspend fun findSupplierByName(name: String): Supplier? = withContext(Dispatchers.IO) { supplierDao.findByName(name) }
    override suspend fun getAllSuppliers(): List<Supplier> = withContext(Dispatchers.IO) { supplierDao.getAll() }
    override suspend fun searchSuppliersByName(query: String) = withContext(Dispatchers.IO) { supplierDao.searchByName(query) }
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

    private val supplierMutex = Mutex()
    override suspend fun addSupplier(name: String): Supplier? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        supplierMutex.withLock {
            supplierDao.findByName(name) ?: run {
                val newSupplier = Supplier(name = name)
                supplierDao.insert(newSupplier)
                val saved = supplierDao.findByName(name)
                saved?.let { cloudOrNull()?.upsertSupplier(it) }
                saved
            }
        }
    }

    private val categoryMutex = Mutex()
    override suspend fun addCategory(name: String): Category? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        categoryMutex.withLock {
            categoryDao.findByName(name) ?: run {
                val newCategory = Category(name = name)
                categoryDao.insert(newCategory)
                val saved = categoryDao.findByName(name)
                saved?.let { cloudOrNull()?.upsertCategory(it) }
                saved
            }
        }
    }

    // --- History Implementations ---
    override fun getFilteredHistoryFlow(filter: DateFilter): Flow<List<HistoryEntry>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return when (filter) {
            is DateFilter.All -> historyDao.getAllFlow()
            is DateFilter.LastMonth -> {
                val today = LocalDate.now()
                val startOfMonth = today.withDayOfMonth(1).atStartOfDay().format(formatter)
                val endOfMonth = today.withDayOfMonth(today.lengthOfMonth()).atTime(23, 59, 59).format(formatter)
                historyDao.getEntriesBetweenDatesFlow(startOfMonth, endOfMonth)
            }
            is DateFilter.PreviousMonth -> {
                val previousMonth = YearMonth.from(LocalDate.now()).minusMonths(1)
                val startOfPreviousMonth = previousMonth.atDay(1).atStartOfDay().format(formatter)
                val endOfPreviousMonth = previousMonth.atEndOfMonth().atTime(23, 59, 59).format(formatter)
                historyDao.getEntriesBetweenDatesFlow(startOfPreviousMonth, endOfPreviousMonth)
            }
            is DateFilter.CustomRange -> {
                val startDateString = filter.startDate.atStartOfDay().format(formatter)
                val endDateString = filter.endDate.atTime(23, 59, 59).format(formatter)
                historyDao.getEntriesBetweenDatesFlow(startDateString, endDateString)
            }
        }
    }


    override suspend fun getHistoryEntryByUid(uid: Long) = withContext(Dispatchers.IO) { historyDao.getByUid(uid) }
    override suspend fun insertHistoryEntry(entry: HistoryEntry): Long =
        withContext(Dispatchers.IO) {
            val rowId = historyDao.insert(entry)
            try { cloudOrNull()?.upsertHistoryEntry(entry.copy(uid = 0)) } catch (_: Exception) {}
            rowId
        }

    override suspend fun updateHistoryEntry(entry: HistoryEntry) {
        withContext(Dispatchers.IO) {
            historyDao.update(entry)
            try { cloudOrNull()?.upsertHistoryEntry(entry.copy(uid = 0)) } catch (_: Exception) {}
        }
    }

    override suspend fun deleteHistoryEntry(entry: HistoryEntry) {
        withContext(Dispatchers.IO) {
            historyDao.delete(entry)
            cloudOrNull()?.deleteHistoryEntryById(entry.id)
        }
    }
    // ⬇️ in DefaultInventoryRepository, aggiungi l'implementazione:
    override suspend fun getAllPriceHistoryRows(): List<PriceHistoryExportRow> =
        withContext(Dispatchers.IO) {
            // Richiede nel ProductPriceDao un metodo che ritorni (barcode, effectiveAt, type, price, source)
            // Esempio: data class ExportRow(val barcode:String, val effectiveAt:String, val type:String, val price:Double, val source:String?)
            // @Query("""
            //   SELECT p.barcode AS barcode, pp.effectiveAt AS effectiveAt, pp.type AS type, pp.price AS price, pp.source AS source
            //   FROM ProductPrice pp
            //   JOIN Product p ON p.id = pp.productId
            //   ORDER BY p.barcode ASC, pp.type ASC, pp.effectiveAt ASC
            // """)
            // fun getAllWithBarcode(): List<ExportRow>
            val rows = priceDao.getAllWithBarcode() // <- aggiungere nel DAO come sopra
            rows.map { PriceHistoryExportRow(it.barcode, it.effectiveAt, it.type, it.price, it.source) }
        }
}
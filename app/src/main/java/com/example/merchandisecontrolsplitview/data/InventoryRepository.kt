package com.example.merchandisecontrolsplitview.data

import android.database.sqlite.SQLiteConstraintException
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
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
/**
 * Esito dell'apply di un singolo [SessionRemotePayload] in Room (task 008).
 * Restituito da [InventoryRepository.applyRemoteSessionPayload].
 */
sealed class RemoteSessionApplyOutcome {
    /** Nuova [HistoryEntry] e riga bridge inserite correttamente. */
    object Inserted : RemoteSessionApplyOutcome()
    /** [HistoryEntry] esistente aggiornata con i campi del payload. */
    object Updated : RemoteSessionApplyOutcome()
    /** Payload invariato rispetto allo stato locale: nessuna scrittura effettuata. */
    object Skipped : RemoteSessionApplyOutcome()
    /** [payloadVersion] non supportata in questa versione dell'app. */
    object UnsupportedVersion : RemoteSessionApplyOutcome()
    /** Errore controllato durante l'apply; l'entry non è stata modificata. */
    data class Failed(val cause: Throwable) : RemoteSessionApplyOutcome()
}

/** Riepilogo aggregato di un apply batch di payload remoti (task 008). */
data class RemoteSessionBatchResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val unsupported: Int
) {
    val totalProcessed: Int get() = inserted + updated + skipped + failed + unsupported
}

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
    suspend fun getCatalogItems(kind: CatalogEntityKind, query: String? = null): List<CatalogListItem>
    suspend fun createCatalogEntry(kind: CatalogEntityKind, name: String): CatalogListItem
    suspend fun renameCatalogEntry(kind: CatalogEntityKind, id: Long, newName: String): CatalogListItem
    suspend fun deleteCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        strategy: CatalogDeleteStrategy
    ): CatalogDeleteResult

    // Category methods
    suspend fun getCategoryById(id: Long): Category?
    suspend fun findCategoryByName(name: String): Category?
    suspend fun getAllCategories(): List<Category>
    suspend fun searchCategoriesByName(query: String): List<Category>
    suspend fun addCategory(name: String): Category?

    /** Database hub: supplier rows for current search; re-emits when Room `suppliers` (and for search, matching rows) change. */
    fun observeSuppliersForHubSearch(query: String): Flow<List<Supplier>>

    /** Database hub: category rows for current search; re-emits when Room `categories` change. */
    fun observeCategoriesForHubSearch(query: String): Flow<List<Category>>

    /** Database hub: catalog cards with product counts; re-emits when linked Room tables change. */
    fun observeCatalogItems(kind: CatalogEntityKind, query: String?): Flow<List<CatalogListItem>>

    // User-visible history methods. Technical import audit rows stay in logcat and are excluded
    // at the DAO source from the normal History flows.
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
    /** Export DB: pagina prodotti con dettaglio (stesso ordinamento di [getAllProductsWithDetails]). */
    suspend fun getProductsWithDetailsPage(limit: Int, offset: Int): List<ProductWithDetails>
    // ⬇️ nell'interfaccia InventoryRepository, aggiungi:
    // PriceHistory export
    suspend fun getAllPriceHistoryRows(): List<PriceHistoryExportRow>
    /** Export DB: pagina cronologia prezzi (stesso ordinamento di [getAllPriceHistoryRows]). */
    suspend fun getPriceHistoryRowsPage(limit: Int, offset: Int): List<PriceHistoryExportRow>
    suspend fun getAllProductsLite(): List<ProductDao.ProductLite>
    suspend fun recordPriceHistoryByBarcodeBatch(
        rows: List<Triple<String /*barcode*/, String /*type*/, Pair<String /*ts*/, Double /*price*/>>>,
        source: String = "IMPORT_SHEET"
    )
    /** Mappa “barcode → (purchase?, retail?)” con i prezzi correnti (1 sola query) */
    suspend fun getCurrentPricesForBarcodes(barcodes: List<String>): Map<String, Pair<Double?, Double?>>

    /** Snapshot “tutto il listino attuale” (utile per export/listino) */
    suspend fun getCurrentPriceSnapshot(): List<CurrentPriceRow>

    // --- Bridge locale: identità remota stabile (task 007 / DEC-017) ---

    /**
     * Restituisce il [remote_id] associato a questa entry, creandolo una sola volta se
     * non esiste ancora. Il [remote_id] è un UUID client-side, stabile rispetto a rename,
     * re-export e navigation locale. Restituisce null se l'entry non esiste.
     */
    suspend fun getOrCreateRemoteId(historyEntryUid: Long): String?

    /** Legge il [HistoryEntryRemoteRef] senza creare nulla. Null se non ancora generato. */
    suspend fun getRemoteRef(historyEntryUid: Long): HistoryEntryRemoteRef?

    // --- Pull remoto controllato: apply e dedup per remoteId (task 008) ---

    /**
     * Applica un singolo [SessionRemotePayload] in Room in modo idempotente e non distruttivo.
     *
     * Comportamento:
     * - [payloadVersion] non supportata → [RemoteSessionApplyOutcome.UnsupportedVersion].
     * - [remoteId] già presente nel bridge → aggiorna i campi payload dell'entry esistente;
     *   se il payload è invariato rispetto allo stato locale → [RemoteSessionApplyOutcome.Skipped].
     * - [remoteId] sconosciuto → inserisce nuova [HistoryEntry] e riga bridge.
     * - Nessuna delete locale: l'assenza di un record nel fetch remoto non cancella nulla.
     * - Il [timestamp] remoto è materializzato/ordinato ma non usato come regola di conflitto.
     */
    suspend fun applyRemoteSessionPayload(payload: SessionRemotePayload): RemoteSessionApplyOutcome

    /**
     * Applica una lista di [SessionRemotePayload] in modo sequenziale e controllato.
     *
     * Ogni record è trattato indipendentemente: un payload invalido non blocca i successivi.
     * Non simula una full sync: non elimina entry locali assenti dalla lista.
     */
    suspend fun applyRemoteSessionPayloadBatch(payloads: List<SessionRemotePayload>): RemoteSessionBatchResult

    // --- Catalogo cloud (task 013 / DEC-020) ---

    /** True se esiste lavoro pendente (revisioni bridge o righe senza bridge con catalogo non vuoto). */
    suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean

    /**
     * Push pendenti verso il cloud poi pull/applica remoto in ordine FK (fornitori → categorie → prodotti).
     * Subito dopo: sync storico prezzi (task 016) se [priceRemote] configurato — ordine catalogo prima, poi prezzi.
     * Solo i transport eseguono rete; Room e bridge restano nel repository.
     */
    suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String
    ): Result<CatalogSyncSummary>
}

internal object DefaultInventoryRepositoryTestHooks {
    @Volatile
    var afterProductsPersisted: (suspend () -> Unit)? = null
}

class DefaultInventoryRepository(private val db: AppDatabase) : InventoryRepository {
    private data class CatalogEntityRef(
        val id: Long,
        val name: String
    )

    private data class RemoteSessionLocalState(
        val editable: List<List<String>>,
        val complete: List<Boolean>,
        val totalItems: Int,
        val orderTotal: Double,
        val paymentTotal: Double,
        val missingItems: Int
    )

    private val productDao: ProductDao = db.productDao()
    private val supplierDao: SupplierDao = db.supplierDao()
    private val categoryDao: CategoryDao = db.categoryDao()
    private val historyDao: HistoryEntryDao = db.historyEntryDao()
    private val priceDao: ProductPriceDao = db.productPriceDao()
    private val remoteRefDao: HistoryEntryRemoteRefDao = db.historyEntryRemoteRefDao()
    private val supplierRemoteRefDao: SupplierRemoteRefDao = db.supplierRemoteRefDao()
    private val categoryRemoteRefDao: CategoryRemoteRefDao = db.categoryRemoteRefDao()
    private val productRemoteRefDao: ProductRemoteRefDao = db.productRemoteRefDao()
    private val productPriceRemoteRefDao: ProductPriceRemoteRefDao = db.productPriceRemoteRefDao()
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
            touchProductDirty(persisted.id)
        }
    }
    override suspend fun updateProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.update(product)

            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let { priceDao.insertIfChanged(product.id, "PURCHASE", it, now, "MANUAL") }
            product.retailPrice  ?.let { priceDao.insertIfChanged(product.id, "RETAIL",   it, now, "MANUAL") }
            touchProductDirty(product.id)
        }
    }
    override suspend fun getAllProductsWithDetails(): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getAllWithDetailsOnce() }

    override suspend fun getProductsWithDetailsPage(limit: Int, offset: Int): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getWithDetailsPage(limit, offset) }
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

    override fun observeSuppliersForHubSearch(query: String): Flow<List<Supplier>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) supplierDao.getAllFlow()
        else supplierDao.searchByNameFlow(trimmed)
    }

    private val supplierMutex = Mutex()
    override suspend fun addSupplier(name: String): Supplier? = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return@withContext null
        supplierMutex.withLock {
            supplierDao.findByNameIgnoreCase(normalizedName)?.let { return@withLock it }
            val newSupplier = Supplier(name = normalizedName)
            val insertedId = supplierDao.insert(newSupplier)
            val created = if (insertedId > 0L) {
                supplierDao.getById(insertedId)
            } else {
                supplierDao.findByNameIgnoreCase(normalizedName)
            }
            created?.also { touchSupplierDirty(it.id) }
        }
    }

    override suspend fun getCatalogItems(
        kind: CatalogEntityKind,
        query: String?
    ): List<CatalogListItem> = withContext(Dispatchers.IO) {
        val normalizedQuery = query?.trim().takeUnless { it.isNullOrEmpty() }
        when (kind) {
            CatalogEntityKind.SUPPLIER -> supplierDao.getCatalogItems(normalizedQuery)
            CatalogEntityKind.CATEGORY -> categoryDao.getCatalogItems(normalizedQuery)
        }
    }

    override fun observeCatalogItems(
        kind: CatalogEntityKind,
        query: String?
    ): Flow<List<CatalogListItem>> {
        val normalizedQuery = query?.trim().takeUnless { it.isNullOrEmpty() }
        return when (kind) {
            CatalogEntityKind.SUPPLIER -> supplierDao.getCatalogItemsFlow(normalizedQuery)
            CatalogEntityKind.CATEGORY -> categoryDao.getCatalogItemsFlow(normalizedQuery)
        }
    }

    override suspend fun createCatalogEntry(
        kind: CatalogEntityKind,
        name: String
    ): CatalogListItem = withContext(Dispatchers.IO) {
        withCatalogMutationLock(kind) {
            val item = createCatalogEntryLocked(kind, normalizedNameFor(kind, name))
            when (kind) {
                CatalogEntityKind.SUPPLIER -> touchSupplierDirty(item.id)
                CatalogEntityKind.CATEGORY -> touchCategoryDirty(item.id)
            }
            item
        }
    }

    override suspend fun renameCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        newName: String
    ): CatalogListItem = withContext(Dispatchers.IO) {
        withCatalogMutationLock(kind) {
            val current = getCatalogEntityRef(kind, id)
                ?: throw CatalogNotFoundException(kind, id)
            val normalizedName = normalizedNameFor(kind, newName, currentId = id)
            if (current.name != normalizedName) {
                renameCatalogEntity(kind, id, normalizedName)
            }
            when (kind) {
                CatalogEntityKind.SUPPLIER -> touchSupplierDirty(id)
                CatalogEntityKind.CATEGORY -> touchCategoryDirty(id)
            }
            CatalogListItem(
                id = id,
                name = normalizedName,
                productCount = linkedProductCount(kind, id)
            )
        }
    }

    override suspend fun deleteCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        strategy: CatalogDeleteStrategy
    ): CatalogDeleteResult = withContext(Dispatchers.IO) {
        withCatalogMutationLock(kind) {
            db.withTransaction {
                getCatalogEntityRef(kind, id) ?: throw CatalogNotFoundException(kind, id)
                when (strategy) {
                    CatalogDeleteStrategy.DeleteIfUnused -> {
                        val linkedCount = linkedProductCount(kind, id)
                        if (linkedCount > 0) {
                            throw CatalogEntityInUseException(linkedCount)
                        }
                        deleteCatalogEntity(kind, id)
                        CatalogDeleteResult(
                            affectedProducts = 0,
                            strategy = strategy
                        )
                    }

                    is CatalogDeleteStrategy.ReplaceWithExisting -> {
                        if (strategy.replacementId == id) {
                            throw CatalogInvalidReplacementException
                        }
                        val replacement = getCatalogEntityRef(kind, strategy.replacementId)
                            ?: throw CatalogNotFoundException(kind, strategy.replacementId)
                        val affectedProducts = reassignCatalogProducts(
                            kind = kind,
                            sourceId = id,
                            replacementId = strategy.replacementId
                        )
                        deleteCatalogEntity(kind, id)
                        CatalogDeleteResult(
                            affectedProducts = affectedProducts,
                            strategy = strategy,
                            replacementName = replacement.name
                        )
                    }

                    is CatalogDeleteStrategy.CreateNewAndReplace -> {
                        val replacement = createCatalogEntryLocked(
                            kind = kind,
                            normalizedName = normalizedNameFor(kind, strategy.replacementName)
                        )
                        val affectedProducts = reassignCatalogProducts(
                            kind = kind,
                            sourceId = id,
                            replacementId = replacement.id
                        )
                        deleteCatalogEntity(kind, id)
                        CatalogDeleteResult(
                            affectedProducts = affectedProducts,
                            strategy = strategy,
                            replacementName = replacement.name
                        )
                    }

                    CatalogDeleteStrategy.ClearAssignments -> {
                        val affectedProducts = clearCatalogAssignments(kind, id)
                        deleteCatalogEntity(kind, id)
                        CatalogDeleteResult(
                            affectedProducts = affectedProducts,
                            strategy = strategy
                        )
                    }
                }
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

    override fun observeCategoriesForHubSearch(query: String): Flow<List<Category>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) categoryDao.getAllFlow()
        else categoryDao.searchByNameFlow(trimmed)
    }

    private val categoryMutex = Mutex()
    override suspend fun addCategory(name: String): Category? = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return@withContext null
        categoryMutex.withLock {
            categoryDao.findByName(normalizedName)?.let { return@withLock it }
            val newCategory = Category(name = normalizedName)
            val insertedId = categoryDao.insert(newCategory)
            val created = if (insertedId > 0L) {
                categoryDao.getById(insertedId)
            } else {
                categoryDao.findByName(normalizedName)
            }
            created?.also { touchCategoryDirty(it.id) }
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
            historyDao.getAllUserVisibleFlow()
        } else {
            historyDao.getUserVisibleEntriesBetweenDatesFlow(range.first, range.second)
        }
    }

    override fun getFilteredHistoryListFlow(filter: DateFilter): Flow<List<HistoryEntryListItem>> {
        val range = historyRangeFor(filter)
        return if (range == null) {
            historyDao.getAllUserVisibleListItemsFlow()
        } else {
            historyDao.getUserVisibleListItemsBetweenDatesFlow(range.first, range.second)
        }
    }

    override fun hasHistoryEntriesFlow(): Flow<Boolean> = historyDao.hasUserVisibleEntriesFlow()

    override suspend fun getHistoryEntryByUid(uid: Long) = withContext(Dispatchers.IO) { historyDao.getByUid(uid) }
    override suspend fun insertHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) { historyDao.insert(entry) }
    override suspend fun updateHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val bridgeRef = remoteRefDao.getByHistoryEntryUid(entry.uid)
        if (bridgeRef != null) {
            // Se esiste un bridge, confronta i campi payload-rilevanti prima di aggiornare.
            // Lettura esplicita dell'entry corrente per rilevare la divergenza in modo centralizzato.
            val old = historyDao.getByUid(entry.uid)
            historyDao.update(entry)
            if (old != null && isPayloadRelevantChange(old, entry)) {
                remoteRefDao.incrementLocalRevision(entry.uid)
            }
        } else {
            historyDao.update(entry)
        }
    }

    /**
     * Restituisce true se la modifica tocca almeno un campo incluso in [SessionRemotePayload] v1.
     * Usata da [updateHistoryEntry] per decidere se incrementare [HistoryEntryRemoteRef.localChangeRevision].
     */
    private fun isPayloadRelevantChange(old: HistoryEntry, new: HistoryEntry): Boolean =
        old.timestamp != new.timestamp ||
        old.supplier != new.supplier ||
        old.category != new.category ||
        old.isManualEntry != new.isManualEntry ||
        old.data != new.data
    override suspend fun deleteHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        db.withTransaction {
            remoteRefDao.deleteByHistoryEntryUid(entry.uid)
            historyDao.delete(entry)
        }
    }
    // ⬇️ in DefaultInventoryRepository, aggiungi l'implementazione:
    override suspend fun getAllPriceHistoryRows(): List<PriceHistoryExportRow> =
        withContext(Dispatchers.IO) {
            mapPriceHistoryExportRows(priceDao.getAllWithBarcode())
        }

    override suspend fun getPriceHistoryRowsPage(limit: Int, offset: Int): List<PriceHistoryExportRow> =
        withContext(Dispatchers.IO) {
            mapPriceHistoryExportRows(priceDao.getAllWithBarcodePage(limit, offset))
        }

    private fun mapPriceHistoryExportRows(rows: List<PriceHistoryExportRowDb>): List<PriceHistoryExportRow> =
        rows.map { r ->
            PriceHistoryExportRow(
                barcode = r.barcode,
                timestamp = r.effectiveAt,
                type = r.type,
                price = r.price,
                source = r.source
            )
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

    // --- Bridge locale (task 007 / DEC-017) ---

    override suspend fun getOrCreateRemoteId(historyEntryUid: Long): String? =
        withContext(Dispatchers.IO) {
            val existing = remoteRefDao.getByHistoryEntryUid(historyEntryUid)
            if (existing != null) return@withContext existing.remoteId

            // Verifica che l'entry esista prima di creare il bridge
            historyDao.getByUid(historyEntryUid) ?: return@withContext null

            val newRef = HistoryEntryRemoteRef(
                historyEntryUid = historyEntryUid,
                remoteId = java.util.UUID.randomUUID().toString()
            )
            val inserted = remoteRefDao.insert(newRef)
            if (inserted > 0L) {
                remoteRefDao.getByHistoryEntryUid(historyEntryUid)?.remoteId
            } else {
                // Race condition: un'altra chiamata concorrente ha già inserito; rilegge
                remoteRefDao.getByHistoryEntryUid(historyEntryUid)?.remoteId
            }
        }

    override suspend fun getRemoteRef(historyEntryUid: Long): HistoryEntryRemoteRef? =
        withContext(Dispatchers.IO) { remoteRefDao.getByHistoryEntryUid(historyEntryUid) }

    // --- Pull remoto controllato (task 008) ---

    override suspend fun applyRemoteSessionPayload(payload: SessionRemotePayload): RemoteSessionApplyOutcome =
        withContext(Dispatchers.IO) {
            if (payload.payloadVersion != SESSION_PAYLOAD_VERSION) {
                return@withContext RemoteSessionApplyOutcome.UnsupportedVersion
            }
            try {
                db.withTransaction { applySingleRemotePayload(payload) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RemoteSessionApplyOutcome.Failed(e)
            }
        }

    private suspend fun applySingleRemotePayload(payload: SessionRemotePayload): RemoteSessionApplyOutcome {
        val fp = payload.payloadFingerprint()
        val existingRef = remoteRefDao.getByRemoteId(payload.remoteId)
        if (existingRef != null) {
            // Fast-path: fingerprint match + entry allineata → Skipped senza caricare HistoryEntry.
            // Evita una lettura del blob data per payload identici già applicati.
            if (existingRef.lastRemotePayloadFingerprint == fp &&
                existingRef.localChangeRevision == existingRef.lastSyncedLocalRevision) {
                return RemoteSessionApplyOutcome.Skipped
            }

            val existingEntry = historyDao.getByUid(existingRef.historyEntryUid)
                ?: return RemoteSessionApplyOutcome.Failed(
                    IllegalStateException("Bridge esiste ma HistoryEntry uid=${existingRef.historyEntryUid} mancante")
                )
            // Nessuna scrittura se il payload è materialmente invariato (slow-path fallback).
            if (existingEntry.timestamp == payload.timestamp &&
                existingEntry.supplier == payload.supplier &&
                existingEntry.category == payload.category &&
                existingEntry.isManualEntry == payload.isManualEntry &&
                existingEntry.data == payload.data) {
                return RemoteSessionApplyOutcome.Skipped
            }
            val refreshedLocalState =
                buildRemoteSessionLocalState(payload.data).takeIf { existingEntry.data != payload.data }
            // Aggiorna i campi payload e riallinea lo scaffolding locale solo se la griglia remota cambia.
            // Chiama historyDao.update() direttamente (non updateHistoryEntry) per non incrementare
            // localChangeRevision: il remote apply non è una modifica locale.
            historyDao.update(
                existingEntry.copy(
                    timestamp = payload.timestamp,
                    supplier = payload.supplier,
                    category = payload.category,
                    isManualEntry = payload.isManualEntry,
                    data = payload.data,
                    editable = refreshedLocalState?.editable ?: existingEntry.editable,
                    complete = refreshedLocalState?.complete ?: existingEntry.complete,
                    totalItems = refreshedLocalState?.totalItems ?: existingEntry.totalItems,
                    orderTotal = refreshedLocalState?.orderTotal ?: existingEntry.orderTotal,
                    paymentTotal = refreshedLocalState?.paymentTotal ?: existingEntry.paymentTotal,
                    missingItems = refreshedLocalState?.missingItems ?: existingEntry.missingItems
                )
            )
            // Allinea la revisione: dopo l'apply remoto l'entry è di nuovo allineata.
            remoteRefDao.updateRemoteApplyState(
                uid = existingRef.historyEntryUid,
                rev = existingRef.localChangeRevision,
                appliedAt = System.currentTimeMillis(),
                fingerprint = fp
            )
            return RemoteSessionApplyOutcome.Updated
        }
        // Insert path: remoteId sconosciuto → nuova entry + bridge con sync state inizializzato.
        val localState = buildRemoteSessionLocalState(payload.data)
        val newEntry = HistoryEntry(
            uid = 0,
            id = payload.remoteId,   // UUID stabile, non collide con prefissi tecnici né nomi utente
            timestamp = payload.timestamp,
            data = payload.data,
            editable = localState.editable,
            complete = localState.complete,
            supplier = payload.supplier,
            category = payload.category,
            isManualEntry = payload.isManualEntry,
            totalItems = localState.totalItems,
            orderTotal = localState.orderTotal,
            paymentTotal = localState.paymentTotal,
            missingItems = localState.missingItems
        )
        val newUid = historyDao.insert(newEntry)
        if (newUid <= 0L) {
            return RemoteSessionApplyOutcome.Failed(
                IllegalStateException("insert ha restituito uid non valido: $newUid")
            )
        }
        check(
            remoteRefDao.insert(
                HistoryEntryRemoteRef(
                    historyEntryUid = newUid,
                    remoteId = payload.remoteId,
                    localChangeRevision = 0,
                    lastSyncedLocalRevision = 0,
                    lastRemoteAppliedAt = System.currentTimeMillis(),
                    lastRemotePayloadFingerprint = fp
                )
            ) > 0L
        ) { "insert bridge ignorato per remoteId=${payload.remoteId}" }
        return RemoteSessionApplyOutcome.Inserted
    }

    private fun buildRemoteSessionLocalState(data: List<List<String>>): RemoteSessionLocalState {
        val editable = List(data.size) { listOf("", "") }
        val complete = List(data.size) { false }

        val header = data.firstOrNull().orEmpty()
        val purchasePriceIndex = header.indexOf("purchasePrice")
        val quantityIndex = header.indexOf("quantity")

        var totalItems = 0
        var orderTotal = 0.0

        if (purchasePriceIndex != -1 && quantityIndex != -1) {
            data.drop(1).forEach { row ->
                val quantity = parseUserQuantityInput(row.getOrNull(quantityIndex)) ?: 0.0
                if (quantity > 0) {
                    totalItems++
                    val purchasePrice = parseUserPriceInput(row.getOrNull(purchasePriceIndex)) ?: 0.0
                    orderTotal += purchasePrice * quantity
                }
            }
        }

        return RemoteSessionLocalState(
            editable = editable,
            complete = complete,
            totalItems = totalItems,
            orderTotal = orderTotal,
            paymentTotal = orderTotal,
            missingItems = totalItems
        )
    }

    override suspend fun applyRemoteSessionPayloadBatch(
        payloads: List<SessionRemotePayload>
    ): RemoteSessionBatchResult = withContext(Dispatchers.IO) {
        var inserted = 0; var updated = 0; var skipped = 0; var failed = 0; var unsupported = 0
        for (payload in payloads) {
            when (applyRemoteSessionPayload(payload)) {
                is RemoteSessionApplyOutcome.Inserted -> inserted++
                is RemoteSessionApplyOutcome.Updated -> updated++
                is RemoteSessionApplyOutcome.Skipped -> skipped++
                is RemoteSessionApplyOutcome.UnsupportedVersion -> unsupported++
                is RemoteSessionApplyOutcome.Failed -> failed++
            }
        }
        RemoteSessionBatchResult(inserted, updated, skipped, failed, unsupported)
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

            supplierDao.findByNameIgnoreCase(normalizedName)?.id?.let { existingId ->
                supplierIdsByName[key] = existingId
                return existingId
            }

            val insertedId = supplierDao.insert(Supplier(name = normalizedName))
            val resolvedId = when {
                insertedId > 0L -> insertedId
                else -> supplierDao.findByNameIgnoreCase(normalizedName)?.id
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

            val insertedId = categoryDao.insert(Category(name = normalizedName))
            val resolvedId = when {
                insertedId > 0L -> insertedId
                else -> categoryDao.findByName(normalizedName)?.id
            } ?: return null
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
        markEntireCatalogDirtyForCloud()
    }

    private suspend fun normalizedNameFor(
        kind: CatalogEntityKind,
        rawName: String,
        currentId: Long? = null
    ): String {
        val normalizedName = rawName.trim()
        if (normalizedName.isBlank()) {
            throw CatalogBlankNameException
        }

        val existing = findCatalogEntityByName(kind, normalizedName)
        if (existing != null && existing.id != currentId) {
            throw CatalogNameConflictException(existing.name)
        }
        return normalizedName
    }

    private suspend fun findCatalogEntityByName(
        kind: CatalogEntityKind,
        name: String
    ): CatalogEntityRef? = when (kind) {
        CatalogEntityKind.SUPPLIER -> supplierDao.findByNameIgnoreCase(name)?.let {
            CatalogEntityRef(it.id, it.name)
        }

        CatalogEntityKind.CATEGORY -> categoryDao.findByName(name)?.let {
            CatalogEntityRef(it.id, it.name)
        }
    }

    private suspend fun getCatalogEntityRef(
        kind: CatalogEntityKind,
        id: Long
    ): CatalogEntityRef? = when (kind) {
        CatalogEntityKind.SUPPLIER -> supplierDao.getById(id)?.let {
            CatalogEntityRef(it.id, it.name)
        }

        CatalogEntityKind.CATEGORY -> categoryDao.getById(id)?.let {
            CatalogEntityRef(it.id, it.name)
        }
    }

    private suspend fun linkedProductCount(kind: CatalogEntityKind, id: Long): Int = when (kind) {
        CatalogEntityKind.SUPPLIER -> productDao.countLinkedToSupplier(id)
        CatalogEntityKind.CATEGORY -> productDao.countLinkedToCategory(id)
    }

    private suspend fun createCatalogEntryLocked(
        kind: CatalogEntityKind,
        normalizedName: String
    ): CatalogListItem {
        val insertedId = try {
            when (kind) {
                CatalogEntityKind.SUPPLIER -> supplierDao.insert(Supplier(name = normalizedName))
                CatalogEntityKind.CATEGORY -> categoryDao.insert(Category(name = normalizedName))
            }
        } catch (exception: SQLiteConstraintException) {
            val conflict = findCatalogEntityByName(kind, normalizedName)
            if (conflict != null) {
                throw CatalogNameConflictException(conflict.name)
            }
            throw exception
        }

        if (insertedId <= 0L) {
            val conflict = findCatalogEntityByName(kind, normalizedName)
            if (conflict != null) {
                throw CatalogNameConflictException(conflict.name)
            }
            throw CatalogNotFoundException(kind, insertedId)
        }

        return CatalogListItem(
            id = insertedId,
            name = normalizedName,
            productCount = 0
        )
    }

    private suspend fun renameCatalogEntity(
        kind: CatalogEntityKind,
        id: Long,
        name: String
    ) {
        val updatedRows = try {
            when (kind) {
                CatalogEntityKind.SUPPLIER -> supplierDao.rename(id, name)
                CatalogEntityKind.CATEGORY -> categoryDao.rename(id, name)
            }
        } catch (exception: SQLiteConstraintException) {
            val conflict = findCatalogEntityByName(kind, name)
            if (conflict != null) {
                throw CatalogNameConflictException(conflict.name)
            }
            throw exception
        }

        if (updatedRows == 0) {
            throw CatalogNotFoundException(kind, id)
        }
    }

    private suspend fun deleteCatalogEntity(
        kind: CatalogEntityKind,
        id: Long
    ) {
        val deletedRows = when (kind) {
            CatalogEntityKind.SUPPLIER -> supplierDao.deleteById(id)
            CatalogEntityKind.CATEGORY -> categoryDao.deleteById(id)
        }
        if (deletedRows == 0) {
            throw CatalogNotFoundException(kind, id)
        }
    }

    private suspend fun reassignCatalogProducts(
        kind: CatalogEntityKind,
        sourceId: Long,
        replacementId: Long
    ): Int {
        val touchedIds = when (kind) {
            CatalogEntityKind.SUPPLIER -> productDao.getIdsForSupplier(sourceId)
            CatalogEntityKind.CATEGORY -> productDao.getIdsForCategory(sourceId)
        }
        val n = when (kind) {
            CatalogEntityKind.SUPPLIER -> productDao.reassignSupplier(sourceId, replacementId)
            CatalogEntityKind.CATEGORY -> productDao.reassignCategory(sourceId, replacementId)
        }
        touchedIds.forEach { touchProductDirty(it) }
        return n
    }

    private suspend fun clearCatalogAssignments(
        kind: CatalogEntityKind,
        id: Long
    ): Int {
        val touchedIds = when (kind) {
            CatalogEntityKind.SUPPLIER -> productDao.getIdsForSupplier(id)
            CatalogEntityKind.CATEGORY -> productDao.getIdsForCategory(id)
        }
        val n = when (kind) {
            CatalogEntityKind.SUPPLIER -> productDao.clearSupplierAssignments(id)
            CatalogEntityKind.CATEGORY -> productDao.clearCategoryAssignments(id)
        }
        touchedIds.forEach { touchProductDirty(it) }
        return n
    }

    private suspend fun <T> withCatalogMutationLock(
        kind: CatalogEntityKind,
        block: suspend () -> T
    ): T = when (kind) {
        CatalogEntityKind.SUPPLIER -> supplierMutex.withLock { block() }
        CatalogEntityKind.CATEGORY -> categoryMutex.withLock { block() }
    }

    // --- Sync catalogo cloud (task 013) ---

    override suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = withContext(Dispatchers.IO) {
        if (supplierRemoteRefDao.hasPendingWork()) return@withContext true
        if (categoryRemoteRefDao.hasPendingWork()) return@withContext true
        if (productRemoteRefDao.hasPendingWork()) return@withContext true
        if (priceDao.countPriceRowsPendingPriceBridge() > 0) return@withContext true
        if (priceDao.countPriceRowsWithoutProductRemote() > 0) return@withContext true
        if (supplierDao.count() == 0 && categoryDao.count() == 0 && productDao.count() == 0) {
            return@withContext false
        }
        supplierRemoteRefDao.countRows() < supplierDao.count() ||
            categoryRemoteRefDao.countRows() < categoryDao.count() ||
            productRemoteRefDao.countRows() < productDao.count()
    }

    override suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        try {
            // Snapshot iniziale (prima di ensure/push catalogo): righe prezzo senza bridge prodotto.
            val deferredPrices = priceDao.countPriceRowsWithoutProductRemote()
            val pushedSuppliers = pushCatalogSuppliers(remote, ownerUserId)
            val pushedCategories = pushCatalogCategories(remote, ownerUserId)
            val pushedProducts = pushCatalogProducts(remote, ownerUserId)
            val bundle = remote.fetchCatalog().getOrElse { return@withContext Result.failure(it) }
            var pulledSuppliers = 0
            var pulledCategories = 0
            var pulledProducts = 0
            db.withTransaction {
                for (row in bundle.suppliers) {
                    if (applyRemoteSupplierInbound(row)) pulledSuppliers++
                }
                for (row in bundle.categories) {
                    if (applyRemoteCategoryInbound(row)) pulledCategories++
                }
                for (row in bundle.products) {
                    if (applyRemoteProductInbound(row)) pulledProducts++
                }
            }
            var pushedPrices = 0
            var pulledPrices = 0
            var skippedPullPrices = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    val pullOutcome = pullProductPricesFromRemote(priceRemote)
                    pulledPrices = pullOutcome.first
                    skippedPullPrices = pullOutcome.second
                    pushedPrices = pushProductPricesToRemote(priceRemote, ownerUserId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    priceSyncFailed = true
                }
            }
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = pushedSuppliers,
                    pushedCategories = pushedCategories,
                    pushedProducts = pushedProducts,
                    pulledSuppliers = pulledSuppliers,
                    pulledCategories = pulledCategories,
                    pulledProducts = pulledProducts,
                    pushedProductPrices = pushedPrices,
                    pulledProductPrices = pulledPrices,
                    deferredProductPricesNoProductRef = deferredPrices,
                    skippedProductPricesPullNoProductRef = skippedPullPrices,
                    priceSyncFailed = priceSyncFailed
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private companion object {
        const val PRODUCT_PRICE_PUSH_CHUNK = 80
    }

    /** Push bulk: una query candidati + chunk verso PostgREST; bridge solo per righe senza remote ancora. */
    private suspend fun pushProductPricesToRemote(
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String
    ): Int {
        val rows = priceDao.getAllForCloudPush()
        if (rows.isEmpty()) return 0
        var pushed = 0
        for (chunk in rows.chunked(PRODUCT_PRICE_PUSH_CHUNK)) {
            val pairs = chunk.map { r ->
                val rid = r.existingPriceRemoteId ?: java.util.UUID.randomUUID().toString()
                r to rid
            }
            val upsertRows = pairs.map { (r, rid) ->
                InventoryProductPriceRow(
                    id = rid,
                    ownerUserId = ownerUserId,
                    productId = r.productRemoteId,
                    type = r.type,
                    price = r.price,
                    effectiveAt = r.effectiveAt,
                    source = r.source,
                    note = r.note,
                    createdAt = r.createdAt
                )
            }
            priceRemote.upsertProductPrices(upsertRows).getOrThrow()
            db.withTransaction {
                for ((r, rid) in pairs) {
                    if (r.existingPriceRemoteId == null) {
                        productPriceRemoteRefDao.insert(
                            ProductPriceRemoteRef(productPriceId = r.id, remoteId = rid)
                        )
                    }
                }
            }
            pushed += chunk.size
        }
        return pushed
    }

    /**
     * Pull idempotente: dedup su `(productId,type,effectiveAt)` e su `remoteId`; nessun `insertIfChanged`;
     * non aggiorna `products.purchasePrice` / `retailPrice`.
     */
    private suspend fun pullProductPricesFromRemote(
        priceRemote: ProductPriceRemoteDataSource
    ): Pair<Int, Int> {
        val remotes = priceRemote.fetchProductPrices().getOrThrow()
        var pulled = 0
        var skippedNoLocalProduct = 0
        db.withTransaction {
            for (row in remotes) {
                if (productPriceRemoteRefDao.getByRemoteId(row.id) != null) continue
                val pref = productRemoteRefDao.getByRemoteId(row.productId) ?: run {
                    skippedNoLocalProduct++
                    continue
                }
                val localProductId = pref.productId
                val existing = priceDao.findByBusinessKey(localProductId, row.type, row.effectiveAt)
                if (existing != null) {
                    if (productPriceRemoteRefDao.getByProductPriceId(existing.id) == null) {
                        productPriceRemoteRefDao.insert(
                            ProductPriceRemoteRef(productPriceId = existing.id, remoteId = row.id)
                        )
                        pulled++
                    }
                    continue
                }
                priceDao.insert(
                    ProductPrice(
                        productId = localProductId,
                        type = row.type,
                        price = row.price,
                        effectiveAt = row.effectiveAt,
                        source = row.source,
                        note = row.note,
                        createdAt = row.createdAt
                    )
                )
                val inserted = priceDao.findByBusinessKey(localProductId, row.type, row.effectiveAt)
                    ?: continue
                productPriceRemoteRefDao.insert(
                    ProductPriceRemoteRef(productPriceId = inserted.id, remoteId = row.id)
                )
                pulled++
            }
        }
        return pulled to skippedNoLocalProduct
    }

    private suspend fun markEntireCatalogDirtyForCloud() {
        supplierDao.getAll().forEach { touchSupplierDirty(it.id) }
        categoryDao.getAll().forEach { touchCategoryDirty(it.id) }
        productDao.getAll().forEach { touchProductDirty(it.id) }
    }

    private suspend fun touchSupplierDirty(supplierId: Long) {
        if (supplierRemoteRefDao.getBySupplierId(supplierId) == null) {
            supplierRemoteRefDao.insert(
                SupplierRemoteRef(
                    supplierId = supplierId,
                    remoteId = java.util.UUID.randomUUID().toString()
                )
            )
        } else {
            supplierRemoteRefDao.incrementLocalRevision(supplierId)
        }
    }

    private suspend fun touchCategoryDirty(categoryId: Long) {
        if (categoryRemoteRefDao.getByCategoryId(categoryId) == null) {
            categoryRemoteRefDao.insert(
                CategoryRemoteRef(
                    categoryId = categoryId,
                    remoteId = java.util.UUID.randomUUID().toString()
                )
            )
        } else {
            categoryRemoteRefDao.incrementLocalRevision(categoryId)
        }
    }

    private suspend fun touchProductDirty(productId: Long) {
        if (productRemoteRefDao.getByProductId(productId) == null) {
            productRemoteRefDao.insert(
                ProductRemoteRef(
                    productId = productId,
                    remoteId = java.util.UUID.randomUUID().toString()
                )
            )
        } else {
            productRemoteRefDao.incrementLocalRevision(productId)
        }
    }

    private fun supplierNeedsPush(ref: SupplierRemoteRef): Boolean =
        ref.lastRemoteAppliedAt == null || ref.localChangeRevision > ref.lastSyncedLocalRevision

    private fun categoryNeedsPush(ref: CategoryRemoteRef): Boolean =
        ref.lastRemoteAppliedAt == null || ref.localChangeRevision > ref.lastSyncedLocalRevision

    private fun productNeedsPush(ref: ProductRemoteRef): Boolean =
        ref.lastRemoteAppliedAt == null || ref.localChangeRevision > ref.lastSyncedLocalRevision

    private suspend fun ensureSupplierRefForPush(supplierId: Long): SupplierRemoteRef {
        supplierRemoteRefDao.getBySupplierId(supplierId)?.let { return it }
        supplierRemoteRefDao.insert(
            SupplierRemoteRef(supplierId = supplierId, remoteId = java.util.UUID.randomUUID().toString())
        )
        return supplierRemoteRefDao.getBySupplierId(supplierId)
            ?: error("supplier_remote_refs: insert fallito per supplierId=$supplierId")
    }

    private suspend fun ensureCategoryRefForPush(categoryId: Long): CategoryRemoteRef {
        categoryRemoteRefDao.getByCategoryId(categoryId)?.let { return it }
        categoryRemoteRefDao.insert(
            CategoryRemoteRef(categoryId = categoryId, remoteId = java.util.UUID.randomUUID().toString())
        )
        return categoryRemoteRefDao.getByCategoryId(categoryId)
            ?: error("category_remote_refs: insert fallito per categoryId=$categoryId")
    }

    private suspend fun ensureProductRefForPush(productId: Long): ProductRemoteRef {
        productRemoteRefDao.getByProductId(productId)?.let { return it }
        productRemoteRefDao.insert(
            ProductRemoteRef(productId = productId, remoteId = java.util.UUID.randomUUID().toString())
        )
        return productRemoteRefDao.getByProductId(productId)
            ?: error("product_remote_refs: insert fallito per productId=$productId")
    }

    private suspend fun pushCatalogSuppliers(
        remote: CatalogRemoteDataSource,
        ownerUserId: String
    ): Int {
        var n = 0
        for (s in supplierDao.getAll()) {
            val ref = ensureSupplierRefForPush(s.id)
            if (!supplierNeedsPush(ref)) continue
            val row = InventorySupplierRow(id = ref.remoteId, ownerUserId = ownerUserId, name = s.name)
            remote.upsertSuppliers(listOf(row)).getOrThrow()
            val fp = fingerprintSupplierName(s.name)
            supplierRemoteRefDao.updateRemoteApplyState(
                s.id,
                ref.localChangeRevision,
                System.currentTimeMillis(),
                fp
            )
            n++
        }
        return n
    }

    private suspend fun pushCatalogCategories(
        remote: CatalogRemoteDataSource,
        ownerUserId: String
    ): Int {
        var n = 0
        for (c in categoryDao.getAll()) {
            val ref = ensureCategoryRefForPush(c.id)
            if (!categoryNeedsPush(ref)) continue
            val row = InventoryCategoryRow(id = ref.remoteId, ownerUserId = ownerUserId, name = c.name)
            remote.upsertCategories(listOf(row)).getOrThrow()
            val fp = fingerprintCategoryName(c.name)
            categoryRemoteRefDao.updateRemoteApplyState(
                c.id,
                ref.localChangeRevision,
                System.currentTimeMillis(),
                fp
            )
            n++
        }
        return n
    }

    private suspend fun pushCatalogProducts(
        remote: CatalogRemoteDataSource,
        ownerUserId: String
    ): Int {
        var n = 0
        for (p in productDao.getAll()) {
            val ref = ensureProductRefForPush(p.id)
            if (!productNeedsPush(ref)) continue
            val supR = p.supplierId?.let { ensureSupplierRefForPush(it).remoteId }
            val catR = p.categoryId?.let { ensureCategoryRefForPush(it).remoteId }
            val row = InventoryProductRow(
                id = ref.remoteId,
                ownerUserId = ownerUserId,
                barcode = p.barcode,
                itemNumber = p.itemNumber,
                productName = p.productName,
                secondProductName = p.secondProductName,
                purchasePrice = p.purchasePrice,
                retailPrice = p.retailPrice,
                supplierId = supR,
                categoryId = catR,
                stockQuantity = p.stockQuantity
            )
            remote.upsertProducts(listOf(row)).getOrThrow()
            val fp = fingerprintProductRow(p, supR, catR)
            productRemoteRefDao.updateRemoteApplyState(
                p.id,
                ref.localChangeRevision,
                System.currentTimeMillis(),
                fp
            )
            n++
        }
        return n
    }

    private suspend fun applyRemoteSupplierInbound(row: InventorySupplierRow): Boolean {
        val fp = fingerprintSupplierName(row.name)
        val existingRef = supplierRemoteRefDao.getByRemoteId(row.id)
        if (existingRef != null) {
            if (existingRef.lastRemotePayloadFingerprint == fp &&
                existingRef.localChangeRevision == existingRef.lastSyncedLocalRevision
            ) {
                return false
            }
            supplierDao.getById(existingRef.supplierId) ?: return false
            val name = row.name.trim()
            try {
                supplierDao.rename(existingRef.supplierId, name)
            } catch (_: SQLiteConstraintException) {
                return false
            }
            supplierRemoteRefDao.updateRemoteApplyState(
                existingRef.supplierId,
                existingRef.localChangeRevision,
                System.currentTimeMillis(),
                fp
            )
            return true
        }
        val name = row.name.trim()
        val local = supplierDao.findByNameIgnoreCase(name)
        val localId = local?.id ?: run {
            val ins = supplierDao.insert(Supplier(name = name))
            when {
                ins > 0L -> ins
                else -> supplierDao.findByNameIgnoreCase(name)?.id ?: return false
            }
        }
        val bridgeForRow = supplierRemoteRefDao.getBySupplierId(localId)
        if (bridgeForRow != null && bridgeForRow.remoteId != row.id) return false
        if (bridgeForRow != null) return false
        supplierRemoteRefDao.insert(
            SupplierRemoteRef(
                supplierId = localId,
                remoteId = row.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                lastRemotePayloadFingerprint = fp
            )
        )
        return true
    }

    private suspend fun applyRemoteCategoryInbound(row: InventoryCategoryRow): Boolean {
        val fp = fingerprintCategoryName(row.name)
        val existingRef = categoryRemoteRefDao.getByRemoteId(row.id)
        if (existingRef != null) {
            if (existingRef.lastRemotePayloadFingerprint == fp &&
                existingRef.localChangeRevision == existingRef.lastSyncedLocalRevision
            ) {
                return false
            }
            categoryDao.getById(existingRef.categoryId) ?: return false
            val name = row.name.trim()
            try {
                categoryDao.rename(existingRef.categoryId, name)
            } catch (_: SQLiteConstraintException) {
                return false
            }
            categoryRemoteRefDao.updateRemoteApplyState(
                existingRef.categoryId,
                existingRef.localChangeRevision,
                System.currentTimeMillis(),
                fp
            )
            return true
        }
        val name = row.name.trim()
        val local = categoryDao.findByName(name)
        val localId = local?.id ?: run {
            val ins = categoryDao.insert(Category(name = name))
            when {
                ins > 0L -> ins
                else -> categoryDao.findByName(name)?.id ?: return false
            }
        }
        val bridgeForRow = categoryRemoteRefDao.getByCategoryId(localId)
        if (bridgeForRow != null && bridgeForRow.remoteId != row.id) return false
        if (bridgeForRow != null) return false
        categoryRemoteRefDao.insert(
            CategoryRemoteRef(
                categoryId = localId,
                remoteId = row.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                lastRemotePayloadFingerprint = fp
            )
        )
        return true
    }

    private suspend fun applyRemoteProductInbound(row: InventoryProductRow): Boolean {
        val fp = fingerprintProductInbound(row)
        val supLocal = row.supplierId?.let { supplierRemoteRefDao.getByRemoteId(it)?.supplierId }
        val catLocal = row.categoryId?.let { categoryRemoteRefDao.getByRemoteId(it)?.categoryId }
        val existingRef = productRemoteRefDao.getByRemoteId(row.id)
        if (existingRef != null) {
            if (existingRef.lastRemotePayloadFingerprint == fp &&
                existingRef.localChangeRevision == existingRef.lastSyncedLocalRevision
            ) {
                return false
            }
            val cur = productDao.getById(existingRef.productId) ?: return false
            val merged = cur.copy(
                barcode = row.barcode.trim(),
                itemNumber = row.itemNumber,
                productName = row.productName,
                secondProductName = row.secondProductName,
                purchasePrice = row.purchasePrice,
                retailPrice = row.retailPrice,
                supplierId = supLocal,
                categoryId = catLocal,
                stockQuantity = row.stockQuantity ?: cur.stockQuantity
            )
            try {
                productDao.update(merged)
            } catch (_: SQLiteConstraintException) {
                return false
            }
            productRemoteRefDao.updateRemoteApplyState(
                existingRef.productId,
                existingRef.localChangeRevision,
                System.currentTimeMillis(),
                fp
            )
            return true
        }
        val bc = row.barcode.trim()
        val localByBarcode = productDao.findByBarcode(bc)
        val targetId: Long
        if (localByBarcode != null) {
            val other = productRemoteRefDao.getByProductId(localByBarcode.id)
            if (other != null && other.remoteId != row.id) return false
            targetId = localByBarcode.id
            val merged = localByBarcode.copy(
                itemNumber = row.itemNumber,
                productName = row.productName,
                secondProductName = row.secondProductName,
                purchasePrice = row.purchasePrice,
                retailPrice = row.retailPrice,
                supplierId = supLocal,
                categoryId = catLocal,
                stockQuantity = row.stockQuantity ?: localByBarcode.stockQuantity
            )
            try {
                productDao.update(merged)
            } catch (_: SQLiteConstraintException) {
                return false
            }
        } else {
            val inserted = Product(
                barcode = bc,
                itemNumber = row.itemNumber,
                productName = row.productName,
                secondProductName = row.secondProductName,
                purchasePrice = row.purchasePrice,
                retailPrice = row.retailPrice,
                supplierId = supLocal,
                categoryId = catLocal,
                stockQuantity = row.stockQuantity ?: 0.0
            )
            try {
                productDao.insert(inserted)
            } catch (_: SQLiteConstraintException) {
                return false
            }
            targetId = productDao.findByBarcode(bc)?.id ?: return false
        }
        if (productRemoteRefDao.getByProductId(targetId) != null) return false
        productRemoteRefDao.insert(
            ProductRemoteRef(
                productId = targetId,
                remoteId = row.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                lastRemotePayloadFingerprint = fp
            )
        )
        return true
    }
}

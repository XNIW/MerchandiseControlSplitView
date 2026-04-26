package com.example.merchandisecontrolsplitview.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.util.parseUserNumericInput
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** Riepilogo push backup sessioni history verso `shared_sheet_sessions` (task 023). */
data class HistorySessionBackupPushSummary(
    val uploaded: Int,
    val skippedAlreadySynced: Int,
    val attempted: Int = uploaded
)

interface InventoryRepository {
    // Product methods
    fun getProductsWithDetailsPaged(filter: String?): PagingSource<Int, ProductWithDetails>
    suspend fun findProductByBarcode(barcode: String): Product?
    suspend fun findProductsByBarcodes(barcodes: List<String>): List<Product>
    suspend fun getAllProducts(): List<Product>
    suspend fun getProductDetailsById(productId: Long): ProductWithDetails?
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
    fun observeHistoryEntryByUid(uid: Long): Flow<HistoryEntry?>
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

    /** Uid user-visible che hanno lavoro sessione da pushare; query precisa su Room + bridge. */
    suspend fun getPendingHistorySessionPushUids(): List<Long>

    // --- Pull remoto controllato: apply e dedup per remoteId (task 008) ---

    /**
     * Applica un singolo [SessionRemotePayload] in Room in modo idempotente e non distruttivo.
     *
     * Comportamento:
     * - [payloadVersion] non supportata → [RemoteSessionApplyOutcome.UnsupportedVersion].
     * - [remoteId] già presente nel bridge → aggiorna i campi payload dell'entry esistente;
     *   se il payload è invariato rispetto allo stato locale → [RemoteSessionApplyOutcome.Skipped].
     * - Se esistono modifiche payload locali non ancora consolidate in sync ([HistoryEntryRemoteRef]:
     *   `localChangeRevision > lastSyncedLocalRevision`) → [RemoteSessionApplyOutcome.Skipped] (task 023).
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
     * Breakdown sintetico tombstone + prezzi + bridge catalogo mancanti (task 030/032).
     * I bridge dirty restano intenzionalmente nel solo booleano inclusivo.
     */
    suspend fun getCatalogCloudPendingBreakdown(): CatalogCloudPendingBreakdown

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

    // --- Backup sessioni cloud (task 023/040): Room-first, payload v1 reader + v2 writer ---

    /**
     * Upload conservativo delle sole entry user-visible con bridge in stato “da inviare”
     * ([HistoryEntryRemoteRef.lastRemoteAppliedAt] assente o revisione locale avanti).
     */
    suspend fun pushHistorySessionsToRemote(
        remote: SessionBackupRemoteDataSource,
        ownerUserId: String,
        candidateUids: Set<Long>? = null
    ): Result<HistorySessionBackupPushSummary>

    /** Fetch owner-scoped paginato + [applyRemoteSessionPayloadBatch] (bootstrap / restore). */
    suspend fun bootstrapHistorySessionsFromRemote(
        remote: SessionBackupRemoteDataSource
    ): Result<RemoteSessionBatchResult>
}

internal object DefaultInventoryRepositoryTestHooks {
    @Volatile
    var afterProductsPersisted: (suspend () -> Unit)? = null
}

class DefaultInventoryRepository(private val db: AppDatabase) :
    InventoryRepository,
    CatalogSyncProgressRepository,
    CatalogAutoSyncRepository {

    private data class HistorySessionPushCandidate(
        val entry: HistoryEntry,
        val ref: HistoryEntryRemoteRef,
        val payload: SessionRemotePayload
    )

    private data class CatalogEntityRef(
        val id: Long,
        val name: String
    )

    private data class CatalogPullApplyCounts(
        val suppliers: Int,
        val categories: Int,
        val products: Int,
        val remoteSupplierRows: Int,
        val remoteCategoryRows: Int,
        val remoteProductRows: Int
    )

    private data class PricePullApplyResult(
        val pulled: Int,
        val skippedNoLocalProduct: Int,
        val remoteRowsEvaluated: Int
    )

    private data class CatalogEntityPushResult(
        val count: Int,
        val remoteIds: List<String>
    )

    private data class ProductPricePushResult(
        val count: Int,
        val remoteIds: List<String>
    )

    private data class SyncEventDrainResult(
        val fetched: Int,
        val processed: Int,
        val skippedSelf: Int,
        val skippedDirtyLocal: Int,
        val watermarkBefore: Long,
        val watermarkAfter: Long,
        val targetedProductsFetched: Int,
        val targetedPricesFetched: Int,
        val remoteUpdatesApplied: Int,
        val tooLarge: Boolean,
        val gapDetected: Boolean,
        val manualFullSyncRequired: Boolean
    )

    private data class RemoteSessionLocalState(
        val editable: List<List<String>>,
        val complete: List<Boolean>,
        val totalItems: Int,
        val orderTotal: Double,
        val paymentTotal: Double,
        val missingItems: Int
    )

    private sealed class OverlayApplyState {
        data class Valid(val localState: RemoteSessionLocalState) : OverlayApplyState()
        object Missing : OverlayApplyState()
        object Invalid : OverlayApplyState()
    }

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
    private val pendingCatalogTombstoneDao: PendingCatalogTombstoneDao = db.pendingCatalogTombstoneDao()
    private val syncEventWatermarkDao: SyncEventWatermarkDao = db.syncEventWatermarkDao()
    private val syncEventDeviceStateDao: SyncEventDeviceStateDao = db.syncEventDeviceStateDao()
    private val syncEventOutboxDao: SyncEventOutboxDao = db.syncEventOutboxDao()
    private val tSFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val applyImportMutex = Mutex()
    private val syncEventJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Volatile
    var onHistorySessionPayloadChanged: ((Long) -> Unit)? = null

    @Volatile
    var onProductCatalogChanged: ((Long) -> Unit)? = null

    @Volatile
    var onCatalogChanged: (() -> Unit)? = null
    // --- Product Implementations ---
    override fun getProductsWithDetailsPaged(filter: String?) = productDao.getAllWithDetailsPaged(filter)
    override suspend fun findProductByBarcode(barcode: String) =
        withContext(Dispatchers.IO) { productDao.findDetailsByBarcode(barcode)?.productWithCurrentPrices() }

    override suspend fun findProductsByBarcodes(barcodes: List<String>) =
        withContext(Dispatchers.IO) {
            if (barcodes.isEmpty()) emptyList()
            else productDao.findDetailsByBarcodes(barcodes).map { it.productWithCurrentPrices() }
        }
    override suspend fun getAllProducts(): List<Product> = withContext(Dispatchers.IO) { productDao.getAll() }
    override suspend fun getProductDetailsById(productId: Long): ProductWithDetails? =
        withContext(Dispatchers.IO) { productDao.getDetailsById(productId) }

    override suspend fun addProduct(product: Product) {
        val persistedId = withContext(Dispatchers.IO) {
            productDao.insert(product)
            val persisted = productDao.findByBarcode(product.barcode) ?: return@withContext null

            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let { priceDao.insertIfChanged(persisted.id, "PURCHASE", it, now, "MANUAL") }
            product.retailPrice  ?.let { priceDao.insertIfChanged(persisted.id, "RETAIL",   it, now, "MANUAL") }
            touchProductDirty(persisted.id)
            persisted.id
        }
        persistedId?.let(::notifyProductCatalogChanged)
    }
    override suspend fun updateProduct(product: Product) {
        withContext(Dispatchers.IO) {
            productDao.update(product)

            val now = LocalDateTime.now().format(tSFMT)

            product.purchasePrice?.let { priceDao.insertIfChanged(product.id, "PURCHASE", it, now, "MANUAL") }
            product.retailPrice  ?.let { priceDao.insertIfChanged(product.id, "RETAIL",   it, now, "MANUAL") }
            touchProductDirty(product.id)
        }
        notifyProductCatalogChanged(product.id)
    }
    override suspend fun getAllProductsWithDetails(): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getAllWithDetailsOnce() }

    override suspend fun getProductsWithDetailsPage(limit: Int, offset: Int): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getWithDetailsPage(limit, offset) }
    override suspend fun deleteProduct(product: Product) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                productRemoteRefDao.getByProductId(product.id)?.remoteId?.let { rid ->
                    pendingCatalogTombstoneDao.insert(
                        PendingCatalogTombstone(
                            entityType = PendingCatalogTombstoneEntityTypes.PRODUCT,
                            remoteId = rid,
                            enqueuedAtMs = System.currentTimeMillis(),
                            attemptCount = 0
                        )
                    )
                }
                productDao.delete(product)
            }
        }
        notifyCatalogChanged()
    }
    override suspend fun applyImport(request: ImportApplyRequest): ImportApplyResult =
        withContext(Dispatchers.IO) {
            if (!applyImportMutex.tryLock()) {
                return@withContext ImportApplyResult.AlreadyRunning
            }

            try {
                val touchedProductIds = db.withTransaction {
                    applyImportAtomically(request)
                }
                touchedProductIds.forEach(::notifyProductCatalogChanged)
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
    override suspend fun addSupplier(name: String): Supplier? {
        val (supplier, didCreate) = withContext(Dispatchers.IO) {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return@withContext null to false
            supplierMutex.withLock {
                supplierDao.findByNameIgnoreCase(normalizedName)?.let { return@withLock it to false }
                val newSupplier = Supplier(name = normalizedName)
                val insertedId = supplierDao.insert(newSupplier)
                val created = if (insertedId > 0L) {
                    supplierDao.getById(insertedId)
                } else {
                    supplierDao.findByNameIgnoreCase(normalizedName)
                }
                Pair(
                    created?.also { touchSupplierDirty(it.id) },
                    created != null && insertedId > 0L
                )
            }
        }
        if (didCreate) {
            notifyCatalogChanged()
        }
        return supplier
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
    ): CatalogListItem {
        val item = withContext(Dispatchers.IO) {
            withCatalogMutationLock(kind) {
                val created = createCatalogEntryLocked(kind, normalizedNameFor(kind, name))
                when (kind) {
                    CatalogEntityKind.SUPPLIER -> touchSupplierDirty(created.id)
                    CatalogEntityKind.CATEGORY -> touchCategoryDirty(created.id)
                }
                created
            }
        }
        notifyCatalogChanged()
        return item
    }

    override suspend fun renameCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        newName: String
    ): CatalogListItem {
        val item = withContext(Dispatchers.IO) {
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
        notifyCatalogChanged()
        return item
    }

    override suspend fun deleteCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        strategy: CatalogDeleteStrategy
    ): CatalogDeleteResult {
        val result = withContext(Dispatchers.IO) {
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
                            when (kind) {
                                CatalogEntityKind.SUPPLIER -> touchSupplierDirty(replacement.id)
                                CatalogEntityKind.CATEGORY -> touchCategoryDirty(replacement.id)
                            }
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
        notifyCatalogChanged()
        return result
    }
    override suspend fun recordPriceIfChanged(
        productId: Long,
        type: String,
        price: Double,
        at: String,
        source: String?
    ) {
        val inserted = withContext(Dispatchers.IO) {
            priceDao.insertIfChanged(productId, type, price, at, source)
        }
        if (inserted) {
            notifyProductCatalogChanged(productId)
        }
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
    override suspend fun addCategory(name: String): Category? {
        val (category, didCreate) = withContext(Dispatchers.IO) {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return@withContext null to false
            categoryMutex.withLock {
                categoryDao.findByName(normalizedName)?.let { return@withLock it to false }
                val newCategory = Category(name = normalizedName)
                val insertedId = categoryDao.insert(newCategory)
                val created = if (insertedId > 0L) {
                    categoryDao.getById(insertedId)
                } else {
                    categoryDao.findByName(normalizedName)
                }
                Pair(
                    created?.also { touchCategoryDirty(it.id) },
                    created != null && insertedId > 0L
                )
            }
        }
        if (didCreate) {
            notifyCatalogChanged()
        }
        return category
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

    override fun observeHistoryEntryByUid(uid: Long): Flow<HistoryEntry?> =
        historyDao.observeByUid(uid)

    override suspend fun getHistoryEntryByUid(uid: Long) = withContext(Dispatchers.IO) { historyDao.getByUid(uid) }
    override suspend fun insertHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val uid = historyDao.insert(entry.withInitialDisplayName())
        if (uid > 0L) notifyHistorySessionPayloadChanged(uid)
        uid
    }
    override suspend fun updateHistoryEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val bridgeRef = remoteRefDao.getByHistoryEntryUid(entry.uid)
        var payloadRelevant = false
        if (bridgeRef != null) {
            // Se esiste un bridge, confronta i campi payload-rilevanti prima di aggiornare.
            // Lettura esplicita dell'entry corrente per rilevare la divergenza in modo centralizzato.
            val old = historyDao.getByUid(entry.uid)
            historyDao.update(entry)
            payloadRelevant = old != null && isPayloadRelevantChange(old, entry)
            if (payloadRelevant) {
                remoteRefDao.incrementLocalRevision(entry.uid)
            }
        } else {
            val old = historyDao.getByUid(entry.uid)
            historyDao.update(entry)
            payloadRelevant = old == null || isPayloadRelevantChange(old, entry)
        }
        if (payloadRelevant) notifyHistorySessionPayloadChanged(entry.uid)
    }

    /**
     * Restituisce true se la modifica tocca almeno un campo incluso in [SessionRemotePayload] v2.
     * Usata da [updateHistoryEntry] per decidere se incrementare [HistoryEntryRemoteRef.localChangeRevision].
     */
    private fun isPayloadRelevantChange(old: HistoryEntry, new: HistoryEntry): Boolean =
        old.displayName != new.displayName ||
        old.timestamp != new.timestamp ||
        old.supplier != new.supplier ||
        old.category != new.category ||
        old.isManualEntry != new.isManualEntry ||
        old.data != new.data ||
        old.editable != new.editable ||
        old.complete != new.complete

    private fun HistoryEntry.withInitialDisplayName(): HistoryEntry =
        if (displayName.isNotBlank()) this
        else copy(displayName = id.takeUnless(::looksLikeUuid).orEmpty())

    private fun looksLikeUuid(value: String): Boolean =
        UUID_PATTERN.matches(value.trim())

    private fun notifyHistorySessionPayloadChanged(uid: Long) {
        onHistorySessionPayloadChanged?.invoke(uid)
    }

    private fun notifyProductCatalogChanged(productId: Long) {
        onProductCatalogChanged?.invoke(productId)
    }

    private fun notifyCatalogChanged() {
        onCatalogChanged?.invoke()
    }
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
        val detailsByBarcode = productDao.findDetailsByBarcodes(barcodes)
            .associateBy { it.product.barcode }
        barcodes.associateWith { barcode ->
            val details = detailsByBarcode[barcode]
            details?.currentPurchasePrice to details?.currentRetailPrice
        }
    }

    override suspend fun getCurrentPriceSnapshot(): List<CurrentPriceRow> = withContext(Dispatchers.IO) {
        productDao.getAllWithDetailsOnce().map { details ->
            val product = details.product
            CurrentPriceRow(
                productId = product.id,
                barcode = product.barcode,
                purchasePrice = details.currentPurchasePrice,
                retailPrice = details.currentRetailPrice
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

    override suspend fun getPendingHistorySessionPushUids(): List<Long> =
        withContext(Dispatchers.IO) { historyDao.getUserVisibleSessionPushCandidateUids() }

    // --- Pull remoto controllato (task 008) ---

    override suspend fun applyRemoteSessionPayload(payload: SessionRemotePayload): RemoteSessionApplyOutcome =
        withContext(Dispatchers.IO) {
            if (payload.payloadVersion !in SUPPORTED_SESSION_PAYLOAD_VERSIONS) {
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
        val overlayState = buildOverlayStateForPayload(payload)
        val existingRef = remoteRefDao.getByRemoteId(payload.remoteId)
        if (existingRef != null) {
            // Policy anti-overwrite (task 023): mai applicare inbound se ci sono modifiche payload
            // non ancora sincronizzate verso remoto o consolidate via apply precedente.
            if (existingRef.localChangeRevision > existingRef.lastSyncedLocalRevision) {
                return RemoteSessionApplyOutcome.Skipped
            }
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
            val incomingDisplayName = displayNameFromPayload(payload, existingEntry.displayName)
            val displayNameUnchanged = existingEntry.displayName == incomingDisplayName
            if (existingEntry.timestamp == payload.timestamp &&
                displayNameUnchanged &&
                existingEntry.supplier == payload.supplier &&
                existingEntry.category == payload.category &&
                existingEntry.isManualEntry == payload.isManualEntry &&
                existingEntry.data == payload.data &&
                overlayState.isMateriallySameAs(existingEntry)) {
                return RemoteSessionApplyOutcome.Skipped
            }
            val refreshedLocalState = when {
                payload.payloadVersion == SESSION_PAYLOAD_VERSION && overlayState is OverlayApplyState.Valid ->
                    overlayState.localState
                payload.payloadVersion == SESSION_PAYLOAD_VERSION && existingEntry.data != payload.data ->
                    buildRemoteSessionLocalState(
                        data = payload.data,
                        overlay = existingEntry.safeOverlayForPayloadData(payload.data)
                    )
                payload.payloadVersion == SESSION_PAYLOAD_VERSION_LEGACY_V1 && existingEntry.data != payload.data ->
                    buildRemoteSessionLocalState(payload.data)
                else -> null
            }
            // Aggiorna i campi payload. In v2 l'overlay valido ripristina lo stato operativo;
            // se l'overlay manca/è invalido, preserva editable/complete locali solo se
            // restano allineati alla nuova shape di data, altrimenti ricostruisce default sicuri.
            // Chiama historyDao.update() direttamente (non updateHistoryEntry) per non incrementare
            // localChangeRevision: il remote apply non è una modifica locale.
            historyDao.update(
                existingEntry.copy(
                    displayName = incomingDisplayName,
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
        val localState = when (overlayState) {
            is OverlayApplyState.Valid -> overlayState.localState
            OverlayApplyState.Invalid,
            OverlayApplyState.Missing -> buildRemoteSessionLocalState(payload.data)
        }
        val newEntry = HistoryEntry(
            uid = 0,
            id = payload.remoteId,   // UUID stabile, non collide con prefissi tecnici né nomi utente
            displayName = displayNameFromPayload(payload, ""),
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

    private fun buildOverlayStateForPayload(payload: SessionRemotePayload): OverlayApplyState {
        if (payload.payloadVersion == SESSION_PAYLOAD_VERSION_LEGACY_V1) {
            return OverlayApplyState.Missing
        }
        val overlay = payload.sessionOverlay ?: return OverlayApplyState.Missing
        val overlayBytes = overlay.canonicalString().encodeToByteArray().size
        val valid = overlay.overlaySchema == SESSION_OVERLAY_SCHEMA &&
            overlayBytes <= SESSION_OVERLAY_MAX_BYTES &&
            overlay.editable.size == payload.data.size &&
            overlay.complete.size == payload.data.size
        if (!valid) {
            Log.w(
                HISTORY_SESSION_SYNC_TAG,
                "reason=overlay_shape_reject remoteId=${payload.remoteId} " +
                    "payloadVersionRead=${payload.payloadVersion} dataRows=${payload.data.size} " +
                    "editableRows=${overlay.editable.size} completeRows=${overlay.complete.size} " +
                    "overlayBytes=$overlayBytes"
            )
            return OverlayApplyState.Invalid
        }
        return OverlayApplyState.Valid(
            buildRemoteSessionLocalState(
                data = payload.data,
                overlay = overlay
            )
        )
    }

    private fun OverlayApplyState.isMateriallySameAs(entry: HistoryEntry): Boolean =
        when (this) {
            is OverlayApplyState.Valid ->
                entry.editable == localState.editable &&
                    entry.complete == localState.complete &&
                    entry.totalItems == localState.totalItems &&
                    entry.orderTotal == localState.orderTotal &&
                    entry.paymentTotal == localState.paymentTotal &&
                    entry.missingItems == localState.missingItems
            OverlayApplyState.Missing,
            OverlayApplyState.Invalid -> true
        }

    private fun displayNameFromPayload(payload: SessionRemotePayload, current: String): String =
        if (payload.payloadVersion == SESSION_PAYLOAD_VERSION) {
            payload.displayName ?: current
        } else {
            current
        }

    private fun HistoryEntry.safeOverlayForPayloadData(data: List<List<String>>): SessionOverlay? =
        if (editable.size == data.size && complete.size == data.size) {
            SessionOverlay(
                overlaySchema = SESSION_OVERLAY_SCHEMA,
                editable = editable,
                complete = complete
            )
        } else {
            null
        }

    private fun buildRemoteSessionLocalState(data: List<List<String>>): RemoteSessionLocalState {
        return buildRemoteSessionLocalState(data, overlay = null)
    }

    private fun buildRemoteSessionLocalState(
        data: List<List<String>>,
        overlay: SessionOverlay?
    ): RemoteSessionLocalState {
        val editable = overlay?.editable ?: List(data.size) { listOf("", "") }
        val complete = overlay?.complete ?: List(data.size) { false }

        val header = data.firstOrNull().orEmpty()
        val purchasePriceIndex = header.indexOf("purchasePrice")
        val quantityIndex = header.indexOf("quantity")
        val discountedPriceIndex = header.indexOf("discountedPrice")
        val discountIndex = header.indexOf("discount")

        var totalItems = 0
        var orderTotal = 0.0
        var completedItems = 0
        var paymentTotal = 0.0

        if (purchasePriceIndex != -1 && quantityIndex != -1) {
            data.drop(1).forEachIndexed { index, row ->
                val modelIndex = index + 1
                val quantity = parseUserQuantityInput(row.getOrNull(quantityIndex)) ?: 0.0
                if (quantity > 0) {
                    totalItems++
                    val purchasePrice = parseUserPriceInput(row.getOrNull(purchasePriceIndex)) ?: 0.0
                    orderTotal += purchasePrice * quantity
                }
                if (complete.getOrNull(modelIndex) == true) {
                    completedItems++
                    val realQuantityStr = editable.getOrNull(modelIndex)?.getOrNull(0).orEmpty()
                    val originalQuantityStr = row.getOrNull(quantityIndex).orEmpty()
                    val quantityToUse = parseUserQuantityInput(realQuantityStr.ifBlank { originalQuantityStr }) ?: 0.0
                    if (quantityToUse > 0) {
                        val purchasePrice = parseUserPriceInput(row.getOrNull(purchasePriceIndex)) ?: 0.0
                        val discountedPrice = parseUserPriceInput(row.getOrNull(discountedPriceIndex))
                        val discountPercent = parseUserNumericInput(row.getOrNull(discountIndex))
                        val finalPaymentPrice = when {
                            discountedPrice != null -> discountedPrice
                            discountPercent != null -> purchasePrice * (1 - (discountPercent / 100))
                            else -> purchasePrice
                        }
                        paymentTotal += finalPaymentPrice * quantityToUse
                    }
                }
            }
        }
        val missingItems = (data.size - 1).coerceAtLeast(0) - completedItems

        return RemoteSessionLocalState(
            editable = editable,
            complete = complete,
            totalItems = totalItems,
            orderTotal = orderTotal,
            paymentTotal = overlay?.let { paymentTotal } ?: orderTotal,
            missingItems = overlay?.let { missingItems } ?: totalItems
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

    private suspend fun applyImportAtomically(request: ImportApplyRequest): Set<Long> {
        val supplierIdsByName = supplierDao.getAll()
            .associate { it.name.trim().lowercase() to it.id }
            .toMutableMap()
        val categoryIdsByName = categoryDao.getAll()
            .associate { it.name.trim().lowercase() to it.id }
            .toMutableMap()
        val createdSupplierIds = mutableSetOf<Long>()
        val createdCategoryIds = mutableSetOf<Long>()

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

            if (insertedId > 0L) {
                createdSupplierIds += resolvedId
            }
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
            if (insertedId > 0L) {
                createdCategoryIds += resolvedId
            }
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
        val touchedProductIds = persistedProducts.map { it.id }.filter { it > 0L }.toSet()
        createdSupplierIds.forEach { touchSupplierDirty(it) }
        createdCategoryIds.forEach { touchCategoryDirty(it) }
        touchedProductIds.forEach { touchProductDirty(it) }
        Log.d(
            TAG,
            "import_dirty_marking productsTouched=${touchedProductIds.size} " +
                "suppliersCreated=${createdSupplierIds.size} categoriesCreated=${createdCategoryIds.size}"
        )
        return touchedProductIds
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
        id: Long,
        enqueueCloudTombstone: Boolean = true
    ) {
        if (enqueueCloudTombstone) {
            when (kind) {
                CatalogEntityKind.SUPPLIER -> {
                    supplierRemoteRefDao.getBySupplierId(id)?.remoteId?.let { rid ->
                        pendingCatalogTombstoneDao.insert(
                            PendingCatalogTombstone(
                                entityType = PendingCatalogTombstoneEntityTypes.SUPPLIER,
                                remoteId = rid,
                                enqueuedAtMs = System.currentTimeMillis(),
                                attemptCount = 0
                            )
                        )
                    }
                }
                CatalogEntityKind.CATEGORY -> {
                    categoryRemoteRefDao.getByCategoryId(id)?.remoteId?.let { rid ->
                        pendingCatalogTombstoneDao.insert(
                            PendingCatalogTombstone(
                                entityType = PendingCatalogTombstoneEntityTypes.CATEGORY,
                                remoteId = rid,
                                enqueuedAtMs = System.currentTimeMillis(),
                                attemptCount = 0
                            )
                        )
                    }
                }
            }
        }
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

    override suspend fun getCatalogCloudPendingBreakdown(): CatalogCloudPendingBreakdown = withContext(Dispatchers.IO) {
        CatalogCloudPendingBreakdown(
            pendingCatalogTombstones = pendingCatalogTombstoneDao.count(),
            productPricesPendingPriceBridge = priceDao.countPriceRowsPendingPriceBridge(),
            productPricesBlockedWithoutProductRemote = priceDao.countPriceRowsWithoutProductRemote(),
            suppliersMissingRemoteRef = supplierRemoteRefDao.countLocalRowsMissingRemoteRef(),
            categoriesMissingRemoteRef = categoryRemoteRefDao.countLocalRowsMissingRemoteRef(),
            productsMissingRemoteRef = productRemoteRefDao.countLocalRowsMissingRemoteRef()
        )
    }

    override suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = withContext(Dispatchers.IO) {
        if (pendingCatalogTombstoneDao.count() > 0) return@withContext true
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

    override suspend fun pushHistorySessionsToRemote(
        remote: SessionBackupRemoteDataSource,
        ownerUserId: String,
        candidateUids: Set<Long>?
    ): Result<HistorySessionBackupPushSummary> = withContext(Dispatchers.IO) {
        if (!remote.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Session backup remote non configurato"))
        }
        try {
            val candidates = mutableListOf<HistorySessionPushCandidate>()
            val candidateUidList = candidateUids
                ?.filter { it > 0L }
                ?.distinct()
                ?: historyDao.getUserVisibleSessionPushCandidateUids()
            val entries = if (candidateUidList.isEmpty()) {
                emptyList()
            } else {
                historyDao.getUserVisibleSnapshotByUids(candidateUidList)
            }
            val skippedAlreadySynced = if (candidateUids == null) {
                (historyDao.countUserVisible() - entries.size).coerceAtLeast(0)
            } else {
                0
            }
            for (entry in entries) {
                val remoteId = getOrCreateRemoteId(entry.uid) ?: continue
                val ref = remoteRefDao.getByHistoryEntryUid(entry.uid) ?: continue
                if (!historySessionNeedsPush(ref)) {
                    continue
                }
                val payload = entry.toRemotePayload(remoteId)
                val overlayIssue = payload.outboundOverlayPushIssue()
                if (overlayIssue != null) {
                    logOutboundOverlayPushIssue(entry, remoteId, overlayIssue)
                    continue
                }
                candidates.add(
                    HistorySessionPushCandidate(
                        entry = entry,
                        ref = ref,
                        payload = payload
                    )
                )
            }
            var uploaded = 0
            for (chunk in candidates.chunked(SESSION_BACKUP_PUSH_CHUNK)) {
                val rows = chunk.map { it.payload.toSharedSheetSessionUpsertRow(ownerUserId) }
                remote.upsertSessions(rows).getOrElse { error ->
                    logHistorySessionPushFailure(chunk, error)
                    return@withContext Result.failure(error)
                }
                db.withTransaction {
                    for (c in chunk) {
                        remoteRefDao.getByHistoryEntryUid(c.entry.uid) ?: continue
                        val fp = c.payload.payloadFingerprint()
                        remoteRefDao.updateRemoteApplyState(
                            uid = c.entry.uid,
                            // Mark only the revision that produced the uploaded payload.
                            // If the entry changed while the network call was in flight,
                            // localChangeRevision remains ahead and the next push retries it.
                            rev = c.ref.localChangeRevision,
                            appliedAt = System.currentTimeMillis(),
                            fingerprint = fp
                        )
                    }
                }
                uploaded += chunk.size
            }
            Result.success(
                HistorySessionBackupPushSummary(
                    uploaded = uploaded,
                    skippedAlreadySynced = skippedAlreadySynced,
                    attempted = candidates.size
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun bootstrapHistorySessionsFromRemote(
        remote: SessionBackupRemoteDataSource
    ): Result<RemoteSessionBatchResult> = withContext(Dispatchers.IO) {
        if (!remote.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Session backup remote non configurato"))
        }
        try {
            val records = remote.fetchAllSessionsForOwner().getOrElse { return@withContext Result.failure(it) }
            val payloads = records.map { it.toSessionRemotePayload() }
            val result = applyRemoteSessionPayloadBatch(payloads)
            Log.i(
                "HistorySessionSyncV2",
                "cycle=pull_apply outcome=ok inserted=${result.inserted} updated=${result.updated} " +
                    "skipped=${result.skipped} dirtyLocalSkips=${result.skipped} failed=${result.failed} " +
                    "source=bootstrap"
            )
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(
                "HistorySessionSyncV2",
                "cycle=pull_apply outcome=fail inserted=0 updated=0 skipped=0 dirtyLocalSkips=0 " +
                    "failed=1 source=bootstrap",
                t
            )
            Result.failure(t)
        }
    }

    private fun historySessionNeedsPush(ref: HistoryEntryRemoteRef): Boolean =
        ref.lastRemoteAppliedAt == null || ref.localChangeRevision > ref.lastSyncedLocalRevision

    private data class OutboundOverlayPushIssue(
        val reason: String,
        val overlayBytes: Int,
        val editableRows: Int,
        val completeRows: Int
    )

    private fun SessionRemotePayload.outboundOverlayPushIssue(): OutboundOverlayPushIssue? {
        val overlay = sessionOverlay ?: return OutboundOverlayPushIssue(
            reason = "overlay_missing_push",
            overlayBytes = 0,
            editableRows = 0,
            completeRows = 0
        )
        val overlayBytes = overlay.canonicalString().encodeToByteArray().size
        val reason = when {
            overlay.overlaySchema != SESSION_OVERLAY_SCHEMA -> "overlay_schema_unsupported_push"
            overlayBytes > SESSION_OVERLAY_MAX_BYTES -> "overlay_too_large"
            overlay.editable.size != data.size || overlay.complete.size != data.size -> "overlay_shape_reject_push"
            else -> null
        } ?: return null
        return OutboundOverlayPushIssue(
            reason = reason,
            overlayBytes = overlayBytes,
            editableRows = overlay.editable.size,
            completeRows = overlay.complete.size
        )
    }

    private fun logOutboundOverlayPushIssue(
        entry: HistoryEntry,
        remoteId: String,
        issue: OutboundOverlayPushIssue
    ) {
        Log.w(
            HISTORY_SESSION_SYNC_TAG,
            "reason=${issue.reason} historyEntryUid=${entry.uid} remoteId=$remoteId " +
                "dataRows=${entry.data.size} editableRows=${issue.editableRows} " +
                "completeRows=${issue.completeRows} overlayBytes=${issue.overlayBytes} " +
                "maxBytes=$SESSION_OVERLAY_MAX_BYTES"
        )
    }

    override suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String
    ): Result<CatalogSyncSummary> =
        syncCatalogWithRemote(
            remote = remote,
            priceRemote = priceRemote,
            ownerUserId = ownerUserId,
            progressReporter = CatalogSyncProgressReporter { }
        )

    override suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val recoveryCache = CatalogConflictRecoveryCache()
            val deferredPrices = measureCatalogSyncPhase(CatalogSyncStage.REALIGN, phaseDurationsMs) {
                progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.REALIGN))
                drainPendingCatalogTombstones(remote, ownerUserId)
                // Snapshot iniziale (prima di ensure/push catalogo): righe prezzo senza bridge prodotto.
                val deferred = priceDao.countPriceRowsWithoutProductRemote()
                // Bridge realign pre-push: se il locale ha righe catalogo senza `*_remote_refs`
                // ma il remoto contiene gia una riga attiva con stesso name/barcode, allineiamo
                // il bridge locale al remoteId esistente — altrimenti `ensureXxxRefForPush`
                // genererebbe UUID nuovi che violano gli UNIQUE parziali `(owner_user_id, lower(name))`
                // / `(owner_user_id, barcode)` WHERE deleted_at IS NULL → 23505 / HTTP 409.
                realignCatalogBridgesIfNeeded(remote, recoveryCache)
                deferred
            }
            val pushedSuppliers = measureCatalogSyncPhase(CatalogSyncStage.PUSH_SUPPLIERS, phaseDurationsMs) {
                pushCatalogSuppliers(remote, ownerUserId, recoveryCache, progressReporter)
            }
            val pushedCategories = measureCatalogSyncPhase(CatalogSyncStage.PUSH_CATEGORIES, phaseDurationsMs) {
                pushCatalogCategories(remote, ownerUserId, recoveryCache, progressReporter)
            }
            val pushedProducts = measureCatalogSyncPhase(CatalogSyncStage.PUSH_PRODUCTS, phaseDurationsMs) {
                pushCatalogProducts(remote, ownerUserId, recoveryCache, progressReporter)
            }
            var pulledSuppliers = 0
            var pulledCategories = 0
            var pulledProducts = 0
            var remoteProductRowsInBundle = 0
            measureCatalogSyncPhase(CatalogSyncStage.PULL_CATALOG, phaseDurationsMs) {
                val counts = pullCatalogFromRemote(remote, progressReporter)
                pulledSuppliers = counts.suppliers
                pulledCategories = counts.categories
                pulledProducts = counts.products
                remoteProductRowsInBundle = counts.remoteProductRows
            }
            var pushedPrices = 0
            var pulledPrices = 0
            var skippedPullPrices = 0
            var remotePriceRowsEvaluated = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES))
                        val pullOutcome = pullProductPricesFromRemote(priceRemote, progressReporter)
                        pulledPrices = pullOutcome.pulled
                        skippedPullPrices = pullOutcome.skippedNoLocalProduct
                        remotePriceRowsEvaluated = pullOutcome.remoteRowsEvaluated
                        pushedPrices = pushProductPricesToRemote(priceRemote, ownerUserId, progressReporter).count
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logSyncTransportFailure("price_sync", t)
                    priceSyncFailed = true
                }
            }
            logCatalogSyncPhaseDurations(
                ok = true,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = priceSyncFailed
            )
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = pushedSuppliers.count,
                    pushedCategories = pushedCategories.count,
                    pushedProducts = pushedProducts.count,
                    pulledSuppliers = pulledSuppliers,
                    pulledCategories = pulledCategories,
                    pulledProducts = pulledProducts,
                    pushedProductPrices = pushedPrices,
                    pulledProductPrices = pulledPrices,
                    deferredProductPricesNoProductRef = deferredPrices,
                    skippedProductPricesPullNoProductRef = skippedPullPrices,
                    priceSyncFailed = priceSyncFailed,
                    fullCatalogFetch = true,
                    fullPriceFetch = priceRemote.isConfigured,
                    remoteProductIdsRequested = remoteProductRowsInBundle,
                    remoteProductsFetched = remoteProductRowsInBundle,
                    remotePriceIdsRequested = remotePriceRowsEvaluated,
                    remotePricesFetched = remotePriceRowsEvaluated,
                    incrementalRemoteSubsetVerifiable = true,
                    incrementalRemoteNotVerifiableReason = null
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logCatalogSyncPhaseDurations(
                ok = false,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = null
            )
            Result.failure(t)
        }
    }

    override suspend fun pushDirtyCatalogDeltaToRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        // 044A: lane rapida — vietato fetchCatalog / pull prezzi full-page; solo push delta e metriche oneste.
        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val recoveryCache = CatalogConflictRecoveryCache(allowRemoteFetch = false)
            val deferredPrices = priceDao.countPriceRowsWithoutProductRemote()
            val pushedProducts = measureCatalogSyncPhase(CatalogSyncStage.PUSH_PRODUCTS, phaseDurationsMs) {
                pushCatalogProducts(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    allowCreatingDependencyRefs = false
                )
            }
            var pushedPrices = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES))
                        pushedPrices = pushProductPricesToRemote(
                            priceRemote = priceRemote,
                            ownerUserId = ownerUserId,
                            progressReporter = progressReporter,
                            requireProductSynced = true
                        ).count
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logSyncTransportFailure("auto_price_push", t)
                    priceSyncFailed = true
                }
            }
            logCatalogSyncPhaseDurations(
                ok = true,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = priceSyncFailed
            )
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = pushedProducts.count,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = 0,
                    pushedProductPrices = pushedPrices,
                    pulledProductPrices = 0,
                    deferredProductPricesNoProductRef = deferredPrices,
                    skippedProductPricesPullNoProductRef = 0,
                    priceSyncFailed = priceSyncFailed,
                    fullCatalogFetch = false,
                    fullPriceFetch = false,
                    remoteProductIdsRequested = 0,
                    remoteProductsFetched = 0,
                    remotePriceIdsRequested = 0,
                    remotePricesFetched = 0,
                    incrementalRemoteSubsetVerifiable = false,
                    incrementalRemoteNotVerifiableReason =
                        CatalogIncrementalRemoteContract044A.INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logCatalogSyncPhaseDurations(
                ok = false,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = null
            )
            Result.failure(t)
        }
    }

    override suspend fun syncCatalogQuickWithEvents(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val capabilities = syncEventRemote.checkCapabilities(ownerUserId).getOrElse {
            SyncEventRemoteCapabilities.disabled("sync_events_capability_error")
        }
        if (!syncEventRemote.isConfigured ||
            !capabilities.syncEventsAvailable ||
            !capabilities.recordSyncEventAvailable
        ) {
            return@withContext pushDirtyCatalogDeltaToRemote(
                remote = remote,
                priceRemote = priceRemote,
                ownerUserId = ownerUserId,
                progressReporter = progressReporter
            ).map {
                it.copy(
                    syncEventsAvailable = capabilities.syncEventsAvailable,
                    recordSyncEventAvailable = capabilities.recordSyncEventAvailable,
                    realtimeSyncEventsAvailable = capabilities.realtimeSyncEventsAvailable,
                    syncEventsFallback044 = true,
                    syncEventsDisabled = true,
                    syncEventOutboxPending = syncEventOutboxDao.countPending(ownerUserId)
                )
            }
        }

        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val deviceId = getOrCreateSyncEventDeviceId()
            val storeScope = syncEventStoreScope(null)
            val watermarkBefore = currentSyncEventWatermark(ownerUserId, storeScope)
            val outboxRetried = retrySyncEventOutbox(syncEventRemote, ownerUserId)
            val recoveryCache = CatalogConflictRecoveryCache(allowRemoteFetch = false)
            val tombstonedIds = measureCatalogSyncPhase(CatalogSyncStage.REALIGN, phaseDurationsMs) {
                progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.REALIGN))
                drainPendingCatalogTombstones(remote, ownerUserId)
            }
            val deferredPrices = priceDao.countPriceRowsWithoutProductRemote()
            val pushedSuppliers = measureCatalogSyncPhase(CatalogSyncStage.PUSH_SUPPLIERS, phaseDurationsMs) {
                pushCatalogSuppliers(remote, ownerUserId, recoveryCache, progressReporter)
            }
            val pushedCategories = measureCatalogSyncPhase(CatalogSyncStage.PUSH_CATEGORIES, phaseDurationsMs) {
                pushCatalogCategories(remote, ownerUserId, recoveryCache, progressReporter)
            }
            val pushedProducts = measureCatalogSyncPhase(CatalogSyncStage.PUSH_PRODUCTS, phaseDurationsMs) {
                pushCatalogProducts(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    allowCreatingDependencyRefs = false
                )
            }
            var pushedPrices = ProductPricePushResult(count = 0, remoteIds = emptyList())
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES))
                        pushedPrices = pushProductPricesToRemote(
                            priceRemote = priceRemote,
                            ownerUserId = ownerUserId,
                            progressReporter = progressReporter,
                            requireProductSynced = true
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logSyncTransportFailure("sync_events_quick_price_push", t)
                    priceSyncFailed = true
                }
            }

            val batchId = java.util.UUID.randomUUID().toString()
            val catalogIds = SyncEventEntityIds(
                supplierIds = (pushedSuppliers.remoteIds + tombstonedIds.supplierIds).distinct(),
                categoryIds = (pushedCategories.remoteIds + tombstonedIds.categoryIds).distinct(),
                productIds = (pushedProducts.remoteIds + tombstonedIds.productIds).distinct()
            )
            val catalogEventType = if (
                pushedSuppliers.count + pushedCategories.count + pushedProducts.count == 0 &&
                !tombstonedIds.isEmpty
            ) {
                SyncEventTypes.CATALOG_TOMBSTONE
            } else {
                SyncEventTypes.CATALOG_CHANGED
            }
            val catalogEventEmitted = recordOrEnqueueSyncEvent(
                remote = syncEventRemote,
                ownerUserId = ownerUserId,
                storeScope = storeScope,
                ids = catalogIds,
                domain = SyncEventDomains.CATALOG,
                eventType = catalogEventType,
                batchId = batchId,
                deviceId = deviceId
            )
            val priceEventEmitted = recordOrEnqueueSyncEvent(
                remote = syncEventRemote,
                ownerUserId = ownerUserId,
                storeScope = storeScope,
                ids = SyncEventEntityIds(priceIds = pushedPrices.remoteIds.distinct()),
                domain = SyncEventDomains.PRICES,
                eventType = SyncEventTypes.PRICES_CHANGED,
                batchId = batchId,
                deviceId = deviceId
            )

            val drain = drainSyncEventsInternal(
                remote = remote,
                priceRemote = priceRemote,
                syncEventRemote = syncEventRemote,
                ownerUserId = ownerUserId,
                deviceId = deviceId,
                progressReporter = progressReporter
            )
            val outboxPending = syncEventOutboxDao.countPending(ownerUserId)
            logCatalogSyncPhaseDurations(
                ok = true,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = priceSyncFailed
            )
            logSyncEventSummary(
                phase = "quick",
                capabilities = capabilities,
                outboxPending = outboxPending,
                outboxRetried = outboxRetried,
                drain = drain,
                catalogEventEmitted = catalogEventEmitted,
                priceEventEmitted = priceEventEmitted
            )
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = pushedSuppliers.count,
                    pushedCategories = pushedCategories.count,
                    pushedProducts = pushedProducts.count,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = drain.remoteUpdatesApplied,
                    pushedProductPrices = pushedPrices.count,
                    pulledProductPrices = drain.targetedPricesFetched,
                    deferredProductPricesNoProductRef = deferredPrices,
                    skippedProductPricesPullNoProductRef = 0,
                    priceSyncFailed = priceSyncFailed,
                    fullCatalogFetch = false,
                    fullPriceFetch = false,
                    remoteProductIdsRequested = drain.targetedProductsFetched,
                    remoteProductsFetched = drain.targetedProductsFetched,
                    remotePriceIdsRequested = drain.targetedPricesFetched,
                    remotePricesFetched = drain.targetedPricesFetched,
                    incrementalRemoteSubsetVerifiable = true,
                    incrementalRemoteNotVerifiableReason = null,
                    incrementalCatchUpTooLarge = drain.tooLarge,
                    syncEventsAvailable = capabilities.syncEventsAvailable,
                    recordSyncEventAvailable = capabilities.recordSyncEventAvailable,
                    realtimeSyncEventsAvailable = capabilities.realtimeSyncEventsAvailable,
                    syncEventOutboxPending = outboxPending,
                    syncEventOutboxRetried = outboxRetried,
                    syncEventsFetched = drain.fetched,
                    syncEventsProcessed = drain.processed,
                    syncEventsSkippedSelf = drain.skippedSelf,
                    syncEventsSkippedDirtyLocal = drain.skippedDirtyLocal,
                    syncEventsWatermarkBefore = watermarkBefore,
                    syncEventsWatermarkAfter = drain.watermarkAfter,
                    syncEventsTooLarge = drain.tooLarge,
                    syncEventsGapDetected = drain.gapDetected,
                    targetedProductsFetched = drain.targetedProductsFetched,
                    targetedPricesFetched = drain.targetedPricesFetched,
                    remoteUpdatesApplied = drain.remoteUpdatesApplied,
                    manualFullSyncRequired = drain.manualFullSyncRequired
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logCatalogSyncPhaseDurations(
                ok = false,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = null
            )
            Result.failure(t)
        }
    }

    override suspend fun drainSyncEventsFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val capabilities = syncEventRemote.checkCapabilities(ownerUserId).getOrElse {
            SyncEventRemoteCapabilities.disabled("sync_events_capability_error")
        }
        if (!syncEventRemote.isConfigured || !capabilities.syncEventsAvailable) {
            return@withContext Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 0,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = 0,
                    syncEventsAvailable = capabilities.syncEventsAvailable,
                    recordSyncEventAvailable = capabilities.recordSyncEventAvailable,
                    realtimeSyncEventsAvailable = capabilities.realtimeSyncEventsAvailable,
                    syncEventsDisabled = true,
                    syncEventsFallback044 = true,
                    syncEventOutboxPending = syncEventOutboxDao.countPending(ownerUserId)
                )
            )
        }
        try {
            val deviceId = getOrCreateSyncEventDeviceId()
            val outboxRetried = retrySyncEventOutbox(syncEventRemote, ownerUserId)
            val drain = drainSyncEventsInternal(
                remote = remote,
                priceRemote = priceRemote,
                syncEventRemote = syncEventRemote,
                ownerUserId = ownerUserId,
                deviceId = deviceId,
                progressReporter = progressReporter
            )
            val outboxPending = syncEventOutboxDao.countPending(ownerUserId)
            logSyncEventSummary(
                phase = "drain",
                capabilities = capabilities,
                outboxPending = outboxPending,
                outboxRetried = outboxRetried,
                drain = drain,
                catalogEventEmitted = false,
                priceEventEmitted = false
            )
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 0,
                    pulledSuppliers = 0,
                    pulledCategories = 0,
                    pulledProducts = drain.remoteUpdatesApplied,
                    pulledProductPrices = drain.targetedPricesFetched,
                    fullCatalogFetch = false,
                    fullPriceFetch = false,
                    remoteProductIdsRequested = drain.targetedProductsFetched,
                    remoteProductsFetched = drain.targetedProductsFetched,
                    remotePriceIdsRequested = drain.targetedPricesFetched,
                    remotePricesFetched = drain.targetedPricesFetched,
                    incrementalRemoteSubsetVerifiable = true,
                    incrementalCatchUpTooLarge = drain.tooLarge,
                    syncEventsAvailable = capabilities.syncEventsAvailable,
                    recordSyncEventAvailable = capabilities.recordSyncEventAvailable,
                    realtimeSyncEventsAvailable = capabilities.realtimeSyncEventsAvailable,
                    syncEventOutboxPending = outboxPending,
                    syncEventOutboxRetried = outboxRetried,
                    syncEventsFetched = drain.fetched,
                    syncEventsProcessed = drain.processed,
                    syncEventsSkippedSelf = drain.skippedSelf,
                    syncEventsSkippedDirtyLocal = drain.skippedDirtyLocal,
                    syncEventsWatermarkBefore = drain.watermarkBefore,
                    syncEventsWatermarkAfter = drain.watermarkAfter,
                    syncEventsTooLarge = drain.tooLarge,
                    syncEventsGapDetected = drain.gapDetected,
                    targetedProductsFetched = drain.targetedProductsFetched,
                    targetedPricesFetched = drain.targetedPricesFetched,
                    remoteUpdatesApplied = drain.remoteUpdatesApplied,
                    manualFullSyncRequired = drain.manualFullSyncRequired
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun pullCatalogBootstrapFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val pullCounts = measureCatalogSyncPhase(CatalogSyncStage.PULL_CATALOG, phaseDurationsMs) {
                pullCatalogFromRemote(remote, progressReporter)
            }
            var pulledPrices = 0
            var skippedPullPrices = 0
            var remotePriceRowsEvaluated = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES))
                        val pullOutcome = pullProductPricesFromRemote(priceRemote, progressReporter)
                        pulledPrices = pullOutcome.pulled
                        skippedPullPrices = pullOutcome.skippedNoLocalProduct
                        remotePriceRowsEvaluated = pullOutcome.remoteRowsEvaluated
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logSyncTransportFailure("bootstrap_price_pull", t)
                    priceSyncFailed = true
                }
            }
            logCatalogSyncPhaseDurations(
                ok = true,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = priceSyncFailed
            )
            Result.success(
                CatalogSyncSummary(
                    pushedSuppliers = 0,
                    pushedCategories = 0,
                    pushedProducts = 0,
                    pulledSuppliers = pullCounts.suppliers,
                    pulledCategories = pullCounts.categories,
                    pulledProducts = pullCounts.products,
                    pushedProductPrices = 0,
                    pulledProductPrices = pulledPrices,
                    deferredProductPricesNoProductRef = 0,
                    skippedProductPricesPullNoProductRef = skippedPullPrices,
                    priceSyncFailed = priceSyncFailed,
                    fullCatalogFetch = true,
                    fullPriceFetch = priceRemote.isConfigured,
                    remoteProductIdsRequested = pullCounts.remoteProductRows,
                    remoteProductsFetched = pullCounts.remoteProductRows,
                    remotePriceIdsRequested = remotePriceRowsEvaluated,
                    remotePricesFetched = remotePriceRowsEvaluated,
                    incrementalRemoteSubsetVerifiable = true,
                    incrementalRemoteNotVerifiableReason = null
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logCatalogSyncPhaseDurations(
                ok = false,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = null
            )
            Result.failure(t)
        }
    }

    private suspend fun pullCatalogFromRemote(
        remote: CatalogRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter
    ): CatalogPullApplyCounts {
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
        val fetchStartedAt = System.currentTimeMillis()
        val bundle = remote.fetchCatalog().getOrThrow()
        val fetchMs = System.currentTimeMillis() - fetchStartedAt
        val applyStartedAt = System.currentTimeMillis()
        val applyCounts = applyCatalogBundleInbound(bundle)
        val applyMs = System.currentTimeMillis() - applyStartedAt
        Log.i(
            TAG,
            "phase_metrics syncDomain=CATALOG phase=PULL_CATALOG " +
                "remoteSuppliers=${bundle.suppliers.size} remoteCategories=${bundle.categories.size} " +
                "remoteProducts=${bundle.products.size} pulledSuppliers=${applyCounts.suppliers} " +
                "pulledCategories=${applyCounts.categories} pulledProducts=${applyCounts.products} " +
                "fetchMs=$fetchMs applyMs=$applyMs"
        )
        return CatalogPullApplyCounts(
            suppliers = applyCounts.suppliers,
            categories = applyCounts.categories,
            products = applyCounts.products,
            remoteSupplierRows = bundle.suppliers.size,
            remoteCategoryRows = bundle.categories.size,
            remoteProductRows = bundle.products.size
        )
    }

    private suspend fun applyCatalogBundleInbound(bundle: InventoryCatalogFetchBundle): CatalogPullApplyCounts {
        var pulledSuppliers = 0
        var pulledCategories = 0
        var pulledProducts = 0
        db.withTransaction {
            for (row in bundle.products.filter { !it.deletedAt.isNullOrBlank() }) {
                if (applyInboundProductTombstone(row)) pulledProducts++
            }
            for (row in bundle.categories.filter { !it.deletedAt.isNullOrBlank() }) {
                if (applyInboundCategoryTombstone(row)) pulledCategories++
            }
            for (row in bundle.suppliers.filter { !it.deletedAt.isNullOrBlank() }) {
                if (applyInboundSupplierTombstone(row)) pulledSuppliers++
            }
            for (row in bundle.suppliers.filter { it.deletedAt.isNullOrBlank() }) {
                if (applyRemoteSupplierInbound(row)) pulledSuppliers++
            }
            for (row in bundle.categories.filter { it.deletedAt.isNullOrBlank() }) {
                if (applyRemoteCategoryInbound(row)) pulledCategories++
            }
            for (row in bundle.products.filter { it.deletedAt.isNullOrBlank() }) {
                if (applyRemoteProductInbound(row)) pulledProducts++
            }
        }
        return CatalogPullApplyCounts(
            suppliers = pulledSuppliers,
            categories = pulledCategories,
            products = pulledProducts,
            remoteSupplierRows = bundle.suppliers.size,
            remoteCategoryRows = bundle.categories.size,
            remoteProductRows = bundle.products.size
        )
    }

    private suspend fun drainSyncEventsInternal(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        deviceId: String,
        progressReporter: CatalogSyncProgressReporter
    ): SyncEventDrainResult {
        val storeScope = syncEventStoreScope(null)
        var watermark = currentSyncEventWatermark(ownerUserId, storeScope)
        val watermarkBefore = watermark
        var fetched = 0
        var processed = 0
        var skippedSelf = 0
        var skippedDirty = 0
        var targetedProductsFetched = 0
        var targetedPricesFetched = 0
        var remoteUpdatesApplied = 0
        var tooLarge = false
        var gapDetected = false
        var manualFullSyncRequired = false
        var iterations = 0

        while (iterations < SYNC_EVENT_DRAIN_MAX_ITERATIONS) {
            val events = syncEventRemote.fetchSyncEventsAfter(
                ownerUserId = ownerUserId,
                storeId = null,
                afterId = watermark,
                limit = SYNC_EVENT_FETCH_LIMIT
            ).getOrThrow().sortedBy { it.id }
            if (events.isEmpty()) break
            fetched += events.size
            for (event in events) {
                if (event.id <= watermark) continue
                val ids = event.entityIds
                if (event.sourceDeviceId == deviceId) {
                    skippedSelf++
                    watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                    continue
                }
                val eventDirty = ids?.let { countDirtyLocalRefsForEvent(it) } ?: 0
                skippedDirty += eventDirty
                if (ids == null || (ids.isEmpty && event.changedCount > 0)) {
                    gapDetected = true
                    manualFullSyncRequired = true
                    watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                    continue
                }
                if (ids.totalIds > SYNC_EVENT_ENTITY_ID_BUDGET) {
                    tooLarge = true
                    manualFullSyncRequired = true
                    watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                    continue
                }
                val applied = when (event.domain) {
                    SyncEventDomains.CATALOG -> {
                        val counts = applyCatalogEventByIds(remote, ids, progressReporter)
                        targetedProductsFetched += counts.remoteProductRows
                        counts.suppliers + counts.categories + counts.products
                    }
                    SyncEventDomains.PRICES -> {
                        val outcome = applyPriceEventByIds(remote, priceRemote, ids, progressReporter)
                        targetedProductsFetched += outcome.first
                        targetedPricesFetched += outcome.second.remoteRowsEvaluated
                        outcome.second.pulled
                    }
                    else -> 0
                }
                remoteUpdatesApplied += applied
                processed++
                watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
            }
            iterations++
            if (events.size < SYNC_EVENT_FETCH_LIMIT) break
        }
        if (iterations >= SYNC_EVENT_DRAIN_MAX_ITERATIONS) {
            gapDetected = true
            manualFullSyncRequired = true
        }
        return SyncEventDrainResult(
            fetched = fetched,
            processed = processed,
            skippedSelf = skippedSelf,
            skippedDirtyLocal = skippedDirty,
            watermarkBefore = watermarkBefore,
            watermarkAfter = watermark,
            targetedProductsFetched = targetedProductsFetched,
            targetedPricesFetched = targetedPricesFetched,
            remoteUpdatesApplied = remoteUpdatesApplied,
            tooLarge = tooLarge,
            gapDetected = gapDetected,
            manualFullSyncRequired = manualFullSyncRequired
        )
    }

    private suspend fun applyCatalogEventByIds(
        remote: CatalogRemoteDataSource,
        ids: SyncEventEntityIds,
        progressReporter: CatalogSyncProgressReporter
    ): CatalogPullApplyCounts {
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
        val first = remote.fetchCatalogByIds(
            supplierIds = ids.supplierIds.toSet(),
            categoryIds = ids.categoryIds.toSet(),
            productIds = ids.productIds.toSet()
        ).getOrThrow()
        val missingSupplierIds = first.products
            .mapNotNull { it.supplierId }
            .filter { supplierRemoteRefDao.getByRemoteId(it) == null }
            .toSet() - ids.supplierIds.toSet()
        val missingCategoryIds = first.products
            .mapNotNull { it.categoryId }
            .filter { categoryRemoteRefDao.getByRemoteId(it) == null }
            .toSet() - ids.categoryIds.toSet()
        val parentBundle = if (missingSupplierIds.isNotEmpty() || missingCategoryIds.isNotEmpty()) {
            remote.fetchCatalogByIds(
                supplierIds = missingSupplierIds,
                categoryIds = missingCategoryIds,
                productIds = emptySet()
            ).getOrThrow()
        } else {
            InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList())
        }
        val merged = mergeCatalogBundles(parentBundle, first)
        val counts = applyCatalogBundleInbound(merged)
        Log.i(
            TAG,
            "sync_events_apply domain=catalog remoteSuppliers=${merged.suppliers.size} " +
                "remoteCategories=${merged.categories.size} remoteProducts=${merged.products.size} " +
                "applied=${counts.suppliers + counts.categories + counts.products}"
        )
        return counts
    }

    private suspend fun applyPriceEventByIds(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ids: SyncEventEntityIds,
        progressReporter: CatalogSyncProgressReporter
    ): Pair<Int, PricePullApplyResult> {
        if (ids.priceIds.isEmpty() || !priceRemote.isConfigured) {
            return 0 to PricePullApplyResult(0, 0, 0)
        }
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES))
        val rows = priceRemote.fetchProductPricesByIds(ids.priceIds.toSet()).getOrThrow()
        val missingProductIds = rows
            .map { it.productId }
            .filter { productRemoteRefDao.getByRemoteId(it) == null }
            .toSet()
        var targetedProductsFetched = 0
        if (missingProductIds.isNotEmpty()) {
            val parentProducts = remote.fetchCatalogByIds(
                supplierIds = emptySet(),
                categoryIds = emptySet(),
                productIds = missingProductIds
            ).getOrThrow()
            val parentSupplierIds = parentProducts.products
                .mapNotNull { it.supplierId }
                .filter { supplierRemoteRefDao.getByRemoteId(it) == null }
                .toSet()
            val parentCategoryIds = parentProducts.products
                .mapNotNull { it.categoryId }
                .filter { categoryRemoteRefDao.getByRemoteId(it) == null }
                .toSet()
            val parentRefs = if (parentSupplierIds.isNotEmpty() || parentCategoryIds.isNotEmpty()) {
                remote.fetchCatalogByIds(
                    supplierIds = parentSupplierIds,
                    categoryIds = parentCategoryIds,
                    productIds = emptySet()
                ).getOrThrow()
            } else {
                InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList())
            }
            val mergedParents = mergeCatalogBundles(parentRefs, parentProducts)
            targetedProductsFetched += mergedParents.products.size
            applyCatalogBundleInbound(mergedParents)
        }
        val result = applyProductPriceRows(rows, progressReporter)
        Log.i(
            TAG,
            "sync_events_apply domain=prices remotePrices=${rows.size} pricesPulled=${result.pulled} " +
                "pricesSkippedNoProductRef=${result.skippedNoLocalProduct}"
        )
        return targetedProductsFetched to result
    }

    private suspend fun applyProductPriceRows(
        remotes: List<InventoryProductPriceRow>,
        progressReporter: CatalogSyncProgressReporter
    ): PricePullApplyResult {
        var pulled = 0
        var skippedNoLocalProduct = 0
        db.withTransaction {
            for ((index, row) in remotes.withIndex()) {
                progressReporter.onProgress(
                    CatalogSyncProgressState.running(
                        CatalogSyncStage.SYNC_PRICES,
                        current = index + 1,
                        total = remotes.size
                    )
                )
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
        return PricePullApplyResult(
            pulled = pulled,
            skippedNoLocalProduct = skippedNoLocalProduct,
            remoteRowsEvaluated = remotes.size
        )
    }

    private fun mergeCatalogBundles(
        first: InventoryCatalogFetchBundle,
        second: InventoryCatalogFetchBundle
    ): InventoryCatalogFetchBundle =
        InventoryCatalogFetchBundle(
            suppliers = (first.suppliers + second.suppliers).distinctBy { it.id },
            categories = (first.categories + second.categories).distinctBy { it.id },
            products = (first.products + second.products).distinctBy { it.id }
        )

    private suspend fun getOrCreateSyncEventDeviceId(): String {
        syncEventDeviceStateDao.get()?.let { return it.deviceId }
        val generated = java.util.UUID.randomUUID().toString()
        syncEventDeviceStateDao.insert(
            SyncEventDeviceState(
                deviceId = generated,
                createdAtMs = System.currentTimeMillis()
            )
        )
        return syncEventDeviceStateDao.get()?.deviceId ?: generated
    }

    private suspend fun currentSyncEventWatermark(ownerUserId: String, storeScope: String): Long =
        syncEventWatermarkDao.get(ownerUserId, storeScope)?.lastSyncEventId ?: 0L

    private suspend fun advanceSyncEventWatermark(ownerUserId: String, storeScope: String, id: Long): Long {
        syncEventWatermarkDao.upsert(
            SyncEventWatermark(
                ownerUserId = ownerUserId,
                storeScope = storeScope,
                lastSyncEventId = id
            )
        )
        return id
    }

    private fun syncEventStoreScope(storeId: String?): String = storeId ?: ""

    private suspend fun retrySyncEventOutbox(
        remote: SyncEventRemoteDataSource,
        ownerUserId: String
    ): Int {
        var retried = 0
        val pending = syncEventOutboxDao.listPending(ownerUserId, SYNC_EVENT_OUTBOX_RETRY_LIMIT)
        for (entry in pending) {
            if (entry.attemptCount >= SYNC_EVENT_OUTBOX_MAX_ATTEMPTS) continue
            val ids = syncEventJson.decodeFromString<SyncEventEntityIds>(entry.entityIdsJson)
            val params = SyncEventRecordRpcParams(
                domain = entry.domain,
                eventType = entry.eventType,
                changedCount = entry.changedCount,
                entityIds = ids,
                storeId = entry.storeScope.ifBlank { null },
                source = entry.source,
                sourceDeviceId = entry.sourceDeviceId,
                batchId = entry.batchId,
                clientEventId = entry.clientEventId,
                metadata = buildJsonObject { put("task", "045") }
            )
            val result = remote.recordSyncEvent(params)
            if (result.isSuccess) {
                syncEventOutboxDao.deleteById(entry.id)
                retried++
            } else {
                val errorType = result.exceptionOrNull()?.let { SyncErrorClassifier.classify(it).category.name }
                    ?: "unknown"
                syncEventOutboxDao.update(
                    entry.copy(
                        attemptCount = entry.attemptCount + 1,
                        lastAttemptAtMs = System.currentTimeMillis(),
                        lastErrorType = errorType
                    )
                )
            }
        }
        return retried
    }

    private suspend fun recordOrEnqueueSyncEvent(
        remote: SyncEventRemoteDataSource,
        ownerUserId: String,
        storeScope: String,
        ids: SyncEventEntityIds,
        domain: String,
        eventType: String,
        batchId: String,
        deviceId: String
    ): Boolean {
        if (ids.isEmpty) return false
        var allRecorded = true
        val chunks = chunkSyncEventIds(ids)
        for ((index, chunk) in chunks.withIndex()) {
            val clientEventId = buildClientEventId(batchId, domain, eventType, chunk, index)
            val metadata = buildJsonObject {
                put("task", "045")
                put("source", "android_repository")
                put("chunk_index", index)
                put("chunk_count", chunks.size)
            }
            val params = SyncEventRecordRpcParams(
                domain = domain,
                eventType = eventType,
                changedCount = chunk.totalIds,
                entityIds = chunk,
                storeId = storeScope.ifBlank { null },
                source = "android",
                sourceDeviceId = deviceId,
                batchId = batchId,
                clientEventId = clientEventId,
                metadata = metadata
            )
            val result = remote.recordSyncEvent(params)
            if (result.isSuccess) continue
            allRecorded = false
            syncEventOutboxDao.insert(
                SyncEventOutboxEntry(
                    ownerUserId = ownerUserId,
                    storeScope = storeScope,
                    domain = domain,
                    eventType = eventType,
                    source = "android",
                    sourceDeviceId = deviceId,
                    batchId = batchId,
                    clientEventId = clientEventId,
                    changedCount = chunk.totalIds,
                    entityIdsJson = syncEventJson.encodeToString(chunk),
                    metadataJson = syncEventJson.encodeToString(metadata),
                    createdAtMs = System.currentTimeMillis(),
                    lastAttemptAtMs = System.currentTimeMillis(),
                    lastErrorType = result.exceptionOrNull()?.let { SyncErrorClassifier.classify(it).category.name }
                )
            )
        }
        return allRecorded
    }

    private fun buildClientEventId(
        batchId: String,
        domain: String,
        eventType: String,
        ids: SyncEventEntityIds,
        chunkIndex: Int
    ): String {
        val fingerprint = listOf(
            ids.supplierIds.sorted().joinToString(","),
            ids.categoryIds.sorted().joinToString(","),
            ids.productIds.sorted().joinToString(","),
            ids.priceIds.sorted().joinToString(",")
        ).joinToString("|").hashCode().toUInt().toString(16)
        return "android-$batchId-$domain-$eventType-$chunkIndex-$fingerprint"
    }

    private fun chunkSyncEventIds(ids: SyncEventEntityIds): List<SyncEventEntityIds> {
        val entries = buildList<Pair<String, String>> {
            ids.supplierIds.forEach { add("supplier" to it) }
            ids.categoryIds.forEach { add("category" to it) }
            ids.productIds.forEach { add("product" to it) }
            ids.priceIds.forEach { add("price" to it) }
        }
        if (entries.isEmpty()) return emptyList()
        return entries.chunked(SYNC_EVENT_ENTITY_ID_BUDGET).map { chunk ->
            SyncEventEntityIds(
                supplierIds = chunk.filter { it.first == "supplier" }.map { it.second },
                categoryIds = chunk.filter { it.first == "category" }.map { it.second },
                productIds = chunk.filter { it.first == "product" }.map { it.second },
                priceIds = chunk.filter { it.first == "price" }.map { it.second }
            )
        }
    }

    private suspend fun countDirtyLocalRefsForEvent(ids: SyncEventEntityIds): Int {
        var dirty = 0
        for (id in ids.supplierIds) {
            val ref = supplierRemoteRefDao.getByRemoteId(id)
            if (ref != null && ref.localChangeRevision > ref.lastSyncedLocalRevision) dirty++
        }
        for (id in ids.categoryIds) {
            val ref = categoryRemoteRefDao.getByRemoteId(id)
            if (ref != null && ref.localChangeRevision > ref.lastSyncedLocalRevision) dirty++
        }
        for (id in ids.productIds) {
            val ref = productRemoteRefDao.getByRemoteId(id)
            if (ref != null && ref.localChangeRevision > ref.lastSyncedLocalRevision) dirty++
        }
        return dirty
    }

    private fun logSyncEventSummary(
        phase: String,
        capabilities: SyncEventRemoteCapabilities,
        outboxPending: Int,
        outboxRetried: Int,
        drain: SyncEventDrainResult,
        catalogEventEmitted: Boolean,
        priceEventEmitted: Boolean
    ) {
        Log.i(
            TAG,
            "sync_events_summary phase=$phase " +
                "syncEventsAvailable=${capabilities.syncEventsAvailable} " +
                "recordSyncEventAvailable=${capabilities.recordSyncEventAvailable} " +
                "realtimeSyncEventsAvailable=${capabilities.realtimeSyncEventsAvailable} " +
                "syncEventOutboxPending=$outboxPending syncEventOutboxRetried=$outboxRetried " +
                "catalogEventEmitted=$catalogEventEmitted priceEventEmitted=$priceEventEmitted " +
                "syncEventsFetched=${drain.fetched} syncEventsProcessed=${drain.processed} " +
                "syncEventsSkippedSelf=${drain.skippedSelf} " +
                "syncEventsSkippedDirtyLocal=${drain.skippedDirtyLocal} " +
                "syncEventsWatermarkBefore=${drain.watermarkBefore} " +
                "syncEventsWatermarkAfter=${drain.watermarkAfter} " +
                "syncEventsTooLarge=${drain.tooLarge} syncEventsGapDetected=${drain.gapDetected} " +
                "targetedProductsFetched=${drain.targetedProductsFetched} " +
                "targetedPricesFetched=${drain.targetedPricesFetched} " +
                "fullCatalogFetch=false fullPriceFetch=false"
        )
    }

    private companion object {
        const val TAG = "CatalogCloudSync"
        const val HISTORY_SESSION_SYNC_TAG = "HistorySessionSyncV2"
        const val PRODUCT_PRICE_PUSH_CHUNK = 80
        const val SYNC_EVENT_FETCH_LIMIT = 100L
        const val SYNC_EVENT_DRAIN_MAX_ITERATIONS = 20
        const val SYNC_EVENT_ENTITY_ID_BUDGET = 250
        const val SYNC_EVENT_OUTBOX_RETRY_LIMIT = 20
        const val SYNC_EVENT_OUTBOX_MAX_ATTEMPTS = 5
        const val SESSION_BACKUP_PUSH_CHUNK = 80
        const val LOG_SAMPLE_LIMIT = 5
        const val POSTGREST_UNIQUE_VIOLATION = "23505"
        val SUPPORTED_SESSION_PAYLOAD_VERSIONS = setOf(
            SESSION_PAYLOAD_VERSION_LEGACY_V1,
            SESSION_PAYLOAD_VERSION
        )
        val UUID_PATTERN = Regex(
            """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"""
        )
    }

    private class CatalogBridgeRealignStats {
        var remoteRowsSeen: Int = 0
        var candidatesWithValidKey: Int = 0
        var localMatches: Int = 0
        var linked: Int = 0
        var relinkedStale: Int = 0
        var skippedEmptyKey: Int = 0
        var skippedNoLocalMatch: Int = 0
        var skippedLocalAlreadyBridged: Int = 0
        var skippedRemoteAlreadyBridged: Int = 0

        fun logFields(prefix: String): String =
            "${prefix}_remote_seen=$remoteRowsSeen " +
                "${prefix}_valid_key=$candidatesWithValidKey " +
                "${prefix}_local_matches=$localMatches " +
                "${prefix}_linked=$linked " +
                "${prefix}_relinked_stale=$relinkedStale " +
                "${prefix}_skip_empty_key=$skippedEmptyKey " +
                "${prefix}_skip_no_local_match=$skippedNoLocalMatch " +
                "${prefix}_skip_local_already_bridged=$skippedLocalAlreadyBridged " +
                "${prefix}_skip_remote_already_bridged=$skippedRemoteAlreadyBridged"
    }

    private class CatalogConflictRecoveryCache(
        private val allowRemoteFetch: Boolean = true
    ) {
        private var bundle: InventoryCatalogFetchBundle? = null

        suspend fun fetch(
            remote: CatalogRemoteDataSource,
            phase: String,
            kind: String,
            localId: Long,
            onFailure: (String, Throwable) -> Unit
        ): InventoryCatalogFetchBundle? {
            bundle?.let { return it }
            if (!allowRemoteFetch) {
                Log.w(TAG, "bridge_recover kind=$kind outcome=skip_remote_fetch_disabled localId=$localId")
                return null
            }
            val loaded = try {
                val result = remote.fetchCatalog()
                if (result.isFailure) {
                    val throwable = result.exceptionOrNull()
                    if (throwable != null) {
                        if (throwable is CancellationException) throw throwable
                        onFailure(phase, throwable)
                    }
                    null
                } else {
                    result.getOrThrow()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                onFailure(phase, t)
                null
            }
            if (loaded == null) {
                Log.w(TAG, "bridge_recover kind=$kind outcome=fetch_failed localId=$localId")
            } else {
                bundle = loaded
            }
            return loaded
        }
    }

    private fun catalogBoundaryTrim(value: String): String =
        value.trim { ch ->
            ch.isWhitespace() ||
                ch == '\u00A0' ||
                ch == '\u2007' ||
                ch == '\u202F' ||
                ch == '\uFEFF'
        }

    private fun normalizeCatalogNameKey(value: String): String =
        catalogBoundaryTrim(value).lowercase()

    private fun normalizeCatalogBarcodeKey(value: String): String =
        catalogBoundaryTrim(value)

    private fun Throwable.isPostgrestUniqueViolationConflict(): Boolean {
        val classification = SyncErrorClassifier.classify(this)
        if (classification.httpStatus == 409 &&
            classification.postgrestCode == POSTGREST_UNIQUE_VIOLATION
        ) {
            return true
        }
        val text = causeChainText()
        return text.contains(POSTGREST_UNIQUE_VIOLATION) &&
            (text.contains("409") || text.contains("duplicate key"))
    }

    private fun Throwable.causeChainText(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(separator = "\n")
            .lowercase()

    private fun logSyncTransportFailure(phase: String, throwable: Throwable) {
        val classification = SyncErrorClassifier.classify(throwable)
        Log.w(
            TAG,
            "phase=$phase category=${classification.category} httpStatus=${classification.httpStatus} " +
                "postgrestCode=${classification.postgrestCode} type=${throwable::class.java.simpleName}"
        )
    }

    private suspend fun <T> measureCatalogSyncPhase(
        stage: CatalogSyncStage,
        durationsMs: MutableMap<CatalogSyncStage, Long>,
        block: suspend () -> T
    ): T {
        val startedAt = System.currentTimeMillis()
        try {
            return block()
        } finally {
            durationsMs[stage] = (durationsMs[stage] ?: 0L) + (System.currentTimeMillis() - startedAt)
        }
    }

    private fun logCatalogSyncPhaseDurations(
        ok: Boolean,
        durationsMs: Map<CatalogSyncStage, Long>,
        priceSyncFailed: Boolean?
    ) {
        val syncDomain = if (durationsMs.containsKey(CatalogSyncStage.SYNC_PRICES)) "MIXED" else "CATALOG"
        Log.i(
            TAG,
            "sync_phase_durations ok=$ok syncDomain=$syncDomain " +
                "realignMs=${durationsMs[CatalogSyncStage.REALIGN]} " +
                "pushSuppliersMs=${durationsMs[CatalogSyncStage.PUSH_SUPPLIERS]} " +
                "pushCategoriesMs=${durationsMs[CatalogSyncStage.PUSH_CATEGORIES]} " +
                "pushProductsMs=${durationsMs[CatalogSyncStage.PUSH_PRODUCTS]} " +
                "pullCatalogMs=${durationsMs[CatalogSyncStage.PULL_CATALOG]} " +
                "syncPricesMs=${durationsMs[CatalogSyncStage.SYNC_PRICES]} " +
                "priceSyncFailed=$priceSyncFailed"
        )
    }

    private fun logHistorySessionPushFailure(
        chunk: List<HistorySessionPushCandidate>,
        throwable: Throwable
    ) {
        val classification = SyncErrorClassifier.classify(throwable)
        Log.w(
            HISTORY_SESSION_SYNC_TAG,
            "cycle=push outcome=fail phase=session_upsert_chunk sessionsInBatch=${chunk.size} " +
                "historyEntryUidSample=${chunk.take(LOG_SAMPLE_LIMIT).joinToString(",") { it.entry.uid.toString() }} " +
                "remoteIdSample=${chunk.take(LOG_SAMPLE_LIMIT).joinToString(",") { it.payload.remoteId }} " +
                "errCategory=${classification.category} httpStatus=${classification.httpStatus} " +
                "postgrestCode=${classification.postgrestCode} type=${throwable::class.java.simpleName}"
        )
    }

    /** Push bulk: una query candidati + chunk verso PostgREST; bridge solo per righe senza remote ancora. */
    private suspend fun pushProductPricesToRemote(
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        requireProductSynced: Boolean = false
    ): ProductPricePushResult {
        val candidates = priceDao.getAllForCloudPush()
        val rows = if (requireProductSynced) {
            candidates.filter { row ->
                val ref = productRemoteRefDao.getByProductId(row.productId)
                ref != null &&
                    ref.lastRemoteAppliedAt != null &&
                    ref.localChangeRevision <= ref.lastSyncedLocalRevision
            }
        } else {
            candidates
        }
        if (rows.isEmpty()) {
            Log.i(
                TAG,
                "phase_metrics syncDomain=PRICES phase=SYNC_PRICES_PUSH " +
                    "pricesEvaluated=${candidates.size} pricesPushed=0 requireProductSynced=$requireProductSynced"
            )
            return ProductPricePushResult(count = 0, remoteIds = emptyList())
        }
        var pushed = 0
        val pushedRemoteIds = mutableListOf<String>()
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
            pushedRemoteIds += pairs.map { it.second }
            progressReporter.onProgress(
                CatalogSyncProgressState.running(
                    CatalogSyncStage.SYNC_PRICES,
                    current = pushed,
                    total = rows.size
                )
            )
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=PRICES phase=SYNC_PRICES_PUSH " +
                "pricesEvaluated=${candidates.size} pricesEligible=${rows.size} pricesPushed=$pushed " +
                "requireProductSynced=$requireProductSynced"
        )
        return ProductPricePushResult(count = pushed, remoteIds = pushedRemoteIds.distinct())
    }

    /**
     * Pull idempotente: dedup su `(productId,type,effectiveAt)` e su `remoteId`; nessun `insertIfChanged`;
     * non aggiorna `products.purchasePrice` / `retailPrice`.
     */
    private suspend fun pullProductPricesFromRemote(
        priceRemote: ProductPriceRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter
    ): PricePullApplyResult {
        val remotes = priceRemote.fetchProductPrices().getOrThrow()
        var pulled = 0
        var skippedNoLocalProduct = 0
        db.withTransaction {
            for ((index, row) in remotes.withIndex()) {
                progressReporter.onProgress(
                    CatalogSyncProgressState.running(
                        CatalogSyncStage.SYNC_PRICES,
                        current = index + 1,
                        total = remotes.size
                    )
                )
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
        Log.i(
            TAG,
            "phase_metrics syncDomain=PRICES phase=SYNC_PRICES_PULL " +
                "remotePricesEvaluated=${remotes.size} pricesPulled=$pulled " +
                "pricesSkippedNoProductRef=$skippedNoLocalProduct"
        )
        return PricePullApplyResult(
            pulled = pulled,
            skippedNoLocalProduct = skippedNoLocalProduct,
            remoteRowsEvaluated = remotes.size
        )
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
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter
    ): CatalogEntityPushResult {
        var n = 0
        var dirty = 0
        var skippedAlreadySynced = 0
        val pushedRemoteIds = mutableListOf<String>()
        val supplierTotal = supplierDao.count()
        val candidates = supplierDao.getCatalogPushCandidates()
        progressReporter.onProgress(
            CatalogSyncProgressState.running(CatalogSyncStage.PUSH_SUPPLIERS, current = 0, total = candidates.size)
        )
        for ((index, candidate) in candidates.withIndex()) {
            val s = candidate.supplier
            val ref = candidate.remoteRef ?: ensureSupplierRefForPush(s.id)
            if (supplierNeedsPush(ref)) {
                dirty++
                if (pushCatalogSupplierRow(remote, ownerUserId, s, ref, recoveryCache)) {
                    n++
                    pushedRemoteIds += supplierRemoteRefDao.getBySupplierId(s.id)?.remoteId ?: ref.remoteId
                }
            } else {
                skippedAlreadySynced++
            }
            progressReporter.onProgress(
                CatalogSyncProgressState.running(
                    CatalogSyncStage.PUSH_SUPPLIERS,
                    current = index + 1,
                    total = candidates.size
                )
            )
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=CATALOG phase=PUSH_SUPPLIERS suppliersTotal=$supplierTotal " +
                "suppliersEvaluated=${candidates.size} suppliersDirty=$dirty suppliersPushed=$n " +
                "suppliersSkippedAlreadySynced=${(supplierTotal - candidates.size + skippedAlreadySynced).coerceAtLeast(0)}"
        )
        return CatalogEntityPushResult(count = n, remoteIds = pushedRemoteIds.distinct())
    }

    private suspend fun pushCatalogCategories(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter
    ): CatalogEntityPushResult {
        var n = 0
        var dirty = 0
        var skippedAlreadySynced = 0
        val pushedRemoteIds = mutableListOf<String>()
        val categoryTotal = categoryDao.count()
        val candidates = categoryDao.getCatalogPushCandidates()
        progressReporter.onProgress(
            CatalogSyncProgressState.running(CatalogSyncStage.PUSH_CATEGORIES, current = 0, total = candidates.size)
        )
        for ((index, candidate) in candidates.withIndex()) {
            val c = candidate.category
            val ref = candidate.remoteRef ?: ensureCategoryRefForPush(c.id)
            if (categoryNeedsPush(ref)) {
                dirty++
                if (pushCatalogCategoryRow(remote, ownerUserId, c, ref, recoveryCache)) {
                    n++
                    pushedRemoteIds += categoryRemoteRefDao.getByCategoryId(c.id)?.remoteId ?: ref.remoteId
                }
            } else {
                skippedAlreadySynced++
            }
            progressReporter.onProgress(
                CatalogSyncProgressState.running(
                    CatalogSyncStage.PUSH_CATEGORIES,
                    current = index + 1,
                    total = candidates.size
                )
            )
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=CATALOG phase=PUSH_CATEGORIES categoriesTotal=$categoryTotal " +
                "categoriesEvaluated=${candidates.size} categoriesDirty=$dirty categoriesPushed=$n " +
                "categoriesSkippedAlreadySynced=${(categoryTotal - candidates.size + skippedAlreadySynced).coerceAtLeast(0)}"
        )
        return CatalogEntityPushResult(count = n, remoteIds = pushedRemoteIds.distinct())
    }

    private suspend fun pushCatalogProducts(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter,
        allowCreatingDependencyRefs: Boolean = true
    ): CatalogEntityPushResult {
        var n = 0
        var dirty = 0
        var skippedMissingDependencyRef = 0
        var skippedAlreadySynced = 0
        val pushedRemoteIds = mutableListOf<String>()
        val productTotal = productDao.count()
        val candidates = productDao.getCatalogPushCandidates()
        progressReporter.onProgress(
            CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS, current = 0, total = candidates.size)
        )
        for ((index, candidate) in candidates.withIndex()) {
            val p = candidate.product
            val productForPush = p.copy(
                purchasePrice = candidate.lastPurchase ?: p.purchasePrice,
                retailPrice = candidate.lastRetail ?: p.retailPrice
            )
            val ref = candidate.remoteRef ?: ensureProductRefForPush(p.id)
            if (productNeedsPush(ref)) {
                dirty++
                val pushed = pushCatalogProductRow(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    product = productForPush,
                    ref = ref,
                    recoveryCache = recoveryCache,
                    allowCreatingDependencyRefs = allowCreatingDependencyRefs
                )
                if (pushed) {
                    n++
                    pushedRemoteIds += productRemoteRefDao.getByProductId(p.id)?.remoteId ?: ref.remoteId
                } else if (!allowCreatingDependencyRefs) {
                    skippedMissingDependencyRef++
                }
            } else {
                skippedAlreadySynced++
            }
            progressReporter.onProgress(
                CatalogSyncProgressState.running(
                    CatalogSyncStage.PUSH_PRODUCTS,
                    current = index + 1,
                    total = candidates.size
                )
            )
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=CATALOG phase=PUSH_PRODUCTS productsTotal=$productTotal " +
                "productsEvaluated=${candidates.size} productsDirty=$dirty productsPushed=$n " +
                "productsSkippedAlreadySynced=${(productTotal - candidates.size + skippedAlreadySynced).coerceAtLeast(0)} " +
                "productsSkippedMissingDependencyRef=$skippedMissingDependencyRef"
        )
        return CatalogEntityPushResult(count = n, remoteIds = pushedRemoteIds.distinct())
    }

    private fun buildSupplierPushRow(
        supplier: Supplier,
        ref: SupplierRemoteRef,
        ownerUserId: String
    ): InventorySupplierRow =
        InventorySupplierRow(
            id = ref.remoteId,
            ownerUserId = ownerUserId,
            name = supplier.name,
            deletedAt = null
        )

    private fun buildCategoryPushRow(
        category: Category,
        ref: CategoryRemoteRef,
        ownerUserId: String
    ): InventoryCategoryRow =
        InventoryCategoryRow(
            id = ref.remoteId,
            ownerUserId = ownerUserId,
            name = category.name,
            deletedAt = null
        )

    private suspend fun buildProductPushRow(
        product: Product,
        ref: ProductRemoteRef,
        ownerUserId: String,
        allowCreatingDependencyRefs: Boolean = true
    ): InventoryProductRow? {
        val supplierRemoteId = product.supplierId?.let { supplierId ->
            if (allowCreatingDependencyRefs) {
                ensureSupplierRefForPush(supplierId).remoteId
            } else {
                supplierRemoteRefDao.getBySupplierId(supplierId)?.remoteId ?: return null
            }
        }
        val categoryRemoteId = product.categoryId?.let { categoryId ->
            if (allowCreatingDependencyRefs) {
                ensureCategoryRefForPush(categoryId).remoteId
            } else {
                categoryRemoteRefDao.getByCategoryId(categoryId)?.remoteId ?: return null
            }
        }
        return InventoryProductRow(
            id = ref.remoteId,
            ownerUserId = ownerUserId,
            barcode = product.barcode,
            itemNumber = product.itemNumber,
            productName = product.productName,
            secondProductName = product.secondProductName,
            purchasePrice = product.purchasePrice,
            retailPrice = product.retailPrice,
            supplierId = supplierRemoteId,
            categoryId = categoryRemoteId,
            stockQuantity = product.stockQuantity,
            deletedAt = null
        )
    }

    private suspend fun pushCatalogSupplierRow(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        supplier: Supplier,
        ref: SupplierRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache
    ): Boolean {
        val row = buildSupplierPushRow(supplier, ref, ownerUserId)
        val first = remote.upsertSuppliers(listOf(row))
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            markSupplierPushApplied(supplier.id, ref, row)
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileSupplierBridgeAfterUniqueConflict(remote, ownerUserId, supplier, ref, recoveryCache)
        if (!recovered) throw firstError
        val correctedRef = supplierRemoteRefDao.getBySupplierId(supplier.id) ?: throw firstError
        if (!supplierNeedsPush(correctedRef)) return false

        val retryRow = buildSupplierPushRow(supplier, correctedRef, ownerUserId)
        remote.upsertSuppliers(listOf(retryRow)).getOrThrow()
        markSupplierPushApplied(supplier.id, correctedRef, retryRow)
        return true
    }

    private suspend fun pushCatalogCategoryRow(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        category: Category,
        ref: CategoryRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache
    ): Boolean {
        val row = buildCategoryPushRow(category, ref, ownerUserId)
        val first = remote.upsertCategories(listOf(row))
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            markCategoryPushApplied(category.id, ref, row)
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileCategoryBridgeAfterUniqueConflict(remote, ownerUserId, category, ref, recoveryCache)
        if (!recovered) throw firstError
        val correctedRef = categoryRemoteRefDao.getByCategoryId(category.id) ?: throw firstError
        if (!categoryNeedsPush(correctedRef)) return false

        val retryRow = buildCategoryPushRow(category, correctedRef, ownerUserId)
        remote.upsertCategories(listOf(retryRow)).getOrThrow()
        markCategoryPushApplied(category.id, correctedRef, retryRow)
        return true
    }

    private suspend fun pushCatalogProductRow(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        product: Product,
        ref: ProductRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache,
        allowCreatingDependencyRefs: Boolean = true
    ): Boolean {
        val row = buildProductPushRow(product, ref, ownerUserId, allowCreatingDependencyRefs)
            ?: return false
        val first = remote.upsertProducts(listOf(row))
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            markProductPushApplied(product.id, ref, row)
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileProductBridgeAfterUniqueConflict(remote, ownerUserId, product, ref, recoveryCache)
        if (!recovered) throw firstError
        val correctedRef = productRemoteRefDao.getByProductId(product.id) ?: throw firstError
        if (!productNeedsPush(correctedRef)) return false

        val retryRow = buildProductPushRow(product, correctedRef, ownerUserId, allowCreatingDependencyRefs)
            ?: return false
        remote.upsertProducts(listOf(retryRow)).getOrThrow()
        markProductPushApplied(product.id, correctedRef, retryRow)
        return true
    }

    private suspend fun markSupplierPushApplied(
        supplierId: Long,
        ref: SupplierRemoteRef,
        row: InventorySupplierRow
    ) {
        supplierRemoteRefDao.updateRemoteApplyState(
            supplierId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintSupplierInbound(row)
        )
    }

    private suspend fun markCategoryPushApplied(
        categoryId: Long,
        ref: CategoryRemoteRef,
        row: InventoryCategoryRow
    ) {
        categoryRemoteRefDao.updateRemoteApplyState(
            categoryId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintCategoryInbound(row)
        )
    }

    private suspend fun markProductPushApplied(
        productId: Long,
        ref: ProductRemoteRef,
        row: InventoryProductRow
    ) {
        productRemoteRefDao.updateRemoteApplyState(
            productId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintProductInbound(row)
        )
    }

    private suspend fun reconcileSupplierBridgeAfterUniqueConflict(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        supplier: Supplier,
        failedRef: SupplierRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache
    ): Boolean {
        val key = normalizeCatalogNameKey(supplier.name)
        if (key.isEmpty()) return false
        val bundle = fetchCatalogForConflictRecovery(remote, "supplier", supplier.id, recoveryCache) ?: return false
        val remoteRow = bundle.suppliers.firstOrNull {
            it.deletedAt.isNullOrBlank() &&
                it.ownerUserId == ownerUserId &&
                normalizeCatalogNameKey(it.name) == key
        } ?: return false
        val recovered = db.withTransaction {
            attachSupplierBridgeForRetry(supplier.id, remoteRow.id)
        }
        logCatalogBridgeRecovery("supplier", recovered, supplier.id, failedRef.remoteId, remoteRow.id)
        return recovered
    }

    private suspend fun reconcileCategoryBridgeAfterUniqueConflict(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        category: Category,
        failedRef: CategoryRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache
    ): Boolean {
        val key = normalizeCatalogNameKey(category.name)
        if (key.isEmpty()) return false
        val bundle = fetchCatalogForConflictRecovery(remote, "category", category.id, recoveryCache) ?: return false
        val remoteRow = bundle.categories.firstOrNull {
            it.deletedAt.isNullOrBlank() &&
                it.ownerUserId == ownerUserId &&
                normalizeCatalogNameKey(it.name) == key
        } ?: return false
        val recovered = db.withTransaction {
            attachCategoryBridgeForRetry(category.id, remoteRow.id)
        }
        logCatalogBridgeRecovery("category", recovered, category.id, failedRef.remoteId, remoteRow.id)
        return recovered
    }

    private suspend fun reconcileProductBridgeAfterUniqueConflict(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        product: Product,
        failedRef: ProductRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache
    ): Boolean {
        val key = normalizeCatalogBarcodeKey(product.barcode)
        if (key.isEmpty()) return false
        val bundle = fetchCatalogForConflictRecovery(remote, "product", product.id, recoveryCache) ?: return false
        val remoteRow = bundle.products.firstOrNull {
            it.deletedAt.isNullOrBlank() &&
                it.ownerUserId == ownerUserId &&
                normalizeCatalogBarcodeKey(it.barcode) == key
        } ?: return false
        val recovered = db.withTransaction {
            attachProductBridgeForRetry(product.id, remoteRow.id)
        }
        logCatalogBridgeRecovery("product", recovered, product.id, failedRef.remoteId, remoteRow.id)
        return recovered
    }

    private suspend fun fetchCatalogForConflictRecovery(
        remote: CatalogRemoteDataSource,
        kind: String,
        localId: Long,
        recoveryCache: CatalogConflictRecoveryCache
    ): InventoryCatalogFetchBundle? {
        return recoveryCache.fetch(
            remote = remote,
            phase = "catalog_bridge_conflict_recover_fetch_$kind",
            kind = kind,
            localId = localId,
            onFailure = ::logSyncTransportFailure
        )
    }

    private suspend fun attachSupplierBridgeForRetry(supplierId: Long, remoteId: String): Boolean {
        val existingRemote = supplierRemoteRefDao.getByRemoteId(remoteId)
        if (existingRemote != null && existingRemote.supplierId != supplierId) return false
        val existingLocal = supplierRemoteRefDao.getBySupplierId(supplierId)
        if (existingLocal == null) {
            supplierRemoteRefDao.insert(SupplierRemoteRef(supplierId = supplierId, remoteId = remoteId))
            return supplierRemoteRefDao.getBySupplierId(supplierId)?.remoteId == remoteId
        }
        if (existingLocal.remoteId == remoteId) return true
        return supplierRemoteRefDao.updateRemoteId(supplierId, remoteId) > 0
    }

    private suspend fun attachCategoryBridgeForRetry(categoryId: Long, remoteId: String): Boolean {
        val existingRemote = categoryRemoteRefDao.getByRemoteId(remoteId)
        if (existingRemote != null && existingRemote.categoryId != categoryId) return false
        val existingLocal = categoryRemoteRefDao.getByCategoryId(categoryId)
        if (existingLocal == null) {
            categoryRemoteRefDao.insert(CategoryRemoteRef(categoryId = categoryId, remoteId = remoteId))
            return categoryRemoteRefDao.getByCategoryId(categoryId)?.remoteId == remoteId
        }
        if (existingLocal.remoteId == remoteId) return true
        return categoryRemoteRefDao.updateRemoteId(categoryId, remoteId) > 0
    }

    private suspend fun attachProductBridgeForRetry(productId: Long, remoteId: String): Boolean {
        val existingRemote = productRemoteRefDao.getByRemoteId(remoteId)
        if (existingRemote != null && existingRemote.productId != productId) return false
        val existingLocal = productRemoteRefDao.getByProductId(productId)
        if (existingLocal == null) {
            productRemoteRefDao.insert(ProductRemoteRef(productId = productId, remoteId = remoteId))
            return productRemoteRefDao.getByProductId(productId)?.remoteId == remoteId
        }
        if (existingLocal.remoteId == remoteId) return true
        return productRemoteRefDao.updateRemoteId(productId, remoteId) > 0
    }

    private fun logCatalogBridgeRecovery(
        kind: String,
        recovered: Boolean,
        localId: Long,
        oldRemoteId: String,
        recoveredRemoteId: String
    ) {
        Log.i(
            TAG,
            "bridge_recover kind=$kind outcome=${if (recovered) "linked" else "skipped"} " +
                "localId=$localId oldRemoteId=$oldRemoteId recoveredRemoteId=$recoveredRemoteId"
        )
    }

    /**
     * Allinea i bridge locali (`supplier_remote_refs`, `category_remote_refs`,
     * `product_remote_refs`) a righe gia presenti nel catalogo remoto quando manca
     * il bridge o quando il bridge locale esiste ma punta a un remoteId stale.
     *
     * Serve a evitare 23505 / HTTP 409 in push: senza bridge locale,
     * `ensureXxxRefForPush` genera un UUID fresco; se il remoto ha gia una riga
     * attiva con stesso `name`/`barcode` per lo stesso owner, l'INSERT viola
     * l'UNIQUE parziale `WHERE deleted_at IS NULL` e il push abortisce prima
     * ancora di pullare le modifiche remote (es. prezzi cambiati su un altro device).
     *
     * Comportamento: nessuna modifica ai valori Room. I bridge mancanti vengono
     * creati come gia sincronizzati col payload remoto corrente. I bridge stale
     * vengono solo riallineati al remoteId corretto, conservando revisioni e
     * fingerprint: se la riga locale e ancora dirty, il push successivo aggiorna
     * il remoto corretto senza passare da 23505.
     *
     * Sicurezza: se il remoteId e gia agganciato a un'altra riga locale, la riga
     * viene saltata. Non si spostano bridge tra entita locali diverse.
     *
     * Best-effort: un fallimento di fetch non propaga — il flow normale
     * fara il suo fetch comunque. Cosi la realign non introduce una nuova
     * fonte di abort per il sync.
     */
    private suspend fun realignCatalogBridgesIfNeeded(
        remote: CatalogRemoteDataSource,
        recoveryCache: CatalogConflictRecoveryCache
    ) {
        val suppliersMissing = supplierRemoteRefDao.countLocalRowsMissingRemoteRef()
        val categoriesMissing = categoryRemoteRefDao.countLocalRowsMissingRemoteRef()
        val productsMissing = productRemoteRefDao.countLocalRowsMissingRemoteRef()
        val suppliersNeverApplied = supplierRemoteRefDao.hasNeverAppliedRemoteRef()
        val categoriesNeverApplied = categoryRemoteRefDao.hasNeverAppliedRemoteRef()
        val productsNeverApplied = productRemoteRefDao.hasNeverAppliedRemoteRef()
        if (suppliersMissing == 0 && categoriesMissing == 0 && productsMissing == 0 &&
            !suppliersNeverApplied && !categoriesNeverApplied && !productsNeverApplied
        ) {
            return
        }

        val bundle = recoveryCache.fetch(
            remote = remote,
            phase = "catalog_bridge_realign_fetch",
            kind = "realign",
            localId = 0L,
            onFailure = ::logSyncTransportFailure
        ) ?: return

        val supplierStats = CatalogBridgeRealignStats()
        val categoryStats = CatalogBridgeRealignStats()
        val productStats = CatalogBridgeRealignStats()
        db.withTransaction {
            if (suppliersMissing > 0 || suppliersNeverApplied) {
                for (row in bundle.suppliers.filter { it.deletedAt.isNullOrBlank() }) {
                    supplierStats.remoteRowsSeen++
                    // Task 041 (hardening): normalizzazione Kotlin unicode-aware su entrambi
                    // i lati. Senza trim lato locale righe importate da Excel con spazi
                    // accidentali sfuggono al match ma collidono sulla partial UNIQUE
                    // `(owner_user_id, lower(name)) WHERE deleted_at IS NULL` -> 23505/409.
                    val normalized = normalizeCatalogNameKey(row.name)
                    if (normalized.isEmpty()) {
                        supplierStats.skippedEmptyKey++
                        continue
                    }
                    supplierStats.candidatesWithValidKey++
                    if (supplierRemoteRefDao.getByRemoteId(row.id) != null) {
                        supplierStats.skippedRemoteAlreadyBridged++
                        continue
                    }
                    val local = supplierDao.findByNormalizedName(normalized)
                    if (local == null) {
                        supplierStats.skippedNoLocalMatch++
                        continue
                    }
                    supplierStats.localMatches++
                    val remoteBridge = supplierRemoteRefDao.getByRemoteId(row.id)
                    if (remoteBridge != null && remoteBridge.supplierId != local.id) {
                        supplierStats.skippedRemoteAlreadyBridged++
                        continue
                    }
                    val localBridge = supplierRemoteRefDao.getBySupplierId(local.id)
                    if (localBridge?.remoteId == row.id) {
                        supplierStats.skippedLocalAlreadyBridged++
                        continue
                    }
                    if (localBridge != null) {
                        if (supplierRemoteRefDao.updateRemoteId(local.id, row.id) > 0) {
                            supplierStats.relinkedStale++
                        } else {
                            supplierStats.skippedLocalAlreadyBridged++
                        }
                    } else {
                        supplierRemoteRefDao.insert(
                            SupplierRemoteRef(
                                supplierId = local.id,
                                remoteId = row.id,
                                localChangeRevision = 0,
                                lastSyncedLocalRevision = 0,
                                lastRemoteAppliedAt = System.currentTimeMillis(),
                                lastRemotePayloadFingerprint = fingerprintSupplierInbound(row)
                            )
                        )
                        supplierStats.linked++
                    }
                }
            }
            if (categoriesMissing > 0 || categoriesNeverApplied) {
                for (row in bundle.categories.filter { it.deletedAt.isNullOrBlank() }) {
                    categoryStats.remoteRowsSeen++
                    // Task 041 (hardening): normalizzazione Kotlin unicode-aware su entrambi
                    // i lati (case + whitespace). Prima `findByName` era case-sensitive
                    // ed exact, quindi anche una sola categoria con case differente tra
                    // due device (stesso Excel) lasciava il bridge vuoto ma il push
                    // collideva sulla partial UNIQUE `(owner_user_id, lower(name))`.
                    val normalized = normalizeCatalogNameKey(row.name)
                    if (normalized.isEmpty()) {
                        categoryStats.skippedEmptyKey++
                        continue
                    }
                    categoryStats.candidatesWithValidKey++
                    if (categoryRemoteRefDao.getByRemoteId(row.id) != null) {
                        categoryStats.skippedRemoteAlreadyBridged++
                        continue
                    }
                    val local = categoryDao.findByNormalizedName(normalized)
                    if (local == null) {
                        categoryStats.skippedNoLocalMatch++
                        continue
                    }
                    categoryStats.localMatches++
                    val remoteBridge = categoryRemoteRefDao.getByRemoteId(row.id)
                    if (remoteBridge != null && remoteBridge.categoryId != local.id) {
                        categoryStats.skippedRemoteAlreadyBridged++
                        continue
                    }
                    val localBridge = categoryRemoteRefDao.getByCategoryId(local.id)
                    if (localBridge?.remoteId == row.id) {
                        categoryStats.skippedLocalAlreadyBridged++
                        continue
                    }
                    if (localBridge != null) {
                        if (categoryRemoteRefDao.updateRemoteId(local.id, row.id) > 0) {
                            categoryStats.relinkedStale++
                        } else {
                            categoryStats.skippedLocalAlreadyBridged++
                        }
                    } else {
                        categoryRemoteRefDao.insert(
                            CategoryRemoteRef(
                                categoryId = local.id,
                                remoteId = row.id,
                                localChangeRevision = 0,
                                lastSyncedLocalRevision = 0,
                                lastRemoteAppliedAt = System.currentTimeMillis(),
                                lastRemotePayloadFingerprint = fingerprintCategoryInbound(row)
                            )
                        )
                        categoryStats.linked++
                    }
                }
            }
            if (productsMissing > 0 || productsNeverApplied) {
                for (row in bundle.products.filter { it.deletedAt.isNullOrBlank() }) {
                    productStats.remoteRowsSeen++
                    // Task 041 (hardening): barcode normalizzato lato locale via `TRIM()`,
                    // per agganciare righe con whitespace accidentale (es. Excel) che
                    // altrimenti collidono sulla partial UNIQUE remota `(owner, barcode)
                    // WHERE deleted_at IS NULL` -> 23505 senza link possibile dal realign.
                    val bc = normalizeCatalogBarcodeKey(row.barcode)
                    if (bc.isEmpty()) {
                        productStats.skippedEmptyKey++
                        continue
                    }
                    productStats.candidatesWithValidKey++
                    if (productRemoteRefDao.getByRemoteId(row.id) != null) {
                        productStats.skippedRemoteAlreadyBridged++
                        continue
                    }
                    val local = productDao.findByTrimmedBarcode(bc)
                    if (local == null) {
                        productStats.skippedNoLocalMatch++
                        continue
                    }
                    productStats.localMatches++
                    val remoteBridge = productRemoteRefDao.getByRemoteId(row.id)
                    if (remoteBridge != null && remoteBridge.productId != local.id) {
                        productStats.skippedRemoteAlreadyBridged++
                        continue
                    }
                    val localBridge = productRemoteRefDao.getByProductId(local.id)
                    if (localBridge?.remoteId == row.id) {
                        productStats.skippedLocalAlreadyBridged++
                        continue
                    }
                    if (localBridge != null) {
                        if (productRemoteRefDao.updateRemoteId(local.id, row.id) > 0) {
                            productStats.relinkedStale++
                        } else {
                            productStats.skippedLocalAlreadyBridged++
                        }
                    } else {
                        productRemoteRefDao.insert(
                            ProductRemoteRef(
                                productId = local.id,
                                remoteId = row.id,
                                localChangeRevision = 0,
                                lastSyncedLocalRevision = 0,
                                lastRemoteAppliedAt = System.currentTimeMillis(),
                                lastRemotePayloadFingerprint = fingerprintProductInbound(row)
                            )
                        )
                        productStats.linked++
                    }
                }
            }
        }

        Log.i(
            TAG,
            "bridge_realign suppliers_linked=${supplierStats.linked} " +
                "categories_linked=${categoryStats.linked} products_linked=${productStats.linked} " +
                "suppliers_missing_before=$suppliersMissing categories_missing_before=$categoriesMissing " +
                "products_missing_before=$productsMissing suppliers_never_applied_before=$suppliersNeverApplied " +
                "categories_never_applied_before=$categoriesNeverApplied " +
                "products_never_applied_before=$productsNeverApplied " +
                "${supplierStats.logFields("suppliers")} " +
                "${categoryStats.logFields("categories")} " +
                productStats.logFields("products")
        )
    }

    private suspend fun drainPendingCatalogTombstones(
        remote: CatalogRemoteDataSource,
        ownerUserId: String
    ): SyncEventEntityIds {
        val pending = pendingCatalogTombstoneDao.listPendingOrdered()
        val suppliers = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val products = mutableListOf<String>()
        for (row in pending) {
            val deletedAt = java.time.Instant.now().toString()
            val patch = CatalogTombstonePatch(
                id = row.remoteId,
                ownerUserId = ownerUserId,
                deletedAt = deletedAt,
                updatedAt = deletedAt
            )
            val outcome = when (row.entityType) {
                PendingCatalogTombstoneEntityTypes.SUPPLIER -> remote.markSupplierTombstoned(patch)
                PendingCatalogTombstoneEntityTypes.CATEGORY -> remote.markCategoryTombstoned(patch)
                PendingCatalogTombstoneEntityTypes.PRODUCT -> remote.markProductTombstoned(patch)
                else -> Result.success(Unit)
            }
            outcome.onFailure {
                pendingCatalogTombstoneDao.incrementAttempt(row.id)
                logSyncTransportFailure("catalog_tombstone_drain_${row.entityType}", it)
                throw it
            }
            pendingCatalogTombstoneDao.deleteById(row.id)
            when (row.entityType) {
                PendingCatalogTombstoneEntityTypes.SUPPLIER -> suppliers += row.remoteId
                PendingCatalogTombstoneEntityTypes.CATEGORY -> categories += row.remoteId
                PendingCatalogTombstoneEntityTypes.PRODUCT -> products += row.remoteId
            }
        }
        return SyncEventEntityIds(
            supplierIds = suppliers.distinct(),
            categoryIds = categories.distinct(),
            productIds = products.distinct()
        )
    }

    private suspend fun applyInboundSupplierTombstone(row: InventorySupplierRow): Boolean {
        if (row.deletedAt.isNullOrBlank()) return false
        val ref = supplierRemoteRefDao.getByRemoteId(row.id) ?: return false
        if (ref.localChangeRevision > ref.lastSyncedLocalRevision) return false
        return try {
            deleteCatalogEntity(CatalogEntityKind.SUPPLIER, ref.supplierId, enqueueCloudTombstone = false)
            true
        } catch (_: CatalogNotFoundException) {
            false
        }
    }

    private suspend fun applyInboundCategoryTombstone(row: InventoryCategoryRow): Boolean {
        if (row.deletedAt.isNullOrBlank()) return false
        val ref = categoryRemoteRefDao.getByRemoteId(row.id) ?: return false
        if (ref.localChangeRevision > ref.lastSyncedLocalRevision) return false
        return try {
            deleteCatalogEntity(CatalogEntityKind.CATEGORY, ref.categoryId, enqueueCloudTombstone = false)
            true
        } catch (_: CatalogNotFoundException) {
            false
        }
    }

    private suspend fun applyInboundProductTombstone(row: InventoryProductRow): Boolean {
        if (row.deletedAt.isNullOrBlank()) return false
        val ref = productRemoteRefDao.getByRemoteId(row.id) ?: return false
        if (ref.localChangeRevision > ref.lastSyncedLocalRevision) return false
        val p = productDao.getById(ref.productId) ?: return false
        productDao.delete(p)
        return true
    }

    private suspend fun applyRemoteSupplierInbound(row: InventorySupplierRow): Boolean {
        if (!row.deletedAt.isNullOrBlank()) return false
        val fp = fingerprintSupplierInbound(row)
        val existingRef = supplierRemoteRefDao.getByRemoteId(row.id)
        if (existingRef != null) {
            if (existingRef.localChangeRevision > existingRef.lastSyncedLocalRevision) {
                return false
            }
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
        if (!row.deletedAt.isNullOrBlank()) return false
        val fp = fingerprintCategoryInbound(row)
        val existingRef = categoryRemoteRefDao.getByRemoteId(row.id)
        if (existingRef != null) {
            if (existingRef.localChangeRevision > existingRef.lastSyncedLocalRevision) {
                return false
            }
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
        if (!row.deletedAt.isNullOrBlank()) return false
        val fp = fingerprintProductInbound(row)
        val existingRef = productRemoteRefDao.getByRemoteId(row.id)
        if (existingRef != null) {
            if (existingRef.localChangeRevision > existingRef.lastSyncedLocalRevision) {
                return false
            }
            if (existingRef.lastRemotePayloadFingerprint == fp &&
                existingRef.localChangeRevision == existingRef.lastSyncedLocalRevision
            ) {
                return false
            }
            val supLocal = row.supplierId?.let { supplierRemoteRefDao.getByRemoteId(it)?.supplierId }
            val catLocal = row.categoryId?.let { categoryRemoteRefDao.getByRemoteId(it)?.categoryId }
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
        val supLocal = row.supplierId?.let { supplierRemoteRefDao.getByRemoteId(it)?.supplierId }
        val catLocal = row.categoryId?.let { categoryRemoteRefDao.getByRemoteId(it)?.categoryId }
        val bc = row.barcode.trim()
        val localByBarcode = productDao.findByBarcode(bc)
        val targetId: Long
        if (localByBarcode != null) {
            val other = productRemoteRefDao.getByProductId(localByBarcode.id)
            if (other != null && other.remoteId != row.id) return false
            if (other != null && other.localChangeRevision > other.lastSyncedLocalRevision) return false
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

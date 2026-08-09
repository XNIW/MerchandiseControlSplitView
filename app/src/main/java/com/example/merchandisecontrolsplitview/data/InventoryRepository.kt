package com.example.merchandisecontrolsplitview.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.BuildConfig
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.util.parseUserNumericInput
import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.CatalogTextPolicy
import com.example.merchandisecontrolsplitview.util.CatalogTextValidationException
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

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
    val attempted: Int = uploaded,
    val remoteIds: List<String> = emptyList()
)

data class LocalDatabaseStatusSnapshot(
    val products: Int,
    val suppliers: Int,
    val categories: Int,
    val priceHistoryRows: Int,
    val historySessions: Int,
    val pendingLocalChanges: Int,
    val syncEventOutboxPending: Int
)

interface InventoryRepository {
    // Product methods
    fun getProductsWithDetailsPaged(filter: String?): PagingSource<Int, ProductWithDetails>
    suspend fun findProductByBarcode(barcode: String): Product?
    suspend fun findProductsByBarcodes(barcodes: List<String>): List<Product>
    suspend fun getAllProducts(): List<Product>
    suspend fun getProductDetailsById(productId: Long): ProductWithDetails?
    /** True solo dopo che il bridge prodotto e' stato applicato almeno una volta al remoto. */
    suspend fun hasSyncedProductRemoteRef(productId: Long): Boolean = false
    val remoteAppliedProductIds: Flow<Set<Long>>
        get() = emptyFlow()
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
    suspend fun updateCurrentPriceFromHistory(
        productId: Long,
        type: String,
        price: Double,
        at: String,
        source: String?
    ): Product?
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

    /** Snapshot compatto per Options: solo conteggi locali, senza rete e senza bloccare la UI. */
    suspend fun getLocalDatabaseStatusSnapshot(
        ownerUserId: String?,
        selectedShop: SelectedShop? = null
    ): LocalDatabaseStatusSnapshot

    /**
     * Svuota il cache business locale quando cambia lo scope dati runtime
     * (account/shop). Non tocca stato auth, device id, watermark o outbox gia'
     * scoping-aware; serve a evitare che prodotti/storico globali vengano
     * riutilizzati sotto un altro shop.
     */
    suspend fun resetBusinessDataForShopContextChange() = Unit

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

    suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        syncCatalogWithRemote(remote, priceRemote, ownerUserId)

    // --- Backup sessioni cloud (task 023/040): Room-first, payload v1 reader + v2 writer ---

    /**
     * Upload History verso cloud.
     *
     * - [candidateUids] non-null: push preciso delle sole entry dirty/pending indicate.
     * - [candidateUids] null: full reconciliation user-visible, usata dopo bootstrap/manual sync
     *   per riparare local-only/clean-stale già marcate synced ma assenti da Supabase.
     */
    suspend fun pushHistorySessionsToRemote(
        remote: SessionBackupRemoteDataSource,
        ownerUserId: String,
        candidateUids: Set<Long>? = null
    ): Result<HistorySessionBackupPushSummary>

    suspend fun pushHistorySessionsToRemote(
        remote: SessionBackupRemoteDataSource,
        ownerUserId: String,
        candidateUids: Set<Long>? = null,
        selectedShop: SelectedShop?
    ): Result<HistorySessionBackupPushSummary> =
        pushHistorySessionsToRemote(remote, ownerUserId, candidateUids)

    /** Fetch owner-scoped paginato + [applyRemoteSessionPayloadBatch] (bootstrap / restore). */
    suspend fun bootstrapHistorySessionsFromRemote(
        remote: SessionBackupRemoteDataSource
    ): Result<RemoteSessionBatchResult>

    suspend fun bootstrapHistorySessionsFromRemote(
        remote: SessionBackupRemoteDataSource,
        selectedShop: SelectedShop?
    ): Result<RemoteSessionBatchResult> =
        bootstrapHistorySessionsFromRemote(remote)
}

internal object DefaultInventoryRepositoryTestHooks {
    @Volatile
    var afterProductsPersisted: (suspend () -> Unit)? = null

    @Volatile
    var beforeLocalProductInsert: (suspend () -> Unit)? = null

    @Volatile
    var afterLocalProductWrite: (suspend () -> Unit)? = null

    @Volatile
    var localProductMutationNow: (() -> LocalDateTime)? = null
}

internal data class ShopSyncRecoveryStageApplyResult(
    val businessRowsApplied: Int,
    val skippedParentRows: Int = 0,
    val failedRows: Int = 0,
    val unsupportedRows: Int = 0
)

class DefaultInventoryRepository(
    private val db: AppDatabase,
    private val businessDataScopeRuntimeGuard: Task126BusinessDataScopeRuntimeGuard =
        Task126UnmanagedBusinessDataScopeRuntimeGuard,
    private val shopSyncReadRemoteDataSource: ShopSyncReadRemoteDataSource? = null,
) :
    InventoryRepository,
    CatalogSyncProgressRepository,
    CatalogAutoSyncRepository,
    Task126BusinessDataScopeRepository {

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
        val remoteProductRows: Int,
        val remoteActiveSuppliers: Int,
        val remoteActiveCategories: Int,
        val remoteActiveProducts: Int,
        val prunedSuppliers: Int = 0,
        val prunedCategories: Int = 0,
        val prunedProducts: Int = 0,
        val completeSnapshot: Boolean = true,
        val appliedProductIds: Set<Long> = emptySet(),
        val targetedMissingRemote: Boolean = false
    )

    private data class TargetedCatalogBundle(
        val bundle: InventoryCatalogFetchBundle,
        val missingRemote: Boolean
    )

    private data class PricePullApplyResult(
        val pulled: Int,
        val skippedNoLocalProduct: Int,
        val remoteRowsEvaluated: Int,
        val appliedProductIds: Set<Long> = emptySet()
    )

    private data class ProductPriceBusinessKey(
        val productId: Long,
        val type: String,
        val effectiveAt: String
    )

    private data class ProductPriceRemoteCandidate(
        val row: InventoryProductPriceRow,
        val localProductId: Long
    )

    private data class CatalogEntityPushResult(
        val count: Int,
        val remoteIds: List<String>
    )

    private data class ProductPushCandidatePrepared(
        val product: Product,
        val ref: ProductRemoteRef,
        val row: InventoryProductRow
    )

    private class ProductPushBatchAccumulator(
        var pushed: Int = 0,
        var completed: Int = 0,
        var batchCount: Int = 0,
        var totalBatchMs: Long = 0L,
        var splitFallbackCount: Int = 0,
        var singleFallbackCount: Int = 0
    ) {
        val remoteIds = mutableListOf<String>()
    }

    private data class ProductPricePushResult(
        val count: Int,
        val remoteIds: List<String>,
        val skippedForeignKey: Int = 0
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
        val targetedHistoryFetched: Int,
        val remoteUpdatesApplied: Int,
        val remoteHistoryUpdatesApplied: Int,
        val tooLarge: Boolean,
        val gapDetected: Boolean,
        val manualFullSyncRequired: Boolean,
        val skippedProtectedLocalCommit: Int = 0,
        val remoteAppliedProductIds: Set<Long> = emptySet()
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

    private data class RetryOutboxResult(
        val pendingBefore: Int,
        val pendingAfter: Int,
        val retryLoaded: Int,
        val retryEligible: Int,
        val retrySkippedMaxAttempts: Int,
        val retrySucceeded: Int,
        val retryFailed: Int,
        val retryDeletedOnSuccess: Int
    ) {
        val outboxRetried: Int get() = retrySucceeded
    }

    private sealed class SyncEventRecordOutcome {
        abstract val attemptedChunks: Int
        abstract val recordedChunks: Int
        abstract val enqueuedChunks: Int
        abstract val outboxInserted: Int

        val recordedFully: Boolean get() = this is Recorded

        val logName: String
            get() = when (this) {
                NoOp -> "no_op"
                is Recorded -> "recorded"
                is Enqueued -> "enqueued"
                is PartiallyRecordedAndEnqueued -> "partially_recorded_and_enqueued"
            }

        object NoOp : SyncEventRecordOutcome() {
            override val attemptedChunks = 0
            override val recordedChunks = 0
            override val enqueuedChunks = 0
            override val outboxInserted = 0
        }

        data class Recorded(private val chunks: Int) : SyncEventRecordOutcome() {
            override val attemptedChunks = chunks
            override val recordedChunks = chunks
            override val enqueuedChunks = 0
            override val outboxInserted = 0
        }

        data class Enqueued(
            private val chunks: Int,
            override val outboxInserted: Int
        ) : SyncEventRecordOutcome() {
            override val attemptedChunks = chunks
            override val recordedChunks = 0
            override val enqueuedChunks = chunks
        }

        data class PartiallyRecordedAndEnqueued(
            override val recordedChunks: Int,
            override val enqueuedChunks: Int,
            override val outboxInserted: Int
        ) : SyncEventRecordOutcome() {
            override val attemptedChunks = recordedChunks + enqueuedChunks
        }

        companion object {
            fun from(
                attemptedChunks: Int,
                recordedChunks: Int,
                enqueuedChunks: Int,
                outboxInserted: Int
            ): SyncEventRecordOutcome =
                when {
                    attemptedChunks == 0 -> NoOp
                    enqueuedChunks == 0 -> Recorded(recordedChunks)
                    recordedChunks == 0 -> Enqueued(enqueuedChunks, outboxInserted)
                    else -> PartiallyRecordedAndEnqueued(recordedChunks, enqueuedChunks, outboxInserted)
                }
        }
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
    private val syncEventApplyStatusDao: SyncEventApplyStatusDao = db.syncEventApplyStatusDao()
    private val businessDataScopeBindingDao: BusinessDataScopeBindingDao = db.businessDataScopeBindingDao()
    private val syncRecoveryJournalDao: SyncRecoveryJournalDao = db.syncRecoveryJournalDao()
    private val syncRecoveryBaselineDao: SyncRecoveryBaselineDao = db.syncRecoveryBaselineDao()
    private val syncRecoveryManifestDao: SyncRecoveryManifestDao = db.syncRecoveryManifestDao()
    private val tSFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val applyImportMutex = Mutex()
    private val syncEventJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val _remoteAppliedProductIds = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 64)
    override val remoteAppliedProductIds: Flow<Set<Long>> = _remoteAppliedProductIds.asSharedFlow()

    private suspend fun requireCurrentBusinessDataScope() {
        businessDataScopeRuntimeGuard.requireCurrentBusinessDataScope()
    }

    /**
     * Registra anche i writer locali nella stessa lease usata dai flight cloud.
     * Una transition account/shop/recovery li cancella e li attende prima
     * dell'activation, impedendo a un job UI/import del vecchio scope di
     * scrivere nel database della nuova generazione dopo il commit atomico.
     */
    private suspend fun <T> withLocalBusinessMutation(
        block: suspend () -> T
    ): T = businessDataScopeRuntimeGuard.withCurrentBusinessDataScopeFlight {
        requireCurrentBusinessDataScope()
        val result = block()
        requireCurrentBusinessDataScope()
        result
    }

    private suspend fun <T> businessScopedRemoteCall(
        block: suspend () -> Result<T>
    ): Result<T> {
        requireCurrentBusinessDataScope()
        val result = block()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        requireCurrentBusinessDataScope()
        return result
    }

    private fun historyTombstoneTimestamp(): String =
        LocalDateTime.now().format(tSFMT)

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
    override suspend fun hasSyncedProductRemoteRef(productId: Long): Boolean =
        withContext(Dispatchers.IO) {
            productRemoteRefDao.getByProductId(productId)?.lastRemoteAppliedAt != null
        }

    override suspend fun addProduct(product: Product) = withLocalBusinessMutation {
        val canonicalProduct = CatalogTextCanonicalizer.product(product).product
        DefaultInventoryRepositoryTestHooks.beforeLocalProductInsert?.invoke()
        requireCurrentBusinessDataScope()
        val persistedId = withContext(Dispatchers.IO) {
            db.withTransaction {
                productDao.insert(canonicalProduct)
                DefaultInventoryRepositoryTestHooks.afterLocalProductWrite?.invoke()
                val persisted = productDao.findByBarcode(canonicalProduct.barcode)
                    ?: return@withTransaction null

                val requestedAt = (DefaultInventoryRepositoryTestHooks.localProductMutationNow
                    ?.invoke() ?: LocalDateTime.now()).format(tSFMT)

                canonicalProduct.purchasePrice?.let {
                    priceDao.insertIfChanged(
                        persisted.id,
                        "PURCHASE",
                        it,
                        uniquePriceEffectiveAtLocked(persisted.id, "PURCHASE", requestedAt),
                        "MANUAL",
                    )
                }
                canonicalProduct.retailPrice?.let {
                    priceDao.insertIfChanged(
                        persisted.id,
                        "RETAIL",
                        it,
                        uniquePriceEffectiveAtLocked(persisted.id, "RETAIL", requestedAt),
                        "MANUAL",
                    )
                }
                touchProductDirty(persisted.id)
                persisted.id
            }
        }
        persistedId?.let(::notifyProductCatalogChanged)
        Unit
    }
    override suspend fun updateProduct(product: Product) = withLocalBusinessMutation {
        val canonicalProduct = CatalogTextCanonicalizer.product(product).product
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val existing = productDao.getById(canonicalProduct.id)
                productDao.update(canonicalProduct)
                DefaultInventoryRepositoryTestHooks.afterLocalProductWrite?.invoke()

                val requestedAt = (DefaultInventoryRepositoryTestHooks.localProductMutationNow
                    ?.invoke() ?: LocalDateTime.now()).format(tSFMT)

                canonicalProduct.purchasePrice?.let {
                    priceDao.insertIfChanged(
                        canonicalProduct.id,
                        "PURCHASE",
                        it,
                        uniquePriceEffectiveAtLocked(canonicalProduct.id, "PURCHASE", requestedAt),
                        "MANUAL",
                    )
                }
                canonicalProduct.retailPrice?.let {
                    priceDao.insertIfChanged(
                        canonicalProduct.id,
                        "RETAIL",
                        it,
                        uniquePriceEffectiveAtLocked(canonicalProduct.id, "RETAIL", requestedAt),
                        "MANUAL",
                    )
                }
                val changedFields = existing
                    ?.let { productChangedFields(it, canonicalProduct) }
                    .orEmpty()
                if (changedFields.isNotEmpty()) {
                    touchProductDirty(canonicalProduct.id, changedFields)
                }
            }
        }
        notifyProductCatalogChanged(canonicalProduct.id)
    }

    override suspend fun updateCurrentPriceFromHistory(
        productId: Long,
        type: String,
        price: Double,
        at: String,
        source: String?
    ): Product? = withLocalBusinessMutation {
        val result = withContext(Dispatchers.IO) {
            db.withTransaction {
                val current = productDao.getById(productId) ?: return@withTransaction null
                val normalizedType = type.uppercase(Locale.ROOT)
                val currentPrice = when (normalizedType) {
                    "PURCHASE" -> current.purchasePrice
                    "RETAIL" -> current.retailPrice
                    else -> throw IllegalArgumentException("Unsupported price type: $type")
                }
                val priceChanged = currentPrice == null || abs(currentPrice - price) > 0.0005
                val updated = when (normalizedType) {
                    "PURCHASE" -> current.copy(purchasePrice = price)
                    "RETAIL" -> current.copy(retailPrice = price)
                    else -> current
                }
                val effectiveAt = uniquePriceEffectiveAtLocked(productId, normalizedType, at)
                val inserted = priceDao.insert(
                    ProductPrice(
                        productId = productId,
                        type = normalizedType,
                        price = price,
                        effectiveAt = effectiveAt,
                        source = source
                    )
                ) > 0L

                if (priceChanged) {
                    productDao.update(updated)
                    touchProductDirty(
                        productId,
                        setOf(
                            when (normalizedType) {
                                "PURCHASE" -> "purchaseprice"
                                "RETAIL" -> "retailprice"
                                else -> "__all__"
                            }
                        )
                    )
                } else if (inserted) {
                    ensureProductRefForPricePushIfMissing(productId)
                }

                if (priceChanged || inserted) updated else current
            }
        }
        if (result != null) {
            notifyProductCatalogChanged(productId)
        }
        result
    }
    override suspend fun getAllProductsWithDetails(): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getAllWithDetailsOnce() }

    override suspend fun getProductsWithDetailsPage(limit: Int, offset: Int): List<ProductWithDetails> =
        withContext(Dispatchers.IO) { productDao.getWithDetailsPage(limit, offset) }
    override suspend fun deleteProduct(product: Product) = withLocalBusinessMutation {
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
        notifyProductCatalogChanged(product.id)
        notifyCatalogChanged()
    }
    override suspend fun applyImport(request: ImportApplyRequest): ImportApplyResult =
        withLocalBusinessMutation {
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
    override suspend fun addSupplier(name: String): Supplier? = withLocalBusinessMutation {
        val (supplier, didCreate) = withContext(Dispatchers.IO) {
            val normalizedName = CatalogTextCanonicalizer.supplierName(name)
            val lookupKey = normalizedName.lowercase(Locale.ROOT)
            supplierMutex.withLock {
                supplierDao.findByNormalizedName(lookupKey)?.let { return@withLock it to false }
                val newSupplier = Supplier(name = normalizedName)
                val insertedId = supplierDao.insert(newSupplier)
                val created = if (insertedId > 0L) {
                    supplierDao.getById(insertedId)
                } else {
                    supplierDao.findByNormalizedName(lookupKey)
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
        supplier
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
    ): CatalogListItem = withLocalBusinessMutation {
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
        item
    }

    override suspend fun renameCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        newName: String
    ): CatalogListItem = withLocalBusinessMutation {
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
        item
    }

    override suspend fun deleteCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        strategy: CatalogDeleteStrategy
    ): CatalogDeleteResult = withLocalBusinessMutation {
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
        result
    }
    override suspend fun recordPriceIfChanged(
        productId: Long,
        type: String,
        price: Double,
        at: String,
        source: String?
    ) = withLocalBusinessMutation {
        val inserted = withContext(Dispatchers.IO) {
            priceDao.insertIfChanged(productId, type, price, at, source)
        }
        if (inserted) {
            notifyProductCatalogChanged(productId)
        }
    }

    private suspend fun uniquePriceEffectiveAtLocked(
        productId: Long,
        type: String,
        requestedAt: String
    ): String {
        var timestamp = runCatching {
            LocalDateTime.parse(requestedAt, tSFMT)
        }.getOrDefault(LocalDateTime.now())

        while (true) {
            val candidate = timestamp.format(tSFMT)
            if (priceDao.findByBusinessKey(productId, type, candidate) == null) {
                return candidate
            }
            timestamp = timestamp.plusSeconds(1)
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
    override suspend fun addCategory(name: String): Category? = withLocalBusinessMutation {
        val (category, didCreate) = withContext(Dispatchers.IO) {
            val normalizedName = CatalogTextCanonicalizer.categoryName(name)
            val lookupKey = normalizedName.lowercase(Locale.ROOT)
            categoryMutex.withLock {
                categoryDao.findByNormalizedName(lookupKey)?.let { return@withLock it to false }
                val newCategory = Category(name = normalizedName)
                val insertedId = categoryDao.insert(newCategory)
                val created = if (insertedId > 0L) {
                    categoryDao.getById(insertedId)
                } else {
                    categoryDao.findByNormalizedName(lookupKey)
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
        category
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
    override suspend fun insertHistoryEntry(entry: HistoryEntry) = withLocalBusinessMutation {
        withContext(Dispatchers.IO) {
            val uid = historyDao.insert(entry.withInitialDisplayName())
            if (uid > 0L) notifyHistorySessionPayloadChanged(uid)
            uid
        }
    }
    override suspend fun updateHistoryEntry(entry: HistoryEntry) = withLocalBusinessMutation {
        withContext(Dispatchers.IO) {
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

    private fun notifyRemoteProductCatalogApplied(productIds: Set<Long>) {
        val cleanIds = productIds.filter { it > 0L }.toSet()
        if (cleanIds.isEmpty()) return
        if (!_remoteAppliedProductIds.tryEmit(cleanIds)) {
            Log.w(TAG, "remote_applied_product_ids_drop count=${cleanIds.size}")
        }
    }

    override suspend fun deleteHistoryEntry(entry: HistoryEntry) = withLocalBusinessMutation {
        withContext(Dispatchers.IO) {
            var changedUid: Long? = null
            db.withTransaction {
                val existingRemoteId = remoteRefDao.getByHistoryEntryUid(entry.uid)?.remoteId
                val remoteId = existingRemoteId ?: run {
                    historyDao.getByUid(entry.uid) ?: return@withTransaction
                    val inserted = remoteRefDao.insert(
                        HistoryEntryRemoteRef(
                            historyEntryUid = entry.uid,
                            remoteId = java.util.UUID.randomUUID().toString()
                        )
                    )
                    if (inserted > 0L) {
                        remoteRefDao.getByHistoryEntryUid(entry.uid)?.remoteId
                    } else {
                        remoteRefDao.getByHistoryEntryUid(entry.uid)?.remoteId
                    }
                }
                if (remoteId == null) {
                    historyDao.delete(entry)
                    return@withTransaction
                }
                val tombstone = historyTombstoneTimestamp()
                historyDao.update(
                    entry.copy(
                        deletedAt = tombstone,
                        syncStatus = SyncStatus.NOT_ATTEMPTED
                    )
                )
                remoteRefDao.incrementLocalRevision(entry.uid)
                changedUid = entry.uid
            }
            val uidToNotify = changedUid
            if (uidToNotify != null) {
                notifyHistorySessionPayloadChanged(uidToNotify)
            }
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
    ) = withLocalBusinessMutation {
        withContext(Dispatchers.IO) {
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
        withLocalBusinessMutation {
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
                db.withTransaction {
                    requireCurrentBusinessDataScope()
                    applySingleRemotePayload(payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RemoteSessionApplyOutcome.Failed(e)
            }
        }

    private suspend fun applySingleRemotePayload(rawPayload: SessionRemotePayload): RemoteSessionApplyOutcome {
        val payload = rawPayload.copy(remoteId = canonicalSessionRemoteId(rawPayload.remoteId))
        val fp = payload.payloadFingerprint()
        val overlayState = buildOverlayStateForPayload(payload)
        val existingRef = remoteRefDao.getByRemoteId(payload.remoteId)
        if (existingRef != null) {
            if (payload.deletedAt != null) {
                val existingEntry = historyDao.getByUid(existingRef.historyEntryUid)
                    ?: return RemoteSessionApplyOutcome.Failed(
                        IllegalStateException("Bridge esiste ma HistoryEntry uid=${existingRef.historyEntryUid} mancante")
                    )
                if (existingEntry.deletedAt.isNullOrBlank() &&
                    existingRef.localChangeRevision > existingRef.lastSyncedLocalRevision
                ) {
                    return RemoteSessionApplyOutcome.Skipped
                }
                if (existingEntry.deletedAt != payload.deletedAt) {
                    historyDao.update(
                        existingEntry.copy(
                            deletedAt = payload.deletedAt,
                            syncStatus = SyncStatus.SYNCED_SUCCESSFULLY
                        )
                    )
                }
                remoteRefDao.updateRemoteApplyState(
                    uid = existingRef.historyEntryUid,
                    rev = existingRef.localChangeRevision,
                    appliedAt = System.currentTimeMillis(),
                    fingerprint = fp
                )
                return RemoteSessionApplyOutcome.Updated
            }
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
                // A legacy v1 receipt can change only fields deliberately not
                // materialized by Room (for example display_name). It still
                // advances the authoritative remote fingerprint: without this
                // bridge-only acknowledgement a fenced A→B recovery would
                // repeatedly fail physical verification despite an unchanged
                // local business row. This is safe because dirty local rows
                // returned above before reaching this branch.
                remoteRefDao.updateRemoteApplyState(
                    uid = existingRef.historyEntryUid,
                    rev = existingRef.localChangeRevision,
                    appliedAt = System.currentTimeMillis(),
                    fingerprint = fp
                )
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
                    missingItems = refreshedLocalState?.missingItems ?: existingEntry.missingItems,
                    syncStatus = SyncStatus.SYNCED_SUCCESSFULLY,
                    deletedAt = null
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
        if (payload.deletedAt != null) {
            return RemoteSessionApplyOutcome.Skipped
        }
        linkEquivalentLocalHistorySession(
            payload = payload,
            payloadFingerprint = fp,
            overlayState = overlayState
        )?.let { return it }

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
            missingItems = localState.missingItems,
            syncStatus = SyncStatus.SYNCED_SUCCESSFULLY,
            deletedAt = null
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

    private suspend fun linkEquivalentLocalHistorySession(
        payload: SessionRemotePayload,
        payloadFingerprint: String,
        overlayState: OverlayApplyState
    ): RemoteSessionApplyOutcome? {
        val candidates = historyDao.getAllUserVisibleSnapshot()
        for (entry in candidates) {
            val localRef = remoteRefDao.getByHistoryEntryUid(entry.uid)
            if (localRef?.remoteId.equals(payload.remoteId, ignoreCase = true)) {
                continue
            }
            if (localRef?.lastRemoteAppliedAt != null) {
                continue
            }
            val localPayload = entry.toRemotePayload(localRef?.remoteId ?: payload.remoteId)
            if (localPayload.payloadFingerprint() != payloadFingerprint) {
                continue
            }

            val localRevision = localRef?.localChangeRevision ?: 0
            val localState = when (overlayState) {
                is OverlayApplyState.Valid -> overlayState.localState
                OverlayApplyState.Invalid,
                OverlayApplyState.Missing -> buildRemoteSessionLocalState(payload.data)
            }
            historyDao.update(
                entry.copy(
                    displayName = displayNameFromPayload(payload, entry.displayName),
                    timestamp = payload.timestamp,
                    supplier = payload.supplier,
                    category = payload.category,
                    isManualEntry = payload.isManualEntry,
                    data = payload.data,
                    editable = localState.editable,
                    complete = localState.complete,
                    totalItems = localState.totalItems,
                    orderTotal = localState.orderTotal,
                    paymentTotal = localState.paymentTotal,
                    missingItems = localState.missingItems,
                    syncStatus = SyncStatus.SYNCED_SUCCESSFULLY,
                    deletedAt = null
                )
            )

            if (localRef == null) {
                val inserted = remoteRefDao.insert(
                    HistoryEntryRemoteRef(
                        historyEntryUid = entry.uid,
                        remoteId = payload.remoteId,
                        localChangeRevision = 0,
                        lastSyncedLocalRevision = 0,
                        lastRemoteAppliedAt = System.currentTimeMillis(),
                        lastRemotePayloadFingerprint = payloadFingerprint
                    )
                )
                if (inserted <= 0L) {
                    return RemoteSessionApplyOutcome.Failed(
                        IllegalStateException("insert bridge ignorato per relink remoteId=${payload.remoteId}")
                    )
                }
            } else if (!localRef.remoteId.equals(payload.remoteId, ignoreCase = true)) {
                val updated = remoteRefDao.updateRemoteId(entry.uid, payload.remoteId)
                if (updated <= 0) {
                    return RemoteSessionApplyOutcome.Failed(
                        IllegalStateException("update bridge remoteId fallito per uid=${entry.uid}")
                    )
                }
            }

            remoteRefDao.updateRemoteApplyState(
                uid = entry.uid,
                rev = localRevision,
                appliedAt = System.currentTimeMillis(),
                fingerprint = payloadFingerprint
            )
            return RemoteSessionApplyOutcome.Updated
        }
        return null
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
        validateImportIdentityCollisions(request)
        val existingSuppliers = supplierDao.getAll()
        val existingCategories = categoryDao.getAll()
        val supplierIdsByName = existingSuppliers
            .associate { normalizedRelationKey(it.name) to it.id }
            .toMutableMap()
        val categoryIdsByName = existingCategories
            .associate { normalizedRelationKey(it.name) to it.id }
            .toMutableMap()
        val supplierNamesById = existingSuppliers
            .associate { it.id to it.name }
            .toMutableMap()
        val categoryNamesById = existingCategories
            .associate { it.id to it.name }
            .toMutableMap()
        val createdSupplierIds = mutableSetOf<Long>()
        val createdCategoryIds = mutableSetOf<Long>()

        suspend fun resolveSupplierIdByName(name: String): Long? {
            val normalizedName = CatalogTextCanonicalizer.supplierName(name)
            val key = normalizedRelationKey(normalizedName)
            supplierIdsByName[key]?.let { return it }

            supplierDao.findByNormalizedName(key)?.let { existing ->
                supplierIdsByName[key] = existing.id
                supplierNamesById[existing.id] = existing.name
                return existing.id
            }

            val insertedId = supplierDao.insert(Supplier(name = normalizedName))
            val resolvedId = when {
                insertedId > 0L -> insertedId
                else -> supplierDao.findByNormalizedName(key)?.id
            } ?: return null

            if (insertedId > 0L) {
                createdSupplierIds += resolvedId
            }
            supplierIdsByName[key] = resolvedId
            supplierNamesById[resolvedId] = normalizedName
            return resolvedId
        }

        suspend fun resolveCategoryIdByName(name: String): Long? {
            val normalizedName = CatalogTextCanonicalizer.categoryName(name)
            val key = normalizedRelationKey(normalizedName)
            categoryIdsByName[key]?.let { return it }

            categoryDao.findByNormalizedName(key)?.let { existing ->
                categoryIdsByName[key] = existing.id
                categoryNamesById[existing.id] = existing.name
                return existing.id
            }

            val insertedId = categoryDao.insert(Category(name = normalizedName))
            val resolvedId = when {
                insertedId > 0L -> insertedId
                else -> categoryDao.findByNormalizedName(key)?.id
            } ?: return null
            if (insertedId > 0L) {
                createdCategoryIds += resolvedId
            }
            categoryIdsByName[key] = resolvedId
            categoryNamesById[resolvedId] = normalizedName
            return resolvedId
        }

        suspend fun supplierNameFor(id: Long?): String? = when {
            id == null -> null
            id < 0L -> request.pendingTempSuppliers[id]
            else -> supplierNamesById[id] ?: supplierDao.getById(id)?.name?.also { supplierNamesById[id] = it }
        }

        suspend fun categoryNameFor(id: Long?): String? = when {
            id == null -> null
            id < 0L -> request.pendingTempCategories[id]
            else -> categoryNamesById[id] ?: categoryDao.getById(id)?.name?.also { categoryNamesById[id] = it }
        }

        suspend fun resolveSupplierIdForProduct(product: Product, oldProduct: Product?): Long? {
            val requestedId = product.supplierId ?: return null
            val requestedName = supplierNameFor(requestedId)
            if (
                oldProduct?.supplierId != null &&
                semanticRelationNameEquals(supplierNameFor(oldProduct.supplierId), requestedName)
            ) {
                return oldProduct.supplierId
            }
            return when {
                requestedId >= 0L -> requestedId
                requestedName != null -> resolveSupplierIdByName(requestedName)
                else -> null
            }
        }

        suspend fun resolveCategoryIdForProduct(product: Product, oldProduct: Product?): Long? {
            val requestedId = product.categoryId ?: return null
            val requestedName = categoryNameFor(requestedId)
            if (
                oldProduct?.categoryId != null &&
                semanticRelationNameEquals(categoryNameFor(oldProduct.categoryId), requestedName)
            ) {
                return oldProduct.categoryId
            }
            return when {
                requestedId >= 0L -> requestedId
                requestedName != null -> resolveCategoryIdByName(requestedName)
                else -> null
            }
        }

        suspend fun resolveProduct(product: Product, oldProduct: Product? = null): Product {
            return product.copy(
                supplierId = resolveSupplierIdForProduct(product, oldProduct),
                categoryId = resolveCategoryIdForProduct(product, oldProduct)
            )
        }

        suspend fun buildRelationDirtySummary(updates: List<ProductUpdate>): ImportRelationDirtySummary {
            var realChanged = 0
            var semanticEquivalent = 0
            var unknown = 0

            suspend fun classify(oldId: Long?, newId: Long?, oldName: String?, newName: String?) {
                if (oldId == newId) return
                when {
                    oldName != null && newName != null && semanticRelationNameEquals(oldName, newName) ->
                        semanticEquivalent++
                    oldName != null || newName != null ->
                        realChanged++
                    else ->
                        unknown++
                }
            }

            for (update in updates) {
                classify(
                    oldId = update.oldProduct.supplierId,
                    newId = update.newProduct.supplierId,
                    oldName = supplierNameFor(update.oldProduct.supplierId),
                    newName = supplierNameFor(update.newProduct.supplierId)
                )
                classify(
                    oldId = update.oldProduct.categoryId,
                    newId = update.newProduct.categoryId,
                    oldName = categoryNameFor(update.oldProduct.categoryId),
                    newName = categoryNameFor(update.newProduct.categoryId)
                )
            }

            return ImportRelationDirtySummary(
                realChangedCount = realChanged,
                semanticEquivalentCount = semanticEquivalent,
                unknownCount = unknown
            )
        }

        request.pendingSupplierNames.forEach { resolveSupplierIdByName(it) }
        request.pendingCategoryNames.forEach { resolveCategoryIdByName(it) }

        val resolvedNewProducts = request.newProducts.map {
            CatalogTextCanonicalizer.product(resolveProduct(it)).product
        }
        val resolvedUpdatedProducts = request.updatedProducts.map { update ->
            val resolved = CatalogTextCanonicalizer.product(
                resolveProduct(update.newProduct, update.oldProduct).copy(id = update.oldProduct.id)
            ).product
            update.oldProduct to resolved
        }
        val relationDirtySummary = buildRelationDirtySummary(request.updatedProducts)
        val actuallyChangedUpdates = resolvedUpdatedProducts.filter { (oldProduct, resolvedProduct) ->
            !productsEquivalentForImportDirty(oldProduct, resolvedProduct)
        }
        val actualProductDirtyReasons = actuallyChangedUpdates.map { (oldProduct, resolvedProduct) ->
            importDirtyChangedFields(oldProduct, resolvedProduct)
        }
        val relationDirtySamples = formatImportRelationDirtySamples(
            changes = actuallyChangedUpdates,
            supplierNamesById = supplierNamesById,
            categoryNamesById = categoryNamesById
        )
        val resolvedUpdatedProductEntities = actuallyChangedUpdates.map { it.second }

        if (resolvedNewProducts.isNotEmpty()) {
            productDao.insertAll(resolvedNewProducts)
        }
        if (resolvedUpdatedProductEntities.isNotEmpty()) {
            productDao.updateAll(resolvedUpdatedProductEntities)
        }

        DefaultInventoryRepositoryTestHooks.afterProductsPersisted?.invoke()

        val now = LocalDateTime.now()
        val prevTs = now.minusSeconds(1).format(tSFMT)
        val nowTs = now.format(tSFMT)

        val allBarcodes = (
            resolvedNewProducts.map { it.barcode } +
                resolvedUpdatedProductEntities.map { it.barcode } +
                request.pendingPriceHistory.map { it.barcode }
            ).distinct()
        val persistedProducts = if (allBarcodes.isEmpty()) {
            emptyList()
        } else {
            productDao.findByBarcodes(allBarcodes)
        }
        val productIdsByBarcode = persistedProducts.associate { normalizedImportKey(it.barcode) to it.id }
        val importedPricesByProductAndType = request.pendingPriceHistory
            .mapNotNull { entry ->
                val productId = productIdsByBarcode[normalizedImportKey(entry.barcode)] ?: return@mapNotNull null
                (productId to entry.type) to entry.price
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second }
            )
        val priceChangedProductIds = linkedSetOf<Long>()
        var importedPriceRowsInserted = 0
        var syntheticPriceCandidates = 0
        var syntheticPriceSkippedAlreadyRepresented = 0
        var priceDirtyFromPriceFieldChange = 0
        var priceDirtySkippedBecauseNonPriceProductUpdate = 0
        val priceRowsPendingBridgeBefore = priceDao.countPriceRowsPendingPriceBridge()

        suspend fun recordImportedCurrentAndPreviousPrices(
            productId: Long,
            product: Product,
            priceChanges: ImportPriceChangeSet
        ): Int {
            val insertedBefore = importedPriceRowsInserted
            suspend fun record(type: String, price: Double?, timestamp: String, source: String) {
                price?.let {
                    syntheticPriceCandidates++
                    val alreadyRepresented = (
                        importedPricesByProductAndType[productId to type]
                            ?.any { representedPrice -> priceEquivalentForImportDirty(representedPrice, it) }
                            == true
                        )
                    if (alreadyRepresented) {
                        syntheticPriceSkippedAlreadyRepresented++
                        return
                    }
                    if (priceDao.insertIfChanged(productId, type, it, timestamp, source)) {
                        priceChangedProductIds += productId
                        importedPriceRowsInserted++
                    }
                }
            }
            if (priceChanges.purchase) {
                record("PURCHASE", product.oldPurchasePrice, prevTs, "IMPORT_PREV")
                record("PURCHASE", product.purchasePrice, nowTs, "IMPORT")
            }
            if (priceChanges.retail) {
                record("RETAIL", product.oldRetailPrice, prevTs, "IMPORT_PREV")
                record("RETAIL", product.retailPrice, nowTs, "IMPORT")
            }
            return importedPriceRowsInserted - insertedBefore
        }

        val shouldRecordSyntheticImportPrices = !request.priceHistoryRepresentsFullDatabase

        if (shouldRecordSyntheticImportPrices) {
            resolvedNewProducts.forEach { product ->
                productIdsByBarcode[normalizedImportKey(product.barcode)]?.let { productId ->
                    recordImportedCurrentAndPreviousPrices(
                        productId = productId,
                        product = product,
                        priceChanges = ImportPriceChangeSet(purchase = true, retail = true)
                    )
                }
            }
        }
        actuallyChangedUpdates.forEach { (oldProduct, product) ->
            val priceChanges = importPriceChangeSet(
                old = oldProduct,
                new = product,
                includePreviousPriceFields = request.pendingPriceHistory.isEmpty()
            )
            if (priceChanges.hasAny && shouldRecordSyntheticImportPrices) {
                priceDirtyFromPriceFieldChange += recordImportedCurrentAndPreviousPrices(
                    productId = product.id,
                    product = product,
                    priceChanges = priceChanges
                )
            } else {
                priceDirtySkippedBecauseNonPriceProductUpdate++
            }
        }

        val pendingPriceHistoryPoints = request.pendingPriceHistory.mapNotNull { entry ->
            val productId = productIdsByBarcode[normalizedImportKey(entry.barcode)] ?: return@mapNotNull null
            ProductPrice(
                productId = productId,
                type = entry.type,
                price = entry.price,
                effectiveAt = entry.timestamp,
                source = entry.source ?: "IMPORT_SHEET"
            )
        }
        val pendingPriceInsertIds = if (pendingPriceHistoryPoints.isNotEmpty()) {
            priceDao.insertAllReturningIds(pendingPriceHistoryPoints)
        } else {
            emptyList()
        }
        val pendingPriceRowsInserted = pendingPriceInsertIds.count { it > 0L }
        val pendingPriceRowsAlreadyPresent = (pendingPriceHistoryPoints.size - pendingPriceRowsInserted)
            .coerceAtLeast(0)
        pendingPriceHistoryPoints.zip(pendingPriceInsertIds).forEach { (point, insertedId) ->
            if (insertedId > 0L) {
                priceChangedProductIds += point.productId
            }
        }
        val priceRowsPendingBridgeAfter = priceDao.countPriceRowsPendingPriceBridge()

        val insertedProductIds = resolvedNewProducts.mapNotNull { productIdsByBarcode[it.barcode] }
            .filter { it > 0L }
            .toSet()
        val updatedProductIds = resolvedUpdatedProductEntities.map { it.id }
            .filter { it > 0L }
            .toSet()
        val catalogDirtyProductIds = (insertedProductIds + updatedProductIds).toSet()
        createdSupplierIds.forEach { touchSupplierDirty(it) }
        createdCategoryIds.forEach { touchCategoryDirty(it) }
        catalogDirtyProductIds.forEach { touchProductDirty(it) }
        val priceOnlyProductIds = priceChangedProductIds - catalogDirtyProductIds
        var productRefsCreatedForPriceOnly = 0
        priceOnlyProductIds.forEach { productId ->
            if (ensureProductRefForPricePushIfMissing(productId)) {
                productRefsCreatedForPriceOnly++
            }
        }
        val touchedProductIds = (catalogDirtyProductIds + priceChangedProductIds).toSet()
        val diagnostics = request.diagnostics
        val importDiagnosticsLog = diagnostics?.let {
            "fileProductCount=${it.fileProductCount} " +
                "fileSupplierCount=${it.fileSupplierCount} " +
                "fileCategoryCount=${it.fileCategoryCount} " +
                "filePriceHistoryCount=${it.filePriceHistoryCount} " +
                "dbProductCountBefore=${it.dbProductCountBefore} " +
                "dbSupplierCountBefore=${it.dbSupplierCountBefore} " +
                "dbCategoryCountBefore=${it.dbCategoryCountBefore} " +
                "dbPriceHistoryCountBefore=${it.dbPriceHistoryCountBefore} " +
                "importFingerprintShort=${it.importFingerprintShort} " +
                "dbSnapshotFingerprintShort=${it.dbSnapshotFingerprintShort} " +
                "classificazione_risultato=${it.resultClassification} "
        }.orEmpty()
        Log.d(
            TAG,
            "import_dirty_marking productsTouched=${touchedProductIds.size} " +
                importDiagnosticsLog +
                "insertedProducts=${resolvedNewProducts.size} " +
                "updatedProducts=${resolvedUpdatedProductEntities.size} " +
                "unchangedProductUpdates=${resolvedUpdatedProducts.size - resolvedUpdatedProductEntities.size} " +
                "dirtyMarkedProducts=${catalogDirtyProductIds.size} " +
                "productFieldChangedCount=${actualProductDirtyReasons.sumOf { it.size }} " +
                "productDirtyReasons=${formatImportDirtyReasonCounts(actualProductDirtyReasons)} " +
                "productDirtyReasonSample=${formatImportDirtyReasonSample(actualProductDirtyReasons)} " +
                "relationDirtySample=$relationDirtySamples " +
                "relationDirtyRealChangedCount=${relationDirtySummary.realChangedCount} " +
                "relationDirtySemanticEquivalentCount=${relationDirtySummary.semanticEquivalentCount} " +
                "relationDirtyUnknownCount=${relationDirtySummary.unknownCount} " +
                "priceHistoryRows=${request.pendingPriceHistory.size} " +
                "priceHistoryInserted=$pendingPriceRowsInserted " +
                "priceHistoryAlreadyPresent=$pendingPriceRowsAlreadyPresent " +
                "syntheticPriceCandidates=$syntheticPriceCandidates " +
                "syntheticPriceSkippedAlreadyRepresented=$syntheticPriceSkippedAlreadyRepresented " +
                "syntheticPriceBridgeCreated=0 syntheticPriceBridgeAlreadyExists=0 " +
                "dirtyMarkedPrices=${importedPriceRowsInserted + pendingPriceRowsInserted} " +
                "dirtyMarkedPriceProducts=${priceChangedProductIds.size} " +
                "priceRowsPendingBridgeBefore=$priceRowsPendingBridgeBefore " +
                "priceRowsPendingBridgeAfter=$priceRowsPendingBridgeAfter " +
                "priceDirtyReason=syntheticProductPrice:$importedPriceRowsInserted,pendingPriceInsert:$pendingPriceRowsInserted " +
                "priceDirtyFromPriceFieldChange=$priceDirtyFromPriceFieldChange " +
                "priceDirtyFromSyntheticFallback=$importedPriceRowsInserted " +
                "priceDirtySkippedBecauseNonPriceProductUpdate=$priceDirtySkippedBecauseNonPriceProductUpdate " +
                "priceOnlyProducts=${priceOnlyProductIds.size} " +
                "priceProductRefsCreated=$productRefsCreatedForPriceOnly " +
                "suppliersCreated=${createdSupplierIds.size} categoriesCreated=${createdCategoryIds.size}"
        )
        return touchedProductIds
    }

    private fun validateImportIdentityCollisions(request: ImportApplyRequest) {
        val incomingProducts = buildList {
            addAll(request.newProducts)
            request.updatedProducts.forEach { add(it.newProduct) }
        }
        validateStrictIdentityCollision(
            rawValues = incomingProducts.map { it.barcode } +
                request.pendingPriceHistory.map { it.barcode },
            field = CatalogTextField.BARCODE,
            required = true,
            maxLength = CatalogTextPolicy.Limits.BARCODE
        )
        validateStrictIdentityCollision(
            rawValues = incomingProducts.mapNotNull { it.itemNumber },
            field = CatalogTextField.ITEM_NUMBER,
            required = false,
            maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
        )
    }

    private fun validateStrictIdentityCollision(
        rawValues: List<String>,
        field: CatalogTextField,
        required: Boolean,
        maxLength: Int
    ) {
        val rejection = CatalogTextPolicy.validateDistinctStrictIdentities(
            rawValues = rawValues,
            required = required,
            maxLength = maxLength
        ) ?: return
        throw CatalogTextValidationException(
            CatalogTextPolicy.FieldRejection(
                field = field,
                reason = rejection.reason
            )
        )
    }

    private fun productsEquivalentForImportDirty(old: Product, new: Product): Boolean =
        normalizedImportText(old.barcode).equals(normalizedImportText(new.barcode), ignoreCase = true) &&
            normalizedImportText(old.itemNumber).equals(normalizedImportText(new.itemNumber), ignoreCase = true) &&
            normalizedImportText(old.productName).equals(normalizedImportText(new.productName), ignoreCase = true) &&
            normalizedImportText(old.secondProductName).equals(normalizedImportText(new.secondProductName), ignoreCase = true) &&
            priceEquivalentForImportDirty(old.purchasePrice, new.purchasePrice) &&
            priceEquivalentForImportDirty(old.retailPrice, new.retailPrice) &&
            priceEquivalentForImportDirty(old.stockQuantity, new.stockQuantity) &&
            old.supplierId == new.supplierId &&
            old.categoryId == new.categoryId

    private fun normalizedImportText(value: String?): String =
        value?.trim().orEmpty()

    private fun normalizedImportKey(value: String?): String =
        normalizedImportText(value).lowercase(Locale.ROOT)

    private fun semanticRelationNameEquals(old: String?, new: String?): Boolean =
        normalizedRelationKey(old) == normalizedRelationKey(new)

    private fun normalizedRelationKey(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        return COMBINING_MARKS.replace(decomposed, "").lowercase(Locale.ROOT)
    }

    private fun priceEquivalentForImportDirty(old: Double?, new: Double?): Boolean =
        abs((old ?: 0.0) - (new ?: 0.0)) <= IMPORT_DIRTY_PRICE_TOLERANCE

    private data class ImportPriceChangeSet(
        val purchase: Boolean,
        val retail: Boolean
    ) {
        val hasAny: Boolean
            get() = purchase || retail
    }

    private data class ImportRelationDirtySummary(
        val realChangedCount: Int,
        val semanticEquivalentCount: Int,
        val unknownCount: Int
    )

    private fun importPriceChangeSet(
        old: Product,
        new: Product,
        includePreviousPriceFields: Boolean
    ): ImportPriceChangeSet =
        ImportPriceChangeSet(
            purchase = !priceEquivalentForImportDirty(old.purchasePrice, new.purchasePrice) ||
                (
                    includePreviousPriceFields &&
                        !priceEquivalentForImportDirty(old.oldPurchasePrice, new.oldPurchasePrice)
                    ),
            retail = !priceEquivalentForImportDirty(old.retailPrice, new.retailPrice) ||
                (
                    includePreviousPriceFields &&
                        !priceEquivalentForImportDirty(old.oldRetailPrice, new.oldRetailPrice)
                    )
        )

    private fun importDirtyChangedFields(old: Product, new: Product): List<String> = buildList {
        if (!normalizedImportText(old.barcode).equals(normalizedImportText(new.barcode), ignoreCase = true)) add("barcode")
        if (!normalizedImportText(old.itemNumber).equals(normalizedImportText(new.itemNumber), ignoreCase = true)) add("itemNumber")
        if (!normalizedImportText(old.productName).equals(normalizedImportText(new.productName), ignoreCase = true)) add("productName")
        if (!normalizedImportText(old.secondProductName).equals(normalizedImportText(new.secondProductName), ignoreCase = true)) add("secondProductName")
        if (!priceEquivalentForImportDirty(old.purchasePrice, new.purchasePrice)) add("purchasePrice")
        if (!priceEquivalentForImportDirty(old.retailPrice, new.retailPrice)) add("retailPrice")
        if (!priceEquivalentForImportDirty(old.stockQuantity, new.stockQuantity)) add("stockQuantity")
        if (old.supplierId != new.supplierId) add("supplierId")
        if (old.categoryId != new.categoryId) add("categoryId")
    }

    private fun formatImportRelationDirtySamples(
        changes: List<Pair<Product, Product>>,
        supplierNamesById: Map<Long, String>,
        categoryNamesById: Map<Long, String>
    ): String {
        val samples = buildList {
            for ((old, new) in changes) {
                if (size >= LOG_SAMPLE_LIMIT) break
                if (old.supplierId != new.supplierId) {
                    add(
                        formatImportRelationDirtySample(
                            kind = "supplier",
                            barcode = old.barcode,
                            oldId = old.supplierId,
                            newId = new.supplierId,
                            oldName = old.supplierId?.let { supplierNamesById[it] },
                            newName = new.supplierId?.let { supplierNamesById[it] }
                        )
                    )
                }
                if (size >= LOG_SAMPLE_LIMIT) break
                if (old.categoryId != new.categoryId) {
                    add(
                        formatImportRelationDirtySample(
                            kind = "category",
                            barcode = old.barcode,
                            oldId = old.categoryId,
                            newId = new.categoryId,
                            oldName = old.categoryId?.let { categoryNamesById[it] },
                            newName = new.categoryId?.let { categoryNamesById[it] }
                        )
                    )
                }
            }
        }
        return if (samples.isEmpty()) "none" else samples.joinToString("|")
    }

    private fun formatImportRelationDirtySample(
        kind: String,
        barcode: String,
        oldId: Long?,
        newId: Long?,
        oldName: String?,
        newName: String?
    ): String =
        "$kind:${maskImportBarcode(barcode)}:${oldId ?: "null"}:${sanitizeLogToken(oldName)}->" +
            "${newId ?: "null"}:${sanitizeLogToken(newName)}"

    private fun maskImportBarcode(barcode: String): String {
        val normalized = normalizedImportText(barcode)
        if (normalized.isEmpty()) return "empty"
        val suffix = normalized.takeLast(4)
        return "***$suffix"
    }

    private fun sanitizeLogToken(value: String?): String =
        normalizedImportText(value)
            .replace(Regex("\\s+"), "_")
            .replace("|", "_")
            .replace(":", "_")
            .take(40)
            .ifBlank { "blank" }

    private fun formatImportDirtyReasonCounts(reasons: List<List<String>>): String {
        val counts = reasons.flatten()
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(LOG_SAMPLE_LIMIT)
        return if (counts.isEmpty()) {
            "none"
        } else {
            counts.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    private fun formatImportDirtyReasonSample(reasons: List<List<String>>): String {
        val sample = reasons
            .map { fields -> fields.ifEmpty { listOf("unknown") }.joinToString("+") }
            .take(LOG_SAMPLE_LIMIT)
        return if (sample.isEmpty()) "none" else sample.joinToString("|")
    }

    private suspend fun normalizedNameFor(
        kind: CatalogEntityKind,
        rawName: String,
        currentId: Long? = null
    ): String {
        val normalizedName = when (kind) {
            CatalogEntityKind.SUPPLIER -> CatalogTextCanonicalizer.supplierName(rawName)
            CatalogEntityKind.CATEGORY -> CatalogTextCanonicalizer.categoryName(rawName)
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
        val changedField = when (kind) {
            CatalogEntityKind.SUPPLIER -> "supplier"
            CatalogEntityKind.CATEGORY -> "category"
        }
        touchedIds.forEach { touchProductDirty(it, setOf(changedField)) }
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
        val changedField = when (kind) {
            CatalogEntityKind.SUPPLIER -> "supplier"
            CatalogEntityKind.CATEGORY -> "category"
        }
        touchedIds.forEach { touchProductDirty(it, setOf(changedField)) }
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

    override suspend fun shouldRunCatalogBootstrap(ownerUserId: String): Boolean = withContext(Dispatchers.IO) {
        productDao.count() == 0
    }

    override suspend fun getLocalDatabaseStatusSnapshot(
        ownerUserId: String?,
        selectedShop: SelectedShop?
    ): LocalDatabaseStatusSnapshot =
        withContext(Dispatchers.IO) {
            readLocalDatabaseStatusSnapshot(ownerUserId, selectedShop)
        }

    private suspend fun readLocalDatabaseStatusSnapshot(
        ownerUserId: String?,
        selectedShop: SelectedShop?
    ): LocalDatabaseStatusSnapshot {
        val breakdown = CatalogCloudPendingBreakdown(
            pendingCatalogTombstones = pendingCatalogTombstoneDao.count(),
            productPricesPendingPriceBridge = priceDao.countPriceRowsPendingPriceBridge(),
            productPricesBlockedWithoutProductRemote = priceDao.countPriceRowsWithoutProductRemote(),
            suppliersMissingRemoteRef = supplierRemoteRefDao.countLocalRowsMissingRemoteRef(),
            categoriesMissingRemoteRef = categoryRemoteRefDao.countLocalRowsMissingRemoteRef(),
            productsMissingRemoteRef = productRemoteRefDao.countLocalRowsMissingRemoteRef()
        )
        val pendingHistorySessions = historyDao.getUserVisibleSessionPushCandidateUids().size
        val outboxPending = ownerUserId
            ?.let { syncEventOutboxDao.countPendingForScope(it, shopScopedStoreScope(selectedShop)) }
            ?: syncEventOutboxDao.countAll()
        val pendingTotal =
            breakdown.pendingCatalogTombstones +
                breakdown.productPricesPendingPriceBridge +
                breakdown.productPricesBlockedWithoutProductRemote +
                breakdown.suppliersMissingRemoteRef +
                breakdown.categoriesMissingRemoteRef +
                breakdown.productsMissingRemoteRef +
                pendingHistorySessions +
                outboxPending

        return LocalDatabaseStatusSnapshot(
            products = productDao.count(),
            suppliers = supplierDao.count(),
            categories = categoryDao.count(),
            priceHistoryRows = priceDao.countAll(),
            historySessions = historyDao.countUserVisible(),
            pendingLocalChanges = pendingTotal,
            syncEventOutboxPending = outboxPending
        )
    }

    override suspend fun resolveBusinessDataScope(
        activeScope: Task126OwnerStoreScope,
        legacyBoundScope: Task126OwnerStoreScope?
    ): Task126BusinessDataScopeState = withContext(Dispatchers.IO) {
        db.withTransaction {
            val storedBinding = businessDataScopeBindingDao.get()
            val boundScope = storedBinding?.toOwnerStoreScope() ?: legacyBoundScope
            if (storedBinding == null && legacyBoundScope != null) {
                businessDataScopeBindingDao.upsert(
                    BusinessDataScopeBinding.from(legacyBoundScope, System.currentTimeMillis())
                )
            }
            val snapshot = readLocalDatabaseStatusSnapshot(ownerUserId = null, selectedShop = null)
            val pendingTargetRecovery = syncRecoveryJournalDao.getForScope(
                ownerHash = activeScope.ownerHash,
                storeScope = activeScope.storeId
            )
            if (pendingTargetRecovery != null) {
                return@withTransaction Task126BusinessDataScopeState(
                    status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    boundScope = boundScope,
                    localSnapshot = snapshot,
                    errorCode = "sync_recovery_required"
                )
            }
            when (val decision = Task126OwnerStoreGate.resolveBinding(boundScope, activeScope, snapshot)) {
                Task126BusinessDataBindingDecision.AllowExisting ->
                    readyStateAfterVerifiedBinding(boundScope ?: activeScope, "binding_missing_after_import")
                Task126BusinessDataBindingDecision.BindEmpty -> {
                    businessDataScopeBindingDao.upsert(
                        BusinessDataScopeBinding.from(activeScope, System.currentTimeMillis())
                    )
                    readyStateAfterVerifiedBinding(activeScope, "binding_empty_commit_failed")
                }
                is Task126BusinessDataBindingDecision.ReviewRequiredUnbound ->
                    Task126BusinessDataScopeState(
                        status = Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND,
                        localSnapshot = decision.localSnapshot
                    )
                is Task126BusinessDataBindingDecision.Blocked ->
                    blockedBusinessDataScopeState(decision.reason, boundScope, snapshot)
            }
        }
    }

    override suspend fun discardUnboundBusinessDataAndBind(
        activeScope: Task126OwnerStoreScope
    ): Task126BusinessDataScopeState {
        var discarded = false
        val state = withContext(Dispatchers.IO) {
            db.withTransaction {
                val existing = businessDataScopeBindingDao.get()?.toOwnerStoreScope()
                if (existing != null) {
                    val snapshot = readLocalDatabaseStatusSnapshot(ownerUserId = null, selectedShop = null)
                    return@withTransaction when (val gate = Task126OwnerStoreGate.validate(existing, activeScope)) {
                        Task126OwnerStoreGateDecision.Allowed ->
                            readyStateAfterVerifiedBinding(existing, "binding_discard_commit_failed")
                        is Task126OwnerStoreGateDecision.Blocked ->
                            blockedBusinessDataScopeState(gate.reason, existing, snapshot)
                    }
                }

                val before = readLocalDatabaseStatusSnapshot(ownerUserId = null, selectedShop = null)
                if (!before.isCompletelyEmptyForBinding) {
                    deleteUnboundBusinessDataInsideTransaction()
                    discarded = true
                }
                businessDataScopeBindingDao.upsert(
                    BusinessDataScopeBinding.from(activeScope, System.currentTimeMillis())
                )
                readyStateAfterVerifiedBinding(activeScope, "binding_discard_commit_failed")
            }
        }
        if (discarded && state.status == Task126BusinessDataScopeStatus.READY) {
            onCatalogChanged?.invoke()
        }
        return state
    }

    override suspend fun replaceMismatchedBusinessDataAndBind(
        activeScope: Task126OwnerStoreScope
    ): Task126BusinessDataScopeState = withContext(Dispatchers.IO) {
            db.withTransaction {
                val existing = businessDataScopeBindingDao.get()?.toOwnerStoreScope()
                val snapshot = readLocalDatabaseStatusSnapshot(ownerUserId = null, selectedShop = null)
                if (existing == null) {
                    return@withTransaction Task126BusinessDataScopeState(
                        status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                        localSnapshot = snapshot,
                        errorCode = "binding_replace_requires_mismatch"
                    )
                }
                when (val gate = Task126OwnerStoreGate.validate(existing, activeScope)) {
                    Task126OwnerStoreGateDecision.Allowed ->
                        readyStateAfterVerifiedBinding(existing, "binding_replace_commit_failed")
                    is Task126OwnerStoreGateDecision.Blocked -> when (gate.reason) {
                        Task126OwnerStoreGateDecision.Reason.SchemaMismatch ->
                            blockedBusinessDataScopeState(gate.reason, existing, snapshot)
                        Task126OwnerStoreGateDecision.Reason.OwnerMismatch,
                        Task126OwnerStoreGateDecision.Reason.StoreMismatch,
                        Task126OwnerStoreGateDecision.Reason.LocalStoreMismatch -> {
                            val nowMs = System.currentTimeMillis()
                            val prior = syncRecoveryJournalDao.get()
                                ?.takeIf {
                                    it.ownerHash == activeScope.ownerHash &&
                                        it.storeScope == activeScope.storeId
                                }
                            val deviceId = checkNotNull(syncEventDeviceStateDao.get()?.deviceId) {
                                "binding_replace_device_identity_missing"
                            }
                            syncRecoveryJournalDao.upsert(
                                SyncRecoveryJournal(
                                    ownerHash = activeScope.ownerHash,
                                    storeScope = activeScope.storeId,
                                    shopId = shopIdFromStoreScope(activeScope.storeId),
                                    deviceId = deviceId,
                                    authorizationMode =
                                        SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
                                    runId = null,
                                    phase = SyncRecoveryJournalPhases.REQUIRED,
                                    reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
                                    blockingEventId = prior?.blockingEventId,
                                    attemptCount = nextSyncRecoveryAttemptCount(
                                        prior?.attemptCount ?: 0
                                    ),
                                    createdAtMs = prior?.createdAtMs ?: nowMs,
                                    updatedAtMs = nowMs,
                                    nextRetryAtMs = nowMs,
                                    checkpointADigest = prior?.checkpointADigest,
                                    checkpointBDigest = prior?.checkpointBDigest,
                                    stagingDatabaseName = prior?.stagingDatabaseName
                                )
                            )
                            Task126BusinessDataScopeState(
                                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                                boundScope = existing,
                                localSnapshot = snapshot,
                                errorCode = "sync_recovery_required"
                            )
                        }
                    }
                }
            }
        }

    private suspend fun readyStateAfterVerifiedBinding(
        expectedScope: Task126OwnerStoreScope,
        errorCode: String
    ): Task126BusinessDataScopeState {
        val persisted = businessDataScopeBindingDao.get()?.toOwnerStoreScope()
        return if (
            persisted != null &&
            Task126OwnerStoreGate.validate(persisted, expectedScope) == Task126OwnerStoreGateDecision.Allowed
        ) {
            val pendingRecovery = syncRecoveryJournalDao.getForScope(
                ownerHash = persisted.ownerHash,
                storeScope = persisted.storeId
            )
            if (pendingRecovery == null) {
                Task126BusinessDataScopeState.ready(persisted)
            } else {
                Task126BusinessDataScopeState(
                    status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    boundScope = persisted,
                    errorCode = "sync_recovery_required"
                )
            }
        } else {
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                errorCode = errorCode
            )
        }
    }

    private fun blockedBusinessDataScopeState(
        reason: Task126OwnerStoreGateDecision.Reason,
        boundScope: Task126OwnerStoreScope?,
        snapshot: LocalDatabaseStatusSnapshot
    ): Task126BusinessDataScopeState =
        Task126BusinessDataScopeState(
            status = when (reason) {
                Task126OwnerStoreGateDecision.Reason.OwnerMismatch ->
                    Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH
                Task126OwnerStoreGateDecision.Reason.StoreMismatch,
                Task126OwnerStoreGateDecision.Reason.LocalStoreMismatch ->
                    Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH
                Task126OwnerStoreGateDecision.Reason.SchemaMismatch ->
                    Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH
            },
            boundScope = boundScope,
            localSnapshot = snapshot
        )

    override suspend fun resetBusinessDataForShopContextChange() {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                deleteBusinessDataInsideTransaction()
            }
        }
        onCatalogChanged?.invoke()
    }

    private suspend fun deleteBusinessDataInsideTransaction() {
        syncRecoveryManifestDao.deleteAll()
        syncRecoveryBaselineDao.deleteAll()
        pendingCatalogTombstoneDao.deleteAll()
        productPriceRemoteRefDao.deleteAll()
        productRemoteRefDao.deleteAll()
        supplierRemoteRefDao.deleteAll()
        categoryRemoteRefDao.deleteAll()
        remoteRefDao.deleteAll()
        priceDao.deleteAll()
        historyDao.deleteAll()
        productDao.deleteAll()
        supplierDao.deleteAll()
        categoryDao.deleteAll()
    }

    private suspend fun deleteUnboundBusinessDataInsideTransaction() {
        deleteBusinessDataInsideTransaction()
        syncEventOutboxDao.deleteAll()
        syncEventWatermarkDao.deleteAll()
        syncEventApplyStatusDao.deleteAll()
        syncRecoveryJournalDao.deleteAll()
    }

    override suspend fun pushHistorySessionsToRemote(
        remote: SessionBackupRemoteDataSource,
        ownerUserId: String,
        candidateUids: Set<Long>?
    ): Result<HistorySessionBackupPushSummary> =
        pushHistorySessionsToRemote(remote, ownerUserId, candidateUids, selectedShop = null)

    override suspend fun pushHistorySessionsToRemote(
        remote: SessionBackupRemoteDataSource,
        ownerUserId: String,
        candidateUids: Set<Long>?,
        selectedShop: SelectedShop?
    ): Result<HistorySessionBackupPushSummary> = withContext(Dispatchers.IO) {
        if (!remote.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Session backup remote non configurato"))
        }
        try {
            val candidates = mutableListOf<HistorySessionPushCandidate>()
            val fullReconciliation = candidateUids == null
            val entries = if (fullReconciliation) {
                historyDao.getHistorySessionPushSnapshot()
            } else {
                val candidateUidList = candidateUids
                    .filter { it > 0L }
                    .distinct()
                if (candidateUidList.isEmpty()) {
                    emptyList()
                } else {
                    historyDao.getHistorySessionPushSnapshotByUids(candidateUidList)
                }
            }
            var skippedAlreadySynced = 0
            for (entry in entries) {
                val remoteId = getOrCreateRemoteId(entry.uid) ?: continue
                val ref = remoteRefDao.getByHistoryEntryUid(entry.uid) ?: continue
                if (!fullReconciliation && !historySessionNeedsPush(ref)) {
                    skippedAlreadySynced++
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
            val uploadedRemoteIds = mutableListOf<String>()
            for (chunk in candidates.chunked(SESSION_BACKUP_PUSH_CHUNK)) {
                val rows = chunk.map {
                    it.payload.toSharedSheetSessionUpsertRow(ownerUserId, selectedShop?.shopId)
                }
                businessScopedRemoteCall {
                    remote.upsertSessions(rows, selectedShop?.shopId)
                }.getOrElse { error ->
                    logHistorySessionPushFailure(chunk, error)
                    return@withContext Result.failure(error)
                }
                db.withTransaction {
                    requireCurrentBusinessDataScope()
                    for (c in chunk) {
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
                        val latestRef = remoteRefDao.getByHistoryEntryUid(c.entry.uid) ?: continue
                        val latestEntry = historyDao.getByUid(c.entry.uid) ?: continue
                        if (latestRef.localChangeRevision == c.ref.localChangeRevision) {
                            historyDao.update(latestEntry.copy(syncStatus = SyncStatus.SYNCED_SUCCESSFULLY))
                        }
                    }
                }
                uploaded += chunk.size
                uploadedRemoteIds += chunk.map { it.payload.remoteId }
            }
            Result.success(
                HistorySessionBackupPushSummary(
                    uploaded = uploaded,
                    skippedAlreadySynced = skippedAlreadySynced,
                    attempted = candidates.size,
                    remoteIds = uploadedRemoteIds.distinct()
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
    ): Result<RemoteSessionBatchResult> =
        bootstrapHistorySessionsFromRemote(remote, selectedShop = null)

    override suspend fun bootstrapHistorySessionsFromRemote(
        remote: SessionBackupRemoteDataSource,
        selectedShop: SelectedShop?
    ): Result<RemoteSessionBatchResult> = withContext(Dispatchers.IO) {
        if (!remote.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Session backup remote non configurato"))
        }
        try {
            val records = businessScopedRemoteCall {
                remote.fetchAllSessionsForOwner(selectedShop?.shopId)
            }
                .getOrElse { return@withContext Result.failure(it) }
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
        syncCatalogWithRemote(remote, priceRemote, ownerUserId, selectedShop = null)

    override suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        syncCatalogWithRemote(
            remote = remote,
            priceRemote = priceRemote,
            ownerUserId = ownerUserId,
            progressReporter = CatalogSyncProgressReporter { },
            selectedShop = selectedShop
        )

    override suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter
    ): Result<CatalogSyncSummary> =
        syncCatalogWithRemote(remote, priceRemote, ownerUserId, progressReporter, selectedShop = null)

    override suspend fun syncCatalogWithRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val canonicalOwnerUserId = CatalogTextCanonicalizer.remoteId(ownerUserId)
        val shopId = CatalogTextCanonicalizer.optionalRemoteId(selectedShop?.shopId)
        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val recoveryCache = CatalogConflictRecoveryCache()
            val deferredPrices = measureCatalogSyncPhase(CatalogSyncStage.REALIGN, phaseDurationsMs) {
                progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.REALIGN))
                drainPendingCatalogTombstones(remote, canonicalOwnerUserId, shopId)
                // Snapshot iniziale (prima di ensure/push catalogo): righe prezzo senza bridge prodotto.
                val deferred = priceDao.countPriceRowsWithoutProductRemote()
                // Bridge realign pre-push: se il locale ha righe catalogo senza `*_remote_refs`
                // ma il remoto contiene gia una riga attiva con stesso name/barcode, allineiamo
                // il bridge locale al remoteId esistente — altrimenti `ensureXxxRefForPush`
                // genererebbe UUID nuovi che violano gli UNIQUE parziali `(owner_user_id, lower(name))`
                // / `(owner_user_id, barcode)` WHERE deleted_at IS NULL → 23505 / HTTP 409.
                realignCatalogBridgesIfNeeded(remote, recoveryCache, shopId)
                deferred
            }
            val pushedSuppliers = measureCatalogSyncPhase(CatalogSyncStage.PUSH_SUPPLIERS, phaseDurationsMs) {
                pushCatalogSuppliers(
                    remote,
                    canonicalOwnerUserId,
                    recoveryCache,
                    progressReporter,
                    shopId
                )
            }
            val pushedCategories = measureCatalogSyncPhase(CatalogSyncStage.PUSH_CATEGORIES, phaseDurationsMs) {
                pushCatalogCategories(
                    remote,
                    canonicalOwnerUserId,
                    recoveryCache,
                    progressReporter,
                    shopId
                )
            }
            val pushedProducts = measureCatalogSyncPhase(CatalogSyncStage.PUSH_PRODUCTS, phaseDurationsMs) {
                pushCatalogProducts(
                    remote,
                    canonicalOwnerUserId,
                    recoveryCache,
                    progressReporter,
                    shopId
                )
            }
            var pulledSuppliers = 0
            var pulledCategories = 0
            var pulledProducts = 0
            var remoteProductRowsInBundle = 0
            var remoteActiveSuppliers = 0
            var remoteActiveCategories = 0
            var remoteActiveProducts = 0
            var prunedSuppliers = 0
            var prunedCategories = 0
            var prunedProducts = 0
            var completeCatalogSnapshot = true
            val remoteAppliedProductIds = linkedSetOf<Long>()
            measureCatalogSyncPhase(CatalogSyncStage.PULL_CATALOG, phaseDurationsMs) {
                val counts = pullCatalogFromRemote(remote, progressReporter, shopId)
                pulledSuppliers = counts.suppliers
                pulledCategories = counts.categories
                pulledProducts = counts.products
                remoteProductRowsInBundle = counts.remoteProductRows
                remoteActiveSuppliers = counts.remoteActiveSuppliers
                remoteActiveCategories = counts.remoteActiveCategories
                remoteActiveProducts = counts.remoteActiveProducts
                prunedSuppliers = counts.prunedSuppliers
                prunedCategories = counts.prunedCategories
                prunedProducts = counts.prunedProducts
                completeCatalogSnapshot = counts.completeSnapshot
                remoteAppliedProductIds += counts.appliedProductIds
            }
            var pushedPrices = 0
            var pulledPrices = 0
            var skippedPullPrices = 0
            var remotePriceRowsEvaluated = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES_PULL))
                        val pullOutcome = pullProductPricesFromRemote(
                            priceRemote = priceRemote,
                            progressReporter = progressReporter,
                            useFullRemoteFetch = true,
                            shopId = shopId
                        )
                        pulledPrices = pullOutcome.pulled
                        skippedPullPrices = pullOutcome.skippedNoLocalProduct
                        remotePriceRowsEvaluated = pullOutcome.remoteRowsEvaluated
                        remoteAppliedProductIds += pullOutcome.appliedProductIds
                        pushedPrices = pushProductPricesToRemote(
                            priceRemote,
                            canonicalOwnerUserId,
                            progressReporter,
                            shopId = shopId
                        ).count
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
            notifyRemoteProductCatalogApplied(remoteAppliedProductIds)
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
                    fullCatalogFetch = completeCatalogSnapshot,
                    fullPriceFetch = priceRemote.isConfigured,
                    remoteProductIdsRequested = remoteProductRowsInBundle,
                    remoteProductsFetched = remoteProductRowsInBundle,
                    remotePriceIdsRequested = remotePriceRowsEvaluated,
                    remotePricesFetched = remotePriceRowsEvaluated,
                    incrementalRemoteSubsetVerifiable = completeCatalogSnapshot,
                    incrementalRemoteNotVerifiableReason = if (completeCatalogSnapshot) null else "scoped_catalog_snapshot",
                    remoteActiveSuppliers = remoteActiveSuppliers,
                    remoteActiveCategories = remoteActiveCategories,
                    remoteActiveProducts = remoteActiveProducts,
                    prunedSuppliers = prunedSuppliers,
                    prunedCategories = prunedCategories,
                    prunedProducts = prunedProducts
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
    ): Result<CatalogSyncSummary> =
        pushDirtyCatalogDeltaToRemote(remote, priceRemote, ownerUserId, progressReporter, selectedShop = null)

    override suspend fun pushDirtyCatalogDeltaToRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val canonicalOwnerUserId = CatalogTextCanonicalizer.remoteId(ownerUserId)
        val shopId = CatalogTextCanonicalizer.optionalRemoteId(selectedShop?.shopId)
        // 044A: lane rapida — vietato fetchCatalog / pull prezzi full-page; solo push delta e metriche oneste.
        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val recoveryCache = CatalogConflictRecoveryCache(allowRemoteFetch = false)
            val tombstonedIds = measureCatalogSyncPhase(CatalogSyncStage.REALIGN, phaseDurationsMs) {
                progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.REALIGN))
                drainPendingCatalogTombstones(remote, canonicalOwnerUserId, shopId)
            }
            val deferredPrices = priceDao.countPriceRowsWithoutProductRemote()
            val pushedProducts = measureCatalogSyncPhase(CatalogSyncStage.PUSH_PRODUCTS, phaseDurationsMs) {
                pushCatalogProducts(
                    remote = remote,
                    ownerUserId = canonicalOwnerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    shopId = shopId,
                    allowCreatingDependencyRefs = false
                )
            }
            var pushedPrices = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES_PUSH))
                        pushedPrices = pushProductPricesToRemote(
                            priceRemote = priceRemote,
                            ownerUserId = canonicalOwnerUserId,
                            progressReporter = progressReporter,
                            shopId = shopId,
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
                    pushedSuppliers = tombstonedIds.supplierIds.size,
                    pushedCategories = tombstonedIds.categoryIds.size,
                    pushedProducts = pushedProducts.count + tombstonedIds.productIds.size,
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

    suspend fun syncCatalogQuickWithEvents(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEventRemote,
            ownerUserId = ownerUserId,
            progressReporter = progressReporter,
            sessionRemote = null,
            selectedShop = selectedShop
        )

    override suspend fun syncCatalogQuickWithEvents(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource?
    ): Result<CatalogSyncSummary> =
        syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEventRemote,
            ownerUserId = ownerUserId,
            progressReporter = progressReporter,
            sessionRemote = sessionRemote,
            selectedShop = null
        )

    override suspend fun syncCatalogQuickWithEvents(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource?,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val shopId = selectedShop?.shopId
        val storeScope = shopScopedStoreScope(selectedShop)
        val capabilities = businessScopedRemoteCall {
            syncEventRemote.checkCapabilities(ownerUserId)
        }.getOrElse { error ->
            return@withContext Result.failure(error)
        }
        if (!syncEventRemote.isConfigured ||
            !capabilities.syncEventsAvailable ||
            !capabilities.recordSyncEventAvailable
        ) {
            return@withContext pushDirtyCatalogDeltaToRemote(
                remote = remote,
                priceRemote = priceRemote,
                ownerUserId = ownerUserId,
                progressReporter = progressReporter,
                selectedShop = selectedShop
            ).map {
                it.copy(
                    syncEventsAvailable = capabilities.syncEventsAvailable,
                    recordSyncEventAvailable = capabilities.recordSyncEventAvailable,
                    realtimeSyncEventsAvailable = capabilities.realtimeSyncEventsAvailable,
                    syncEventsFallback044 = true,
                    syncEventsDisabled = true,
                    syncEventOutboxPending = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
                )
            }
        }

        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val deviceId = getOrCreateSyncEventDeviceId()
            val watermarkBefore = currentSyncEventWatermark(ownerUserId, storeScope)
            val retryOutboxResult = retrySyncEventOutbox(syncEventRemote, ownerUserId, storeScope)
            val recoveryCache = CatalogConflictRecoveryCache(allowRemoteFetch = false)
            val tombstonedIds = measureCatalogSyncPhase(CatalogSyncStage.REALIGN, phaseDurationsMs) {
                progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.REALIGN))
                drainPendingCatalogTombstones(remote, ownerUserId, shopId)
            }
            val deferredPrices = priceDao.countPriceRowsWithoutProductRemote()
            val pushedSuppliers = measureCatalogSyncPhase(CatalogSyncStage.PUSH_SUPPLIERS, phaseDurationsMs) {
                pushCatalogSuppliers(remote, ownerUserId, recoveryCache, progressReporter, shopId)
            }
            val pushedCategories = measureCatalogSyncPhase(CatalogSyncStage.PUSH_CATEGORIES, phaseDurationsMs) {
                pushCatalogCategories(remote, ownerUserId, recoveryCache, progressReporter, shopId)
            }
            val pushedProducts = measureCatalogSyncPhase(CatalogSyncStage.PUSH_PRODUCTS, phaseDurationsMs) {
                pushCatalogProducts(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    shopId = shopId,
                    allowCreatingDependencyRefs = false
                )
            }
            var pushedPrices = ProductPricePushResult(count = 0, remoteIds = emptyList())
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES_PUSH))
                        pushedPrices = pushProductPricesToRemote(
                            priceRemote = priceRemote,
                            ownerUserId = ownerUserId,
                            progressReporter = progressReporter,
                            shopId = shopId,
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
            val rawCatalogIds = SyncEventEntityIds(
                supplierIds = (pushedSuppliers.remoteIds + tombstonedIds.supplierIds).distinct(),
                categoryIds = (pushedCategories.remoteIds + tombstonedIds.categoryIds).distinct(),
                productIds = (pushedProducts.remoteIds + tombstonedIds.productIds).distinct()
            )
            val rawPriceIds = SyncEventEntityIds(priceIds = pushedPrices.remoteIds.distinct())
            val catalogEventType = if (
                pushedSuppliers.count + pushedCategories.count + pushedProducts.count == 0 &&
                !tombstonedIds.isEmpty
            ) {
                SyncEventTypes.CATALOG_TOMBSTONE
            } else {
                SyncEventTypes.CATALOG_CHANGED
            }
            val catalogEventOutcome = recordOrEnqueueSyncEvent(
                remote = syncEventRemote,
                ownerUserId = ownerUserId,
                storeScope = storeScope,
                ids = rawCatalogIds,
                domain = SyncEventDomains.CATALOG,
                eventType = catalogEventType,
                batchId = batchId,
                deviceId = deviceId,
                shopId = shopId
            )
            val priceEventOutcome = recordOrEnqueueSyncEvent(
                remote = syncEventRemote,
                ownerUserId = ownerUserId,
                storeScope = storeScope,
                ids = rawPriceIds,
                domain = SyncEventDomains.PRICES,
                eventType = SyncEventTypes.PRICES_CHANGED,
                batchId = batchId,
                deviceId = deviceId,
                shopId = shopId
            )

            val drain = drainSyncEventsInternal(
                remote = remote,
                priceRemote = priceRemote,
                syncEventRemote = syncEventRemote,
                sessionRemote = sessionRemote,
                ownerUserId = ownerUserId,
                deviceId = deviceId,
                progressReporter = progressReporter,
                selectedShop = selectedShop,
                protectedLocalCommitIds = SyncEventEntityIds(
                    supplierIds = rawCatalogIds.supplierIds,
                    categoryIds = rawCatalogIds.categoryIds,
                    productIds = rawCatalogIds.productIds,
                    priceIds = rawPriceIds.priceIds
                )
            )
            val outboxPending = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
            logCatalogSyncPhaseDurations(
                ok = true,
                durationsMs = phaseDurationsMs,
                priceSyncFailed = priceSyncFailed
            )
            logSyncEventSummary(
                phase = "quick",
                capabilities = capabilities,
                outboxPending = outboxPending,
                retryOutboxResult = retryOutboxResult,
                drain = drain,
                catalogEventOutcome = catalogEventOutcome,
                priceEventOutcome = priceEventOutcome
            )
            notifyRemoteProductCatalogApplied(drain.remoteAppliedProductIds)
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
                    syncEventOutboxRetried = retryOutboxResult.outboxRetried,
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
                    targetedHistoryFetched = drain.targetedHistoryFetched,
                    remoteUpdatesApplied = drain.remoteUpdatesApplied,
                    remoteHistoryUpdatesApplied = drain.remoteHistoryUpdatesApplied,
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

    suspend fun drainSyncEventsFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> =
        drainSyncEventsFromRemote(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEventRemote,
            ownerUserId = ownerUserId,
            progressReporter = progressReporter,
            sessionRemote = null,
            selectedShop = selectedShop
        )

    override suspend fun drainSyncEventsFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource?
    ): Result<CatalogSyncSummary> =
        drainSyncEventsFromRemote(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEventRemote,
            ownerUserId = ownerUserId,
            progressReporter = progressReporter,
            sessionRemote = sessionRemote,
            selectedShop = null
        )

    override suspend fun drainSyncEventsFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        ownerUserId: String,
        progressReporter: CatalogSyncProgressReporter,
        sessionRemote: SessionBackupRemoteDataSource?,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val storeScope = shopScopedStoreScope(selectedShop)
        val capabilities = businessScopedRemoteCall {
            syncEventRemote.checkCapabilities(ownerUserId)
        }.getOrElse { error ->
            return@withContext Result.failure(error)
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
                    syncEventOutboxPending = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
                )
            )
        }
        try {
            val deviceId = getOrCreateSyncEventDeviceId()
            val retryOutboxResult = retrySyncEventOutbox(syncEventRemote, ownerUserId, storeScope)
            val drain = drainSyncEventsInternal(
                remote = remote,
                priceRemote = priceRemote,
                syncEventRemote = syncEventRemote,
                sessionRemote = sessionRemote,
                ownerUserId = ownerUserId,
                deviceId = deviceId,
                progressReporter = progressReporter,
                selectedShop = selectedShop
            )
            val outboxPending = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
            logSyncEventSummary(
                phase = "drain",
                capabilities = capabilities,
                outboxPending = outboxPending,
                retryOutboxResult = retryOutboxResult,
                drain = drain,
                catalogEventOutcome = SyncEventRecordOutcome.NoOp,
                priceEventOutcome = SyncEventRecordOutcome.NoOp
            )
            notifyRemoteProductCatalogApplied(drain.remoteAppliedProductIds)
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
                    syncEventOutboxRetried = retryOutboxResult.outboxRetried,
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
                    targetedHistoryFetched = drain.targetedHistoryFetched,
                    remoteUpdatesApplied = drain.remoteUpdatesApplied,
                    remoteHistoryUpdatesApplied = drain.remoteHistoryUpdatesApplied,
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
    ): Result<CatalogSyncSummary> =
        pullCatalogBootstrapFromRemote(remote, priceRemote, progressReporter, selectedShop = null)

    override suspend fun pullCatalogBootstrapFromRemote(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        val shopId = selectedShop?.shopId
        val phaseDurationsMs = linkedMapOf<CatalogSyncStage, Long>()
        try {
            val remoteAppliedProductIds = linkedSetOf<Long>()
            val pullCounts = measureCatalogSyncPhase(CatalogSyncStage.PULL_CATALOG, phaseDurationsMs) {
                pullCatalogFromRemote(remote, progressReporter, shopId)
            }
            remoteAppliedProductIds += pullCounts.appliedProductIds
            var pulledPrices = 0
            var skippedPullPrices = 0
            var remotePriceRowsEvaluated = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_PRICES_PULL))
                        val pullOutcome = pullProductPricesFromRemote(priceRemote, progressReporter, shopId = shopId)
                        pulledPrices = pullOutcome.pulled
                        skippedPullPrices = pullOutcome.skippedNoLocalProduct
                        remotePriceRowsEvaluated = pullOutcome.remoteRowsEvaluated
                        remoteAppliedProductIds += pullOutcome.appliedProductIds
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
            notifyRemoteProductCatalogApplied(remoteAppliedProductIds)
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
                    fullCatalogFetch = pullCounts.completeSnapshot,
                    fullPriceFetch = priceRemote.isConfigured,
                    remoteProductIdsRequested = pullCounts.remoteProductRows,
                    remoteProductsFetched = pullCounts.remoteProductRows,
                    remotePriceIdsRequested = remotePriceRowsEvaluated,
                    remotePricesFetched = remotePriceRowsEvaluated,
                    incrementalRemoteSubsetVerifiable = pullCounts.completeSnapshot,
                    incrementalRemoteNotVerifiableReason = if (pullCounts.completeSnapshot) null else "scoped_catalog_snapshot",
                    remoteActiveSuppliers = pullCounts.remoteActiveSuppliers,
                    remoteActiveCategories = pullCounts.remoteActiveCategories,
                    remoteActiveProducts = pullCounts.remoteActiveProducts,
                    prunedSuppliers = pullCounts.prunedSuppliers,
                    prunedCategories = pullCounts.prunedCategories,
                    prunedProducts = pullCounts.prunedProducts
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

    internal suspend fun pullTask087CatalogFromRemote(
        remote: SupabaseCatalogRemoteDataSource
    ): Result<CatalogSyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            require(BuildConfig.DEBUG) { "TASK087 smoke disabled" }
            val bundle = businessScopedRemoteCall {
                remote.fetchTask087CatalogByBarcodes(
                    setOf("TASK087_BAR_A", "TASK087_BAR_I")
                )
            }.getOrThrow()
            val counts = applyCatalogBundleInbound(bundle)
            notifyRemoteProductCatalogApplied(counts.appliedProductIds)
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = 0,
                pulledSuppliers = counts.suppliers,
                pulledCategories = counts.categories,
                pulledProducts = counts.products,
                pushedProductPrices = 0,
                pulledProductPrices = 0,
                fullCatalogFetch = false,
                fullPriceFetch = false,
                remoteProductIdsRequested = bundle.products.size,
                remoteProductsFetched = bundle.products.size,
                remotePriceIdsRequested = 0,
                remotePricesFetched = 0,
                incrementalRemoteSubsetVerifiable = true,
                incrementalRemoteNotVerifiableReason = null,
                targetedProductsFetched = bundle.products.size,
                remoteUpdatesApplied = counts.suppliers + counts.categories + counts.products
            )
        }
    }

    private suspend fun pullCatalogFromRemote(
        remote: CatalogRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?
    ): CatalogPullApplyCounts {
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
        val fetchStartedAt = System.currentTimeMillis()
        val bundle = businessScopedRemoteCall { remote.fetchCatalog(shopId) }.getOrThrow()
        val fetchMs = System.currentTimeMillis() - fetchStartedAt
        val applyStartedAt = System.currentTimeMillis()
        val applyCounts = applyCatalogBundleInbound(bundle)
        val pruneCounts = if (bundle.isCompleteSnapshot) {
            reconcileLocalCatalogAfterInboundPull(bundle)
        } else {
            Log.i(TAG, "catalog_prune skipped reason=scoped_catalog_snapshot")
            CatalogPruneCounts()
        }
        val applyMs = System.currentTimeMillis() - applyStartedAt
        val remoteActiveSuppliers = bundle.suppliers.count { it.deletedAt.isNullOrBlank() }
        val remoteActiveCategories = bundle.categories.count { it.deletedAt.isNullOrBlank() }
        val remoteActiveProducts = bundle.products.count { it.deletedAt.isNullOrBlank() }
        Log.i(
            TAG,
            "phase_metrics syncDomain=CATALOG phase=PULL_CATALOG " +
                "remoteSuppliers=${bundle.suppliers.size} remoteCategories=${bundle.categories.size} " +
                "remoteProducts=${bundle.products.size} remoteActiveProducts=$remoteActiveProducts " +
                "pulledSuppliers=${applyCounts.suppliers} pulledCategories=${applyCounts.categories} " +
                "pulledProducts=${applyCounts.products} prunedProducts=${pruneCounts.products} " +
                "prunedSuppliers=${pruneCounts.suppliers} prunedCategories=${pruneCounts.categories} " +
                "fetchMs=$fetchMs applyMs=$applyMs"
        )
        return CatalogPullApplyCounts(
            suppliers = applyCounts.suppliers,
            categories = applyCounts.categories,
            products = applyCounts.products,
            remoteSupplierRows = bundle.suppliers.size,
            remoteCategoryRows = bundle.categories.size,
            remoteProductRows = bundle.products.size,
            remoteActiveSuppliers = remoteActiveSuppliers,
            remoteActiveCategories = remoteActiveCategories,
            remoteActiveProducts = remoteActiveProducts,
            prunedSuppliers = pruneCounts.suppliers,
            prunedCategories = pruneCounts.categories,
            prunedProducts = pruneCounts.products,
            completeSnapshot = bundle.isCompleteSnapshot,
            appliedProductIds = applyCounts.appliedProductIds
        )
    }

    /**
     * TASK-114: dopo un pull catalogo completo, elimina righe locali «clean» il cui bridge punta a
     * remote assenti o tombstonati nel bundle (zombie accumulati quando il remoto si restringe).
     */
    private suspend fun reconcileLocalCatalogAfterInboundPull(
        bundle: InventoryCatalogFetchBundle
    ): CatalogPruneCounts {
        val allSupplierIds = bundle.suppliers.map { it.id }.toSet()
        val allCategoryIds = bundle.categories.map { it.id }.toSet()
        val allProductIds = bundle.products.map { it.id }.toSet()
        val tombstonedSupplierIds = bundle.suppliers
            .filter { !it.deletedAt.isNullOrBlank() }
            .map { it.id }
            .toSet()
        val tombstonedCategoryIds = bundle.categories
            .filter { !it.deletedAt.isNullOrBlank() }
            .map { it.id }
            .toSet()
        val tombstonedProductIds = bundle.products
            .filter { !it.deletedAt.isNullOrBlank() }
            .map { it.id }
            .toSet()

        var prunedSuppliers = 0
        var prunedCategories = 0
        var prunedProducts = 0

        db.withTransaction {
            requireCurrentBusinessDataScope()
            val pendingTombstones = pendingCatalogTombstoneDao.listPendingOrdered()
            val pendingSupplierTombstones = pendingTombstones
                .filter { it.entityType == PendingCatalogTombstoneEntityTypes.SUPPLIER }
                .map { it.remoteId }
                .toSet()
            val pendingCategoryTombstones = pendingTombstones
                .filter { it.entityType == PendingCatalogTombstoneEntityTypes.CATEGORY }
                .map { it.remoteId }
                .toSet()
            val pendingProductTombstones = pendingTombstones
                .filter { it.entityType == PendingCatalogTombstoneEntityTypes.PRODUCT }
                .map { it.remoteId }
                .toSet()

            for (ref in supplierRemoteRefDao.getCleanRefs()) {
                if (ref.remoteId in pendingSupplierTombstones) continue
                val stale = ref.remoteId !in allSupplierIds || ref.remoteId in tombstonedSupplierIds
                if (!stale) continue
                if (try {
                        deleteCatalogEntity(
                            CatalogEntityKind.SUPPLIER,
                            ref.supplierId,
                            enqueueCloudTombstone = false
                        )
                        true
                    } catch (_: CatalogNotFoundException) {
                        false
                    }
                ) {
                    prunedSuppliers++
                }
            }
            for (ref in categoryRemoteRefDao.getCleanRefs()) {
                if (ref.remoteId in pendingCategoryTombstones) continue
                val stale = ref.remoteId !in allCategoryIds || ref.remoteId in tombstonedCategoryIds
                if (!stale) continue
                if (try {
                        deleteCatalogEntity(
                            CatalogEntityKind.CATEGORY,
                            ref.categoryId,
                            enqueueCloudTombstone = false
                        )
                        true
                    } catch (_: CatalogNotFoundException) {
                        false
                    }
                ) {
                    prunedCategories++
                }
            }
            for (ref in productRemoteRefDao.getCleanRefs()) {
                if (ref.remoteId in pendingProductTombstones) continue
                val stale = ref.remoteId !in allProductIds || ref.remoteId in tombstonedProductIds
                if (!stale) continue
                val product = productDao.getById(ref.productId) ?: continue
                productDao.delete(product)
                prunedProducts++
            }
        }

        if (prunedSuppliers + prunedCategories + prunedProducts > 0) {
            Log.i(
                TAG,
                "catalog_prune suppliers=$prunedSuppliers categories=$prunedCategories products=$prunedProducts"
            )
        }
        return CatalogPruneCounts(
            suppliers = prunedSuppliers,
            categories = prunedCategories,
            products = prunedProducts
        )
    }

    private suspend fun applyCatalogBundleInbound(bundle: InventoryCatalogFetchBundle): CatalogPullApplyCounts {
        var pulledSuppliers = 0
        var pulledCategories = 0
        var pulledProducts = 0
        val appliedProductIds = linkedSetOf<Long>()
        db.withTransaction {
            requireCurrentBusinessDataScope()
            for (row in bundle.products.filter { !it.deletedAt.isNullOrBlank() }) {
                applyInboundProductTombstone(row)?.let { productId ->
                    pulledProducts++
                    appliedProductIds += productId
                }
            }
            for (row in bundle.categories.filter { !it.deletedAt.isNullOrBlank() }) {
                val affectedProductIds = categoryRemoteRefDao.getByRemoteId(row.id)
                    ?.categoryId
                    ?.let { productDao.getIdsForCategory(it) }
                    .orEmpty()
                if (applyInboundCategoryTombstone(row)) {
                    pulledCategories++
                    appliedProductIds += affectedProductIds
                }
            }
            for (row in bundle.suppliers.filter { !it.deletedAt.isNullOrBlank() }) {
                val affectedProductIds = supplierRemoteRefDao.getByRemoteId(row.id)
                    ?.supplierId
                    ?.let { productDao.getIdsForSupplier(it) }
                    .orEmpty()
                if (applyInboundSupplierTombstone(row)) {
                    pulledSuppliers++
                    appliedProductIds += affectedProductIds
                }
            }
            for (row in bundle.suppliers.filter { it.deletedAt.isNullOrBlank() }) {
                val existingRef = supplierRemoteRefDao.getByRemoteId(row.id)
                val affectedProductIds = existingRef
                    ?.supplierId
                    ?.let { productDao.getIdsForSupplier(it) }
                    .orEmpty()
                if (applyRemoteSupplierInbound(row)) {
                    pulledSuppliers++
                    if (existingRef != null) {
                        appliedProductIds += affectedProductIds
                    }
                }
            }
            for (row in bundle.categories.filter { it.deletedAt.isNullOrBlank() }) {
                val existingRef = categoryRemoteRefDao.getByRemoteId(row.id)
                val affectedProductIds = existingRef
                    ?.categoryId
                    ?.let { productDao.getIdsForCategory(it) }
                    .orEmpty()
                if (applyRemoteCategoryInbound(row)) {
                    pulledCategories++
                    if (existingRef != null) {
                        appliedProductIds += affectedProductIds
                    }
                }
            }
            for (row in bundle.products.filter { it.deletedAt.isNullOrBlank() }) {
                applyRemoteProductInbound(row)?.let { productId ->
                    pulledProducts++
                    appliedProductIds += productId
                }
            }
        }
        return CatalogPullApplyCounts(
            suppliers = pulledSuppliers,
            categories = pulledCategories,
            products = pulledProducts,
            remoteSupplierRows = bundle.suppliers.size,
            remoteCategoryRows = bundle.categories.size,
            remoteProductRows = bundle.products.size,
            remoteActiveSuppliers = bundle.suppliers.count { it.deletedAt.isNullOrBlank() },
            remoteActiveCategories = bundle.categories.count { it.deletedAt.isNullOrBlank() },
            remoteActiveProducts = bundle.products.count { it.deletedAt.isNullOrBlank() },
            appliedProductIds = appliedProductIds
        )
    }

    private suspend fun drainSyncEventsInternal(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        syncEventRemote: SyncEventRemoteDataSource,
        sessionRemote: SessionBackupRemoteDataSource?,
        ownerUserId: String,
        deviceId: String,
        progressReporter: CatalogSyncProgressReporter,
        selectedShop: SelectedShop?,
        protectedLocalCommitIds: SyncEventEntityIds = SyncEventEntityIds()
    ): SyncEventDrainResult {
        val shopId = selectedShop?.shopId
        val storeScope = shopScopedStoreScope(selectedShop)
        var watermark = currentSyncEventWatermark(ownerUserId, storeScope)
        val watermarkBefore = watermark
        var fetched = 0
        var processed = 0
        var skippedSelf = 0
        var skippedDirty = 0
        var targetedProductsFetched = 0
        var targetedPricesFetched = 0
        var targetedHistoryFetched = 0
        var remoteUpdatesApplied = 0
        var remoteHistoryUpdatesApplied = 0
        var tooLarge = false
        var gapDetected = false
        var manualFullSyncRequired = false
        var skippedProtectedLocalCommit = 0
        val remoteAppliedProductIds = linkedSetOf<Long>()
        var iterations = 0
        var checkpointBlockedByUnappliedEvent = false
        var iterationCapNeedsOverflowProbe = false
        var resolvedShopReadScope: ShopSyncScope? = null
        var capturedShopEventMaxId: String? = null
        var capturedShopDomainEventMaxIds: Map<String, String> = emptyMap()
        var verifiedBaselineScopeKey: String? = null
        var verifiedShopBaseline: ShopSyncRecoveryCheckpoint? = null

        while (iterations < SYNC_EVENT_DRAIN_MAX_ITERATIONS) {
            var shopPageHasMore: Boolean? = null
            val events = if (shopId != null) {
                val reader = shopSyncReadRemoteDataSource
                    ?.takeIf { it.isConfigured }
                    ?: throw ShopSyncContractException("shop_sync_reader_unavailable")
                if (resolvedShopReadScope == null) {
                    val baseline = shopSyncBaselineForEventDrain(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        shopId = shopId,
                        deviceId = deviceId,
                        watermark = watermark
                    )
                    if (watermark > 0L && baseline == null) {
                        recordShopSyncRecoveryRequiredWithoutEvent(
                            ownerUserId = ownerUserId,
                            storeScope = storeScope,
                            shopId = shopId,
                            deviceId = deviceId,
                            reason = SyncEventApplyStatusReasons.SCOPE_MISMATCH
                        )
                        return SyncEventDrainResult(
                            fetched = fetched,
                            processed = processed,
                            skippedSelf = skippedSelf,
                            skippedDirtyLocal = skippedDirty,
                            watermarkBefore = watermarkBefore,
                            watermarkAfter = watermark,
                            targetedProductsFetched = targetedProductsFetched,
                            targetedPricesFetched = targetedPricesFetched,
                            targetedHistoryFetched = targetedHistoryFetched,
                            remoteUpdatesApplied = remoteUpdatesApplied,
                            remoteHistoryUpdatesApplied = remoteHistoryUpdatesApplied,
                            tooLarge = tooLarge,
                            gapDetected = true,
                            manualFullSyncRequired = true
                        )
                    }
                    val checkpoint = businessScopedRemoteCall {
                        reader.checkpoint(
                            ShopSyncRpcContext(
                                accountId = ownerUserId,
                                shopId = shopId,
                                deviceIdentifier = deviceId,
                                expectedScope = baseline?.scope,
                                verifiedBaselineId = watermark.toString(),
                                expectedBaselineScopeKey = baseline?.scope?.key
                            )
                        )
                    }.getOrThrow()
                    if (checkpoint.syncEvents.requiresFullRecovery) {
                        recordShopSyncRecoveryRequiredWithoutEvent(
                            ownerUserId = ownerUserId,
                            storeScope = storeScope,
                            shopId = shopId,
                            deviceId = deviceId,
                            reason = SyncEventApplyStatusReasons.MISSING_ENTITY_IDS,
                            blockingEventId = checkpoint.syncEvents.oldestBlockingId
                                ?.let(::parseShopSyncMaxEventId)
                        )
                        return SyncEventDrainResult(
                            fetched = fetched,
                            processed = processed,
                            skippedSelf = skippedSelf,
                            skippedDirtyLocal = skippedDirty,
                            watermarkBefore = watermarkBefore,
                            watermarkAfter = watermark,
                            targetedProductsFetched = targetedProductsFetched,
                            targetedPricesFetched = targetedPricesFetched,
                            targetedHistoryFetched = targetedHistoryFetched,
                            remoteUpdatesApplied = remoteUpdatesApplied,
                            remoteHistoryUpdatesApplied = remoteHistoryUpdatesApplied,
                            tooLarge = tooLarge,
                            gapDetected = true,
                            manualFullSyncRequired = true
                        )
                    }
                    resolvedShopReadScope = checkpoint.scope
                    // The checkpoint is the only authoritative bootstrap for
                    // a non-zero watermark. Keep its opaque snapshot fence on
                    // the first event-page request as well: omitting it is
                    // rejected by the V6 reader and could otherwise turn a
                    // valid post-recovery delta into a false no-work path.
                    capturedShopEventMaxId = checkpoint.syncEvents.maxId
                    capturedShopDomainEventMaxIds = checkpoint.syncEvents.domainMaxIds
                    verifiedBaselineScopeKey = checkpoint.scope.key
                    verifiedShopBaseline = baseline
                }
                val page = businessScopedRemoteCall {
                    reader.eventPage(
                        context = ShopSyncRpcContext(
                            accountId = ownerUserId,
                            shopId = shopId,
                            deviceIdentifier = deviceId,
                            expectedScope = resolvedShopReadScope,
                            expectedEventMaxId = capturedShopEventMaxId
                        ),
                        afterId = watermark,
                        limit = SYNC_EVENT_FETCH_LIMIT.toInt()
                    )
                }.getOrThrow()
                resolvedShopReadScope = page.scope
                if (capturedShopEventMaxId == null) {
                    capturedShopEventMaxId = page.asOfEventMaxId
                    capturedShopDomainEventMaxIds = page.asOfDomainEventMaxIds
                }
                shopPageHasMore = page.hasMore
                page.rows
            } else {
                businessScopedRemoteCall {
                    syncEventRemote.fetchSyncEventsAfter(
                        ownerUserId = ownerUserId,
                        storeId = remoteStoreIdFromStoreScope(storeScope),
                        shopId = null,
                        afterId = watermark,
                        limit = SYNC_EVENT_FETCH_LIMIT
                    )
                }.getOrThrow().sortedBy { it.id }
            }
            if (events.isEmpty()) break
            fetched += events.size
            for (event in events) {
                if (event.id <= watermark) continue
                val ids = event.entityIds
                val scopeMatches = if (shopId == null) {
                    val expectedStoreId = remoteStoreIdFromStoreScope(storeScope)
                    event.shopId == null &&
                        event.ownerUserId == ownerUserId &&
                        event.storeId?.takeIf { it.isNotBlank() } == expectedStoreId
                } else {
                    when (resolvedShopReadScope?.kind) {
                        ShopSyncScopeKinds.SHOP_SCOPED ->
                            event.shopId?.lowercase() == shopId.lowercase()
                        ShopSyncScopeKinds.LEGACY_OWNER_BRIDGE ->
                            event.shopId == null &&
                                resolvedShopReadScope.legacyOwnerKey ==
                                task126OwnerHash(event.ownerUserId.lowercase())
                        ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY ->
                            event.shopId?.lowercase() == shopId.lowercase() ||
                                (
                                    event.shopId == null &&
                                        resolvedShopReadScope.legacyOwnerKey ==
                                        task126OwnerHash(event.ownerUserId.lowercase())
                                )
                        else -> false
                    }
                }
                if (!scopeMatches) {
                    gapDetected = true
                    manualFullSyncRequired = true
                    checkpointBlockedByUnappliedEvent = true
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = ids,
                        status = SyncEventApplyStatusValues.BLOCKED,
                        reason = SyncEventApplyStatusReasons.SCOPE_MISMATCH
                    )
                    break
                }
                val supportedDomain = event.domain == SyncEventDomains.CATALOG ||
                    event.domain == SyncEventDomains.PRICES ||
                    event.domain == SyncEventDomains.HISTORY
                if (!supportedDomain) {
                    gapDetected = true
                    manualFullSyncRequired = true
                    checkpointBlockedByUnappliedEvent = true
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = ids,
                        status = SyncEventApplyStatusValues.BLOCKED,
                        reason = SyncEventApplyStatusReasons.UNSUPPORTED_DOMAIN
                    )
                    break
                }
                if (!SyncEventContract.hasSupportedEventType(event.domain, event.eventType)) {
                    gapDetected = true
                    manualFullSyncRequired = true
                    checkpointBlockedByUnappliedEvent = true
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = ids,
                        status = SyncEventApplyStatusValues.BLOCKED,
                        reason = SyncEventApplyStatusReasons.UNSUPPORTED_EVENT_TYPE
                    )
                    break
                }
                val normalizedIds = ids ?: SyncEventEntityIds()
                val completeIds = !event.requiresFullRecovery &&
                    SyncEventContract.hasCompletePrimaryIds(
                    event.domain,
                    event.changedCount,
                    normalizedIds
                )
                if (!completeIds) {
                    val exceedsBudget = event.changedCount >
                        SyncEventContract.maxPrimaryEntityIds(event.domain) ||
                        (ids?.let { SyncEventContract.primaryChangedCount(event.domain, it) } ?: 0) >
                        SyncEventContract.maxPrimaryEntityIds(event.domain)
                    tooLarge = tooLarge || exceedsBudget
                    gapDetected = true
                    manualFullSyncRequired = true
                    checkpointBlockedByUnappliedEvent = true
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = normalizedIds,
                        status = SyncEventApplyStatusValues.BLOCKED,
                        reason = if (exceedsBudget) {
                            SyncEventApplyStatusReasons.ENTITY_IDS_TOO_LARGE
                        } else {
                            SyncEventApplyStatusReasons.MISSING_ENTITY_IDS
                        }
                    )
                    break
                }
                if (
                    event.sourceDeviceId == deviceId ||
                    event.sourceDeviceKey == syncEventDeviceKey(deviceId)
                ) {
                    skippedSelf++
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = normalizedIds,
                        status = SyncEventApplyStatusValues.SKIPPED,
                        reason = SyncEventApplyStatusReasons.SELF_ORIGIN
                    )
                    if (!checkpointBlockedByUnappliedEvent) {
                        watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                    }
                    continue
                }
                if (normalizedIds.isEmpty) {
                    processed++
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = normalizedIds,
                        status = SyncEventApplyStatusValues.APPLIED,
                        reason = SyncEventApplyStatusReasons.APPLIED
                    )
                    if (!checkpointBlockedByUnappliedEvent) {
                        watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                    }
                    continue
                }
                val effectiveIds = normalizedIds.withoutProtected(protectedLocalCommitIds)
                skippedProtectedLocalCommit += normalizedIds.totalIds - effectiveIds.totalIds
                if (normalizedIds.totalIds > 0 && effectiveIds.isEmpty) {
                    recordSyncEventApplyStatus(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        event = event,
                        ids = normalizedIds,
                        status = SyncEventApplyStatusValues.SKIPPED,
                        reason = SyncEventApplyStatusReasons.PROTECTED_LOCAL_COMMIT
                    )
                    if (!checkpointBlockedByUnappliedEvent) {
                        watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                    }
                    continue
                }
                val idsForApply = effectiveIds
                val eventDirty = countDirtyLocalRefsForEvent(idsForApply)
                skippedDirty += eventDirty
                if (eventDirty > 0) {
                    checkpointBlockedByUnappliedEvent = true
                    val previousStatus = syncEventApplyStatusDao.get(ownerUserId, storeScope, event.id)
                    val nowMs = System.currentTimeMillis()
                    val retryDeferred = previousStatus?.let { previous ->
                        previous.status == SyncEventApplyStatusValues.BLOCKED &&
                            previous.reason == SyncEventApplyStatusReasons.DIRTY_LOCAL &&
                            (
                                previous.attemptCount >= SYNC_EVENT_APPLY_MAX_ATTEMPTS ||
                                    (previous.nextRetryAtMs ?: Long.MAX_VALUE) > nowMs
                                )
                    } == true
                    if (!retryDeferred) {
                        recordSyncEventApplyStatus(
                            ownerUserId = ownerUserId,
                            storeScope = storeScope,
                            event = event,
                            ids = idsForApply,
                            status = SyncEventApplyStatusValues.BLOCKED,
                            reason = SyncEventApplyStatusReasons.DIRTY_LOCAL
                        )
                    }
                    break
                }
                var blockedReason: String? = null
                val applied = when (event.domain) {
                    SyncEventDomains.CATALOG -> {
                        val shopReadContext = resolvedShopReadScope?.let { scope ->
                            shopSyncTargetedContext(
                                ownerUserId = ownerUserId,
                                shopId = requireNotNull(shopId),
                                deviceId = deviceId,
                                scope = scope,
                                eventMaxId = requireNotNull(capturedShopEventMaxId),
                                domainEventMaxIds = capturedShopDomainEventMaxIds,
                                domain = SyncEventDomains.CATALOG
                            )
                        }
                        val counts = applyCatalogEventByIds(
                            remote,
                            idsForApply,
                            progressReporter,
                            shopId,
                            shopReadContext
                        )
                        targetedProductsFetched += counts.remoteProductRows
                        remoteAppliedProductIds += counts.appliedProductIds
                        if (counts.targetedMissingRemote) {
                            gapDetected = true
                            manualFullSyncRequired = true
                            checkpointBlockedByUnappliedEvent = true
                            blockedReason = SyncEventApplyStatusReasons.MISSING_REMOTE
                        }
                        var applied = counts.suppliers + counts.categories + counts.products
                        if (idsForApply.productIds.isNotEmpty() && priceRemote.isConfigured) {
                            val priceOutcome = applyPriceRowsForProductIds(
                                priceRemote = priceRemote,
                                productRemoteIds = idsForApply.productIds.toSet(),
                                progressReporter = progressReporter,
                                shopId = shopId,
                                shopReadContext = shopReadContext
                            )
                            targetedPricesFetched += priceOutcome.remoteRowsEvaluated
                            remoteAppliedProductIds += priceOutcome.appliedProductIds
                            applied += priceOutcome.pulled
                        }
                        applied
                    }
                    SyncEventDomains.PRICES -> {
                        val shopReadContext = resolvedShopReadScope?.let { scope ->
                            shopSyncTargetedContext(
                                ownerUserId = ownerUserId,
                                shopId = requireNotNull(shopId),
                                deviceId = deviceId,
                                scope = scope,
                                eventMaxId = requireNotNull(capturedShopEventMaxId),
                                domainEventMaxIds = capturedShopDomainEventMaxIds,
                                domain = SyncEventDomains.PRICES
                            )
                        }
                        val outcome = applyPriceEventByIds(
                            remote,
                            priceRemote,
                            idsForApply,
                            progressReporter,
                            shopId,
                            shopReadContext
                        )
                        targetedProductsFetched += outcome.first
                        targetedPricesFetched += outcome.second.remoteRowsEvaluated
                        remoteAppliedProductIds += outcome.second.appliedProductIds
                        if (
                            idsForApply.priceIds.isNotEmpty() &&
                            (
                                outcome.second.remoteRowsEvaluated < idsForApply.priceIds.size ||
                                    outcome.second.skippedNoLocalProduct > 0
                                )
                        ) {
                            gapDetected = true
                            manualFullSyncRequired = true
                            checkpointBlockedByUnappliedEvent = true
                            blockedReason = SyncEventApplyStatusReasons.MISSING_REMOTE
                        }
                        outcome.second.pulled
                    }
                    SyncEventDomains.HISTORY -> {
                        val shopReadContext = resolvedShopReadScope?.let { scope ->
                            shopSyncTargetedContext(
                                ownerUserId = ownerUserId,
                                shopId = requireNotNull(shopId),
                                deviceId = deviceId,
                                scope = scope,
                                eventMaxId = requireNotNull(capturedShopEventMaxId),
                                domainEventMaxIds = capturedShopDomainEventMaxIds,
                                domain = SyncEventDomains.HISTORY
                            )
                        }
                        val outcome = applyHistoryEventByIds(
                            sessionRemote,
                            idsForApply,
                            shopId,
                            shopReadContext
                        )
                        targetedHistoryFetched += outcome.remoteRows
                        remoteHistoryUpdatesApplied += outcome.appliedRows
                        if (
                            idsForApply.sessionIds.isNotEmpty() &&
                            outcome.remoteRows < idsForApply.sessionIds.size
                        ) {
                            gapDetected = true
                            manualFullSyncRequired = true
                            checkpointBlockedByUnappliedEvent = true
                            blockedReason = SyncEventApplyStatusReasons.MISSING_REMOTE
                        } else if (outcome.unsupportedRows > 0) {
                            gapDetected = true
                            manualFullSyncRequired = true
                            checkpointBlockedByUnappliedEvent = true
                            blockedReason =
                                SyncEventApplyStatusReasons.UNSUPPORTED_PAYLOAD_VERSION
                        } else if (outcome.failedRows > 0) {
                            gapDetected = true
                            manualFullSyncRequired = true
                            checkpointBlockedByUnappliedEvent = true
                            blockedReason = SyncEventApplyStatusReasons.REMOTE_APPLY_FAILED
                        }
                        outcome.appliedRows
                    }
                    else -> {
                        gapDetected = true
                        manualFullSyncRequired = true
                        checkpointBlockedByUnappliedEvent = true
                        blockedReason = SyncEventApplyStatusReasons.UNSUPPORTED_DOMAIN
                        0
                    }
                }
                remoteUpdatesApplied += applied
                processed++
                recordSyncEventApplyStatus(
                    ownerUserId = ownerUserId,
                    storeScope = storeScope,
                    event = event,
                    ids = idsForApply,
                    status = if (blockedReason == null) {
                        SyncEventApplyStatusValues.APPLIED
                    } else {
                        SyncEventApplyStatusValues.BLOCKED
                    },
                    reason = blockedReason ?: SyncEventApplyStatusReasons.APPLIED
                )
                if (blockedReason != null) {
                    break
                }
                if (!checkpointBlockedByUnappliedEvent) {
                    watermark = advanceSyncEventWatermark(ownerUserId, storeScope, event.id)
                }
            }
            iterations++
            if (checkpointBlockedByUnappliedEvent) break
            if (shopPageHasMore == false) break
            if (events.size < SYNC_EVENT_FETCH_LIMIT) break
            iterationCapNeedsOverflowProbe = iterations >= SYNC_EVENT_DRAIN_MAX_ITERATIONS
        }
        if (iterationCapNeedsOverflowProbe && !checkpointBlockedByUnappliedEvent) {
            val overflowEvent = if (shopId != null) {
                val reader = checkNotNull(shopSyncReadRemoteDataSource) {
                    "shop_sync_reader_unavailable"
                }
                businessScopedRemoteCall {
                    reader.eventPage(
                        context = ShopSyncRpcContext(
                            accountId = ownerUserId,
                            shopId = shopId,
                            deviceIdentifier = deviceId,
                            expectedScope = resolvedShopReadScope,
                            expectedEventMaxId = capturedShopEventMaxId
                        ),
                        afterId = watermark,
                        limit = 1
                    )
                }.getOrThrow().rows.firstOrNull()
            } else {
                businessScopedRemoteCall {
                    syncEventRemote.fetchSyncEventsAfter(
                        ownerUserId = ownerUserId,
                        storeId = remoteStoreIdFromStoreScope(storeScope),
                        shopId = null,
                        afterId = watermark,
                        limit = 1L
                    )
                }.getOrThrow().sortedBy { it.id }.firstOrNull { it.id > watermark }
            }
            if (overflowEvent != null) {
                fetched++
                gapDetected = true
                manualFullSyncRequired = true
                checkpointBlockedByUnappliedEvent = true
                recordSyncEventApplyStatus(
                    ownerUserId = ownerUserId,
                    storeScope = storeScope,
                    event = overflowEvent,
                    ids = overflowEvent.entityIds,
                    status = SyncEventApplyStatusValues.BLOCKED,
                    reason = SyncEventApplyStatusReasons.DRAIN_LIMIT_REACHED
                )
            }
        }
        if (
            shopId != null &&
            !checkpointBlockedByUnappliedEvent &&
            !manualFullSyncRequired &&
            resolvedShopReadScope != null &&
            capturedShopEventMaxId != null &&
            watermark == parseShopSyncMaxEventId(requireNotNull(capturedShopEventMaxId))
        ) {
            val journalPending = syncRecoveryJournalDao.getForScope(
                ownerHash = task126OwnerHash(ownerUserId),
                storeScope = Task126OwnerStoreScope.normalizedStoreId(storeScope)
            ) != null
            val outboxPending = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope) > 0
            if (journalPending || outboxPending) {
                gapDetected = true
                manualFullSyncRequired = true
                recordShopSyncRecoveryRequiredWithoutEvent(
                    ownerUserId = ownerUserId,
                    storeScope = storeScope,
                    shopId = shopId,
                    deviceId = deviceId,
                    reason = SyncEventApplyStatusReasons.SCOPE_MISMATCH
                )
            } else {
                val reader = checkNotNull(shopSyncReadRemoteDataSource) {
                    "shop_sync_reader_unavailable"
                }
                val marker = try {
                    businessScopedRemoteCall {
                        reader.convergenceMarker(
                            ShopSyncRpcContext(
                                accountId = ownerUserId,
                                shopId = shopId,
                                deviceIdentifier = deviceId,
                                expectedScope = requireNotNull(resolvedShopReadScope),
                                verifiedBaselineId = watermark.toString(),
                                expectedBaselineScopeKey = requireNotNull(verifiedBaselineScopeKey)
                            )
                        )
                    }.getOrThrow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                // An event drain can prove that every targeted RPC stayed inside
                // its server fence, but it cannot mutate the immutable recovery
                // manifest into a new strong local digest. Therefore it must not
                // publish noWork merely because the server marker is ready. Only
                // the previously activated baseline at the same watermark is a
                // local proof; every other case retains a durable recovery latch.
                if (!markerProvesNoWorkAgainstBaseline(marker, verifiedShopBaseline, watermark)) {
                    gapDetected = true
                    manualFullSyncRequired = true
                    recordShopSyncRecoveryRequiredWithoutEvent(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        shopId = shopId,
                        deviceId = deviceId,
                        reason = SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED
                    )
                }
            }
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
            targetedHistoryFetched = targetedHistoryFetched,
            remoteUpdatesApplied = remoteUpdatesApplied,
            remoteHistoryUpdatesApplied = remoteHistoryUpdatesApplied,
            tooLarge = tooLarge,
            gapDetected = gapDetected,
            manualFullSyncRequired = manualFullSyncRequired,
            skippedProtectedLocalCommit = skippedProtectedLocalCommit,
            remoteAppliedProductIds = remoteAppliedProductIds
        )
    }

    private fun SyncEventEntityIds.withoutProtected(protected: SyncEventEntityIds): SyncEventEntityIds {
        if (protected.isEmpty) return this
        val protectedSupplierIds = protected.supplierIds.toSet()
        val protectedCategoryIds = protected.categoryIds.toSet()
        val protectedProductIds = protected.productIds.toSet()
        val protectedPriceIds = protected.priceIds.toSet()
        val protectedSessionIds = protected.sessionIds.toSet()
        return SyncEventEntityIds(
            supplierIds = supplierIds.filterNot { it in protectedSupplierIds },
            categoryIds = categoryIds.filterNot { it in protectedCategoryIds },
            productIds = productIds.filterNot { it in protectedProductIds },
            priceIds = priceIds.filterNot { it in protectedPriceIds },
            sessionIds = sessionIds.filterNot { it in protectedSessionIds }
        )
    }

    private suspend fun applyCatalogEventByIds(
        remote: CatalogRemoteDataSource,
        ids: SyncEventEntityIds,
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?,
        shopReadContext: ShopSyncRpcContext?
    ): CatalogPullApplyCounts {
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN))
        val firstResult = if (shopReadContext != null) {
            fetchCatalogBundleViaShopRpc(ids, shopReadContext)
        } else {
            val bundle = businessScopedRemoteCall {
                remote.fetchCatalogByIds(
                    supplierIds = ids.supplierIds.toSet(),
                    categoryIds = ids.categoryIds.toSet(),
                    productIds = ids.productIds.toSet(),
                    shopId = shopId
                )
            }.getOrThrow()
            TargetedCatalogBundle(
                bundle = bundle,
                missingRemote =
                    bundle.suppliers.map { it.id }.toSet().containsAll(ids.supplierIds).not() ||
                        bundle.categories.map { it.id }.toSet().containsAll(ids.categoryIds).not() ||
                        bundle.products.map { it.id }.toSet().containsAll(ids.productIds).not()
            )
        }
        val first = firstResult.bundle
        val directlyMissingRemote = firstResult.missingRemote
        val missingSupplierIds = first.products
            .mapNotNull { it.supplierId }
            .filter { supplierRemoteRefDao.getByRemoteId(it) == null }
            .toSet() - ids.supplierIds.toSet()
        val missingCategoryIds = first.products
            .mapNotNull { it.categoryId }
            .filter { categoryRemoteRefDao.getByRemoteId(it) == null }
            .toSet() - ids.categoryIds.toSet()
        val parentResult = if (missingSupplierIds.isNotEmpty() || missingCategoryIds.isNotEmpty()) {
            if (shopReadContext != null) {
                fetchCatalogBundleViaShopRpc(
                    SyncEventEntityIds(
                        supplierIds = missingSupplierIds.toList(),
                        categoryIds = missingCategoryIds.toList()
                    ),
                    shopReadContext
                )
            } else {
                val bundle = businessScopedRemoteCall {
                    remote.fetchCatalogByIds(
                        supplierIds = missingSupplierIds,
                        categoryIds = missingCategoryIds,
                        productIds = emptySet(),
                        shopId = shopId
                    )
                }.getOrThrow()
                TargetedCatalogBundle(
                    bundle = bundle,
                    missingRemote =
                        bundle.suppliers.map { it.id }.toSet().containsAll(missingSupplierIds).not() ||
                            bundle.categories.map { it.id }.toSet().containsAll(missingCategoryIds).not()
                )
            }
        } else {
            TargetedCatalogBundle(
                InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()),
                missingRemote = false
            )
        }
        val missingParentRemote = parentResult.missingRemote
        val merged = mergeCatalogBundles(parentResult.bundle, first)
        val counts = applyCatalogBundleInbound(merged)
        Log.i(
            TAG,
            "sync_events_apply domain=catalog remoteSuppliers=${merged.suppliers.size} " +
                "remoteCategories=${merged.categories.size} remoteProducts=${merged.products.size} " +
                "applied=${counts.suppliers + counts.categories + counts.products}"
        )
        return counts.copy(targetedMissingRemote = directlyMissingRemote || missingParentRemote)
    }

    private suspend fun applyPriceEventByIds(
        remote: CatalogRemoteDataSource,
        priceRemote: ProductPriceRemoteDataSource,
        ids: SyncEventEntityIds,
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?,
        shopReadContext: ShopSyncRpcContext?
    ): Pair<Int, PricePullApplyResult> {
        if (ids.priceIds.isEmpty() || (shopReadContext == null && !priceRemote.isConfigured)) {
            return 0 to PricePullApplyResult(0, 0, 0)
        }
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN))
        val rows = if (shopReadContext != null) {
            val targeted = fetchShopRowsByIds(
                ShopSyncRowDomain.PRICES,
                ids.priceIds,
                shopReadContext
            )
            (targeted.rows as ShopSyncRows.Prices).values
        } else {
            businessScopedRemoteCall {
                priceRemote.fetchProductPricesByIds(ids.priceIds.toSet(), shopId)
            }.getOrThrow()
        }
        val missingProductIds = rows
            .map { it.productId }
            .filter { productRemoteRefDao.getByRemoteId(it) == null }
            .toSet()
        var targetedProductsFetched = 0
        val parentAppliedProductIds = linkedSetOf<Long>()
        if (missingProductIds.isNotEmpty()) {
            val parentProducts = if (shopReadContext != null) {
                fetchCatalogBundleViaShopRpc(
                    SyncEventEntityIds(productIds = missingProductIds.toList()),
                    shopReadContext
                ).bundle
            } else {
                businessScopedRemoteCall {
                    remote.fetchCatalogByIds(
                        supplierIds = emptySet(),
                        categoryIds = emptySet(),
                        productIds = missingProductIds,
                        shopId = shopId
                    )
                }.getOrThrow()
            }
            val parentSupplierIds = parentProducts.products
                .mapNotNull { it.supplierId }
                .filter { supplierRemoteRefDao.getByRemoteId(it) == null }
                .toSet()
            val parentCategoryIds = parentProducts.products
                .mapNotNull { it.categoryId }
                .filter { categoryRemoteRefDao.getByRemoteId(it) == null }
                .toSet()
            val parentRefs = if (parentSupplierIds.isNotEmpty() || parentCategoryIds.isNotEmpty()) {
                if (shopReadContext != null) {
                    fetchCatalogBundleViaShopRpc(
                        SyncEventEntityIds(
                            supplierIds = parentSupplierIds.toList(),
                            categoryIds = parentCategoryIds.toList()
                        ),
                        shopReadContext
                    ).bundle
                } else {
                    businessScopedRemoteCall {
                        remote.fetchCatalogByIds(
                            supplierIds = parentSupplierIds,
                            categoryIds = parentCategoryIds,
                            productIds = emptySet(),
                            shopId = shopId
                        )
                    }.getOrThrow()
                }
            } else {
                InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList())
            }
            val mergedParents = mergeCatalogBundles(parentRefs, parentProducts)
            targetedProductsFetched += mergedParents.products.size
            parentAppliedProductIds += applyCatalogBundleInbound(mergedParents).appliedProductIds
        }
        val result = applyProductPriceRows(rows, progressReporter)
        Log.i(
            TAG,
            "sync_events_apply domain=prices remotePrices=${rows.size} pricesPulled=${result.pulled} " +
                "pricesSkippedNoProductRef=${result.skippedNoLocalProduct}"
        )
        return targetedProductsFetched to result.copy(
            appliedProductIds = parentAppliedProductIds + result.appliedProductIds
        )
    }

    private suspend fun applyPriceRowsForProductIds(
        priceRemote: ProductPriceRemoteDataSource,
        productRemoteIds: Set<String>,
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?,
        shopReadContext: ShopSyncRpcContext?
    ): PricePullApplyResult {
        if (shopReadContext != null) {
            // Il contratto shop targeted e' per ID di riga; i cambi prezzo
            // arrivano come eventi prices separati e non usano query per parent ID.
            return PricePullApplyResult(0, 0, 0)
        }
        if (productRemoteIds.isEmpty() || !priceRemote.isConfigured) {
            return PricePullApplyResult(0, 0, 0)
        }
        val rows = businessScopedRemoteCall {
            priceRemote.fetchProductPricesByProductIds(productRemoteIds, shopId)
        }.getOrThrow()
        val result = applyProductPriceRows(rows, progressReporter)
        Log.i(
            TAG,
            "sync_events_apply domain=catalog_prices remoteProductIds=${productRemoteIds.size} " +
                "remotePrices=${rows.size} pricesPulled=${result.pulled} " +
                "pricesSkippedNoProductRef=${result.skippedNoLocalProduct}"
        )
        return result
    }

    private suspend fun applyHistoryEventByIds(
        sessionRemote: SessionBackupRemoteDataSource?,
        ids: SyncEventEntityIds,
        shopId: String?,
        shopReadContext: ShopSyncRpcContext?
    ): HistoryEventApplyResult {
        if (
            ids.sessionIds.isEmpty() ||
            (shopReadContext == null && (sessionRemote == null || !sessionRemote.isConfigured))
        ) {
            return HistoryEventApplyResult()
        }
        val records = if (shopReadContext != null) {
            ids.sessionIds
                .chunked(SHOP_SYNC_HISTORY_TARGETED_ID_LIMIT)
                .flatMap { chunk ->
                    val targeted = fetchShopRowsByIds(
                        ShopSyncRowDomain.HISTORY,
                        chunk,
                        shopReadContext
                    )
                    (targeted.rows as ShopSyncRows.History).values
                }
                .distinctBy { it.remoteId.lowercase() }
        } else {
            businessScopedRemoteCall {
                checkNotNull(sessionRemote).fetchSessionsByRemoteIds(ids.sessionIds.toSet(), shopId)
            }.getOrThrow()
        }
        val result = applyRemoteSessionPayloadBatch(records.map { it.toSessionRemotePayload() })
        val applied = result.inserted + result.updated
        Log.i(
            TAG,
            "sync_events_apply domain=history remoteSessions=${records.size} applied=$applied " +
                "skipped=${result.skipped} failed=${result.failed} unsupported=${result.unsupported}"
        )
        return HistoryEventApplyResult(
            remoteRows = records.size,
            appliedRows = applied,
            failedRows = result.failed,
            unsupportedRows = result.unsupported
        )
    }

    private data class HistoryEventApplyResult(
        val remoteRows: Int = 0,
        val appliedRows: Int = 0,
        val failedRows: Int = 0,
        val unsupportedRows: Int = 0
    )

    private suspend fun fetchCatalogBundleViaShopRpc(
        ids: SyncEventEntityIds,
        context: ShopSyncRpcContext
    ): TargetedCatalogBundle {
        val suppliers = if (ids.supplierIds.isEmpty()) {
            emptyList()
        } else {
            val result = fetchShopRowsByIds(ShopSyncRowDomain.SUPPLIERS, ids.supplierIds, context)
            (result.rows as ShopSyncRows.Suppliers).values
        }
        val categories = if (ids.categoryIds.isEmpty()) {
            emptyList()
        } else {
            val result = fetchShopRowsByIds(ShopSyncRowDomain.CATEGORIES, ids.categoryIds, context)
            (result.rows as ShopSyncRows.Categories).values
        }
        val products = if (ids.productIds.isEmpty()) {
            emptyList()
        } else {
            val result = fetchShopRowsByIds(ShopSyncRowDomain.PRODUCTS, ids.productIds, context)
            (result.rows as ShopSyncRows.Products).values
        }
        val missingRemote =
            suppliers.map { it.id }.toSet().containsAll(ids.supplierIds).not() ||
                categories.map { it.id }.toSet().containsAll(ids.categoryIds).not() ||
                products.map { it.id }.toSet().containsAll(ids.productIds).not()
        return TargetedCatalogBundle(
            bundle = InventoryCatalogFetchBundle(suppliers, categories, products),
            missingRemote = missingRemote
        )
    }

    private suspend fun fetchShopRowsByIds(
        domain: ShopSyncRowDomain,
        ids: List<String>,
        context: ShopSyncRpcContext
    ): ShopSyncTargetedRows {
        val reader = shopSyncReadRemoteDataSource
            ?.takeIf { it.isConfigured }
            ?: throw ShopSyncContractException("shop_sync_reader_unavailable")
        val cap = when (domain) {
            ShopSyncRowDomain.SUPPLIERS,
            ShopSyncRowDomain.CATEGORIES,
            ShopSyncRowDomain.PRODUCTS -> 60
            ShopSyncRowDomain.PRICES -> 120
            ShopSyncRowDomain.HISTORY -> 3
            ShopSyncRowDomain.IMAGES -> 240
        }
        val chunks = ids.distinct().chunked(cap)
        if (chunks.isEmpty()) {
            throw ShopSyncContractException("targeted_ids_empty")
        }
        val results = chunks.map { chunk ->
            businessScopedRemoteCall {
                reader.rowsByIds(context, domain, chunk)
            }.getOrThrow()
        }
        if (results.size == 1) return results.single()
        val first = results.first()
        if (results.any {
                it.scope != first.scope ||
                    it.asOfEventMaxId != first.asOfEventMaxId ||
                    it.minimumDomainEventMaxId != first.minimumDomainEventMaxId ||
                    it.domain != domain
            }
        ) {
            throw ShopSyncContractException("targeted_chunk_fence_changed")
        }
        val mergedRows = when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> ShopSyncRows.Suppliers(
                results.flatMap { (it.rows as ShopSyncRows.Suppliers).values }
            )
            ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(
                results.flatMap { (it.rows as ShopSyncRows.Categories).values }
            )
            ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(
                results.flatMap { (it.rows as ShopSyncRows.Products).values }
            )
            ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(
                results.flatMap { (it.rows as ShopSyncRows.Prices).values }
            )
            ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(
                results.flatMap { (it.rows as ShopSyncRows.History).values }
            )
            ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(
                results.flatMap { (it.rows as ShopSyncRows.Images).values }
            )
        }
        return first.copy(
            requestedCount = results.sumOf { it.requestedCount },
            rows = mergedRows,
            missingIds = results.flatMap { it.missingIds }
        )
    }

    private suspend fun applyProductPriceRows(
        remotes: List<InventoryProductPriceRow>,
        progressReporter: CatalogSyncProgressReporter,
        stage: CatalogSyncStage = CatalogSyncStage.SYNC_EVENTS_DRAIN,
        processedBefore: Int = 0,
        totalRows: Int? = remotes.size
    ): PricePullApplyResult {
        if (remotes.isEmpty()) {
            return PricePullApplyResult(
                pulled = 0,
                skippedNoLocalProduct = 0,
                remoteRowsEvaluated = 0
            )
        }
        var pulled = 0
        var skippedNoLocalProduct = 0
        val appliedProductIds = linkedSetOf<Long>()
        db.withTransaction {
            requireCurrentBusinessDataScope()
            fun reportProgress(index: Int) {
                if (index == 0 || index == remotes.lastIndex || (processedBefore + index + 1) % 100 == 0) {
                    progressReporter.onProgress(
                        CatalogSyncProgressState.running(
                            stage,
                            current = processedBefore + index + 1,
                            total = totalRows
                        )
                    )
                }
            }

            val knownRemoteIds = productPriceRemoteRefDao
                .getByRemoteIds(remotes.map { it.id }.distinct())
                .mapTo(hashSetOf()) { it.remoteId }
            val rowsWithoutRemoteRef = remotes.filterIndexed { index, row ->
                reportProgress(index)
                row.id !in knownRemoteIds
            }
            if (rowsWithoutRemoteRef.isEmpty()) {
                return@withTransaction
            }

            val productRefsByRemoteId = productRemoteRefDao
                .getByRemoteIds(rowsWithoutRemoteRef.map { it.productId }.distinct())
                .associateBy { it.remoteId }
            val candidates = ArrayList<ProductPriceRemoteCandidate>(rowsWithoutRemoteRef.size)
            for (row in rowsWithoutRemoteRef) {
                val pref = productRefsByRemoteId[row.productId] ?: run {
                    skippedNoLocalProduct++
                    continue
                }
                candidates += ProductPriceRemoteCandidate(row = row, localProductId = pref.productId)
            }
            if (candidates.isEmpty()) {
                return@withTransaction
            }

            val existingPricesByKey = priceDao
                .getForProducts(candidates.map { it.localProductId }.distinct())
                .associateBy { ProductPriceBusinessKey(it.productId, it.type, it.effectiveAt) }
            val existingPriceIds = existingPricesByKey.values.map { it.id }.distinct()
            val existingPriceRefsByPriceId = if (existingPriceIds.isEmpty()) {
                emptyMap()
            } else {
                existingPriceIds
                    .chunked(ROOM_QUERY_BIND_CHUNK)
                    .flatMap { productPriceRemoteRefDao.getByProductPriceIds(it) }
                    .associateBy { it.productPriceId }
            }

            val refsToInsert = mutableListOf<ProductPriceRemoteRef>()
            val newPriceRows = mutableListOf<ProductPrice>()
            val newPriceRemoteIds = mutableListOf<String>()
            for (candidate in candidates) {
                val row = candidate.row
                val key = ProductPriceBusinessKey(candidate.localProductId, row.type, row.effectiveAt)
                val existing = existingPricesByKey[key]
                if (existing != null) {
                    if (existingPriceRefsByPriceId[existing.id] == null) {
                        refsToInsert += ProductPriceRemoteRef(productPriceId = existing.id, remoteId = row.id)
                    }
                    continue
                }
                newPriceRows += ProductPrice(
                    productId = candidate.localProductId,
                    type = row.type,
                    price = row.price,
                    effectiveAt = row.effectiveAt,
                    source = row.source,
                    note = row.note,
                    createdAt = row.createdAt
                )
                newPriceRemoteIds += row.id
                appliedProductIds += candidate.localProductId
            }

            if (newPriceRows.isNotEmpty()) {
                val insertedIds = priceDao.insertAllReturningIds(newPriceRows)
                for ((index, insertedId) in insertedIds.withIndex()) {
                    if (insertedId > 0L) {
                        refsToInsert += ProductPriceRemoteRef(
                            productPriceId = insertedId,
                            remoteId = newPriceRemoteIds[index]
                        )
                    }
                }
            }

            if (refsToInsert.isNotEmpty()) {
                pulled += productPriceRemoteRefDao.insertAll(refsToInsert).count { it > 0L }
            }
        }
        return PricePullApplyResult(
            pulled = pulled,
            skippedNoLocalProduct = skippedNoLocalProduct,
            remoteRowsEvaluated = remotes.size,
            appliedProductIds = appliedProductIds
        )
    }

    /**
     * Materializza una pagina del contratto recovery esclusivamente nel DB
     * associato a questa istanza. Il coordinator usa un'istanza unmanaged su
     * un file Room temporaneo: nessuna pagina diventa quindi visibile al DB/UI
     * attivi prima del commit di attivazione.
     */
    internal suspend fun applyShopSyncRecoveryRows(
        rows: ShopSyncRows
    ): ShopSyncRecoveryStageApplyResult = when (rows) {
        is ShopSyncRows.Suppliers -> {
            val applied = applyCatalogBundleInbound(
                InventoryCatalogFetchBundle(
                    suppliers = rows.values,
                    categories = emptyList(),
                    products = emptyList(),
                    isCompleteSnapshot = false
                )
            )
            ShopSyncRecoveryStageApplyResult(applied.suppliers)
        }
        is ShopSyncRows.Categories -> {
            val applied = applyCatalogBundleInbound(
                InventoryCatalogFetchBundle(
                    suppliers = emptyList(),
                    categories = rows.values,
                    products = emptyList(),
                    isCompleteSnapshot = false
                )
            )
            ShopSyncRecoveryStageApplyResult(applied.categories)
        }
        is ShopSyncRows.Products -> {
            val applied = applyCatalogBundleInbound(
                InventoryCatalogFetchBundle(
                    suppliers = emptyList(),
                    categories = emptyList(),
                    products = rows.values,
                    isCompleteSnapshot = false
                )
            )
            ShopSyncRecoveryStageApplyResult(applied.products)
        }
        is ShopSyncRows.Prices -> {
            val applied = applyProductPriceRows(
                remotes = rows.values,
                progressReporter = CatalogSyncProgressReporter { },
                stage = CatalogSyncStage.SYNC_PRICES_PULL
            )
            ShopSyncRecoveryStageApplyResult(
                businessRowsApplied = applied.pulled,
                skippedParentRows = applied.skippedNoLocalProduct
            )
        }
        is ShopSyncRows.History -> {
            val result = applyRemoteSessionPayloadBatch(
                rows.values.map { it.toSessionRemotePayload() }
            )
            ShopSyncRecoveryStageApplyResult(
                businessRowsApplied = result.inserted + result.updated,
                failedRows = result.failed,
                unsupportedRows = result.unsupported
            )
        }
        is ShopSyncRows.Images -> ShopSyncRecoveryStageApplyResult(0)
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
        requireCurrentBusinessDataScope()
        syncEventWatermarkDao.upsert(
            SyncEventWatermark(
                ownerUserId = ownerUserId,
                storeScope = storeScope,
                lastSyncEventId = id
            )
        )
        return id
    }

    private suspend fun recordSyncEventApplyStatus(
        ownerUserId: String,
        storeScope: String,
        event: SyncEventRemoteRow,
        ids: SyncEventEntityIds?,
        status: String,
        reason: String?
    ) {
        requireCurrentBusinessDataScope()
        db.withTransaction {
            requireCurrentBusinessDataScope()
            val previous = syncEventApplyStatusDao.get(ownerUserId, storeScope, event.id)
            val repeatedRecoveryBlocker = status == SyncEventApplyStatusValues.BLOCKED &&
                reason.requiresVerifiedRecovery() &&
                previous?.status == SyncEventApplyStatusValues.BLOCKED &&
                previous.reason == reason
            val attemptCount = if (repeatedRecoveryBlocker) {
                previous.attemptCount
            } else {
                (previous?.attemptCount ?: 0) + 1
            }
            val nowMs = System.currentTimeMillis()
            val nextRetryAtMs = if (repeatedRecoveryBlocker) {
                previous.nextRetryAtMs
            } else when (status) {
                SyncEventApplyStatusValues.BLOCKED,
                SyncEventApplyStatusValues.RETRYING -> if (
                    attemptCount >= SYNC_EVENT_APPLY_MAX_ATTEMPTS
                ) {
                    null
                } else {
                    nowMs + syncRecoveryRetryDelayMs(attemptCount)
                }
                else -> null
            }
            syncEventApplyStatusDao.upsert(
                SyncEventApplyStatus(
                    ownerUserId = ownerUserId,
                    storeScope = storeScope,
                    eventId = event.id,
                    shopId = event.shopId,
                    domain = event.domain,
                    entityType = event.metadata["entity_type"]?.toString()?.trim('"'),
                    entityIdsJson = syncEventJson.encodeToString(ids ?: SyncEventEntityIds()),
                    status = status,
                    reason = reason,
                    attemptCount = attemptCount,
                    lastAttemptAtMs = nowMs,
                    nextRetryAtMs = nextRetryAtMs,
                    correlationId = event.clientEventId ?: event.batchId,
                    clientEventId = event.clientEventId,
                    remoteCreatedAt = event.createdAt
                )
            )
            if (status == SyncEventApplyStatusValues.BLOCKED && reason.requiresVerifiedRecovery()) {
                val recoveryStoreScope = Task126OwnerStoreScope.normalizedStoreId(storeScope)
                val existing = syncRecoveryJournalDao.get()
                    ?.takeIf {
                        it.ownerHash == task126OwnerHash(ownerUserId) &&
                            it.storeScope == recoveryStoreScope
                    }
                val blockingEventId = listOfNotNull(existing?.blockingEventId, event.id).minOrNull()
                val selectedReason = if (
                    existing != null && blockingEventId == existing.blockingEventId
                ) {
                    existing.reason
                } else {
                    requireNotNull(reason)
                }
                syncRecoveryJournalDao.upsert(
                    SyncRecoveryJournal(
                        ownerHash = task126OwnerHash(ownerUserId),
                        storeScope = recoveryStoreScope,
                        shopId = shopIdFromStoreScope(storeScope),
                        deviceId = checkNotNull(syncEventDeviceStateDao.get()?.deviceId) {
                            "sync_recovery_device_identity_missing"
                        },
                        authorizationMode = existing?.authorizationMode
                            ?: SyncRecoveryAuthorizationModes.SAME_SCOPE,
                        runId = existing?.runId,
                        phase = existing?.phase ?: SyncRecoveryJournalPhases.REQUIRED,
                        reason = selectedReason,
                        blockingEventId = blockingEventId,
                        // Il budget appartiene ai tentativi di snapshot, non alle
                        // ri-osservazioni dello stesso evento bloccante.
                        attemptCount = existing?.attemptCount ?: 0,
                        createdAtMs = existing?.createdAtMs ?: nowMs,
                        updatedAtMs = nowMs,
                        nextRetryAtMs = existing?.nextRetryAtMs ?: nowMs,
                        checkpointADigest = existing?.checkpointADigest,
                        checkpointBDigest = existing?.checkpointBDigest,
                        stagingDatabaseName = existing?.stagingDatabaseName
                    )
                )
            }
            requireCurrentBusinessDataScope()
        }
    }

    /**
     * The V6 reader cannot safely resume a non-zero watermark without the
     * opaque baseline scope. There is no event row to attach in this branch,
     * so persist the same recovery latch explicitly instead of silently
     * returning no work.
     */
    private suspend fun recordShopSyncRecoveryRequiredWithoutEvent(
        ownerUserId: String,
        storeScope: String,
        shopId: String,
        deviceId: String,
        reason: String,
        blockingEventId: Long? = null
    ) {
        requireCurrentBusinessDataScope()
        db.withTransaction {
            requireCurrentBusinessDataScope()
            val recoveryStoreScope = Task126OwnerStoreScope.normalizedStoreId(storeScope)
            val existing = syncRecoveryJournalDao.get()
                ?.takeIf {
                    it.ownerHash == task126OwnerHash(ownerUserId) &&
                        it.storeScope == recoveryStoreScope
                }
            val nowMs = System.currentTimeMillis()
            syncRecoveryJournalDao.upsert(
                SyncRecoveryJournal(
                    ownerHash = task126OwnerHash(ownerUserId),
                    storeScope = recoveryStoreScope,
                    shopId = shopId,
                    deviceId = deviceId,
                    authorizationMode = existing?.authorizationMode
                        ?: SyncRecoveryAuthorizationModes.SAME_SCOPE,
                    runId = existing?.runId,
                    phase = existing?.phase ?: SyncRecoveryJournalPhases.REQUIRED,
                    reason = existing?.reason ?: reason,
                    blockingEventId = listOfNotNull(existing?.blockingEventId, blockingEventId)
                        .minOrNull(),
                    attemptCount = existing?.attemptCount ?: 0,
                    createdAtMs = existing?.createdAtMs ?: nowMs,
                    updatedAtMs = nowMs,
                    nextRetryAtMs = existing?.nextRetryAtMs ?: nowMs,
                    checkpointADigest = existing?.checkpointADigest,
                    checkpointBDigest = existing?.checkpointBDigest,
                    stagingDatabaseName = existing?.stagingDatabaseName
                )
            )
            requireCurrentBusinessDataScope()
        }
    }

    private suspend fun shopSyncBaselineForEventDrain(
        ownerUserId: String,
        storeScope: String,
        shopId: String,
        deviceId: String,
        watermark: Long
    ): ShopSyncRecoveryCheckpoint? {
        if (watermark == 0L) return null
        val baseline = syncRecoveryBaselineDao.get() ?: return null
        if (
            baseline.ownerHash != task126OwnerHash(ownerUserId) ||
            baseline.storeScope != Task126OwnerStoreScope.normalizedStoreId(storeScope) ||
            baseline.shopId.lowercase() != shopId.lowercase() ||
            baseline.deviceId != deviceId
        ) {
            return null
        }
        val checkpoint = runCatching { decodeRecoveryCheckpointJson(baseline.checkpointJson) }
            .getOrNull() ?: return null
        if (
            checkpoint.scope.key != baseline.scopeKey ||
            checkpoint.syncEvents.maxId != watermark.toString() ||
            // A persisted recovery baseline must be the C receipt obtained
            // after marker(B): it is self-verifying at the activated
            // watermark. Raw checkpoint B used A as its query baseline and
            // would otherwise re-latch recovery on every next drain.
            checkpoint.syncEvents.verifiedBaselineId != watermark.toString()
        ) {
            return null
        }
        return checkpoint
    }

    private fun shopSyncTargetedContext(
        ownerUserId: String,
        shopId: String,
        deviceId: String,
        scope: ShopSyncScope,
        eventMaxId: String,
        domainEventMaxIds: Map<String, String>,
        domain: String
    ): ShopSyncRpcContext = ShopSyncRpcContext(
        accountId = ownerUserId,
        shopId = shopId,
        deviceIdentifier = deviceId,
        expectedScope = scope,
        expectedEventMaxId = eventMaxId,
        expectedDomainEventMaxId = domainEventMaxIds[domain]
            ?: throw ShopSyncContractException("sync_event_domain_fence_missing")
    )

    private fun syncEventDeviceKey(deviceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun String?.requiresVerifiedRecovery(): Boolean = this in setOf(
        SyncEventApplyStatusReasons.MISSING_ENTITY_IDS,
        SyncEventApplyStatusReasons.ENTITY_IDS_TOO_LARGE,
        SyncEventApplyStatusReasons.MISSING_REMOTE,
        SyncEventApplyStatusReasons.UNSUPPORTED_DOMAIN,
        SyncEventApplyStatusReasons.UNSUPPORTED_EVENT_TYPE,
        SyncEventApplyStatusReasons.SCOPE_MISMATCH,
        SyncEventApplyStatusReasons.DRAIN_LIMIT_REACHED,
        SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED
    )

    private fun markerProvesNoWorkAgainstBaseline(
        marker: ShopSyncConvergenceMarker?,
        baseline: ShopSyncRecoveryCheckpoint?,
        watermark: Long
    ): Boolean {
        val expected = baseline ?: return false
        val expectedWatermark = watermark.toString()
        return marker != null &&
            marker.status == "ready" &&
            marker.serverNoWorkEligible &&
            marker.shopId == expected.shopId &&
            marker.scope == expected.scope &&
            marker.syncEvents.maxId == expectedWatermark &&
            marker.syncEvents.verifiedBaselineId == expectedWatermark &&
            marker.syncEvents.domainMaxIds == expected.syncEvents.domainMaxIds &&
            marker.checkpointDigest == expected.checkpointDigest &&
            !marker.syncEvents.requiresFullRecovery &&
            marker.catalog == expected.catalog &&
            marker.prices == expected.prices &&
            marker.history == expected.history &&
            marker.images == expected.images &&
            marker.integrity.totalViolationCount == 0L
    }

    private fun syncRecoveryRetryDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (SYNC_RECOVERY_RETRY_BASE_MS * (1L shl exponent))
            .coerceAtMost(SYNC_RECOVERY_RETRY_MAX_MS)
    }

    private suspend fun retrySyncEventOutbox(
        remote: SyncEventRemoteDataSource,
        ownerUserId: String,
        storeScope: String
    ): RetryOutboxResult {
        val pendingBefore = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
        val skippedMaxAttempts = syncEventOutboxDao.countPendingAtOrAboveAttemptsForScope(
            ownerUserId,
            storeScope,
            SYNC_EVENT_OUTBOX_MAX_ATTEMPTS
        )
        val pending = syncEventOutboxDao.listPendingRetryableForScope(
            ownerUserId,
            storeScope,
            SYNC_EVENT_OUTBOX_MAX_ATTEMPTS,
            SYNC_EVENT_OUTBOX_RETRY_LIMIT
        )
        var retryEligible = 0
        var retrySucceeded = 0
        var retryFailed = 0
        var retryDeletedOnSuccess = 0
        for (entry in pending) {
            if (entry.attemptCount >= SYNC_EVENT_OUTBOX_MAX_ATTEMPTS) continue
            retryEligible++
            val ids = syncEventJson.decodeFromString<SyncEventEntityIds>(entry.entityIdsJson)
            if (
                !SyncEventContract.hasCompletePrimaryIds(
                    domain = entry.domain,
                    changedCount = entry.changedCount,
                    ids = ids
                )
            ) {
                val nextAttemptCount = entry.attemptCount + 1
                val errorType = SyncEventApplyStatusReasons.MISSING_ENTITY_IDS
                requireCurrentBusinessDataScope()
                syncEventOutboxDao.update(
                    entry.copy(
                        attemptCount = nextAttemptCount,
                        lastAttemptAtMs = System.currentTimeMillis(),
                        lastErrorType = errorType
                    )
                )
                retryFailed++
                logSyncEventOutboxRetryEntry(
                    outcome = "rejected_invalid_entity_ids",
                    entry = entry,
                    lastErrorType = errorType,
                    attemptCount = nextAttemptCount,
                    retryDeletedOnSuccess = 0
                )
                continue
            }
            val params = SyncEventRecordRpcParams(
                domain = entry.domain,
                eventType = entry.eventType,
                changedCount = entry.changedCount,
                entityIds = ids,
                storeId = remoteStoreIdFromStoreScope(entry.storeScope),
                source = entry.source,
                sourceDeviceId = entry.sourceDeviceId,
                batchId = entry.batchId,
                clientEventId = entry.clientEventId,
                metadata = buildJsonObject { put("task", "045") },
                shopId = shopIdFromStoreScope(entry.storeScope)
            )
            val result = businessScopedRemoteCall { remote.recordSyncEvent(params) }
            if (result.isSuccess) {
                requireCurrentBusinessDataScope()
                syncEventOutboxDao.deleteById(entry.id)
                retrySucceeded++
                retryDeletedOnSuccess++
                logSyncEventOutboxRetryEntry(
                    outcome = "success",
                    entry = entry,
                    lastErrorType = entry.lastErrorType,
                    attemptCount = entry.attemptCount,
                    retryDeletedOnSuccess = 1
                )
            } else {
                requireCurrentBusinessDataScope()
                val errorType = result.exceptionOrNull()?.let { SyncErrorClassifier.classify(it).category.name }
                    ?: "unknown"
                val nextAttemptCount = entry.attemptCount + 1
                requireCurrentBusinessDataScope()
                syncEventOutboxDao.update(
                    entry.copy(
                        attemptCount = nextAttemptCount,
                        lastAttemptAtMs = System.currentTimeMillis(),
                        lastErrorType = errorType
                    )
                )
                retryFailed++
                logSyncEventOutboxRetryEntry(
                    outcome = "failure",
                    entry = entry,
                    lastErrorType = errorType,
                    attemptCount = nextAttemptCount,
                    retryDeletedOnSuccess = 0
                )
            }
        }
        val pendingAfter = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
        val result = RetryOutboxResult(
            pendingBefore = pendingBefore,
            pendingAfter = pendingAfter,
            retryLoaded = pending.size,
            retryEligible = retryEligible,
            retrySkippedMaxAttempts = skippedMaxAttempts,
            retrySucceeded = retrySucceeded,
            retryFailed = retryFailed,
            retryDeletedOnSuccess = retryDeletedOnSuccess
        )
        Log.i(
            TAG,
            "sync_event_outbox_retry_summary " +
                "pendingBefore=${result.pendingBefore} pendingAfter=${result.pendingAfter} " +
                "retryLoaded=${result.retryLoaded} retryEligible=${result.retryEligible} " +
                "retrySkippedMaxAttempts=${result.retrySkippedMaxAttempts} " +
                "retrySucceeded=${result.retrySucceeded} retryFailed=${result.retryFailed} " +
                "retryDeletedOnSuccess=${result.retryDeletedOnSuccess}"
        )
        return result
    }

    private suspend fun recordOrEnqueueSyncEvent(
        remote: SyncEventRemoteDataSource,
        ownerUserId: String,
        storeScope: String,
        ids: SyncEventEntityIds,
        domain: String,
        eventType: String,
        batchId: String,
        deviceId: String,
        shopId: String? = null
    ): SyncEventRecordOutcome {
        val totalChangedCount = SyncEventContract.primaryChangedCount(domain, ids)
        if (ids.isEmpty && totalChangedCount <= 0) {
            val outcome = SyncEventRecordOutcome.NoOp
            logSyncEventRecordOutcome(
                domain = domain,
                eventType = eventType,
                totalChangedCount = totalChangedCount,
                outcome = outcome
            )
            return outcome
        }
        var recordedChunks = 0
        var enqueuedChunks = 0
        var outboxInserted = 0
        val chunks = if (ids.isEmpty) listOf(ids) else SyncEventContract.chunkPrimaryIds(domain, ids)
        for ((index, chunk) in chunks.withIndex()) {
            val clientEventId = buildClientEventId(batchId, domain, eventType, chunk, index)
            val chunkChangedCount = SyncEventContract.primaryChangedCount(domain, chunk)
            check(SyncEventContract.hasCompletePrimaryIds(domain, chunkChangedCount, chunk)) {
                "sync_event_chunk_invalid_primary_ids"
            }
            val metadata = buildJsonObject {
                put("task", "045")
                put("source", "android_repository")
                put("chunk_index", index)
                put("chunk_count", chunks.size)
                put("entity_ids_compacted", false)
            }
            val params = SyncEventRecordRpcParams(
                domain = domain,
                eventType = eventType,
                changedCount = chunkChangedCount,
                entityIds = chunk,
                storeId = remoteStoreIdFromStoreScope(storeScope),
                source = "android",
                sourceDeviceId = deviceId,
                batchId = batchId,
                clientEventId = clientEventId,
                metadata = metadata,
                shopId = shopId
            )
            val result = businessScopedRemoteCall { remote.recordSyncEvent(params) }
            if (result.isSuccess) {
                recordedChunks++
                continue
            }
            enqueuedChunks++
            requireCurrentBusinessDataScope()
            val errorType = result.exceptionOrNull()?.let { SyncErrorClassifier.classify(it).category.name }
                ?: "unknown"
            val insertId = syncEventOutboxDao.insert(
                SyncEventOutboxEntry(
                    ownerUserId = ownerUserId,
                    storeScope = storeScope,
                    domain = domain,
                    eventType = eventType,
                    source = "android",
                    sourceDeviceId = deviceId,
                    batchId = batchId,
                    clientEventId = clientEventId,
                    changedCount = chunkChangedCount,
                    entityIdsJson = syncEventJson.encodeToString(chunk),
                    metadataJson = syncEventJson.encodeToString(metadata),
                    createdAtMs = System.currentTimeMillis(),
                    lastAttemptAtMs = System.currentTimeMillis(),
                    lastErrorType = errorType
                )
            )
            val inserted = insertId != -1L
            if (inserted) outboxInserted++
            Log.w(
                TAG,
                "sync_event_outbox_enqueue " +
                    "eventType=$eventType domain=$domain outboxInserted=${if (inserted) 1 else 0} " +
                    "lastErrorType=$errorType attemptCount=0 " +
                    "clientEventIdHash=${clientEventIdHash(clientEventId)} " +
                    "changedCount=$chunkChangedCount entityIdsCompacted=false"
            )
        }
        val outcome = SyncEventRecordOutcome.from(
            attemptedChunks = chunks.size,
            recordedChunks = recordedChunks,
            enqueuedChunks = enqueuedChunks,
            outboxInserted = outboxInserted
        )
        logSyncEventRecordOutcome(
            domain = domain,
            eventType = eventType,
            totalChangedCount = totalChangedCount,
            outcome = outcome
        )
        return outcome
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
            ids.priceIds.sorted().joinToString(","),
            ids.sessionIds.sorted().joinToString(",")
        ).joinToString("|").hashCode().toUInt().toString(16)
        return "android-$batchId-$domain-$eventType-$chunkIndex-$fingerprint"
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
        for (id in ids.sessionIds) {
            val ref = remoteRefDao.getByRemoteId(canonicalSessionRemoteId(id))
            if (ref != null && ref.localChangeRevision > ref.lastSyncedLocalRevision) dirty++
        }
        return dirty
    }

    private fun logSyncEventSummary(
        phase: String,
        capabilities: SyncEventRemoteCapabilities,
        outboxPending: Int,
        retryOutboxResult: RetryOutboxResult,
        drain: SyncEventDrainResult,
        catalogEventOutcome: SyncEventRecordOutcome,
        priceEventOutcome: SyncEventRecordOutcome
    ) {
        val outboxInserted = catalogEventOutcome.outboxInserted + priceEventOutcome.outboxInserted
        Log.i(
            TAG,
            "sync_events_summary phase=$phase " +
                "syncEventsAvailable=${capabilities.syncEventsAvailable} " +
                "recordSyncEventAvailable=${capabilities.recordSyncEventAvailable} " +
                "realtimeSyncEventsAvailable=${capabilities.realtimeSyncEventsAvailable} " +
                "syncEventOutboxPending=$outboxPending " +
                "syncEventOutboxPendingBefore=${retryOutboxResult.pendingBefore} " +
                "syncEventOutboxPendingAfter=$outboxPending " +
                "syncEventOutboxRetried=${retryOutboxResult.outboxRetried} " +
                "syncEventOutboxRetryLoaded=${retryOutboxResult.retryLoaded} " +
                "syncEventOutboxRetryEligible=${retryOutboxResult.retryEligible} " +
                "syncEventOutboxRetrySkippedMaxAttempts=${retryOutboxResult.retrySkippedMaxAttempts} " +
                "syncEventOutboxRetrySucceeded=${retryOutboxResult.retrySucceeded} " +
                "syncEventOutboxRetryFailed=${retryOutboxResult.retryFailed} " +
                "syncEventOutboxRetryDeletedOnSuccess=${retryOutboxResult.retryDeletedOnSuccess} " +
                "syncEventOutboxInserted=$outboxInserted " +
                "catalogEventEmitted=${catalogEventOutcome.recordedFully} " +
                "priceEventEmitted=${priceEventOutcome.recordedFully} " +
                "catalogEventOutcome=${catalogEventOutcome.logName} " +
                "priceEventOutcome=${priceEventOutcome.logName} " +
                "syncEventsFetched=${drain.fetched} syncEventsProcessed=${drain.processed} " +
                "syncEventsSkippedSelf=${drain.skippedSelf} " +
                "syncEventsSkippedDirtyLocal=${drain.skippedDirtyLocal} " +
                "syncEventsSkippedProtectedLocalCommit=${drain.skippedProtectedLocalCommit} " +
                "syncEventsWatermarkBefore=${drain.watermarkBefore} " +
                "syncEventsWatermarkAfter=${drain.watermarkAfter} " +
                "syncEventsTooLarge=${drain.tooLarge} syncEventsGapDetected=${drain.gapDetected} " +
                "manualFullSyncRequired=${drain.manualFullSyncRequired} " +
                "targetedProductsFetched=${drain.targetedProductsFetched} " +
                "targetedPricesFetched=${drain.targetedPricesFetched} " +
                "fullCatalogFetch=false fullPriceFetch=false"
        )
    }

    private fun logSyncEventRecordOutcome(
        domain: String,
        eventType: String,
        totalChangedCount: Int,
        outcome: SyncEventRecordOutcome
    ) {
        Log.i(
            TAG,
            "sync_event_record_outcome " +
                "domain=$domain eventType=$eventType outcome=${outcome.logName} " +
                "attemptedChunks=${outcome.attemptedChunks} recordedChunks=${outcome.recordedChunks} " +
                "enqueuedChunks=${outcome.enqueuedChunks} outboxInserted=${outcome.outboxInserted} " +
                "changedCount=$totalChangedCount entityIdsCompacted=false"
        )
    }

    private fun logSyncEventOutboxRetryEntry(
        outcome: String,
        entry: SyncEventOutboxEntry,
        lastErrorType: String?,
        attemptCount: Int,
        retryDeletedOnSuccess: Int
    ) {
        Log.i(
            TAG,
            "sync_event_outbox_retry_entry " +
                "outcome=$outcome eventType=${entry.eventType} domain=${entry.domain} " +
                "lastErrorType=${lastErrorType ?: "none"} attemptCount=$attemptCount " +
                "clientEventIdHash=${clientEventIdHash(entry.clientEventId)} " +
                "retryDeletedOnSuccess=$retryDeletedOnSuccess"
        )
    }

    private fun clientEventIdHash(clientEventId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clientEventId.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val TAG = "CatalogCloudSync"
        const val HISTORY_SESSION_SYNC_TAG = "HistorySessionSyncV2"
        const val PRODUCT_BULK_PUSH_ENABLED = true
        const val PRODUCT_BULK_PUSH_CHUNK = 100
        const val PRODUCT_BULK_PUSH_FALLBACK_50 = 50
        const val PRODUCT_BULK_PUSH_FALLBACK_25 = 25
        const val PRODUCT_PRICE_PUSH_CHUNK = 80
        const val ROOM_QUERY_BIND_CHUNK = 900
        const val IMPORT_DIRTY_PRICE_TOLERANCE = 0.001
        const val SYNC_EVENT_FETCH_LIMIT = 100L
        const val SYNC_EVENT_DRAIN_MAX_ITERATIONS = 20
        const val SYNC_EVENT_ENTITY_ID_BUDGET = SyncEventContract.MAX_PRIMARY_ENTITY_IDS_PER_EVENT
        const val SYNC_EVENT_OUTBOX_RETRY_LIMIT = 20
        const val SYNC_EVENT_OUTBOX_MAX_ATTEMPTS = 5
        const val SYNC_EVENT_APPLY_MAX_ATTEMPTS = 5
        // Contratto V6: history targeted massimo tre ID per chiamata.
        const val SHOP_SYNC_HISTORY_TARGETED_ID_LIMIT = 3
        const val SYNC_RECOVERY_RETRY_BASE_MS = 30_000L
        const val SYNC_RECOVERY_RETRY_MAX_MS = 15 * 60_000L
        const val SESSION_BACKUP_PUSH_CHUNK = 80
        const val LOG_SAMPLE_LIMIT = 5
        const val POSTGREST_UNIQUE_VIOLATION = "23505"
        const val POSTGREST_FOREIGN_KEY_VIOLATION = "23503"
        val COMBINING_MARKS = Regex("\\p{Mn}+")
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

    private inner class CatalogConflictRecoveryCache(
        private val allowRemoteFetch: Boolean = true
    ) {
        private var bundle: InventoryCatalogFetchBundle? = null

        suspend fun fetch(
            remote: CatalogRemoteDataSource,
            shopId: String?,
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
                val result = businessScopedRemoteCall { remote.fetchCatalog(shopId) }
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

    private fun Throwable.isPostgrestForeignKeyViolationConflict(): Boolean {
        val classification = SyncErrorClassifier.classify(this)
        if (classification.httpStatus == 409 &&
            classification.postgrestCode == POSTGREST_FOREIGN_KEY_VIOLATION
        ) {
            return true
        }
        val text = causeChainText()
        return text.contains(POSTGREST_FOREIGN_KEY_VIOLATION) &&
            (text.contains("409") || text.contains("foreign key"))
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
        shopId: String? = null,
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
                    "pricesEvaluated=${candidates.size} pricesPushed=0 requireProductSynced=$requireProductSynced " +
                    "batchSize=$PRODUCT_PRICE_PUSH_CHUNK batchCount=0 avgBatchMs=0"
            )
            return ProductPricePushResult(count = 0, remoteIds = emptyList())
        }
        var pushed = 0
        var processed = 0
        var batchCount = 0
        var totalBatchMs = 0L
        var skippedForeignKey = 0
        val pushedRemoteIds = mutableListOf<String>()
        for (chunk in rows.chunked(PRODUCT_PRICE_PUSH_CHUNK)) {
            val pairs = chunk.map { r ->
                val rid = r.existingPriceRemoteId ?: java.util.UUID.randomUUID().toString()
                r to rid
            }
            val upsertRows = pairs.map { (r, rid) -> buildProductPricePushRow(r, rid, ownerUserId, shopId) }
            val batchStartedAt = System.currentTimeMillis()
            val result = businessScopedRemoteCall {
                priceRemote.upsertProductPrices(upsertRows, shopId)
            }
            totalBatchMs += System.currentTimeMillis() - batchStartedAt
            batchCount++
            val firstError = result.exceptionOrNull()
            if (firstError == null) {
                markProductPricePushApplied(pairs)
                pushed += chunk.size
                pushedRemoteIds += pairs.map { it.second }
                processed += chunk.size
            } else if (firstError.isPostgrestForeignKeyViolationConflict()) {
                Log.w(
                    TAG,
                    "price_push_batch_fk_fallback rows=${chunk.size} requireProductSynced=$requireProductSynced"
                )
                for ((r, rid) in pairs) {
                    val row = buildProductPricePushRow(r, rid, ownerUserId, shopId)
                    val singleStartedAt = System.currentTimeMillis()
                    val single = businessScopedRemoteCall {
                        priceRemote.upsertProductPrices(listOf(row), shopId)
                    }
                    totalBatchMs += System.currentTimeMillis() - singleStartedAt
                    batchCount++
                    val singleError = single.exceptionOrNull()
                    if (singleError == null) {
                        markProductPricePushApplied(listOf(r to rid))
                        pushed++
                        pushedRemoteIds += rid
                    } else if (singleError.isPostgrestForeignKeyViolationConflict()) {
                        skippedForeignKey++
                        Log.w(
                            TAG,
                            "price_push_skip_fk requireProductSynced=$requireProductSynced"
                        )
                    } else {
                        throw singleError
                    }
                    processed++
                    progressReporter.onProgress(
                        CatalogSyncProgressState.running(
                            CatalogSyncStage.SYNC_PRICES_PUSH,
                            current = processed,
                            total = rows.size
                        )
                    )
                }
            } else {
                throw firstError
            }
            progressReporter.onProgress(
                CatalogSyncProgressState.running(
                    CatalogSyncStage.SYNC_PRICES_PUSH,
                    current = processed,
                    total = rows.size
                )
            )
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=PRICES phase=SYNC_PRICES_PUSH " +
                "pricesEvaluated=${candidates.size} pricesEligible=${rows.size} pricesPushed=$pushed " +
                "pricesSkippedForeignKey=$skippedForeignKey requireProductSynced=$requireProductSynced " +
                "batchSize=$PRODUCT_PRICE_PUSH_CHUNK " +
                "batchCount=$batchCount avgBatchMs=${if (batchCount == 0) 0 else totalBatchMs / batchCount}"
        )
        return ProductPricePushResult(
            count = pushed,
            remoteIds = pushedRemoteIds.distinct(),
            skippedForeignKey = skippedForeignKey
        )
    }

    private fun buildProductPricePushRow(
        row: ProductPricePushRow,
        remoteId: String,
        ownerUserId: String,
        shopId: String? = null
    ): InventoryProductPriceRow =
        InventoryProductPriceRow(
            id = remoteId,
            ownerUserId = ownerUserId,
            shopId = shopId,
            productId = row.productRemoteId,
            type = row.type,
            price = row.price,
            effectiveAt = row.effectiveAt,
            source = row.source,
            note = row.note,
            createdAt = row.createdAt
        )

    private suspend fun markProductPricePushApplied(
        pairs: List<Pair<ProductPricePushRow, String>>
    ) {
        db.withTransaction {
            requireCurrentBusinessDataScope()
            for ((row, remoteId) in pairs) {
                if (row.existingPriceRemoteId == null) {
                    productPriceRemoteRefDao.insert(
                        ProductPriceRemoteRef(productPriceId = row.id, remoteId = remoteId)
                    )
                }
            }
        }
    }

    /**
     * Pull idempotente: dedup su `(productId,type,effectiveAt)` e su `remoteId`; nessun `insertIfChanged`;
     * non aggiorna `products.purchasePrice` / `retailPrice`.
     */
    private suspend fun pullProductPricesFromRemote(
        priceRemote: ProductPriceRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter,
        useFullRemoteFetch: Boolean = false,
        shopId: String? = null
    ): PricePullApplyResult {
        var pulled = 0
        var skippedNoLocalProduct = 0
        var remoteRowsEvaluated = 0
        var pageCount = 0
        var lastRemoteId: String? = null
        val appliedProductIds = linkedSetOf<Long>()

        while (true) {
            val page = businessScopedRemoteCall {
                priceRemote.fetchProductPricesPage(lastRemoteId, INVENTORY_REMOTE_PAGE_SIZE, shopId)
            }.getOrThrow()
            if (page.isEmpty()) break

            pageCount++
            val pageResult = applyProductPriceRows(
                page,
                progressReporter,
                stage = CatalogSyncStage.SYNC_PRICES_PULL,
                processedBefore = remoteRowsEvaluated,
                totalRows = null
            )
            pulled += pageResult.pulled
            skippedNoLocalProduct += pageResult.skippedNoLocalProduct
            remoteRowsEvaluated += pageResult.remoteRowsEvaluated
            appliedProductIds += pageResult.appliedProductIds
            lastRemoteId = page.last().id

            if (page.size.toLong() < INVENTORY_REMOTE_PAGE_SIZE) break
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=PRICES phase=SYNC_PRICES_PULL " +
                "mode=${if (useFullRemoteFetch) "full_fetch_paged" else "paged"} " +
                "remotePricesEvaluated=$remoteRowsEvaluated pricesPulled=$pulled " +
                "pricesSkippedNoProductRef=$skippedNoLocalProduct " +
                "pageSize=$INVENTORY_REMOTE_PAGE_SIZE pageCount=$pageCount"
        )
        return PricePullApplyResult(
            pulled = pulled,
            skippedNoLocalProduct = skippedNoLocalProduct,
            remoteRowsEvaluated = remoteRowsEvaluated,
            appliedProductIds = appliedProductIds
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

    private suspend fun touchProductDirty(productId: Long, changedFields: Set<String>? = null) {
        val ref = productRemoteRefDao.getByProductId(productId)
        if (ref == null) {
            productRemoteRefDao.insert(
                ProductRemoteRef(
                    productId = productId,
                    remoteId = java.util.UUID.randomUUID().toString()
                )
            )
        } else {
            val encoded = encodeProductChangedFields(changedFields)
            if (encoded == null) {
                productRemoteRefDao.incrementLocalRevision(productId)
            } else {
                val existingChangedFields =
                    if (
                        ref.localChangedFields == null &&
                        ref.localChangeRevision > ref.lastSyncedLocalRevision
                    ) {
                        "__all__"
                    } else {
                        ref.localChangedFields
                    }
                productRemoteRefDao.markLocalChanged(
                    productId,
                    mergeEncodedProductChangedFields(existingChangedFields, encoded)
                )
            }
        }
    }

    private fun productChangedFields(old: Product, new: Product): Set<String> =
        buildSet {
            if (old.barcode != new.barcode) add("barcode")
            if (old.itemNumber != new.itemNumber) add("itemnumber")
            if (old.productName != new.productName) add("productname")
            if (old.secondProductName != new.secondProductName) add("secondproductname")
            if (!nullableDoubleEquals(old.purchasePrice, new.purchasePrice)) add("purchaseprice")
            if (!nullableDoubleEquals(old.retailPrice, new.retailPrice)) add("retailprice")
            if (old.supplierId != new.supplierId) add("supplier")
            if (old.categoryId != new.categoryId) add("category")
            if (!nullableDoubleEquals(old.stockQuantity, new.stockQuantity)) add("stockquantity")
        }

    private fun nullableDoubleEquals(lhs: Double?, rhs: Double?): Boolean =
        when {
            lhs == null && rhs == null -> true
            lhs == null || rhs == null -> false
            else -> abs(lhs - rhs) <= IMPORT_DIRTY_PRICE_TOLERANCE
        }

    private fun encodeProductChangedFields(fields: Set<String>?): String? {
        val normalized = fields
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        if (normalized.isEmpty()) return null
        if ("__all__" in normalized) return "__all__"
        return normalized.sorted().joinToString(",")
    }

    private fun mergeEncodedProductChangedFields(existingRaw: String?, nextEncoded: String): String {
        val existing = decodeProductChangedFields(existingRaw)
        val next = decodeProductChangedFields(nextEncoded)
        if ("__all__" in existing || "__all__" in next) return "__all__"
        return encodeProductChangedFields(existing + next) ?: nextEncoded
    }

    private fun decodeProductChangedFields(raw: String?): Set<String> =
        raw
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private suspend fun ensureProductRefForPricePushIfMissing(productId: Long): Boolean {
        if (productRemoteRefDao.getByProductId(productId) != null) return false
        productRemoteRefDao.insert(
            ProductRemoteRef(
                productId = productId,
                remoteId = java.util.UUID.randomUUID().toString()
            )
        )
        return productRemoteRefDao.getByProductId(productId) != null
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
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?
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
            val s = canonicalizeSupplierForCatalogPush(candidate.supplier)
            val ref = candidate.remoteRef ?: ensureSupplierRefForPush(s.id)
            if (supplierNeedsPush(ref)) {
                dirty++
                if (pushCatalogSupplierRow(remote, ownerUserId, s, ref, recoveryCache, shopId)) {
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
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?
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
            val c = canonicalizeCategoryForCatalogPush(candidate.category)
            val ref = candidate.remoteRef ?: ensureCategoryRefForPush(c.id)
            if (categoryNeedsPush(ref)) {
                dirty++
                if (pushCatalogCategoryRow(remote, ownerUserId, c, ref, recoveryCache, shopId)) {
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
        shopId: String?,
        allowCreatingDependencyRefs: Boolean = true
    ): CatalogEntityPushResult {
        var dirty = 0
        var skippedMissingDependencyRef = 0
        var skippedAlreadySynced = 0
        val productTotal = productDao.count()
        val candidates = productDao.getCatalogPushCandidates()
        val prepared = mutableListOf<ProductPushCandidatePrepared>()
        val accumulator = ProductPushBatchAccumulator()
        progressReporter.onProgress(
            CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS, current = 0, total = candidates.size)
        )
        for (candidate in candidates) {
            val p = canonicalizeProductForCatalogPush(candidate.product)
            val productForPush = p.copy(
                purchasePrice = candidate.lastPurchase ?: p.purchasePrice,
                retailPrice = candidate.lastRetail ?: p.retailPrice
            )
            val ref = productRemoteRefDao.getByProductId(p.id) ?: ensureProductRefForPush(p.id)
            if (productNeedsPush(ref)) {
                dirty++
                if (canPatchProduct(ref)) {
                    val patch = buildProductPatch(
                        product = productForPush,
                        ref = ref,
                        allowCreatingDependencyRefs = allowCreatingDependencyRefs
                    )
                    if (patch == null) {
                        skippedMissingDependencyRef++
                    } else if (!patch.isEmpty) {
                        val startedAt = System.currentTimeMillis()
                        businessScopedRemoteCall {
                            remote.patchProduct(
                                CatalogTextCanonicalizer.remoteId(ref.remoteId),
                                CatalogTextCanonicalizer.remoteId(ownerUserId),
                                CatalogTextCanonicalizer.optionalRemoteId(shopId),
                                patch
                            )
                        }.getOrThrow()
                        accumulator.totalBatchMs += System.currentTimeMillis() - startedAt
                        accumulator.batchCount++
                        markProductPatchApplied(productForPush.id, ref, patch)
                        accumulator.pushed++
                        accumulator.remoteIds += ref.remoteId
                    }
                    accumulator.completed++
                    reportProductPushProgress(progressReporter, accumulator.completed, candidates.size)
                } else {
                    val row = buildProductPushRow(
                        product = productForPush,
                        ref = ref,
                        ownerUserId = ownerUserId,
                        shopId = shopId,
                        allowCreatingDependencyRefs = allowCreatingDependencyRefs
                    )
                    if (row == null) {
                        skippedMissingDependencyRef++
                        accumulator.completed++
                        reportProductPushProgress(progressReporter, accumulator.completed, candidates.size)
                    } else {
                        prepared += ProductPushCandidatePrepared(
                            product = productForPush,
                            ref = ref,
                            row = row
                        )
                    }
                }
            } else {
                skippedAlreadySynced++
                accumulator.completed++
                reportProductPushProgress(progressReporter, accumulator.completed, candidates.size)
            }
        }
        if (prepared.isNotEmpty()) {
            if (PRODUCT_BULK_PUSH_ENABLED) {
                pushPreparedProductBatches(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    total = candidates.size,
                    allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                    shopId = shopId,
                    prepared = prepared,
                    accumulator = accumulator
                )
            } else {
                pushPreparedProductsOneByOne(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    total = candidates.size,
                    allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                    shopId = shopId,
                    prepared = prepared,
                    accumulator = accumulator
                )
            }
        }
        Log.i(
            TAG,
            "phase_metrics syncDomain=CATALOG phase=PUSH_PRODUCTS productsTotal=$productTotal " +
                "productsEvaluated=${candidates.size} productsDirty=$dirty productsPushed=${accumulator.pushed} " +
                "productsPrepared=${prepared.size} " +
                "productsSkippedAlreadySynced=${(productTotal - candidates.size + skippedAlreadySynced).coerceAtLeast(0)} " +
                "productsSkippedMissingDependencyRef=$skippedMissingDependencyRef " +
                "bulkEnabled=$PRODUCT_BULK_PUSH_ENABLED batchSize=$PRODUCT_BULK_PUSH_CHUNK " +
                "batchCount=${accumulator.batchCount} productsPushed=${accumulator.pushed} " +
                "pushProductsMs=${accumulator.totalBatchMs} " +
                "avgBatchMs=${if (accumulator.batchCount == 0) 0 else accumulator.totalBatchMs / accumulator.batchCount} " +
                "splitFallbackCount=${accumulator.splitFallbackCount} " +
                "singleFallbackCount=${accumulator.singleFallbackCount}"
        )
        return CatalogEntityPushResult(count = accumulator.pushed, remoteIds = accumulator.remoteIds.distinct())
    }

    /**
     * Repairs only the current pending/dirty candidate. The existing bridge
     * revision remains dirty and no outbox or PriceHistory row is created.
     */
    private suspend fun canonicalizeSupplierForCatalogPush(supplier: Supplier): Supplier {
        val canonicalName = CatalogTextCanonicalizer.supplierName(supplier.name)
        if (canonicalName == supplier.name) return supplier
        return db.withTransaction {
            requireCurrentBusinessDataScope()
            supplierDao.rename(supplier.id, canonicalName)
            supplier.copy(name = canonicalName)
        }
    }

    private suspend fun canonicalizeCategoryForCatalogPush(category: Category): Category {
        val canonicalName = CatalogTextCanonicalizer.categoryName(category.name)
        if (canonicalName == category.name) return category
        return db.withTransaction {
            requireCurrentBusinessDataScope()
            categoryDao.rename(category.id, canonicalName)
            category.copy(name = canonicalName)
        }
    }

    private suspend fun canonicalizeProductForCatalogPush(product: Product): Product {
        val canonical = CatalogTextCanonicalizer.product(product).product
        if (canonical == product) return product
        val repairedFields = productChangedFields(product, canonical)
        return db.withTransaction {
            requireCurrentBusinessDataScope()
            productDao.update(canonical)
            touchProductDirty(canonical.id, repairedFields)
            canonical
        }
    }

    private suspend fun pushPreparedProductBatches(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter,
        total: Int,
        allowCreatingDependencyRefs: Boolean,
        shopId: String?,
        prepared: List<ProductPushCandidatePrepared>,
        accumulator: ProductPushBatchAccumulator
    ) {
        for (chunk in prepared.chunked(PRODUCT_BULK_PUSH_CHUNK)) {
            pushPreparedProductBatchWithFallback(
                remote = remote,
                ownerUserId = ownerUserId,
                recoveryCache = recoveryCache,
                progressReporter = progressReporter,
                total = total,
                allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                shopId = shopId,
                batch = chunk,
                accumulator = accumulator
            )
        }
    }

    private suspend fun pushPreparedProductBatchWithFallback(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter,
        total: Int,
        allowCreatingDependencyRefs: Boolean,
        shopId: String?,
        batch: List<ProductPushCandidatePrepared>,
        accumulator: ProductPushBatchAccumulator
    ) {
        if (batch.isEmpty()) return
        if (batch.size == 1) {
            pushPreparedProductSingle(
                remote = remote,
                ownerUserId = ownerUserId,
                recoveryCache = recoveryCache,
                progressReporter = progressReporter,
                total = total,
                allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                shopId = shopId,
                prepared = batch.single(),
                accumulator = accumulator
            )
            return
        }

        val startedAt = System.currentTimeMillis()
        val first = businessScopedRemoteCall {
            remote.upsertProducts(batch.map { it.row }, shopId)
        }
        accumulator.totalBatchMs += System.currentTimeMillis() - startedAt
        accumulator.batchCount++
        val error = first.exceptionOrNull()
        if (error == null) {
            markProductPushBatchApplied(batch)
            accumulator.pushed += batch.size
            accumulator.completed += batch.size
            accumulator.remoteIds += batch.map { it.row.id }
            reportProductPushProgress(progressReporter, accumulator.completed, total)
            return
        }
        if (error is CancellationException) throw error

        val fallbackSize = nextProductPushFallbackSize(batch.size)
        if (fallbackSize <= 1) {
            accumulator.singleFallbackCount += batch.size
            for (prepared in batch) {
                pushPreparedProductSingle(
                    remote = remote,
                    ownerUserId = ownerUserId,
                    recoveryCache = recoveryCache,
                    progressReporter = progressReporter,
                    total = total,
                    allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                    shopId = shopId,
                    prepared = prepared,
                    accumulator = accumulator
                )
            }
            return
        }

        accumulator.splitFallbackCount += batch.size.ceilDiv(fallbackSize)
        for (chunk in batch.chunked(fallbackSize)) {
            pushPreparedProductBatchWithFallback(
                remote = remote,
                ownerUserId = ownerUserId,
                recoveryCache = recoveryCache,
                progressReporter = progressReporter,
                total = total,
                allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                shopId = shopId,
                batch = chunk,
                accumulator = accumulator
            )
        }
    }

    private suspend fun pushPreparedProductsOneByOne(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter,
        total: Int,
        allowCreatingDependencyRefs: Boolean,
        shopId: String?,
        prepared: List<ProductPushCandidatePrepared>,
        accumulator: ProductPushBatchAccumulator
    ) {
        accumulator.singleFallbackCount += prepared.size
        for (candidate in prepared) {
            pushPreparedProductSingle(
                remote = remote,
                ownerUserId = ownerUserId,
                recoveryCache = recoveryCache,
                progressReporter = progressReporter,
                total = total,
                allowCreatingDependencyRefs = allowCreatingDependencyRefs,
                shopId = shopId,
                prepared = candidate,
                accumulator = accumulator
            )
        }
    }

    private suspend fun pushPreparedProductSingle(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        recoveryCache: CatalogConflictRecoveryCache,
        progressReporter: CatalogSyncProgressReporter,
        total: Int,
        allowCreatingDependencyRefs: Boolean,
        shopId: String?,
        prepared: ProductPushCandidatePrepared,
        accumulator: ProductPushBatchAccumulator
    ) {
        val startedAt = System.currentTimeMillis()
        val pushed = pushCatalogProductRow(
            remote = remote,
            ownerUserId = ownerUserId,
            product = prepared.product,
            ref = prepared.ref,
            recoveryCache = recoveryCache,
            shopId = shopId,
            allowCreatingDependencyRefs = allowCreatingDependencyRefs
        )
        accumulator.totalBatchMs += System.currentTimeMillis() - startedAt
        accumulator.batchCount++
        accumulator.completed++
        if (pushed) {
            accumulator.pushed++
            accumulator.remoteIds += productRemoteRefDao.getByProductId(prepared.product.id)?.remoteId
                ?: prepared.ref.remoteId
        }
        reportProductPushProgress(progressReporter, accumulator.completed, total)
    }

    private suspend fun markProductPushBatchApplied(batch: List<ProductPushCandidatePrepared>) {
        val appliedAt = System.currentTimeMillis()
        db.withTransaction {
            requireCurrentBusinessDataScope()
            for (prepared in batch) {
                productRemoteRefDao.updateRemoteApplyState(
                    prepared.product.id,
                    prepared.ref.localChangeRevision,
                    appliedAt,
                    fingerprintProductInbound(prepared.row),
                    prepared.row.updatedAt
                )
            }
        }
    }

    private fun nextProductPushFallbackSize(size: Int): Int = when {
        size > PRODUCT_BULK_PUSH_FALLBACK_50 -> PRODUCT_BULK_PUSH_FALLBACK_50
        size > PRODUCT_BULK_PUSH_FALLBACK_25 -> PRODUCT_BULK_PUSH_FALLBACK_25
        size > 1 -> 1
        else -> 1
    }

    private fun reportProductPushProgress(
        progressReporter: CatalogSyncProgressReporter,
        current: Int,
        total: Int
    ) {
        progressReporter.onProgress(
            CatalogSyncProgressState.running(
                CatalogSyncStage.PUSH_PRODUCTS,
                current = current.coerceAtMost(total),
                total = total
            )
        )
    }

    private fun Int.ceilDiv(other: Int): Int = (this + other - 1) / other

    private fun buildSupplierPushRow(
        supplier: Supplier,
        ref: SupplierRemoteRef,
        ownerUserId: String,
        shopId: String?
    ): InventorySupplierRow =
        InventorySupplierRow(
            id = CatalogTextCanonicalizer.remoteId(ref.remoteId),
            ownerUserId = CatalogTextCanonicalizer.remoteId(ownerUserId),
            shopId = CatalogTextCanonicalizer.optionalRemoteId(shopId),
            name = supplier.name,
            deletedAt = null
        )

    private fun buildCategoryPushRow(
        category: Category,
        ref: CategoryRemoteRef,
        ownerUserId: String,
        shopId: String?
    ): InventoryCategoryRow =
        InventoryCategoryRow(
            id = CatalogTextCanonicalizer.remoteId(ref.remoteId),
            ownerUserId = CatalogTextCanonicalizer.remoteId(ownerUserId),
            shopId = CatalogTextCanonicalizer.optionalRemoteId(shopId),
            name = category.name,
            deletedAt = null
        )

    private suspend fun buildProductPushRow(
        product: Product,
        ref: ProductRemoteRef,
        ownerUserId: String,
        shopId: String?,
        allowCreatingDependencyRefs: Boolean = true
    ): InventoryProductRow? {
        val supplierRemoteId = product.supplierId?.let { supplierId ->
            if (allowCreatingDependencyRefs) {
                CatalogTextCanonicalizer.remoteId(ensureSupplierRefForPush(supplierId).remoteId)
            } else {
                supplierRemoteRefDao.getBySupplierId(supplierId)
                    ?.remoteId
                    ?.let(CatalogTextCanonicalizer::remoteId)
                    ?: return null
            }
        }
        val categoryRemoteId = product.categoryId?.let { categoryId ->
            if (allowCreatingDependencyRefs) {
                CatalogTextCanonicalizer.remoteId(ensureCategoryRefForPush(categoryId).remoteId)
            } else {
                categoryRemoteRefDao.getByCategoryId(categoryId)
                    ?.remoteId
                    ?.let(CatalogTextCanonicalizer::remoteId)
                    ?: return null
            }
        }
        return InventoryProductRow(
            id = CatalogTextCanonicalizer.remoteId(ref.remoteId),
            ownerUserId = CatalogTextCanonicalizer.remoteId(ownerUserId),
            shopId = CatalogTextCanonicalizer.optionalRemoteId(shopId),
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

    private fun canPatchProduct(ref: ProductRemoteRef): Boolean {
        val fields = decodeProductChangedFields(ref.localChangedFields)
        return ref.lastRemoteAppliedAt != null && fields.isNotEmpty() && "__all__" !in fields
    }

    private suspend fun buildProductPatch(
        product: Product,
        ref: ProductRemoteRef,
        allowCreatingDependencyRefs: Boolean = true
    ): InventoryProductPatch? {
        val fields = decodeProductChangedFields(ref.localChangedFields)
        if (fields.isEmpty() || "__all__" in fields) return null
        val supplierRemoteId = if ("supplier" in fields) {
            product.supplierId?.let { supplierId ->
                if (allowCreatingDependencyRefs) {
                    CatalogTextCanonicalizer.remoteId(ensureSupplierRefForPush(supplierId).remoteId)
                } else {
                    supplierRemoteRefDao.getBySupplierId(supplierId)
                        ?.remoteId
                        ?.let(CatalogTextCanonicalizer::remoteId)
                        ?: return null
                }
            }
        } else {
            null
        }
        val categoryRemoteId = if ("category" in fields) {
            product.categoryId?.let { categoryId ->
                if (allowCreatingDependencyRefs) {
                    CatalogTextCanonicalizer.remoteId(ensureCategoryRefForPush(categoryId).remoteId)
                } else {
                    categoryRemoteRefDao.getByCategoryId(categoryId)
                        ?.remoteId
                        ?.let(CatalogTextCanonicalizer::remoteId)
                        ?: return null
                }
            }
        } else {
            null
        }
        return InventoryProductPatch(
            changedFields = fields,
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
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
    ): Boolean {
        val row = buildSupplierPushRow(supplier, ref, ownerUserId, shopId)
        val first = businessScopedRemoteCall { remote.upsertSuppliers(listOf(row), shopId) }
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            markSupplierPushApplied(supplier.id, ref, row)
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileSupplierBridgeAfterUniqueConflict(remote, ownerUserId, supplier, ref, recoveryCache, shopId)
        if (!recovered) throw firstError
        val correctedRef = supplierRemoteRefDao.getBySupplierId(supplier.id) ?: throw firstError
        if (!supplierNeedsPush(correctedRef)) return false

        val retryRow = buildSupplierPushRow(supplier, correctedRef, ownerUserId, shopId)
        businessScopedRemoteCall { remote.upsertSuppliers(listOf(retryRow), shopId) }.getOrThrow()
        markSupplierPushApplied(supplier.id, correctedRef, retryRow)
        return true
    }

    private suspend fun pushCatalogCategoryRow(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        category: Category,
        ref: CategoryRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
    ): Boolean {
        val row = buildCategoryPushRow(category, ref, ownerUserId, shopId)
        val first = businessScopedRemoteCall { remote.upsertCategories(listOf(row), shopId) }
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            markCategoryPushApplied(category.id, ref, row)
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileCategoryBridgeAfterUniqueConflict(remote, ownerUserId, category, ref, recoveryCache, shopId)
        if (!recovered) throw firstError
        val correctedRef = categoryRemoteRefDao.getByCategoryId(category.id) ?: throw firstError
        if (!categoryNeedsPush(correctedRef)) return false

        val retryRow = buildCategoryPushRow(category, correctedRef, ownerUserId, shopId)
        businessScopedRemoteCall { remote.upsertCategories(listOf(retryRow), shopId) }.getOrThrow()
        markCategoryPushApplied(category.id, correctedRef, retryRow)
        return true
    }

    private suspend fun pushCatalogProductRow(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        product: Product,
        ref: ProductRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?,
        allowCreatingDependencyRefs: Boolean = true
    ): Boolean {
        val row = buildProductPushRow(product, ref, ownerUserId, shopId, allowCreatingDependencyRefs)
            ?: return false
        val first = businessScopedRemoteCall { remote.upsertProducts(listOf(row), shopId) }
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            markProductPushApplied(product.id, ref, row)
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileProductBridgeAfterUniqueConflict(remote, ownerUserId, product, ref, recoveryCache, shopId)
        if (!recovered) throw firstError
        val correctedRef = productRemoteRefDao.getByProductId(product.id) ?: throw firstError
        if (!productNeedsPush(correctedRef)) return false

        val retryRow = buildProductPushRow(product, correctedRef, ownerUserId, shopId, allowCreatingDependencyRefs)
            ?: return false
        businessScopedRemoteCall { remote.upsertProducts(listOf(retryRow), shopId) }.getOrThrow()
        markProductPushApplied(product.id, correctedRef, retryRow)
        return true
    }

    private suspend fun markSupplierPushApplied(
        supplierId: Long,
        ref: SupplierRemoteRef,
        row: InventorySupplierRow
    ) {
        requireCurrentBusinessDataScope()
        supplierRemoteRefDao.updateRemoteApplyState(
            supplierId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintSupplierInbound(row),
            row.updatedAt
        )
    }

    private suspend fun markCategoryPushApplied(
        categoryId: Long,
        ref: CategoryRemoteRef,
        row: InventoryCategoryRow
    ) {
        requireCurrentBusinessDataScope()
        categoryRemoteRefDao.updateRemoteApplyState(
            categoryId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintCategoryInbound(row),
            row.updatedAt
        )
    }

    private suspend fun markProductPushApplied(
        productId: Long,
        ref: ProductRemoteRef,
        row: InventoryProductRow
    ) {
        requireCurrentBusinessDataScope()
        productRemoteRefDao.updateRemoteApplyState(
            productId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintProductInbound(row),
            row.updatedAt
        )
    }

    private suspend fun markProductPatchApplied(
        productId: Long,
        ref: ProductRemoteRef,
        patch: InventoryProductPatch
    ) {
        requireCurrentBusinessDataScope()
        productRemoteRefDao.updateRemoteApplyState(
            productId,
            ref.localChangeRevision,
            System.currentTimeMillis(),
            fingerprintProductPatch(ref.lastRemotePayloadFingerprint, patch),
            null
        )
    }

    private fun fingerprintProductPatch(base: String?, patch: InventoryProductPatch): String =
        buildString {
            append("patch:")
            append(base.orEmpty())
            append('|')
            append(patch.changedFields.sorted().joinToString(","))
            append('|')
            if (patch.includes("barcode")) append("barcode=").append(patch.barcode)
            if (patch.includes("itemnumber")) append("itemNumber=").append(patch.itemNumber)
            if (patch.includes("productname")) append("productName=").append(patch.productName)
            if (patch.includes("secondproductname")) append("secondProductName=").append(patch.secondProductName)
            if (patch.includes("purchaseprice")) append("purchasePrice=").append(patch.purchasePrice)
            if (patch.includes("retailprice")) append("retailPrice=").append(patch.retailPrice)
            if (patch.includes("supplier")) append("supplierId=").append(patch.supplierId)
            if (patch.includes("category")) append("categoryId=").append(patch.categoryId)
            if (patch.includes("stockquantity")) append("stockQuantity=").append(patch.stockQuantity)
            if (patch.includes("tombstone")) append("deletedAt=").append(patch.deletedAt)
        }

    private suspend fun reconcileSupplierBridgeAfterUniqueConflict(
        remote: CatalogRemoteDataSource,
        ownerUserId: String,
        supplier: Supplier,
        failedRef: SupplierRemoteRef,
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
    ): Boolean {
        val key = normalizeCatalogNameKey(supplier.name)
        if (key.isEmpty()) return false
        val bundle = fetchCatalogForConflictRecovery(remote, "supplier", supplier.id, recoveryCache, shopId) ?: return false
        val remoteRow = bundle.suppliers.firstOrNull {
            it.deletedAt.isNullOrBlank() &&
                it.ownerUserId == ownerUserId &&
                (shopId == null || it.shopId == shopId) &&
                normalizeCatalogNameKey(it.name) == key
        } ?: return false
        val recovered = db.withTransaction {
            requireCurrentBusinessDataScope()
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
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
    ): Boolean {
        val key = normalizeCatalogNameKey(category.name)
        if (key.isEmpty()) return false
        val bundle = fetchCatalogForConflictRecovery(remote, "category", category.id, recoveryCache, shopId) ?: return false
        val remoteRow = bundle.categories.firstOrNull {
            it.deletedAt.isNullOrBlank() &&
                it.ownerUserId == ownerUserId &&
                (shopId == null || it.shopId == shopId) &&
                normalizeCatalogNameKey(it.name) == key
        } ?: return false
        val recovered = db.withTransaction {
            requireCurrentBusinessDataScope()
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
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
    ): Boolean {
        val key = normalizeCatalogBarcodeKey(product.barcode)
        if (key.isEmpty()) return false
        val bundle = fetchCatalogForConflictRecovery(remote, "product", product.id, recoveryCache, shopId) ?: return false
        val remoteRow = bundle.products.firstOrNull {
            it.deletedAt.isNullOrBlank() &&
                it.ownerUserId == ownerUserId &&
                (shopId == null || it.shopId == shopId) &&
                normalizeCatalogBarcodeKey(it.barcode) == key
        } ?: return false
        val recovered = db.withTransaction {
            requireCurrentBusinessDataScope()
            attachProductBridgeForRetry(product.id, remoteRow.id)
        }
        logCatalogBridgeRecovery("product", recovered, product.id, failedRef.remoteId, remoteRow.id)
        return recovered
    }

    private suspend fun fetchCatalogForConflictRecovery(
        remote: CatalogRemoteDataSource,
        kind: String,
        localId: Long,
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
    ): InventoryCatalogFetchBundle? {
        return recoveryCache.fetch(
            remote = remote,
            shopId = shopId,
            phase = "catalog_bridge_conflict_recover_fetch_$kind",
            kind = kind,
            localId = localId,
            onFailure = ::logSyncTransportFailure
        )
    }

    private suspend fun attachSupplierBridgeForRetry(supplierId: Long, remoteId: String): Boolean {
        val canonicalRemoteId = CatalogTextCanonicalizer.remoteId(remoteId)
        val existingRemote = supplierRemoteRefDao.getByRemoteId(canonicalRemoteId)
        if (existingRemote != null && existingRemote.supplierId != supplierId) return false
        val existingLocal = supplierRemoteRefDao.getBySupplierId(supplierId)
        if (existingLocal == null) {
            supplierRemoteRefDao.insert(
                SupplierRemoteRef(supplierId = supplierId, remoteId = canonicalRemoteId)
            )
            return supplierRemoteRefDao.getBySupplierId(supplierId)?.remoteId == canonicalRemoteId
        }
        if (existingLocal.remoteId == canonicalRemoteId) return true
        return supplierRemoteRefDao.updateRemoteId(supplierId, canonicalRemoteId) > 0
    }

    private suspend fun attachCategoryBridgeForRetry(categoryId: Long, remoteId: String): Boolean {
        val canonicalRemoteId = CatalogTextCanonicalizer.remoteId(remoteId)
        val existingRemote = categoryRemoteRefDao.getByRemoteId(canonicalRemoteId)
        if (existingRemote != null && existingRemote.categoryId != categoryId) return false
        val existingLocal = categoryRemoteRefDao.getByCategoryId(categoryId)
        if (existingLocal == null) {
            categoryRemoteRefDao.insert(
                CategoryRemoteRef(categoryId = categoryId, remoteId = canonicalRemoteId)
            )
            return categoryRemoteRefDao.getByCategoryId(categoryId)?.remoteId == canonicalRemoteId
        }
        if (existingLocal.remoteId == canonicalRemoteId) return true
        return categoryRemoteRefDao.updateRemoteId(categoryId, canonicalRemoteId) > 0
    }

    private suspend fun attachProductBridgeForRetry(productId: Long, remoteId: String): Boolean {
        val canonicalRemoteId = CatalogTextCanonicalizer.remoteId(remoteId)
        val existingRemote = productRemoteRefDao.getByRemoteId(canonicalRemoteId)
        if (existingRemote != null && existingRemote.productId != productId) return false
        val existingLocal = productRemoteRefDao.getByProductId(productId)
        if (existingLocal == null) {
            productRemoteRefDao.insert(
                ProductRemoteRef(productId = productId, remoteId = canonicalRemoteId)
            )
            return productRemoteRefDao.getByProductId(productId)?.remoteId == canonicalRemoteId
        }
        if (existingLocal.remoteId == canonicalRemoteId) return true
        return productRemoteRefDao.updateRemoteId(productId, canonicalRemoteId) > 0
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
        recoveryCache: CatalogConflictRecoveryCache,
        shopId: String?
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
            shopId = shopId,
            phase = "catalog_bridge_realign_fetch",
            kind = "realign",
            localId = 0L,
            onFailure = ::logSyncTransportFailure
        ) ?: return

        val supplierStats = CatalogBridgeRealignStats()
        val categoryStats = CatalogBridgeRealignStats()
        val productStats = CatalogBridgeRealignStats()
        db.withTransaction {
            requireCurrentBusinessDataScope()
            if (suppliersMissing > 0 || suppliersNeverApplied) {
                for (rawRow in bundle.suppliers.filter {
                    it.deletedAt.isNullOrBlank() && (shopId == null || it.shopId == shopId)
                }) {
                    val row = canonicalSupplierInboundRow(rawRow)
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
                                lastRemotePayloadFingerprint = fingerprintSupplierInbound(row),
                                remoteUpdatedAt = row.updatedAt
                            )
                        )
                        supplierStats.linked++
                    }
                }
            }
            if (categoriesMissing > 0 || categoriesNeverApplied) {
                for (rawRow in bundle.categories.filter {
                    it.deletedAt.isNullOrBlank() && (shopId == null || it.shopId == shopId)
                }) {
                    val row = canonicalCategoryInboundRow(rawRow)
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
                                lastRemotePayloadFingerprint = fingerprintCategoryInbound(row),
                                remoteUpdatedAt = row.updatedAt
                            )
                        )
                        categoryStats.linked++
                    }
                }
            }
            if (productsMissing > 0 || productsNeverApplied) {
                for (rawRow in bundle.products.filter {
                    it.deletedAt.isNullOrBlank() && (shopId == null || it.shopId == shopId)
                }) {
                    val row = canonicalProductInboundRow(rawRow)
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
                                lastRemotePayloadFingerprint = fingerprintProductInbound(row),
                                remoteUpdatedAt = row.updatedAt
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
        ownerUserId: String,
        shopId: String?
    ): SyncEventEntityIds {
        val pending = pendingCatalogTombstoneDao.listPendingOrdered()
        val suppliers = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val products = mutableListOf<String>()
        for (row in pending) {
            val deletedAt = java.time.Instant.now().toString()
            val patch = CatalogTombstonePatch(
                id = CatalogTextCanonicalizer.remoteId(row.remoteId),
                ownerUserId = CatalogTextCanonicalizer.remoteId(ownerUserId),
                shopId = CatalogTextCanonicalizer.optionalRemoteId(shopId),
                deletedAt = deletedAt,
                updatedAt = deletedAt
            )
            val outcome = businessScopedRemoteCall {
                when (row.entityType) {
                    PendingCatalogTombstoneEntityTypes.SUPPLIER -> remote.markSupplierTombstoned(patch, shopId)
                    PendingCatalogTombstoneEntityTypes.CATEGORY -> remote.markCategoryTombstoned(patch, shopId)
                    PendingCatalogTombstoneEntityTypes.PRODUCT -> remote.markProductTombstoned(patch, shopId)
                    else -> Result.success(Unit)
                }
            }
            outcome.onFailure {
                requireCurrentBusinessDataScope()
                pendingCatalogTombstoneDao.incrementAttempt(row.id)
                logSyncTransportFailure("catalog_tombstone_drain_${row.entityType}", it)
                throw it
            }
            requireCurrentBusinessDataScope()
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

    private fun canonicalSupplierInboundRow(row: InventorySupplierRow): InventorySupplierRow =
        row.copy(
            id = CatalogTextCanonicalizer.remoteId(row.id),
            ownerUserId = CatalogTextCanonicalizer.remoteId(row.ownerUserId),
            shopId = CatalogTextCanonicalizer.optionalRemoteId(row.shopId),
            name = CatalogTextCanonicalizer.supplierName(row.name)
        )

    private fun canonicalCategoryInboundRow(row: InventoryCategoryRow): InventoryCategoryRow =
        row.copy(
            id = CatalogTextCanonicalizer.remoteId(row.id),
            ownerUserId = CatalogTextCanonicalizer.remoteId(row.ownerUserId),
            shopId = CatalogTextCanonicalizer.optionalRemoteId(row.shopId),
            name = CatalogTextCanonicalizer.categoryName(row.name)
        )

    private fun canonicalProductInboundRow(row: InventoryProductRow): InventoryProductRow =
        row.copy(
            id = CatalogTextCanonicalizer.remoteId(row.id),
            ownerUserId = CatalogTextCanonicalizer.remoteId(row.ownerUserId),
            shopId = CatalogTextCanonicalizer.optionalRemoteId(row.shopId),
            barcode = CatalogTextCanonicalizer.barcode(row.barcode),
            itemNumber = row.itemNumber
                ?.let(CatalogTextCanonicalizer::itemNumber)
                ?.takeIf { it.isNotEmpty() },
            productName = row.productName
                ?.let(CatalogTextCanonicalizer::productName)
                ?.takeIf { it.isNotEmpty() },
            secondProductName = row.secondProductName
                ?.let(CatalogTextCanonicalizer::secondProductName)
                ?.takeIf { it.isNotEmpty() },
            supplierId = CatalogTextCanonicalizer.optionalRemoteId(row.supplierId),
            categoryId = CatalogTextCanonicalizer.optionalRemoteId(row.categoryId),
            primaryImageVersionId = CatalogTextCanonicalizer.optionalRemoteId(
                row.primaryImageVersionId
            )
        )

    private suspend fun applyInboundSupplierTombstone(row: InventorySupplierRow): Boolean {
        if (row.deletedAt.isNullOrBlank()) return false
        val remoteId = CatalogTextCanonicalizer.remoteId(row.id)
        val ref = supplierRemoteRefDao.getByRemoteId(remoteId) ?: return false
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
        val remoteId = CatalogTextCanonicalizer.remoteId(row.id)
        val ref = categoryRemoteRefDao.getByRemoteId(remoteId) ?: return false
        if (ref.localChangeRevision > ref.lastSyncedLocalRevision) return false
        return try {
            deleteCatalogEntity(CatalogEntityKind.CATEGORY, ref.categoryId, enqueueCloudTombstone = false)
            true
        } catch (_: CatalogNotFoundException) {
            false
        }
    }

    private suspend fun applyInboundProductTombstone(row: InventoryProductRow): Long? {
        if (row.deletedAt.isNullOrBlank()) return null
        val remoteId = CatalogTextCanonicalizer.remoteId(row.id)
        val ref = productRemoteRefDao.getByRemoteId(remoteId) ?: return null
        if (ref.localChangeRevision > ref.lastSyncedLocalRevision) return null
        val p = productDao.getById(ref.productId) ?: return null
        productDao.delete(p)
        return ref.productId
    }

    private suspend fun applyRemoteSupplierInbound(row: InventorySupplierRow): Boolean {
        if (!row.deletedAt.isNullOrBlank()) return false
        val canonicalRow = canonicalSupplierInboundRow(row)
        val fp = fingerprintSupplierInbound(canonicalRow)
        val existingRef = supplierRemoteRefDao.getByRemoteId(canonicalRow.id)
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
            val name = canonicalRow.name
            try {
                supplierDao.rename(existingRef.supplierId, name)
            } catch (_: SQLiteConstraintException) {
                return false
            }
            supplierRemoteRefDao.updateRemoteApplyState(
                existingRef.supplierId,
                existingRef.localChangeRevision,
                System.currentTimeMillis(),
                fp,
                canonicalRow.updatedAt
            )
            return true
        }
        val name = canonicalRow.name
        val local = supplierDao.findByNameIgnoreCase(name)
        val localId = local?.id ?: run {
            val ins = supplierDao.insert(Supplier(name = name))
            when {
                ins > 0L -> ins
                else -> supplierDao.findByNameIgnoreCase(name)?.id ?: return false
            }
        }
        val bridgeForRow = supplierRemoteRefDao.getBySupplierId(localId)
        if (bridgeForRow != null && bridgeForRow.remoteId != canonicalRow.id) return false
        if (bridgeForRow != null) return false
        supplierRemoteRefDao.insert(
            SupplierRemoteRef(
                supplierId = localId,
                remoteId = canonicalRow.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                lastRemotePayloadFingerprint = fp,
                remoteUpdatedAt = canonicalRow.updatedAt
            )
        )
        return true
    }

    private suspend fun applyRemoteCategoryInbound(row: InventoryCategoryRow): Boolean {
        if (!row.deletedAt.isNullOrBlank()) return false
        val canonicalRow = canonicalCategoryInboundRow(row)
        val fp = fingerprintCategoryInbound(canonicalRow)
        val existingRef = categoryRemoteRefDao.getByRemoteId(canonicalRow.id)
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
            val name = canonicalRow.name
            try {
                categoryDao.rename(existingRef.categoryId, name)
            } catch (_: SQLiteConstraintException) {
                return false
            }
            categoryRemoteRefDao.updateRemoteApplyState(
                existingRef.categoryId,
                existingRef.localChangeRevision,
                System.currentTimeMillis(),
                fp,
                canonicalRow.updatedAt
            )
            return true
        }
        val name = canonicalRow.name
        val local = categoryDao.findByName(name)
        val localId = local?.id ?: run {
            val ins = categoryDao.insert(Category(name = name))
            when {
                ins > 0L -> ins
                else -> categoryDao.findByName(name)?.id ?: return false
            }
        }
        val bridgeForRow = categoryRemoteRefDao.getByCategoryId(localId)
        if (bridgeForRow != null && bridgeForRow.remoteId != canonicalRow.id) return false
        if (bridgeForRow != null) return false
        categoryRemoteRefDao.insert(
            CategoryRemoteRef(
                categoryId = localId,
                remoteId = canonicalRow.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                lastRemotePayloadFingerprint = fp,
                remoteUpdatedAt = canonicalRow.updatedAt
            )
        )
        return true
    }

    private suspend fun applyRemoteProductInbound(row: InventoryProductRow): Long? {
        if (!row.deletedAt.isNullOrBlank()) return null
        val canonicalRow = canonicalProductInboundRow(row)
        val fp = fingerprintProductInbound(canonicalRow)
        val existingRef = productRemoteRefDao.getByRemoteId(canonicalRow.id)
        if (existingRef != null) {
            if (existingRef.localChangeRevision > existingRef.lastSyncedLocalRevision) {
                // L'immagine e' un sottodominio remoto-autoritativo: applicarla non
                // deve sovrascrivere i campi prodotto dirty ne' marcare la revisione synced.
                productDao.updateRemoteImageReference(
                    existingRef.productId,
                    canonicalRow.primaryImageVersionId,
                    canonicalRow.primaryImageUpdatedAt
                )
                return null
            }
            if (existingRef.lastRemotePayloadFingerprint == fp &&
                existingRef.localChangeRevision == existingRef.lastSyncedLocalRevision
            ) {
                return null
            }
            val supLocal = canonicalRow.supplierId
                ?.let { supplierRemoteRefDao.getByRemoteId(it)?.supplierId }
            val catLocal = canonicalRow.categoryId
                ?.let { categoryRemoteRefDao.getByRemoteId(it)?.categoryId }
            val cur = productDao.getById(existingRef.productId) ?: return null
            val merged = CatalogTextCanonicalizer.product(
                cur.copy(
                    barcode = canonicalRow.barcode,
                    itemNumber = canonicalRow.itemNumber,
                    productName = canonicalRow.productName,
                    secondProductName = canonicalRow.secondProductName,
                    purchasePrice = canonicalRow.purchasePrice,
                    retailPrice = canonicalRow.retailPrice,
                    supplierId = supLocal,
                    categoryId = catLocal,
                    stockQuantity = canonicalRow.stockQuantity ?: cur.stockQuantity,
                    primaryImageVersionId = canonicalRow.primaryImageVersionId,
                    primaryImageUpdatedAt = canonicalRow.primaryImageUpdatedAt
                )
            ).product
            try {
                productDao.update(merged)
            } catch (_: SQLiteConstraintException) {
                return null
            }
            productRemoteRefDao.updateRemoteApplyState(
                existingRef.productId,
                existingRef.localChangeRevision,
                System.currentTimeMillis(),
                fp,
                canonicalRow.updatedAt
            )
            return existingRef.productId
        }
        val supLocal = canonicalRow.supplierId
            ?.let { supplierRemoteRefDao.getByRemoteId(it)?.supplierId }
        val catLocal = canonicalRow.categoryId
            ?.let { categoryRemoteRefDao.getByRemoteId(it)?.categoryId }
        val bc = canonicalRow.barcode
        val localByBarcode = productDao.findByBarcode(bc)
        val targetId: Long
        if (localByBarcode != null) {
            val other = productRemoteRefDao.getByProductId(localByBarcode.id)
            if (other != null && other.remoteId != canonicalRow.id) return null
            if (other != null && other.localChangeRevision > other.lastSyncedLocalRevision) return null
            targetId = localByBarcode.id
            val merged = CatalogTextCanonicalizer.product(
                localByBarcode.copy(
                    itemNumber = canonicalRow.itemNumber,
                    productName = canonicalRow.productName,
                    secondProductName = canonicalRow.secondProductName,
                    purchasePrice = canonicalRow.purchasePrice,
                    retailPrice = canonicalRow.retailPrice,
                    supplierId = supLocal,
                    categoryId = catLocal,
                    stockQuantity = canonicalRow.stockQuantity ?: localByBarcode.stockQuantity,
                    primaryImageVersionId = canonicalRow.primaryImageVersionId,
                    primaryImageUpdatedAt = canonicalRow.primaryImageUpdatedAt
                )
            ).product
            try {
                productDao.update(merged)
            } catch (_: SQLiteConstraintException) {
                return null
            }
        } else {
            val inserted = CatalogTextCanonicalizer.product(
                Product(
                    barcode = bc,
                    itemNumber = canonicalRow.itemNumber,
                    productName = canonicalRow.productName,
                    secondProductName = canonicalRow.secondProductName,
                    purchasePrice = canonicalRow.purchasePrice,
                    retailPrice = canonicalRow.retailPrice,
                    supplierId = supLocal,
                    categoryId = catLocal,
                    stockQuantity = canonicalRow.stockQuantity ?: 0.0,
                    primaryImageVersionId = canonicalRow.primaryImageVersionId,
                    primaryImageUpdatedAt = canonicalRow.primaryImageUpdatedAt
                )
            ).product
            try {
                productDao.insert(inserted)
            } catch (_: SQLiteConstraintException) {
                return null
            }
            targetId = productDao.findByBarcode(bc)?.id ?: return null
        }
        if (productRemoteRefDao.getByProductId(targetId) != null) return null
        productRemoteRefDao.insert(
            ProductRemoteRef(
                productId = targetId,
                remoteId = canonicalRow.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                lastRemotePayloadFingerprint = fp,
                remoteUpdatedAt = canonicalRow.updatedAt
            )
        )
        return targetId
    }
}

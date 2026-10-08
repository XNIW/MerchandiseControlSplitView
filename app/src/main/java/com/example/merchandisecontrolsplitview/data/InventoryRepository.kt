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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
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
    fun getProductsWithDetailsPaged(
        filter: String?,
        productIds: Set<Long>?
    ): PagingSource<Int, ProductWithDetails> = getProductsWithDetailsPaged(filter)
    suspend fun findProductByBarcode(barcode: String): Product?
    suspend fun findProductsByBarcodes(barcodes: List<String>): List<Product>
    suspend fun getAllProducts(): List<Product>
    suspend fun getProductDetailsById(productId: Long): ProductWithDetails?
    /** Mapping bounded remote identity -> righe locali, nell'ordine remoto richiesto. */
    suspend fun getProductsWithDetailsByRemoteIds(
        remoteIds: List<String>
    ): List<ProductWithDetails> = emptyList()
    /** True solo dopo che il bridge prodotto e' stato applicato almeno una volta al remoto. */
    suspend fun hasSyncedProductRemoteRef(productId: Long): Boolean = false
    /** Confirmation of this current product and its price points, independent of other pending records. */
    fun observeProductCloudConfirmed(productId: Long): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    /** Identita' remote stabili e gia' riconciliate, mai barcode. */
    suspend fun getSyncedProductRemoteIds(productIds: List<Long>): Map<Long, String> = emptyMap()
    val remoteAppliedProductIds: Flow<Set<Long>>
        get() = emptyFlow()
    suspend fun addProduct(product: Product)
    suspend fun addProductAndReturnId(product: Product): Long {
        addProduct(product)
        return requireNotNull(findProductByBarcode(product.barcode)?.id) {
            "Inserted product id is unavailable"
        }
    }
    suspend fun updateProduct(product: Product)
    /** Merge only the fields changed since opening the editor, atomically against the current row. */
    suspend fun updateProductFromEditor(baseline: Product, product: Product) {
        throw UnsupportedOperationException("Product editor concurrency is not supported")
    }
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
    var afterOrdinaryShopSyncWrites: (suspend () -> Unit)? = null

    @Volatile
    var localProductMutationNow: (() -> LocalDateTime)? = null
}

internal data class ShopSyncRecoveryStageApplyResult(
    val businessRowsApplied: Int,
    val skippedParentRows: Int = 0,
    val failedRows: Int = 0,
    val unsupportedRows: Int = 0
)

/** Internal handoff: a checkpoint-owned store must use the existing fenced event drain. */
internal class CanonicalCatalogInboundRequired(val outbound: CatalogSyncSummary? = null) :
    IllegalStateException("canonical_catalog_inbound_required")

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
        val ordinaryPending: Boolean = false,
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
    ): T = businessDataScopeRuntimeGuard.withLocalBusinessDataScopeFlight {
        requireCurrentBusinessDataScope()
        val result = block()
        requireCurrentBusinessDataScope()
        result
    }

    private suspend fun <T> businessScopedRemoteCall(
        block: suspend () -> Result<T>
    ): Result<T> {
        businessDataScopeRuntimeGuard.requireCloudBusinessDataScope()
        requireCurrentBusinessDataScope()
        val result = block()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        requireCurrentBusinessDataScope()
        return result
    }

    private val businessWriteOutbox by lazy {
        BusinessWriteOutbox(db, ::requireCurrentBusinessDataScope, ::getOrCreateSyncEventDeviceId,
            ::acknowledgeBusinessWrite) { it.isPostgrestUniqueViolationConflict() || it.isPostgrestForeignKeyViolationConflict() }
    }

    private suspend fun acknowledgeBusinessWrite(attempt: BusinessWriteAttempt) {
        requireCurrentBusinessDataScope()
        val now = System.currentTimeMillis()
        for (sent in attempt.payload.revisions) {
            val revision = sent.revision.toInt()
            when (sent.domain) {
                "SUPPLIER" -> supplierRemoteRefDao.getByRemoteId(sent.remoteId)?.let {
                    if (it.lastSyncedLocalRevision <= revision) supplierRemoteRefDao.updateRemoteApplyState(
                        it.supplierId,revision,now,requireNotNull(sent.fingerprint),sent.remoteUpdatedAt)
                }
                "CATEGORY" -> categoryRemoteRefDao.getByRemoteId(sent.remoteId)?.let {
                    if (it.lastSyncedLocalRevision <= revision) categoryRemoteRefDao.updateRemoteApplyState(
                        it.categoryId,revision,now,requireNotNull(sent.fingerprint),sent.remoteUpdatedAt)
                }
                "PRODUCT" -> productRemoteRefDao.getByRemoteId(sent.remoteId)?.let {
                    productRemoteRefDao.updateRemoteApplyState(it.productId,revision,now,
                        requireNotNull(sent.fingerprint),sent.remoteUpdatedAt)
                }
                "HISTORY" -> remoteRefDao.getByRemoteId(sent.remoteId)?.let {
                    if (it.lastSyncedLocalRevision <= revision) {
                        remoteRefDao.updateRemoteApplyState(it.historyEntryUid,revision,now,requireNotNull(sent.fingerprint))
                        if (it.localChangeRevision == revision) historyDao.getByUid(it.historyEntryUid)?.let { row ->
                            historyDao.update(row.copy(syncStatus=SyncStatus.SYNCED_SUCCESSFULLY))
                        }
                    }
                }
                "PRICE" -> {
                    val localId = requireNotNull(sent.localPriceId)
                    val row = attempt.payload.prices.single { it.id == sent.remoteId }
                    // Price rows are immutable append records. Verify the logical identity before ACK.
                    val matches = db.openHelper.readableDatabase.query(
                        "SELECT 1 FROM product_prices p JOIN product_remote_refs r ON r.productId=p.productId " +
                            "WHERE p.id=? AND r.remoteId=? AND p.type=? AND p.price=? AND p.effectiveAt=? " +
                            "AND p.source IS ? AND p.note IS ? AND p.createdAt=?",
                        arrayOf<Any?>(localId,row.productId,row.type,row.price,row.effectiveAt,row.source,row.note,row.createdAt)).use { it.moveToFirst() }
                    require(matches) { "business_write_price_body_mismatch" }
                    run {
                        val existing = productPriceRemoteRefDao.getByProductPriceId(localId)
                        require(existing == null || existing.remoteId == sent.remoteId) { "business_write_price_identity_mismatch" }
                        productPriceRemoteRefDao.insert(ProductPriceRemoteRef(productPriceId=localId,remoteId=sent.remoteId))
                    }
                }
                else -> error("business_write_revision_domain_invalid")
            }
        }
        recordAcknowledgedLocalBodies(db, attempt)
    }

    private suspend fun sendCatalogBusinessWrite(remote: CatalogRemoteDataSource, attempt: BusinessWriteAttempt): Result<Unit> =
        businessScopedRemoteCall {
            when(attempt.payload.kind) {
                "PRODUCTS" -> remote.upsertProducts(attempt.payload.products,attempt.shop)
                "SUPPLIERS" -> remote.upsertSuppliers(attempt.payload.suppliers,attempt.shop)
                "CATEGORIES" -> remote.upsertCategories(attempt.payload.categories,attempt.shop)
                "PATCH" -> remote.patchProduct(requireNotNull(attempt.payload.patchId),attempt.owner,
                    attempt.shop,requireNotNull(attempt.payload.patch))
                else -> Result.failure(IllegalStateException("business_write_catalog_kind_invalid"))
            }
        }

    private suspend fun productWriteRevision(product: Product, ref: ProductRemoteRef,
        owner: String, shop: String?): BusinessWriteRevision {
        val semantic = requireNotNull(buildProductPushRow(product,ref,owner,shop)).copy(
            updatedAt=ref.remoteUpdatedAt, primaryImageVersionId=product.primaryImageVersionId,
            primaryImageUpdatedAt=product.primaryImageUpdatedAt)
        return BusinessWriteRevision("PRODUCT",ref.remoteId,ref.localChangeRevision.toLong(),
            fingerprintProductInbound(semantic),ref.remoteUpdatedAt)
    }

    private suspend fun writeCatalogProducts(remote: CatalogRemoteDataSource, owner: String,
        shop: String?, prepared: List<ProductPushCandidatePrepared>): Result<Unit> =
        businessWriteOutbox.execute(owner,shop,BusinessWritePayload("PRODUCTS",
            products=prepared.map { it.row }, revisions=prepared.map {
                productWriteRevision(it.product,it.ref,owner,shop)
            })) { sendCatalogBusinessWrite(remote,it) }

    private suspend fun writeSupplier(remote: CatalogRemoteDataSource, owner: String, shop: String?,
        row: InventorySupplierRow, ref: SupplierRemoteRef): Result<Unit> =
        businessWriteOutbox.execute(owner,shop,BusinessWritePayload("SUPPLIERS",suppliers=listOf(row),
            revisions=listOf(BusinessWriteRevision("SUPPLIER",row.id,ref.localChangeRevision.toLong(),
                fingerprintSupplierInbound(row.copy(updatedAt=ref.remoteUpdatedAt)),ref.remoteUpdatedAt)))) {
            sendCatalogBusinessWrite(remote,it)
        }

    private suspend fun writeCategory(remote: CatalogRemoteDataSource, owner: String, shop: String?,
        row: InventoryCategoryRow, ref: CategoryRemoteRef): Result<Unit> =
        businessWriteOutbox.execute(owner,shop,BusinessWritePayload("CATEGORIES",categories=listOf(row),
            revisions=listOf(BusinessWriteRevision("CATEGORY",row.id,ref.localChangeRevision.toLong(),
                fingerprintCategoryInbound(row.copy(updatedAt=ref.remoteUpdatedAt)),ref.remoteUpdatedAt)))) {
            sendCatalogBusinessWrite(remote,it)
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
    override fun getProductsWithDetailsPaged(
        filter: String?,
        productIds: Set<Long>?
    ) = productDao.getAllWithDetailsPagedForProductIds(
        filter = filter,
        applyProductIds = productIds != null,
        productIds = productIds?.toList()?.ifEmpty { listOf(Long.MIN_VALUE) }
            ?: listOf(Long.MIN_VALUE)
    )
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
    override suspend fun getProductsWithDetailsByRemoteIds(
        remoteIds: List<String>
    ): List<ProductWithDetails> = withContext(Dispatchers.IO) {
        if (remoteIds.isEmpty()) return@withContext emptyList()
        require(remoteIds.size <= 100 && remoteIds.all(::isStorefrontRemoteIdentity))
        val refsByRemoteId = productRemoteRefDao.getByRemoteIds(remoteIds.distinct())
            .asSequence()
            .filter { it.lastRemoteAppliedAt != null }
            .associateBy(ProductRemoteRef::remoteId)
        val orderedLocalIds = remoteIds.mapNotNull { refsByRemoteId[it]?.productId }
        val detailsById = productDao.getDetailsByIds(orderedLocalIds.distinct())
            .associateBy { it.product.id }
        orderedLocalIds.mapNotNull(detailsById::get)
    }
    override suspend fun hasSyncedProductRemoteRef(productId: Long): Boolean =
        withContext(Dispatchers.IO) {
            productRemoteRefDao.getByProductId(productId)?.lastRemoteAppliedAt != null
        }

    override fun observeProductCloudConfirmed(productId: Long): Flow<Boolean> =
        productRemoteRefDao.observeProductCloudConfirmed(productId)
    override suspend fun getSyncedProductRemoteIds(productIds: List<Long>): Map<Long, String> =
        withContext(Dispatchers.IO) {
            if (productIds.isEmpty()) emptyMap()
            else productRemoteRefDao.getByProductIds(productIds.distinct())
                .asSequence()
                .filter {
                    it.lastRemoteAppliedAt != null &&
                        isStorefrontRemoteIdentity(it.remoteId)
                }
                .associate { it.productId to it.remoteId }
        }

    override suspend fun addProduct(product: Product) {
        addProductAndReturnId(product)
    }

    override suspend fun addProductAndReturnId(product: Product): Long = withLocalBusinessMutation {
        val canonicalProduct = CatalogTextCanonicalizer.product(product).product
        DefaultInventoryRepositoryTestHooks.beforeLocalProductInsert?.invoke()
        requireCurrentBusinessDataScope()
        val persistedId = withContext(Dispatchers.IO) {
            db.withTransaction {
                productDao.insert(canonicalProduct)
                DefaultInventoryRepositoryTestHooks.afterLocalProductWrite?.invoke()
                val persisted = requireNotNull(productDao.findByBarcode(canonicalProduct.barcode)) {
                    "Inserted product id is unavailable"
                }

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
        notifyProductCatalogChanged(persistedId)
        persistedId
    }
    override suspend fun updateProduct(product: Product) = persistProductUpdate(product)

    override suspend fun updateProductFromEditor(baseline: Product, product: Product) =
        persistProductUpdate(product, baseline)

    private suspend fun persistProductUpdate(product: Product, baseline: Product? = null) = withLocalBusinessMutation {
        val canonicalProduct = CatalogTextCanonicalizer.product(product).product
        val canonicalBaseline = baseline?.let { CatalogTextCanonicalizer.product(it).product }
        require(canonicalBaseline == null || canonicalBaseline.id == canonicalProduct.id) {
            "Product editor baseline must match the saved product"
        }
        val didWrite = withContext(Dispatchers.IO) {
            db.withTransaction {
                val existing = productDao.getById(canonicalProduct.id)
                val updated = if (canonicalBaseline != null) {
                    mergeProductEditorChanges(
                        canonicalBaseline,
                        canonicalProduct,
                        existing ?: throw ProductEditConflictException()
                    )
                } else canonicalProduct
                if (canonicalBaseline != null && existing == updated) return@withTransaction false
                val changedFields = existing
                    ?.let { productChangedFields(it, updated) }
                    .orEmpty()
                productDao.update(updated)
                DefaultInventoryRepositoryTestHooks.afterLocalProductWrite?.invoke()

                val requestedAt = (DefaultInventoryRepositoryTestHooks.localProductMutationNow
                    ?.invoke() ?: LocalDateTime.now()).format(tSFMT)

                updated.purchasePrice?.takeIf {
                    canonicalBaseline == null || "purchaseprice" in changedFields
                }?.let {
                    priceDao.insertIfChanged(
                        updated.id,
                        "PURCHASE",
                        it,
                        uniquePriceEffectiveAtLocked(updated.id, "PURCHASE", requestedAt),
                        "MANUAL",
                    )
                }
                updated.retailPrice?.takeIf {
                    canonicalBaseline == null || "retailprice" in changedFields
                }?.let {
                    priceDao.insertIfChanged(
                        updated.id,
                        "RETAIL",
                        it,
                        uniquePriceEffectiveAtLocked(updated.id, "RETAIL", requestedAt),
                        "MANUAL",
                    )
                }
                if (changedFields.isNotEmpty()) {
                    touchProductDirty(updated.id, changedFields)
                }
                true
            }
        }
        if (didWrite) notifyProductCatalogChanged(canonicalProduct.id)
    }

    private fun mergeProductEditorChanges(baseline: Product, edited: Product, current: Product): Product {
        val editedFields = productChangedFields(baseline, edited)
        val concurrentFields = productChangedFields(baseline, current)
        val differentValues = productChangedFields(current, edited)
        if ((editedFields intersect concurrentFields intersect differentValues).isNotEmpty()) {
            throw ProductEditConflictException()
        }
        // Historical prices and image metadata have separate owners; never restore their editor snapshot.
        return current.copy(
            barcode = if ("barcode" in editedFields) edited.barcode else current.barcode,
            itemNumber = if ("itemnumber" in editedFields) edited.itemNumber else current.itemNumber,
            productName = if ("productname" in editedFields) edited.productName else current.productName,
            secondProductName = if ("secondproductname" in editedFields) edited.secondProductName else current.secondProductName,
            purchasePrice = if ("purchaseprice" in editedFields) edited.purchasePrice else current.purchasePrice,
            retailPrice = if ("retailprice" in editedFields) edited.retailPrice else current.retailPrice,
            supplierId = if ("supplier" in editedFields) edited.supplierId else current.supplierId,
            categoryId = if ("category" in editedFields) edited.categoryId else current.categoryId,
            stockQuantity = if ("stockquantity" in editedFields) edited.stockQuantity else current.stockQuantity
        )
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
                    retireAcknowledgedPriceBodiesForProductDelete(db, product.id, rid)
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
                db.withTransaction {
                    supplierDao.findByNormalizedName(lookupKey)?.let { return@withTransaction it to false }
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
                db.withTransaction {
                    val created = createCatalogEntryLocked(kind, normalizedNameFor(kind, name))
                    when (kind) {
                        CatalogEntityKind.SUPPLIER -> touchSupplierDirty(created.id)
                        CatalogEntityKind.CATEGORY -> touchCategoryDirty(created.id)
                    }
                    created
                }
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
                db.withTransaction {
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
                db.withTransaction {
                    categoryDao.findByNormalizedName(lookupKey)?.let { return@withTransaction it to false }
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
        if (productDao.count() > 0) return@withContext false
        // A verified empty catalog is usable only under the caller's current managed lease.
        // The receipt cannot select a shop, create an identity, or authorize a default watermark.
        try {
            businessDataScopeRuntimeGuard.withCurrentBusinessDataScopeFlight {
                requireCurrentBusinessDataScope()
                val lease = kotlinx.coroutines.currentCoroutineContext()[Task126BusinessDataScopeLeaseContext]?.lease
                val scope = lease?.boundScope
                if (lease == null || lease.unmanaged || scope == null ||
                    scope.ownerHash != task126OwnerHash(ownerUserId)) {
                    return@withCurrentBusinessDataScopeFlight true
                }
                val shopId = shopIdFromStoreScope(scope.storeId)
                    ?: return@withCurrentBusinessDataScopeFlight true
                db.withTransaction {
                    requireCurrentBusinessDataScope()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (productDao.count() > 0) return@withTransaction false
                    val binding = businessDataScopeBindingDao.get() ?: return@withTransaction true
                    if (Task126OwnerStoreGate.validate(binding.toOwnerStoreScope(), scope) !=
                        Task126OwnerStoreGateDecision.Allowed) return@withTransaction true
                    val device = syncEventDeviceStateDao.get() ?: return@withTransaction true
                    val captured = syncRecoveryBaselineDao.get() ?: return@withTransaction true
                    val watermark = syncEventWatermarkDao.get(ownerUserId, scope.storeId)
                        ?: return@withTransaction true
                    if (syncRecoveryJournalDao.get() != null ||
                        ordinaryShopSyncPendingCount(ownerUserId, scope.storeId) > 0) return@withTransaction true
                    val checkpoint = shopSyncBaselineForEventDrain(ownerUserId, scope.storeId, shopId,
                        device.deviceId, watermark.lastSyncEventId, captured, watermark)
                        ?: return@withTransaction true
                    if (checkpoint.syncEvents.domainMaxIds.keys != setOf(SyncEventDomains.CATALOG,
                        SyncEventDomains.PRICES, SyncEventDomains.HISTORY) ||
                        checkpoint.syncEvents.domainMaxIds.values.any {
                            parseShopSyncMaxEventId(it) > watermark.lastSyncEventId
                        }) return@withTransaction true
                    validateRecoveryScopeIdentity(checkpoint.scope, ownerUserId, device.deviceId)
                    validateRecoveryCheckpointResourceBounds(checkpoint, DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS)
                    validateShopSyncActiveReceipt(db, captured.generationId, checkpoint)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    requireCurrentBusinessDataScope()
                    val changed = syncRecoveryBaselineDao.get() != captured ||
                        businessDataScopeBindingDao.get() != binding ||
                        syncEventDeviceStateDao.get() != device ||
                        syncEventWatermarkDao.get(ownerUserId, scope.storeId) != watermark ||
                        syncRecoveryJournalDao.get() != null ||
                        ordinaryShopSyncPendingCount(ownerUserId, scope.storeId) > 0
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    requireCurrentBusinessDataScope()
                    changed
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
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
                val sameScope = storedBinding != null && boundScope != null &&
                    Task126OwnerStoreGate.validate(boundScope, activeScope) == Task126OwnerStoreGateDecision.Allowed &&
                    pendingTargetRecovery.authorizationMode == SyncRecoveryAuthorizationModes.SAME_SCOPE
                val localScope = boundScope?.takeIf { sameScope && hasStructurallyUsableLocalSnapshot(it) }
                return@withTransaction Task126BusinessDataScopeState(
                    status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    boundScope = boundScope,
                    localSnapshot = snapshot,
                    errorCode = "sync_recovery_required",
                    localAccessScope = localScope
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
                            // A restored binding may precede the first READY scope and
                            // therefore the first normal device registration. Persist the
                            // canonical install identity atomically with the recovery intent.
                            val deviceId = DeviceInstallIdProvider(syncEventDeviceStateDao).getOrCreate()
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
                if (syncRecoveryJournalDao.get() != null) {
                    Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                        boundScope=persisted,errorCode="sync_recovery_required")
                } else if (hasStructurallyUsableLocalSnapshot(persisted)) {
                    Task126BusinessDataScopeState.ready(persisted)
                } else {
                    val now = System.currentTimeMillis()
                    syncRecoveryJournalDao.upsert(SyncRecoveryJournal(
                        ownerHash=persisted.ownerHash,storeScope=persisted.storeId,
                        shopId=shopIdFromStoreScope(persisted.storeId),
                        deviceId=DeviceInstallIdProvider(syncEventDeviceStateDao).getOrCreate(),
                        authorizationMode=SyncRecoveryAuthorizationModes.SAME_SCOPE,
                        phase=SyncRecoveryJournalPhases.REQUIRED,reason="local_snapshot_unavailable",
                        blockingEventId=null,attemptCount=0,createdAtMs=now,updatedAtMs=now,nextRetryAtMs=now))
                    Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                        boundScope=persisted,errorCode="sync_recovery_required",localReadsAllowed=false)
                }
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

    /** Read-only resolution: never binds/adopts a store just because cloud is unavailable. */
    internal suspend fun resolveOfflineBusinessDataScope(
        activeScope: Task126OwnerStoreScope, canWrite: Boolean
    ): Task126BusinessDataScopeState = withContext(Dispatchers.IO) {
        db.withTransaction {
            val binding = businessDataScopeBindingDao.get()?.toOwnerStoreScope()
            val snapshot = readLocalDatabaseStatusSnapshot(null, null)
            if (binding == null) return@withTransaction Task126BusinessDataScopeState(
                status = if (snapshot.isCompletelyEmptyForBinding) Task126BusinessDataScopeStatus.CHECKING
                    else Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND, localSnapshot = snapshot)
            val decision = Task126OwnerStoreGate.validate(binding, activeScope)
            if (decision is Task126OwnerStoreGateDecision.Blocked) {
                return@withTransaction blockedBusinessDataScopeState(decision.reason, binding, snapshot)
            }
            val journal = syncRecoveryJournalDao.get()
            val eligibleJournal = journal == null || (journal.ownerHash == binding.ownerHash &&
                journal.storeScope == binding.storeId && journal.authorizationMode == SyncRecoveryAuthorizationModes.SAME_SCOPE)
            val usable = eligibleJournal && hasStructurallyUsableLocalSnapshot(binding)
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.CHECKING, boundScope = binding,
                localSnapshot = snapshot, localAccessScope = binding.takeIf { usable },
                localWritesAllowed = canWrite,
                errorCode = if (usable) null else "local_snapshot_unavailable")
        }
    }

    internal suspend fun resolveSignedOutBusinessDataScope(): Task126BusinessDataScopeState =
        withContext(Dispatchers.IO) {
            val binding = businessDataScopeBindingDao.get()?.toOwnerStoreScope()
            if (binding == null) Task126BusinessDataScopeState.unmanagedAllowed()
            else Task126BusinessDataScopeState(Task126BusinessDataScopeStatus.CHECKING, boundScope = binding)
        }

    /** Local provenance and structure survive legitimate offline edits; cloud body digests do not. */
    private suspend fun hasStructurallyUsableLocalSnapshot(scope: Task126OwnerStoreScope): Boolean {
        val sql = db.openHelper.readableDatabase
        if (!sql.query("PRAGMA quick_check(1)").use { it.moveToFirst() && it.getString(0) == "ok" }) return false
        if (sql.query("PRAGMA foreign_key_check").use { it.moveToFirst() }) return false
        val baseline = syncRecoveryBaselineDao.get() ?: return true
        if (baseline.ownerHash != scope.ownerHash || baseline.storeScope != scope.storeId ||
            shopIdFromStoreScope(scope.storeId)?.lowercase() != baseline.shopId.lowercase() ||
            syncEventDeviceStateDao.get()?.deviceId != baseline.deviceId) return false
        return try {
            val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
            if (checkpoint.scope.accountKey != scope.ownerHash || checkpoint.scope.deviceKey != task126OwnerHash(baseline.deviceId) ||
                checkpoint.scope.kind != baseline.scopeKind || checkpoint.scope.key != baseline.scopeKey ||
                checkpoint.shopId.lowercase() != baseline.shopId.lowercase() ||
                checkpoint.syncEvents.verifiedBaselineId != checkpoint.syncEvents.maxId) return false
            val watermarkValid = sql.query("SELECT ownerUserId, lastSyncEventId FROM sync_event_watermarks WHERE storeScope=?",
                arrayOf(scope.storeId)).use { c ->
                c.moveToFirst() && task126OwnerHash(c.getString(0)) == scope.ownerHash &&
                    c.getLong(1).toString() == checkpoint.syncEvents.maxId && !c.moveToNext()
            }
            if (!watermarkValid) return false
            validateRecoveryCheckpointResourceBounds(checkpoint, DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS)
            validateShopSyncCanonicalReceipt(db, baseline.generationId, checkpoint)
            validatePhysicalSnapshot(db,baseline.generationId,activeStoreHistory=true,
                localOverlay=true, localAcknowledgements=true)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
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
            val replayedHistory = businessWriteOutbox.replay(ownerUserId,selectedShop?.shopId,setOf("HISTORY")) {
                businessScopedRemoteCall { remote.upsertSessions(it.payload.history,it.shop) }
            }
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
            var uploaded = replayedHistory.sumOf { it.payload.revisions.size }
            val uploadedRemoteIds = replayedHistory.flatMap { it.payload.revisions.map { r -> r.remoteId } }.toMutableList()
            for (chunk in candidates.chunked(SESSION_BACKUP_PUSH_CHUNK)) {
                val rows = chunk.map {
                    it.payload.toSharedSheetSessionUpsertRow(ownerUserId, selectedShop?.shopId)
                }
                businessWriteOutbox.execute(ownerUserId,selectedShop?.shopId,BusinessWritePayload("HISTORY",
                    history=rows,revisions=chunk.map { BusinessWriteRevision("HISTORY",it.payload.remoteId,
                        it.ref.localChangeRevision.toLong(),it.payload.payloadFingerprint()) })) {
                    businessScopedRemoteCall { remote.upsertSessions(it.payload.history,it.shop) }
                }.getOrElse { error ->
                    logHistorySessionPushFailure(chunk, error)
                    return@withContext Result.failure(error)
                }
                uploaded += chunk.size
                uploadedRemoteIds += chunk.map { it.payload.remoteId }
            }
            Result.success(
                HistorySessionBackupPushSummary(
                    uploaded = uploaded,
                    skippedAlreadySynced = skippedAlreadySynced,
                    attempted = candidates.size + replayedHistory.sumOf { it.payload.revisions.size },
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
            db.withTransaction { requireLegacyCatalogInboundAllowed(selectedShop?.shopId) }
            val records = businessScopedRemoteCall {
                remote.fetchAllSessionsForOwner(selectedShop?.shopId)
            }
                .getOrElse { return@withContext Result.failure(it) }
            val payloads = records.map { it.toSessionRemotePayload() }
            val result = db.withTransaction {
                requireLegacyCatalogInboundAllowed(selectedShop?.shopId)
                applyRemoteSessionPayloadBatch(payloads)
            }
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
            val canonicalOwned = try {
                db.withTransaction { requireLegacyCatalogInboundAllowed(shopId) }
                false
            } catch (_: CanonicalCatalogInboundRequired) { true }
            val recoveryCache = CatalogConflictRecoveryCache(allowRemoteFetch = !canonicalOwned)
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
                if (!canonicalOwned) realignCatalogBridgesIfNeeded(remote, recoveryCache, shopId)
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
            var completeCatalogSnapshot = false
            var canonicalInbound: CanonicalCatalogInboundRequired? = null
            val remoteAppliedProductIds = linkedSetOf<Long>()
            measureCatalogSyncPhase(CatalogSyncStage.PULL_CATALOG, phaseDurationsMs) {
                val counts = try { pullCatalogFromRemote(remote, progressReporter, shopId) }
                catch (required: CanonicalCatalogInboundRequired) { canonicalInbound = required; null }
                if (counts != null) {
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
            }
            var pushedPrices = 0
            var pulledPrices = 0
            var skippedPullPrices = 0
            var remotePriceRowsEvaluated = 0
            var priceSyncFailed = false
            if (priceRemote.isConfigured) {
                try {
                    measureCatalogSyncPhase(CatalogSyncStage.SYNC_PRICES, phaseDurationsMs) {
                        progressReporter.onProgress(CatalogSyncProgressState.running(
                            if (canonicalInbound == null) CatalogSyncStage.SYNC_PRICES_PULL else CatalogSyncStage.SYNC_PRICES_PUSH))
                        if (canonicalInbound == null) {
                            try {
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
                            } catch (required: CanonicalCatalogInboundRequired) {
                                canonicalInbound = required
                            }
                        }
                        pushedPrices = pushProductPricesToRemote(
                            priceRemote,
                            canonicalOwnerUserId,
                            progressReporter,
                            shopId = shopId
                        ).count
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (contract: ShopSyncContractException) {
                    throw contract
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
            val summary = CatalogSyncSummary(
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
                    fullPriceFetch = priceRemote.isConfigured && canonicalInbound == null,
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
            if (canonicalInbound != null) throw CanonicalCatalogInboundRequired(summary)
            Result.success(summary)
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
            val changedCatalogOutcome = recordOrEnqueueSyncEvent(
                remote = syncEventRemote, ownerUserId = ownerUserId, storeScope = storeScope,
                ids = SyncEventEntityIds(supplierIds = pushedSuppliers.remoteIds.distinct(),
                    categoryIds = pushedCategories.remoteIds.distinct(), productIds = pushedProducts.remoteIds.distinct()),
                domain = SyncEventDomains.CATALOG, eventType = SyncEventTypes.CATALOG_CHANGED,
                batchId = batchId, deviceId = deviceId, shopId = shopId
            )
            val tombstoneCatalogOutcome = recordOrEnqueueSyncEvent(
                remote = syncEventRemote, ownerUserId = ownerUserId, storeScope = storeScope,
                ids = tombstonedIds, domain = SyncEventDomains.CATALOG, eventType = SyncEventTypes.CATALOG_TOMBSTONE,
                batchId = batchId, deviceId = deviceId, shopId = shopId
            )
            val catalogEventOutcome = SyncEventRecordOutcome.from(
                attemptedChunks = changedCatalogOutcome.attemptedChunks + tombstoneCatalogOutcome.attemptedChunks,
                recordedChunks = changedCatalogOutcome.recordedChunks + tombstoneCatalogOutcome.recordedChunks,
                enqueuedChunks = changedCatalogOutcome.enqueuedChunks + tombstoneCatalogOutcome.enqueuedChunks,
                outboxInserted = changedCatalogOutcome.outboxInserted + tombstoneCatalogOutcome.outboxInserted
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
                    manualFullSyncRequired = drain.manualFullSyncRequired,
                    syncEventsOrdinaryPending = drain.ordinaryPending
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
                    manualFullSyncRequired = drain.manualFullSyncRequired,
                    syncEventsOrdinaryPending = drain.ordinaryPending
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
                } catch (required: CanonicalCatalogInboundRequired) {
                    throw required
                } catch (contract: ShopSyncContractException) {
                    throw contract
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

    private suspend fun requireLegacyCatalogInboundAllowed(shopId: String?) {
        requireCurrentBusinessDataScope()
        val baseline = syncRecoveryBaselineDao.get() ?: return
        val binding = businessDataScopeBindingDao.get()
            ?: throw ShopSyncContractException("canonical_catalog_binding_missing")
        val device = syncEventDeviceStateDao.get()
            ?: throw ShopSyncContractException("canonical_catalog_device_missing")
        val lease = coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease
        if (shopId == null || binding.ownerHash != baseline.ownerHash || binding.storeId != baseline.storeScope ||
            shopId.lowercase() != baseline.shopId.lowercase() || device.deviceId != baseline.deviceId ||
            (lease != null && !lease.unmanaged && (lease.boundScope == null ||
                Task126OwnerStoreGate.validate(binding.toOwnerStoreScope(), lease.boundScope) !=
                    Task126OwnerStoreGateDecision.Allowed))) {
            throw ShopSyncContractException("canonical_catalog_scope_mismatch")
        }
        val watermark = db.openHelper.readableDatabase.query(
            "SELECT ownerUserId,lastSyncEventId FROM sync_event_watermarks WHERE storeScope=? LIMIT 2",
            arrayOf(baseline.storeScope)).use { c ->
            if (!c.moveToFirst()) throw ShopSyncContractException("canonical_catalog_watermark_missing")
            val row = SyncEventWatermark(c.getString(0), baseline.storeScope, c.getLong(1))
            if (c.moveToNext() || task126OwnerHash(row.ownerUserId) != binding.ownerHash)
                throw ShopSyncContractException("canonical_catalog_watermark_scope_mismatch")
            row
        }
        val checkpoint = shopSyncBaselineForEventDrain(watermark.ownerUserId, baseline.storeScope,
            shopId, device.deviceId, watermark.lastSyncEventId, baseline, watermark)
            ?: throw ShopSyncContractException("canonical_catalog_baseline_invalid")
        validateShopSyncCanonicalReceipt(db, baseline.generationId, checkpoint)
        requireCurrentBusinessDataScope()
        throw CanonicalCatalogInboundRequired()
    }

    private suspend fun pullCatalogFromRemote(
        remote: CatalogRemoteDataSource,
        progressReporter: CatalogSyncProgressReporter,
        shopId: String?
    ): CatalogPullApplyCounts {
        progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
        db.withTransaction { requireLegacyCatalogInboundAllowed(shopId) }
        val fetchStartedAt = System.currentTimeMillis()
        val bundle = businessScopedRemoteCall { remote.fetchCatalog(shopId) }.getOrThrow()
        val fetchMs = System.currentTimeMillis() - fetchStartedAt
        val applyStartedAt = System.currentTimeMillis()
        val (applyCounts, pruneCounts) = db.withTransaction {
            // A can be published while fetch is suspended. Never apply or prune across that fence.
            requireLegacyCatalogInboundAllowed(shopId)
            val applied = applyCatalogBundleInbound(bundle)
            val pruned = if (bundle.isCompleteSnapshot) {
                reconcileLocalCatalogAfterInboundPull(bundle)
            } else {
                Log.i(TAG, "catalog_prune skipped reason=scoped_catalog_snapshot")
                CatalogPruneCounts()
            }
            applied to pruned
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
        val capturedWatermark = syncEventWatermarkDao.get(ownerUserId, storeScope)
        var watermark = capturedWatermark?.lastSyncEventId ?: 0L
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
        var ordinaryPending = false
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
        var verifiedShopBaselineEntity: SyncRecoveryBaseline? = null
        var verifiedShopBinding: BusinessDataScopeBinding? = null
        var verifiedShopDevice: SyncEventDeviceState? = null
        var verifiedShopWatermark: SyncEventWatermark? = null

        while (iterations < SYNC_EVENT_DRAIN_MAX_ITERATIONS) {
            var shopPageHasMore: Boolean? = null
            val events = if (shopId != null) {
                val reader = shopSyncReadRemoteDataSource
                    ?.takeIf { it.isConfigured }
                    ?: throw ShopSyncContractException("shop_sync_reader_unavailable")
                if (resolvedShopReadScope == null) {
                    val capturedBaseline = syncRecoveryBaselineDao.get()
                    val capturedBinding = businessDataScopeBindingDao.get()
                    val capturedDevice = syncEventDeviceStateDao.get()
                    val baseline = shopSyncBaselineForEventDrain(
                        ownerUserId = ownerUserId,
                        storeScope = storeScope,
                        shopId = shopId,
                        deviceId = deviceId,
                        watermark = watermark,
                        capturedBaseline = capturedBaseline,
                        capturedWatermark = capturedWatermark
                    )
                    if ((watermark > 0L || capturedBaseline != null) && baseline == null) {
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
                    if (baseline != null && capturedBaseline != null &&
                        parseShopSyncMaxEventId(checkpoint.syncEvents.maxId) > watermark) {
                        return drainVerifiedShopSyncWindow(
                            reader, ownerUserId, storeScope, shopId, deviceId, watermark,
                            capturedBaseline, capturedBinding, capturedDevice, requireNotNull(capturedWatermark),
                            baseline, checkpoint
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
                    verifiedShopBaselineEntity = capturedBaseline
                    verifiedShopBinding = capturedBinding
                    verifiedShopDevice = capturedDevice
                    verifiedShopWatermark = capturedWatermark
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
            val localPending = ordinaryShopSyncPendingCount(ownerUserId, storeScope)
            if (localPending > 0) {
                skippedDirty += localPending
            } else {
                val journalPending = syncRecoveryJournalDao.getForScope(
                    ownerHash = task126OwnerHash(ownerUserId),
                    storeScope = Task126OwnerStoreScope.normalizedStoreId(storeScope)
                ) != null
                val outboxPending = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
                if (outboxPending > 0) {
                    skippedDirty += outboxPending
                } else if (journalPending) {
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
                    var markerError = OrdinaryProofErrorCode.NONE
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
                    } catch (failure: Exception) {
                        val classification = SyncErrorClassifier.classify(failure)
                        if ((failure is ShopSyncContractException &&
                                failure.code.startsWith("shop_sync_rpc_http_")) ||
                            classification.httpStatus != null ||
                            classification.category == SyncErrorCategory.NetworkOfflineOrTimeout
                        ) {
                            throw failure
                        }
                        markerError = OrdinaryProofErrorCode.fromFailure(failure)
                        Log.w(TAG, "ordinary_convergence_proof branch=no_work stage=marker_error " +
                            "errorCode=${markerError.wireValue}")
                        null
                    }
                    // With no new captured window, the existing A receipt must still
                    // prove the complete physical store. A ready server marker alone
                    // cannot conceal local corruption or publish no-work.
                    var localReceiptProved = false
                    var advancedTooLarge = false
                    var localError = OrdinaryProofErrorCode.NONE
                    val captured = verifiedShopBaselineEntity
                    val baseline = verifiedShopBaseline
                    if (captured != null && baseline != null) {
                        try {
                            db.withTransaction {
                                requireCurrentBusinessDataScope()
                                if (syncRecoveryBaselineDao.get() != captured ||
                                    businessDataScopeBindingDao.get() != verifiedShopBinding ||
                                    syncEventDeviceStateDao.get() != verifiedShopDevice ||
                                    syncEventWatermarkDao.get(ownerUserId, storeScope) != verifiedShopWatermark ||
                                    syncRecoveryJournalDao.get() != null) {
                                    throw ShopSyncContractException("ordinary_captured_publication_changed")
                                }
                                val commitPending = ordinaryShopSyncPendingCount(ownerUserId, storeScope)
                                if (commitPending > 0) throw OrdinaryShopSyncDeferred(commitPending)
                                validateShopSyncActiveReceipt(db, captured.generationId, baseline)
                                if (markerIsOrdinaryAdvanceFrom(marker, baseline, watermark)) {
                                    advancedTooLarge = requireNotNull(marker?.syncEvents?.inspectedCount) > SHOP_SYNC_ORDINARY_MAX_EVENTS
                                    ordinaryPending = !advancedTooLarge
                                }
                                coroutineContext.ensureActive()
                                requireCurrentBusinessDataScope()
                            }
                            localReceiptProved = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (deferred: OrdinaryShopSyncDeferred) {
                            skippedDirty += deferred.pendingCount
                        } catch (failure: ShopSyncContractException) {
                            if (failure.code == "ordinary_captured_publication_changed") throw failure
                            localError = OrdinaryProofErrorCode.fromFailure(failure)
                            Log.w(TAG, "ordinary_convergence_proof branch=no_work stage=local_receipt_error " +
                                "errorCode=${localError.wireValue}")
                        }
                    }
                    var markerProof = OrdinaryMarkerProof.NOT_EVALUATED
                    if (skippedDirty == 0 && !ordinaryPending && (!localReceiptProved ||
                        markerProofAgainstBaseline(marker, verifiedShopBaseline, watermark)
                            .also { markerProof = it } != OrdinaryMarkerProof.PROVED)) {
                        try {
                            db.withTransaction {
                                requireCurrentBusinessDataScope()
                                if (captured != null && (syncRecoveryBaselineDao.get() != captured ||
                                    businessDataScopeBindingDao.get() != verifiedShopBinding ||
                                    syncEventDeviceStateDao.get() != verifiedShopDevice ||
                                    syncEventWatermarkDao.get(ownerUserId, storeScope) != verifiedShopWatermark)) {
                                    throw ShopSyncContractException("ordinary_captured_publication_changed")
                                }
                                val pending = ordinaryShopSyncPendingCount(ownerUserId, storeScope)
                                if (pending > 0) throw OrdinaryShopSyncDeferred(pending)
                                recordShopSyncRecoveryRequiredWithoutEvent(
                                    ownerUserId = ownerUserId, storeScope = storeScope, shopId = shopId,
                                    deviceId = deviceId, reason = if (advancedTooLarge) SyncEventApplyStatusReasons.DRAIN_LIMIT_REACHED
                                        else SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED
                                )
                            }
                            // The journal transaction committed; deferred/stale attempts stay silent.
                            val markerEvaluated = markerProof != OrdinaryMarkerProof.NOT_EVALUATED
                            Log.w(TAG, "ordinary_convergence_proof branch=no_work stage=recovery_required " +
                                "localReceipt=$localReceiptProved markerEvaluated=$markerEvaluated " +
                                "markerResult=${if (markerEvaluated) "false" else "not_evaluated"} " +
                                "firstFalse=${if (localReceiptProved) markerProof.wireValue else "local_receipt"} " +
                                "markerError=${markerError.wireValue} localError=${localError.wireValue}")
                            gapDetected = true
                            manualFullSyncRequired = true
                            tooLarge = tooLarge || advancedTooLarge
                        } catch (deferred: OrdinaryShopSyncDeferred) {
                            skippedDirty += deferred.pendingCount
                        }
                    }
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
            ordinaryPending = ordinaryPending,
            skippedProtectedLocalCommit = skippedProtectedLocalCommit,
            remoteAppliedProductIds = remoteAppliedProductIds
        )
    }

    private class OrdinaryShopSyncDeferred(val pendingCount: Int) : IllegalStateException()

    /** Count known local work without loading every candidate UID or body. Synced orphans remain proof failures. */
    private suspend fun ordinaryShopSyncPendingCount(ownerUserId: String, storeScope: String): Int {
        val scopedOutbox = syncEventOutboxDao.countPendingForScope(ownerUserId, storeScope)
        if (syncEventOutboxDao.countAll() != scopedOutbox) {
            throw ShopSyncContractException("ordinary_outbox_scope_mismatch")
        }
        val local = db.openHelper.readableDatabase.query(
            """
            SELECT
                (SELECT COUNT(*) FROM pending_catalog_tombstones) +
                (SELECT COUNT(*) FROM suppliers p LEFT JOIN supplier_remote_refs r ON r.supplierId=p.id
                 WHERE r.id IS NULL OR r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision<>r.lastSyncedLocalRevision) +
                (SELECT COUNT(*) FROM categories p LEFT JOIN category_remote_refs r ON r.categoryId=p.id
                 WHERE r.id IS NULL OR r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision<>r.lastSyncedLocalRevision) +
                (SELECT COUNT(*) FROM products p LEFT JOIN product_remote_refs r ON r.productId=p.id
                 WHERE r.id IS NULL OR r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision<>r.lastSyncedLocalRevision) +
                (SELECT COUNT(*) FROM product_prices p LEFT JOIN product_price_remote_refs r ON r.productPriceId=p.id
                 WHERE r.id IS NULL) +
                (SELECT COUNT(*) FROM history_entries h LEFT JOIN history_entry_remote_refs r ON r.historyEntryUid=h.uid
                 WHERE r.localChangeRevision<>r.lastSyncedLocalRevision OR h.syncStatus<>'SYNCED_SUCCESSFULLY')
            """.trimIndent()
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
        return Math.addExact(local, scopedOutbox)
    }

    /** Prepare a complete captured window, then publish physical data, full ledger, original C and watermark together. */
    private suspend fun drainVerifiedShopSyncWindow(
        reader: ShopSyncReadRemoteDataSource,
        ownerUserId: String,
        storeScope: String,
        shopId: String,
        deviceId: String,
        watermark: Long,
        capturedBaseline: SyncRecoveryBaseline,
        capturedBinding: BusinessDataScopeBinding?,
        capturedDevice: SyncEventDeviceState?,
        capturedWatermark: SyncEventWatermark,
        baseline: ShopSyncRecoveryCheckpoint,
        checkpoint: ShopSyncRecoveryCheckpoint
    ): SyncEventDrainResult {
        val events = ArrayList<SyncEventRemoteRow>(SHOP_SYNC_ORDINARY_MAX_EVENTS)
        var prepared: PreparedShopSyncOrdinaryWindow? = null
        var initialProofComplete = false
        fun result(
            processed: Int = 0,
            skippedSelf: Int = 0,
            dirty: Int = 0,
            after: Long = watermark,
            applied: Int = 0,
            historyApplied: Int = 0,
            manual: Boolean = false,
            tooLarge: Boolean = false,
            productIds: Set<Long> = emptySet()
        ) = SyncEventDrainResult(
            fetched = events.size, processed = processed, skippedSelf = skippedSelf,
            skippedDirtyLocal = dirty, watermarkBefore = watermark, watermarkAfter = after,
            targetedProductsFetched = prepared?.rows?.get(ShopSyncRowDomain.PRODUCTS)?.size ?: 0,
            targetedPricesFetched = prepared?.rows?.get(ShopSyncRowDomain.PRICES)?.size ?: 0,
            targetedHistoryFetched = prepared?.rows?.get(ShopSyncRowDomain.HISTORY)?.size ?: 0,
            remoteUpdatesApplied = applied, remoteHistoryUpdatesApplied = historyApplied,
            tooLarge = tooLarge, gapDetected = manual, manualFullSyncRequired = manual,
            remoteAppliedProductIds = productIds
        )
        suspend fun requireCapturedPublication() {
            requireCurrentBusinessDataScope()
            if (syncRecoveryBaselineDao.get() != capturedBaseline ||
                businessDataScopeBindingDao.get() != capturedBinding ||
                syncEventDeviceStateDao.get() != capturedDevice ||
                syncEventWatermarkDao.get(ownerUserId, storeScope) != capturedWatermark ||
                syncRecoveryJournalDao.get() != null) {
                // Retry from the new receipt; never overwrite it with stale preparation or a stale latch.
                throw ShopSyncContractException("ordinary_captured_publication_changed")
            }
        }
        try {
            requireCapturedPublication()
            val pending = ordinaryShopSyncPendingCount(ownerUserId, storeScope)
            if (pending > 0) return result(dirty = pending)
            validateRecoveryCheckpointResourceBounds(checkpoint, DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS)
            if (checkpoint.status != "ready" || checkpoint.scope != baseline.scope ||
                checkpoint.shopId.lowercase() != shopId.lowercase() ||
                checkpoint.syncEvents.verifiedBaselineId != watermark.toString() ||
                checkpoint.integrity.totalViolationCount != 0L) {
                throw ShopSyncContractException("ordinary_checkpoint_scope_mismatch")
            }
            val maximum = parseShopSyncMaxEventId(checkpoint.syncEvents.maxId)
            if (checkpoint.syncEvents.domainMaxIds.keys != setOf(SyncEventDomains.CATALOG, SyncEventDomains.PRICES, SyncEventDomains.HISTORY)) {
                throw ShopSyncContractException("ordinary_domain_fence_missing")
            }
            checkpoint.syncEvents.domainMaxIds.forEach { (domain, fence) ->
                val previous = baseline.syncEvents.domainMaxIds[domain]
                    ?: throw ShopSyncContractException("ordinary_domain_fence_missing")
                val current = parseShopSyncMaxEventId(fence)
                if (current < parseShopSyncMaxEventId(previous) || current > maximum) {
                    throw ShopSyncContractException("ordinary_domain_fence_invalid")
                }
            }
            var cursor = watermark
            var responseBytes = 0L
            do {
                coroutineContext.ensureActive()
                val page = businessScopedRemoteCall {
                    reader.eventPage(
                        ShopSyncRpcContext(ownerUserId, shopId, deviceId,
                            expectedScope = checkpoint.scope, expectedEventMaxId = checkpoint.syncEvents.maxId),
                        cursor, SYNC_EVENT_FETCH_LIMIT.toInt()
                    )
                }.getOrThrow()
                if (!initialProofComplete) {
                    // Fetch under the captured fence, then prove canonical G0 before trusting or preparing any rows.
                    validateShopSyncCanonicalReceipt(db, capturedBaseline.generationId, baseline)
                    validateRecoveryScopeIdentity(checkpoint.scope, ownerUserId, deviceId)
                    if (capturedBinding == null || capturedBinding.ownerHash != capturedBaseline.ownerHash ||
                        capturedBinding.storeId != capturedBaseline.storeScope || capturedDevice?.deviceId != deviceId) {
                        throw ShopSyncContractException("ordinary_checkpoint_scope_mismatch")
                    }
                    initialProofComplete = true
                }
                validateTailPage(page, checkpoint, shopId, cursor, SYNC_EVENT_FETCH_LIMIT.toInt())
                if (page.scopeEventMaxId != checkpoint.syncEvents.maxId || page.responseBytes < 0L) {
                    throw ShopSyncContractException("ordinary_event_fence_changed")
                }
                responseBytes = Math.addExact(responseBytes, page.responseBytes)
                if (responseBytes > DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.defaultPageResponseBytes ||
                    events.size + page.rows.size > SHOP_SYNC_ORDINARY_MAX_EVENTS) {
                    throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
                }
                if (page.rows.isEmpty()) throw ShopSyncContractException("ordinary_event_window_incomplete")
                page.rows.forEach { event ->
                    if (event.id <= cursor || event.id > maximum) {
                        throw ShopSyncContractException("ordinary_event_order_invalid")
                    }
                    validateTailEvent(event, checkpoint, shopId)
                    events += event
                    cursor = event.id
                }
                if (page.hasMore && page.nextAfterId != cursor) {
                    throw ShopSyncContractException("ordinary_event_cursor_invalid")
                }
                if (!page.hasMore && cursor != maximum) {
                    throw ShopSyncContractException("ordinary_event_window_incomplete")
                }
            } while (page.hasMore)
            prepared = prepareShopSyncOrdinaryWindow(db, capturedBaseline.generationId, checkpoint, events) { domain, ids ->
                businessScopedRemoteCall {
                    reader.rowsByIds(
                        shopSyncTargetedContext(ownerUserId, shopId, deviceId, checkpoint.scope,
                            checkpoint.syncEvents.maxId, checkpoint.syncEvents.domainMaxIds, domain.syncEventDomain()),
                        domain, ids
                    )
                }.getOrThrow()
            }
            val marker = businessScopedRemoteCall {
                reader.convergenceMarker(
                    ShopSyncRpcContext(ownerUserId, shopId, deviceId,
                        expectedScope = checkpoint.scope, verifiedBaselineId = checkpoint.syncEvents.maxId,
                        expectedBaselineScopeKey = checkpoint.scope.key)
                )
            }.getOrThrow()
            // Material equality is strict. C's opaque digest is deliberately not compared with A's digest.
            val published = publishedBaselineFromMarker(checkpoint, marker)
            val window = requireNotNull(prepared)
            var applied = 0
            var historyApplied = 0
            val appliedProductIds = linkedSetOf<Long>()
            db.withTransaction {
                requireCapturedPublication()
                val commitPending = ordinaryShopSyncPendingCount(ownerUserId, storeScope)
                if (commitPending > 0) throw OrdinaryShopSyncDeferred(commitPending)
                validateShopSyncCanonicalReceipt(db, capturedBaseline.generationId, baseline)
                coroutineContext.ensureActive()
                // Update the full ledger first, so the same-generation parent proof sees the new product state.
                for ((domain, rows) in window.rows) {
                    syncRecoveryManifestDao.upsertAll(rows.toManifestRows(capturedBaseline.generationId, domain))
                    // The validated ordinary writer now owns the current remote body for these IDs.
                    syncRecoveryManifestDao.deleteByRemoteIds(capturedBaseline.generationId,
                        LOCAL_ACK_BODY_PREFIX + domain.wireValue, rows.ids())
                }
                window.removedImageProductIds.chunked(500).forEach { ids ->
                    syncRecoveryManifestDao.deleteByRemoteIds(capturedBaseline.generationId,
                        ShopSyncRowDomain.IMAGES.wireValue, ids)
                }
                for (domain in ShopSyncRowDomain.entries) {
                    val rows = window.rows[domain] ?: continue
                    val eligible = materializableRecoveryRows(db, capturedBaseline.generationId, rows)
                    val counts = applyShopSyncRecoveryRows(eligible)
                    if (counts.failedRows != 0 || counts.unsupportedRows != 0 || counts.skippedParentRows != 0) {
                        throw ShopSyncContractException("ordinary_mapper_apply_failed")
                    }
                    when (domain) {
                        ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS ->
                            applied += counts.businessRowsApplied
                        ShopSyncRowDomain.HISTORY -> historyApplied += counts.businessRowsApplied
                        else -> Unit
                    }
                }
                validateShopSyncActiveReceipt(db, capturedBaseline.generationId, published)
                DefaultInventoryRepositoryTestHooks.afterOrdinaryShopSyncWrites?.invoke()
                coroutineContext.ensureActive()
                requireCapturedPublication()
                // No RPC occurs in this writer transaction. The C receipt came from the completed prepared window.
                syncRecoveryBaselineDao.upsert(capturedBaseline.copy(
                    checkpointJson = encodeRecoveryCheckpointJson(published), activatedAtMs = System.currentTimeMillis()
                ))
                for (event in events) {
                    val self = event.sourceDeviceId == deviceId || event.sourceDeviceKey == syncEventDeviceKey(deviceId)
                    recordSyncEventApplyStatus(ownerUserId, storeScope, event, event.entityIds,
                        if (self) SyncEventApplyStatusValues.SKIPPED else SyncEventApplyStatusValues.APPLIED,
                        if (self) SyncEventApplyStatusReasons.SELF_ORIGIN else SyncEventApplyStatusReasons.APPLIED)
                }
                advanceSyncEventWatermark(ownerUserId, storeScope, maximum)
                (window.rows[ShopSyncRowDomain.PRODUCTS] as? ShopSyncRows.Products)?.values
                    .orEmpty().filter { it.deletedAt == null }.forEach { row ->
                        productRemoteRefDao.getByRemoteId(row.id)?.productId?.let(appliedProductIds::add)
                    }
                requireCurrentBusinessDataScope()
                coroutineContext.ensureActive()
            }
            val selfCount = events.count { it.sourceDeviceId == deviceId || it.sourceDeviceKey == syncEventDeviceKey(deviceId) }
            return result(processed = events.size - selfCount, skippedSelf = selfCount, after = maximum,
                applied = applied, historyApplied = historyApplied, productIds = appliedProductIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (deferred: OrdinaryShopSyncDeferred) {
            return result(dirty = deferred.pendingCount)
        } catch (failure: ShopSyncContractException) {
            if (failure.code.startsWith("shop_sync_rpc_http_") ||
                failure.code == "ordinary_captured_publication_changed"
            ) throw failure
            val bound = failure.code == "ordinary_prepared_window_bound_exceeded"
            val reason = when {
                initialProofComplete && failure.code == "ordinary_targeted_missing_remote" -> SyncEventApplyStatusReasons.MISSING_REMOTE
                failure.code.contains("scope_mismatch") -> SyncEventApplyStatusReasons.SCOPE_MISMATCH
                bound -> SyncEventApplyStatusReasons.DRAIN_LIMIT_REACHED
                else -> SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED
            }
            try {
                db.withTransaction {
                    requireCapturedPublication()
                    val pending = ordinaryShopSyncPendingCount(ownerUserId, storeScope)
                    if (pending > 0) throw OrdinaryShopSyncDeferred(pending)
                    recordShopSyncRecoveryRequiredWithoutEvent(ownerUserId, storeScope, shopId, deviceId, reason,
                        events.firstOrNull()?.id)
                }
            } catch (deferred: OrdinaryShopSyncDeferred) {
                return result(dirty = deferred.pendingCount)
            }
            val diagnosticCode = OrdinaryProofErrorCode.fromFailure(failure)
            Log.w(TAG, "ordinary_convergence_proof branch=incremental_window stage=recovery_required " +
                "errorCode=${diagnosticCode.wireValue} initialProofComplete=$initialProofComplete " +
                "eventsPresent=${events.isNotEmpty()}")
            return result(manual = true, tooLarge = bound)
        }
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
        watermark: Long,
        capturedBaseline: SyncRecoveryBaseline?,
        capturedWatermark: SyncEventWatermark?
    ): ShopSyncRecoveryCheckpoint? {
        // Zero is a valid activated fence only when its scoped row really exists.
        if (capturedWatermark == null || capturedWatermark.ownerUserId != ownerUserId ||
            capturedWatermark.storeScope != storeScope || capturedWatermark.lastSyncEventId != watermark) return null
        val baseline = capturedBaseline ?: return null
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
            checkpoint.scope.kind != baseline.scopeKind ||
            checkpoint.shopId.lowercase() != shopId.lowercase() ||
            checkpoint.syncEvents.maxId != watermark.toString() ||
            // A persisted recovery baseline must be the C receipt obtained
            // after marker(B): it is self-verifying at the activated
            // watermark. Raw checkpoint B used A as its query baseline and
            // would otherwise re-latch recovery on every next drain.
            checkpoint.syncEvents.verifiedBaselineId != watermark.toString()
        ) {
            return null
        }
        if (runCatching { validateRecoveryScopeIdentity(checkpoint.scope, ownerUserId, deviceId) }.isFailure) return null
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

    private enum class OrdinaryMarkerProof {
        NOT_EVALUATED, PROVED, BASELINE_ABSENT, MARKER_ABSENT, STATUS_NOT_READY,
        SERVER_NOT_ELIGIBLE, SHOP_MISMATCH, SCOPE_MISMATCH, MAX_ID_MISMATCH,
        VERIFIED_BASELINE_MISMATCH, DOMAIN_MAX_IDS_MISMATCH, CHECKPOINT_DIGEST_MISMATCH,
        FULL_RECOVERY_REQUIRED, CATALOG_MISMATCH, PRICES_MISMATCH, HISTORY_MISMATCH,
        IMAGES_MISMATCH, INTEGRITY_VIOLATION;

        val wireValue: String get() = name.lowercase(Locale.ROOT)
    }

    /** Same short-circuit order as the original Boolean proof; no additional reads. */
    private fun markerIsOrdinaryAdvanceFrom(
        marker: ShopSyncConvergenceMarker?, baseline: ShopSyncRecoveryCheckpoint, watermark: Long
    ): Boolean {
        if (marker == null || marker.schemaVersion != "shop-sync-convergence-marker-v1" || marker.status != "ready" ||
            marker.shopId != baseline.shopId || marker.scope != baseline.scope ||
            marker.syncEvents.verifiedBaselineId != watermark.toString() || !marker.hasCompleteOrdinaryAdvanceScan()) return false
        val maximum = runCatching { parseShopSyncMaxEventId(marker.syncEvents.maxId) }.getOrNull() ?: return false
        if (maximum <= watermark || marker.syncEvents.domainMaxIds.keys != baseline.syncEvents.domainMaxIds.keys) return false
        return marker.syncEvents.domainMaxIds.all { (domain, value) ->
            val current = runCatching { parseShopSyncMaxEventId(value) }.getOrNull()
            val previous = runCatching { parseShopSyncMaxEventId(baseline.syncEvents.domainMaxIds.getValue(domain)) }.getOrNull()
            current != null && previous != null && current in previous..maximum
        }
    }

    private fun markerProofAgainstBaseline(
        marker: ShopSyncConvergenceMarker?,
        baseline: ShopSyncRecoveryCheckpoint?,
        watermark: Long
    ): OrdinaryMarkerProof {
        val expected = baseline ?: return OrdinaryMarkerProof.BASELINE_ABSENT
        val expectedWatermark = watermark.toString()
        return when {
            marker == null -> OrdinaryMarkerProof.MARKER_ABSENT
            marker.status != "ready" -> OrdinaryMarkerProof.STATUS_NOT_READY
            !marker.serverNoWorkEligible -> OrdinaryMarkerProof.SERVER_NOT_ELIGIBLE
            marker.shopId != expected.shopId -> OrdinaryMarkerProof.SHOP_MISMATCH
            marker.scope != expected.scope -> OrdinaryMarkerProof.SCOPE_MISMATCH
            marker.syncEvents.maxId != expectedWatermark -> OrdinaryMarkerProof.MAX_ID_MISMATCH
            marker.syncEvents.verifiedBaselineId != expectedWatermark -> OrdinaryMarkerProof.VERIFIED_BASELINE_MISMATCH
            marker.syncEvents.domainMaxIds != expected.syncEvents.domainMaxIds -> OrdinaryMarkerProof.DOMAIN_MAX_IDS_MISMATCH
            marker.checkpointDigest != expected.checkpointDigest -> OrdinaryMarkerProof.CHECKPOINT_DIGEST_MISMATCH
            marker.syncEvents.requiresFullRecovery -> OrdinaryMarkerProof.FULL_RECOVERY_REQUIRED
            marker.catalog != expected.catalog -> OrdinaryMarkerProof.CATALOG_MISMATCH
            marker.prices != expected.prices -> OrdinaryMarkerProof.PRICES_MISMATCH
            marker.history != expected.history -> OrdinaryMarkerProof.HISTORY_MISMATCH
            marker.images != expected.images -> OrdinaryMarkerProof.IMAGES_MISMATCH
            marker.integrity.totalViolationCount != 0L -> OrdinaryMarkerProof.INTEGRITY_VIOLATION
            else -> OrdinaryMarkerProof.PROVED
        }
    }

    /** Exact closed codes only: arbitrary exception codes/messages never enter the log. */
    private enum class OrdinaryProofErrorCode {
        NONE, OTHER_CONTRACT, OTHER_EXCEPTION, ILLEGAL_STATE, ILLEGAL_ARGUMENT, SQLITE, SERIALIZATION,
        BASELINE_SCOPE_CONTEXT_MISMATCH, BASELINE_SCOPE_KEY_MISMATCH,
        BLOCKING_EVENT_ID_INVALID, CATALOG_DIGEST_INVALID,
        CHECKPOINT_COUNT_INVALID, CHECKPOINT_COUNT_OVERFLOW_CATEGORIES,
        CHECKPOINT_COUNT_OVERFLOW_HISTORY, CHECKPOINT_COUNT_OVERFLOW_IMAGES,
        CHECKPOINT_COUNT_OVERFLOW_PRICES, CHECKPOINT_COUNT_OVERFLOW_PRODUCTS,
        CHECKPOINT_COUNT_OVERFLOW_SUPPLIERS, CHECKPOINT_DIGEST_INVALID,
        CHECKPOINT_INTEGRITY_BLOCKED, CHECKPOINT_INVALID_BASELINE,
        CHECKPOINT_NOT_READY, CHECKPOINT_RESOURCE_EXCEEDED,
        CHECKPOINT_ROW_BUDGET_EXCEEDED_CATEGORIES, CHECKPOINT_ROW_BUDGET_EXCEEDED_HISTORY,
        CHECKPOINT_ROW_BUDGET_EXCEEDED_IMAGES, CHECKPOINT_ROW_BUDGET_EXCEEDED_PRICES,
        CHECKPOINT_ROW_BUDGET_EXCEEDED_PRODUCTS, CHECKPOINT_ROW_BUDGET_EXCEEDED_SUPPLIERS,
        CHECKPOINT_STATUS_UNSUPPORTED, CHECKPOINT_TOTAL_COUNT_OVERFLOW,
        CHECKPOINT_TOTAL_ROW_BUDGET_EXCEEDED, CHECKPOINT_TRACE_BODY_REPLAY,
        CHECKPOINT_TRACE_REJECTED, CHECKPOINT_TRACE_STALE,
        CONVERGENCE_MARKER_BASELINE_MISMATCH, CONVERGENCE_MARKER_INTEGRITY_VIOLATION,
        CONVERGENCE_MARKER_NOT_ELIGIBLE, DEVICE_IDENTITY_INVALID,
        DEVICE_KEY_INVALID, DOMAIN_EVENT_MAX_AFTER_GLOBAL,
        DOMAIN_EVENT_MAX_ID_INVALID, DOMAIN_EVENT_MAX_IDS_INVALID,
        EVENT_AS_OF_MAX_ID_MISMATCH, EVENT_BOOTSTRAP_FENCE_MISSING,
        EVENT_CURSOR_INVALID, EVENT_CURSOR_NOT_INCREASING,
        EVENT_CURSOR_STALLED, EVENT_DOMAIN_MAX_AFTER_AS_OF,
        EVENT_DOMAIN_MAX_IDS_INVALID, EVENT_LIMIT_INVALID,
        EVENT_LIMIT_MISMATCH, EVENT_MAX_BEFORE_BASELINE,
        EVENT_MAX_ID_INVALID, EVENT_MAX_ID_NONCANONICAL,
        EVENT_MAX_ID_OVERFLOW, EVENT_SCOPE_FENCE_MISSING,
        EVENT_SCOPE_MAX_BEFORE_AS_OF, EVENT_TERMINAL_CURSOR_PRESENT,
        EVENT_TIMESTAMP_INVALID, EXPECTED_BASELINE_SCOPE_KEY_MISSING,
        HISTORY_OWNER_MISSING, HISTORY_ROW_RESPONSE_BUDGET_EXCEEDED,
        HISTORY_ROW_RESPONSE_SIZE_INVALID, ID_SET_DIGEST_INVALID,
        IDENTITY_DIGEST_INVALID, INTEGRITY_COUNT_INVALID,
        LEGACY_OWNER_KEY_INVALID, LEGACY_OWNER_KEY_MISSING,
        LOCAL_ACK_IDENTITY_INVALID, LOCAL_ACK_PHYSICAL_BODY_MISMATCH,
        LOCAL_ACK_SCOPE_MISMATCH, MARKER_CATALOG_DIGEST_INVALID,
        MARKER_CHECKPOINT_DIGEST_INVALID, MARKER_DIGEST_INVALID,
        MARKER_INTEGRITY_BLOCKED, MARKER_INVALID_BASELINE,
        MARKER_PRODUCT_IDENTITY_DIGEST_INVALID, MARKER_PRODUCT_IDENTITY_DIGEST_MISSING,
        MARKER_RESOURCE_EXCEEDED, MARKER_STATUS_UNSUPPORTED,
        ORDINARY_CANONICAL_RECEIPT_INVALID, ORDINARY_CAPTURED_PUBLICATION_CHANGED,
        ORDINARY_CHECKPOINT_SCOPE_MISMATCH, ORDINARY_DOMAIN_FENCE_INVALID,
        ORDINARY_DOMAIN_FENCE_MISSING, ORDINARY_EVENT_CURSOR_INVALID,
        ORDINARY_EVENT_FENCE_CHANGED, ORDINARY_EVENT_ORDER_INVALID,
        ORDINARY_EVENT_WINDOW_INCOMPLETE, ORDINARY_HISTORY_SHADOW_MANIFEST_INVALID,
        ORDINARY_HISTORY_SHADOW_MANIFEST_MISSING, ORDINARY_HISTORY_SHADOW_REF_INVALID,
        ORDINARY_HISTORY_SHADOW_TOMBSTONE_INVALID, ORDINARY_IMAGE_PRODUCT_MISMATCH,
        ORDINARY_IMAGE_PRODUCT_MISSING, ORDINARY_MAPPER_APPLY_FAILED,
        ORDINARY_PREPARED_WINDOW_BOUND_EXCEEDED, ORDINARY_TARGETED_DOMAIN_MISMATCH,
        ORDINARY_TARGETED_FENCE_CHANGED, ORDINARY_TARGETED_MATERIAL_CHANGED,
        ORDINARY_TARGETED_MISSING_REMOTE, PAGE_CURSOR_NOT_INCREASING,
        PAGE_CURSOR_STALLED, PAGE_DOMAIN_EVENT_MAX_ID_MISMATCH,
        PAGE_DOMAIN_EVENT_REGRESSED, PAGE_DOMAIN_FENCE_AFTER_EVENT_FENCE,
        PAGE_DOMAIN_FENCE_MISSING, PAGE_DOMAIN_MISMATCH,
        PAGE_DOMAIN_SCOPE_MISMATCH, PAGE_EVENT_FENCE_MISSING,
        PAGE_LIMIT_INVALID, PAGE_LIMIT_MISMATCH,
        PAGE_NEXT_CURSOR_MISSING, PAGE_OVERFULL,
        PAGE_SCOPE_EVENT_REGRESSED, PAGE_SCOPE_FENCE_MISSING,
        PAGE_SNAPSHOT_EVENT_MAX_ID_MISMATCH, PAGE_TERMINAL_CURSOR_PRESENT,
        PRODUCT_IDENTITY_DIGEST_INVALID, PRODUCT_IDENTITY_DIGEST_MISSING,
        RECOVERY_ACTIVATION_HEADROOM_SIZE_INVALID, RECOVERY_CATALOG_DIGEST_MISMATCH,
        RECOVERY_CONVERGENCE_MARKER_MISMATCH, RECOVERY_COUNT_MISSING,
        RECOVERY_CURSOR_TYPE_UNSUPPORTED, RECOVERY_DOMAIN_RESPONSE_BUDGET_EXCEEDED,
        RECOVERY_ENTITY_ID_INVALID, RECOVERY_HISTORY_MANIFEST_INVALID,
        RECOVERY_HISTORY_MANIFEST_PAYLOAD_VERSION_INVALID, RECOVERY_IMAGE_MANIFEST_INVALID,
        RECOVERY_IMAGE_METADATA_INVALID, RECOVERY_IMAGE_PRODUCT_INVALID,
        RECOVERY_IMAGE_PRODUCT_STATE_MISMATCH, RECOVERY_IMAGE_SET_INCOMPLETE,
        RECOVERY_IMAGE_STATUS_INVALID, RECOVERY_INTEGRITY_CHECK_FAILED,
        RECOVERY_LIVE_ROW_BUDGET_EXCEEDED_CATEGORIES, RECOVERY_LIVE_ROW_BUDGET_EXCEEDED_HISTORY,
        RECOVERY_LIVE_ROW_BUDGET_EXCEEDED_IMAGES, RECOVERY_LIVE_ROW_BUDGET_EXCEEDED_PRICES,
        RECOVERY_LIVE_ROW_BUDGET_EXCEEDED_PRODUCTS, RECOVERY_LIVE_ROW_BUDGET_EXCEEDED_SUPPLIERS,
        RECOVERY_LIVE_ROW_COUNT_INVALID, RECOVERY_LIVE_TOTAL_ROW_BUDGET_EXCEEDED,
        RECOVERY_MANIFEST_DIGEST_MISMATCH_CATEGORIES, RECOVERY_MANIFEST_DIGEST_MISMATCH_HISTORY,
        RECOVERY_MANIFEST_DIGEST_MISMATCH_IMAGES, RECOVERY_MANIFEST_DIGEST_MISMATCH_PRICES,
        RECOVERY_MANIFEST_DIGEST_MISMATCH_PRODUCTS, RECOVERY_MANIFEST_DIGEST_MISMATCH_SUPPLIERS,
        RECOVERY_MARKER_CHECKPOINT_DIGEST_INVALID, RECOVERY_OVERLAY_SOURCE_COUNT_MISMATCH,
        RECOVERY_PAGE_RESPONSE_BUDGET_EXCEEDED, RECOVERY_PHYSICAL_COUNT_MISMATCH_CATEGORIES,
        RECOVERY_PHYSICAL_COUNT_MISMATCH_HISTORY, RECOVERY_PHYSICAL_COUNT_MISMATCH_IMAGES,
        RECOVERY_PHYSICAL_COUNT_MISMATCH_PRICES, RECOVERY_PHYSICAL_COUNT_MISMATCH_PRODUCTS,
        RECOVERY_PHYSICAL_COUNT_MISMATCH_SUPPLIERS, RECOVERY_PHYSICAL_DIGEST_MISMATCH_CATEGORIES,
        RECOVERY_PHYSICAL_DIGEST_MISMATCH_HISTORY, RECOVERY_PHYSICAL_DIGEST_MISMATCH_IMAGES,
        RECOVERY_PHYSICAL_DIGEST_MISMATCH_PRICES, RECOVERY_PHYSICAL_DIGEST_MISMATCH_PRODUCTS,
        RECOVERY_PHYSICAL_DIGEST_MISMATCH_SUPPLIERS, RECOVERY_PHYSICAL_DOMAIN_INVALID,
        RECOVERY_PHYSICAL_HISTORY_DIRTY, RECOVERY_PHYSICAL_HISTORY_STATE_MISSING,
        RECOVERY_PHYSICAL_HISTORY_V2_PAYLOAD_MISMATCH, RECOVERY_PHYSICAL_REF_DIRTY,
        RECOVERY_PRICE_CANONICAL_INVALID, RECOVERY_PRICE_CANONICAL_MISSING,
        RECOVERY_PRICE_PARENT_LOOKUP_BOUND_EXCEEDED, RECOVERY_PRICE_PARENT_MANIFEST_INVALID,
        RECOVERY_PRICE_PARENT_MANIFEST_MISSING, RECOVERY_PRICE_TYPE_INVALID,
        RECOVERY_PRIMARY_IMAGE_INVALID, RECOVERY_PRODUCT_MANIFEST_INVALID,
        RECOVERY_PRODUCT_TOMBSTONE_REFERENCE_INVALID, RECOVERY_RESUME_RESOURCE_EXCEEDED,
        RECOVERY_ROW_RESPONSE_SIZE_INVALID, RECOVERY_SCOPE_IDENTITY_KEY_MISMATCH,
        RECOVERY_STAGING_FOREIGN_KEY_VIOLATION, RECOVERY_STAGING_TABLE_COUNT_MISMATCH_CATEGORIES,
        RECOVERY_STAGING_TABLE_COUNT_MISMATCH_CATEGORY_REMOTE_REFS, RECOVERY_STAGING_TABLE_COUNT_MISMATCH_HISTORY_ENTRIES,
        RECOVERY_STAGING_TABLE_COUNT_MISMATCH_HISTORY_ENTRY_REMOTE_REFS, RECOVERY_STAGING_TABLE_COUNT_MISMATCH_PRODUCT_PRICE_REMOTE_REFS,
        RECOVERY_STAGING_TABLE_COUNT_MISMATCH_PRODUCT_PRICES, RECOVERY_STAGING_TABLE_COUNT_MISMATCH_PRODUCT_REMOTE_REFS,
        RECOVERY_STAGING_TABLE_COUNT_MISMATCH_PRODUCTS, RECOVERY_STAGING_TABLE_COUNT_MISMATCH_SUPPLIER_REMOTE_REFS,
        RECOVERY_STAGING_TABLE_COUNT_MISMATCH_SUPPLIERS, RECOVERY_TAIL_DOMAIN_FENCE_MISSING,
        RECOVERY_TAIL_EVENT_ORDER_INVALID, RECOVERY_TAIL_EVENT_RESPONSE_BUDGET_EXCEEDED,
        RECOVERY_TAIL_EVENT_UNSAFE, RECOVERY_TAIL_PAGE_CONTRACT_MISMATCH,
        RECOVERY_TAIL_TARGETED_CALL_BOUND_EXCEEDED, RECOVERY_TAIL_TARGETED_CONTRACT_MISMATCH,
        RECOVERY_TAIL_TARGETED_ROW_SCOPE_MISMATCH, RECOVERY_TAIL_TOTAL_RESPONSE_BUDGET_EXCEEDED,
        RECOVERY_TIMESTAMP_INVALID, RECOVERY_TOTAL_RESPONSE_BUDGET_EXCEEDED,
        RECOVERY_UUID_INVALID, RESPONSE_SHOP_ID_INVALID,
        RESPONSE_SHOP_MISMATCH, ROW_COMPOUND_SCOPE_MISMATCH,
        ROW_LEGACY_SCOPE_MISMATCH, ROW_SCOPE_KIND_UNSUPPORTED,
        ROW_SHOP_SCOPE_MISMATCH, RPC_RESPONSE_BUDGET_EXCEEDED,
        RPC_RESPONSE_INVALID, RPC_RESPONSE_JSON_DEPTH_BUDGET_EXCEEDED,
        RPC_RESPONSE_JSON_SCALAR_BUDGET_EXCEEDED, RPC_RESPONSE_JSON_STRING_BUDGET_EXCEEDED,
        RPC_RESPONSE_JSON_TOKEN_BUDGET_EXCEEDED, RPC_RESPONSE_LIMIT_INVALID,
        RPC_RESPONSE_MISSING_FIELDS, SCHEMA_VERSION_MISMATCH,
        SCOPE_ACCOUNT_IDENTITY_MISMATCH, SCOPE_ACCOUNT_KEY_INVALID,
        SCOPE_ACCOUNT_KEY_MISSING, SCOPE_CHANGED,
        SCOPE_DEVICE_IDENTITY_MISMATCH, SCOPE_HISTORY_KIND_MISSING,
        SCOPE_HISTORY_KIND_UNSUPPORTED, SCOPE_KEY_INVALID,
        SCOPE_KIND_UNSUPPORTED, SCOPE_LEGACY_KEY_UNEXPECTED,
        SHOP_SYNC_CLIENT_MISSING, SHOP_SYNC_RPC_JSON_INVALID,
        TARGETED_DOMAIN_EVENT_MAX_ID_MISMATCH, TARGETED_DOMAIN_EVENT_REGRESSED,
        TARGETED_DOMAIN_SCOPE_MISMATCH, TARGETED_ENVELOPE_MISMATCH,
        TARGETED_EVENT_MAX_ID_MISMATCH, TARGETED_IDS_COUNT_INVALID,
        TARGETED_IDS_DUPLICATE, TARGETED_PARTITION_INVALID,
        TARGETED_SCOPE_EVENT_REGRESSED, VERIFIED_BASELINE_ID_INVALID,
        VERIFIED_BASELINE_ID_MISMATCH, VERSION_DIGEST_INVALID;

        val wireValue: String get() = name.lowercase(Locale.ROOT)

        companion object {
            private val contractCodes = entries.filterNot {
                it in setOf(NONE, OTHER_CONTRACT, OTHER_EXCEPTION, ILLEGAL_STATE, ILLEGAL_ARGUMENT, SQLITE, SERIALIZATION)
            }.associateBy { it.wireValue }

            fun fromFailure(failure: Exception): OrdinaryProofErrorCode = when (failure) {
                is ShopSyncContractException -> contractCodes[failure.code] ?: OTHER_CONTRACT
                is android.database.sqlite.SQLiteException -> SQLITE
                // SerializationException is an IllegalArgumentException: keep the specific category first.
                is kotlinx.serialization.SerializationException -> SERIALIZATION
                is IllegalArgumentException -> ILLEGAL_ARGUMENT
                is IllegalStateException -> ILLEGAL_STATE
                else -> OTHER_EXCEPTION
            }
        }
    }

    private fun syncRecoveryRetryDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (SYNC_RECOVERY_RETRY_BASE_MS * (1L shl exponent))
            .coerceAtMost(SYNC_RECOVERY_RETRY_MAX_MS)
    }

    private fun usesCanonicalShopV6Boundary(remote: SyncEventRemoteDataSource): Boolean =
        remote is SupabaseSyncEventRemoteDataSource ||
            (remote is DeviceGuardedSyncEventRemoteDataSource && remote.usesCanonicalShopV6Boundary)

    private fun syncEventOutboxErrorType(
        error: Throwable?, params: SyncEventRecordRpcParams, remote: SyncEventRemoteDataSource
    ): String {
        val category = error?.let { SyncErrorClassifier.classify(it).category }
        // Only this concrete writer used the corrected V6 route; shop events cannot fall back.
        return if (category == SyncErrorCategory.PayloadValidation &&
            usesCanonicalShopV6Boundary(remote) && params.shopId != null &&
            (params.storeId == null || params.storeId == params.shopId)) {
            SYNC_EVENT_OUTBOX_V6_SHOP_PAYLOAD_VALIDATION
        } else category?.name ?: "unknown"
    }

    private fun isShopScopeV6CorrectionCandidate(
        entry: SyncEventOutboxEntry, owner: String, store: String, device: String?,
        ids: SyncEventEntityIds, metadata: JsonObject?
    ): Boolean {
        if (entry.attemptCount != SYNC_EVENT_OUTBOX_MAX_ATTEMPTS ||
            entry.lastErrorType != SyncErrorCategory.PayloadValidation.name ||
            entry.ownerUserId != owner || entry.storeScope != store ||
            entry.source != "android" || device == null || entry.sourceDeviceId != device ||
            entry.batchId == null || !UUID_PATTERN.matches(entry.batchId) ||
            entry.domain !in setOf(SyncEventDomains.CATALOG, SyncEventDomains.PRICES) ||
            !SyncEventContract.hasSupportedEventType(entry.domain, entry.eventType) ||
            entry.changedCount <= 0 || !SyncEventContract.hasCompletePrimaryIds(entry.domain, entry.changedCount, ids) ||
            metadata == null || metadata.keys != setOf("task", "source", "chunk_index", "chunk_count", "entity_ids_compacted")) return false
        fun field(key: String) = metadata[key] as? JsonPrimitive
        if (field("task")?.let { it.isString && it.content == "045" } != true ||
            field("source")?.let { it.isString && it.content == "android_repository" } != true ||
            field("entity_ids_compacted")?.let { !it.isString && it.booleanOrNull == false } != true) return false
        val index = field("chunk_index")?.takeUnless { it.isString }?.intOrNull ?: return false
        val count = field("chunk_count")?.takeUnless { it.isString }?.intOrNull ?: return false
        return index in 0..100000 && count in 1..100000 && count > index &&
            entry.eventType == (if (entry.domain == SyncEventDomains.PRICES) SyncEventTypes.PRICES_CHANGED else SyncEventTypes.CATALOG_CHANGED) &&
            entry.clientEventId == buildClientEventId(entry.batchId, entry.domain, entry.eventType, ids, index)
    }

    /** Bounded physical linkage; a missing bridge/parent never becomes a partial V6 event. */
    private suspend fun syncEventPriceBodies(ids: SyncEventEntityIds): Map<String, InventoryProductPriceRow>? {
        if (ids.priceIds.isEmpty() || ids.priceIds.size > SyncEventContract.MAX_PRICE_ENTITY_IDS_PER_EVENT ||
            ids.priceIds.distinct().size != ids.priceIds.size || ids.priceIds.any { !UUID_PATTERN.matches(it) }) return null
        val rows = linkedMapOf<String, InventoryProductPriceRow>()
        val marks = ids.priceIds.joinToString(",") { "?" }
        db.openHelper.readableDatabase.query(
            """SELECT b.remoteId, r.remoteId, p.type, p.price, p.effectiveAt, p.source, p.note, p.createdAt,
                      p.id, parent.id
               FROM product_price_remote_refs b
               LEFT JOIN product_prices p ON p.id=b.productPriceId
               LEFT JOIN products parent ON parent.id=p.productId
               LEFT JOIN product_remote_refs r ON r.productId=p.productId
               WHERE b.remoteId IN ($marks)""".trimIndent(), ids.priceIds.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isNull(1) || cursor.isNull(8) || cursor.isNull(9)) return null
                val id = cursor.getString(0)
                val parent = cursor.getString(1)
                if (!UUID_PATTERN.matches(parent) || rows.containsKey(id)) return null
                rows[id] = InventoryProductPriceRow(id=id, ownerUserId="", productId=parent,
                    type=cursor.getString(2), price=cursor.getDouble(3), effectiveAt=cursor.getString(4),
                    source=if(cursor.isNull(5)) null else cursor.getString(5),
                    note=if(cursor.isNull(6)) null else cursor.getString(6), createdAt=cursor.getString(7))
            }
        }
        return rows.takeIf { it.keys == ids.priceIds.toSet() }
    }

    private fun syncEventPriceEnvelopeFits(ids: SyncEventEntityIds): Boolean {
        val encoded = buildJsonObject {
            put("price_ids", JsonArray(ids.priceIds.map(::JsonPrimitive)))
            put("product_ids", JsonArray(ids.productIds.map(::JsonPrimitive)))
        }.toString().toByteArray(Charsets.UTF_8).size
        // PostgreSQL's jsonb text includes separator spaces; the deployed helper caps it at 16 KiB.
        return encoded + ids.priceIds.size + ids.productIds.size + 4 <= 16_384
    }

    private suspend fun withSyncEventPriceParents(ids: SyncEventEntityIds): SyncEventEntityIds? {
        val rows = syncEventPriceBodies(ids) ?: return null
        val parents = rows.values.map { it.productId }.distinct().sorted()
        if (ids.productIds.isNotEmpty() && ids.productIds.toSet() != parents.toSet()) return null
        return ids.copy(productIds=parents)
    }

    /** Exceptional sixth send needs a current-generation actual ACK proof, not just a local ID. */
    private suspend fun prepareShopScopeV6CorrectionIds(
        entry: SyncEventOutboxEntry, ids: SyncEventEntityIds, owner: String, shop: String, device: String
    ): SyncEventEntityIds? {
        if (businessDataScopeRuntimeGuard === Task126UnmanagedBusinessDataScopeRuntimeGuard) return null
        val binding = db.businessDataScopeBindingDao().get() ?: return null
        val baseline = db.syncRecoveryBaselineDao().get() ?: return null
        val watermark = syncEventWatermarkDao.get(owner, entry.storeScope) ?: return null
        if (binding.ownerHash != task126OwnerHash(owner) || binding.storeId != entry.storeScope ||
            db.syncRecoveryJournalDao().get() != null || shopSyncBaselineForEventDrain(owner, entry.storeScope,
                shop, device, watermark.lastSyncEventId, baseline, watermark) == null) return null
        suspend fun acknowledged(domain: ShopSyncRowDomain, id: String, revision: Long, fingerprint: String): Boolean {
            val proof = db.syncRecoveryManifestDao().get(baseline.generationId, LOCAL_ACK_BODY_PREFIX + domain.wireValue, id)
                ?: return false
            if (!proof.active || proof.idLine != id || proof.payloadDigest != fingerprint || proof.versionLine.length > 4096) return false
            val identity = runCatching { syncEventJson.decodeFromString<LocalAcknowledgedBodyIdentity>(proof.versionLine) }.getOrNull()
                ?: return false
            return identity.matches(binding, device) && identity.revision == revision
        }
        suspend fun productAcknowledged(id: String): Boolean {
            val ref = productRemoteRefDao.getByRemoteId(id) ?: return false
            val product = productDao.getById(ref.productId) ?: return false
            if (ref.lastRemoteAppliedAt == null || ref.localChangeRevision != ref.lastSyncedLocalRevision) return false
            val row = buildProductPushRow(product, ref, owner, shop, allowCreatingDependencyRefs=false) ?: return false
            return acknowledged(ShopSyncRowDomain.PRODUCTS, id, ref.lastSyncedLocalRevision.toLong(),
                fingerprintProductInbound(row.copy(updatedAt=ref.remoteUpdatedAt,
                    primaryImageVersionId=product.primaryImageVersionId, primaryImageUpdatedAt=product.primaryImageUpdatedAt)))
        }
        val prepared = when (entry.domain) {
            SyncEventDomains.PRICES -> {
                val bodies = syncEventPriceBodies(ids) ?: return null
                val parents = bodies.values.map { it.productId }.distinct().sorted()
                if (ids.productIds.isNotEmpty() && ids.productIds.toSet() != parents.toSet()) return null
                for (parent in parents) if (!productAcknowledged(parent)) return null
                for ((id, body) in bodies) if (!acknowledged(ShopSyncRowDomain.PRICES, id, 0L,
                    localAcknowledgedPriceFingerprint(body))) return null
                ids.copy(productIds=parents)
            }
            SyncEventDomains.CATALOG -> {
                // An old mixed/tombstone operation is never relabelled under its durable operation ID.
                if (entry.eventType != SyncEventTypes.CATALOG_CHANGED) return null
                for (id in ids.supplierIds) {
                    val ref = supplierRemoteRefDao.getByRemoteId(id) ?: return null
                    val row = supplierDao.getById(ref.supplierId) ?: return null
                    if (ref.lastRemoteAppliedAt == null || ref.localChangeRevision != ref.lastSyncedLocalRevision ||
                        !acknowledged(ShopSyncRowDomain.SUPPLIERS, id, ref.lastSyncedLocalRevision.toLong(),
                            fingerprintSupplierInbound(buildSupplierPushRow(row,ref,owner,shop).copy(updatedAt=ref.remoteUpdatedAt)))) return null
                }
                for (id in ids.categoryIds) {
                    val ref = categoryRemoteRefDao.getByRemoteId(id) ?: return null
                    val row = categoryDao.getById(ref.categoryId) ?: return null
                    if (ref.lastRemoteAppliedAt == null || ref.localChangeRevision != ref.lastSyncedLocalRevision ||
                        !acknowledged(ShopSyncRowDomain.CATEGORIES, id, ref.lastSyncedLocalRevision.toLong(),
                            fingerprintCategoryInbound(buildCategoryPushRow(row,ref,owner,shop).copy(updatedAt=ref.remoteUpdatedAt)))) return null
                }
                for (id in ids.productIds) if (!productAcknowledged(id)) return null
                ids
            }
            else -> return null
        }
        if (entry.domain == SyncEventDomains.PRICES && !syncEventPriceEnvelopeFits(prepared)) return null
        requireCurrentBusinessDataScope()
        return prepared.takeIf { db.businessDataScopeBindingDao().get() == binding &&
            db.syncRecoveryBaselineDao().get() == baseline && db.syncRecoveryJournalDao().get() == null &&
            syncEventDeviceStateDao.get()?.deviceId == device }
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
        val ordinaryPending = syncEventOutboxDao.listPendingRetryableForScope(
            ownerUserId,
            storeScope,
            SYNC_EVENT_OUTBOX_MAX_ATTEMPTS,
            SYNC_EVENT_OUTBOX_RETRY_LIMIT
        )
        val shopId = shopIdFromStoreScope(storeScope)
        val deviceId = syncEventDeviceStateDao.get()?.deviceId
        val correctionCandidates = if (usesCanonicalShopV6Boundary(remote) &&
            shopId != null && UUID_PATTERN.matches(shopId) && deviceId != null && UUID_PATTERN.matches(deviceId)) {
            syncEventOutboxDao.listShopScopeCorrectionCandidates(ownerUserId, storeScope,
                SYNC_EVENT_OUTBOX_MAX_ATTEMPTS, SYNC_EVENT_OUTBOX_RETRY_LIMIT - ordinaryPending.size)
        } else emptyList()
        val pending = ordinaryPending + correctionCandidates
        var correctionClaims = 0
        var retryEligible = 0
        var retrySucceeded = 0
        var retryFailed = 0
        var retryDeletedOnSuccess = 0
        for (entry in pending) {
            val correction = entry.attemptCount == SYNC_EVENT_OUTBOX_MAX_ATTEMPTS
            if (entry.attemptCount > SYNC_EVENT_OUTBOX_MAX_ATTEMPTS) continue
            val ids = if (correction) runCatching {
                syncEventJson.decodeFromString<SyncEventEntityIds>(entry.entityIdsJson)
            }.getOrNull() ?: continue else syncEventJson.decodeFromString<SyncEventEntityIds>(entry.entityIdsJson)
            var correctionIds: SyncEventEntityIds? = null
            if (correction) {
                val metadata = runCatching { syncEventJson.decodeFromString<JsonObject>(entry.metadataJson) }.getOrNull()
                if (!isShopScopeV6CorrectionCandidate(entry, ownerUserId, storeScope, deviceId, ids, metadata)) continue
                businessDataScopeRuntimeGuard.requireCloudBusinessDataScope()
                requireCurrentBusinessDataScope()
                val signal = businessDataScopeRuntimeGuard.captureBusinessDataScopeSignal(ownerUserId, shopId)
                val claimed = db.withTransaction {
                    requireCurrentBusinessDataScope()
                    if (!businessDataScopeRuntimeGuard.isCurrentBusinessDataScopeSignal(signal) ||
                        syncEventDeviceStateDao.get()?.deviceId != deviceId || syncEventOutboxDao.getById(entry.id) != entry) false
                    else {
                        val prepared = prepareShopScopeV6CorrectionIds(entry, ids, ownerUserId, requireNotNull(shopId), requireNotNull(deviceId))
                        if (prepared == null || !businessDataScopeRuntimeGuard.isCurrentBusinessDataScopeSignal(signal) ||
                            syncEventOutboxDao.getById(entry.id) != entry) false
                        else {
                            // Prepare and claim under the same authorization/body transaction before any RPC.
                            correctionIds = prepared
                            syncEventOutboxDao.update(entry.copy(attemptCount = entry.attemptCount + 1))
                            true
                        }
                    }
                }
                if (!claimed) continue
                correctionClaims++
            }
            retryEligible++
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
            val persistedMetadata = runCatching {
                syncEventJson.decodeFromString<JsonObject>(entry.metadataJson)
            }.getOrNull()
            if (persistedMetadata == null) {
                requireCurrentBusinessDataScope()
                syncEventOutboxDao.update(entry.copy(
                    attemptCount = entry.attemptCount + 1,
                    lastAttemptAtMs = System.currentTimeMillis(),
                    lastErrorType = "invalid_persisted_metadata"
                ))
                retryFailed++
                logSyncEventOutboxRetryEntry("rejected_invalid_metadata", entry,
                    "invalid_persisted_metadata", entry.attemptCount + 1, 0)
                continue
            }
            val wireIds = correctionIds ?: if (shopId != null && entry.domain == SyncEventDomains.PRICES && usesCanonicalShopV6Boundary(remote)) {
                db.withTransaction { withSyncEventPriceParents(ids) }
            } else ids
            val params = SyncEventRecordRpcParams(
                domain = entry.domain,
                eventType = entry.eventType,
                changedCount = entry.changedCount,
                entityIds = wireIds ?: ids,
                storeId = remoteStoreIdFromStoreScope(entry.storeScope),
                source = entry.source,
                sourceDeviceId = entry.sourceDeviceId,
                batchId = entry.batchId,
                clientEventId = entry.clientEventId,
                metadata = persistedMetadata,
                shopId = shopIdFromStoreScope(entry.storeScope)
            )
            val result = if (wireIds == null || (shopId != null && entry.domain == SyncEventDomains.PRICES &&
                    usesCanonicalShopV6Boundary(remote) && !syncEventPriceEnvelopeFits(wireIds)))
                Result.failure(ShopSyncContractException("sync_event_price_parent_invalid"))
                else businessScopedRemoteCall { remote.recordSyncEvent(params) }
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
                val errorType = syncEventOutboxErrorType(result.exceptionOrNull(), params, remote)
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
            retrySkippedMaxAttempts = (skippedMaxAttempts - correctionClaims).coerceAtLeast(0),
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
        val primaryChunks = if (ids.isEmpty) listOf(ids) else SyncEventContract.chunkPrimaryIds(domain, ids)
        val chunks = if (shopId != null && domain == SyncEventDomains.PRICES && usesCanonicalShopV6Boundary(remote)) {
            primaryChunks.flatMap { chunk ->
                val linked = db.withTransaction { withSyncEventPriceParents(chunk) }
                if (linked != null && !syncEventPriceEnvelopeFits(linked)) {
                    // Only fresh operation IDs may be split; old persisted operations remain indivisible.
                    chunk.priceIds.chunked(SyncEventContract.MAX_PRICE_ENTITY_IDS_PER_EVENT / 2)
                        .map { SyncEventEntityIds(priceIds=it) }
                } else listOf(chunk)
            }
        } else primaryChunks
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
            val wireIds = if (shopId != null && domain == SyncEventDomains.PRICES && usesCanonicalShopV6Boundary(remote)) {
                db.withTransaction { withSyncEventPriceParents(chunk) }
            } else chunk
            val params = SyncEventRecordRpcParams(
                domain = domain,
                eventType = eventType,
                changedCount = chunkChangedCount,
                entityIds = wireIds ?: chunk,
                storeId = remoteStoreIdFromStoreScope(storeScope),
                source = "android",
                sourceDeviceId = deviceId,
                batchId = batchId,
                clientEventId = clientEventId,
                metadata = metadata,
                shopId = shopId
            )
            val result = if (wireIds == null) Result.failure(ShopSyncContractException("sync_event_price_parent_invalid"))
                else businessScopedRemoteCall { remote.recordSyncEvent(params) }
            if (result.isSuccess) {
                recordedChunks++
                continue
            }
            enqueuedChunks++
            requireCurrentBusinessDataScope()
            val errorType = syncEventOutboxErrorType(result.exceptionOrNull(), params, remote)
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
        const val SYNC_EVENT_OUTBOX_V6_SHOP_PAYLOAD_VALIDATION = "PayloadValidationV6ShopScope"
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
        val replayedPrices = businessWriteOutbox.replay(ownerUserId,shopId,setOf("PRICES")) {
            businessScopedRemoteCall { priceRemote.upsertProductPrices(it.payload.prices,it.shop) }
        }
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
            return ProductPricePushResult(count = replayedPrices.sumOf { it.payload.revisions.size },
                remoteIds = replayedPrices.flatMap { it.payload.revisions.map { r -> r.remoteId } }.distinct())
        }
        var pushed = replayedPrices.sumOf { it.payload.revisions.size }
        var processed = 0
        var batchCount = 0
        var totalBatchMs = 0L
        var skippedForeignKey = 0
        val pushedRemoteIds = replayedPrices.flatMap { it.payload.revisions.map { r -> r.remoteId } }.toMutableList()
        for (chunk in rows.chunked(PRODUCT_PRICE_PUSH_CHUNK)) {
            val pairs = chunk.map { r ->
                val rid = r.existingPriceRemoteId ?: java.util.UUID.randomUUID().toString()
                r to rid
            }
            val upsertRows = pairs.map { (r, rid) -> buildProductPricePushRow(r, rid, ownerUserId, shopId) }
            val batchStartedAt = System.currentTimeMillis()
            val result = businessWriteOutbox.execute(ownerUserId,shopId,BusinessWritePayload("PRICES",
                prices=upsertRows,revisions=pairs.map { (r,id) -> BusinessWriteRevision("PRICE",id,0,null,localPriceId=r.id) })) {
                businessScopedRemoteCall { priceRemote.upsertProductPrices(it.payload.prices,it.shop) }
            }
            totalBatchMs += System.currentTimeMillis() - batchStartedAt
            batchCount++
            val firstError = result.exceptionOrNull()
            if (firstError == null) {
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
                    val single = businessWriteOutbox.execute(ownerUserId,shopId,BusinessWritePayload("PRICES",
                        prices=listOf(row),revisions=listOf(BusinessWriteRevision("PRICE",rid,0,null,localPriceId=r.id)))) {
                        businessScopedRemoteCall { priceRemote.upsertProductPrices(it.payload.prices,it.shop) }
                    }
                    totalBatchMs += System.currentTimeMillis() - singleStartedAt
                    batchCount++
                    val singleError = single.exceptionOrNull()
                    if (singleError == null) {
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
            db.withTransaction { requireLegacyCatalogInboundAllowed(shopId) }
            val page = businessScopedRemoteCall {
                priceRemote.fetchProductPricesPage(lastRemoteId, INVENTORY_REMOTE_PAGE_SIZE, shopId)
            }.getOrThrow()
            val pageResult = db.withTransaction {
                requireLegacyCatalogInboundAllowed(shopId)
                if (page.isEmpty()) null else applyProductPriceRows(
                    page,
                    progressReporter,
                    stage = CatalogSyncStage.SYNC_PRICES_PULL,
                    processedBefore = remoteRowsEvaluated,
                    totalRows = null
                )
            }
            if (pageResult == null) break

            pageCount++
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
        val replayed = businessWriteOutbox.replay(ownerUserId,shopId,setOf("SUPPLIERS")) {
            sendCatalogBusinessWrite(remote,it)
        }
        var n = replayed.sumOf { it.payload.revisions.size }
        var dirty = 0
        var skippedAlreadySynced = 0
        val pushedRemoteIds = replayed.flatMap { it.payload.revisions.map { r -> r.remoteId } }.toMutableList()
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
        val replayed = businessWriteOutbox.replay(ownerUserId,shopId,setOf("CATEGORIES")) {
            sendCatalogBusinessWrite(remote,it)
        }
        var n = replayed.sumOf { it.payload.revisions.size }
        var dirty = 0
        var skippedAlreadySynced = 0
        val pushedRemoteIds = replayed.flatMap { it.payload.revisions.map { r -> r.remoteId } }.toMutableList()
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
        val replayed = businessWriteOutbox.replay(ownerUserId,shopId,setOf("PRODUCTS","PATCH")) {
            sendCatalogBusinessWrite(remote,it)
        }
        var dirty = 0
        var skippedMissingDependencyRef = 0
        var skippedAlreadySynced = 0
        val productTotal = productDao.count()
        val candidates = productDao.getCatalogPushCandidates()
        val prepared = mutableListOf<ProductPushCandidatePrepared>()
        val accumulator = ProductPushBatchAccumulator()
        accumulator.pushed += replayed.sumOf { it.payload.revisions.size }
        accumulator.remoteIds += replayed.flatMap { it.payload.revisions.map { r -> r.remoteId } }
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
                        businessWriteOutbox.execute(ownerUserId,shopId,BusinessWritePayload("PATCH",
                            patchId=CatalogTextCanonicalizer.remoteId(ref.remoteId),patch=patch,
                            revisions=listOf(productWriteRevision(productForPush,ref,ownerUserId,shopId)))) {
                            sendCatalogBusinessWrite(remote,it)
                        }.getOrThrow()
                        accumulator.totalBatchMs += System.currentTimeMillis() - startedAt
                        accumulator.batchCount++
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
        val first = writeCatalogProducts(remote,ownerUserId,shopId,batch)
        accumulator.totalBatchMs += System.currentTimeMillis() - startedAt
        accumulator.batchCount++
        val error = first.exceptionOrNull()
        if (error == null) {
            accumulator.pushed += batch.size
            accumulator.completed += batch.size
            accumulator.remoteIds += batch.map { it.row.id }
            reportProductPushProgress(progressReporter, accumulator.completed, total)
            return
        }
        if (error is CancellationException) throw error
        // An ambiguous response retains the original whole batch for exact replay.
        if (!error.isPostgrestUniqueViolationConflict() && !error.isPostgrestForeignKeyViolationConflict()) throw error

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
        val first = writeSupplier(remote,ownerUserId,shopId,row,ref)
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileSupplierBridgeAfterUniqueConflict(remote, ownerUserId, supplier, ref, recoveryCache, shopId)
        if (!recovered) throw firstError
        val correctedRef = supplierRemoteRefDao.getBySupplierId(supplier.id) ?: throw firstError
        if (!supplierNeedsPush(correctedRef)) return false

        val retryRow = buildSupplierPushRow(supplier, correctedRef, ownerUserId, shopId)
        writeSupplier(remote,ownerUserId,shopId,retryRow,correctedRef).getOrThrow()
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
        val first = writeCategory(remote,ownerUserId,shopId,row,ref)
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileCategoryBridgeAfterUniqueConflict(remote, ownerUserId, category, ref, recoveryCache, shopId)
        if (!recovered) throw firstError
        val correctedRef = categoryRemoteRefDao.getByCategoryId(category.id) ?: throw firstError
        if (!categoryNeedsPush(correctedRef)) return false

        val retryRow = buildCategoryPushRow(category, correctedRef, ownerUserId, shopId)
        writeCategory(remote,ownerUserId,shopId,retryRow,correctedRef).getOrThrow()
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
        val first = writeCatalogProducts(remote,ownerUserId,shopId,listOf(ProductPushCandidatePrepared(product,ref,row)))
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            return true
        }
        if (!firstError.isPostgrestUniqueViolationConflict()) throw firstError

        val recovered = reconcileProductBridgeAfterUniqueConflict(remote, ownerUserId, product, ref, recoveryCache, shopId)
        if (!recovered) throw firstError
        val correctedRef = productRemoteRefDao.getByProductId(product.id) ?: throw firstError
        if (!productNeedsPush(correctedRef)) return false

        val retryRow = buildProductPushRow(product, correctedRef, ownerUserId, shopId, allowCreatingDependencyRefs)
            ?: return false
        writeCatalogProducts(remote,ownerUserId,shopId,listOf(ProductPushCandidatePrepared(product,correctedRef,retryRow))).getOrThrow()
        return true
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

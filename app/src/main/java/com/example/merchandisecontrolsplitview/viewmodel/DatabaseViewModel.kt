package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.ImportAnalyzer
import com.example.merchandisecontrolsplitview.util.analyzeFullDbImportStreaming
import com.example.merchandisecontrolsplitview.util.applyFullDbPriceHistoryStreaming
import com.example.merchandisecontrolsplitview.util.detectImportWorkbookRoute
import com.example.merchandisecontrolsplitview.util.ImportWorkbookRoute
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.sync.Mutex

sealed class UiState {
    data object Idle : UiState()
    data class Loading(val message: String? = null, val progress: Int? = null) : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

private data class PendingPriceEvent(
    val barcode: String,
    val timestamp: String,
    val type: String,   // "PURCHASE" | "RETAIL"
    val newPrice: Double,
    val source: String?
)

private data class PendingImportApplySnapshot(
    val pendingPriceHistory: List<PendingPriceEvent>,
    val pendingSupplierNames: Set<String>,
    val pendingCategoryNames: Set<String>,
    val pendingTempSuppliers: Map<Long, String>,
    val pendingTempCategories: Map<Long, String>,
    val pendingFullImportUri: Uri?,
    val hasPendingPriceHistorySheet: Boolean
)

private data class ResolvedImportPayload(
    val newProducts: List<Product>,
    val updatedProducts: List<Product>
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: InventoryRepository =
        DefaultInventoryRepository(AppDatabase.getDatabase(app))

    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Uid dell'entry di log per il "full import" (analisi file)
    private var currentImportLogUid: Long? = null
    private val importMutex = Mutex()
// --- Helper per creare/aggiornare i log nella tabella history_entries ---

    private suspend fun startHistoryLog(kind: String, message: String): Long =
        withContext(Dispatchers.IO) {
            repository.insertHistoryEntry(
                HistoryEntry(
                    id = "${kind}_${System.currentTimeMillis()}", // etichetta generica, la chiave reale è 'uid' autogenerato
                    timestamp = LocalDateTime.now().format(tsFmt),
                    data = listOf(
                        listOf("status", "message"),
                        listOf("STARTED", message)
                    ),
                    editable = listOf(listOf("", "")),
                    complete = listOf(false),
                    supplier = "—",
                    category = "—",
                    totalItems = 0,
                    orderTotal = 0.0,
                    paymentTotal = 0.0,
                    missingItems = 0,
                    syncStatus = SyncStatus.NOT_ATTEMPTED,
                    wasExported = false
                )
            )
        }

    private suspend fun appendHistoryLog(uid: Long, status: String, message: String) =
        withContext(Dispatchers.IO) {
            val cur = repository.getHistoryEntryByUid(uid) ?: return@withContext
            repository.updateHistoryEntry(
                cur.copy(
                    data = cur.data + listOf(listOf(status, message)),
                    complete = cur.complete + listOf(
                        status == "SUCCESS" || status == "FAILED" || status == "CANCELLED"
                    )
                )
            )
        }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun consumeUiState() { _uiState.value = UiState.Idle }
    private val _filter = MutableStateFlow<String?>(null)

    private val appContext = getApplication<Application>().applicationContext
    val filter: StateFlow<String?> = _filter.asStateFlow()

    val pager = filter.flatMapLatest { filterStr ->
        Pager(PagingConfig(pageSize = 20)) {
            repository.getProductsWithDetailsPaged(filterStr)
        }.flow.cachedIn(viewModelScope)
    }

    private val _supplierInputText = MutableStateFlow("")
    val supplierInputText: StateFlow<String> = _supplierInputText.asStateFlow()

    fun onSupplierSearchQueryChanged(query: String) {
        _supplierInputText.value = query
    }

    val suppliers: StateFlow<List<Supplier>> = _supplierInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank())
                flow { emit(repository.getAllSuppliers()) }
            else
                flow { emit(repository.searchSuppliersByName(query)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _categoryInputText = MutableStateFlow("")
    val categoryInputText: StateFlow<String> = _categoryInputText.asStateFlow()

    fun onCategorySearchQueryChanged(query: String) {
        _categoryInputText.value = query
    }

    // Replaced categoryDao calls with repository calls
    val categories: StateFlow<List<Category>> = _categoryInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank())
                flow { emit(repository.getAllCategories()) }
            else
                flow { emit(repository.searchCategoriesByName(query)) }
        }
        // Removed .flowOn(Dispatchers.IO) as the repository handles threading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    // --- FIX END ---

    private var pendingPriceHistory: List<PendingPriceEvent> = emptyList()
    private var pendingSupplierNames: Set<String> = emptySet()
    private var pendingCategoryNames: Set<String> = emptySet()
    private var pendingTempSuppliers: Map<Long, String> = emptyMap()
    private var pendingTempCategories: Map<Long, String> = emptyMap()
    private var pendingFullImportUri: Uri? = null
    private var hasPendingPriceHistorySheet = false

    private val sheetProducts = "Products"
    private val sheetSuppliers = "Suppliers"
    private val sheetCategories = "Categories"
    private val sheetPriceHistory = "PriceHistory"

    fun setFilter(text: String) {
        _filter.value = text.ifBlank { null }
    }

    private val _importAnalysisResult = MutableStateFlow<ImportAnalysis?>(null)
    val importAnalysisResult: StateFlow<ImportAnalysis?> = _importAnalysisResult.asStateFlow()

    fun startSmartImport(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                when (withContext(Dispatchers.IO) { detectImportWorkbookRoute(context, uri) }) {
                    ImportWorkbookRoute.FULL_DATABASE -> startFullDbImport(context, uri)
                    ImportWorkbookRoute.SINGLE_SHEET -> startImportAnalysis(context, uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
                _importAnalysisResult.value = null
                val errorMessage = e.message ?: "Not enough memory to analyze this file"
                _uiState.value = UiState.Error(context.getString(R.string.error_data_analysis, errorMessage))
            } catch (e: Exception) {
                handleImportAnalysisError(context, e)
            }
        }
    }

    fun startImportAnalysis(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading(message = context.getString(R.string.import_loading_file), progress = 5)
        viewModelScope.launch {
            try {
                val (normalizedHeader, dataRows) = parseImportFile(context, uri)
                if (!validateImportFile(context, normalizedHeader, dataRows)) return@launch

                _uiState.value = UiState.Loading(message = context.getString(R.string.import_mapping_rows), progress = 30)

                val currentDbProducts = fetchCurrentDatabaseProducts()

                _uiState.value = UiState.Loading(message = context.getString(R.string.import_analyzing), progress = 85)

                val chunks = buildChunkedRows(normalizedHeader, dataRows)
                val analysis = analyzeImportStreaming(context, chunks, currentDbProducts)

                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
                _importAnalysisResult.value = null
                val errorMessage = e.message ?: "Not enough memory to analyze this file"
                _uiState.value = UiState.Error(context.getString(R.string.error_data_analysis, errorMessage))
            } catch (e: Exception) {
                handleImportAnalysisError(context, e)
            }
        }
    }

    private suspend fun parseImportFile(
        context: Context,
        uri: Uri
    ): Pair<List<String>, List<List<String>>> {
        val (normalizedHeader, dataRows, _) = withContext(Dispatchers.IO) {
            readAndAnalyzeExcel(context, uri)
        }
        return normalizedHeader to dataRows
    }

    private fun validateImportFile(
        context: Context,
        normalizedHeader: List<String>,
        dataRows: List<List<String>>
    ): Boolean {
        if (normalizedHeader.isEmpty() || dataRows.isEmpty()) {
            _uiState.value = UiState.Error(context.getString(R.string.error_file_empty_or_invalid))
            return false
        }
        return true
    }

    private suspend fun fetchCurrentDatabaseProducts(): List<Product> {
        _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_fetching_db), progress = 55)
        return withContext(Dispatchers.IO) {
            repository.getAllProducts()
        }
    }

    private fun buildChunkedRows(
        normalizedHeader: List<String>,
        dataRows: List<List<String>>
    ): Sequence<List<Map<String, String>>> {
        return sequence {
            val seq = dataRows.asSequence().map { row ->
                normalizedHeader.mapIndexed { index, headerKey ->
                    headerKey to (row.getOrNull(index) ?: "")
                }.toMap()
            }
            for (block in seq.chunked(1000)) yield(block)
        }
    }

    private suspend fun analyzeImportStreaming(
        context: Context,
        chunks: Sequence<List<Map<String, String>>>,
        currentDbProducts: List<Product>
    ): ImportAnalysis {
        return withContext(Dispatchers.Default) {
            ImportAnalyzer.analyzeStreaming(context, chunks, currentDbProducts, repository)
        }
    }

    private fun handleImportAnalysisError(context: Context, e: Exception) {
        e.printStackTrace()
        val errorMessage = e.message ?: context.getString(R.string.unknown)
        _uiState.value = UiState.Error(context.getString(R.string.error_data_analysis, errorMessage))
    }

    fun clearImportAnalysis() {
        resetPendingImportState(clearAnalysisResult = true)
    }

    fun importProducts(
        newProducts: List<Product>,
        updatedProducts: List<ProductUpdate>,
        context: Context
    ) {
        val pendingSnapshot = snapshotPendingImportApplyState()

        // prima era progress = null
        _uiState.value = UiState.Loading(
            message = context.getString(R.string.import_applying_changes),
            progress = 90
        )

        viewModelScope.launch {
            val applyLogUid = startHistoryLog(
                "APPLY_IMPORT",
                "Applico import: new=${newProducts.size}, updated=${updatedProducts.size}"
            )
            Log.d("DB_IMPORT", "APPLY_IMPORT START uid=$applyLogUid")

            try {
                withContext(Dispatchers.IO) {
                    val resolvedPayload = resolveImportPayload(
                        newProducts = newProducts,
                        updatedProducts = updatedProducts,
                        pendingSnapshot = pendingSnapshot
                    )
                    repository.applyImport(
                        resolvedPayload.newProducts,
                        resolvedPayload.updatedProducts
                    )

                    // Eventuale storico prezzi
                    applyPendingPriceHistory(context, pendingSnapshot)
                }

                // mini feedback finale prima del SUCCESS
                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_applying_changes),
                    progress = 98
                )

                appendHistoryLog(applyLogUid, "SUCCESS", "Import applicato correttamente.")
                Log.d("DB_IMPORT", "APPLY_IMPORT SUCCESS uid=$applyLogUid")
                _uiState.value = UiState.Success(context.getString(R.string.import_success))
            } catch (e: Exception) {
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(context.getString(R.string.import_error, errorMessage))
                appendHistoryLog(applyLogUid, "FAILED", "Errore durante apply: ${e.message}")
                Log.e("DB_IMPORT", "APPLY_IMPORT FAILED uid=$applyLogUid", e)
            } finally {
                resetPendingImportState(clearAnalysisResult = false)
            }
        }
    }

    private fun snapshotPendingImportApplyState(): PendingImportApplySnapshot =
        PendingImportApplySnapshot(
            pendingPriceHistory = pendingPriceHistory.toList(),
            pendingSupplierNames = pendingSupplierNames.toSet(),
            pendingCategoryNames = pendingCategoryNames.toSet(),
            pendingTempSuppliers = pendingTempSuppliers.toMap(),
            pendingTempCategories = pendingTempCategories.toMap(),
            pendingFullImportUri = pendingFullImportUri,
            hasPendingPriceHistorySheet = hasPendingPriceHistorySheet
        )

    private fun resetPendingImportState(clearAnalysisResult: Boolean) {
        if (clearAnalysisResult) {
            _importAnalysisResult.value = null
        }
        pendingPriceHistory = emptyList()
        pendingSupplierNames = emptySet()
        pendingCategoryNames = emptySet()
        pendingTempSuppliers = emptyMap()
        pendingTempCategories = emptyMap()
        pendingFullImportUri = null
        hasPendingPriceHistorySheet = false
    }

    private suspend fun resolveImportPayload(
        newProducts: List<Product>,
        updatedProducts: List<ProductUpdate>,
        pendingSnapshot: PendingImportApplySnapshot
    ): ResolvedImportPayload {
        if (
            pendingSnapshot.pendingSupplierNames.isEmpty() &&
            pendingSnapshot.pendingCategoryNames.isEmpty() &&
            pendingSnapshot.pendingTempSuppliers.isEmpty() &&
            pendingSnapshot.pendingTempCategories.isEmpty()
        ) {
            return ResolvedImportPayload(
                newProducts = newProducts,
                updatedProducts = updatedProducts.map { pu ->
                    pu.newProduct.copy(id = pu.oldProduct.id)
                }
            )
        }

        pendingSnapshot.pendingSupplierNames.forEach { repository.addSupplier(it) }
        pendingSnapshot.pendingCategoryNames.forEach { repository.addCategory(it) }

        val supplierIdsByName = repository.getAllSuppliers()
            .associateBy { it.name.trim().lowercase() }
        val categoryIdsByName = repository.getAllCategories()
            .associateBy { it.name.trim().lowercase() }

        fun resolveProduct(product: Product): Product {
            val resolvedSupplierId = when {
                product.supplierId == null -> null
                product.supplierId >= 0L -> product.supplierId
                else -> pendingSnapshot.pendingTempSuppliers[product.supplierId]
                    ?.trim()
                    ?.lowercase()
                    ?.let { supplierIdsByName[it]?.id }
            }

            val resolvedCategoryId = when {
                product.categoryId == null -> null
                product.categoryId >= 0L -> product.categoryId
                else -> pendingSnapshot.pendingTempCategories[product.categoryId]
                    ?.trim()
                    ?.lowercase()
                    ?.let { categoryIdsByName[it]?.id }
            }

            return product.copy(
                supplierId = resolvedSupplierId,
                categoryId = resolvedCategoryId
            )
        }

        return ResolvedImportPayload(
            newProducts = newProducts.map(::resolveProduct),
            updatedProducts = updatedProducts.map { pu ->
                resolveProduct(pu.newProduct).copy(id = pu.oldProduct.id)
            }
        )
    }

    private suspend fun applyPendingPriceHistory(
        context: Context,
        pendingSnapshot: PendingImportApplySnapshot
    ) {
        if (pendingSnapshot.pendingPriceHistory.isNotEmpty()) {
            pendingSnapshot.pendingPriceHistory
                .groupBy { it.source ?: "IMPORT_SHEET" }
                .forEach { (src, events) ->
                    val rows = events.map { e ->
                        Triple(e.barcode, e.type, e.timestamp to e.newPrice)
                    }
                    repository.recordPriceHistoryByBarcodeBatch(rows, src)
                }
            return
        }

        if (!pendingSnapshot.hasPendingPriceHistorySheet) return

        val fullImportUri = pendingSnapshot.pendingFullImportUri
            ?: throw IllegalStateException("Missing full import uri for pending price history")
        applyFullDbPriceHistoryStreaming(context, fullImportUri, repository)
    }

    fun exportToExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch {
            try {
                val products = repository.getAllProductsWithDetails()
                if (products.isEmpty()) {
                    _uiState.value = UiState.Error(context.getString(R.string.error_no_products_to_export))
                    return@launch
                }
                // Scrittura file su dispatcher I/O
                withContext(Dispatchers.IO) {
                    writeProductsToExcel(context, uri, products)
                }
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: Exception) {
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(context.getString(R.string.export_error, errorMessage))
            }
        }
    }

    fun addProduct(product: Product) {
        viewModelScope.launch {
            try {
                repository.addProduct(product)
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_added))
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_barcode_already_exists))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_added))
            }
        }
    }

    // --- FIX START ---
    fun updateProduct(product: Product) {
        viewModelScope.launch { // Removed Dispatchers.IO
            try {
                repository.updateProduct(product) // Replaced dao.update with repository.updateProduct
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_updated))
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_barcode_already_exists))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_updated))
            }
        }
    }
    // --- FIX END ---

    // --- FIX START ---
    fun deleteProduct(product: Product) {
        viewModelScope.launch { // Removed Dispatchers.IO
            try {
                repository.deleteProduct(product) // Replaced dao.delete with repository.deleteProduct
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_deleted))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_deleted))
            }
        }
    }
    // --- FIX END ---

    private fun writeProductsToExcel(context: Context, uri: Uri, products: List<ProductWithDetails>) {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(context.getString(R.string.sheet_name_products))

            val headerRow = sheet.createRow(0)
            val headers = listOf(
                context.getString(R.string.header_barcode),
                context.getString(R.string.header_item_number),
                context.getString(R.string.header_product_name),
                context.getString(R.string.header_second_product_name),
                context.getString(R.string.header_purchase_price),
                context.getString(R.string.header_retail_price),
                context.getString(R.string.product_purchase_price_old_short), // PrevPurchase
                context.getString(R.string.product_retail_price_old_short),
                context.getString(R.string.header_supplier),
                context.getString(R.string.header_category),
                context.getString(R.string.header_stock_quantity)
            )
            headers.forEachIndexed { index, header ->
                headerRow.createCell(index).setCellValue(header)
            }

            products.forEachIndexed { index, details ->
                val p = details.product
                val row = sheet.createRow(index + 1)

                row.createCell(0).setCellValue(p.barcode)
                row.createCell(1).setCellValue(p.itemNumber ?: "")
                row.createCell(2).setCellValue(p.productName ?: "")
                row.createCell(3).setCellValue(p.secondProductName ?: "")
                row.createCell(4).setCellValue(p.purchasePrice ?: 0.0)          // prezzo corrente
                row.createCell(5).setCellValue(p.retailPrice ?: 0.0)
                row.createCell(6).setCellValue(details.prevPurchase ?: 0.0)
                row.createCell(7).setCellValue(details.prevRetail ?: 0.0)// prezzo corrente
                row.createCell(8).setCellValue(details.supplierName ?: "")
                row.createCell(9).setCellValue(details.categoryName ?: "")
                row.createCell(10).setCellValue(p.stockQuantity ?: 0.0)
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { workbook.write(it) }
            } catch (e: IOException) {
                e.printStackTrace()
                throw e
            }
        }
    }

    fun analyzeGridData(gridData: List<Map<String, String>>) {
        _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 10)
        viewModelScope.launch {
            try {
                val currentDbProducts = withContext(Dispatchers.IO) {
                    _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_fetching_db), progress = 30)
                    repository.getAllProducts()
                }
                _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 70)
                val analysis = withContext(Dispatchers.Default) {
                    ImportAnalyzer.analyze(appContext, gridData, currentDbProducts, repository)
                }
                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                val msg = e.message ?: appContext.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(appContext.getString(R.string.error_data_analysis, msg))
            }
        }
    }

    suspend fun addSupplier(name: String): Supplier? {
        return repository.addSupplier(name)
    }

    suspend fun addCategory(name: String): Category? {
        return repository.addCategory(name)
    }

    suspend fun getSupplierById(id: Long): Supplier? {
        return repository.getSupplierById(id)
    }

    suspend fun getCategoryById(id: Long): Category? {
        return repository.getCategoryById(id)
    }

    suspend fun findProductByBarcode(barcode: String): Product? {
        return repository.findProductByBarcode(barcode)
    }
    fun getPriceSeries(productId: Long, type: String) =
        repository.getPriceSeries(productId, type)

    // ⬇️ EXPORT COMPLETO: nuovo metodo pubblico
    fun exportFullDbToExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading(message = context.getString(R.string.operation_in_progress), progress = null)
        viewModelScope.launch {
            try {
                val products = repository.getAllProductsWithDetails()
                val suppliers = repository.getAllSuppliers()
                val categories = repository.getAllCategories()
                val priceRows = repository.getAllPriceHistoryRows()

                withContext(Dispatchers.IO) {
                    XSSFWorkbook().use { wb ->

                        // 1) Products (stesso schema dell'export attuale)
                        run {
                            val sheet = wb.createSheet(sheetProducts)
                            val header = listOf(
                                context.getString(R.string.header_barcode),
                                context.getString(R.string.header_item_number),
                                context.getString(R.string.header_product_name),
                                context.getString(R.string.header_second_product_name),
                                context.getString(R.string.header_purchase_price),
                                context.getString(R.string.header_retail_price),
                                context.getString(R.string.product_purchase_price_old_short),
                                context.getString(R.string.product_retail_price_old_short),
                                context.getString(R.string.header_supplier),
                                context.getString(R.string.header_category),
                                context.getString(R.string.header_stock_quantity)
                            )
                            val h = sheet.createRow(0)
                            header.forEachIndexed { i, t -> h.createCell(i).setCellValue(t) }

                            products.forEachIndexed { idx, d ->
                                val p = d.product
                                val r = sheet.createRow(idx + 1)
                                r.createCell(0).setCellValue(p.barcode)
                                r.createCell(1).setCellValue(p.itemNumber ?: "")
                                r.createCell(2).setCellValue(p.productName ?: "")
                                r.createCell(3).setCellValue(p.secondProductName ?: "")
                                r.createCell(4).setCellValue(p.purchasePrice ?: 0.0)
                                r.createCell(5).setCellValue(p.retailPrice ?: 0.0)
                                r.createCell(6).setCellValue(d.prevPurchase ?: 0.0)
                                r.createCell(7).setCellValue(d.prevRetail ?: 0.0)
                                r.createCell(8).setCellValue(d.supplierName ?: "")
                                r.createCell(9).setCellValue(d.categoryName ?: "")
                                r.createCell(10).setCellValue(p.stockQuantity ?: 0.0)
                            }
                        }

                        // 2) Suppliers
                        run {
                            val sheet = wb.createSheet(sheetSuppliers)
                            val h = sheet.createRow(0)
                            h.createCell(0).setCellValue("id")
                            h.createCell(1).setCellValue("name")
                            suppliers.forEachIndexed { idx, s ->
                                val r = sheet.createRow(idx + 1)
                                r.createCell(0).setCellValue(s.id.toDouble())
                                r.createCell(1).setCellValue(s.name)
                            }
                        }

                        // 3) Categories
                        run {
                            val sheet = wb.createSheet(sheetCategories)
                            val h = sheet.createRow(0)
                            h.createCell(0).setCellValue("id")
                            h.createCell(1).setCellValue("name")
                            categories.forEachIndexed { idx, c ->
                                val r = sheet.createRow(idx + 1)
                                r.createCell(0).setCellValue(c.id.toDouble())
                                r.createCell(1).setCellValue(c.name)
                            }
                        }

                        // 4) PriceHistory (compute oldPrice accodando gli eventi ordinati)
                        run {
                            val sheet = wb.createSheet(sheetPriceHistory)
                            val h = sheet.createRow(0)
                            listOf(
                                "productBarcode",
                                "timestamp",
                                "type",
                                "oldPrice",
                                "newPrice",
                                "source"
                            ).forEachIndexed { i, t -> h.createCell(i).setCellValue(t) }

                            // group per (barcode,type) e ordina per timestamp
                            val grouped =
                                priceRows.groupBy { it.barcode + "|" + it.type.uppercase() }
                            var rowIdx = 1
                            for ((_, events) in grouped) {
                                var prev: Double? = null
                                for (e in events.sortedBy { it.timestamp }) {
                                    val r = sheet.createRow(rowIdx++)
                                    r.createCell(0).setCellValue(e.barcode)
                                    r.createCell(1).setCellValue(e.timestamp)
                                    val t = e.type.uppercase()
                                    r.createCell(2)
                                        .setCellValue(if (t.startsWith("PUR")) "purchase" else "retail")
                                    prev?.let { r.createCell(3).setCellValue(it) }
                                        ?: r.createCell(3).setBlank()
                                    r.createCell(4).setCellValue(e.price)
                                    r.createCell(5).setCellValue(e.source ?: "")
                                    prev = e.price
                                }
                            }
                        }

                        context.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
                    }
                }

                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(context.getString(R.string.export_error, e.message ?: context.getString(R.string.unknown_error)))
            }
        }
    }

    // ⬇️ IMPORT COMPLETO: nuovo metodo pubblico
    fun startFullDbImport(context: Context, uri: Uri) {
        // blocca se c'è già un import in corso
        if (!importMutex.tryLock()) return
        resetPendingImportState(clearAnalysisResult = true)
        _uiState.value = UiState.Loading(message = context.getString(R.string.operation_in_progress))

        viewModelScope.launch {
            try {
                // 1) log di avvio
                currentImportLogUid = startHistoryLog("FULL_IMPORT", "Analisi file avviata: $uri")
                Log.d("DB_IMPORT", "FULL_IMPORT START uri=$uri")

                // 2) lettura/analisi su IO
                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_loading_file),
                    progress = 5
                )

                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_fetching_db),
                    progress = 55
                )
                val currentDbProducts = withContext(Dispatchers.IO) {
                    repository.getAllProducts()
                }

                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_analyzing),
                    progress = 85
                )

                val importResult = withContext(Dispatchers.IO) {
                    analyzeFullDbImportStreaming(
                        context = context,
                        uri = uri,
                        currentDbProducts = currentDbProducts,
                        repository = repository
                    )
                }

                _importAnalysisResult.value = importResult.analysis.analysis
                pendingPriceHistory = emptyList()
                pendingSupplierNames = importResult.pendingSupplierNames
                pendingCategoryNames = importResult.pendingCategoryNames
                pendingTempSuppliers = importResult.analysis.pendingSuppliers
                pendingTempCategories = importResult.analysis.pendingCategories
                pendingFullImportUri = uri
                hasPendingPriceHistorySheet = importResult.hasPriceHistorySheet

                _uiState.value = UiState.Idle
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(
                        uid,
                        "SUCCESS",
                        "Analisi completata. Products=${importResult.productsRowCount}, Suppliers=${importResult.supplierRowCount}, Categories=${importResult.categoryRowCount}, PriceHistory=${importResult.hasPriceHistorySheet}."
                    )
                    Log.d("DB_IMPORT", "FULL_IMPORT SUCCESS uid=$uid")
                }
                currentImportLogUid = null

            } catch (e: CancellationException) {
                resetPendingImportState(clearAnalysisResult = true)
                _uiState.value = UiState.Idle
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(uid, "CANCELLED", "Analisi annullata.")
                    Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED uid=$uid")
                }
                currentImportLogUid = null
                throw e

            } catch (e: OutOfMemoryError) {
                resetPendingImportState(clearAnalysisResult = true)
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(
                    context.getString(R.string.error_data_analysis, errorMessage)
                )
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(uid, "FAILED", "Analisi fallita per memoria insufficiente: $errorMessage")
                    Log.e("DB_IMPORT", "FULL_IMPORT OOM uid=$uid", e)
                }
                currentImportLogUid = null

            } catch (e: Exception) {
                resetPendingImportState(clearAnalysisResult = true)
                _uiState.value = UiState.Error(
                    context.getString(R.string.error_data_analysis, e.message ?: context.getString(R.string.unknown_error))
                )
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(uid, "FAILED", "Analisi fallita: ${e.message}")
                    Log.e("DB_IMPORT", "FULL_IMPORT FAILED uid=$uid", e)
                }
                currentImportLogUid = null

            } finally {
                importMutex.unlock()
            }
        }
    }
}

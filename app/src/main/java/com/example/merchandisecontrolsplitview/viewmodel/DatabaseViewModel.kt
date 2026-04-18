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
import com.example.merchandisecontrolsplitview.util.DatabaseExportSheet
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import com.example.merchandisecontrolsplitview.util.ImportAnalyzer
import com.example.merchandisecontrolsplitview.util.SmartImportWorkbookOutcome
import com.example.merchandisecontrolsplitview.util.analyzeSmartImportWorkbook
import com.example.merchandisecontrolsplitview.util.analyzeFullDbImportStreaming
import com.example.merchandisecontrolsplitview.util.buildDatabaseExportSchema
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.example.merchandisecontrolsplitview.util.resolveExcelFileErrorMessage
import com.example.merchandisecontrolsplitview.util.writeDatabaseExportStreaming
import java.io.IOException
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.coroutines.sync.Mutex

sealed class UiState {
    data object Idle : UiState()
    data class Loading(val message: String? = null, val progress: Int? = null) : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

sealed interface ImportFlowState {
    data object Idle : ImportFlowState
    data object PreviewLoading : ImportFlowState
    data class PreviewReady(val previewId: Long) : ImportFlowState
    data class Applying(val previewId: Long) : ImportFlowState
    data class Success(val previewId: Long) : ImportFlowState
    data class Error(
        val previewId: Long?,
        val message: String,
        val occurredDuringApply: Boolean
    ) : ImportFlowState
    data class Cancelled(val previewId: Long?) : ImportFlowState
}

data class ExportUiState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val progress: Int? = null
)

private data class ExportProgressSnapshot(
    val message: String,
    val progress: Int
)

private class ExportProgressTracker(
    private val context: Context,
    selection: ExportSheetSelection
) {
    private val totalWeight = selection.selectedSheetsInOrder()
        .sumOf(DatabaseExportSheet::weight)
        .coerceAtLeast(1)
    private var completedWeight = 0f

    fun preparing(): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(R.string.export_preparing),
            progress = 5
        )

    fun fetching(sheet: DatabaseExportSheet): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(
                R.string.export_fetching_sheet,
                context.getString(sheet.labelRes)
            ),
            progress = progressFor(completedWeight)
        )

    fun fetched(sheet: DatabaseExportSheet): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(
                R.string.export_writing_sheet,
                context.getString(sheet.labelRes)
            ),
            progress = progressFor(completedWeight + (sheet.weight * 0.4f))
        )

    fun sheetWritten(sheet: DatabaseExportSheet): ExportProgressSnapshot {
        completedWeight += sheet.weight.toFloat()
        return ExportProgressSnapshot(
            message = context.getString(
                R.string.export_writing_sheet,
                context.getString(sheet.labelRes)
            ),
            progress = progressFor(completedWeight)
        )
    }

    fun finishing(): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(R.string.export_finishing),
            progress = 97
        )

    private fun progressFor(consumedWeight: Float): Int {
        val normalized = (consumedWeight / totalWeight).coerceIn(0f, 1f)
        return (5 + (normalized * 90f)).toInt().coerceIn(5, 95)
    }
}

private class FullImportAlreadyInProgressException : RuntimeException(null, null, false, false)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class DatabaseViewModel(
    app: Application,
    private val repository: InventoryRepository
) : AndroidViewModel(app) {
    private val importMutex = Mutex()
    private val exportMutex = Mutex()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val _importFlowState = MutableStateFlow<ImportFlowState>(ImportFlowState.Idle)
    val importFlowState: StateFlow<ImportFlowState> = _importFlowState.asStateFlow()
    private val _exportUiState = MutableStateFlow(ExportUiState())
    val exportUiState: StateFlow<ExportUiState> = _exportUiState.asStateFlow()
    private val _selectedHubTab = MutableStateFlow(DatabaseHubTab.PRODUCTS)
    val selectedHubTab: StateFlow<DatabaseHubTab> = _selectedHubTab.asStateFlow()

    fun consumeUiState() { _uiState.value = UiState.Idle }
    private val _filter = MutableStateFlow<String?>(null)

    private val appContext = getApplication<Application>().applicationContext
    val filter: StateFlow<String?> = _filter.asStateFlow()

    private val _supplierCatalogQuery = MutableStateFlow("")
    private val _supplierCatalogRefresh = MutableStateFlow(0)
    val supplierCatalogQuery: StateFlow<String> = _supplierCatalogQuery.asStateFlow()
    val supplierCatalogSection: StateFlow<CatalogSectionUiState> = catalogSectionState(
        kind = CatalogEntityKind.SUPPLIER,
        queryFlow = _supplierCatalogQuery,
        refreshFlow = _supplierCatalogRefresh
    )

    private val _categoryCatalogQuery = MutableStateFlow("")
    private val _categoryCatalogRefresh = MutableStateFlow(0)
    val categoryCatalogQuery: StateFlow<String> = _categoryCatalogQuery.asStateFlow()
    val categoryCatalogSection: StateFlow<CatalogSectionUiState> = catalogSectionState(
        kind = CatalogEntityKind.CATEGORY,
        queryFlow = _categoryCatalogQuery,
        refreshFlow = _categoryCatalogRefresh
    )

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
        .flatMapLatest { query -> repository.observeSuppliersForHubSearch(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _categoryInputText = MutableStateFlow("")
    val categoryInputText: StateFlow<String> = _categoryInputText.asStateFlow()

    fun onCategorySearchQueryChanged(query: String) {
        _categoryInputText.value = query
    }

    val categories: StateFlow<List<Category>> = _categoryInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query -> repository.observeCategoriesForHubSearch(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList()
    private var pendingSupplierNames: Set<String> = emptySet()
    private var pendingCategoryNames: Set<String> = emptySet()
    private var pendingTempSuppliers: Map<Long, String> = emptyMap()
    private var pendingTempCategories: Map<Long, String> = emptyMap()
    private var activePreviewId: Long? = null
    private var nextPreviewId = 1L
    private var nextPendingSupplierTempId = -1L
    private var nextPendingCategoryTempId = -1L

    private fun analysisErrorMessage(context: Context, throwable: Throwable): String {
        return resolveExcelFileErrorMessage(
            context = context,
            throwable = throwable,
            unknownFallbackResId = R.string.error_data_analysis_generic
        )
    }

    private fun importErrorMessage(context: Context, throwable: Throwable): String {
        return resolveExcelFileErrorMessage(
            context = context,
            throwable = throwable,
            unknownFallbackResId = R.string.error_import_generic
        )
    }

    private fun exportErrorMessage(context: Context, throwable: Throwable): String {
        return when (throwable) {
            is OutOfMemoryError -> context.getString(R.string.error_file_too_large_or_complex)
            is SecurityException, is IOException ->
                context.getString(R.string.error_file_access_denied)
            else -> context.getString(R.string.error_export_generic)
        }
    }

    private fun updateExportUiState(snapshot: ExportProgressSnapshot) {
        _exportUiState.value = ExportUiState(
            inProgress = true,
            message = snapshot.message,
            progress = snapshot.progress
        )
    }

    private fun clearExportUiState() {
        _exportUiState.value = ExportUiState()
    }

    fun selectHubTab(tab: DatabaseHubTab) {
        _selectedHubTab.value = tab
    }

    fun onCatalogQueryChanged(kind: CatalogEntityKind, query: String) {
        when (kind) {
            CatalogEntityKind.SUPPLIER -> _supplierCatalogQuery.value = query
            CatalogEntityKind.CATEGORY -> _categoryCatalogQuery.value = query
        }
    }

    fun retryCatalogSection(kind: CatalogEntityKind) {
        refreshCatalogSection(kind)
    }

    private fun allocatePreviewId(): Long = nextPreviewId++

    private fun updatePendingTempCounters() {
        nextPendingSupplierTempId = (pendingTempSuppliers.keys.minOrNull() ?: 0L) - 1L
        nextPendingCategoryTempId = (pendingTempCategories.keys.minOrNull() ?: 0L) - 1L
    }

    private fun publishPreviewAnalysis(
        analysis: ImportAnalysis,
        pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList(),
        pendingSupplierNames: Set<String> = emptySet(),
        pendingCategoryNames: Set<String> = emptySet(),
        pendingTempSuppliers: Map<Long, String> = emptyMap(),
        pendingTempCategories: Map<Long, String> = emptyMap()
    ) {
        _importAnalysisResult.value = analysis
        this.pendingPriceHistory = pendingPriceHistory
        this.pendingSupplierNames = pendingSupplierNames
        this.pendingCategoryNames = pendingCategoryNames
        this.pendingTempSuppliers = pendingTempSuppliers
        this.pendingTempCategories = pendingTempCategories
        updatePendingTempCounters()
        val previewId = allocatePreviewId()
        activePreviewId = previewId
        _importFlowState.value = ImportFlowState.PreviewReady(previewId)
    }

    private fun clearPendingImportState(clearAnalysisResult: Boolean) {
        if (clearAnalysisResult) {
            _importAnalysisResult.value = null
        }
        pendingPriceHistory = emptyList()
        pendingSupplierNames = emptySet()
        pendingCategoryNames = emptySet()
        pendingTempSuppliers = emptyMap()
        pendingTempCategories = emptyMap()
        activePreviewId = null
        nextPendingSupplierTempId = -1L
        nextPendingCategoryTempId = -1L
    }

    fun cancelImportPreview() {
        val previewId = activePreviewId
        clearPendingImportState(clearAnalysisResult = true)
        _importFlowState.value = if (previewId != null) {
            ImportFlowState.Cancelled(previewId)
        } else {
            ImportFlowState.Idle
        }
    }

    fun dismissImportPreview() {
        clearPendingImportState(clearAnalysisResult = true)
        _importFlowState.value = ImportFlowState.Idle
    }

    private fun markPreviewLoading() {
        _importFlowState.value = ImportFlowState.PreviewLoading
    }

    private fun markPreviewError(message: String) {
        _importFlowState.value = ImportFlowState.Error(
            previewId = activePreviewId,
            message = message,
            occurredDuringApply = false
        )
    }

    suspend fun resolveImportPreviewSupplierId(name: String): Long? {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return null
        repository.findSupplierByName(normalizedName)?.let { return it.id }
        pendingTempSuppliers.entries.firstOrNull { (_, value) ->
            value.equals(normalizedName, ignoreCase = true)
        }?.let { return it.key }

        val tempId = nextPendingSupplierTempId--
        pendingTempSuppliers = pendingTempSuppliers + (tempId to normalizedName)
        return tempId
    }

    suspend fun resolveImportPreviewCategoryId(name: String): Long? {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return null
        repository.findCategoryByName(normalizedName)?.let { return it.id }
        pendingTempCategories.entries.firstOrNull { (_, value) ->
            value.equals(normalizedName, ignoreCase = true)
        }?.let { return it.key }

        val tempId = nextPendingCategoryTempId--
        pendingTempCategories = pendingTempCategories + (tempId to normalizedName)
        return tempId
    }

    suspend fun getSupplierDisplayName(id: Long?): String? {
        if (id == null) return null
        if (id < 0L) return pendingTempSuppliers[id]
        return repository.getSupplierById(id)?.name
    }

    suspend fun getCategoryDisplayName(id: Long?): String? {
        if (id == null) return null
        if (id < 0L) return pendingTempCategories[id]
        return repository.getCategoryById(id)?.name
    }

    fun setFilter(text: String) {
        _filter.value = text.ifBlank { null }
    }

    private fun catalogSectionState(
        kind: CatalogEntityKind,
        queryFlow: StateFlow<String>,
        refreshFlow: StateFlow<Int>
    ): StateFlow<CatalogSectionUiState> {
        val debouncedQuery = queryFlow
            .debounce(250L)
            .distinctUntilChanged()

        return combine(debouncedQuery, refreshFlow) { query, _ -> query }
            .flatMapLatest { query ->
                val trimmedQuery = query.trim().takeIf { it.isNotEmpty() }
                repository.observeCatalogItems(kind, trimmedQuery)
                    .map { items ->
                        CatalogSectionUiState(query = query, isLoading = false, items = items)
                    }
                    .onStart {
                        emit(CatalogSectionUiState(query = query, isLoading = true))
                    }
                    .catch { throwable ->
                        Log.e("DATABASE_HUB", "Unable to load catalog section: $kind", throwable)
                        emit(
                            CatalogSectionUiState(
                                query = query,
                                isLoading = false,
                                errorMessage = catalogLoadErrorMessage(kind)
                            )
                        )
                    }
            }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = CatalogSectionUiState(isLoading = true)
        )
    }

    private fun refreshCatalogSection(kind: CatalogEntityKind) {
        when (kind) {
            CatalogEntityKind.SUPPLIER -> _supplierCatalogRefresh.value += 1
            CatalogEntityKind.CATEGORY -> _categoryCatalogRefresh.value += 1
        }
    }

    private fun catalogEntityLabel(kind: CatalogEntityKind): String =
        appContext.getString(
            when (kind) {
                CatalogEntityKind.SUPPLIER -> R.string.database_catalog_entity_supplier
                CatalogEntityKind.CATEGORY -> R.string.database_catalog_entity_category
            }
        )

    private fun catalogLoadErrorMessage(kind: CatalogEntityKind): String =
        appContext.getString(
            when (kind) {
                CatalogEntityKind.SUPPLIER -> R.string.database_suppliers_load_failed
                CatalogEntityKind.CATEGORY -> R.string.database_categories_load_failed
            }
        )

    private fun catalogOperationErrorMessage(
        kind: CatalogEntityKind,
        throwable: Throwable
    ): String = when (throwable) {
        is CatalogBlankNameException -> appContext.getString(
            R.string.database_catalog_name_required,
            catalogEntityLabel(kind)
        )

        is CatalogNameConflictException -> appContext.getString(
            R.string.database_catalog_name_exists,
            catalogEntityLabel(kind)
        )

        is CatalogEntityInUseException -> appContext.getString(
            R.string.database_catalog_delete_requires_resolution,
            catalogEntityLabel(kind),
            throwable.productCount
        )

        is CatalogInvalidReplacementException -> appContext.getString(
            R.string.database_catalog_replacement_invalid,
            catalogEntityLabel(kind)
        )

        is CatalogNotFoundException -> appContext.getString(
            R.string.database_catalog_item_missing,
            catalogEntityLabel(kind)
        )

        else -> appContext.getString(
            R.string.database_catalog_operation_failed,
            catalogEntityLabel(kind)
        )
    }

    private fun catalogDeleteSuccessMessage(
        kind: CatalogEntityKind,
        result: CatalogDeleteResult
    ): String = when (result.strategy) {
        CatalogDeleteStrategy.DeleteIfUnused -> appContext.getString(
            R.string.database_catalog_deleted,
            catalogEntityLabel(kind)
        )

        is CatalogDeleteStrategy.ReplaceWithExisting,
        is CatalogDeleteStrategy.CreateNewAndReplace -> appContext.getString(
            R.string.database_catalog_deleted_reassigned,
            catalogEntityLabel(kind),
            result.affectedProducts,
            result.replacementName.orEmpty()
        )

        CatalogDeleteStrategy.ClearAssignments -> appContext.getString(
            R.string.database_catalog_deleted_cleared,
            catalogEntityLabel(kind),
            result.affectedProducts
        )
    }

    private suspend fun <T> runCatalogMutation(
        kind: CatalogEntityKind,
        successMessage: (T) -> String,
        action: suspend () -> T
    ): T? = try {
        val result = action()
        _uiState.value = UiState.Success(successMessage(result))
        result
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) {
            throw throwable
        }
        throwable.printStackTrace()
        _uiState.value = UiState.Error(catalogOperationErrorMessage(kind, throwable))
        null
    }

    private val _importAnalysisResult = MutableStateFlow<ImportAnalysis?>(null)
    val importAnalysisResult: StateFlow<ImportAnalysis?> = _importAnalysisResult.asStateFlow()

    fun startSmartImport(context: Context, uri: Uri) {
        if (importFlowState.value is ImportFlowState.Applying) return
        viewModelScope.launch {
            var fullImportLockAcquired = false
            try {
                when (val outcome = withContext(Dispatchers.IO) {
                    analyzeSmartImportWorkbook(
                        context = context,
                        uri = uri,
                        repository = repository,
                        loadCurrentDbProducts = {
                            if (!importMutex.tryLock()) {
                                throw FullImportAlreadyInProgressException()
                            }
                            fullImportLockAcquired = true
                            prepareSmartFullImportAnalysis(context, uri)
                        }
                    )
                }) {
                    SmartImportWorkbookOutcome.SingleSheet -> startImportAnalysis(context, uri)
                    is SmartImportWorkbookOutcome.FullDatabaseAnalyzed ->
                        finalizeFullImportAnalysisSuccess(outcome.result)
                }
            } catch (_: FullImportAlreadyInProgressException) {
                return@launch
            } catch (e: CancellationException) {
                if (fullImportLockAcquired) {
                    handleSmartFullImportCancelled()
                }
                throw e
            } catch (e: OutOfMemoryError) {
                if (fullImportLockAcquired) {
                    handleSmartFullImportFailure(context, e)
                } else {
                    e.printStackTrace()
                    clearPendingImportState(clearAnalysisResult = true)
                    val userMessage = analysisErrorMessage(context, e)
                    _uiState.value = UiState.Error(userMessage)
                    markPreviewError(userMessage)
                }
            } catch (e: Exception) {
                if (fullImportLockAcquired) {
                    handleSmartFullImportFailure(context, e)
                } else {
                    handleImportAnalysisError(context, e)
                }
            } finally {
                if (fullImportLockAcquired) {
                    importMutex.unlock()
                }
            }
        }
    }

    private suspend fun prepareSmartFullImportAnalysis(
        context: Context,
        uri: Uri
    ): List<Product> {
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        withContext(Dispatchers.Main) {
            _uiState.value = UiState.Loading(
                message = context.getString(R.string.import_loading_file),
                progress = 5
            )
        }

        Log.d("DB_IMPORT", "FULL_IMPORT START uri=$uri")

        withContext(Dispatchers.Main) {
            _uiState.value = UiState.Loading(
                message = context.getString(R.string.import_fetching_db),
                progress = 55
            )
        }
        val currentDbProducts = repository.getAllProducts()

        withContext(Dispatchers.Main) {
            _uiState.value = UiState.Loading(
                message = context.getString(R.string.import_analyzing),
                progress = 85
            )
        }

        return currentDbProducts
    }

    private suspend fun finalizeFullImportAnalysisSuccess(
        importResult: com.example.merchandisecontrolsplitview.util.FullDbImportStreamingResult
    ) {
        publishPreviewAnalysis(
            analysis = importResult.analysis.analysis,
            pendingPriceHistory = importResult.pendingPriceHistory,
            pendingSupplierNames = importResult.pendingSupplierNames,
            pendingCategoryNames = importResult.pendingCategoryNames,
            pendingTempSuppliers = importResult.analysis.pendingSuppliers,
            pendingTempCategories = importResult.analysis.pendingCategories
        )
        _uiState.value = UiState.Idle
        Log.d(
            "DB_IMPORT",
            "FULL_IMPORT SUCCESS products=${importResult.productsRowCount} " +
                "suppliers=${importResult.supplierRowCount} " +
                "categories=${importResult.categoryRowCount} " +
                "priceHistory=${importResult.hasPriceHistorySheet}"
        )
    }

    private suspend fun handleSmartFullImportCancelled() {
        cancelImportPreview()
        _uiState.value = UiState.Idle
        Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED")
    }

    private suspend fun handleSmartFullImportFailure(
        context: Context,
        throwable: Throwable
    ) {
        clearPendingImportState(clearAnalysisResult = true)
        val userMessage = analysisErrorMessage(context, throwable)
        _uiState.value = UiState.Error(userMessage)
        markPreviewError(userMessage)
        Log.e("DB_IMPORT", "FULL_IMPORT FAILED message=$userMessage", throwable)
    }

    fun startImportAnalysis(context: Context, uri: Uri) {
        if (importFlowState.value is ImportFlowState.Applying) return
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
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

                publishPreviewAnalysis(
                    analysis = analysis.analysis,
                    pendingTempSuppliers = analysis.pendingSuppliers,
                    pendingTempCategories = analysis.pendingCategories
                )
                _uiState.value = UiState.Idle
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
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
            val userMessage = context.getString(R.string.error_file_empty_or_invalid)
            _uiState.value = UiState.Error(userMessage)
            markPreviewError(userMessage)
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
    ): ImportAnalyzer.DeferredRelationImportAnalysis {
        return withContext(Dispatchers.Default) {
            ImportAnalyzer.analyzeStreamingDeferredRelations(
                context = context,
                currentDbProducts = currentDbProducts,
                repository = repository
            ) { consumer ->
                chunks.forEach { chunk -> chunk.forEach(consumer) }
            }
        }
    }

    private fun handleImportAnalysisError(context: Context, e: Exception) {
        e.printStackTrace()
        clearPendingImportState(clearAnalysisResult = true)
        val userMessage = analysisErrorMessage(context, e)
        _uiState.value = UiState.Error(userMessage)
        markPreviewError(userMessage)
    }

    fun clearImportAnalysis() {
        when (importFlowState.value) {
            is ImportFlowState.Applying -> Unit
            is ImportFlowState.PreviewReady,
            is ImportFlowState.PreviewLoading -> cancelImportPreview()
            else -> dismissImportPreview()
        }
    }

    fun importProducts(
        previewId: Long,
        newProducts: List<Product>,
        updatedProducts: List<ProductUpdate>,
        context: Context
    ) {
        val hasMatchingPreview = when (val state = importFlowState.value) {
            is ImportFlowState.PreviewReady -> state.previewId == previewId
            is ImportFlowState.Error -> state.previewId == previewId
            else -> false
        }
        if (!hasMatchingPreview || activePreviewId != previewId) {
            val message = context.getString(R.string.import_preview_invalidated)
            _uiState.value = UiState.Error(message)
            _importFlowState.value = ImportFlowState.Error(
                previewId = activePreviewId,
                message = message,
                occurredDuringApply = false
            )
            return
        }

        val importRequest = ImportApplyRequest(
            newProducts = newProducts,
            updatedProducts = updatedProducts,
            pendingSupplierNames = pendingSupplierNames.toSet(),
            pendingCategoryNames = pendingCategoryNames.toSet(),
            pendingTempSuppliers = pendingTempSuppliers.toMap(),
            pendingTempCategories = pendingTempCategories.toMap(),
            pendingPriceHistory = pendingPriceHistory.toList()
        )
        _importFlowState.value = ImportFlowState.Applying(previewId)

        _uiState.value = UiState.Loading(
            message = context.getString(R.string.import_applying_changes),
            progress = 90
        )

        viewModelScope.launch {
            Log.d(
                "DB_IMPORT",
                "APPLY_IMPORT START previewId=$previewId new=${newProducts.size} updated=${updatedProducts.size}"
            )

            try {
                when (val outcome = withContext(Dispatchers.IO) {
                    repository.applyImport(importRequest)
                }) {
                    ImportApplyResult.Success -> {
                        _uiState.value = UiState.Loading(
                            message = context.getString(R.string.import_applying_changes),
                            progress = 98
                        )

                        Log.d("DB_IMPORT", "APPLY_IMPORT SUCCESS previewId=$previewId")
                        _importFlowState.value = ImportFlowState.Success(previewId)
                        _uiState.value = UiState.Success(context.getString(R.string.import_success))
                    }
                    ImportApplyResult.AlreadyRunning -> {
                        val userMessage = context.getString(R.string.operation_in_progress)
                        _uiState.value = UiState.Error(userMessage)
                        _importFlowState.value = ImportFlowState.Error(
                            previewId = previewId,
                            message = userMessage,
                            occurredDuringApply = true
                        )
                    }
                    is ImportApplyResult.Failure -> {
                        val userMessage = importErrorMessage(context, outcome.cause)
                        _uiState.value = UiState.Error(userMessage)
                        _importFlowState.value = ImportFlowState.Error(
                            previewId = previewId,
                            message = userMessage,
                            occurredDuringApply = true
                        )
                        Log.e("DB_IMPORT", "APPLY_IMPORT FAILED previewId=$previewId", outcome.cause)
                    }
                }
            } catch (e: CancellationException) {
                val userMessage = importErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                _importFlowState.value = ImportFlowState.Error(
                    previewId = previewId,
                    message = userMessage,
                    occurredDuringApply = true
                )
                Log.e("DB_IMPORT", "APPLY_IMPORT CANCELLED previewId=$previewId", e)
            } catch (e: Exception) {
                val userMessage = importErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                _importFlowState.value = ImportFlowState.Error(
                    previewId = previewId,
                    message = userMessage,
                    occurredDuringApply = true
                )
                Log.e("DB_IMPORT", "APPLY_IMPORT FAILED previewId=$previewId", e)
            }
        }
    }

    fun exportDatabase(
        context: Context,
        uri: Uri,
        selection: ExportSheetSelection
    ) {
        if (selection.isEmpty || !exportMutex.tryLock()) return

        val progressTracker = ExportProgressTracker(context, selection)
        updateExportUiState(progressTracker.preparing())

        viewModelScope.launch {
            try {
                val schema = buildDatabaseExportSchema(context)

                val suppliers = if (selection.suppliers) {
                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.SUPPLIERS))
                    repository.getAllSuppliers().also {
                        updateExportUiState(progressTracker.fetched(DatabaseExportSheet.SUPPLIERS))
                    }
                } else {
                    emptyList()
                }

                val categories = if (selection.categories) {
                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.CATEGORIES))
                    repository.getAllCategories().also {
                        updateExportUiState(progressTracker.fetched(DatabaseExportSheet.CATEGORIES))
                    }
                } else {
                    emptyList()
                }

                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        writeDatabaseExportStreaming(
                            outputStream = outputStream,
                            selection = selection,
                            schema = schema,
                            suppliers = suppliers,
                            categories = categories,
                            fetchProductPage = { limit, offset ->
                                repository.getProductsWithDetailsPage(limit, offset)
                            },
                            fetchPriceHistoryPage = { limit, offset ->
                                repository.getPriceHistoryRowsPage(limit, offset)
                            },
                            onBeforeProductsSheet = {
                                if (selection.products) {
                                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.PRODUCTS))
                                }
                            },
                            onAfterFirstProductPageFetched = {
                                if (selection.products) {
                                    updateExportUiState(progressTracker.fetched(DatabaseExportSheet.PRODUCTS))
                                }
                            },
                            onBeforePriceHistorySheet = {
                                if (selection.priceHistory) {
                                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.PRICE_HISTORY))
                                }
                            },
                            onAfterFirstPriceHistoryPageFetched = {
                                if (selection.priceHistory) {
                                    updateExportUiState(progressTracker.fetched(DatabaseExportSheet.PRICE_HISTORY))
                                }
                            },
                            onSheetWritten = { sheet ->
                                updateExportUiState(progressTracker.sheetWritten(sheet))
                            }
                        )
                    } ?: throw IOException("Unable to open output stream for $uri")
                }

                updateExportUiState(progressTracker.finishing())
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _uiState.value = UiState.Error(exportErrorMessage(context, e))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(exportErrorMessage(context, e))
            } finally {
                clearExportUiState()
                exportMutex.unlock()
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

    fun updateProduct(product: Product) {
        viewModelScope.launch {
            try {
                repository.updateProduct(product)
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

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            try {
                repository.deleteProduct(product)
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_deleted))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_deleted))
            }
        }
    }

    fun analyzeGridData(gridData: List<Map<String, String>>) {
        if (importFlowState.value is ImportFlowState.Applying) return
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 10)
        viewModelScope.launch {
            try {
                val currentDbProducts = withContext(Dispatchers.IO) {
                    _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_fetching_db), progress = 30)
                    repository.getAllProducts()
                }
                _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 70)
                val analysis = withContext(Dispatchers.Default) {
                    ImportAnalyzer.analyzeStreamingDeferredRelations(
                        context = appContext,
                        currentDbProducts = currentDbProducts,
                        repository = repository
                    ) { consumer ->
                        gridData.forEach(consumer)
                    }
                }
                publishPreviewAnalysis(
                    analysis = analysis.analysis,
                    pendingTempSuppliers = analysis.pendingSuppliers,
                    pendingTempCategories = analysis.pendingCategories
                )
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(appContext, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
            }
        }
    }

    suspend fun addSupplier(name: String): Supplier? {
        return repository.addSupplier(name)
    }

    suspend fun addCategory(name: String): Category? {
        return repository.addCategory(name)
    }

    suspend fun createCatalogEntry(
        kind: CatalogEntityKind,
        name: String
    ): CatalogListItem? = runCatalogMutation(
        kind = kind,
        successMessage = {
            appContext.getString(
                R.string.database_catalog_created,
                catalogEntityLabel(kind)
            )
        }
    ) {
        repository.createCatalogEntry(kind, name)
    }

    suspend fun renameCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        newName: String
    ): CatalogListItem? = runCatalogMutation(
        kind = kind,
        successMessage = {
            appContext.getString(
                R.string.database_catalog_renamed,
                catalogEntityLabel(kind)
            )
        }
    ) {
        repository.renameCatalogEntry(kind, id, newName)
    }

    suspend fun deleteCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        strategy: CatalogDeleteStrategy
    ): CatalogDeleteResult? = runCatalogMutation(
        kind = kind,
        successMessage = { result -> catalogDeleteSuccessMessage(kind, result) }
    ) {
        repository.deleteCatalogEntry(kind, id, strategy)
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

    // ⬇️ IMPORT COMPLETO: nuovo metodo pubblico
    fun startFullDbImport(context: Context, uri: Uri) {
        if (importFlowState.value is ImportFlowState.Applying) return
        // blocca se c'è già un import in corso
        if (!importMutex.tryLock()) return
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        _uiState.value = UiState.Loading(message = context.getString(R.string.operation_in_progress))

        viewModelScope.launch {
            try {
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

                publishPreviewAnalysis(
                    analysis = importResult.analysis.analysis,
                    pendingPriceHistory = importResult.pendingPriceHistory,
                    pendingSupplierNames = importResult.pendingSupplierNames,
                    pendingCategoryNames = importResult.pendingCategoryNames,
                    pendingTempSuppliers = importResult.analysis.pendingSuppliers,
                    pendingTempCategories = importResult.analysis.pendingCategories
                )
                _uiState.value = UiState.Idle
                Log.d(
                    "DB_IMPORT",
                    "FULL_IMPORT SUCCESS products=${importResult.productsRowCount} " +
                        "suppliers=${importResult.supplierRowCount} " +
                        "categories=${importResult.categoryRowCount} " +
                        "priceHistory=${importResult.hasPriceHistorySheet}"
                )

            } catch (e: CancellationException) {
                cancelImportPreview()
                _uiState.value = UiState.Idle
                Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED")
                throw e

            } catch (e: OutOfMemoryError) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
                Log.e("DB_IMPORT", "FULL_IMPORT OOM message=$userMessage", e)

            } catch (e: Exception) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
                Log.e("DB_IMPORT", "FULL_IMPORT FAILED message=$userMessage", e)

            } finally {
                importMutex.unlock()
            }
        }
    }

    companion object {
        fun factory(app: Application, repository: InventoryRepository): androidx.lifecycle.ViewModelProvider.Factory {
            return object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DatabaseViewModel::class.java)) {
                        return DatabaseViewModel(app, repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}

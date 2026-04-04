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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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


    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Uid dell'entry di log per il "full import" (analisi file)
    private var currentImportLogUid: Long? = null
    private val importMutex = Mutex()
    private val exportMutex = Mutex()
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
    private val _importFlowState = MutableStateFlow<ImportFlowState>(ImportFlowState.Idle)
    val importFlowState: StateFlow<ImportFlowState> = _importFlowState.asStateFlow()
    private val _exportUiState = MutableStateFlow(ExportUiState())
    val exportUiState: StateFlow<ExportUiState> = _exportUiState.asStateFlow()

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

        currentImportLogUid = startHistoryLog("FULL_IMPORT", "Analisi file avviata: $uri")
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
        currentImportLogUid?.let { uid ->
            appendHistoryLog(
                uid,
                "SUCCESS",
                "Analisi completata. Products=${importResult.productsRowCount}, Suppliers=${importResult.supplierRowCount}, Categories=${importResult.categoryRowCount}, PriceHistory=${importResult.hasPriceHistorySheet}."
            )
            Log.d("DB_IMPORT", "FULL_IMPORT SUCCESS uid=$uid")
        }
        currentImportLogUid = null
    }

    private suspend fun handleSmartFullImportCancelled() {
        cancelImportPreview()
        _uiState.value = UiState.Idle
        currentImportLogUid?.let { uid ->
            appendHistoryLog(uid, "CANCELLED", "Analisi annullata.")
            Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED uid=$uid")
        }
        currentImportLogUid = null
    }

    private suspend fun handleSmartFullImportFailure(
        context: Context,
        throwable: Throwable
    ) {
        clearPendingImportState(clearAnalysisResult = true)
        val userMessage = analysisErrorMessage(context, throwable)
        _uiState.value = UiState.Error(userMessage)
        markPreviewError(userMessage)
        currentImportLogUid?.let { uid ->
            appendHistoryLog(uid, "FAILED", userMessage)
            Log.e("DB_IMPORT", "FULL_IMPORT FAILED uid=$uid", throwable)
        }
        currentImportLogUid = null
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
            val applyLogUid = startHistoryLog(
                "APPLY_IMPORT",
                "Applico import: new=${newProducts.size}, updated=${updatedProducts.size}"
            )
            Log.d("DB_IMPORT", "APPLY_IMPORT START uid=$applyLogUid")

            try {
                when (val outcome = withContext(Dispatchers.IO) {
                    repository.applyImport(importRequest)
                }) {
                    ImportApplyResult.Success -> {
                        _uiState.value = UiState.Loading(
                            message = context.getString(R.string.import_applying_changes),
                            progress = 98
                        )

                        appendHistoryLog(applyLogUid, "SUCCESS", "Import applicato correttamente.")
                        Log.d("DB_IMPORT", "APPLY_IMPORT SUCCESS uid=$applyLogUid")
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
                        appendHistoryLog(applyLogUid, "FAILED", userMessage)
                        Log.e("DB_IMPORT", "APPLY_IMPORT FAILED uid=$applyLogUid", outcome.cause)
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
                appendHistoryLog(applyLogUid, "FAILED", userMessage)
                Log.e("DB_IMPORT", "APPLY_IMPORT CANCELLED uid=$applyLogUid", e)
            } catch (e: Exception) {
                val userMessage = importErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                _importFlowState.value = ImportFlowState.Error(
                    previewId = previewId,
                    message = userMessage,
                    occurredDuringApply = true
                )
                appendHistoryLog(applyLogUid, "FAILED", userMessage)
                Log.e("DB_IMPORT", "APPLY_IMPORT FAILED uid=$applyLogUid", e)
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

                publishPreviewAnalysis(
                    analysis = importResult.analysis.analysis,
                    pendingPriceHistory = importResult.pendingPriceHistory,
                    pendingSupplierNames = importResult.pendingSupplierNames,
                    pendingCategoryNames = importResult.pendingCategoryNames,
                    pendingTempSuppliers = importResult.analysis.pendingSuppliers,
                    pendingTempCategories = importResult.analysis.pendingCategories
                )
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
                cancelImportPreview()
                _uiState.value = UiState.Idle
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(uid, "CANCELLED", "Analisi annullata.")
                    Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED uid=$uid")
                }
                currentImportLogUid = null
                throw e

            } catch (e: OutOfMemoryError) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(uid, "FAILED", userMessage)
                    Log.e("DB_IMPORT", "FULL_IMPORT OOM uid=$uid", e)
                }
                currentImportLogUid = null

            } catch (e: Exception) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
                currentImportLogUid?.let { uid ->
                    appendHistoryLog(uid, "FAILED", userMessage)
                    Log.e("DB_IMPORT", "FULL_IMPORT FAILED uid=$uid", e)
                }
                currentImportLogUid = null

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

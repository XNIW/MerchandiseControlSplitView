package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.HistoryDisplayEntry
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.HistoryEntryListItem
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.util.calculateTotalQuantityFromRows
import com.example.merchandisecontrolsplitview.util.formatClPriceInput
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.util.parseUserNumericInput
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Classe sealed per rappresentare i possibili stati del filtro data.
 * Questo approccio è type-safe e rende il codice più leggibile e manutenibile.
 */
sealed class DateFilter {
    object All : DateFilter()
    // Manteniamo il nome esistente per compatibilita, ma il filtro rappresenta il mese corrente.
    object LastMonth : DateFilter()
    object PreviousMonth : DateFilter()
    data class CustomRange(val startDate: LocalDate, val endDate: LocalDate) : DateFilter()
}

/**
 * Filtro combinato per la schermata Cronologia: periodo + fornitore + categoria.
 * Il filtro data opera a livello DB; supplier e category sono filtri in-memory.
 */
data class HistoryFilter(
    val dateFilter: DateFilter = DateFilter.All,
    val supplier: String = "",
    val category: String = ""
) {
    val isDateFilterActive: Boolean get() = dateFilter !is DateFilter.All
    val isSupplierFilterActive: Boolean get() = supplier.isNotBlank()
    val isCategoryFilterActive: Boolean get() = category.isNotBlank()
    val hasAnyActiveFilter: Boolean
        get() = isDateFilterActive || isSupplierFilterActive || isCategoryFilterActive
}

data class PreGenerateDataQualitySummary(
    val duplicateBarcodeCount: Int = 0,
    val duplicateBarcodeSamples: List<String> = emptyList(),
    val missingPurchasePriceCount: Int = 0
) {
    val hasWarnings: Boolean
        get() = duplicateBarcodeCount > 0 || missingPurchasePriceCount > 0
}

/**
 * ViewModel per gestione griglia Excel e cronologia persistente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExcelViewModel(
    application: Application,
    private val repository: InventoryRepository
) : AndroidViewModel(application) {
    private val localReadGate = LocalBusinessReadGate(application)
    // The shell owns the same RAM draft before and after physical opening.
    private var draft = ExcelImportDraftState(viewModelScope)
    // DB-derived History/old-price rows have a separate RAM projection. Only the
    // existing gated business routes consume it; the shell's raw draft never does.
    private val businessProjection = mutableStateOf<ExcelImportDraftState?>(null)
    private val displayedGrid: ExcelImportDraftState get() = businessProjection.value ?: draft
    internal val importDraftState: ExcelImportDraftState get() = draft
    internal constructor(application: Application, repository: InventoryRepository, sharedDraft: ExcelImportDraftState)
        : this(application, repository) { draft = sharedDraft }

    // Stato griglia
    val excelData get() = displayedGrid.excelData
    val originalHeaders get() = displayedGrid.originalHeaders
    val selectedColumns get() = displayedGrid.selectedColumns
    val editableValues get() = displayedGrid.editableValues
    val completeStates get() = displayedGrid.completeStates
    val errorRowIndexes get() = displayedGrid.errorRowIndexes

    // Flag UI
    val generated = mutableStateOf(false)
    val isLoading get() = draft.isLoading
    val loadError get() = draft.loadError
    val historyActionMessage = mutableStateOf<String?>(null)
    val currentEntryName = mutableStateOf("")
    val currentEntryFileName = mutableStateOf("")

    val loadingProgress get() = draft.loadingProgress

    val isExporting = mutableStateOf(false)
    val exportProgress = mutableStateOf<Int?>(null)
    private suspend fun postExport(p: Int?) = withContext(Dispatchers.Main) {
        exportProgress.value = p?.coerceIn(0, 100)
    }

    /** Export con progress (chiama questa, non più saveFileSuspend da UI) */
    suspend fun exportToUri(context: Context, uri: Uri) {
        isExporting.value = true
        postExport(5)
        try {
            withContext(Dispatchers.IO) {
                saveExcelFileInternal(
                    context, uri,
                    excelData, editableValues, completeStates,
                    supplierName, categoryName
                ) { pct ->
                    // siamo su IO -> aggiorna su Main
                    viewModelScope.launch(Dispatchers.Main) { exportProgress.value = pct }
                }
            }
            postExport(100)
        } finally {
            isExporting.value = false
            postExport(null)
        }
    }


    fun consumeHistoryActionMessage() {
        historyActionMessage.value = null
    }


    // Stato privato che mantiene il filtro data. Alimenta le query Room.
    private val _dateFilter = MutableStateFlow<DateFilter>(DateFilter.All)

    // Filtri in-memory per fornitore e categoria (applicati dopo il filtro DB).
    private val _supplierFilter = MutableStateFlow("")
    private val _categoryFilter = MutableStateFlow("")

    // Filtro combinato esposto alla UI: periodo + supplier + category.
    val historyFilter: StateFlow<HistoryFilter> = combine(
        _dateFilter, _supplierFilter, _categoryFilter
    ) { date, supplier, category ->
        HistoryFilter(dateFilter = date, supplier = supplier, category = category)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = HistoryFilter()
    )

    // Lista alleggerita per la schermata History: usa solo la history utente visibile
    // ed evita di caricare i blob della griglia nello scroll principale.
    // Filtrata solo per data — usata anche da GeneratedScreen per recuperare il timestamp entry.
    val historyListEntries: StateFlow<List<HistoryEntryListItem>> = localReadGate.observe(emptyList<HistoryEntryListItem>()) {
        _dateFilter.flatMapLatest { filter -> repository.getFilteredHistoryListFlow(filter) }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    // Flusso completo usato dai percorsi che devono leggere o aggiornare tutta l'entry
    // visibile all'utente.
    val historyEntries: StateFlow<List<HistoryEntry>> = localReadGate.observe(emptyList<HistoryEntry>()) {
        _dateFilter.flatMapLatest { filter -> repository.getFilteredHistoryFlow(filter) }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    // Lista per la visualizzazione nella HistoryScreen: filtrata anche per supplier e category.
    val historyDisplayEntries: StateFlow<List<HistoryDisplayEntry>> = combine(
        historyListEntries, historyEntries, _supplierFilter, _categoryFilter
    ) { entries, fullEntries, supplier, category ->
        val totalQuantityByUid = fullEntries.associate { entry ->
            entry.uid to calculateTotalQuantityFromRows(entry.data)
        }
        entries.filter { entry ->
            (supplier.isBlank() || entry.supplier.equals(supplier, ignoreCase = true)) &&
                (category.isBlank() || entry.category.equals(category, ignoreCase = true))
        }.map { entry ->
            HistoryDisplayEntry(
                listItem = entry,
                totalQuantity = totalQuantityByUid[entry.uid]
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    // Fornitori distinti disponibili nel periodo selezionato (prima del filtro in-memory).
    val availableHistorySuppliers: StateFlow<List<String>> = historyListEntries
        .map { entries ->
            entries.mapNotNull { e -> e.supplier.trim().takeIf { it.isNotBlank() } }
                .distinct()
                .sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    // Categorie distinte disponibili nel periodo selezionato (prima del filtro in-memory).
    val availableHistoryCategories: StateFlow<List<String>> = historyListEntries
        .map { entries ->
            entries.mapNotNull { e -> e.category.trim().takeIf { it.isNotBlank() } }
                .distinct()
                .sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    val hasHistoryEntries: StateFlow<Boolean> = localReadGate.observe(false) { repository.hasHistoryEntriesFlow() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = false
        )

    val currentEntryStatus = mutableStateOf(Triple(SyncStatus.NOT_ATTEMPTED, false, 0L))
    val headerTypes get() = displayedGrid.headerTypes

    /** Argomenti ultima navigazione a Generated (044C). */
    private var generatedRouteIsNew: Boolean = false
    private var generatedRouteIsManualEntry: Boolean = false
    private var importOriginForGeneratedSession: ImportNavOrigin = ImportNavOrigin.HOME

    fun noteGeneratedNavigationContext(
        isNew: Boolean,
        isManualEntry: Boolean,
        importOrigin: ImportNavOrigin
    ) {
        generatedRouteIsNew = isNew
        generatedRouteIsManualEntry = isManualEntry
        importOriginForGeneratedSession = importOrigin
    }

    fun peekGeneratedRouteIsNew(): Boolean = generatedRouteIsNew
    fun peekGeneratedRouteIsManualEntry(): Boolean = generatedRouteIsManualEntry
    fun peekImportOriginForGeneratedSession(): ImportNavOrigin = importOriginForGeneratedSession

    // --- BLOCCO DATABASE ---

    private var currentSupplierName: String = ""
    val supplierName: String
        get() = currentSupplierName

    private var currentCategoryName: String = ""
    val categoryName: String
        get() = currentCategoryName

    private var _originalHistoryEntryState: HistoryEntry? = null
    private var _preGenerateStateBackup: List<List<String>>? = null
    private var currentHistoryEntryObserverJob: Job? = null
    private val suppressedHistoryEntryObserverReloads = mutableMapOf<Long, Int>()

    val lastUsedCategory = mutableStateOf<String?>(null)

    // Funzione per verificare se una colonna è essenziale
    fun isColumnEssential(colIdx: Int): Boolean = displayedGrid.isColumnEssential(colIdx)

    // Funzione per gestire la selezione di una colonna, bloccando quelle essenziali
    fun toggleColumnSelection(colIdx: Int) = displayedGrid.toggleColumnSelection(colIdx)

    // Funzione per gestire il "Seleziona/Deseleziona Tutto" in modo sicuro
    fun toggleSelectAll() = displayedGrid.toggleSelectAll()

    /**
     * Funzione helper PRIVATA che popola lo stato live del ViewModel.
     * Non tocca i backup.
     */
    private fun populateStateFromEntry(entry: HistoryEntry) {
        // A stored session is business output, never raw file input.
        draft.retirePendingFileRead()
        businessProjection.value = ExcelImportDraftState(viewModelScope)
        excelData.clear()
        originalHeaders.clear()
        selectedColumns.clear()
        editableValues.clear()
        completeStates.clear()
        errorRowIndexes.value = emptySet()
        excelData.addAll(entry.data)
        originalHeaders.addAll(entry.data.firstOrNull()?.toList().orEmpty())
        entry.editable.forEach { row ->
            editableValues.add(row.map { mutableStateOf(it) }.toMutableList())
        }
        completeStates.addAll(entry.complete)
        excelData.firstOrNull()?.size?.let { cols ->
            repeat(cols) { selectedColumns.add(false) }
        }
        generated.value = true
        currentEntryName.value = entry.displayName
        currentEntryFileName.value = entry.id
        currentSupplierName = entry.supplier
        currentCategoryName = entry.category
        currentEntryStatus.value = Triple(entry.syncStatus, entry.wasExported, entry.uid)
    }

    /**
     * Sovrascrive la voce corrente nel database con il backup originale.
     * Questa è l'azione attiva di "Annulla modifiche".
     */
    suspend fun revertDatabaseToOriginalState() {
        _originalHistoryEntryState?.let { originalEntry ->
            repository.updateHistoryEntry(originalEntry)
        }
    }

    /**
     * Ripristina lo stato della griglia a come era in PreGenerateScreen.
     */
    fun revertToPreGenerateState() {
        if (_preGenerateStateBackup != null) clearBusinessPresentation()
        // The original raw object, mapping, typed inputs and editable values never changed.
    }

    fun clearOriginalState() {
        _originalHistoryEntryState = null
    }


    /**
     * Imposta il filtro combinato (periodo + fornitore + categoria) dalla UI.
     */
    fun setHistoryFilter(filter: HistoryFilter) {
        _dateFilter.value = filter.dateFilter
        _supplierFilter.value = filter.supplier
        _categoryFilter.value = filter.category
    }

    internal fun observeQualifiedHistory(
        key: LocalBusinessPresentationKey, scope: Task126OwnerStoreScope
    ): Flow<List<HistoryEntry>?> {
        val local = repository as? DefaultInventoryRepository ?: return flowOf(null)
        return local.observeQualifiedHistory(scope).map { entries ->
            entries?.takeIf { localReadGate.snapshot().key == key }
        }
    }

    internal fun historyDateRange(filter: DateFilter): Pair<String, String>? =
        (repository as? DefaultInventoryRepository)?.historyRangeFor(filter)

    fun loadHistoryEntry(entryUid: Long, onLoaded: (() -> Unit)? = null) {
        val selection = localReadGate.snapshot()
        if (entryUid <= 0L || (selection.state != null && selection.key == null)) return
        currentHistoryEntryObserverJob?.cancel()
        suppressedHistoryEntryObserverReloads.clear()
        _originalHistoryEntryState = null
        currentHistoryEntryObserverJob = viewModelScope.launch {
            var firstLoadDelivered = false
            localReadGate.observe<HistoryEntry?>(null) {
                if (localReadGate.snapshot().key == selection.key) repository.observeHistoryEntryByUid(entryUid)
                else flowOf(null)
            }.collect { entry ->
                val current = localReadGate.snapshot()
                if (current.key != selection.key || !localReadGate.isCurrent(current)) return@collect
                if (entry?.uid == entryUid) {
                    if (consumeSuppressedHistoryEntryObserverReload(entry.uid)) {
                        Log.i(
                            GENERATED_DB_TAG,
                            "flow=history_entry_observer uid=${entry.uid} outcome=skip_self_reload"
                        )
                    } else {
                        loadHistoryEntry(entry)
                    }
                    if (!firstLoadDelivered) {
                        firstLoadDelivered = true
                        onLoaded?.invoke()
                    }
                } else if (entry == null && firstLoadDelivered && currentEntryStatus.value.third == entryUid) {
                    clearBusinessPresentation()
                }
            }
        }
    }

    private fun shouldSuppressNextHistoryEntryObserverReload(uid: Long): Boolean =
        currentHistoryEntryObserverJob != null && currentEntryStatus.value.third == uid

    private fun suppressNextHistoryEntryObserverReload(uid: Long) {
        suppressedHistoryEntryObserverReloads[uid] = (suppressedHistoryEntryObserverReloads[uid] ?: 0) + 1
    }

    private fun cancelSuppressedHistoryEntryObserverReload(uid: Long) {
        val remaining = (suppressedHistoryEntryObserverReloads[uid] ?: 0) - 1
        if (remaining > 0) {
            suppressedHistoryEntryObserverReloads[uid] = remaining
        } else {
            suppressedHistoryEntryObserverReloads.remove(uid)
        }
    }

    private fun consumeSuppressedHistoryEntryObserverReload(uid: Long): Boolean {
        if (!suppressedHistoryEntryObserverReloads.containsKey(uid)) return false
        cancelSuppressedHistoryEntryObserverReload(uid)
        return true
    }

    private suspend fun updateHistoryEntryWithoutObserverReload(entry: HistoryEntry, flow: String) {
        val suppressObserverReload = shouldSuppressNextHistoryEntryObserverReload(entry.uid)
        if (suppressObserverReload) {
            suppressNextHistoryEntryObserverReload(entry.uid)
        }
        try {
            repository.updateHistoryEntry(entry)
            Log.i(
                GENERATED_DB_TAG,
                "flow=$flow uid=${entry.uid} rows=${entry.data.size.coerceAtLeast(0)} " +
                    "observerReloadSuppressed=$suppressObserverReload"
            )
        } catch (t: Throwable) {
            if (suppressObserverReload) {
                cancelSuppressedHistoryEntryObserverReload(entry.uid)
            }
            throw t
        }
    }

    fun loadFromMultipleUris(context: Context, uris: List<Uri>) {
        businessProjection.value = null
        draft.loadFromMultipleUris(context, uris)
    }

    fun appendFromMultipleUris(context: Context, uris: List<Uri>) = draft.appendFromMultipleUris(context, uris)


    private fun initPreGenerateState() = displayedGrid.initPreGenerateState()

    fun defaultIsColumnIncluded(headerKey: String?, headerType: String?): Boolean =
        draft.defaultIsColumnIncluded(headerKey, headerType)


    fun generateFilteredWithOldPrices(supplierName: String, categoryName: String, onResult: (Long) -> Unit) {
        val admission = localReadGate.snapshot()
        if (!localReadGate.isWriteCurrent(admission)) return
        // Back from a gated business route still shows the pristine raw draft.
        // A new Generate must use that draft, not the previous History/generated projection.
        currentHistoryEntryObserverJob?.cancel()
        currentHistoryEntryObserverJob = null
        suppressedHistoryEntryObserverReloads.clear()
        businessProjection.value = null
        // Prima di qualsiasi modifica, salviamo lo stato attuale della griglia.
        _preGenerateStateBackup = excelData.map { it.toList() }

        viewModelScope.launch { // Esegui operazioni pesanti su un thread in background
          localReadGate.runIfCurrent(admission) {
            val header = excelData.firstOrNull() ?: return@runIfCurrent
            val barcodeIdx = header.indexOf("barcode")

            val previousPricesMap = fetchPreviousPrices(barcodeIdx)
            if (!localReadGate.isCurrent(admission)) return@runIfCurrent
            val filteredData = buildFilteredDataWithOldPrices(barcodeIdx, previousPricesMap)
            val newEditableValues = createEditableValuesForFilteredData(filteredData)
            val newCompleteStates = createCompleteStatesForFilteredData(filteredData)

            val now = LocalDateTime.now()
            val newEntry = buildHistoryEntry(
                supplierName,
                categoryName,
                filteredData,
                newEditableValues,
                newCompleteStates,
                now
            )

            val newUid = repository.insertHistoryEntry(newEntry)
            if (!localReadGate.isCurrent(admission)) return@runIfCurrent

            applyGeneratedState(
                filteredData,
                newEditableValues,
                newCompleteStates,
                newEntry.id,
                newEntry.displayName,
                supplierName,
                categoryName,
                newUid,
                onResult
            )
          }
        }
    }

    fun getPreGenerateDataQualitySummary(): PreGenerateDataQualitySummary = displayedGrid.getPreGenerateDataQualitySummary()

    private suspend fun fetchPreviousPrices(barcodeIdx: Int): Map<String, Pair<Double?, Double?>> {
        val allBarcodesInFile = collectUniqueBarcodes(barcodeIdx)
        val nowForQuery = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return if (allBarcodesInFile.isNotEmpty()) {
            repository.getPreviousPricesForBarcodes(allBarcodesInFile, nowForQuery)
        } else {
            emptyMap()
        }
    }

    private fun collectUniqueBarcodes(barcodeIdx: Int): List<String> {
        if (barcodeIdx == -1) return emptyList()
        return excelData.drop(1)
            .mapNotNull { row -> row.getOrNull(barcodeIdx)?.takeIf { it.isNotBlank() } }
            .distinct()
    }

    private fun buildFilteredDataWithOldPrices(
        barcodeIdx: Int,
        previousPricesMap: Map<String, Pair<Double?, Double?>>
    ): List<List<String>> {
        return excelData.mapIndexed { idx, row ->
            if (idx == 0) {
                row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true } +
                        listOf("oldPurchasePrice", "oldRetailPrice", "realQuantity", "RetailPrice", "complete")
            } else {
                val original = row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true }
                val barcode = if (barcodeIdx != -1) row.getOrNull(barcodeIdx) else null
                val prices = barcode?.let { previousPricesMap[it] }
                val oldPurchase = formatClPriceInput(prices?.first)
                val oldRetail = formatClPriceInput(prices?.second)

                original + listOf(
                    oldPurchase,
                    oldRetail,
                    editableValues.getOrNull(idx)?.getOrNull(0)?.value.orEmpty(),
                    editableValues.getOrNull(idx)?.getOrNull(1)?.value.orEmpty(),
                    ""
                )
            }
        }.map { it.toList() }
    }

    private fun createEditableValuesForFilteredData(
        filteredData: List<List<String>>
    ): SnapshotStateList<MutableList<MutableState<String>>> {
        return mutableStateListOf<MutableList<MutableState<String>>>().apply {
            add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            filteredData.drop(1).forEach { row ->
                val q = row.getOrNull(row.size - 3) ?: ""
                val p = row.getOrNull(row.size - 2) ?: ""
                add(mutableListOf(mutableStateOf(q), mutableStateOf(p)))
            }
        }
    }

    private fun createCompleteStatesForFilteredData(filteredData: List<List<String>>): SnapshotStateList<Boolean> {
        return mutableStateListOf<Boolean>().apply {
            repeat(filteredData.size) { add(false) }
        }
    }

    private fun buildHistoryEntry(
        supplierName: String,
        categoryName: String,
        filteredData: List<List<String>>,
        newEditableValues: List<List<MutableState<String>>>,
        newCompleteStates: List<Boolean>,
        now: LocalDateTime
    ): HistoryEntry {
        val fileNameId = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + "_${supplierName.replace("\\W".toRegex(), "_")}.xlsx"
        val timestampForDb = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val (initialTotalItems, initialOrderTotal) = calculateInitialSummary(filteredData)

        return HistoryEntry(
            id = fileNameId,
            displayName = supplierName.trim().ifBlank { fileNameId },
            timestamp = timestampForDb,
            isManualEntry = false,
            data = filteredData,
            editable = newEditableValues.map { r -> r.map { it.value } },
            complete = newCompleteStates.toList(),
            supplier = supplierName,
            category = categoryName,
            totalItems = initialTotalItems,
            orderTotal = initialOrderTotal,
            paymentTotal = initialOrderTotal,
            missingItems = initialTotalItems,
            syncStatus = SyncStatus.NOT_ATTEMPTED,
            wasExported = false
        )
    }

    private suspend fun applyGeneratedState(
        filteredData: List<List<String>>,
        newEditableValues: SnapshotStateList<MutableList<MutableState<String>>>,
        newCompleteStates: SnapshotStateList<Boolean>,
        entryName: String,
        displayName: String,
        supplierName: String,
        categoryName: String,
        newUid: Long,
        onResult: (Long) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            draft.retirePendingFileRead()
            businessProjection.value = ExcelImportDraftState(viewModelScope)
            excelData.clear()
            excelData.addAll(filteredData)
            editableValues.clear()
            editableValues.addAll(newEditableValues)
            completeStates.clear()
            completeStates.addAll(newCompleteStates)
            selectedColumns.clear()
            filteredData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }

            generated.value = true
            currentEntryName.value = displayName
            currentEntryFileName.value = entryName
            currentSupplierName = supplierName
            currentCategoryName = categoryName
            currentEntryStatus.value = Triple(SyncStatus.NOT_ATTEMPTED, false, newUid)

            onResult(newUid)
        }
    }

    private fun launchHistoryAction(
        entryUid: Long,
        onMissing: (() -> Unit)? = null,
        action: suspend (HistoryEntry, LocalBusinessReadSnapshot) -> Unit
    ) {
        val admission = localReadGate.snapshot()
        if (!localReadGate.isCurrent(admission)) return
        viewModelScope.launch {
            localReadGate.runIfCurrent(admission) {
                val entry = repository.getHistoryEntryByUid(entryUid)
                if (!localReadGate.isCurrent(admission)) return@runIfCurrent
                if (entry == null) onMissing?.invoke() else action(entry, admission)
            }
        }
    }

    private suspend fun renameHistoryEntry(
        entry: HistoryEntry,
        admission: LocalBusinessReadSnapshot,
        newName: String,
        newSupplier: String? = null,
        newCategory: String? = null
    ) {
            try {
                val updatedSupplier = newSupplier?.takeIf { it.isNotBlank() } ?: entry.supplier
                val updatedCategory = newCategory?.takeIf { it.isNotBlank() } ?: entry.category
                val updated = entry.copy(
                    id = newName,
                    displayName = newName,
                    supplier = updatedSupplier,
                    category = updatedCategory
                )
                updateHistoryEntryWithoutObserverReload(updated, flow = "rename")
                if (!localReadGate.isCurrent(admission)) return
                if (updated.uid == currentEntryStatus.value.third) {
                    currentEntryName.value = updated.displayName
                    currentEntryFileName.value = updated.id
                    currentSupplierName = updatedSupplier
                    currentCategoryName = updatedCategory
                }
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.history_entry_renamed)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!localReadGate.isCurrent(admission)) return
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.error_history_entry_rename)
            }
    }

    fun renameHistoryEntry(
        entryUid: Long,
        newName: String,
        newSupplier: String? = null,
        newCategory: String? = null
    ) {
        launchHistoryAction(entryUid, onMissing = {
            historyActionMessage.value = getApplication<Application>().getString(R.string.error_history_entry_rename)
        }) { entry, admission -> renameHistoryEntry(entry, admission, newName, newSupplier, newCategory) }
    }

    private suspend fun deleteHistoryEntry(entry: HistoryEntry, admission: LocalBusinessReadSnapshot) {
        try {
            repository.deleteHistoryEntry(entry)
            if (!localReadGate.isCurrent(admission)) return
            historyActionMessage.value = getApplication<Application>().getString(R.string.history_entry_deleted)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!localReadGate.isCurrent(admission)) return
            historyActionMessage.value = getApplication<Application>().getString(R.string.error_history_entry_delete)
        }
    }

    fun deleteHistoryEntry(entryUid: Long) {
        launchHistoryAction(entryUid, onMissing = {
            historyActionMessage.value = getApplication<Application>().getString(R.string.error_history_entry_delete)
        }) { entry, admission -> deleteHistoryEntry(entry, admission) }
    }

    fun updateHistoryEntry(entryUid: Long) {
        launchHistoryAction(entryUid) { entryToUpdate, _ ->
                val (finalPaymentTotal, finalMissingItems) = calculateFinalSummary(excelData, editableValues, completeStates)
                val updatedEntry = entryToUpdate.copy(
                    data = excelData.map { it.toList() },
                    editable = editableValues.map { row -> row.map { it.value } },
                    complete = completeStates.toList(),
                    paymentTotal = finalPaymentTotal,
                    missingItems = finalMissingItems
                )
                updateHistoryEntryWithoutObserverReload(updatedEntry, flow = "generated_update")
        }
    }

    suspend fun saveCurrentStateToHistory(entryUid: Long): Boolean {
        val admission = localReadGate.snapshot()
        return localReadGate.runIfCurrent(admission) {
          withContext(Dispatchers.IO) {
        val entryToUpdate = repository.getHistoryEntryByUid(entryUid) ?: return@withContext false
        if (!localReadGate.isCurrent(admission)) return@withContext false
        val (finalPaymentTotal, finalMissingItems) = calculateFinalSummary(excelData, editableValues, completeStates)
        val updatedEntry = entryToUpdate.copy(
            data = excelData.map { it.toList() },
            editable = editableValues.map { row -> row.map { it.value } },
            complete = completeStates.toList(),
            paymentTotal = finalPaymentTotal,
            missingItems = finalMissingItems
        )
        updateHistoryEntryWithoutObserverReload(updatedEntry, flow = "generated_save")
        true
          }
        } ?: false
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        // Se lo stato originale non è ancora stato impostato, o se l'ID è diverso,
        // impostalo. Altrimenti, non fare nulla per non sovrascrivere il backup
        // quando chiamiamo la funzione per un ripristino.
        if (_originalHistoryEntryState == null || _originalHistoryEntryState?.uid != entry.uid) {
            _originalHistoryEntryState = entry.copy(
                data = entry.data.map { it.toList() },
                editable = entry.editable.map { it.toMutableList() },
                complete = entry.complete.toList()
            )
        }
        populateStateFromEntry(entry)
    }

    fun resetState() {
        clearBusinessPresentation()
        draft.resetState()
    }

    private fun clearBusinessPresentation() {
        currentHistoryEntryObserverJob?.cancel()
        currentHistoryEntryObserverJob = null
        suppressedHistoryEntryObserverReloads.clear()
        _originalHistoryEntryState = null
        businessProjection.value = null
        generated.value = false
        currentEntryName.value = ""
        currentEntryFileName.value = ""
        currentSupplierName = ""
        currentCategoryName = ""
        currentEntryStatus.value = Triple(SyncStatus.NOT_ATTEMPTED, false, 0L)
        generatedRouteIsNew = false
        generatedRouteIsManualEntry = false
        importOriginForGeneratedSession = ImportNavOrigin.HOME
    }

    fun setHeaderType(colIdx: Int, type: String?) = displayedGrid.setHeaderType(colIdx, type)

    fun restoreOriginalHeader(colIdx: Int) = displayedGrid.restoreOriginalHeader(colIdx)

    fun markCurrentEntryAsExported(entryUid: Long) {
        launchHistoryAction(entryUid) { entry, admission ->
                if (!entry.wasExported) {
                    val updatedEntry = entry.copy(wasExported = true)
                    updateHistoryEntryWithoutObserverReload(updatedEntry, flow = "mark_exported")
                    if (!localReadGate.isCurrent(admission)) return@launchHistoryAction
                    if (entry.uid == currentEntryStatus.value.third) {
                        currentEntryStatus.value = currentEntryStatus.value.copy(second = true)
                    }
                }
        }
    }

    fun markCurrentEntryAsSyncedSuccessfully(entryUid: Long) {
        updateSyncStatus(entryUid, SyncStatus.SYNCED_SUCCESSFULLY)
    }

    fun markCurrentEntryAsSyncedWithErrors(entryUid: Long) {
        updateSyncStatus(entryUid, SyncStatus.ATTEMPTED_WITH_ERRORS)
    }

    private fun updateSyncStatus(entryUid: Long, newStatus: SyncStatus) {
        launchHistoryAction(entryUid) { entry, admission ->
                if (entry.syncStatus != newStatus) {
                    val updatedEntry = entry.copy(syncStatus = newStatus)
                    updateHistoryEntryWithoutObserverReload(updatedEntry, flow = "sync_status")
                    if (!localReadGate.isCurrent(admission)) return@launchHistoryAction
                    if (entry.uid == currentEntryStatus.value.third) {
                        currentEntryStatus.value = currentEntryStatus.value.copy(first = newStatus)
                    }
                }
        }
    }

    /**
     * Read-only derived value: initial purchase-order total for the current [excelData].
     * Exposed for display purposes only (GeneratedScreen progress card).
     * Delegates to [calculateInitialSummary] — no logic duplicated in the UI.
     */
    val initialOrderTotal: Double
        get() = if (excelData.size > 1) calculateInitialSummary(excelData).second else 0.0

    val initialTotalQuantity: Double?
        get() = calculateTotalQuantityFromRows(excelData)

    private fun calculateInitialSummary(
        data: List<List<String>>
    ): Pair<Int, Double> {
        var totalItems = 0
        var orderTotal = 0.0

        val header = data.firstOrNull() ?: return Pair(0, 0.0)
        val purchasePriceIndex = header.indexOf("purchasePrice")
        val quantityIndex = header.indexOf("quantity")

        // Itera su tutte le righe di dati, saltando l'intestazione
        data.drop(1).forEach { rowData ->
            val quantity = parseUserQuantityInput(rowData.getOrNull(quantityIndex)) ?: 0.0

            if (quantity > 0) {
                totalItems++
                val purchasePrice = parseUserPriceInput(rowData.getOrNull(purchasePriceIndex)) ?: 0.0
                orderTotal += purchasePrice * quantity
            }
        }
        return Pair(totalItems, orderTotal)
    }

    /**
     * Calcola il totale finale di pagamento e il numero di prodotti mancanti.
     * Si basa solo sulle righe segnate come complete.
     */
    private fun calculateFinalSummary(
        data: List<List<String>>,
        editable: List<List<MutableState<String>>>,
        complete: List<Boolean>
    ): Pair<Double, Int> {
        var paymentTotal = 0.0
        var completedItems = 0

        val header = data.firstOrNull() ?: return Pair(0.0, 0)
        val purchasePriceIndex = header.indexOf("purchasePrice")
        val originalQuantityIndex = header.indexOf("quantity")
        val discountedPriceIndex = header.indexOf("discountedPrice")
        val discountIndex = header.indexOf("discount")

        data.drop(1).forEachIndexed { index, rowData ->
            val modelIndex = index + 1 // L'indice per le liste di stato (editable, complete)

            if (complete.getOrNull(modelIndex) == true) {
                completedItems++

                val realQuantityStr = editable.getOrNull(modelIndex)?.getOrNull(0)?.value ?: ""
                val originalQuantityStr = if (originalQuantityIndex != -1) rowData.getOrNull(originalQuantityIndex) ?: "0" else "0"
                val quantityToUseStr = realQuantityStr.ifBlank { originalQuantityStr }
                val quantity = parseUserQuantityInput(quantityToUseStr) ?: 0.0

                if (quantity > 0) {
                    val purchasePrice = parseUserPriceInput(rowData.getOrNull(purchasePriceIndex)) ?: 0.0

                    val discountedPrice = parseUserPriceInput(rowData.getOrNull(discountedPriceIndex))
                    val discountPercent = parseUserNumericInput(rowData.getOrNull(discountIndex))

                    val finalPaymentPrice = when {
                        discountedPrice != null -> discountedPrice
                        discountPercent != null -> purchasePrice * (1 - (discountPercent / 100))
                        else -> purchasePrice
                    }

                    paymentTotal += finalPaymentPrice * quantity
                }
            }
        }

        val totalDataRows = data.size - 1
        val missingItems = totalDataRows - completedItems

        return Pair(paymentTotal, missingItems)
    }

    // --- 1. AGGIUNGI UNA FUNZIONE PER CREARE L'ENTRY MANUALE ---
    fun createManualEntry(context: Context, onResult: (Long) -> Unit) {
        val admission = localReadGate.snapshot()
        if (!localReadGate.isWriteCurrent(admission)) return
        viewModelScope.launch {
            // Definisci l'intestazione standard
            val manualHeader = listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category")
            val dataGrid = listOf(manualHeader)

            val now = LocalDateTime.now()
            val suffixRaw = context.getString(R.string.manual_add_suffix)
            val suffixFileSafe = suffixRaw.replace("[^\\p{L}\\p{N}_-]".toRegex(), "_")
            val fileNameId = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "_${suffixFileSafe}.xlsx"
            val timestampForDb = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            val newEntry = HistoryEntry(
                id = fileNameId,
                displayName = context.getString(R.string.supplier_manual),
                timestamp = timestampForDb,
                isManualEntry = true,
                data = dataGrid,
                editable = listOf(listOf("","")), // Aggiungi uno stato per l'header
                complete = listOf(false),          // Aggiungi uno stato per l'header
                supplier = context.getString(R.string.supplier_manual),
                category = "",
                totalItems = 0,
                orderTotal = 0.0,
                paymentTotal = 0.0,
                missingItems = 0,
                syncStatus = SyncStatus.NOT_ATTEMPTED,
                wasExported = false
            )

            val newUid = localReadGate.runIfCurrent(admission) {
                repository.insertHistoryEntry(newEntry)
            } ?: return@launch
            if (newUid <= 0L || !localReadGate.isWriteCurrent(admission)) return@launch
            clearBusinessPresentation()
            populateStateFromEntry(newEntry.copy(uid = newUid))
            onResult(newUid)
        }
    }

    fun addManualRow(entryUid: Long, rowData: List<String>, categoryName: String) {
        viewModelScope.launch {
            excelData.add(rowData)
            editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            completeStates.add(false)

            // Salva lo stato completo nel DB
            if (saveCurrentStateToHistory(entryUid)) {
                lastUsedCategory.value = categoryName
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.manual_row_added)
            }
        }
    }

    fun updateManualRow(entryUid: Long, index: Int, rowData: List<String>, categoryName: String) {
        viewModelScope.launch {
            val dataIndex = index + 1
            if (dataIndex in excelData.indices) {
                excelData[dataIndex] = rowData
                // Non serve modificare editable/complete perché non sono usati qui
                if (saveCurrentStateToHistory(entryUid)) {
                    lastUsedCategory.value = categoryName
                    historyActionMessage.value = getApplication<Application>()
                        .getString(R.string.manual_row_updated)
                }
            }
        }
    }

    fun deleteManualRow(entryUid: Long, index: Int) {
        viewModelScope.launch {
            val dataIndex = index + 1
            if (dataIndex in excelData.indices) {
                excelData.removeAt(dataIndex)

                if (dataIndex in editableValues.indices) {
                    editableValues.removeAt(dataIndex)
                }
                if (dataIndex in completeStates.indices) {
                    completeStates.removeAt(dataIndex)
                }

                if (saveCurrentStateToHistory(entryUid)) {
                    historyActionMessage.value = getApplication<Application>()
                        .getString(R.string.manual_row_deleted)
                }
            }
        }
    }

    companion object {
        internal fun sharedDraftFactory(app: Application, repository: InventoryRepository, draft: ExcelImportDraftState): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ExcelViewModel::class.java)) return ExcelViewModel(app, repository, draft) as T
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }

        private const val GENERATED_DB_TAG = "GeneratedDbFlow"

        fun factory(app: Application, repository: InventoryRepository): androidx.lifecycle.ViewModelProvider.Factory {
            return object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ExcelViewModel::class.java)) {
                        return ExcelViewModel(app, repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}

private fun saveExcelFileInternal(
    context: Context,
    uri: Uri,
    data: List<List<String>>,
    editable: List<List<MutableState<String>>>,
    complete: List<Boolean>,
    supplier: String,
    category: String,
    onProgress: (Int) -> Unit = {}
) {
    val wb = XSSFWorkbook()
    val sheet = wb.createSheet(context.getString(R.string.sheet_name_export))

    onProgress(10)

    val numericTypes = setOf(
        "quantity", "purchasePrice", "retailPrice", "totalPrice", "rowNumber",
        "discount", "discountedPrice", "realQuantity",
        "oldPurchasePrice", "oldRetailPrice"
    )

    val styleComplete = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_GREEN.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
    }
    val styleFilled = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
    }

    val originalHeader = data.firstOrNull() ?: run {
        wb.close()
        return
    }
    val headerWithIndices = originalHeader.mapIndexedNotNull { index, header ->
        if (header != "complete") index to header else null
    }

    val filteredHeaderKeys = headerWithIndices.map { it.second }
    val headerRow = sheet.createRow(0)

    // Scrive le intestazioni esistenti e filtrate
    filteredHeaderKeys.forEachIndexed { newIndex, headerKey ->
        headerRow.createCell(newIndex).setCellValue(getLocalizedHeader(context, headerKey))
    }

    val originalHeaderKeysSet = originalHeader.toSet()
    var extraCellIndex = filteredHeaderKeys.size

    if ("supplier" !in originalHeaderKeysSet) {
        headerRow.createCell(extraCellIndex).setCellValue(getLocalizedHeader(context, "supplier"))
        extraCellIndex++
    }

    if ("category" !in originalHeaderKeysSet) {
        headerRow.createCell(extraCellIndex).setCellValue(getLocalizedHeader(context, "category"))
    }

    data.drop(1).forEachIndexed { rowIndex, rowData ->
        val excelRow = sheet.createRow(rowIndex + 1)
        var hasEditableValues = false
        if (editable.getOrNull(rowIndex + 1)?.all { it.value.isNotEmpty() } == true) {
            hasEditableValues = true
        }

        // Scrive i dati delle celle esistenti
        headerWithIndices.forEachIndexed { newIndex, (originalIndex, headerKey) ->
            val cell = excelRow.createCell(newIndex)
            val cellValue: String = when (headerKey) {
                "realQuantity" -> editable.getOrNull(rowIndex + 1)?.getOrNull(0)?.value ?: ""
                "RetailPrice" -> editable.getOrNull(rowIndex + 1)?.getOrNull(1)?.value ?: ""
                else -> rowData.getOrNull(originalIndex) ?: ""
            }
            if (numericTypes.contains(headerKey)) {
                val numericValue = cellValue.replace(",", ".").toDoubleOrNull()
                if (numericValue != null) {
                    cell.setCellValue(numericValue)
                } else {
                    cell.setCellValue(cellValue)
                }
            } else {
                cell.setCellValue(cellValue)
            }
            if (complete.getOrNull(rowIndex + 1) == true) {
                cell.cellStyle = styleComplete
            } else if (hasEditableValues) {
                cell.cellStyle = styleFilled
            }
        }

        extraCellIndex = filteredHeaderKeys.size

        if ("supplier" !in originalHeaderKeysSet) {
            val supplierCell = excelRow.createCell(extraCellIndex)
            supplierCell.setCellValue(supplier)
            if (complete.getOrNull(rowIndex + 1) == true) {
                supplierCell.cellStyle = styleComplete
            } else if (hasEditableValues) {
                supplierCell.cellStyle = styleFilled
            }
            extraCellIndex++
        }

        if ("category" !in originalHeaderKeysSet) {
            val categoryCell = excelRow.createCell(extraCellIndex)
            categoryCell.setCellValue(category)
            if (complete.getOrNull(rowIndex + 1) == true) {
                categoryCell.cellStyle = styleComplete
            } else if (hasEditableValues) {
                categoryCell.cellStyle = styleFilled
            }
        }
    }

    val totalRows = (data.size - 1).coerceAtLeast(1)
    data.drop(1).forEachIndexed { i, _ ->
        // ... scrittura delle celle come fai già ...
        if (i % 50 == 0) {                   // throttling per non spammare
            onProgress(10 + ((i + 1) * 80 / totalRows))
        }
    }

    onProgress(95)
    sheet.defaultColumnWidth = 15
    try {
        context.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
            ?: throw IOException("Unable to open output stream for $uri")
    } finally {
        wb.close()
    }
    onProgress(100)
}

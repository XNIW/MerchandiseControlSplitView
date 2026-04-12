package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.HistoryEntryListItem
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.util.formatClPriceInput
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.util.parseUserNumericInput
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcelDetailed
import com.example.merchandisecontrolsplitview.util.classifyExcelFileUserError
import com.example.merchandisecontrolsplitview.util.resolveExcelFileErrorMessage
import com.example.merchandisecontrolsplitview.util.ExcelFileUserError
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private const val PRE_GENERATE_DUPLICATE_SAMPLE_LIMIT = 2

/**
 * ViewModel per gestione griglia Excel e cronologia persistente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExcelViewModel(
    application: Application,
    private val repository: InventoryRepository
) : AndroidViewModel(application) {
    private val essentialColumns = setOf("barcode", "productName", "purchasePrice")
    // Stato griglia
    val excelData = mutableStateListOf<List<String>>()
    val originalHeaders = mutableStateListOf<String>()
    val selectedColumns = mutableStateListOf<Boolean>()
    val editableValues = mutableStateListOf<MutableList<MutableState<String>>>()
    val completeStates = mutableStateListOf<Boolean>()
    val errorRowIndexes = mutableStateOf<Set<Int>>(emptySet())

    // Flag UI
    val generated = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    val historyActionMessage = mutableStateOf<String?>(null)
    val currentEntryName = mutableStateOf("")

    val loadingProgress = mutableStateOf<Int?>(null)

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

    private suspend fun postProgress(p: Int?) = withContext(Dispatchers.Main) {
        loadingProgress.value = p?.coerceIn(0, 100)
    }

    fun consumeHistoryActionMessage() {
        historyActionMessage.value = null
    }

    private fun fileLoadErrorMessage(
        context: Context,
        throwable: Throwable,
        genericResId: Int
    ): String {
        return resolveExcelFileErrorMessage(context, throwable, genericResId)
    }

    // Removed repository instantiation as it is now injected via constructor


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
    val historyListEntries: StateFlow<List<HistoryEntryListItem>> = _dateFilter
        .flatMapLatest { filter ->
            repository.getFilteredHistoryListFlow(filter)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    // Lista per la visualizzazione nella HistoryScreen: filtrata anche per supplier e category.
    val historyDisplayEntries: StateFlow<List<HistoryEntryListItem>> = combine(
        historyListEntries, _supplierFilter, _categoryFilter
    ) { entries, supplier, category ->
        entries.filter { entry ->
            (supplier.isBlank() || entry.supplier.equals(supplier, ignoreCase = true)) &&
                (category.isBlank() || entry.category.equals(category, ignoreCase = true))
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

    val hasHistoryEntries: StateFlow<Boolean> = repository.hasHistoryEntriesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = false
        )

    // Flusso completo usato dai percorsi che devono leggere o aggiornare tutta l'entry
    // visibile all'utente.
    val historyEntries: StateFlow<List<HistoryEntry>> = _dateFilter
        .flatMapLatest { filter ->
            repository.getFilteredHistoryFlow(filter)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    val currentEntryStatus = mutableStateOf(Triple(SyncStatus.NOT_ATTEMPTED, false, 0L))
    val headerTypes = mutableStateListOf<String>()

    // --- BLOCCO DATABASE ---

    private var currentSupplierName: String = ""
    val supplierName: String
        get() = currentSupplierName

    private var currentCategoryName: String = ""
    val categoryName: String
        get() = currentCategoryName

    private var _originalHistoryEntryState: HistoryEntry? = null
    private var _preGenerateStateBackup: List<List<String>>? = null
    private var _preGenerateOriginalHeadersBackup: List<String>? = null
    private var _preGenerateHeaderTypesBackup: List<String>? = null

    val lastUsedCategory = mutableStateOf<String?>(null)

    // Funzione per verificare se una colonna è essenziale
    fun isColumnEssential(colIdx: Int): Boolean {
        val headerKey = excelData.getOrNull(0)?.getOrNull(colIdx)
        return headerKey in essentialColumns
    }

    // Funzione per gestire la selezione di una colonna, bloccando quelle essenziali
    fun toggleColumnSelection(colIdx: Int) {
        if (isColumnEssential(colIdx)) {
            return
        }
        if (colIdx in selectedColumns.indices) {
            selectedColumns[colIdx] = !selectedColumns[colIdx]
        }
    }

    // Funzione per gestire il "Seleziona/Deseleziona Tutto" in modo sicuro
    fun toggleSelectAll() {
        val anyUnselected = selectedColumns.indices.any { idx ->
            !selectedColumns[idx] && !isColumnEssential(idx)
        }
        selectedColumns.indices.forEach { idx ->
            if (isColumnEssential(idx)) {
                selectedColumns[idx] = true
            } else {
                selectedColumns[idx] = anyUnselected
            }
        }
    }

    /**
     * Funzione helper PRIVATA che popola lo stato live del ViewModel.
     * Non tocca i backup.
     */
    private fun populateStateFromEntry(entry: HistoryEntry) {
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
        currentEntryName.value = entry.id
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
        _preGenerateStateBackup?.let { backupData ->
            resetState()
            excelData.addAll(backupData)
            originalHeaders.addAll(
                _preGenerateOriginalHeadersBackup
                    ?: backupData.firstOrNull()?.toList().orEmpty()
            )
            headerTypes.addAll(
                _preGenerateHeaderTypesBackup
                    ?: List(backupData.firstOrNull()?.size ?: 0) { "unknown" }
            )
            initPreGenerateState()
            generated.value = false
        }
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

    /**
     * Aggiorna solo il filtro data, mantenendo supplier e category correnti.
     * Mantenuto per compatibilità con eventuali call site non migrati.
     */
    fun setDateFilter(filter: DateFilter) {
        _dateFilter.value = filter
    }

    fun loadHistoryEntry(entryUid: Long, onLoaded: (() -> Unit)? = null) {
        viewModelScope.launch {
            repository.getHistoryEntryByUid(entryUid)?.let { entry ->
                loadHistoryEntry(entry)
                onLoaded?.invoke()
            }
        }
    }

    fun loadFromMultipleUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null
            postProgress(5) // sta partendo

            try {
                val firstAnalysis = try {
                    withContext(Dispatchers.IO) {
                        readAndAnalyzeExcelDetailed(context, uris.first())
                    }
                } catch (e: Exception) {
                    val isFirstFileEmpty =
                        classifyExcelFileUserError(context, e) == ExcelFileUserError.EmptyOrInvalid
                    if (isFirstFileEmpty) {
                        throw IllegalArgumentException(
                            context.getString(R.string.error_first_file_empty_or_invalid)
                        )
                    }
                    throw e
                }
                val goldenHeader = firstAnalysis.header
                val firstDataRows = firstAnalysis.dataRows
                val headerSource = firstAnalysis.headerSource
                postProgress(15)

                val allValidRows = mutableListOf<List<String>>()
                allValidRows.addAll(firstDataRows)

                if (uris.size > 1) {
                    val remaining = uris.size - 1
                    uris.drop(1).forEachIndexed { idx, uri ->
                        val (newHeader, newDataRows, _) = withContext(Dispatchers.IO) {
                            readAndAnalyzeExcel(context, uri)
                        }
                        if (newHeader != goldenHeader) {
                            throw IllegalArgumentException(context.getString(R.string.error_different_columns))
                        }
                        allValidRows.addAll(newDataRows)

                        // 15 → 85 distribuiti sui file rimanenti
                        val pct = 15 + ((idx + 1) * 70 / remaining)
                        postProgress(pct)
                    }
                }

                postProgress(95) // merge dati / setup stato
                excelData.add(goldenHeader)
                excelData.addAll(allValidRows)
                originalHeaders.addAll(firstAnalysis.originalHeaders)
                headerTypes.addAll(headerSource)
                initPreGenerateState()
            } catch (e: Exception) {
                loadError.value = fileLoadErrorMessage(
                    context,
                    e,
                    R.string.error_unknown_file_analysis
                )
            } finally {
                isLoading.value = false
                postProgress(null) // finito
            }
        }
    }

    fun appendFromMultipleUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null
            postProgress(5)

            try {
                val originalHeader = excelData.firstOrNull()
                if (originalHeader.isNullOrEmpty()) {
                    loadError.value = context.getString(R.string.error_main_file_needed)
                    return@launch
                }
                val allNewDataRows = mutableListOf<List<String>>()

                withContext(Dispatchers.IO) {
                    val total = uris.size.coerceAtLeast(1)
                    for ((i, uri) in uris.withIndex()) {
                        val (newHeader, newDataRows, _) = readAndAnalyzeExcel(
                            context = context,
                            uri = uri,
                            allowEmptyTabularResult = true
                        )
                        if (newHeader.isEmpty() && newDataRows.isEmpty()) {
                            val pct = 10 + ((i + 1) * 80 / total)
                            postProgress(pct)
                            continue
                        }
                        if (originalHeader != newHeader) {
                            throw IllegalArgumentException(context.getString(R.string.error_incompatible_file_structure))
                        }
                        allNewDataRows.addAll(newDataRows)

                        val pct = 10 + ((i + 1) * 80 / total) // 10 → 90
                        postProgress(pct)
                    }
                }

                if (allNewDataRows.isEmpty()) {
                    loadError.value = context.getString(R.string.error_append_no_data_rows)
                    return@launch
                }

                postProgress(95)
                appendRowsToCurrentGrid(allNewDataRows)
            } catch (e: Exception) {
                loadError.value = fileLoadErrorMessage(
                    context,
                    e,
                    R.string.error_adding_files
                )
            } finally {
                isLoading.value = false
                postProgress(null)
            }
        }
    }

    private fun appendRowsToCurrentGrid(newRows: List<List<String>>) {
        if (newRows.isEmpty()) return
        excelData.addAll(newRows)
        repeat(newRows.size) {
            editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            completeStates.add(false)
        }
    }

    private fun initPreGenerateState() {
        selectedColumns.clear()
        excelData.firstOrNull()?.size?.let { cols ->
            repeat(cols) { _ ->
                // MODIFICA: Semplifica l'espressione. L'obiettivo è che tutte le
                // colonne partano come selezionate. La logica di blocco è gestita
                // altrove (in toggleColumnSelection).
                selectedColumns.add(true)
            }
        }
        editableValues.clear()
        repeat(excelData.size) { editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf(""))) }
        completeStates.clear()
        repeat(excelData.size) { completeStates.add(false) }
    }

    fun generateFilteredWithOldPrices(supplierName: String, categoryName: String, onResult: (Long) -> Unit) {
        // Prima di qualsiasi modifica, salviamo lo stato attuale della griglia.
        _preGenerateStateBackup = excelData.map { it.toList() }
        _preGenerateOriginalHeadersBackup = originalHeaders.toList()
        _preGenerateHeaderTypesBackup = headerTypes.toList()

        viewModelScope.launch { // Esegui operazioni pesanti su un thread in background
            val header = excelData.firstOrNull() ?: return@launch
            val barcodeIdx = header.indexOf("barcode")

            val previousPricesMap = fetchPreviousPrices(barcodeIdx)
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

            applyGeneratedState(
                filteredData,
                newEditableValues,
                newCompleteStates,
                newEntry.id,
                supplierName,
                categoryName,
                newUid,
                onResult
            )
        }
    }

    fun getPreGenerateDataQualitySummary(): PreGenerateDataQualitySummary {
        val header = excelData.firstOrNull() ?: return PreGenerateDataQualitySummary()
        val barcodeIndex = header.indexOf("barcode")
        val purchasePriceIndex = header.indexOf("purchasePrice")

        if (barcodeIndex == -1) {
            return PreGenerateDataQualitySummary()
        }

        val barcodeOccurrences = LinkedHashMap<String, Int>()
        var missingPurchasePriceCount = 0

        excelData.drop(1).forEach { row ->
            val barcode = row.getOrNull(barcodeIndex)?.trim().orEmpty()
            if (barcode.isBlank()) {
                return@forEach
            }

            barcodeOccurrences[barcode] = (barcodeOccurrences[barcode] ?: 0) + 1

            if (purchasePriceIndex != -1) {
                val purchasePrice = row.getOrNull(purchasePriceIndex)?.trim().orEmpty()
                if (purchasePrice.isBlank()) {
                    missingPurchasePriceCount++
                }
            }
        }

        val duplicateBarcodes = barcodeOccurrences
            .asSequence()
            .filter { (_, occurrences) -> occurrences > 1 }
            .map { (barcode, _) -> barcode }
            .toList()

        return PreGenerateDataQualitySummary(
            duplicateBarcodeCount = duplicateBarcodes.size,
            duplicateBarcodeSamples = duplicateBarcodes.take(PRE_GENERATE_DUPLICATE_SAMPLE_LIMIT),
            missingPurchasePriceCount = missingPurchasePriceCount
        )
    }

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
        supplierName: String,
        categoryName: String,
        newUid: Long,
        onResult: (Long) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            excelData.clear()
            excelData.addAll(filteredData)
            editableValues.clear()
            editableValues.addAll(newEditableValues)
            completeStates.clear()
            completeStates.addAll(newCompleteStates)
            selectedColumns.clear()
            filteredData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }

            generated.value = true
            currentEntryName.value = entryName
            currentSupplierName = supplierName
            currentCategoryName = categoryName
            currentEntryStatus.value = Triple(SyncStatus.NOT_ATTEMPTED, false, newUid)

            onResult(newUid)
        }
    }

    fun renameHistoryEntry(
        entry: HistoryEntry,
        newName: String,
        newSupplier: String? = null,
        newCategory: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updatedSupplier = newSupplier?.takeIf { it.isNotBlank() } ?: entry.supplier
                val updatedCategory = newCategory?.takeIf { it.isNotBlank() } ?: entry.category
                val updated = entry.copy(
                    id = newName,
                    supplier = updatedSupplier,
                    category = updatedCategory
                )
                repository.updateHistoryEntry(updated)
                if (updated.uid == currentEntryStatus.value.third) {
                    currentEntryName.value = updated.id
                    currentSupplierName = updatedSupplier
                    currentCategoryName = updatedCategory
                }
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.history_entry_renamed)
            } catch (_: Exception) {
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.error_history_entry_rename)
            }
        }
    }

    fun renameHistoryEntry(
        entryUid: Long,
        newName: String,
        newSupplier: String? = null,
        newCategory: String? = null
    ) {
        viewModelScope.launch {
            repository.getHistoryEntryByUid(entryUid)?.let { entry ->
                renameHistoryEntry(entry, newName, newSupplier, newCategory)
            } ?: run {
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.error_history_entry_rename)
            }
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            try {
                repository.deleteHistoryEntry(entry)
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.history_entry_deleted)
            } catch (_: Exception) {
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.error_history_entry_delete)
            }
        }
    }

    fun deleteHistoryEntry(entryUid: Long) {
        viewModelScope.launch {
            repository.getHistoryEntryByUid(entryUid)?.let { entry ->
                deleteHistoryEntry(entry)
            } ?: run {
                historyActionMessage.value = getApplication<Application>()
                    .getString(R.string.error_history_entry_delete)
            }
        }
    }

    fun updateHistoryEntry(entryUid: Long) {
        viewModelScope.launch { // Rimuoviamo Dispatchers.IO
            repository.getHistoryEntryByUid(entryUid)?.let { entryToUpdate ->
                val (finalPaymentTotal, finalMissingItems) = calculateFinalSummary(excelData, editableValues, completeStates)
                val updatedEntry = entryToUpdate.copy(
                    data = excelData.map { it.toList() },
                    editable = editableValues.map { row -> row.map { it.value } },
                    complete = completeStates.toList(),
                    paymentTotal = finalPaymentTotal,
                    missingItems = finalMissingItems
                )
                repository.updateHistoryEntry(updatedEntry)
            }
        }
    }

    // 3. AGGIUNGI: Nuova funzione `suspend` per il salvataggio garantito
    suspend fun saveCurrentStateToHistory(entryUid: Long): Boolean = withContext(Dispatchers.IO) {
        val entryToUpdate = repository.getHistoryEntryByUid(entryUid) ?: return@withContext false
        val (finalPaymentTotal, finalMissingItems) = calculateFinalSummary(excelData, editableValues, completeStates)
        val updatedEntry = entryToUpdate.copy(
            data = excelData.map { it.toList() },
            editable = editableValues.map { row -> row.map { it.value } },
            complete = completeStates.toList(),
            paymentTotal = finalPaymentTotal,
            missingItems = finalMissingItems
        )
        repository.updateHistoryEntry(updatedEntry)
        true
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
        excelData.clear()
        originalHeaders.clear()
        selectedColumns.clear()
        editableValues.clear()
        completeStates.clear()
        errorRowIndexes.value = emptySet()
        generated.value = false
        isLoading.value = false
        loadError.value = null
        currentEntryName.value = ""
        currentSupplierName = ""
        currentCategoryName = ""
        headerTypes.clear()
        currentEntryStatus.value = Triple(SyncStatus.NOT_ATTEMPTED, false, 0L)
    }

    fun setHeaderType(colIdx: Int, type: String?) {
        if (colIdx in headerTypes.indices) {
            headerTypes[colIdx] = type ?: "unknown"
        }
        val headerRow = excelData.firstOrNull()?.toMutableList()
        if (headerRow != null && colIdx in headerRow.indices) {
            if (type != null) {
                headerRow[colIdx] = type
                excelData[0] = headerRow
            }
        }
    }

    fun restoreOriginalHeader(colIdx: Int) {
        val originalHeader = originalHeaders.getOrNull(colIdx)?.takeIf { it.isNotBlank() } ?: return
        if (colIdx in headerTypes.indices) {
            headerTypes[colIdx] = "unknown"
        }
        val headerRow = excelData.firstOrNull()?.toMutableList() ?: return
        if (colIdx in headerRow.indices) {
            headerRow[colIdx] = originalHeader
            excelData[0] = headerRow
        }
    }

    fun markCurrentEntryAsExported(entryUid: Long) {
        viewModelScope.launch {
            repository.getHistoryEntryByUid(entryUid)?.let { entry ->
                if (!entry.wasExported) {
                    val updatedEntry = entry.copy(wasExported = true)
                    repository.updateHistoryEntry(updatedEntry)
                    if (entry.uid == currentEntryStatus.value.third) {
                        currentEntryStatus.value = currentEntryStatus.value.copy(second = true)
                    }
                }
            }
        }
    }

    fun markCurrentEntryAsSyncedSuccessfully(entryUid: Long) { // <-- Cambia firma
        updateSyncStatus(entryUid, SyncStatus.SYNCED_SUCCESSFULLY)
    }

    fun markCurrentEntryAsSyncedWithErrors(entryUid: Long) { // <-- Cambia firma
        updateSyncStatus(entryUid, SyncStatus.ATTEMPTED_WITH_ERRORS)
    }

    private fun updateSyncStatus(entryUid: Long, newStatus: SyncStatus) {
        viewModelScope.launch {
            repository.getHistoryEntryByUid(entryUid)?.let { entry ->
                if (entry.syncStatus != newStatus) {
                    val updatedEntry = entry.copy(syncStatus = newStatus)
                    repository.updateHistoryEntry(updatedEntry)
                    if (entry.uid == currentEntryStatus.value.third) {
                        currentEntryStatus.value = currentEntryStatus.value.copy(first = newStatus)
                    }
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
     * 💡 NUOVO: Calcola i dati FINALI e VARIABILI.
     * Si basa solo sulle righe segnate come "complete".
     * Restituisce: (Totale Pagamento Effettivo, Numero di Prodotti Mancanti)
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
        // Aggiungiamo indici per i prezzi scontati se presenti
        val discountedPriceIndex = header.indexOf("discountedPrice")
        val discountIndex = header.indexOf("discount")

        data.drop(1).forEachIndexed { index, rowData ->
            val modelIndex = index + 1 // L'indice per le liste di stato (editable, complete)

            // Calcola solo se la riga è segnata come "completa"
            if (complete.getOrNull(modelIndex) == true) {
                completedItems++

                // Usa la quantità contata dall'utente, altrimenti quella originale
                val realQuantityStr = editable.getOrNull(modelIndex)?.getOrNull(0)?.value ?: ""
                val originalQuantityStr = if (originalQuantityIndex != -1) rowData.getOrNull(originalQuantityIndex) ?: "0" else "0"
                val quantityToUseStr = realQuantityStr.ifBlank { originalQuantityStr }
                val quantity = parseUserQuantityInput(quantityToUseStr) ?: 0.0

                if (quantity > 0) {
                    val purchasePrice = parseUserPriceInput(rowData.getOrNull(purchasePriceIndex)) ?: 0.0

                    // Logica per calcolare il prezzo finale di pagamento
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
    fun createManualEntry(context: Context, onResult: (Long) -> Unit){
        viewModelScope.launch { // Non serve Dispatchers.IO per questa logica
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

            // Inserisci e ottieni l'UID sul thread IO
            val newUid = repository.insertHistoryEntry(newEntry)

            // Torna al thread principale per aggiornare lo stato e navigare
            withContext(Dispatchers.Main) {
                // 👇 CARICA LA NUOVA ENTRY NELLO STATO DEL VIEWMODEL
                populateStateFromEntry(newEntry.copy(uid = newUid))
                onResult(newUid)
            }
        }
    }

    fun addManualRow(entryUid: Long, rowData: List<String>, categoryName: String) {
        viewModelScope.launch {
            excelData.add(rowData)

            // CORREZIONE: Le entry manuali non usano la stessa logica di
            // editableValues e completeStates delle entry da file.
            // Quando si aggiunge una riga, queste liste devono essere aggiornate
            // per mantenere la coerenza degli indici, anche se non usate.
            // Aggiungiamo uno stato "vuoto" per la nuova riga.
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

                // CORREZIONE: Rimuovi anche lo stato corrispondente per evitare IndexOutOfBounds
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

    // --- INIZIO MODIFICA: Logica di esportazione robusta ---
    val originalHeaderKeysSet = originalHeader.toSet()
    var extraCellIndex = filteredHeaderKeys.size

    // Aggiungi l'intestazione fornitore solo se non esiste già
    if ("supplier" !in originalHeaderKeysSet) {
        headerRow.createCell(extraCellIndex).setCellValue(getLocalizedHeader(context, "supplier"))
        extraCellIndex++
    }

    // Aggiungi l'intestazione categoria solo se non esiste già
    if ("category" !in originalHeaderKeysSet) {
        headerRow.createCell(extraCellIndex).setCellValue(getLocalizedHeader(context, "category"))
    }
    // --- FINE MODIFICA ---

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

        // --- INIZIO MODIFICA: Aggiunta condizionale dei dati per fornitore e categoria ---
        extraCellIndex = filteredHeaderKeys.size // Resetta l'indice per la riga corrente

        // Aggiungi i dati del fornitore solo se la colonna è stata creata ex novo
        if ("supplier" !in originalHeaderKeysSet) {
            val supplierCell = excelRow.createCell(extraCellIndex)
            supplierCell.setCellValue(supplier)
            // Applica stile
            if (complete.getOrNull(rowIndex + 1) == true) {
                supplierCell.cellStyle = styleComplete
            } else if (hasEditableValues) {
                supplierCell.cellStyle = styleFilled
            }
            extraCellIndex++
        }

        // Aggiungi i dati della categoria solo se la colonna è stata creata ex novo
        if ("category" !in originalHeaderKeysSet) {
            val categoryCell = excelRow.createCell(extraCellIndex)
            // Se la colonna non esisteva, usiamo il valore di fallback passato alla funzione
            categoryCell.setCellValue(category)
            // Applica stile
            if (complete.getOrNull(rowIndex + 1) == true) {
                categoryCell.cellStyle = styleComplete
            } else if (hasEditableValues) {
                categoryCell.cellStyle = styleFilled
            }
        }
        // --- FINE MODIFICA ---
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

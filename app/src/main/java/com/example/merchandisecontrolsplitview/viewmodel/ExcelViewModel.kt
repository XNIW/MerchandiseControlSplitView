package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedStringForInput
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Classe sealed per rappresentare i possibili stati del filtro data.
 * Questo approccio è type-safe e rende il codice più leggibile e manutenibile.
 */
sealed class DateFilter {
    object All : DateFilter()
    object LastMonth : DateFilter()
    object PreviousMonth : DateFilter()
    data class CustomRange(val startDate: LocalDate, val endDate: LocalDate) : DateFilter()
}

/**
 * ViewModel per gestione griglia Excel e cronologia persistente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExcelViewModel(application: Application) : AndroidViewModel(application) {

    // Stato griglia
    val excelData = mutableStateListOf<List<String>>()
    val selectedColumns = mutableStateListOf<Boolean>()
    val editableValues = mutableStateListOf<MutableList<MutableState<String>>>()
    val completeStates = mutableStateListOf<Boolean>()
    val errorRowIndexes = mutableStateOf<Set<Int>>(emptySet())

    // Flag UI
    val generated = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)

    // --- NUOVA GESTIONE CRONOLOGIA REATTIVA ---

    // Stato privato che mantiene il filtro corrente. Inizia con "Mostra tutto".
    private val _dateFilter = MutableStateFlow<DateFilter>(DateFilter.All)

    // Flusso pubblico osservabile dalla UI.
    // flatMapLatest assicura che se il filtro cambia, la vecchia query al DB viene cancellata
    // e ne parte una nuova, prevenendo race conditions.
    val historyEntries: StateFlow<List<HistoryEntry>> = _dateFilter
        .flatMapLatest { filter ->
            getFilteredHistoryFlow(filter)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily, // Il flow si attiva solo se c'è almeno un osservatore (la UI)
            initialValue = emptyList()      // Valore iniziale mentre si attende il primo dato dal DB
        )

    private var currentIndex: Int? = null

    val currentEntryStatus = mutableStateOf(Pair(SyncStatus.NOT_ATTEMPTED, false))
    val headerTypes = mutableStateListOf<String>()

    // --- BLOCCO DATABASE ---
    private val db = AppDatabase.getDatabase(application)
    private val historyDao = db.historyEntryDao()
    private val productDao = db.productDao()

    private var currentSupplierName: String = ""
    val supplierName: String
        get() = currentSupplierName

    private var currentCategoryName: String = ""
    val categoryName: String
        get() = currentCategoryName

    /**
     * NUOVO: Funzione helper che sceglie la query DAO corretta in base al filtro.
     * Converte i filtri (es. LastMonth) in date concrete (stringhe "AAAA-MM-GG HH:mm:ss").
     */
    private fun getFilteredHistoryFlow(filter: DateFilter): Flow<List<HistoryEntry>> {
        // Formattatore per le query SQLite, che può confrontare questo formato di stringa.
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        return when (filter) {
            is DateFilter.All -> historyDao.getAllFlow()

            is DateFilter.LastMonth -> {
                val today = LocalDate.now()
                val startOfMonth = today.withDayOfMonth(1)
                val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())
                val startDateString = startOfMonth.atStartOfDay().format(formatter)
                val endDateString = endOfMonth.atTime(23, 59, 59).format(formatter)
                historyDao.getEntriesBetweenDatesFlow(startDateString, endDateString)
            }

            is DateFilter.PreviousMonth -> {
                val today = LocalDate.now()
                val previousMonth = YearMonth.from(today).minusMonths(1)
                val startOfPreviousMonth = previousMonth.atDay(1)
                val endOfPreviousMonth = previousMonth.atEndOfMonth()
                val startDateString = startOfPreviousMonth.atStartOfDay().format(formatter)
                val endDateString = endOfPreviousMonth.atTime(23, 59, 59).format(formatter)
                historyDao.getEntriesBetweenDatesFlow(startDateString, endDateString)
            }

            is DateFilter.CustomRange -> {
                val startDateString = filter.startDate.atStartOfDay().format(formatter)
                val endDateString = filter.endDate.atTime(23, 59, 59).format(formatter)
                historyDao.getEntriesBetweenDatesFlow(startDateString, endDateString)
            }
        }
    }

    /**
     * NUOVO: Metodo pubblico chiamato dalla UI per cambiare il filtro attivo.
     */
    fun setDateFilter(filter: DateFilter) {
        _dateFilter.value = filter
    }

    private fun updateCurrentEntryStatus() {
        // Per accedere alla lista, ora usiamo .value sullo StateFlow
        val entry = currentIndex?.let { historyEntries.value.getOrNull(it) }
        currentEntryStatus.value = Pair(
            entry?.syncStatus ?: SyncStatus.NOT_ATTEMPTED,
            entry?.wasExported ?: false
        )
    }

    fun loadFromMultipleUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null
            resetState()
            try {
                val (goldenHeader, firstDataRows, headerSource) = withContext(Dispatchers.IO) {
                    readAndAnalyzeExcel(context, uris.first())
                }
                if (goldenHeader.isEmpty()) {
                    throw IllegalStateException(context.getString(R.string.error_first_file_empty_or_invalid))
                }
                val allValidRows = mutableListOf<List<String>>()
                allValidRows.addAll(firstDataRows)
                if (uris.size > 1) {
                    for (uri in uris.drop(1)) {
                        val (newHeader, newDataRows, _) = withContext(Dispatchers.IO) {
                            readAndAnalyzeExcel(context, uri)
                        }
                        if (newHeader != goldenHeader) {
                            throw IllegalArgumentException(context.getString(R.string.error_different_columns))
                        }
                        allValidRows.addAll(newDataRows)
                    }
                }
                excelData.add(goldenHeader)
                excelData.addAll(allValidRows)
                headerTypes.addAll(headerSource)
                initPreGenerateState()
            } catch (e: Exception) {
                loadError.value = e.message ?: context.getString(R.string.error_unknown_file_analysis)
            } finally {
                isLoading.value = false
            }
        }
    }

    fun appendFromMultipleUris(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null

            if (excelData.isEmpty()) {
                loadError.value = context.getString(R.string.error_main_file_needed)
                isLoading.value = false
                return@launch
            }

            try {
                val originalHeader = excelData.first()
                val allNewDataRows = mutableListOf<List<String>>()

                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        val (newHeader, newDataRows, _) = readAndAnalyzeExcel(context, uri)

                        if (originalHeader != newHeader) {
                            throw IllegalArgumentException(context.getString(R.string.error_incompatible_file_structure))
                        }
                        allNewDataRows.addAll(newDataRows)
                    }
                }

                if (allNewDataRows.isNotEmpty()) {
                    excelData.addAll(allNewDataRows)

                    repeat(allNewDataRows.size) {
                        editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
                        completeStates.add(false)
                    }
                }

            } catch (e: Exception) {
                loadError.value = e.message ?: context.getString(R.string.error_adding_files)
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun initPreGenerateState() {
        selectedColumns.clear()
        excelData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(true) } }
        editableValues.clear()
        excelData.forEach { _ -> editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf(""))) }
        completeStates.clear()
        repeat(excelData.size) { completeStates.add(false) }
    }

    fun generateFilteredWithOldPrices(supplierName: String, categoryName: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val filtered = excelData.mapIndexed { idx, row ->
                if (idx == 0) {
                    row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true } +
                            listOf("oldPurchasePrice", "oldRetailPrice", "realQuantity", "RetailPrice", "complete")
                } else {
                    val original = row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true }
                    val barcodeIdx = excelData.firstOrNull()?.indexOf("barcode") ?: -1
                    val barcode = excelData[idx].getOrNull(barcodeIdx)
                    var oldPurchase = ""
                    var oldRetail = ""
                    if (!barcode.isNullOrBlank()) {
                        val product = withContext(Dispatchers.IO) { productDao.findByBarcode(barcode) }
                        if (product != null) {
                            oldPurchase = formatNumberAsRoundedStringForInput(product.purchasePrice)
                            oldRetail = formatNumberAsRoundedStringForInput(product.retailPrice)
                        }
                    }
                    original + listOf(oldPurchase, oldRetail, editableValues.getOrNull(idx)?.getOrNull(0)?.value.orEmpty(), editableValues.getOrNull(idx)?.getOrNull(1)?.value.orEmpty(), "")
                }
            }
            excelData.clear()
            excelData.addAll(filtered)
            editableValues.clear()
            editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            filtered.drop(1).forEach { row ->
                val q = row.getOrNull(row.size - 3) ?: ""
                val p = row.getOrNull(row.size - 2) ?: ""
                editableValues.add(mutableListOf(mutableStateOf(q), mutableStateOf(p)))
            }
            completeStates.clear()
            repeat(filtered.size) { completeStates.add(false) }
            selectedColumns.clear()
            filtered.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }
            generated.value = true
            val now = LocalDateTime.now()
            val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            val cleanedSupplier = supplierName.replace("\\W".toRegex(), "_")
            val id = if (supplierName.isNotBlank()) "${stamp}_$cleanedSupplier.xlsx" else "$stamp.xlsx"
            currentSupplierName = supplierName
            currentCategoryName = categoryName
            addHistoryEntryWithId(id, supplierName, categoryName)
            onResult(id)
        }
    }

    private fun addHistoryEntryWithId(id: String, supplier: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = LocalDateTime.now()
            val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            val (totalItems, orderTotal, paymentTotal) = calculateSummary(excelData)

            val entry = HistoryEntry(
                id = id,
                timestamp = stamp,
                data = excelData.map { it.toList() },
                editable = editableValues.map { row -> row.map { it.value } },
                complete = completeStates.toList(),
                supplier = supplier,
                category = category,
                totalItems = totalItems,
                orderTotal = orderTotal,
                paymentTotal = paymentTotal
            )

            historyDao.insert(entry)

            withContext(Dispatchers.Main) {
                currentIndex = 0
                currentEntryStatus.value = Pair(SyncStatus.NOT_ATTEMPTED, false)
            }
        }
    }

    fun renameHistoryEntry(entry: HistoryEntry, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedEntry = entry.copy(id = newName)
            historyDao.update(updatedEntry)
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.delete(entry)
        }
    }

    fun updateHistoryEntry() {
        currentIndex?.takeIf { it in historyEntries.value.indices }?.let { idx ->
            val e = historyEntries.value[idx]

            val updatedEntry = e.copy(
                data = excelData.map { it.toList() },
                editable = editableValues.map { row -> row.map { it.value } },
                complete = completeStates.toList()
            )

            viewModelScope.launch(Dispatchers.IO) {
                historyDao.update(updatedEntry)
            }
        }
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        val idx = historyEntries.value.indexOfFirst { it.uid == entry.uid }
        if (idx >= 0) {
            currentIndex = idx
            excelData.clear(); excelData.addAll(entry.data)
            selectedColumns.clear(); excelData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }
            editableValues.clear(); entry.editable.forEach { row -> editableValues.add(row.map { mutableStateOf(it) }.toMutableList()) }
            completeStates.clear(); completeStates.addAll(entry.complete)
            generated.value = true
            currentSupplierName = entry.supplier
            currentCategoryName = entry.category // Assicurati di caricare anche la categoria
            updateCurrentEntryStatus()
        }
    }

    fun resetState() {
        excelData.clear()
        selectedColumns.clear()
        editableValues.clear()
        completeStates.clear()
        errorRowIndexes.value = emptySet()
        generated.value = false
        isLoading.value = false
        loadError.value = null
        currentIndex = null
        currentSupplierName = ""
        currentCategoryName = ""
        updateCurrentEntryStatus()
    }

    suspend fun saveFileSuspend(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        saveExcelFileInternal(context, uri, excelData, editableValues, completeStates, currentSupplierName, currentCategoryName)
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

    fun markCurrentEntryAsExported() {
        currentIndex?.takeIf { it in historyEntries.value.indices }?.let { idx ->
            val entry = historyEntries.value[idx]
            if (!entry.wasExported) {
                val updatedEntry = entry.copy(wasExported = true)
                viewModelScope.launch(Dispatchers.IO) {
                    historyDao.update(updatedEntry)
                }
                currentEntryStatus.value = currentEntryStatus.value.copy(second = true)
            }
        }
    }

    fun markCurrentEntryAsSyncedSuccessfully() {
        updateSyncStatus(SyncStatus.SYNCED_SUCCESSFULLY)
    }

    fun markCurrentEntryAsSyncedWithErrors() {
        updateSyncStatus(SyncStatus.ATTEMPTED_WITH_ERRORS)
    }

    private fun updateSyncStatus(newStatus: SyncStatus) {
        currentIndex?.takeIf { it in historyEntries.value.indices }?.let { idx ->
            val entry = historyEntries.value[idx]
            if (entry.syncStatus != newStatus) {
                val updatedEntry = entry.copy(syncStatus = newStatus)
                viewModelScope.launch(Dispatchers.IO) {
                    historyDao.update(updatedEntry)
                }
                currentEntryStatus.value = currentEntryStatus.value.copy(first = newStatus)
            }
        }
    }

    private fun calculateSummary(data: List<List<String>>): Triple<Int, Double, Double> {
        var totalItems = 0
        var orderTotal = 0.0
        var paymentTotal = 0.0

        val header = data.firstOrNull() ?: return Triple(0, 0.0, 0.0)

        val purchasePriceIndex = header.indexOf("purchasePrice")
        val discountedPriceIndex = header.indexOf("discountedPrice")
        val discountIndex = header.indexOf("discount")
        val originalQuantityIndex = header.indexOf("quantity")
        val realQuantityIndex = header.indexOf("realQuantity")

        data.drop(1).forEach { rowData ->
            val realQuantityStr = if (realQuantityIndex != -1) rowData.getOrNull(realQuantityIndex) ?: "" else ""
            val originalQuantityStr = if (originalQuantityIndex != -1) rowData.getOrNull(originalQuantityIndex) ?: "0" else "0"

            val quantityToUseStr = realQuantityStr.ifBlank { originalQuantityStr }
            val quantity = quantityToUseStr.replace(",", ".").toDoubleOrNull() ?: 0.0

            if (quantity > 0) {
                totalItems++

                val purchasePriceFromFile = (if (purchasePriceIndex != -1) rowData.getOrNull(purchasePriceIndex) else "0")
                    ?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                val discountedPriceFromFile = (if (discountedPriceIndex != -1) rowData.getOrNull(discountedPriceIndex) else null)
                    ?.replace(",", ".")?.toDoubleOrNull()
                val discountFromFile = (if (discountIndex != -1) rowData.getOrNull(discountIndex) else null)
                    ?.replace(",", ".")?.toDoubleOrNull()

                val finalPaymentPrice = when {
                    discountedPriceFromFile != null -> discountedPriceFromFile
                    discountFromFile != null -> purchasePriceFromFile * (1 - (discountFromFile / 100))
                    else -> purchasePriceFromFile
                }

                orderTotal += purchasePriceFromFile * quantity
                paymentTotal += finalPaymentPrice * quantity
            }
        }

        return Triple(totalItems, orderTotal, paymentTotal)
    }
}

private fun saveExcelFileInternal(
    context: Context,
    uri: Uri,
    data: List<List<String>>,
    editable: List<List<MutableState<String>>>,
    complete: List<Boolean>,
    supplier: String,
    category: String // 1. AGGIUNGI IL PARAMETRO MANCANTE
) {
    val wb = XSSFWorkbook()
    val sheet = wb.createSheet(context.getString(R.string.sheet_name_export))

    // Questa variabile è usata, l'avviso era dovuto a codice incompleto
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

    val originalHeader = data.firstOrNull() ?: return
    val headerWithIndices = originalHeader.mapIndexedNotNull { index, header ->
        if (header != "complete") index to header else null
    }

    val filteredHeader = headerWithIndices.map { it.second }
    val headerRow = sheet.createRow(0)

    filteredHeader.forEachIndexed { newIndex, headerKey ->
        headerRow.createCell(newIndex).setCellValue(getLocalizedHeader(context, headerKey))
    }

    // 2. AGGIUNGI LE INTESTAZIONI PER FORNITORE E CATEGORIA
    headerRow.createCell(filteredHeader.size).setCellValue(getLocalizedHeader(context, "supplier"))
    headerRow.createCell(filteredHeader.size + 1).setCellValue(getLocalizedHeader(context, "category"))

    data.drop(1).forEachIndexed { rowIndex, rowData ->
        val excelRow = sheet.createRow(rowIndex + 1)
        var hasEditableValues = false
        if (complete.getOrNull(rowIndex + 1) == true) {
            // Flag per colorare
        } else if (editable.getOrNull(rowIndex + 1)?.all { it.value.isNotEmpty() } == true) {
            hasEditableValues = true
        }

        // 3. RIPRISTINA LA LOGICA COMPLETA PER SCRIVERE LE CELLE
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

        // 4. AGGIUNGI I VALORI NELLE CELLE DI FORNITORE E CATEGORIA
        val supplierCell = excelRow.createCell(filteredHeader.size)
        supplierCell.setCellValue(supplier)

        val categoryCell = excelRow.createCell(filteredHeader.size + 1)
        categoryCell.setCellValue(category) // Usa il parametro 'category'

        // Applica gli stili anche alla nuova cella
        if (complete.getOrNull(rowIndex + 1) == true) {
            supplierCell.cellStyle = styleComplete
            categoryCell.cellStyle = styleComplete
        } else if (hasEditableValues) {
            supplierCell.cellStyle = styleFilled
            categoryCell.cellStyle = styleFilled
        }
    }

    sheet.defaultColumnWidth = 15
    context.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
    wb.close()
}
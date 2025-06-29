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
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ViewModel per gestione griglia Excel e cronologia persistente.
 */
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

    // Cronologia persistente
    val historyEntries = mutableStateListOf<HistoryEntry>()
    private var currentIndex: Int? = null

    val currentEntryStatus = mutableStateOf(Pair(SyncStatus.NOT_ATTEMPTED, false))
    val headerTypes = mutableStateListOf<String>()

    // --- BLOCCO DATABASE CORRETTO ---
    private val db = AppDatabase.getDatabase(application)
    private val historyDao = db.historyEntryDao()
    private val productDao = db.productDao() // Definiamo qui il DAO per i prodotti

    private var currentSupplierName: String = ""
    val supplierName: String
        get() = currentSupplierName

    init {
        loadHistoryFromDb()
    }

    private fun loadHistoryFromDb() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = historyDao.getAll()
            withContext(Dispatchers.Main) {
                historyEntries.clear()
                historyEntries.addAll(list)
            }
        }
    }

    private fun updateCurrentEntryStatus() {
        val entry = currentIndex?.let { historyEntries.getOrNull(it) }
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

                // 1. Cicla su ogni Uri per validare e raccogliere i dati
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        // Leggi e analizza il file corrente
                        val (newHeader, newDataRows, _) = readAndAnalyzeExcel(context, uri)

                        // 2. --- VALIDAZIONE ---
                        // Se l'header non corrisponde, interrompi l'intera operazione.
                        if (originalHeader != newHeader) {
                            // Lancia un'eccezione per bloccare il processo
                            throw IllegalArgumentException(context.getString(R.string.error_incompatible_file_structure))
                        }

                        // Se valido, aggiungi le sue righe alla lista temporanea
                        allNewDataRows.addAll(newDataRows)
                    }
                }

                // 3. Se il ciclo è completato senza errori, accoda tutti i dati raccolti
                if (allNewDataRows.isNotEmpty()) {
                    excelData.addAll(allNewDataRows)

                    // Estendi gli altri stati per mantenere la coerenza
                    repeat(allNewDataRows.size) {
                        editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
                        completeStates.add(false)
                    }
                }

            } catch (e: Exception) {
                // Cattura sia le eccezioni di I/O che la nostra eccezione di validazione
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

    fun generateFilteredWithOldPrices(supplierName: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val filtered = excelData.mapIndexed { idx, row ->
                if (idx == 0) {
                    row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true } +
                            listOf("oldPurchasePrice", "oldRetailPrice", "autocount", "newRetailPrice", "complete")
                } else {
                    val original = row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true }
                    val barcodeIdx = excelData.firstOrNull()?.indexOf("barcode") ?: -1
                    val barcode = excelData[idx].getOrNull(barcodeIdx)
                    var oldPurchase = ""
                    var oldRetail = ""
                    if (!barcode.isNullOrBlank()) {
                        val product = withContext(Dispatchers.IO) {
                            productDao.findByBarcode(barcode) // Usa il DAO definito all'inizio
                        }
                        if (product != null) {
                            oldPurchase = product.newPurchasePrice?.toString() ?: ""
                            oldRetail = product.newRetailPrice?.toString() ?: ""
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
            addHistoryEntryWithId(id, supplierName)
            onResult(id)
        }
    }

    private fun addHistoryEntryWithId(id: String, supplier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = LocalDateTime.now()
            val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val entry = HistoryEntry(
                id = id,
                timestamp = stamp,
                data = excelData.map { it.toList() },
                editable = editableValues.map { row -> row.map { it.value } },
                complete = completeStates.toList(),
                supplier = supplier
            )
            historyDao.insert(entry)
            val updatedList = historyDao.getAll()
            withContext(Dispatchers.Main) {
                historyEntries.clear()
                historyEntries.addAll(updatedList)
                currentIndex = 0
                updateCurrentEntryStatus()
            }
        }
    }

    fun renameHistoryEntry(entry: HistoryEntry, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedEntry = entry.copy(id = newName)
            historyDao.update(updatedEntry)
            withContext(Dispatchers.Main) {
                val idx = historyEntries.indexOfFirst { it.uid == entry.uid }
                if (idx >= 0) {
                    historyEntries[idx] = updatedEntry
                }
            }
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.delete(entry)
            withContext(Dispatchers.Main) {
                historyEntries.removeIf { it.uid == entry.uid }
            }
        }
    }

    fun updateHistoryEntry() {
        currentIndex?.takeIf { it in historyEntries.indices }?.let { idx ->
            val e = historyEntries[idx]
            val updatedEntry = e.copy(
                data = excelData.map { it.toList() },
                editable = editableValues.map { row -> row.map { it.value } },
                complete = completeStates.toList()
            )
            historyEntries[idx] = updatedEntry
            viewModelScope.launch(Dispatchers.IO) {
                historyDao.update(updatedEntry)
            }
        }
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        // La logica qui è principalmente sulla UI, quindi non serve cambiare molto
        val idx = historyEntries.indexOfFirst { it.uid == entry.uid }
        if (idx >= 0) {
            currentIndex = idx
            excelData.clear(); excelData.addAll(entry.data)
            selectedColumns.clear(); excelData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }
            editableValues.clear(); entry.editable.forEach { row -> editableValues.add(row.map { mutableStateOf(it) }.toMutableList()) }
            completeStates.clear(); completeStates.addAll(entry.complete)
            generated.value = true
            currentSupplierName = entry.supplier
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
        updateCurrentEntryStatus()
    }

    suspend fun saveFileSuspend(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        saveExcelFileInternal(context, uri, excelData, editableValues, completeStates, currentSupplierName)
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
        currentIndex?.takeIf { it in historyEntries.indices }?.let { idx ->
            val entry = historyEntries[idx]
            if (!entry.wasExported) {
                val updatedEntry = entry.copy(wasExported = true)
                historyEntries[idx] = updatedEntry
                viewModelScope.launch(Dispatchers.IO) {
                    historyDao.update(updatedEntry)
                }
                updateCurrentEntryStatus()
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
        currentIndex?.takeIf { it in historyEntries.indices }?.let { idx ->
            val entry = historyEntries[idx]
            if (entry.syncStatus != newStatus) {
                val updatedEntry = entry.copy(syncStatus = newStatus)
                historyEntries[idx] = updatedEntry
                viewModelScope.launch(Dispatchers.IO) {
                    historyDao.update(updatedEntry)
                }
                updateCurrentEntryStatus()
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
    supplier: String
) {
    val wb = XSSFWorkbook()
    val sheet = wb.createSheet(context.getString(R.string.sheet_name_export))
    val numericTypes = setOf(
        "quantity", "purchasePrice", "retailPrice", "totalPrice", "rowNumber",
        "discount", "discountedPrice", "realQuantity", "newRetailPrice",
        "oldPurchasePrice", "oldRetailPrice", "autocount"
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
    headerRow.createCell(filteredHeader.size).setCellValue(getLocalizedHeader(context, "supplier"))
    data.drop(1).forEachIndexed { rowIndex, rowData ->
        val excelRow = sheet.createRow(rowIndex + 1)
        var hasEditableValues = false
        if (complete.getOrNull(rowIndex + 1) == true) {
            // Flag per colorare tutta la riga dopo
        } else if (editable.getOrNull(rowIndex + 1)?.all { it.value.isNotEmpty() } == true) {
            hasEditableValues = true
        }
        headerWithIndices.forEachIndexed { newIndex, (originalIndex, headerKey) ->
            val cell = excelRow.createCell(newIndex)
            val cellValue: String = when (headerKey) {
                "autocount" -> editable.getOrNull(rowIndex + 1)?.getOrNull(0)?.value ?: ""
                "newRetailPrice" -> editable.getOrNull(rowIndex + 1)?.getOrNull(1)?.value ?: ""
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
        val supplierCell = excelRow.createCell(filteredHeader.size)
        supplierCell.setCellValue(supplier)
        if (complete.getOrNull(rowIndex + 1) == true) {
            supplierCell.cellStyle = styleComplete
        } else if (hasEditableValues) {
            supplierCell.cellStyle = styleFilled
        }
    }
    sheet.defaultColumnWidth = 15
    context.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
    wb.close()
}
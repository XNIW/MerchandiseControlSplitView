package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.util.getLocalizedHeader
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val prefs = application.getSharedPreferences("history_prefs", Context.MODE_PRIVATE)
    private val gson  = Gson()

    // Stato griglia
    val excelData       = mutableStateListOf<List<String>>()
    val selectedColumns = mutableStateListOf<Boolean>()
    val editableValues  = mutableStateListOf<MutableList<MutableState<String>>>()
    val completeStates  = mutableStateListOf<Boolean>()

    val errorRowIndexes = mutableStateOf<Set<Int>>(emptySet())

    // Flag UI
    val generated = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)

    // Cronologia persistente
    val historyEntries  = mutableStateListOf<HistoryEntry>()
    private var currentIndex: Int? = null

    val currentEntryStatus = mutableStateOf(Pair(SyncStatus.NOT_ATTEMPTED, false))

    /** Aggiorna lo stato reattivo in base all'elemento corrente. */
    private fun updateCurrentEntryStatus() {
        val entry = currentIndex?.let { historyEntries.getOrNull(it) }
        currentEntryStatus.value = Pair(
            entry?.syncStatus ?: SyncStatus.NOT_ATTEMPTED, // <-- Legge dal nuovo stato
            entry?.wasExported ?: false
        )
    }

    val headerTypes = mutableStateListOf<String>()

    private val db = AppDatabase.getDatabase(application)

    private var currentSupplierName: String = ""
    val supplierName: String
        get() = currentSupplierName

    init {
        loadHistoryFromPrefs()
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun loadHistoryFromPrefs() {
        prefs.getString("history_list", null)?.let { json ->
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            val list: List<HistoryEntry> = gson.fromJson(json, type)

            // Add this block to fix old data
            val fixedList = list.map { entry ->
                // If syncStatus is null (because the entry is old), set a default value.
                if (entry.syncStatus == null) {
                    entry.copy(syncStatus = SyncStatus.NOT_ATTEMPTED)
                } else {
                    entry
                }
            }
            // End of correction block

            historyEntries.clear()
            historyEntries.addAll(fixedList) // Use the corrected list
        }
    }

    private fun saveHistoryToPrefs() {
        val json = gson.toJson(historyEntries.toList())
        prefs.edit {
            putString("history_list", json)
        }
    }

    fun loadFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null
            try {
                val (header, dataRows, headerSource) = withContext(Dispatchers.IO) { readAndAnalyzeExcel(context, uri) }
                excelData.clear()
                if (header.isNotEmpty()) {
                    excelData.add(header)
                    excelData.addAll(dataRows)
                }
                headerTypes.clear()
                headerTypes.addAll(headerSource)
                generated.value = false
                initPreGenerateState()
            } catch (_: Exception) {
                loadError.value = "Errore nel leggere il file Excel"
            }
            isLoading.value = false
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
                            db.productDao().findByBarcode(barcode)
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

            val now   = LocalDateTime.now()
            // *** MODIFICA QUI ***
            // Sostituiamo i due punti (:) con i trattini (-) per rendere il nome sicuro
            val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            val cleanedSupplier = supplierName.replace("\\W".toRegex(), "_")
            val id    = if (supplierName.isNotBlank()) "${stamp}_$cleanedSupplier.xlsx" else "$stamp.xlsx"

            currentSupplierName = supplierName

            addHistoryEntryWithId(id, supplierName)
            saveHistoryToPrefs()
            onResult(id) // L'ID ora è sicuro
        }
    }

    private fun addHistoryEntryWithId(id: String, supplier: String) {
        val now   = LocalDateTime.now()
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val entry = HistoryEntry(
            id, stamp,
            excelData.map { it.toList() },
            editableValues.map { row -> row.map { it.value } },
            completeStates.toList(),
            supplier
        )
        historyEntries.add(0, entry)
        currentIndex = 0
        updateCurrentEntryStatus()
    }

    // ... il resto del ViewModel non cambia ...
    fun renameHistoryEntry(entry: HistoryEntry, newName: String) {
        val idx = historyEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            val old = historyEntries[idx]
            historyEntries[idx] = old.copy(id = newName)
            saveHistoryToPrefs()
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        historyEntries.remove(entry)
        saveHistoryToPrefs()
    }

    fun updateHistoryEntry() {
        currentIndex?.takeIf { it in historyEntries.indices }?.let { idx ->
            val e = historyEntries[idx]
            historyEntries[idx] = e.copy(
                data     = excelData.map { it.toList() },
                editable = editableValues.map { row -> row.map { it.value } },
                complete = completeStates.toList()
            )
            saveHistoryToPrefs()
        }
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        val idx = historyEntries.indexOfFirst { it.id == entry.id }
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
            // Aggiorna solo se non è già impostato, per efficienza
            if (!entry.wasExported) {
                historyEntries[idx] = entry.copy(wasExported = true)
                saveHistoryToPrefs() // Salva la modifica
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

    // AGGIUNGI questa funzione helper privata
    private fun updateSyncStatus(newStatus: SyncStatus) {
        currentIndex?.takeIf { it in historyEntries.indices }?.let { idx ->
            val entry = historyEntries[idx]
            if (entry.syncStatus != newStatus) {
                historyEntries[idx] = entry.copy(syncStatus = newStatus)
                saveHistoryToPrefs()
                updateCurrentEntryStatus()
            }
        }
    }
}


// --- FUNZIONE DI ESPORTAZIONE CORRETTA ---
private fun saveExcelFileInternal(
    context: Context,
    uri: Uri,
    data: List<List<String>>,
    editable: List<List<MutableState<String>>>,
    complete: List<Boolean>,
    supplier: String
) {
    val wb = XSSFWorkbook()
    val sheet = wb.createSheet("Export")

    // --- 1. Definiamo i tipi di colonna ---
    val numericTypes = setOf(
        "quantity", "purchasePrice", "retailPrice", "totalPrice", "rowNumber",
        "discount", "discountedPrice", "realQuantity", "newRetailPrice",
        "oldPurchasePrice", "oldRetailPrice", "autocount"
    )

    // Definiamo gli stili per le righe
    val styleComplete = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_GREEN.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
    }
    val styleFilled = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
    }

    // --- 2. Filtriamo l'header per rimuovere la colonna "complete" ---
    val originalHeader = data.firstOrNull() ?: return
    val headerWithIndices = originalHeader.mapIndexedNotNull { index, header ->
        if (header != "complete") index to header else null
    }
    val filteredHeader = headerWithIndices.map { it.second }

    // --- 3. Scriviamo il nuovo header filtrato (localizzato) + la colonna supplier ---
    val headerRow = sheet.createRow(0)
    filteredHeader.forEachIndexed { newIndex, headerKey ->
        headerRow.createCell(newIndex).setCellValue(getLocalizedHeader(context, headerKey))
    }
    headerRow.createCell(filteredHeader.size).setCellValue(getLocalizedHeader(context, "supplier"))


    // --- 4. Scriviamo le righe di dati con i tipi corretti ---
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

            // --- INIZIO CORREZIONE ---
            // Determina la sorgente corretta per il valore della cella.
            // Per 'autocount' e 'newRetailPrice', usa la lista 'editable', altrimenti la lista 'data'.
            val cellValue: String = when (headerKey) {
                "autocount" -> editable.getOrNull(rowIndex + 1)?.getOrNull(0)?.value ?: ""
                "newRetailPrice" -> editable.getOrNull(rowIndex + 1)?.getOrNull(1)?.value ?: ""
                else -> rowData.getOrNull(originalIndex) ?: ""
            }
            // --- FINE CORREZIONE ---

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

    // --- CORREZIONE ---
    // Rimossa la sezione "autoSizeColumn" che causava il crash.
    // Inseriamo invece una larghezza di colonna predefinita per migliorare la leggibilità.
    // Il valore 15 corrisponde a circa 15 caratteri di larghezza.
    sheet.defaultColumnWidth = 15

    // Scriviamo il file
    context.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
    wb.close()
}
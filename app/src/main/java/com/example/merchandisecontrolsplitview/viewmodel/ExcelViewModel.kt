package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit // IMPORT AGGIUNTO per SharedPreferences.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.example.merchandisecontrolsplitview.data.AppDatabase

/** Un singolo record di cronologia. */
data class HistoryEntry(
    val id: String,                // es. "2025-06-17_16:59:07.xlsx"
    val timestamp: String,
    val data: List<List<String>>,
    val editable: List<List<String>>,
    val complete: List<Boolean>
)

/**
 * ViewModel per gestione griglia Excel e cronologia persistente.
 */
class ExcelViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("history_prefs", Context.MODE_PRIVATE)
    private val gson  = Gson()

    // --- Stato griglia ---
    val excelData       = mutableStateListOf<List<String>>()
    val selectedColumns = mutableStateListOf<Boolean>()
    val editableValues  = mutableStateListOf<MutableList<MutableState<String>>>()
    val completeStates  = mutableStateListOf<Boolean>()

    // --- Flag UI ---
    val generated = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)

    // --- Cronologia persistente ---
    val historyEntries  = mutableStateListOf<HistoryEntry>()
    private var currentIndex: Int? = null

    private val db = AppDatabase.getDatabase(application)

    init {
        loadHistoryFromPrefs()
    }

    private fun loadHistoryFromPrefs() {
        prefs.getString("history_list", null)?.let { json ->
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            val list: List<HistoryEntry> = gson.fromJson(json, type)
            historyEntries.clear()
            historyEntries.addAll(list)
        }
    }

    private fun saveHistoryToPrefs() {
        val json = gson.toJson(historyEntries.toList())
        // MODIFICATO: Utilizzo della funzione KTX "edit"
        prefs.edit {
            putString("history_list", json)
        }
    }

    /** Carica e analizza un file Excel da URI. */
    fun loadFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            isLoading.value = true
            loadError.value = null
            try {
                val (header, dataRows) = withContext(Dispatchers.IO) { readAndAnalyzeExcel(context, uri) }
                excelData.clear()
                if (header.isNotEmpty()) {
                    excelData.add(header)   // Prima riga: intestazione
                    excelData.addAll(dataRows)  // Dati: dalla seconda riga in poi
                }
                generated.value = false
                initPreGenerateState()
            } catch (e: Exception) {
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

    fun generateFilteredWithOldPrices() {
        viewModelScope.launch {
            val filtered = excelData.mapIndexed { idx, row ->
                if (idx == 0) {
                    // HEADER: inserisci le nuove colonne prima di "autocount", "newRetailPrice", "complete"
                    // BLOCCO CORRETTO
                    row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true } +
                            listOf("oldPurchasePrice", "oldRetailPrice", "autocount", "newRetailPrice", "complete")
                } else {
                    // Trova il barcode
                    val original = row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true }
                    val barcodeIdx = excelData.firstOrNull()?.indexOf("barcode") ?: -1
                    val barcode = excelData[idx].getOrNull(barcodeIdx)
                    var oldPurchase = ""
                    var oldRetail = ""

                    if (!barcode.isNullOrBlank()) {
                        // Query database (usa withContext su IO per non bloccare main thread!)
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

            // Aggiorna stato come prima
            excelData.clear()
            excelData.addAll(filtered)

            // Ricostruisci i valori editabili (autocount/newRetailPrice)
            editableValues.clear()
            editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            filtered.drop(1).forEach { row ->
                val q = row.getOrNull(row.size - 3) ?: ""
                val p = row.getOrNull(row.size - 2) ?: ""
                editableValues.add(mutableListOf(mutableStateOf(q), mutableStateOf(p)))
            }

            // Reset complete e colonne
            completeStates.clear()
            repeat(filtered.size) { completeStates.add(false) }
            selectedColumns.clear()
            filtered.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }

            generated.value = true
            addHistoryEntry()
            saveHistoryToPrefs()
        }
    }



    /** Aggiunge un nuovo entry con secondi e persiste. */
    // MODIFICATO: La funzione è ora privata
    private fun addHistoryEntry() {
        val now   = LocalDateTime.now()
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss"))
        val id    = "$stamp.xlsx"
        val entry = HistoryEntry(
            id, stamp,
            excelData.map { it.toList() },
            editableValues.map { row -> row.map { it.value } },
            completeStates.toList()
        )
        historyEntries.add(0, entry)
        currentIndex = 0
    }

    /**
     * Rinomina un entry di cronologia e persiste.
     */
    fun renameHistoryEntry(entry: HistoryEntry, newName: String) {
        val idx = historyEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            val old = historyEntries[idx]
            historyEntries[idx] = old.copy(id = newName)
            saveHistoryToPrefs()
        }
    }

    /**
     * Elimina un entry di cronologia e persiste.
     */
    fun deleteHistoryEntry(entry: HistoryEntry) {
        historyEntries.remove(entry)
        saveHistoryToPrefs()
    }

    /**
     * Aggiorna l’entry corrente di cronologia con lo stato attuale della griglia.
     */
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

    /**
     * Carica uno HistoryEntry senza modificarne l’ordine,
     * memorizza l’indice per update futuri e ripristina stato.
     */
    fun loadHistoryEntry(entry: HistoryEntry) {
        val idx = historyEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            currentIndex = idx
            excelData.clear(); excelData.addAll(entry.data)
            selectedColumns.clear(); excelData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }
            editableValues.clear(); entry.editable.forEach { row -> editableValues.add(row.map { mutableStateOf(it) }.toMutableList()) }
            completeStates.clear(); completeStates.addAll(entry.complete)
            generated.value = true
        }
    }

    /**
     * Resetta completamente lo stato del ViewModel per prepararlo a un nuovo file.
     */
    fun resetState() {
        excelData.clear()
        selectedColumns.clear()
        editableValues.clear()
        completeStates.clear()
        generated.value = false
        isLoading.value = false
        loadError.value = null
        currentIndex = null
    }

    suspend fun saveFileSuspend(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        saveExcelFileInternal(context, uri, excelData, editableValues, completeStates)
    }
}

private fun saveExcelFileInternal(
    context: Context,
    uri: Uri,
    data: List<List<String>>,
    editable: List<List<MutableState<String>>>,
    complete: List<Boolean>
) {
    val wb = XSSFWorkbook()
    val sheet = wb.createSheet("Export")
    val styleComplete = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_GREEN.index
        fillPattern         = FillPatternType.SOLID_FOREGROUND
    }
    val styleFilled = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
        fillPattern         = FillPatternType.SOLID_FOREGROUND
    }

    // Header
    val headerRow = sheet.createRow(0)
    data.firstOrNull()?.forEachIndexed { ci, name ->
        headerRow.createCell(ci).setCellValue(name)
    }

    // Data rows
    data.drop(1).forEachIndexed { ri, row ->
        val excelRow = sheet.createRow(ri + 1)
        row.forEachIndexed { ci, txt ->
            val cell = excelRow.createCell(ci)
            when (ci) {
                row.size - 3, row.size - 2 ->
                    cell.setCellValue(editable[ri + 1][ci - (row.size - 3)].value)
                else ->
                    cell.setCellValue(txt)
            }
            when {
                complete.getOrNull(ri + 1) == true ->
                    cell.cellStyle = styleComplete
                editable.getOrNull(ri + 1)
                    ?.let { it[0].value.isNotEmpty() && it[1].value.isNotEmpty() } == true
                        && ci < row.size - 1 ->
                    cell.cellStyle = styleFilled
            }
        }
    }

    context.contentResolver.openOutputStream(uri)?.use { wb.write(it) }
    wb.close()
}

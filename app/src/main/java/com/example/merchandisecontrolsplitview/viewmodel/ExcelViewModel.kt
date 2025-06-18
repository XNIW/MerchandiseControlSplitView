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
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        excelData.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }
        editableValues.clear()
        excelData.forEach { _ -> editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf(""))) }
        completeStates.clear()
        repeat(excelData.size) { completeStates.add(false) }
    }

    /**
     * Filtra colonne, aggiunge Quantità/Prezzo/Completo,
     * registra un entry in cronologia e persiste.
     */
    // MODIFICATO: Rimosso il parametro "context" non utilizzato
    fun generateFiltered() {
        val filtered = excelData.mapIndexed { idx, row ->
            if (idx == 0) {
                row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true }
                    .plus(listOf("Quantità", "Prezzo", "Completo"))
            } else {
                row.filterIndexed { i, _ -> selectedColumns.getOrNull(i) == true }
                    .plus(listOf(
                        editableValues.getOrNull(idx)?.getOrNull(0)?.value.orEmpty(),
                        editableValues.getOrNull(idx)?.getOrNull(1)?.value.orEmpty(),
                        ""
                    ))
            }
        }
        excelData.clear(); excelData.addAll(filtered)

        // Ricostruisco valori editabili
        editableValues.clear()
        editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        filtered.drop(1).forEach { row ->
            val q = row.getOrNull(row.size - 3) ?: ""
            val p = row.getOrNull(row.size - 2) ?: ""
            editableValues.add(mutableListOf(mutableStateOf(q), mutableStateOf(p)))
        }

        // Reset complete e colonne
        completeStates.clear(); repeat(filtered.size) { completeStates.add(false) }
        selectedColumns.clear(); filtered.firstOrNull()?.size?.let { cols -> repeat(cols) { selectedColumns.add(false) } }

        generated.value = true
        addHistoryEntry()
        saveHistoryToPrefs()
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

private fun readAndAnalyzeExcel(
    context: Context,
    uri: Uri
): Pair<List<String>, List<List<String>>> {
    // 1) Lettura grezza di tutte le righe
    val rows = mutableListOf<List<String>>()
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val wb = WorkbookFactory.create(stream)
        val sheet = wb.getSheetAt(0)
        sheet.forEach { row ->
            val temp = mutableListOf<String>()
            val last = row.lastCellNum.toInt()
            for (i in 0 until last) {
                val cell = row.getCell(i)
                val txt = when {
                    cell == null -> ""
                    cell.cellType == CellType.STRING  -> cell.stringCellValue ?: ""
                    cell.cellType == CellType.NUMERIC -> {
                        val n = cell.numericCellValue
                        if (n == n.toLong().toDouble())
                            n.toLong().toString()
                        else
                            n.toBigDecimal().toPlainString()
                    }
                    cell.cellType == CellType.BOOLEAN -> cell.booleanCellValue.toString()
                    else -> ""
                }.trim()
                temp.add(txt)
            }
            val trimmed = temp.dropLastWhile { it.isEmpty() }
            if (!trimmed.all { it.isEmpty() }) {
                rows.add(trimmed)
            }
        }
        wb.close()
    }

    // 2) Trova la prima riga "intestazione dati"
    var dataRowIdx = -1
    for ((idx, row) in rows.withIndex()) {
        val numericCount = row.count { it.toLongOrNull() != null }
        val textCount = row.count { it.isNotBlank() && it.toLongOrNull() == null }
        if (numericCount >= 3 && textCount >= 1) {
            dataRowIdx = idx
            break
        }
    }
    if (dataRowIdx <= 0 || dataRowIdx >= rows.size) {
        return Pair(emptyList(), emptyList())
    }

    // 3) Intestazione (riga sopra dataRowIdx) e dati utili
    val header = rows[dataRowIdx - 1].toMutableList()
    val dataRows = rows
        .drop(dataRowIdx)
        .filter { row ->
            val numericCount = row.count { it.toLongOrNull() != null }
            val textCount = row.count { it.isNotBlank() && it.toLongOrNull() == null }
            numericCount >= 3 && textCount >= 1
        }
    if (dataRows.isEmpty()) return Pair(header, dataRows)

    val colCount = header.size
    val threshold = (dataRows.size * 0.5).toInt()

    // 4) Rilevazione automatica di barcode, quantità, prezzo, totale, nome, codice
    var barcodeIdx: Int? = null
    var qtyIdx: Int? = null
    var priceUnitIdx: Int? = null
    var totalIdx: Int? = null
    var nameIdx: Int? = null
    var codeIdx: Int? = null

    // — barcode
    for (col in 0 until colCount) {
        val matches = dataRows.count { row ->
            val v = row.getOrNull(col) ?: ""
            (v.length in listOf(8,12,13) && v.all(Char::isDigit))
        }
        if (matches >= threshold) {
            barcodeIdx = col; break
        }
    }
    barcodeIdx?.takeIf { it < header.size }?.let { header[it] = "条码" }

    // — quantità
    for (col in 0 until colCount) {
        if (col == barcodeIdx) continue
        val nums = dataRows.mapNotNull { it.getOrNull(col)?.toLongOrNull() }
        if (nums.isNotEmpty() && nums.all { it > 0 } && nums.size >= dataRows.size * 0.7) {
            qtyIdx = col; break
        }
    }
    qtyIdx?.takeIf { it < header.size }?.let { header[it] = "数量" }

    // — prezzo unitario
    for (col in 0 until colCount) {
        if (col == barcodeIdx || col == qtyIdx) continue
        val nums = dataRows.mapNotNull { it.getOrNull(col)?.toLongOrNull() }
        val qtyAvg = qtyIdx?.let { idx -> dataRows.mapNotNull { it.getOrNull(idx)?.toLongOrNull() }.average() } ?: 0.0
        if (
            nums.isNotEmpty() &&
            nums.all { it > 0 } &&
            nums.count { it % 10L == 0L } >= nums.size * 0.7 &&
            nums.average() > qtyAvg &&
            nums.size >= dataRows.size * 0.7
        ) {
            priceUnitIdx = col; break
        }
    }
    priceUnitIdx?.takeIf { it < header.size }?.let { header[it] = "进价" }

    // — totale
    if (qtyIdx != null && priceUnitIdx != null) {
        for (col in 0 until colCount) {
            if (col in listOf(barcodeIdx, qtyIdx, priceUnitIdx)) continue
            val matches = dataRows.count { row ->
                val q = row.getOrNull(qtyIdx)?.toLongOrNull() ?: return@count false
                val p = row.getOrNull(priceUnitIdx)?.toLongOrNull() ?: return@count false
                val tot = row.getOrNull(col)?.toLongOrNull() ?: return@count false
                tot in ((q * p) * 0.98).toLong()..((q * p) * 1.02).toLong()
            }
            if (matches >= dataRows.size * 0.7) {
                totalIdx = col; break
            }
        }
    }
    totalIdx?.takeIf { it < header.size }?.let { header[it] = "总价" }

    // — nome prodotto
    for (col in 0 until colCount) {
        val matches = dataRows.count { row ->
            val v = row.getOrNull(col) ?: ""
            v.length >= 10 && v.any { !it.isDigit() }
        }
        if (matches >= dataRows.size * 0.5) {
            nameIdx = col; break
        }
    }
    nameIdx?.takeIf { it < header.size }?.let { header[it] = "品名" }

    // — codice prodotto
    for (col in 0 until colCount) {
        if (col in listOf(barcodeIdx, qtyIdx, priceUnitIdx, totalIdx, nameIdx)) continue
        val matches = dataRows.count { row ->
            val v = row.getOrNull(col) ?: ""
            v.length in 4..12 && (v.any { it.isDigit() } || v.any { it.isLetter() })
        }
        if (matches >= dataRows.size * 0.5) {
            codeIdx = col; break
        }
    }
    codeIdx?.takeIf { it < header.size }?.let { header[it] = "货号" }

    // 5) Filtra via le colonne completamente vuote sui dati
    val nonEmptyCols = header.indices.filter { col ->
        dataRows.any { row -> row.getOrNull(col)?.isNotBlank() == true }
    }
    val filteredHeader = nonEmptyCols.map { header[it] }
    val filteredDataRows = dataRows.map { row ->
        nonEmptyCols.map { idx -> row.getOrNull(idx) ?: "" }
    }

    return Pair(filteredHeader, filteredDataRows)
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

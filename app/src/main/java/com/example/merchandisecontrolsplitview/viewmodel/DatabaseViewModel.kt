package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel // Usa la tua utility
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.ImportAnalyzer
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException

// La tua classe UiState rimane invariata
sealed class UiState {
    data object Idle : UiState()
    data class Loading(val progress: Int? = null) : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getDatabase(app)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()

    val pager = filter.flatMapLatest { filterStr ->
        Pager(PagingConfig(pageSize = 20)) {
            db.productDao().getAllPaged(filterStr)
        }.flow.cachedIn(viewModelScope)
    }

    fun setFilter(text: String) {
        _filter.value = text.ifBlank { null }
    }

    private val _importAnalysisResult = MutableStateFlow<ImportAnalysis?>(null)
    val importAnalysisResult: StateFlow<ImportAnalysis?> = _importAnalysisResult.asStateFlow()

    fun startImportAnalysis(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // --- FIX: Utilizzo corretto di ExcelUtils.kt ---
                // 1. La tua funzione readAndAnalyzeExcel restituisce le intestazioni GIA' NORMALIZZATE.
                //    Non serve alcuna logica di normalizzazione aggiuntiva qui.
                val (normalizedHeader, dataRows, _) = readAndAnalyzeExcel(context, uri)

                if (normalizedHeader.isEmpty() || dataRows.isEmpty()) {
                    _uiState.value = UiState.Error("Il file Excel è vuoto o non ha un'intestazione valida.")
                    return@launch
                }

                // 2. Crea la mappa usando direttamente le chiavi normalizzate fornite da ExcelUtils.
                val importedRowsAsMap = dataRows.map { row ->
                    normalizedHeader.mapIndexed { index, headerKey ->
                        headerKey to (row.getOrNull(index) ?: "")
                    }.toMap()
                }

                // 3. Il resto del flusso rimane invariato, perché l'analyzer riceve i dati
                //    nel formato corretto che si aspetta.
                val currentDbProducts = db.productDao().getAll()
                val analysis = ImportAnalyzer.analyze(importedRowsAsMap, currentDbProducts)
                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Errore durante l'analisi del file: ${e.message}")
            }
        }
    }

    // La funzione helper `normalizeHeader` è stata rimossa perché ridondante.

    fun clearImportAnalysis() {
        _importAnalysisResult.value = null
    }

    fun importProducts(newProducts: List<Product>, updatedProducts: List<ProductUpdate>, context: Context) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = db.productDao()
                if (newProducts.isNotEmpty()) {
                    dao.insertAll(newProducts)
                }
                if (updatedProducts.isNotEmpty()) {
                    dao.updateAll(updatedProducts.map { it.newProduct })
                }
                _uiState.value = UiState.Success(context.getString(R.string.import_success))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(context.getString(R.string.import_error, e.message ?: "Errore sconosciuto"))
            }
        }
    }

    fun exportToExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val products = db.productDao().getAll()
                if (products.isEmpty()) {
                    _uiState.value = UiState.Error("Nessun prodotto da esportare.")
                    return@launch
                }
                writeProductsToExcel(context, uri, products)
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(context.getString(R.string.export_error, e.message ?: "Errore sconosciuto"))
            }
        }
    }

    private fun writeProductsToExcel(context: Context, uri: Uri, products: List<Product>) {
        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Prodotti")
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "barcode", "itemNumber", "productName", "newPurchasePrice",
            "newRetailPrice", "oldPurchasePrice", "oldRetailPrice", "supplier"
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }
        products.forEachIndexed { index, product ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(product.barcode)
            row.createCell(1).setCellValue(product.itemNumber ?: "")
            row.createCell(2).setCellValue(product.productName ?: "")
            row.createCell(3).setCellValue(product.newPurchasePrice ?: 0.0)
            row.createCell(4).setCellValue(product.newRetailPrice ?: 0.0)
            row.createCell(5).setCellValue(product.oldPurchasePrice ?: 0.0)
            row.createCell(6).setCellValue(product.oldRetailPrice ?: 0.0)
            row.createCell(7).setCellValue(product.supplier ?: "")
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { workbook.write(it) }
            workbook.close()
        } catch (e: IOException) {
            e.printStackTrace()
            throw e
        }
    }
}

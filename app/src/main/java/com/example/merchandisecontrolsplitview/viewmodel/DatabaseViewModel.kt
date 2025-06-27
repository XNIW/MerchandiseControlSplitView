package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.ImportAnalyzer
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException

sealed class UiState {
    data object Idle : UiState()
    data class Loading(val progress: Int? = null) : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getDatabase(app)
    private val dao = db.productDao()
    private val supplierDao = db.supplierDao()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()

    val pager = filter.flatMapLatest { filterStr ->
        Pager(PagingConfig(pageSize = 20)) {
            dao.getAllPaged(filterStr)
        }.flow.cachedIn(viewModelScope)
    }

    // --- BLOCCO FORNITORI FINALE E OTTIMIZZATO ---

    // 1. Questo StateFlow è l'unica fonte di verità per il testo nel campo di ricerca.
    private val _supplierInputText = MutableStateFlow("")
    val supplierInputText: StateFlow<String> = _supplierInputText.asStateFlow()

    // 2. Funzione per la UI per aggiornare il testo.
    fun onSupplierSearchQueryChanged(query: String) {
        _supplierInputText.value = query
    }

    // 3. Il flusso dei suggerimenti reagisce a _supplierInputText con debounce.
    val suppliers: StateFlow<List<Supplier>> = _supplierInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank())
                flow { emit(supplierDao.getAll()) }
            else
                flow { emit(supplierDao.searchByName(query)) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())


    fun setFilter(text: String) {
        _filter.value = text.ifBlank { null }
    }

    private val _importAnalysisResult = MutableStateFlow<ImportAnalysis?>(null)
    val importAnalysisResult: StateFlow<ImportAnalysis?> = _importAnalysisResult.asStateFlow()

    fun startImportAnalysis(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (normalizedHeader, dataRows, _) = readAndAnalyzeExcel(context, uri)
                if (normalizedHeader.isEmpty() || dataRows.isEmpty()) {
                    _uiState.value = UiState.Error("Il file Excel è vuoto o non ha un'intestazione valida.")
                    return@launch
                }
                val importedRowsAsMap = dataRows.map { row ->
                    normalizedHeader.mapIndexed { index, headerKey ->
                        headerKey to (row.getOrNull(index) ?: "")
                    }.toMap()
                }
                val currentDbProducts = dao.getAll()
                val analysis = ImportAnalyzer.analyze(importedRowsAsMap, currentDbProducts, supplierDao)
                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Errore durante l'analisi del file: ${e.message}")
            }
        }
    }

    fun clearImportAnalysis() {
        _importAnalysisResult.value = null
    }

    fun importProducts(newProducts: List<Product>, updatedProducts: List<ProductUpdate>, context: Context) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                val products = dao.getAll()
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

    fun addProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.insert(product)
                _uiState.value = UiState.Success("Prodotto aggiunto con successo.")
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Errore durante l'aggiunta del prodotto.")
            }
        }
    }

    fun updateProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.update(product)
                _uiState.value = UiState.Success("Prodotto aggiornato con successo.")
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Errore durante l'aggiornamento del prodotto.")
            }
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.delete(product)
                _uiState.value = UiState.Success("Prodotto eliminato con successo.")
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Errore durante l'eliminazione del prodotto.")
            }
        }
    }

    private fun writeProductsToExcel(context: Context, uri: Uri, products: List<Product>) {
        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Prodotti")
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "barcode", "itemNumber", "productName", "newPurchasePrice",
            "newRetailPrice", "oldPurchasePrice", "oldRetailPrice", "supplierId"
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
            row.createCell(7).setCellValue(product.supplierId?.toDouble() ?: 0.0)
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { workbook.write(it) }
            workbook.close()
        } catch (e: IOException) {
            e.printStackTrace()
            throw e
        }
    }

    fun analyzeGridData(gridData: List<Map<String, String>>) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentDbProducts = dao.getAll()
                val analysis = ImportAnalyzer.analyze(gridData, currentDbProducts, supplierDao)
                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error("Errore durante l'analisi dei dati: ${e.message}")
            }
        }
    }

    suspend fun addSupplier(name: String): Supplier? {
        if (name.isBlank()) return null
        val existing = supplierDao.findByName(name)
        if (existing != null) return existing

        val newSupplier = Supplier(name = name)
        return withContext(Dispatchers.IO) {
            supplierDao.insert(newSupplier)
            supplierDao.findByName(name)
        }
    }

    suspend fun getSupplierById(id: Long): Supplier? {
        return supplierDao.getById(id)
    }
}
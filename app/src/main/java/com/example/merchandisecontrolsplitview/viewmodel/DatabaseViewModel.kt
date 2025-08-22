package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.ImportAnalyzer
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class UiState {
    data object Idle : UiState()
    data class Loading(val message: String? = null, val progress: Int? = null) : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: InventoryRepository =
        DefaultInventoryRepository(AppDatabase.getDatabase(app))


    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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

    // --- FIX START ---
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


    fun setFilter(text: String) {
        _filter.value = text.ifBlank { null }
    }

    private val _importAnalysisResult = MutableStateFlow<ImportAnalysis?>(null)
    val importAnalysisResult: StateFlow<ImportAnalysis?> = _importAnalysisResult.asStateFlow()

    fun startImportAnalysis(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading(message = context.getString(R.string.import_loading_file), progress = 5)
        viewModelScope.launch {
            try {
                // 1) Lettura/parsing file (I/O)
                val (normalizedHeader, dataRows, _) = withContext(Dispatchers.IO) {
                    readAndAnalyzeExcel(context, uri)
                }

                if (normalizedHeader.isEmpty() || dataRows.isEmpty()) {
                    _uiState.value = UiState.Error(context.getString(R.string.error_file_empty_or_invalid))
                    return@launch
                }

                // 2) Preparazione righe (CPU)
                _uiState.value = UiState.Loading(message = context.getString(R.string.import_mapping_rows), progress = 30)
                val importedRowsAsMap = withContext(Dispatchers.Default) {
                    dataRows.map { row ->
                        normalizedHeader.mapIndexed { index, headerKey ->
                            headerKey to (row.getOrNull(index) ?: "")
                        }.toMap()
                    }
                }

                // 3) Letture DB (I/O)
                _uiState.value = UiState.Loading(message = context.getString(R.string.import_fetching_db), progress = 55)
                val currentDbProducts = withContext(Dispatchers.IO) {
                    repository.getAllProducts()
                }

                // 4) Analisi (I/O + un po’ di CPU)
                _uiState.value = UiState.Loading(message = context.getString(R.string.import_analyzing), progress = 85)
                val analysis = withContext(Dispatchers.IO) {
                    ImportAnalyzer.analyze(context, importedRowsAsMap, currentDbProducts, repository)
                }

                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMessage = e.message ?: context.getString(R.string.unknown)
                _uiState.value = UiState.Error(context.getString(R.string.error_data_analysis, errorMessage))
            }
        }
    }


    fun clearImportAnalysis() {
        _importAnalysisResult.value = null
    }

    fun importProducts(newProducts: List<Product>, updatedProducts: List<ProductUpdate>, context: Context) {
        _uiState.value = UiState.Loading(message = context.getString(R.string.import_applying_changes), progress = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Converti ProductUpdate -> Product con l'ID corretto
                    val updatesAsProducts: List<Product> = updatedProducts.map { pu ->
                        pu.newProduct.copy(id = pu.oldProduct.id)
                    }
                    repository.applyImport(newProducts, updatesAsProducts)
                }
                _uiState.value = UiState.Success(context.getString(R.string.import_success))
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(context.getString(R.string.import_error, errorMessage))
            }
        }
    }

    fun exportToExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch {
            try {
                val products = repository.getAllProductsWithDetails()
                if (products.isEmpty()) {
                    _uiState.value = UiState.Error(context.getString(R.string.error_no_products_to_export))
                    return@launch
                }
                // Scrittura file su dispatcher I/O
                withContext(Dispatchers.IO) {
                    writeProductsToExcel(context, uri, products)
                }
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: Exception) {
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(context.getString(R.string.export_error, errorMessage))
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

    private fun writeProductsToExcel(context: Context, uri: Uri, products: List<ProductWithDetails>) {
        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet(context.getString(R.string.sheet_name_products))

        val headerRow = sheet.createRow(0)
        val headers = listOf(
            context.getString(R.string.header_barcode),
            context.getString(R.string.header_item_number),
            context.getString(R.string.header_product_name),
            context.getString(R.string.header_second_product_name),
            context.getString(R.string.header_purchase_price),
            context.getString(R.string.header_retail_price),
            context.getString(R.string.product_purchase_price_old_short), // PrevPurchase
            context.getString(R.string.product_retail_price_old_short),
            context.getString(R.string.header_supplier),
            context.getString(R.string.header_category),
            context.getString(R.string.header_stock_quantity)
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }

        products.forEachIndexed { index, details ->
            val p = details.product
            val row = sheet.createRow(index + 1)

            row.createCell(0).setCellValue(p.barcode)
            row.createCell(1).setCellValue(p.itemNumber ?: "")
            row.createCell(2).setCellValue(p.productName ?: "")
            row.createCell(3).setCellValue(p.secondProductName ?: "")
            row.createCell(4).setCellValue(p.purchasePrice ?: 0.0)          // prezzo corrente
            row.createCell(5).setCellValue(p.retailPrice ?: 0.0)
            row.createCell(6).setCellValue(details.prevPurchase ?: 0.0)
            row.createCell(7).setCellValue(details.prevRetail ?: 0.0)// prezzo corrente
            row.createCell(8).setCellValue(details.supplierName ?: "")
            row.createCell(9).setCellValue(details.categoryName ?: "")
            row.createCell(10).setCellValue(p.stockQuantity ?: 0.0)
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
        _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 10)
        viewModelScope.launch {
            try {
                val currentDbProducts = withContext(Dispatchers.IO) {
                    _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_fetching_db), progress = 30)
                    repository.getAllProducts()
                }
                _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 70)
                val analysis = withContext(Dispatchers.Default) {
                    ImportAnalyzer.analyze(appContext, gridData, currentDbProducts, repository)
                }
                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                val msg = e.message ?: appContext.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(appContext.getString(R.string.error_data_analysis, msg))
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
}
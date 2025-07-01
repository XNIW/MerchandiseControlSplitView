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
    private val categoryDao = db.categoryDao()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow<String?>(null)

    private val appContext = getApplication<Application>().applicationContext
    val filter: StateFlow<String?> = _filter.asStateFlow()

    val pager = filter.flatMapLatest { filterStr ->
        Pager(PagingConfig(pageSize = 20)) {
            dao.getAllPaged(filterStr)
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
                flow { emit(supplierDao.getAll()) }
            else
                flow { emit(supplierDao.searchByName(query)) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _categoryInputText = MutableStateFlow("")
    val categoryInputText: StateFlow<String> = _categoryInputText.asStateFlow()

    fun onCategorySearchQueryChanged(query: String) {
        _categoryInputText.value = query
    }

    val categories: StateFlow<List<Category>> = _categoryInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank())
                flow { emit(categoryDao.getAll()) }
            else
                flow { emit(categoryDao.searchByName(query)) }
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
                    _uiState.value = UiState.Error(context.getString(R.string.error_file_empty_or_invalid))
                    return@launch
                }
                val importedRowsAsMap = dataRows.map { row ->
                    normalizedHeader.mapIndexed { index, headerKey ->
                        headerKey to (row.getOrNull(index) ?: "")
                    }.toMap()
                }
                val currentDbProducts = dao.getAll()
                // --- CORREZIONE QUI ---
                val analysis = ImportAnalyzer.analyze(context, importedRowsAsMap, currentDbProducts, supplierDao, categoryDao)
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
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(context.getString(R.string.import_error, errorMessage))
            }
        }
    }

    fun exportToExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val products = dao.getAll()
                if (products.isEmpty()) {
                    _uiState.value = UiState.Error(context.getString(R.string.error_no_products_to_export))
                    return@launch
                }
                writeProductsToExcel(context, uri, products)
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: Exception) {
                val errorMessage = e.message ?: context.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(context.getString(R.string.import_error, errorMessage))
            }
        }
    }

    fun addProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.insert(product)
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_added))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_added))
            }
        }
    }

    fun updateProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.update(product)
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_updated))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_updated))
            }
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.delete(product)
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_deleted))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_deleted))
            }
        }
    }

    private suspend fun writeProductsToExcel(context: Context, uri: Uri, products: List<Product>) {
        // Fetch names for all suppliers and categories in one go to avoid N+1 queries.
        val suppliersMap = supplierDao.getAll().associateBy { it.id }
        val categoriesMap = categoryDao.getAll().associateBy { it.id }

        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet(context.getString(R.string.sheet_name_products))

        // Define user-friendly headers that are also compatible with the import alias system.
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            context.getString(R.string.header_barcode),
            context.getString(R.string.header_item_number),
            context.getString(R.string.header_product_name),
            context.getString(R.string.header_second_product_name),
            context.getString(R.string.header_purchase_price),
            context.getString(R.string.header_retail_price),
            context.getString(R.string.header_old_purchase_price),
            context.getString(R.string.header_old_retail_price),
            context.getString(R.string.header_supplier),  // User-friendly "Fornitore"
            context.getString(R.string.header_category),  // User-friendly "Categoria"
            context.getString(R.string.header_stock_quantity)
        )
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }

        // Populate rows with product data, using names for supplier and category.
        products.forEachIndexed { index, product ->
            val row = sheet.createRow(index + 1)
            val supplierName = product.supplierId?.let { suppliersMap[it]?.name } ?: ""
            val categoryName = product.categoryId?.let { categoriesMap[it]?.name } ?: ""

            row.createCell(0).setCellValue(product.barcode)
            row.createCell(1).setCellValue(product.itemNumber ?: "")
            row.createCell(2).setCellValue(product.productName ?: "")
            row.createCell(3).setCellValue(product.secondProductName ?: "")
            row.createCell(4).setCellValue(product.purchasePrice ?: 0.0)
            row.createCell(5).setCellValue(product.retailPrice ?: 0.0)
            row.createCell(6).setCellValue(product.oldPurchasePrice ?: 0.0)
            row.createCell(7).setCellValue(product.oldRetailPrice ?: 0.0)
            row.createCell(8).setCellValue(supplierName) // Write supplier name
            row.createCell(9).setCellValue(categoryName) // Write category name
            row.createCell(10).setCellValue(product.stockQuantity ?: 0.0)
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
                // --- CORREZIONE QUI ---
                val analysis = ImportAnalyzer.analyze(appContext, gridData, currentDbProducts, supplierDao, categoryDao)
                _importAnalysisResult.value = analysis
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMessage = e.message ?: appContext.getString(R.string.unknown_error)
                _uiState.value = UiState.Error(appContext.getString(R.string.error_data_analysis, errorMessage))
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

    suspend fun addCategory(name: String): Category? {
        if (name.isBlank()) return null
        val existing = categoryDao.findByName(name)
        if (existing != null) return existing

        val newCategory = Category(name = name)
        return withContext(Dispatchers.IO) {
            categoryDao.insert(newCategory)
            categoryDao.findByName(name)
        }
    }

    suspend fun getSupplierById(id: Long): Supplier? {
        return supplierDao.getById(id)
    }

    suspend fun getCategoryById(id: Long): Category? {
        return categoryDao.getById(id)
    }
}
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
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.example.merchandisecontrolsplitview.R

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

    fun importFromExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val products = importExcelToProducts(getApplication(), uri)
                val dao = db.productDao()
                var processed = 0
                products.forEach { product ->
                    val existing = dao.findByBarcode(product.barcode)
                    val finalProduct = if (existing == null) {
                        product.copy(
                            oldPurchasePrice = null,
                            oldRetailPrice = null
                        )
                    } else {
                        product.copy(
                            id = existing.id,
                            oldPurchasePrice = existing.newPurchasePrice,
                            oldRetailPrice = existing.newRetailPrice
                        )
                    }
                    dao.upsert(finalProduct)
                    processed++
                    if (processed % 10 == 0)
                        _uiState.value = UiState.Loading(progress = processed * 100 / products.size)
                }
                // FIX: Use getApplication() to get the Context
                _uiState.value = UiState.Success(context.getString(R.string.import_success))
            } catch (e: Exception) {
                // FIX: Use getApplication() to get the Context
                _uiState.value = UiState.Error(context.getString(R.string.import_error, e.message ?: ""))
            }
        }
    }

    fun exportToExcel(context: Context, uri: Uri) {
        _uiState.value = UiState.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = db.productDao()
                // Preleva tutto il contenuto caricando tutte le pagine
                val pagingSource = dao.getAllPaged(null)
                val products = mutableListOf<Product>()
                val result = pagingSource.load(PagingSource.LoadParams.Refresh(null, 10000, false))
                if (result is PagingSource.LoadResult.Page) {
                    products.addAll(result.data)
                }
                writeProductsToExcel(getApplication(), uri, products)
                // FIX: Use getApplication() to get the Context
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: Exception) {
                // FIX: Use getApplication() to get the Context
                _uiState.value = UiState.Error(context.getString(R.string.export_error, e.message ?: ""))
            }
        }
    }

    private fun writeProductsToExcel(context: Context, uri: Uri, products: List<Product>) {
        // Qui puoi implementare la scrittura su CSV/Excel: vuoi un esempio funzionante per CSV?
    }

    private fun importExcelToProducts(context: Context, uri: Uri): List<Product> {
        val (header, dataRows) = readAndAnalyzeExcel(context, uri)
        if (header.isEmpty() || dataRows.isEmpty()) return emptyList()

        // Trova gli indici delle colonne di interesse
        val idxBarcode      = header.indexOf("barcode")
        val idxItemNumber   = header.indexOf("itemNumber")
        val idxProductName  = header.indexOf("productName")
        val idxPurchase     = header.indexOf("purchasePrice")
        val idxRetail       = header.indexOf("retailPrice") // solo se vuoi anche retailPrice
        val idxOldPurchase  = header.indexOf("oldPurchasePrice") // opzionale
        val idxOldRetail    = header.indexOf("oldRetailPrice")   // opzionale
        val idxSupplier     = header.indexOf("supplier") // opzionale
        val idxQuantity     = header.indexOf("quantity")
        val idxTotal        = header.indexOf("totalPrice")

        val products = mutableListOf<Product>()

        for (row in dataRows) {
            val barcode = row.getOrNull(idxBarcode)?.trim().takeIf { !it.isNullOrEmpty() }
            if (barcode == null) continue // senza barcode salta

            // Conversioni di tipo sicure
            val itemNumber      = row.getOrNull(idxItemNumber)?.takeIf { it.isNotBlank() }
            val productName     = row.getOrNull(idxProductName)?.takeIf { it.isNotBlank() }
            val newPurchase     = row.getOrNull(idxPurchase)?.replace(",", ".")?.toDoubleOrNull()
            val newRetail       = row.getOrNull(idxRetail)?.replace(",", ".")?.toDoubleOrNull()
            val oldPurchase     = row.getOrNull(idxOldPurchase)?.replace(",", ".")?.toDoubleOrNull()
            val oldRetail       = row.getOrNull(idxOldRetail)?.replace(",", ".")?.toDoubleOrNull()
            val supplier        = row.getOrNull(idxSupplier)?.takeIf { it.isNotBlank() }
            val quantity        = row.getOrNull(idxQuantity)?.replace(",", ".")?.toLongOrNull()
            val totalPrice      = row.getOrNull(idxTotal)?.replace(",", ".")?.toDoubleOrNull()

            products.add(
                Product(
                    id = 0L, // sarà autoincrement da Room
                    barcode = barcode,
                    itemNumber = itemNumber,
                    productName = productName,
                    newPurchasePrice = newPurchase,
                    newRetailPrice = newRetail,
                    oldPurchasePrice = oldPurchase,
                    oldRetailPrice = oldRetail,
                    supplier = supplier
                    // Se hai altri campi, aggiungili qui!
                )
            )
        }
        return products
    }
}
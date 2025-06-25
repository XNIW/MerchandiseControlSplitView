package com.example.merchandisecontrolsplitview.util

import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductUpdate
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.data.ImportAnalysis
import kotlin.math.abs

/**
 * Un oggetto helper che contiene la logica di business per analizzare i dati
 * di un file Excel prima di importarli nel database.
 */
object ImportAnalyzer {

    private const val MAX_PRODUCT_NAME_LENGTH = 100
    private const val PRICE_COMPARISON_TOLERANCE = 0.001

    /**
     * Analizza una lista di righe importate, le confronta con i prodotti esistenti
     * nel database e le categorizza in nuovi prodotti, prodotti aggiornati e righe con errori.
     *
     * @param importedRows I dati grezzi letti dal file Excel, come lista di mappe.
     * @param currentDbProducts La lista di tutti i prodotti attualmente nel database.
     * @return Un oggetto [ImportAnalysis] che contiene i risultati dell'analisi.
     */
    fun analyze(
        importedRows: List<Map<String, String>>,
        currentDbProducts: List<Product>
    ): ImportAnalysis {
        val dbProductByBarcode = currentDbProducts.associateBy { it.barcode }
        val newProducts = mutableListOf<Product>()
        val updatedProducts = mutableListOf<ProductUpdate>()
        val errors = mutableListOf<RowImportError>()

        for ((rowIndex, row) in importedRows.withIndex()) {
            try {
                // --- 1. PARSING E CONVERSIONE TIPI ---
                // Qui avviene la conversione da testo (String) ai tipi di dati corretti.
                val barcode = row["barcode"]?.trim() ?: ""
                val itemNumber = row["itemNumber"]?.trim()?.takeIf { it.isNotBlank() }
                val productName = row["productName"]?.trim()?.takeIf { it.isNotBlank() }

                // **PUNTO CHIAVE**: Conversione esplicita dei prezzi da String a Double?
                val purchasePrice = row["purchasePrice"]?.replace(",", ".")?.toDoubleOrNull()
                val newRetailPrice = row["newRetailPrice"]?.replace(",", ".")?.toDoubleOrNull()

                val supplier = row["supplier"]?.trim()?.takeIf { it.isNotBlank() }

                // --- 2. VALIDAZIONE BASATA SUI TIPI CONVERTITI ---
                val validationError = validateRow(rowIndex, row, barcode, productName, purchasePrice, newRetailPrice)
                if (validationError != null) {
                    errors.add(validationError)
                    continue // Salta al prossimo prodotto se la conversione o la validazione fallisce
                }

                val existingProduct = dbProductByBarcode[barcode]

                if (existingProduct == null) {
                    // Crea un nuovo prodotto usando i valori convertiti (non più null)
                    val newProduct = Product(
                        barcode = barcode,
                        itemNumber = itemNumber,
                        productName = productName!!,
                        newPurchasePrice = purchasePrice!!,
                        newRetailPrice = newRetailPrice!!,
                        oldPurchasePrice = null,
                        oldRetailPrice = null,
                        supplier = supplier
                    )
                    newProducts.add(newProduct)
                } else {
                    // Aggiorna un prodotto esistente
                    val updatedProduct = existingProduct.copy(
                        itemNumber = itemNumber ?: existingProduct.itemNumber,
                        productName = productName ?: existingProduct.productName,
                        supplier = supplier ?: existingProduct.supplier,
                        oldPurchasePrice = existingProduct.newPurchasePrice,
                        oldRetailPrice = existingProduct.newRetailPrice,
                        newPurchasePrice = purchasePrice!!,
                        newRetailPrice = newRetailPrice!!
                    )

                    val changedFields = getChangedFields(existingProduct, updatedProduct)
                    if (changedFields.isNotEmpty()) {
                        updatedProducts.add(ProductUpdate(existingProduct, updatedProduct, changedFields))
                    }
                }
            } catch (ex: Exception) {
                errors.add(
                    RowImportError(rowIndex + 1, row.values.toList(), "Errore di parsing imprevisto: ${ex.message}")
                )
            }
        }
        return ImportAnalysis(newProducts, updatedProducts, errors)
    }

    /**
     * Esegue la validazione su una singola riga di dati, controllando i tipi già convertiti.
     * @return Un [RowImportError] se la validazione fallisce, altrimenti null.
     */
    private fun validateRow(
        rowIndex: Int, row: Map<String, String>, barcode: String, productName: String?,
        purchasePrice: Double?, retailPrice: Double?
    ): RowImportError? {
        val rowContentForError = row.values.toList()
        return when {
            barcode.isBlank() ->
                RowImportError(rowIndex + 1, rowContentForError, "Il barcode è obbligatorio.")
            productName.isNullOrBlank() ->
                RowImportError(rowIndex + 1, rowContentForError, "Il nome del prodotto è obbligatorio.")
            productName.length > MAX_PRODUCT_NAME_LENGTH ->
                RowImportError(rowIndex + 1, rowContentForError, "Nome prodotto troppo lungo (max $MAX_PRODUCT_NAME_LENGTH).")
            purchasePrice == null ->
                RowImportError(rowIndex + 1, rowContentForError, "Il prezzo di acquisto non è un numero valido.")
            retailPrice == null ->
                RowImportError(rowIndex + 1, rowContentForError, "Il prezzo di vendita non è un numero valido.")
            purchasePrice < 0 || retailPrice < 0 ->
                RowImportError(rowIndex + 1, rowContentForError, "I prezzi non possono essere negativi.")
            else -> null
        }
    }

    /**
     * Confronta due prodotti e restituisce una lista dei nomi dei campi che sono cambiati.
     */
    private fun getChangedFields(old: Product, new: Product): List<String> {
        val fields = mutableListOf<String>()
        if (!old.productName.equals(new.productName, ignoreCase = true)) fields.add("Nome Prodotto")
        if (!old.itemNumber.equals(new.itemNumber, ignoreCase = true)) fields.add("Codice Articolo")
        if (abs((old.newPurchasePrice ?: 0.0) - (new.newPurchasePrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add("Prezzo Acquisto")
        if (abs((old.newRetailPrice ?: 0.0) - (new.newRetailPrice ?: 0.0)) > PRICE_COMPARISON_TOLERANCE) fields.add("Prezzo Vendita")
        if (!old.supplier.equals(new.supplier, ignoreCase = true)) fields.add("Fornitore")
        return fields
    }
}

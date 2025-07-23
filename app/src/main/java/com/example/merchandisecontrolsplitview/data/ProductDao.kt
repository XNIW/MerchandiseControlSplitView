package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ProductDao {

    /**
     * Recupera una lista paginata di prodotti, filtrabile per barcode, nome, fornitore o codice articolo.
     * @param filter La stringa di ricerca. Se null, restituisce tutti i prodotti.
     * @return Un PagingSource per l'integrazione con la libreria Paging 3.
     */
    // --- QUERY MODIFICATA E CORRETTA ---
    @Query("""
        SELECT products.* FROM products
        LEFT JOIN suppliers ON products.supplierId = suppliers.id
        WHERE (:filter IS NULL OR 
               products.barcode LIKE '%' || :filter || '%' OR 
               products.productName LIKE '%' || :filter || '%' OR 
               suppliers.name LIKE '%' || :filter || '%' OR
               products.itemNumber LIKE '%' || :filter || '%')
        ORDER BY products.id ASC
    """)
    fun getAllPaged(filter: String?): PagingSource<Int, Product>

    /**
     * Inserisce una lista di prodotti. Se un prodotto con lo stesso barcode esiste già,
     * viene sostituito (logica "upsert").
     * @param products La lista di prodotti da inserire/aggiornare.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: Product)

    @Update
    suspend fun update(product: Product)

    @Delete
    suspend fun delete(product: Product)

    /**
     * Aggiorna una lista di prodotti esistenti.
     * @param products La lista di prodotti da aggiornare.
     */
    @Update
    suspend fun updateAll(products: List<Product>)

    /**
     * Trova un prodotto specifico tramite il suo barcode.
     * @param barcode Il barcode univoco del prodotto da cercare.
     * @return Il prodotto trovato, o null se non esiste.
     */
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?

    /**
     * Recupera tutti i prodotti presenti nel database.
     * Utile per operazioni di confronto in memoria, come l'analisi pre-importazione.
     * @return Una lista di tutti i prodotti.
     */
    @Query("SELECT * FROM products")
    suspend fun getAll(): List<Product>

    /**
     * Elimina tutti i prodotti dalla tabella.
     * Utile per operazioni di reset o test.
     */
    @Query("DELETE FROM products")
    suspend fun deleteAll()

    /**
     * NUOVA FUNZIONE: Esegue una sostituzione completa dei dati in un'unica transazione.
     * Prima cancella tutti i prodotti esistenti e poi inserisce la nuova lista.
     * Questo garantisce che l'operazione sia atomica: o va a buon fine interamente, o non fa nulla.
     * @param products La nuova lista di prodotti che sostituirà completamente i dati esistenti.
     */
    @Transaction
    suspend fun replaceAll(products: List<Product>) {
        deleteAll()
        insertAll(products)
    }

    /**
    * Recupera una lista di prodotti basata su una lista di barcode.
    * Ottimizzato per evitare il problema N+1 query.
    * @param barcodes La lista di barcode da cercare.
    * @return Una lista di prodotti che corrispondono ai barcode forniti.
    */
    @Query("SELECT * FROM products WHERE barcode IN (:barcodes)")
    suspend fun findByBarcodes(barcodes: List<String>): List<Product>
}
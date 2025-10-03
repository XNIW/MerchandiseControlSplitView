package com.example.merchandisecontrolsplitview.data.remote

import com.example.merchandisecontrolsplitview.data.*
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import com.google.gson.Gson

class CloudStore(userId: String) {
    private val root = Firebase.firestore.collection("users").document(userId)
    private val products = root.collection("products")
    private val suppliers = root.collection("suppliers")
    private val categories = root.collection("categories")
    private val priceHistory = root.collection("priceHistory")
    private val historyEntries = root.collection("historyEntries")
    private val gson = Gson()

    // ---------- Products ----------
    suspend fun upsertProduct(p: Product) {
        products.document(p.barcode).set(p, SetOptions.merge()).await()
    }
    suspend fun deleteProduct(p: Product) {
        products.document(p.barcode).delete().await()
    }

    // ---------- Suppliers ----------
    suspend fun upsertSupplier(s: Supplier) {
        suppliers.document(s.name).set(s, SetOptions.merge()).await()
    }

    // ---------- Categories ----------
    suspend fun upsertCategory(c: Category) {
        categories.document(c.name).set(c, SetOptions.merge()).await()
    }

    // ---------- Price History ----------
    suspend fun upsertPriceRow(
        barcode: String,
        type: String,           // "PURCHASE" | "RETAIL"
        effectiveAt: String,    // "yyyy-MM-dd HH:mm:ss"
        price: Double,
        source: String?,        // "MANUAL" | "IMPORT" | "IMPORT_PREV" | ...
        createdAt: String       // in genere uguale a effectiveAt
    ) {
        val id = "${barcode}_${type}_$effectiveAt"
        val doc = mapOf(
            "barcode" to barcode,
            "type" to type,
            "effectiveAt" to effectiveAt,
            "price" to price,
            "source" to source,
            "createdAt" to createdAt
        )
        priceHistory.document(id).set(doc, SetOptions.merge()).await()
    }

    suspend fun deletePricesOfProduct(barcode: String) {
        val snap = priceHistory.whereEqualTo("barcode", barcode).get().await()
        if (!snap.isEmpty) {
            val batch = Firebase.firestore.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    // ---------- HistoryEntry (FULL) ----------
    // Nota: non salviamo il campo 'uid' (chiave locale autoincrement)
    private fun historyToMap(e: HistoryEntry): Map<String, Any?> = mapOf(
        "id" to e.id,
        "timestamp" to e.timestamp,
        // 🔁 niente nested arrays: salviamo come stringhe JSON
        "dataJson" to gson.toJson(e.data),
        "editableJson" to gson.toJson(e.editable),
        // questi vanno bene così
        "complete" to e.complete,
        "supplier" to e.supplier,
        "category" to e.category,
        "wasExported" to e.wasExported,
        "syncStatus" to e.syncStatus.toString(),
        "orderTotal" to e.orderTotal,
        "paymentTotal" to e.paymentTotal,
        "missingItems" to e.missingItems,
        "totalItems" to e.totalItems,
        "isManualEntry" to e.isManualEntry
    )

    suspend fun upsertHistoryEntry(e: HistoryEntry) {
        historyEntries.document(e.id).set(historyToMap(e), SetOptions.merge()).await()
    }

    suspend fun deleteHistoryEntryById(id: String) {
        historyEntries.document(id).delete().await()
    }
}

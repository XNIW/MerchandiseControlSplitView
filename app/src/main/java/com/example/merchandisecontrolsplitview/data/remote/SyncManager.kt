package com.example.merchandisecontrolsplitview.data.remote

import com.example.merchandisecontrolsplitview.data.*
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SyncManager(
    private val db: AppDatabase,
    userId: String,
    private val scope: CoroutineScope
) {
    private val root = Firebase.firestore.collection("users").document(userId)
    private val gson = Gson()
    private val gridType = object : TypeToken<List<List<String>>>() {}.type

    private var stopFns = mutableListOf<() -> Unit>()

    fun start() {
        // LOG: avvio sync e aggancio listener
        Log.i("Sync", "start(): attaching listeners for uid=${root.id}")
        listenCollection("suppliers") { doc ->
            val s = doc.toObject(Supplier::class.java) ?: return@listenCollection
            scope.launch(Dispatchers.IO) {
                try { db.supplierDao().insert(s) } catch (_: Exception) { /* se esiste già, ok */ }
            }
        }
        listenCollection("categories") { doc ->
            val c = doc.toObject(Category::class.java) ?: return@listenCollection
            scope.launch(Dispatchers.IO) {
                try { db.categoryDao().insert(c) } catch (_: Exception) { /* se esiste già, ok */ }
            }
        }
        listenCollection("products") { doc ->
            val p = doc.toObject(Product::class.java) ?: return@listenCollection
            scope.launch(Dispatchers.IO) {
                // Inserisci/aggiorna senza crashare su duplicato
                try { db.productDao().insert(p) } catch (_: Exception) { db.productDao().update(p) }
            }
        }
        // ---------- priceHistory ----------
        listenCollection("priceHistory") { doc ->
            val barcode = doc.getString("barcode") ?: return@listenCollection
            val type = doc.getString("type") ?: return@listenCollection
            val effectiveAt = doc.getString("effectiveAt") ?: return@listenCollection
            val price = doc.getDouble("price") ?: return@listenCollection
            val source = doc.getString("source")

            scope.launch(Dispatchers.IO) {
                val p = db.productDao().findByBarcode(barcode) ?: return@launch
                db.productPriceDao().insertIfChanged(p.id, type, price, effectiveAt, source)
            }
        }

        // ---------- historyEntries (full) ----------
        listenCollection("historyEntries") { doc ->
            val id = doc.id
            val timestamp = doc.getString("timestamp") ?: return@listenCollection

            // ✅ preferisci JSON, fallback a eventuale campo legacy
            val data: List<List<String>> =
                doc.getString("dataJson")?.let { gson.fromJson(it, gridType) }
                    ?: (doc.get("data") as? List<List<String>>) ?: emptyList()

            val editable: List<List<String>> =
                doc.getString("editableJson")?.let { gson.fromJson(it, gridType) }
                    ?: (doc.get("editable") as? List<List<String>>) ?: emptyList()

            val complete = (doc.get("complete") as? List<Boolean>) ?: emptyList()
            val supplier = doc.getString("supplier") ?: ""
            val category = doc.getString("category") ?: ""
            val wasExported = doc.getBoolean("wasExported") ?: false
            val syncStatusStr = doc.getString("syncStatus") ?: "NOT_ATTEMPTED"
            val orderTotal = doc.getDouble("orderTotal") ?: 0.0
            val paymentTotal = doc.getDouble("paymentTotal") ?: 0.0
            val missingItems = (doc.getLong("missingItems") ?: 0L).toInt()
            val totalItems = (doc.getLong("totalItems") ?: 0L).toInt()
            val isManualEntry = doc.getBoolean("isManualEntry") ?: false

            scope.launch(Dispatchers.IO) {
                val existing = db.historyEntryDao().getById(id)
                val status = try { SyncStatus.valueOf(syncStatusStr) } catch (_: Exception) { SyncStatus.NOT_ATTEMPTED }
                val local = HistoryEntry(
                    uid = existing?.uid ?: 0L, // preserva la riga locale se esiste
                    id = id,
                    timestamp = timestamp,
                    data = data,
                    editable = editable,
                    complete = complete,
                    supplier = supplier,
                    category = category,
                    wasExported = wasExported,
                    syncStatus = status,
                    orderTotal = orderTotal,
                    paymentTotal = paymentTotal,
                    missingItems = missingItems,
                    totalItems = totalItems,
                    isManualEntry = isManualEntry
                )
                if (existing == null) db.historyEntryDao().insert(local) else db.historyEntryDao().update(local)
            }
        }
    }

    private inline fun listenCollection(
        name: String,
        crossinline onAddOrMod: (DocumentSnapshot) -> Unit
    ) {
        // LOG: registrazione listener per la collezione
        Log.d("Sync", "listen '$name': attaching snapshot listener")

        val reg = root.collection(name).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e("Sync", "listen '$name': error", err)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            // LOG: metadati della snapshot (utile per capire cache/pending writes)
            Log.d(
                "Sync",
                "listen '$name': size=${snap.size()} " +
                        "fromCache=${snap.metadata.isFromCache} " +
                        "hasPending=${snap.metadata.hasPendingWrites()} " +
                        "changes=${snap.documentChanges.size}"
            )

            for (dc in snap.documentChanges) {
                // Evita echo locali (modifiche appena fatte da questo device)
                if (dc.document.metadata.hasPendingWrites()) continue

                // LOG: dettaglio di ogni cambiamento
                Log.v(
                    "Sync",
                    "listen '$name': ${dc.type} id=${dc.document.id}"
                )
                when (dc.type) {
                    DocumentChange.Type.ADDED,
                    DocumentChange.Type.MODIFIED -> onAddOrMod(dc.document)

                    DocumentChange.Type.REMOVED -> {
                        scope.launch(Dispatchers.IO) {
                            when (name) {
                                "products" -> {
                                    // DocId = barcode
                                    db.productDao().deleteByBarcode(dc.document.id)
                                }
                                "suppliers" -> {
                                    // DocId = name
                                    db.supplierDao().deleteByName(dc.document.id)
                                }
                                "categories" -> {
                                    // DocId = name
                                    db.categoryDao().deleteByName(dc.document.id)
                                }
                                "priceHistory" -> {
                                    // opzionale: potresti aggiungere un DAO per cancellare quella riga
                                    // in base a (barcode,type,effectiveAt). Se non lo hai, puoi ignorare.
                                }
                                "historyEntries" -> {
                                    val existing = db.historyEntryDao().getById(dc.document.id)
                                    if (existing != null) db.historyEntryDao().delete(existing)
                                }
                            }
                        }
                    }
                }
            }
        }
        stopFns += { reg.remove() }
    }

    fun stop() {
        Log.i("Sync", "stop(): removing ${stopFns.size} listeners")
        stopFns.forEach { it() }; stopFns.clear() }
}
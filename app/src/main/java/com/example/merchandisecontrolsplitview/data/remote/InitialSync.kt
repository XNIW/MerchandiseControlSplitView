package com.example.merchandisecontrolsplitview.data.remote

import com.example.merchandisecontrolsplitview.data.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

suspend fun runInitialSync(db: AppDatabase, uid: String) {
    val root = Firebase.firestore.collection("users").document(uid)
    val gson = Gson()
    val gridType = object : TypeToken<List<List<String>>>() {}.type

    val hasCloudData = root.collection("products").limit(1).get().await().size() > 0

    if (!hasCloudData) {
        // ──> Push LOCALE → CLOUD (primo upload)
        val products = db.productDao().getAll()
        val suppliers = db.supplierDao().getAll()
        val categories = db.categoryDao().getAll()

        // prodotti / fornitori / categorie
        products.forEach { root.collection("products").document(it.barcode).set(it).await() }
        suppliers.forEach { root.collection("suppliers").document(it.name).set(it).await() }
        categories.forEach { root.collection("categories").document(it.name).set(it).await() }

        // priceHistory (usa la query già presente nel DAO)
        db.productPriceDao().getAllWithBarcode().forEach { r ->
            val id = "${r.barcode}_${r.type}_${r.effectiveAt}"
            val doc = mapOf(
                "barcode" to r.barcode,
                "type" to r.type,
                "effectiveAt" to r.effectiveAt,
                "price" to r.price,
                "source" to r.source,
                "createdAt" to r.effectiveAt
            )
            root.collection("priceHistory").document(id).set(doc).await()
        }

        // historyEntries (full)
        db.historyEntryDao().getAll().forEach { e ->
            val doc = mapOf(
                "id" to e.id,
                "timestamp" to e.timestamp,
                "dataJson" to gson.toJson(e.data),
                "editableJson" to gson.toJson(e.editable),
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
            root.collection("historyEntries").document(e.id).set(doc).await()
        }
    } else {
        // ──> Pull CLOUD → LOCALE (bootstrap)
        val supDocs = root.collection("suppliers").get().await().documents
        val catDocs = root.collection("categories").get().await().documents
        val prodDocs = root.collection("products").get().await().documents
        val priceDocs = root.collection("priceHistory").get().await().documents
        val histDocs = root.collection("historyEntries").get().await().documents

        withContext(Dispatchers.IO) {
            supDocs.forEach { doc ->
                try { db.supplierDao().insert(Supplier(name = doc.id)) } catch (_: Exception) {}
            }
            catDocs.forEach { doc ->
                try { db.categoryDao().insert(Category(name = doc.id)) } catch (_: Exception) {}
            }
            prodDocs.mapNotNull { it.toObject(Product::class.java) }.forEach { p ->
                try { db.productDao().insert(p) } catch (_: Exception) { db.productDao().update(p) }
            }
            // priceHistory
            val byBarcode = db.productDao().getAll().associateBy { it.barcode }
            priceDocs.forEach { d ->
                val b = d.getString("barcode") ?: return@forEach
                val p = byBarcode[b] ?: return@forEach
                val type = d.getString("type") ?: return@forEach
                val eff = d.getString("effectiveAt") ?: return@forEach
                val price = d.getDouble("price") ?: return@forEach
                val src = d.getString("source")
                db.productPriceDao().insertIfChanged(p.id, type, price, eff, src)
            }
            // historyEntries
            histDocs.forEach { doc ->
                val id = doc.id
                val timestamp = doc.getString("timestamp") ?: return@forEach

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
                val status = try { SyncStatus.valueOf(syncStatusStr) } catch (_: Exception) { SyncStatus.NOT_ATTEMPTED }

                val existing = db.historyEntryDao().getById(id)
                val local = HistoryEntry(
                    uid = existing?.uid ?: 0L,
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
}

package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.example.merchandisecontrolsplitview.data.auth.AuthManager
import com.example.merchandisecontrolsplitview.data.remote.CloudStore

class PriceBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val productDao = db.productDao()
        val priceDao = db.productPriceDao()

        val nowStr = LocalDateTime.now().format(fmt)

        // Se l'utente è loggato, specchia anche su Firestore
        val cloud: CloudStore? = AuthManager.currentUid()?.let { uid -> CloudStore(uid) }

        // Se il prodotto ha già QUALCHE prezzo in locale, lo saltiamo (idempotente)
        val already = priceDao.getProductIdsWithAnyPrice().toSet()
        val products = productDao.getAll()

        for (p in products) {
            if (p.id in already) continue

            p.purchasePrice?.let { price ->
                // Locale
                priceDao.insertIfChanged(p.id, "PURCHASE", price, nowStr, "BACKFILL_CURR")
                // Cloud (idempotente: usa barcode+type+effectiveAt come docId)
                cloud?.upsertPriceRow(
                    barcode = p.barcode,
                    type = "PURCHASE",
                    effectiveAt = nowStr,
                    price = price,
                    source = "BACKFILL_CURR",
                    createdAt = nowStr
                )
            }

            p.retailPrice?.let { price ->
                // Locale
                priceDao.insertIfChanged(p.id, "RETAIL", price, nowStr, "BACKFILL_CURR")
                // Cloud
                cloud?.upsertPriceRow(
                    barcode = p.barcode,
                    type = "RETAIL",
                    effectiveAt = nowStr,
                    price = price,
                    source = "BACKFILL_CURR",
                    createdAt = nowStr
                )
            }
        }

        return Result.success(
            Data.Builder()
                .putInt("backfilled_products", products.size)
                .build()
        )
    }
}
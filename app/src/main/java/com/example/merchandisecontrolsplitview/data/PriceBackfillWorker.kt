package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PriceBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override suspend fun doWork(): Result {
        // ✳️ QUI: usa AppDatabase.getDatabase(..), non "getInstance"
        val db = AppDatabase.getDatabase(applicationContext)
        val productDao = db.productDao()
        val priceDao = db.productPriceDao()

        val now = LocalDateTime.now()
        val nowStr = fmt.format(now)

        // Evita di backfillare prodotti che hanno già almeno un prezzo in history
        val already = priceDao.getProductIdsWithAnyPrice().toSet()

        val products = productDao.getAll()
        for (p in products) {
            if (p.id in already) continue

            p.purchasePrice?.let { price ->
                priceDao.insertIfChanged(p.id, "PURCHASE", price, nowStr, "BACKFILL_CURR")
            }
            p.retailPrice?.let { price ->
                priceDao.insertIfChanged(p.id, "RETAIL", price, nowStr, "BACKFILL_CURR")
            }
        }

        return Result.success(
            Data.Builder()
                .putInt("backfilled_products", products.size)
                .build()
        )
    }
}

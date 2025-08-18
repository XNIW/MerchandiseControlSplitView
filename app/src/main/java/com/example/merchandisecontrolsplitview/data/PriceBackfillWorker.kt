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
        val db = AppDatabase.getDatabase(applicationContext)
        val productDao = db.productDao()
        val priceDao = db.productPriceDao()

        // se lo storico c'è già per un prodotto, salta
        val already: Set<Long> = priceDao.getProductIdsWithAnyPrice().toSet()

        val nowStr = LocalDateTime.now().format(fmt)

        val products = productDao.getAll()
        for (p in products) {
            if (p.id in already) continue

            p.purchasePrice?.let {
                priceDao.insertIfChanged(p.id, "PURCHASE", it, nowStr, "BACKFILL_CURR")
            }
            p.retailPrice?.let {
                priceDao.insertIfChanged(p.id, "RETAIL", it, nowStr, "BACKFILL_CURR")
            }
        }
        return Result.success(Data.Builder().putInt("backfilled_products", products.size).build())
    }
}

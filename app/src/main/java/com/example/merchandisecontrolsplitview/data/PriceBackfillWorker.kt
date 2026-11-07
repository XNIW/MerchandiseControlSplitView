package com.example.merchandisecontrolsplitview.data

import android.content.Context
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.LocalStoreAvailability
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class PriceBackfillPlan(
    val eligibleProductCount: Int,
    val points: List<ProductPrice>
)

internal fun planPriceBackfill(
    products: List<Product>,
    productIdsWithHistory: Set<Long>,
    cloudLinkedProductIds: Set<Long>,
    effectiveAt: String
): PriceBackfillPlan {
    val eligibleProducts = products.filter { product ->
        product.id !in productIdsWithHistory && product.id !in cloudLinkedProductIds
    }
    val points = eligibleProducts.flatMap { product ->
        buildList {
            product.purchasePrice?.let { price ->
                add(
                    ProductPrice(
                        productId = product.id,
                        type = "PURCHASE",
                        price = price,
                        effectiveAt = effectiveAt,
                        source = "BACKFILL_CURR"
                    )
                )
            }
            product.retailPrice?.let { price ->
                add(
                    ProductPrice(
                        productId = product.id,
                        type = "RETAIL",
                        price = price,
                        effectiveAt = effectiveAt,
                        source = "BACKFILL_CURR"
                    )
                )
            }
        }
    }
    return PriceBackfillPlan(
        eligibleProductCount = eligibleProducts.size,
        points = points
    )
}

class PriceBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override suspend fun doWork(): Result {
        // Persisted work can run before MainActivity. It joins the existing owner only after open.
        val app = applicationContext as? MerchandiseControlApplication ?: return Result.retry()
        if (app.localStoreAvailability.value !is LocalStoreAvailability.Ready) return Result.retry()
        return try {
          app.catalogSyncStateTracker.withLocalBusinessDataScopeFlight {
        val db = app.database
        val productDao = db.productDao()
        val priceDao = db.productPriceDao()
        val productRemoteRefDao = db.productRemoteRefDao()

        val now = LocalDateTime.now()
        val nowStr = fmt.format(now)

        priceDao.deleteCloudLinkedBackfillRowsWithoutRemoteRef()

        // Evita di backfillare prodotti che hanno già almeno un prezzo in history
        val already = priceDao.getProductIdsWithAnyPrice().toSet()
        val cloudLinkedProductIds = productRemoteRefDao.getAllProductIds().toSet()

        val products = productDao.getAll()
        val plan = planPriceBackfill(
            products = products,
            productIdsWithHistory = already,
            cloudLinkedProductIds = cloudLinkedProductIds,
            effectiveAt = nowStr
        )
        val backfilledProductCount = priceDao.insertBackfillIfStillEligible(plan.points)

        Result.success(
            Data.Builder()
                .putInt("backfilled_products", backfilledProductCount)
                .build()
        )
          }
        } catch (_: Task126BusinessDataScopeChangedException) { Result.retry() }
    }
}

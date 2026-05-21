package com.example.merchandisecontrolsplitview

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task114AndroidFullReconciliationTest {
    @Test
    fun fullPullFromSupabaseWithoutClearingLocalData() = runBlocking {
        requireLiveReconciliationEnabled()
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        app.catalogAutoSyncCoordinator.onAppBackground()
        app.authManager.restoreSession()

        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        authState as? AuthState.SignedIn
            ?: throw AssertionError("TASK-114 Android full reconciliation requires signed-in session: ${authState::class.java.simpleName}")

        val database = app.database
        val before = database.localCounts()
        val startedAt = SystemClock.elapsedRealtime()
        val summary = withTimeout(PULL_TIMEOUT_MS) {
            app.repository.pullCatalogBootstrapFromRemote(
                remote = app.catalogRemoteDataSource,
                priceRemote = app.productPriceRemoteDataSource,
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val after = database.localCounts()

        assertFalse("ProductPrice pull failed after catalog apply", summary.priceSyncFailed)
        assertEquals("Skipped ProductPrice rows without local product", 0, summary.skippedProductPricesPullNoProductRef)
        assertEquals(summary.remoteProductsFetched, after.products)
        assertEquals(summary.remoteSupplierRows(), after.suppliers)
        assertEquals(summary.remoteCategoryRows(), after.categories)
        assertEquals(summary.remotePricesFetched, after.productPrices)
        assertEquals(after.products, after.productRefs)
        assertEquals(after.productPrices, after.priceRefs)
        assertEquals(0, database.duplicateProductPriceLogicalKeyCount())

        println(
            "TASK114_ANDROID_FULL_RECON owner=redacted " +
                "before_products=${before.products} before_suppliers=${before.suppliers} " +
                "before_categories=${before.categories} before_prices=${before.productPrices} " +
                "after_products=${after.products} after_suppliers=${after.suppliers} " +
                "after_categories=${after.categories} after_prices=${after.productPrices} " +
                "remote_products=${summary.remoteProductsFetched} remote_prices=${summary.remotePricesFetched} " +
                "pruned_products=${summary.prunedProducts} elapsed_ms=$elapsedMs"
        )
    }

    private fun requireLiveReconciliationEnabled() {
        val value = InstrumentationRegistry.getArguments()
            .getString("task114AndroidFullReconcile")
            ?.lowercase()
        assumeTrue(
            "TASK-114 Android full reconciliation is gated. Pass -e task114AndroidFullReconcile true.",
            value == "1" || value == "true"
        )
    }

    private suspend fun AppDatabase.localCounts(): LocalCounts =
        LocalCounts(
            products = productDao().count(),
            suppliers = supplierDao().count(),
            categories = categoryDao().count(),
            productPrices = productPriceDao().countAll(),
            productRefs = productRemoteRefDao().countRows(),
            priceRefs = rawCount("product_price_remote_refs")
        )

    private fun AppDatabase.duplicateProductPriceLogicalKeyCount(): Int {
        val cursor = openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM (
                SELECT productId, type, effectiveAt, COUNT(*) AS c
                FROM product_prices
                GROUP BY productId, type, effectiveAt
                HAVING c > 1
            )
            """.trimIndent()
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun AppDatabase.rawCount(tableName: String): Int {
        val cursor = openHelper.readableDatabase.query("SELECT COUNT(*) FROM $tableName")
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun CatalogSyncSummary.remoteSupplierRows(): Int = remoteActiveSuppliers

    private fun CatalogSyncSummary.remoteCategoryRows(): Int = remoteActiveCategories

    private data class LocalCounts(
        val products: Int,
        val suppliers: Int,
        val categories: Int,
        val productPrices: Int,
        val productRefs: Int,
        val priceRefs: Int
    )

    private companion object {
        const val PULL_TIMEOUT_MS = 600_000L
    }
}

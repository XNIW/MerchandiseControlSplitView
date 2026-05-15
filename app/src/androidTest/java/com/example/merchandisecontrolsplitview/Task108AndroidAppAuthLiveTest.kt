package com.example.merchandisecontrolsplitview

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task108AndroidAppAuthLiveTest {
    @Test
    fun fullPullNoOpAndPushNoOpWhenEnabled() = runBlocking {
        requireLiveSyncEnabled()
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        app.catalogAutoSyncCoordinator.onAppBackground()
        app.authManager.restoreSession()

        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        val signedIn = authState as? AuthState.SignedIn
            ?: throw AssertionError("TASK-108 Android app-auth live requires signed-in session: ${authState::class.java.simpleName}")

        val database = app.database
        database.clearAllTables()

        val firstStartedAt = SystemClock.elapsedRealtime()
        val first = withTimeout(PULL_TIMEOUT_MS) {
            app.repository.pullCatalogBootstrapFromRemote(
                remote = app.catalogRemoteDataSource,
                priceRemote = app.productPriceRemoteDataSource,
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()
        }
        val firstPullMs = SystemClock.elapsedRealtime() - firstStartedAt
        assertFalse("ProductPrice pull failed after catalog apply", first.priceSyncFailed)
        assertEquals("Skipped ProductPrice rows without local product", 0, first.skippedProductPricesPullNoProductRef)

        val afterFirst = database.localCounts()
        assertEquals(first.remoteProductsFetched, afterFirst.products)
        assertEquals(first.remoteSupplierRows(), afterFirst.suppliers)
        assertEquals(first.remoteCategoryRows(), afterFirst.categories)
        assertEquals(first.remotePricesFetched, afterFirst.productPrices)
        assertEquals(afterFirst.products, afterFirst.productRefs)
        assertEquals(afterFirst.productPrices, afterFirst.priceRefs)
        assertEquals(0, database.duplicateProductPriceLogicalKeyCount())

        val secondStartedAt = SystemClock.elapsedRealtime()
        val second = withTimeout(PULL_TIMEOUT_MS) {
            app.repository.pullCatalogBootstrapFromRemote(
                remote = app.catalogRemoteDataSource,
                priceRemote = app.productPriceRemoteDataSource,
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()
        }
        val secondPullMs = SystemClock.elapsedRealtime() - secondStartedAt
        assertFalse("Second ProductPrice pull failed", second.priceSyncFailed)
        assertEquals("Second pull skipped ProductPrice rows", 0, second.skippedProductPricesPullNoProductRef)

        val afterSecond = database.localCounts()
        assertEquals(afterFirst, afterSecond)
        assertEquals(0, database.duplicateProductPriceLogicalKeyCount())

        val pushStartedAt = SystemClock.elapsedRealtime()
        val pushNoOp = withTimeout(PUSH_TIMEOUT_MS) {
            app.repository.pushDirtyCatalogDeltaToRemote(
                remote = app.catalogRemoteDataSource,
                priceRemote = app.productPriceRemoteDataSource,
                ownerUserId = signedIn.userId,
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()
        }
        val pushMs = SystemClock.elapsedRealtime() - pushStartedAt
        val pushedRows = pushNoOp.pushedSuppliers +
            pushNoOp.pushedCategories +
            pushNoOp.pushedProducts +
            pushNoOp.pushedProductPrices
        assertEquals("No-op push should not create remote rows", 0, pushedRows)
        assertEquals(afterSecond, database.localCounts())
        assertEquals(0, database.duplicateProductPriceLogicalKeyCount())

        println(
            "TASK108_ANDROID_APP_AUTH_PULL_NOOP_PUSH_NOOP " +
                "products=${afterSecond.products} suppliers=${afterSecond.suppliers} " +
                "categories=${afterSecond.categories} product_prices=${afterSecond.productPrices} " +
                "product_refs=${afterSecond.productRefs} price_refs=${afterSecond.priceRefs} " +
                "remote_prices=${second.remotePricesFetched} pushed_noop=$pushedRows " +
                "first_pull_ms=$firstPullMs second_pull_ms=$secondPullMs push_ms=$pushMs"
        )
    }

    private fun requireLiveSyncEnabled() {
        val value = InstrumentationRegistry.getArguments()
            .getString("task108AndroidLiveSync")
            ?.lowercase()
        assumeTrue(
            "TASK-108 Android app-auth live sync is gated. Pass -e task108AndroidLiveSync true.",
            value == "1" || value == "true"
        )
    }

    private suspend fun com.example.merchandisecontrolsplitview.data.AppDatabase.localCounts(): LocalCounts =
        LocalCounts(
            products = productDao().count(),
            suppliers = supplierDao().count(),
            categories = categoryDao().count(),
            productPrices = productPriceDao().countAll(),
            productRefs = productRemoteRefDao().countRows(),
            priceRefs = rawCount("product_price_remote_refs")
        )

    private fun com.example.merchandisecontrolsplitview.data.AppDatabase.duplicateProductPriceLogicalKeyCount(): Int {
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

    private fun com.example.merchandisecontrolsplitview.data.AppDatabase.rawCount(tableName: String): Int {
        val cursor = openHelper.readableDatabase.query("SELECT COUNT(*) FROM $tableName")
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun com.example.merchandisecontrolsplitview.data.CatalogSyncSummary.remoteSupplierRows(): Int =
        pulledSuppliers

    private fun com.example.merchandisecontrolsplitview.data.CatalogSyncSummary.remoteCategoryRows(): Int =
        pulledCategories

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
        const val PUSH_TIMEOUT_MS = 180_000L
    }
}

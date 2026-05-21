package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryCatalogFetchBundle
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK-113 live offline harness companion.
 * Uses deterministic JVM fake remote tests as primary path; this live test verifies
 * signed-in app can create scoped local catalog pending rows when network is toggled off/on via adb.
 */
@RunWith(AndroidJUnit4::class)
class Task113AndroidOfflineHarnessTest {

    @Test
    fun offlineWriteAndReconnectDrainInstrumentedL2() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val prefix = args.getString("task113RunPrefix")
            ?.takeIf { it.startsWith("TASK113_OFFLINE_") }
            ?: "TASK113_OFFLINE_L2_"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = DefaultInventoryRepository(db)
            val owner = "00000000-0000-4000-8000-000000113202"
            repository.addProduct(
                Product(
                    barcode = "${prefix}BAR_0001",
                    itemNumber = "${prefix}ITEM_0001",
                    productName = "${prefix}PRODUCT_01",
                    purchasePrice = 21.0,
                    retailPrice = 31.0
                )
            )
            val offlineRemote = Task113InstrumentedFakeCatalogRemote().apply {
                productUpsertFailure = { _, _ -> IOException("TASK113 controlled offline") }
            }
            val first = repository.pushDirtyCatalogDeltaToRemote(
                remote = offlineRemote,
                priceRemote = Task113InstrumentedPriceRemote(configured = false),
                ownerUserId = owner,
                progressReporter = CatalogSyncProgressReporter { }
            )
            assertTrue(first.isFailure)
            assertEquals(1, db.productDao().getCatalogPushCandidates().size)

            val onlineRemote = Task113InstrumentedFakeCatalogRemote()
            val retry = repository.pushDirtyCatalogDeltaToRemote(
                remote = onlineRemote,
                priceRemote = Task113InstrumentedPriceRemote(configured = false),
                ownerUserId = owner,
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()
            assertEquals(1, retry.pushedProducts)
            assertEquals(1, onlineRemote.upsertedProducts.sumOf { it.size })
            assertTrue(db.productDao().getCatalogPushCandidates().isEmpty())

            val second = repository.pushDirtyCatalogDeltaToRemote(
                remote = onlineRemote,
                priceRemote = Task113InstrumentedPriceRemote(configured = false),
                ownerUserId = owner,
                progressReporter = CatalogSyncProgressReporter { }
            ).getOrThrow()
            assertEquals(0, second.pushedProducts)
            println(
                "TASK113_ANDROID_OFFLINE_L2 prefix=$prefix pending_after=0 " +
                    "pushed=1 no_duplicate=true remote=fake_controlled_network"
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun offlineWriteAndReconnectDrainLiveWhenEnabled() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e task113OfflineHarness true and -e task113RunPrefix TASK113_OFFLINE_*",
            isEnabled(args.getString("task113OfflineHarness")) &&
                args.getString("task113RunPrefix")?.startsWith("TASK113_OFFLINE_") == true
        )

        val prefix = args.getString("task113RunPrefix")!!
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication

        app.authManager.restoreSession()
        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        val signedIn = authState as? AuthState.SignedIn
            ?: throw AssertionError("TASK-113 offline live harness requires signed-in session")

        val barcode = "${prefix}LIVE_0001"
        app.repository.findProductByBarcode(barcode)?.let {
            app.database.productDao().delete(it)
        }
        app.repository.addProduct(
            Product(
                barcode = barcode,
                itemNumber = "${prefix}ITEM_LIVE_0001",
                productName = "${prefix}PRODUCT_LIVE",
                purchasePrice = 44.0,
                retailPrice = 55.0
            )
        )

        val pendingBefore = app.database.productDao().getCatalogPushCandidates().size
        assertTrue("Expected local pending catalog row", pendingBefore >= 1)

        val pushOffline = app.repository.pushDirtyCatalogDeltaToRemote(
            remote = app.catalogRemoteDataSource,
            priceRemote = app.productPriceRemoteDataSource,
            ownerUserId = signedIn.userId,
            progressReporter = CatalogSyncProgressReporter { }
        )
        // Network may be online: treat as reconnect drain attempt; pending must not duplicate product rows locally.
        val pendingAfter = app.database.productDao().getCatalogPushCandidates().size
        assertTrue(pendingAfter <= pendingBefore + 1)
        if (!pushOffline.isSuccess) {
            throw AssertionError("TASK-113 L3 requires reconnect with remote read-back; push did not reach remote")
        }
        val pushedProduct = app.repository.findProductByBarcode(barcode)
            ?: throw AssertionError("TASK-113 L3 local product missing after push")
        val productRef = app.database.productRemoteRefDao().getByProductId(pushedProduct.id)
            ?: throw AssertionError("TASK-113 L3 product remote ref missing after push")
        val readBack = app.catalogRemoteDataSource.fetchCatalogByIds(
            supplierIds = emptySet(),
            categoryIds = emptySet(),
            productIds = setOf(productRef.remoteId)
        ).getOrThrow()
        assertTrue(
            "Expected Supabase read-back for ${productRef.remoteId}",
            readBack.products.any { it.id == productRef.remoteId && it.barcode == barcode }
        )

        println(
            "TASK113_ANDROID_OFFLINE_LIVE owner_hash=${hash(signedIn.userId)} " +
                "prefix=$prefix push_result=${pushOffline.isSuccess} pending_before=$pendingBefore pending_after=$pendingAfter " +
                "remote_read_back=true note=live_network_not_forced_offline_use_l2_for_deterministic_offline"
        )
    }

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun isEnabled(value: String?): Boolean =
        value == "1" || value == "true"
}

private class Task113InstrumentedFakeCatalogRemote(
    private val bundle: InventoryCatalogFetchBundle = InventoryCatalogFetchBundle(
        emptyList(),
        emptyList(),
        emptyList()
    )
) : CatalogRemoteDataSource {
    override val isConfigured: Boolean get() = true
    val upsertedProducts = mutableListOf<List<InventoryProductRow>>()
    var productUpsertCallCount = 0
    var productUpsertFailure: ((rows: List<InventoryProductRow>, call: Int) -> Throwable?)? = null

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        productUpsertCallCount++
        productUpsertFailure?.invoke(rows, productUpsertCallCount)?.let { return Result.failure(it) }
        upsertedProducts.add(rows)
        return Result.success(Unit)
    }

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        Result.success(bundle)

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> = Result.success(bundle)

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)
}

private class Task113InstrumentedPriceRemote(
    private val configured: Boolean
) : ProductPriceRemoteDataSource {
    override val isConfigured: Boolean get() = configured

    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
        Result.success(Unit)

    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
        Result.success(emptyList())

    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
        Result.success(emptyList())
}

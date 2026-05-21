package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryCatalogFetchBundle
import com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Task098CrossPlatformSmokeTest {
    @Test
    fun test01PreflightAndCollisionScanReadOnly() = runBlocking {
        requireLiveSmokeEnabled()
        val runtime = runtime()
        val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
        val prices = runtime.priceRemote.fetchProductPrices().getOrThrow()

        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))
        val supplierCount = activeSuppliers(catalog).size
        val categoryCount = activeCategories(catalog).size
        val productCount = activeProducts(catalog, setOf(BAR_A, BAR_B)).size
        assumeTrue(
            "TASK-098 evidence rows are already present; strict collision scan is pre-write only.",
            supplierCount + categoryCount + productCount == 0
        )
        assertEquals(0, supplierCount)
        assertEquals(0, categoryCount)
        assertEquals(0, productCount)
        val fixtureProductIds = activeProducts(catalog, setOf(BAR_A, BAR_B)).map { it.id }.toSet()
        assertTrue(prices.filter { it.productId in fixtureProductIds }.isEmpty())

        println(
            "TASK098_ANDROID_PREFLIGHT project_hash=${hash(BuildConfig.SUPABASE_URL)} " +
                "owner_hash=${hash(runtime.ownerUserId)} collision=free"
        )
    }

    @Test
    fun test02AndroidWriteAAndRemoteReadBack() = runBlocking {
        requireLiveSmokeEnabled()
        val runtime = runtime()
        val before = runtime.catalogRemote.fetchCatalog().getOrThrow()
        singleActiveProduct(before, BAR_A)?.let {
            assertEquals(PRODUCT_A, it.productName)
        }

        val supplier = runtime.repository.addSupplier(SUPPLIER)
            ?: runtime.repository.findSupplierByName(SUPPLIER)
            ?: throw AssertionError("Supplier not available")
        val category = runtime.repository.addCategory(CATEGORY)
            ?: runtime.repository.findCategoryByName(CATEGORY)
            ?: throw AssertionError("Category not available")

        val existing = runtime.repository.findProductByBarcode(BAR_A)
        if (existing == null) {
            runtime.repository.addProduct(
                Product(
                    barcode = BAR_A,
                    productName = PRODUCT_A,
                    supplierId = supplier.id,
                    categoryId = category.id,
                    stockQuantity = 0.0
                )
            )
        } else {
            assertEquals(PRODUCT_A, existing.productName)
        }
        val product = runtime.repository.findProductByBarcode(BAR_A)
            ?: throw AssertionError("Product A was not inserted locally")

        for (point in expectedA()) {
            runtime.repository.recordPriceIfChanged(
                productId = product.id,
                type = point.type,
                price = point.price,
                at = point.effectiveAt,
                source = "TASK098_ANDROID_PUSH"
            )
        }

        val summary = runtime.repository.syncCatalogWithRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            ownerUserId = runtime.ownerUserId
        ).getOrThrow()

        val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
        val prices = runtime.priceRemote.fetchProductPrices().getOrThrow()
        val remoteA = singleActiveProduct(catalog, BAR_A) ?: throw AssertionError("Remote Product A missing")
        assertEquals(PRODUCT_A, remoteA.productName)
        assertPrice(remoteA.purchasePrice, 42.22, "remote A catalog purchase")
        assertPrice(remoteA.retailPrice, 84.44, "remote A catalog retail")
        assertRemotePrices(prices.filter { it.productId == remoteA.id }, expectedA())
        assertLocalPrices(runtime.repository, BAR_A, expectedA())

        println(
            "TASK098_ANDROID_WRITE_A owner_hash=${hash(runtime.ownerUserId)} " +
                "pushed_catalog=${summary.pushedSuppliers + summary.pushedCategories + summary.pushedProducts} " +
                "pushed_prices=${summary.pushedProductPrices} product_hash=${hash(remoteA.id)}"
        )
    }

    @Test
    fun test03AndroidPullReadBackB() = runBlocking {
        requireLiveSmokeEnabled()
        val runtime = runtime()
        val summary = runtime.repository.pullCatalogBootstrapFromRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        val product = runtime.repository.findProductByBarcode(BAR_B)
            ?: throw AssertionError("Product B missing after Android pull")
        assertEquals(PRODUCT_B, product.productName)
        assertLocalPrices(runtime.repository, BAR_B, expectedB())

        val details = runtime.repository.getProductDetailsById(product.id)
        assertNotNull(details)
        assertPrice(details?.currentPurchasePrice, 55.55, "Android B current purchase")
        assertPrice(details?.prevPurchase, 51.11, "Android B previous purchase")
        assertPrice(details?.currentRetailPrice, 111.10, "Android B current retail")
        assertPrice(details?.prevRetail, 101.11, "Android B previous retail")

        println(
            "TASK098_ANDROID_PULL_B owner_hash=${hash(runtime.ownerUserId)} " +
                "scoped_bootstrap=true pulled_products=${summary.pulledProducts} " +
                "pulled_prices=${summary.pulledProductPrices} " +
                "room_product_local_id_redacted=true"
        )
    }

    private suspend fun runtime(): Runtime {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        app.catalogAutoSyncCoordinator.onAppBackground()
        app.authManager.restoreSession()

        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        val signedIn = authState as? AuthState.SignedIn
            ?: throw AssertionError("Android Supabase session is not signed in: ${authState::class.java.simpleName}")

        assertTrue(app.catalogRemoteDataSource.isConfigured)
        assertTrue(app.productPriceRemoteDataSource.isConfigured)
        withTimeoutOrNull(5_000) {
            app.catalogSyncStateTracker.state.first { !it.isBusy }
        }
        val client = app.supabaseClient ?: throw AssertionError("Supabase client missing")
        return Runtime(
            app = app,
            repository = app.repository,
            ownerUserId = signedIn.userId,
            catalogRemote = Task098ScopedCatalogRemoteDataSource(app.catalogRemoteDataSource, client),
            priceRemote = Task098ScopedProductPriceRemoteDataSource(app.productPriceRemoteDataSource, client)
        )
    }

    private fun requireLiveSmokeEnabled() {
        val value = InstrumentationRegistry.getArguments()
            .getString("task098LiveSmoke")
            ?.lowercase()
        assumeTrue(
            "TASK-098 live smoke is gated. Pass -e task098LiveSmoke true and run the selected tests in the documented order.",
            value == "1" || value == "true"
        )
    }

    private fun activeSuppliers(catalog: InventoryCatalogFetchBundle) =
        catalog.suppliers.filter { it.deletedAt == null && it.name == SUPPLIER }

    private fun activeCategories(catalog: InventoryCatalogFetchBundle) =
        catalog.categories.filter { it.deletedAt == null && it.name == CATEGORY }

    private fun activeProducts(catalog: InventoryCatalogFetchBundle, barcodes: Set<String>) =
        catalog.products.filter { it.deletedAt == null && it.barcode in barcodes }

    private fun singleActiveProduct(catalog: InventoryCatalogFetchBundle, barcode: String): InventoryProductRow? {
        val matches = activeProducts(catalog, setOf(barcode))
        return if (matches.size == 1) matches.single() else null
    }

    private suspend fun assertLocalPrices(
        repository: DefaultInventoryRepository,
        barcode: String,
        expected: List<ExpectedPoint>
    ) {
        val rows = repository.getAllPriceHistoryRows().filter { it.barcode == barcode }
        assertEquals(expected.size, rows.size)
        for (point in expected) {
            val row = rows.firstOrNull { it.type == point.type && it.timestamp == point.effectiveAt }
                ?: throw AssertionError("Missing local price ${point.type} ${point.effectiveAt}")
            assertPrice(row.price, point.price, "local $barcode ${point.type} ${point.effectiveAt}")
        }
    }

    private fun assertRemotePrices(
        rows: List<InventoryProductPriceRow>,
        expected: List<ExpectedPoint>
    ) {
        assertEquals(expected.size, rows.size)
        for (point in expected) {
            val row = rows.firstOrNull {
                it.type.uppercase() == point.type && canonicalEffectiveAt(it.effectiveAt) == point.effectiveAt
            } ?: throw AssertionError("Missing remote price ${point.type} ${point.effectiveAt}")
            assertPrice(row.price, point.price, "remote ${point.type} ${point.effectiveAt}")
        }
    }

    private fun assertPrice(actual: Double?, expected: Double, label: String) {
        assertNotNull("$label missing", actual)
        assertTrue("$label expected $expected got $actual", abs(actual!! - expected) <= TOLERANCE)
    }

    private fun canonicalEffectiveAt(value: String): String =
        value.replace("T", " ").take(19)

    private fun expectedA() = listOf(
        ExpectedPoint("PURCHASE", 41.11, "2026-05-10 11:00:00"),
        ExpectedPoint("PURCHASE", 42.22, "2026-05-10 11:05:00"),
        ExpectedPoint("RETAIL", 81.11, "2026-05-10 11:10:00"),
        ExpectedPoint("RETAIL", 84.44, "2026-05-10 11:15:00")
    )

    private fun expectedB() = listOf(
        ExpectedPoint("PURCHASE", 51.11, "2026-05-10 11:20:00"),
        ExpectedPoint("PURCHASE", 55.55, "2026-05-10 11:30:00"),
        ExpectedPoint("RETAIL", 101.11, "2026-05-10 11:25:00"),
        ExpectedPoint("RETAIL", 111.10, "2026-05-10 11:35:00")
    )

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private data class Runtime(
        val app: MerchandiseControlApplication,
        val repository: DefaultInventoryRepository,
        val ownerUserId: String,
        val catalogRemote: CatalogRemoteDataSource,
        val priceRemote: ProductPriceRemoteDataSource
    )

    private data class ExpectedPoint(
        val type: String,
        val price: Double,
        val effectiveAt: String
    )

    private companion object {
        const val SUPPLIER = "TASK098_SUPPLIER_CROSS_PLATFORM"
        const val CATEGORY = "TASK098_CATEGORY_CROSS_PLATFORM"
        const val PRODUCT_A = "TASK098_PRODUCT_ANDROID_TO_IOS"
        const val BAR_A = "TASK098_BAR_A2I"
        const val PRODUCT_B = "TASK098_PRODUCT_IOS_TO_ANDROID"
        const val BAR_B = "TASK098_BAR_I2A"
        const val TOLERANCE = 0.005
        val BARCODES = setOf(BAR_A, BAR_B)
    }

    private class Task098ScopedCatalogRemoteDataSource(
        private val delegate: CatalogRemoteDataSource,
        private val client: SupabaseClient
    ) : CatalogRemoteDataSource {
        override val isConfigured: Boolean get() = delegate.isConfigured

        override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
            delegate.upsertSuppliers(rows)

        override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
            delegate.upsertCategories(rows)

        override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
            delegate.upsertProducts(rows)

        override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = runCatching {
            val products = fetchProductsByBarcodes(BARCODES)
            val supplierIds = products.mapNotNull { it.supplierId }.toSet()
            val categoryIds = products.mapNotNull { it.categoryId }.toSet()
            val parents = delegate.fetchCatalogByIds(
                supplierIds = supplierIds,
                categoryIds = categoryIds,
                productIds = emptySet()
            ).getOrThrow()
            InventoryCatalogFetchBundle(
                suppliers = (parents.suppliers + fetchSuppliersByName(SUPPLIER)).distinctBy { it.id },
                categories = (parents.categories + fetchCategoriesByName(CATEGORY)).distinctBy { it.id },
                products = products,
                isCompleteSnapshot = false
            )
        }

        override suspend fun fetchCatalogByIds(
            supplierIds: Set<String>,
            categoryIds: Set<String>,
            productIds: Set<String>
        ): Result<InventoryCatalogFetchBundle> =
            delegate.fetchCatalogByIds(supplierIds, categoryIds, productIds)

        override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
            delegate.markSupplierTombstoned(patch)

        override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
            delegate.markCategoryTombstoned(patch)

        override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
            delegate.markProductTombstoned(patch)

        private suspend fun fetchProductsByBarcodes(barcodes: Set<String>): List<InventoryProductRow> {
            if (barcodes.isEmpty()) return emptyList()
            val rows = mutableListOf<InventoryProductRow>()
            for (chunk in barcodes.chunked(80)) {
                rows += client.postgrest["inventory_products"].select {
                    filter {
                        isIn("barcode", chunk)
                    }
                }.decodeList()
            }
            return rows
        }

        private suspend fun fetchSuppliersByName(name: String): List<InventorySupplierRow> =
            client.postgrest["inventory_suppliers"].select {
                filter {
                    eq("name", name)
                }
            }.decodeList()

        private suspend fun fetchCategoriesByName(name: String): List<InventoryCategoryRow> =
            client.postgrest["inventory_categories"].select {
                filter {
                    eq("name", name)
                }
            }.decodeList()
    }

    private class Task098ScopedProductPriceRemoteDataSource(
        private val delegate: ProductPriceRemoteDataSource,
        private val client: SupabaseClient
    ) : ProductPriceRemoteDataSource {
        override val isConfigured: Boolean get() = delegate.isConfigured

        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
            delegate.upsertProductPrices(rows)

        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = runCatching {
            val productIds = mutableSetOf<String>()
            for (chunk in BARCODES.chunked(80)) {
                val products = client.postgrest["inventory_products"].select {
                    filter {
                        isIn("barcode", chunk)
                    }
                }.decodeList<InventoryProductRow>()
                productIds += products.map { it.id }
            }
            if (productIds.isEmpty()) return@runCatching emptyList()
            val rows = mutableListOf<InventoryProductPriceRow>()
            for (chunk in productIds.chunked(80)) {
                rows += client.postgrest["inventory_product_prices"].select {
                    filter {
                        isIn("product_id", chunk)
                    }
                }.decodeList()
            }
            rows
        }

        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            delegate.fetchProductPricesByIds(remoteIds)
    }
}

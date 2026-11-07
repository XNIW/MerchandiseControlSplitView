package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorefrontProductPagingProjectionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `storefront filter pages only matching local product identities`() = runTest {
        val firstId = repository.addProductAndReturnId(product("SF-PAGE-1", "First"))
        val secondId = repository.addProductAndReturnId(product("SF-PAGE-2", "Second"))

        val filtered = repository.getProductsWithDetailsPaged(null, setOf(secondId)).load(refresh())
        val empty = repository.getProductsWithDetailsPaged(null, emptySet()).load(refresh())

        assertEquals(
            listOf(secondId),
            (filtered as PagingSource.LoadResult.Page).data.map { it.product.id }
        )
        assertEquals(emptyList<ProductWithDetails>(), (empty as PagingSource.LoadResult.Page).data)
        check(firstId != secondId)
    }

    @Test
    fun `catalog rows use current price and relations while previous price loads per product`() = runTest {
        val supplierId = db.supplierDao().insert(Supplier(name = "Scoped supplier"))
        val categoryId = db.categoryDao().insert(Category(name = "Scoped category"))
        db.productDao().insert(
            product("CAT-CURRENT", "Current").copy(supplierId = supplierId, categoryId = categoryId)
        )
        val productId = requireNotNull(db.productDao().findByBarcode("CAT-CURRENT")).id
        db.productPriceDao().insertAll(listOf(
            ProductPrice(productId = productId, type = "RETAIL", price = 600.0, effectiveAt = "2026-01-01 00:00:00"),
            ProductPrice(productId = productId, type = "RETAIL", price = 800.0, effectiveAt = "2026-01-02 00:00:00")
        ))

        val page = repository.getCurrentDetailsPaged("Scoped supplier", setOf(productId)).load(refresh())
        val current = (page as PagingSource.LoadResult.Page).data.single()
        assertEquals(productId, current.product.id)
        assertEquals("Scoped supplier", current.supplierName)
        assertEquals("Scoped category", current.categoryName)
        assertEquals(1_000.0, current.currentRetailPrice)
        assertNull(current.prevRetail)
        assertEquals(current, repository.getCurrentProductDetails(productId))
        assertEquals(600.0, repository.observePriceSummaryForProduct(productId).first()?.prevRetail)
        assertEquals(600.0, repository.getProductDetailsById(productId)?.prevRetail)
    }

    @Test
    fun `cloud confirmation reattaches after same scope cutover and rejects another owner`() = runTest {
        db.productDao().insert(product("CAT-ACK", "Acknowledgement"))
        val productId = requireNotNull(db.productDao().findByBarcode("CAT-ACK")).id
        db.productRemoteRefDao().insert(
            ProductRemoteRef(productId = productId, remoteId = "ack-candidate", localChangeRevision = 1)
        )
        val ready = Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope("ack-owner", null))
        val tracker = CatalogSyncStateTracker(ready)
        val guarded = DefaultInventoryRepository(db, tracker)
        val results = Channel<Boolean>(Channel.UNLIMITED)
        val observer = backgroundScope.launch {
            guarded.observeProductCloudConfirmed(productId).collect { results.send(it) }
        }
        suspend fun next() = withContext(Dispatchers.IO) { withTimeout(5_000) { results.receive() } }

        assertEquals(false, next())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transition = async {
            tracker.withBusinessDataScopeTransition {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            assertEquals(false, next())
            db.productRemoteRefDao().updateRemoteApplyState(productId, 1, 1L, "ack-fingerprint", null)
        } finally {
            release.complete(Unit)
        }
        transition.await()
        assertEquals(true, next())

        tracker.updateBusinessDataScopeState(
            Task126BusinessDataScopeState.ready(task126ActiveOwnerStoreScope("foreign-owner", null))
        )
        assertEquals(false, next())
        observer.cancel()
    }

    private fun refresh() = PagingSource.LoadParams.Refresh<Int>(
        key = null,
        loadSize = 20,
        placeholdersEnabled = false
    )

    private fun product(barcode: String, name: String) = Product(
        barcode = barcode,
        productName = name,
        retailPrice = 1_000.0
    )
}

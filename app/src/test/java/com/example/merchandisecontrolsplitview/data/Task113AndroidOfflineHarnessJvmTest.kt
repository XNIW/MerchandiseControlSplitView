package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryCatalogFetchBundle
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Task113AndroidOfflineHarnessJvmTest {

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
    fun offlineWriteKeepsPendingWhenRemoteFails() = runTest {
        val owner = "00000000-0000-4000-8000-000000113001"
        val prefix = "TASK113_OFFLINE_JVM_"
        repository.addProduct(
            Product(
                barcode = "${prefix}BAR_0001",
                productName = "${prefix}PRODUCT_01",
                purchasePrice = 12.0,
                retailPrice = 22.0
            )
        )
        val offlineRemote = Task113FakeCatalogRemote().apply {
            productUpsertFailure = { _, _ -> IOException("simulated offline") }
        }

        val first = repository.pushDirtyCatalogDeltaToRemote(
            remote = offlineRemote,
            priceRemote = Task113RecordingPriceRemote(configured = false),
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        )

        assertTrue(first.isFailure)
        assertTrue(offlineRemote.upsertedProducts.isEmpty())
        assertEquals(1, db.productDao().getCatalogPushCandidates().size)

        println(
            "TASK113_ANDROID_OFFLINE_WRITE owner_hash=jvm " +
                "prefix=$prefix pending=1 remote_writes=0 simulated_offline=true"
        )
    }

    @Test
    fun reconnectDrainRetriesPendingWithoutDuplicates() = runTest {
        val owner = "00000000-0000-4000-8000-000000113002"
        val prefix = "TASK113_OFFLINE_JVM_"
        repository.addProduct(
            Product(
                barcode = "${prefix}BAR_0002",
                itemNumber = "${prefix}ITEM_0002",
                productName = "${prefix}PRODUCT_02",
                purchasePrice = 13.0,
                retailPrice = 23.0
            )
        )
        val failRemote = Task113FakeCatalogRemote().apply {
            productUpsertFailure = { _, _ -> IOException("simulated offline") }
        }
        assertTrue(
            repository.pushDirtyCatalogDeltaToRemote(
                remote = failRemote,
                priceRemote = Task113RecordingPriceRemote(configured = false),
                ownerUserId = owner,
                progressReporter = CatalogSyncProgressReporter { }
            ).isFailure
        )
        assertEquals(1, db.productDao().getCatalogPushCandidates().size)

        val okRemote = Task113FakeCatalogRemote()
        val retry = repository.pushDirtyCatalogDeltaToRemote(
            remote = okRemote,
            priceRemote = Task113RecordingPriceRemote(configured = false),
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(1, retry.pushedProducts)
        assertEquals(1, okRemote.upsertedProducts.sumOf { it.size })
        assertTrue(db.productDao().getCatalogPushCandidates().isEmpty())

        val second = repository.pushDirtyCatalogDeltaToRemote(
            remote = okRemote,
            priceRemote = Task113RecordingPriceRemote(configured = false),
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()
        assertEquals(0, second.pushedProducts)

        println(
            "TASK113_ANDROID_RECONNECT_DRAIN owner_hash=jvm " +
                "prefix=$prefix pushed=1 pending_after=0 no_duplicate=true"
        )
    }
}

private class Task113FakeCatalogRemote(
    private val bundle: InventoryCatalogFetchBundle = InventoryCatalogFetchBundle(
        emptyList(),
        emptyList(),
        emptyList()
    )
) : CatalogRemoteDataSource {
    override val isConfigured get() = true
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

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = Result.success(bundle)

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> = Result.success(bundle)

    override suspend fun markSupplierTombstoned(patch: com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun markCategoryTombstoned(patch: com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun markProductTombstoned(patch: com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch): Result<Unit> =
        Result.success(Unit)
}

private class Task113RecordingPriceRemote(
    private val configured: Boolean
) : ProductPriceRemoteDataSource {
    override val isConfigured: Boolean get() = configured
    override suspend fun upsertProductPrices(rows: List<com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow>): Result<Unit> =
        Result.success(Unit)
    override suspend fun fetchProductPrices(): Result<List<com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow>> =
        Result.success(emptyList())
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow>> =
        Result.success(emptyList())
}

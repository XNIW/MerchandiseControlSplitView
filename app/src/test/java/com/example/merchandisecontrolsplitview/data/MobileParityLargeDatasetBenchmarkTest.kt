package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Explicit host Room benchmark, not a UI/device latency test or an Excel harness.
 * Run with MOBILE_PARITY_LARGE_BENCHMARK=1 and --tests '*MobileParityLargeDatasetBenchmarkTest'.
 * Default CI skips before allocating a database. Results have no pass/fail timing threshold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MobileParityLargeDatasetBenchmarkTest {
    private var database: AppDatabase? = null
    private lateinit var repository: DefaultInventoryRepository
    private val db: AppDatabase get() = checkNotNull(database)
    private val resultFile = File(
        System.getenv("MOBILE_PARITY_BENCHMARK_OUTPUT")
            ?: "${System.getProperty("java.io.tmpdir")}/mobile-parity-android-large-benchmark.json"
    )
    private val metrics = linkedMapOf<String, JsonObject>()
    private var seedMs = 0.0
    private var seedHeapBytes = 0L
    private var sqliteBytes = 0L
    private val startHeapBytes = usedHeapBytes()

    @Before
    fun setup() {
        assumeTrue("Opt-in large synthetic dataset benchmark", System.getenv("MOBILE_PARITY_LARGE_BENCHMARK") == "1")
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
        writeReport("seeding")
    }

    @After
    fun teardown() {
        database?.close()
    }

    @Test
    fun `measure real repository queries and local editor writes at large scale`() = runBlocking {
        val seedStarted = System.nanoTime()
        seedDataset()
        seedMs = elapsedMs(seedStarted)
        seedHeapBytes = usedHeapBytes()
        sqliteBytes = pragmaLong("page_count") * pragmaLong("page_size")
        assertEquals(PRODUCT_COUNT, db.productDao().count())
        assertEquals(PRICE_COUNT, db.productPriceDao().countAll())
        writeReport("measuring")

        for (offset in listOf(0, 10_000, 19_950)) {
            measure("details_page_50_offset_$offset") {
                val rows = repository.getProductsWithDetailsPage(50, offset)
                assertEquals(50, rows.size)
                assertEquals(offset + 1L, rows.first().product.id)
                assertEquals(offset + 50L, rows.last().product.id)
                assertEquals(1_014.0, rows.first().lastPurchase)
                assertEquals(2_013.0, rows.first().lastRetail)
                assertEquals("Supplier ${offset % SUPPLIER_COUNT + 1}", rows.first().supplierName)
                assertEquals("Category ${offset % CATEGORY_COUNT + 1}", rows.first().categoryName)
            }
        }
        measure("paging_search_specific_barcode") {
            val source = repository.getProductsWithDetailsPaged("BENCH-020000")
            try {
                val result = source.load(PagingSource.LoadParams.Refresh(null, 50, true))
                assertTrue(result is PagingSource.LoadResult.Page)
                val page = result as PagingSource.LoadResult.Page
                assertEquals(1, page.data.size)
                assertEquals(20_000L, page.data.single().product.id)
            } finally {
                source.invalidate()
            }
        }
        measure("paging_search_broad_name") {
            val source = repository.getProductsWithDetailsPaged("Café")
            try {
                val result = source.load(PagingSource.LoadParams.Refresh(null, 50, true))
                assertTrue(result is PagingSource.LoadResult.Page)
                val page = result as PagingSource.LoadResult.Page
                assertEquals(50, page.data.size)
                assertEquals(PRODUCT_COUNT, page.data.size + page.itemsBefore + page.itemsAfter)
            } finally {
                source.invalidate()
            }
        }
        measure("barcode_lookup_current_prices") {
            val product = repository.findProductByBarcode("BENCH-020000")
            assertNotNull(product)
            assertEquals(1_014.0, product!!.purchasePrice)
            assertEquals(2_013.0, product.retailPrice)
        }
        measure("product_details_with_previous_prices") {
            val details = repository.getProductDetailsById(20_000L)!!
            assertEquals(1_014.0, details.lastPurchase)
            assertEquals(1_012.0, details.prevPurchase)
            assertEquals(2_013.0, details.lastRetail)
            assertEquals(2_011.0, details.prevRetail)
        }
        measure("price_history_flow_first") {
            val rows = repository.getPriceSeries(20_000L, "PURCHASE").first()
            assertEquals(8, rows.size)
            assertEquals(1_014.0, rows.first().price, 0.0001)
        }

        val baseline = repository.findProductByBarcode("BENCH-020000")!!
        var notifications = 0
        repository.onProductCatalogChanged = { notifications++ }
        measure("editor_unchanged_no_op") {
            repository.updateProductFromEditor(baseline, baseline)
        }
        assertEquals(0, notifications)
        assertEquals(PRICE_COUNT, db.productPriceDao().countAll())
        assertEquals(null, db.productRemoteRefDao().getByProductId(baseline.id))

        var current = baseline
        var editIndex = 0
        measure("editor_name_write_with_dirty_marker") {
            val edited = current.copy(productName = "Edited benchmark ${++editIndex}")
            repository.updateProductFromEditor(current, edited)
            current = edited
        }
        assertEquals(current, repository.findProductByBarcode(baseline.barcode))
        assertEquals(WARMUPS + SAMPLES, notifications)
        assertEquals(PRICE_COUNT, db.productPriceDao().countAll())
        val ref = db.productRemoteRefDao().getByProductId(baseline.id)!!
        assertEquals(WARMUPS + SAMPLES - 1, ref.localChangeRevision)
        assertFalse(ref.lastRemoteAppliedAt != null)
        assertEquals(PRODUCT_COUNT, db.productDao().count())
        writeReport("complete")
        println("MOBILE_PARITY_LARGE_BENCHMARK_RESULT ${resultFile.absolutePath}")
    }

    private suspend fun seedDataset() {
        db.withTransaction {
            for (id in 1..SUPPLIER_COUNT) db.supplierDao().insert(Supplier(id.toLong(), "Supplier $id"))
            for (id in 1..CATEGORY_COUNT) db.categoryDao().insert(Category(id.toLong(), "Category $id"))
        }
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val timestamps = (0 until PRICES_PER_PRODUCT).map {
            LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(it.toLong()).format(formatter)
        }
        for (firstId in 1..PRODUCT_COUNT step SEED_CHUNK) {
            val products = (firstId until firstId + SEED_CHUNK).map { id ->
                Product(
                    id = id.toLong(),
                    barcode = "BENCH-${id.toString().padStart(6, '0')}",
                    itemNumber = "ITEM-$id",
                    productName = "Café 商品 $id",
                    supplierId = ((id - 1) % SUPPLIER_COUNT + 1).toLong(),
                    categoryId = ((id - 1) % CATEGORY_COUNT + 1).toLong(),
                    purchasePrice = 1_014.0,
                    retailPrice = 2_013.0,
                    stockQuantity = (id % 20).toDouble()
                )
            }
            val prices = products.flatMap { product ->
                timestamps.mapIndexed { index, timestamp ->
                    val purchase = index % 2 == 0
                    ProductPrice(
                        productId = product.id,
                        type = if (purchase) "PURCHASE" else "RETAIL",
                        price = (if (purchase) 1_000.0 else 2_000.0) + index,
                        effectiveAt = timestamp,
                        source = "BENCHMARK_SYNTHETIC"
                    )
                }
            }
            db.withTransaction {
                db.productDao().insertAll(products)
                db.productPriceDao().insertAll(prices)
            }
        }
    }

    private suspend fun measure(name: String, action: suspend () -> Unit) {
        repeat(WARMUPS) { action() }
        val samples = List(SAMPLES) {
            val started = System.nanoTime()
            action()
            elapsedMs(started)
        }
        val sorted = samples.sorted()
        metrics[name] = buildJsonObject {
            put("samples_ms", JsonArray(samples.map(::JsonPrimitive)))
            put("p50_ms", sorted[ceil(SAMPLES * 0.50).toInt() - 1])
            put("p95_ms", sorted[ceil(SAMPLES * 0.95).toInt() - 1])
            put("max_ms", sorted.last())
        }
        writeReport("measuring")
    }

    private fun writeReport(status: String) {
        val report = buildJsonObject {
            put("status", status)
            put("environment", "Robolectric API33 host JVM; Room in-memory; warm queries; no UI or network")
            put("comparison", "Final implementation only; no before-after baseline")
            put("process_id", ProcessHandle.current().pid())
            put("java_version", System.getProperty("java.version"))
            put("os_name", System.getProperty("os.name"))
            put("os_arch", System.getProperty("os.arch"))
            put("product_count", PRODUCT_COUNT)
            put("price_count", PRICE_COUNT)
            put("supplier_count", SUPPLIER_COUNT)
            put("category_count", CATEGORY_COUNT)
            put("seed_product_chunk", SEED_CHUNK)
            put("seed_price_chunk", SEED_CHUNK * PRICES_PER_PRODUCT)
            put("seed_ms", seedMs)
            put("warmups_per_scenario", WARMUPS)
            put("samples_per_scenario", SAMPLES)
            put("percentile_method", "nearest rank")
            put("heap_used_start_bytes", startHeapBytes)
            put("heap_used_after_seed_bytes", seedHeapBytes)
            put("heap_used_current_bytes", usedHeapBytes())
            put("heap_max_bytes", Runtime.getRuntime().maxMemory())
            put("sqlite_page_bytes_after_seed", sqliteBytes)
            put("metrics", JsonObject(metrics))
        }
        resultFile.writeText(REPORT_JSON.encodeToString(JsonObject.serializer(), report))
    }

    private fun pragmaLong(name: String): Long =
        db.openHelper.readableDatabase.query("PRAGMA $name").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun elapsedMs(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

    private companion object {
        val REPORT_JSON = Json { prettyPrint = true }
        const val PRODUCT_COUNT = 20_000
        const val SUPPLIER_COUNT = 100
        const val CATEGORY_COUNT = 50
        const val PRICES_PER_PRODUCT = 15
        const val PRICE_COUNT = PRODUCT_COUNT * PRICES_PER_PRODUCT
        const val SEED_CHUNK = 200
        const val WARMUPS = 3
        const val SAMPLES = 30
    }
}

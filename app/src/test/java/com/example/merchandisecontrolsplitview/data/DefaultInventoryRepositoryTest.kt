package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultInventoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository
    private val timestampPattern = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

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
        DefaultInventoryRepositoryTestHooks.afterProductsPersisted = null
        db.close()
    }

    @Test
    fun `addProduct persists product and creates initial manual price history`() = runTest {
        val product = Product(
            barcode = "12345678",
            productName = "Product",
            purchasePrice = 10.0,
            retailPrice = 15.0
        )

        repository.addProduct(product)

        val saved = repository.findProductByBarcode(product.barcode)
        assertNotNull(saved)

        val purchaseHistory = repository.getPriceSeries(saved!!.id, "PURCHASE").first()
        val retailHistory = repository.getPriceSeries(saved.id, "RETAIL").first()

        assertEquals(1, purchaseHistory.size)
        assertEquals(1, retailHistory.size)
        assertEquals("MANUAL", purchaseHistory.single().source)
        assertEquals("MANUAL", retailHistory.single().source)
        assertTrue(timestampPattern.matches(purchaseHistory.single().effectiveAt))
        assertTrue(timestampPattern.matches(retailHistory.single().effectiveAt))
    }

    @Test
    fun `updateProduct appends only changed manual price history`() = runTest {
        repository.addProduct(
            Product(
                barcode = "87654321",
                productName = "Original",
                purchasePrice = 5.0,
                retailPrice = 8.0
            )
        )
        val saved = repository.findProductByBarcode("87654321")!!

        Thread.sleep(2_100)

        repository.updateProduct(
            saved.copy(
                productName = "Updated",
                purchasePrice = 6.5,
                retailPrice = 8.0
            )
        )

        val reloaded = repository.findProductByBarcode("87654321")
        val purchaseHistory = repository.getPriceSeries(saved.id, "PURCHASE").first()
        val retailHistory = repository.getPriceSeries(saved.id, "RETAIL").first()

        assertEquals("Updated", reloaded?.productName)
        assertEquals(2, purchaseHistory.size)
        assertEquals(1, retailHistory.size)
        assertEquals(listOf(6.5, 5.0), purchaseHistory.map { it.price })
        assertTrue(purchaseHistory.all { it.source == "MANUAL" })
    }

    @Test
    fun `applyImport persists new and updated products with prev and current import prices`() = runTest {
        repository.addProduct(
            Product(
                barcode = "11112222",
                productName = "Existing",
                purchasePrice = 2.0,
                retailPrice = 3.0
            )
        )
        val existing = repository.findProductByBarcode("11112222")!!

        Thread.sleep(1_100)

        val newProduct = Product(
            barcode = "33334444",
            productName = "Imported New",
            purchasePrice = 4.0,
            retailPrice = 6.0,
            oldPurchasePrice = 3.5,
            oldRetailPrice = 5.5
        )
        val updatedProduct = existing.copy(
            productName = "Imported Updated",
            purchasePrice = 2.5,
            retailPrice = 3.5,
            oldPurchasePrice = 1.8,
            oldRetailPrice = 2.8
        )

        val result = repository.applyImport(
            importRequest(
                newProducts = listOf(newProduct),
                updatedProducts = listOf(ProductUpdate(existing, updatedProduct, changedFields = emptyList()))
            )
        )

        assertEquals(ImportApplyResult.Success, result)
        val importedNew = repository.findProductByBarcode("33334444")!!
        val importedUpdated = repository.findProductByBarcode("11112222")!!
        val newPurchaseHistory = repository.getPriceSeries(importedNew.id, "PURCHASE").first()
        val importRows = repository.getAllPriceHistoryRows()

        assertEquals("Imported Updated", importedUpdated.productName)
        assertEquals(setOf("IMPORT_PREV", "IMPORT"), newPurchaseHistory.mapNotNull { it.source }.toSet())
        val prevEvent = newPurchaseHistory.first { it.source == "IMPORT_PREV" }
        val currentEvent = newPurchaseHistory.first { it.source == "IMPORT" }
        assertTrue(
            LocalDateTime.parse(prevEvent.effectiveAt, timestampFormatter) <=
                LocalDateTime.parse(currentEvent.effectiveAt, timestampFormatter)
        )
        assertTrue(importRows.any { it.barcode == "33334444" && it.source == "IMPORT_PREV" })
        assertTrue(importRows.any { it.barcode == "33334444" && it.source == "IMPORT" })
    }

    @Test
    fun `applyImport resolves deferred relations and includes pending price history in one apply`() = runTest {
        val result = repository.applyImport(
            importRequest(
                newProducts = listOf(
                    Product(
                        barcode = "22223333",
                        productName = "Deferred Product",
                        purchasePrice = 4.0,
                        retailPrice = 6.0,
                        supplierId = -1L,
                        categoryId = -2L
                    )
                ),
                pendingTempSuppliers = mapOf(-1L to "Deferred Supplier"),
                pendingTempCategories = mapOf(-2L to "Deferred Category"),
                pendingPriceHistory = listOf(
                    ImportPriceHistoryEntry(
                        barcode = "22223333",
                        type = "PURCHASE",
                        timestamp = "2026-04-03 10:00:00",
                        price = 4.2,
                        source = "IMPORT_SHEET"
                    )
                )
            )
        )

        assertEquals(ImportApplyResult.Success, result)
        val imported = repository.findProductByBarcode("22223333")!!
        val supplier = repository.getSupplierById(imported.supplierId!!)
        val category = repository.getCategoryById(imported.categoryId!!)
        val priceHistoryRows = repository.getAllPriceHistoryRows()

        assertEquals("Deferred Supplier", supplier?.name)
        assertEquals("Deferred Category", category?.name)
        assertTrue(
            priceHistoryRows.any {
                it.barcode == "22223333" &&
                    it.timestamp == "2026-04-03 10:00:00" &&
                    it.source == "IMPORT_SHEET"
            }
        )
    }

    @Test
    fun `applyImport rolls back products relations and price history on failure after product persistence`() = runTest {
        DefaultInventoryRepositoryTestHooks.afterProductsPersisted = {
            throw IllegalStateException("boom")
        }

        val result = repository.applyImport(
            importRequest(
                newProducts = listOf(
                    Product(
                        barcode = "77778888",
                        productName = "Rollback Product",
                        purchasePrice = 2.0,
                        retailPrice = 3.0,
                        supplierId = -1L,
                        categoryId = -2L
                    )
                ),
                pendingTempSuppliers = mapOf(-1L to "Rollback Supplier"),
                pendingTempCategories = mapOf(-2L to "Rollback Category"),
                pendingPriceHistory = listOf(
                    ImportPriceHistoryEntry(
                        barcode = "77778888",
                        type = "PURCHASE",
                        timestamp = "2026-04-03 11:00:00",
                        price = 2.2,
                        source = "IMPORT_SHEET"
                    )
                )
            )
        )

        assertTrue(result is ImportApplyResult.Failure)
        assertNull(repository.findProductByBarcode("77778888"))
        assertTrue(repository.getAllSuppliers().isEmpty())
        assertTrue(repository.getAllCategories().isEmpty())
        assertTrue(repository.getAllPriceHistoryRows().isEmpty())
    }

    @Test
    fun `applyImport rolls back when cancellation happens before commit`() = runTest {
        DefaultInventoryRepositoryTestHooks.afterProductsPersisted = {
            throw CancellationException("cancelled")
        }

        try {
            repository.applyImport(
                importRequest(
                    newProducts = listOf(
                        Product(
                            barcode = "99998888",
                            productName = "Cancelled Product",
                            purchasePrice = 2.0,
                            retailPrice = 3.0,
                            supplierId = -1L
                        )
                    ),
                    pendingTempSuppliers = mapOf(-1L to "Cancelled Supplier")
                )
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            assertNull(repository.findProductByBarcode("99998888"))
            assertTrue(repository.getAllSuppliers().isEmpty())
            assertTrue(repository.getAllPriceHistoryRows().isEmpty())
        }
    }

    @Test
    fun `applyImport rejects a second concurrent apply`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        DefaultInventoryRepositoryTestHooks.afterProductsPersisted = {
            started.complete(Unit)
            gate.await()
        }

        val firstRequest = importRequest(
            newProducts = listOf(
                Product(
                    barcode = "12340000",
                    productName = "First Apply",
                    purchasePrice = 1.0,
                    retailPrice = 2.0
                )
            )
        )
        val secondRequest = importRequest(
            newProducts = listOf(
                Product(
                    barcode = "12340001",
                    productName = "Second Apply",
                    purchasePrice = 1.0,
                    retailPrice = 2.0
                )
            )
        )

        val firstApply = async { repository.applyImport(firstRequest) }
        started.await()

        val secondResult = async { repository.applyImport(secondRequest) }.await()
        gate.complete(Unit)
        val firstResult = firstApply.await()

        assertEquals(ImportApplyResult.AlreadyRunning, secondResult)
        assertEquals(ImportApplyResult.Success, firstResult)
        assertNotNull(repository.findProductByBarcode("12340000"))
        assertNull(repository.findProductByBarcode("12340001"))
    }

    @Test
    fun `addSupplier returns existing supplier when name already exists`() = runTest {
        val first = repository.addSupplier("My Supplier")
        val second = repository.addSupplier("My Supplier")

        assertNotNull(first)
        assertEquals(first?.id, second?.id)
        assertEquals(1, repository.getAllSuppliers().size)
    }

    @Test
    fun `addCategory returns existing category when name already exists`() = runTest {
        val first = repository.addCategory("My Category")
        val second = repository.addCategory("My Category")

        assertNotNull(first)
        assertEquals(first?.id, second?.id)
        assertEquals(1, repository.getAllCategories().size)
    }

    @Test
    fun `getCatalogItems reports linked product counts for suppliers and categories`() = runTest {
        val supplier = repository.addSupplier("Alpha Supplier")!!
        val category = repository.addCategory("Seasonal")!!
        repository.addProduct(
            Product(
                barcode = "12121212",
                productName = "Linked Product",
                supplierId = supplier.id,
                categoryId = category.id,
                purchasePrice = 3.0,
                retailPrice = 5.0
            )
        )

        val supplierItems = repository.getCatalogItems(CatalogEntityKind.SUPPLIER)
        val categoryItems = repository.getCatalogItems(CatalogEntityKind.CATEGORY)

        assertEquals(1, supplierItems.single { it.id == supplier.id }.productCount)
        assertEquals(1, categoryItems.single { it.id == category.id }.productCount)
    }

    @Test
    fun `renameCatalogEntry keeps supplier id and refreshes linked product details`() = runTest {
        val supplier = repository.addSupplier("Legacy Supplier")!!
        repository.addProduct(
            Product(
                barcode = "34343434",
                productName = "Rename Me",
                supplierId = supplier.id,
                purchasePrice = 2.0,
                retailPrice = 4.0
            )
        )

        val renamed = repository.renameCatalogEntry(
            kind = CatalogEntityKind.SUPPLIER,
            id = supplier.id,
            newName = "Modern Supplier"
        )
        val productDetails = repository.getAllProductsWithDetails().single { it.product.barcode == "34343434" }

        assertEquals(supplier.id, renamed.id)
        assertEquals("Modern Supplier", repository.getSupplierById(supplier.id)?.name)
        assertEquals("Modern Supplier", productDetails.supplierName)
    }

    @Test
    fun `deleteCatalogEntry can reassign or clear linked products before deleting`() = runTest {
        val oldSupplier = repository.addSupplier("Old Supplier")!!
        val newSupplier = repository.addSupplier("New Supplier")!!
        val category = repository.addCategory("Disposable Category")!!

        repository.addProduct(
            Product(
                barcode = "56565656",
                productName = "Supplier Product",
                supplierId = oldSupplier.id,
                purchasePrice = 2.0,
                retailPrice = 3.0
            )
        )
        repository.addProduct(
            Product(
                barcode = "78787878",
                productName = "Category Product",
                categoryId = category.id,
                purchasePrice = 2.5,
                retailPrice = 4.5
            )
        )

        val supplierDelete = repository.deleteCatalogEntry(
            kind = CatalogEntityKind.SUPPLIER,
            id = oldSupplier.id,
            strategy = CatalogDeleteStrategy.ReplaceWithExisting(newSupplier.id)
        )
        val categoryDelete = repository.deleteCatalogEntry(
            kind = CatalogEntityKind.CATEGORY,
            id = category.id,
            strategy = CatalogDeleteStrategy.ClearAssignments
        )

        val supplierProduct = repository.findProductByBarcode("56565656")!!
        val categoryProduct = repository.findProductByBarcode("78787878")!!

        assertEquals(1, supplierDelete.affectedProducts)
        assertEquals(1, categoryDelete.affectedProducts)
        assertEquals(newSupplier.id, supplierProduct.supplierId)
        assertNull(categoryProduct.categoryId)
        assertNull(repository.getSupplierById(oldSupplier.id))
        assertNull(repository.getCategoryById(category.id))
    }

    @Test
    fun `getFilteredHistoryFlow respects custom date range`() = runTest {
        repository.insertHistoryEntry(historyEntry(id = "JAN", timestamp = "2026-01-10 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "FEB", timestamp = "2026-02-15 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "APPLY_IMPORT_1700000000000", timestamp = "2026-01-12 10:00:00"))

        val januaryEntries = repository.getFilteredHistoryFlow(
            DateFilter.CustomRange(
                startDate = LocalDate.of(2026, 1, 1),
                endDate = LocalDate.of(2026, 1, 31)
            )
        ).first()

        assertEquals(1, januaryEntries.size)
        assertEquals("JAN", januaryEntries.single().id)
    }

    @Test
    fun `getFilteredHistoryFlow all returns every entry ordered descending`() = runTest {
        repository.insertHistoryEntry(historyEntry(id = "OLD", timestamp = "2026-01-10 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "NEW", timestamp = "2026-02-15 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "FULL_IMPORT_1700000000000", timestamp = "2026-02-16 10:00:00"))

        val entries = repository.getFilteredHistoryFlow(DateFilter.All).first()

        assertEquals(listOf("NEW", "OLD"), entries.map { it.id })
    }

    @Test
    fun `getFilteredHistoryFlow current month includes both month boundaries`() = runTest {
        val today = LocalDate.now()
        val firstDay = today.withDayOfMonth(1)
        val lastDay = today.withDayOfMonth(today.lengthOfMonth())
        val beforeMonth = firstDay.minusDays(1)

        repository.insertHistoryEntry(historyEntry(id = "BEFORE", timestamp = "${beforeMonth} 23:59:59"))
        repository.insertHistoryEntry(historyEntry(id = "FIRST", timestamp = "${firstDay} 00:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "LAST", timestamp = "${lastDay} 23:59:59"))

        val entries = repository.getFilteredHistoryFlow(DateFilter.LastMonth).first()

        assertEquals(listOf("LAST", "FIRST"), entries.map { it.id })
    }

    @Test
    fun `getFilteredHistoryFlow previous month keeps previous boundary and excludes current month start`() = runTest {
        val currentMonthStart = LocalDate.now().withDayOfMonth(1)
        val previousMonthStart = currentMonthStart.minusMonths(1).withDayOfMonth(1)
        val previousMonthEnd = currentMonthStart.minusDays(1)

        repository.insertHistoryEntry(historyEntry(id = "PREV_END", timestamp = "${previousMonthEnd} 23:59:59"))
        repository.insertHistoryEntry(historyEntry(id = "CURR_START", timestamp = "${currentMonthStart} 00:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "PREV_START", timestamp = "${previousMonthStart} 00:00:00"))

        val entries = repository.getFilteredHistoryFlow(DateFilter.PreviousMonth).first()

        assertEquals(listOf("PREV_END", "PREV_START"), entries.map { it.id })
    }

    @Test
    fun `getFilteredHistoryListFlow respects custom date range`() = runTest {
        repository.insertHistoryEntry(historyEntry(id = "JAN", timestamp = "2026-01-10 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "FEB", timestamp = "2026-02-15 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "FULL_IMPORT_1700000000001", timestamp = "2026-01-18 10:00:00"))

        val januaryEntries = repository.getFilteredHistoryListFlow(
            DateFilter.CustomRange(
                startDate = LocalDate.of(2026, 1, 1),
                endDate = LocalDate.of(2026, 1, 31)
            )
        ).first()

        assertEquals(1, januaryEntries.size)
        assertEquals("JAN", januaryEntries.single().id)
    }

    @Test
    fun `getFilteredHistoryFlow keeps user entries with placeholder metadata while filtering technical prefixes`() = runTest {
        repository.insertHistoryEntry(
            historyEntry(id = "USER_VISIBLE", timestamp = "2026-03-28 09:00:00").copy(
                supplier = "—",
                category = "—",
                totalItems = 0,
                orderTotal = 0.0,
                paymentTotal = 0.0,
                missingItems = 0
            )
        )
        repository.insertHistoryEntry(historyEntry(id = "APPLY_IMPORT_1700000000002", timestamp = "2026-03-28 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "FULL_IMPORT_1700000000003", timestamp = "2026-03-28 11:00:00"))

        val entries = repository.getFilteredHistoryFlow(DateFilter.All).first()

        assertEquals(listOf("USER_VISIBLE"), entries.map { it.id })
    }

    @Test
    fun `hasHistoryEntriesFlow ignores technical-only history`() = runTest {
        assertEquals(false, repository.hasHistoryEntriesFlow().first())

        repository.insertHistoryEntry(historyEntry(id = "APPLY_IMPORT_1700000000004", timestamp = "2026-03-28 10:00:00"))
        repository.insertHistoryEntry(historyEntry(id = "FULL_IMPORT_1700000000005", timestamp = "2026-03-28 10:00:01"))

        assertEquals(false, repository.hasHistoryEntriesFlow().first())

        repository.insertHistoryEntry(historyEntry(id = "ONLY", timestamp = "2026-03-28 10:00:02"))

        assertEquals(true, repository.hasHistoryEntriesFlow().first())
    }

    @Test
    fun `getCurrentPricesForBarcodes returns current prices for requested barcodes`() = runTest {
        repository.addProduct(
            Product(
                barcode = "99990000",
                productName = "Snapshot",
                purchasePrice = 9.0,
                retailPrice = 12.0
            )
        )
        val saved = repository.findProductByBarcode("99990000")!!
        repository.recordPriceIfChanged(saved.id, "PURCHASE", 10.0, "2099-03-28 10:00:00", "UPDATE")

        val currentPrices = repository.getCurrentPricesForBarcodes(listOf("99990000", "missing"))

        assertEquals(10.0, currentPrices["99990000"]?.first)
        assertEquals(12.0, currentPrices["99990000"]?.second)
        assertEquals(null to null, currentPrices["missing"])
    }

    @Test
    fun `recordPriceIfChanged ignores unchanged value and getLastPrice returns latest`() = runTest {
        repository.addProduct(
            Product(
                barcode = "55556666",
                productName = "Tracked",
                purchasePrice = 1.0,
                retailPrice = 2.0
            )
        )
        val saved = repository.findProductByBarcode("55556666")!!

        repository.recordPriceIfChanged(saved.id, "PURCHASE", 1.0, "2099-03-28 11:00:00", "MANUAL")
        repository.recordPriceIfChanged(saved.id, "PURCHASE", 1.5, "2099-03-28 12:00:00", "MANUAL")

        val purchaseHistory = repository.getPriceSeries(saved.id, "PURCHASE").first()
        val currentSnapshot = repository.getCurrentPriceSnapshot()
        val snapshotRow = currentSnapshot.single { it.barcode == "55556666" }

        assertEquals(2, purchaseHistory.size)
        assertEquals(1.5, repository.getLastPrice(saved.id, "PURCHASE"))
        assertEquals(1.5, snapshotRow.purchasePrice)
        assertEquals(2.0, snapshotRow.retailPrice)
    }

    private fun historyEntry(
        id: String,
        timestamp: String
    ) = HistoryEntry(
        id = id,
        timestamp = timestamp,
        data = listOf(listOf("barcode"), listOf(id)),
        editable = listOf(listOf("", ""), listOf("", "")),
        complete = listOf(false, false),
        supplier = "",
        category = "",
        totalItems = 1,
        orderTotal = 0.0,
        paymentTotal = 0.0,
        missingItems = 0,
        syncStatus = SyncStatus.NOT_ATTEMPTED,
        wasExported = false
    )

    private fun importRequest(
        newProducts: List<Product> = emptyList(),
        updatedProducts: List<ProductUpdate> = emptyList(),
        pendingSupplierNames: Set<String> = emptySet(),
        pendingCategoryNames: Set<String> = emptySet(),
        pendingTempSuppliers: Map<Long, String> = emptyMap(),
        pendingTempCategories: Map<Long, String> = emptyMap(),
        pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList()
    ) = ImportApplyRequest(
        newProducts = newProducts,
        updatedProducts = updatedProducts,
        pendingSupplierNames = pendingSupplierNames,
        pendingCategoryNames = pendingCategoryNames,
        pendingTempSuppliers = pendingTempSuppliers,
        pendingTempCategories = pendingTempCategories,
        pendingPriceHistory = pendingPriceHistory
    )
}

package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.viewmodel.DateFilter
import java.io.IOException
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
import org.junit.Assert.assertFalse
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
    fun `observeSuppliersForHubSearch emits when suppliers table changes`() = runTest {
        assertTrue(repository.observeSuppliersForHubSearch("").first().isEmpty())
        repository.addSupplier("Acme")
        assertEquals("Acme", repository.observeSuppliersForHubSearch("").first().single().name)
    }

    @Test
    fun `observeCategoriesForHubSearch emits when categories table changes`() = runTest {
        assertTrue(repository.observeCategoriesForHubSearch("").first().isEmpty())
        repository.addCategory("Season")
        assertEquals("Season", repository.observeCategoriesForHubSearch("").first().single().name)
    }

    @Test
    fun `observeCatalogItems updates product counts when products change`() = runTest {
        val supplier = repository.addSupplier("Count Supplier")!!
        assertEquals(
            0,
            repository.observeCatalogItems(CatalogEntityKind.SUPPLIER, null).first()
                .single { it.id == supplier.id }.productCount
        )
        repository.addProduct(
            Product(
                barcode = "88880001",
                productName = "Item",
                supplierId = supplier.id,
                purchasePrice = 1.0,
                retailPrice = 2.0
            )
        )
        assertEquals(
            1,
            repository.observeCatalogItems(CatalogEntityKind.SUPPLIER, null).first()
                .single { it.id == supplier.id }.productCount
        )
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

    // --- Test bridge locale: identità remota stabile (task 007 / DEC-017) ---

    private fun buildMinimalHistoryEntry(id: String = "test_entry.xlsx"): HistoryEntry =
        HistoryEntry(
            id = id,
            timestamp = "2026-04-15 10:00:00",
            data = listOf(listOf("barcode", "productName")),
            editable = listOf(listOf("", "")),
            complete = listOf(false)
        )

    @Test
    fun `getOrCreateRemoteId returns non-null UUID for existing entry`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        val remoteId = repository.getOrCreateRemoteId(uid)
        assertNotNull(remoteId)
        assertTrue(remoteId!!.isNotBlank())
    }

    @Test
    fun `getOrCreateRemoteId returns same remote_id on repeated calls`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        val first = repository.getOrCreateRemoteId(uid)
        val second = repository.getOrCreateRemoteId(uid)
        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `getOrCreateRemoteId returns same remote_id under concurrent calls and stores one bridge row`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())

        val firstDeferred = async { repository.getOrCreateRemoteId(uid) }
        val secondDeferred = async { repository.getOrCreateRemoteId(uid) }

        val first = firstDeferred.await()
        val second = secondDeferred.await()

        assertNotNull(first)
        assertEquals(first, second)
        assertNotNull(repository.getRemoteRef(uid))

        val bridgeCount = db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM history_entry_remote_refs WHERE historyEntryUid = $uid")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        assertEquals(1, bridgeCount)
    }

    @Test
    fun `remote_id is stable after rename — uid stays local navigation key`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry("original_name.xlsx"))
        val remoteIdBefore = repository.getOrCreateRemoteId(uid)

        // Simula rename: modifica id e supplier, lascia uid invariato
        val entry = repository.getHistoryEntryByUid(uid)!!
        val renamed = entry.copy(id = "renamed_name.xlsx", supplier = "NuovoFornitore")
        repository.updateHistoryEntry(renamed)

        val remoteIdAfter = repository.getOrCreateRemoteId(uid)
        assertEquals("remote_id deve restare stabile dopo rename", remoteIdBefore, remoteIdAfter)

        // uid rimane la chiave locale — non cambia
        val reloaded = repository.getHistoryEntryByUid(uid)
        assertNotNull(reloaded)
        assertEquals(uid, reloaded!!.uid)
    }

    @Test
    fun `getOrCreateRemoteId returns null for non-existent uid`() = runTest {
        val remoteId = repository.getOrCreateRemoteId(historyEntryUid = 99999L)
        assertNull(remoteId)
    }

    @Test
    fun `deleteHistoryEntry also removes the bridge row`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        repository.getOrCreateRemoteId(uid) // crea il bridge

        val entry = repository.getHistoryEntryByUid(uid)!!
        assertNotNull(repository.getRemoteRef(uid))

        repository.deleteHistoryEntry(entry)

        assertNull(repository.getHistoryEntryByUid(uid))
        assertNull(repository.getRemoteRef(uid))
    }

    @Test
    fun `getRemoteRef returns null before first getOrCreateRemoteId call`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        assertNull(repository.getRemoteRef(uid))
    }

    @Test
    fun `toRemotePayload builds autosufficiente payload from entry and remoteId`() = runTest {
        val entry = buildMinimalHistoryEntry().copy(
            supplier = "Fornitore Test",
            category = "Cat Test",
            isManualEntry = true
        )
        val uid = repository.insertHistoryEntry(entry)
        val remoteId = repository.getOrCreateRemoteId(uid)!!

        val persisted = repository.getHistoryEntryByUid(uid)!!
        val payload = persisted.toRemotePayload(remoteId)

        assertEquals(remoteId, payload.remoteId)
        assertEquals(SESSION_PAYLOAD_VERSION, payload.payloadVersion)
        assertEquals("2026-04-15 10:00:00", payload.timestamp)
        assertEquals("Fornitore Test", payload.supplier)
        assertEquals("Cat Test", payload.category)
        assertTrue(payload.isManualEntry)
        // data non è vuota
        assertTrue(payload.data.isNotEmpty())
    }

    // --- Test pull remoto controllato (task 008) ---

    private fun remotePayload(
        remoteId: String = java.util.UUID.randomUUID().toString(),
        timestamp: String = "2026-04-15 12:00:00",
        supplier: String = "RemoteSupplier",
        category: String = "RemoteCat",
        isManualEntry: Boolean = false,
        data: List<List<String>> = listOf(listOf("barcode", "qty"), listOf("111", "2"))
    ) = SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = SESSION_PAYLOAD_VERSION,
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data
    )

    @Test
    fun `applyRemoteSessionPayload inserts new entry and bridge for unknown remoteId`() = runTest {
        val payload = remotePayload()
        val outcome = repository.applyRemoteSessionPayload(payload)

        assertEquals(RemoteSessionApplyOutcome.Inserted, outcome)
        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)
        assertNotNull(ref)
        val entry = repository.getHistoryEntryByUid(ref!!.historyEntryUid)
        assertNotNull(entry)
        assertEquals(payload.supplier, entry!!.supplier)
        assertEquals(payload.category, entry.category)
        assertEquals(payload.timestamp, entry.timestamp)
        assertEquals(payload.data, entry.data)
        assertEquals(payload.isManualEntry, entry.isManualEntry)
        // id usa remoteId (convenzione stabile non tecnica)
        assertEquals(payload.remoteId, entry.id)
    }

    @Test
    fun `applyRemoteSessionPayload returns Skipped on identical reapply`() = runTest {
        val payload = remotePayload()
        val first = repository.applyRemoteSessionPayload(payload)
        val second = repository.applyRemoteSessionPayload(payload)

        assertEquals(RemoteSessionApplyOutcome.Inserted, first)
        assertEquals(RemoteSessionApplyOutcome.Skipped, second)
        // Nessun duplicato: una sola entry con quel remoteId
        val count = db.historyEntryRemoteRefDao()
            .run { getByRemoteId(payload.remoteId) }
        assertNotNull(count)
    }

    @Test
    fun `applyRemoteSessionPayload updates existing entry on changed payload`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        val original = remotePayload(remoteId = remoteId, supplier = "Fornitore A")
        repository.applyRemoteSessionPayload(original)

        val updated = original.copy(supplier = "Fornitore B", timestamp = "2026-04-16 10:00:00")
        val outcome = repository.applyRemoteSessionPayload(updated)

        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)
        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)
        val entry = repository.getHistoryEntryByUid(ref!!.historyEntryUid)!!
        assertEquals("Fornitore B", entry.supplier)
        assertEquals("2026-04-16 10:00:00", entry.timestamp)
        // uid locale invariato dopo update
        assertEquals(ref.historyEntryUid, entry.uid)
    }

    @Test
    fun `applyRemoteSessionPayload returns UnsupportedVersion for unknown payloadVersion`() = runTest {
        val payload = remotePayload().copy(payloadVersion = 99)
        val outcome = repository.applyRemoteSessionPayload(payload)
        assertEquals(RemoteSessionApplyOutcome.UnsupportedVersion, outcome)
        // Nessuna entry creata
        assertNull(db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId))
    }

    @Test
    fun `applyRemoteSessionPayload materializes local defaults and initial summary for payload v1`() = runTest {
        val data = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "10", "3")
        )
        val payload = remotePayload(data = data)
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)
        val entry = repository.getHistoryEntryByUid(ref!!.historyEntryUid)!!

        // editable: due celle locali per riga (quantita/prezzo finale), non mirror della larghezza data
        assertEquals(List(data.size) { listOf("", "") }, entry.editable)
        // complete: false per ogni riga
        assertEquals(List(data.size) { false }, entry.complete)
        assertEquals(1, entry.totalItems)
        assertEquals(30.0, entry.orderTotal, 0.0001)
        assertEquals(30.0, entry.paymentTotal, 0.0001)
        assertEquals(1, entry.missingItems)
    }

    @Test
    fun `applyRemoteSessionPayload does not delete local entry absent from fetch`() = runTest {
        val local = buildMinimalHistoryEntry("local_session.xlsx")
        val localUid = repository.insertHistoryEntry(local)

        // Apply di un payload con remoteId diverso
        repository.applyRemoteSessionPayload(remotePayload())

        // L'entry locale non è stata toccata
        assertNotNull(repository.getHistoryEntryByUid(localUid))
    }

    @Test
    fun `applyRemoteSessionPayload timestamp is materialized but not used as conflict rule`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        // Prima apply: timestamp remoto recente
        val payloadNew = remotePayload(remoteId = remoteId, timestamp = "2026-12-01 10:00:00")
        repository.applyRemoteSessionPayload(payloadNew)

        // Seconda apply: timestamp remoto più vecchio ma altri campi diversi
        val payloadOld = payloadNew.copy(timestamp = "2026-01-01 08:00:00", supplier = "NuovoFornitore")
        val outcome = repository.applyRemoteSessionPayload(payloadOld)

        // L'update avviene comunque — il timestamp non decide chi vince
        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)
        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)
        val entry = repository.getHistoryEntryByUid(ref!!.historyEntryUid)!!
        assertEquals("NuovoFornitore", entry.supplier)
        assertEquals("2026-01-01 08:00:00", entry.timestamp)
    }

    @Test
    fun `applyRemoteSessionPayload resets local scaffolding when remote data changes`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        val originalData = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "5", "2")
        )
        repository.applyRemoteSessionPayload(remotePayload(remoteId = remoteId, data = originalData))

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        val locallyEdited = repository.getHistoryEntryByUid(ref.historyEntryUid)!!.copy(
            editable = listOf(listOf("header", "header"), listOf("99", "88")),
            complete = listOf(true, true),
            totalItems = 99,
            orderTotal = 999.0,
            paymentTotal = 777.0,
            missingItems = 0
        )
        repository.updateHistoryEntry(locallyEdited)

        val changedData = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "12", "3"),
            listOf("BBB", "4", "1")
        )
        val outcome = repository.applyRemoteSessionPayload(
            remotePayload(
                remoteId = remoteId,
                supplier = "RemoteUpdated",
                data = changedData
            )
        )

        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        assertEquals(changedData, entry.data)
        assertEquals(List(changedData.size) { listOf("", "") }, entry.editable)
        assertEquals(List(changedData.size) { false }, entry.complete)
        assertEquals(2, entry.totalItems)
        assertEquals(40.0, entry.orderTotal, 0.0001)
        assertEquals(40.0, entry.paymentTotal, 0.0001)
        assertEquals(2, entry.missingItems)
    }

    @Test
    fun `applyRemoteSessionPayloadBatch applies valid records and counts unsupported separately`() = runTest {
        val valid1 = remotePayload(supplier = "A")
        val valid2 = remotePayload(supplier = "B")
        val unsupported = remotePayload().copy(payloadVersion = 99)

        val result = repository.applyRemoteSessionPayloadBatch(listOf(valid1, valid2, unsupported))

        assertEquals(2, result.inserted)
        assertEquals(0, result.updated)
        assertEquals(0, result.skipped)
        assertEquals(1, result.unsupported)
        assertEquals(0, result.failed)
        assertEquals(3, result.totalProcessed)
        // Le entry valide sono presenti
        assertNotNull(db.historyEntryRemoteRefDao().getByRemoteId(valid1.remoteId))
        assertNotNull(db.historyEntryRemoteRefDao().getByRemoteId(valid2.remoteId))
        // Il payload non supportato non ha creato nulla
        assertNull(db.historyEntryRemoteRefDao().getByRemoteId(unsupported.remoteId))
    }

    @Test
    fun `applyRemoteSessionPayloadBatch one failed record does not block others`() = runTest {
        val valid = remotePayload(supplier = "GoodRecord")
        val orphanRemoteId = java.util.UUID.randomUUID().toString()
        val initialOrphanPayload = remotePayload(remoteId = orphanRemoteId, supplier = "Orphan")
        repository.applyRemoteSessionPayload(initialOrphanPayload)

        val orphanRef = db.historyEntryRemoteRefDao().getByRemoteId(orphanRemoteId)!!
        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.execSQL("PRAGMA foreign_keys = OFF")
        // Costruisce un orphan ref in modo deterministico senza dipendere dal cascade FK
        // su connessioni Room/SQLite diverse.
        sqliteDb.execSQL(
            "UPDATE history_entry_remote_refs " +
                "SET historyEntryUid = 999999999 " +
                "WHERE id = ${orphanRef.id}"
        )
        sqliteDb.execSQL("PRAGMA foreign_keys = ON")

        // Cambia fingerprint per evitare il fast-path Skipped sui bridge gia` allineati.
        val orphanPayload = initialOrphanPayload.copy(supplier = "OrphanChanged")
        val result = repository.applyRemoteSessionPayloadBatch(listOf(orphanPayload, valid))

        assertEquals(1, result.failed)
        assertEquals(1, result.inserted)
        // L'entry valida è stata comunque inserita
        assertNotNull(db.historyEntryRemoteRefDao().getByRemoteId(valid.remoteId))
    }

    @Test
    fun `applyRemoteSessionPayload new entry is visible in user-visible history flow`() = runTest {
        val payload = remotePayload(supplier = "VisibleSupplier", timestamp = "2026-04-15 14:00:00")
        repository.applyRemoteSessionPayload(payload)

        val entries = repository.getFilteredHistoryFlow(DateFilter.All).first()

        assertTrue(entries.any { it.supplier == "VisibleSupplier" })
    }

    // --- Test sync state locale minimo (task 009 / baseline conflitti) ---

    @Test
    fun `Inserted bridge has correct initial sync state`() = runTest {
        val payload = remotePayload()
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        assertEquals(0, ref.localChangeRevision)
        assertEquals(0, ref.lastSyncedLocalRevision)
        assertNotNull(ref.lastRemoteAppliedAt)
        assertEquals(payload.payloadFingerprint(), ref.lastRemotePayloadFingerprint)
    }

    @Test
    fun `Updated bridge has synced revision and updated fingerprint`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        repository.applyRemoteSessionPayload(remotePayload(remoteId = remoteId, supplier = "A"))

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!

        // Modifica locale payload-rilevante: entry diventa dirty
        repository.updateHistoryEntry(entry.copy(supplier = "Local"))

        val dirtyRef = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        assertEquals(1, dirtyRef.localChangeRevision)
        assertEquals(0, dirtyRef.lastSyncedLocalRevision)

        // Apply remoto con payload diverso: riallinea
        val newPayload = remotePayload(remoteId = remoteId, supplier = "B")
        val outcome = repository.applyRemoteSessionPayload(newPayload)

        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)
        val syncedRef = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        assertEquals(1, syncedRef.localChangeRevision)        // invariato: apply non è modifica locale
        assertEquals(1, syncedRef.lastSyncedLocalRevision)   // allineato alla revisione corrente
        assertNotNull(syncedRef.lastRemoteAppliedAt)
        assertEquals(newPayload.payloadFingerprint(), syncedRef.lastRemotePayloadFingerprint)
    }

    @Test
    fun `localChangeRevision increments on payload-relevant local update after bridge creation`() = runTest {
        val payload = remotePayload(supplier = "Initial")
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        assertEquals(0, ref.localChangeRevision)

        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        repository.updateHistoryEntry(entry.copy(supplier = "Modified"))

        val updatedRef = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        assertEquals(1, updatedRef.localChangeRevision)
        // lastSyncedLocalRevision non cambia con le modifiche locali
        assertEquals(0, updatedRef.lastSyncedLocalRevision)
    }

    @Test
    fun `localChangeRevision does not increment for non-payload-relevant changes`() = runTest {
        val payload = remotePayload()
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!

        // wasExported non è un campo payload: non deve incrementare la revisione
        repository.updateHistoryEntry(entry.copy(wasExported = true))

        val updatedRef = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        assertEquals(0, updatedRef.localChangeRevision)

        // syncStatus non è un campo payload: non deve incrementare la revisione
        repository.updateHistoryEntry(entry.copy(syncStatus = SyncStatus.SYNCED_SUCCESSFULLY))

        val refAfterSync = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        assertEquals(0, refAfterSync.localChangeRevision)
    }

    @Test
    fun `state machine allineato after Inserted`() = runTest {
        val payload = remotePayload()
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        // allineato: localChangeRevision == lastSyncedLocalRevision
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        assertNotNull(ref.lastRemoteAppliedAt)
    }

    @Test
    fun `state machine dirty locale after payload-relevant local change`() = runTest {
        val payload = remotePayload(data = listOf(listOf("barcode"), listOf("AAA")))
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!

        repository.updateHistoryEntry(entry.copy(data = listOf(listOf("barcode"), listOf("BBB"))))

        val dirtyRef = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        // dirty: localChangeRevision > lastSyncedLocalRevision
        assertTrue(dirtyRef.localChangeRevision > dirtyRef.lastSyncedLocalRevision)
    }

    @Test
    fun `fast-path Skipped does not modify bridge state`() = runTest {
        val payload = remotePayload()
        repository.applyRemoteSessionPayload(payload) // Inserted: fingerprint settato

        val refBefore = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!

        val outcome = repository.applyRemoteSessionPayload(payload) // stesso payload → Skipped fast-path
        assertEquals(RemoteSessionApplyOutcome.Skipped, outcome)

        val refAfter = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        // Bridge immutato
        assertEquals(refBefore.localChangeRevision, refAfter.localChangeRevision)
        assertEquals(refBefore.lastSyncedLocalRevision, refAfter.lastSyncedLocalRevision)
        assertEquals(refBefore.lastRemotePayloadFingerprint, refAfter.lastRemotePayloadFingerprint)
        assertEquals(refBefore.lastRemoteAppliedAt, refAfter.lastRemoteAppliedAt)
    }

    @Test
    fun `Skipped on dirty entry falls through to field comparison and applies remote payload`() = runTest {
        // Scenario: entry dirty + remote re-invia lo stesso payload precedente → Updated (remote wins)
        val remoteId = java.util.UUID.randomUUID().toString()
        val original = remotePayload(remoteId = remoteId, supplier = "Original")
        repository.applyRemoteSessionPayload(original) // Inserted

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!

        // Modifica locale: dirty
        repository.updateHistoryEntry(entry.copy(supplier = "LocalChange"))

        // Il remote re-invia lo stesso payload originale: fingerprint uguale MA entry è dirty
        // → fast-path non scatta → field comparison → supplier locale diverso → Updated (remote wins)
        val outcome = repository.applyRemoteSessionPayload(original)
        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)

        val updatedEntry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        assertEquals("Original", updatedEntry.supplier) // remote ha vinto
    }

    @Test
    fun `addProduct registers catalog cloud pending work`() = runTest {
        repository.addProduct(
            Product(
                barcode = "catalog-cloud-pending-1",
                productName = "Pending cloud"
            )
        )
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
    }

    @Test
    fun `syncCatalogWithRemote pushes product prices when product_remote_refs exists`() = runTest {
        val priceRemote = RecordingPriceRemote016()
        repository.addProduct(
            Product(
                barcode = "price-push-1",
                productName = "P",
                purchasePrice = 3.0,
                retailPrice = 4.0
            )
        )
        val p = repository.findProductByBarcode("price-push-1")!!
        db.openHelper.writableDatabase.execSQL("DELETE FROM product_remote_refs WHERE productId = ${p.id}")
        db.productRemoteRefDao().insert(
            ProductRemoteRef(productId = p.id, remoteId = "00000000-0000-4000-8000-0000000000aa")
        )
        val result = repository.syncCatalogWithRemote(FakeCatalogRemote016(), priceRemote, "00000000-0000-4000-8000-0000000000bb")
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertTrue(summary.pushedProductPrices >= 1)
        assertFalse(summary.priceSyncFailed)
        assertTrue(priceRemote.upsertBatches.isNotEmpty())
        val totalUpserted = priceRemote.upsertBatches.sumOf { it.size }
        assertTrue(totalUpserted >= 2)
        val bridgeDao = db.productPriceRemoteRefDao()
        val purchase = repository.getPriceSeries(p.id, "PURCHASE").first().first()
        assertNotNull(bridgeDao.getByProductPriceId(purchase.id))
    }

    @Test
    fun `pull product price links bridge when local business key already exists`() = runTest {
        val priceRemote = RecordingPriceRemote016()
        repository.addProduct(
            Product(
                barcode = "price-pull-dedup",
                productName = "D",
                purchasePrice = 5.0,
                retailPrice = 6.0
            )
        )
        val p = repository.findProductByBarcode("price-pull-dedup")!!
        val prodRemote = "00000000-0000-4000-8000-0000000000cc"
        db.openHelper.writableDatabase.execSQL("DELETE FROM product_remote_refs WHERE productId = ${p.id}")
        db.productRemoteRefDao().insert(ProductRemoteRef(productId = p.id, remoteId = prodRemote))
        val purchase = repository.getPriceSeries(p.id, "PURCHASE").first().first()
        val eff = purchase.effectiveAt
        priceRemote.fetchRows = listOf(
            InventoryProductPriceRow(
                id = "00000000-0000-4000-8000-0000000000dd",
                ownerUserId = "00000000-0000-4000-8000-0000000000ee",
                productId = prodRemote,
                type = "PURCHASE",
                price = purchase.price,
                effectiveAt = eff,
                source = "MANUAL",
                note = null,
                createdAt = eff
            )
        )
        val result = repository.syncCatalogWithRemote(FakeCatalogRemote016(), priceRemote, "00000000-0000-4000-8000-0000000000ee")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().pulledProductPrices)
        assertEquals(1, repository.getPriceSeries(p.id, "PURCHASE").first().size)
        assertEquals(
            "00000000-0000-4000-8000-0000000000dd",
            db.productPriceRemoteRefDao().getByProductPriceId(purchase.id)!!.remoteId
        )
    }

    @Test
    fun `syncCatalogWithRemote reports deferred prices when product has no remote ref`() = runTest {
        val priceRemote = RecordingPriceRemote016()
        val now = LocalDateTime.now().format(timestampFormatter)
        db.productDao().insert(
            Product(
                barcode = "def-1",
                productName = "X",
                purchasePrice = 1.0,
                retailPrice = 2.0
            )
        )
        val p = repository.findProductByBarcode("def-1")!!
        db.productPriceDao().insert(
            ProductPrice(
                productId = p.id,
                type = "PURCHASE",
                price = 1.0,
                effectiveAt = now,
                source = "MANUAL",
                createdAt = now
            )
        )
        db.productPriceDao().insert(
            ProductPrice(
                productId = p.id,
                type = "RETAIL",
                price = 2.0,
                effectiveAt = now,
                source = "MANUAL",
                createdAt = now
            )
        )
        val result = repository.syncCatalogWithRemote(FakeCatalogRemote016(), priceRemote, "00000000-0000-4000-8000-0000000000ff")
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertTrue(summary.deferredProductPricesNoProductRef >= 2)
        assertTrue(summary.pushedProductPrices >= 2)
        val purchase = repository.getPriceSeries(p.id, "PURCHASE").first().first()
        val retail = repository.getPriceSeries(p.id, "RETAIL").first().first()
        assertNotNull(db.productRemoteRefDao().getByProductId(p.id))
        assertNotNull(db.productPriceRemoteRefDao().getByProductPriceId(purchase.id))
        assertNotNull(db.productPriceRemoteRefDao().getByProductPriceId(retail.id))
    }

    @Test
    fun `021 bootstrap on empty Room with populated cloud materializes catalog prices and bridges`() = runTest {
        val owner = OWNER_021
        val bundle = bootstrapBundle021(owner)
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(bootstrapPurchasePrice021(owner))
        }

        val result = repository.syncCatalogWithRemote(
            FakeCatalogRemote016(bundle),
            priceRemote,
            owner
        )

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.pulledSuppliers)
        assertEquals(1, summary.pulledCategories)
        assertEquals(1, summary.pulledProducts)
        assertEquals(1, summary.pulledProductPrices)
        assertEquals(0, summary.pushedProductPrices)
        assertFalse(summary.priceSyncFailed)
        val supplier = repository.getAllSuppliers().single()
        val category = repository.getAllCategories().single()
        val product = repository.findProductByBarcode(BOOTSTRAP_BARCODE_021)!!
        val purchaseHistory = repository.getPriceSeries(product.id, "PURCHASE").first()
        assertEquals("Bootstrap Supplier 021", supplier.name)
        assertEquals("Bootstrap Category 021", category.name)
        assertEquals(supplier.id, product.supplierId)
        assertEquals(category.id, product.categoryId)
        assertEquals(1, purchaseHistory.size)
        assertEquals(12.34, purchaseHistory.single().price, 0.0001)
        assertEquals(BOOTSTRAP_SUPPLIER_REMOTE_021, db.supplierRemoteRefDao().getBySupplierId(supplier.id)!!.remoteId)
        assertEquals(BOOTSTRAP_CATEGORY_REMOTE_021, db.categoryRemoteRefDao().getByCategoryId(category.id)!!.remoteId)
        assertEquals(BOOTSTRAP_PRODUCT_REMOTE_021, db.productRemoteRefDao().getByProductId(product.id)!!.remoteId)
        assertEquals(BOOTSTRAP_PRICE_REMOTE_021, db.productPriceRemoteRefDao().getByProductPriceId(purchaseHistory.single().id)!!.remoteId)
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
    }

    @Test
    fun `021 reinstall with same account and populated cloud is equivalent to empty Room bootstrap`() = runTest {
        val owner = OWNER_021
        val remote = FakeCatalogRemote016(bootstrapBundle021(owner))
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(bootstrapPurchasePrice021(owner))
        }

        val result = repository.syncCatalogWithRemote(remote, priceRemote, owner)

        assertTrue(result.isSuccess)
        assertEquals(1, repository.getAllSuppliers().size)
        assertEquals(1, repository.getAllCategories().size)
        assertEquals(1, repository.getAllProducts().size)
        assertEquals(1, repository.getPriceSeries(repository.findProductByBarcode(BOOTSTRAP_BARCODE_021)!!.id, "PURCHASE").first().size)
        assertEquals(0, db.pendingCatalogTombstoneDao().count())
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
    }

    @Test
    fun `021 second manual refresh after bootstrap is idempotent without duplicated catalog or prices`() = runTest {
        val owner = OWNER_021
        val remote = FakeCatalogRemote016(bootstrapBundle021(owner))
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(bootstrapPurchasePrice021(owner))
        }

        val first = repository.syncCatalogWithRemote(remote, priceRemote, owner)
        assertTrue(first.isSuccess)
        val product = repository.findProductByBarcode(BOOTSTRAP_BARCODE_021)!!
        val firstPriceId = repository.getPriceSeries(product.id, "PURCHASE").first().single().id

        val second = repository.syncCatalogWithRemote(remote, priceRemote, owner)

        assertTrue(second.isSuccess)
        val summary = second.getOrThrow()
        assertEquals(0, summary.pushedSuppliers)
        assertEquals(0, summary.pushedCategories)
        assertEquals(0, summary.pushedProducts)
        assertEquals(0, summary.pulledSuppliers)
        assertEquals(0, summary.pulledCategories)
        assertEquals(0, summary.pulledProducts)
        assertEquals(0, summary.pulledProductPrices)
        assertEquals(0, summary.pushedProductPrices)
        assertFalse(summary.priceSyncFailed)
        assertEquals(1, repository.getAllSuppliers().size)
        assertEquals(1, repository.getAllCategories().size)
        assertEquals(1, repository.getAllProducts().size)
        assertEquals(listOf(firstPriceId), repository.getPriceSeries(product.id, "PURCHASE").first().map { it.id })
        assertTrue(priceRemote.upsertBatches.isEmpty())
    }

    @Test
    fun `021 price remote not configured still completes catalog bootstrap`() = runTest {
        val owner = OWNER_021
        val priceRemote = RecordingPriceRemote016(configured = false).apply {
            failIfCalled = true
        }

        val result = repository.syncCatalogWithRemote(
            FakeCatalogRemote016(bootstrapBundle021(owner)),
            priceRemote,
            owner
        )

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.pulledProducts)
        assertEquals(0, summary.pulledProductPrices)
        assertEquals(0, summary.pushedProductPrices)
        assertFalse(summary.priceSyncFailed)
        assertNotNull(repository.findProductByBarcode(BOOTSTRAP_BARCODE_021))
        assertEquals(0, priceRemote.fetchCount)
        assertTrue(priceRemote.upsertBatches.isEmpty())
    }

    @Test
    fun `021 aligned catalog with zero remote price rows does not become price failure`() = runTest {
        val owner = OWNER_021
        val result = repository.syncCatalogWithRemote(
            FakeCatalogRemote016(bootstrapBundle021(owner)),
            RecordingPriceRemote016(),
            owner
        )

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.pulledProducts)
        assertEquals(0, summary.pulledProductPrices)
        assertEquals(0, summary.pushedProductPrices)
        assertFalse(summary.priceSyncFailed)
        val product = repository.findProductByBarcode(BOOTSTRAP_BARCODE_021)!!
        assertTrue(repository.getPriceSeries(product.id, "PURCHASE").first().isEmpty())
    }

    @Test
    fun `021 price sync failure preserves catalog apply and manual retry pulls prices`() = runTest {
        val owner = OWNER_021
        val remote = FakeCatalogRemote016(bootstrapBundle021(owner))
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(bootstrapPurchasePrice021(owner))
            failNextFetch = IOException("price network")
        }

        val first = repository.syncCatalogWithRemote(remote, priceRemote, owner)

        assertTrue(first.isSuccess)
        assertTrue(first.getOrThrow().priceSyncFailed)
        val product = repository.findProductByBarcode(BOOTSTRAP_BARCODE_021)!!
        assertTrue(repository.getPriceSeries(product.id, "PURCHASE").first().isEmpty())
        assertNotNull(db.productRemoteRefDao().getByProductId(product.id))

        val second = repository.syncCatalogWithRemote(remote, priceRemote, owner)

        assertTrue(second.isSuccess)
        val summary = second.getOrThrow()
        assertFalse(summary.priceSyncFailed)
        assertEquals(1, summary.pulledProductPrices)
        assertEquals(1, repository.getPriceSeries(product.id, "PURCHASE").first().size)
    }

    @Test
    fun `021 drain tombstone failure aborts then manual retry drains and applies catalog once`() = runTest {
        val owner = OWNER_021
        val local = repository.addSupplier("Retry Tombstone 021")!!
        val tombstoneRemoteId = "00000000-0000-4000-8000-000000000219"
        db.openHelper.writableDatabase.execSQL("DELETE FROM supplier_remote_refs WHERE supplierId = ${local.id}")
        db.supplierRemoteRefDao().insert(SupplierRemoteRef(supplierId = local.id, remoteId = tombstoneRemoteId))
        repository.deleteCatalogEntry(
            CatalogEntityKind.SUPPLIER,
            local.id,
            CatalogDeleteStrategy.DeleteIfUnused
        )
        val remote = FakeCatalogRemote016(bootstrapBundle021(owner)).apply {
            failNextSupplierTombstone = IOException("network")
        }

        val first = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(), owner)

        assertTrue(first.isFailure)
        assertEquals(0, remote.fetchCount)
        assertEquals(0, repository.getAllProducts().size)
        val pendingAfterFailure = db.pendingCatalogTombstoneDao().listPendingOrdered().single()
        assertEquals(1, pendingAfterFailure.attemptCount)

        val second = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(), owner)

        assertTrue(second.isSuccess)
        assertEquals(0, db.pendingCatalogTombstoneDao().count())
        assertEquals(1, remote.supplierTombstones.size)
        assertEquals(tombstoneRemoteId, remote.supplierTombstones.single().id)
        assertEquals(1, remote.fetchCount)
        assertEquals(1, repository.getAllProducts().size)

        val third = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(), owner)

        assertTrue(third.isSuccess)
        assertEquals(2, remote.fetchCount)
        assertEquals(1, repository.getAllProducts().size)
        assertEquals(0, third.getOrThrow().pulledProducts)
    }

    @Test
    fun `019 delete supplier with remote ref enqueues tombstone and sync drains it`() = runTest {
        val s = repository.addSupplier("Tomb supplier019")!!
        val rid = "00000000-0000-4000-8000-000000000099"
        db.openHelper.writableDatabase.execSQL("DELETE FROM supplier_remote_refs WHERE supplierId = ${s.id}")
        db.supplierRemoteRefDao().insert(SupplierRemoteRef(supplierId = s.id, remoteId = rid))
        repository.deleteCatalogEntry(
            CatalogEntityKind.SUPPLIER,
            s.id,
            CatalogDeleteStrategy.DeleteIfUnused
        )
        assertNull(repository.getSupplierById(s.id))
        val pending = db.pendingCatalogTombstoneDao().listPendingOrdered()
        assertEquals(1, pending.size)
        assertEquals(PendingCatalogTombstoneEntityTypes.SUPPLIER, pending[0].entityType)
        assertEquals(rid, pending[0].remoteId)
        val remote = FakeCatalogRemote016()
        val priceRemote = RecordingPriceRemote016()
        val owner = "00000000-0000-4000-8000-0000000000aa"
        val result = repository.syncCatalogWithRemote(remote, priceRemote, owner)
        assertTrue(result.isSuccess)
        assertEquals(0, db.pendingCatalogTombstoneDao().count())
        assertEquals(1, remote.supplierTombstones.size)
        assertEquals(rid, remote.supplierTombstones[0].id)
        assertEquals(owner, remote.supplierTombstones[0].ownerUserId)
    }

    @Test
    fun `019 tombstone outbox dedup unique entity type and remote id`() = runTest {
        db.withTransaction {
            val dao = db.pendingCatalogTombstoneDao()
            dao.insert(
                PendingCatalogTombstone(
                    entityType = PendingCatalogTombstoneEntityTypes.PRODUCT,
                    remoteId = "00000000-0000-4000-8000-00000000ded1",
                    enqueuedAtMs = 1L,
                    attemptCount = 0
                )
            )
            dao.insert(
                PendingCatalogTombstone(
                    entityType = PendingCatalogTombstoneEntityTypes.PRODUCT,
                    remoteId = "00000000-0000-4000-8000-00000000ded1",
                    enqueuedAtMs = 2L,
                    attemptCount = 0
                )
            )
        }
        assertEquals(1, db.pendingCatalogTombstoneDao().count())
    }

    @Test
    fun `019 drain failure increments attempt count and leaves row`() = runTest {
        db.pendingCatalogTombstoneDao().insert(
            PendingCatalogTombstone(
                entityType = PendingCatalogTombstoneEntityTypes.SUPPLIER,
                remoteId = "00000000-0000-4000-8000-00000000bad1",
                enqueuedAtMs = 1L,
                attemptCount = 0
            )
        )
        val remote = FakeCatalogRemote016().apply {
            failNextSupplierTombstone = IOException("network")
        }
        val result = repository.syncCatalogWithRemote(
            remote,
            RecordingPriceRemote016(),
            "00000000-0000-4000-8000-0000000000bb"
        )
        assertTrue(result.isFailure)
        val row = db.pendingCatalogTombstoneDao().listPendingOrdered().single()
        assertEquals(1, row.attemptCount)
    }

    @Test
    fun `019 inbound tombstone without bridge does not delete local suppliers`() = runTest {
        repository.addSupplier("Local only")!!
        val before = repository.getAllSuppliers().size
        val bundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(
                    id = "00000000-0000-4000-8000-00000000dead",
                    ownerUserId = "00000000-0000-4000-8000-000000000001",
                    name = "Ghost",
                    deletedAt = "2026-04-18T10:00:00Z"
                )
            ),
            categories = emptyList(),
            products = emptyList()
        )
        val remote = FakeCatalogRemote016(bundle)
        val result = repository.syncCatalogWithRemote(
            remote,
            RecordingPriceRemote016(),
            "00000000-0000-4000-8000-000000000002"
        )
        assertTrue(result.isSuccess)
        assertEquals(before, repository.getAllSuppliers().size)
    }

    @Test
    fun `019 deleteProduct with remote ref enqueues product tombstone`() = runTest {
        repository.addProduct(
            Product(barcode = "tomb-p1", productName = "P")
        )
        val p = repository.findProductByBarcode("tomb-p1")!!
        val rid = "00000000-0000-4000-8000-00000000cafe"
        db.openHelper.writableDatabase.execSQL("DELETE FROM product_remote_refs WHERE productId = ${p.id}")
        db.productRemoteRefDao().insert(ProductRemoteRef(productId = p.id, remoteId = rid))
        repository.deleteProduct(p)
        assertNull(repository.findProductByBarcode("tomb-p1"))
        val pending = db.pendingCatalogTombstoneDao().listPendingOrdered()
        assertEquals(1, pending.size)
        assertEquals(PendingCatalogTombstoneEntityTypes.PRODUCT, pending[0].entityType)
        assertEquals(rid, pending[0].remoteId)
    }
}

private class FakeCatalogRemote016(
    private val bundle: InventoryCatalogFetchBundle = InventoryCatalogFetchBundle(
        emptyList(),
        emptyList(),
        emptyList()
    )
) : CatalogRemoteDataSource {
    override val isConfigured get() = true
    val supplierTombstones = mutableListOf<CatalogTombstonePatch>()
    val categoryTombstones = mutableListOf<CatalogTombstonePatch>()
    val productTombstones = mutableListOf<CatalogTombstonePatch>()
    val upsertedSuppliers = mutableListOf<List<InventorySupplierRow>>()
    val upsertedCategories = mutableListOf<List<InventoryCategoryRow>>()
    val upsertedProducts = mutableListOf<List<InventoryProductRow>>()
    var fetchCount = 0
    var failNextFetch: Throwable? = null
    var failNextSupplierTombstone: Throwable? = null
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> {
        upsertedSuppliers.add(rows)
        return Result.success(Unit)
    }
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> {
        upsertedCategories.add(rows)
        return Result.success(Unit)
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        upsertedProducts.add(rows)
        return Result.success(Unit)
    }
    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> {
        fetchCount++
        failNextFetch?.let { t ->
            failNextFetch = null
            return Result.failure(t)
        }
        return Result.success(bundle)
    }
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> {
        failNextSupplierTombstone?.let { t ->
            failNextSupplierTombstone = null
            return Result.failure(t)
        }
        supplierTombstones.add(patch)
        return Result.success(Unit)
    }
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> {
        categoryTombstones.add(patch)
        return Result.success(Unit)
    }
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> {
        productTombstones.add(patch)
        return Result.success(Unit)
    }
}

private class RecordingPriceRemote016(
    private val configured: Boolean = true
) : ProductPriceRemoteDataSource {
    val upsertBatches = mutableListOf<List<InventoryProductPriceRow>>()
    var fetchRows: List<InventoryProductPriceRow> = emptyList()
    var fetchCount = 0
    var failIfCalled = false
    var failNextFetch: Throwable? = null
    var failNextUpsert: Throwable? = null
    override val isConfigured get() = configured
    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> {
        if (failIfCalled) error("ProductPriceRemoteDataSource should not be called")
        failNextUpsert?.let { t ->
            failNextUpsert = null
            return Result.failure(t)
        }
        upsertBatches.add(rows)
        return Result.success(Unit)
    }
    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> {
        if (failIfCalled) error("ProductPriceRemoteDataSource should not be called")
        fetchCount++
        failNextFetch?.let { t ->
            failNextFetch = null
            return Result.failure(t)
        }
        return Result.success(fetchRows)
    }
}

private const val OWNER_021 = "00000000-0000-4000-8000-000000000210"
private const val BOOTSTRAP_SUPPLIER_REMOTE_021 = "00000000-0000-4000-8000-000000000211"
private const val BOOTSTRAP_CATEGORY_REMOTE_021 = "00000000-0000-4000-8000-000000000212"
private const val BOOTSTRAP_PRODUCT_REMOTE_021 = "00000000-0000-4000-8000-000000000213"
private const val BOOTSTRAP_PRICE_REMOTE_021 = "00000000-0000-4000-8000-000000000214"
private const val BOOTSTRAP_BARCODE_021 = "bootstrap-021"

private fun bootstrapBundle021(owner: String): InventoryCatalogFetchBundle =
    InventoryCatalogFetchBundle(
        suppliers = listOf(
            InventorySupplierRow(
                id = BOOTSTRAP_SUPPLIER_REMOTE_021,
                ownerUserId = owner,
                name = "Bootstrap Supplier 021"
            )
        ),
        categories = listOf(
            InventoryCategoryRow(
                id = BOOTSTRAP_CATEGORY_REMOTE_021,
                ownerUserId = owner,
                name = "Bootstrap Category 021"
            )
        ),
        products = listOf(
            InventoryProductRow(
                id = BOOTSTRAP_PRODUCT_REMOTE_021,
                ownerUserId = owner,
                barcode = BOOTSTRAP_BARCODE_021,
                productName = "Bootstrap Product 021",
                purchasePrice = 12.34,
                retailPrice = 18.99,
                supplierId = BOOTSTRAP_SUPPLIER_REMOTE_021,
                categoryId = BOOTSTRAP_CATEGORY_REMOTE_021,
                stockQuantity = 7.0
            )
        )
    )

private fun bootstrapPurchasePrice021(owner: String): InventoryProductPriceRow =
    InventoryProductPriceRow(
        id = BOOTSTRAP_PRICE_REMOTE_021,
        ownerUserId = owner,
        productId = BOOTSTRAP_PRODUCT_REMOTE_021,
        type = "PURCHASE",
        price = 12.34,
        effectiveAt = "2026-04-18 10:00:00",
        source = "REMOTE_BOOTSTRAP",
        note = null,
        createdAt = "2026-04-18 10:00:00"
    )

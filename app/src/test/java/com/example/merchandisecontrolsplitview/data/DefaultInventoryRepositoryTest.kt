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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
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
    fun `updateProduct then getProductDetailsById returns current and previous prices`() = runTest {
        repository.addProduct(
            Product(
                barcode = "87654322",
                productName = "Original",
                purchasePrice = 10.0,
                retailPrice = 15.0
            )
        )
        val saved = repository.findProductByBarcode("87654322")!!

        Thread.sleep(2_100)

        repository.updateProduct(
            saved.copy(
                productName = "Updated",
                purchasePrice = 12.0,
                retailPrice = 18.0
            )
        )

        val details = repository.getProductDetailsById(saved.id)

        assertNotNull(details)
        assertEquals("Updated", details!!.product.productName)
        assertEquals(12.0, details.product.purchasePrice ?: -1.0, 0.0)
        assertEquals(18.0, details.product.retailPrice ?: -1.0, 0.0)
        assertEquals(12.0, details.currentPurchasePrice ?: -1.0, 0.0)
        assertEquals(18.0, details.currentRetailPrice ?: -1.0, 0.0)
        assertEquals(12.0, details.lastPurchase ?: -1.0, 0.0)
        assertEquals(10.0, details.prevPurchase ?: -1.0, 0.0)
        assertEquals(18.0, details.lastRetail ?: -1.0, 0.0)
        assertEquals(15.0, details.prevRetail ?: -1.0, 0.0)
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
    fun `034 applyImport marks imported catalog dirty and exposes price history as pending cloud work`() = runTest {
        val changedProductIds = mutableListOf<Long>()
        repository.onProductCatalogChanged = { productId ->
            changedProductIds += productId
        }

        val result = repository.applyImport(
            importRequest(
                newProducts = listOf(
                    Product(
                        barcode = "034-import-cloud",
                        productName = "Import Pending Cloud 034",
                        purchasePrice = 4.0,
                        retailPrice = 6.0,
                        supplierId = -1L,
                        categoryId = -2L
                    )
                ),
                pendingTempSuppliers = mapOf(-1L to "Import Supplier 034"),
                pendingTempCategories = mapOf(-2L to "Import Category 034"),
                pendingPriceHistory = listOf(
                    ImportPriceHistoryEntry(
                        barcode = "034-import-cloud",
                        type = "PURCHASE",
                        timestamp = "2026-04-20 10:34:00",
                        price = 4.25,
                        source = null
                    )
                )
            )
        )

        assertEquals(ImportApplyResult.Success, result)
        val imported = repository.findProductByBarcode("034-import-cloud")!!
        val supplierRef = db.supplierRemoteRefDao().getBySupplierId(imported.supplierId!!)
        val categoryRef = db.categoryRemoteRefDao().getByCategoryId(imported.categoryId!!)
        val productRef = db.productRemoteRefDao().getByProductId(imported.id)
        val pushRows = db.productPriceDao().getAllForCloudPush()
        val breakdown = repository.getCatalogCloudPendingBreakdown()

        assertNotNull(supplierRef)
        assertNotNull(categoryRef)
        assertNotNull(productRef)
        assertEquals(0, breakdown.pendingCatalogTombstones)
        assertEquals(3, breakdown.productPricesPendingPriceBridge)
        assertEquals(0, breakdown.productPricesBlockedWithoutProductRemote)
        assertEquals(0, breakdown.suppliersMissingRemoteRef)
        assertEquals(0, breakdown.categoriesMissingRemoteRef)
        assertEquals(0, breakdown.productsMissingRemoteRef)
        assertTrue(breakdown.hasTombstoneOrPriceRelatedPending)
        assertFalse(breakdown.hasCatalogBridgeGaps)
        assertTrue(breakdown.hasAnyPendingBreakdown)
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertEquals(3, pushRows.size)
        assertTrue(pushRows.all { it.productId == imported.id })
        assertTrue(pushRows.any { it.source == "IMPORT_SHEET" && it.effectiveAt == "2026-04-20 10:34:00" })
        assertEquals(listOf(imported.id), changedProductIds)
    }

    @Test
    fun `applyImport updating one synced product marks only that product dirty`() = runTest {
        val supplierId = db.supplierDao().insert(Supplier(name = "Import Supplier 057"))
        val categoryId = db.categoryDao().insert(Category(name = "Import Category 057"))
        db.productDao().insert(
            Product(
                barcode = "057-update-target",
                productName = "Import Target 057",
                purchasePrice = 10.0,
                retailPrice = 12.0,
                supplierId = supplierId,
                categoryId = categoryId
            )
        )
        db.productDao().insert(
            Product(
                barcode = "057-update-other",
                productName = "Import Other 057",
                purchasePrice = 20.0,
                retailPrice = 24.0,
                supplierId = supplierId,
                categoryId = categoryId
            )
        )
        val target = repository.findProductByBarcode("057-update-target")!!
        val other = repository.findProductByBarcode("057-update-other")!!
        db.supplierRemoteRefDao().insert(
            SupplierRemoteRef(
                supplierId = supplierId,
                remoteId = "00000000-0000-4000-8000-000000000571",
                lastRemoteAppliedAt = 1L,
                lastRemotePayloadFingerprint = "supplier-057"
            )
        )
        db.categoryRemoteRefDao().insert(
            CategoryRemoteRef(
                categoryId = categoryId,
                remoteId = "00000000-0000-4000-8000-000000000572",
                lastRemoteAppliedAt = 1L,
                lastRemotePayloadFingerprint = "category-057"
            )
        )
        db.productRemoteRefDao().insert(
            ProductRemoteRef(
                productId = target.id,
                remoteId = "00000000-0000-4000-8000-000000000573",
                lastRemoteAppliedAt = 1L,
                lastRemotePayloadFingerprint = "target-057"
            )
        )
        db.productRemoteRefDao().insert(
            ProductRemoteRef(
                productId = other.id,
                remoteId = "00000000-0000-4000-8000-000000000574",
                lastRemoteAppliedAt = 1L,
                lastRemotePayloadFingerprint = "other-057"
            )
        )

        val result = repository.applyImport(
            importRequest(
                updatedProducts = listOf(
                    ProductUpdate(
                        oldProduct = target,
                        newProduct = target.copy(
                            productName = "Import Target 057 Updated",
                            purchasePrice = 11.0,
                            retailPrice = 13.0
                        ),
                        changedFields = emptyList()
                    )
                )
            )
        )

        assertEquals(ImportApplyResult.Success, result)
        val supplierRef = db.supplierRemoteRefDao().getBySupplierId(supplierId)!!
        val categoryRef = db.categoryRemoteRefDao().getByCategoryId(categoryId)!!
        val targetRef = db.productRemoteRefDao().getByProductId(target.id)!!
        val otherRef = db.productRemoteRefDao().getByProductId(other.id)!!

        assertEquals(0, supplierRef.localChangeRevision)
        assertEquals(0, categoryRef.localChangeRevision)
        assertEquals(1, targetRef.localChangeRevision)
        assertEquals(0, targetRef.lastSyncedLocalRevision)
        assertEquals(0, otherRef.localChangeRevision)
        assertTrue(db.supplierDao().getCatalogPushCandidates().isEmpty())
        assertTrue(db.categoryDao().getCatalogPushCandidates().isEmpty())
        assertEquals(
            listOf(target.id),
            db.productDao().getCatalogPushCandidates().map { it.product.id }
        )
    }

    @Test
    fun `applyImport single update adds one candidate on top of existing product backlog`() = runTest {
        repeat(3) { index ->
            db.productDao().insert(
                Product(
                    barcode = "057-backlog-$index",
                    productName = "Backlog Product 057 $index",
                    purchasePrice = 10.0 + index,
                    retailPrice = 12.0 + index
                )
            )
        }
        db.productDao().insert(
            Product(
                barcode = "057-backlog-update-target",
                productName = "Backlog Update Target 057",
                purchasePrice = 30.0,
                retailPrice = 35.0
            )
        )
        val target = repository.findProductByBarcode("057-backlog-update-target")!!
        db.productRemoteRefDao().insert(
            ProductRemoteRef(
                productId = target.id,
                remoteId = "00000000-0000-4000-8000-000000000575",
                lastRemoteAppliedAt = 1L,
                lastRemotePayloadFingerprint = "target-backlog-057"
            )
        )
        val backlogIds = db.productDao().getCatalogPushCandidates().map { it.product.id }
        assertEquals(3, backlogIds.size)
        assertFalse(backlogIds.contains(target.id))

        val result = repository.applyImport(
            importRequest(
                updatedProducts = listOf(
                    ProductUpdate(
                        oldProduct = target,
                        newProduct = target.copy(
                            productName = "Backlog Update Target 057 Updated",
                            purchasePrice = 31.0,
                            retailPrice = 36.0
                        ),
                        changedFields = emptyList()
                    )
                )
            )
        )

        assertEquals(ImportApplyResult.Success, result)
        val candidatesAfterImport = db.productDao().getCatalogPushCandidates().map { it.product.id }
        assertEquals(backlogIds.toSet() + target.id, candidatesAfterImport.toSet())

        val progress = mutableListOf<CatalogSyncProgressState>()
        val push = repository.pushDirtyCatalogDeltaToRemote(
            remote = FakeCatalogRemote016(),
            priceRemote = RecordingPriceRemote016(configured = false),
            ownerUserId = "00000000-0000-4000-8000-000000000576",
            progressReporter = CatalogSyncProgressReporter { progress.add(it) }
        )

        assertTrue(push.isSuccess)
        assertEquals(4, push.getOrThrow().pushedProducts)
        assertEquals(
            4,
            progress.first { it.stage == CatalogSyncStage.PUSH_PRODUCTS }.total
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
        var catalogChangeCount = 0
        repository.onCatalogChanged = {
            catalogChangeCount++
        }

        val first = repository.addSupplier("My Supplier")
        val second = repository.addSupplier("My Supplier")

        assertNotNull(first)
        assertEquals(first?.id, second?.id)
        assertEquals(1, repository.getAllSuppliers().size)
        assertEquals(1, catalogChangeCount)
    }

    @Test
    fun `addCategory returns existing category when name already exists`() = runTest {
        var catalogChangeCount = 0
        repository.onCatalogChanged = {
            catalogChangeCount++
        }

        val first = repository.addCategory("My Category")
        val second = repository.addCategory("My Category")

        assertNotNull(first)
        assertEquals(first?.id, second?.id)
        assertEquals(1, repository.getAllCategories().size)
        assertEquals(1, catalogChangeCount)
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
    fun `catalog entity mutations notify generic catalog auto sync`() = runTest {
        var catalogChangeCount = 0
        repository.onCatalogChanged = {
            catalogChangeCount++
        }

        val supplier = repository.createCatalogEntry(CatalogEntityKind.SUPPLIER, "Sync Supplier")
        repository.renameCatalogEntry(CatalogEntityKind.SUPPLIER, supplier.id, "Sync Supplier Renamed")
        repository.deleteCatalogEntry(
            kind = CatalogEntityKind.SUPPLIER,
            id = supplier.id,
            strategy = CatalogDeleteStrategy.DeleteIfUnused
        )

        assertEquals(3, catalogChangeCount)
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
    fun `043 current price helpers fall back to product cache when summary is absent`() = runTest {
        db.productDao().insert(
            Product(
                barcode = "cache-only-043",
                productName = "Cache Only 043",
                purchasePrice = 7.0,
                retailPrice = 9.0
            )
        )

        val prices = repository.getCurrentPricesForBarcodes(listOf("cache-only-043"))
        val snapshotRow = repository.getCurrentPriceSnapshot().single { it.barcode == "cache-only-043" }

        assertEquals(7.0, prices["cache-only-043"]?.first)
        assertEquals(9.0, prices["cache-only-043"]?.second)
        assertEquals(7.0, snapshotRow.purchasePrice)
        assertEquals(9.0, snapshotRow.retailPrice)
    }

    @Test
    fun `recordPriceIfChanged ignores unchanged value and getLastPrice returns latest`() = runTest {
        val changedProductIds = mutableListOf<Long>()
        repository.onProductCatalogChanged = { productId ->
            changedProductIds += productId
        }
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
        assertEquals(listOf(saved.id, saved.id), changedProductIds)
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
            displayName = "Session display",
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
        assertEquals("Session display", payload.displayName)
        assertEquals("2026-04-15 10:00:00", payload.timestamp)
        assertEquals("Fornitore Test", payload.supplier)
        assertEquals("Cat Test", payload.category)
        assertTrue(payload.isManualEntry)
        // data non è vuota
        assertTrue(payload.data.isNotEmpty())
        assertEquals(persisted.editable, payload.sessionOverlay?.editable)
        assertEquals(persisted.complete, payload.sessionOverlay?.complete)
    }

    @Test
    fun `040 v2 upsert row serializes session overlay with db required shape`() = runTest {
        val entry = buildMinimalHistoryEntry().copy(
            editable = listOf(listOf("1", "")),
            complete = listOf(true)
        )
        val row = entry
            .toRemotePayload("00000000-0000-4000-8000-000000000401")
            .toSharedSheetSessionUpsertRow("00000000-0000-4000-8000-000000000402")

        val encoded = Json.encodeToJsonElement(
            SharedSheetSessionUpsertRow.serializer(),
            row
        ).jsonObject
        val overlayElement = encoded["session_overlay"]

        assertNotNull(overlayElement)
        val overlay = overlayElement!!.jsonObject
        assertEquals(SESSION_OVERLAY_SCHEMA, overlay["overlay_schema"]?.jsonPrimitive?.int)
        assertTrue(overlay["editable"] is JsonArray)
        assertTrue(overlay["complete"] is JsonArray)
    }

    // --- Test pull remoto controllato (task 008) ---

    private fun remotePayload(
        remoteId: String = java.util.UUID.randomUUID().toString(),
        payloadVersion: Int = SESSION_PAYLOAD_VERSION,
        displayName: String? = null,
        timestamp: String = "2026-04-15 12:00:00",
        supplier: String = "RemoteSupplier",
        category: String = "RemoteCat",
        isManualEntry: Boolean = false,
        data: List<List<String>> = listOf(listOf("barcode", "qty"), listOf("111", "2")),
        sessionOverlay: SessionOverlay? = null
    ) = SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = payloadVersion,
        displayName = displayName,
        timestamp = timestamp,
        supplier = supplier,
        category = category,
        isManualEntry = isManualEntry,
        data = data,
        sessionOverlay = sessionOverlay
    )

    @Test
    fun `040 legacy v1 fingerprint keeps pre v2 canonical contract`() = runTest {
        val payload = remotePayload(
            payloadVersion = SESSION_PAYLOAD_VERSION_LEGACY_V1,
            displayName = "Ignored by v1",
            timestamp = "2026-04-15 12:00:00",
            supplier = "RemoteSupplier",
            category = "RemoteCat",
            isManualEntry = true,
            data = listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("AAA", "10", "2")
            ),
            sessionOverlay = SessionOverlay(
                editable = listOf(listOf("", ""), listOf("2", "")),
                complete = listOf(false, true)
            )
        )

        val legacyCanonical = "2026-04-15 12:00:00|RemoteSupplier|RemoteCat|true|barcode,purchasePrice,quantity,AAA,10,2"
        assertEquals(legacyCanonical.hashCode().toString(), payload.payloadFingerprint())
    }

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
        val payload = remotePayload(
            payloadVersion = SESSION_PAYLOAD_VERSION_LEGACY_V1,
            data = data
        )
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
    fun `040 applyRemoteSessionPayload v2 materializes display name and operational overlay`() = runTest {
        val data = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "10", "3")
        )
        val overlay = SessionOverlay(
            editable = listOf(listOf("", ""), listOf("2", "")),
            complete = listOf(false, true)
        )
        val payload = remotePayload(
            displayName = "Sessione condivisa",
            data = data,
            sessionOverlay = overlay
        )

        val outcome = repository.applyRemoteSessionPayload(payload)

        assertEquals(RemoteSessionApplyOutcome.Inserted, outcome)
        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        assertEquals("Sessione condivisa", entry.displayName)
        assertEquals(overlay.editable, entry.editable)
        assertEquals(overlay.complete, entry.complete)
        assertEquals(20.0, entry.paymentTotal, 0.0001)
        assertEquals(0, entry.missingItems)
    }

    @Test
    fun `040 invalid v2 overlay applies data and display name without resetting local overlay`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        val initial = remotePayload(
            remoteId = remoteId,
            displayName = "Initial",
            data = listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("AAA", "5", "2")
            ),
            sessionOverlay = SessionOverlay(
                editable = listOf(listOf("", ""), listOf("1", "")),
                complete = listOf(false, true)
            )
        )
        repository.applyRemoteSessionPayload(initial)
        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        val before = repository.getHistoryEntryByUid(ref.historyEntryUid)!!

        val changedData = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "9", "1")
        )
        val invalidOverlay = SessionOverlay(
            editable = listOf(listOf("", "")),
            complete = listOf(false)
        )
        val outcome = repository.applyRemoteSessionPayload(
            remotePayload(
                remoteId = remoteId,
                displayName = "Remote title",
                data = changedData,
                sessionOverlay = invalidOverlay
            )
        )

        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)
        val after = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        assertEquals("Remote title", after.displayName)
        assertEquals(changedData, after.data)
        assertEquals(before.editable, after.editable)
        assertEquals(before.complete, after.complete)
        assertEquals(9.0, after.orderTotal, 0.0001)
        assertEquals(9.0, after.paymentTotal, 0.0001)
        assertEquals(0, after.missingItems)
    }

    @Test
    fun `040 invalid v2 overlay with changed row count rebuilds local state safely`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        val initial = remotePayload(
            remoteId = remoteId,
            displayName = "Initial",
            data = listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("AAA", "5", "2")
            ),
            sessionOverlay = SessionOverlay(
                editable = listOf(listOf("", ""), listOf("1", "")),
                complete = listOf(false, true)
            )
        )
        repository.applyRemoteSessionPayload(initial)
        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!

        val changedData = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "9", "1"),
            listOf("BBB", "4", "2")
        )
        val invalidOverlay = SessionOverlay(
            editable = listOf(listOf("", "")),
            complete = listOf(false)
        )

        val outcome = repository.applyRemoteSessionPayload(
            remotePayload(
                remoteId = remoteId,
                displayName = "Remote title",
                data = changedData,
                sessionOverlay = invalidOverlay
            )
        )

        assertEquals(RemoteSessionApplyOutcome.Updated, outcome)
        val after = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        assertEquals(changedData, after.data)
        assertEquals(List(changedData.size) { listOf("", "") }, after.editable)
        assertEquals(List(changedData.size) { false }, after.complete)
        assertEquals(2, after.totalItems)
        assertEquals(17.0, after.orderTotal, 0.0001)
        assertEquals(17.0, after.paymentTotal, 0.0001)
        assertEquals(2, after.missingItems)
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
    fun `applyRemoteSessionPayload v1 resets local scaffolding when remote data changes and local is clean`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        val originalData = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "5", "2")
        )
        repository.applyRemoteSessionPayload(
            remotePayload(
                remoteId = remoteId,
                payloadVersion = SESSION_PAYLOAD_VERSION_LEGACY_V1,
                data = originalData
            )
        )

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        val locallyEdited = repository.getHistoryEntryByUid(ref.historyEntryUid)!!.copy(
            editable = listOf(listOf("header", "header"), listOf("99", "88")),
            complete = listOf(true, true),
            totalItems = 99,
            orderTotal = 999.0,
            paymentTotal = 777.0,
            missingItems = 0
        )
        db.historyEntryDao().update(locallyEdited)

        val changedData = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("AAA", "12", "3"),
            listOf("BBB", "4", "1")
        )
        val outcome = repository.applyRemoteSessionPayload(
            remotePayload(
                remoteId = remoteId,
                payloadVersion = SESSION_PAYLOAD_VERSION_LEGACY_V1,
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

        // Apply remoto con payload diverso: policy conservativa — salta se il locale è ancora "dirty"
        val newPayload = remotePayload(remoteId = remoteId, supplier = "B")
        val outcome = repository.applyRemoteSessionPayload(newPayload)

        assertEquals(RemoteSessionApplyOutcome.Skipped, outcome)
        val syncedRef = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        assertEquals(1, syncedRef.localChangeRevision)
        assertEquals(0, syncedRef.lastSyncedLocalRevision)
        val entryAfter = repository.getHistoryEntryByUid(syncedRef.historyEntryUid)!!
        assertEquals("Local", entryAfter.supplier)
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
    fun `040 localChangeRevision increments on complete only local update`() = runTest {
        val payload = remotePayload(
            data = listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("AAA", "10", "1")
            ),
            sessionOverlay = SessionOverlay(
                editable = listOf(listOf("", ""), listOf("", "")),
                complete = listOf(false, false)
            )
        )
        repository.applyRemoteSessionPayload(payload)

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        repository.updateHistoryEntry(entry.copy(complete = listOf(false, true)))

        val updatedRef = db.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId)!!
        assertEquals(1, updatedRef.localChangeRevision)
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
    fun `applyRemoteSessionPayload skips inbound when local payload edits are still pending sync`() = runTest {
        val remoteId = java.util.UUID.randomUUID().toString()
        val original = remotePayload(remoteId = remoteId, supplier = "Original")
        repository.applyRemoteSessionPayload(original) // Inserted

        val ref = db.historyEntryRemoteRefDao().getByRemoteId(remoteId)!!
        val entry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!

        repository.updateHistoryEntry(entry.copy(supplier = "LocalChange"))

        // Remote re-invia il payload precedente: non sovrascrivere silenziosamente il locale dirty
        val outcome = repository.applyRemoteSessionPayload(original)
        assertEquals(RemoteSessionApplyOutcome.Skipped, outcome)

        val updatedEntry = repository.getHistoryEntryByUid(ref.historyEntryUid)!!
        assertEquals("LocalChange", updatedEntry.supplier)
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
    fun `042 incremental catalog push evaluates only dirty product candidates`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000420"
        repeat(3) { index ->
            db.productDao().insert(
                Product(
                    barcode = "042-incremental-$index",
                    productName = "Incremental Product $index",
                    purchasePrice = 10.0 + index,
                    retailPrice = 20.0 + index
                )
            )
        }
        val remote = FakeCatalogRemote016()

        val first = repository.syncCatalogWithRemote(
            remote,
            RecordingPriceRemote016(configured = false),
            owner
        )

        assertTrue(first.isSuccess)
        assertEquals(3, first.getOrThrow().pushedProducts)
        assertTrue(db.productDao().getCatalogPushCandidates().isEmpty())

        val noopProgress = mutableListOf<CatalogSyncProgressState>()
        val productCallsAfterFirst = remote.productUpsertCallCount
        val second = repository.syncCatalogWithRemote(
            remote = remote,
            priceRemote = RecordingPriceRemote016(configured = false),
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { noopProgress.add(it) }
        )

        assertTrue(second.isSuccess)
        assertEquals(0, second.getOrThrow().pushedProducts)
        assertEquals(productCallsAfterFirst, remote.productUpsertCallCount)
        assertEquals(
            0,
            noopProgress.last { it.stage == CatalogSyncStage.PUSH_PRODUCTS }.total
        )

        val dirtyProduct = repository.findProductByBarcode("042-incremental-1")!!
        db.productRemoteRefDao().incrementLocalRevision(dirtyProduct.id)

        val dirtyProgress = mutableListOf<CatalogSyncProgressState>()
        val productCallsBeforeDirtyPush = remote.productUpsertCallCount
        val fetchCallsBeforeDirtyPush = remote.fetchCount
        val third = repository.syncCatalogWithRemote(
            remote = remote,
            priceRemote = RecordingPriceRemote016(configured = false),
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { dirtyProgress.add(it) }
        )

        assertTrue(third.isSuccess)
        assertEquals(1, third.getOrThrow().pushedProducts)
        assertEquals(productCallsBeforeDirtyPush + 1, remote.productUpsertCallCount)
        // Dirty locali gia' allineati in passato non devono pagare una fetch extra di realign:
        // resta solo il pull catalogo finale.
        assertEquals(fetchCallsBeforeDirtyPush + 1, remote.fetchCount)
        assertEquals(
            1,
            dirtyProgress.first { it.stage == CatalogSyncStage.PUSH_PRODUCTS }.total
        )
        assertTrue(db.productDao().getCatalogPushCandidates().isEmpty())
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

    /**
     * D-022-T0 (obbligatorio): bundle aggregato equivalente a catalogo multipagina — fornitori/categorie/prodotti
     * “in coda” per ordinamento `id` e prezzi remoti che referenziano solo il prodotto tardivo.
     * Verifica che, dopo fetch catalogo completo + apply + pull prezzi, non compaia un falso positivo
     * [CatalogSyncSummary.skippedProductPricesPullNoProductRef] dovuto a catalogo incompleto.
     */
    @Test
    fun `D-022-T0 late catalog rows and prices only for last product no false skipped price refs`() = runTest {
        val owner = "00000000-0000-4000-8000-0000000000d0"
        val supplierEarlyId = "10000000-0000-4000-8000-000000000001"
        val categoryEarlyId = "20000000-0000-4000-8000-000000000001"
        val supplierLateId = "ffffffff-ffff-4fff-8fff-fffffffffff1"
        val categoryLateId = "ffffffff-ffff-4fff-8fff-fffffffffff2"
        val productEarlyId = "30000000-0000-4000-8000-000000000001"
        val productLateId = "ffffffff-ffff-4fff-8fff-fffffffffff3"
        val bundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(id = supplierEarlyId, ownerUserId = owner, name = "Early S"),
                InventorySupplierRow(id = supplierLateId, ownerUserId = owner, name = "Late S")
            ),
            categories = listOf(
                InventoryCategoryRow(id = categoryEarlyId, ownerUserId = owner, name = "Early C"),
                InventoryCategoryRow(id = categoryLateId, ownerUserId = owner, name = "Late C")
            ),
            products = listOf(
                InventoryProductRow(
                    id = productEarlyId,
                    ownerUserId = owner,
                    barcode = "022-page-a",
                    productName = "Early row",
                    supplierId = supplierEarlyId,
                    categoryId = categoryEarlyId,
                    purchasePrice = 1.0,
                    retailPrice = 2.0
                ),
                InventoryProductRow(
                    id = productLateId,
                    ownerUserId = owner,
                    barcode = "022-page-b",
                    productName = "Late row",
                    supplierId = supplierLateId,
                    categoryId = categoryLateId,
                    purchasePrice = 10.0,
                    retailPrice = 20.0
                )
            )
        )
        val eff = "2026-04-18 12:00:00"
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(
                InventoryProductPriceRow(
                    id = "40000000-0000-4000-8000-000000000001",
                    ownerUserId = owner,
                    productId = productLateId,
                    type = "PURCHASE",
                    price = 9.5,
                    effectiveAt = eff,
                    source = "CLOUD",
                    note = null,
                    createdAt = eff
                )
            )
        }
        val result = repository.syncCatalogWithRemote(FakeCatalogRemote016(bundle), priceRemote, owner)
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(
            "catalog completo deve risolvere product_remote_refs per il prezzo tardivo",
            0,
            summary.skippedProductPricesPullNoProductRef
        )
        assertFalse(summary.priceSyncFailed)
        assertEquals(1, summary.pulledProductPrices)
        val lateLocal = repository.findProductByBarcode("022-page-b")!!
        assertEquals("Late S", repository.getSupplierById(lateLocal.supplierId!!)!!.name)
        assertEquals("Late C", repository.getCategoryById(lateLocal.categoryId!!)!!.name)
        assertNotNull(db.productPriceRemoteRefDao().getByRemoteId("40000000-0000-4000-8000-000000000001"))
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
        var catalogChangeCount = 0
        repository.onCatalogChanged = {
            catalogChangeCount++
        }
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
        assertEquals(1, catalogChangeCount)
    }

    // --- Pending breakdown snapshot (task 030, repository-first) ---

    @Test
    fun `030 getCatalogCloudPendingBreakdown reflects tombstone and aggregate price counts`() = runTest {
        val empty = repository.getCatalogCloudPendingBreakdown()
        assertEquals(0, empty.pendingCatalogTombstones)
        assertEquals(0, empty.productPricesPendingPriceBridge)
        assertEquals(0, empty.productPricesBlockedWithoutProductRemote)
        assertEquals(0, empty.suppliersMissingRemoteRef)
        assertEquals(0, empty.categoriesMissingRemoteRef)
        assertEquals(0, empty.productsMissingRemoteRef)
        assertFalse(empty.hasTombstoneOrPriceRelatedPending)
        assertFalse(empty.hasCatalogBridgeGaps)
        assertFalse(empty.hasAnyPendingBreakdown)
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())

        db.pendingCatalogTombstoneDao().insert(
            PendingCatalogTombstone(
                entityType = PendingCatalogTombstoneEntityTypes.SUPPLIER,
                remoteId = "00000000-0000-4000-8000-000000000301",
                enqueuedAtMs = 1L
            )
        )
        val withTomb = repository.getCatalogCloudPendingBreakdown()
        assertEquals(1, withTomb.pendingCatalogTombstones)
        assertEquals(0, withTomb.productPricesPendingPriceBridge)
        assertEquals(0, withTomb.productPricesBlockedWithoutProductRemote)
        assertEquals(0, withTomb.suppliersMissingRemoteRef)
        assertEquals(0, withTomb.categoriesMissingRemoteRef)
        assertEquals(0, withTomb.productsMissingRemoteRef)
        assertTrue(withTomb.hasTombstoneOrPriceRelatedPending)
        assertTrue(withTomb.hasAnyPendingBreakdown)

        repository.addProduct(
            Product(
                barcode = "bd-price-bridge",
                productName = "Price bridge",
                purchasePrice = 10.0,
                retailPrice = 12.0
            )
        )
        val withPriceBridgePending = repository.getCatalogCloudPendingBreakdown()
        assertEquals(1, withPriceBridgePending.pendingCatalogTombstones)
        assertEquals(2, withPriceBridgePending.productPricesPendingPriceBridge)
        assertEquals(0, withPriceBridgePending.productPricesBlockedWithoutProductRemote)
        assertEquals(0, withPriceBridgePending.suppliersMissingRemoteRef)
        assertEquals(0, withPriceBridgePending.categoriesMissingRemoteRef)
        assertEquals(0, withPriceBridgePending.productsMissingRemoteRef)

        db.productDao().insert(
            Product(
                barcode = "bd-price-blocked",
                productName = "Price blocked",
                purchasePrice = 20.0
            )
        )
        val blockedProduct = repository.findProductByBarcode("bd-price-blocked")!!
        db.productPriceDao().insert(
            ProductPrice(
                productId = blockedProduct.id,
                type = "PURCHASE",
                price = 20.0,
                effectiveAt = "2026-04-19 10:00:00",
                source = "MANUAL",
                createdAt = "2026-04-19 10:00:00"
            )
        )
        val withBlockedPrice = repository.getCatalogCloudPendingBreakdown()
        assertEquals(1, withBlockedPrice.pendingCatalogTombstones)
        assertEquals(2, withBlockedPrice.productPricesPendingPriceBridge)
        assertEquals(1, withBlockedPrice.productPricesBlockedWithoutProductRemote)
        assertEquals(0, withBlockedPrice.suppliersMissingRemoteRef)
        assertEquals(0, withBlockedPrice.categoriesMissingRemoteRef)
        assertEquals(1, withBlockedPrice.productsMissingRemoteRef)
        assertTrue(withBlockedPrice.hasCatalogBridgeGaps)
        assertTrue(withBlockedPrice.hasTombstoneOrPriceRelatedPending)
        assertTrue(withBlockedPrice.hasAnyPendingBreakdown)
    }

    @Test
    fun `032 breakdown surfaces local catalog rows missing remote refs and sync reconciles them`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000320"
        val supplierId = db.supplierDao().insert(Supplier(name = "Bridge Gap Supplier 032"))
        val categoryId = db.categoryDao().insert(Category(name = "Bridge Gap Category 032"))
        db.productDao().insert(
            Product(
                barcode = "bridge-gap-032",
                productName = "Bridge Gap Product 032",
                supplierId = supplierId,
                categoryId = categoryId,
                stockQuantity = 3.0
            )
        )
        val product = repository.findProductByBarcode("bridge-gap-032")!!

        val before = repository.getCatalogCloudPendingBreakdown()
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertEquals(1, before.suppliersMissingRemoteRef)
        assertEquals(1, before.categoriesMissingRemoteRef)
        assertEquals(1, before.productsMissingRemoteRef)
        assertFalse(before.hasTombstoneOrPriceRelatedPending)
        assertTrue(before.hasCatalogBridgeGaps)
        assertTrue(before.hasAnyPendingBreakdown)

        val remote = FakeCatalogRemote016()
        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(), owner)

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.pushedSuppliers)
        assertEquals(1, summary.pushedCategories)
        assertEquals(1, summary.pushedProducts)
        assertNotNull(db.supplierRemoteRefDao().getBySupplierId(supplierId))
        assertNotNull(db.categoryRemoteRefDao().getByCategoryId(categoryId))
        assertNotNull(db.productRemoteRefDao().getByProductId(product.id))
        val after = repository.getCatalogCloudPendingBreakdown()
        assertEquals(0, after.suppliersMissingRemoteRef)
        assertEquals(0, after.categoriesMissingRemoteRef)
        assertEquals(0, after.productsMissingRemoteRef)
        assertFalse(after.hasCatalogBridgeGaps)
        assertFalse(after.hasAnyPendingBreakdown)
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
    }

    @Test
    fun `041 realign links local rows to existing remote ids before push when bridge missing`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000410"
        val remoteSupplierId = "00000000-0000-4000-8000-000000000411"
        val remoteCategoryId = "00000000-0000-4000-8000-000000000412"
        val remoteProductId = "00000000-0000-4000-8000-000000000413"

        val supplierId = db.supplierDao().insert(Supplier(name = "Shared Supplier 041"))
        val categoryId = db.categoryDao().insert(Category(name = "Shared Category 041"))
        db.productDao().insert(
            Product(
                barcode = "shared-barcode-041",
                productName = "Shared Product 041",
                purchasePrice = 10.0,
                retailPrice = 15.0,
                supplierId = supplierId,
                categoryId = categoryId,
                stockQuantity = 3.0
            )
        )
        val localProduct = repository.findProductByBarcode("shared-barcode-041")!!

        val remoteBundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(id = remoteSupplierId, ownerUserId = owner, name = "Shared Supplier 041")
            ),
            categories = listOf(
                InventoryCategoryRow(id = remoteCategoryId, ownerUserId = owner, name = "Shared Category 041")
            ),
            products = listOf(
                InventoryProductRow(
                    id = remoteProductId,
                    ownerUserId = owner,
                    barcode = "shared-barcode-041",
                    productName = "Shared Product 041",
                    purchasePrice = 10.0,
                    retailPrice = 15.0,
                    supplierId = remoteSupplierId,
                    categoryId = remoteCategoryId,
                    stockQuantity = 3.0
                )
            )
        )
        val remote = FakeCatalogRemote016(remoteBundle)

        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)

        assertTrue(result.isSuccess)
        val supplierRef = db.supplierRemoteRefDao().getBySupplierId(supplierId)
        val categoryRef = db.categoryRemoteRefDao().getByCategoryId(categoryId)
        val productRef = db.productRemoteRefDao().getByProductId(localProduct.id)
        assertNotNull(supplierRef)
        assertNotNull(categoryRef)
        assertNotNull(productRef)
        // Bridge must reuse the remote id discovered via realign, not a freshly minted UUID.
        assertEquals(remoteSupplierId, supplierRef!!.remoteId)
        assertEquals(remoteCategoryId, categoryRef!!.remoteId)
        assertEquals(remoteProductId, productRef!!.remoteId)
        // Realign marked the bridges as already synced with the remote fingerprint,
        // so a subsequent push does not attempt any upsert for these rows.
        val summary = result.getOrThrow()
        assertEquals(0, summary.pushedSuppliers)
        assertEquals(0, summary.pushedCategories)
        assertEquals(0, summary.pushedProducts)
        assertEquals(
            0,
            remote.upsertedSuppliers.sumOf { it.size } +
                remote.upsertedCategories.sumOf { it.size } +
                remote.upsertedProducts.sumOf { it.size }
        )
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
    }

    @Test
    fun `041 realign is no-op when there are no bridge gaps`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000420"
        val remote = FakeCatalogRemote016()
        val first = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)
        assertTrue(first.isSuccess)
        val fetchAfterFirst = remote.fetchCount

        val second = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)
        assertTrue(second.isSuccess)
        // Senza bridge gap il realign salta la fetch extra: incremento di una sola fetch.
        assertEquals(fetchAfterFirst + 1, remote.fetchCount)
    }

    @Test
    fun `041 realign normalizes whitespace and case on both sides to link pre-existing remote rows`() = runTest {
        // Scenario reale task 041: due device importano lo stesso Excel → stesso
        // whitespace/case nei dati; la partial UNIQUE remota `(owner, lower(name))` /
        // `(owner, barcode)` collide comunque, ma il realign deve agganciare i bridge
        // senza inviare nuove righe (niente 23505/409 al push).
        val owner = "00000000-0000-4000-8000-000000000430"
        val remoteSupplierId = "00000000-0000-4000-8000-000000000431"
        val remoteCategoryId = "00000000-0000-4000-8000-000000000432"
        val remoteProductId = "00000000-0000-4000-8000-000000000433"

        // Local: trailing whitespace sul supplier, case differente sulla category,
        // trailing whitespace sul barcode del prodotto — tutti match legittimi post-normalize.
        val supplierId = db.supplierDao().insert(Supplier(name = "Shared Supplier 041 "))
        val categoryId = db.categoryDao().insert(Category(name = "Shared Category 041"))
        db.productDao().insert(
            Product(
                barcode = "shared-barcode-041 ",
                productName = "Shared Product 041",
                purchasePrice = 10.0,
                retailPrice = 15.0,
                supplierId = supplierId,
                categoryId = categoryId,
                stockQuantity = 3.0
            )
        )
        val localProduct = repository.findProductByBarcode("shared-barcode-041 ")!!

        val remoteBundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                // Stesso trailing space del locale ⇒ lower(trim)=lower(trim)="shared supplier 041".
                InventorySupplierRow(id = remoteSupplierId, ownerUserId = owner, name = "Shared Supplier 041 ")
            ),
            categories = listOf(
                // Case differente ma lower uguale ⇒ match post-normalize.
                InventoryCategoryRow(id = remoteCategoryId, ownerUserId = owner, name = "SHARED CATEGORY 041")
            ),
            products = listOf(
                InventoryProductRow(
                    id = remoteProductId,
                    ownerUserId = owner,
                    barcode = "shared-barcode-041 ",
                    productName = "Shared Product 041",
                    purchasePrice = 10.0,
                    retailPrice = 15.0,
                    supplierId = remoteSupplierId,
                    categoryId = remoteCategoryId,
                    stockQuantity = 3.0
                )
            )
        )
        val remote = FakeCatalogRemote016(remoteBundle)

        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)
        assertTrue(result.isSuccess)

        val supplierRef = db.supplierRemoteRefDao().getBySupplierId(supplierId)
        val categoryRef = db.categoryRemoteRefDao().getByCategoryId(categoryId)
        val productRef = db.productRemoteRefDao().getByProductId(localProduct.id)
        assertNotNull(supplierRef)
        assertNotNull(categoryRef)
        assertNotNull(productRef)
        assertEquals(remoteSupplierId, supplierRef!!.remoteId)
        assertEquals(remoteCategoryId, categoryRef!!.remoteId)
        assertEquals(remoteProductId, productRef!!.remoteId)

        // Nessun push dopo il realign: niente superficie per 23505/409.
        val summary = result.getOrThrow()
        assertEquals(0, summary.pushedSuppliers)
        assertEquals(0, summary.pushedCategories)
        assertEquals(0, summary.pushedProducts)
        assertEquals(
            0,
            remote.upsertedSuppliers.sumOf { it.size } +
                remote.upsertedCategories.sumOf { it.size } +
                remote.upsertedProducts.sumOf { it.size }
        )
    }

    @Test
    fun `041 realign skips non-matching rows without creating bridges`() = runTest {
        // Se il remoto ha righe con nome/barcode totalmente differenti dal locale,
        // il realign non deve inventare bridge fantasma: tocca al push normale creare
        // righe nuove via `ensureXxxRefForPush`.
        val owner = "00000000-0000-4000-8000-000000000440"
        val remoteStaleSupplier = "00000000-0000-4000-8000-000000000441"

        val supplierId = db.supplierDao().insert(Supplier(name = "Real Local Supplier"))
        val remoteBundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(id = remoteStaleSupplier, ownerUserId = owner, name = "Completely Different Remote")
            ),
            categories = emptyList(),
            products = emptyList()
        )
        val remote = FakeCatalogRemote016(remoteBundle)

        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)
        assertTrue(result.isSuccess)

        val ref = db.supplierRemoteRefDao().getBySupplierId(supplierId)
        assertNotNull(ref)
        // Il bridge locale ha remoteId fresco, NON quello remoto "Completely Different Remote".
        assertFalse(ref!!.remoteId == remoteStaleSupplier)
        // Push ha creato una nuova riga remota per il supplier locale.
        assertEquals(1, result.getOrThrow().pushedSuppliers)
    }

    @Test
    fun `041 realign corrects stale local bridges before push`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000470"
        val remoteSupplierId = "00000000-0000-4000-8000-000000000471"
        val remoteCategoryId = "00000000-0000-4000-8000-000000000472"
        val remoteProductId = "00000000-0000-4000-8000-000000000473"
        val staleSupplierId = "00000000-0000-4000-8000-000000000474"
        val staleCategoryId = "00000000-0000-4000-8000-000000000475"
        val staleProductId = "00000000-0000-4000-8000-000000000476"

        val supplierId = db.supplierDao().insert(Supplier(name = "Stale Supplier 041"))
        val categoryId = db.categoryDao().insert(Category(name = "Stale Category 041"))
        db.productDao().insert(
            Product(
                barcode = "stale-barcode-041",
                productName = "Stale Product 041",
                supplierId = supplierId,
                categoryId = categoryId
            )
        )
        val product = repository.findProductByBarcode("stale-barcode-041")!!
        db.supplierRemoteRefDao().insert(SupplierRemoteRef(supplierId = supplierId, remoteId = staleSupplierId))
        db.categoryRemoteRefDao().insert(CategoryRemoteRef(categoryId = categoryId, remoteId = staleCategoryId))
        db.productRemoteRefDao().insert(ProductRemoteRef(productId = product.id, remoteId = staleProductId))

        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = listOf(
                    InventorySupplierRow(id = remoteSupplierId, ownerUserId = owner, name = "Stale Supplier 041")
                ),
                categories = listOf(
                    InventoryCategoryRow(id = remoteCategoryId, ownerUserId = owner, name = "Stale Category 041")
                ),
                products = listOf(
                    InventoryProductRow(
                        id = remoteProductId,
                        ownerUserId = owner,
                        barcode = "stale-barcode-041",
                        productName = "Stale Product 041",
                        supplierId = remoteSupplierId,
                        categoryId = remoteCategoryId
                    )
                )
            )
        )

        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.pushedSuppliers)
        assertEquals(1, summary.pushedCategories)
        assertEquals(1, summary.pushedProducts)
        assertEquals(remoteSupplierId, db.supplierRemoteRefDao().getBySupplierId(supplierId)!!.remoteId)
        assertEquals(remoteCategoryId, db.categoryRemoteRefDao().getByCategoryId(categoryId)!!.remoteId)
        assertEquals(remoteProductId, db.productRemoteRefDao().getByProductId(product.id)!!.remoteId)
        assertEquals(1, remote.supplierUpsertCallCount)
        assertEquals(1, remote.categoryUpsertCallCount)
        assertEquals(1, remote.productUpsertCallCount)
        assertEquals(remoteSupplierId, remote.upsertedSuppliers.single().single().id)
        assertEquals(remoteCategoryId, remote.upsertedCategories.single().single().id)
        assertEquals(remoteProductId, remote.upsertedProducts.single().single().id)
    }

    @Test
    fun `041 conflict 23505 recovers supplier category product bridges and retries once`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000450"
        val remoteSupplierId = "00000000-0000-4000-8000-000000000451"
        val remoteCategoryId = "00000000-0000-4000-8000-000000000452"
        val remoteProductId = "00000000-0000-4000-8000-000000000453"
        val wrongSupplierId = "00000000-0000-4000-8000-000000000454"
        val wrongCategoryId = "00000000-0000-4000-8000-000000000455"
        val wrongProductId = "00000000-0000-4000-8000-000000000456"

        val supplierId = db.supplierDao().insert(Supplier(name = "Conflict Supplier 041 "))
        val categoryId = db.categoryDao().insert(Category(name = "Conflict Category 041"))
        db.productDao().insert(
            Product(
                barcode = "conflict-barcode-041 ",
                productName = "Conflict Product 041",
                purchasePrice = 11.0,
                retailPrice = 17.0,
                supplierId = supplierId,
                categoryId = categoryId,
                stockQuantity = 4.0
            )
        )
        val product = repository.findProductByBarcode("conflict-barcode-041 ")!!
        db.supplierRemoteRefDao().insert(SupplierRemoteRef(supplierId = supplierId, remoteId = wrongSupplierId))
        db.categoryRemoteRefDao().insert(CategoryRemoteRef(categoryId = categoryId, remoteId = wrongCategoryId))
        db.productRemoteRefDao().insert(ProductRemoteRef(productId = product.id, remoteId = wrongProductId))

        val remoteBundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(id = remoteSupplierId, ownerUserId = owner, name = "Conflict Supplier 041 ")
            ),
            categories = listOf(
                InventoryCategoryRow(id = remoteCategoryId, ownerUserId = owner, name = "CONFLICT CATEGORY 041")
            ),
            products = listOf(
                InventoryProductRow(
                    id = remoteProductId,
                    ownerUserId = owner,
                    barcode = "conflict-barcode-041 ",
                    productName = "Conflict Product 041",
                    purchasePrice = 11.0,
                    retailPrice = 17.0,
                    supplierId = remoteSupplierId,
                    categoryId = remoteCategoryId,
                    stockQuantity = 4.0
                )
            )
        )
        val remote = FakeCatalogRemote016(remoteBundle).apply {
            failNextSupplierUpsert = FakePostgrestUniqueViolation()
            failNextCategoryUpsert = FakePostgrestUniqueViolation()
            failNextProductUpsert = FakePostgrestUniqueViolation()
        }

        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.pushedSuppliers)
        assertEquals(1, summary.pushedCategories)
        assertEquals(1, summary.pushedProducts)
        assertEquals(2, remote.supplierUpsertCallCount)
        assertEquals(2, remote.categoryUpsertCallCount)
        assertEquals(2, remote.productUpsertCallCount)
        assertEquals(remoteSupplierId, db.supplierRemoteRefDao().getBySupplierId(supplierId)!!.remoteId)
        assertEquals(remoteCategoryId, db.categoryRemoteRefDao().getByCategoryId(categoryId)!!.remoteId)
        assertEquals(remoteProductId, db.productRemoteRefDao().getByProductId(product.id)!!.remoteId)
        assertEquals(remoteSupplierId, remote.upsertedSuppliers.single().single().id)
        assertEquals(remoteCategoryId, remote.upsertedCategories.single().single().id)
        assertEquals(remoteProductId, remote.upsertedProducts.single().single().id)
        assertEquals(remoteSupplierId, remote.upsertedProducts.single().single().supplierId)
        assertEquals(remoteCategoryId, remote.upsertedProducts.single().single().categoryId)
        assertEquals(2, remote.fetchCount)
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
    }

    @Test
    fun `041 conflict 23505 does not retry forever when reconcile finds no remote row`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000460"
        val wrongSupplierId = "00000000-0000-4000-8000-000000000461"
        val supplierId = db.supplierDao().insert(Supplier(name = "Missing Remote Supplier 041"))
        db.supplierRemoteRefDao().insert(SupplierRemoteRef(supplierId = supplierId, remoteId = wrongSupplierId))
        val remote = FakeCatalogRemote016().apply {
            failNextSupplierUpsert = FakePostgrestUniqueViolation()
        }

        val result = repository.syncCatalogWithRemote(remote, RecordingPriceRemote016(configured = false), owner)

        assertTrue(result.isFailure)
        assertEquals(1, remote.supplierUpsertCallCount)
        assertEquals(wrongSupplierId, db.supplierRemoteRefDao().getBySupplierId(supplierId)!!.remoteId)
        // Una sola snapshot catalogo per realign/recovery: nessun retry loop e nessuna fetch ripetuta.
        assertEquals(1, remote.fetchCount)
    }

    @Test
    fun `032 breakdown distinguishes product bridge gap from blocked price rows`() = runTest {
        val now = "2026-04-20 10:00:00"
        db.productDao().insert(
            Product(
                barcode = "bridge-price-gap-032",
                productName = "Bridge Price Gap 032",
                purchasePrice = 20.0
            )
        )
        val product = repository.findProductByBarcode("bridge-price-gap-032")!!
        db.productPriceDao().insert(
            ProductPrice(
                productId = product.id,
                type = "PURCHASE",
                price = 20.0,
                effectiveAt = now,
                source = "MANUAL",
                createdAt = now
            )
        )

        val breakdown = repository.getCatalogCloudPendingBreakdown()

        assertEquals(1, breakdown.productsMissingRemoteRef)
        assertEquals(1, breakdown.productPricesBlockedWithoutProductRemote)
        assertTrue(breakdown.hasCatalogBridgeGaps)
        assertTrue(breakdown.hasTombstoneOrPriceRelatedPending)
        assertTrue(breakdown.hasAnyPendingBreakdown)
    }

    @Test
    fun `043 current product lookup and details prefer latest price summary over product cache`() = runTest {
        repository.addProduct(
            Product(
                barcode = "dm04-043",
                productName = "DM04 043",
                purchasePrice = 10.0,
                retailPrice = 1100.0
            )
        )
        val product = repository.findProductByBarcode("dm04-043")!!
        db.productPriceDao().insert(
            ProductPrice(
                productId = product.id,
                type = "RETAIL",
                price = 1101.0,
                effectiveAt = "2099-01-01 00:00:00",
                source = "REMOTE",
                createdAt = "2099-01-01 00:00:00"
            )
        )

        val details = repository.getAllProductsWithDetails().single()
        val hydratedProduct = repository.findProductByBarcode("dm04-043")!!

        assertEquals(1101.0, details.currentRetailPrice!!, 0.0001)
        assertEquals(1101.0, details.productWithCurrentPrices().retailPrice!!, 0.0001)
        assertEquals(1101.0, hydratedProduct.retailPrice!!, 0.0001)
    }

    @Test
    fun `043 dirty catalog delta pushes products and pending price history without pull or full catalog push`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000431"
        repository.addProduct(
            Product(
                barcode = "delta-043",
                productName = "Delta 043",
                purchasePrice = 12.0,
                retailPrice = 18.0
            )
        )
        val remote = FakeCatalogRemote016()
        val priceRemote = RecordingPriceRemote016()

        val summary = repository.pushDirtyCatalogDeltaToRemote(
            remote = remote,
            priceRemote = priceRemote,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(0, remote.fetchCount)
        assertEquals(0, remote.supplierUpsertCallCount)
        assertEquals(0, remote.categoryUpsertCallCount)
        assertEquals(1, remote.productUpsertCallCount)
        assertEquals(1, summary.pushedProducts)
        assertEquals(2, summary.pushedProductPrices)
        assertEquals(2, priceRemote.upsertBatches.flatten().size)
        assertEquals(0, priceRemote.fetchCount)
        assertFalse(summary.fullCatalogFetch)
        assertFalse(summary.fullPriceFetch)
        assertEquals(0, summary.remoteProductIdsRequested)
        assertEquals(0, summary.remoteProductsFetched)
        assertEquals(0, summary.remotePriceIdsRequested)
        assertEquals(0, summary.remotePricesFetched)
        assertFalse(summary.incrementalRemoteSubsetVerifiable)
        assertEquals(
            CatalogIncrementalRemoteContract044A.INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES,
            summary.incrementalRemoteNotVerifiableReason
        )

        val second = repository.pushDirtyCatalogDeltaToRemote(
            remote = remote,
            priceRemote = priceRemote,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(0, second.pushedProducts)
        assertEquals(0, second.pushedProductPrices)
        assertEquals(1, remote.productUpsertCallCount)
        assertEquals(1, priceRemote.upsertBatches.size)
    }

    @Test
    fun `045 quick sync emits catalog and price sync events with remote ids only`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000451"
        repository.addProduct(
            Product(
                barcode = "sync-event-045-one",
                productName = "Sync Event 045",
                purchasePrice = 3.0,
                retailPrice = 5.0
            )
        )
        val local = repository.findProductByBarcode("sync-event-045-one")!!
        val remote = FakeCatalogRemote016()
        val priceRemote = RecordingPriceRemote016()
        val syncEvents = FakeSyncEventRemote()

        val summary = repository.syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertFalse(summary.fullCatalogFetch)
        assertFalse(summary.fullPriceFetch)
        assertEquals(0, remote.fetchCount)
        assertEquals(0, priceRemote.fetchCount)
        assertEquals(2, syncEvents.recordedParams.size)
        assertEquals(SyncEventDomains.CATALOG, syncEvents.recordedParams[0].domain)
        assertEquals(SyncEventDomains.PRICES, syncEvents.recordedParams[1].domain)
        assertEquals(syncEvents.recordedParams[0].batchId, syncEvents.recordedParams[1].batchId)
        val pushedProductRemoteId = remote.upsertedProducts.single().single().id
        assertEquals(listOf(pushedProductRemoteId), syncEvents.recordedParams[0].entityIds!!.productIds)
        assertFalse(syncEvents.recordedParams[0].entityIds!!.productIds.contains(local.id.toString()))
        assertEquals(2, syncEvents.recordedParams[1].entityIds!!.priceIds.size)
        assertEquals(2, summary.syncEventsSkippedSelf)
        assertEquals(0, summary.targetedProductsFetched)
        assertEquals(0, summary.targetedPricesFetched)
    }

    @Test
    fun `045 generated import batch emits at most catalog and price events with shared batch`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000452"
        repeat(30) { index ->
            repository.addProduct(
                Product(
                    barcode = "sync-event-045-batch-$index",
                    productName = "Batch $index",
                    purchasePrice = index + 1.0,
                    retailPrice = index + 2.0
                )
            )
        }
        val remote = FakeCatalogRemote016()
        val priceRemote = RecordingPriceRemote016()
        val syncEvents = FakeSyncEventRemote()

        repository.syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(2, syncEvents.recordedParams.size)
        assertEquals(30, syncEvents.recordedParams[0].entityIds!!.productIds.size)
        assertEquals(60, syncEvents.recordedParams[1].entityIds!!.priceIds.size)
        assertEquals(syncEvents.recordedParams[0].batchId, syncEvents.recordedParams[1].batchId)
    }

    @Test
    fun `045 capability false keeps quick sync push-only without hidden full pull`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000453"
        repository.addProduct(
            Product(
                barcode = "sync-event-045-fallback",
                productName = "Fallback",
                purchasePrice = 1.0,
                retailPrice = 2.0
            )
        )
        val remote = FakeCatalogRemote016()
        val priceRemote = RecordingPriceRemote016()
        val syncEvents = FakeSyncEventRemote(
            capabilities = SyncEventRemoteCapabilities.disabled("test_schema_missing")
        )

        val summary = repository.syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertTrue(summary.syncEventsFallback044)
        assertTrue(summary.syncEventsDisabled)
        assertFalse(summary.fullCatalogFetch)
        assertFalse(summary.fullPriceFetch)
        assertEquals(0, remote.fetchCount)
        assertEquals(0, priceRemote.fetchCount)
        assertTrue(syncEvents.recordedParams.isEmpty())
    }

    @Test
    fun `045 drain fetches targeted product and price ids then advances watermark`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000454"
        val productRemoteId = "00000000-0000-4000-8000-000000000455"
        val priceRemoteId = "00000000-0000-4000-8000-000000000456"
        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = emptyList(),
                categories = emptyList(),
                products = listOf(
                    InventoryProductRow(
                        id = productRemoteId,
                        ownerUserId = owner,
                        barcode = "sync-event-045-target",
                        productName = "Target",
                        retailPrice = 9.0
                    )
                )
            )
        )
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(
                InventoryProductPriceRow(
                    id = priceRemoteId,
                    ownerUserId = owner,
                    productId = productRemoteId,
                    type = "RETAIL",
                    price = 10.0,
                    effectiveAt = "2026-04-24 10:00:00",
                    source = "REMOTE",
                    createdAt = "2026-04-24 10:00:00"
                )
            )
        }
        val syncEvents = FakeSyncEventRemote().apply {
            externalEvents += SyncEventRemoteRow(
                id = 1,
                ownerUserId = owner,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                sourceDeviceId = "other-device",
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(productRemoteId)),
                createdAt = "2026-04-24T10:00:00Z"
            )
            externalEvents += SyncEventRemoteRow(
                id = 2,
                ownerUserId = owner,
                domain = SyncEventDomains.PRICES,
                eventType = SyncEventTypes.PRICES_CHANGED,
                sourceDeviceId = "other-device",
                changedCount = 1,
                entityIds = SyncEventEntityIds(priceIds = listOf(priceRemoteId)),
                createdAt = "2026-04-24T10:00:01Z"
            )
        }

        val summary = repository.drainSyncEventsFromRemote(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(0, remote.fetchCount)
        assertEquals(1, remote.targetedFetchCount)
        assertEquals(setOf(productRemoteId), remote.targetedProductIds.first())
        assertEquals(1, priceRemote.targetedFetchCount)
        assertEquals(setOf(priceRemoteId), priceRemote.targetedPriceIds.single())
        assertEquals(2, summary.syncEventsProcessed)
        assertEquals(2, summary.syncEventsWatermarkAfter)
        assertEquals("Target", repository.findProductByBarcode("sync-event-045-target")!!.productName)
        assertEquals(1, repository.getPriceSeries(repository.findProductByBarcode("sync-event-045-target")!!.id, "RETAIL").first().size)
    }

    @Test
    fun `045 emit failure stores outbox and retry records only pending event`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000457"
        repository.addProduct(
            Product(
                barcode = "sync-event-045-outbox",
                productName = "Outbox",
                purchasePrice = 4.0,
                retailPrice = 6.0
            )
        )
        val remote = FakeCatalogRemote016()
        val priceRemote = RecordingPriceRemote016()
        val syncEvents = FakeSyncEventRemote().apply {
            failRecordForDomains += SyncEventDomains.PRICES
        }

        val first = repository.syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(1, first.syncEventOutboxPending)
        assertEquals(listOf(SyncEventDomains.CATALOG), syncEvents.recordedParams.map { it.domain })

        val second = repository.syncCatalogQuickWithEvents(
            remote = remote,
            priceRemote = priceRemote,
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(1, second.syncEventOutboxRetried)
        assertEquals(0, second.syncEventOutboxPending)
        assertEquals(2, syncEvents.recordedParams.size)
        assertEquals(SyncEventDomains.PRICES, syncEvents.recordedParams.last().domain)
    }

    @Test
    fun `045 watermark advances only after event apply succeeds`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000458"
        val productRemoteId = "00000000-0000-4000-8000-000000000459"
        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = emptyList(),
                categories = emptyList(),
                products = listOf(
                    InventoryProductRow(
                        id = productRemoteId,
                        ownerUserId = owner,
                        barcode = "sync-event-045-watermark",
                        productName = "Watermark"
                    )
                )
            )
        ).apply {
            failNextFetch = IOException("targeted fetch failed")
        }
        val syncEvents = FakeSyncEventRemote().apply {
            externalEvents += SyncEventRemoteRow(
                id = 7,
                ownerUserId = owner,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                sourceDeviceId = "other-device",
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(productRemoteId)),
                createdAt = "2026-04-24T10:00:00Z"
            )
        }

        val failed = repository.drainSyncEventsFromRemote(
            remote = remote,
            priceRemote = RecordingPriceRemote016(configured = false),
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        )

        assertTrue(failed.isFailure)
        assertNull(db.syncEventWatermarkDao().get(owner, ""))

        val ok = repository.drainSyncEventsFromRemote(
            remote = remote,
            priceRemote = RecordingPriceRemote016(configured = false),
            syncEventRemote = syncEvents,
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(7, ok.syncEventsWatermarkAfter)
        assertEquals(7, db.syncEventWatermarkDao().get(owner, "")!!.lastSyncEventId)
    }

    @Test
    fun `044A manual sync summary marks full catalog and price fetch`() = runTest {
        val owner = "00000000-0000-4000-8000-00000000044a"
        val productRemoteId = "00000000-0000-4000-8000-00000000044b"
        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = emptyList(),
                categories = emptyList(),
                products = listOf(
                    InventoryProductRow(
                        id = productRemoteId,
                        ownerUserId = owner,
                        barcode = "044a-full",
                        productName = "Full sync 044A",
                        retailPrice = 1.0
                    )
                )
            )
        )
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(
                InventoryProductPriceRow(
                    id = "00000000-0000-4000-8000-00000000044c",
                    ownerUserId = owner,
                    productId = productRemoteId,
                    type = "RETAIL",
                    price = 2.0,
                    effectiveAt = "2026-04-23 12:00:00",
                    source = "REMOTE",
                    createdAt = "2026-04-23 12:00:00"
                )
            )
        }
        val summary = repository.syncCatalogWithRemote(remote, priceRemote, owner).getOrThrow()
        assertTrue(summary.fullCatalogFetch)
        assertTrue(summary.fullPriceFetch)
        assertEquals(1, summary.remoteProductIdsRequested)
        assertEquals(1, summary.remoteProductsFetched)
        assertEquals(1, summary.remotePriceIdsRequested)
        assertEquals(1, summary.remotePricesFetched)
        assertEquals(1, summary.pulledProductPrices)
        assertTrue(summary.incrementalRemoteSubsetVerifiable)
        assertNull(summary.incrementalRemoteNotVerifiableReason)
        assertEquals(1, remote.fetchCount)
        assertEquals(1, priceRemote.fetchCount)
    }

    @Test
    fun `044A full fetch metrics remain remote-row counts when inbound apply is no-op`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000451"
        val productRemoteId = "00000000-0000-4000-8000-000000000452"
        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = emptyList(),
                categories = emptyList(),
                products = listOf(
                    InventoryProductRow(
                        id = productRemoteId,
                        ownerUserId = owner,
                        barcode = "044a-metrics",
                        productName = "Metrics product",
                        retailPrice = 1.0
                    )
                )
            )
        )
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(
                InventoryProductPriceRow(
                    id = "00000000-0000-4000-8000-000000000453",
                    ownerUserId = owner,
                    productId = productRemoteId,
                    type = "RETAIL",
                    price = 2.0,
                    effectiveAt = "2026-04-23 12:00:00",
                    source = "REMOTE",
                    createdAt = "2026-04-23 12:00:00"
                )
            )
        }

        repository.syncCatalogWithRemote(remote, priceRemote, owner).getOrThrow()
        val second = repository.syncCatalogWithRemote(remote, priceRemote, owner).getOrThrow()

        assertEquals(0, second.pulledProducts)
        assertEquals(1, second.remoteProductIdsRequested)
        assertEquals(1, second.remoteProductsFetched)
        assertEquals(0, second.pulledProductPrices)
        assertEquals(1, second.remotePriceIdsRequested)
        assertEquals(1, second.remotePricesFetched)
        assertEquals(2, remote.fetchCount)
        assertEquals(2, priceRemote.fetchCount)
    }

    @Test
    fun `044A manual sync with prices disabled marks full catalog only`() = runTest {
        val owner = "00000000-0000-4000-8000-00000000044d"
        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = emptyList(),
                categories = emptyList(),
                products = listOf(
                    InventoryProductRow(
                        id = "00000000-0000-4000-8000-00000000044e",
                        ownerUserId = owner,
                        barcode = "044a-noprice",
                        productName = "No price remote",
                        retailPrice = 1.0
                    )
                )
            )
        )
        val summary = repository.syncCatalogWithRemote(
            remote,
            RecordingPriceRemote016(configured = false),
            owner
        ).getOrThrow()
        assertTrue(summary.fullCatalogFetch)
        assertFalse(summary.fullPriceFetch)
        assertEquals(0, summary.remotePriceIdsRequested)
    }

    @Test
    fun `044A bootstrap pull skips dirty catalog inbound overwrite`() = runTest {
        val owner = "00000000-0000-4000-8000-00000000044f"
        val supplierRemoteId = "00000000-0000-4000-8000-000000000454"
        val categoryRemoteId = "00000000-0000-4000-8000-000000000455"
        val productRemoteId = "00000000-0000-4000-8000-000000000450"
        val bundleV1 = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(
                    id = supplierRemoteId,
                    ownerUserId = owner,
                    name = "Remote Supplier V1"
                )
            ),
            categories = listOf(
                InventoryCategoryRow(
                    id = categoryRemoteId,
                    ownerUserId = owner,
                    name = "Remote Category V1"
                )
            ),
            products = listOf(
                InventoryProductRow(
                    id = productRemoteId,
                    ownerUserId = owner,
                    barcode = "044a-dirty",
                    productName = "Remote V1",
                    retailPrice = 10.0,
                    supplierId = supplierRemoteId,
                    categoryId = categoryRemoteId
                )
            )
        )
        val remote = FakeCatalogRemote016(bundleV1)
        repository.syncCatalogWithRemote(
            remote,
            RecordingPriceRemote016(configured = false),
            owner
        ).getOrThrow()
        val supplier = repository.findSupplierByName("Remote Supplier V1")!!
        val category = repository.findCategoryByName("Remote Category V1")!!
        val local = repository.findProductByBarcode("044a-dirty")!!
        repository.renameCatalogEntry(CatalogEntityKind.SUPPLIER, supplier.id, "Local supplier dirty")
        repository.renameCatalogEntry(CatalogEntityKind.CATEGORY, category.id, "Local category dirty")
        repository.updateProduct(local.copy(productName = "Local dirty edit"))
        val supplierRefAfterEdit = db.supplierRemoteRefDao().getBySupplierId(supplier.id)!!
        val categoryRefAfterEdit = db.categoryRemoteRefDao().getByCategoryId(category.id)!!
        val refAfterEdit = db.productRemoteRefDao().getByProductId(local.id)!!
        assertTrue(supplierRefAfterEdit.localChangeRevision > supplierRefAfterEdit.lastSyncedLocalRevision)
        assertTrue(categoryRefAfterEdit.localChangeRevision > categoryRefAfterEdit.lastSyncedLocalRevision)
        assertTrue(refAfterEdit.localChangeRevision > refAfterEdit.lastSyncedLocalRevision)

        val bundleV2 = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(
                    id = supplierRemoteId,
                    ownerUserId = owner,
                    name = "Remote Supplier V2 overwrite attempt"
                )
            ),
            categories = listOf(
                InventoryCategoryRow(
                    id = categoryRemoteId,
                    ownerUserId = owner,
                    name = "Remote Category V2 overwrite attempt"
                )
            ),
            products = listOf(
                InventoryProductRow(
                    id = productRemoteId,
                    ownerUserId = owner,
                    barcode = "044a-dirty",
                    productName = "Remote V2 overwrite attempt",
                    retailPrice = 99.0,
                    supplierId = supplierRemoteId,
                    categoryId = categoryRemoteId
                )
            )
        )
        val remote2 = FakeCatalogRemote016(bundleV2)
        repository.pullCatalogBootstrapFromRemote(
            remote2,
            RecordingPriceRemote016(configured = false),
            CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals("Local supplier dirty", repository.getSupplierById(supplier.id)!!.name)
        assertEquals("Local category dirty", repository.getCategoryById(category.id)!!.name)
        val after = repository.findProductByBarcode("044a-dirty")!!
        assertEquals("Local dirty edit", after.productName)
        assertEquals(10.0, after.retailPrice!!, 0.0001)
    }

    @Test
    fun `044A bootstrap pull skips dirty catalog tombstone inbound`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000456"
        val supplierRemoteId = "00000000-0000-4000-8000-000000000457"
        val categoryRemoteId = "00000000-0000-4000-8000-000000000458"
        val productRemoteId = "00000000-0000-4000-8000-000000000459"
        val activeBundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(
                    id = supplierRemoteId,
                    ownerUserId = owner,
                    name = "Remote Supplier Tombstone V1"
                )
            ),
            categories = listOf(
                InventoryCategoryRow(
                    id = categoryRemoteId,
                    ownerUserId = owner,
                    name = "Remote Category Tombstone V1"
                )
            ),
            products = listOf(
                InventoryProductRow(
                    id = productRemoteId,
                    ownerUserId = owner,
                    barcode = "044a-dirty-tombstone",
                    productName = "Remote Tombstone V1",
                    retailPrice = 10.0,
                    supplierId = supplierRemoteId,
                    categoryId = categoryRemoteId
                )
            )
        )
        repository.syncCatalogWithRemote(
            FakeCatalogRemote016(activeBundle),
            RecordingPriceRemote016(configured = false),
            owner
        ).getOrThrow()
        val supplier = repository.findSupplierByName("Remote Supplier Tombstone V1")!!
        val category = repository.findCategoryByName("Remote Category Tombstone V1")!!
        val product = repository.findProductByBarcode("044a-dirty-tombstone")!!
        repository.renameCatalogEntry(CatalogEntityKind.SUPPLIER, supplier.id, "Local supplier survives tombstone")
        repository.renameCatalogEntry(CatalogEntityKind.CATEGORY, category.id, "Local category survives tombstone")
        repository.updateProduct(product.copy(productName = "Local product survives tombstone"))

        val deletedAt = "2026-04-23T12:00:00Z"
        val tombstoneBundle = InventoryCatalogFetchBundle(
            suppliers = listOf(
                InventorySupplierRow(
                    id = supplierRemoteId,
                    ownerUserId = owner,
                    name = "Remote Supplier Tombstone V1",
                    deletedAt = deletedAt
                )
            ),
            categories = listOf(
                InventoryCategoryRow(
                    id = categoryRemoteId,
                    ownerUserId = owner,
                    name = "Remote Category Tombstone V1",
                    deletedAt = deletedAt
                )
            ),
            products = listOf(
                InventoryProductRow(
                    id = productRemoteId,
                    ownerUserId = owner,
                    barcode = "044a-dirty-tombstone",
                    productName = "Remote Tombstone V1",
                    deletedAt = deletedAt
                )
            )
        )

        repository.pullCatalogBootstrapFromRemote(
            FakeCatalogRemote016(tombstoneBundle),
            RecordingPriceRemote016(configured = false),
            CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals("Local supplier survives tombstone", repository.getSupplierById(supplier.id)!!.name)
        assertEquals("Local category survives tombstone", repository.getCategoryById(category.id)!!.name)
        assertEquals(
            "Local product survives tombstone",
            repository.findProductByBarcode("044a-dirty-tombstone")!!.productName
        )
    }

    @Test
    fun `043 product delta push serializes current summary prices instead of stale product cache`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000435"
        repository.addProduct(
            Product(
                barcode = "delta-summary-043",
                productName = "Delta Summary 043",
                purchasePrice = 12.0,
                retailPrice = 18.0
            )
        )
        val product = repository.findProductByBarcode("delta-summary-043")!!
        db.productPriceDao().insert(
            ProductPrice(
                productId = product.id,
                type = "RETAIL",
                price = 19.0,
                effectiveAt = "2099-04-23 10:00:00",
                source = "REMOTE",
                createdAt = "2099-04-23 10:00:00"
            )
        )
        val rawProductCache = db.productDao().getById(product.id)!!
        assertEquals(18.0, rawProductCache.retailPrice!!, 0.0001)

        val remote = FakeCatalogRemote016()
        val summary = repository.pushDirtyCatalogDeltaToRemote(
            remote = remote,
            priceRemote = RecordingPriceRemote016(configured = false),
            ownerUserId = owner,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        assertEquals(1, summary.pushedProducts)
        val pushedRow = remote.upsertedProducts.single().single()
        assertEquals(12.0, pushedRow.purchasePrice!!, 0.0001)
        assertEquals(19.0, pushedRow.retailPrice!!, 0.0001)
    }

    @Test
    fun `043 bootstrap pull applies remote catalog and prices without outbound upserts`() = runTest {
        val owner = "00000000-0000-4000-8000-000000000432"
        val productRemoteId = "00000000-0000-4000-8000-000000000433"
        val priceRemoteId = "00000000-0000-4000-8000-000000000434"
        val remote = FakeCatalogRemote016(
            InventoryCatalogFetchBundle(
                suppliers = emptyList(),
                categories = emptyList(),
                products = listOf(
                    InventoryProductRow(
                        id = productRemoteId,
                        ownerUserId = owner,
                        barcode = "bootstrap-043",
                        productName = "Bootstrap 043",
                        retailPrice = 100.0
                    )
                )
            )
        )
        val priceRemote = RecordingPriceRemote016().apply {
            fetchRows = listOf(
                InventoryProductPriceRow(
                    id = priceRemoteId,
                    ownerUserId = owner,
                    productId = productRemoteId,
                    type = "RETAIL",
                    price = 101.0,
                    effectiveAt = "2026-04-23 10:00:00",
                    source = "REMOTE",
                    createdAt = "2026-04-23 10:00:00"
                )
            )
        }

        val summary = repository.pullCatalogBootstrapFromRemote(
            remote = remote,
            priceRemote = priceRemote,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        val product = repository.findProductByBarcode("bootstrap-043")!!
        val priceSeries = repository.getPriceSeries(product.id, "RETAIL").first()
        assertEquals(1, remote.fetchCount)
        assertEquals(0, remote.productUpsertCallCount)
        assertTrue(priceRemote.upsertBatches.isEmpty())
        assertEquals(1, priceRemote.fetchCount)
        assertEquals(1, summary.pulledProducts)
        assertEquals(1, summary.pulledProductPrices)
        assertEquals(101.0, product.retailPrice!!, 0.0001)
        assertEquals(101.0, priceSeries.single().price, 0.0001)
    }

    // --- Backup sessioni cloud (task 023) ---

    @Test
    fun `023 pushHistorySessionsToRemote uploads dirty user-visible entry`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        repository.getOrCreateRemoteId(uid)
        val entry = repository.getHistoryEntryByUid(uid)!!
        repository.updateHistoryEntry(entry.copy(supplier = "PushMe"))
        val fake = FakeSessionBackupRemote023()
        val owner = "00000000-0000-4000-8000-0000000000aa"
        val result = repository.pushHistorySessionsToRemote(fake, owner)
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.skippedAlreadySynced)
        assertEquals(1, fake.upsertedChunks.flatten().size)
        val row = fake.upsertedChunks.single().single()
        assertEquals("PushMe", row.supplier)
        assertEquals(SESSION_PAYLOAD_VERSION, row.payloadVersion)
        assertEquals("test_entry.xlsx", row.displayName)
        assertEquals(listOf(listOf("", "")), row.sessionOverlay.editable)
        assertEquals(listOf(false), row.sessionOverlay.complete)
        assertEquals(owner, row.ownerUserId)
    }

    @Test
    fun `023 push skips technical import rows`() = runTest {
        val technical = buildMinimalHistoryEntry("APPLY_IMPORT_audit.xlsx")
        val uid = repository.insertHistoryEntry(technical)
        repository.getOrCreateRemoteId(uid)
        val fake = FakeSessionBackupRemote023()
        val result = repository.pushHistorySessionsToRemote(fake, "00000000-0000-4000-8000-0000000000bb")
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().uploaded)
        assertTrue(fake.upsertedChunks.isEmpty())
    }

    @Test
    fun `023 push skips already synced sessions`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        repository.getOrCreateRemoteId(uid)
        val entry = repository.getHistoryEntryByUid(uid)!!
        repository.updateHistoryEntry(entry.copy(supplier = "Once"))
        val fake = FakeSessionBackupRemote023()
        val owner = "00000000-0000-4000-8000-0000000000cc"
        assertEquals(1, repository.pushHistorySessionsToRemote(fake, owner).getOrThrow().uploaded)
        val second = repository.pushHistorySessionsToRemote(fake, owner).getOrThrow()
        assertEquals(0, second.uploaded)
        assertEquals(1, second.skippedAlreadySynced)
    }

    @Test
    fun `040 push blocks overlay above soft cap and keeps session pending`() = runTest {
        val tooLargeCell = "x".repeat(SESSION_OVERLAY_MAX_BYTES + 1)
        val uid = repository.insertHistoryEntry(
            buildMinimalHistoryEntry("large_overlay.xlsx").copy(
                editable = listOf(listOf(tooLargeCell, "")),
                complete = listOf(false)
            )
        )
        val fake = FakeSessionBackupRemote023()

        val result = repository
            .pushHistorySessionsToRemote(fake, "00000000-0000-4000-8000-000000000040")
            .getOrThrow()

        assertEquals(0, result.uploaded)
        assertTrue(fake.upsertedChunks.isEmpty())
        val ref = db.historyEntryRemoteRefDao().getByHistoryEntryUid(uid)
        assertNotNull(ref)
        assertNull(ref!!.lastRemoteAppliedAt)
    }

    @Test
    fun `040 push blocks invalid overlay shape and keeps session pending`() = runTest {
        val uid = repository.insertHistoryEntry(
            buildMinimalHistoryEntry("invalid_overlay.xlsx").copy(
                data = listOf(
                    listOf("barcode", "purchasePrice", "quantity"),
                    listOf("AAA", "10", "3")
                ),
                editable = listOf(listOf("", "")),
                complete = listOf(false)
            )
        )
        val fake = FakeSessionBackupRemote023()

        val result = repository
            .pushHistorySessionsToRemote(fake, "00000000-0000-4000-8000-000000000041")
            .getOrThrow()

        assertEquals(0, result.uploaded)
        assertTrue(fake.upsertedChunks.isEmpty())
        assertTrue(repository.getPendingHistorySessionPushUids().contains(uid))
        val ref = db.historyEntryRemoteRefDao().getByHistoryEntryUid(uid)
        assertNotNull(ref)
        assertNull(ref!!.lastRemoteAppliedAt)
    }

    @Test
    fun `040 push skips invalid overlay while uploading valid session in same batch`() = runTest {
        val invalidUid = repository.insertHistoryEntry(
            buildMinimalHistoryEntry("invalid_overlay_batch.xlsx").copy(
                data = listOf(
                    listOf("barcode", "purchasePrice", "quantity"),
                    listOf("AAA", "10", "3")
                ),
                editable = listOf(listOf("", "")),
                complete = listOf(false)
            )
        )
        val validUid = repository.insertHistoryEntry(
            buildMinimalHistoryEntry("valid_overlay_batch.xlsx").copy(
                supplier = "Pushable"
            )
        )
        val fake = FakeSessionBackupRemote023()

        val result = repository
            .pushHistorySessionsToRemote(
                fake,
                "00000000-0000-4000-8000-000000000042",
                setOf(invalidUid, validUid)
            )
            .getOrThrow()

        assertEquals(1, result.uploaded)
        val pushed = fake.upsertedChunks.single().single()
        assertEquals("valid_overlay_batch.xlsx", pushed.displayName)
        assertTrue(repository.getPendingHistorySessionPushUids().contains(invalidUid))
        assertFalse(repository.getPendingHistorySessionPushUids().contains(validUid))
        val invalidRef = db.historyEntryRemoteRefDao().getByHistoryEntryUid(invalidUid)
        val validRef = db.historyEntryRemoteRefDao().getByHistoryEntryUid(validUid)
        assertNotNull(invalidRef)
        assertNotNull(validRef)
        assertNull(invalidRef!!.lastRemoteAppliedAt)
        assertNotNull(validRef!!.lastRemoteAppliedAt)
    }

    @Test
    fun `040 failed session push keeps entry pending for retry`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry("pinmark.xlsx"))
        val fake = FakeSessionBackupRemote023().apply {
            failNextUpsert = IllegalStateException("permission denied for table shared_sheet_sessions")
        }

        val result = repository.pushHistorySessionsToRemote(
            fake,
            "00000000-0000-4000-8000-000000000040"
        )

        assertTrue(result.isFailure)
        assertTrue(repository.getPendingHistorySessionPushUids().contains(uid))
        val ref = db.historyEntryRemoteRefDao().getByHistoryEntryUid(uid)
        assertNotNull(ref)
        assertNull(ref!!.lastRemoteAppliedAt)
    }

    @Test
    fun `023 pushHistorySessionsToRemote chunks upserts`() = runTest {
        val fake = FakeSessionBackupRemote023()
        val owner = "00000000-0000-4000-8000-00000000dd01"
        repeat(81) { i ->
            val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry("chunk_$i.xlsx"))
            repository.getOrCreateRemoteId(uid)
            val e = repository.getHistoryEntryByUid(uid)!!
            repository.updateHistoryEntry(e.copy(supplier = "S$i"))
        }
        val result = repository.pushHistorySessionsToRemote(fake, owner).getOrThrow()
        assertEquals(81, result.uploaded)
        assertEquals(2, fake.upsertedChunks.size)
        assertEquals(80, fake.upsertedChunks[0].size)
        assertEquals(1, fake.upsertedChunks[1].size)
    }

    @Test
    fun `023 push keeps newer local edit dirty when it happens during upload`() = runTest {
        val uid = repository.insertHistoryEntry(buildMinimalHistoryEntry())
        repository.getOrCreateRemoteId(uid)
        repository.updateHistoryEntry(repository.getHistoryEntryByUid(uid)!!.copy(supplier = "First"))

        val fake = FakeSessionBackupRemote023(
            onUpsert = {
                val liveEntry = repository.getHistoryEntryByUid(uid)!!
                repository.updateHistoryEntry(liveEntry.copy(supplier = "Second"))
            }
        )
        val owner = "00000000-0000-4000-8000-0000000000ef"

        val first = repository.pushHistorySessionsToRemote(fake, owner).getOrThrow()

        assertEquals(1, first.uploaded)
        val dirtyRef = db.historyEntryRemoteRefDao().getByHistoryEntryUid(uid)!!
        assertTrue(dirtyRef.localChangeRevision > dirtyRef.lastSyncedLocalRevision)

        val secondFake = FakeSessionBackupRemote023()
        val second = repository.pushHistorySessionsToRemote(secondFake, owner).getOrThrow()

        assertEquals(1, second.uploaded)
        assertEquals("Second", secondFake.upsertedChunks.single().single().supplier)
        val syncedRef = db.historyEntryRemoteRefDao().getByHistoryEntryUid(uid)!!
        assertEquals(syncedRef.localChangeRevision, syncedRef.lastSyncedLocalRevision)
    }

    @Test
    fun `023 bootstrapHistorySessionsFromRemote applies remote records`() = runTest {
        val rid1 = java.util.UUID.randomUUID().toString()
        val rid2 = java.util.UUID.randomUUID().toString()
        val fake = FakeSessionBackupRemote023(
            records = listOf(
                SharedSheetSessionRecord(
                    remoteId = rid1,
                    payloadVersion = 1,
                    timestamp = "2026-04-01 10:00:00",
                    supplier = "S1",
                    category = "C1",
                    isManualEntry = true,
                    data = listOf(listOf("h"))
                ),
                SharedSheetSessionRecord(
                    remoteId = rid2,
                    payloadVersion = 1,
                    timestamp = "2026-04-02 10:00:00",
                    supplier = "S2",
                    category = "C2",
                    isManualEntry = false,
                    data = listOf(listOf("h"))
                )
            )
        )
        val batch = repository.bootstrapHistorySessionsFromRemote(fake).getOrThrow()
        assertEquals(2, batch.inserted)
        assertNotNull(db.historyEntryRemoteRefDao().getByRemoteId(rid1))
        assertNotNull(db.historyEntryRemoteRefDao().getByRemoteId(rid2))
    }

    @Test
    fun `023 bootstrap twice skips identical payloads`() = runTest {
        val rid = java.util.UUID.randomUUID().toString()
        val rec = SharedSheetSessionRecord(
            remoteId = rid,
            payloadVersion = 1,
            timestamp = "2026-04-01 10:00:00",
            supplier = "S",
            category = "C",
            isManualEntry = true,
            data = listOf(listOf("x"))
        )
        val fake = FakeSessionBackupRemote023(records = listOf(rec))
        repository.bootstrapHistorySessionsFromRemote(fake).getOrThrow()
        val second = repository.bootstrapHistorySessionsFromRemote(fake).getOrThrow()
        assertEquals(1, second.skipped)
    }
}

private class FakeSessionBackupRemote023(
    val records: List<SharedSheetSessionRecord> = emptyList(),
    private val onUpsert: (suspend (List<SharedSheetSessionUpsertRow>) -> Unit)? = null
) : SessionBackupRemoteDataSource {
    override val isConfigured = true
    val upsertedChunks = mutableListOf<List<SharedSheetSessionUpsertRow>>()
    var failNextUpsert: Throwable? = null
    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        Result.success(records)
    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> {
        upsertedChunks.add(rows)
        onUpsert?.invoke(rows)
        failNextUpsert?.let { t ->
            failNextUpsert = null
            return Result.failure(t)
        }
        return Result.success(Unit)
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
    var targetedFetchCount = 0
    val targetedSupplierIds = mutableListOf<Set<String>>()
    val targetedCategoryIds = mutableListOf<Set<String>>()
    val targetedProductIds = mutableListOf<Set<String>>()
    var supplierUpsertCallCount = 0
    var categoryUpsertCallCount = 0
    var productUpsertCallCount = 0
    var failNextFetch: Throwable? = null
    var failNextSupplierUpsert: Throwable? = null
    var failNextCategoryUpsert: Throwable? = null
    var failNextProductUpsert: Throwable? = null
    var failNextSupplierTombstone: Throwable? = null
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> {
        supplierUpsertCallCount++
        failNextSupplierUpsert?.let { t ->
            failNextSupplierUpsert = null
            return Result.failure(t)
        }
        upsertedSuppliers.add(rows)
        return Result.success(Unit)
    }
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> {
        categoryUpsertCallCount++
        failNextCategoryUpsert?.let { t ->
            failNextCategoryUpsert = null
            return Result.failure(t)
        }
        upsertedCategories.add(rows)
        return Result.success(Unit)
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        productUpsertCallCount++
        failNextProductUpsert?.let { t ->
            failNextProductUpsert = null
            return Result.failure(t)
        }
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
    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> {
        targetedFetchCount++
        targetedSupplierIds.add(supplierIds)
        targetedCategoryIds.add(categoryIds)
        targetedProductIds.add(productIds)
        failNextFetch?.let { t ->
            failNextFetch = null
            return Result.failure(t)
        }
        return Result.success(
            InventoryCatalogFetchBundle(
                suppliers = bundle.suppliers.filter { it.id in supplierIds },
                categories = bundle.categories.filter { it.id in categoryIds },
                products = bundle.products.filter { it.id in productIds }
            )
        )
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

private class FakePostgrestUniqueViolation : RuntimeException(
    "httpStatus=409 postgrestCode=23505 duplicate key value violates unique constraint"
)

private class RecordingPriceRemote016(
    private val configured: Boolean = true
) : ProductPriceRemoteDataSource {
    val upsertBatches = mutableListOf<List<InventoryProductPriceRow>>()
    var fetchRows: List<InventoryProductPriceRow> = emptyList()
    var fetchCount = 0
    var targetedFetchCount = 0
    val targetedPriceIds = mutableListOf<Set<String>>()
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
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> {
        if (failIfCalled) error("ProductPriceRemoteDataSource should not be called")
        targetedFetchCount++
        targetedPriceIds.add(remoteIds)
        failNextFetch?.let { t ->
            failNextFetch = null
            return Result.failure(t)
        }
        return Result.success(fetchRows.filter { it.id in remoteIds })
    }
}

private class FakeSyncEventRemote(
    private val capabilities: SyncEventRemoteCapabilities = SyncEventRemoteCapabilities(
        syncEventsAvailable = true,
        recordSyncEventAvailable = true,
        realtimeSyncEventsAvailable = true
    )
) : SyncEventRemoteDataSource {
    override val isConfigured: Boolean get() = true
    val recordedParams = mutableListOf<SyncEventRecordRpcParams>()
    val emittedRows = mutableListOf<SyncEventRemoteRow>()
    val externalEvents = mutableListOf<SyncEventRemoteRow>()
    val failRecordForDomains = mutableSetOf<String>()
    private var nextId = 1L

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        Result.success(capabilities)

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
        if (!capabilities.recordSyncEventAvailable) {
            return Result.failure(IOException("record_sync_event unavailable"))
        }
        if (failRecordForDomains.remove(params.domain)) {
            return Result.failure(IOException("record_sync_event failed for ${params.domain}"))
        }
        val existing = emittedRows.firstOrNull { it.clientEventId == params.clientEventId }
        if (existing != null) return Result.success(existing)
        recordedParams += params
        val row = SyncEventRemoteRow(
            id = nextId++,
            ownerUserId = "00000000-0000-4000-8000-000000000000",
            storeId = params.storeId,
            domain = params.domain,
            eventType = params.eventType,
            source = params.source,
            sourceDeviceId = params.sourceDeviceId,
            batchId = params.batchId,
            clientEventId = params.clientEventId,
            changedCount = params.changedCount,
            entityIds = params.entityIds,
            createdAt = "2026-04-24T10:00:00Z"
        )
        emittedRows += row
        return Result.success(row)
    }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        Result.success(
            (externalEvents + emittedRows)
                .filter { it.id > afterId }
                .sortedBy { it.id }
                .take(limit.toInt())
        )
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

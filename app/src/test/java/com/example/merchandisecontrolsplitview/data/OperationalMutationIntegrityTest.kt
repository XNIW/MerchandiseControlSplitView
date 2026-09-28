package com.example.merchandisecontrolsplitview.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class OperationalMutationIntegrityTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository
    private var catalogNotifications = 0
    private var productNotifications = 0

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
        repository.onCatalogChanged = { catalogNotifications++ }
        repository.onProductCatalogChanged = { productNotifications++ }
    }

    @After
    fun teardown() {
        DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = null
        db.close()
    }

    @Test
    fun `add supplier rolls back when remote ref insertion fails`() = runTest {
        failDirtyWrite("supplier_remote_refs", "INSERT")
        expectStorageFailure { repository.addSupplier("New supplier") }
        assertNull(repository.findSupplierByName("New supplier"))
        assertEquals(0, db.supplierRemoteRefDao().countRows())
        assertEquals(0, catalogNotifications)
    }

    @Test
    fun `add category rolls back when remote ref insertion fails`() = runTest {
        failDirtyWrite("category_remote_refs", "INSERT")
        expectStorageFailure { repository.addCategory("New category") }
        assertNull(repository.findCategoryByName("New category"))
        assertEquals(0, db.categoryRemoteRefDao().countRows())
        assertEquals(0, catalogNotifications)
    }

    @Test
    fun `create supplier rolls back when remote ref insertion fails`() = runTest {
        failDirtyWrite("supplier_remote_refs", "INSERT")
        expectStorageFailure { repository.createCatalogEntry(CatalogEntityKind.SUPPLIER, "New supplier") }
        assertNull(repository.findSupplierByName("New supplier"))
        assertEquals(0, db.supplierRemoteRefDao().countRows())
        assertEquals(0, catalogNotifications)
    }

    @Test
    fun `create category rolls back when remote ref insertion fails`() = runTest {
        failDirtyWrite("category_remote_refs", "INSERT")
        expectStorageFailure { repository.createCatalogEntry(CatalogEntityKind.CATEGORY, "New category") }
        assertNull(repository.findCategoryByName("New category"))
        assertEquals(0, db.categoryRemoteRefDao().countRows())
        assertEquals(0, catalogNotifications)
    }

    @Test
    fun `rename supplier rolls back entity and revision when dirty update fails`() = runTest {
        val supplier = repository.addSupplier("Original")!!
        db.supplierRemoteRefDao().updateRemoteApplyState(supplier.id, 0, 1, "synced", null)
        val ref = db.supplierRemoteRefDao().getBySupplierId(supplier.id)
        catalogNotifications = 0
        failDirtyWrite("supplier_remote_refs", "UPDATE")
        expectStorageFailure { repository.renameCatalogEntry(CatalogEntityKind.SUPPLIER, supplier.id, "Changed") }
        assertEquals(supplier, repository.getSupplierById(supplier.id))
        assertEquals(ref, db.supplierRemoteRefDao().getBySupplierId(supplier.id))
        assertFalse(db.supplierRemoteRefDao().hasPendingWork())
        assertEquals(0, catalogNotifications)
    }

    @Test
    fun `rename category rolls back entity and revision when dirty update fails`() = runTest {
        val category = repository.addCategory("Original")!!
        db.categoryRemoteRefDao().updateRemoteApplyState(category.id, 0, 1, "synced", null)
        val ref = db.categoryRemoteRefDao().getByCategoryId(category.id)
        catalogNotifications = 0
        failDirtyWrite("category_remote_refs", "UPDATE")
        expectStorageFailure { repository.renameCatalogEntry(CatalogEntityKind.CATEGORY, category.id, "Changed") }
        assertEquals(category, repository.getCategoryById(category.id))
        assertEquals(ref, db.categoryRemoteRefDao().getByCategoryId(category.id))
        assertFalse(db.categoryRemoteRefDao().hasPendingWork())
        assertEquals(0, catalogNotifications)
    }

    @Test
    fun `editor name edit preserves price and image updates after editor opened`() = runTest {
        val baseline = seededProduct()
        repository.updateProduct(baseline.copy(purchasePrice = 20.0))
        val current = db.productDao().getById(baseline.id)!!.copy(
            primaryImageVersionId = "new-image", primaryImageUpdatedAt = "2026-09-28T12:00:00Z"
        )
        db.productDao().update(current)
        val history = repository.getAllPriceHistoryRows()

        saveEditor(baseline, baseline.copy(productName = "Edited name"))

        assertEquals(current.copy(productName = "Edited name"), db.productDao().getById(baseline.id))
        assertEquals(history, repository.getAllPriceHistoryRows())
    }

    @Test
    fun `editor overlapping edit rejects all writes and history`() = runTest {
        val baseline = seededProduct()
        repository.updateProduct(baseline.copy(purchasePrice = 20.0))
        val current = db.productDao().getById(baseline.id)
        val history = repository.getAllPriceHistoryRows()
        val ref = db.productRemoteRefDao().getByProductId(baseline.id)
        productNotifications = 0

        val error = runCatching {
            saveEditor(baseline, baseline.copy(productName = "Must not persist", purchasePrice = 30.0))
        }.exceptionOrNull()

        assertTrue("Overlapping editor changes must be rejected", error is ProductEditConflictException)
        assertEquals(current, db.productDao().getById(baseline.id))
        assertEquals(history, repository.getAllPriceHistoryRows())
        assertEquals(ref, db.productRemoteRefDao().getByProductId(baseline.id))
        assertEquals(0, productNotifications)
    }

    @Test
    fun `editor keeps concurrent catalog price without inventing manual price history`() = runTest {
        val baseline = seededProduct()
        // Catalog pull and price pull are independent: current price can arrive before its history.
        db.productDao().update(baseline.copy(purchasePrice = 20.0))
        val history = repository.getAllPriceHistoryRows()

        saveEditor(baseline, baseline.copy(productName = "Edited name"))

        assertEquals(20.0, db.productDao().getById(baseline.id)!!.purchasePrice)
        assertEquals(history, repository.getAllPriceHistoryRows())
    }

    @Test
    fun `editor untouched or same concurrent value is a no op`() = runTest {
        val baseline = seededProduct()
        repository.updateProduct(baseline.copy(purchasePrice = 20.0))
        val current = db.productDao().getById(baseline.id)
        val history = repository.getAllPriceHistoryRows()
        val ref = db.productRemoteRefDao().getByProductId(baseline.id)
        productNotifications = 0

        saveEditor(baseline, baseline)
        saveEditor(baseline, baseline.copy(purchasePrice = 20.0))

        assertEquals(current, db.productDao().getById(baseline.id))
        assertEquals(history, repository.getAllPriceHistoryRows())
        assertEquals(ref, db.productRemoteRefDao().getByProductId(baseline.id))
        assertEquals(0, productNotifications)
    }

    @Test
    fun `editor intentional null clear merges with independent remote name`() = runTest {
        val baseline = seededProduct()
        repository.updateProduct(baseline.copy(productName = "Remote name"))
        val history = repository.getAllPriceHistoryRows()

        saveEditor(baseline, baseline.copy(purchasePrice = null))

        assertEquals(baseline.copy(productName = "Remote name", purchasePrice = null), db.productDao().getById(baseline.id))
        assertEquals(history, repository.getAllPriceHistoryRows())
    }

    @Test
    fun `editor successful price delta writes exactly one history and dirty revision`() = runTest {
        val baseline = seededProduct()
        repository.updateProduct(baseline.copy(productName = "Remote name"))
        val ref = db.productRemoteRefDao().getByProductId(baseline.id)!!
        val history = repository.getAllPriceHistoryRows()
        productNotifications = 0

        saveEditor(baseline, baseline.copy(purchasePrice = 30.0))

        assertEquals(baseline.copy(productName = "Remote name", purchasePrice = 30.0), db.productDao().getById(baseline.id))
        assertEquals(history.size + 1, repository.getAllPriceHistoryRows().size)
        assertEquals(30.0, repository.getLastPrice(baseline.id, "PURCHASE"))
        assertEquals(ref.localChangeRevision + 1, db.productRemoteRefDao().getByProductId(baseline.id)!!.localChangeRevision)
        assertEquals(1, productNotifications)
    }

    @Test
    fun `editor all editable fields reject overlapping changes`() = runTest {
        val baseline = seededProduct()
        val supplierA = repository.addSupplier("Supplier A")!!.id
        val supplierB = repository.addSupplier("Supplier B")!!.id
        val categoryA = repository.addCategory("Category A")!!.id
        val categoryB = repository.addCategory("Category B")!!.id
        val candidates = listOf(
            baseline.copy(barcode = "REMOTE") to baseline.copy(barcode = "LOCAL"),
            baseline.copy(itemNumber = "REMOTE") to baseline.copy(itemNumber = "LOCAL"),
            baseline.copy(productName = "Remote") to baseline.copy(productName = "Local"),
            baseline.copy(secondProductName = "Remote") to baseline.copy(secondProductName = "Local"),
            baseline.copy(purchasePrice = 20.0) to baseline.copy(purchasePrice = 30.0),
            baseline.copy(retailPrice = 20.0) to baseline.copy(retailPrice = 30.0),
            baseline.copy(supplierId = supplierA) to baseline.copy(supplierId = supplierB),
            baseline.copy(categoryId = categoryA) to baseline.copy(categoryId = categoryB),
            baseline.copy(stockQuantity = 2.0) to baseline.copy(stockQuantity = 3.0)
        )
        val history = repository.getAllPriceHistoryRows()
        val ref = db.productRemoteRefDao().getByProductId(baseline.id)
        productNotifications = 0

        for ((current, edited) in candidates) {
            db.productDao().update(current)
            val error = runCatching { saveEditor(baseline, edited) }.exceptionOrNull()
            assertTrue(error is ProductEditConflictException)
            assertEquals(current, db.productDao().getById(baseline.id))
        }
        assertEquals(history, repository.getAllPriceHistoryRows())
        assertEquals(ref, db.productRemoteRefDao().getByProductId(baseline.id))
        assertEquals(0, productNotifications)
    }

    @Test
    fun `editor missing product conflicts without recreating row`() = runTest {
        val baseline = seededProduct()
        repository.deleteProduct(baseline)
        productNotifications = 0

        val error = runCatching { saveEditor(baseline, baseline.copy(productName = "Resurrected")) }.exceptionOrNull()

        assertTrue(error is ProductEditConflictException)
        assertNull(db.productDao().getById(baseline.id))
        assertEquals(0, db.productPriceDao().countAll())
        assertNull(db.productRemoteRefDao().getByProductId(baseline.id))
        assertEquals(0, productNotifications)
    }

    @Test
    fun `editor merge rolls back if dirty marker fails after product and price write`() = runTest {
        val baseline = seededProduct()
        repository.updateProduct(baseline.copy(productName = "Remote name"))
        val current = db.productDao().getById(baseline.id)
        val history = repository.getAllPriceHistoryRows()
        val ref = db.productRemoteRefDao().getByProductId(baseline.id)
        productNotifications = 0
        failDirtyWrite("product_remote_refs", "UPDATE")

        expectStorageFailure { saveEditor(baseline, baseline.copy(purchasePrice = 30.0)) }

        assertEquals(current, db.productDao().getById(baseline.id))
        assertEquals(history, repository.getAllPriceHistoryRows())
        assertEquals(ref, db.productRemoteRefDao().getByProductId(baseline.id))
        assertEquals(0, productNotifications)
    }

    private suspend fun seededProduct(): Product {
        repository.addProduct(Product(barcode = "EDITOR-143", productName = "Original", purchasePrice = 10.0))
        return repository.findProductByBarcode("EDITOR-143")!!
    }

    private suspend fun saveEditor(baseline: Product, edited: Product) {
        repository.updateProductFromEditor(baseline, edited)
    }

    private fun failDirtyWrite(table: String, operation: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_dirty BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )
    }

    private suspend fun expectStorageFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected injected storage failure")
        } catch (_: SQLiteConstraintException) {
            // Deliberate local SQLite failure, with real Room transactions and DAOs.
        }
    }
}

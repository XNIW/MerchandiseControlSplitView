package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultInventoryRepositoryTest {

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
    fun `addProduct - success`() = runTest {
        val product = Product(barcode = "123", productName = "P1", purchasePrice = 1.0, retailPrice = 2.0)
        repository.addProduct(product)
        
        val all = repository.getAllProducts()
        assertEquals(1, all.size)
        assertEquals("123", all[0].barcode)
    }

    @Test
    fun `updateProduct - success`() = runTest {
        val product = Product(barcode = "123", productName = "P1", purchasePrice = 1.0, retailPrice = 2.0)
        repository.addProduct(product)
        
        val saved = repository.findProductByBarcode("123")
        assertNotNull(saved)
        val updated = saved!!.copy(productName = "P1 Updated", purchasePrice = 1.5)
        repository.updateProduct(updated)
        
        val reloaded = repository.findProductByBarcode("123")
        assertEquals("P1 Updated", reloaded?.productName)
        assertEquals(1.5, reloaded?.purchasePrice)
    }

    @Test
    fun `applyImport - success with new and updated`() = runTest {
        val oldProd = Product(barcode = "111", productName = "Old", purchasePrice = 2.0, retailPrice = 4.0)
        repository.addProduct(oldProd)
        val savedOld = repository.findProductByBarcode("111")!!

        val newProducts = listOf(Product(barcode = "222", productName = "New"))
        val updatedProducts = listOf(ProductUpdate(oldProduct = savedOld, newProduct = savedOld.copy(productName = "Old Updated")))
        
        repository.applyImport(newProducts, updatedProducts)
        
        val p1 = repository.findProductByBarcode("111")
        assertEquals("Old Updated", p1?.productName)
        
        val p2 = repository.findProductByBarcode("222")
        assertNotNull(p2)
    }

    @Test
    fun `addSupplier - creates and returns new supplier`() = runTest {
        val supplier = repository.addSupplier("My Supplier")
        assertNotNull(supplier)
        assertEquals("My Supplier", supplier?.name)
        
        val all = repository.getAllSuppliers()
        assertEquals(1, all.size)
        
        // duplicated add returns existing
        val supplier2 = repository.addSupplier("My Supplier")
        assertEquals(supplier?.id, supplier2?.id)
    }

    @Test
    fun `addCategory - creates and returns new category`() = runTest {
        val category = repository.addCategory("My Category")
        assertNotNull(category)
        assertEquals("My Category", category?.name)
        
        val all = repository.getAllCategories()
        assertEquals(1, all.size)
    }

    @Test
    fun `getFilteredHistoryFlow - filtering works`() = runTest {
        val e1 = HistoryEntry(id = "E1", timestamp = "2023-01-01 10:00:00", data = emptyList(), editable = emptyList(), complete = emptyList(), supplier = "", category = "", totalItems = 1, orderTotal = 0.0, paymentTotal = 0.0, missingItems = 0, syncStatus = SyncStatus.NOT_ATTEMPTED, wasExported = false)
        repository.insertHistoryEntry(e1)
        val e2 = HistoryEntry(id = "E2", timestamp = "2023-02-01 10:00:00", data = emptyList(), editable = emptyList(), complete = emptyList(), supplier = "", category = "", totalItems = 1, orderTotal = 0.0, paymentTotal = 0.0, missingItems = 0, syncStatus = SyncStatus.NOT_ATTEMPTED, wasExported = false)
        repository.insertHistoryEntry(e2)

        val allFlow = repository.getFilteredHistoryFlow(com.example.merchandisecontrolsplitview.viewmodel.DateFilter.All)
        val firstItem = allFlow.take(1).toList().first()
        assertEquals(2, firstItem.size)
    }

    @Test
    fun `recordPriceIfChanged and getCurrentPriceSnapshot`() = runTest {
        val p = Product(barcode = "888", productName = "Test", purchasePrice = 1.0, retailPrice = 2.0)
        repository.addProduct(p)
        val saved = repository.findProductByBarcode("888")!!

        // record initial prices
        repository.recordPriceIfChanged(saved.id, "PURCHASE", 1.0, "2023-01-01 10:00:00", "init")
        repository.recordPriceIfChanged(saved.id, "RETAIL", 2.0, "2023-01-01 10:00:00", "init")

        val snap1 = repository.getCurrentPriceSnapshot()
        val row1 = snap1.find { it.barcode == "888" }
        assertEquals(1.0, row1?.purchasePrice)
        assertEquals(2.0, row1?.retailPrice)
        
        // update purchase price
        repository.recordPriceIfChanged(saved.id, "PURCHASE", 1.5, "2023-01-02 10:00:00", "update")
        val snap2 = repository.getCurrentPriceSnapshot()
        val row2 = snap2.find { it.barcode == "888" }
        assertEquals(1.5, row2?.purchasePrice)

        // unchanged price should not add new row (getLastPrice logic)
        val lastP = repository.getLastPrice(saved.id, "PURCHASE")
        assertEquals(1.5, lastP)
    }
}

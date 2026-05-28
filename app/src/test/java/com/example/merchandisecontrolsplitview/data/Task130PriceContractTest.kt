package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Task130PriceContractTest {

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
    fun `new product current lives on product and last price comes from history`() = runTest {
        repository.addProduct(
            Product(
                barcode = "TASK130_PRICE_NEW",
                productName = "Task 130 price new",
                purchasePrice = 10.0,
                retailPrice = 15.0
            )
        )

        val product = repository.findProductByBarcode("TASK130_PRICE_NEW")
        val details = db.productDao().findDetailsByBarcode("TASK130_PRICE_NEW")

        assertNotNull(product)
        assertNotNull(details)
        assertEquals(10.0, details!!.currentPurchasePrice!!, 0.0001)
        assertEquals(15.0, details.currentRetailPrice!!, 0.0001)
        assertEquals(10.0, details.lastPurchase!!, 0.0001)
        assertEquals(15.0, details.lastRetail!!, 0.0001)
        assertNull(details.prevPurchase)
        assertNull(details.prevRetail)
    }

    @Test
    fun `price updates move current fields and expose previous history`() = runTest {
        repository.addProduct(
            Product(
                barcode = "TASK130_PRICE_UPDATE",
                productName = "Task 130 update",
                purchasePrice = 10.0,
                retailPrice = 15.0
            )
        )
        val product = repository.findProductByBarcode("TASK130_PRICE_UPDATE")!!

        repository.updateCurrentPriceFromHistory(
            productId = product.id,
            type = "PURCHASE",
            price = 12.0,
            at = "2099-01-01 10:00:00",
            source = "MANUAL"
        )
        repository.updateCurrentPriceFromHistory(
            productId = product.id,
            type = "RETAIL",
            price = 18.0,
            at = "2099-01-01 10:00:01",
            source = "MANUAL"
        )

        val details = db.productDao().findDetailsByBarcode("TASK130_PRICE_UPDATE")!!
        assertEquals(12.0, details.currentPurchasePrice!!, 0.0001)
        assertEquals(18.0, details.currentRetailPrice!!, 0.0001)
        assertEquals(12.0, details.lastPurchase!!, 0.0001)
        assertEquals(10.0, details.prevPurchase!!, 0.0001)
        assertEquals(18.0, details.lastRetail!!, 0.0001)
        assertEquals(15.0, details.prevRetail!!, 0.0001)
    }

    @Test
    fun `history-only remote row does not override current product field`() = runTest {
        repository.addProduct(
            Product(
                barcode = "TASK130_HISTORY_ONLY",
                productName = "Task 130 history only",
                purchasePrice = 14.0,
                retailPrice = 24.0
            )
        )
        val product = repository.findProductByBarcode("TASK130_HISTORY_ONLY")!!

        db.productPriceDao().insert(
            ProductPrice(
                productId = product.id,
                type = "PURCHASE",
                price = 99.0,
                effectiveAt = "2099-01-02 00:00:00",
                source = "REMOTE",
                createdAt = "2099-01-02 00:00:00"
            )
        )

        val details = db.productDao().findDetailsByBarcode("TASK130_HISTORY_ONLY")!!
        val hydratedProduct = repository.findProductByBarcode("TASK130_HISTORY_ONLY")!!
        assertEquals(14.0, details.currentPurchasePrice!!, 0.0001)
        assertEquals(99.0, details.lastPurchase!!, 0.0001)
        assertEquals(14.0, details.prevPurchase!!, 0.0001)
        assertEquals(14.0, details.productWithCurrentPrices().purchasePrice!!, 0.0001)
        assertEquals(14.0, hydratedProduct.purchasePrice!!, 0.0001)
    }

    @Test
    fun `import old prices become previous history not primary current`() = runTest {
        val result = repository.applyImport(
            ImportApplyRequest(
                newProducts = listOf(
                    Product(
                        barcode = "TASK130_IMPORT_OLD",
                        productName = "Task 130 import old",
                        purchasePrice = 11.0,
                        retailPrice = 17.0,
                        oldPurchasePrice = 9.0,
                        oldRetailPrice = 15.0
                    )
                ),
                updatedProducts = emptyList()
            )
        )

        assertTrue(result is ImportApplyResult.Success)
        val details = db.productDao().findDetailsByBarcode("TASK130_IMPORT_OLD")!!
        val product = repository.findProductByBarcode("TASK130_IMPORT_OLD")!!
        val purchaseHistory = db.productPriceDao().getSeries(product.id, "PURCHASE").first()
        val retailHistory = db.productPriceDao().getSeries(product.id, "RETAIL").first()

        assertEquals(11.0, details.currentPurchasePrice!!, 0.0001)
        assertEquals(17.0, details.currentRetailPrice!!, 0.0001)
        assertEquals(11.0, details.lastPurchase!!, 0.0001)
        assertEquals(9.0, details.prevPurchase!!, 0.0001)
        assertEquals(17.0, details.lastRetail!!, 0.0001)
        assertEquals(15.0, details.prevRetail!!, 0.0001)
        assertEquals(listOf("IMPORT", "IMPORT_PREV"), purchaseHistory.map { it.source })
        assertEquals(listOf("IMPORT", "IMPORT_PREV"), retailHistory.map { it.source })
    }
}

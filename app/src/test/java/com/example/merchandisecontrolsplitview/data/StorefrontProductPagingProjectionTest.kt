package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

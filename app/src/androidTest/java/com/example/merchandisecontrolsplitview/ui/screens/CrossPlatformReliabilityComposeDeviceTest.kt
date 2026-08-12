package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTestHooks
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ProductEditorSaveResult
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrossPlatformReliabilityComposeDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository
    private lateinit var viewModelStoreOwner: ViewModelStoreOwner
    private lateinit var viewModel: DatabaseViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
        val application = context.applicationContext as MerchandiseControlApplication
        viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DatabaseViewModel(application, repository) as T
        }
        viewModel = ViewModelProvider(viewModelStoreOwner, factory)[DatabaseViewModel::class.java]
    }

    @After
    fun tearDown() {
        DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = null
        viewModelStoreOwner.viewModelStore.clear()
        db.close()
    }

    @Test
    fun failedRepositorySaveKeepsDraftOpenAndRetryAddsOneHistoryPoint() {
        val initial = Product(
            barcode = "RELIABILITY-SAVE-001",
            productName = "Original",
            purchasePrice = 10.0,
            retailPrice = 20.0,
            stockQuantity = 2.0
        )
        runBlocking { repository.addProduct(initial) }
        val persisted = requireNotNull(runBlocking {
            repository.findProductByBarcode(initial.barcode)
        })
        val historyBefore = runBlocking { db.productPriceDao().countAll() }
        var failNextWrite = true
        DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("deterministic repository failure")
            }
        }
        val visible = mutableStateOf(true)

        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface {
                    if (visible.value) {
                        EditProductDialog(
                            product = persisted,
                            viewModel = viewModel,
                            onDismiss = { visible.value = false },
                            onSave = viewModel::saveProductFromEditor
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("product-editor-name")
            .performTextReplacement("Draft survives")
        composeRule.onNodeWithTag("task141.edit.purchase-price")
            .performTextReplacement("10")
        composeRule.onNodeWithTag("product-editor-retail-price")
            .performTextReplacement("25")
        composeRule.onNodeWithTag("product-editor-save")
            .performClick()

        composeRule.onNodeWithTag("product-editor-save-error").assertExists()
        composeRule.onNodeWithTag("task141.edit.dialog-root").assertExists()
        composeRule.onNodeWithText("Draft survives").assertExists()
        composeRule.onNodeWithTag("product-editor-retail-price").assertIsFocused()
        assertEquals("Original", runBlocking {
            repository.findProductByBarcode(initial.barcode)?.productName
        })
        assertEquals(historyBefore, runBlocking { db.productPriceDao().countAll() })

        composeRule.onNodeWithTag("product-editor-save").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("task141.edit.dialog-root")
                .fetchSemanticsNodes()
                .isEmpty()
        }

        val recovered = runBlocking { repository.findProductByBarcode(initial.barcode) }
        assertEquals("Draft survives", recovered?.productName)
        assertEquals(historyBefore + 1, runBlocking { db.productPriceDao().countAll() })
    }

    @Test
    fun supplierQuickCreatePreservesInputAfterFailureAndRetrySelectsCreatedSupplier() {
        val visible = mutableStateOf(true)
        val attempts = AtomicInteger()
        val expectedFailure = context.getString(R.string.quick_create_supplier_failed)

        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface {
                    if (visible.value) {
                        EditProductDialog(
                            product = newProduct("RELIABILITY-SUPPLIER-001"),
                            viewModel = viewModel,
                            onResolveSupplierId = { name ->
                                if (attempts.incrementAndGet() == 1) {
                                    throw IllegalStateException("deterministic supplier failure")
                                }
                                viewModel.addSupplier(name)?.id
                            },
                            onDismiss = { visible.value = false },
                            onSave = { ProductEditorSaveResult.Saved() }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("product-editor-supplier-selector")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.search_or_add_supplier))
            .performTextReplacement("Supplier Retry")
        composeRule.onNodeWithText(
            context.getString(R.string.add_new_supplier_prompt, "Supplier Retry")
        ).performClick()

        composeRule.onNodeWithTag("supplier-quick-create-error").assertExists()
        composeRule.onNodeWithText(expectedFailure).assertExists()
        composeRule.onNodeWithText("Supplier Retry").assertExists()
        composeRule.onNodeWithText(
            context.getString(R.string.quick_create_supplier_retry, "Supplier Retry")
        ).performClick()

        composeRule.onNodeWithText("Supplier Retry").assertExists()
        assertEquals(2, attempts.get())
        assertNotNull(runBlocking { repository.findSupplierByName("Supplier Retry") })
    }

    @Test
    fun categoryQuickCreateDisablesDoubleSubmitAndSelectsTheSingleCreatedRow() {
        val visible = mutableStateOf(true)
        val attempts = AtomicInteger()
        val releaseSave = CompletableDeferred<Unit>()

        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface {
                    if (visible.value) {
                        EditProductDialog(
                            product = newProduct("RELIABILITY-CATEGORY-001"),
                            viewModel = viewModel,
                            onResolveCategoryId = { name ->
                                attempts.incrementAndGet()
                                releaseSave.await()
                                viewModel.addCategory(name)?.id
                            },
                            onDismiss = { visible.value = false },
                            onSave = { ProductEditorSaveResult.Saved() }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("product-editor-category-selector")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.search_or_add_category))
            .performTextReplacement("Category Once")
        composeRule.onNodeWithText(
            context.getString(R.string.add_new_category_prompt, "Category Once")
        ).performClick()

        composeRule.onNodeWithTag("category-quick-create-saving").assertExists()
        composeRule.onNodeWithText(
            context.getString(R.string.quick_create_category_saving, "Category Once")
        ).assertIsNotEnabled().performClick()
        assertEquals(1, attempts.get())

        releaseSave.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.findCategoryByName("Category Once") } != null
        }

        assertEquals(1, attempts.get())
        assertNotNull(runBlocking { repository.findCategoryByName("Category Once") })
    }

    @Test
    fun fullscreenImageOffersRetryAndBoundedZoomControls() {
        val thumbnailBytes = byteArrayOf(1)
        val state = mutableStateOf(
            productImagePreviewState(
                mainState = ProductImageUiState(
                    status = ProductImageUiStatus.ERROR,
                    errorCode = "temporary"
                ),
                thumbState = ProductImageUiState(
                    status = ProductImageUiStatus.READY,
                    bytes = thumbnailBytes
                )
            ) ?: ProductImageUiState(
                status = ProductImageUiStatus.ERROR,
                errorCode = "temporary"
            )
        )
        val retries = AtomicInteger()

        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                ProductImageFullscreenDialog(
                    state = state.value,
                    contentDescription = context.getString(R.string.product_image_main),
                    onDismiss = {},
                    onRetry = {
                        retries.incrementAndGet()
                        state.value = ProductImageUiState(
                            status = ProductImageUiStatus.READY,
                            bytes = byteArrayOf(1)
                        )
                    }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, retries.get())
        assertEquals(ProductImageUiStatus.READY, state.value.status)
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.product_image_zoom_out)
        ).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.product_image_zoom_in)
        ).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.product_image_zoom_out)
        ).assertIsEnabled()
    }

    private fun newProduct(barcode: String) = Product(
        barcode = barcode,
        productName = "Draft",
        retailPrice = 20.0,
        stockQuantity = 1.0
    )
}

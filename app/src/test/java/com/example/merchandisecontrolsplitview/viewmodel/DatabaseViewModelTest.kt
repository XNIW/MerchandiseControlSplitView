package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import app.cash.turbine.test
import com.example.merchandisecontrolsplitview.data.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DatabaseViewModelTest {

    private lateinit var repository: InventoryRepository
    private lateinit var viewModel: DatabaseViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)

        viewModel = DatabaseViewModel(app, repository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addProduct - success`() = runTest {
        val product = Product(barcode = "123", productName = "P", purchasePrice = 1.0, retailPrice = 2.0)
        coEvery { repository.addProduct(product) } returns Unit

        viewModel.addProduct(product)
        advanceUntilIdle()

        coVerify { repository.addProduct(product) }
        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state is UiState.Success)
        }
    }

    @Test
    fun `updateProduct - success`() = runTest {
        val product = Product(barcode = "123", productName = "P", purchasePrice = 1.0, retailPrice = 2.0)
        coEvery { repository.updateProduct(product) } returns Unit

        viewModel.updateProduct(product)
        advanceUntilIdle()

        coVerify { repository.updateProduct(product) }
        viewModel.uiState.test {
            assertTrue(expectMostRecentItem() is UiState.Success)
        }
    }

    @Test
    fun `deleteProduct - success`() = runTest {
        val product = Product(barcode = "123", productName = "P", purchasePrice = 1.0, retailPrice = 2.0)
        coEvery { repository.deleteProduct(product) } returns Unit

        viewModel.deleteProduct(product)
        advanceUntilIdle()

        coVerify { repository.deleteProduct(product) }
        viewModel.uiState.test {
            assertTrue(expectMostRecentItem() is UiState.Success)
        }
    }

    @Test
    fun `analyzeGridData - success`() = runTest {
        val products = listOf(Product(barcode = "123", productName = "Exist"))
        coEvery { repository.getAllProducts() } returns products

        val rawData = listOf(
            mapOf("barcode" to "123", "purchasePrice" to "1.0", "quantity" to "5"),
            mapOf("barcode" to "456", "purchasePrice" to "2.0", "quantity" to "10")
        )

        viewModel.analyzeGridData(rawData)
        advanceUntilIdle()

        viewModel.importAnalysisResult.test {
            val result = expectMostRecentItem()
            assertNotNull(result)
            assertEquals(1, result?.newProducts?.size)    // 456 is new
            assertEquals(1, result?.updatedProducts?.size) // 123 is updated
        }
        viewModel.uiState.test {
            assertEquals(UiState.Idle, expectMostRecentItem())
        }
    }

    @Test
    fun `startImportAnalysis - success with POI workbook`() = runTest {
        val tempFile = File.createTempFile("test_import", ".xlsx")
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet()
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("barcode")
        header.createCell(1).setCellValue("purchasePrice")
        header.createCell(2).setCellValue("quantity")

        val r1 = sheet.createRow(1)
        r1.createCell(0).setCellValue("999")
        r1.createCell(1).setCellValue("5.0")
        r1.createCell(2).setCellValue("10")
        
        tempFile.outputStream().use { wb.write(it) }
        wb.close()

        val uri = Uri.fromFile(tempFile)
        
        coEvery { repository.getAllProducts() } returns emptyList()

        viewModel.startImportAnalysis(app, uri)
        advanceUntilIdle()

        viewModel.importAnalysisResult.test {
            val res = expectMostRecentItem()
            assertNotNull(res)
            assertEquals(1, res?.newProducts?.size)
            assertEquals("999", res?.newProducts?.first()?.barcode)
        }
    }

    @Test
    fun `exportToExcel - list not empty`() = runTest {
        coEvery { repository.getAllProductsWithDetails() } returns listOf(
            ProductWithDetails(Product(barcode = "111", productName = "X", purchasePrice = 1.0, retailPrice = 2.0), null, null, null, null, null, null)
        )
        val tempFile = File.createTempFile("test_export", ".xlsx")
        val uri = Uri.fromFile(tempFile)

        viewModel.exportToExcel(app, uri)
        advanceUntilIdle()

        viewModel.uiState.test {
            assertTrue(expectMostRecentItem() is UiState.Success)
        }
        assertTrue(tempFile.length() > 0)
    }

    @Test
    fun `consumeUiState - resets to Idle`() = runTest {
        viewModel.consumeUiState()
        viewModel.uiState.test {
            assertEquals(UiState.Idle, expectMostRecentItem())
        }
    }

    @Test
    fun `clearImportAnalysis - clears result`() = runTest {
        viewModel.clearImportAnalysis()
        viewModel.importAnalysisResult.test {
            assertNull(expectMostRecentItem())
        }
    }

    @Test
    fun `importProducts - applies changes and logs history`() = runTest {
        val newProds = listOf(Product(barcode = "123", productName = "New", purchasePrice = 1.0, retailPrice = 2.0))
        val updatedProds = emptyList<ProductUpdate>()
        
        coEvery { repository.applyImport(any(), any()) } returns Unit
        coEvery { repository.insertHistoryEntry(any()) } returns 1L
        coEvery { repository.updateHistoryEntry(any()) } returns Unit
        
        viewModel.importProducts(newProds, updatedProds, app)
        advanceUntilIdle()
        
        coVerify { repository.applyImport(newProds, emptyList()) }
        coVerify { repository.insertHistoryEntry(any()) }
        viewModel.uiState.test {
            assertTrue(expectMostRecentItem() is UiState.Success)
        }
    }
}

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
import java.io.File
import org.apache.poi.xssf.usermodel.XSSFWorkbook

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExcelViewModelTest {

    private lateinit var repository: InventoryRepository
    private lateinit var viewModel: ExcelViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        viewModel = ExcelViewModel(app, repository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleColumnSelection logic`() = runTest {
        viewModel.headerTypes.add("barcode")
        viewModel.headerTypes.add("quantity")
        viewModel.excelData.add(listOf("barcode", "quantity"))
        viewModel.selectedColumns.add(false)
        viewModel.selectedColumns.add(false)

        assertTrue(viewModel.isColumnEssential(0)) // barcode is essential
        assertFalse(viewModel.isColumnEssential(1))

        viewModel.toggleColumnSelection(0)
        assertFalse(viewModel.selectedColumns[0]) // still false, because essential cannot be unchecked

        viewModel.toggleColumnSelection(1)
        assertTrue(viewModel.selectedColumns[1]) // quantity can be toggled
    }

    @Test
    fun `toggleSelectAll logic`() = runTest {
        viewModel.excelData.add(listOf("barcode", "quantity", "discount"))
        viewModel.headerTypes.addAll(listOf("barcode", "quantity", "discount"))
        viewModel.selectedColumns.addAll(listOf(true, false, false))

        viewModel.toggleSelectAll()
        assertTrue(viewModel.selectedColumns[1])
        assertTrue(viewModel.selectedColumns[2])
    }

    @Test
    fun `generateFilteredWithOldPrices invokes callback with new uid`() = runTest {
        val callback = mockk<(Long) -> Unit>(relaxed = true)
        coEvery { repository.insertHistoryEntry(any()) } returns 1L

        viewModel.headerTypes.add("barcode")
        viewModel.selectedColumns.add(true)
        viewModel.excelData.add(listOf("barcode"))
        viewModel.excelData.add(listOf("123"))

        viewModel.generateFilteredWithOldPrices("MySupplier", "MyCat", callback)
        advanceUntilIdle()

        verify { callback.invoke(any()) }
        assertEquals("MySupplier", viewModel.supplierName)
        assertEquals("MyCat", viewModel.categoryName)
    }

    @Test
    fun `loadHistoryEntry restores state`() = runTest {
        val entry = HistoryEntry(
            id = "test",
            timestamp = "time",
            data = listOf(listOf("barcode"), listOf("123")),
            editable = listOf(listOf("", ""), listOf("10", "")),
            complete = listOf(false, true),
            supplier = "Sup",
            category = "Cat",
            totalItems = 1,
            orderTotal = 0.0,
            paymentTotal = 0.0,
            missingItems = 0,
            syncStatus = SyncStatus.NOT_ATTEMPTED,
            wasExported = false
        )

        viewModel.loadHistoryEntry(entry)
        
        assertEquals(2, viewModel.excelData.size)
        assertEquals("10", viewModel.editableValues[1][0].value)
        assertTrue(viewModel.completeStates[1])
    }

    @Test
    fun `updateHistoryEntry writes to repo`() = runTest {
        val entry = HistoryEntry(id = "test", timestamp = "...", data = emptyList(), editable = emptyList(), complete = emptyList(), supplier = "A", category = "B", totalItems = 0, orderTotal = 0.0, paymentTotal = 0.0, missingItems = 0, syncStatus = SyncStatus.NOT_ATTEMPTED, wasExported = false)
        coEvery { repository.getHistoryEntryByUid(1L) } returns entry

        viewModel.excelData.add(listOf("barcode"))
        viewModel.editableValues.add(mutableListOf())
        viewModel.completeStates.add(false)
        
        viewModel.updateHistoryEntry(1L)
        advanceUntilIdle()

        coVerify { repository.updateHistoryEntry(any()) }
    }

    @Test
    fun `renameHistoryEntry updates id`() = runTest {
        val entry = HistoryEntry(id = "old", timestamp = "...", data = emptyList(), editable = emptyList(), complete = emptyList(), supplier = "A", category = "B", totalItems = 0, orderTotal = 0.0, paymentTotal = 0.0, missingItems = 0, syncStatus = SyncStatus.NOT_ATTEMPTED, wasExported = false)
        viewModel.renameHistoryEntry(entry, "new_name", "new_sup", "new_cat")
        advanceUntilIdle()
        
        coVerify {
            repository.updateHistoryEntry(match { 
                it.id == "new_name" && it.supplier == "new_sup" && it.category == "new_cat"
            })
        }
    }

    @Test
    fun `deleteHistoryEntry invokes delete on repo`() = runTest {
        val entry = HistoryEntry(id = "old", timestamp = "...", data = emptyList(), editable = emptyList(), complete = emptyList(), supplier = "A", category = "B", totalItems = 0, orderTotal = 0.0, paymentTotal = 0.0, missingItems = 0, syncStatus = SyncStatus.NOT_ATTEMPTED, wasExported = false)
        
        viewModel.deleteHistoryEntry(entry)
        advanceUntilIdle()
        
        coVerify { repository.deleteHistoryEntry(entry) }
    }

    @Test
    fun `exportToUri saves file`() = runTest {
        viewModel.excelData.add(listOf("barcode", "purchasePrice"))
        viewModel.excelData.add(listOf("123", "5.0"))
        
        val tempFile = File.createTempFile("test_export", ".xlsx")
        val uri = Uri.fromFile(tempFile)

        viewModel.exportToUri(app, uri)
        advanceUntilIdle()

        assertTrue(tempFile.length() > 0)
    }

    @Test
    fun `createManualEntry and addManualRow work correctly`() = runTest {
        val callback = mockk<(Long) -> Unit>(relaxed = true)
        
        // 1. Create a manual entry
        viewModel.createManualEntry(app, callback)
        advanceUntilIdle()
        verify { callback.invoke(any()) }
        
        // 2. Add row
        viewModel.addManualRow(1L, listOf("111", "ManProd", "1.0", "2.0"), "ManualCat")
        advanceUntilIdle()
        
        assertEquals(2, viewModel.excelData.size) // Header + 1 row
        assertEquals("111", viewModel.excelData[1][0])
        assertEquals(2, viewModel.editableValues.size)
        assertEquals(2, viewModel.completeStates.size)
    }
}

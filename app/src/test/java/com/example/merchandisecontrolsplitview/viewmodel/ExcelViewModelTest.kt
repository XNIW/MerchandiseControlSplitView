package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExcelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: InventoryRepository
    private lateinit var viewModel: ExcelViewModel
    private lateinit var app: Application

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        repository = mockk(relaxed = true)

        every { repository.getFilteredHistoryFlow(any()) } returns flowOf(emptyList())
        coEvery { repository.getPreviousPricesForBarcodes(any(), any()) } returns emptyMap()

        viewModel = ExcelViewModel(app, repository)
    }

    @Test
    fun `toggleColumnSelection does not change essential column`() = runTest {
        seedColumnSelectionState()

        viewModel.toggleColumnSelection(0)

        assertTrue(viewModel.isColumnEssential(0))
        assertFalse(viewModel.selectedColumns[0])
    }

    @Test
    fun `toggleColumnSelection toggles non essential column`() = runTest {
        seedColumnSelectionState()

        viewModel.toggleColumnSelection(1)

        assertTrue(viewModel.selectedColumns[1])
    }

    @Test
    fun `toggleSelectAll selects non essential columns and keeps essentials enabled`() = runTest {
        viewModel.excelData.add(listOf("barcode", "quantity", "discount"))
        viewModel.selectedColumns.addAll(listOf(false, false, false))

        viewModel.toggleSelectAll()

        assertTrue(viewModel.selectedColumns[0])
        assertTrue(viewModel.selectedColumns[1])
        assertTrue(viewModel.selectedColumns[2])
    }

    @Test
    fun `toggleSelectAll deselects only non essential columns when all are selected`() = runTest {
        viewModel.excelData.add(listOf("barcode", "quantity", "discount"))
        viewModel.selectedColumns.addAll(listOf(true, true, true))

        viewModel.toggleSelectAll()

        assertTrue(viewModel.selectedColumns[0])
        assertFalse(viewModel.selectedColumns[1])
        assertFalse(viewModel.selectedColumns[2])
    }

    @Test
    fun `generateFilteredWithOldPrices persists entry and updates generated state`() = runTest {
        val insertedEntry = slot<HistoryEntry>()
        val callback = mockk<(Long) -> Unit>(relaxed = true)
        coEvery { repository.insertHistoryEntry(capture(insertedEntry)) } returns 77L
        coEvery {
            repository.getPreviousPricesForBarcodes(match { it == listOf("12345678") }, any())
        } returns mapOf("12345678" to (2.0 to 5.0))

        seedGeneratedGrid()

        viewModel.generateFilteredWithOldPrices("Supplier A", "Category A", callback)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.insertHistoryEntry(any()) }
        verify(exactly = 1) { callback.invoke(77L) }
        assertTrue(viewModel.generated.value)
        assertEquals("Supplier A", viewModel.supplierName)
        assertEquals("Category A", viewModel.categoryName)
        assertEquals(77L, viewModel.currentEntryStatus.value.third)
        assertEquals("2", insertedEntry.captured.data[1][4])
        assertEquals("5", insertedEntry.captured.data[1][5])
    }

    @Test
    fun `loadHistoryEntry restores viewmodel state from entry`() = runTest {
        val entry = historyEntry(
            uid = 44L,
            data = listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("12345678", "4", "2")
            ),
            editable = listOf(listOf("", ""), listOf("10", "12")),
            complete = listOf(false, true),
            supplier = "Supplier",
            category = "Category"
        )

        viewModel.loadHistoryEntry(entry)

        assertEquals(2, viewModel.excelData.size)
        assertEquals("10", viewModel.editableValues[1][0].value)
        assertTrue(viewModel.completeStates[1])
        assertTrue(viewModel.generated.value)
        assertEquals("Supplier", viewModel.supplierName)
        assertEquals("Category", viewModel.categoryName)
        assertEquals(44L, viewModel.currentEntryStatus.value.third)
    }

    @Test
    fun `updateHistoryEntry writes edited state and summary to repository`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(15L) } returns historyEntry(uid = 15L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("12345678", "3", "2")
            )
        )
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.editableValues.add(mutableListOf(mutableStateOf("2"), mutableStateOf("")))
        viewModel.completeStates.addAll(listOf(false, true))

        viewModel.updateHistoryEntry(15L)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertEquals(6.0, updatedEntry.captured.paymentTotal, 0.0001)
        assertEquals(0, updatedEntry.captured.missingItems)
        assertEquals("12345678", updatedEntry.captured.data[1][0])
    }

    @Test
    fun `renameHistoryEntry updates id supplier and category`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.renameHistoryEntry(
            entry = historyEntry(uid = 30L, supplier = "Old Supplier", category = "Old Category"),
            newName = "new_name.xlsx",
            newSupplier = "New Supplier",
            newCategory = "New Category"
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertEquals("new_name.xlsx", updatedEntry.captured.id)
        assertEquals("New Supplier", updatedEntry.captured.supplier)
        assertEquals("New Category", updatedEntry.captured.category)
        assertEquals(
            app.getString(R.string.history_entry_renamed),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `deleteHistoryEntry delegates delete to repository`() = runTest {
        val entry = historyEntry(uid = 31L)

        viewModel.deleteHistoryEntry(entry)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteHistoryEntry(entry) }
        assertEquals(
            app.getString(R.string.history_entry_deleted),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `renameHistoryEntry failure emits localized feedback`() = runTest {
        coEvery { repository.updateHistoryEntry(any()) } throws IllegalStateException("db unavailable")

        viewModel.renameHistoryEntry(
            entry = historyEntry(uid = 32L),
            newName = "broken.xlsx"
        )
        advanceUntilIdle()

        assertEquals(
            app.getString(R.string.error_history_entry_rename),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `exportToUri writes workbook and clears exporting indicators`() = runTest {
        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "purchasePrice", "complete"),
                listOf("12345678", "4.0", "")
            )
        )
        viewModel.editableValues.addAll(
            listOf(
                mutableListOf(mutableStateOf(""), mutableStateOf("")),
                mutableListOf(mutableStateOf("2"), mutableStateOf("5"))
            )
        )
        viewModel.completeStates.addAll(listOf(false, true))

        val targetFile = File.createTempFile("excel-viewmodel-export", ".xlsx", app.cacheDir)

        viewModel.exportToUri(app, Uri.fromFile(targetFile))
        advanceUntilIdle()

        assertTrue(targetFile.length() > 0)
        assertFalse(viewModel.isExporting.value)
        assertEquals(null, viewModel.exportProgress.value)
    }

    @Test
    fun `loadFromMultipleUris invalid workbook uses generic localized error`() = runTest {
        val invalidFile = File.createTempFile("invalid-load", ".xlsx", app.cacheDir).apply {
            writeText("not an excel workbook")
        }

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(invalidFile)))
        advanceUntilIdle()
        waitForCondition { viewModel.loadError.value != null }

        assertEquals(
            app.getString(R.string.error_file_access_denied),
            viewModel.loadError.value
        )
    }

    @Test
    fun `createManualEntry persists manual history entry and loads it into state`() = runTest {
        val insertedEntry = slot<HistoryEntry>()
        val callback = mockk<(Long) -> Unit>(relaxed = true)
        coEvery { repository.insertHistoryEntry(capture(insertedEntry)) } returns 91L

        viewModel.createManualEntry(app, callback)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.insertHistoryEntry(any()) }
        verify(exactly = 1) { callback.invoke(91L) }
        assertTrue(insertedEntry.captured.isManualEntry)
        assertEquals(app.getString(R.string.supplier_manual), insertedEntry.captured.supplier)
        assertEquals(91L, viewModel.currentEntryStatus.value.third)
        assertEquals("barcode", viewModel.excelData.first()[0])
        assertTrue(viewModel.generated.value)
    }

    @Test
    fun `addManualRow appends row updates history entry and tracks last category`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(1L) } returns historyEntry(uid = 1L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.add(listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"))
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.completeStates.add(false)

        viewModel.addManualRow(
            entryUid = 1L,
            rowData = listOf("11112222", "Manual Product", "3.0", "5.0", "2", "Cat A"),
            categoryName = "Cat A"
        )
        advanceUntilIdle()
        waitForCondition { viewModel.lastUsedCategory.value == "Cat A" }

        assertEquals(2, viewModel.excelData.size)
        assertEquals("11112222", viewModel.excelData[1][0])
        assertEquals("Cat A", viewModel.lastUsedCategory.value)
        assertEquals(2, viewModel.editableValues.size)
        assertEquals(2, viewModel.completeStates.size)
        assertEquals("11112222", updatedEntry.captured.data[1][0])
    }

    private fun seedColumnSelectionState() {
        viewModel.excelData.add(listOf("barcode", "quantity"))
        viewModel.selectedColumns.addAll(listOf(false, false))
    }

    private fun seedGeneratedGrid() {
        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "quantity"),
                listOf("12345678", "Generated Product", "4", "2")
            )
        )
        viewModel.selectedColumns.addAll(listOf(true, true, true, true))
        viewModel.editableValues.addAll(
            listOf(
                mutableListOf(mutableStateOf(""), mutableStateOf("")),
                mutableListOf(mutableStateOf("3"), mutableStateOf("7"))
            )
        )
        viewModel.completeStates.addAll(listOf(false, true))
    }

    private fun historyEntry(
        uid: Long = 0L,
        data: List<List<String>> = listOf(listOf("barcode")),
        editable: List<List<String>> = listOf(listOf("", "")),
        complete: List<Boolean> = listOf(false),
        supplier: String = "",
        category: String = ""
    ) = HistoryEntry(
        uid = uid,
        id = "history-$uid",
        timestamp = "2026-03-28 10:00:00",
        data = data,
        editable = editable,
        complete = complete,
        supplier = supplier,
        category = category,
        totalItems = 0,
        orderTotal = 0.0,
        paymentTotal = 0.0,
        missingItems = 0,
        syncStatus = SyncStatus.NOT_ATTEMPTED,
        wasExported = false,
        isManualEntry = false
    )

    private fun waitForCondition(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }
}

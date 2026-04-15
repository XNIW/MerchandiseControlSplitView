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
import com.example.merchandisecontrolsplitview.testutil.createMalformedLegacyObjWorkbookFile
import com.example.merchandisecontrolsplitview.testutil.createStrictOoXmlWorkbookFile
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
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
        every { repository.getFilteredHistoryListFlow(any()) } returns flowOf(emptyList())
        every { repository.hasHistoryEntriesFlow() } returns flowOf(false)
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
    fun `getPreGenerateDataQualitySummary keeps duplicate feedback compact and counts missing purchase prices`() =
        runTest {
            viewModel.excelData.addAll(
                listOf(
                    listOf("barcode", "purchasePrice", "productName"),
                    listOf("111", "", "A"),
                    listOf("111", "100", "A second row"),
                    listOf("222", "", "B"),
                    listOf("222", "200", "B second row"),
                    listOf("333", "300", "C"),
                    listOf("333", "300", "C second row")
                )
            )

            val summary = viewModel.getPreGenerateDataQualitySummary()

            assertEquals(3, summary.duplicateBarcodeCount)
            assertEquals(listOf("111", "222"), summary.duplicateBarcodeSamples)
            assertEquals(2, summary.missingPurchasePriceCount)
            assertTrue(summary.hasWarnings)
        }

    @Test
    fun `getPreGenerateDataQualitySummary ignores blank barcodes and missing purchase price column`() =
        runTest {
            viewModel.excelData.addAll(
                listOf(
                    listOf("barcode", "productName"),
                    listOf("", "Missing barcode"),
                    listOf("111", "Single row")
                )
            )

            val summary = viewModel.getPreGenerateDataQualitySummary()

            assertEquals(0, summary.duplicateBarcodeCount)
            assertTrue(summary.duplicateBarcodeSamples.isEmpty())
            assertEquals(0, summary.missingPurchasePriceCount)
            assertFalse(summary.hasWarnings)
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
        assertEquals(insertedEntry.captured.id, viewModel.currentEntryName.value)
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
        assertEquals("history-44", viewModel.currentEntryName.value)
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

    /**
     * EXPECTED_CORRECTION (TASK-027): "1.234" is CL grouped thousands (1234), not decimal 1.234.
     */
    @Test
    fun `updateHistoryEntry CL grouped purchasePrice EXPECTED_CORRECTION uses thousand grouping not decimal dot`() =
        runTest {
            val updatedEntry = slot<HistoryEntry>()
            coEvery { repository.getHistoryEntryByUid(16L) } returns historyEntry(uid = 16L)
            coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

            viewModel.excelData.addAll(
                listOf(
                    listOf("barcode", "purchasePrice", "quantity"),
                    listOf("12345678", "1.234", "2")
                )
            )
            viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            viewModel.completeStates.addAll(listOf(false, true))

            viewModel.updateHistoryEntry(16L)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
            assertEquals(2468.0, updatedEntry.captured.paymentTotal, 0.0001)
            assertEquals(0, updatedEntry.captured.missingItems)
        }

    @Test
    fun `updateHistoryEntry discount column uses parseUserNumericInput for CL comma decimal`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(17L) } returns historyEntry(uid = 17L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "purchasePrice", "quantity", "discountedPrice", "discount"),
                listOf("12345678", "1000", "1", "", "10,5")
            )
        )
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.completeStates.addAll(listOf(false, true))

        viewModel.updateHistoryEntry(17L)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertEquals(895.0, updatedEntry.captured.paymentTotal, 0.0001)
    }

    @Test
    fun `updateHistoryEntry editable CL comma quantity keeps final summary deterministic`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(18L) } returns historyEntry(uid = 18L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "purchasePrice", "quantity"),
                listOf("12345678", "1000", "0")
            )
        )
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.editableValues.add(mutableListOf(mutableStateOf("1,5"), mutableStateOf("")))
        viewModel.completeStates.addAll(listOf(false, true))

        viewModel.updateHistoryEntry(18L)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertEquals(1500.0, updatedEntry.captured.paymentTotal, 0.0001)
        assertEquals(0, updatedEntry.captured.missingItems)
    }

    /**
     * EXPECTED_CORRECTION (TASK-027): "1.234" on discountedPrice is CL grouped thousands and must
     * remain the winning branch over percentage fallback.
     */
    @Test
    fun `updateHistoryEntry discountedPrice branch takes precedence with CL grouped discountedPrice EXPECTED_CORRECTION`() =
        runTest {
            val updatedEntry = slot<HistoryEntry>()
            coEvery { repository.getHistoryEntryByUid(19L) } returns historyEntry(uid = 19L)
            coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

            viewModel.excelData.addAll(
                listOf(
                    listOf("barcode", "purchasePrice", "quantity", "discountedPrice", "discount"),
                    listOf("12345678", "1000", "1", "1.234", "50")
                )
            )
            viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            viewModel.completeStates.addAll(listOf(false, true))

            viewModel.updateHistoryEntry(19L)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
            assertEquals(1234.0, updatedEntry.captured.paymentTotal, 0.0001)
        }

    @Test
    fun `updateHistoryEntry discountedPrice branch takes precedence over discount percent`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(20L) } returns historyEntry(uid = 20L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "purchasePrice", "quantity", "discountedPrice", "discount"),
                listOf("12345678", "1000", "1", "500", "50")
            )
        )
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.completeStates.addAll(listOf(false, true))

        viewModel.updateHistoryEntry(20L)
        advanceUntilIdle()

        assertEquals(500.0, updatedEntry.captured.paymentTotal, 0.0001)
    }

    @Test
    fun `updateHistoryEntry invalid numeric input falls back to zero without changing completed-row missing semantics`() =
        runTest {
            val updatedEntry = slot<HistoryEntry>()
            coEvery { repository.getHistoryEntryByUid(21L) } returns historyEntry(uid = 21L)
            coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

            viewModel.excelData.addAll(
                listOf(
                    listOf("barcode", "purchasePrice", "quantity"),
                    listOf("12345678", "abc", "abc")
                )
            )
            viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
            viewModel.completeStates.addAll(listOf(false, true))

            viewModel.updateHistoryEntry(21L)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
            assertEquals(0.0, updatedEntry.captured.paymentTotal, 0.0001)
            assertEquals(0, updatedEntry.captured.missingItems)
        }

    /**
     * EXPECTED_CORRECTION (TASK-027): initial summary uses CL parsers; "1.234" × 2 = 2468 not ~2.468.
     */
    @Test
    fun `generateFilteredWithOldPrices initial CL grouped orderTotal EXPECTED_CORRECTION`() = runTest {
        val insertedEntry = slot<HistoryEntry>()
        val callback = mockk<(Long) -> Unit>(relaxed = true)
        coEvery { repository.insertHistoryEntry(capture(insertedEntry)) } returns 78L
        coEvery {
            repository.getPreviousPricesForBarcodes(match { it == listOf("12345678") }, any())
        } returns mapOf("12345678" to (2.0 to 5.0))

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "quantity"),
                listOf("12345678", "Generated Product", "1.234", "2")
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

        viewModel.generateFilteredWithOldPrices("Supplier A", "Category A", callback)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.insertHistoryEntry(any()) }
        assertEquals(2468.0, insertedEntry.captured.orderTotal, 0.0001)
        assertEquals(2468.0, insertedEntry.captured.paymentTotal, 0.0001)
        assertEquals(1, insertedEntry.captured.totalItems)
        assertEquals(1, insertedEntry.captured.missingItems)
    }

    /**
     * EXPECTED_CORRECTION (TASK-027): "1.234,5" must parse as CL grouped decimal 1234.5.
     */
    @Test
    fun `generateFilteredWithOldPrices initial CL grouped decimal orderTotal EXPECTED_CORRECTION`() = runTest {
        val insertedEntry = slot<HistoryEntry>()
        val callback = mockk<(Long) -> Unit>(relaxed = true)
        coEvery { repository.insertHistoryEntry(capture(insertedEntry)) } returns 79L
        coEvery {
            repository.getPreviousPricesForBarcodes(match { it == listOf("12345678") }, any())
        } returns mapOf("12345678" to (2.0 to 5.0))

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "quantity"),
                listOf("12345678", "Generated Product", "1.234,5", "2")
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

        viewModel.generateFilteredWithOldPrices("Supplier A", "Category A", callback)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.insertHistoryEntry(any()) }
        assertEquals(2469.0, insertedEntry.captured.orderTotal, 0.0001)
        assertEquals(2469.0, insertedEntry.captured.paymentTotal, 0.0001)
        assertEquals(1, insertedEntry.captured.totalItems)
        assertEquals(1, insertedEntry.captured.missingItems)
    }

    @Test
    fun `renameHistoryEntry updates id supplier and category`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery {
            repository.getHistoryEntryByUid(30L)
        } returns historyEntry(uid = 30L, supplier = "Old Supplier", category = "Old Category")
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.renameHistoryEntry(
            entryUid = 30L,
            newName = "new_name.xlsx",
            newSupplier = "New Supplier",
            newCategory = "New Category"
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(30L) }
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
    fun `renameHistoryEntry by uid fetches full entry before update`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(33L) } returns historyEntry(
            uid = 33L,
            supplier = "Stored Supplier",
            category = "Stored Category"
        )
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.renameHistoryEntry(
            entryUid = 33L,
            newName = "renamed.xlsx"
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(33L) }
        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertEquals("renamed.xlsx", updatedEntry.captured.id)
        assertEquals("Stored Supplier", updatedEntry.captured.supplier)
        assertEquals("Stored Category", updatedEntry.captured.category)
    }

    @Test
    fun `deleteHistoryEntry delegates delete to repository`() = runTest {
        val entry = historyEntry(uid = 31L)
        coEvery { repository.getHistoryEntryByUid(31L) } returns entry

        viewModel.deleteHistoryEntry(31L)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(31L) }
        coVerify(exactly = 1) { repository.deleteHistoryEntry(entry) }
        assertEquals(
            app.getString(R.string.history_entry_deleted),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `renameHistoryEntry failure emits localized feedback`() = runTest {
        coEvery { repository.getHistoryEntryByUid(32L) } returns historyEntry(uid = 32L)
        coEvery { repository.updateHistoryEntry(any()) } throws IllegalStateException("db unavailable")

        viewModel.renameHistoryEntry(
            entryUid = 32L,
            newName = "broken.xlsx"
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(32L) }
        assertEquals(
            app.getString(R.string.error_history_entry_rename),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `markCurrentEntryAsExported fetches full entry before update`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(51L) } returns historyEntry(uid = 51L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.markCurrentEntryAsExported(51L)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(51L) }
        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertTrue(updatedEntry.captured.wasExported)
        assertEquals(listOf(listOf("barcode")), updatedEntry.captured.data)
    }

    @Test
    fun `markCurrentEntryAsSyncedSuccessfully fetches full entry before update`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(52L) } returns historyEntry(uid = 52L)
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.markCurrentEntryAsSyncedSuccessfully(52L)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(52L) }
        coVerify(exactly = 1) { repository.updateHistoryEntry(any()) }
        assertEquals(SyncStatus.SYNCED_SUCCESSFULLY, updatedEntry.captured.syncStatus)
        assertEquals(listOf(listOf("barcode")), updatedEntry.captured.data)
    }

    @Test
    fun `loadHistoryEntry by uid restores state and invokes callback`() = runTest {
        val callback = mockk<() -> Unit>(relaxed = true)
        coEvery { repository.getHistoryEntryByUid(44L) } returns historyEntry(
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

        viewModel.loadHistoryEntry(44L, callback)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getHistoryEntryByUid(44L) }
        verify(exactly = 1) { callback.invoke() }
        assertEquals("10", viewModel.editableValues[1][0].value)
        assertEquals("Supplier", viewModel.supplierName)
        assertEquals("Category", viewModel.categoryName)
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
            app.getString(R.string.error_file_read_failed),
            viewModel.loadError.value
        )
    }

    @Test
    fun `loadFromMultipleUris malformed legacy xls loads preview rows`() = runTest {
        val malformedWorkbook = createMalformedLegacyObjWorkbookFile(
            cacheDir = app.cacheDir,
            name = "load-malformed-legacy",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Quantity", "Total Price"),
                listOf("12345678", "Recovered Item", 4.0, 2.0, 8.0)
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(malformedWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 2 && !viewModel.isLoading.value }

        assertEquals(null, viewModel.loadError.value)
        assertEquals(
            listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
            viewModel.excelData.first()
        )
        assertEquals(listOf("12345678", "Recovered Item", "4", "2", "8"), viewModel.excelData[1])
    }

    @Test
    fun `loadFromMultipleUris strict ooxml xlsx loads preview rows`() = runTest {
        val strictWorkbook = createStrictOoXmlWorkbookFile(
            cacheDir = app.cacheDir,
            name = "load-strict-ooxml",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Quantity", "Total Price"),
                listOf("12345678", "Strict Item", 4.0, 2.0, 8.0)
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(strictWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 2 && !viewModel.isLoading.value }

        assertEquals(null, viewModel.loadError.value)
        assertEquals(
            listOf("barcode", "productName", "purchasePrice", "quantity", "totalPrice"),
            viewModel.excelData.first()
        )
        assertEquals(listOf("12345678", "Strict Item", "4", "2", "8"), viewModel.excelData[1])
    }

    @Test
    fun `loadFromMultipleUris excludes footer rows with false product identity from preview`() = runTest {
        val workbook = createWorkbook(
            name = "load-footer-summary",
            rows = hyperAsianLikeRows(
                listOf(
                    listOf("1", "12345678", "ITEM-1", "Alpha", "2", "4", "8"),
                    listOf("2", "23456789", "ITEM-2", "Beta", "1", "5", "5")
                ),
                listOf("3", "0", "150", "合计总数", "3", "9", "13")
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(workbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 3 && !viewModel.isLoading.value }

        val header = viewModel.excelData.first()
        val nameCol = header.indexOf("productName")
        val itemCol = header.indexOf("itemNumber")

        assertEquals(null, viewModel.loadError.value)
        assertEquals(3, viewModel.excelData.size)
        assertFalse(viewModel.excelData.drop(1).any { row -> row.getOrNull(nameCol) == "合计总数" })
        assertFalse(viewModel.excelData.drop(1).any { row -> row.getOrNull(itemCol) == "150" })
    }

    @Test
    fun `loadFromMultipleUris empty first workbook uses first file empty message`() = runTest {
        val emptyWorkbook = createWorkbook("excel-first-empty", emptyList())

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(emptyWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.loadError.value != null }

        assertEquals(
            app.getString(R.string.error_first_file_empty_or_invalid),
            viewModel.loadError.value
        )
        assertTrue(viewModel.excelData.isEmpty())
    }

    @Test
    fun `loadFromMultipleUris merges no-header files after structural cleanup without spurious rows or columns`() = runTest {
        val firstWorkbook = createWorkbook(
            name = "load-no-header-first",
            rows = listOf(
                listOf("", "20034", "6871128200344", "Dream Item One", "", 12.0, 270.0, 3240.0),
                listOf("", "20089", "6871128200894", "Dream Item Two", "", 24.0, 480.0, 11520.0),
                emptyList(),
                emptyList()
            )
        )
        val secondWorkbook = createWorkbook(
            name = "load-no-header-second",
            rows = listOf(
                listOf("", "20102", "6871128201020", "Dream Item Three", "", 6.0, 550.0, 3300.0),
                emptyList(),
                emptyList(),
                emptyList()
            )
        )

        viewModel.loadFromMultipleUris(
            app,
            listOf(Uri.fromFile(firstWorkbook), Uri.fromFile(secondWorkbook))
        )
        advanceUntilIdle()
        waitForCondition { !viewModel.isLoading.value }

        assertEquals(null, viewModel.loadError.value)
        assertEquals(
            listOf("itemNumber", "barcode", "productName", "quantity", "purchasePrice", "totalPrice"),
            viewModel.excelData.first()
        )
        assertEquals(
            listOf("20034", "6871128200344", "Dream Item One", "12", "270", "3240"),
            viewModel.excelData[1]
        )
        assertEquals(
            listOf("20102", "6871128201020", "Dream Item Three", "6", "550", "3300"),
            viewModel.excelData.last()
        )
        assertEquals(
            listOf("pattern", "pattern", "pattern", "pattern", "pattern", "pattern"),
            viewModel.headerTypes.toList()
        )
    }

    @Test
    fun `loadFromMultipleUris handles printable split header workbook`() = runTest {
        val workbook = createWorkbook(
            name = "load-split-header",
            rows = listOf(
                listOf("SHOPPING HOGAR SPA", "", "", "", "", "", "", "", ""),
                listOf("Nº ALBARAN :", "13076", "FECHA", "1/4/2026 16:41", "COD.CLIE:1048", "", "PAG:", "1/1", ""),
                listOf("", "REF.CAJAS", "COD.BARRA", "", "CANTID", "PRE/U", "DTO%", "PRE/U", "IMPORTE"),
                listOf("", "", "", "ARTICULO", "", "", "", "", ""),
                listOf(1, "10161", "6120000101614", "XJ2204-3", 12, 900, 0, 900, 10800),
                listOf(2, "10162", "6120000101621", "YJ5237", 12, 1100, 0, 1100, 13200)
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(workbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 3 && !viewModel.isLoading.value }

        val header = viewModel.excelData.first()
        assertEquals("", header[0])
        assertEquals("itemNumber", header[1])
        assertEquals("barcode", header[2])
        assertEquals("productName", header[3])
        assertEquals("quantity", header[4])
        assertEquals("purchasePrice", header[5])
        assertEquals("discount", header[6])
        assertEquals("totalPrice", header[8])
        assertEquals(
            listOf("1", "10161", "6120000101614", "XJ2204-3", "12", "900", "0", "900", "10800"),
            viewModel.excelData[1]
        )
        assertEquals("alias", viewModel.headerTypes[1])
        assertEquals("alias", viewModel.headerTypes[2])
        assertEquals("alias", viewModel.headerTypes[3])
        assertEquals("alias", viewModel.headerTypes[4])
        assertEquals("alias", viewModel.headerTypes[5])
        assertEquals("alias", viewModel.headerTypes[6])
    }

    @Test
    fun `loadFromMultipleUris handles shopping hogar printable offsets with grouped totals`() = runTest {
        fun sparseRow(lastColumn: Int, values: Map<Int, Any?>): List<Any?> {
            val row = MutableList<Any?>(lastColumn + 1) { "" }
            values.forEach { (index, value) -> row[index] = value }
            return row
        }

        val workbook = createWorkbook(
            name = "load-shopping-hogar-printable",
            rows = listOf(
                sparseRow(
                    51,
                    mapOf(
                        3 to "Nº ALBARAN : 13076",
                        13 to "FECHA: 1/4/2026 16:41",
                        20 to "COD.CLIE:1048",
                        36 to "PAG: 1/1"
                    )
                ),
                sparseRow(
                    51,
                    mapOf(
                        1 to "REF.CAJAS",
                        9 to "COD.BARRA",
                        27 to "CANTID",
                        33 to "PRE/U",
                        40 to "DTO%",
                        44 to "PRE/U",
                        49 to "IMPORTE"
                    )
                ),
                sparseRow(51, mapOf(9 to "ARTICULO")),
                sparseRow(
                    51,
                    mapOf(
                        1 to "1",
                        6 to "10161",
                        11 to "6120000101614",
                        15 to "XJ2204-3马桶刷",
                        29 to "12",
                        38 to "900",
                        43 to "0",
                        47 to "900",
                        51 to "10,800"
                    )
                ),
                sparseRow(
                    51,
                    mapOf(
                        1 to "2",
                        6 to "10162",
                        11 to "6120000101621",
                        15 to "YJ5237马桶刷",
                        29 to "12",
                        38 to "1,100",
                        43 to "0",
                        47 to "1,100",
                        51 to "13,200"
                    )
                ),
                sparseRow(
                    51,
                    mapOf(
                        1 to "3",
                        6 to "10163",
                        11 to "6120000101638",
                        15 to "HX-093马桶吸",
                        29 to "12",
                        38 to "1,000",
                        43 to "0",
                        47 to "1,000",
                        51 to "12,000"
                    )
                ),
                sparseRow(
                    51,
                    mapOf(
                        1 to "4",
                        6 to "11683",
                        11 to "6120000116830",
                        15 to "45X2M 无胶膜玻璃贴 R041",
                        29 to "8",
                        38 to "1,500",
                        43 to "0",
                        47 to "1,500",
                        51 to "12,000"
                    )
                ),
                sparseRow(
                    51,
                    mapOf(
                        1 to "5",
                        6 to "11692",
                        11 to "6120000116922",
                        15 to "45X2M 无胶膜玻璃贴 R001",
                        29 to "8",
                        38 to "1,500",
                        43 to "0",
                        47 to "1,500",
                        51 to "12,000"
                    )
                )
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(workbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 6 && !viewModel.isLoading.value }

        val header = viewModel.excelData.first()
        val itemNumberIdx = header.indexOf("itemNumber")
        val barcodeIdx = header.indexOf("barcode")
        val productNameIdx = header.indexOf("productName")
        val quantityIdx = header.indexOf("quantity")
        val purchasePriceIdx = header.indexOf("purchasePrice")
        val totalPriceIdx = header.indexOf("totalPrice")

        assertTrue(header.contains("REF.CAJAS"))
        assertTrue(itemNumberIdx >= 0)
        assertTrue(barcodeIdx >= 0)
        assertTrue(productNameIdx >= 0)
        assertTrue(quantityIdx >= 0)
        assertTrue(purchasePriceIdx >= 0)
        assertTrue(totalPriceIdx >= 0)
        assertEquals(
            "10161",
            viewModel.excelData[1][itemNumberIdx]
        )
        assertEquals("6120000101614", viewModel.excelData[1][barcodeIdx])
        assertEquals("XJ2204-3马桶刷", viewModel.excelData[1][productNameIdx])
        assertEquals("12", viewModel.excelData[1][quantityIdx])
        assertEquals("900", viewModel.excelData[1][purchasePriceIdx])
        assertEquals("10,800", viewModel.excelData[1][totalPriceIdx])
        assertEquals("pattern", viewModel.headerTypes[itemNumberIdx])
        assertEquals("pattern", viewModel.headerTypes[purchasePriceIdx])
        assertEquals("pattern", viewModel.headerTypes[totalPriceIdx])
    }

    @Test
    fun `appendFromMultipleUris without base grid shows main file needed and keeps state`() = runTest {
        val validWorkbook = createWorkbook(
            name = "append-no-base",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(12345678.0, "Base Item", 4.0, 6.0, 2.0)
            )
        )
        val before = captureGridState()

        viewModel.appendFromMultipleUris(app, listOf(Uri.fromFile(validWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.loadError.value != null && !viewModel.isLoading.value }

        assertEquals(
            app.getString(R.string.error_main_file_needed),
            viewModel.loadError.value
        )
        assertEquals(before, captureGridState())
    }

    @Test
    fun `appendFromMultipleUris all empty files keeps grid unchanged and shows append error`() = runTest {
        val baseWorkbook = createWorkbook(
            name = "append-base",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(12345678.0, "Base Item", 4.0, 6.0, 2.0)
            )
        )
        val emptyWorkbook = createWorkbook("append-empty", emptyList())

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(baseWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 2 }
        val before = captureGridState()

        viewModel.appendFromMultipleUris(app, listOf(Uri.fromFile(emptyWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.loadError.value != null && !viewModel.isLoading.value }

        assertEquals(
            app.getString(R.string.error_append_no_data_rows),
            viewModel.loadError.value
        )
        assertEquals(before, captureGridState())
    }

    @Test
    fun `appendFromMultipleUris incompatible header keeps grid unchanged`() = runTest {
        val baseWorkbook = createWorkbook(
            name = "append-base-incompatible",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(12345678.0, "Base Item", 4.0, 6.0, 2.0)
            )
        )
        val incompatibleWorkbook = createWorkbook(
            name = "append-incompatible",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Quantity"),
                listOf(87654321.0, "Other Item", 5.0, 1.0)
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(baseWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 2 }
        val before = captureGridState()

        viewModel.appendFromMultipleUris(app, listOf(Uri.fromFile(incompatibleWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.loadError.value != null && !viewModel.isLoading.value }

        assertEquals(
            app.getString(R.string.error_incompatible_file_structure),
            viewModel.loadError.value
        )
        assertEquals(before, captureGridState())
    }

    @Test
    fun `appendFromMultipleUris appends valid rows and skips empty files in same batch`() = runTest {
        val baseWorkbook = createWorkbook(
            name = "append-base-valid",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(12345678.0, "Base Item", 4.0, 6.0, 2.0)
            )
        )
        val emptyWorkbook = createWorkbook("append-skip-empty", emptyList())
        val appendWorkbook = createWorkbook(
            name = "append-valid",
            rows = listOf(
                listOf("Barcode", "Product name", "Purchase Price", "Retail Price", "Quantity"),
                listOf(87654321.0, "Added Item", 5.0, 8.0, 3.0)
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(baseWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 2 }

        viewModel.appendFromMultipleUris(
            app,
            listOf(Uri.fromFile(emptyWorkbook), Uri.fromFile(appendWorkbook))
        )
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 3 && !viewModel.isLoading.value }

        assertEquals(null, viewModel.loadError.value)
        assertEquals(listOf("87654321", "Added Item", "5", "8", "3"), viewModel.excelData.last())
        assertEquals(3, viewModel.editableValues.size)
        assertEquals(listOf("", ""), viewModel.editableValues.last().map { it.value })
        assertEquals(listOf(false, false, false), viewModel.completeStates.toList())
    }

    @Test
    fun `appendFromMultipleUris keeps footer rows excluded for compatible appended files`() = runTest {
        val baseWorkbook = createWorkbook(
            name = "append-footer-base",
            rows = hyperAsianLikeRows(
                listOf(listOf("1", "12345678", "ITEM-1", "Alpha", "2", "4", "8")),
                listOf("2", "0", "2", "合计总数", "2", "4", "8")
            )
        )
        val appendWorkbook = createWorkbook(
            name = "append-footer-extra",
            rows = hyperAsianLikeRows(
                listOf(listOf("1", "23456789", "ITEM-2", "Beta", "1", "5", "5")),
                listOf("2", "0", "1", "合计总数", "1", "5", "5")
            )
        )

        viewModel.loadFromMultipleUris(app, listOf(Uri.fromFile(baseWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 2 && !viewModel.isLoading.value }

        viewModel.appendFromMultipleUris(app, listOf(Uri.fromFile(appendWorkbook)))
        advanceUntilIdle()
        waitForCondition { viewModel.excelData.size == 3 && !viewModel.isLoading.value }

        val header = viewModel.excelData.first()
        val nameCol = header.indexOf("productName")

        assertEquals(null, viewModel.loadError.value)
        assertEquals(3, viewModel.excelData.size)
        assertEquals("Beta", viewModel.excelData.last()[nameCol])
        assertFalse(viewModel.excelData.drop(1).any { row -> row.getOrNull(nameCol) == "合计总数" })
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
        assertEquals(
            app.getString(R.string.manual_row_added),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `addManualRow does not emit success feedback when history entry is missing`() = runTest {
        coEvery { repository.getHistoryEntryByUid(11L) } returns null

        viewModel.excelData.add(listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"))
        viewModel.editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        viewModel.completeStates.add(false)

        viewModel.addManualRow(
            entryUid = 11L,
            rowData = listOf("99990000", "Manual Product", "3.0", "5.0", "2", "Cat Z"),
            categoryName = "Cat Z"
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateHistoryEntry(any()) }
        assertEquals(null, viewModel.historyActionMessage.value)
        assertEquals(null, viewModel.lastUsedCategory.value)
    }

    @Test
    fun `updateManualRow updates row history entry and emits localized feedback`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(2L) } returns historyEntry(
            uid = 2L,
            data = listOf(
                listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"),
                listOf("11112222", "Before", "3.0", "5.0", "2", "Cat A")
            ),
            editable = listOf(listOf("", ""), listOf("", "")),
            complete = listOf(false, false)
        )
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"),
                listOf("11112222", "Before", "3.0", "5.0", "2", "Cat A")
            )
        )
        viewModel.editableValues.addAll(
            listOf(
                mutableListOf(mutableStateOf(""), mutableStateOf("")),
                mutableListOf(mutableStateOf(""), mutableStateOf(""))
            )
        )
        viewModel.completeStates.addAll(listOf(false, false))

        viewModel.updateManualRow(
            entryUid = 2L,
            index = 0,
            rowData = listOf("11112222", "After", "4.0", "6.0", "3", "Cat B"),
            categoryName = "Cat B"
        )
        advanceUntilIdle()
        waitForCondition { viewModel.historyActionMessage.value == app.getString(R.string.manual_row_updated) }

        assertEquals("After", viewModel.excelData[1][1])
        assertEquals("Cat B", viewModel.lastUsedCategory.value)
        assertEquals("After", updatedEntry.captured.data[1][1])
        assertEquals(
            app.getString(R.string.manual_row_updated),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `updateManualRow does not emit success feedback when history entry is missing`() = runTest {
        coEvery { repository.getHistoryEntryByUid(12L) } returns null

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"),
                listOf("11112222", "Before", "3.0", "5.0", "2", "Cat A")
            )
        )
        viewModel.editableValues.addAll(
            listOf(
                mutableListOf(mutableStateOf(""), mutableStateOf("")),
                mutableListOf(mutableStateOf(""), mutableStateOf(""))
            )
        )
        viewModel.completeStates.addAll(listOf(false, false))

        viewModel.updateManualRow(
            entryUid = 12L,
            index = 0,
            rowData = listOf("11112222", "After", "4.0", "6.0", "3", "Cat B"),
            categoryName = "Cat B"
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateHistoryEntry(any()) }
        assertEquals(null, viewModel.historyActionMessage.value)
        assertEquals(null, viewModel.lastUsedCategory.value)
    }

    @Test
    fun `deleteManualRow removes row state persists history entry and emits localized feedback`() = runTest {
        val updatedEntry = slot<HistoryEntry>()
        coEvery { repository.getHistoryEntryByUid(3L) } returns historyEntry(
            uid = 3L,
            data = listOf(
                listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"),
                listOf("11112222", "Delete Me", "3.0", "5.0", "2", "Cat A")
            ),
            editable = listOf(listOf("", ""), listOf("", "")),
            complete = listOf(false, false)
        )
        coEvery { repository.updateHistoryEntry(capture(updatedEntry)) } just runs

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"),
                listOf("11112222", "Delete Me", "3.0", "5.0", "2", "Cat A")
            )
        )
        viewModel.editableValues.addAll(
            listOf(
                mutableListOf(mutableStateOf(""), mutableStateOf("")),
                mutableListOf(mutableStateOf(""), mutableStateOf(""))
            )
        )
        viewModel.completeStates.addAll(listOf(false, false))

        viewModel.deleteManualRow(entryUid = 3L, index = 0)
        advanceUntilIdle()
        waitForCondition { viewModel.historyActionMessage.value == app.getString(R.string.manual_row_deleted) }

        assertEquals(1, viewModel.excelData.size)
        assertEquals(1, viewModel.editableValues.size)
        assertEquals(1, viewModel.completeStates.size)
        assertEquals(1, updatedEntry.captured.data.size)
        assertEquals(
            app.getString(R.string.manual_row_deleted),
            viewModel.historyActionMessage.value
        )
    }

    @Test
    fun `deleteManualRow does not emit success feedback when history entry is missing`() = runTest {
        coEvery { repository.getHistoryEntryByUid(13L) } returns null

        viewModel.excelData.addAll(
            listOf(
                listOf("barcode", "productName", "purchasePrice", "retailPrice", "quantity", "category"),
                listOf("11112222", "Delete Me", "3.0", "5.0", "2", "Cat A")
            )
        )
        viewModel.editableValues.addAll(
            listOf(
                mutableListOf(mutableStateOf(""), mutableStateOf("")),
                mutableListOf(mutableStateOf(""), mutableStateOf(""))
            )
        )
        viewModel.completeStates.addAll(listOf(false, false))

        viewModel.deleteManualRow(entryUid = 13L, index = 0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateHistoryEntry(any()) }
        assertEquals(null, viewModel.historyActionMessage.value)
    }

    private fun createWorkbook(
        name: String,
        rows: List<List<Any?>>,
        configure: (XSSFWorkbook, Sheet) -> Unit = { _, _ -> }
    ): File {
        val file = File.createTempFile(name, ".xlsx", app.cacheDir)
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet1")
            rows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex)
                values.forEachIndexed { cellIndex, value ->
                    when (value) {
                        null -> Unit
                        is Number -> row.createCell(cellIndex).setCellValue(value.toDouble())
                        else -> row.createCell(cellIndex).setCellValue(value.toString())
                    }
                }
            }
            configure(workbook, sheet)
            file.outputStream().use(workbook::write)
        }
        return file
    }

    private fun hyperAsianLikeRows(
        products: List<List<Any?>>,
        footer: List<Any?>
    ): List<List<Any?>> {
        return listOf(
            listOf("rowNumber", "barcode", "itemNumber", "productName", "quantity", "purchasePrice", "totalPrice")
        ) + products + listOf(footer)
    }

    private fun captureGridState() = GridSnapshot(
        data = viewModel.excelData.map { it.toList() },
        editable = viewModel.editableValues.map { row -> row.map { it.value } },
        complete = viewModel.completeStates.toList(),
        headerTypes = viewModel.headerTypes.toList()
    )

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

    private data class GridSnapshot(
        val data: List<List<String>>,
        val editable: List<List<String>>,
        val complete: List<Boolean>,
        val headerTypes: List<String>
    )
}

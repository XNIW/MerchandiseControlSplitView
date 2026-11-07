package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.util.ExcelAnalysisResult
import com.example.merchandisecontrolsplitview.util.ExcelAnalysisTrace
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126LocalPresentationStamp
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Source artifacts only until ROOT assigns a JVM lane. No Room store in this fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BootstrapDraftBridgeTest {
    @get:Rule val mainDispatcher = MainDispatcherRule(StandardTestDispatcher())

    @Test fun sameDraftMappingAndPresentationInputsAreAdoptedWithoutResetByOpenedBusinessVm() = runTest(mainDispatcher.dispatcher) {
        val repository = mockk<InventoryRepository>(relaxed = true)
        val ram = ExcelImportDraftState(backgroundScope)
        ram.excelData.addAll(listOf(listOf("barcode", "productName", "purchasePrice"), listOf("A", "Draft", "4")))
        ram.originalHeaders.addAll(listOf("Code", "Name", "Cost"))
        ram.headerTypes.addAll(listOf("alias", "alias", "alias"))
        ram.initPreGenerateState()
        ram.editableValues[1][0].value = "7"
        ram.completeStates[1] = true
        ram.supplierQuery.value = "Typed before opening"
        ram.categoryQuery.value = "Unbound RAM category"
        verify(exactly = 0) { repository.getFilteredHistoryFlow(any()) }
        val vm = ExcelViewModel(RuntimeEnvironment.getApplication(), repository, ram)
        assertSame(ram, vm.importDraftState)
        assertSame(ram.excelData, vm.excelData)
        assertSame(ram.headerTypes, vm.headerTypes)
        assertSame(ram.selectedColumns, vm.selectedColumns)
        assertEquals("7", vm.editableValues[1][0].value)
        assertTrue(vm.completeStates[1])
        assertEquals("Typed before opening", vm.importDraftState.supplierQuery.value)
        vm.setHeaderType(1, "secondProductName")
        assertEquals("secondProductName", ram.excelData[0][1])
    }

    @Test fun heldRealParserCompletesIntoSameDraftAfterBusinessVmAdoptsIt() = runTest(mainDispatcher.dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parser = object : ExcelImportParser {
            override suspend fun readDetailed(context: Context, uri: Uri): ExcelAnalysisResult {
                entered.complete(Unit)
                release.await()
                return ExcelAnalysisResult(listOf("barcode"), listOf("Code"),
                    listOf(listOf("late parsed row")), listOf("alias"),
                    ExcelAnalysisTrace(true, 1, listOf(0), "held-seam", 1, emptyList()))
            }
            override suspend fun read(context: Context, uri: Uri, allowEmptyTabularResult: Boolean):
                Triple<List<String>, List<List<String>>, List<String>> = error("Only one file expected")
        }
        val ram = ExcelImportDraftState(this, parser, mainDispatcher.dispatcher)
        ram.loadFromMultipleUris(RuntimeEnvironment.getApplication(), listOf(Uri.parse("content://held/bootstrap.xlsx")))
        runCurrent()
        assertTrue(entered.isCompleted)
        assertTrue(ram.isLoading.value)
        val vm = ExcelViewModel(RuntimeEnvironment.getApplication(), mockk(relaxed = true), ram)
        ram.supplierQuery.value = "Typed during physical opening"
        release.complete(Unit)
        advanceUntilIdle()
        assertSame(ram, vm.importDraftState)
        assertEquals("late parsed row", vm.excelData[1][0])
        assertFalse(vm.isLoading.value)
        assertEquals("Typed during physical opening", vm.importDraftState.supplierQuery.value)
        vm.resetState()
        assertTrue(ram.excelData.isEmpty())
        assertEquals("", ram.supplierQuery.value)
    }

    @Test fun explicitHistorySelectionRetiresHeldRawParserBeforeItCanOverwriteBusinessSession() = runTest(mainDispatcher.dispatcher) {
        val release = CompletableDeferred<Unit>()
        val parser = object : ExcelImportParser {
            override suspend fun readDetailed(context: Context, uri: Uri): ExcelAnalysisResult {
                withContext(NonCancellable) { release.await() }
                return ExcelAnalysisResult(listOf("barcode"), listOf("Code"), listOf(listOf("stale import")),
                    listOf("alias"), ExcelAnalysisTrace(true, 1, listOf(0), "held-seam", 1, emptyList()))
            }
            override suspend fun read(context: Context, uri: Uri, allowEmptyTabularResult: Boolean):
                Triple<List<String>, List<List<String>>, List<String>> = error("Only one file expected")
        }
        val ram = ExcelImportDraftState(this, parser, mainDispatcher.dispatcher)
        ram.loadFromMultipleUris(RuntimeEnvironment.getApplication(), listOf(Uri.parse("content://held/retired.xlsx")))
        runCurrent()
        val vm = ExcelViewModel(RuntimeEnvironment.getApplication(), mockk(relaxed = true), ram)
        vm.loadHistoryEntry(HistoryEntry(uid = 42, id = "stored", timestamp = "2026-11-05", data =
            listOf(listOf("barcode"), listOf("stored session")), editable = listOf(listOf("", ""), listOf("", "")),
            complete = listOf(false, false)))
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("stored session", vm.excelData[1][0])
        assertEquals(42L, vm.currentEntryStatus.value.third)
        assertTrue(vm.generated.value)
        assertFalse(vm.isLoading.value)
    }

    private fun rawFileDraft(): ExcelImportDraftState = ExcelImportDraftState(kotlinx.coroutines.CoroutineScope(mainDispatcher.dispatcher)).also { raw ->
        raw.excelData.addAll(listOf(listOf("barcode", "productName", "purchasePrice"), listOf("FILE-ONLY", "Raw typed item", "4")))
        raw.originalHeaders.addAll(listOf("Code", "Name", "Cost"))
        raw.headerTypes.addAll(listOf("alias", "alias", "alias"))
        raw.initPreGenerateState()
        raw.editableValues[1][0].value = "7"
        raw.supplierQuery.value = "Unbound typed supplier"
        raw.categoryQuery.value = "Unbound typed category"
    }

    private fun readStates() = MutableStateFlow(LocalBusinessReadSnapshot(
        LocalBusinessPresentationKey("owner-a", 1, "shop-a", "local-a", 126, 2, 1),
        Task126BusinessDataScopeState.unmanagedAllowed(), Task126LocalPresentationStamp(1), true))

    private fun injectGate(vm: ExcelViewModel, states: MutableStateFlow<LocalBusinessReadSnapshot>) {
        ExcelViewModel::class.java.getDeclaredField("localReadGate").apply { isAccessible = true }
            .set(vm, LocalBusinessReadGate(states))
    }

    private fun assertPristineRaw(raw: ExcelImportDraftState, vm: ExcelViewModel, before: List<List<String>>) {
        // This is the exact object passed to PreGenerateDraftScreen by the stable root.
        assertSame(raw, vm.importDraftState)
        assertEquals(before, vm.importDraftState.excelData.toList())
        assertFalse(vm.importDraftState.excelData.flatten().contains("77123"))
        assertFalse(vm.importDraftState.excelData.flatten().contains("88234"))
        assertFalse(vm.importDraftState.excelData.flatten().contains("DB-HISTORY-SECRET"))
        assertEquals(listOf("Code", "Name", "Cost"), raw.originalHeaders.toList())
        assertEquals(listOf(true, true, true), raw.selectedColumns.toList())
        assertEquals("7", raw.editableValues[1][0].value)
        assertEquals("Unbound typed supplier", raw.supplierQuery.value)
        assertEquals("Unbound typed category", raw.categoryQuery.value)
    }

    @Test fun generatedOldPricesStayInGatedBusinessProjectionAcrossWithdrawalForeignKeyAndReturnToRaw() = runTest(mainDispatcher.dispatcher) {
        val repository = mockk<InventoryRepository>(relaxed = true)
        val inserted = slot<HistoryEntry>()
        coEvery { repository.getPreviousPricesForBarcodes(listOf("FILE-ONLY"), any()) } returns
            mapOf("FILE-ONLY" to (77123.0 to 88234.0))
        coEvery { repository.insertHistoryEntry(capture(inserted)) } returns 71L
        val raw = rawFileDraft()
        val before = raw.excelData.map { it.toList() }
        val vm = ExcelViewModel(RuntimeEnvironment.getApplication(), repository, raw)
        val states = readStates()
        injectGate(vm, states)
        var callback: Long? = null
        vm.generateFilteredWithOldPrices("Supplier", "Category") { callback = it }
        advanceUntilIdle()
        assertEquals(71L, callback)
        assertTrue(vm.excelData[0].contains("oldPurchasePrice"))
        assertTrue(vm.excelData.flatten().contains("77123"))
        assertTrue(vm.excelData.flatten().contains("88234"))
        assertNotSame(vm.excelData, raw.excelData)
        assertEquals(inserted.captured.data, vm.excelData.toList())
        assertPristineRaw(raw, vm, before)
        states.value = states.value.copy(allowed = false) // same-key read retirement while Generated is hidden
        assertPristineRaw(raw, vm, before) // Back can only mount this raw input
        states.value = states.value.copy(key = states.value.key!!.copy(owner = "owner-b", authGeneration = 2, store = "shop-b"))
        assertPristineRaw(raw, vm, before)
        vm.revertToPreGenerateState()
        assertSame(raw.excelData, vm.excelData)
        assertFalse(vm.generated.value)
        assertPristineRaw(raw, vm, before)
        states.value = states.value.copy(allowed = true, stamp = Task126LocalPresentationStamp(2))
        vm.generateFilteredWithOldPrices("Supplier B", "Category B") { callback = it }
        advanceUntilIdle()
        assertEquals(1, vm.excelData[0].count { it == "oldPurchasePrice" })
        coVerify(exactly = 2) { repository.getPreviousPricesForBarcodes(listOf("FILE-ONLY"), any()) }
        assertPristineRaw(raw, vm, before)
    }

    @Test fun storedHistoryRowsNeverEnterRawPreviewAfterSameKeyRetirementOrOwnerShopAuthChange() = runTest(mainDispatcher.dispatcher) {
        val raw = rawFileDraft()
        val before = raw.excelData.map { it.toList() }
        val vm = ExcelViewModel(RuntimeEnvironment.getApplication(), mockk(relaxed = true), raw)
        val states = readStates()
        injectGate(vm, states)
        vm.loadHistoryEntry(HistoryEntry(uid = 99, id = "private-stored", timestamp = "2026-11-05",
            data = listOf(listOf("barcode", "oldPurchasePrice"), listOf("DB-HISTORY-SECRET", "77123")),
            editable = listOf(listOf("", ""), listOf("2", "3")), complete = listOf(false, true)))
        assertEquals("DB-HISTORY-SECRET", vm.excelData[1][0])
        assertNotSame(raw.excelData, vm.excelData)
        assertPristineRaw(raw, vm, before)
        states.value = states.value.copy(allowed = false)
        assertPristineRaw(raw, vm, before)
        states.value = states.value.copy(key = states.value.key!!.copy(owner = "owner-b", authGeneration = 2, store = "shop-b"))
        assertPristineRaw(raw, vm, before)
        assertEquals(99L, vm.currentEntryStatus.value.third) // gated session retained, never leaked into raw
    }
}

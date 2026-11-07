package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import com.example.merchandisecontrolsplitview.util.ExcelAnalysisResult
import com.example.merchandisecontrolsplitview.util.ExcelAnalysisTrace
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** FILE-ONLY candidate: tests have NOT been run in this proposal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ExcelImportDraftStateTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val header = listOf("barcode", "productName", "purchasePrice", "opaque")
    private val uri = Uri.parse("content://unbound-draft/file.xlsx")

    private fun analysis(barcode: String) = ExcelAnalysisResult(
        header = header,
        originalHeaders = listOf("EAN", "Name", "Price", "Mystery"),
        dataRows = listOf(listOf(barcode, "Product", "10", "raw")),
        headerSource = listOf("alias", "alias", "alias", "unknown"),
        trace = ExcelAnalysisTrace(true, 1, listOf(0), "test-seam", 1, emptyList())
    )

    private class Parser(
        val detailed: suspend (Context, Uri) -> ExcelAnalysisResult,
        val plain: suspend (Context, Uri, Boolean) -> Triple<List<String>, List<List<String>>, List<String>> =
            { _, _, _ -> error("Unexpected secondary-file parser call") }
    ) : ExcelImportParser {
        override suspend fun readDetailed(context: Context, uri: Uri) = detailed(context, uri)
        override suspend fun read(context: Context, uri: Uri, allowEmptyTabularResult: Boolean) =
            plain(context, uri, allowEmptyTabularResult)
    }

    @Test
    fun `new request retains loading authority when cancelled old parser finishes first`() = runTest(main.dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        val currentGate = CompletableDeferred<Unit>()
        val oldUri = Uri.parse("content://unbound-draft/old")
        val parser = Parser(detailed = { _, selected ->
            if (selected == oldUri) {
                withContext(NonCancellable) { oldGate.await() }
                analysis("old")
            } else {
                currentGate.await()
                analysis("current")
            }
        })
        val draft = ExcelImportDraftState(this, parser, main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(oldUri))
        runCurrent()
        draft.loadFromMultipleUris(context, listOf(uri))
        runCurrent()
        draft.supplierQuery.value = "typed supplier"
        draft.categoryQuery.value = "typed category"
        oldGate.complete(Unit)
        runCurrent()
        assertTrue(draft.isLoading.value)
        assertNull(draft.loadError.value)
        assertEquals(5, draft.loadingProgress.value)
        assertTrue(draft.excelData.isEmpty())
        currentGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("current", draft.excelData[1][0])
        assertEquals("typed supplier", draft.supplierQuery.value)
        assertEquals("typed category", draft.categoryQuery.value)
        assertEquals(listOf(true, true, true, false), draft.selectedColumns.toList())
        assertFalse(draft.isLoading.value)
        assertNull(draft.loadingProgress.value)
    }

    @Test
    fun `reset rejects a held parser completion and clears only raw draft state`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val parser = Parser(detailed = { _, _ ->
            withContext(NonCancellable) { gate.await() }
            analysis("late")
        })
        val draft = ExcelImportDraftState(this, parser, main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(uri))
        runCurrent()
        draft.supplierQuery.value = "to clear"
        draft.categoryQuery.value = "to clear"
        draft.resetState()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(draft.excelData.isEmpty())
        assertTrue(draft.originalHeaders.isEmpty())
        assertTrue(draft.headerTypes.isEmpty())
        assertTrue(draft.selectedColumns.isEmpty())
        assertTrue(draft.editableValues.isEmpty())
        assertTrue(draft.completeStates.isEmpty())
        assertTrue(draft.errorRowIndexes.value.isEmpty())
        assertEquals("", draft.supplierQuery.value)
        assertEquals("", draft.categoryQuery.value)
        assertFalse(draft.isLoading.value)
        assertNull(draft.loadError.value)
        assertNull(draft.loadingProgress.value)
    }

    @Test
    fun `stale read error cannot replace a newer successful draft`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val oldUri = Uri.parse("content://unbound-draft/old-error")
        val parser = Parser(detailed = { _, selected ->
            if (selected == oldUri) {
                withContext(NonCancellable) { gate.await() }
                throw IOException("old failure")
            }
            analysis("new")
        })
        val draft = ExcelImportDraftState(this, parser, main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(oldUri))
        runCurrent()
        draft.loadFromMultipleUris(context, listOf(uri))
        runCurrent()
        assertEquals("new", draft.excelData[1][0])
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", draft.excelData[1][0])
        assertNull(draft.loadError.value)
        assertFalse(draft.isLoading.value)
    }

    @Test
    fun `failed replacement preserves prior draft and maps first empty file message`() = runTest(main.dispatcher) {
        var fail = false
        val parser = Parser(detailed = { appContext, _ ->
            if (fail) throw IllegalArgumentException(appContext.getString(R.string.error_file_empty_or_invalid))
            analysis("retained")
        })
        val draft = ExcelImportDraftState(this, parser, main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(uri))
        advanceUntilIdle()
        val before = draft.excelData.map { it.toList() }
        fail = true
        draft.loadFromMultipleUris(context, listOf(uri))
        advanceUntilIdle()
        assertEquals(before, draft.excelData.toList())
        assertEquals(context.getString(R.string.error_first_file_empty_or_invalid), draft.loadError.value)
        assertFalse(draft.isLoading.value)
        assertNull(draft.loadingProgress.value)
    }

    @Test
    fun `incompatible append publishes no partial rows`() = runTest(main.dispatcher) {
        val incompatible = Uri.parse("content://unbound-draft/incompatible")
        val parser = Parser(
            detailed = { _, _ -> analysis("original") },
            plain = { _, selected, allowEmpty ->
                assertTrue(allowEmpty)
                Triple(
                    if (selected == incompatible) listOf("different") else header,
                    listOf(listOf("appended", "Product", "20", "raw")),
                    emptyList()
                )
            }
        )
        val draft = ExcelImportDraftState(this, parser, main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(uri))
        advanceUntilIdle()
        val before = draft.excelData.map { it.toList() }
        draft.appendFromMultipleUris(context, listOf(uri, incompatible))
        advanceUntilIdle()
        assertEquals(before, draft.excelData.toList())
        assertEquals(2, draft.editableValues.size)
        assertEquals(2, draft.completeStates.size)
        assertEquals(context.getString(R.string.error_incompatible_file_structure), draft.loadError.value)
    }

    @Test
    fun `append skips empty tables and preserves prior mapping and editable cells`() = runTest(main.dispatcher) {
        val emptyUri = Uri.parse("content://unbound-draft/empty")
        val parser = Parser(
            detailed = { _, _ -> analysis("A") },
            plain = { _, selected, allowEmpty ->
                assertTrue(allowEmpty)
                if (selected == emptyUri) Triple(emptyList(), emptyList(), emptyList())
                else Triple(header, listOf(listOf("A", "Product", "", "more")), emptyList())
            }
        )
        val draft = ExcelImportDraftState(this, parser, main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(uri))
        advanceUntilIdle()
        draft.selectedColumns[3] = true
        draft.editableValues[1][0].value = "kept edit"
        draft.completeStates[1] = true
        draft.appendFromMultipleUris(context, listOf(emptyUri, uri))
        advanceUntilIdle()
        assertEquals(3, draft.excelData.size)
        assertEquals(3, draft.editableValues.size)
        assertEquals(3, draft.completeStates.size)
        assertTrue(draft.selectedColumns[3])
        assertEquals("kept edit", draft.editableValues[1][0].value)
        assertTrue(draft.completeStates[1])
        assertFalse(draft.completeStates[2])
        assertEquals(listOf("EAN", "Name", "Price", "Mystery"), draft.originalHeaders.toList())
        assertEquals(PreGenerateDataQualitySummary(1, listOf("A"), 1), draft.getPreGenerateDataQualitySummary())
        assertNull(draft.loadError.value)
    }

    @Test
    fun `column defaults essential locks mapping and restore match existing semantics`() = runTest(main.dispatcher) {
        val draft = ExcelImportDraftState(this, Parser(detailed = { _, _ -> analysis("A") }), main.dispatcher)
        draft.loadFromMultipleUris(context, listOf(uri))
        advanceUntilIdle()
        draft.toggleColumnSelection(0)
        assertTrue(draft.selectedColumns[0])
        draft.toggleSelectAll()
        assertTrue(draft.selectedColumns.all { it })
        draft.toggleSelectAll()
        assertEquals(listOf(true, true, true, false), draft.selectedColumns.toList())
        draft.setHeaderType(3, "quantity")
        assertEquals("quantity", draft.excelData[0][3])
        assertTrue(draft.selectedColumns[3])
        draft.restoreOriginalHeader(3)
        assertEquals("Mystery", draft.excelData[0][3])
        assertEquals("unknown", draft.headerTypes[3])
        assertFalse(draft.selectedColumns[3])
        assertTrue(draft.defaultIsColumnIncluded("custom", "pattern"))
        assertFalse(draft.defaultIsColumnIncluded("custom", "generated"))
        assertFalse(draft.defaultIsColumnIncluded(" ", "alias"))
    }

    @Test
    fun `RAM viewmodel exposes one state without constructing production application`() = runTest(main.dispatcher) {
        val vm = RamExcelImportViewModel()
        val store = ViewModelStore()
        store.put("ram-import", vm)
        assertSame(vm.draft, vm.draft)
        assertTrue(vm.draft.excelData.isEmpty())
        assertFalse(vm.draft.isLoading.value)
        store.clear()
    }
}

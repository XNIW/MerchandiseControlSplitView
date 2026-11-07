package com.example.merchandisecontrolsplitview.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.ExcelAnalysisResult
import com.example.merchandisecontrolsplitview.util.ExcelFileUserError
import com.example.merchandisecontrolsplitview.util.canonicalExcelHeaderKey
import com.example.merchandisecontrolsplitview.util.classifyExcelFileUserError
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcelDetailed
import com.example.merchandisecontrolsplitview.util.resolveExcelFileErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Suspend seam for file parsing only; neither implementation owns business storage. */
internal interface ExcelImportParser {
    suspend fun readDetailed(context: Context, uri: Uri): ExcelAnalysisResult
    suspend fun read(
        context: Context,
        uri: Uri,
        allowEmptyTabularResult: Boolean
    ): Triple<List<String>, List<List<String>>, List<String>>
}

private object DefaultExcelImportParser : ExcelImportParser {
    override suspend fun readDetailed(context: Context, uri: Uri): ExcelAnalysisResult =
        readAndAnalyzeExcelDetailed(context, uri)

    override suspend fun read(
        context: Context,
        uri: Uri,
        allowEmptyTabularResult: Boolean
    ): Triple<List<String>, List<List<String>>, List<String>> =
        readAndAnalyzeExcel(context, uri, allowEmptyTabularResult)
}

/**
 * Unbound raw file/mapping draft. Create once with the stable RAM ViewModel scope,
 * then share this same object with the business ViewModel after store admission.
 * Call mutations on the owner's Main scope; all parsing runs on [ioDispatcher].
 */
internal class ExcelImportDraftState(
    private val scope: CoroutineScope,
    private val parser: ExcelImportParser = DefaultExcelImportParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val excelData = mutableStateListOf<List<String>>()
    val originalHeaders = mutableStateListOf<String>()
    val headerTypes = mutableStateListOf<String>()
    val selectedColumns = mutableStateListOf<Boolean>()
    val editableValues = mutableStateListOf<MutableList<MutableState<String>>>()
    val completeStates = mutableStateListOf<Boolean>()
    val errorRowIndexes = mutableStateOf<Set<Int>>(emptySet())
    val isLoading = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    val loadingProgress = mutableStateOf<Int?>(null)
    val supplierQuery = mutableStateOf("")
    val categoryQuery = mutableStateOf("")

    private val essentialColumns = setOf("barcode", "productName", "purchasePrice")
    private val defaultIncludedColumnKeys = setOf(
        "barcode", "productName", "purchasePrice", "quantity", "retailPrice",
        "totalPrice", "secondProductName", "itemNumber", "supplier", "rowNumber",
        "discount", "discountedPrice", "category", "realQuantity",
        "oldPurchasePrice", "oldRetailPrice"
    )
    private var requestEpoch = 0L
    private var parseJob: Job? = null

    private fun beginRequest(): Long {
        // Retire the previous publication authority BEFORE cancellation executes
        // its catch/finally blocks. Blocking file readers may finish afterwards.
        requestEpoch += 1
        parseJob?.cancel()
        parseJob = null
        return requestEpoch
    }

    private fun isCurrent(epoch: Long): Boolean = requestEpoch == epoch

    private fun updateProgress(epoch: Long, progress: Int?) {
        if (isCurrent(epoch)) loadingProgress.value = progress?.coerceIn(0, 100)
    }

    fun loadFromMultipleUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val epoch = beginRequest()
        parseJob = scope.launch {
            if (!isCurrent(epoch)) return@launch
            isLoading.value = true
            loadError.value = null
            updateProgress(epoch, 5)
            try {
                val firstAnalysis = try {
                    withContext(ioDispatcher) { parser.readDetailed(context, uris.first()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (classifyExcelFileUserError(context, error) == ExcelFileUserError.EmptyOrInvalid) {
                        throw IllegalArgumentException(context.getString(R.string.error_first_file_empty_or_invalid))
                    }
                    throw error
                }
                if (!isCurrent(epoch)) return@launch
                val goldenHeader = firstAnalysis.header
                val allValidRows = firstAnalysis.dataRows.toMutableList()
                updateProgress(epoch, 15)
                val remaining = uris.size - 1
                uris.drop(1).forEachIndexed { index, uri ->
                    val (newHeader, newRows, _) = withContext(ioDispatcher) {
                        parser.read(context, uri, allowEmptyTabularResult = false)
                    }
                    if (!isCurrent(epoch)) return@launch
                    if (newHeader != goldenHeader) {
                        throw IllegalArgumentException(context.getString(R.string.error_different_columns))
                    }
                    allValidRows.addAll(newRows)
                    updateProgress(epoch, 15 + ((index + 1) * 70 / remaining))
                }
                if (!isCurrent(epoch)) return@launch
                updateProgress(epoch, 95)
                // Publish a complete replacement only after every file succeeds;
                // a failed selection does not partially overwrite the old draft.
                clearRowsAndMapping()
                excelData.add(goldenHeader)
                excelData.addAll(allValidRows)
                originalHeaders.addAll(firstAnalysis.originalHeaders)
                headerTypes.addAll(firstAnalysis.headerSource)
                initPreGenerateState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(epoch)) {
                    loadError.value = resolveExcelFileErrorMessage(
                        context, error, R.string.error_unknown_file_analysis
                    )
                }
            } finally {
                if (isCurrent(epoch)) {
                    isLoading.value = false
                    updateProgress(epoch, null)
                }
            }
        }
    }

    fun appendFromMultipleUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val epoch = beginRequest()
        parseJob = scope.launch {
            if (!isCurrent(epoch)) return@launch
            isLoading.value = true
            loadError.value = null
            updateProgress(epoch, 5)
            try {
                val originalHeader = excelData.firstOrNull()?.toList()
                if (originalHeader.isNullOrEmpty()) {
                    loadError.value = context.getString(R.string.error_main_file_needed)
                    return@launch
                }
                val allNewRows = mutableListOf<List<String>>()
                val total = uris.size.coerceAtLeast(1)
                for ((index, uri) in uris.withIndex()) {
                    val (newHeader, newRows, _) = withContext(ioDispatcher) {
                        parser.read(context, uri, allowEmptyTabularResult = true)
                    }
                    if (!isCurrent(epoch)) return@launch
                    if (newHeader.isEmpty() && newRows.isEmpty()) {
                        updateProgress(epoch, 10 + ((index + 1) * 80 / total))
                        continue
                    }
                    if (originalHeader != newHeader) {
                        throw IllegalArgumentException(context.getString(R.string.error_incompatible_file_structure))
                    }
                    allNewRows.addAll(newRows)
                    updateProgress(epoch, 10 + ((index + 1) * 80 / total))
                }
                if (!isCurrent(epoch)) return@launch
                if (allNewRows.isEmpty()) {
                    loadError.value = context.getString(R.string.error_append_no_data_rows)
                    return@launch
                }
                updateProgress(epoch, 95)
                excelData.addAll(allNewRows)
                repeat(allNewRows.size) {
                    editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
                    completeStates.add(false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(epoch)) {
                    loadError.value = resolveExcelFileErrorMessage(context, error, R.string.error_adding_files)
                }
            } finally {
                if (isCurrent(epoch)) {
                    isLoading.value = false
                    updateProgress(epoch, null)
                }
            }
        }
    }

    private fun clearRowsAndMapping() {
        excelData.clear()
        originalHeaders.clear()
        headerTypes.clear()
        selectedColumns.clear()
        editableValues.clear()
        completeStates.clear()
        errorRowIndexes.value = emptySet()
    }

    /** Retire a pending parser without discarding the user's raw file/mapping. */
    fun retirePendingFileRead() {
        beginRequest()
        isLoading.value = false
        loadingProgress.value = null
    }

    fun resetState() {
        retirePendingFileRead()
        clearRowsAndMapping()
        loadError.value = null
        supplierQuery.value = ""
        categoryQuery.value = ""
    }

    fun initPreGenerateState() {
        selectedColumns.clear()
        excelData.firstOrNull()?.size?.let { columns ->
            repeat(columns) { selectedColumns.add(defaultIsColumnSelected(it)) }
        }
        editableValues.clear()
        repeat(excelData.size) {
            editableValues.add(mutableListOf(mutableStateOf(""), mutableStateOf("")))
        }
        completeStates.clear()
        repeat(excelData.size) { completeStates.add(false) }
    }

    fun isColumnEssential(colIdx: Int): Boolean =
        excelData.firstOrNull()?.getOrNull(colIdx) in essentialColumns

    fun toggleColumnSelection(colIdx: Int) {
        if (isColumnEssential(colIdx)) {
            if (colIdx in selectedColumns.indices) selectedColumns[colIdx] = true
            return
        }
        if (colIdx in selectedColumns.indices) selectedColumns[colIdx] = !selectedColumns[colIdx]
    }

    fun toggleSelectAll() {
        val anyUnselected = selectedColumns.indices.any { !selectedColumns[it] && !isColumnEssential(it) }
        selectedColumns.indices.forEach { index ->
            selectedColumns[index] = isColumnEssential(index) || anyUnselected
        }
    }

    fun defaultIsColumnIncluded(headerKey: String?, headerType: String?): Boolean {
        val canonicalKey = headerKey?.trim()?.takeIf { it.isNotBlank() }
            ?.let { canonicalExcelHeaderKey(it) ?: it } ?: return false
        if (canonicalKey in defaultIncludedColumnKeys) return true
        return when (headerType?.trim()) {
            "alias", "pattern" -> true
            null, "", "unknown", "generated" -> false
            else -> true
        }
    }

    private fun defaultIsColumnSelected(colIdx: Int): Boolean = defaultIsColumnIncluded(
        excelData.firstOrNull()?.getOrNull(colIdx), headerTypes.getOrNull(colIdx)
    )

    fun setHeaderType(colIdx: Int, type: String?) {
        if (colIdx in headerTypes.indices) headerTypes[colIdx] = type ?: "unknown"
        val headerRow = excelData.firstOrNull()?.toMutableList()
        if (headerRow != null && colIdx in headerRow.indices && type != null) {
            headerRow[colIdx] = type
            excelData[0] = headerRow
        }
        if (colIdx in selectedColumns.indices) {
            selectedColumns[colIdx] = isColumnEssential(colIdx) || defaultIsColumnSelected(colIdx)
        }
    }

    fun restoreOriginalHeader(colIdx: Int) {
        val original = originalHeaders.getOrNull(colIdx)?.takeIf { it.isNotBlank() } ?: return
        if (colIdx in headerTypes.indices) headerTypes[colIdx] = "unknown"
        val headerRow = excelData.firstOrNull()?.toMutableList() ?: return
        if (colIdx in headerRow.indices) {
            headerRow[colIdx] = original
            excelData[0] = headerRow
        }
        if (colIdx in selectedColumns.indices) {
            selectedColumns[colIdx] = isColumnEssential(colIdx) || defaultIsColumnSelected(colIdx)
        }
    }

    fun getPreGenerateDataQualitySummary(): PreGenerateDataQualitySummary {
        val header = excelData.firstOrNull() ?: return PreGenerateDataQualitySummary()
        val barcodeIndex = header.indexOf("barcode")
        val purchasePriceIndex = header.indexOf("purchasePrice")
        if (barcodeIndex == -1) return PreGenerateDataQualitySummary()
        val occurrences = LinkedHashMap<String, Int>()
        var missingPrices = 0
        excelData.drop(1).forEach { row ->
            val barcode = row.getOrNull(barcodeIndex)?.trim().orEmpty()
            if (barcode.isBlank()) return@forEach
            occurrences[barcode] = (occurrences[barcode] ?: 0) + 1
            if (purchasePriceIndex != -1 && row.getOrNull(purchasePriceIndex)?.trim().orEmpty().isBlank()) {
                missingPrices++
            }
        }
        val duplicates = occurrences.filterValues { it > 1 }.keys.toList()
        return PreGenerateDataQualitySummary(
            duplicateBarcodeCount = duplicates.size,
            duplicateBarcodeSamples = duplicates.take(2),
            missingPurchasePriceCount = missingPrices
        )
    }
}

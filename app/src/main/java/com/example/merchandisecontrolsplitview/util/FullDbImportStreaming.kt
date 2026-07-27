package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.ImportPriceHistoryEntry
import com.example.merchandisecontrolsplitview.data.ImportRowSource
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.CatalogTextNormalizationWarning
import com.example.merchandisecontrolsplitview.data.RowImportError
import com.example.merchandisecontrolsplitview.util.canonicalExcelHeaderKey
import com.example.merchandisecontrolsplitview.util.normalizeExcelHeader
import kotlinx.coroutines.runBlocking
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.StylesTable
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

private const val PRICE_HISTORY_BATCH_SIZE = 500
private const val MAX_ENTITY_TEXT_DIAGNOSTICS = 500
private const val MAX_ALL_ENTITY_TEXT_DIAGNOSTICS = MAX_ENTITY_TEXT_DIAGNOSTICS * 2
enum class ImportWorkbookRoute {
    SINGLE_SHEET,
    FULL_DATABASE
}

internal sealed interface SmartImportWorkbookOutcome {
    data object SingleSheet : SmartImportWorkbookOutcome
    data class FullDatabaseAnalyzed(
        val result: FullDbImportStreamingResult
    ) : SmartImportWorkbookOutcome
}

internal object FullDbImportStreamingTestHooks {
    @Volatile
    var onWorkbookStaged: ((uri: Uri, stagedFile: File) -> Unit)? = null
}

data class FullDbImportStreamingResult(
    val analysis: ImportAnalyzer.DeferredRelationImportAnalysis,
    val pendingSupplierNames: Set<String>,
    val pendingCategoryNames: Set<String>,
    val pendingPriceHistory: List<ImportPriceHistoryEntry>,
    val hasPriceHistorySheet: Boolean,
    val productsRowCount: Int,
    val supplierRowCount: Int,
    val categoryRowCount: Int,
    val datasetFingerprint: ImportDatasetFingerprint
)

private data class ParsedPriceHistoryRow(
    val barcode: String,
    val type: String,
    val timestamp: String,
    val price: Double,
    val source: String?
)

private data class PriceHistoryHeaderIndexes(
    val barcodeIndex: Int,
    val timestampIndex: Int,
    val typeIndex: Int,
    val newPriceIndex: Int,
    val sourceIndex: Int
)

private data class ParsedEntityNamesSheet(
    val names: Set<String>,
    val rowCount: Int,
    val warnings: List<CatalogTextNormalizationWarning>,
    val warningCount: Int,
    val errors: List<RowImportError>,
    val errorCount: Int
)

private class StrictIdentityCollisionTracker(
    private val field: CatalogTextField,
    private val required: Boolean,
    private val maxLength: Int
) {
    private val firstRawByCanonical = linkedMapOf<String, String>()

    fun canonicalize(raw: String): String {
        val canonical = when (
            val outcome = CatalogTextPolicy.strict(
                raw = raw,
                required = required,
                maxLength = maxLength
            )
        ) {
            is CatalogTextPolicy.Outcome.Unchanged -> outcome.value
            is CatalogTextPolicy.Outcome.Normalized -> outcome.value
            is CatalogTextPolicy.Outcome.Rejected -> throw CatalogTextValidationException(
                CatalogTextPolicy.FieldRejection(field, outcome.reason)
            )
        }
        if (!required && canonical.isEmpty()) return canonical

        val firstRaw = firstRawByCanonical.putIfAbsent(canonical, raw)
        if (firstRaw != null && firstRaw != raw) {
            throw CatalogTextValidationException(
                CatalogTextPolicy.FieldRejection(
                    field,
                    CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM
                )
            )
        }
        return canonical
    }
}

private class StopAfterFirstParsedRow : RuntimeException(null, null, false, false)

private fun <T> MutableList<T>.appendBounded(values: List<T>) {
    val remaining = (MAX_ALL_ENTITY_TEXT_DIAGNOSTICS - size).coerceAtLeast(0)
    if (remaining > 0) addAll(values.take(remaining))
}

internal suspend fun analyzeSmartImportWorkbook(
    context: Context,
    uri: Uri,
    repository: InventoryRepository,
    loadCurrentDbProducts: suspend () -> List<Product>
): SmartImportWorkbookOutcome {
    if (!looksLikeXlsxWorkbook(context, uri)) {
        return SmartImportWorkbookOutcome.SingleSheet
    }

    return withStagedWorkbook(context, uri) { stagedFile ->
        when (detectImportWorkbookRoute(stagedFile)) {
            ImportWorkbookRoute.SINGLE_SHEET -> SmartImportWorkbookOutcome.SingleSheet
            ImportWorkbookRoute.FULL_DATABASE -> SmartImportWorkbookOutcome.FullDatabaseAnalyzed(
                result = analyzeFullDbImportStreaming(
                    context = context,
                    stagedFile = stagedFile,
                    currentDbProducts = loadCurrentDbProducts(),
                    repository = repository
                )
            )
        }
    }
}

suspend fun detectImportWorkbookRoute(
    context: Context,
    uri: Uri
): ImportWorkbookRoute {
    if (!looksLikeXlsxWorkbook(context, uri)) {
        return ImportWorkbookRoute.SINGLE_SHEET
    }

    return withStagedWorkbook(context, uri) { stagedFile ->
        detectImportWorkbookRoute(stagedFile)
    }
}

private fun detectImportWorkbookRoute(stagedFile: File): ImportWorkbookRoute {
    val normalizedSheetNames = inspectWorkbookSheetNames(stagedFile)
        .map(::normalizeExcelHeader)
        .filter { it.isNotBlank() }

    val productsNormalized = normalizeExcelHeader("Products")
    return if (normalizedSheetNames.any { it == productsNormalized }) {
        ImportWorkbookRoute.FULL_DATABASE
    } else {
        ImportWorkbookRoute.SINGLE_SHEET
    }
}

suspend fun analyzeFullDbImportStreaming(
    context: Context,
    uri: Uri,
    currentDbProducts: List<Product>,
    repository: InventoryRepository
): FullDbImportStreamingResult = withStagedWorkbook(context, uri) { stagedFile ->
    analyzeFullDbImportStreaming(
        context = context,
        stagedFile = stagedFile,
        currentDbProducts = currentDbProducts,
        repository = repository
    )
}

private suspend fun analyzeFullDbImportStreaming(
    context: Context,
    stagedFile: File,
    currentDbProducts: List<Product>,
    repository: InventoryRepository
): FullDbImportStreamingResult = withWorkbookReader(stagedFile) { reader, styles, sharedStrings ->
    var productsAnalysis: ImportAnalyzer.DeferredRelationImportAnalysis? = null
    var productsRowCount = 0
    var supplierRowCount = 0
    var categoryRowCount = 0
    val supplierNames = linkedSetOf<String>()
    val categoryNames = linkedSetOf<String>()
    val entityWarnings = mutableListOf<CatalogTextNormalizationWarning>()
    val entityErrors = mutableListOf<RowImportError>()
    var entityWarningCount = 0
    var entityErrorCount = 0
    var hasPriceHistorySheet = false
    val priceHistoryRows = mutableListOf<ImportPriceHistoryEntry>()
    val fingerprintBuilder = ImportDatasetFingerprintBuilder()

    forEachWorkbookSheet(reader) { sheetName, sheetStream ->
        when (normalizeExcelHeader(sheetName)) {
            normalizeExcelHeader("Suppliers") -> {
                val parsedNames = parseEntityNamesSheet(
                    context,
                    sheetStream,
                    styles,
                    sharedStrings,
                    field = CatalogTextField.SUPPLIER_NAME,
                    source = ImportRowSource.SUPPLIERS
                )
                supplierRowCount += parsedNames.rowCount
                supplierNames += parsedNames.names
                entityWarnings.appendBounded(parsedNames.warnings)
                entityErrors.appendBounded(parsedNames.errors)
                entityWarningCount += parsedNames.warningCount
                entityErrorCount += parsedNames.errorCount
                parsedNames.names.forEach(fingerprintBuilder::addSupplierName)
            }

            normalizeExcelHeader("Categories") -> {
                val parsedNames = parseEntityNamesSheet(
                    context,
                    sheetStream,
                    styles,
                    sharedStrings,
                    field = CatalogTextField.CATEGORY_NAME,
                    source = ImportRowSource.CATEGORIES
                )
                categoryRowCount += parsedNames.rowCount
                categoryNames += parsedNames.names
                entityWarnings.appendBounded(parsedNames.warnings)
                entityErrors.appendBounded(parsedNames.errors)
                entityWarningCount += parsedNames.warningCount
                entityErrorCount += parsedNames.errorCount
                parsedNames.names.forEach(fingerprintBuilder::addCategoryName)
            }

            normalizeExcelHeader("Products") -> {
                val result = analyzeProductsSheet(
                    context = context,
                    sheetStream = sheetStream,
                    styles = styles,
                    sharedStrings = sharedStrings,
                    currentDbProducts = currentDbProducts,
                    repository = repository,
                    fingerprintBuilder = fingerprintBuilder
                )
                productsAnalysis = result.analysis
                productsRowCount = result.rowCount
            }

            normalizeExcelHeader("PriceHistory") -> {
                val parsedRows = parsePriceHistorySheet(sheetStream, styles, sharedStrings)
                priceHistoryRows += parsedRows
                parsedRows.forEach { row ->
                    fingerprintBuilder.addPriceHistoryEntry(
                        barcode = row.barcode,
                        timestamp = row.timestamp,
                        type = row.type,
                        price = row.price,
                        source = row.source
                    )
                }
                hasPriceHistorySheet = true
            }
        }
    }

    val analysis = productsAnalysis
        ?: throw IllegalArgumentException(context.getString(R.string.error_file_empty_or_invalid))

    val productAnalysis = analysis.analysis
    val mergedAnalysis = productAnalysis.copy(
        errors = productAnalysis.errors + entityErrors,
        textNormalizationWarnings = productAnalysis.textNormalizationWarnings + entityWarnings,
        totalErrorCount = productAnalysis.totalErrorCount + entityErrorCount,
        totalTextNormalizationRowCount =
            productAnalysis.totalTextNormalizationRowCount + entityWarningCount,
        totalTextNormalizationFieldCount =
            productAnalysis.totalTextNormalizationFieldCount + entityWarningCount
    )

    FullDbImportStreamingResult(
        analysis = analysis.copy(analysis = mergedAnalysis),
        pendingSupplierNames = (supplierNames + analysis.pendingSuppliers.values).toSet(),
        pendingCategoryNames = (categoryNames + analysis.pendingCategories.values).toSet(),
        pendingPriceHistory = priceHistoryRows,
        hasPriceHistorySheet = hasPriceHistorySheet,
        productsRowCount = productsRowCount,
        supplierRowCount = supplierRowCount,
        categoryRowCount = categoryRowCount,
        datasetFingerprint = fingerprintBuilder.build(
            productCount = productsRowCount,
            supplierCount = supplierRowCount,
            categoryCount = categoryRowCount,
            priceHistoryCount = priceHistoryRows.size
        )
    )
}

/**
 * Legacy helper for importing only the `PriceHistory` sheet in a second pass.
 *
 * The current UI full-database import path analyzes `PriceHistory` into
 * [FullDbImportStreamingResult.pendingPriceHistory] and persists those rows through
 * `InventoryRepository.applyImport`, so catalog dirty marking stays inside the same
 * repository transaction. Keep this helper behavior unchanged unless a caller is
 * reintroduced with matching repository-level tests.
 */
suspend fun applyFullDbPriceHistoryStreaming(
    context: Context,
    uri: Uri,
    repository: InventoryRepository
): Int = withStagedWorkbook(context, uri) { stagedFile ->
    validatePriceHistoryIdentityCollisions(stagedFile)
    withWorkbookReader(stagedFile) { reader, styles, sharedStrings ->
    var importedRows = 0

    forEachWorkbookSheet(reader) { sheetName, sheetStream ->
        if (normalizeExcelHeader(sheetName) != normalizeExcelHeader("PriceHistory")) {
            return@forEachWorkbookSheet
        }

        var headerIndexes: PriceHistoryHeaderIndexes? = null
        var headerRow: List<String>? = null
        val batch = mutableListOf<ParsedPriceHistoryRow>()
        val barcodeTracker = StrictIdentityCollisionTracker(
            field = CatalogTextField.BARCODE,
            required = true,
            maxLength = CatalogTextPolicy.Limits.BARCODE
        )

        fun flushBatch() {
            if (batch.isEmpty()) return
            val grouped = batch.groupBy { it.source ?: "IMPORT_SHEET" }
            batch.clear()
            runBlocking {
                grouped.forEach { (source, rows) ->
                    repository.recordPriceHistoryByBarcodeBatch(
                        rows = rows.map { row ->
                            Triple(
                                row.barcode,
                                row.type,
                                row.timestamp to row.price
                            )
                        },
                        source = source
                    )
                }
            }
        }

        parseSheetRows(sheetStream, styles, sharedStrings) { row ->
            if (headerIndexes == null) {
                headerRow = row
                headerIndexes = requirePriceHistoryHeaderIndexes(row)
                return@parseSheetRows
            }

            val indexes = requireNotNull(headerIndexes)
            val barcode = barcodeTracker.canonicalize(
                row.getOrNull(indexes.barcodeIndex).orEmpty()
            )
            val timestamp = row.getOrNull(indexes.timestampIndex)?.trim().orEmpty()
            val rawType = row.getOrNull(indexes.typeIndex)?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val newPrice = row.getOrNull(indexes.newPriceIndex)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
            val source = row.getOrNull(indexes.sourceIndex)?.trim().orEmpty().ifBlank { null }

            if (barcode.isBlank() || timestamp.isBlank() || rawType.isBlank() || newPrice == null) {
                return@parseSheetRows
            }

            batch += ParsedPriceHistoryRow(
                barcode = barcode,
                type = if (rawType.startsWith("pur")) "PURCHASE" else "RETAIL",
                timestamp = timestamp,
                price = newPrice,
                source = source
            )
            importedRows++

            if (batch.size >= PRICE_HISTORY_BATCH_SIZE) {
                flushBatch()
            }
        }

        if (headerRow == null) {
            requirePriceHistoryHeaderIndexes(null)
        }

        flushBatch()
    }

    importedRows
    }
}

private suspend fun validatePriceHistoryIdentityCollisions(stagedFile: File) {
    withWorkbookReader(stagedFile) { reader, styles, sharedStrings ->
        forEachWorkbookSheet(reader) { sheetName, sheetStream ->
            if (normalizeExcelHeader(sheetName) != normalizeExcelHeader("PriceHistory")) {
                return@forEachWorkbookSheet
            }

            var headerIndexes: PriceHistoryHeaderIndexes? = null
            var headerRow: List<String>? = null
            val barcodeTracker = StrictIdentityCollisionTracker(
                field = CatalogTextField.BARCODE,
                required = true,
                maxLength = CatalogTextPolicy.Limits.BARCODE
            )
            parseSheetRows(sheetStream, styles, sharedStrings) { row ->
                if (headerIndexes == null) {
                    headerRow = row
                    headerIndexes = requirePriceHistoryHeaderIndexes(row)
                    return@parseSheetRows
                }
                val indexes = requireNotNull(headerIndexes)
                barcodeTracker.canonicalize(
                    row.getOrNull(indexes.barcodeIndex).orEmpty()
                )
            }
            if (headerRow == null) {
                requirePriceHistoryHeaderIndexes(null)
            }
        }
    }
}

private data class ProductsSheetAnalysis(
    val analysis: ImportAnalyzer.DeferredRelationImportAnalysis,
    val rowCount: Int
)

private suspend fun analyzeProductsSheet(
    context: Context,
    sheetStream: InputStream,
    styles: StylesTable,
    sharedStrings: ReadOnlySharedStringsTable,
    currentDbProducts: List<Product>,
    repository: InventoryRepository,
    fingerprintBuilder: ImportDatasetFingerprintBuilder
): ProductsSheetAnalysis {
    var header: List<String>? = null
    var rowCount = 0

    val analysis = ImportAnalyzer.analyzeStreamingDeferredRelations(
        context = context,
        currentDbProducts = currentDbProducts,
        repository = repository
    ) { consumer ->
        parseSheetRows(sheetStream, styles, sharedStrings) { row ->
            if (header == null) {
                header = canonicalizeProductsHeader(row)
                return@parseSheetRows
            }

            val normalizedHeader = requireNotNull(header)
            val mappedRow = normalizedHeader.mapIndexed { index, key ->
                key to (row.getOrNull(index) ?: "")
            }.toMap()
            rowCount++
            fingerprintBuilder.addProductRow(mappedRow)
            consumer(mappedRow)
        }
    }

    if (header == null) {
        throw IllegalArgumentException(context.getString(R.string.error_file_empty_or_invalid))
    }

    return ProductsSheetAnalysis(
        analysis = analysis,
        rowCount = rowCount
    )
}

private fun parseEntityNamesSheet(
    context: Context,
    sheetStream: InputStream,
    styles: StylesTable,
    sharedStrings: ReadOnlySharedStringsTable,
    field: CatalogTextField,
    source: ImportRowSource
): ParsedEntityNamesSheet {
    var nameIndex: Int? = null
    val names = linkedSetOf<String>()
    val warnings = mutableListOf<CatalogTextNormalizationWarning>()
    val errors = mutableListOf<RowImportError>()
    var warningCount = 0
    var errorCount = 0
    var rowCount = 0

    parseSheetRowsIndexed(sheetStream, styles, sharedStrings) { sheetRowNumber, row ->
        if (nameIndex == null) {
            nameIndex = row.indexOfFirst { normalizeExcelHeader(it) == "name" }
            return@parseSheetRowsIndexed
        }

        val idx = requireNotNull(nameIndex)
        val rawName = row.getOrNull(idx)?.takeIf { it.isNotBlank() }
            ?: return@parseSheetRowsIndexed
        rowCount++
        val maxLength = when (field) {
            CatalogTextField.SUPPLIER_NAME -> CatalogTextPolicy.Limits.SUPPLIER_NAME
            CatalogTextField.CATEGORY_NAME -> CatalogTextPolicy.Limits.CATEGORY_NAME
            else -> error("Unsupported full database entity field: $field")
        }
        when (
            val outcome = CatalogTextPolicy.display(
                raw = rawName,
                required = true,
                maxLength = maxLength
            )
        ) {
            is CatalogTextPolicy.Outcome.Unchanged -> names += outcome.value
            is CatalogTextPolicy.Outcome.Normalized -> {
                names += outcome.value
                warningCount++
                if (warnings.size < MAX_ENTITY_TEXT_DIAGNOSTICS) {
                    warnings += CatalogTextNormalizationWarning(
                        rowNumber = sheetRowNumber,
                        fields = setOf(field),
                        source = source
                    )
                }
            }
            is CatalogTextPolicy.Outcome.Rejected -> {
                errorCount++
                if (errors.size < MAX_ENTITY_TEXT_DIAGNOSTICS) {
                    val key = when (field) {
                        CatalogTextField.SUPPLIER_NAME -> "supplier"
                        CatalogTextField.CATEGORY_NAME -> "category"
                    }
                    errors += catalogTextRowError(
                        context = context,
                        rowNumber = sheetRowNumber,
                        row = mapOf(key to rawName),
                        field = field,
                        reason = outcome.reason,
                        source = source
                    )
                }
            }
        }
    }

    return ParsedEntityNamesSheet(
        names = names,
        rowCount = rowCount,
        warnings = warnings,
        warningCount = warningCount,
        errors = errors,
        errorCount = errorCount
    )
}

private fun parsePriceHistorySheet(
    sheetStream: InputStream,
    styles: StylesTable,
    sharedStrings: ReadOnlySharedStringsTable
): List<ImportPriceHistoryEntry> {
    var headerIndexes: PriceHistoryHeaderIndexes? = null
    var headerRow: List<String>? = null
    val rows = mutableListOf<ImportPriceHistoryEntry>()
    val barcodeTracker = StrictIdentityCollisionTracker(
        field = CatalogTextField.BARCODE,
        required = true,
        maxLength = CatalogTextPolicy.Limits.BARCODE
    )

    parseSheetRows(sheetStream, styles, sharedStrings) { row ->
        if (headerIndexes == null) {
            headerRow = row
            headerIndexes = requirePriceHistoryHeaderIndexes(row)
            return@parseSheetRows
        }

        val indexes = requireNotNull(headerIndexes)
        val barcode = barcodeTracker.canonicalize(
            row.getOrNull(indexes.barcodeIndex).orEmpty()
        )
        val timestamp = row.getOrNull(indexes.timestampIndex)?.trim().orEmpty()
        val rawType = row.getOrNull(indexes.typeIndex)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val newPrice = row.getOrNull(indexes.newPriceIndex)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
        val source = row.getOrNull(indexes.sourceIndex)?.trim().orEmpty().ifBlank { null }

        if (barcode.isBlank() || timestamp.isBlank() || rawType.isBlank() || newPrice == null) {
            return@parseSheetRows
        }

        rows += ImportPriceHistoryEntry(
            barcode = barcode,
            type = if (rawType.startsWith("pur")) "PURCHASE" else "RETAIL",
            timestamp = timestamp,
            price = newPrice,
            source = source
        )
    }

    if (headerRow == null) {
        requirePriceHistoryHeaderIndexes(null)
    }

    return rows
}

private fun validatePriceHistorySheetHeader(
    sheetStream: InputStream,
    styles: StylesTable,
    sharedStrings: ReadOnlySharedStringsTable
) {
    var headerRow: List<String>? = null
    try {
        parseSheetRows(sheetStream, styles, sharedStrings) { row ->
            headerRow = row
            throw StopAfterFirstParsedRow()
        }
    } catch (_: StopAfterFirstParsedRow) {
        // Early stop once the first non-empty row has been validated as header.
    }
    requirePriceHistoryHeaderIndexes(headerRow)
}

private fun requirePriceHistoryHeaderIndexes(header: List<String>?): PriceHistoryHeaderIndexes {
    val actualHeader = header
        ?: throw IllegalArgumentException("PriceHistory sheet is empty or missing the header row.")
    return resolvePriceHistoryHeaderIndexes(actualHeader)
}

private fun resolvePriceHistoryHeaderIndexes(header: List<String>): PriceHistoryHeaderIndexes {
    val normalizedHeader = header.map(::normalizeExcelHeader)
    val barcodeIndex = normalizedHeader.indexOfFirst { it == "productbarcode" || it == "barcode" }
    val timestampIndex = normalizedHeader.indexOf("timestamp")
    val typeIndex = normalizedHeader.indexOf("type")
    val newPriceIndex = normalizedHeader.indexOf("newprice")
    val sourceIndex = normalizedHeader.indexOf("source")

    val missingHeaders = buildList {
        if (barcodeIndex < 0) add("productBarcode/barcode")
        if (timestampIndex < 0) add("timestamp")
        if (typeIndex < 0) add("type")
        if (newPriceIndex < 0) add("newPrice")
    }

    if (missingHeaders.isNotEmpty()) {
        throw IllegalArgumentException(
            "PriceHistory sheet missing required headers: ${missingHeaders.joinToString(", ")}."
        )
    }

    return PriceHistoryHeaderIndexes(
        barcodeIndex = barcodeIndex,
        timestampIndex = timestampIndex,
        typeIndex = typeIndex,
        newPriceIndex = newPriceIndex,
        sourceIndex = sourceIndex
    )
}

private fun canonicalizeProductsHeader(header: List<String>): List<String> {
    return header.mapIndexed { index, rawHeader ->
        canonicalExcelHeaderKey(rawHeader) ?: rawHeader.trim().ifBlank { "column${index + 1}" }
    }
}

private suspend inline fun <T> withWorkbookReader(
    context: Context,
    uri: Uri,
    block: suspend (reader: XSSFReader, styles: StylesTable, sharedStrings: ReadOnlySharedStringsTable) -> T
): T = withStagedWorkbook(context, uri) { stagedFile ->
    withWorkbookReader(stagedFile, block)
}

private suspend inline fun <T> withWorkbookReader(
    stagedFile: File,
    block: suspend (reader: XSSFReader, styles: StylesTable, sharedStrings: ReadOnlySharedStringsTable) -> T
): T {
    OPCPackage.open(stagedFile, PackageAccess.READ).use { pkg ->
        val reader = XSSFReader(pkg)
        val styles = reader.stylesTable
        val sharedStrings = ReadOnlySharedStringsTable(pkg)
        return block(reader, styles, sharedStrings)
    }
}

private suspend inline fun <T> withStagedWorkbook(
    context: Context,
    uri: Uri,
    block: suspend (stagedFile: File) -> T
): T {
    val stagedFile = stageWorkbookToCache(context, uri)
    try {
        return block(stagedFile)
    } finally {
        stagedFile.delete()
    }
}

private fun stageWorkbookToCache(context: Context, uri: Uri): File {
    val tempFile = File.createTempFile("full-import-", ".xlsx", context.cacheDir)
    try {
        ImportResourcePolicy.validateSourceSizeIfAvailable(context.contentResolver, uri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                ImportResourcePolicy.copy(input, output)
            }
        } ?: throw IOException("Unable to open input stream for $uri")
    } catch (t: Throwable) {
        tempFile.delete()
        throw t
    }
    FullDbImportStreamingTestHooks.onWorkbookStaged?.invoke(uri, tempFile)
    return tempFile
}

private fun looksLikeXlsxWorkbook(context: Context, uri: Uri): Boolean {
    val signature = ByteArray(4)
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytesRead = input.read(signature)
        return bytesRead == signature.size &&
            signature[0] == 0x50.toByte() &&
            signature[1] == 0x4B.toByte() &&
            signature[2] == 0x03.toByte() &&
            signature[3] == 0x04.toByte()
    } ?: throw IOException("Unable to open input stream for $uri")
}

private fun inspectWorkbookSheetNames(stagedFile: File): List<String> {
    OPCPackage.open(stagedFile, PackageAccess.READ).use { pkg ->
        val reader = XSSFReader(pkg)
        val iterator = reader.sheetsData as XSSFReader.SheetIterator
        val sheetNames = mutableListOf<String>()

        while (iterator.hasNext()) {
            iterator.next().use {
                sheetNames += iterator.sheetName
            }
        }

        return sheetNames
    }
}

private suspend fun forEachWorkbookSheet(
    reader: XSSFReader,
    onSheet: suspend (sheetName: String, sheetStream: InputStream) -> Unit
) {
    val iterator = reader.sheetsData as XSSFReader.SheetIterator
    while (iterator.hasNext()) {
        iterator.next().use { sheetStream ->
            onSheet(iterator.sheetName, sheetStream)
        }
    }
}

private fun parseSheetRows(
    sheetStream: InputStream,
    styles: StylesTable,
    sharedStrings: ReadOnlySharedStringsTable,
    onRow: (List<String>) -> Unit
) {
    parseSheetRowsIndexed(sheetStream, styles, sharedStrings) { _, row ->
        onRow(row)
    }
}

private fun parseSheetRowsIndexed(
    sheetStream: InputStream,
    styles: StylesTable,
    sharedStrings: ReadOnlySharedStringsTable,
    onRow: (rowNumber: Int, cells: List<String>) -> Unit
) {
    val sheetHandler = object : XSSFSheetXMLHandler.SheetContentsHandler {
        private val currentRow = linkedMapOf<Int, String>()
        private var lastColumnIndex = -1

        override fun startRow(rowNum: Int) {
            currentRow.clear()
            lastColumnIndex = -1
        }

        override fun endRow(rowNum: Int) {
            if (lastColumnIndex < 0) return

            val cells = MutableList(lastColumnIndex + 1) { "" }
            currentRow.forEach { (columnIndex, value) ->
                cells[columnIndex] = value
            }
            val trimmedCells = cells.dropLastWhile { it.isEmpty() }
            if (trimmedCells.any { it.isNotBlank() }) {
                onRow(rowNum + 1, trimmedCells)
            }
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: org.apache.poi.xssf.usermodel.XSSFComment?) {
            val columnIndex = cellReference
                ?.let { CellReference(it).col.toInt() }
                ?: (lastColumnIndex + 1)
            // Preserve source text until the field-specific policy runs. Trimming
            // here would erase the evidence for strict identity collisions.
            currentRow[columnIndex] = formattedValue.orEmpty()
            lastColumnIndex = maxOf(lastColumnIndex, columnIndex)
        }

        override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) = Unit
    }

    val parserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
    }
    val parser = parserFactory.newSAXParser().xmlReader
    parser.contentHandler = XSSFSheetXMLHandler(
        styles,
        null,
        sharedStrings,
        sheetHandler,
        DataFormatter(),
        false
    )
    parser.parse(InputSource(sheetStream))
}

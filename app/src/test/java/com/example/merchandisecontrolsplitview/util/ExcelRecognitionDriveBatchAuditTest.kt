package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExcelRecognitionDriveBatchAuditTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    @Test
    fun auditDriveSupplierCorpusReadOnly() {
        assumeTrue(
            "Drive batch audit runs only via :app:excelRecognitionDriveBatchAudit",
            System.getProperty("excelAudit.runDriveBatch") == "true"
        )

        val files = resolveBatchFiles()
        assertTrue(
            "No Excel supplier files found. Pass -PexcelAudit.batchDirs=/path/to/corpus",
            files.isNotEmpty()
        )

        val androidResults = files.map { file -> auditAndroidFile(file) }
        val iosResults = files.map { file -> notRunResult(file, Platform.IOS) }
        val adminResults = files.map { file -> notRunResult(file, Platform.ADMIN_WEB) }
        val allResults = androidResults + iosResults + adminResults
        val report = BatchAuditReport(
            metadata = BatchAuditMetadata(
                driveFolderUrl = DRIVE_FOLDER_URL,
                timestamp = Instant.now().toString(),
                harness = "ExcelRecognitionDriveBatchAuditTest/v1-read-only",
                v1InvalidationStatus = V1_INVALIDATION_STATUS,
                v1InvalidationNote = V1_INVALIDATION_NOTE,
                supersededBy = "app/build/reports/excelRecognitionAudit/oracle-v2/",
                gitRevision = gitRevision(),
                localCorpusDirs = resolvedBatchDirs().map { it.absolutePath },
                localCorpusFiles = resolvedBatchFilesProperty().map { it.absolutePath },
                filesDiscoveredInLocalCorpus = files.size,
                filesAnalyzedWithAndroid = androidResults.size,
                driveDiscoveryNote = DRIVE_DISCOVERY_NOTE,
                iosAdapterStatus = IOS_ADAPTER_STATUS,
                adminAdapterStatus = ADMIN_ADAPTER_STATUS
            ),
            platformSummaries = Platform.entries.map { platform ->
                platformSummary(platform, allResults.filter { it.platform == platform.name })
            },
            crossPlatform = crossPlatformSummary(files, allResults),
            manualReviewPriority = manualReviewPriority(androidResults),
            results = allResults
        )

        writeReports(report)
        val screen = renderScreenSummary(report)
        println(screen)

        assertTrue(
            "Drive batch audit report was not generated",
            report.results.isNotEmpty()
        )
    }

    private fun resolveBatchFiles(): List<File> {
        val explicitFiles = resolvedBatchFilesProperty()
        val directories = resolvedBatchDirs()
        val candidates = explicitFiles + directories.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile }.toList()
        }
        return candidates
            .filter { it.exists() && it.isFile && it.name != ".DS_Store" && it.looksLikeExcelFile() }
            .distinctBy { it.canonicalPath }
            .sortedBy { it.name.lowercase() }
    }

    private fun resolvedBatchFilesProperty(): List<File> {
        return System.getProperty("excelAudit.batchFiles")
            ?.split(',', File.pathSeparatorChar)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map(::File)
            .orEmpty()
    }

    private fun resolvedBatchDirs(): List<File> {
        val configured = System.getProperty("excelAudit.batchDirs")
            ?.split(',', File.pathSeparatorChar)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map(::File)
            .orEmpty()
        if (configured.isNotEmpty()) return configured

        val defaults = DEFAULT_BATCH_DIRS.map(::File)
        val combined = defaults.firstOrNull { it.name == "combined-corpus-by-name" && it.exists() && it.isDirectory }
        return combined?.let(::listOf)
            ?: defaults.filter { it.exists() && it.isDirectory }
    }

    private fun auditAndroidFile(file: File): BatchFilePlatformResult {
        val base = baseFileInfo(file, Platform.ANDROID)
        return runCatching {
            val analysis = readAndAnalyzeExcelDetailed(
                context = context,
                uri = Uri.fromFile(file),
                allowEmptyTabularResult = true
            )
            val scan = workbookScan(file, analysis.originalHeaders)
            val mappings = importantFields().map { field ->
                mappingFor(field, analysis)
            }
            val expected = loadExpected(file.name)
            val classification = if (expected != null) {
                classifyExpected(expected, analysis, scan)
            } else {
                classifyUnknownDriveFile(analysis, scan, mappings)
            }
            base.copy(
                verdict = classification.verdict.name,
                confidence = classification.confidence,
                sheet = scan.sheetName,
                sheetCount = scan.sheetCount,
                headerRow = scan.headerRowOneBased ?: analysis.trace.headerRows.lastOrNull()?.plus(1),
                firstDataRow = scan.firstDataRowOneBased
                    ?: analysis.trace.dataRowIdx.takeIf { it >= 0 }?.plus(1),
                rowCount = analysis.dataRows.size,
                headerMode = analysis.trace.headerMode,
                originalHeaders = analysis.originalHeaders,
                canonicalHeaders = analysis.header,
                headerSource = analysis.headerSource,
                mapping = mappings,
                essentialMissing = classification.essentialMissing,
                generatedEssentialColumns = classification.generatedEssentialColumns,
                suspiciousColumns = suspiciousColumns(analysis),
                sampleValues = sampleValues(mappings),
                reasons = classification.reasons,
                warnings = classification.warnings,
                rawHeaderHints = scan.rawHeaderHints,
                exception = null
            )
        }.getOrElse { throwable ->
            base.copy(
                verdict = exceptionVerdict(throwable).name,
                confidence = "high",
                reasons = listOf("Parser exception: ${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}"),
                exception = "${throwable::class.java.name}: ${throwable.message.orEmpty()}"
            )
        }
    }

    private fun classifyExpected(
        expected: ExpectedCase,
        analysis: ExcelAnalysisResult,
        scan: WorkbookScan
    ): Classification {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val actualHeaderRow = scan.headerRowOneBased ?: analysis.trace.headerRows.lastOrNull()?.plus(1)
        val actualFirstDataRow = scan.firstDataRowOneBased
            ?: analysis.trace.dataRowIdx.takeIf { it >= 0 }?.plus(1)

        if (actualHeaderRow != expected.expectedHeaderRow) {
            reasons += "HEADER_ROW_MISMATCH expected=${expected.expectedHeaderRow} actual=$actualHeaderRow"
        }
        if (actualFirstDataRow != expected.expectedFirstDataRow) {
            reasons += "FIRST_DATA_ROW_MISMATCH expected=${expected.expectedFirstDataRow} actual=$actualFirstDataRow"
        }
        expected.requiredFields.forEach { field ->
            if (analysis.header.indexOf(field) < 0) reasons += "FIELD_MISSING field=$field"
        }
        expected.fields.forEach { (field, fieldExpected) ->
            val actualIndex = analysis.header.indexOf(field)
            if (actualIndex < 0) {
                reasons += "FIELD_MISMATCH field=$field expected=${fieldExpected.colLetter} actual=missing"
                return@forEach
            }
            if (actualIndex != fieldExpected.colIndex) {
                reasons += "FIELD_MISMATCH field=$field expected=${fieldExpected.colLetter} actual=${columnLetter(actualIndex)}"
            }
            val actualOriginal = analysis.originalHeaders.getOrNull(actualIndex).orEmpty()
            if (actualOriginal != fieldExpected.originalHeader) {
                reasons += "HEADER_ORIGINAL_MISMATCH field=$field expected=${fieldExpected.originalHeader.forReason()} actual=${actualOriginal.forReason()}"
            }
            val actualSource = analysis.headerSource.getOrNull(actualIndex).orEmpty()
            if (actualSource == "generated") {
                reasons += "REQUIRED_GENERATED field=$field expected=${fieldExpected.colLetter}"
            }
            if (actualSource != fieldExpected.source) {
                reasons += "HEADER_SOURCE_MISMATCH field=$field expected=${fieldExpected.source} actual=$actualSource"
            }
        }

        val generated = generatedEssentials(analysis)
        val missing = missingEssentials(analysis)
        return Classification(
            verdict = if (reasons.isEmpty()) BatchVerdict.PASS_CONFIRMED else BatchVerdict.FAIL_CONFIRMED,
            confidence = "confirmed",
            essentialMissing = missing,
            generatedEssentialColumns = generated,
            reasons = reasons,
            warnings = warnings
        )
    }

    private fun classifyUnknownDriveFile(
        analysis: ExcelAnalysisResult,
        scan: WorkbookScan,
        mappings: List<FieldMapping>
    ): Classification {
        val failReasons = mutableListOf<String>()
        val warnReasons = mutableListOf<String>()
        val rawText = (scan.rawHeaderHints + analysis.originalHeaders + analysis.canonicalHeaders()).joinToString("\n")
        val generated = generatedEssentials(analysis)
        val missing = missingEssentials(analysis)
        val mappingByField = mappings.associateBy { it.field }

        if (analysis.dataRows.isEmpty()) {
            failReasons += "No data rows survived parser filtering"
        } else if (analysis.dataRows.size < 2) {
            warnReasons += "Very low data row count: ${analysis.dataRows.size}"
        }

        if ((scan.sheetCount ?: 0) > 1) {
            warnReasons += "Workbook has multiple sheets (${scan.sheetCount}); audit uses first selected sheet=${scan.sheetName}"
        }

        if (!analysis.trace.hasHeader || analysis.trace.headerMode.contains("generated")) {
            warnReasons += "Header mode is ${analysis.trace.headerMode}"
        }

        if (generated.any { it.field == "productName" || it.field == "purchasePrice" }) {
            failReasons += "Generated essential column(s): ${generated.joinToString { it.field }}"
        } else if (generated.any { it.field == "barcode" }) {
            if (rawText.containsAny(BARCODE_HINTS)) {
                failReasons += "barcode generated although raw workbook contains barcode-like header hints"
            } else {
                warnReasons += "barcode generated; no strong barcode header hint found"
            }
        }

        if (missing.isNotEmpty()) {
            failReasons += "Missing essential field(s): ${missing.joinToString()}"
        }

        if (rawText.containsAny(PRODUCT_HINTS) && isMissingOrGenerated(mappingByField["productName"])) {
            failReasons += "productName missing/generated although description/name header hints are present"
        }
        if (rawText.containsAny(PURCHASE_PRICE_HINTS) && isMissingOrGenerated(mappingByField["purchasePrice"])) {
            failReasons += "purchasePrice missing/generated although price/cost header hints are present"
        }
        if (rawText.containsAny(QUANTITY_HINTS) && isMissingOrGenerated(mappingByField["quantity"])) {
            failReasons += "quantity missing/generated although quantity header hints are present"
        }
        if (rawText.containsAny(DISCOUNT_HINTS) && isMissingOrGenerated(mappingByField["discount"])) {
            failReasons += "discount missing/generated although discount header hints are present"
        }
        if (rawText.containsAny(DISCOUNTED_PRICE_HINTS) && isMissingOrGenerated(mappingByField["discountedPrice"])) {
            failReasons += "discountedPrice missing/generated although discounted-price header hints are present"
        }
        if (rawText.containsAny(TOTAL_PRICE_HINTS) && isMissingOrGenerated(mappingByField["totalPrice"])) {
            failReasons += "totalPrice missing/generated although total/sum header hints are present"
        }

        if (mappingByField["barcode"]?.source != "generated") {
            validateBarcode(mappingByField["barcode"])?.let { failReasons += it }
        }
        validateTextField("productName", mappingByField["productName"])?.let { failReasons += it }
        listOf("purchasePrice", "quantity", "discountedPrice", "totalPrice").forEach { field ->
            validateNumericField(field, mappingByField[field])?.let { failReasons += it }
        }

        val criticalPatternFields = mappings.filter {
            it.field in CRITICAL_FIELDS && it.source == "pattern"
        }
        if (criticalPatternFields.isNotEmpty()) {
            warnReasons += "Critical field(s) resolved by pattern instead of alias: ${criticalPatternFields.joinToString { it.field }}"
        }

        columnCollisions(mappings).forEach { collision ->
            val fields = collision.value
            if ("totalPrice" in fields && ("purchasePrice" in fields || "discountedPrice" in fields)) {
                failReasons += "totalPrice shares column ${collision.key} with ${fields.joinToString()}"
            } else {
                warnReasons += "Multiple fields share column ${collision.key}: ${fields.joinToString()}"
            }
        }

        val suspicious = suspiciousColumns(analysis)
        if (suspicious.size >= 4) {
            warnReasons += "Many unknown non-empty textual columns: ${suspicious.take(8).joinToString { it.colLetter + '=' + it.originalHeader.forReason() }}"
        }

        val verdict = when {
            failReasons.isNotEmpty() -> BatchVerdict.FAIL_SUSPECT
            warnReasons.isNotEmpty() -> BatchVerdict.WARN_REVIEW
            else -> BatchVerdict.PASS_LIKELY
        }
        val confidence = when (verdict) {
            BatchVerdict.PASS_LIKELY -> "medium"
            BatchVerdict.WARN_REVIEW -> "medium"
            BatchVerdict.FAIL_SUSPECT -> "high"
            else -> "low"
        }
        return Classification(
            verdict = verdict,
            confidence = confidence,
            essentialMissing = missing,
            generatedEssentialColumns = generated,
            reasons = failReasons,
            warnings = warnReasons
        )
    }

    private fun notRunResult(file: File, platform: Platform): BatchFilePlatformResult {
        val status = when (platform) {
            Platform.IOS -> IOS_ADAPTER_STATUS
            Platform.ADMIN_WEB -> ADMIN_ADAPTER_STATUS
            Platform.ANDROID -> "Android parser was not run"
        }
        return baseFileInfo(file, platform).copy(
            verdict = BatchVerdict.NOT_RUN.name,
            confidence = "none",
            reasons = listOf(status)
        )
    }

    private fun baseFileInfo(file: File, platform: Platform): BatchFilePlatformResult {
        return BatchFilePlatformResult(
            filename = file.name,
            filePath = file.absolutePath,
            sha256 = file.sha256(),
            size = file.length(),
            platform = platform.name,
            verdict = BatchVerdict.NOT_RUN.name,
            confidence = "none",
            sheet = null,
            sheetCount = null,
            headerRow = null,
            firstDataRow = null,
            rowCount = null,
            headerMode = null,
            originalHeaders = emptyList(),
            canonicalHeaders = emptyList(),
            headerSource = emptyList(),
            mapping = emptyList(),
            essentialMissing = emptyList(),
            generatedEssentialColumns = emptyList(),
            suspiciousColumns = emptyList(),
            sampleValues = emptyMap(),
            rawHeaderHints = emptyList(),
            reasons = emptyList(),
            warnings = emptyList(),
            exception = null
        )
    }

    private fun mappingFor(field: String, analysis: ExcelAnalysisResult): FieldMapping {
        val traceByField = analysis.trace.fieldDecisions.associateBy { it.field }
        val colIndex = analysis.header.indexOf(field).takeIf { it >= 0 }
        val samples = colIndex?.let { column ->
            analysis.dataRows.take(VALIDATION_SAMPLE_ROWS).map { row -> row.getOrNull(column).orEmpty() }
        }.orEmpty()
        return FieldMapping(
            field = field,
            colIndex = colIndex,
            colLetter = colIndex?.let(::columnLetter),
            originalHeader = colIndex?.let { analysis.originalHeaders.getOrNull(it).orEmpty() },
            canonicalHeader = colIndex?.let { analysis.header.getOrNull(it).orEmpty() },
            source = colIndex?.let { analysis.headerSource.getOrNull(it).orEmpty() },
            confidence = traceByField[field]?.confidence,
            reason = traceByField[field]?.reason,
            sampleValues = samples.take(SAMPLE_ROWS)
        )
    }

    private fun sampleValues(mappings: List<FieldMapping>): Map<String, List<String>> {
        return mappings
            .filter { it.field in CRITICAL_FIELDS }
            .associate { it.field to it.sampleValues }
    }

    private fun suspiciousColumns(analysis: ExcelAnalysisResult): List<SuspiciousColumn> {
        return analysis.header.mapIndexedNotNull { index, canonical ->
            val source = analysis.headerSource.getOrNull(index).orEmpty()
            val original = analysis.originalHeaders.getOrNull(index).orEmpty()
            val hasSamples = analysis.dataRows.any { row -> row.getOrNull(index)?.isNotBlank() == true }
            val noise = original.isBlank() || original.containsAny(IMAGE_OR_EMPTY_HINTS)
            if (source == "unknown" && !noise && hasSamples) {
                SuspiciousColumn(
                    colIndex = index,
                    colLetter = columnLetter(index),
                    originalHeader = original,
                    canonicalHeader = canonical,
                    sampleValues = analysis.dataRows.take(SAMPLE_ROWS).map { row -> row.getOrNull(index).orEmpty() }
                )
            } else {
                null
            }
        }
    }

    private fun generatedEssentials(analysis: ExcelAnalysisResult): List<GeneratedColumn> {
        return ESSENTIAL_FIELDS.mapNotNull { field ->
            val index = analysis.header.indexOf(field)
            val source = index.takeIf { it >= 0 }?.let { analysis.headerSource.getOrNull(it).orEmpty() }
            if (index >= 0 && source == "generated") {
                GeneratedColumn(
                    field = field,
                    colIndex = index,
                    colLetter = columnLetter(index)
                )
            } else {
                null
            }
        }
    }

    private fun missingEssentials(analysis: ExcelAnalysisResult): List<String> {
        return ESSENTIAL_FIELDS.filter { field -> analysis.header.indexOf(field) < 0 }
    }

    private fun isMissingOrGenerated(mapping: FieldMapping?): Boolean {
        return mapping?.colIndex == null || mapping.source == "generated"
    }

    private fun validateBarcode(mapping: FieldMapping?): String? {
        val values = mapping?.sampleValues?.filter { it.isNotBlank() }.orEmpty()
        if (values.size < 2) return null
        val ratio = values.count { it.isBarcodeLike() }.toDouble() / values.size.toDouble()
        return if (ratio < 0.50) {
            "barcode samples do not look like barcodes: ${values.take(3).joinToString(" / ").forReason()}"
        } else {
            null
        }
    }

    private fun validateTextField(field: String, mapping: FieldMapping?): String? {
        val values = mapping?.sampleValues?.filter { it.isNotBlank() }.orEmpty()
        if (values.size < 2 || mapping?.source == "generated") return null
        val ratio = values.count { it.hasLettersOrCjk() && parseAuditNumber(it) == null }
            .toDouble() / values.size.toDouble()
        return if (ratio < 0.50) {
            "$field samples do not look like product text: ${values.take(3).joinToString(" / ").forReason()}"
        } else {
            null
        }
    }

    private fun validateNumericField(field: String, mapping: FieldMapping?): String? {
        val values = mapping?.sampleValues?.filter { it.isNotBlank() }.orEmpty()
        if (values.size < 2 || mapping?.source == "generated") return null
        val ratio = values.count { parseAuditNumber(it) != null }.toDouble() / values.size.toDouble()
        return if (ratio < 0.50) {
            "$field samples are mostly non-numeric: ${values.take(3).joinToString(" / ").forReason()}"
        } else {
            null
        }
    }

    private fun columnCollisions(mappings: List<FieldMapping>): Map<String, List<String>> {
        return mappings
            .filter { it.colLetter != null && it.source != "generated" && it.field in COLLISION_FIELDS }
            .groupBy { it.colLetter.orEmpty() }
            .mapValues { entry -> entry.value.map { it.field } }
            .filterValues { it.size > 1 }
    }

    private fun workbookScan(file: File, originalHeaders: List<String>): WorkbookScan {
        return runCatching {
            file.inputStream().use { input ->
                WorkbookFactory.create(input).use { workbook ->
                    val sheet = workbook.getSheetAt(0)
                    val formatter = DataFormatter()
                    val rawHints = firstRows(sheet, formatter)
                    val headerRow = physicalHeaderRow(sheet, formatter, originalHeaders)
                    WorkbookScan(
                        sheetName = sheet.sheetName,
                        sheetCount = workbook.numberOfSheets,
                        headerRowOneBased = headerRow,
                        firstDataRowOneBased = headerRow?.let { nextNonBlankRow(sheet, it, formatter) },
                        rawHeaderHints = rawHints
                    )
                }
            }
        }.getOrElse {
            WorkbookScan(
                sheetName = "sheet-0",
                sheetCount = null,
                headerRowOneBased = null,
                firstDataRowOneBased = null,
                rawHeaderHints = emptyList()
            )
        }
    }

    private fun firstRows(sheet: Sheet, formatter: DataFormatter): List<String> {
        return (0..minOf(sheet.lastRowNum, RAW_HINT_ROWS - 1)).mapNotNull { rowIndex ->
            val row = sheet.getRow(rowIndex) ?: return@mapNotNull null
            val last = row.lastCellNum.toInt().coerceAtLeast(0)
            val cells = (0 until minOf(last, RAW_HINT_COLUMNS)).map { column ->
                formatter.formatCellValue(row.getCell(column)).trim()
            }.filter { it.isNotBlank() }
            cells.joinToString(" | ").takeIf { it.isNotBlank() }
        }
    }

    private fun physicalHeaderRow(
        sheet: Sheet,
        formatter: DataFormatter,
        originalHeaders: List<String>
    ): Int? {
        val expected = originalHeaders.dropLastWhile { it.isBlank() }
        if (expected.isEmpty()) return null
        for (rowIndex in 0..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            val last = maxOf(row.lastCellNum.toInt().coerceAtLeast(0), expected.size)
            val values = (0 until last).map { column ->
                formatter.formatCellValue(row.getCell(column)).trim()
            }.dropLastWhile { it.isBlank() }
            if (values == expected) return rowIndex + 1
        }
        return null
    }

    private fun nextNonBlankRow(
        sheet: Sheet,
        headerRowOneBased: Int,
        formatter: DataFormatter
    ): Int? {
        for (rowIndex in headerRowOneBased..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            val last = row.lastCellNum.toInt().coerceAtLeast(0)
            val hasValue = (0 until last).any { column ->
                formatter.formatCellValue(row.getCell(column)).trim().isNotBlank()
            }
            if (hasValue) return rowIndex + 1
        }
        return null
    }

    private fun loadExpected(fileName: String): ExpectedCase? {
        val expectedName = fileName.substringBeforeLast('.') + ".expected.json"
        val stream = javaClass.classLoader?.getResourceAsStream("excel/$expectedName") ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            gson.fromJson(reader, ExpectedCase::class.java)
        }
    }

    private fun writeReports(report: BatchAuditReport) {
        val reportDir = File(
            System.getProperty("excelAudit.reportDir")
                ?: "build/reports/excelRecognitionAudit"
        )
        reportDir.mkdirs()
        File(reportDir, "drive-batch-audit.json").writeText(gson.toJson(report), Charsets.UTF_8)
        File(reportDir, "drive-batch-audit.csv").writeText(renderCsv(report), Charsets.UTF_8)
        File(reportDir, "drive-batch-audit.md").writeText(renderMarkdown(report), Charsets.UTF_8)
    }

    private fun renderScreenSummary(report: BatchAuditReport): String = buildString {
        appendLine("BATCH EXCEL RECOGNITION AUDIT — DRIVE SUPPLIER CORPUS")
        appendLine()
        appendLine("Corpus:")
        appendLine("- Drive folder: ${report.metadata.driveFolderUrl}")
        appendLine("- Files discovered in local materialized corpus: ${report.metadata.filesDiscoveredInLocalCorpus}")
        appendLine("- Files analyzed with Android parser: ${report.metadata.filesAnalyzedWithAndroid}")
        appendLine("- Timestamp: ${report.metadata.timestamp}")
        appendLine("- Harness: ${report.metadata.harness}")
        appendLine("- Commit: ${report.metadata.gitRevision}")
        appendLine("- Drive access note: ${report.metadata.driveDiscoveryNote}")
        appendLine()
        report.platformSummaries.forEach { summary ->
            appendLine("${summary.platform}:")
            BatchVerdict.entries.forEach { verdict ->
                appendLine("- ${verdict.name}: ${summary.counts[verdict.name] ?: 0}")
            }
            val problematic = report.results
                .filter { it.platform == summary.platform && it.verdict in ISSUE_VERDICTS }
            val notRunCount = report.results.count {
                it.platform == summary.platform && it.verdict == BatchVerdict.NOT_RUN.name
            }
            appendLine("- File problematici:")
            if (problematic.isEmpty()) {
                appendLine("  (none)")
            } else {
                problematic.forEachIndexed { index, result ->
                    appendLine("  ${index + 1}. ${result.filename} — ${result.verdict} — ${result.shortReason()}")
                }
            }
            if (notRunCount > 0) {
                appendLine("- NOT_RUN files: $notRunCount (see JSON/CSV for exact rows)")
            }
            appendLine()
        }
        appendLine("Cross-platform:")
        appendLine("- File OK su tutte le piattaforme: ${report.crossPlatform.filesOkOnAllPlatforms}")
        appendLine("- File problematici su tutte le piattaforme: ${report.crossPlatform.filesProblematicOnAllPlatforms}")
        appendLine("- File problematici solo Android: ${report.crossPlatform.filesProblematicOnlyAndroid}")
        appendLine("- File problematici solo iOS: ${report.crossPlatform.filesProblematicOnlyIos}")
        appendLine("- File problematici solo Admin: ${report.crossPlatform.filesProblematicOnlyAdmin}")
        appendLine("- File con risultati divergenti: ${report.crossPlatform.filesWithDivergentResults}")
        appendLine("- Note: ${report.crossPlatform.note}")
        appendLine()
        appendLine("Manual review priority:")
        if (report.manualReviewPriority.isEmpty()) {
            appendLine("1. (none)")
        } else {
            report.manualReviewPriority.take(25).forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.filename} — ${item.verdict} — ${item.reason}")
            }
        }
    }

    private fun renderMarkdown(report: BatchAuditReport): String = buildString {
        appendLine("# Drive Batch Excel Recognition Audit")
        appendLine()
        appendLine("> **${report.metadata.v1InvalidationStatus} / SUPERSEDED_BY_ORACLE_V2**")
        appendLine(">")
        appendLine("> ${report.metadata.v1InvalidationNote}")
        appendLine("> Use `${report.metadata.supersededBy}` before any manual-testing workflow.")
        appendLine()
        appendLine("## Corpus")
        appendLine()
        appendLine("- Drive folder: ${report.metadata.driveFolderUrl}")
        appendLine("- Files discovered in local materialized corpus: ${report.metadata.filesDiscoveredInLocalCorpus}")
        appendLine("- Files analyzed with Android parser: ${report.metadata.filesAnalyzedWithAndroid}")
        appendLine("- Timestamp: ${report.metadata.timestamp}")
        appendLine("- Harness: ${report.metadata.harness}")
        appendLine("- Commit: ${report.metadata.gitRevision}")
        appendLine("- Local corpus dirs: ${report.metadata.localCorpusDirs.joinToString().ifBlank { "(none)" }}")
        appendLine("- Local corpus files: ${report.metadata.localCorpusFiles.joinToString().ifBlank { "(none)" }}")
        appendLine("- Drive access note: ${report.metadata.driveDiscoveryNote}")
        appendLine()
        appendLine("## Platform Summary")
        appendLine()
        appendLine("| Platform | PASS_CONFIRMED | PASS_LIKELY | WARN_REVIEW | FAIL_SUSPECT | FAIL_CONFIRMED | UNSUPPORTED | ERROR | NOT_RUN |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        report.platformSummaries.forEach { summary ->
            appendLine(
                "| ${summary.platform} | " +
                    "${summary.counts[BatchVerdict.PASS_CONFIRMED.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.PASS_LIKELY.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.WARN_REVIEW.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.FAIL_SUSPECT.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.FAIL_CONFIRMED.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.UNSUPPORTED.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.ERROR.name] ?: 0} | " +
                    "${summary.counts[BatchVerdict.NOT_RUN.name] ?: 0} |"
            )
        }
        appendLine()
        appendLine("## Adapter Status")
        appendLine()
        appendLine("- iOS: ${report.metadata.iosAdapterStatus}")
        appendLine("- Admin Web: ${report.metadata.adminAdapterStatus}")
        appendLine()
        appendLine("## Cross-Platform")
        appendLine()
        appendLine("- File OK su tutte le piattaforme: ${report.crossPlatform.filesOkOnAllPlatforms}")
        appendLine("- File problematici su tutte le piattaforme: ${report.crossPlatform.filesProblematicOnAllPlatforms}")
        appendLine("- File problematici solo Android: ${report.crossPlatform.filesProblematicOnlyAndroid}")
        appendLine("- File problematici solo iOS: ${report.crossPlatform.filesProblematicOnlyIos}")
        appendLine("- File problematici solo Admin: ${report.crossPlatform.filesProblematicOnlyAdmin}")
        appendLine("- File con risultati divergenti: ${report.crossPlatform.filesWithDivergentResults}")
        appendLine("- Note: ${report.crossPlatform.note}")
        appendLine()
        appendLine("## Manual Review Priority")
        appendLine()
        if (report.manualReviewPriority.isEmpty()) {
            appendLine("(none)")
        } else {
            report.manualReviewPriority.forEachIndexed { index, item ->
                appendLine("${index + 1}. `${item.filename}` — `${item.verdict}` — ${item.reason}")
            }
        }
        appendLine()
        appendLine("## Android Problem Files")
        appendLine()
        val androidProblems = report.results
            .filter { it.platform == Platform.ANDROID.name && it.verdict in PROBLEM_VERDICTS }
        if (androidProblems.isEmpty()) {
            appendLine("(none)")
        } else {
            appendLine("| File | Verdict | Header row | First data row | Rows | Reasons | Warnings |")
            appendLine("|---|---|---:|---:|---:|---|---|")
            androidProblems.forEach { result ->
                appendLine(
                    "| ${result.filename.forMarkdownCell()} | ${result.verdict} | " +
                        "${result.headerRow ?: "-"} | ${result.firstDataRow ?: "-"} | " +
                        "${result.rowCount ?: "-"} | ${result.reasons.joinToString("; ").forMarkdownCell()} | " +
                        "${result.warnings.joinToString("; ").forMarkdownCell()} |"
                )
            }
        }
        appendLine()
        appendLine("## File Details")
        appendLine()
        report.results
            .filter { it.platform == Platform.ANDROID.name }
            .forEach { result ->
                appendLine("### ${result.filename} — ${result.verdict}")
                appendLine()
                appendLine("- sha256: `${result.sha256}`")
                appendLine("- size: ${result.size}")
                appendLine("- platform: ${result.platform}")
                appendLine("- confidence: ${result.confidence}")
                appendLine("- sheet: ${result.sheet ?: "-"}")
                appendLine("- sheet count: ${result.sheetCount ?: "-"}")
                appendLine("- header row: ${result.headerRow ?: "-"}")
                appendLine("- first data row: ${result.firstDataRow ?: "-"}")
                appendLine("- row count: ${result.rowCount ?: "-"}")
                appendLine("- header mode: ${result.headerMode ?: "-"}")
                appendLine("- original headers: ${result.originalHeaders.joinToString { it.forMarkdownInline() }}")
                appendLine("- canonical headers: ${result.canonicalHeaders.joinToString()}")
                appendLine("- header source: ${result.headerSource.joinToString()}")
                appendLine("- essential missing: ${result.essentialMissing.joinToString().ifBlank { "(none)" }}")
                appendLine("- generated essential columns: ${result.generatedEssentialColumns.joinToString { it.field + ':' + it.colLetter }.ifBlank { "(none)" }}")
                appendLine()
                appendLine("| Field | Column | Original header | Canonical | Source | Confidence | Reason | Samples |")
                appendLine("|---|---:|---|---|---|---|---|---|")
                result.mapping.forEach { mapping ->
                    appendLine(
                        "| ${mapping.field} | ${mapping.colLetter ?: "-"} | " +
                            "${mapping.originalHeader.orEmpty().forMarkdownCell()} | " +
                            "${mapping.canonicalHeader.orEmpty().forMarkdownCell()} | " +
                            "${mapping.source ?: "-"} | ${mapping.confidence ?: "-"} | " +
                            "${mapping.reason.orEmpty().forMarkdownCell()} | " +
                            "${mapping.sampleValues.joinToString(" / ").forMarkdownCell()} |"
                    )
                }
                if (result.suspiciousColumns.isNotEmpty()) {
                    appendLine()
                    appendLine("Suspicious columns:")
                    result.suspiciousColumns.forEach { column ->
                        appendLine(
                            "- ${column.colLetter} `${column.originalHeader.forReason()}` " +
                                "samples=${column.sampleValues.joinToString(" / ").forMarkdownInline()}"
                        )
                    }
                }
                if (result.reasons.isNotEmpty()) {
                    appendLine()
                    appendLine("Reasons:")
                    result.reasons.forEach { appendLine("- $it") }
                }
                if (result.warnings.isNotEmpty()) {
                    appendLine()
                    appendLine("Warnings:")
                    result.warnings.forEach { appendLine("- $it") }
                }
                if (result.exception != null) {
                    appendLine()
                    appendLine("Exception: `${result.exception}`")
                }
                appendLine()
            }
    }

    private fun renderCsv(report: BatchAuditReport): String = buildString {
        appendLine(
            listOf(
                "filename",
                "sha256",
                "size",
                "platform",
                "verdict",
                "confidence",
                "sheet",
                "sheetCount",
                "headerRow",
                "firstDataRow",
                "rowCount",
                "headerMode",
                "originalHeaders",
                "canonicalHeaders",
                "headerSource",
                "mapping",
                "essentialMissing",
                "generatedEssentialColumns",
                "suspiciousColumns",
                "sampleValues",
                "reasons",
                "warnings",
                "exception",
                "v1InvalidationStatus",
                "v1InvalidationNote"
            ).joinToString(",")
        )
        report.results.forEach { result ->
            appendLine(
                listOf(
                    result.filename,
                    result.sha256,
                    result.size.toString(),
                    result.platform,
                    result.verdict,
                    result.confidence,
                    result.sheet.orEmpty(),
                    result.sheetCount?.toString().orEmpty(),
                    result.headerRow?.toString().orEmpty(),
                    result.firstDataRow?.toString().orEmpty(),
                    result.rowCount?.toString().orEmpty(),
                    result.headerMode.orEmpty(),
                    result.originalHeaders.joinToString(" | "),
                    result.canonicalHeaders.joinToString(" | "),
                    result.headerSource.joinToString(" | "),
                    result.mapping.joinToString(" | ") { mapping ->
                        "${mapping.field}:${mapping.colLetter ?: "-"}:${mapping.originalHeader.orEmpty().forReason()}:${mapping.source ?: "-"}"
                    },
                    result.essentialMissing.joinToString(" | "),
                    result.generatedEssentialColumns.joinToString(" | ") { "${it.field}:${it.colLetter}" },
                    result.suspiciousColumns.joinToString(" | ") { "${it.colLetter}:${it.originalHeader.forReason()}" },
                    result.sampleValues.entries.joinToString(" | ") { "${it.key}=${it.value.joinToString("/")}" },
                    result.reasons.joinToString(" | "),
                    result.warnings.joinToString(" | "),
                    result.exception.orEmpty(),
                    report.metadata.v1InvalidationStatus,
                    report.metadata.v1InvalidationNote
                ).joinToString(",") { it.csvEscape() }
            )
        }
    }

    private fun platformSummary(
        platform: Platform,
        results: List<BatchFilePlatformResult>
    ): PlatformSummary {
        val counts = BatchVerdict.entries.associate { verdict ->
            verdict.name to results.count { it.verdict == verdict.name }
        }
        return PlatformSummary(platform = platform.name, counts = counts)
    }

    private fun crossPlatformSummary(
        files: List<File>,
        results: List<BatchFilePlatformResult>
    ): CrossPlatformSummary {
        val byFile = results.groupBy { it.filename }
        val allOk = byFile.values.count { platformResults ->
            Platform.entries.all { platform ->
                platformResults.any { it.platform == platform.name && it.verdict in OK_VERDICTS }
            }
        }
        val allProblematic = byFile.values.count { platformResults ->
            Platform.entries.all { platform ->
                platformResults.any { it.platform == platform.name && it.verdict in ISSUE_VERDICTS }
            }
        }
        val divergent = byFile.values.count { platformResults ->
            platformResults
                .filter { it.verdict != BatchVerdict.NOT_RUN.name }
                .map { it.verdict }
                .distinct()
                .size > 1
        }
        return CrossPlatformSummary(
            filesOkOnAllPlatforms = allOk,
            filesProblematicOnAllPlatforms = allProblematic,
            filesProblematicOnlyAndroid = 0,
            filesProblematicOnlyIos = 0,
            filesProblematicOnlyAdmin = 0,
            filesWithDivergentResults = divergent,
            note = "Only Android has a full batch parser result in this run. iOS/Admin are NOT_RUN/PARTIAL, so platform-only problem counts are not asserted. Local materialized files=${files.size}."
        )
    }

    private fun manualReviewPriority(androidResults: List<BatchFilePlatformResult>): List<ManualReviewItem> {
        return androidResults
            .filter { it.verdict in PROBLEM_VERDICTS }
            .sortedWith(
                compareBy<BatchFilePlatformResult> {
                    when (it.verdict) {
                        BatchVerdict.FAIL_CONFIRMED.name -> 0
                        BatchVerdict.FAIL_SUSPECT.name -> 1
                        BatchVerdict.ERROR.name -> 2
                        BatchVerdict.UNSUPPORTED.name -> 3
                        BatchVerdict.WARN_REVIEW.name -> 4
                        else -> 5
                    }
                }.thenBy { it.filename.lowercase() }
            )
            .map {
                ManualReviewItem(
                    filename = it.filename,
                    platform = it.platform,
                    verdict = it.verdict,
                    reason = it.shortReason()
                )
            }
    }

    private fun exceptionVerdict(throwable: Throwable): BatchVerdict {
        return when (throwable) {
            is EncryptedDocumentException,
            is InvalidFormatException -> BatchVerdict.UNSUPPORTED
            else -> {
                val name = throwable::class.java.name.lowercase()
                val message = throwable.message.orEmpty().lowercase()
                if ("unsupported" in name || "encrypted" in message || "password" in message) {
                    BatchVerdict.UNSUPPORTED
                } else {
                    BatchVerdict.ERROR
                }
            }
        }
    }

    private fun importantFields(): List<String> = listOf(
        "rowNumber",
        "itemNumber",
        "barcode",
        "productName",
        "secondProductName",
        "quantity",
        "purchasePrice",
        "discount",
        "discountedPrice",
        "totalPrice",
        "supplier",
        "category",
        "retailPrice"
    )

    private fun ExcelAnalysisResult.canonicalHeaders(): List<String> = header

    private fun BatchFilePlatformResult.shortReason(): String {
        return (reasons + warnings).firstOrNull()?.take(180)
            ?: exception?.take(180)
            ?: "no reason captured"
    }

    private fun File.looksLikeExcelFile(): Boolean {
        val extensionLooksRight = name.lowercase().let {
            it.endsWith(".xls") || it.endsWith(".xlsx") || it.endsWith(".xlsm")
        }
        return runCatching {
            inputStream().use { input ->
                val bytes = ByteArray(4096)
                val count = input.read(bytes)
                val prefix = bytes.copyOf(count.coerceAtLeast(0))
                val text = prefix.toString(Charsets.UTF_8).lowercase()
                extensionLooksRight ||
                    prefix.hasPrefix(byteArrayOf(0x50.toByte(), 0x4B.toByte())) ||
                    prefix.hasPrefix(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) ||
                    "<html" in text ||
                    "<table" in text
            }
        }.getOrDefault(extensionLooksRight)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.containsAny(tokens: List<String>): Boolean {
        val normalized = lowercase()
        return tokens.any { token -> token.lowercase() in normalized }
    }

    private fun String.isBarcodeLike(): Boolean {
        val digits = filter { it.isDigit() }
        val compact = replace(Regex("[\\s.,-]"), "")
        return digits.length in 8..14 && digits.length >= compact.length - 1
    }

    private fun String.hasLettersOrCjk(): Boolean {
        return any { it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }

    private fun parseAuditNumber(value: String): Double? {
        val cleaned = value.trim()
            .replace("$", "")
            .replace("€", "")
            .replace("￥", "")
            .replace("%", "")
            .replace(" ", "")
        if (cleaned.isBlank()) return null
        val decimalNormalized = when {
            ',' in cleaned && '.' in cleaned -> cleaned.replace(".", "").replace(',', '.')
            ',' in cleaned -> cleaned.replace(',', '.')
            else -> cleaned
        }
        return decimalNormalized.toDoubleOrNull()
    }

    private fun columnLetter(index: Int): String {
        var current = index + 1
        val result = StringBuilder()
        while (current > 0) {
            val remainder = (current - 1) % 26
            result.insert(0, ('A'.code + remainder).toChar())
            current = (current - 1) / 26
        }
        return result.toString()
    }

    private fun gitRevision(): String {
        return runCatching {
            val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { "unknown" }
        }.getOrDefault("unknown")
    }

    private fun String.forReason(): String = replace("\r\n", "\n").replace("\n", "\\n")

    private fun String.forMarkdownInline(): String = forReason().ifBlank { "(blank)" }

    private fun String.forMarkdownCell(): String = forMarkdownInline().replace("|", "\\|")

    private fun String.csvEscape(): String {
        return "\"" + replace("\"", "\"\"") + "\""
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private data class Classification(
        val verdict: BatchVerdict,
        val confidence: String,
        val essentialMissing: List<String>,
        val generatedEssentialColumns: List<GeneratedColumn>,
        val reasons: List<String>,
        val warnings: List<String>
    )

    private data class WorkbookScan(
        val sheetName: String?,
        val sheetCount: Int?,
        val headerRowOneBased: Int?,
        val firstDataRowOneBased: Int?,
        val rawHeaderHints: List<String>
    )

    private data class ExpectedCase(
        val caseName: String,
        val fileName: String,
        val sheetIndex: Int,
        val expectedHeaderRow: Int,
        val expectedFirstDataRow: Int,
        val requiredFields: List<String>,
        val fields: Map<String, ExpectedField>,
        val ignoredColumns: List<ExpectedIgnoredColumn>
    )

    private data class ExpectedField(
        val colIndex: Int,
        val colLetter: String,
        val originalHeader: String,
        val source: String
    )

    private data class ExpectedIgnoredColumn(
        val colIndex: Int,
        val colLetter: String,
        val originalHeader: String
    )

    private data class BatchAuditReport(
        val metadata: BatchAuditMetadata,
        val platformSummaries: List<PlatformSummary>,
        val crossPlatform: CrossPlatformSummary,
        val manualReviewPriority: List<ManualReviewItem>,
        val results: List<BatchFilePlatformResult>
    )

    private data class BatchAuditMetadata(
        val driveFolderUrl: String,
        val timestamp: String,
        val harness: String,
        val v1InvalidationStatus: String,
        val v1InvalidationNote: String,
        val supersededBy: String,
        val gitRevision: String,
        val localCorpusDirs: List<String>,
        val localCorpusFiles: List<String>,
        val filesDiscoveredInLocalCorpus: Int,
        val filesAnalyzedWithAndroid: Int,
        val driveDiscoveryNote: String,
        val iosAdapterStatus: String,
        val adminAdapterStatus: String
    )

    private data class PlatformSummary(
        val platform: String,
        val counts: Map<String, Int>
    )

    private data class CrossPlatformSummary(
        val filesOkOnAllPlatforms: Int,
        val filesProblematicOnAllPlatforms: Int,
        val filesProblematicOnlyAndroid: Int,
        val filesProblematicOnlyIos: Int,
        val filesProblematicOnlyAdmin: Int,
        val filesWithDivergentResults: Int,
        val note: String
    )

    private data class ManualReviewItem(
        val filename: String,
        val platform: String,
        val verdict: String,
        val reason: String
    )

    private data class BatchFilePlatformResult(
        val filename: String,
        val filePath: String,
        val sha256: String,
        val size: Long,
        val platform: String,
        val verdict: String,
        val confidence: String,
        val sheet: String?,
        val sheetCount: Int?,
        val headerRow: Int?,
        val firstDataRow: Int?,
        val rowCount: Int?,
        val headerMode: String?,
        val originalHeaders: List<String>,
        val canonicalHeaders: List<String>,
        val headerSource: List<String>,
        val mapping: List<FieldMapping>,
        val essentialMissing: List<String>,
        val generatedEssentialColumns: List<GeneratedColumn>,
        val suspiciousColumns: List<SuspiciousColumn>,
        val sampleValues: Map<String, List<String>>,
        val rawHeaderHints: List<String>,
        val reasons: List<String>,
        val warnings: List<String>,
        val exception: String?
    )

    private data class FieldMapping(
        val field: String,
        val colIndex: Int?,
        val colLetter: String?,
        val originalHeader: String?,
        val canonicalHeader: String?,
        val source: String?,
        val confidence: String?,
        val reason: String?,
        val sampleValues: List<String>
    )

    private data class GeneratedColumn(
        val field: String,
        val colIndex: Int,
        val colLetter: String
    )

    private data class SuspiciousColumn(
        val colIndex: Int,
        val colLetter: String,
        val originalHeader: String,
        val canonicalHeader: String,
        val sampleValues: List<String>
    )

    private enum class Platform {
        ANDROID,
        IOS,
        ADMIN_WEB
    }

    private enum class BatchVerdict {
        PASS_CONFIRMED,
        FAIL_CONFIRMED,
        PASS_LIKELY,
        WARN_REVIEW,
        FAIL_SUSPECT,
        UNSUPPORTED,
        ERROR,
        NOT_RUN
    }

    private companion object {
        const val DRIVE_FOLDER_URL = "https://drive.google.com/drive/folders/1aRsUPNPygXEa5BhHe3d_YMuqvwrIqLE8"
        const val DRIVE_DISCOVERY_NOTE =
            "Google Drive connector listed more than gdown's public 50-file page limit. " +
                "Raw Android audit used the local materialized corpus assembled from gdown's 50 files, " +
                "the local Desktop/Cartella Excel folder, and Desktop golden/Pinmark files. " +
                "Files visible in Drive but not materialized locally are not claimed PASS/FAIL in this report."
        const val V1_INVALIDATION_STATUS = "INVALID_V1_SELF_REFERENTIAL_AUDIT"
        const val V1_INVALIDATION_NOTE =
            "Do not use this v1 report or /Users/minxiang/Desktop/File testing as manual-testing truth; " +
                "it is superseded by the independent Oracle v2/v3 harness."
        const val IOS_ADAPTER_STATUS =
            "PARTIAL/NOT_RUN: iOS parser found in ExcelSessionViewModel.swift and XCTest smoke passed, " +
                "but no batch adapter/report test exists yet; no iOS parser/alias changes were made."
        const val ADMIN_ADAPTER_STATUS =
            "PARTIAL/NOT_RUN: Admin parser/header detector found and a read-only ephemeral header-only probe read 50 files " +
                "(48 detected, no detection for 20250820_Guanxing.xlsx and 2026.5.21-Mana.xlsx), " +
                "but full Admin preview mapping requires a dedicated read-only test adapter; no Admin parser/alias changes were made."
        val DEFAULT_BATCH_DIRS = listOf(
            "/tmp/excel-supplier-audit/combined-corpus-by-name",
            "/tmp/excel-supplier-audit/drive-corpus"
        )
        val ESSENTIAL_FIELDS = listOf("barcode", "productName", "purchasePrice")
        val CRITICAL_FIELDS = listOf(
            "barcode",
            "productName",
            "quantity",
            "purchasePrice",
            "discount",
            "discountedPrice",
            "totalPrice"
        )
        val COLLISION_FIELDS = CRITICAL_FIELDS + listOf("itemNumber", "secondProductName", "retailPrice")
        val OK_VERDICTS = setOf(BatchVerdict.PASS_CONFIRMED.name, BatchVerdict.PASS_LIKELY.name)
        val ISSUE_VERDICTS = setOf(
            BatchVerdict.FAIL_CONFIRMED.name,
            BatchVerdict.FAIL_SUSPECT.name,
            BatchVerdict.WARN_REVIEW.name,
            BatchVerdict.UNSUPPORTED.name,
            BatchVerdict.ERROR.name
        )
        val PROBLEM_VERDICTS = ISSUE_VERDICTS + setOf(
            BatchVerdict.NOT_RUN.name
        )
        val BARCODE_HINTS = listOf("barcode", "bar code", "ean", "upc", "barra", "barras", "条码", "條碼")
        val PRODUCT_HINTS = listOf("descripcion", "descripción", "description", "producto", "product", "articulo", "artículo", "nombre", "品名", "名称", "商品")
        val PURCHASE_PRICE_HINTS = listOf("purchase", "precio", "price", "pre", "costo", "cost", "批发价", "进价", "單價", "单价", "售价")
        val QUANTITY_HINTS = listOf("cantidad", "cant", "qty", "quantity", "数量", "數量")
        val DISCOUNT_HINTS = listOf("d.%", "descuento", "discount", "折扣")
        val DISCOUNTED_PRICE_HINTS = listOf("p.desc", "pdesc", "discounted", "折后价", "折後價", "折后", "折後")
        val TOTAL_PRICE_HINTS = listOf("sum", "total", "importe", "monto", "合计", "合計", "总价", "總價", "金额", "金額")
        val IMAGE_OR_EMPTY_HINTS = listOf("image", "imagen", "图片", "圖片", "foto", "photo")
        const val SAMPLE_ROWS = 5
        const val VALIDATION_SAMPLE_ROWS = 20
        const val RAW_HINT_ROWS = 25
        const val RAW_HINT_COLUMNS = 30
    }
}

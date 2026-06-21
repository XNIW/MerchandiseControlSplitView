package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import java.io.File
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExcelRecognitionAuditTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    @Test
    fun auditExcelRecognitionGoldenFixtures() {
        val files = resolveAuditFiles()
        val reports = files.map { file -> auditFile(file) }
        writeReports(reports)

        val markdown = renderMarkdown(reports)
        println(markdown)

        val nonPassGolden = reports.filter { it.hasExpected && it.verdict != Verdict.PASS.name }
        assertTrue(
            nonPassGolden.joinToString(separator = "\n") { report ->
                "${report.fileName}: ${report.verdict} ${report.failReasons.joinToString()}"
            },
            nonPassGolden.isEmpty()
        )
    }

    private fun resolveAuditFiles(): List<File> {
        val externalFiles = System.getProperty("excelAudit.files")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return if (externalFiles.isNotEmpty()) {
            externalFiles.map(::File)
        } else {
            listOf(
                resourceFile("excel/Vs20260529-ModaLina.xlsx"),
                resourceFile("excel/20260620-Pinmark.xlsx")
            )
        }
    }

    private fun auditFile(file: File): AuditFileReport {
        require(file.exists()) { "Excel audit file not found: ${file.absolutePath}" }

        val expected = loadExpected(file.name)
        val analysis = readAndAnalyzeExcelDetailed(context, Uri.fromFile(file))
        val sheetSnapshot = sheetSnapshot(file, analysis.originalHeaders)
        val traceByField = analysis.trace.fieldDecisions.associateBy { it.field }
        val importantFields = (
            expected?.requiredFields.orEmpty() + listOf(
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
        ).distinct()

        val mappings = importantFields.map { field ->
            val colIndex = analysis.header.indexOf(field).takeIf { it >= 0 }
            FieldMappingReport(
                field = field,
                colIndex = colIndex,
                colLetter = colIndex?.let(::columnLetter),
                originalHeader = colIndex?.let { analysis.originalHeaders.getOrNull(it).orEmpty() },
                source = colIndex?.let { analysis.headerSource.getOrNull(it).orEmpty() },
                confidence = traceByField[field]?.confidence,
                reason = traceByField[field]?.reason,
                sampleValues = colIndex?.let { col ->
                    analysis.dataRows.take(SAMPLE_ROWS).map { row -> row.getOrNull(col).orEmpty() }
                }.orEmpty()
            )
        }

        val failReasons = mutableListOf<String>()
        val warnReasons = mutableListOf<String>()

        if (expected != null) {
            val actualHeaderRow = sheetSnapshot.headerRowOneBased
                ?: analysis.trace.headerRows.lastOrNull()?.plus(1)
            if (actualHeaderRow != expected.expectedHeaderRow) {
                failReasons += "HEADER_ROW_MISMATCH expected=${expected.expectedHeaderRow} actual=$actualHeaderRow"
            }
            val actualFirstDataRow = sheetSnapshot.firstDataRowOneBased
                ?: analysis.trace.dataRowIdx.takeIf { it >= 0 }?.plus(1)
            if (actualFirstDataRow != expected.expectedFirstDataRow) {
                failReasons += "FIRST_DATA_ROW_MISMATCH expected=${expected.expectedFirstDataRow} actual=$actualFirstDataRow"
            }

            expected.requiredFields.forEach { field ->
                if (analysis.header.indexOf(field) < 0) {
                    failReasons += "FIELD_MISSING field=$field"
                }
            }

            expected.fields.forEach { (field, fieldExpected) ->
                val actualIndex = analysis.header.indexOf(field)
                if (actualIndex < 0) {
                    failReasons += "FIELD_MISMATCH field=$field expected=${fieldExpected.colLetter} actual=missing"
                    return@forEach
                }

                if (actualIndex != fieldExpected.colIndex) {
                    failReasons += "FIELD_MISMATCH field=$field expected=${fieldExpected.colLetter} actual=${columnLetter(actualIndex)}"
                }

                val actualOriginal = analysis.originalHeaders.getOrNull(actualIndex).orEmpty()
                if (actualOriginal != fieldExpected.originalHeader) {
                    failReasons += "HEADER_ORIGINAL_MISMATCH field=$field expected=${fieldExpected.originalHeader.forReason()} actual=${actualOriginal.forReason()}"
                }

                val actualSource = analysis.headerSource.getOrNull(actualIndex).orEmpty()
                if (actualSource == "generated") {
                    failReasons += "REQUIRED_GENERATED field=$field expected=${fieldExpected.colLetter}"
                }
                if (actualSource != fieldExpected.source) {
                    failReasons += "HEADER_SOURCE_MISMATCH field=$field expected=${fieldExpected.source} actual=$actualSource"
                }
            }
        }

        val ignoredColumnIndexes = expected?.ignoredColumns.orEmpty().map { it.colIndex }.toSet()
        val suspiciousColumns = analysis.header.mapIndexedNotNull { index, canonical ->
            val source = analysis.headerSource.getOrNull(index).orEmpty()
            val original = analysis.originalHeaders.getOrNull(index).orEmpty()
            val hasSamples = analysis.dataRows.any { row -> row.getOrNull(index)?.isNotBlank() == true }
            if (source == "unknown" && original.isNotBlank() && hasSamples && index !in ignoredColumnIndexes) {
                SuspiciousColumnReport(
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

        if (suspiciousColumns.isNotEmpty()) {
            warnReasons += suspiciousColumns.joinToString(prefix = "SUSPICIOUS_COLUMNS ") { column ->
                "${column.colLetter}=${column.originalHeader.forReason()}"
            }
        }

        val verdict = when {
            failReasons.isNotEmpty() -> Verdict.FAIL
            warnReasons.isNotEmpty() -> Verdict.WARN
            else -> Verdict.PASS
        }

        return AuditFileReport(
            fileName = file.name,
            filePath = file.absolutePath,
            sheet = sheetSnapshot.sheetName,
            hasExpected = expected != null,
            detectedHeaderRow = sheetSnapshot.headerRowOneBased
                ?: analysis.trace.headerRows.lastOrNull()?.plus(1),
            detectedFirstDataRow = sheetSnapshot.firstDataRowOneBased
                ?: analysis.trace.dataRowIdx.takeIf { it >= 0 }?.plus(1),
            headerMode = analysis.trace.headerMode,
            originalHeaders = analysis.originalHeaders,
            canonicalHeaders = analysis.header,
            headerSource = analysis.headerSource,
            mappings = mappings,
            missingRequiredFields = expected?.requiredFields.orEmpty().filter { field ->
                analysis.header.indexOf(field) < 0
            },
            suspiciousColumns = suspiciousColumns,
            verdict = verdict.name,
            failReasons = failReasons,
            warnReasons = warnReasons
        )
    }

    private fun loadExpected(fileName: String): ExpectedCase? {
        val expectedName = fileName.substringBeforeLast('.') + ".expected.json"
        val stream = javaClass.classLoader?.getResourceAsStream("excel/$expectedName") ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            gson.fromJson(reader, ExpectedCase::class.java)
        }
    }

    private fun writeReports(reports: List<AuditFileReport>) {
        val reportDir = File(
            System.getProperty("excelAudit.reportDir")
                ?: "build/reports/excelRecognitionAudit"
        )
        reportDir.mkdirs()
        File(reportDir, "excel-recognition-audit.json").writeText(
            gson.toJson(AuditRunReport(reports = reports)),
            Charsets.UTF_8
        )
        File(reportDir, "excel-recognition-audit.md").writeText(
            renderMarkdown(reports),
            Charsets.UTF_8
        )
    }

    private fun renderMarkdown(reports: List<AuditFileReport>): String = buildString {
        appendLine("# Excel Recognition Audit")
        appendLine()
        reports.forEach { report ->
            appendLine("## ${report.fileName} — ${report.verdict}")
            appendLine()
            appendLine("- Sheet: ${report.sheet}")
            appendLine("- Header row: ${report.detectedHeaderRow ?: "not detected"}")
            appendLine("- First data row: ${report.detectedFirstDataRow ?: "not detected"}")
            appendLine("- Header mode: ${report.headerMode}")
            appendLine("- Original headers: ${report.originalHeaders.joinToString { it.forMarkdownInline() }}")
            appendLine("- Canonical headers: ${report.canonicalHeaders.joinToString()}")
            appendLine("- Header source: ${report.headerSource.joinToString()}")
            appendLine()
            appendLine("| Field | Column | Original header | Source | Confidence | Reason | Samples |")
            appendLine("|---|---:|---|---|---|---|---|")
            report.mappings.forEach { mapping ->
                appendLine(
                    "| ${mapping.field} | ${mapping.colLetter ?: "-"} | " +
                        "${mapping.originalHeader.orEmpty().forMarkdownCell()} | " +
                        "${mapping.source ?: "-"} | ${mapping.confidence ?: "-"} | " +
                        "${mapping.reason ?: "-"} | ${mapping.sampleValues.joinToString(" / ").forMarkdownCell()} |"
                )
            }
            if (report.missingRequiredFields.isNotEmpty()) {
                appendLine()
                appendLine("Missing required fields: ${report.missingRequiredFields.joinToString()}")
            }
            if (report.suspiciousColumns.isNotEmpty()) {
                appendLine()
                appendLine("Suspicious columns:")
                report.suspiciousColumns.forEach { column ->
                    appendLine(
                        "- ${column.colLetter} ${column.originalHeader.forMarkdownInline()} " +
                            "samples=${column.sampleValues.joinToString(" / ").forMarkdownInline()}"
                    )
                }
            }
            if (report.failReasons.isNotEmpty()) {
                appendLine()
                appendLine("Fail reasons:")
                report.failReasons.forEach { appendLine("- $it") }
            }
            if (report.warnReasons.isNotEmpty()) {
                appendLine()
                appendLine("Warn reasons:")
                report.warnReasons.forEach { appendLine("- $it") }
            }
            appendLine()
        }
    }

    private fun resourceFile(path: String): File {
        val resource = javaClass.classLoader?.getResource(path)
            ?: error("Missing test resource: $path")
        return File(resource.toURI())
    }

    private fun sheetSnapshot(
        file: File,
        originalHeaders: List<String>
    ): SheetSnapshot {
        return runCatching {
            WorkbookFactory.create(file).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = org.apache.poi.ss.usermodel.DataFormatter()
                var headerRow: Int? = null
                for (rowIndex in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    val last = maxOf(row.lastCellNum.toInt().coerceAtLeast(0), originalHeaders.size)
                    val values = (0 until last).map { column ->
                        formatter.formatCellValue(row.getCell(column)).trim()
                    }.dropLastWhile { it.isBlank() }
                    val expected = originalHeaders.dropLastWhile { it.isBlank() }
                    if (expected.isNotEmpty() && values == expected) {
                        headerRow = rowIndex + 1
                        break
                    }
                }
                SheetSnapshot(
                    sheetName = sheet.sheetName,
                    headerRowOneBased = headerRow,
                    firstDataRowOneBased = headerRow?.let { nextNonBlankRow(sheet, it) }
                )
            }
        }.getOrElse {
            SheetSnapshot(
                sheetName = "sheet-0",
                headerRowOneBased = null,
                firstDataRowOneBased = null
            )
        }
    }

    private fun nextNonBlankRow(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        headerRowOneBased: Int
    ): Int? {
        val formatter = org.apache.poi.ss.usermodel.DataFormatter()
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

    private fun String.forReason(): String = replace("\r\n", "\n").replace("\n", "\\n")

    private fun String.forMarkdownInline(): String = forReason().ifBlank { "(blank)" }

    private fun String.forMarkdownCell(): String = forMarkdownInline().replace("|", "\\|")

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

    private data class AuditRunReport(
        val reports: List<AuditFileReport>
    )

    private data class SheetSnapshot(
        val sheetName: String,
        val headerRowOneBased: Int?,
        val firstDataRowOneBased: Int?
    )

    private data class AuditFileReport(
        val fileName: String,
        val filePath: String,
        val sheet: String,
        val hasExpected: Boolean,
        val detectedHeaderRow: Int?,
        val detectedFirstDataRow: Int?,
        val headerMode: String,
        val originalHeaders: List<String>,
        val canonicalHeaders: List<String>,
        val headerSource: List<String>,
        val mappings: List<FieldMappingReport>,
        val missingRequiredFields: List<String>,
        val suspiciousColumns: List<SuspiciousColumnReport>,
        val verdict: String,
        val failReasons: List<String>,
        val warnReasons: List<String>
    )

    private data class FieldMappingReport(
        val field: String,
        val colIndex: Int?,
        val colLetter: String?,
        val originalHeader: String?,
        val source: String?,
        val confidence: String?,
        val reason: String?,
        val sampleValues: List<String>
    )

    private data class SuspiciousColumnReport(
        val colIndex: Int,
        val colLetter: String,
        val originalHeader: String,
        val canonicalHeader: String,
        val sampleValues: List<String>
    )

    private enum class Verdict {
        PASS,
        WARN,
        FAIL
    }

    private companion object {
        const val SAMPLE_ROWS = 3
    }
}

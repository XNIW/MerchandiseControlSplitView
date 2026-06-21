package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExcelRecognitionOracleV2Test {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    @Test
    fun validatePinmarkAndModalinaWithIndependentRawWorkbookOracle() {
        assumeTrue(
            "Oracle v2 runs only via :app:excelRecognitionOracleV2",
            System.getProperty("excelAudit.runOracleV2") == "true"
        )

        val cases = listOf(
            GoldenOracleCase(
                id = "pinmark",
                displayName = "Pinmark",
                file = resolveInputFile(
                    property = "excelOracle.pinmarkFile",
                    desktopPath = "/Users/minxiang/Desktop/20260620-Pinmark.xlsx",
                    resourcePath = "excel/20260620-Pinmark.xlsx"
                ),
                oracle = OracleExpected(
                    status = OracleStatus.ORACLE_CONFIRMED.name,
                    expectedHeaderRowPhysical = 9,
                    expectedFirstDataRowPhysical = 10,
                    expectedLastDataRowPhysical = 42,
                    expectedDataRowCount = 33,
                    ignoredMetadataRows = listOf(2, 3, 4, 5, 6, 7, 8),
                    ignoredSummaryRows = listOf(43, 44),
                    fields = mapOf(
                        "itemNumber" to OracleField("A", "REF / item code"),
                        "barcode" to OracleField("B", "EAN barcode"),
                        "productName" to OracleField("C", "Spanish product description"),
                        "secondProductName" to OracleField("E", "secondary Chinese product name"),
                        "quantity" to OracleField("G", "quantity"),
                        "purchasePrice" to OracleField("H", "wholesale price"),
                        "discount" to OracleField("I", "discount percent"),
                        "discountedPrice" to OracleField("J", "discounted unit price"),
                        "totalPrice" to OracleField("K", "line total")
                    ),
                    ignoredColumns = listOf(
                        OracleIgnoredColumn("D", "blank internal spacer"),
                        OracleIgnoredColumn("F", "image column")
                    ),
                    confidence = "confirmed",
                    reason = "Raw workbook shows order metadata on rows 2-8, composite header on row 9, and product rows 10-42."
                )
            ),
            GoldenOracleCase(
                id = "modalina",
                displayName = "Modalina",
                file = resolveInputFile(
                    property = "excelOracle.modalinaFile",
                    desktopPath = "/Users/minxiang/Desktop/Vs20260529-ModaLina.xlsx",
                    resourcePath = "excel/Vs20260529-ModaLina.xlsx"
                ),
                oracle = OracleExpected(
                    status = OracleStatus.ORACLE_CONFIRMED.name,
                    expectedHeaderRowPhysical = 10,
                    expectedFirstDataRowPhysical = 11,
                    expectedLastDataRowPhysical = 53,
                    expectedDataRowCount = 43,
                    ignoredMetadataRows = listOf(1, 2, 3, 4, 5, 6, 7, 8),
                    ignoredSummaryRows = listOf(55),
                    fields = mapOf(
                        "rowNumber" to OracleField("A", "row number"),
                        "itemNumber" to OracleField("B", "item number"),
                        "barcode" to OracleField("C", "barcode"),
                        "productName" to OracleField("D", "primary product name"),
                        "secondProductName" to OracleField("E", "secondary product name"),
                        "quantity" to OracleField("G", "quantity"),
                        "discount" to OracleField("H", "discount"),
                        "purchasePrice" to OracleField("I", "unit selling/purchase price for this supplier format"),
                        "totalPrice" to OracleField("J", "line total")
                    ),
                    ignoredColumns = listOf(
                        OracleIgnoredColumn("F", "box count, not imported as quantity")
                    ),
                    confidence = "confirmed",
                    reason = "Raw workbook shows metadata rows 1-8, physical header row 10, product rows 11-53, and summary row 55."
                )
            )
        )

        val validations = cases.map { oracleCase ->
            val raw = inspectRawWorkbook(oracleCase.file)
            val platform = runAndroidParser(oracleCase.file, raw, oracleCase.oracle)
            val comparison = comparePlatformToOracle(oracleCase.oracle, platform, raw)
            writeCaseReports(oracleCase, raw, platform, comparison)
            GoldenValidation(
                caseName = oracleCase.displayName,
                fileName = oracleCase.file.name,
                sha256 = oracleCase.file.sha256(),
                rawWorkbook = raw,
                oracle = oracleCase.oracle,
                androidCurrentOutput = platform,
                comparison = comparison
            )
        }

        val pinmark = validations.single { it.caseName == "Pinmark" }
        assertEquals(9, pinmark.rawWorkbook.headerCandidateRow)
        assertEquals(10, pinmark.oracle.expectedFirstDataRowPhysical)
        assertEquals("C", pinmark.oracle.fields.getValue("productName").colLetter)
        validations.forEach { validation ->
            assertEquals(
                "${validation.caseName} must match confirmed oracle",
                OracleVerdict.PASS_CONFIRMED.name,
                validation.comparison.verdict
            )
            assertEquals("${validation.caseName} row boundary", "PASS", validation.comparison.rowBoundaryVerdict)
            assertEquals("${validation.caseName} column mapping", "PASS", validation.comparison.columnMappingVerdict)
        }

        val badPinmark = syntheticBadPinmarkSnapshot(pinmark.rawWorkbook)
        val badComparison = comparePlatformToOracle(pinmark.oracle, badPinmark, pinmark.rawWorkbook)
        assertTrue(
            "Oracle v2 must detect dirty Pinmark row 7",
            badComparison.rowErrors.any { it.code == RowErrorCode.DIRTY_ROW_INCLUDED.name && it.physicalRow == 7 }
        )
        assertTrue(
            "Oracle v2 must detect missing/misassigned Pinmark productName",
            badComparison.columnErrors.any {
                it.code == ColumnErrorCode.PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS.name ||
                    it.code == ColumnErrorCode.FIELD_MISASSIGNED.name ||
                    it.code == ColumnErrorCode.REQUIRED_FIELD_MISSING.name
            }
        )

        val runReport = OracleV2RunReport(
            timestamp = Instant.now().toString(),
            status = "HARNESS_V2_VALIDATION",
            v1Status = "INVALID_V1_SELF_REFERENTIAL_AUDIT",
            validations = validations,
            pinmarkNegativeControl = badComparison
        )
        writeRunReports(runReport)
        println(renderValidationSummary(runReport))
    }

    private fun resolveInputFile(
        property: String,
        desktopPath: String,
        resourcePath: String
    ): File {
        val configured = System.getProperty(property).orEmpty().trim()
        if (configured.isNotEmpty()) return File(configured)

        val desktop = File(desktopPath)
        if (desktop.exists()) return desktop

        val resource = javaClass.classLoader?.getResource(resourcePath)
            ?: error("Missing oracle v2 input: $desktopPath or resource $resourcePath")
        return File(resource.toURI())
    }

    private fun inspectRawWorkbook(file: File): RawWorkbookInspection {
        require(file.exists()) { "Raw workbook not found: ${file.absolutePath}" }
        file.inputStream().use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = DataFormatter()
                val maxColumn = maxColumn(sheet)
                val rows = (0..sheet.lastRowNum).map { rowIndex ->
                    val row = sheet.getRow(rowIndex)
                    RawRow(
                        physicalRow = rowIndex + 1,
                        hidden = row?.zeroHeight == true,
                        empty = row == null || (0 until maxColumn).all { column ->
                            formatter.formatCellValue(row.getCell(column)).trim().isBlank()
                        },
                        cells = (0 until maxColumn).map { column ->
                            val value = row?.getCell(column)?.let { formatter.formatCellValue(it).trim() }.orEmpty()
                            RawCell(
                                row = rowIndex + 1,
                                colIndex = column,
                                colLetter = columnLetter(column),
                                value = value,
                                hiddenColumn = sheet.isColumnHidden(column)
                            )
                        }
                    )
                }
                val headerCandidate = detectRawHeaderCandidate(rows)
                return RawWorkbookInspection(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    sha256 = file.sha256(),
                    sheetName = sheet.sheetName,
                    sheetCount = workbook.numberOfSheets,
                    maxRow = sheet.lastRowNum + 1,
                    maxColumn = maxColumn,
                    mergedCells = (0 until sheet.numMergedRegions).map { index ->
                        sheet.getMergedRegion(index).formatAsString()
                    },
                    hiddenRows = rows.filter { it.hidden }.map { it.physicalRow },
                    hiddenColumns = (0 until maxColumn).filter { sheet.isColumnHidden(it) }.map(::columnLetter),
                    imageColumns = imageColumns(rows, headerCandidate),
                    rawRowsPreview = rows.take(RAW_PREVIEW_ROWS),
                    headerCandidateRow = headerCandidate,
                    metadataRows = classifyMetadataRows(rows, headerCandidate),
                    summaryRows = classifySummaryRows(rows, headerCandidate)
                )
            }
        }
    }

    private fun maxColumn(sheet: Sheet): Int {
        return (0..sheet.lastRowNum).maxOfOrNull { rowIndex ->
            sheet.getRow(rowIndex)?.lastCellNum?.toInt()?.coerceAtLeast(0) ?: 0
        } ?: 0
    }

    private fun detectRawHeaderCandidate(rows: List<RawRow>): Int? {
        return rows
            .filterNot { it.empty }
            .maxByOrNull { row ->
                val values = row.cells.map { it.value }
                headerScore(values)
            }
            ?.physicalRow
    }

    private fun headerScore(values: List<String>): Int {
        val joined = values.joinToString("\n").lowercase()
        val tokens = listOf(
            "ean", "条码", "barcode", "descripcion", "descripción", "品名",
            "producto", "产品名", "数量", "cant", "precio", "pre", "批发价",
            "折扣", "p.desc", "sum", "总价", "合计", "货号", "ref"
        )
        val tokenHits = tokens.count { it.lowercase() in joined }
        val nonBlank = values.count { it.isNotBlank() }
        return tokenHits * 10 + nonBlank
    }

    private fun classifyMetadataRows(rows: List<RawRow>, headerCandidate: Int?): List<Int> {
        val header = headerCandidate ?: return emptyList()
        return rows
            .filter { it.physicalRow < header && !it.empty }
            .map { it.physicalRow }
    }

    private fun classifySummaryRows(rows: List<RawRow>, headerCandidate: Int?): List<Int> {
        val header = headerCandidate ?: return emptyList()
        return rows
            .filter { it.physicalRow > header && !it.empty }
            .filter { row ->
                val nonBlank = row.cells.map { it.value }.filter { it.isNotBlank() }
                val joined = nonBlank.joinToString(" ").lowercase()
                nonBlank.size <= 4 && (
                    "total" in joined ||
                        "总价" in joined ||
                        "总数" in joined ||
                        "upos" in joined ||
                        "合作愉快" in joined
                    )
            }
            .map { it.physicalRow }
    }

    private fun imageColumns(rows: List<RawRow>, headerCandidate: Int?): List<String> {
        val headerRow = rows.firstOrNull { it.physicalRow == headerCandidate } ?: return emptyList()
        return headerRow.cells
            .filter { cell ->
                val value = cell.value.lowercase()
                "image" in value || "imagen" in value || "图片" in value || "圖片" in value
            }
            .map { it.colLetter }
    }

    private fun runAndroidParser(
        file: File,
        raw: RawWorkbookInspection,
        oracle: OracleExpected
    ): PlatformSnapshot {
        val analysis = readAndAnalyzeExcelDetailed(context, Uri.fromFile(file), allowEmptyTabularResult = true)
        val mapping = oracle.fields.keys.sorted().associateWith { field ->
            platformFieldMapping(field, analysis, raw, oracle)
        }
        val physicalRows = mapPlatformRowsToPhysicalRows(analysis.dataRows, raw)
        return PlatformSnapshot(
            platform = "ANDROID",
            parser = "readAndAnalyzeExcelDetailed",
            detectedHeaderRowPhysical = platformHeaderRowPhysical(analysis, raw),
            detectedFirstDataRowPhysical = physicalRows.firstOrNull(),
            detectedLastDataRowPhysical = physicalRows.lastOrNull(),
            detectedDataRowCount = analysis.dataRows.size,
            detectedPhysicalRows = physicalRows,
            headerMode = analysis.trace.headerMode,
            normalizedHeaders = analysis.header,
            originalHeaders = analysis.originalHeaders,
            headerSource = analysis.headerSource,
            fieldMappings = mapping,
            generatedColumns = analysis.header.mapIndexedNotNull { index, field ->
                if (analysis.headerSource.getOrNull(index) == "generated") {
                    GeneratedColumn(field = field, colLetter = columnLetter(index))
                } else {
                    null
                }
            },
            unknownColumns = analysis.header.mapIndexedNotNull { index, field ->
                if (analysis.headerSource.getOrNull(index) == "unknown") {
                    UnknownColumn(
                        canonicalHeader = field,
                        originalHeader = analysis.originalHeaders.getOrNull(index).orEmpty(),
                        platformColLetter = columnLetter(index)
                    )
                } else {
                    null
                }
            },
            previewRows = analysis.dataRows.take(8)
        )
    }

    private fun platformHeaderRowPhysical(
        analysis: ExcelAnalysisResult,
        raw: RawWorkbookInspection
    ): Int? {
        return physicalHeaderRowByOriginalHeaders(raw, analysis.originalHeaders)
            ?: analysis.trace.headerRows.lastOrNull()?.let { compactRowIndex ->
                compactRowIndexToPhysicalRow(raw, compactRowIndex)
            }
    }

    private fun physicalHeaderRowByOriginalHeaders(
        raw: RawWorkbookInspection,
        originalHeaders: List<String>
    ): Int? {
        val expectedHeaders = originalHeaders.dropLastWhile { it.isBlank() }
        val expectedNonBlank = expectedHeaders
            .mapIndexedNotNull { index, value ->
                normalizeHeader(value).takeIf { it.isNotBlank() }?.let { index to it }
            }
        if (expectedNonBlank.isEmpty()) return null

        return loadAllRows(raw)
            .filterNot { it.empty }
            .firstOrNull { row ->
                expectedNonBlank.all { (index, expected) ->
                    normalizeHeader(row.cells.getOrNull(index)?.value.orEmpty()) == expected
                }
            }
            ?.physicalRow
    }

    private fun compactRowIndexToPhysicalRow(
        raw: RawWorkbookInspection,
        compactRowIndex: Int
    ): Int? {
        return loadAllRows(raw)
            .filterNot { it.empty }
            .drop(compactRowIndex)
            .firstOrNull()
            ?.physicalRow
    }

    private fun platformFieldMapping(
        field: String,
        analysis: ExcelAnalysisResult,
        raw: RawWorkbookInspection,
        oracle: OracleExpected
    ): PlatformFieldMapping {
        val index = analysis.header.indexOf(field).takeIf { it >= 0 }
        val original = index?.let { analysis.originalHeaders.getOrNull(it).orEmpty() }
        val physicalColumn = original?.let { originalHeader ->
            rawHeaderColumnByValue(raw, oracle.expectedHeaderRowPhysical, originalHeader)
        } ?: index?.let { columnLetter(it) }
        val samples = index?.let { col ->
            analysis.dataRows.take(8).map { row -> row.getOrNull(col).orEmpty() }
        }.orEmpty()
        return PlatformFieldMapping(
            field = field,
            platformColIndex = index,
            platformColLetter = index?.let(::columnLetter),
            physicalColLetter = physicalColumn,
            originalHeader = original,
            canonicalHeader = index?.let { analysis.header.getOrNull(it).orEmpty() },
            source = index?.let { analysis.headerSource.getOrNull(it).orEmpty() },
            sampleValues = samples
        )
    }

    private fun rawHeaderColumnByValue(
        raw: RawWorkbookInspection,
        headerRow: Int,
        headerValue: String
    ): String? {
        val target = normalizeHeader(headerValue)
        if (target.isBlank()) return null
        return raw.rawRowsPreview
            .firstOrNull { it.physicalRow == headerRow }
            ?.cells
            ?.firstOrNull { normalizeHeader(it.value) == target }
            ?.colLetter
    }

    private fun normalizeHeader(value: String): String {
        return value
            .replace("\r\n", "\n")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun mapPlatformRowsToPhysicalRows(
        platformRows: List<List<String>>,
        raw: RawWorkbookInspection
    ): List<Int> {
        val usedRows = mutableSetOf<Int>()
        return platformRows.mapNotNull { platformRow ->
            val nonBlank = platformRow.map { it.trim() }.filter { it.isNotBlank() }
            if (nonBlank.isEmpty()) return@mapNotNull null
            val match = raw.rawRowsPreview
                .asSequence()
                .plus(loadAllRows(raw))
                .filter { it.physicalRow !in usedRows && !it.empty }
                .map { row -> row to rowMatchScore(nonBlank, row.cells.map { it.value.trim() }) }
                .filter { it.second >= 0.70 }
                .maxByOrNull { it.second }
                ?.first
            if (match != null) {
                usedRows += match.physicalRow
                match.physicalRow
            } else {
                null
            }
        }
    }

    private fun loadAllRows(raw: RawWorkbookInspection): Sequence<RawRow> {
        return File(raw.filePath).inputStream().use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = DataFormatter()
                val maxColumn = raw.maxColumn
                (0..sheet.lastRowNum).map { rowIndex ->
                    val row = sheet.getRow(rowIndex)
                    RawRow(
                        physicalRow = rowIndex + 1,
                        hidden = row?.zeroHeight == true,
                        empty = row == null || (0 until maxColumn).all { column ->
                            formatter.formatCellValue(row.getCell(column)).trim().isBlank()
                        },
                        cells = (0 until maxColumn).map { column ->
                            RawCell(
                                row = rowIndex + 1,
                                colIndex = column,
                                colLetter = columnLetter(column),
                                value = row?.getCell(column)?.let { formatter.formatCellValue(it).trim() }.orEmpty(),
                                hiddenColumn = sheet.isColumnHidden(column)
                            )
                        }
                    )
                }.asSequence()
            }
        }
    }

    private fun rowMatchScore(platformValues: List<String>, rawValues: List<String>): Double {
        val rawMultiset = rawValues.filter { it.isNotBlank() }.toMutableList()
        var matches = 0
        platformValues.forEach { platformValue ->
            val index = rawMultiset.indexOfFirst { it == platformValue }
            if (index >= 0) {
                matches += 1
                rawMultiset.removeAt(index)
            }
        }
        return matches.toDouble() / platformValues.size.toDouble()
    }

    private fun comparePlatformToOracle(
        oracle: OracleExpected,
        platform: PlatformSnapshot,
        raw: RawWorkbookInspection
    ): OracleComparison {
        val rowErrors = mutableListOf<RowError>()
        val columnErrors = mutableListOf<ColumnError>()

        if (platform.detectedHeaderRowPhysical != null &&
            platform.detectedHeaderRowPhysical < oracle.expectedHeaderRowPhysical
        ) {
            rowErrors += RowError(
                RowErrorCode.HEADER_ROW_TOO_EARLY.name,
                platform.detectedHeaderRowPhysical,
                "Detected header row ${platform.detectedHeaderRowPhysical}, expected ${oracle.expectedHeaderRowPhysical}"
            )
        }
        if (platform.detectedHeaderRowPhysical != null &&
            platform.detectedHeaderRowPhysical > oracle.expectedHeaderRowPhysical
        ) {
            rowErrors += RowError(
                RowErrorCode.HEADER_ROW_TOO_LATE.name,
                platform.detectedHeaderRowPhysical,
                "Detected header row ${platform.detectedHeaderRowPhysical}, expected ${oracle.expectedHeaderRowPhysical}"
            )
        }
        if (platform.detectedFirstDataRowPhysical != null &&
            platform.detectedFirstDataRowPhysical != oracle.expectedFirstDataRowPhysical
        ) {
            rowErrors += RowError(
                RowErrorCode.FIRST_DATA_ROW_WRONG.name,
                platform.detectedFirstDataRowPhysical,
                "Detected first data row ${platform.detectedFirstDataRowPhysical}, expected ${oracle.expectedFirstDataRowPhysical}"
            )
        }
        platform.detectedPhysicalRows.forEach { row ->
            if (row in oracle.ignoredMetadataRows) {
                rowErrors += RowError(RowErrorCode.METADATA_ROW_INCLUDED.name, row, "Metadata row included as product data")
            }
            if (row in oracle.ignoredSummaryRows) {
                rowErrors += RowError(RowErrorCode.SUMMARY_ROW_INCLUDED.name, row, "Summary/footer row included as product data")
            }
            if (row == 7 && 7 in oracle.ignoredMetadataRows) {
                rowErrors += RowError(RowErrorCode.DIRTY_ROW_INCLUDED.name, row, "Pinmark order discount/total row included as product data")
            }
            if (oracle.expectedLastDataRowPhysical != null && row > oracle.expectedLastDataRowPhysical) {
                rowErrors += RowError(RowErrorCode.SUMMARY_ROW_INCLUDED.name, row, "Row after expected last product row included")
            }
        }
        if (platform.detectedDataRowCount != oracle.expectedDataRowCount) {
            rowErrors += RowError(
                RowErrorCode.ROW_COUNT_MISMATCH.name,
                null,
                "Detected ${platform.detectedDataRowCount} data rows, expected ${oracle.expectedDataRowCount}"
            )
        }

        oracle.fields.forEach { (field, expected) ->
            val actual = platform.fieldMappings[field]
            if (actual == null || actual.platformColIndex == null) {
                columnErrors += ColumnError(
                    ColumnErrorCode.REQUIRED_FIELD_MISSING.name,
                    field,
                    expected.colLetter,
                    actual?.physicalColLetter,
                    "$field missing from platform mapping"
                )
                return@forEach
            }
            if (actual.source == "generated") {
                columnErrors += ColumnError(
                    ColumnErrorCode.GENERATED_BUT_REAL_COLUMN_EXISTS.name,
                    field,
                    expected.colLetter,
                    actual.physicalColLetter,
                    "$field generated although oracle raw column ${expected.colLetter} exists"
                )
            }
            if (actual.physicalColLetter != null && actual.physicalColLetter != expected.colLetter) {
                columnErrors += ColumnError(
                    ColumnErrorCode.FIELD_MISASSIGNED.name,
                    field,
                    expected.colLetter,
                    actual.physicalColLetter,
                    "$field mapped to ${actual.physicalColLetter}, expected ${expected.colLetter}"
                )
            }
        }

        val productName = platform.fieldMappings["productName"]
        val expectedProductColumn = oracle.fields["productName"]?.colLetter
        if (expectedProductColumn != null && productNameLooksEmpty(productName)) {
            val rawSamples = rawColumnSamples(raw, expectedProductColumn, oracle.expectedFirstDataRowPhysical)
            if (rawSamples.any { it.hasLettersOrCjk() }) {
                columnErrors += ColumnError(
                    ColumnErrorCode.PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS.name,
                    "productName",
                    expectedProductColumn,
                    productName?.physicalColLetter,
                    "productName is empty/unknown but raw column $expectedProductColumn contains ${rawSamples.take(3).joinToString(" / ")}"
                )
            }
        }

        sameColumnAssignments(platform).forEach { (column, fields) ->
            columnErrors += ColumnError(
                ColumnErrorCode.SAME_COLUMN_ASSIGNED_TO_MULTIPLE_FIELDS.name,
                fields.joinToString("+"),
                null,
                column,
                "Multiple fields assigned to $column: ${fields.joinToString()}"
            )
        }

        val purchase = platform.fieldMappings["purchasePrice"]?.physicalColLetter
        val total = platform.fieldMappings["totalPrice"]?.physicalColLetter
        val expectedPurchase = oracle.fields["purchasePrice"]?.colLetter
        val expectedTotal = oracle.fields["totalPrice"]?.colLetter
        if (purchase == expectedTotal && total == expectedPurchase) {
            columnErrors += ColumnError(
                ColumnErrorCode.PURCHASE_PRICE_CONFUSED_WITH_TOTAL_PRICE.name,
                "purchasePrice/totalPrice",
                "$expectedPurchase/$expectedTotal",
                "$purchase/$total",
                "purchasePrice and totalPrice appear swapped"
            )
        }

        val verdict = when {
            rowErrors.isEmpty() && columnErrors.isEmpty() && oracle.status == OracleStatus.ORACLE_CONFIRMED.name ->
                OracleVerdict.PASS_CONFIRMED
            rowErrors.any { it.code == RowErrorCode.DIRTY_ROW_INCLUDED.name || it.code == RowErrorCode.METADATA_ROW_INCLUDED.name } ||
                columnErrors.any { it.code in HARD_COLUMN_FAILURES } ->
                if (oracle.status == OracleStatus.ORACLE_CONFIRMED.name) OracleVerdict.FAIL_CONFIRMED else OracleVerdict.FAIL_SUSPECT
            rowErrors.isNotEmpty() || columnErrors.isNotEmpty() -> OracleVerdict.WARN_REVIEW
            else -> OracleVerdict.PASS_LIKELY
        }
        return OracleComparison(
            platform = platform.platform,
            verdict = verdict.name,
            rowBoundaryVerdict = if (rowErrors.isEmpty()) "PASS" else "ROW_BOUNDARY_FAIL",
            columnMappingVerdict = if (columnErrors.isEmpty()) "PASS" else "COLUMN_MAPPING_FAIL",
            rowErrors = rowErrors.distinct(),
            columnErrors = columnErrors.distinct()
        )
    }

    private fun syntheticBadPinmarkSnapshot(raw: RawWorkbookInspection): PlatformSnapshot {
        return PlatformSnapshot(
            platform = "ANDROID_SYNTHETIC_BAD_CONTROL",
            parser = "oracle-v2-negative-control",
            detectedHeaderRowPhysical = 7,
            detectedFirstDataRowPhysical = 7,
            detectedLastDataRowPhysical = 42,
            detectedDataRowCount = 34,
            detectedPhysicalRows = listOf(7) + (10..42),
            headerMode = "bad-ui-observed-control",
            normalizedHeaders = listOf("barcode", "-", "quantity", "purchasePrice", "totalPrice"),
            originalHeaders = listOf("EAN\n条码", "-", "CANT\n数量", "PRE\n批发价", "SUM\n折后合计"),
            headerSource = listOf("alias", "unknown", "alias", "alias", "alias"),
            fieldMappings = mapOf(
                "barcode" to PlatformFieldMapping("barcode", 0, "B", "B", "EAN\n条码", "barcode", "alias", listOf("1681688142313")),
                "productName" to PlatformFieldMapping("productName", null, null, null, "-", "productName", "generated", listOf("-", "-", "-")),
                "quantity" to PlatformFieldMapping("quantity", 2, "G", "G", "CANT\n数量", "quantity", "alias", listOf("24")),
                "purchasePrice" to PlatformFieldMapping("purchasePrice", 3, "H", "H", "PRE\n批发价", "purchasePrice", "alias", listOf("780")),
                "discount" to PlatformFieldMapping("discount", null, null, null, null, null, null, emptyList()),
                "discountedPrice" to PlatformFieldMapping("discountedPrice", null, null, null, null, null, null, emptyList()),
                "totalPrice" to PlatformFieldMapping("totalPrice", 4, "K", "K", "SUM\n折后合计", "totalPrice", "alias", listOf("18720"))
            ),
            generatedColumns = listOf(GeneratedColumn("productName", "-")),
            unknownColumns = listOf(UnknownColumn("-", "-", "-")),
            previewRows = raw.rawRowsPreview.firstOrNull { it.physicalRow == 7 }?.let { row ->
                listOf(row.cells.map { it.value })
            }.orEmpty()
        )
    }

    private fun productNameLooksEmpty(mapping: PlatformFieldMapping?): Boolean {
        if (mapping == null || mapping.platformColIndex == null || mapping.source == "generated") return true
        return mapping.sampleValues
            .filter { it.isNotBlank() }
            .take(5)
            .all { it == "-" || it.equals("null", ignoreCase = true) }
    }

    private fun sameColumnAssignments(platform: PlatformSnapshot): Map<String, List<String>> {
        return platform.fieldMappings.values
            .filter { it.physicalColLetter != null && it.source != "generated" }
            .groupBy { it.physicalColLetter.orEmpty() }
            .mapValues { entry -> entry.value.map { it.field } }
            .filterValues { it.size > 1 }
    }

    private fun rawColumnSamples(
        raw: RawWorkbookInspection,
        colLetter: String,
        firstDataRow: Int
    ): List<String> {
        val index = CellReference.convertColStringToIndex(colLetter)
        return loadAllRows(raw)
            .filter { it.physicalRow >= firstDataRow }
            .take(8)
            .mapNotNull { row -> row.cells.getOrNull(index)?.value?.takeIf { it.isNotBlank() } }
            .toList()
    }

    private fun writeCaseReports(
        oracleCase: GoldenOracleCase,
        raw: RawWorkbookInspection,
        platform: PlatformSnapshot,
        comparison: OracleComparison
    ) {
        val dir = oracleReportDir()
        dir.mkdirs()
        val id = oracleCase.id
        File(dir, "raw-workbook-$id.md").writeText(renderRawWorkbookMarkdown(raw), Charsets.UTF_8)
        File(dir, "oracle-$id.expected.json").writeText(gson.toJson(oracleCase.oracle), Charsets.UTF_8)
        File(dir, "platform-android-$id.json").writeText(gson.toJson(platform), Charsets.UTF_8)
        File(dir, "comparison-android-$id.md").writeText(
            renderComparisonMarkdown(oracleCase.displayName, raw, oracleCase.oracle, platform, comparison),
            Charsets.UTF_8
        )
    }

    private fun writeRunReports(report: OracleV2RunReport) {
        val dir = oracleReportDir()
        dir.mkdirs()
        File(dir, "harness-v2-validation.json").writeText(gson.toJson(report), Charsets.UTF_8)
        File(dir, "harness-v2-validation.md").writeText(renderValidationMarkdown(report), Charsets.UTF_8)
        File(reportRootDir(), "INVALID_V1_SELF_REFERENTIAL_AUDIT.md").writeText(
            "# INVALID_V1_SELF_REFERENTIAL_AUDIT\n\n" +
                "The previous Drive batch audit is superseded by Oracle v2 because it classified files too close to Android parser output.\n" +
                "Do not use `drive-batch-audit.*` or `/Users/minxiang/Desktop/File testing` as manual-testing truth.\n" +
                "Use Oracle v2 reports under `oracle-v2/` and regenerate `File testing v2/` only after oracle candidate review.\n",
            Charsets.UTF_8
        )
    }

    private fun reportRootDir(): File {
        return File(
            System.getProperty("excelAudit.reportDir")
                ?: "build/reports/excelRecognitionAudit"
        )
    }

    private fun oracleReportDir(): File = File(reportRootDir(), "oracle-v2")

    private fun renderRawWorkbookMarkdown(raw: RawWorkbookInspection): String = buildString {
        appendLine("# Raw Workbook Inspection — ${raw.fileName}")
        appendLine()
        appendLine("- Sheet: ${raw.sheetName}")
        appendLine("- Sheet count: ${raw.sheetCount}")
        appendLine("- Max row: ${raw.maxRow}")
        appendLine("- Max column: ${raw.maxColumn}")
        appendLine("- Header candidate row: ${raw.headerCandidateRow ?: "not detected"}")
        appendLine("- Merged cells: ${raw.mergedCells.joinToString().ifBlank { "(none)" }}")
        appendLine("- Hidden rows: ${raw.hiddenRows.joinToString().ifBlank { "(none)" }}")
        appendLine("- Hidden columns: ${raw.hiddenColumns.joinToString().ifBlank { "(none)" }}")
        appendLine("- Image columns: ${raw.imageColumns.joinToString().ifBlank { "(none)" }}")
        appendLine("- Metadata rows: ${raw.metadataRows.joinToString().ifBlank { "(none)" }}")
        appendLine("- Summary/footer rows: ${raw.summaryRows.joinToString().ifBlank { "(none)" }}")
        appendLine()
        appendLine("| Row | Values |")
        appendLine("|---:|---|")
        raw.rawRowsPreview.forEach { row ->
            val values = row.cells.joinToString(" | ") { cell ->
                val display = cell.value.replace("\n", "\\n")
                "${cell.colLetter}=${display.ifBlank { "(blank)" }}"
            }
            appendLine("| ${row.physicalRow} | ${values.forMarkdownCell()} |")
        }
    }

    private fun renderComparisonMarkdown(
        name: String,
        raw: RawWorkbookInspection,
        oracle: OracleExpected,
        platform: PlatformSnapshot,
        comparison: OracleComparison
    ): String = buildString {
        appendLine("# Oracle v2 Comparison — $name / ${platform.platform}")
        appendLine()
        appendLine("## Oracle")
        appendLine()
        appendLine("- Header row: ${oracle.expectedHeaderRowPhysical}")
        appendLine("- First data row: ${oracle.expectedFirstDataRowPhysical}")
        appendLine("- Last data row: ${oracle.expectedLastDataRowPhysical ?: "-"}")
        appendLine("- Expected row count: ${oracle.expectedDataRowCount}")
        appendLine("- Ignored metadata rows: ${oracle.ignoredMetadataRows.joinToString()}")
        appendLine("- Ignored summary rows: ${oracle.ignoredSummaryRows.joinToString()}")
        appendLine("- Mapping: ${oracle.fields.map { it.key + "=" + it.value.colLetter }.joinToString()}")
        appendLine()
        appendLine("## Platform Output")
        appendLine()
        appendLine("- Header row: ${platform.detectedHeaderRowPhysical ?: "-"}")
        appendLine("- First data row: ${platform.detectedFirstDataRowPhysical ?: "-"}")
        appendLine("- Last data row: ${platform.detectedLastDataRowPhysical ?: "-"}")
        appendLine("- Data row count: ${platform.detectedDataRowCount}")
        appendLine("- Header mode: ${platform.headerMode}")
        appendLine("- Physical rows: ${platform.detectedPhysicalRows.joinToString()}")
        appendLine()
        appendLine("| Field | Expected physical col | Actual physical col | Source | Samples |")
        appendLine("|---|---:|---:|---|---|")
        oracle.fields.forEach { (field, expected) ->
            val actual = platform.fieldMappings[field]
            appendLine(
                "| $field | ${expected.colLetter} | ${actual?.physicalColLetter ?: "-"} | " +
                    "${actual?.source ?: "-"} | ${actual?.sampleValues.orEmpty().joinToString(" / ").forMarkdownCell()} |"
            )
        }
        appendLine()
        appendLine("## Verdict")
        appendLine()
        appendLine("- Verdict: ${comparison.verdict}")
        appendLine("- Row boundary: ${comparison.rowBoundaryVerdict}")
        appendLine("- Column mapping: ${comparison.columnMappingVerdict}")
        if (comparison.rowErrors.isNotEmpty()) {
            appendLine()
            appendLine("Row errors:")
            comparison.rowErrors.forEach { error ->
                appendLine("- ${error.code}: row=${error.physicalRow ?: "-"} ${error.reason}")
            }
        }
        if (comparison.columnErrors.isNotEmpty()) {
            appendLine()
            appendLine("Column errors:")
            comparison.columnErrors.forEach { error ->
                appendLine("- ${error.code}: field=${error.field} expected=${error.expectedCol ?: "-"} actual=${error.actualCol ?: "-"} ${error.reason}")
            }
        }
        appendLine()
        appendLine("Raw header row ${raw.headerCandidateRow}:")
        val rawHeader = raw.rawRowsPreview.firstOrNull { it.physicalRow == raw.headerCandidateRow }
        appendLine(rawHeader?.cells.orEmpty().joinToString(" | ") { "${it.colLetter}=${it.value.replace("\n", "\\n")}" })
    }

    private fun renderValidationMarkdown(report: OracleV2RunReport): String = buildString {
        appendLine("# Harness v2 Validation")
        appendLine()
        appendLine("- Timestamp: ${report.timestamp}")
        appendLine("- V1 status: ${report.v1Status}")
        appendLine()
        report.validations.forEach { validation ->
            appendLine("## ${validation.caseName}")
            appendLine()
            appendLine("- Raw header row: ${validation.rawWorkbook.headerCandidateRow}")
            appendLine("- Raw first data row: ${validation.oracle.expectedFirstDataRowPhysical}")
            appendLine("- Dirty rows excluded: ${validation.oracle.ignoredMetadataRows.joinToString()}")
            appendLine("- Oracle mapping: ${validation.oracle.fields.map { it.key + "=" + it.value.colLetter }.joinToString()}")
            appendLine("- Android header row: ${validation.androidCurrentOutput.detectedHeaderRowPhysical ?: "-"}")
            appendLine("- Android first data row: ${validation.androidCurrentOutput.detectedFirstDataRowPhysical ?: "-"}")
            appendLine("- Android productName mapping: ${validation.androidCurrentOutput.fieldMappings["productName"]?.physicalColLetter ?: "-"}")
            appendLine("- Android dirty rows included: ${validation.comparison.rowErrors.any { it.code == RowErrorCode.DIRTY_ROW_INCLUDED.name || it.code == RowErrorCode.METADATA_ROW_INCLUDED.name }}")
            appendLine("- Verdict: ${validation.comparison.verdict}")
            appendLine("- Row boundary: ${validation.comparison.rowBoundaryVerdict}")
            appendLine("- Column mapping: ${validation.comparison.columnMappingVerdict}")
            appendLine()
        }
        appendLine("## Pinmark Negative Control")
        appendLine()
        appendLine("- Verdict: ${report.pinmarkNegativeControl.verdict}")
        appendLine("- Row boundary: ${report.pinmarkNegativeControl.rowBoundaryVerdict}")
        appendLine("- Column mapping: ${report.pinmarkNegativeControl.columnMappingVerdict}")
        appendLine("- Row errors: ${report.pinmarkNegativeControl.rowErrors.joinToString { it.code }}")
        appendLine("- Column errors: ${report.pinmarkNegativeControl.columnErrors.joinToString { it.code }}")
    }

    private fun renderValidationSummary(report: OracleV2RunReport): String = buildString {
        appendLine("HARNESS V2 VALIDATION")
        appendLine()
        report.validations.forEach { validation ->
            appendLine("${validation.caseName}:")
            appendLine("- Raw header row: ${validation.rawWorkbook.headerCandidateRow}")
            appendLine("- Raw first data row: ${validation.oracle.expectedFirstDataRowPhysical}")
            appendLine("- Dirty rows excluded: ${validation.oracle.ignoredMetadataRows.joinToString()}")
            appendLine("- Oracle mapping: ${validation.oracle.fields.map { it.value.colLetter }.joinToString("/")}")
            appendLine("- Android current output:")
            appendLine("  - header row: ${validation.androidCurrentOutput.detectedHeaderRowPhysical ?: "-"}")
            appendLine("  - first data row: ${validation.androidCurrentOutput.detectedFirstDataRowPhysical ?: "-"}")
            appendLine("  - productName mapping: ${validation.androidCurrentOutput.fieldMappings["productName"]?.physicalColLetter ?: "-"}")
            appendLine("  - dirty rows included: ${validation.comparison.rowErrors.any { it.code == RowErrorCode.DIRTY_ROW_INCLUDED.name || it.code == RowErrorCode.METADATA_ROW_INCLUDED.name }}")
            appendLine("- Verdict:")
            appendLine("  - ${validation.comparison.rowBoundaryVerdict}")
            appendLine("  - ${validation.comparison.columnMappingVerdict}")
            appendLine()
        }
        appendLine("Pinmark negative control:")
        appendLine("- ${report.pinmarkNegativeControl.rowBoundaryVerdict}")
        appendLine("- ${report.pinmarkNegativeControl.columnMappingVerdict}")
        appendLine("- row errors: ${report.pinmarkNegativeControl.rowErrors.joinToString { it.code }}")
        appendLine("- column errors: ${report.pinmarkNegativeControl.columnErrors.joinToString { it.code }}")
        appendLine()
        appendLine("Previous Drive batch: ${report.v1Status}")
        appendLine("Ready to regenerate File testing v2: ${report.validations.all { it.comparison.verdict == OracleVerdict.PASS_CONFIRMED.name }}")
    }

    private fun columnLetter(index: Int): String = CellReference.convertNumToColString(index)

    private fun String.hasLettersOrCjk(): Boolean {
        return any { it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }

    private fun String.forMarkdownCell(): String = replace("\r\n", "\n").replace("\n", "\\n").replace("|", "\\|")

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

    private data class GoldenOracleCase(
        val id: String,
        val displayName: String,
        val file: File,
        val oracle: OracleExpected
    )

    private data class OracleV2RunReport(
        val timestamp: String,
        val status: String,
        val v1Status: String,
        val validations: List<GoldenValidation>,
        val pinmarkNegativeControl: OracleComparison
    )

    private data class GoldenValidation(
        val caseName: String,
        val fileName: String,
        val sha256: String,
        val rawWorkbook: RawWorkbookInspection,
        val oracle: OracleExpected,
        val androidCurrentOutput: PlatformSnapshot,
        val comparison: OracleComparison
    )

    private data class RawWorkbookInspection(
        val fileName: String,
        val filePath: String,
        val sha256: String,
        val sheetName: String,
        val sheetCount: Int,
        val maxRow: Int,
        val maxColumn: Int,
        val mergedCells: List<String>,
        val hiddenRows: List<Int>,
        val hiddenColumns: List<String>,
        val imageColumns: List<String>,
        val rawRowsPreview: List<RawRow>,
        val headerCandidateRow: Int?,
        val metadataRows: List<Int>,
        val summaryRows: List<Int>
    )

    private data class RawRow(
        val physicalRow: Int,
        val hidden: Boolean,
        val empty: Boolean,
        val cells: List<RawCell>
    )

    private data class RawCell(
        val row: Int,
        val colIndex: Int,
        val colLetter: String,
        val value: String,
        val hiddenColumn: Boolean
    )

    private data class OracleExpected(
        val status: String,
        val expectedHeaderRowPhysical: Int,
        val expectedFirstDataRowPhysical: Int,
        val expectedLastDataRowPhysical: Int?,
        val expectedDataRowCount: Int,
        val ignoredMetadataRows: List<Int>,
        val ignoredSummaryRows: List<Int>,
        val fields: Map<String, OracleField>,
        val ignoredColumns: List<OracleIgnoredColumn>,
        val confidence: String,
        val reason: String
    )

    private data class OracleField(
        val colLetter: String,
        val reason: String
    )

    private data class OracleIgnoredColumn(
        val colLetter: String,
        val reason: String
    )

    private data class PlatformSnapshot(
        val platform: String,
        val parser: String,
        val detectedHeaderRowPhysical: Int?,
        val detectedFirstDataRowPhysical: Int?,
        val detectedLastDataRowPhysical: Int?,
        val detectedDataRowCount: Int,
        val detectedPhysicalRows: List<Int>,
        val headerMode: String,
        val normalizedHeaders: List<String>,
        val originalHeaders: List<String>,
        val headerSource: List<String>,
        val fieldMappings: Map<String, PlatformFieldMapping>,
        val generatedColumns: List<GeneratedColumn>,
        val unknownColumns: List<UnknownColumn>,
        val previewRows: List<List<String>>
    )

    private data class PlatformFieldMapping(
        val field: String,
        val platformColIndex: Int?,
        val platformColLetter: String?,
        val physicalColLetter: String?,
        val originalHeader: String?,
        val canonicalHeader: String?,
        val source: String?,
        val sampleValues: List<String>
    )

    private data class GeneratedColumn(
        val field: String,
        val colLetter: String
    )

    private data class UnknownColumn(
        val canonicalHeader: String,
        val originalHeader: String,
        val platformColLetter: String
    )

    private data class OracleComparison(
        val platform: String,
        val verdict: String,
        val rowBoundaryVerdict: String,
        val columnMappingVerdict: String,
        val rowErrors: List<RowError>,
        val columnErrors: List<ColumnError>
    )

    private data class RowError(
        val code: String,
        val physicalRow: Int?,
        val reason: String
    )

    private data class ColumnError(
        val code: String,
        val field: String,
        val expectedCol: String?,
        val actualCol: String?,
        val reason: String
    )

    private enum class OracleStatus {
        NEEDS_HUMAN_REVIEW,
        ORACLE_CONFIRMED
    }

    private enum class OracleVerdict {
        PASS_CONFIRMED,
        PASS_LIKELY,
        WARN_REVIEW,
        FAIL_SUSPECT,
        FAIL_CONFIRMED,
        ERROR,
        UNSUPPORTED,
        NOT_RUN
    }

    private enum class RowErrorCode {
        DIRTY_ROW_INCLUDED,
        HEADER_ROW_TOO_EARLY,
        HEADER_ROW_TOO_LATE,
        FIRST_DATA_ROW_WRONG,
        DATA_ROW_LOST,
        SUMMARY_ROW_INCLUDED,
        METADATA_ROW_INCLUDED,
        ROW_COUNT_MISMATCH
    }

    private enum class ColumnErrorCode {
        REQUIRED_FIELD_MISSING,
        GENERATED_BUT_REAL_COLUMN_EXISTS,
        FIELD_MISASSIGNED,
        COLUMN_SHIFT,
        DISCOUNT_MISSED,
        DISCOUNTED_PRICE_MISSED,
        TOTAL_PRICE_CONFUSED_WITH_PURCHASE_PRICE,
        PURCHASE_PRICE_CONFUSED_WITH_TOTAL_PRICE,
        BARCODE_INVALID_SAMPLE,
        PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS,
        SAME_COLUMN_ASSIGNED_TO_MULTIPLE_FIELDS,
        IMPORTANT_TEXT_HEADER_UNKNOWN
    }

    private companion object {
        const val RAW_PREVIEW_ROWS = 30
        val HARD_COLUMN_FAILURES = setOf(
            ColumnErrorCode.REQUIRED_FIELD_MISSING.name,
            ColumnErrorCode.GENERATED_BUT_REAL_COLUMN_EXISTS.name,
            ColumnErrorCode.FIELD_MISASSIGNED.name,
            ColumnErrorCode.DISCOUNT_MISSED.name,
            ColumnErrorCode.DISCOUNTED_PRICE_MISSED.name,
            ColumnErrorCode.TOTAL_PRICE_CONFUSED_WITH_PURCHASE_PRICE.name,
            ColumnErrorCode.PURCHASE_PRICE_CONFUSED_WITH_TOTAL_PRICE.name,
            ColumnErrorCode.BARCODE_INVALID_SAMPLE.name,
            ColumnErrorCode.PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS.name,
            ColumnErrorCode.SAME_COLUMN_ASSIGNED_TO_MULTIPLE_FIELDS.name
        )
    }
}

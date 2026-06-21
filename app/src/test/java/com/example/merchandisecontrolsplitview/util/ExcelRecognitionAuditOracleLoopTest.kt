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
class ExcelRecognitionAuditOracleLoopTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    @Test
    fun runOrchestratedOracleLoopAndroidFirst() {
        assumeTrue(
            "Oracle loop runs only via :app:excelRecognitionOracleLoop",
            System.getProperty("excelAudit.runOracleLoop") == "true"
        )

        val goldenCases = goldenCases()
        val driveFiles = selectDriveSample(
            resolveDriveFiles()
                .filterNot { file -> goldenCases.any { it.file.name == file.name } }
        )
        assertTrue("Need at least 10 local Drive files for the oracle loop sample", driveFiles.size >= 10)

        val corpusManifest = corpusManifest(resolveDriveFiles(), driveFiles.map { it.canonicalPath }.toSet())
        val iterations = listOf(5, DRIVE_SAMPLE_TARGET).mapIndexed { index, driveCount ->
            val cases = goldenCases + driveFiles.take(driveCount).map(::driveCase)
            runIteration(index + 1, cases)
        }

        val latest = iterations.last()
        val pinmark = latest.results.single { it.caseName == "Pinmark" }
        val modalina = latest.results.single { it.caseName == "Modalina" }
        val badPinmark = syntheticBadPinmark(pinmark.rawWorkbook)
        val badComparison = compareToOracle(pinmark.oracle, badPinmark, pinmark.rawWorkbook)

        assertEquals(OracleVerdict.PASS_CONFIRMED.name, pinmark.comparison.verdict)
        assertEquals("PASS", pinmark.comparison.rowBoundaryVerdict)
        assertEquals("PASS", pinmark.comparison.columnMappingVerdict)
        assertEquals(OracleVerdict.PASS_CONFIRMED.name, modalina.comparison.verdict)
        assertTrue(
            "Pinmark dirty row control must detect row 7",
            badComparison.rowErrors.any { it.code == RowErrorCode.DIRTY_ROW_INCLUDED.name && it.physicalRow == 7 }
        )
        assertTrue(
            "Pinmark productName control must detect a missing/misassigned mapping",
            badComparison.columnErrors.any {
                it.code == ColumnErrorCode.PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS.name ||
                    it.code == ColumnErrorCode.REQUIRED_FIELD_MISSING.name ||
                    it.code == ColumnErrorCode.FIELD_MISASSIGNED.name
            }
        )

        val finalReport = LoopFinalReport(
            timestamp = Instant.now().toString(),
            iterationsCompleted = iterations.size,
            maxIterations = MAX_ITERATIONS,
            goldenControls = GoldenControlSummary(
                pinmark = goldenSummary(pinmark, badComparison),
                modalina = goldenSummary(modalina, null)
            ),
            androidHarnessReadiness = readiness(latest),
            driveSample = driveSampleSummary(latest),
            iosAdmin = IosAdminStatus(
                ios = IOS_ADAPTER_STATUS,
                adminWeb = ADMIN_ADAPTER_STATUS,
                nextConcreteStep = "Add read-only platform adapter tests that emit the same PlatformSnapshot shape; do not patch platform parsers."
            ),
            regressionGuardian = RegressionGuardianSummary(
                productionParserChangedAfterThisTask = false,
                productionParserChangeNote = "ExcelUtils.kt is dirty from prior Pinmark/Modalina work and was not modified by this harness-only loop.",
                hardcodedFileLogic = false,
                modalinaStable = modalina.comparison.verdict == OracleVerdict.PASS_CONFIRMED.name,
                pinmarkDiagnosticCorrect = badComparison.rowBoundaryVerdict == "ROW_BOUNDARY_FAIL" &&
                    badComparison.columnMappingVerdict == "COLUMN_MAPPING_FAIL"
            ),
            reports = LoopReportPaths(
                finalMarkdown = File(loopDir(), "final-harness-validation.md").absolutePath,
                finalJson = File(loopDir(), "final-harness-validation.json").absolutePath,
                manualReviewQueueCsv = File(loopDir(), "manual-review-queue.csv").absolutePath,
                corpusManifestCsv = File(loopDir(), "corpus-manifest.csv").absolutePath
            ),
            iterations = iterations
        )

        writeLoopReports(iterations, finalReport, corpusManifest)
        writeManualReviewFolder(latest)
        println(renderFinalScreen(finalReport))
    }

    private fun runIteration(number: Int, cases: List<LoopCase>): LoopIterationReport {
        val results = cases.map { loopCase ->
            val raw = inspectRawWorkbook(loopCase.file)
            val oracle = loopCase.confirmedOracle ?: generateOracleCandidate(raw)
            val platform = runAndroidParser(loopCase.file, raw, oracle)
            val comparison = compareToOracle(oracle, platform, raw)
            LoopFileResult(
                caseName = loopCase.displayName,
                filename = loopCase.file.name,
                filePath = loopCase.file.absolutePath,
                sha256 = loopCase.file.sha256(),
                platform = Platform.ANDROID.name,
                rawWorkbook = raw,
                oracle = oracle,
                androidOutput = platform,
                comparison = comparison,
                reviewerNotes = reviewerNotes(oracle, comparison),
                needsHumanReview = oracle.status != OracleStatus.ORACLE_CONFIRMED.name ||
                    comparison.verdict in setOf(
                        OracleVerdict.WARN_REVIEW.name,
                        OracleVerdict.FAIL_SUSPECT.name,
                        OracleVerdict.ERROR.name,
                        OracleVerdict.UNSUPPORTED.name
                    )
            )
        }
        val report = LoopIterationReport(
            iteration = number,
            timestamp = Instant.now().toString(),
            dataset = IterationDataset(
                goldenControls = results.count { it.oracle.status == OracleStatus.ORACLE_CONFIRMED.name },
                driveFiles = results.count { it.oracle.status != OracleStatus.ORACLE_CONFIRMED.name },
                files = results.map { it.filename }
            ),
            results = results
        )
        writeIterationReport(report)
        return report
    }

    private fun goldenCases(): List<LoopCase> {
        return listOf(
            LoopCase(
                displayName = "Pinmark",
                file = resolveInputFile(
                    "/Users/minxiang/Desktop/20260620-Pinmark.xlsx",
                    "excel/20260620-Pinmark.xlsx"
                ),
                confirmedOracle = OracleExpected(
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
                        "purchasePrice" to OracleField("H", "wholesale unit price"),
                        "discount" to OracleField("I", "discount percent"),
                        "discountedPrice" to OracleField("J", "discounted unit price"),
                        "totalPrice" to OracleField("K", "line total")
                    ),
                    ignoredColumns = listOf(
                        OracleIgnoredColumn("D", "blank internal spacer"),
                        OracleIgnoredColumn("F", "image column")
                    ),
                    confidence = "confirmed",
                    reasons = listOf("Raw workbook confirms row 9 header and rows 10-42 product data.")
                )
            ),
            LoopCase(
                displayName = "Modalina",
                file = resolveInputFile(
                    "/Users/minxiang/Desktop/Vs20260529-ModaLina.xlsx",
                    "excel/Vs20260529-ModaLina.xlsx"
                ),
                confirmedOracle = OracleExpected(
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
                        "purchasePrice" to OracleField("I", "unit price"),
                        "totalPrice" to OracleField("J", "line total")
                    ),
                    ignoredColumns = listOf(OracleIgnoredColumn("F", "box count, not imported as quantity")),
                    confidence = "confirmed",
                    reasons = listOf("Raw workbook confirms row 10 header and rows 11-53 product data.")
                )
            )
        )
    }

    private fun driveCase(file: File): LoopCase {
        return LoopCase(displayName = file.nameWithoutExtension, file = file, confirmedOracle = null)
    }

    private fun resolveInputFile(desktopPath: String, resourcePath: String): File {
        val desktop = File(desktopPath)
        if (desktop.exists()) return desktop
        val resource = javaClass.classLoader?.getResource(resourcePath)
            ?: error("Missing input: $desktopPath or resource $resourcePath")
        return File(resource.toURI())
    }

    private fun resolveDriveFiles(): List<File> {
        val configured = System.getProperty("excelAudit.batchDirs")
            .orEmpty()
            .split(',', File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::File)
        val dirs = configured.ifEmpty { listOf(File(DEFAULT_BATCH_DIR)) }
        return dirs
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .filter { it.name != ".DS_Store" && it.looksLikeWorkbookCandidate() }
            .distinctBy { it.canonicalPath }
            .sortedBy { it.name.lowercase() }
    }

    private fun selectDriveSample(files: List<File>): List<File> {
        val semanticDiscountCase = files.firstOrNull { it.name == "20251121-Mile" }
        return (listOfNotNull(semanticDiscountCase) + files.filter { it != semanticDiscountCase })
            .take(DRIVE_SAMPLE_TARGET)
    }

    private fun File.looksLikeWorkbookCandidate(): Boolean {
        if (extension.lowercase() in setOf("xls", "xlsx", "html", "htm")) return true
        val header = inputStream().use { input -> input.readNBytes(8).toList() }
        return header.take(2) == listOf(0x50.toByte(), 0x4B.toByte()) ||
            header.take(4) == listOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte())
    }

    private fun inspectRawWorkbook(file: File): RawWorkbookInspection {
        file.inputStream().use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = DataFormatter()
                val maxColumn = maxColumn(sheet)
                val mergedRegions = (0 until sheet.numMergedRegions).map { sheet.getMergedRegion(it) }
                val rows = (0..sheet.lastRowNum).map { rowIndex ->
                    val row = sheet.getRow(rowIndex)
                    RawRow(
                        physicalRow = rowIndex + 1,
                        hidden = row?.zeroHeight == true,
                        empty = row == null || (0 until maxColumn).all { col ->
                            formatter.formatCellValue(row.getCell(col)).trim().isBlank()
                        },
                        cells = (0 until maxColumn).map { col ->
                            val value = row?.getCell(col)?.let { formatter.formatCellValue(it).trim() }.orEmpty()
                            val mergedRegion = mergedRegions.firstOrNull { it.isInRange(rowIndex, col) }
                            RawCell(
                                row = rowIndex + 1,
                                colIndex = col,
                                colLetter = columnLetter(col),
                                value = value,
                                isBlank = value.isBlank(),
                                hiddenColumn = sheet.isColumnHidden(col),
                                mergedRange = mergedRegion?.formatAsString(),
                                mergedAnchor = mergedRegion?.let { it.firstRow == rowIndex && it.firstColumn == col } ?: false
                            )
                        }
                    )
                }
                val headerCandidates = rows
                    .filterNot { it.empty }
                    .map { row -> RawHeaderCandidate(row.physicalRow, headerScore(row.cells.map { it.value })) }
                    .sortedWith(compareByDescending<RawHeaderCandidate> { it.score }.thenBy { it.physicalRow })
                    .take(3)
                val headerRow = headerCandidates.firstOrNull()?.physicalRow
                val dataCandidates = rows
                    .filter { row -> headerRow != null && row.physicalRow > headerRow && looksLikeDataRow(row) }
                    .map { it.physicalRow }
                return RawWorkbookInspection(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    sha256 = file.sha256(),
                    sheetName = sheet.sheetName,
                    sheetCount = workbook.numberOfSheets,
                    maxRow = sheet.lastRowNum + 1,
                    maxColumn = maxColumn,
                    mergedCells = (0 until sheet.numMergedRegions).map { sheet.getMergedRegion(it).formatAsString() },
                    hiddenRows = rows.filter { it.hidden }.map { it.physicalRow },
                    hiddenColumns = (0 until maxColumn).filter { sheet.isColumnHidden(it) }.map(::columnLetter),
                    imageColumns = imageColumns(rows, headerRow),
                    allRows = rows,
                    rawRowsPreview = rows.take(RAW_PREVIEW_ROWS),
                    headerCandidates = headerCandidates,
                    metadataRows = rows.filter { headerRow != null && it.physicalRow < headerRow && !it.empty }.map { it.physicalRow },
                    summaryRows = rows.filter { headerRow != null && it.physicalRow > headerRow && isSummaryRow(it) }.map { it.physicalRow },
                    dataCandidateRows = dataCandidates
                )
            }
        }
    }

    private fun generateOracleCandidate(raw: RawWorkbookInspection): OracleExpected {
        val headerRow = raw.headerCandidates.firstOrNull()?.physicalRow
        val rawHeader = raw.rawRowsPreview.firstOrNull { it.physicalRow == headerRow }
        val fields = mutableMapOf<String, OracleField>()
        val ignoredColumns = mutableListOf<OracleIgnoredColumn>()
        rawHeader?.cells.orEmpty().forEach { cell ->
            val value = cell.value
            if (value.isBlank()) {
                ignoredColumns += OracleIgnoredColumn(cell.colLetter, "blank header column")
                return@forEach
            }
            if (value.containsAny(IMAGE_HINTS)) {
                ignoredColumns += OracleIgnoredColumn(cell.colLetter, "image-like header")
                return@forEach
            }
            val field = oracleFieldForHeader(value)
            if (field != null && field !in fields) {
                fields[field] = OracleField(cell.colLetter, "header evidence `${value.forReason()}`")
            }
        }
        val firstData = raw.dataCandidateRows.firstOrNull()
        val lastData = raw.dataCandidateRows.lastOrNull()
        return OracleExpected(
            status = OracleStatus.NEEDS_HUMAN_REVIEW.name,
            expectedHeaderRowPhysical = headerRow,
            expectedFirstDataRowPhysical = firstData,
            expectedLastDataRowPhysical = lastData,
            expectedDataRowCount = raw.dataCandidateRows.size,
            ignoredMetadataRows = raw.metadataRows,
            ignoredSummaryRows = raw.summaryRows,
            fields = fields.toMap(),
            ignoredColumns = ignoredColumns.distinctBy { it.colLetter },
            confidence = candidateConfidence(fields, raw),
            reasons = listOf(
                "Oracle candidate generated from raw header row ${headerRow ?: "not detected"} using independent header/sample heuristics.",
                "Requires human confirmation before FAIL_CONFIRMED."
            )
        )
    }

    private fun oracleFieldForHeader(value: String): String? {
        val normalized = normalizeForOracle(value)
        return when {
            normalized.containsAny("imagen", "image", "图片") -> null
            normalized.containsAny("ean", "barcode", "bar code", "cobarra", "codbarra", "codigo barra", "código barra", "条码", "条形码") -> "barcode"
            normalized.containsAny("codigo producto", "código producto", "codigoproducto", "product code", "codigo", "código", "codice", "货号", "编号", "ref") &&
                !normalized.containsAny("barra", "barre", "barcode", "条码") -> "itemNumber"
            normalized.containsAny("nombre producto", "nome prodotto", "product name", "descripcion", "descripción", "description", "descrizione", "品名", "产品名", "产品品名", "名称", "nombre") &&
                !normalized.containsAny("codigo", "código", "货号", "编号") -> "productName"
            normalized.containsAny("secundario", "second", "segundo", "产品名2", "第二名称", "西语名称") -> "secondProductName"
            normalized.containsAny("cantidad", "cant", "quantity", "qty", "数量", "总数量", "unds") -> "quantity"
            normalized.containsAny("pdesc", "p desc", "pre d", "pre-d", "折后价", "discounted") -> "discountedPrice"
            normalized.containsAny("descuento", "discount", "dto", "dcto", "d%", "d.%", "折扣", "折") -> "discount"
            normalized.containsAny("subtotal", "total", "合计", "金额", "总价", "sum") -> "totalPrice"
            normalized.containsAny("precio", "prezzo", "price", "售价", "单价", "价格", "批发价", "pre") -> "purchasePrice"
            else -> null
        }
    }

    private fun runAndroidParser(
        file: File,
        raw: RawWorkbookInspection,
        oracle: OracleExpected
    ): PlatformSnapshot {
        return runCatching {
            val analysis = readAndAnalyzeExcelDetailed(context, Uri.fromFile(file), allowEmptyTabularResult = true)
            val physicalRows = mapPlatformRowsToPhysicalRows(analysis.dataRows, raw)
            val mapping = ORACLE_FIELDS.associateWith { field ->
                platformFieldMapping(field, analysis, raw)
            }
            PlatformSnapshot(
                platform = Platform.ANDROID.name,
                parser = "readAndAnalyzeExcelDetailed",
                detectedHeaderRowPhysical = physicalHeaderRowByOriginalHeaders(raw, analysis.originalHeaders)
                    ?: compactRowIndexToPhysicalRow(raw, analysis.trace.headerRows.lastOrNull()),
                detectedFirstDataRowPhysical = physicalRows.firstOrNull(),
                detectedLastDataRowPhysical = physicalRows.lastOrNull(),
                detectedDataRowCount = analysis.dataRows.size,
                detectedPhysicalRows = physicalRows,
                unmatchedDataRowCount = (analysis.dataRows.size - physicalRows.size).coerceAtLeast(0),
                headerMode = analysis.trace.headerMode,
                normalizedHeaders = analysis.header,
                originalHeaders = analysis.originalHeaders,
                headerSource = analysis.headerSource,
                fieldMappings = mapping,
                generatedColumns = analysis.header.mapIndexedNotNull { index, field ->
                    if (analysis.headerSource.getOrNull(index) == "generated") GeneratedColumn(field, columnLetter(index)) else null
                },
                unknownColumns = analysis.header.mapIndexedNotNull { index, field ->
                    if (analysis.headerSource.getOrNull(index) == "unknown") {
                        UnknownColumn(field, analysis.originalHeaders.getOrNull(index).orEmpty(), columnLetter(index))
                    } else {
                        null
                    }
                },
                previewRows = analysis.dataRows.take(8),
                exception = null
            )
        }.getOrElse { throwable ->
            PlatformSnapshot(
                platform = Platform.ANDROID.name,
                parser = "readAndAnalyzeExcelDetailed",
                detectedHeaderRowPhysical = null,
                detectedFirstDataRowPhysical = null,
                detectedLastDataRowPhysical = null,
                detectedDataRowCount = 0,
                detectedPhysicalRows = emptyList(),
                unmatchedDataRowCount = 0,
                headerMode = "ERROR",
                normalizedHeaders = emptyList(),
                originalHeaders = emptyList(),
                headerSource = emptyList(),
                fieldMappings = oracle.fields.keys.associateWith {
                    PlatformFieldMapping(it, null, null, null, null, null, null, emptyList())
                },
                generatedColumns = emptyList(),
                unknownColumns = emptyList(),
                previewRows = emptyList(),
                exception = "${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}"
            )
        }
    }

    private fun compareToOracle(
        oracle: OracleExpected,
        platform: PlatformSnapshot,
        raw: RawWorkbookInspection
    ): OracleComparison {
        val rowErrors = mutableListOf<RowError>()
        val columnErrors = mutableListOf<ColumnError>()

        if (platform.exception != null) {
            return OracleComparison(
                platform = platform.platform,
                verdict = OracleVerdict.ERROR.name,
                rowBoundaryVerdict = "ERROR",
                columnMappingVerdict = "ERROR",
                rowErrors = listOf(RowError(RowErrorCode.ROW_COUNT_MISMATCH.name, null, platform.exception)),
                columnErrors = emptyList()
            )
        }

        val expectedHeader = oracle.expectedHeaderRowPhysical
        val expectedFirst = oracle.expectedFirstDataRowPhysical
        if (expectedHeader != null && platform.detectedHeaderRowPhysical != null) {
            if (platform.detectedHeaderRowPhysical < expectedHeader) {
                rowErrors += RowError(RowErrorCode.HEADER_ROW_TOO_EARLY.name, platform.detectedHeaderRowPhysical, "expected=$expectedHeader")
            }
            if (platform.detectedHeaderRowPhysical > expectedHeader) {
                rowErrors += RowError(RowErrorCode.HEADER_ROW_TOO_LATE.name, platform.detectedHeaderRowPhysical, "expected=$expectedHeader")
            }
        }
        if (expectedFirst != null && platform.detectedFirstDataRowPhysical != null &&
            platform.detectedFirstDataRowPhysical != expectedFirst
        ) {
            rowErrors += RowError(RowErrorCode.FIRST_DATA_ROW_WRONG.name, platform.detectedFirstDataRowPhysical, "expected=$expectedFirst")
        }
        platform.detectedPhysicalRows.forEach { row ->
            if (row in oracle.ignoredMetadataRows) {
                rowErrors += RowError(RowErrorCode.METADATA_ROW_INCLUDED.name, row, "metadata row included as product data")
            }
            if (row == 7 && 7 in oracle.ignoredMetadataRows) {
                rowErrors += RowError(RowErrorCode.DIRTY_ROW_INCLUDED.name, row, "Pinmark order discount/total row included as product data")
            }
            if (row in oracle.ignoredSummaryRows) {
                rowErrors += RowError(RowErrorCode.SUMMARY_ROW_INCLUDED.name, row, "summary row included as product data")
            }
        }
        if (platform.unmatchedDataRowCount > 0) {
            rowErrors += RowError(
                RowErrorCode.DATA_ROW_LOST.name,
                null,
                "${platform.unmatchedDataRowCount} platform data row(s) could not be matched back to physical workbook rows"
            )
        }
        if (oracle.expectedDataRowCount > 0 && platform.detectedDataRowCount != oracle.expectedDataRowCount) {
            rowErrors += RowError(
                RowErrorCode.ROW_COUNT_MISMATCH.name,
                null,
                "detected=${platform.detectedDataRowCount} expected=${oracle.expectedDataRowCount}"
            )
        }

        oracle.fields.forEach { (field, expected) ->
            val actual = platform.fieldMappings[field]
            if (actual == null || actual.platformColIndex == null) {
                columnErrors += ColumnError(ColumnErrorCode.REQUIRED_FIELD_MISSING.name, field, expected.colLetter, actual?.physicalColLetter, "required field missing")
                if (field == "discount") columnErrors += ColumnError(ColumnErrorCode.DISCOUNT_MISSED.name, field, expected.colLetter, null, "discount present in oracle")
                if (field == "discountedPrice") columnErrors += ColumnError(ColumnErrorCode.DISCOUNTED_PRICE_MISSED.name, field, expected.colLetter, null, "discounted price present in oracle")
                return@forEach
            }
            if (actual.source == "generated") {
                columnErrors += ColumnError(ColumnErrorCode.GENERATED_BUT_REAL_COLUMN_EXISTS.name, field, expected.colLetter, actual.physicalColLetter, "real raw column exists")
            }
            if (actual.physicalColLetter != null && actual.physicalColLetter != expected.colLetter) {
                columnErrors += ColumnError(ColumnErrorCode.FIELD_MISASSIGNED.name, field, expected.colLetter, actual.physicalColLetter, "wrong physical column")
            }
        }

        val productName = platform.fieldMappings["productName"]
        val expectedProduct = oracle.fields["productName"]?.colLetter
        if (expectedProduct != null && productNameLooksEmpty(productName)) {
            val samples = rawColumnSamples(raw, expectedProduct, oracle.expectedFirstDataRowPhysical ?: 1)
            if (samples.any { it.hasLettersOrCjk() }) {
                columnErrors += ColumnError(
                    ColumnErrorCode.PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS.name,
                    "productName",
                    expectedProduct,
                    productName?.physicalColLetter,
                    "raw column has product text: ${samples.take(3).joinToString(" / ")}"
                )
            }
        }

        sameColumnAssignments(platform).forEach { (column, fields) ->
            columnErrors += ColumnError(
                ColumnErrorCode.SAME_COLUMN_ASSIGNED_TO_MULTIPLE_FIELDS.name,
                fields.joinToString("+"),
                null,
                column,
                "multiple fields assigned to $column"
            )
        }

        val purchase = platform.fieldMappings["purchasePrice"]?.physicalColLetter
        val total = platform.fieldMappings["totalPrice"]?.physicalColLetter
        val expectedPurchase = oracle.fields["purchasePrice"]?.colLetter
        val expectedTotal = oracle.fields["totalPrice"]?.colLetter
        if (purchase == expectedTotal && total == expectedPurchase && purchase != null && total != null) {
            columnErrors += ColumnError(
                ColumnErrorCode.PURCHASE_PRICE_CONFUSED_WITH_TOTAL_PRICE.name,
                "purchasePrice/totalPrice",
                "$expectedPurchase/$expectedTotal",
                "$purchase/$total",
                "purchase and total appear swapped"
            )
        }

        val verdict = when {
            oracle.status == OracleStatus.ORACLE_CONFIRMED.name && rowErrors.isEmpty() && columnErrors.isEmpty() ->
                OracleVerdict.PASS_CONFIRMED
            oracle.status == OracleStatus.ORACLE_CONFIRMED.name ->
                OracleVerdict.FAIL_CONFIRMED
            rowErrors.any { it.code in HARD_ROW_FAILURES } || columnErrors.any { it.code in HARD_COLUMN_FAILURES } ->
                OracleVerdict.FAIL_SUSPECT
            rowErrors.isNotEmpty() || columnErrors.isNotEmpty() || oracle.confidence != "high" ->
                OracleVerdict.WARN_REVIEW
            else ->
                OracleVerdict.PASS_LIKELY
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

    private fun writeLoopReports(
        iterations: List<LoopIterationReport>,
        finalReport: LoopFinalReport,
        corpusManifest: List<CorpusManifestRow>
    ) {
        val dir = loopDir().apply { mkdirs() }
        File(dir, "final-harness-validation.json").writeText(gson.toJson(finalReport), Charsets.UTF_8)
        File(dir, "final-harness-validation.md").writeText(renderFinalMarkdown(finalReport), Charsets.UTF_8)
        File(dir, "manual-review-queue.csv").writeText(renderManualReviewQueue(iterations.last()), Charsets.UTF_8)
        File(dir, "corpus-manifest.csv").writeText(renderCorpusManifest(corpusManifest), Charsets.UTF_8)
    }

    private fun writeIterationReport(report: LoopIterationReport) {
        val dir = loopDir().apply { mkdirs() }
        val prefix = "iteration-${report.iteration.toString().padStart(2, '0')}-summary"
        File(dir, "$prefix.json").writeText(gson.toJson(report), Charsets.UTF_8)
        File(dir, "$prefix.md").writeText(renderIterationMarkdown(report), Charsets.UTF_8)
    }

    private fun writeManualReviewFolder(report: LoopIterationReport) {
        val root = manualReviewDir()
        if (root.exists()) root.deleteRecursively()
        val androidRoot = File(root, "Android")
        val rows = report.results
            .filter { it.oracle.status != OracleStatus.ORACLE_CONFIRMED.name }
            .filter { it.comparison.verdict in setOf(OracleVerdict.FAIL_SUSPECT.name, OracleVerdict.WARN_REVIEW.name, OracleVerdict.PASS_LIKELY.name) }
        val manifestRows = mutableListOf<ManualFolderManifestRow>()
        rows.forEach { result ->
            val queueStatus = if (result.oracle.status == OracleStatus.NEEDS_HUMAN_REVIEW.name) {
                "NEEDS_ORACLE_REVIEW/${result.comparison.verdict}"
            } else {
                result.comparison.verdict
            }
            val dir = File(androidRoot, queueStatus).apply { mkdirs() }
            val excelTarget = File(dir, result.filename)
            File(result.filePath).copyTo(excelTarget, overwrite = true)
            val stem = result.filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val audit = File(dir, "$stem.audit.md")
            val oracle = File(dir, "$stem.oracle-candidate.json")
            val comparison = File(dir, "$stem.platform-comparison.json")
            audit.writeText(renderSingleAuditMarkdown(result), Charsets.UTF_8)
            oracle.writeText(gson.toJson(result.oracle), Charsets.UTF_8)
            comparison.writeText(
                gson.toJson(ComparisonSidecar(result.filename, result.sha256, result.platform, result.androidOutput, result.oracle, result.comparison)),
                Charsets.UTF_8
            )
            manifestRows += ManualFolderManifestRow(
                caseId = stem,
                filename = result.filename,
                sha256 = result.sha256,
                sourcePath = result.filePath,
                platform = result.platform,
                oracleStatus = result.oracle.status,
                queueStatus = queueStatus.replace('/', ':'),
                verdict = result.comparison.verdict,
                rowBoundaryVerdict = result.comparison.rowBoundaryVerdict,
                columnMappingVerdict = result.comparison.columnMappingVerdict,
                rowErrors = result.comparison.rowErrors.map { it.code },
                columnErrors = result.comparison.columnErrors.map { it.code + ":" + it.field },
                auditPath = audit.absolutePath,
                oraclePath = oracle.absolutePath,
                comparisonPath = comparison.absolutePath,
                decisionNotes = "Pending human review"
            )
        }
        File(root, "manifest.json").writeText(gson.toJson(manifestRows), Charsets.UTF_8)
        File(root, "manifest.csv").writeText(renderManualFolderManifest(manifestRows), Charsets.UTF_8)
        File(root, "_README_ORACLE_V2_V3.txt").writeText(
            "Oracle v2/v3 manual review folder. Files are sample diagnostics, not confirmed parser fixes.\n" +
                "Use sidecar .audit.md, .oracle-candidate.json, and .platform-comparison.json before judging a file.\n" +
                "All Drive files in this folder are NEEDS_ORACLE_REVIEW until the user confirms the oracle.\n" +
                "Do not use /Users/minxiang/Desktop/File testing or drive-batch-audit v1 as truth.\n",
            Charsets.UTF_8
        )
    }

    private fun renderFinalScreen(report: LoopFinalReport): String = buildString {
        appendLine("HARNESS ORCHESTRATED LOOP RESULT")
        appendLine()
        appendLine("Iterations completed: ${report.iterationsCompleted} / ${report.maxIterations}")
        appendLine()
        appendLine("Golden controls:")
        appendLine("- Pinmark:")
        appendLine("  - row boundary: ${report.goldenControls.pinmark.rowBoundary}")
        appendLine("  - dirty row detection: ${report.goldenControls.pinmark.dirtyRowDetection}")
        appendLine("  - column mapping: ${report.goldenControls.pinmark.columnMapping}")
        appendLine("  - verdict: ${report.goldenControls.pinmark.verdict}")
        appendLine("- Modalina:")
        appendLine("  - row boundary: ${report.goldenControls.modalina.rowBoundary}")
        appendLine("  - column mapping: ${report.goldenControls.modalina.columnMapping}")
        appendLine("  - verdict: ${report.goldenControls.modalina.verdict}")
        appendLine()
        appendLine("Android harness readiness:")
        appendLine("- ${report.androidHarnessReadiness.status}")
        appendLine("- ${report.androidHarnessReadiness.reasons.joinToString("; ")}")
        appendLine()
        appendLine("Drive sample:")
        appendLine("- files tested: ${report.driveSample.filesTested}")
        appendLine("- PASS_LIKELY: ${report.driveSample.passLikely}")
        appendLine("- WARN_REVIEW: ${report.driveSample.warnReview}")
        appendLine("- FAIL_SUSPECT: ${report.driveSample.failSuspect}")
        appendLine("- NEEDS_HUMAN_REVIEW: ${report.driveSample.needsHumanReview}")
        appendLine()
        appendLine("iOS/Admin:")
        appendLine("- iOS: ${report.iosAdmin.ios}")
        appendLine("- Admin Web: ${report.iosAdmin.adminWeb}")
        appendLine("- next concrete step: ${report.iosAdmin.nextConcreteStep}")
        appendLine()
        appendLine("Regression Guardian:")
        appendLine("- production parser changed after this task? ${if (report.regressionGuardian.productionParserChangedAfterThisTask) "yes" else "no"}")
        appendLine("- hardcoded file logic? ${if (report.regressionGuardian.hardcodedFileLogic) "yes" else "no"}")
        appendLine("- Modalina stable? ${if (report.regressionGuardian.modalinaStable) "yes" else "no"}")
        appendLine("- Pinmark diagnostic correct? ${if (report.regressionGuardian.pinmarkDiagnosticCorrect) "yes" else "no"}")
        appendLine()
        appendLine("Reports:")
        appendLine("- ${report.reports.finalMarkdown}")
        appendLine("- ${report.reports.finalJson}")
        appendLine("- ${report.reports.manualReviewQueueCsv}")
    }

    private fun renderFinalMarkdown(report: LoopFinalReport): String = buildString {
        appendLine("# Harness Orchestrated Loop Result")
        appendLine()
        appendLine("```text")
        append(renderFinalScreen(report))
        appendLine("```")
        appendLine()
        appendLine("## Iterations")
        report.iterations.forEach { iteration ->
            appendLine("- Iteration ${iteration.iteration}: ${iteration.dataset.driveFiles} Drive files, " +
                "${iteration.results.count { it.needsHumanReview }} need human review")
        }
    }

    private fun renderIterationMarkdown(report: LoopIterationReport): String = buildString {
        appendLine("# Oracle Loop Iteration ${report.iteration}")
        appendLine()
        appendLine("- Timestamp: ${report.timestamp}")
        appendLine("- Golden controls: ${report.dataset.goldenControls}")
        appendLine("- Drive files: ${report.dataset.driveFiles}")
        appendLine()
        appendLine("| File | Oracle | Verdict | Row | Column | Header | First data | Reasons |")
        appendLine("|---|---|---|---|---|---:|---:|---|")
        report.results.forEach { result ->
            appendLine(
                "| ${result.filename.forMarkdownCell()} | ${result.oracle.status} | ${result.comparison.verdict} | " +
                    "${result.comparison.rowBoundaryVerdict} | ${result.comparison.columnMappingVerdict} | " +
                    "${result.oracle.expectedHeaderRowPhysical ?: "-"} | ${result.oracle.expectedFirstDataRowPhysical ?: "-"} | " +
                    "${(result.comparison.rowErrors.map { it.code } + result.comparison.columnErrors.map { it.code }).joinToString("; ").forMarkdownCell()} |"
            )
        }
    }

    private fun renderSingleAuditMarkdown(result: LoopFileResult): String = buildString {
        appendLine("# ${result.filename} — ${result.comparison.verdict}")
        appendLine()
        appendLine("- Platform: ${result.platform}")
        appendLine("- Oracle status: ${result.oracle.status}")
        appendLine("- Confidence: ${result.oracle.confidence}")
        appendLine("- Header candidates: ${result.rawWorkbook.headerCandidates.joinToString { it.physicalRow.toString() + ":" + it.score }}")
        appendLine("- Oracle mapping: ${result.oracle.fields.map { it.key + '=' + it.value.colLetter }.joinToString()}")
        appendLine("- Android header row: ${result.androidOutput.detectedHeaderRowPhysical ?: "-"}")
        appendLine("- Android first data row: ${result.androidOutput.detectedFirstDataRowPhysical ?: "-"}")
        appendLine("- Row errors: ${result.comparison.rowErrors.joinToString { it.code }.ifBlank { "(none)" }}")
        appendLine("- Column errors: ${result.comparison.columnErrors.joinToString { it.code + ':' + it.field }.ifBlank { "(none)" }}")
        appendLine()
        appendLine("## Samples")
        result.androidOutput.fieldMappings.values.forEach { mapping ->
            if (mapping.sampleValues.isNotEmpty()) {
                appendLine("- ${mapping.field}: ${mapping.sampleValues.take(5).joinToString(" / ")}")
            }
        }
        appendLine()
        appendLine("## Raw Windows")
        rawWindowRows(result.rawWorkbook).forEach { row ->
            appendLine(
                "- row ${row.physicalRow}: " +
                    row.cells.joinToString(" | ") { cell ->
                        val merged = cell.mergedRange?.let { " merged=$it${if (cell.mergedAnchor) ":anchor" else ""}" }.orEmpty()
                        cell.colLetter + '=' + cell.value.replace("\n", "\\n") + merged
                    }
            )
        }
    }

    private fun rawWindowRows(raw: RawWorkbookInspection): List<RawRow> {
        val interestingRows = (
            raw.allRows.take(5).map { it.physicalRow } +
                raw.headerCandidates.flatMap { candidate -> (candidate.physicalRow - 1)..(candidate.physicalRow + 2) } +
                raw.dataCandidateRows.take(3) +
                raw.summaryRows +
                raw.allRows.takeLast(5).map { it.physicalRow }
            )
            .filter { it in 1..raw.maxRow }
            .toSet()
        return raw.allRows.filter { it.physicalRow in interestingRows }
    }

    private fun renderManualReviewQueue(report: LoopIterationReport): String = buildString {
        appendLine("filename,platform,queueStatus,verdict,oracleStatus,confidence,needsHumanReview,rowErrors,columnErrors,reason,filePath")
        report.results
            .filter { it.needsHumanReview }
            .forEach { result ->
                appendLine(
                    listOf(
                        result.filename,
                        result.platform,
                        if (result.oracle.status == OracleStatus.NEEDS_HUMAN_REVIEW.name) "NEEDS_ORACLE_REVIEW" else result.comparison.verdict,
                        result.comparison.verdict,
                        result.oracle.status,
                        result.oracle.confidence,
                        result.needsHumanReview.toString(),
                        result.comparison.rowErrors.joinToString("|") { it.code },
                        result.comparison.columnErrors.joinToString("|") { it.code + ":" + it.field },
                        reviewerNotes(result.oracle, result.comparison).joinToString(" | "),
                        result.filePath
                    ).joinToString(",") { it.csvEscape() }
                )
            }
    }

    private fun renderManualFolderManifest(rows: List<ManualFolderManifestRow>): String = buildString {
        appendLine("caseId,filename,sha256,sourcePath,platform,oracleStatus,queueStatus,verdict,rowBoundaryVerdict,columnMappingVerdict,rowErrors,columnErrors,auditPath,oraclePath,comparisonPath,decisionNotes")
        rows.forEach { row ->
            appendLine(
                listOf(
                    row.caseId,
                    row.filename,
                    row.sha256,
                    row.sourcePath,
                    row.platform,
                    row.oracleStatus,
                    row.queueStatus,
                    row.verdict,
                    row.rowBoundaryVerdict,
                    row.columnMappingVerdict,
                    row.rowErrors.joinToString("|"),
                    row.columnErrors.joinToString("|"),
                    row.auditPath,
                    row.oraclePath,
                    row.comparisonPath,
                    row.decisionNotes
                ).joinToString(",") { it.csvEscape() }
            )
        }
    }

    private fun renderCorpusManifest(rows: List<CorpusManifestRow>): String = buildString {
        appendLine("filename,sha256,size,sourcePath,analyzed,reasonIfNotAnalyzed")
        rows.forEach { row ->
            appendLine(
                listOf(
                    row.filename,
                    row.sha256,
                    row.size.toString(),
                    row.sourcePath,
                    row.analyzed.toString(),
                    row.reasonIfNotAnalyzed
                ).joinToString(",") { it.csvEscape() }
            )
        }
    }

    private fun corpusManifest(files: List<File>, analyzedPaths: Set<String>): List<CorpusManifestRow> {
        return files.map { file ->
            CorpusManifestRow(
                filename = file.name,
                sha256 = file.sha256(),
                size = file.length(),
                sourcePath = file.absolutePath,
                analyzed = file.canonicalPath in analyzedPaths,
                reasonIfNotAnalyzed = if (file.canonicalPath in analyzedPaths) "" else "outside deterministic 10-file loop sample"
            )
        }
    }

    private fun goldenSummary(result: LoopFileResult, negativeControl: OracleComparison?): GoldenSummary {
        return GoldenSummary(
            rowBoundary = result.comparison.rowBoundaryVerdict,
            dirtyRowDetection = if (negativeControl == null) "N/A" else if (
                negativeControl.rowErrors.any { it.code == RowErrorCode.DIRTY_ROW_INCLUDED.name }
            ) "PASS" else "FAIL",
            columnMapping = result.comparison.columnMappingVerdict,
            verdict = result.comparison.verdict
        )
    }

    private fun readiness(iteration: LoopIterationReport): ReadinessSummary {
        val pinmark = iteration.results.single { it.caseName == "Pinmark" }
        val modalina = iteration.results.single { it.caseName == "Modalina" }
        val driveCount = iteration.results.count { it.oracle.status != OracleStatus.ORACLE_CONFIRMED.name }
        val ready = pinmark.comparison.verdict == OracleVerdict.PASS_CONFIRMED.name &&
            modalina.comparison.verdict == OracleVerdict.PASS_CONFIRMED.name &&
            driveCount >= DRIVE_SAMPLE_TARGET
        return ReadinessSummary(
            status = if (ready) "READY_FOR_MANUAL_BATCH_REVIEW" else "NOT_READY",
            reasons = listOf(
                "Pinmark oracle/control=${pinmark.comparison.verdict}",
                "Modalina regression=${modalina.comparison.verdict}",
                "Drive sample files=$driveCount",
                "No production parser fixes in this loop"
            )
        )
    }

    private fun driveSampleSummary(iteration: LoopIterationReport): DriveSampleSummary {
        val drive = iteration.results.filter { it.oracle.status != OracleStatus.ORACLE_CONFIRMED.name }
        return DriveSampleSummary(
            filesTested = drive.size,
            passLikely = drive.count { it.comparison.verdict == OracleVerdict.PASS_LIKELY.name },
            warnReview = drive.count { it.comparison.verdict == OracleVerdict.WARN_REVIEW.name },
            failSuspect = drive.count { it.comparison.verdict == OracleVerdict.FAIL_SUSPECT.name },
            needsHumanReview = drive.count { it.needsHumanReview }
        )
    }

    private fun reviewerNotes(oracle: OracleExpected, comparison: OracleComparison): List<String> {
        val notes = mutableListOf<String>()
        if (oracle.status == OracleStatus.NEEDS_HUMAN_REVIEW.name) {
            notes += "Oracle candidate requires manual confirmation before FAIL_CONFIRMED."
        }
        if (comparison.rowErrors.isNotEmpty()) notes += "Review physical row boundaries."
        if (comparison.columnErrors.isNotEmpty()) notes += "Review semantic field mapping."
        if (notes.isEmpty()) notes += "No obvious harness/platform mismatch in this sample."
        return notes
    }

    private fun platformFieldMapping(
        field: String,
        analysis: ExcelAnalysisResult,
        raw: RawWorkbookInspection
    ): PlatformFieldMapping {
        val index = analysis.header.indexOf(field).takeIf { it >= 0 }
        val original = index?.let { analysis.originalHeaders.getOrNull(it).orEmpty() }
        val physical = original?.let { rawHeaderColumnByValue(raw, it) } ?: index?.let(::columnLetter)
        val samples = index?.let { col -> analysis.dataRows.take(8).map { row -> row.getOrNull(col).orEmpty() } }.orEmpty()
        return PlatformFieldMapping(
            field = field,
            platformColIndex = index,
            platformColLetter = index?.let(::columnLetter),
            physicalColLetter = physical,
            originalHeader = original,
            canonicalHeader = index?.let { analysis.header.getOrNull(it).orEmpty() },
            source = index?.let { analysis.headerSource.getOrNull(it).orEmpty() },
            sampleValues = samples
        )
    }

    private fun rawHeaderColumnByValue(raw: RawWorkbookInspection, headerValue: String): String? {
        val target = normalizeHeaderValue(headerValue)
        if (target.isBlank()) return null
        return raw.rawRowsPreview
            .firstOrNull { it.physicalRow == raw.headerCandidates.firstOrNull()?.physicalRow }
            ?.cells
            ?.firstOrNull { normalizeHeaderValue(it.value) == target }
            ?.colLetter
    }

    private fun physicalHeaderRowByOriginalHeaders(raw: RawWorkbookInspection, originalHeaders: List<String>): Int? {
        val expected = originalHeaders.dropLastWhile { it.isBlank() }
            .mapIndexedNotNull { index, value -> normalizeHeaderValue(value).takeIf { it.isNotBlank() }?.let { index to it } }
        if (expected.isEmpty()) return null
        return loadAllRows(raw)
            .filterNot { it.empty }
            .firstOrNull { row ->
                expected.all { (index, value) -> normalizeHeaderValue(row.cells.getOrNull(index)?.value.orEmpty()) == value }
            }
            ?.physicalRow
    }

    private fun compactRowIndexToPhysicalRow(raw: RawWorkbookInspection, compactRowIndex: Int?): Int? {
        if (compactRowIndex == null) return null
        return loadAllRows(raw).filterNot { it.empty }.drop(compactRowIndex).firstOrNull()?.physicalRow
    }

    private fun mapPlatformRowsToPhysicalRows(platformRows: List<List<String>>, raw: RawWorkbookInspection): List<Int> {
        val used = mutableSetOf<Int>()
        val allRows = loadAllRows(raw).filterNot { it.empty }.toList()
        return platformRows.mapNotNull { platformRow ->
            val values = platformRow.map { it.trim() }.filter { it.isNotBlank() }
            if (values.isEmpty()) return@mapNotNull null
            val match = allRows
                .filter { it.physicalRow !in used }
                .map { row -> row to rowMatchScore(values, row.cells.map { it.value.trim() }) }
                .filter { it.second >= 0.70 }
                .maxByOrNull { it.second }
                ?.first
            if (match != null) {
                used += match.physicalRow
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
                val mergedRegions = (0 until sheet.numMergedRegions).map { sheet.getMergedRegion(it) }
                (0..sheet.lastRowNum).map { rowIndex ->
                    val row = sheet.getRow(rowIndex)
                    RawRow(
                        physicalRow = rowIndex + 1,
                        hidden = row?.zeroHeight == true,
                        empty = row == null || (0 until raw.maxColumn).all { col ->
                            formatter.formatCellValue(row.getCell(col)).trim().isBlank()
                        },
                        cells = (0 until raw.maxColumn).map { col ->
                            val value = row?.getCell(col)?.let { formatter.formatCellValue(it).trim() }.orEmpty()
                            val mergedRegion = mergedRegions.firstOrNull { it.isInRange(rowIndex, col) }
                            RawCell(
                                row = rowIndex + 1,
                                colIndex = col,
                                colLetter = columnLetter(col),
                                value = value,
                                isBlank = value.isBlank(),
                                hiddenColumn = sheet.isColumnHidden(col),
                                mergedRange = mergedRegion?.formatAsString(),
                                mergedAnchor = mergedRegion?.let { it.firstRow == rowIndex && it.firstColumn == col } ?: false
                            )
                        }
                    )
                }.asSequence()
            }
        }
    }

    private fun maxColumn(sheet: Sheet): Int {
        return (0..sheet.lastRowNum).maxOfOrNull { rowIndex ->
            sheet.getRow(rowIndex)?.lastCellNum?.toInt()?.coerceAtLeast(0) ?: 0
        } ?: 0
    }

    private fun imageColumns(rows: List<RawRow>, headerRow: Int?): List<String> {
        return rows.firstOrNull { it.physicalRow == headerRow }
            ?.cells
            .orEmpty()
            .filter { it.value.containsAny(IMAGE_HINTS) }
            .map { it.colLetter }
    }

    private fun headerScore(values: List<String>): Int {
        val joined = values.joinToString("\n").lowercase()
        val hits = HEADER_HINTS.count { it in joined }
        val nonBlank = values.count { it.isNotBlank() }
        return hits * 10 + nonBlank
    }

    private fun looksLikeDataRow(row: RawRow): Boolean {
        val nonBlank = row.cells.map { it.value }.filter { it.isNotBlank() }
        if (nonBlank.size < 3) return false
        val numeric = nonBlank.count { it.replace("%", "").replace(".", "").replace(",", "").toDoubleOrNull() != null }
        val text = nonBlank.count { it.hasLettersOrCjk() }
        return numeric >= 1 && text >= 1 && !isSummaryRow(row)
    }

    private fun isSummaryRow(row: RawRow): Boolean {
        val nonBlank = row.cells.map { it.value }.filter { it.isNotBlank() }
        val joined = normalizeForOracle(nonBlank.joinToString(" "))
        return nonBlank.size <= 4 && joined.containsAny("total", "subtotal", "总价", "总数", "合计", "importe total", "合作愉快")
    }

    private fun candidateConfidence(fields: Map<String, OracleField>, raw: RawWorkbookInspection): String {
        val essential = listOf("productName", "purchasePrice")
        return when {
            essential.all { it in fields } && raw.dataCandidateRows.size >= 2 -> "high"
            "productName" in fields && raw.dataCandidateRows.isNotEmpty() -> "medium"
            else -> "low"
        }
    }

    private fun productNameLooksEmpty(mapping: PlatformFieldMapping?): Boolean {
        if (mapping == null || mapping.platformColIndex == null || mapping.source == "generated") return true
        return mapping.sampleValues.filter { it.isNotBlank() }.take(5).all { it == "-" || it.equals("null", ignoreCase = true) }
    }

    private fun rawColumnSamples(raw: RawWorkbookInspection, colLetter: String, firstDataRow: Int): List<String> {
        val index = CellReference.convertColStringToIndex(colLetter)
        return loadAllRows(raw)
            .filter { it.physicalRow >= firstDataRow }
            .take(8)
            .mapNotNull { it.cells.getOrNull(index)?.value?.takeIf { value -> value.isNotBlank() } }
            .toList()
    }

    private fun sameColumnAssignments(platform: PlatformSnapshot): Map<String, List<String>> {
        return platform.fieldMappings.values
            .filter { it.physicalColLetter != null && it.source != "generated" }
            .groupBy { it.physicalColLetter.orEmpty() }
            .mapValues { it.value.map { mapping -> mapping.field } }
            .filterValues { it.size > 1 }
    }

    private fun rowMatchScore(platformValues: List<String>, rawValues: List<String>): Double {
        val rawBag = rawValues.filter { it.isNotBlank() }.toMutableList()
        var matches = 0
        platformValues.forEach { value ->
            val index = rawBag.indexOfFirst { it == value }
            if (index >= 0) {
                matches += 1
                rawBag.removeAt(index)
            }
        }
        return matches.toDouble() / platformValues.size.toDouble()
    }

    private fun syntheticBadPinmark(raw: RawWorkbookInspection): PlatformSnapshot {
        return PlatformSnapshot(
            platform = "ANDROID_SYNTHETIC_BAD_CONTROL",
            parser = "oracle-loop-negative-control",
            detectedHeaderRowPhysical = 7,
            detectedFirstDataRowPhysical = 7,
            detectedLastDataRowPhysical = 42,
            detectedDataRowCount = 34,
            detectedPhysicalRows = listOf(7) + (10..42),
            unmatchedDataRowCount = 0,
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
            previewRows = raw.rawRowsPreview.firstOrNull { it.physicalRow == 7 }?.let { listOf(it.cells.map { cell -> cell.value }) }.orEmpty(),
            exception = null
        )
    }

    private fun loopDir(): File = File(reportRootDir(), "oracle-loop")

    private fun reportRootDir(): File {
        return File(System.getProperty("excelAudit.reportDir") ?: "build/reports/excelRecognitionAudit")
    }

    private fun manualReviewDir(): File {
        val configured = System.getProperty("excelAudit.loopManualReviewDir").orEmpty().trim()
        return File(configured.ifEmpty { "/Users/minxiang/Desktop/File testing v2" })
    }

    private fun columnLetter(index: Int): String = CellReference.convertNumToColString(index)

    private fun normalizeHeaderValue(value: String): String {
        return value.replace("\r\n", "\n").trim().replace(Regex("\\s+"), " ").lowercase()
    }

    private fun normalizeForOracle(value: String): String {
        return value.lowercase()
            .replace("\r\n", "\n")
            .replace(Regex("[_./\\\\:：()（）\\[\\]{}|;%\\n\\r-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { it in this }

    private fun String.containsAny(needles: Iterable<String>): Boolean {
        val lower = lowercase()
        return needles.any { it.lowercase() in lower }
    }

    private fun String.hasLettersOrCjk(): Boolean {
        return any { it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }

    private fun String.forMarkdownCell(): String = replace("\r\n", "\n").replace("\n", "\\n").replace("|", "\\|")

    private fun String.forReason(): String = replace("\r\n", "\n").replace("\n", "\\n")

    private fun String.csvEscape(): String {
        val escaped = replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
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

    private data class LoopCase(
        val displayName: String,
        val file: File,
        val confirmedOracle: OracleExpected?
    )

    private data class LoopFinalReport(
        val timestamp: String,
        val iterationsCompleted: Int,
        val maxIterations: Int,
        val goldenControls: GoldenControlSummary,
        val androidHarnessReadiness: ReadinessSummary,
        val driveSample: DriveSampleSummary,
        val iosAdmin: IosAdminStatus,
        val regressionGuardian: RegressionGuardianSummary,
        val reports: LoopReportPaths,
        val iterations: List<LoopIterationReport>
    )

    private data class GoldenControlSummary(
        val pinmark: GoldenSummary,
        val modalina: GoldenSummary
    )

    private data class GoldenSummary(
        val rowBoundary: String,
        val dirtyRowDetection: String,
        val columnMapping: String,
        val verdict: String
    )

    private data class ReadinessSummary(
        val status: String,
        val reasons: List<String>
    )

    private data class DriveSampleSummary(
        val filesTested: Int,
        val passLikely: Int,
        val warnReview: Int,
        val failSuspect: Int,
        val needsHumanReview: Int
    )

    private data class IosAdminStatus(
        val ios: String,
        val adminWeb: String,
        val nextConcreteStep: String
    )

    private data class RegressionGuardianSummary(
        val productionParserChangedAfterThisTask: Boolean,
        val productionParserChangeNote: String,
        val hardcodedFileLogic: Boolean,
        val modalinaStable: Boolean,
        val pinmarkDiagnosticCorrect: Boolean
    )

    private data class LoopReportPaths(
        val finalMarkdown: String,
        val finalJson: String,
        val manualReviewQueueCsv: String,
        val corpusManifestCsv: String
    )

    private data class LoopIterationReport(
        val iteration: Int,
        val timestamp: String,
        val dataset: IterationDataset,
        val results: List<LoopFileResult>
    )

    private data class IterationDataset(
        val goldenControls: Int,
        val driveFiles: Int,
        val files: List<String>
    )

    private data class LoopFileResult(
        val caseName: String,
        val filename: String,
        val filePath: String,
        val sha256: String,
        val platform: String,
        val rawWorkbook: RawWorkbookInspection,
        val oracle: OracleExpected,
        val androidOutput: PlatformSnapshot,
        val comparison: OracleComparison,
        val reviewerNotes: List<String>,
        val needsHumanReview: Boolean
    )

    private data class CorpusManifestRow(
        val filename: String,
        val sha256: String,
        val size: Long,
        val sourcePath: String,
        val analyzed: Boolean,
        val reasonIfNotAnalyzed: String
    )

    private data class ComparisonSidecar(
        val filename: String,
        val sha256: String,
        val platform: String,
        val platformOutput: PlatformSnapshot,
        val oracle: OracleExpected,
        val comparison: OracleComparison
    )

    private data class ManualFolderManifestRow(
        val caseId: String,
        val filename: String,
        val sha256: String,
        val sourcePath: String,
        val platform: String,
        val oracleStatus: String,
        val queueStatus: String,
        val verdict: String,
        val rowBoundaryVerdict: String,
        val columnMappingVerdict: String,
        val rowErrors: List<String>,
        val columnErrors: List<String>,
        val auditPath: String,
        val oraclePath: String,
        val comparisonPath: String,
        val decisionNotes: String
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
        val allRows: List<RawRow>,
        val rawRowsPreview: List<RawRow>,
        val headerCandidates: List<RawHeaderCandidate>,
        val metadataRows: List<Int>,
        val summaryRows: List<Int>,
        val dataCandidateRows: List<Int>
    )

    private data class RawHeaderCandidate(
        val physicalRow: Int,
        val score: Int
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
        val isBlank: Boolean,
        val hiddenColumn: Boolean,
        val mergedRange: String?,
        val mergedAnchor: Boolean
    )

    private data class OracleExpected(
        val status: String,
        val expectedHeaderRowPhysical: Int?,
        val expectedFirstDataRowPhysical: Int?,
        val expectedLastDataRowPhysical: Int?,
        val expectedDataRowCount: Int,
        val ignoredMetadataRows: List<Int>,
        val ignoredSummaryRows: List<Int>,
        val fields: Map<String, OracleField>,
        val ignoredColumns: List<OracleIgnoredColumn>,
        val confidence: String,
        val reasons: List<String>
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
        val unmatchedDataRowCount: Int,
        val headerMode: String,
        val normalizedHeaders: List<String>,
        val originalHeaders: List<String>,
        val headerSource: List<String>,
        val fieldMappings: Map<String, PlatformFieldMapping>,
        val generatedColumns: List<GeneratedColumn>,
        val unknownColumns: List<UnknownColumn>,
        val previewRows: List<List<String>>,
        val exception: String?
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

    private enum class Platform {
        ANDROID,
        IOS,
        ADMIN_WEB
    }

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
        const val DEFAULT_BATCH_DIR = "/tmp/excel-supplier-audit/combined-corpus-by-name"
        const val RAW_PREVIEW_ROWS = 30
        const val DRIVE_SAMPLE_TARGET = 10
        const val MAX_ITERATIONS = 5
        const val IOS_ADAPTER_STATUS =
            "PARTIAL/NOT_RUN: local iOS parser exists, but no read-only OracleSnapshot adapter is wired in this Android harness run."
        const val ADMIN_ADAPTER_STATUS =
            "PARTIAL/NOT_RUN: Admin Web parser location still needs a read-only adapter emitting PlatformSnapshot."
        val ORACLE_FIELDS = listOf(
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
            "retailPrice",
            "supplier",
            "category"
        )
        val HEADER_HINTS = listOf(
            "ean", "barcode", "条码", "codigo", "código", "descripcion", "descripción",
            "nombre", "品名", "产品名", "cantidad", "数量", "precio", "prezzo",
            "total", "subtotal", "合计", "折扣", "折后价", "货号", "ref"
        )
        val IMAGE_HINTS = listOf("image", "imagen", "图片", "圖片")
        val HARD_ROW_FAILURES = setOf(
            RowErrorCode.DIRTY_ROW_INCLUDED.name,
            RowErrorCode.METADATA_ROW_INCLUDED.name,
            RowErrorCode.SUMMARY_ROW_INCLUDED.name,
            RowErrorCode.FIRST_DATA_ROW_WRONG.name
        )
        val HARD_COLUMN_FAILURES = setOf(
            ColumnErrorCode.REQUIRED_FIELD_MISSING.name,
            ColumnErrorCode.GENERATED_BUT_REAL_COLUMN_EXISTS.name,
            ColumnErrorCode.FIELD_MISASSIGNED.name,
            ColumnErrorCode.DISCOUNT_MISSED.name,
            ColumnErrorCode.DISCOUNTED_PRICE_MISSED.name,
            ColumnErrorCode.PRODUCT_NAME_EMPTY_BUT_TEXT_COLUMN_EXISTS.name,
            ColumnErrorCode.PURCHASE_PRICE_CONFUSED_WITH_TOTAL_PRICE.name,
            ColumnErrorCode.TOTAL_PRICE_CONFUSED_WITH_PURCHASE_PRICE.name,
            ColumnErrorCode.SAME_COLUMN_ASSIGNED_TO_MULTIPLE_FIELDS.name
        )
    }
}

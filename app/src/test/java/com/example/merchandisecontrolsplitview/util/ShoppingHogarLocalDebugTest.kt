package com.example.merchandisecontrolsplitview.util

import android.net.Uri
import java.io.File
import org.apache.poi.ss.usermodel.DataFormatter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShoppingHogarLocalDebugTest {

    @Test
    fun dumpShoppingHogarCurrentMapping() {
        val context = RuntimeEnvironment.getApplication()
        val file = File("/Users/minxiang/Downloads/20260404-Shopping Hogar.xls")
        assertTrue("Fixture locale mancante: ${file.absolutePath}", file.exists())

        val bytes = file.readBytes()
        createWorkbookWithLegacyFallback(bytes).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val fmt = DataFormatter()
            println("MERGED_START")
            for (i in 0 until sheet.numMergedRegions) {
                val region = sheet.getMergedRegion(i)
                if (region.firstRow <= 30 && region.lastRow >= 22) {
                    println("MERGE[$i]=r${region.firstRow}-${region.lastRow},c${region.firstColumn}-${region.lastColumn}")
                }
            }
            println("MERGED_END")
            println("RAW_ROWS_START")
            for (r in 0..minOf(sheet.lastRowNum, 35)) {
                val row = sheet.getRow(r)
                if (row == null) {
                    println("RAW[$r]=<null>")
                    continue
                }
                val last = row.lastCellNum.toInt().coerceAtLeast(0)
                val values = (0 until last).map { c -> fmt.formatCellValue(row.getCell(c)).trim() }
                println("RAW[$r]=$values")
                if (r in 22..31) {
                    val indexed = values.withIndex()
                        .filter { it.value.isNotBlank() }
                        .joinToString { "${it.index}='${it.value}'" }
                    println("RAW_INDEXED[$r]=$indexed")
                }
            }
            println("RAW_ROWS_END")
        }

        val (header, rows, headerSource) = readAndAnalyzeExcel(context, Uri.fromFile(file))
        println("HEADER=$header")
        println("HEADER_SOURCE=$headerSource")
        println("ROW_COUNT=${rows.size}")
        rows.take(5).forEachIndexed { index, row ->
            println("ROW[$index]=$row")
        }

        createWorkbookWithLegacyFallback(bytes).use { workbook ->
            val analysis = analyzePoiSheetDetailed(context, workbook.getSheetAt(0))
            println("TRACE_HEADER_MODE=${analysis.trace.headerMode}")
            println("TRACE_DATA_ROW_IDX=${analysis.trace.dataRowIdx}")
            println("TRACE_HEADER_ROWS=${analysis.trace.headerRows}")
            analysis.header.forEachIndexed { index, value ->
                println("TRACE_HEADER[$index]=$value source=${analysis.headerSource.getOrNull(index)}")
            }
            analysis.trace.fieldDecisions.forEach { decision ->
                println(
                    "TRACE_FIELD=${decision.field} selected=${decision.selectedColumnIndex} " +
                        "confidence=${decision.confidence} reason=${decision.reason}"
                )
                decision.candidates.forEach { candidate ->
                    println(
                        "TRACE_CANDIDATE=${decision.field} col=${candidate.columnIndex} " +
                            "score=${"%.3f".format(candidate.score)} reasons=${candidate.reasons}"
                    )
                }
            }
        }
    }
}

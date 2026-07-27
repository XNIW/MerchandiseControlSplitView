package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.R
import java.io.File
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ErrorExporterCatalogTextTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `catalog text row errors never retain prohibited raw values`() {
        val prohibitedValues = listOf(
            "hidden\u200Bvalue",
            "bidi\u202Evalue",
            "nul\u0000value"
        )

        prohibitedValues.forEach { raw ->
            val error = catalogTextRowError(
                context = context,
                rowNumber = 2,
                row = linkedMapOf(
                    "barcode" to "SAFE-001",
                    "productName" to raw
                ),
                field = CatalogTextField.PRODUCT_NAME,
                reason = CatalogTextPolicy.RejectionReason.PROHIBITED_CONTROL
            )

            assertFalse(error.rowContent.values.any { it.contains(raw) })
            assertEquals("", error.rowContent.getValue("productName"))
            assertEquals(setOf("productName"), error.redactedFields)
            assertEquals(
                context.getString(R.string.catalog_text_redacted_value),
                error.valueForPresentation(
                    "productName",
                    context.getString(R.string.catalog_text_redacted_value)
                )
            )
        }
    }

    @Test
    fun `xlsx error export writes localized marker and never prohibited catalog text`() {
        val rawValues = listOf("zero\u200Bwidth", "bidi\u202Eoverride", "nul\u0000value")
        val errors = rawValues.mapIndexed { index, raw ->
            catalogTextRowError(
                context = context,
                rowNumber = index + 2,
                row = linkedMapOf(
                    "barcode" to "SAFE-$index",
                    "productName" to raw
                ),
                field = CatalogTextField.PRODUCT_NAME,
                reason = CatalogTextPolicy.RejectionReason.PROHIBITED_ZERO_WIDTH
            )
        }
        val target = File.createTempFile("catalog-text-errors", ".xlsx", context.cacheDir)

        assertTrue(ErrorExporter.exportErrorsToXlsx(errors, context, Uri.fromFile(target)))

        XSSFWorkbook(target.inputStream()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val cellValues = sheet.flatMap { row ->
                row.map { cell -> cell.toString() }
            }
            assertEquals(errors.size, cellValues.count {
                it == context.getString(R.string.catalog_text_redacted_value)
            })
            rawValues.forEach { raw ->
                assertFalse(cellValues.any { it.contains(raw) })
            }
        }
    }
}

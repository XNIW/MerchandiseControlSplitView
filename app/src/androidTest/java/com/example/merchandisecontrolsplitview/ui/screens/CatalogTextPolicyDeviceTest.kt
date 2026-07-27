package com.example.merchandisecontrolsplitview.ui.screens

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.CatalogTextPolicy
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogTextPolicyDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importAnalysisNormalizationWarningRendersLocalizedRowAndFields() {
        val fields = linkedSetOf(
            CatalogTextField.PRODUCT_NAME,
            CatalogTextField.SUPPLIER_NAME
        )
        val expectedFields = listOf(
            context.getString(R.string.field_product_name),
            context.getString(R.string.field_supplier)
        ).joinToString(", ")
        val expected = context.getString(
            R.string.catalog_text_normalization_row,
            7,
            expectedFields
        )

        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface {
                    TextNormalizationWarningRow(rowNumber = 7, fields = fields)
                }
            }
        }

        composeRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun runtimePolicyCanonicalizesUnicodeAndRejectsStrictControls() {
        assertEquals(
            CatalogTextPolicy.Outcome.Normalized(
                value = "Café 茶 👩‍💻",
                changes = setOf(
                    CatalogTextPolicy.Change.LINE_BREAK_TO_SPACE,
                    CatalogTextPolicy.Change.SPACE_SEPARATOR_TO_SPACE,
                    CatalogTextPolicy.Change.TRIMMED,
                    CatalogTextPolicy.Change.UNICODE_NFC
                )
            ),
            CatalogTextPolicy.display(
                raw = " Cafe\u0301\n茶\u00a0👩‍💻 ",
                required = true,
                maxLength = CatalogTextPolicy.Limits.PRODUCT_NAME
            )
        )
        assertEquals(
            CatalogTextPolicy.RejectionReason.PROHIBITED_CONTROL,
            (CatalogTextPolicy.strict(
                raw = "1234\n5678",
                required = true,
                maxLength = CatalogTextPolicy.Limits.BARCODE
            ) as CatalogTextPolicy.Outcome.Rejected).reason
        )
    }

    @Test
    fun catalogTextMessagesExistInEverySupportedLocale() {
        val localizedTitles = listOf("it", "en", "es", "zh").map { language ->
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            context.createConfigurationContext(configuration)
                .getString(R.string.catalog_text_normalization_warning_title)
        }

        assertTrue(localizedTitles.all { it.isNotBlank() })
        assertEquals(4, localizedTitles.distinct().size)
        assertFalse(localizedTitles.any { it.contains("catalog_text_") })
    }
}

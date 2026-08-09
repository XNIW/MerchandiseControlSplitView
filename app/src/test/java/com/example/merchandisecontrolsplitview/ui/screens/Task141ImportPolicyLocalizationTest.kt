package com.example.merchandisecontrolsplitview.ui.screens

import android.content.res.Configuration
import com.example.merchandisecontrolsplitview.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Task141ImportPolicyLocalizationTest {
    @Test
    fun `duplicate barcode warning says last row wins without quantity sum in every locale`() {
        val expectedByLanguage = mapOf(
            "it" to "Verranno usati i dati dell'ultima riga; le quantità non verranno sommate.",
            "en" to "The data from the last row will be used; quantities will not be summed.",
            "es" to "Se usarán los datos de la última fila; las cantidades no se sumarán.",
            "zh" to "将使用最后一行的数据；数量不会相加。",
        )
        val baseContext = RuntimeEnvironment.getApplication()

        expectedByLanguage.forEach { (language, expected) ->
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localizedContext = baseContext.createConfigurationContext(configuration)
            assertEquals(
                "Unexpected duplicate policy copy for $language",
                expected,
                localizedContext.getString(R.string.warning_duplicate_resolution),
            )
        }
    }
}

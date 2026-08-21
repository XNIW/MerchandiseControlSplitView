package com.example.merchandisecontrolsplitview.ui.screens

import android.content.res.Configuration
import com.example.merchandisecontrolsplitview.R
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorefrontLocalizationTest {
    @Test
    fun `storefront authoring actions and offline states are localized in every supported locale`() {
        val baseContext = RuntimeEnvironment.getApplication()
        listOf("it", "en", "es", "zh").forEach { language ->
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val context = baseContext.createConfigurationContext(configuration)
            listOf(
                R.string.storefront_customer_app,
                R.string.storefront_save_draft,
                R.string.storefront_publish,
                R.string.storefront_schedule,
                R.string.storefront_hide,
                R.string.storefront_archive,
                R.string.storefront_local_draft_waiting,
                R.string.storefront_server_version_unverified,
                R.string.storefront_conflict_title,
                R.string.storefront_sync_before_publish
            ).forEach { resource ->
                val value = context.getString(resource)
                assertFalse("Missing Storefront text for $language/$resource", value.isBlank())
                assertTrue("Resource fallback marker for $language/$resource", !value.startsWith("storefront_"))
            }
        }
    }
}

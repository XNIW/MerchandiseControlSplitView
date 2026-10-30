package com.example.merchandisecontrolsplitview.ui.navigation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.MobileSyncPerformanceTestRunner
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.serialization.json.*

/** Opt-in configuration witness for existing controlled native fixtures. No OS-setting writes,
 * synthetic Density, auth restore, TalkBack action or accessibility-service activation.
 * The separate host Settings-UI receipt is required: FONT_SCALE alone is not proof of slider max.
 */
internal class Task144AccessibilityConfiguration private constructor(
    val language: String,
    val darkTheme: Boolean,
    private val mode: String,
    private val runId: String,
    private val settingsReceiptSha256: String,
    private val expectedScale: Float,
    private val targetContext: Context,
    private val selector: String
) {
    private val witness = AtomicReference<JsonObject?>(null)

    /** Preserve the owned context's DB/files/preferences routing while localizing resources. */
    fun localizedContext(owned: Context): Context {
        val localized = owned.createConfigurationContext(Configuration(owned.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language))
        })
        return object : ContextWrapper(owned) {
            override fun getResources(): Resources = localized.resources
            override fun getAssets(): AssetManager = localized.assets
        }
    }

    @Composable
    fun Content(localized: Context, content: @Composable () -> Unit) {
        val platformContext = LocalContext.current
        val platformConfiguration = LocalConfiguration.current
        val actualDensity = LocalDensity.current
        val actualScale = Settings.System.getFloat(targetContext.contentResolver, Settings.System.FONT_SCALE)
        check(abs(actualScale - expectedScale) < 0.001f) { "OS font scale changed during the test" }
        check(abs(platformConfiguration.fontScale - expectedScale) < 0.001f) { "Activity configuration is stale" }
        check(abs(actualDensity.fontScale - expectedScale) < 0.001f) { "Compose host density is stale" }
        val conversions = listOf(12f, 16f, 32f, 48f).map { size ->
            val actual = with(actualDensity) { size.sp.toPx() }
            val platform = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size,
                platformContext.resources.displayMetrics)
            check(abs(actual - platform) <= 1f) { "Host Compose font conversion differs from Android: $size sp" }
            buildJsonObject { put("sp", size); put("compose_px", actual); put("android_px", platform) }
        }
        if (mode == "os-max") {
            check(conversions.any {
                val sp = it.getValue("sp").jsonPrimitive.float
                abs(it.getValue("compose_px").jsonPrimitive.float - sp * actualDensity.density * expectedScale) > 1f
            }) { "Maximum font must retain the real Android nonlinear conversion" }
        }
        val configuration = localized.resources.configuration
        check(configuration.locales[0].language == language)
        check(abs(configuration.fontScale - expectedScale) < 0.001f)
        SideEffect {
            witness.set(buildJsonObject {
                put("protocol", PROTOCOL); put("kind", "native_os_font_configuration_witness")
                put("status", "OBSERVED_CONFIGURATION_NOT_ACCEPTANCE")
                put("selector", selector); put("run_id", runId); put("api", Build.VERSION.SDK_INT)
                put("font_mode", mode); put("font_scale_setting", actualScale)
                put("activity_font_scale", platformConfiguration.fontScale)
                put("compose_font_scale", actualDensity.fontScale)
                put("configuration_font_scale", configuration.fontScale)
                put("density", actualDensity.density); put("font_conversions", JsonArray(conversions))
                put("language", language); put("dark_theme", darkTheme)
                put("host_settings_receipt_sha256", settingsReceiptSha256)
                put("font_provider", "unchanged_activity_LocalDensity")
                put("talkback_interaction", "NOT_RUN_SEPARATE_GATE")
                put("acceptance_boundary", "controlled native fixture; configuration witness only; no authenticated TEST claim")
            })
        }
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
            MerchandiseControlTheme(darkTheme = darkTheme, content = content)
        }
    }

    /** Call after Compose has become idle. A fresh owned output prevents evidence replacement. */
    fun writeWitness(output: File) {
        val value = checkNotNull(witness.get()) { "No committed host configuration was observed" }
        check(output.createNewFile()) { "Configuration evidence already exists" }
        FileOutputStream(output).use { stream ->
            stream.write((value.toString() + "\n").toByteArray()); stream.fd.sync()
        }
    }

    companion object {
        const val PROTOCOL = "task144-native-os-font-v1"
        private const val PREFIX = "task144A11y"
        private const val ROOT_CLASS = "com.example.merchandisecontrolsplitview.ui.navigation.LocalAvailabilityRootDeviceTest"
        // Only the already-owned route/editor selectors; timed performance invocations stay separate.
        private val selectors = setOf(
            "filePickerContextSurvivesSameScopeRequalification",
            "databaseContextSurvivesSameScopeRequalification",
            "historyContextSurvivesSameScopeRequalification",
            "preGenerateContextSurvivesSameScopeRequalification",
            "generatedContextSurvivesSameScopeRequalification",
            "importAnalysisContextSurvivesSameScopeRequalification",
            "databaseEditorWindowSuspendsAndRestoresDraftFocusAndSelection",
            "preGenerateKeepsDraftAndRouteAcrossSameScopeRequalification"
        ).map { "$ROOT_CLASS#$it" }.toSet()

        fun fromArguments(context: Context): Task144AccessibilityConfiguration? {
            val args = InstrumentationRegistry.getArguments()
            val names = args.keySet().filter { it.startsWith(PREFIX) }.toSet()
            if (names.isEmpty()) return null
            check(names == setOf("${PREFIX}Protocol", "${PREFIX}Mode", "${PREFIX}Locale", "${PREFIX}Theme",
                "${PREFIX}RunId", "${PREFIX}SettingsReceiptSha256")) { "Incomplete or unknown accessibility arguments" }
            check(args.getString("${PREFIX}Protocol") == PROTOCOL)
            check(InstrumentationRegistry.getInstrumentation().javaClass == MobileSyncPerformanceTestRunner::class.java)
            check(context.applicationContext.javaClass == Application::class.java)
            check(Build.VERSION.SDK_INT == 35) { "Only the existing API 35 campaign is qualified by this proposal" }
            val selector = checkNotNull(args.getString("class"))
            check(selector in selectors) { "Accessibility profile requires one exact registered route/editor selector" }
            val mode = checkNotNull(args.getString("${PREFIX}Mode"))
            val expectedScale = when (mode) { "os-normal" -> 1f; "os-max" -> 2f; else -> error("Unknown font mode") }
            val locale = checkNotNull(args.getString("${PREFIX}Locale")); check(locale in setOf("it", "en", "es", "zh"))
            val dark = when (args.getString("${PREFIX}Theme")) { "light" -> false; "dark" -> true; else -> error("Unknown theme") }
            val runId = checkNotNull(args.getString("${PREFIX}RunId")); check(runId.matches(Regex("[a-z0-9][a-z0-9-]{7,79}")))
            val receipt = checkNotNull(args.getString("${PREFIX}SettingsReceiptSha256")); check(receipt.matches(Regex("[0-9a-f]{64}")))
            check(abs(Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE) - expectedScale) < 0.001f) {
                "Set font through standard Settings UI before running, then bind its receipt"
            }
            return Task144AccessibilityConfiguration(locale, dark, mode, runId, receipt, expectedScale, context, selector)
        }
    }
}

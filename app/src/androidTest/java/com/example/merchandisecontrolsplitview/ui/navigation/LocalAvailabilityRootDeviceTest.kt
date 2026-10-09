package com.example.merchandisecontrolsplitview.ui.navigation

import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production NavHost/screens + file-backed repository, while actual recovery RPC is held.
 * Controlled adapters prove local integration, never authenticated TEST/backend acceptance.
 */
@RunWith(AndroidJUnit4::class)
class LocalAvailabilityRootDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as MerchandiseControlApplication
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val store = ViewModelStore()
    private var database: AppDatabase? = null
    private var auto: CatalogAutoSyncCoordinator? = null
    private val release = CompletableDeferred<Unit>()
    private val visible = mutableStateOf(true)

    @After fun cleanup() {
        release.complete(Unit)
        auto?.shutdown()
        runBlocking { job.cancelAndJoin() }
        compose.runOnUiThread { store.clear() }
        database?.close()
        context.deleteDatabase(DB_NAME)
        ShopSyncRecoveryTestHooks.reset()
    }

    @Test fun actualBusySyncBannerDoesNotOverlapDatabaseHeaderInFourLocales() {
        val locales = listOf("it", "es", "zh", "en")
        val fontScales = listOf(1.0f, 1.6f)
        clearEvidence(*locales.flatMap { locale -> fontScales.map { font ->
            "android-sync-banner-$locale-font${(font * 100).toInt()}.png"
        } }.toTypedArray())
        context.deleteDatabase(DB_NAME)
        val db = openDatabase().also { database = it }
        val repository = DefaultInventoryRepository(db)
        val business = runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(coordinator(db, repository, Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote())
                .recover(OWNER, shop(), ownerScope()) is ShopSyncRecoveryResult.Activated)
            assertNull(db.syncRecoveryJournalDao().get())
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            repository.resolveBusinessDataScope(ownerScope())
        }
        assertTrue(business.allowsLocalOperations)
        lateinit var databaseVM: DatabaseViewModel
        lateinit var excelVM: ExcelViewModel
        compose.runOnUiThread {
            databaseVM = DatabaseViewModel(app, repository); store.put("database", databaseVM)
            excelVM = ExcelViewModel(app, repository); store.put("excel", excelVM)
        }
        val language = mutableStateOf("it")
        val selectedFontScale = mutableStateOf(1.0f)
        val progress = mutableStateOf(CatalogSyncProgressState.idle())
        compose.setContent {
            val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            val cfg = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language.value))
                this.fontScale = selectedFontScale.value
            }
            val localized = context.createConfigurationContext(cfg)
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides cfg,
                LocalDensity provides Density(density, selectedFontScale.value),
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
                MerchandiseControlTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).fillMaxHeight().testTag("sync-banner-phone-viewport")) {
                        AppNavGraphContent(app, excelVM, databaseVM, true,
                            AuthState.SignedIn(OWNER, "root-test@example.invalid"), progress.value,
                            ShopContext(OWNER, emptyList(), shop(), syncAllowed = true, localAccessAllowed = true), business)
                    }
                }
            }
        }
        compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement("BANNER-DRAFT")
        val viewport = compose.onNodeWithTag("sync-banner-phone-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("the actual root is constrained to a phone width", 360f,
            viewport.width / context.resources.displayMetrics.density, 1f)
        val overlaps = mutableListOf<String>()
        val textFailures = mutableListOf<String>()
        for (locale in locales) for (font in fontScales) {
            compose.runOnIdle {
                language.value = locale
                selectedFontScale.value = font
                progress.value = CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN)
            }
            val cfg = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale)); this.fontScale = font
            }
            val localized = context.createConfigurationContext(cfg)
            val description = localized.getString(R.string.cloud_sync_indicator_status_cd,
                localized.getString(R.string.catalog_cloud_stage_sync_events_drain_short))
            // Allow the production 700ms visibility delay and fade to settle on the Compose clock.
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val banner = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val title = compose.onNode(hasText(localized.getString(R.string.database)) and
                !hasAnyAncestor(hasTestTag("root-tab-databaseScreen")), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val importButton = compose.onNodeWithContentDescription(localized.getString(R.string.import_file))
                .assertHasClickAction().assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val exportButton = compose.onNodeWithContentDescription(localized.getString(R.string.export_file))
                .assertHasClickAction().assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for ((name, bounds) in listOf("title" to title, "import" to importButton, "export" to exportButton)) {
                if (banner.overlaps(bounds)) overlaps += "$locale/font=$font/$name: banner=$banner header=$bounds"
            }
            for ((name, expectedText) in listOf(
                "stage" to localized.getString(R.string.catalog_cloud_stage_sync_events_drain_short),
                "detail" to localized.getString(R.string.cloud_sync_indicator_local_ready)
            )) {
                val layouts = mutableListOf<TextLayoutResult>()
                val textNode = compose.onNodeWithText(expectedText, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                    .fetchSemanticsNode()
                assertEquals("$locale/font=$font/$name has one actual Text layout", 1, layouts.size)
                val layout = layouts.single()
                assertEquals(expectedText, layout.layoutInput.text.text)
                if (layout.hasVisualOverflow) {
                    textFailures += "$locale/font=$font/$name overflow: width=${layout.didOverflowWidth}, " +
                        "height=${layout.didOverflowHeight}, lines=${layout.lineCount}, maxLines=${layout.layoutInput.maxLines}"
                }
                // Unclipped origin and size prevent a clipped semantic rectangle from hiding lost text.
                val origin = textNode.positionInRoot
                val contained = origin.x >= banner.left && origin.y >= banner.top &&
                    origin.x + textNode.size.width <= banner.right && origin.y + textNode.size.height <= banner.bottom
                if (!contained) textFailures += "$locale/font=$font/$name outside banner: " +
                    "origin=$origin size=${textNode.size} banner=$banner"
            }
            compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected().assertIsEnabled()
            compose.onNodeWithTag("database-search").assertTextContains("BANNER-DRAFT")
            assertEquals("BANNER-DRAFT", databaseVM.filter.value)
            capture("android-sync-banner-$locale-font${(font * 100).toInt()}.png")
        }
        assertTrue("Busy banner occludes the actual Database header:\n${overlaps.joinToString("\n")}", overlaps.isEmpty())
        assertTrue("Busy banner text must be complete and contained:\n${textFailures.joinToString("\n")}", textFailures.isEmpty())
    }

    @Test fun actualDatabaseFilteredEmptyMessageDoesNotOverlapFloatingActionsInFourLocales() {
        val locales = listOf("it", "es", "zh", "en")
        val fontScales = listOf(1.0f, 1.6f)
        clearEvidence(*locales.flatMap { locale -> fontScales.map { font ->
            "android-database-empty-fab-$locale-font${(font * 100).toInt()}.png"
        } }.toTypedArray())
        context.deleteDatabase(DB_NAME)
        val db = openDatabase().also { database = it }
        val repository = DefaultInventoryRepository(db)
        val business = runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(coordinator(db, repository, Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote())
                .recover(OWNER, shop(), ownerScope()) is ShopSyncRecoveryResult.Activated)
            assertNull(db.syncRecoveryJournalDao().get())
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            repository.resolveBusinessDataScope(ownerScope())
        }
        assertTrue(business.allowsLocalOperations)
        lateinit var databaseVM: DatabaseViewModel
        lateinit var excelVM: ExcelViewModel
        compose.runOnUiThread {
            databaseVM = DatabaseViewModel(app, repository); store.put("database", databaseVM)
            excelVM = ExcelViewModel(app, repository); store.put("excel", excelVM)
        }
        val language = mutableStateOf("it")
        val selectedFontScale = mutableStateOf(1.0f)
        val progress = mutableStateOf(CatalogSyncProgressState.idle())
        compose.setContent {
            val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            val cfg = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language.value)); fontScale = selectedFontScale.value
            }
            val localized = context.createConfigurationContext(cfg)
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides cfg,
                LocalDensity provides Density(density, selectedFontScale.value),
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
                MerchandiseControlTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).fillMaxHeight().testTag("database-empty-fab-phone-viewport")) {
                        AppNavGraphContent(app, excelVM, databaseVM, true,
                            AuthState.SignedIn(OWNER, "root-test@example.invalid"), progress.value,
                            ShopContext(OWNER, emptyList(), shop(), syncAllowed = true, localAccessAllowed = true), business)
                    }
                }
            }
        }
        compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement("TABS-DRAFT")
        val failures = mutableListOf<String>()
        val measurements = mutableListOf<String>()
        for (locale in locales) for (font in fontScales) {
            compose.runOnIdle {
                language.value = locale; selectedFontScale.value = font
                progress.value = CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN)
            }
            // Same production banner delay/fade and Compose clock allowance as the existing root tests.
            compose.mainClock.advanceTimeBy(1_000)
            val cfg = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale)); fontScale = font
            }
            val localized = context.createConfigurationContext(cfg)
            val expected = localized.getString(R.string.no_results_for, "TABS-DRAFT")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(expected, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val viewport = compose.onNodeWithTag("database-empty-fab-phone-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals("actual root phone width", 360f, viewport.width / context.resources.displayMetrics.density, 1f)
            val title = compose.onNode(hasText(localized.getString(R.string.database)) and
                !hasAnyAncestor(hasTestTag("root-tab-databaseScreen")), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for (resource in listOf(R.string.import_file, R.string.export_file)) {
                compose.onNodeWithContentDescription(localized.getString(resource))
                    .assertHasClickAction().assertIsEnabled().assertIsDisplayed()
            }
            val search = compose.onNodeWithTag("database-search").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val rootTab = compose.onNodeWithTag("root-tab-databaseScreen")
                .assertIsSelected().assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            val text = compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                .fetchSemanticsNode()
            assertEquals("$locale/font=$font actual empty-message layout", 1, layouts.size)
            val layout = layouts.single()
            assertEquals(expected, layout.layoutInput.text.text)
            assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layout.layoutInput.style.textAlign)
            assertTrue("$locale/font=$font empty message has a line", layout.lineCount > 0)
            val visibleEnd = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
            val ellipsized = (0 until layout.lineCount).map(layout::isLineEllipsized)
            if (visibleEnd != expected.length || ellipsized.any { it } || layout.didOverflowHeight) {
                failures += "$locale/font=$font incomplete empty message: end=$visibleEnd/${expected.length}, " +
                    "ellipsis=$ellipsized height=${layout.didOverflowHeight}"
            }
            val origin = text.positionInRoot
            // These are the rendered node's root coordinates, not the semantics MultiParagraph width.
            val message = androidx.compose.ui.geometry.Rect(origin.x, origin.y,
                origin.x + text.size.width, origin.y + text.size.height)
            // Simple centered Text can export a wider semantics paragraph than the actual node.
            val semanticOffsetX = (layout.multiParagraph.width - layout.size.width) / 2f
            val lineBounds = (0 until layout.lineCount).map { line ->
                androidx.compose.ui.geometry.Rect(layout.getLineLeft(line), layout.getLineTop(line),
                    layout.getLineRight(line), layout.getLineBottom(line))
            }
            val glyphBounds = expected.indices.map(layout::getBoundingBox)
            for ((kind, bounds) in listOf("line" to lineBounds, "glyph" to glyphBounds)) {
                for ((index, semanticRect) in bounds.withIndex()) {
                    val rect = androidx.compose.ui.geometry.Rect(semanticRect.left - semanticOffsetX,
                        semanticRect.top, semanticRect.right - semanticOffsetX, semanticRect.bottom)
                    // Only float-to-IntSize subpixel rounding; no overlap tolerance for the FAB targets.
                    if (rect.left < -1f || rect.top < -1f || rect.right > text.size.width + 1f ||
                        rect.bottom > text.size.height + 1f) {
                        failures += "$locale/font=$font $kind$index outside rendered text: rect=$rect size=${text.size}"
                    }
                }
            }
            if (message.left < viewport.left || message.right > viewport.right ||
                message.top < search.bottom || message.top < title.bottom || message.bottom > rootTab.top) {
                failures += "$locale/font=$font empty message outside content: message=$message " +
                    "viewport=$viewport search=$search rootTab=$rootTab"
            }
            val actions = listOf(R.string.scan_barcode, R.string.add_product).map { resource ->
                val action = compose.onNode(hasContentDescription(localized.getString(resource)) and hasClickAction())
                    .assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                if (message.overlaps(action)) failures += "$locale/font=$font/$resource empty message overlaps FAB: " +
                    "message=$message action=$action"
                resource to action
            }
            val measurement = "$locale/font=$font message=$message nodeSize=${text.size} " +
                "layoutSize=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                "constraints=${layout.layoutInput.constraints} semanticOffsetX=$semanticOffsetX " +
                "visibleEnd=$visibleEnd/${expected.length} ellipsis=$ellipsized " +
                "heightFlag=${layout.didOverflowHeight} widthDiagnosticOnly=${layout.didOverflowWidth} " +
                "lineBounds=$lineBounds glyphBounds=$glyphBounds actions=$actions"
            measurements += measurement
            android.util.Log.i("Task143EmptyLayout", measurement)
            compose.onNodeWithTag("database-search").assertTextContains("TABS-DRAFT")
            assertEquals("TABS-DRAFT", databaseVM.filter.value)
            capture("android-database-empty-fab-$locale-font${(font * 100).toInt()}.png")
        }
        assertTrue("Database empty message must be complete and separate from floating actions:\n${failures.joinToString("\n")}\n" +
            "Actual layout measurements:\n${measurements.joinToString("\n")}", failures.isEmpty())
    }

    @Test fun actualDatabaseSecondaryTabsShowCompleteLabelsInFourLocalesAtLargeFont() {
        val locales = listOf("it", "es", "zh", "en")
        val fontScales = listOf(1.0f, 1.6f)
        clearEvidence(*locales.flatMap { locale -> fontScales.map { font ->
            "android-database-tabs-$locale-font${(font * 100).toInt()}.png"
        } }.toTypedArray())
        context.deleteDatabase(DB_NAME)
        val db = openDatabase().also { database = it }
        val repository = DefaultInventoryRepository(db)
        val business = runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(coordinator(db, repository, Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote())
                .recover(OWNER, shop(), ownerScope()) is ShopSyncRecoveryResult.Activated)
            assertNull(db.syncRecoveryJournalDao().get())
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            repository.resolveBusinessDataScope(ownerScope())
        }
        assertTrue(business.allowsLocalOperations)
        lateinit var databaseVM: DatabaseViewModel
        lateinit var excelVM: ExcelViewModel
        compose.runOnUiThread {
            databaseVM = DatabaseViewModel(app, repository); store.put("database", databaseVM)
            excelVM = ExcelViewModel(app, repository); store.put("excel", excelVM)
        }
        val language = mutableStateOf("it")
        val selectedFontScale = mutableStateOf(1.0f)
        val progress = mutableStateOf(CatalogSyncProgressState.idle())
        compose.setContent {
            val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            val cfg = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language.value)); fontScale = selectedFontScale.value
            }
            val localized = context.createConfigurationContext(cfg)
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides cfg,
                LocalDensity provides Density(density, selectedFontScale.value),
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
                MerchandiseControlTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).fillMaxHeight().testTag("database-tabs-phone-viewport")) {
                        AppNavGraphContent(app, excelVM, databaseVM, true,
                            AuthState.SignedIn(OWNER, "root-test@example.invalid"), progress.value,
                            ShopContext(OWNER, emptyList(), shop(), syncAllowed = true, localAccessAllowed = true), business)
                    }
                }
            }
        }
        compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement("TABS-DRAFT")
        val failures = mutableListOf<String>()
        val measurements = mutableListOf<String>()
        for (locale in locales) for (font in fontScales) {
            compose.runOnIdle {
                language.value = locale; selectedFontScale.value = font
                progress.value = CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN)
            }
            compose.mainClock.advanceTimeBy(1_000)
            val cfg = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale)); fontScale = font
            }
            val localized = context.createConfigurationContext(cfg)
            val viewport = compose.onNodeWithTag("database-tabs-phone-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals("actual root phone width", 360f, viewport.width / context.resources.displayMetrics.density, 1f)
            val title = compose.onNode(hasText(localized.getString(R.string.database)) and
                !hasAnyAncestor(hasTestTag("root-tab-databaseScreen")), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for (resource in listOf(R.string.import_file, R.string.export_file)) {
                compose.onNodeWithContentDescription(localized.getString(resource))
                    .assertHasClickAction().assertIsEnabled().assertIsDisplayed()
            }
            val search = compose.onNodeWithTag("database-search").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val labelResources = listOf(R.string.database_tab_products, R.string.database_tab_suppliers,
                R.string.database_tab_categories)
            for (resource in labelResources) {
                val expected = localized.getString(resource)
                val tab = compose.onNodeWithText(expected).assertHasClickAction().assertIsEnabled().assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                val layouts = mutableListOf<TextLayoutResult>()
                val text = compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                    .fetchSemanticsNode()
                assertEquals("$locale/font=$font/$expected actual layout", 1, layouts.size)
                val layout = layouts.single()
                assertEquals(expected, layout.layoutInput.text.text)
                assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layout.layoutInput.style.textAlign)
                // Simple Text semantics uses the maximum-width paragraph, while the node may be narrower.
                val semanticOffsetX = (layout.multiParagraph.width - layout.size.width) / 2f
                assertTrue("$locale/font=$font/$expected has a text line", layout.lineCount > 0)
                val visibleEnds = (0 until layout.lineCount).map { layout.getLineEnd(it, visibleEnd = true) }
                val ellipsized = (0 until layout.lineCount).map(layout::isLineEllipsized)
                val lineBounds = (0 until layout.lineCount).map { line ->
                    androidx.compose.ui.geometry.Rect(layout.getLineLeft(line), layout.getLineTop(line),
                        layout.getLineRight(line), layout.getLineBottom(line))
                }
                val glyphBounds = expected.indices.map(layout::getBoundingBox)
                val measurement = "$locale/font=$font/$expected size=${layout.size} " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                    "constraints=${layout.layoutInput.constraints} widthFlag=${layout.didOverflowWidth} " +
                    "heightFlag=${layout.didOverflowHeight} maxLines=${layout.layoutInput.maxLines} " +
                    "semanticOffsetX=$semanticOffsetX " +
                    "visibleEnds=$visibleEnds expectedEnd=${expected.length} ellipsis=$ellipsized " +
                    "lineBounds=$lineBounds glyphBounds=$glyphBounds"
                measurements += measurement
                android.util.Log.i("Task143TabLayout", measurement)
                if (visibleEnds.last() != expected.length || ellipsized.any { it } || layout.didOverflowHeight) {
                    failures += "$locale/font=$font/$expected incomplete visible text: " +
                        "end=${visibleEnds.last()}/${expected.length}, ellipsis=$ellipsized, height=${layout.didOverflowHeight}"
                }
                val origin = text.positionInRoot
                for ((kind, bounds) in listOf("line" to lineBounds, "glyph" to glyphBounds)) {
                    for ((index, semanticRect) in bounds.withIndex()) {
                        val rect = androidx.compose.ui.geometry.Rect(semanticRect.left - semanticOffsetX,
                            semanticRect.top, semanticRect.right - semanticOffsetX, semanticRect.bottom)
                        // Text size is exported as IntSize; tolerate only its subpixel rounding, never clipping a character.
                        val insideText = rect.left >= -1f && rect.top >= -1f &&
                            rect.right <= layout.size.width + 1f && rect.bottom <= layout.size.height + 1f
                        val insideTab = origin.x + rect.left >= tab.left && origin.y + rect.top >= tab.top &&
                            origin.x + rect.right <= tab.right && origin.y + rect.bottom <= tab.bottom
                        if (!insideText || !insideTab) failures += "$locale/font=$font/$expected $kind$index outside: " +
                            "rect=$rect origin=$origin textSize=${layout.size} tab=$tab"
                    }
                }
                if (origin.x < tab.left || origin.y < tab.top ||
                    origin.x + text.size.width > tab.right || origin.y + text.size.height > tab.bottom) {
                    failures += "$locale/font=$font/$expected text outside actual tab: origin=$origin size=${text.size} tab=$tab"
                }
                if (tab.left < viewport.left || tab.right > viewport.right || tab.top < title.bottom || tab.bottom > search.top) {
                    failures += "$locale/font=$font/$expected tab outside visible header: tab=$tab viewport=$viewport title=$title search=$search"
                }
            }
            // Exercise the production tab actions, then return to the same product query.
            for (resource in labelResources.drop(1) + labelResources.first()) {
                compose.onNodeWithText(localized.getString(resource)).performClick().assertIsSelected()
            }
            compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected().assertIsEnabled()
            compose.onNodeWithTag("database-search").assertTextContains("TABS-DRAFT")
            assertEquals("TABS-DRAFT", databaseVM.filter.value)
            capture("android-database-tabs-$locale-font${(font * 100).toInt()}.png")
        }
        assertTrue("Database secondary tab labels must be complete and contained:\n${failures.joinToString("\n")}\n" +
            "Actual layout measurements:\n${measurements.joinToString("\n")}", failures.isEmpty())
    }

    @Test fun ordinaryEventDrainRecoveryKeepsActualRootDraftAndSaveAvailable() =
        ordinaryRecoveryKeepsActualRootAvailable(push = false)

    @Test fun ordinaryCatalogPushRecoveryKeepsActualRootDraftAndSaveAvailable() =
        ordinaryRecoveryKeepsActualRootAvailable(push = true)

    private fun ordinaryRecoveryKeepsActualRootAvailable(push: Boolean) {
        context.deleteDatabase(DB_NAME)
        val first = openDatabase().also { database = it }
        val empty = Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote()
        runBlocking {
            val initial = DefaultInventoryRepository(first)
            first.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 1L))
            first.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(coordinator(first, initial, empty).recover(OWNER, shop(), ownerScope()) is ShopSyncRecoveryResult.Activated)
            assertNull(first.syncRecoveryJournalDao().get())
            val baseline = requireNotNull(first.syncRecoveryBaselineDao().get())
            validateShopSyncActiveReceipt(first, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            repeat(30) { i -> initial.addProduct(Product(barcode = "ROOT-%03d".format(i),
                productName = "Local product %03d".format(i), purchasePrice = 10.0, retailPrice = 20.0)) }
        }
        first.close()
        val db = openDatabase().also { database = it }
        val tracker = CatalogSyncStateTracker(runBlocking { DefaultInventoryRepository(db).resolveBusinessDataScope(ownerScope()) })
        assertTrue(tracker.businessDataScopeState.value.allowsCloudSync)
        tracker.updateNetworkAvailability(true)
        val ordinary = object : ShopSyncReadRemoteDataSource by empty {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> =
                empty.checkpoint(context).map { it.copy(syncEvents = it.syncEvents.copy(requiresFullRecovery = true)) }
        }
        val repository = DefaultInventoryRepository(db, tracker, ordinary)
        val entered = CompletableDeferred<Unit>()
        val held = object : ShopSyncReadRemoteDataSource by empty {
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                if (domain == ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit); release.await() }
                return empty.recoveryPage(context, domain, afterId, limit)
            }
        }
        val events = object : SyncEventRemoteDataSource {
            override val isConfigured = true
            override suspend fun checkCapabilities(ownerUserId: String) =
                Result.success(SyncEventRemoteCapabilities(true, true, true))
            override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams) = Result.success(
                SyncEventRemoteRow(id = 43L, ownerUserId = OWNER, shopId = SHOP, storeId = params.storeId,
                    domain = params.domain, eventType = params.eventType, sourceDeviceId = params.sourceDeviceId,
                    clientEventId = params.clientEventId, changedCount = params.changedCount,
                    entityIds = params.entityIds, createdAt = "2026-07-21T12:00:00Z"))
            override suspend fun fetchSyncEventsAfter(ownerUserId: String, storeId: String?, afterId: Long, limit: Long) =
                Result.success(emptyList<SyncEventRemoteRow>())
        }
        val device = object : ShopDeviceRegistrationRemote {
            override val isConfigured = true
            override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
                error("already registered device expected")
            override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
                error("scoped device check expected")
            override suspend fun shopDeviceStatusForShop(shopId: String, reason: String): Result<ShopDeviceAuthorizationSnapshot> {
                assertEquals(SHOP, shopId)
                return Result.success(ShopDeviceAuthorizationSnapshot("active", "success", true,
                    "2026-07-21T11:00:00Z", "2026-07-21T11:00:00Z", "active", "allow", 1L))
            }
        }
        var recovery: Deferred<ShopSyncRecoveryResult>? = null
        auto = CatalogAutoSyncCoordinator(repository, RootCatalogRemote(), RootPriceRemote(),
            syncEventRemote = events,
            deviceAuthorization = ShopDeviceAuthorizationRepository(device, businessDataScopeRuntimeGuard = tracker),
            authFlow = MutableStateFlow<AuthState>(AuthState.SignedIn(OWNER, "root@example.invalid")),
            selectedShopProvider = ::shop, syncStateTracker = tracker, scope = scope, debounceMs = Long.MAX_VALUE,
            onRecoveryRequired = { recovery = scope.async { coordinator(db, repository, held, tracker).recover(OWNER, shop(), ownerScope()) } })
        lateinit var databaseVM: DatabaseViewModel
        lateinit var excelVM: ExcelViewModel
        compose.runOnUiThread {
            databaseVM = DatabaseViewModel(app, repository); store.put("database", databaseVM)
            excelVM = ExcelViewModel(app, repository); store.put("excel", excelVM)
        }
        compose.setContent {
            val business by tracker.businessDataScopeState.collectAsState()
            val progress by tracker.state.collectAsState()
            MerchandiseControlTheme(darkTheme = false) {
                AppNavGraphContent(app, excelVM, databaseVM, true, AuthState.SignedIn(OWNER, "root@example.invalid"),
                    progress, ShopContext(OWNER, emptyList(), shop(), syncAllowed = true, localAccessAllowed = true), business)
            }
        }
        compose.onNodeWithTag("root-tab-databaseScreen").performClick()
        compose.onNodeWithTag("database-search").performTextReplacement("ROOT-")
        compose.onNodeWithTag("database-product-list").performScrollToNode(hasText("ROOT-024", substring = true))
        val beforeScroll = scrollValue()
        compose.onNodeWithText("ROOT-024", substring = true).performClick()
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement("Draft across ordinary recovery")
        try {
            runBlocking {
                if (push) auto!!.runPushCycle("local_commit") else auto!!.runSyncEventDrainCycle("root_held_recovery")
                withTimeout(15_000) { entered.await() }
            }
            assertNotNull(runBlocking { db.syncRecoveryJournalDao().get() })
            assertFalse(requireNotNull(recovery).isCompleted)
            compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
            compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected().assertIsEnabled()
            compose.onNodeWithText("Draft across ordinary recovery").assertExists()
            compose.onNodeWithTag("product-editor-name").assertIsFocused()
            assertEquals("ROOT-", databaseVM.filter.value)
            assertEquals(beforeScroll, scrollValue(), 0f)
            compose.onNodeWithTag("product-editor-save").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isEmpty() }
            assertEquals("Draft across ordinary recovery", runBlocking { repository.findProductByBarcode("ROOT-024") }?.productName)
            assertFalse(release.isCompleted)
        } finally {
            recovery?.cancel()
            release.complete(Unit)
            runBlocking { recovery?.join() }
        }
    }

    @Test fun actualEmptyFirstBootstrapShellIsNavigableBeforeRecoveryRelease() {
        clearEvidence("android-root-empty-held-options.png","android-root-empty-activated.png")
        context.deleteDatabase(DB_NAME)
        val db=openDatabase().also { database=it }
        val tracker=CatalogSyncStateTracker(Task126BusinessDataScopeState.checking())
        val repository=DefaultInventoryRepository(db,tracker)
        val empty=Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote()
        val entered=CompletableDeferred<Unit>()
        val held=object : ShopSyncReadRemoteDataSource by empty {
            override suspend fun recoveryPage(context: ShopSyncRpcContext,domain: ShopSyncRowDomain,afterId:String?,limit:Int):Result<ShopSyncRecoveryPage> {
                if(domain==ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit);release.await() }
                return empty.recoveryPage(context,domain,afterId,limit)
            }
        }
        runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId=DEVICE,createdAtMs=1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
        }
        val recovery=scope.async { coordinator(db,repository,held,tracker).recover(OWNER,shop(),ownerScope()) }
        runBlocking { withTimeout(10_000) { entered.await() } }
        lateinit var databaseVM:DatabaseViewModel;lateinit var excelVM:ExcelViewModel
        compose.runOnUiThread {
            databaseVM=DatabaseViewModel(app,repository);store.put("database",databaseVM)
            excelVM=ExcelViewModel(app,repository);store.put("excel",excelVM)
        }
        compose.setContent {
            val business by tracker.businessDataScopeState.collectAsState()
            val progress by tracker.state.collectAsState()
            MerchandiseControlTheme(darkTheme=false) {
                AppNavGraphContent(app,excelVM,databaseVM,true,AuthState.SignedIn(OWNER,"root-test@example.invalid"),
                    progress,ShopContext(OWNER,emptyList(),shop(),isLoading=true,syncAllowed=false,localAccessAllowed=true),business)
            }
        }
        for(tab in listOf("history","databaseScreen","filePicker")) {
            compose.onNodeWithTag("root-tab-$tab").assertIsEnabled().performClick().assertIsSelected()
            compose.onNodeWithTag("root-business-placeholder").assertExists()
            compose.onNodeWithTag("database-search").assertDoesNotExist()
        }
        compose.onNodeWithTag("root-tab-optionsScreen").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
        assertFalse(release.isCompleted)
        assertEquals(0,runBlocking { db.productDao().count() })
        capture("android-root-empty-held-options.png")
        release.complete(Unit)
        assertTrue(runBlocking { withTimeout(15_000) { recovery.await() } } is ShopSyncRecoveryResult.Activated)
        val ready=runBlocking { repository.resolveBusinessDataScope(ownerScope()) }
        compose.runOnIdle { tracker.updateBusinessDataScopeState(ready) }
        compose.onNodeWithTag("root-tab-optionsScreen").assertIsSelected()
        compose.onNodeWithTag("root-tab-databaseScreen").performClick()
        compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
        compose.onNodeWithTag("database-search").assertExists()
        assertEquals(0,runBlocking { db.productDao().count() })
        capture("android-root-empty-activated.png")
    }

    @Test fun actualOptionsAccountWrapsAtLargeFontInFourLanguages() {
        val locales=listOf("it","en","es","zh")
        clearEvidence(*locales.map { "android-options-$it-large-font.png" }.toTypedArray())
        context.deleteDatabase(DB_NAME)
        val db=openDatabase().also { database=it };val repository=DefaultInventoryRepository(db)
        lateinit var databaseVM:DatabaseViewModel;lateinit var excelVM:ExcelViewModel
        compose.runOnUiThread {
            databaseVM=DatabaseViewModel(app,repository);store.put("database",databaseVM)
            excelVM=ExcelViewModel(app,repository);store.put("excel",excelVM)
        }
        val language=mutableStateOf("it")
        compose.setContent {
            val cfg=Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language.value));fontScale=1.6f
            }
            val localized=context.createConfigurationContext(cfg)
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides cfg,
                LocalDensity provides Density(density,1.6f)) {
                MerchandiseControlTheme(darkTheme=false) {
                    AppNavGraphContent(app,excelVM,databaseVM,true,
                        AuthState.SignedIn(OWNER,"root-test@a-long-example-domain-for-confirmed-account-text-with-large-font.example.invalid"),
                        CatalogSyncProgressState.idle(),ShopContext(OWNER,emptyList(),shop(),syncAllowed=false,localAccessAllowed=true),
                        Task126BusinessDataScopeState.checking())
                }
            }
        }
        compose.onNodeWithTag("root-tab-optionsScreen").performClick()
        for(locale in locales) {
            compose.runOnIdle { language.value=locale }
            for(tag in listOf("options-account-title","options-account-email")) {
                val layouts=mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                assertEquals(1,layouts.size)
                capture("android-options-$locale-large-font.png")
                val layout=layouts.single()
                assertFalse("$locale/$tag overflow: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, size=${layout.size}, lines=${layout.lineCount}, constraints=${layout.layoutInput.constraints}, maxLines=${layout.layoutInput.maxLines}",layout.hasVisualOverflow)
            }
            for(route in listOf("filePicker","history","databaseScreen","optionsScreen")) {
                val layouts=mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("root-tab-label-$route",useUnmergedTree=true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                assertEquals(1,layouts.size)
                assertFalse("$locale/$route label overflow at fontScale1.6",layouts.single().hasVisualOverflow)
            }
            compose.onNodeWithTag("options-account-signout").performScrollTo().assertIsDisplayed().assertIsEnabled()
            capture("android-options-$locale-large-font.png")
        }
    }

    @Test fun actualRootTabSearchEditorSaveBeforeRecoveryReleaseThenAutomaticQueueAndReopen() {
        clearEvidence("android-root-held-draft.png","android-root-held-saved.png","android-root-held-second-draft.png","android-root-activated-draft.png","android-root-activated-saved.png")
        context.deleteDatabase(DB_NAME)
        val db = openDatabase().also { database = it }
        val initialRepo = DefaultInventoryRepository(db)
        val empty = Task139ShopSyncRecoveryForceStopDeviceTest.EmptyRecoveryRemote()
        runBlocking {
            db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId=DEVICE,createdAtMs=1L))
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED))
            assertTrue(coordinator(db,initialRepo,empty).recover(OWNER,shop(),ownerScope()) is ShopSyncRecoveryResult.Activated)
            repeat(30) { i -> initialRepo.addProduct(Product(barcode="ROOT-%03d".format(i),
                productName="Held product %03d".format(i),purchasePrice=10.0,retailPrice=20.0,stockQuantity=2.0)) }
            db.syncRecoveryJournalDao().upsert(journal(SyncRecoveryAuthorizationModes.SAME_SCOPE))
        }
        val walBefore=db.openHelper.writableDatabase.isWriteAheadLoggingEnabled
        assertTrue("owned real-root database uses production WAL",walBefore)
        val tracker = CatalogSyncStateTracker(runBlocking { initialRepo.resolveOfflineBusinessDataScope(ownerScope(),true) })
        assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
        assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
        val repository = DefaultInventoryRepository(db,tracker)
        val observedId=runBlocking { requireNotNull(repository.findProductByBarcode("ROOT-024")).id }
        val hotDao=MutableStateFlow<Boolean?>(null)
        val hotFailure=MutableStateFlow<String?>(null)
        scope.launch { try { repository.observeProductCloudConfirmed(observedId).collect { hotDao.value=it } }
            catch(error:Exception) { hotFailure.value=error.javaClass.simpleName } }
        val entered = CompletableDeferred<Unit>()
        val held = object : ShopSyncReadRemoteDataSource by empty {
            override suspend fun recoveryPage(context: ShopSyncRpcContext,domain: ShopSyncRowDomain,
                afterId: String?,limit: Int): Result<ShopSyncRecoveryPage> {
                if (domain == ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit);release.await() }
                return empty.recoveryPage(context,domain,afterId,limit)
            }
        }
        val recoveryFailure=AtomicReference<Exception?>(null)
        ShopSyncRecoveryTestHooks.onRecoveryFailure={ recoveryFailure.set(it) }
        val recovery = scope.async { coordinator(db,repository,held,tracker).recover(OWNER,shop(),ownerScope()) }
        runBlocking { withTimeout(10_000) { entered.await() } }
        val cloud = RootCatalogRemote()
        val price = RootPriceRemote()
        auto = CatalogAutoSyncCoordinator(repository,cloud,price,
            authFlow=MutableStateFlow<AuthState>(AuthState.SignedIn(OWNER,"root-test@example.invalid")),
            selectedShopProvider=::shop,syncStateTracker=tracker,debounceMs=100)
        repository.onProductCatalogChanged = auto!!::onLocalProductChanged
        repository.onCatalogChanged = auto!!::onLocalCatalogChanged
        lateinit var databaseVM: DatabaseViewModel
        lateinit var excelVM: ExcelViewModel
        compose.runOnUiThread {
            databaseVM=DatabaseViewModel(app,repository);store.put("database",databaseVM)
            excelVM=ExcelViewModel(app,repository);store.put("excel",excelVM)
        }
        compose.setContent {
            val business by tracker.businessDataScopeState.collectAsState()
            val progress by tracker.state.collectAsState()
            MerchandiseControlTheme(darkTheme=false) {
              if (visible.value) {
                AppNavGraphContent(app,excelVM,databaseVM,true,
                    AuthState.SignedIn(OWNER,"root-test@example.invalid"),progress,
                    ShopContext(OWNER,emptyList(),shop(),isLoading=true,syncAllowed=false,localAccessAllowed=true),business)
              }
            }
        }
        compose.onNodeWithTag("root-tab-filePicker").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("root-tab-history").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("root-tab-databaseScreen").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement("ROOT-")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("database-product-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("database-product-list").performScrollToNode(hasText("ROOT-024",substring=true))
        val scrollBefore = scrollValue()
        compose.onNodeWithText("ROOT-024",substring=true).performClick()
        compose.onNodeWithTag("task141.edit.dialog-root").assertExists()
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement("Draft before network release")
        assertFalse("root was used before release",release.isCompleted)
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        compose.runOnIdle {
            tracker.update(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            tracker.updateBusinessDataScopeState(tracker.businessDataScopeState.value.copy(
                status=Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,errorCode="controlled retry"))
        }
        compose.onNodeWithText("Draft before network release").assertExists()
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected()
        assertEquals("ROOT-",databaseVM.filter.value)
        assertEquals(scrollBefore,scrollValue(),0f)
        capture("android-root-held-draft.png")
        val pricesBefore=runBlocking { db.productPriceDao().countAll() }
        compose.onNodeWithTag("product-editor-save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isEmpty() }
        val saved=runBlocking { requireNotNull(repository.findProductByBarcode("ROOT-024")) }
        assertEquals("Draft before network release",saved.productName)
        assertFalse("durable Save preceded transport release",release.isCompleted)
        assertEquals(pricesBefore,runBlocking { db.productPriceDao().countAll() })
        assertTrue(cloud.products.isEmpty())
        compose.waitUntil(10_000) { databaseVM.lastProductSaveCloudConfirmed.value==false }
        compose.onNodeWithText(context.getString(com.example.merchandisecontrolsplitview.R.string.success_product_updated)).assertExists()
        capture("android-root-held-saved.png")
        // A second, still-unsaved editor must survive the actual generation publication too.
        compose.onNodeWithTag("database-product-list").performScrollToNode(hasText("ROOT-026",substring=true))
        val cutoverScroll=scrollValue()
        compose.onNodeWithText("ROOT-026",substring=true).performClick()
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement("Unsaved draft across actual activation")
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        capture("android-root-held-second-draft.png")
        release.complete(Unit)
        val recovered = runBlocking { withTimeout(15_000) { recovery.await() } }
        assertTrue("$recovered; actual isolated cause=${recoveryFailure.get()?.stackTraceToString()}", recovered is ShopSyncRecoveryResult.Activated)
        assertEquals("cutover preserves the live Room writer and WAL",walBefore,db.openHelper.writableDatabase.isWriteAheadLoggingEnabled)
        assertEquals(saved.id,runBlocking { repository.findProductByBarcode("ROOT-024") }?.id)
        val ready = runBlocking { repository.resolveBusinessDataScope(ownerScope()) }
        assertTrue(ready.toString(), ready.allowsCloudSync)
        compose.runOnIdle { tracker.updateBusinessDataScopeState(ready) }
        auto!!.onDeviceStatusActive() // Same automatic production trigger following active device/recovery.
        compose.waitUntil(15_000) { cloud.products.values.any { it.barcode=="ROOT-024" && it.productName==saved.productName } &&
            runBlocking { !db.productRemoteRefDao().hasPendingWork() } }
        assertEquals("ROOT-",databaseVM.filter.value)
        compose.onNodeWithTag("root-tab-databaseScreen").assertIsSelected()
        compose.onNodeWithText("Unsaved draft across actual activation").assertExists()
        compose.onNodeWithTag("product-editor-name").assertIsFocused()
        assertEquals(cutoverScroll,scrollValue(),0f)
        capture("android-root-activated-draft.png")
        try {
            compose.waitUntil(10_000) { databaseVM.lastProductSaveCloudConfirmed.value==true }
        } catch (failure: Throwable) {
            val diagnostic=runBlocking {
                "vm=${databaseVM.lastProductSaveCloudConfirmed.value}, hotDao=${hotDao.value}, hotFailure=${hotFailure.value}, roomAfter=${roomDiagnostics(db)}, dao=${repository.observeProductCloudConfirmed(saved.id).first()}, " +
                    "ref=${db.productRemoteRefDao().getByProductId(saved.id)}, priceBridgePending=${db.productPriceDao().countPriceRowsPendingPriceBridge()}, " +
                    "remotePrices=${price.count()}, receipts=" + db.syncEventOutboxDao().listPending(OWNER,100).map {
                        "${it.domain}:${it.attemptCount}:${it.lastErrorType}" } + ", cloud=${tracker.state.value}"
            }
            throw AssertionError(diagnostic,failure)
        }
        compose.onNodeWithTag("product-editor-save").performClick()
        compose.waitUntil(15_000) { cloud.products.values.any { it.barcode=="ROOT-026" &&
            it.productName=="Unsaved draft across actual activation" } &&
            runBlocking { !db.productRemoteRefDao().hasPendingWork() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(
            com.example.merchandisecontrolsplitview.R.string.product_save_cloud_confirmed)).fetchSemanticsNodes().isNotEmpty() }
        capture("android-root-activated-saved.png")
        auto!!.shutdown();auto=null
        runBlocking { job.cancelAndJoin() }
        compose.runOnIdle { visible.value = false }
        compose.runOnUiThread { store.clear() }
        db.close();database=null
        val reopened = openDatabase()
        try {
            val durable=runBlocking { DefaultInventoryRepository(reopened).findProductByBarcode("ROOT-024") }
            assertEquals(saved.id,durable?.id);assertEquals(saved.productName,durable?.productName)
            assertFalse(runBlocking { reopened.productRemoteRefDao().hasPendingWork() })
            val second=runBlocking { DefaultInventoryRepository(reopened).findProductByBarcode("ROOT-026") }
            assertEquals("Unsaved draft across actual activation",second?.productName)
            assertTrue(runBlocking { DefaultInventoryRepository(reopened).resolveOfflineBusinessDataScope(ownerScope(),true) }.allowsLocalOperations)
            reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { reopened.close() }
    }

    private suspend fun roomDiagnostics(db:AppDatabase):String = db.withTransaction {
        val sql=db.openHelper.readableDatabase
        val triggers=sql.query("SELECT name FROM sqlite_temp_master WHERE type='trigger' AND name LIKE 'room_%' ORDER BY name").use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val invalidated=try { sql.query("SELECT table_id,invalidated FROM room_table_modification_log ORDER BY table_id").use { c ->
            buildList { while(c.moveToNext()) add("${c.getLong(0)}:${c.getLong(1)}") } } }
            catch(error:android.database.sqlite.SQLiteException) { listOf("tracker_table_absent:${error.javaClass.simpleName}") }
        "triggers=$triggers; invalidated=$invalidated"
    }

    private fun scrollValue(): Float = compose.onNodeWithTag("database-product-list",useUnmergedTree=true)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun capture(name: String) {
        val file=File(context.filesDir,"local-availability-root/$name");file.parentFile!!.mkdirs()
        val target = if (name.contains("draft")) compose.onNodeWithTag("task141.edit.dialog-root") else compose.onRoot()
        file.outputStream().use { target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun clearEvidence(vararg names:String) {
        names.forEach { File(context.filesDir,"local-availability-root/$it").delete() }
    }
    private fun openDatabase() = Room.databaseBuilder(context,AppDatabase::class.java,DB_NAME)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray()).build().also { it.openHelper.writableDatabase }
    private fun coordinator(db: AppDatabase,repo: DefaultInventoryRepository,remote: ShopSyncReadRemoteDataSource,
        tracker: CatalogSyncStateTracker?=null) = ShopSyncRecoveryCoordinator(context,db,repo,remote,
        registerDeviceForRecovery={ Result.success(ShopDeviceRegistrationResult(ok=true,code="success",shopId=it)) },
        scopeStillValid={ account,selected -> account==OWNER && selected==SHOP },
        activationBoundary={ block -> if (tracker==null) block() else tracker.withBusinessDataScopeTransition { block() } },
        availableStorageBytes={Long.MAX_VALUE})
    private fun shop() = SelectedShop(SHOP,"ROOT","Isolated root test","shop_owner","active",true)
    private fun ownerScope() = task126ActiveOwnerStoreScope(OWNER,shop())
    private fun journal(mode: String) = SyncRecoveryJournal(ownerHash=ownerScope().ownerHash,
        storeScope=ownerScope().storeId,shopId=SHOP,deviceId=DEVICE,authorizationMode=mode,
        phase=SyncRecoveryJournalPhases.REQUIRED,reason=if(mode==SyncRecoveryAuthorizationModes.SAME_SCOPE) "root held recovery" else SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
        blockingEventId=42L,attemptCount=0,createdAtMs=1L,updatedAtMs=1L,nextRetryAtMs=1L)
    private companion object {
        const val DB_NAME="task143-local-availability-root.db"
        const val OWNER="10000000-0000-4000-8000-000000000001"
        const val SHOP="10000000-0000-4000-8000-000000000003"
        const val DEVICE="android-recovery-force-stop-device"
    }
}

private class RootCatalogRemote : CatalogRemoteDataSource {
    override val isConfigured=true
    val products=ConcurrentHashMap<String,InventoryProductRow>()
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> { rows.forEach { products[it.id]=it };return Result.success(Unit) }
    override suspend fun patchProduct(id:String,ownerUserId:String,patch:InventoryProductPatch):Result<Unit> {
        val previous=products[id] ?: return Result.failure(IllegalStateException("root adapter product missing"))
        if(patch.changedFields!=setOf("productname")) return Result.failure(IllegalStateException("root adapter unexpected patch"))
        products[id]=previous.copy(productName=patch.productName)
        return Result.success(Unit)
    }
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>)=Result.success(Unit)
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>)=Result.success(Unit)
    override suspend fun fetchCatalog()=Result.success(InventoryCatalogFetchBundle(emptyList(),emptyList(),products.values.toList()))
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>,categoryIds: Set<String>,productIds: Set<String>)=
        Result.success(InventoryCatalogFetchBundle(emptyList(),emptyList(),products.values.filter { it.id in productIds },false))
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch)=Result.success(Unit)
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch)=Result.success(Unit)
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch)=Result.success(Unit)
}
private class RootPriceRemote : ProductPriceRemoteDataSource {
    override val isConfigured=true
    private val rows=ConcurrentHashMap<String,InventoryProductPriceRow>()
    fun count()=rows.size
    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> { rows.forEach { this.rows[it.id]=it };return Result.success(Unit) }
    override suspend fun fetchProductPrices()=Result.success(rows.values.toList())
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>)=Result.success(rows.values.filter { it.id in remoteIds })
}

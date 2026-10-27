package com.example.merchandisecontrolsplitview.ui.navigation

import android.graphics.Bitmap
import android.app.Application
import android.os.SystemClock
import com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks
import com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event
import com.example.merchandisecontrolsplitview.productimage.ProductImageApiClient
import com.example.merchandisecontrolsplitview.productimage.ProductImageService
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
    private var ownsT20Fixture = false
    private var talkBackCheckpoint: Task144TalkBackCheckpoint? = null

    @After fun cleanup() {
        var primary: Throwable? = null
        try {
            release.complete(Unit)
            auto?.shutdown()
            runBlocking { job.cancelAndJoin() }
            compose.runOnUiThread { store.clear() }
            database?.close()
            if (!ownsT20Fixture) context.deleteDatabase(DB_NAME)
            MobileSyncInteractionTestHooks.observer = null
            MobileSyncInteractionTestHooks.observationError = null
            ShopSyncRecoveryTestHooks.reset()
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try { talkBackCheckpoint?.close() }
            catch (cleanup: Throwable) { val first = primary; if (first != null) first.addSuppressed(cleanup) else throw cleanup }
        }
    }


    /** Native UI components only: this does not manufacture a process cold/warm launch. */
    @Test fun largeLocalUiComponentsIdle() = runLargeLocalUiComponents(recovery = false)
    @Test fun largeLocalUiComponentsDuringRecovery() = runLargeLocalUiComponents(recovery = true)

    @Test fun filePickerContextSurvivesSameScopeRequalification() = runLargeLocalUiComponents(false, Screen.FilePicker.route)
    @Test fun databaseContextSurvivesSameScopeRequalification() = runLargeLocalUiComponents(false, Screen.Database.route)
    @Test fun historyContextSurvivesSameScopeRequalification() = runLargeLocalUiComponents(false, Screen.History.route)
    @Test fun preGenerateContextSurvivesSameScopeRequalification() = runLargeLocalUiComponents(false, Screen.PreGenerate.route)
    @Test fun generatedContextSurvivesSameScopeRequalification() = runLargeLocalUiComponents(false, Screen.Generated.route)
    @Test fun importAnalysisContextSurvivesSameScopeRequalification() = runLargeLocalUiComponents(false, Screen.ImportAnalysis.route)

    @Test fun databaseEditorWindowSuspendsAndRestoresDraftFocusAndSelection() =
        runLargeLocalUiComponents(false, Screen.Database.route, editorWindow = true)

    @Test fun preGenerateKeepsDraftAndRouteAcrossSameScopeRequalification() =
        runLargeLocalUiComponents(recovery = false, routeRed = true)

    private fun runLargeLocalUiComponents(recovery: Boolean, contextualRoute: String? = null, editorWindow: Boolean = false,
        routeRed: Boolean = false) {
        check(!routeRed || (contextualRoute == null && !editorWindow && !recovery)) {
            "Exact route07 and matrix/window gates require separate invocations"
        }
        check(context.applicationContext.javaClass == Application::class.java) {
            "T20 UI requires MobileSyncPerformanceTestRunner on a dedicated target"
        }
        val accessibility = Task144AccessibilityConfiguration.fromArguments(context)
        ownsT20Fixture = true
        val runRoot = File(context.cacheDir,
            "mobile-sync-t20/ui-${if (recovery) "recovery" else "idle"}-${UUID.randomUUID()}")
        check(runRoot.mkdirs())
        val talkBack = Task144TalkBackCheckpoint.fromArguments(context, runRoot).also { talkBackCheckpoint = it }
        val owned = T20IsolatedDatabaseContext(context, runRoot)
        val uiStrings = accessibility?.localizedContext(owned) ?: owned
        val db = Room.databaseBuilder(owned, AppDatabase::class.java, "t20-ui-active.db")
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray()).build()
        val output = File(runRoot, "ui-component-samples.jsonl").also { check(it.createNewFile()) }
        val clockId = "android-ui-process-${UUID.randomUUID()}"
        fun append(row: JsonObject) {
            FileOutputStream(output, true).use { stream ->
                stream.write((row.toString() + "\n").toByteArray()); stream.fd.sync()
            }
            println("T20_UI_COMPONENT_SAMPLE $row")
        }
        var tracker: CatalogSyncStateTracker? = null
        val fixture = T20NativeRecoveryFixture(owned, db, activationBoundary = { block ->
            val activeTracker = tracker
            if (activeTracker == null) block() else {
                val observer = ShopSyncRecoveryTestHooks.activationBoundaryTiming
                try {
                    activeTracker.withBusinessDataScopeTransition {
                        ShopSyncRecoveryTestHooks.observeTiming(observer, "acquired")
                        block()
                    }
                } finally { ShopSyncRecoveryTestHooks.observeTiming(observer, "released") }
            }
        }, heldPageTimeoutMs = 90_000L)
        var auth: SupabaseAuthManager? = null
        var images: ProductImageService? = null
        var appScope: CoroutineScope? = null
        var activeSample: UiComponentTiming? = null
        var cutoverTiming: T20ActivationTimingCapture? = null
        val measured = mutableMapOf<String, MutableList<Double>>()
        var currentIndex = 0
        var currentKind = "fixture_setup"
        val ownedViewModelJobs = mutableListOf<Job>()
        var terminalFailure: Throwable? = null
        try {
            runBlocking { fixture.seedViaRecovery() }
            val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = fixture.remote)
            val business = runBlocking { initial.resolveBusinessDataScope(fixture.scope) }
            check(business.allowsLocalOperations)
            val liveTracker = CatalogSyncStateTracker(business)
            tracker = liveTracker
            val repository = DefaultInventoryRepository(db, liveTracker, shopSyncReadRemoteDataSource = fixture.remote)
            val ownedApp = object : MerchandiseControlApplication() {
                fun attach() = attachBaseContext(uiStrings)
            }.apply { attach() }
            fun install(name: String, value: Any?) = MerchandiseControlApplication::class.java
                .getDeclaredField(name).apply { isAccessible = true }.set(ownedApp, value)
            val ownedScope: CoroutineScope = scopeSwitchField(ownedApp, "appScope")
            appScope = ownedScope
            val ownedAuth = SupabaseAuthManager(client = null, googleWebClientId = "", scope = ownedScope)
            auth = ownedAuth
            scopeSwitchField<MutableStateFlow<AuthState>>(ownedAuth, "_state").value =
                AuthState.SignedIn(fixture.owner, "t20-ui@example.invalid")
            val selected = object : SelectedShopStore {
                override fun getSelectedShopId(ownerUserId: String) = fixture.shop
                override fun setSelectedShopId(ownerUserId: String, shopId: String) { check(shopId == fixture.shop) }
                override fun clearSelectedShopId(ownerUserId: String) = Unit
            }
            val linked = object : LinkedShopRemoteDataSource {
                override val isConfigured = true
                override suspend fun fetchLinkedShops() = Result.success(listOf(LinkedShop(
                    fixture.shop, "T20", "T20 synthetic", "shop_owner", "active",
                    membershipStatus = "active", shopStatus = "active", selectable = true, canWrite = true)))
            }
            val shops = ShopContextRepository(linked, selected, { fixture.owner }, { fixture.device })
            runBlocking { shops.refresh(fixture.owner) }
            check(shops.state.value.localAccessAllowed)
            install("database\$delegate", lazyOf(db)); install("repository\$delegate", lazyOf(repository))
            install("authManager\$delegate", lazyOf(ownedAuth)); install("shopContextRepository\$delegate", lazyOf(shops))
            install("catalogSyncStateTracker\$delegate", lazyOf(liveTracker)); install("supabaseClient\$delegate", lazyOf(null))
            install("shopSyncReadRemoteDataSource\$delegate", lazyOf(fixture.remote))
            val ownedImages = ProductImageService(owned, db, ProductImageApiClient("", "", debugBuild = true),
                accountIdProvider = { fixture.owner }, selectedShopProvider = { shops.state.value.selectedShop },
                accessTokenProvider = { null }, businessDataScopeAllowed = liveTracker::allowsBusinessDataScope,
                businessDataScopeRuntimeGuard = liveTracker)
            images = ownedImages
            install("productImageServiceDelegate", lazyOf(ownedImages))
            lateinit var databaseVM: DatabaseViewModel
            lateinit var excelVM: ExcelViewModel
            compose.runOnUiThread {
                databaseVM = DatabaseViewModel(ownedApp, repository); store.put("database", databaseVM)
                excelVM = ExcelViewModel(ownedApp, repository); store.put("excel", excelVM)
                databaseVM.viewModelScope.coroutineContext[Job]?.let(ownedViewModelJobs::add)
                excelVM.viewModelScope.coroutineContext[Job]?.let(ownedViewModelJobs::add)
            }
            // The observer only writes primitive timestamps. No Room/JSON/files/AX work is timed.
            val sampleRef = AtomicReference<UiComponentTiming?>(null)
            MobileSyncInteractionTestHooks.observer = { event, id, detail, nanos ->
                sampleRef.get()?.observe(event, id, detail, nanos)
            }
            var heldAt = 0L
            if (recovery) {
                runBlocking { fixture.beginHeldRecovery() }
                heldAt = SystemClock.elapsedRealtimeNanos()
                liveTracker.updateBusinessDataScopeState(runBlocking {
                    repository.resolveOfflineBusinessDataScope(fixture.scope, true)
                })
            }
            val nativeView = AtomicReference<android.view.View?>(null)
            compose.setContent {
                if (contextualRoute != null) nativeView.set(androidx.compose.ui.platform.LocalView.current)
                val state by liveTracker.businessDataScopeState.collectAsState()
                val progress by liveTracker.state.collectAsState()
                val rootContent: @androidx.compose.runtime.Composable () -> Unit = {
                    if (visible.value) AppNavGraphContent(ownedApp, excelVM, databaseVM, true,
                        ownedAuth.state.value, progress, shops.state.value, state)
                }
                if (accessibility == null) MerchandiseControlTheme(darkTheme = false, content = rootContent)
                else accessibility.Content(uiStrings, content = rootContent)
            }
            if (accessibility != null) {
                compose.waitForIdle()
                accessibility.writeWitness(File(runRoot, "accessibility-configuration.json"))
            }
            if (routeRed) {
                currentKind = "pre_generate_route_requalification"
                fun witness(phase: String, state: Task126BusinessDataScopeState) {
                    append(buildJsonObject {
                        put("kind", "route_witness"); put("phase", phase); put("status", "OBSERVED")
                        put("expected_route", Screen.PreGenerate.route)
                        put("options_tab_present", compose.onAllNodesWithTag("root-tab-optionsScreen").fetchSemanticsNodes().isNotEmpty())
                        put("global_placeholder_present", compose.onAllNodesWithTag("root-business-placeholder").fetchSemanticsNodes().isNotEmpty())
                        put("supplier_draft", databaseVM.supplierInputText.value)
                        put("scope_status", state.status.name); put("local_reads_allowed", state.allowsLocalOperations)
                        put("bound_scope_unchanged", state.boundScope == business.boundScope)
                        put("at_ns", SystemClock.elapsedRealtimeNanos())
                        put("acceptance_boundary", "actual NavHost + real owned file input; controlled capability transitions; no auth restore/live TEST claim")
                    })
                    val file = File(runRoot, "route-$phase.png")
                    check(file.createNewFile())
                    FileOutputStream(file).use { stream ->
                        check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
                        stream.fd.sync()
                    }
                }
                val sourceFile = File(owned.filesDir, "r23-route-source.xlsx")
                check(sourceFile.createNewFile())
                org.apache.poi.xssf.usermodel.XSSFWorkbook().use { workbook ->
                    val sheet = workbook.createSheet("Products")
                    sheet.createRow(0).apply {
                        createCell(0).setCellValue("Barcode")
                        createCell(1).setCellValue("Product Name")
                        createCell(2).setCellValue("Purchase Price")
                        createCell(3).setCellValue("Retail Price")
                    }
                    sheet.createRow(1).apply {
                        createCell(0).setCellValue("R23-ROUTE-ONE")
                        createCell(1).setCellValue("Owned route input")
                        createCell(2).setCellValue(3.0)
                        createCell(3).setCellValue(5.0)
                    }
                    FileOutputStream(sourceFile).use { workbook.write(it) }
                }
                compose.waitForIdle()
                compose.runOnUiThread {
                    check(com.example.merchandisecontrolsplitview.MainActivity.ShareBus.uris.tryEmit(
                        listOf(android.net.Uri.fromFile(sourceFile))))
                }
                compose.waitUntil(30_000L) {
                    !excelVM.isLoading.value && (excelVM.excelData.size > 1 || excelVM.loadError.value != null)
                }
                check(excelVM.loadError.value == null) { "owned route XLSX did not load: ${excelVM.loadError.value}" }
                check(excelVM.excelData.size > 1) { "owned route XLSX has no parsed row" }
                compose.onNodeWithText(uiStrings.getString(R.string.preview_file_title)).assertExists()
                // Lazy content may not yet contain the offscreen supplier field. Materialize its real item first.
                compose.waitUntil(10_000L) {
                    compose.onAllNodes(hasScrollToKeyAction()).fetchSemanticsNodes().size == 1
                }
                witness("ready-before-supplier", business)
                File(runRoot, "route-ready-semantics.txt").writeText(compose.onRoot().printToString())
                compose.onNode(hasScrollToKeyAction()).performScrollToKey("supplier")
                compose.onNode(hasSetTextAction() and hasText(uiStrings.getString(R.string.search_or_add_supplier)))
                    .performClick().performTextReplacement("R23 route draft")
                val draftField = hasSetTextAction() and hasText("R23 route draft")
                compose.onNode(draftField).assertIsFocused()
                val admittedError = business.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    localAccessScope = requireNotNull(business.boundScope), errorCode = "controlled_transient")
                check(admittedError.allowsLocalOperations)
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(admittedError) }
                compose.onNode(draftField).assertIsDisplayed().assertIsFocused()
                compose.onNodeWithTag("root-tab-optionsScreen").assertDoesNotExist()
                witness("admitted-transient", admittedError)
                // Requalification temporarily withdraws business reads, while owner/store identity stays unchanged.
                val checking = business.copy(status = Task126BusinessDataScopeStatus.CHECKING,
                    localAccessScope = null, localReadsAllowed = false, localWritesAllowed = false)
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(checking) }
                compose.onNode(draftField).assertDoesNotExist() // no unqualified business render
                compose.onNodeWithTag("root-tab-optionsScreen").assertDoesNotExist()
                witness("checking", checking)
                val waiting = checking.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    errorCode = "controlled_requalification_transport_failure")
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(waiting) }
                witness("requalification-error", waiting)
                // This is the first behavioral RED in the old root: its effect forces a non-root route to Options.
                compose.onNodeWithTag("root-tab-optionsScreen").assertDoesNotExist()
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(business) }
                compose.onNodeWithText(uiStrings.getString(R.string.preview_file_title)).assertExists()
                compose.onNode(draftField).assertIsDisplayed()
                check(databaseVM.supplierInputText.value == "R23 route draft") { "same-scope requalification replaced the draft" }
                witness("restored", business)
                append(buildJsonObject {
                    put("kind", "route_red_result"); put("status", "PASS")
                    put("route", Screen.PreGenerate.route); put("draft_restored", true)
                    put("six_route_matrix", "NOT_RUN_SEPARATE_DOWNSTREAM_GATE")
                })
                return
            }
            if (contextualRoute != null) {
                currentKind = "contextual_route:$contextualRoute"
                val inputText = if (contextualRoute == Screen.Database.route && !editorWindow) fixture.firstBarcode else "R23 unsaved route input"
                var input: SemanticsMatcher? = null
                val sourceFile = File(owned.filesDir, "contextual-route-source.xlsx")
                if (contextualRoute == Screen.PreGenerate.route || contextualRoute == Screen.ImportAnalysis.route) {
                    check(sourceFile.createNewFile())
                    org.apache.poi.xssf.usermodel.XSSFWorkbook().use { workbook ->
                        val sheet = workbook.createSheet("Products")
                        sheet.createRow(0).apply {
                            createCell(0).setCellValue("Barcode"); createCell(1).setCellValue("Product Name")
                            createCell(2).setCellValue("Purchase Price"); createCell(3).setCellValue("Retail Price")
                        }
                        sheet.createRow(1).apply {
                            createCell(0).setCellValue("R23-CONTEXT-ONE"); createCell(1).setCellValue("Contextual route input")
                            createCell(2).setCellValue(3.0); createCell(3).setCellValue(5.0)
                        }
                        FileOutputStream(sourceFile).use { workbook.write(it) }
                    }
                }
                when (contextualRoute) {
                    Screen.FilePicker.route -> compose.onNodeWithText(uiStrings.getString(R.string.load_excel_file)).assertIsDisplayed()
                    Screen.Database.route -> {
                        compose.onNodeWithTag("root-tab-databaseScreen").performClick()
                        compose.onNodeWithTag("database-search").performClick().performTextReplacement(if (editorWindow) fixture.firstBarcode else inputText)
                        input = hasTestTag("database-search")
                    }
                    Screen.History.route -> compose.onNodeWithTag("root-tab-history").performClick()
                    Screen.PreGenerate.route -> {
                        compose.waitForIdle()
                        compose.runOnUiThread { check(com.example.merchandisecontrolsplitview.MainActivity.ShareBus.uris
                            .tryEmit(listOf(android.net.Uri.fromFile(sourceFile)))) }
                        compose.waitUntil(30_000L) { !excelVM.isLoading.value && excelVM.excelData.size > 1 }
                        check(excelVM.loadError.value == null)
                        compose.onNodeWithText(uiStrings.getString(R.string.preview_file_title)).assertIsDisplayed()
                        compose.waitUntil(10_000L) {
                            compose.onAllNodes(hasScrollToKeyAction()).fetchSemanticsNodes().size == 1
                        }
                        compose.onNode(hasScrollToKeyAction()).performScrollToKey("supplier")
                        compose.onNode(hasSetTextAction() and hasText(uiStrings.getString(R.string.search_or_add_supplier)))
                            .performClick().performTextReplacement(inputText)
                        input = hasSetTextAction() and hasText(inputText)
                    }
                    Screen.Generated.route -> {
                        compose.onNodeWithText(uiStrings.getString(R.string.add_products_manually)).performScrollTo().performClick()
                        compose.waitUntil(30_000L) { excelVM.currentEntryStatus.value.third > 0L && excelVM.generated.value }
                        compose.onNodeWithTag("root-tab-optionsScreen").assertDoesNotExist()
                    }
                    Screen.ImportAnalysis.route -> {
                        // Same public command used by file-picker result; NavGraph's actual result effect navigates.
                        compose.runOnUiThread { databaseVM.startImportAnalysis(owned, android.net.Uri.fromFile(sourceFile), ImportNavOrigin.DATABASE) }
                        compose.waitUntil(30_000L) { databaseVM.importAnalysisResult.value != null }
                        compose.onNodeWithText(uiStrings.getString(R.string.import_analysis_title)).assertIsDisplayed()
                    }
                    else -> error("Unregistered route probe")
                }
                compose.waitForIdle()
                if (contextualRoute == Screen.Database.route) {
                    compose.waitUntil(10_000L) { compose.onAllNodes(hasText(fixture.firstBarcode, substring = true) and
                        hasAnyAncestor(hasTestTag("database-product-list"))).fetchSemanticsNodes().size == 1 }
                }
                val originalProduct = if (editorWindow) runBlocking {
                    requireNotNull(repository.findProductByBarcode(fixture.firstBarcode))
                } else null
                if (editorWindow) {
                    compose.onNode(hasText(fixture.firstBarcode, substring = true) and
                        hasAnyAncestor(hasTestTag("database-product-list"))).performClick()
                    compose.onNodeWithTag("task141.edit.dialog-root").assertIsDisplayed()
                    compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement(inputText)
                    compose.onNodeWithTag("product-editor-name")
                        .performSemanticsAction(SemanticsActions.SetSelection) { check(it(3, 9, false)) }
                    input = hasTestTag("product-editor-name")
                }
                val originalEntry = excelVM.currentEntryStatus.value.third
                val originalPreview = databaseVM.importAnalysisResult.value
                val originalSupplier = databaseVM.supplierInputText.value
                fun scrollValues(): List<Float> = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() }
                val originalScroll = scrollValues()
                fun focusedInputWindow(): android.view.View? = if (!editorWindow) nativeView.get()?.rootView else {
                    val activityRoot = nativeView.get()?.rootView
                    android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .map { it.rootView }.distinct().singleOrNull {
                            it !== activityRoot && it.visibility == android.view.View.VISIBLE && it.hasWindowFocus()
                        }
                }
                fun keyboardShown() = focusedInputWindow()?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                fun witness(phase: String, state: Task126BusinessDataScopeState) {
                    append(buildJsonObject {
                        put("kind", "contextual_route_witness"); put("status", "OBSERVED"); put("route", contextualRoute)
                        put("phase", phase); put("scope_status", state.status.name); put("local_reads_allowed", state.allowsLocalOperations)
                        put("same_bound_scope", state.boundScope == business.boundScope)
                        put("global_placeholder", compose.onAllNodesWithTag("root-business-placeholder").fetchSemanticsNodes().isNotEmpty())
                        val inputWindow = focusedInputWindow()
                        put("keyboard_shown", inputWindow?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                        put("input_window_identity", inputWindow?.let { System.identityHashCode(it).toString() }?.let(::JsonPrimitive) ?: JsonNull)
                        put("input_window_is_activity", inputWindow != null && inputWindow === nativeView.get()?.rootView)
                        put("input_window_focused", inputWindow?.hasWindowFocus() == true)
                        put("input_focus_required", input != null)
                        put("entry_uid", excelVM.currentEntryStatus.value.third)
                        put("editor_window_probe", editorWindow)
                        put("editor_window_visible", compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isNotEmpty())
                        put("pending_import_preserved", originalPreview == databaseVM.importAnalysisResult.value)
                        put("pending_supplier_preserved", originalSupplier == databaseVM.supplierInputText.value)
                        put("scroll_values", JsonArray(scrollValues().map { JsonPrimitive(it) }))
                        put("acceptance_boundary", "controlled same-scope capability; actual NavHost and VM commands; no TalkBack or auth-restore claim")
                    })
                    val image = File(runRoot, "context-${contextualRoute.substringBefore('/') }-$phase.png")
                    check(image.createNewFile())
                    FileOutputStream(image).use { out ->
                        val target = if (editorWindow && compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isNotEmpty())
                            compose.onNodeWithTag("task141.edit.dialog-root") else compose.onRoot()
                        check(target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)); out.fd.sync()
                    }
                }
                witness("ready", business)
                if (input != null) {
                    currentKind = "contextual_route:$contextualRoute:ime_precondition"
                    compose.onNode(requireNotNull(input)).assertIsFocused()
                    compose.waitUntil(5_000L) { keyboardShown() }
                    currentKind = "contextual_route:$contextualRoute"
                }
                val transient = business.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
                    localAccessScope = requireNotNull(business.boundScope), errorCode = "controlled_local_admitted_transport")
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(transient) }
                input?.let { compose.onNode(it).assertIsFocused().assertTextContains(inputText); check(keyboardShown()) }
                assertEquals(originalScroll, scrollValues())
                witness("admitted-transient", transient)
                val checking = business.copy(status = Task126BusinessDataScopeStatus.CHECKING,
                    localAccessScope = null, localReadsAllowed = false, localWritesAllowed = false)
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(checking) }
                witness("checking", checking)
                compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
                // Route-specific context is an explicit presentation contract, not a TalkBack proof.
                compose.onNodeWithTag("route-context-$contextualRoute").assertIsDisplayed()
                if (editorWindow) {
                    compose.onAllNodes(isDialog()).assertCountEquals(0)
                    compose.onNodeWithTag("task141.edit.dialog-root").assertDoesNotExist()
                    val result = runBlocking { databaseVM.saveProductFromEditor(requireNotNull(originalProduct).copy(productName = "Must not commit")) }
                    check(result !is com.example.merchandisecontrolsplitview.viewmodel.ProductEditorSaveResult.Saved)
                    assertEquals(originalProduct, runBlocking { repository.findProductByBarcode(fixture.firstBarcode) })
                }
                listOf(R.string.no_products_in_db, R.string.pre_generate_empty_state_message).forEach { id ->
                    compose.onAllNodesWithText(uiStrings.getString(id)).assertCountEquals(0)
                }
                val waiting = checking.copy(status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "controlled_requalification_wait")
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(waiting) }
                witness("waiting", waiting)
                compose.onNodeWithTag("route-context-$contextualRoute").assertIsDisplayed()
                compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
                compose.runOnIdle { liveTracker.updateBusinessDataScopeState(business) }
                compose.waitForIdle()
                assertEquals(originalEntry, excelVM.currentEntryStatus.value.third)
                assertEquals(originalPreview, databaseVM.importAnalysisResult.value)
                assertEquals(originalSupplier, databaseVM.supplierInputText.value)
                input?.let { compose.onNode(it).assertIsFocused().assertTextContains(inputText); check(keyboardShown()) }
                assertEquals(originalScroll, scrollValues())
                if (editorWindow) {
                    compose.onNodeWithTag("task141.edit.dialog-root").assertIsDisplayed()
                    assertEquals(androidx.compose.ui.text.TextRange(3, 9),
                        compose.onNodeWithTag("product-editor-name").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                    assertEquals(originalProduct, runBlocking { repository.findProductByBarcode(fixture.firstBarcode) })
                }
                witness("restored", business)
                check(runBlocking { db.productDao().count() } == 20_000)
                // Existing functional assertions finish before this distinct real-input observation window.
                talkBack?.hold("restored", contextualRoute)
                append(buildJsonObject { put("kind", "contextual_route_result"); put("status", "PASS"); put("route", contextualRoute) })
                return
            }
            fun measure(kind: String, start: Event, end: Event, id: Long = 0L,
                detail: String? = null, maxMs: Double, action: () -> Unit, verify: () -> Unit) {
                currentKind = kind
                val sample = UiComponentTiming(start, end, id, detail)
                activeSample = sample
                sampleRef.set(sample)
                var failure: Throwable? = null
                var readbackVerified = false
                var failureGate = "input_or_draw"
                try {
                    if (recovery) check(fixture.isRecoveryHeld) { "Recovery not held before UI action" }
                    action()
                    compose.waitUntil(10_000L) { sample.endNs != 0L }
                    sampleRef.set(null)
                    failureGate = "data_and_ui_readback"
                    verify()
                    check(MobileSyncInteractionTestHooks.observationError == null) { "UI observation failed" }
                    check(ShopSyncRecoveryTestHooks.timingObservationError == null) { "Activation observation failed" }
                    if (recovery) check(fixture.isRecoveryHeld) { "Recovery ended during UI action" }
                    readbackVerified = true
                    val ms = (sample.endNs - sample.startNs) / 1_000_000.0
                    if (currentIndex > 3) {
                        measured.getOrPut(kind) { mutableListOf() }.add(ms)
                        failureGate = "performance_max"
                        check(ms <= maxMs) { "$kind exceeded max: $ms > $maxMs ms" }
                        val p95Limit = when (kind) {
                            "query_selective", "query_broad", "save" -> 300.0
                            "editor" -> 500.0
                            else -> 1_000.0
                        }
                        // The second over-budget sample makes rank 29 of 30 irreversibly fail.
                        failureGate = "performance_p95_fixed30"
                        check(measured.getValue(kind).count { it > p95Limit } < 2) {
                            "$kind fixed 30-sample p95 cannot pass: two samples exceed $p95Limit ms"
                        }
                    }
                } catch (error: Throwable) { failure = error } finally {
                    sampleRef.set(null)
                    append(buildJsonObject {
                        put("sample_id", "T20-UI-${if (recovery) "recovery" else "idle"}-$currentIndex-$kind")
                        put("kind", kind); put("status", if (failure == null) "PASS" else "FAIL")
                        put("reason", failure?.message.orEmpty()); put("warmup", currentIndex <= 3)
                        put("measurement_number", maxOf(0, currentIndex - 3))
                        put("clock_id", clockId); put("start_ns", sample.startNs); put("end_ns", sample.endNs)
                        if (sample.endNs > 0L) put("elapsed_ms", (sample.endNs - sample.startNs) / 1_000_000.0)
                        put("condition", if (recovery) "recovery" else "idle")
                        put("timestamp_utc", Instant.now().toString()); put("product_id", id)
                        put("products", 20_000); put("prices", 40_000); put("readback_verified", readbackVerified)
                        put("failure_gate", if (failure == null) "" else failureGate)
                        put("timing_source", "app_handler_to_in_process_draw_or_repository_return")
                        put("acceptance_boundary", "native UI component; no cold/warm launch, OS offline, live auth or backend acceptance")
                        if (kind == "save") put("durable_boundary", "repository return; postcommit scope exception yields no successful sample")
                    })
                }
                failure?.let { throw it }
            }
            fun productRow(barcode: String) = compose.onNode(
                hasText(barcode, substring = true) and hasAnyAncestor(hasTestTag("database-product-list"))
            )
            repeat(33) { offset ->
                currentIndex = offset + 1
                measure("root_tab", Event.ROOT_TAB_CLICK, Event.ROOT_TAB_DRAW,
                    detail = Screen.History.route, maxMs = 2_000.0,
                    action = { compose.onNodeWithTag("root-tab-history").performClick() },
                    verify = { compose.onNodeWithTag("root-tab-history").assertIsSelected() })
                compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
                // Different exact barcode every time prevents an old paging generation satisfying the draw.
                val barcode = "T20-${(1000 + currentIndex).toString().padStart(8, '0')}"
                val row = runBlocking { requireNotNull(repository.findProductByBarcode(barcode)) }
                measure("query_selective", Event.FILTER_INPUT, Event.PRODUCT_DRAW, row.id, barcode, 1_000.0,
                    action = { compose.onNodeWithTag("database-search").performTextReplacement(barcode) },
                    verify = { productRow(barcode).assertIsDisplayed() })
                measure("editor", Event.EDITOR_OPEN, Event.EDITOR_DRAW, row.id, maxMs = 1_000.0,
                    action = { productRow(barcode).performClick() },
                    verify = { compose.onNodeWithTag("task141.edit.dialog-root").assertIsDisplayed() })
                val desiredName = "T20 UI ${if (recovery) "recovery" else "idle"} $currentIndex"
                compose.onNodeWithTag("product-editor-name").performScrollTo().performTextReplacement(desiredName)
                measure("save", Event.SAVE_CLICK, Event.SAVE_DURABLE_RETURN, row.id, maxMs = 1_000.0,
                    action = { compose.onNodeWithTag("product-editor-save").performClick() },
                    verify = {
                        assertEquals(desiredName, runBlocking { db.productDao().getById(row.id) }?.productName)
                        compose.waitUntil(10_000) {
                            compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isEmpty()
                        }
                        assertEquals(40_000, runBlocking { db.productPriceDao().countAll() })
                    })
                val broad = "T20-0000"
                val first = runBlocking { requireNotNull(repository.findProductByBarcode(fixture.firstBarcode)) }
                measure("query_broad", Event.FILTER_INPUT, Event.PRODUCT_DRAW, first.id, broad, 1_000.0,
                    action = { compose.onNodeWithTag("database-search").performTextReplacement(broad) },
                    verify = { productRow(fixture.firstBarcode).assertIsDisplayed() })
            }
            for ((kind, samples) in measured) {
                val p95 = samples.sorted()[28]
                val limit = when (kind) { "query_selective", "query_broad", "save" -> 300.0; "editor" -> 500.0; else -> 1_000.0 }
                append(buildJsonObject {
                    put("kind", "${kind}_summary"); put("count", samples.size); put("p95_ms", p95)
                    put("max_ms", samples.max()); put("status", if (p95 <= limit) "PASS" else "FAIL")
                })
                check(p95 <= limit) { "$kind p95 $p95 exceeded $limit ms" }
            }
            if (recovery) {
                currentKind = "cutover_component"
                // Retain an actual editor draft through activation; readback is outside the writer timer.
                productRow(fixture.firstBarcode).performClick()
                compose.onNodeWithTag("product-editor-name").performScrollTo().performTextReplacement("T20 draft across cutover")
                activeSample = null
                val timing = T20ActivationTimingCapture().also { it.install() }
                cutoverTiming = timing
                runBlocking {
                    val holdRemaining = 10_000L - (SystemClock.elapsedRealtimeNanos() - heldAt) / 1_000_000L
                    if (holdRemaining > 0L) delay(holdRemaining)
                    // Explicit test watchdog, not the per-RPC transport deadline.
                    check(withTimeout(900_000L) { fixture.releaseHeldRecovery() } is ShopSyncRecoveryResult.Activated)
                }
                compose.onNodeWithTag("product-editor-name").assertTextEquals("T20 draft across cutover")
                check(ShopSyncRecoveryTestHooks.timingObservationError == null) { "Activation observation failed" }
                val observed = timing.snapshot()
                check(observed.error == null) { "Timing pairing failed: ${observed.error}" }
                val unavailableMs = requireNotNull(observed.maxUnavailableMs)
                append(buildJsonObject {
                    put("kind", "cutover_component"); put("status", if (unavailableMs <= 300.0) "PASS" else "FAIL")
                    put("clock_id", clockId); put("timing", observed.raw); put("draft_preserved", true)
                    put("writer_unavailable_ms", unavailableMs)
                    observed.room?.let { put("room_transaction_ms", it.elapsedMs) }
                    observed.transition?.let { put("activation_transition_ms", it.elapsedMs) }
                    put("timing_observation_error", ShopSyncRecoveryTestHooks.timingObservationError.orEmpty())
                    put("acceptance_boundary", "one real activation; no 30-sample p95 or frame-pause acceptance")
                })
                check(unavailableMs <= 300.0) { "Cutover writer unavailable maximum exceeded: $unavailableMs ms" }
            }
        } catch (failure: Throwable) {
            terminalFailure = failure
            if (cutoverTiming != null) {
                try { runBlocking { fixture.stopHeldRecovery() } }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            try {
                append(buildJsonObject {
                    put("kind", "sequence_stop"); put("status", "FAIL"); put("first_failure_metric", currentKind)
                    put("sample_index", currentIndex); put("reason", failure.message.orEmpty())
                    put("downstream_status", "NOT_TRAVERSED"); put("automatic_retry", false)
                    put("start_ns", activeSample?.startNs ?: 0L); put("end_ns", activeSample?.endNs ?: 0L)
                    cutoverTiming?.let { put("timing", it.snapshot().raw) }
                })
            } catch (reportFailure: Throwable) { failure.addSuppressed(reportFailure) }
            throw failure
        } finally {
            if (contextualRoute != null || routeRed) com.example.merchandisecontrolsplitview.MainActivity.ShareBus.uris.tryEmit(emptyList())
            MobileSyncInteractionTestHooks.observer = null
            ShopSyncRecoveryTestHooks.activationWriterTiming = null
            ShopSyncRecoveryTestHooks.activationBoundaryTiming = null
            ShopSyncRecoveryTestHooks.activationTransitionTiming = null
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = error else first.addSuppressed(error)
                }
            }
            cleanup { compose.runOnUiThread { visible.value = false; store.clear() } }
            cleanup { auth?.shutdown(); images?.close() }
            var ownedJobsJoined = false
            cleanup {
                runBlocking {
                    withContext(NonCancellable) {
                        fixture.stopHeldRecovery()
                        ownedViewModelJobs.forEach { it.cancelAndJoin() }
                        appScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                        ownedJobsJoined = true
                    }
                }
            }
            if (ownedJobsJoined) cleanup { db.close() }
            println("T20_UI_COMPONENT_ARTIFACT ${runRoot.absolutePath}")
            cleanupFailure?.let { error ->
                val originalFailure = terminalFailure
                if (originalFailure != null) originalFailure.addSuppressed(error) else throw error
            }
        }
    }

    /** Single armed sample: callback does no allocation, blocking, assertions or file I/O. */
    private class UiComponentTiming(
        val start: Event, val end: Event, val productId: Long, val detail: String?
    ) {
        @Volatile var startNs = 0L
        @Volatile var endNs = 0L
        fun observe(event: Event, id: Long, value: String?, ns: Long) {
            if (event == start && startNs == 0L &&
                (start == Event.FILTER_INPUT || id == productId) && value == detail) startNs = ns
            if (event == end && startNs != 0L && endNs == 0L && id == productId && value == detail) endNs = ns
        }
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
            compose.onNodeWithTag("route-context-$tab").assertIsDisplayed()
            compose.onNodeWithTag("root-business-placeholder").assertDoesNotExist()
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

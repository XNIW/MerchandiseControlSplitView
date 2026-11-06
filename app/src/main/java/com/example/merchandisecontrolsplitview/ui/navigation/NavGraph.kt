package com.example.merchandisecontrolsplitview.ui.navigation

import com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks
import com.example.merchandisecontrolsplitview.ui.MobileSyncInteractionTestHooks.Event
import com.example.merchandisecontrolsplitview.ui.observeMobileSyncDraw
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.merchandisecontrolsplitview.ui.LocalBusinessRouteVisible
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.merchandisecontrolsplitview.R
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.merchandisecontrolsplitview.LocalStoreAvailability
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.data.LocalAuthIdentity
import com.example.merchandisecontrolsplitview.viewmodel.qualifiedLocalBusinessOwner
import com.example.merchandisecontrolsplitview.viewmodel.LocalBusinessPresentationKey
import com.example.merchandisecontrolsplitview.viewmodel.localBusinessPresentationKey
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressState
import com.example.merchandisecontrolsplitview.data.ShopContext
import com.example.merchandisecontrolsplitview.data.QualifiedCatalogRequest
import com.example.merchandisecontrolsplitview.pos.PosAccess
import com.example.merchandisecontrolsplitview.pos.PosFeatureEnvironment
import com.example.merchandisecontrolsplitview.pos.PosRoute
import com.example.merchandisecontrolsplitview.pos.PosSalesRoute
import com.example.merchandisecontrolsplitview.pos.PosSalesViewModel
import com.example.merchandisecontrolsplitview.pos.PosScope
import com.example.merchandisecontrolsplitview.data.task126ActiveOwnerStoreScope
import com.example.merchandisecontrolsplitview.ui.components.CloudSyncIndicator
import com.example.merchandisecontrolsplitview.ui.screens.*
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModel
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelImportDraftState
import com.example.merchandisecontrolsplitview.viewmodel.RamExcelImportViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ImportFlowState
import com.example.merchandisecontrolsplitview.viewmodel.businessScopeCheckingPresentation
import com.example.merchandisecontrolsplitview.MainActivity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class OpenedBusinessUi(
    val excel: ExcelViewModel, val database: DatabaseViewModel,
    val cloud: CatalogSyncProgressState, val shop: ShopContext,
    val scope: Task126BusinessDataScopeState, val identity: LocalAuthIdentity?,
    val identityCurrent: Boolean, val posScope: PosScope?, val posOnline: Boolean?
)

@Composable
fun AppNavGraph() {
    val app = LocalContext.current.applicationContext as? MerchandiseControlApplication
        ?: error("MerchandiseControlApplication non configurata nel Manifest")
    val storeAvailability by app.localStoreAvailability.collectAsState()
    val authState by app.authManager.state.collectAsState()
    val ram: RamExcelImportViewModel = viewModel()
    val pos = if (app.authManager.isEnabled) {
        viewModel<PosSalesViewModel>(factory = app.posSalesViewModelFactory)
    } else null
    // This branch owns all DB consumers. The shell below has one identity across the transition.
    val opened = if (storeAvailability is LocalStoreAvailability.Ready) {
        val repository = app.repository
        val localIdentity by app.authManager.localIdentity.collectAsState()
        val cloud by app.catalogSyncStateTracker.state.collectAsState()
        val shop by app.shopContextRepository.state.collectAsState()
        val scope by app.catalogSyncStateTracker.businessDataScopeState.collectAsState()
        val posGeneration by app.shopContextRepository.posContextGeneration.collectAsState()
        val posOnline by app.catalogSyncStateTracker.networkAvailable.collectAsState()
        val excel: ExcelViewModel = viewModel(factory = ExcelViewModel.sharedDraftFactory(app, repository, ram.draft))
        val database: DatabaseViewModel = viewModel(factory = DatabaseViewModel.factory(app, repository))
        OpenedBusinessUi(excel, database, cloud, shop, scope, localIdentity,
            localIdentity?.let(app.authManager::isCurrentLocalIdentity) == true,
            app.currentPosScope(posGeneration), posOnline)
    } else null
    AppShellNavGraphContent(
        app = app, draft = ram.draft, excelViewModel = opened?.excel,
        dbViewModel = opened?.database, authEnabled = app.authManager.isEnabled, authState = authState,
        cloudSyncState = opened?.cloud ?: CatalogSyncProgressState.idle(),
        shopContext = opened?.shop ?: ShopContext.legacy(),
        businessDataScopeState = opened?.scope ?: Task126BusinessDataScopeState.checking(),
        localIdentity = opened?.identity, localIdentityCurrent = opened?.identityCurrent == true,
        storeAvailability = storeAvailability,
        posViewModel = pos, posScope = opened?.posScope, posOnline = opened?.posOnline
    )
}

/** The production root; its dependencies can also be supplied by an isolated integrated test. */
@Composable
internal fun AppNavGraphContent(
    app: MerchandiseControlApplication,
    excelViewModel: ExcelViewModel,
    dbViewModel: DatabaseViewModel,
    authEnabled: Boolean,
    authState: AuthState,
    cloudSyncState: CatalogSyncProgressState,
    shopContext: ShopContext,
    businessDataScopeState: Task126BusinessDataScopeState,
    localIdentity: LocalAuthIdentity? = null,
    localIdentityCurrent: Boolean = false
) {
    AppShellNavGraphContent(app, excelViewModel.importDraftState, excelViewModel, dbViewModel,
        authEnabled, authState, cloudSyncState, shopContext, businessDataScopeState,
        localIdentity, localIdentityCurrent, LocalStoreAvailability.Ready)
}

/** Nullable business dependencies are never manufactured for the safe shell. */
@Composable
internal fun AppShellNavGraphContent(
    app: MerchandiseControlApplication,
    draft: ExcelImportDraftState,
    excelViewModel: ExcelViewModel?,
    dbViewModel: DatabaseViewModel?,
    authEnabled: Boolean,
    authState: AuthState,
    cloudSyncState: CatalogSyncProgressState,
    shopContext: ShopContext,
    businessDataScopeState: Task126BusinessDataScopeState,
    localIdentity: LocalAuthIdentity? = null,
    localIdentityCurrent: Boolean = false,
    storeAvailability: LocalStoreAvailability,
    posViewModel: PosSalesViewModel? = null,
    posScope: PosScope? = null,
    posOnline: Boolean? = null
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    // RAM-only binding survives disposal/recomposition of the same back-stack entry.
    val routePresentationBindings = remember { mutableStateMapOf<String, LocalBusinessPresentationKey>() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val authScope = rememberCoroutineScope()

    val importAnalysisResult = if (dbViewModel != null) {
        val result by dbViewModel.importAnalysisResult.collectAsState()
        result
    } else null
    val currentExcelViewModel by rememberUpdatedState(excelViewModel)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentRootTab = navBackStackEntry?.destination.currentRootTab()
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val posState = posViewModel?.state?.collectAsState()?.value
    // Null chooses the authorized POS overview automatically; explicit control-merchandise wins.
    var inventoryShowsPos by remember(posScope?.principalId, posScope?.shopId,
        posScope?.deviceIdentifier, posScope?.authGeneration) { mutableStateOf<Boolean?>(null) }
    val posEnvironment = PosFeatureEnvironment(
        scope = posScope, online = posOnline == true,
        foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED),
        visible = currentRoute == Screen.FilePicker.route && inventoryShowsPos != false
    )
    var boundPosEnvironment by remember(posViewModel) { mutableStateOf<PosFeatureEnvironment?>(null) }
    LaunchedEffect(posViewModel, posEnvironment) {
        posViewModel?.environment(posEnvironment)
        boundPosEnvironment = posEnvironment
    }
    DisposableEffect(posViewModel) {
        onDispose { posViewModel?.environment(PosFeatureEnvironment(null, false, false, false)) }
    }
    val showPos = posScope != null && posOnline != null && posEnvironment.foreground &&
        posEnvironment.visible && boundPosEnvironment == posEnvironment && app.currentPosScope() == posScope &&
        (inventoryShowsPos == true || posState?.access == PosAccess.AVAILABLE)
    LaunchedEffect(showPos, posState?.access) {
        if (showPos && inventoryShowsPos == null && posState?.access == PosAccess.AVAILABLE) inventoryShowsPos = true
    }
    val localOwner = qualifiedLocalBusinessOwner(authState, localIdentity, localIdentityCurrent,
        shopContext, businessDataScopeState)
    val businessContentAllowed = excelViewModel != null && dbViewModel != null && businessContentAvailable(
        authEnabled = authEnabled,
        authState = authState,
        state = businessDataScopeState,
        activeScope = localOwner?.let { task126ActiveOwnerStoreScope(it, shopContext.selectedShop) },
        locallyQualifiedOwner = localOwner
    )
    val businessWriteAllowed = businessContentAllowed && businessDataScopeState.localWritesAllowed
    val presentationKey = localBusinessPresentationKey(authState, localIdentity, localIdentityCurrent,
        shopContext, businessDataScopeState)
    val showBottomBar = currentRootTab != null
    val showCloudSyncIndicator = businessContentAllowed && currentRoute != Screen.Options.route
    var pendingImportAnalysisExitCleanup by remember {
        mutableStateOf<ImportAnalysisExitCleanup?>(null)
    }
    var importAnalysisExitCleanupApplied by remember { mutableStateOf(false) }

    LaunchedEffect(
        importAnalysisResult,
        pendingImportAnalysisExitCleanup,
        businessContentAllowed
    ) {
        if (!businessContentAllowed) return@LaunchedEffect
        if (pendingImportAnalysisExitCleanup != null) {
            if (importAnalysisResult == null) {
                pendingImportAnalysisExitCleanup = null
                importAnalysisExitCleanupApplied = false
            }
            return@LaunchedEffect
        }

        if (importAnalysisResult != null && pendingImportAnalysisExitCleanup == null) {
            val dest = navController.currentDestination?.route
            if (dest != Screen.ImportAnalysis.route) {
                val origin = dbViewModel?.importNavigationOrigin?.value ?: return@LaunchedEffect
                navController.navigate(Screen.ImportAnalysis.createRoute(origin))
            }
        }
    }

    LaunchedEffect(pendingImportAnalysisExitCleanup, currentRoute, businessContentAllowed) {
        if (!businessContentAllowed) return@LaunchedEffect
        val cleanup = pendingImportAnalysisExitCleanup ?: return@LaunchedEffect
        if (importAnalysisExitCleanupApplied) return@LaunchedEffect
        if (currentRoute == null || currentRoute == Screen.ImportAnalysis.route) return@LaunchedEffect

        importAnalysisExitCleanupApplied = true
        when (cleanup) {
            ImportAnalysisExitCleanup.CancelPreview -> dbViewModel?.clearImportAnalysis()
            ImportAnalysisExitCleanup.DismissPreview -> dbViewModel?.dismissImportPreview()
        }
    }

    LaunchedEffect(navController, lifecycleOwner, draft) {
        navController.currentBackStackEntryFlow.first()

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            MainActivity.ShareBus.uris.collect { uris ->
                if (uris.isNotEmpty()) {
                    if (navController.currentDestination?.route != Screen.PreGenerate.route) {
                        navController.navigate(Screen.PreGenerate.route) { launchSingleTop = true }
                    }
                    currentExcelViewModel?.resetState() ?: draft.resetState()
                    draft.loadFromMultipleUris(context, uris)
                    MainActivity.ShareBus.uris.tryEmit(emptyList())
                }
            }
        }
    }


    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                RootNavigationBar(
                    selectedTab = currentRootTab,
                    onTabSelected = { tab ->
                        if (tab.screen == Screen.History && excelViewModel != null && businessContentAllowed) {
                            navigateByGeneratedExitRequest(
                                navController = navController,
                                request = GeneratedExitRequest(
                                    origin = ImportNavOrigin.HOME,
                                    exitReason = GeneratedExitReason.HistoryTabSelected,
                                    currentRoute = currentRoute,
                                    entryUid = excelViewModel.currentEntryStatus.value.third
                                        .takeIf { it > 0L },
                                    isNewEntry = excelViewModel.peekGeneratedRouteIsNew(),
                                    isManualEntry = excelViewModel.peekGeneratedRouteIsManualEntry()
                                ),
                                excelViewModel = excelViewModel
                            )
                        } else {
                            navigateToRootTab(navController, tab)
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
      Column(modifier = Modifier.fillMaxSize()) {
        if (storeAvailability !is LocalStoreAvailability.Ready) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("local-store-shell-status")) {
                Text(stringResource(if (storeAvailability is LocalStoreAvailability.Checking)
                    R.string.local_store_open_checking else R.string.local_store_open_unavailable))
                if (storeAvailability is LocalStoreAvailability.Unavailable) {
                    androidx.compose.material3.TextButton(onClick = app::retryLocalStoreOpen) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
        // Reserve the indicator's measured height without changing the NavHost's identity.
        // Options keeps its own cloud status card as the primary surface.
        if (showCloudSyncIndicator) {
            CloudSyncIndicator(
                state = cloudSyncState,
                modifier = Modifier.align(Alignment.End)
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        NavHost(
            navController = navController,
            startDestination = Screen.FilePicker.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.FilePicker.route) {
                if (showPos && posViewModel != null) {
                    val route = posState?.route ?: PosRoute.OVERVIEW
                    val coordinator = posViewModel.coordinator
                    BackHandler(enabled = route != PosRoute.OVERVIEW) {
                        when (route) {
                            PosRoute.HISTORY -> coordinator.showOverview()
                            PosRoute.DETAIL -> coordinator.closeDetail()
                            PosRoute.FILTER -> coordinator.closeFilters()
                            PosRoute.OVERVIEW -> Unit
                        }
                    }
                    PosSalesRoute(coordinator, { inventoryShowsPos = false }, innerPadding)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (posViewModel != null && posScope != null && posOnline != null) {
                            TextButton(onClick = { inventoryShowsPos = true }) {
                                Text(stringResource(R.string.task146_pos_today))
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            FilePickerDraftScreen(
                                contentPadding = innerPadding, shopContext = shopContext,
                                manualEntryEnabled = businessWriteAllowed,
                                onShopSelected = { shopId ->
                                    if (businessContentAllowed) app.shopContextRepository.selectShop(shopId)
                                },
                                onFilesPicked = { uris ->
                                    excelViewModel?.resetState() ?: draft.resetState()
                                    draft.loadFromMultipleUris(context, uris)
                                    navController.navigate(Screen.PreGenerate.route)
                                },
                                onManualAdd = manual@ {
                                    val excel = excelViewModel ?: return@manual
                                    if (!businessWriteAllowed) return@manual
                                    excel.resetState()
                                    excel.createManualEntry(context) { newUid ->
                                        excel.noteGeneratedNavigationContext(isNew = true, isManualEntry = true, importOrigin = ImportNavOrigin.HOME)
                                        navController.navigate(Screen.Generated.createRoute(newUid, isNew = true, isManualEntry = true))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            composable(Screen.PreGenerate.route) {
                val dbUiState = if (dbViewModel != null) {
                    val state by dbViewModel.uiState.collectAsState()
                    state
                } else null
                PreGenerateDraftScreen(
                    draft = draft, databaseUiState = dbUiState, databaseViewModel = dbViewModel,
                    canReadBusiness = businessContentAllowed, canWriteBusiness = businessWriteAllowed,
                    businessPresentationKey = presentationKey,
                    onPrepareDraftReset = { excelViewModel?.resetState() ?: draft.resetState() },
                    onGenerate = generate@ { supplierName, categoryName ->
                        val excel = excelViewModel ?: return@generate
                        if (!businessWriteAllowed) return@generate
                        excel.generateFilteredWithOldPrices(supplierName, categoryName) { entryUid ->
                            excel.noteGeneratedNavigationContext(isNew = true, isManualEntry = false, importOrigin = ImportNavOrigin.HOME)
                            navController.navigate(Screen.Generated.createRoute(entryUid, isNew = true))
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Generated.route,
                arguments = listOf(
                    navArgument("entryUid") { type = NavType.LongType },
                    navArgument("isNew") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("isManualEntry") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { backStackEntry ->
                BusinessRouteContent(
                    screen = Screen.Generated, allowed = businessContentAllowed,
                    currentKey = presentationKey,
                    admittedKey = routePresentationBindings[backStackEntry.id],
                    onAdmitted = { routePresentationBindings[backStackEntry.id] = it },
                    state = businessDataScopeState, authState = authState,
                    contentPadding = innerPadding,
                    onBack = { if (!navController.popBackStack()) navigateToRootTab(navController, rootTabs.first()) },
                    onDetails = { navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Options }) }
                ) {
                val excelViewModel = excelViewModel ?: return@BusinessRouteContent
                val dbViewModel = dbViewModel ?: return@BusinessRouteContent
                val entryUid = backStackEntry.arguments?.getLong("entryUid") ?: 0L
                val isNewEntry = backStackEntry.arguments?.getBoolean("isNew") ?: false
                val isManualEntry = backStackEntry.arguments?.getBoolean("isManualEntry") ?: false
                val generatedOrigin = resolveGeneratedSessionOrigin(excelViewModel, isNewEntry)
                val currentSessionUid = excelViewModel.currentEntryStatus.value.third
                val hasGeneratedSession =
                    entryUid > 0L && currentSessionUid == entryUid && excelViewModel.generated.value

                if (!hasGeneratedSession) {
                    val navigateMissingSession = {
                        navigateByGeneratedExitRequest(
                            navController = navController,
                            request = GeneratedExitRequest(
                                origin = generatedOrigin,
                                exitReason = GeneratedExitReason.MissingSession,
                                currentRoute = currentRoute,
                                entryUid = entryUid.takeIf { it > 0L },
                                isNewEntry = isNewEntry,
                                isManualEntry = isManualEntry
                            ),
                            excelViewModel = excelViewModel
                        )
                    }
                    LaunchedEffect(entryUid, generatedOrigin, currentRoute, businessContentAllowed) {
                        if (businessContentAllowed) navigateMissingSession()
                    }
                    RouteFallbackState(onNavigate = navigateMissingSession)
                } else {
                    GeneratedScreen(
                        excelViewModel = excelViewModel,
                        databaseViewModel = dbViewModel,
                        onExit = exit@ { exitReason ->
                            if (!businessContentAllowed) return@exit
                            navigateByGeneratedExitRequest(
                                navController = navController,
                                request = GeneratedExitRequest(
                                    origin = generatedOrigin,
                                    exitReason = exitReason,
                                    currentRoute = currentRoute,
                                    entryUid = entryUid.takeIf { it > 0L },
                                    isNewEntry = isNewEntry,
                                    isManualEntry = isManualEntry
                                ),
                                excelViewModel = excelViewModel
                            )
                        },
                        onNavigateToDatabase = {
                            navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Database })
                        },
                        entryUid = entryUid,
                        isNewEntry = isNewEntry,
                        isManualEntry = isManualEntry
                    )
                }
                }
            }

            composable(Screen.History.route) { backStackEntry ->
                val historyKey = presentationKey
                val historyViewModel = excelViewModel
                val boundKey = routePresentationBindings[backStackEntry.id]
                val readOnlyHistory = if (!businessContentAllowed && historyKey != null && historyViewModel != null &&
                    businessDataScopeState.localReadsAllowed && (boundKey == null || boundKey == historyKey)) {
                    key(historyKey) {
                        val source = remember(historyViewModel, historyKey) {
                            historyViewModel.observeQualifiedHistory(
                                historyKey, task126ActiveOwnerStoreScope(historyKey.owner, shopContext.selectedShop)
                            )
                        }
                        val entries by source.collectAsState(initial = null)
                        entries
                    }
                } else null
                BusinessRouteContent(
                    screen = Screen.History, allowed = businessContentAllowed || readOnlyHistory != null,
                    currentKey = presentationKey,
                    admittedKey = routePresentationBindings[backStackEntry.id],
                    onAdmitted = { routePresentationBindings[backStackEntry.id] = it },
                    state = businessDataScopeState, authState = authState,
                    contentPadding = innerPadding,
                    onBack = { if (!navController.popBackStack()) navigateToRootTab(navController, rootTabs.first()) },
                    onDetails = { navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Options }) }
                ) {
                val excelViewModel = excelViewModel ?: return@BusinessRouteContent
                val currentHistoryFilter by excelViewModel.historyFilter.collectAsState()
                if (!businessContentAllowed && readOnlyHistory != null) {
                    QualifiedHistoryScreen(
                        innerPadding, readOnlyHistory, currentHistoryFilter,
                        excelViewModel.historyDateRange(currentHistoryFilter.dateFilter), excelViewModel::setHistoryFilter
                    )
                    return@BusinessRouteContent
                }
                val historyList by excelViewModel.historyDisplayEntries.collectAsState()
                val historyActionMessage by excelViewModel.historyActionMessage
                val hasHistoryEntries by excelViewModel.hasHistoryEntries.collectAsState()
                val availableSuppliers by excelViewModel.availableHistorySuppliers.collectAsState()
                val availableCategories by excelViewModel.availableHistoryCategories.collectAsState()

                HistoryScreen(
                    contentPadding = innerPadding,
                    historyList = historyList,
                    currentFilter = currentHistoryFilter,
                    hasAnyHistoryEntries = hasHistoryEntries,
                    historyActionMessage = historyActionMessage,
                    availableSuppliers = availableSuppliers,
                    availableCategories = availableCategories,
                    onSelect = select@ { entry ->
                        if (!businessContentAllowed) return@select
                        excelViewModel.loadHistoryEntry(entry.uid) {
                            excelViewModel.noteGeneratedNavigationContext(
                                isNew = false,
                                isManualEntry = entry.isManualEntry,
                                importOrigin = ImportNavOrigin.HISTORY
                            )
                            navController.navigate(
                                Screen.Generated.createRoute(
                                    entryUid = entry.uid,
                                    isNew = false,
                                    isManualEntry = entry.isManualEntry
                                )
                            )
                        }
                    },
                    onRename = { entry, newName -> if (businessWriteAllowed) excelViewModel.renameHistoryEntry(entry.uid, newName) },
                    onDelete = { entry -> if (businessWriteAllowed) excelViewModel.deleteHistoryEntry(entry.uid) },
                    onHistoryActionMessageConsumed = { excelViewModel.consumeHistoryActionMessage() },
                    onSetFilter = { filter -> excelViewModel.setHistoryFilter(filter) },
                    canEdit = businessWriteAllowed
                )
                }
            }

            composable(Screen.Database.route) { backStackEntry ->
                val catalogKey = presentationKey
                val catalogViewModel = dbViewModel
                val boundKey = routePresentationBindings[backStackEntry.id]
                val pageSelection = remember(catalogKey) { MutableStateFlow<QualifiedCatalogRequest?>(null) }
                var ordinaryCatalogAdmitted by remember(catalogKey) { mutableStateOf(false) }
                SideEffect {
                    if (businessContentAllowed && (boundKey == null || boundKey == catalogKey)) {
                        ordinaryCatalogAdmitted = true
                    }
                }
                val readOnlyCatalog = if (!businessContentAllowed && catalogKey != null && catalogViewModel != null &&
                    businessDataScopeState.localReadsAllowed && (boundKey == null || boundKey == catalogKey)) {
                    key(catalogKey) {
                        val source = remember(catalogViewModel, catalogKey, pageSelection) {
                            catalogViewModel.observeQualifiedCatalog(catalogKey,
                                task126ActiveOwnerStoreScope(catalogKey.owner, shopContext.selectedShop), pageSelection)
                        }
                        val page by source.collectAsState(initial = null)
                        page
                    }
                } else null
                BusinessRouteContent(
                    screen = Screen.Database, allowed = businessContentAllowed || readOnlyCatalog != null,
                    currentKey = presentationKey,
                    admittedKey = routePresentationBindings[backStackEntry.id],
                    onAdmitted = { routePresentationBindings[backStackEntry.id] = it },
                    state = businessDataScopeState, authState = authState,
                    contentPadding = innerPadding,
                    onBack = { if (!navController.popBackStack()) navigateToRootTab(navController, rootTabs.first()) },
                    onDetails = { navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Options }) }
                ) {
                val dbViewModel = dbViewModel ?: return@BusinessRouteContent
                if (businessContentAllowed || ordinaryCatalogAdmitted) {
                    val ordinaryVisible = businessContentAllowed && LocalBusinessRouteVisible.current
                    Box(Modifier.fillMaxSize()
                        .graphicsLayer { alpha = if (ordinaryVisible) 1f else 0f }
                        .then(if (ordinaryVisible) Modifier else Modifier.clearAndSetSemantics { })
                        .onPreviewKeyEvent { !ordinaryVisible }) {
                        CompositionLocalProvider(LocalBusinessRouteVisible provides ordinaryVisible) {
                            DatabaseScreen(contentPadding = innerPadding, viewModel = dbViewModel)
                        }
                    }
                }
                if (!businessContentAllowed && readOnlyCatalog != null) {
                    val rawFilter by dbViewModel.filter.collectAsState()
                    val appliedFilter by dbViewModel.appliedProductFilter.collectAsState()
                    val selectedPage by pageSelection.collectAsState()
                    val request = selectedPage?.takeIf { it.filter == appliedFilter }
                        ?: QualifiedCatalogRequest(appliedFilter, 0)
                    Surface(Modifier.fillMaxSize()) {
                        QualifiedCatalogReadScreen(innerPadding, readOnlyCatalog, request, rawFilter.orEmpty(),
                            dbViewModel::setFilter, { pageSelection.value = it })
                    }
                }
                }
            }

            composable(Screen.Options.route) {
                if (dbViewModel == null) {
                    SafeBootstrapOptions(app, authState, authEnabled, storeAvailability, innerPadding)
                    return@composable
                }

                val catalogSyncViewModel: CatalogSyncViewModel = viewModel(
                    factory = CatalogSyncViewModel.factory(app)
                )
                val catalogSyncUi by catalogSyncViewModel.uiState.collectAsState()
                val localDatabaseStatusUi by catalogSyncViewModel.localDatabaseStatusUi.collectAsState()
                val verifiedActiveScope = (authState as? AuthState.SignedIn)
                    ?.takeIf { signedIn ->
                        signedIn.userId.isNotBlank() &&
                        shopContext.ownerUserId == signedIn.userId &&
                            !shopContext.isLoading &&
                            shopContext.syncAllowed &&
                            shopContext.selectedShop?.shopId?.isNotBlank() == true
                    }
                    ?.let { signedIn ->
                        task126ActiveOwnerStoreScope(signedIn.userId, shopContext.selectedShop)
                    }
                val mismatchDialogEligibility = businessScopeMismatchDialogEligibility(
                    state = businessDataScopeState,
                    verifiedActiveScope = verifiedActiveScope
                )
                LaunchedEffect(Unit) {
                    catalogSyncViewModel.onOptionsScreenVisible()
                }
                var showExistingDraftNotice by remember { mutableStateOf(false) }
                if (showExistingDraftNotice) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showExistingDraftNotice = false },
                        title = { Text(stringResource(R.string.catalog_conflict_review_title)) },
                        text = { Text(stringResource(R.string.catalog_conflict_existing_draft)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showExistingDraftNotice = false
                                navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Database })
                            }) { Text(stringResource(R.string.catalog_conflict_return_to_draft)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showExistingDraftNotice = false }) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    )
                }
                OptionsScreen(
                    contentPadding = innerPadding,
                    authState = authState,
                    authEnabled = authEnabled,
                    wechatAuthEnabled = app.authManager.isWeChatEnabled,
                    onSignIn = { activityContext ->
                        authScope.launch { app.authManager.signInWithGoogle(activityContext) }
                    },
                    onSignInWithWeChat = { activityContext ->
                        authScope.launch { app.authManager.signInWithWeChat(activityContext) }
                    },
                    onSignOut = {
                        authScope.launch { app.authManager.signOut() }
                    },
                    onDismissError = { app.authManager.dismissError() },
                    onDiscardUnboundLocalData = {
                        app.discardUnboundLocalBusinessDataAndBind()
                    },
                    onReplaceMismatchedLocalData = {
                        app.replaceMismatchedLocalBusinessDataAndBind()
                    },
                    businessScopeMismatchIdentity = mismatchDialogEligibility.identity,
                    canReplaceMismatchedLocalData = mismatchDialogEligibility.canReplace,
                    catalogSyncUi = if (authEnabled) catalogSyncUi else null,
                    localDatabaseStatusUi = localDatabaseStatusUi,
                    onReviewPersistedConflict = { target ->
                        if (dbViewModel.requestPersistedCatalogConflict(target)) {
                            navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Database })
                        } else showExistingDraftNotice = true
                    }
                )
            }

            composable(
                route = Screen.ImportAnalysis.route,
                arguments = listOf(
                    navArgument(Screen.ImportAnalysis.ARG_ORIGIN) {
                        type = NavType.StringType
                        defaultValue = ImportNavOrigin.HOME.routeArg
                    }
                )
            ) { importBackStackEntry ->
                BusinessRouteContent(
                    screen = Screen.ImportAnalysis, allowed = businessContentAllowed,
                    currentKey = presentationKey,
                    admittedKey = routePresentationBindings[importBackStackEntry.id],
                    onAdmitted = { routePresentationBindings[importBackStackEntry.id] = it },
                    state = businessDataScopeState, authState = authState,
                    contentPadding = innerPadding,
                    onBack = { if (!navController.popBackStack()) navigateToRootTab(navController, rootTabs.first()) },
                    onDetails = { navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Options }) }
                ) {
                val excelViewModel = excelViewModel ?: return@BusinessRouteContent
                val dbViewModel = dbViewModel ?: return@BusinessRouteContent
                val importOrigin = ImportNavOrigin.parse(
                    importBackStackEntry.arguments?.getString(Screen.ImportAnalysis.ARG_ORIGIN)
                )
                val analysis = importAnalysisResult
                val importFlowState by dbViewModel.importFlowState.collectAsState()
                val currentEntryUid = excelViewModel.currentEntryStatus.value.third
                val hasGeneratedImportContext =
                    importOrigin != ImportNavOrigin.DATABASE && currentEntryUid != 0L
                var fallbackNavigationRequested by remember(importOrigin) { mutableStateOf(false) }

                LaunchedEffect(analysis, importOrigin) {
                    if (analysis != null) {
                        fallbackNavigationRequested = false
                    }
                }

                if (analysis == null) {
                    val isPreviewLoading = importFlowState is ImportFlowState.PreviewLoading
                    val navigateMissingPreview = {
                        fallbackNavigationRequested = true
                        navigateByGeneratedExitRequest(
                            navController = navController,
                            request = GeneratedExitRequest(
                                origin = importOrigin,
                                exitReason = GeneratedExitReason.MissingPreview,
                                currentRoute = currentRoute,
                                entryUid = currentEntryUid.takeIf { it > 0L },
                                isNewEntry = excelViewModel.peekGeneratedRouteIsNew(),
                                isManualEntry = excelViewModel.peekGeneratedRouteIsManualEntry()
                            ),
                            excelViewModel = excelViewModel
                        )
                    }
                    LaunchedEffect(
                        importOrigin,
                        isPreviewLoading,
                        fallbackNavigationRequested, businessContentAllowed
                    ) {
                        if (businessContentAllowed && !isPreviewLoading && !fallbackNavigationRequested) {
                            navigateMissingPreview()
                        }
                    }
                    if (isPreviewLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        RouteFallbackState(onNavigate = navigateMissingPreview)
                    }
                } else {
                    val isApplyError =
                        (importFlowState as? ImportFlowState.Error)?.occurredDuringApply == true
                    var successNavigationRequested by remember(analysis, importOrigin) {
                        mutableStateOf(false)
                    }

                    LaunchedEffect(importFlowState, analysis.errors.size, currentEntryUid, importOrigin, businessContentAllowed) {
                        if (businessContentAllowed && importFlowState is ImportFlowState.Success && !successNavigationRequested) {
                            successNavigationRequested = true
                            if (hasGeneratedImportContext) {
                                if (analysis.errors.isEmpty()) {
                                    excelViewModel.markCurrentEntryAsSyncedSuccessfully(currentEntryUid)
                                } else {
                                    excelViewModel.markCurrentEntryAsSyncedWithErrors(currentEntryUid)
                                    excelViewModel.errorRowIndexes.value =
                                        analysis.errors.map { it.rowNumber }.toSet()
                                }
                            }
                            fallbackNavigationRequested = true
                            importAnalysisExitCleanupApplied = false
                            pendingImportAnalysisExitCleanup = ImportAnalysisExitCleanup.DismissPreview
                            navigateByGeneratedExitRequest(
                                navController = navController,
                                request = GeneratedExitRequest(
                                    origin = importOrigin,
                                    exitReason = GeneratedExitReason.ImportSuccess,
                                    currentRoute = currentRoute,
                                    entryUid = currentEntryUid.takeIf { it > 0L },
                                    isNewEntry = excelViewModel.peekGeneratedRouteIsNew(),
                                    isManualEntry = excelViewModel.peekGeneratedRouteIsManualEntry(),
                                    previewId = (importFlowState as? ImportFlowState.Success)?.previewId
                                ),
                                excelViewModel = excelViewModel
                            )
                        }
                    }

                    ImportAnalysisScreen(
                        excelViewModel = excelViewModel,
                        databaseViewModel = dbViewModel,
                        importAnalysis = analysis,
                        importFlowState = importFlowState,
                        canReturnToGeneratedForCorrection = hasGeneratedImportContext && !isApplyError,
                        onConfirm = confirm@ { previewId, newProducts, updatedProducts ->
                            if (!businessWriteAllowed) return@confirm
                            dbViewModel.importProducts(previewId, newProducts, updatedProducts, context)
                        },
                        onCorrectRows = correct@ {
                            if (!businessContentAllowed) return@correct
                            excelViewModel.errorRowIndexes.value =
                                analysis.errors.map { it.rowNumber }.toSet()
                            fallbackNavigationRequested = true
                            importAnalysisExitCleanupApplied = false
                            pendingImportAnalysisExitCleanup = ImportAnalysisExitCleanup.CancelPreview
                            navigateByGeneratedExitRequest(
                                navController = navController,
                                request = GeneratedExitRequest(
                                    origin = importOrigin,
                                    exitReason = GeneratedExitReason.CorrectRows,
                                    currentRoute = currentRoute,
                                    entryUid = currentEntryUid.takeIf { it > 0L },
                                    isNewEntry = excelViewModel.peekGeneratedRouteIsNew(),
                                    isManualEntry = excelViewModel.peekGeneratedRouteIsManualEntry(),
                                    previewId = (importFlowState as? ImportFlowState.PreviewReady)?.previewId
                                ),
                                excelViewModel = excelViewModel
                            )
                        },
                        onClose = close@ {
                            if (!businessContentAllowed) return@close
                            val flowState = importFlowState
                            if (flowState is ImportFlowState.Error && flowState.occurredDuringApply) {
                                if (hasGeneratedImportContext) {
                                    excelViewModel.markCurrentEntryAsSyncedWithErrors(currentEntryUid)
                                }
                                dbViewModel.recoverImportPreviewAfterApplyError()
                            } else {
                                fallbackNavigationRequested = true
                                importAnalysisExitCleanupApplied = false
                                pendingImportAnalysisExitCleanup = ImportAnalysisExitCleanup.CancelPreview
                                navigateByGeneratedExitRequest(
                                    navController = navController,
                                    request = GeneratedExitRequest(
                                        origin = importOrigin,
                                        exitReason = GeneratedExitReason.ImportCancel,
                                        currentRoute = currentRoute,
                                        entryUid = currentEntryUid.takeIf { it > 0L },
                                        isNewEntry = excelViewModel.peekGeneratedRouteIsNew(),
                                        isManualEntry = excelViewModel.peekGeneratedRouteIsManualEntry(),
                                        previewId = (flowState as? ImportFlowState.PreviewReady)?.previewId
                                    ),
                                    excelViewModel = excelViewModel
                                )
                            }
                        },
                    )
                }
                }
            }
        }

        }
      }
    }
}


@Composable
private fun SafeBootstrapOptions(
    app: MerchandiseControlApplication, authState: AuthState, authEnabled: Boolean,
    availability: LocalStoreAvailability, padding: androidx.compose.foundation.layout.PaddingValues
) {
    val authScope = rememberCoroutineScope()
    OptionsScreen(contentPadding = padding, authState = authState, authEnabled = authEnabled,
        wechatAuthEnabled = app.authManager.isWeChatEnabled,
        onSignIn = { activity -> authScope.launch { app.authManager.signInWithGoogle(activity) } },
        onSignInWithWeChat = { activity -> authScope.launch { app.authManager.signInWithWeChat(activity) } },
        onSignOut = { authScope.launch { app.authManager.signOut() } },
        onDismissError = { app.authManager.dismissError() },
        localStoreStatusMessage = stringResource(if (availability is LocalStoreAvailability.Checking)
            R.string.local_store_open_checking else R.string.local_store_open_unavailable),
        localStoreStatusDetails = (availability as? LocalStoreAvailability.Unavailable)?.let { failure ->
            stringResource(R.string.local_store_open_details,
                stringResource(if (failure.code == "local_store_open_failed") R.string.local_store_open_failed_reason
                    else R.string.local_store_open_unknown_reason),
                com.example.merchandisecontrolsplitview.BuildConfig.VERSION_NAME)
        })
}

/** Every route owns a context surface. Keeping RAM nodes does not retain read/write authority. */
@Composable
private fun BusinessRouteContent(
    screen: Screen,
    allowed: Boolean,
    currentKey: LocalBusinessPresentationKey?,
    admittedKey: LocalBusinessPresentationKey?,
    onAdmitted: (LocalBusinessPresentationKey) -> Unit,
    state: Task126BusinessDataScopeState,
    authState: AuthState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    content: @Composable () -> Unit
) {
    val samePresentation = admittedKey != null && admittedKey == currentKey
    val showContent = allowed && currentKey != null && (admittedKey == null || samePresentation)
    SideEffect { if (showContent && admittedKey == null) currentKey.let(onAdmitted) }
    Box(Modifier.fillMaxSize()) {
        // Cold blocked routes never mount business consumers. For a previously admitted
        // same-identity route, paused read gates preserve only RAM inputs, focus and scroll.
        // A different owner/shop/auth generation cannot remount the previous session body.
        if (showContent || samePresentation) {
            Box(Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (showContent) 1f else 0f }
                .then(if (showContent) Modifier else Modifier.clearAndSetSemantics { })
                .onPreviewKeyEvent { !showContent }) {
                CompositionLocalProvider(LocalBusinessRouteVisible provides showContent) { content() }
            }
        }
        if (!showContent) {
            val checking = businessScopeCheckingPresentation(authState)
            val foreignPresentation = admittedKey != null && admittedKey != currentKey
            val message = when {
                foreignPresentation -> R.string.business_scope_account_mismatch_message
                state.status == Task126BusinessDataScopeStatus.CHECKING -> checking.messageRes
                state.status == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND -> R.string.business_scope_unbound_review_message
                state.status == Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH -> R.string.business_scope_account_mismatch_message
                state.status == Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH -> R.string.business_scope_shop_mismatch_message
                state.status == Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH -> R.string.business_scope_schema_mismatch_message
                else -> R.string.business_scope_recoverable_error
            }
            Surface(Modifier.fillMaxSize().testTag("route-context-${screen.route}")
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        // The topmost pointer node blocks hit-testing the hidden sibling;
                        // do not consume child Back/Details gestures.
                        while (true) awaitPointerEvent()
                    }
                }, color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(when (screen) {
                        Screen.FilePicker, Screen.Generated -> R.string.inventory_title
                        Screen.PreGenerate -> R.string.preview_file_title
                        Screen.Database -> R.string.database
                        Screen.History -> R.string.history_root_title
                        else -> R.string.import_analysis_title
                    }), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    androidx.compose.material3.TextButton(onClick = onDetails) {
                        Text(stringResource(R.string.business_scope_review_details))
                    }
                }
            }
        }
    }
}

private enum class ImportAnalysisExitCleanup {
    CancelPreview,
    DismissPreview
}

internal fun businessContentAvailable(
    authEnabled: Boolean,
    authState: AuthState,
    state: com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState,
    activeScope: com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope? = state.localAccessScope ?: state.boundScope,
    locallyQualifiedOwner: String? = null
): Boolean {
    if (!authEnabled || authState is AuthState.SignedOut) return state.localReadsAllowed && state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED && state.boundScope == null
    val owner = when (authState) {
        is AuthState.SignedIn -> authState.userId
        AuthState.Checking -> locallyQualifiedOwner
        else -> null
    }?.takeIf(String::isNotBlank) ?: return false
    if (state.status == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED || !state.allowsLocalOperations || activeScope == null) return false
    val localScope = state.localAccessScope ?: state.boundScope ?: return false
    return localScope.ownerHash == com.example.merchandisecontrolsplitview.data.task126OwnerHash(owner) &&
        com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGate.validate(localScope, activeScope) ==
        com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGateDecision.Allowed
}

@Composable
private fun RouteFallbackState(
    onNavigate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.error_label),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.import_preview_invalidated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onNavigate) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

private fun resolveGeneratedSessionOrigin(
    excelViewModel: ExcelViewModel,
    isNewEntry: Boolean
): ImportNavOrigin {
    val storedOrigin = excelViewModel.peekImportOriginForGeneratedSession()
    return if (storedOrigin == ImportNavOrigin.HOME && !isNewEntry) {
        ImportNavOrigin.HISTORY
    } else {
        storedOrigin
    }
}

private fun navigateByGeneratedExitRequest(
    navController: NavHostController,
    request: GeneratedExitRequest,
    excelViewModel: ExcelViewModel
) {
    val destination = GeneratedExitDestinationResolver.resolve(request)
    Log.d(
        GENERATED_EXIT_TAG,
        "resolve origin=${request.origin} reason=${request.exitReason} " +
            "route=${request.currentRoute} entryUid=${request.entryUid ?: 0L} " +
            "previewId=${request.previewId ?: 0L} destination=$destination"
    )
    navigateToGeneratedExitDestination(
        navController = navController,
        destination = destination,
        excelViewModel = excelViewModel
    )
}

private fun navigateToGeneratedExitDestination(
    navController: NavHostController,
    destination: GeneratedExitDestination,
    excelViewModel: ExcelViewModel
) {
    when (destination) {
        GeneratedExitDestination.NewExcelDestination -> navController.navigate(Screen.FilePicker.route) {
            popUpTo(Screen.FilePicker.route) { inclusive = false }
            launchSingleTop = true
        }
        GeneratedExitDestination.HistoryRoot -> {
            val startRoute = navController.graph.findStartDestination().route ?: Screen.FilePicker.route
            navController.navigate(Screen.History.route) {
                popUpTo(startRoute) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = false
            }
        }
        GeneratedExitDestination.DatabaseRoot -> {
            val startRoute = navController.graph.findStartDestination().route ?: Screen.FilePicker.route
            navController.navigate(Screen.Database.route) {
                popUpTo(startRoute) {
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
        }
        is GeneratedExitDestination.Generated -> {
            navController.navigate(
                Screen.Generated.createRoute(
                    entryUid = destination.entryUid,
                    isNew = destination.isNewEntry,
                    isManualEntry = destination.isManualEntry
                )
            ) {
                // Do not save/restore the ImportAnalysis child route when intentionally returning.
                popUpTo(navController.graph.findStartDestination().route ?: Screen.FilePicker.route) {
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
        }
        is GeneratedExitDestination.RecoverableError -> navigateToGeneratedExitDestination(
            navController = navController,
            destination = destination.fallback,
            excelViewModel = excelViewModel
        )
    }
}

private const val GENERATED_EXIT_TAG = "GeneratedExit"

private fun navigateToRootTab(
    navController: NavHostController,
    tab: RootTab
) {
    val startDestinationRoute = navController.graph.findStartDestination().route ?: Screen.FilePicker.route
    navController.navigate(tab.screen.route) {
        popUpTo(startDestinationRoute) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun RootNavigationBar(
    selectedTab: RootTab?,
    onTabSelected: (RootTab) -> Unit
) {
    val minimumBarHeight=with(LocalDensity.current) {
        48.dp + MaterialTheme.typography.labelMedium.lineHeight.toDp() * 2
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            NavigationBar(
                modifier = Modifier.heightIn(min=minimumBarHeight),
                windowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                rootTabs.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("root-tab-${tab.screen.route}")
                            .observeMobileSyncDraw(Event.ROOT_TAB_DRAW, detail = tab.screen.route,
                                enabled = selectedTab?.screen == tab.screen),
                        selected = selectedTab?.screen == tab.screen,
                        enabled = true,
                        onClick = {
                            MobileSyncInteractionTestHooks.emit(Event.ROOT_TAB_CLICK, detail = tab.screen.route)
                            onTabSelected(tab)
                        },
                        icon = {
                            androidx.compose.material3.Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelRes)
                            )
                        },
                        label = { Text(stringResource(tab.labelRes),
                            modifier=Modifier.fillMaxWidth().testTag("root-tab-label-${tab.screen.route}"),
                            textAlign=TextAlign.Center) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    }
}

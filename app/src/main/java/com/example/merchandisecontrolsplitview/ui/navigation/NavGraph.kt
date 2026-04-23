package com.example.merchandisecontrolsplitview.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.ui.components.CloudSyncIndicator
import com.example.merchandisecontrolsplitview.ui.screens.*
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModel
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ImportFlowState
import com.example.merchandisecontrolsplitview.MainActivity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AppNavGraph() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val app = context.applicationContext as? MerchandiseControlApplication
        ?: error("MerchandiseControlApplication non configurata nel Manifest")
    // Repository singleton dall'Application (task 010): owner unico, nessuna duplicazione.
    val repository = app.repository
    // Stato sync globale strutturato: fase corrente + conteggio opzionale.
    val cloudSyncState by app.catalogSyncStateTracker.state.collectAsState()

    val excelViewModel: ExcelViewModel = viewModel(
        factory = ExcelViewModel.factory(app, repository)
    )
    val dbViewModel: DatabaseViewModel = viewModel(
        factory = DatabaseViewModel.factory(app, repository)
    )

    // Auth state (task 011): unica fonte di verita' per lo stato sessione.
    val authState by app.authManager.state.collectAsState()
    val authScope = rememberCoroutineScope()

    val importAnalysisResult by dbViewModel.importAnalysisResult.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentRootTab = navBackStackEntry?.destination.currentRootTab()
    val showBottomBar = currentRootTab != null
    val showCloudSyncIndicator = currentRoute != Screen.Options.route

    LaunchedEffect(importAnalysisResult) {
        if (importAnalysisResult != null) {
            if (navController.currentDestination?.route != Screen.ImportAnalysis.route) {
                navController.navigate(Screen.ImportAnalysis.route)
            }
        }
    }

    LaunchedEffect(navController, lifecycleOwner) {
        navController.currentBackStackEntryFlow.first()

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            MainActivity.ShareBus.uris.collect { uris ->
                if (uris.isNotEmpty()) {
                    if (navController.currentDestination?.route != Screen.PreGenerate.route) {
                        navController.navigate(Screen.PreGenerate.route) { launchSingleTop = true }
                    }
                    excelViewModel.resetState()
                    excelViewModel.loadFromMultipleUris(context, uris)
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
                        navigateToRootTab(navController, tab)
                    }
                )
            }
        }
    ) { innerPadding ->
      Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.FilePicker.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.FilePicker.route) {
                FilePickerScreen(
                    contentPadding = innerPadding,
                    onFilesPicked = { uris ->
                        excelViewModel.resetState()

                        excelViewModel.loadFromMultipleUris(context, uris)
                        navController.navigate(Screen.PreGenerate.route)
                    },
                    onManualAdd = {
                        excelViewModel.resetState()

                        excelViewModel.createManualEntry(context) { newUid ->
                            navController.navigate(
                                Screen.Generated.createRoute(
                                    entryUid = newUid,
                                    isNew = true,
                                    isManualEntry = true
                                )
                            )
                        }
                    }
                )
            }

            composable(Screen.PreGenerate.route) {
                val dbUiState by dbViewModel.uiState.collectAsState()
                PreGenerateScreen(
                    excelViewModel = excelViewModel,
                    databaseUiState = dbUiState,
                    databaseViewModel = dbViewModel,
                    onGenerate = { supplierName, categoryName ->
                        excelViewModel.generateFilteredWithOldPrices(supplierName, categoryName) { entryUid ->
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
                val entryUid = backStackEntry.arguments?.getLong("entryUid") ?: 0L
                val isNewEntry = backStackEntry.arguments?.getBoolean("isNew") ?: false
                val isManualEntry = backStackEntry.arguments?.getBoolean("isManualEntry") ?: false

                GeneratedScreen(
                    excelViewModel = excelViewModel,
                    databaseViewModel = dbViewModel,
                    onBackToStart = { navController.popBackStack() },
                    onNavigateToHome = {
                        navController.navigate(Screen.FilePicker.route) {
                            popUpTo(Screen.FilePicker.route) {
                                inclusive = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToDatabase = {
                        navigateToRootTab(navController, rootTabs.first { it.screen == Screen.Database })
                    },
                    entryUid = entryUid,
                    isNewEntry = isNewEntry,
                    isManualEntry = isManualEntry
                )
            }

            composable(Screen.History.route) {
                val historyList by excelViewModel.historyDisplayEntries.collectAsState()
                val historyActionMessage by excelViewModel.historyActionMessage
                val currentHistoryFilter by excelViewModel.historyFilter.collectAsState()
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
                    onSelect = { entry ->
                        excelViewModel.loadHistoryEntry(entry.uid) {
                            navController.navigate(
                                Screen.Generated.createRoute(
                                    entryUid = entry.uid,
                                    isNew = false,
                                    isManualEntry = entry.isManualEntry
                                )
                            )
                        }
                    },
                    onRename = { entry, newName -> excelViewModel.renameHistoryEntry(entry.uid, newName) },
                    onDelete = { entry -> excelViewModel.deleteHistoryEntry(entry.uid) },
                    onHistoryActionMessageConsumed = { excelViewModel.consumeHistoryActionMessage() },
                    onSetFilter = { filter -> excelViewModel.setHistoryFilter(filter) }
                )
            }

            composable(Screen.Database.route) {
                DatabaseScreen(
                    contentPadding = innerPadding,
                    viewModel = dbViewModel
                )
            }

            composable(Screen.Options.route) {
                val catalogSyncViewModel: CatalogSyncViewModel = viewModel(
                    factory = CatalogSyncViewModel.factory(app)
                )
                val catalogSyncUi by catalogSyncViewModel.uiState.collectAsState()
                LaunchedEffect(Unit) {
                    catalogSyncViewModel.onOptionsScreenVisible()
                }
                OptionsScreen(
                    contentPadding = innerPadding,
                    authState = authState,
                    authEnabled = app.authManager.isEnabled,
                    onSignIn = { activityContext ->
                        authScope.launch { app.authManager.signInWithGoogle(activityContext) }
                    },
                    onSignOut = {
                        authScope.launch { app.authManager.signOut() }
                    },
                    onDismissError = { app.authManager.dismissError() },
                    catalogSyncUi = if (app.authManager.isEnabled) catalogSyncUi else null,
                    onCatalogRefresh = { catalogSyncViewModel.refreshCatalog() },
                    onCatalogQuickSync = { catalogSyncViewModel.syncCatalogQuick() }
                )
            }

            composable(Screen.ImportAnalysis.route) {
                importAnalysisResult?.let { analysis ->
                    val importFlowState by dbViewModel.importFlowState.collectAsState()
                    val currentEntryUid = excelViewModel.currentEntryStatus.value.third

                    LaunchedEffect(importFlowState, analysis.errors.size, currentEntryUid) {
                        if (importFlowState is ImportFlowState.Success) {
                            if (currentEntryUid != 0L) {
                                if (analysis.errors.isEmpty()) {
                                    excelViewModel.markCurrentEntryAsSyncedSuccessfully(currentEntryUid)
                                } else {
                                    excelViewModel.markCurrentEntryAsSyncedWithErrors(currentEntryUid)
                                }
                            }
                            dbViewModel.dismissImportPreview()
                            navController.popBackStack()
                        }
                    }

                    ImportAnalysisScreen(
                        excelViewModel = excelViewModel,
                        databaseViewModel = dbViewModel,
                        importAnalysis = analysis,
                        importFlowState = importFlowState,
                        onConfirm = { previewId, newProducts, updatedProducts ->
                            dbViewModel.importProducts(previewId, newProducts, updatedProducts, context)
                        },
                        onClose = {
                            val flowState = importFlowState
                            if (
                                flowState is ImportFlowState.Error &&
                                flowState.occurredDuringApply &&
                                currentEntryUid != 0L
                            ) {
                                excelViewModel.markCurrentEntryAsSyncedWithErrors(currentEntryUid)
                            }
                            dbViewModel.clearImportAnalysis()
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
        // Overlay indicatore sync cloud (root tabs + Generated/PreGenerate/Import).
        // In Opzioni la card "Catalogo sul cloud" resta la superficie primaria.
        if (showCloudSyncIndicator) {
            CloudSyncIndicator(
                state = cloudSyncState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = innerPadding.calculateTopPadding() + 12.dp, end = 12.dp)
            )
        }
      }
    }
}

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
                windowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                rootTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab?.screen == tab.screen,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            androidx.compose.material3.Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelRes)
                            )
                        },
                        label = { androidx.compose.material3.Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    }
}

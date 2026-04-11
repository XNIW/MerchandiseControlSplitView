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
import com.example.merchandisecontrolsplitview.ui.screens.*
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ImportFlowState
import com.example.merchandisecontrolsplitview.MainActivity
import kotlinx.coroutines.flow.first

@Composable
fun AppNavGraph() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val app = context.applicationContext as android.app.Application
    val repository = androidx.compose.runtime.remember {
        com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository(
            com.example.merchandisecontrolsplitview.data.AppDatabase.getDatabase(app)
        )
    }

    val excelViewModel: ExcelViewModel = viewModel(
        factory = ExcelViewModel.factory(app, repository)
    )
    val dbViewModel: DatabaseViewModel = viewModel(
        factory = DatabaseViewModel.factory(app, repository)
    )

    val importAnalysisResult by dbViewModel.importAnalysisResult.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRootTab = navBackStackEntry?.destination.currentRootTab()
    val showBottomBar = currentRootTab != null

    LaunchedEffect(importAnalysisResult) {
        if (importAnalysisResult != null) {
            if (navController.currentDestination?.route != Screen.ImportAnalysis.route) {
                navController.navigate(Screen.ImportAnalysis.route)
            }
        }
    }

    LaunchedEffect(navController, lifecycleOwner) {
        // aspetta che il grafo sia attaccato
        navController.currentBackStackEntryFlow.first()

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            MainActivity.ShareBus.uris.collect { uris ->
                if (uris.isNotEmpty()) {
                    if (navController.currentDestination?.route != Screen.PreGenerate.route) {
                        navController.navigate(Screen.PreGenerate.route) { launchSingleTop = true }
                    }
                    excelViewModel.resetState()
                    excelViewModel.loadFromMultipleUris(context, uris)
                    MainActivity.ShareBus.uris.tryEmit(emptyList()) // ok svuotare ora
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
                        navigateToRootTab(navController, rootTabs.first { it.screen == Screen.FilePicker })
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
                val historyList by excelViewModel.historyListEntries.collectAsState()
                val historyActionMessage by excelViewModel.historyActionMessage
                val currentDateFilter by excelViewModel.dateFilter.collectAsState()
                val hasHistoryEntries by excelViewModel.hasHistoryEntries.collectAsState()

                HistoryScreen(
                    contentPadding = innerPadding,
                    historyList = historyList,
                    currentFilter = currentDateFilter,
                    hasAnyHistoryEntries = hasHistoryEntries,
                    historyActionMessage = historyActionMessage,
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
                    onSetFilter = { filter -> excelViewModel.setDateFilter(filter) }
                )
            }

            composable(Screen.Database.route) {
                DatabaseScreen(
                    contentPadding = innerPadding,
                    viewModel = dbViewModel
                )
            }

            composable(Screen.Options.route) {
                OptionsScreen(contentPadding = innerPadding)
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

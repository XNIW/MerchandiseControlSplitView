package com.example.merchandisecontrolsplitview.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.merchandisecontrolsplitview.ui.screens.*
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ImportFlowState
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.merchandisecontrolsplitview.MainActivity
import kotlinx.coroutines.flow.first
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle

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

    NavHost(
        navController = navController,
        startDestination = Screen.FilePicker.route
    ) {
        composable(Screen.FilePicker.route) {
            FilePickerScreen(
                onFilesPicked = { uris ->
                    excelViewModel.resetState()

                    excelViewModel.loadFromMultipleUris(context, uris)
                    navController.navigate(Screen.PreGenerate.route)
                },
                onViewHistory = { navController.navigate(Screen.History.route) },
                onDatabase = { navController.navigate(Screen.Database.route) },
                onOptions = { navController.navigate(Screen.Options.route) },
                onManualAdd = {
                    excelViewModel.resetState()

                    excelViewModel.createManualEntry(context) { newUid ->
                        navController.navigate(Screen.Generated.createRoute(
                            entryUid = newUid,
                            isNew = true,
                            isManualEntry = true
                        ))
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
                        popUpTo(navController.graph.startDestinationId) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                },
                onNavigateToDatabase = {
                    navController.navigate(Screen.Database.route)
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
                onSetFilter = { filter -> excelViewModel.setDateFilter(filter) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Database.route) {
            DatabaseScreen(navController = navController, viewModel = dbViewModel)
        }

        composable(Screen.Options.route) {
            OptionsScreen(navController = navController)
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

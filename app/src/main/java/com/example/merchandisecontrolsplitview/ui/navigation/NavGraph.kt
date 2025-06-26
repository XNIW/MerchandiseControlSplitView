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

@Composable
fun AppNavGraph() {
    val context = LocalContext.current
    val navController = rememberNavController()

    // ... (Gestione stato non cambia)

    val excelViewModel: ExcelViewModel = viewModel()
    val dbViewModel: DatabaseViewModel = viewModel()

    val importAnalysisResult by dbViewModel.importAnalysisResult.collectAsState()
    LaunchedEffect(importAnalysisResult) {
        if (importAnalysisResult != null) {
            if (navController.currentDestination?.route != Screen.ImportAnalysis.route) {
                navController.navigate(Screen.ImportAnalysis.route)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.FilePicker.route
    ) {
        composable(Screen.FilePicker.route) {
            FilePickerScreen(
                viewModel = excelViewModel,
                onFilePicked = { uri ->
                    excelViewModel.loadFromUri(context, uri)
                    navController.navigate(Screen.PreGenerate.route)
                },
                onViewHistory = { navController.navigate(Screen.History.route) },
                onDatabase = { navController.navigate(Screen.Database.route) },
                onOptions = { navController.navigate(Screen.Options.route) }
            )
        }

        composable(Screen.PreGenerate.route) {
            val dbUiState by dbViewModel.uiState.collectAsState()
            PreGenerateScreen(
                excelViewModel = excelViewModel,
                databaseUiState = dbUiState,
                onGenerate = { supplierName ->
                    excelViewModel.generateFilteredWithOldPrices(supplierName) { entryId ->
                        navController.navigate(Screen.Generated.createRoute(entryId))
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Generated.route) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString("entryId") ?: ""
            GeneratedScreen(
                excelViewModel = excelViewModel,
                databaseViewModel = dbViewModel,
                navController = navController,
                onBackToStart = { navController.popBackStack() },
                entryId = entryId
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                navController = navController,
                historyList = excelViewModel.historyEntries,
                onSelect = { entry ->
                    excelViewModel.loadHistoryEntry(entry)
                },
                onRename = { entry, newName -> excelViewModel.renameHistoryEntry(entry, newName) },
                onDelete = { entry -> excelViewModel.deleteHistoryEntry(entry) },
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
            DisposableEffect(Unit) {
                onDispose {
                    dbViewModel.clearImportAnalysis()
                }
            }

            importAnalysisResult?.let { analysis ->
                ImportAnalysisScreen(
                    excelViewModel = excelViewModel,
                    importAnalysis = analysis,
                    onConfirm = { newProducts, updatedProducts ->
                        dbViewModel.importProducts(newProducts, updatedProducts, context)
                        // --- INIZIO MODIFICA ---
                        // Torna alla schermata iniziale e pulisce lo stack di navigazione.
                        navController.navigate(Screen.FilePicker.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                inclusive = true
                            }
                            launchSingleTop = true // Evita di creare istanze multiple della stessa schermata
                        }
                        // --- FINE MODIFICA ---
                    },
                    onCancel = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
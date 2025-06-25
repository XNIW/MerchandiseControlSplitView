package com.example.merchandisecontrolsplitview.ui.navigation

import android.os.Bundle
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

object NavigationStateHolder {
    var savedState: Bundle? = null
}

// MODIFICA: Il parametro startDestination è stato rimosso
@Composable
fun AppNavGraph() {
    val context = LocalContext.current

    val navController = rememberNavController()

    // --- Ripristina lo stato navigation se disponibile ---
    LaunchedEffect(Unit) {
        NavigationStateHolder.savedState?.let { state ->
            navController.restoreState(state)
            NavigationStateHolder.savedState = null
        }
    }

    // --- Salva lo stato navigation quando la composable viene dismessa ---
    DisposableEffect(Unit) {
        onDispose {
            val state = navController.saveState()
            if (state != null) {
                NavigationStateHolder.savedState = state
            }
        }
    }

    val excelViewModel: ExcelViewModel = viewModel()
    val dbViewModel: DatabaseViewModel = viewModel()

    val importAnalysisResult by dbViewModel.importAnalysisResult.collectAsState()
    LaunchedEffect(importAnalysisResult, navController) {
        if (importAnalysisResult != null) {
            if (navController.currentDestination?.route != Screen.ImportAnalysis.route) {
                navController.navigate(Screen.ImportAnalysis.route)
            }
        }
    }

    NavHost(
        navController = navController,
        // MODIFICA: startDestination è ora sempre Screen.FilePicker.route
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
                        navController.navigate("${Screen.Generated.route}/$entryId")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("${Screen.Generated.route}/{entryId}") { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString("entryId") ?: ""
            GeneratedScreen(
                viewModel = excelViewModel,
                onBackToStart = {
                    excelViewModel.resetState()
                    navController.popBackStack(Screen.FilePicker.route, inclusive = false)
                },
                entryId = entryId
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                historyList = excelViewModel.historyEntries,
                onSelect = { entry ->
                    excelViewModel.loadHistoryEntry(entry)
                    navController.navigate("${Screen.Generated.route}/${entry.id}")
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

        // --- Schermata di Analisi (Logica Corretta e Robusta) ---
        composable(Screen.ImportAnalysis.route) {
            DisposableEffect(Unit) {
                onDispose {
                    dbViewModel.clearImportAnalysis()
                }
            }

            importAnalysisResult?.let { analysis ->
                ImportAnalysisScreen(
                    importAnalysis = analysis,
                    onConfirm = { newProducts, updatedProducts ->
                        dbViewModel.importProducts(newProducts, updatedProducts, context)
                        navController.popBackStack()
                    },
                    onCancel = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
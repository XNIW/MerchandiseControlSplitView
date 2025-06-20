package com.example.merchandisecontrolsplitview.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.merchandisecontrolsplitview.ui.screens.DatabaseScreen
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.ui.screens.FilePickerScreen
import com.example.merchandisecontrolsplitview.ui.screens.PreGenerateScreen
import com.example.merchandisecontrolsplitview.ui.screens.GeneratedScreen
import com.example.merchandisecontrolsplitview.ui.screens.HistoryScreen

/**
 * Navigation graph for the MerchandiseControlSplitView app.
 */
@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: ExcelViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.FilePicker.route
    ) {
        composable(Screen.FilePicker.route) {
            FilePickerScreen(
                onFilePicked = { uri ->
                    viewModel.loadFromUri(context, uri)
                    navController.navigate(Screen.PreGenerate.route)
                },
                onViewHistory = {
                    navController.navigate(Screen.History.route)
                },
                onDatabase = {
                    navController.navigate("databaseScreen") // AGGIUNGI QUESTO
                },
                viewModel = viewModel
            )
        }

        composable(Screen.PreGenerate.route) {
            PreGenerateScreen(
                viewModel = viewModel,
                onGenerate = {
                    viewModel.generateFilteredWithOldPrices(context)
                    navController.navigate(Screen.Generated.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Generated.route) {
            GeneratedScreen(
                viewModel = viewModel,
                onBackToStart = {
                    viewModel.resetState()
                    navController.popBackStack(
                        Screen.FilePicker.route,
                        inclusive = false
                    )
                }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                historyList = viewModel.historyEntries,
                onSelect    = { entry ->
                    viewModel.loadHistoryEntry(entry)
                    navController.navigate(Screen.Generated.route)
                },
                onRename    = { entry, newName ->
                    viewModel.renameHistoryEntry(entry, newName)
                },
                onDelete    = { entry ->
                    viewModel.deleteHistoryEntry(entry)
                },
                onBack      = {
                    navController.popBackStack()
                }
            )
        }

        composable("databaseScreen") {
            DatabaseScreen(navController = navController)
        }
    }
}
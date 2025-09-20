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
import androidx.navigation.NavType // <-- AGGIUNGI QUESTO IMPORT
import androidx.navigation.navArgument // <-- AGGIUNGI QUESTO IMPORT
import com.example.merchandisecontrolsplitview.MainActivity
import kotlinx.coroutines.flow.first
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle

@Composable
fun AppNavGraph() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

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
                        // Naviga alla GeneratedScreen con i nuovi flag
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
                    excelViewModel.generateFilteredWithOldPrices(supplierName, categoryName) { entryUid -> // Riceve il Long
                        // Assicurati che il percorso di navigazione accetti un long
                        navController.navigate(Screen.Generated.createRoute(entryUid, isNew = true))
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Generated.route,
            // 1. Specifica che la rotta accetta un argomento 'entryUid' di tipo Long
            arguments = listOf(
                navArgument("entryUid") { type = NavType.LongType },
                navArgument("isNew") {
                    type = NavType.BoolType
                    defaultValue = false // Il valore predefinito è false
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
                onNavigateToDatabase = {                     // <--- AGGIUNTO
                    navController.navigate(Screen.Database.route)
                },
                entryUid = entryUid,
                isNewEntry = isNewEntry,
                isManualEntry = isManualEntry
            )
            // --- FINE MODIFICA ---
        }

        composable(Screen.History.route) {
            val historyList by excelViewModel.historyEntries.collectAsState()

            HistoryScreen(
                historyList = historyList,
                onSelect = { entry ->
                    excelViewModel.loadHistoryEntry(entry)
                    // Da qui l'entry non è nuova, quindi usiamo il valore predefinito isNew = false
                    navController.navigate(
                        Screen.Generated.createRoute(
                            entryUid = entry.uid,
                            isNew = false,
                            isManualEntry = entry.isManualEntry // <-- USA IL NUOVO CAMPO
                        )
                    )
                },
                onRename = { entry, newName -> excelViewModel.renameHistoryEntry(entry, newName) },
                onDelete = { entry -> excelViewModel.deleteHistoryEntry(entry) },
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
            DisposableEffect(Unit) {
                onDispose {
                    dbViewModel.clearImportAnalysis()
                }
            }

            importAnalysisResult?.let { analysis ->
                ImportAnalysisScreen(
                    excelViewModel = excelViewModel,
                    databaseViewModel = dbViewModel,
                    importAnalysis = analysis,
                    onConfirm = { newProducts, updatedProducts ->
                        val currentEntryUid = excelViewModel.currentEntryStatus.value.third

                        // Applica import
                        dbViewModel.importProducts(newProducts, updatedProducts, context)

                        // Aggiorna lo stato della voce corrente (se vuoi farlo dopo il successo, spostalo in UiState.Success observer)
                        if (importAnalysisResult?.errors?.isEmpty() == true) {
                            excelViewModel.markCurrentEntryAsSyncedSuccessfully(currentEntryUid)
                        } else {
                            excelViewModel.markCurrentEntryAsSyncedWithErrors(currentEntryUid)
                        }

                        // Torna alla schermata precedente (Generated)
                        navController.popBackStack()
                    },
//                    onConfirm = { newProducts, updatedProducts ->
//                        // 1. Il nome della variabile ora riflette correttamente il tipo di dato (Long uid)
//                        val currentEntryUid = excelViewModel.currentEntryStatus.value.third
//
//                        // 2. La logica sottostante ora funziona come previsto perché le funzioni del ViewModel sono state aggiornate
//                        if (importAnalysisResult?.errors?.isEmpty() == true) {
//                            excelViewModel.markCurrentEntryAsSyncedSuccessfully(currentEntryUid)
//                        } else {
//                            excelViewModel.markCurrentEntryAsSyncedWithErrors(currentEntryUid)
//                        }
//                        dbViewModel.importProducts(newProducts, updatedProducts, context)
//                        navController.navigate(Screen.FilePicker.route) {
//                            popUpTo(navController.graph.startDestinationId) {
//                                inclusive = true
//                            }
//                            launchSingleTop = true
//                        }
//                    },
                    onCancel = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
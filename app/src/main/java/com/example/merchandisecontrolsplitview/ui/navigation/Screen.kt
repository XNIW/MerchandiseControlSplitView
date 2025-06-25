package com.example.merchandisecontrolsplitview.ui.navigation

/**
 * Definisce tutte le rotte di navigazione in modo sicuro e centralizzato.
 * Ogni oggetto rappresenta una schermata unica nell'applicazione.
 */
sealed class Screen(val route: String) {
    // Schermate Principali
    data object FilePicker : Screen("filePicker")
    data object PreGenerate : Screen("preGenerate")
    data object Generated : Screen("generatedScreen") // Nota: la rotta completa include l'argomento
    data object History : Screen("history")
    data object Database : Screen("databaseScreen")
    data object Options : Screen("optionsScreen")

    // Schermate del Flusso di Importazione
    data object ImportAnalysis : Screen("importAnalysisScreen")
}

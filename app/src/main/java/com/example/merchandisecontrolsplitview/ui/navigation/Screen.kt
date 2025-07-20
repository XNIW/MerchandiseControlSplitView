package com.example.merchandisecontrolsplitview.ui.navigation

/**
 * Definisce tutte le rotte di navigazione in modo sicuro e centralizzato.
 * Ogni oggetto rappresenta una schermata unica nell'applicazione.
 */
sealed class Screen(val route: String) {
    // Schermate Principali
    data object FilePicker : Screen("filePicker")
    data object PreGenerate : Screen("preGenerate")
    // MODIFICA: La rotta ora include il placeholder per l'argomento
    data object Generated : Screen("generated/{entryUid}") { // 1. Cambiato placeholder per chiarezza
        // 2. La funzione ora accetta un Long
        fun createRoute(entryUid: Long): String {
            // 3. La codifica URL non è più necessaria per un numero
            return "generated/$entryUid"
        }
    }
    data object History : Screen("history")
    data object Database : Screen("databaseScreen")
    data object Options : Screen("optionsScreen")

    // Schermate del Flusso di Importazione
    data object ImportAnalysis : Screen("importAnalysisScreen")
}
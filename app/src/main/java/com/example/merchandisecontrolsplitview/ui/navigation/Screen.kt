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
    data object Generated : Screen("generated/{entryUid}?isNew={isNew}") {
        // La funzione ora accetta un booleano per indicare se l'entry è nuova
        fun createRoute(entryUid: Long, isNew: Boolean = false): String {
            return "generated/$entryUid?isNew=$isNew"
        }
    }
    data object History : Screen("history")
    data object Database : Screen("databaseScreen")
    data object Options : Screen("optionsScreen")

    // Schermate del Flusso di Importazione
    data object ImportAnalysis : Screen("importAnalysisScreen")
}
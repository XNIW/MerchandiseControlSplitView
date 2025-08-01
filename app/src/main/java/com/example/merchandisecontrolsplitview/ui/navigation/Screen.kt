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
    data object Generated : Screen("generated/{entryUid}/{isNew}/{isManualEntry}") {
        /**
         * Crea la rotta di navigazione per la schermata Generated.
         * @param entryUid L'ID univoco della voce di cronologia.
         * @param isNew Flag per indicare se la voce è appena stata creata.
         * @param isManualEntry Flag per indicare se si tratta di un'aggiunta manuale. [cite: 25]
         */
        fun createRoute(
            entryUid: Long,
            isNew: Boolean = false,
            isManualEntry: Boolean = false
        ): String {
            return "generated/$entryUid/$isNew/$isManualEntry"
        }
    }
    data object History : Screen("history")
    data object Database : Screen("databaseScreen")
    data object Options : Screen("optionsScreen")

    // Schermate del Flusso di Importazione
    data object ImportAnalysis : Screen("importAnalysisScreen")
}
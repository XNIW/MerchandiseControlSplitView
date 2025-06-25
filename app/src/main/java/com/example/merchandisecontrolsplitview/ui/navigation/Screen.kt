package com.example.merchandisecontrolsplitview.ui.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Definisce tutte le rotte di navigazione in modo sicuro e centralizzato.
 * Ogni oggetto rappresenta una schermata unica nell'applicazione.
 */
sealed class Screen(val route: String) {
    // Schermate Principali
    data object FilePicker : Screen("filePicker")
    data object PreGenerate : Screen("preGenerate")
    // MODIFICA: La rotta ora include il placeholder per l'argomento
    data object Generated : Screen("generated/{entryId}") {
        // Funzione helper per costruire la rotta in modo sicuro
        fun createRoute(entryId: String): String {
            val encodedId = URLEncoder.encode(entryId, StandardCharsets.UTF_8.toString())
            return "generated/$encodedId"
        }
    }
    data object History : Screen("history")
    data object Database : Screen("databaseScreen")
    data object Options : Screen("optionsScreen")

    // Schermate del Flusso di Importazione
    data object ImportAnalysis : Screen("importAnalysisScreen")
}
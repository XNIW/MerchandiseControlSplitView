package com.example.merchandisecontrolsplitview.ui.navigation

import android.util.Log

/**
 * Schermata di provenienza per il flusso Import Analysis (task 044C).
 * Usato come argomento di navigazione stabile e per il pop-up-to dopo apply di successo.
 */
enum class ImportNavOrigin(val routeArg: String) {
    HOME("home"),
    HISTORY("history"),
    DATABASE("database"),
    GENERATED("generated");

    companion object {
        private const val TAG = "ImportNav"

        fun parse(arg: String?): ImportNavOrigin {
            if (arg.isNullOrBlank()) {
                Log.w(TAG, "missingImportOrigin: blank arg, fallback home")
                return HOME
            }
            val key = arg.trim().lowercase()
            return entries.firstOrNull { it.routeArg == key }
                ?: run {
                    Log.w(TAG, "missingImportOrigin: unknown arg=$arg, fallback home")
                    HOME
                }
        }
    }
}

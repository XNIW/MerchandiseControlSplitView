package com.example.merchandisecontrolsplitview

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.merchandisecontrolsplitview.ui.navigation.AppNavGraph
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.util.setLocale
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import com.example.merchandisecontrolsplitview.data.PriceBackfillWorker

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "it") ?: "it" // default italiano
        val context = setLocale(newBase, lang)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Avvia il backfill idempotente
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "price-backfill-v1",                 // cambia il suffisso v* se in futuro modifichi la logica
            ExistingWorkPolicy.KEEP,             // non enqueua se c'è già una run pendente
            OneTimeWorkRequestBuilder<PriceBackfillWorker>().build()
        )

        setContent {
            val context = LocalContext.current
            val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
            val themePref = remember {
                prefs.getString("theme", "auto") ?: "auto"
            }

            val darkTheme = when (themePref) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MerchandiseControlTheme(darkTheme = darkTheme) {
                // La chiamata ora è pulita, senza parametri.
                // Lo stato di navigazione è gestito internamente da AppNavGraph.
                AppNavGraph()
            }
        }
    }
}
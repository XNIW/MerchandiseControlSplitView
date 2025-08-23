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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ⬇️ “bus” per recapitare gli Uri alla UI
    object ShareBus {
        // prima: MutableSharedFlow<List<Uri>>(extraBufferCapacity = 1)
        val uris = MutableSharedFlow<List<Uri>>(replay = 1)
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "it") ?: "it" // default italiano
        val context = setLocale(newBase, lang)
        super.attachBaseContext(context)
    }

    private fun extractSharedUris(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        return when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM)
                listOfNotNull(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                l ?: emptyList()
            }
            else -> emptyList()
        }.also { list ->
            // (opzionale) prova a persistere dove applicabile (SAF); ignora errori
            list.forEach { uri ->
                try {
                    if (DocumentsContract.isDocumentUri(this, uri)) {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                } catch (_: Exception) { /* no-op */ }
            }
        }
    }

    private fun handleShareIntent(i: Intent?) {
        val incoming = extractSharedUris(i)
        if (incoming.isNotEmpty()) {
            lifecycleScope.launch { ShareBus.uris.tryEmit(incoming) }
            // evita re-trigger se l’activity viene ricreata con lo stesso intent
            intent?.action = null
            intent?.removeExtra(Intent.EXTRA_STREAM)
        }
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
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // usa la property access invece del setter
        this.intent = intent
        // share “a caldo”
        handleShareIntent(intent)
    }

}
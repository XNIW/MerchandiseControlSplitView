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
import kotlinx.coroutines.channels.BufferOverflow
import java.io.File
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.appcompat.app.AppCompatDelegate

class MainActivity : ComponentActivity() {

    // ⬇️ “bus” per recapitare gli Uri alla UI
    object ShareBus {
        // mantieni l'ultimo evento se il collector non è ancora partito
        val uris = MutableSharedFlow<List<Uri>>(
            replay = 1,
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)

        val saved = prefs.getString("lang", null)
        val langToUse = saved ?: run {
            // minSdk 31 → puoi usare sempre locales[0]
            val sysLang = newBase.resources.configuration.locales[0].language

            val normalized = when {
                sysLang.startsWith("zh", true) -> "zh"
                sysLang.startsWith("it", true) -> "it"
                sysLang.startsWith("es", true) -> "es"
                sysLang.startsWith("en", true) -> "en"
                else -> "en"
            }
            // KTX edit
            prefs.edit { putString("lang", normalized) }
            normalized
        }

        val context = setLocale(newBase, langToUse)
        super.attachBaseContext(context)
    }

    private fun Uri.guessExtension(contentType: String?): String {
        val fromMime = when (contentType) {
            "application/vnd.ms-excel" -> ".xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            "application/vnd.ms-excel.sheet.macroEnabled.12" -> ".xlsm"
            else -> null
        }
        val fromName = lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
            ?.let { if (it in listOf("xls","xlsx","xlsm")) ".$it" else null }
        return fromMime ?: fromName ?: ".xlsx"
    }

    // Copia in cache preservando meglio l’estensione
    private fun copyToCacheIfNeeded(src: Uri): Uri = try {
        if (DocumentsContract.isDocumentUri(this, src)) {
            src
        } else {
            val ext = src.guessExtension(contentResolver.getType(src))
            val out = File(cacheDir, "shared-${System.currentTimeMillis()}$ext")
            contentResolver.openInputStream(src)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.toUri()
        }
    } catch (_: Exception) {
        src
    }

    private fun extractSharedUris(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        val found = mutableListOf<Uri>()

        when (i.action) {
            Intent.ACTION_VIEW -> {
                i.data?.let { found += it }
            }
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) found += u
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                if (list != null) found += list
            }
        }

        // ✅ Fallback: molte app usano solo ClipData (anche per singolo file)
        i.clipData?.let { clip ->
            for (idx in 0 until clip.itemCount) {
                clip.getItemAt(idx)?.uri?.let { found += it }
            }
        }

        // Persisti se possibile e copia in cache se necessario (coerente col tuo codice attuale)
        return found.distinct().map { uri ->
            try {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (_: Exception) {}
            copyToCacheIfNeeded(uri) // già presente nel tuo file
        }
    }

    private fun handleShareIntent(i: Intent?) {
        val incoming = extractSharedUris(i)
        if (incoming.isNotEmpty()) {
            lifecycleScope.launch { ShareBus.uris.tryEmit(incoming) }
            // ✅ Pulisci TUTTO per evitare ri-trigger/residui
            intent?.action = null
            intent?.data = null
            intent?.clipData = null
            intent?.removeExtra(Intent.EXTRA_STREAM)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Imposta il tema predefinito "light" SOLO al primo avvio
        val prefsBoot = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefsBoot.contains("theme")) {
            prefsBoot.edit { putString("theme", "light") } // KTX
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Avvia il backfill idempotente
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "price-backfill-v1",                 // cambia il suffisso v* se in futuro modifichi la logica
            ExistingWorkPolicy.KEEP,             // non enqueua se c'è già una run pendente
            OneTimeWorkRequestBuilder<PriceBackfillWorker>().build()
        )
        handleShareIntent(intent)
        setContent {
            val context = LocalContext.current
            val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)

            // 🔁 default "light" (non più "auto")
            val themePref = remember { prefs.getString("theme", "light") ?: "light" }

            val darkTheme = when (themePref) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MerchandiseControlTheme(darkTheme = darkTheme) {
                AppNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        handleShareIntent(intent)
    }

}
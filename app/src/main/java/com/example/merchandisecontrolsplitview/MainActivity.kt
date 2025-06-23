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

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("lang", "it") ?: "it" // default italiano
        val context = setLocale(newBase, lang)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Leggi la preferenza del tema
            val context = LocalContext.current
            // Usa remember per evitare di rileggere ogni recomposition
            val themePref = remember {
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getString("theme", "auto") ?: "auto"
            }
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
}
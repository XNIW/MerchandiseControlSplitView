package com.example.merchandisecontrolsplitview.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavHostController
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.setLocale

// Import aggiunti
import androidx.compose.ui.Alignment // Import risolto per Alignment
import androidx.appcompat.app.AppCompatDelegate // Import risolto per AppCompatDelegate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    navController: NavHostController,
) {
    val context = LocalContext.current

    // --- Tema ---
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val savedTheme = prefs.getString("theme", "auto") ?: "auto"
    var themePref by remember { mutableStateOf(savedTheme) }

    val themeOptions = listOf(
        "auto" to stringResource(R.string.theme_auto),
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)
    )

    // --- Lingue ---
    val languages = listOf(
        "zh" to stringResource(id = R.string.chinese),
        "it" to stringResource(id = R.string.italian),
        "es" to stringResource(id = R.string.spanish),
        "en" to stringResource(id = R.string.english)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.options)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // ---- TEMA ----
            Text(text = stringResource(R.string.select_theme))
            Spacer(modifier = Modifier.height(8.dp))
            themeOptions.forEach { (value, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = themePref == value,
                        onClick = {
                            themePref = value
                            prefs.edit {
                                putString("theme", value)
                            }
                            // Imposta il tema runtime
                            when (value) {
                                "auto" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                            }
                            // Riavvia activity per forzare ricostruzione tema
                            (context as? Activity)?.recreate()
                        }
                    )
                    Text(label)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- LINGUA ----
            Text(text = stringResource(id = R.string.select_language))
            Spacer(modifier = Modifier.height(16.dp))
            languages.forEach { (langCode, langName) ->
                Button(
                    onClick = {
                        prefs.edit {
                            putString("lang", langCode)
                        }
                        setLocale(context, langCode)
                        (context as? Activity)?.recreate()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(langName)
                }
            }
        }
    }
}
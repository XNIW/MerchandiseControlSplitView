package com.example.merchandisecontrolsplitview.ui.screens

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavHostController
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.setLocale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    navController: NavHostController,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    BackHandler {
        navController.popBackStack()
    }

    val savedTheme = prefs.getString("theme", "light") ?: "light"
    var themePref by remember { mutableStateOf(savedTheme) }
    val themeOptions = listOf(
        "auto" to stringResource(R.string.theme_auto),
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)
    )

    val savedLang = prefs.getString("lang", "en") ?: "en"
    var langPref by remember { mutableStateOf(savedLang) }
    val languages = listOf(
        "zh" to stringResource(id = R.string.language_endonym_zh),
        "it" to stringResource(id = R.string.language_endonym_it),
        "es" to stringResource(id = R.string.language_endonym_es),
        "en" to stringResource(id = R.string.language_endonym_en)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- THEME SECTION (Vertical Radio Buttons) ---
            OptionsGroup(
                title = stringResource(R.string.select_theme),
                icon = Icons.Default.Palette
            ) {
                Column(Modifier.fillMaxWidth()) {
                    themeOptions.forEach { (value, label) ->
                        val selected = value == themePref
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        if (themePref != value) {
                                            themePref = value
                                            prefs.edit { putString("theme", value) }
                                            when (value) {
                                                "auto" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                                                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                                                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                                            }
                                            (context as? Activity)?.recreate()
                                        }
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            }

            // --- LANGUAGE SECTION (Vertical Radio Buttons) ---
            OptionsGroup(
                title = stringResource(id = R.string.select_language),
                icon = Icons.Default.Language
            ) {
                Column(Modifier.fillMaxWidth()) {
                    languages.forEach { (langCode, langName) ->
                        val selected = langCode == langPref
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        if (langPref != langCode) {
                                            langPref = langCode
                                            prefs.edit {
                                                putString("lang", langCode)
                                            }
                                            setLocale(context, langCode)
                                            (context as? Activity)?.recreate()
                                        }
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null
                            )
                            Text(
                                text = langName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun OptionsGroup(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalAlignment = Alignment.Start
            ) {
                content()
            }
        }
    }
}

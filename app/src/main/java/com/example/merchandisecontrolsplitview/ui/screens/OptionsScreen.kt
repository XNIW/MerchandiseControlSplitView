package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.util.setLocale
import com.example.merchandisecontrolsplitview.R
import androidx.core.content.edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    navController: NavHostController,
) {
    val context = LocalContext.current
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
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = stringResource(id = R.string.select_language))
            Spacer(modifier = Modifier.height(16.dp))
            languages.forEach { (langCode, langName) ->
                Button(
                    onClick = {
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        prefs.edit {
                            putString("lang", langCode)
                        }
                        setLocale(context, langCode)
                        (context as? android.app.Activity)?.recreate()
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
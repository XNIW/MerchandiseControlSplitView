package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.util.setLocale
import com.example.merchandisecontrolsplitview.R
// Import the KTX extension for SharedPreferences
import androidx.core.content.edit // This is the import you might need to add or confirm

@Composable
fun OptionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val languages = listOf(
        "zh" to stringResource(id = R.string.chinese),
        "it" to stringResource(id = R.string.italian),
        "es" to stringResource(id = R.string.spanish),
        "en" to stringResource(id = R.string.english)
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(id = R.string.select_language))
        Spacer(modifier = Modifier.height(16.dp))
        languages.forEach { (langCode, langName) ->
            Button(
                onClick = {
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

                    // Use the KTX extension function 'edit'
                    prefs.edit {
                        putString("lang", langCode)
                    }
                    // The .apply() is implicitly handled by the KTX extension
                    // You no longer need .apply() explicitly after putString inside this block.

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
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(id = R.string.back))
        }
    }
}
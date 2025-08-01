package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon

@Composable
fun FilePickerScreen(
    onFilesPicked: (List<Uri>) -> Unit,
    onViewHistory: () -> Unit,
    onDatabase: () -> Unit,
    onOptions: () -> Unit,
    onManualAdd: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        // Se l'utente ha scelto almeno un file, invoca la callback
        if (uris.isNotEmpty()) {
            onFilesPicked(uris)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // --- Pulsante Cronologia con Icona ---
            Button(
                onClick = onViewHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp) // MODIFICA: Assicurati che tutti abbiano padding sotto
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.file_history))
            }

            // --- Pulsante Carica Excel con Icona ---
            Button(
                onClick = {
                    launcher.launch(arrayOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    ))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp) // MODIFICA: Aggiunto padding sotto per uniformità
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.load_excel_file))
            }

            // --- Pulsante Aggiungi Manualmente ---
            Button(
                onClick = { onManualAdd() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp) // MODIFICA: Già corretto
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.add_products_manually))
            }

            // --- Pulsante Database con Icona ---
            Button(
                onClick = { onDatabase() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp) // MODIFICA: Cambiato da top a bottom
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.database))
            }

            // --- Pulsante Opzioni con Icona (l'ultimo non ha bisogno di padding bottom) ---
            Button(
                onClick = onOptions,
                modifier = Modifier.fillMaxWidth() // MODIFICA: Rimosso padding per l'ultimo elemento
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.options))
            }
        }
    }
}
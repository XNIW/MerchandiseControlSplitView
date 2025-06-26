package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel
import com.example.merchandisecontrolsplitview.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon

/**
 * Screen that allows the user to pick an Excel file from device storage.
 * @param onFilePicked callback invoked with the chosen file's Uri
 */
@Composable
fun FilePickerScreen(
    onFilePicked: (Uri) -> Unit,
    onViewHistory: () -> Unit,
    onDatabase: () -> Unit,
    onOptions: () -> Unit,
    viewModel: ExcelViewModel
) {
    LaunchedEffect(Unit) {
        viewModel.resetState()
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(onFilePicked)
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
                    .padding(bottom = 8.dp)
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.load_excel_file))
            }

            // --- Pulsante Database con Icona ---
            Button(
                onClick = { onDatabase() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(id = R.string.database))
            }

            // --- Pulsante Opzioni con Icona ---
            Button(
                onClick = onOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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
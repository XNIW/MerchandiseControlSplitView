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
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel

/**
 * Screen that allows the user to pick an Excel file from device storage.
 * @param onFilePicked callback invoked with the chosen file's Uri
 */
@Composable
fun FilePickerScreen(
    onFilePicked: (Uri) -> Unit,
    onViewHistory: () -> Unit,
    onDatabase: () -> Unit,         // AGGIUNGI QUESTA!
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
            Button(
                onClick = onViewHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text("File storico")
            }
            Button(
                onClick = {
                    launcher.launch(arrayOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Carica file Excel")
            }
            Button(
                onClick = { onDatabase() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Database")
            }

        }
    }
}
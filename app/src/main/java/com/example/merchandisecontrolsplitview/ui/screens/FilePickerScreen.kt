package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R

@OptIn(ExperimentalMaterial3Api::class)
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
        if (uris.isNotEmpty()) onFilesPicked(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) } // nessuna icona/login
            )
        }
    ) { innerPadding ->
        ActionsGrid(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            onHistory = onViewHistory,
            onLoadExcel = {
                launcher.launch(
                    arrayOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/html",
                        "application/octet-stream"
                    )
                )
            },
            onManualAdd = onManualAdd,
            onDatabase = onDatabase,
            onOptions = onOptions
        )
    }
}

@Composable
private fun ActionsGrid(
    modifier: Modifier = Modifier,
    onHistory: () -> Unit,
    onLoadExcel: () -> Unit,
    onManualAdd: () -> Unit,
    onDatabase: () -> Unit,
    onOptions: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        item {
            ActionCard(
                label = stringResource(R.string.file_history),
                icon = Icons.Filled.History,
                onClick = onHistory
            )
        }
        item {
            ActionCard(
                label = stringResource(R.string.load_excel_file),
                icon = Icons.Filled.UploadFile,
                onClick = onLoadExcel
            )
        }
        item {
            ActionCard(
                label = stringResource(R.string.add_products_manually),
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onClick = onManualAdd
            )
        }
        item {
            ActionCard(
                label = stringResource(R.string.database),
                icon = Icons.Filled.Storage,
                onClick = onDatabase
            )
        }
        // Opzioni: occupa due colonne per evitare l'effetto "mezza card"
        item(span = { GridItemSpan(2) }) {
            ActionCard(
                label = stringResource(R.string.options),
                icon = Icons.Filled.Settings,
                onClick = onOptions
            )
        }
    }
}

@Composable
private fun ActionCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .height(96.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
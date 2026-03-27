package com.example.merchandisecontrolsplitview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R

private val filePickerMimeTypes = arrayOf(
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/html",
    "application/octet-stream"
)

private data class SecondaryAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

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
                title = { Text(stringResource(R.string.app_name)) }
            )
        }
    ) { innerPadding ->
        ActionsGrid(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            onHistory = onViewHistory,
            onLoadExcel = { launcher.launch(filePickerMimeTypes) },
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
    val historyAction = SecondaryAction(
        label = stringResource(R.string.file_history),
        icon = Icons.Filled.History,
        onClick = onHistory
    )
    val manualAddAction = SecondaryAction(
        label = stringResource(R.string.add_products_manually),
        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
        onClick = onManualAdd
    )
    val databaseAction = SecondaryAction(
        label = stringResource(R.string.database),
        icon = Icons.Filled.Storage,
        onClick = onDatabase
    )
    val optionsAction = SecondaryAction(
        label = stringResource(R.string.options),
        icon = Icons.Filled.Settings,
        onClick = onOptions
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PrimaryActionCard(
            label = stringResource(R.string.load_excel_file),
            supportingText = stringResource(R.string.file_picker_primary_supporting),
            icon = Icons.Filled.UploadFile,
            onClick = onLoadExcel
        )
        SecondaryActionsRow(
            startAction = historyAction,
            endAction = manualAddAction
        )
        SecondaryActionsRow(
            startAction = databaseAction,
            endAction = optionsAction
        )
    }
}

@Composable
private fun SecondaryActionsRow(
    startAction: SecondaryAction,
    endAction: SecondaryAction
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SecondaryActionCard(
            action = startAction,
            modifier = Modifier.weight(1f)
        )
        SecondaryActionCard(
            action = endAction,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PrimaryActionCard(
    label: String,
    supportingText: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionIconBadge(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                )
            }
        }
    }
}

@Composable
private fun SecondaryActionCard(
    action: SecondaryAction,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = action.onClick,
        modifier = modifier.height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionIconBadge(
                icon = action.icon,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = action.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActionIconBadge(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(12.dp)
                .size(24.dp)
        )
    }
}

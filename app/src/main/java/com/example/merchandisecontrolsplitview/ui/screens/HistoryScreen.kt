package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.viewmodel.HistoryEntry
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.navigation.Screen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    historyList: List<HistoryEntry>,
    onSelect: (HistoryEntry) -> Unit,
    onRename: (HistoryEntry, String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onBack: () -> Unit
) {
    // Stati per i dialog, gestiti a livello di schermata
    var showRenameDialog by remember { mutableStateOf(false) }
    var entryToRename by remember { mutableStateOf<HistoryEntry?>(null) }
    var renameText by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    var navigateToEntryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(navigateToEntryId) {
        navigateToEntryId?.let { entryId ->
            val encodedId = URLEncoder.encode(entryId, StandardCharsets.UTF_8.toString())
            val route = Screen.Generated.route.replace("{entryId}", encodedId)
            navController.navigate(route)
            navigateToEntryId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
        ) {
            items(historyList, key = { it.id }) { entry ->
                // La LazyColumn ora è molto più pulita, chiama solo il componente HistoryRow
                HistoryRow(
                    entry = entry,
                    onClick = {
                        onSelect(entry)
                        navigateToEntryId = entry.id
                    },
                    onRenameClick = {
                        entryToRename = entry
                        renameText = entry.id
                        showRenameDialog = true
                    },
                    onDeleteClick = {
                        entryToDelete = entry
                        showDeleteDialog = true
                    }
                )
            }
        }
    }

    // I dialog rimangono a livello di schermata per essere gestiti centralmente
    if (showRenameDialog && entryToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_file)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    entryToRename?.let { onRename(it, renameText) }
                    showRenameDialog = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDeleteDialog && entryToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    entryToDelete?.let(onDelete)
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/**
 * NUOVO Composable che rappresenta una singola riga della cronologia.
 * Contiene tutta la logica di UI, incluso lo swipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteClick()
                    false // Non far scomparire la riga, mostriamo un dialogo
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onRenameClick()
                    false // Non far scomparire la riga, mostriamo un dialogo
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.targetValue
            if (direction != SwipeToDismissBoxValue.Settled) {
                val color = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Color.DarkGray
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFB00020) // Un rosso più scuro
                    else -> Color.Transparent
                }
                val icon = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                    else -> null
                }
                val align = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    else -> Alignment.Center
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 20.dp),
                    contentAlignment = align
                ) {
                    icon?.let { Icon(it, contentDescription = null, tint = Color.White) }
                }
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Padding verticale ridotto per rendere la riga più sottile
                        .padding(top = 10.dp, bottom = 30.dp, start = 16.dp, end = 16.dp)
                ) {
                    Text(
                        text = entry.id,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Data: ${entry.timestamp}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (entry.supplier.isNotBlank()) {
                        Text("Fornitore: ${entry.supplier}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Icone di Stato in basso a destra
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusIcon(
                        baseIcon = Icons.Default.Sync,
                        showBadge = entry.wasSyncedSuccessfully,
                        contentDescription = "Stato Sincronizzazione"
                    )
                    StatusIcon(
                        baseIcon = Icons.Default.FileDownload,
                        showBadge = entry.wasExported,
                        contentDescription = "Stato Esportazione"
                    )
                }
            }
        }
    }
}


/**
 * Composable helper per visualizzare un'icona con un badge di conferma opzionale.
 */
@Composable
private fun StatusIcon(
    baseIcon: ImageVector,
    showBadge: Boolean,
    contentDescription: String
) {
    Box {
        Icon(
            imageVector = baseIcon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showBadge) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Completato",
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp),
                tint = Color(0xFF00C853) // Colore verde per il badge
            )
        }
    }
}
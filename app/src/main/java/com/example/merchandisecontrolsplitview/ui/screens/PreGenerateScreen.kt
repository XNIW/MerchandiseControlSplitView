package com.example.merchandisecontrolsplitview.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.ui.components.ZoomableExcelGrid
import com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModel

/**
 * Screen for selecting columns before generating the filtered sheet.
 */
@Composable
fun PreGenerateScreen(
    viewModel: ExcelViewModel,
    onGenerate: () -> Unit,
    onBack: () -> Unit
) {
    // State from ViewModel
    val excelData = viewModel.excelData
    val selectedColumns = viewModel.selectedColumns
    val editableValues = viewModel.editableValues
    val completeStates = viewModel.completeStates
    val isLoading by viewModel.isLoading
    val loadError by viewModel.loadError

    // Local UI state
    var editMode by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Intercept back gesture to confirm exit
    BackHandler {
        showExitDialog = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            loadError != null -> {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            excelData.isNotEmpty() -> {
                ZoomableExcelGrid(
                    data = excelData,
                    cellWidth = 120.dp,
                    cellHeight = 48.dp,
                    selectedColumns = selectedColumns,
                    editableValues = editableValues,
                    completeStates = completeStates,
                    searchMatches = emptySet(),
                    generated = false,
                    editMode = editMode,
                    onCompleteToggle = {},
                    onCellEditRequest = { _, _ -> },
                    onQuantityCellClick = {},
                    onPriceCellClick = {},
                    onRowCellClick = { }
                )
            }
        }

        // FABs: Seleziona Tutto / Modifica / Genera
        if (excelData.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                if (!editMode) {
                    FloatingActionButton(onClick = {
                        val anyUnselected = selectedColumns.any { !it }
                        selectedColumns.forEachIndexed { idx, _ ->
                            selectedColumns[idx] = anyUnselected
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Seleziona tutto"
                        )
                    }
                }
                FloatingActionButton(onClick = {
                    editMode = !editMode
                }) {
                    Icon(
                        imageVector = if (editMode) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = if (editMode) "Esci da modifica" else "Modifica"
                    )
                }
                if (!editMode) {
                    FloatingActionButton(onClick = onGenerate) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Genera foglio filtrato"
                        )
                    }
                }
            }
        }

        // Dialog per conferma di uscita
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Sei sicuro di uscire? Perderai i dati caricati.") },
                confirmButton = {
                    TextButton(onClick = {
                        showExitDialog = false
                        onBack()
                    }) {
                        Text("Esci")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("Annulla")
                    }
                }
            )
        }
    }
}
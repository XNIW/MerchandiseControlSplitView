package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R

@Composable
fun ZoomableExcelGrid(
    data: List<List<String>>,
    cellWidth: Dp,
    cellHeight: Dp,
    selectedColumns: SnapshotStateList<Boolean>,
    editableValues: List<List<MutableState<String>>>,
    completeStates: SnapshotStateList<Boolean>,
    searchMatches: Set<Pair<Int, Int>>,
    errorRowIndexes: Set<Int>,
    generated: Boolean,
    editMode: Boolean,
    onCompleteToggle: (Int) -> Unit,
    onCellEditRequest: (Int, Int) -> Unit,
    onQuantityCellClick: (Int) -> Unit,
    onPriceCellClick: (Int) -> Unit,
    onRowCellClick: (Int) -> Unit,
    headerTypes: List<String>? = null,
    onHeaderClick: ((colIndex: Int) -> Unit)? = null
) {
    if (data.isEmpty()) return

    val columnCount = data[0].size
    if (selectedColumns.size != columnCount) {
        selectedColumns.clear().also { repeat(columnCount) { selectedColumns.add(false) } }
    }

    val hasEditable = columnCount >= 3
    val indexQuantita = columnCount - 3
    val indexPrezzo = columnCount - 2
    val indexCompleto = columnCount - 1

    val horizontalState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.horizontalScroll(horizontalState)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Header row
                item {
                    Row {
                        repeat(columnCount) { ci ->
                            // --- MODIFICA CHIAVE: Logica colori header ---
                            val headerBgColor = when (headerTypes?.getOrNull(ci)) {
                                // Verde per le colonne trovate tramite ALIAS
                                "alias" -> if (isSystemInDarkTheme()) Color(0xFF00C853).copy(alpha = 0.5f) else Color(0xFFB9F6CA)
                                // Arancione per le colonne trovate tramite PATTERN
                                "pattern" -> if (isSystemInDarkTheme()) Color(0xFFFF9100).copy(alpha = 0.5f) else Color(0xFFFFD180)
                                // Altrimenti, TableCell userà il suo grigio di default
                                else -> null
                            }
                            TableCell(
                                text = data[0][ci],
                                width = cellWidth,
                                height = cellHeight,
                                isHeader = true,
                                onCellClick = if (onHeaderClick != null) { { onHeaderClick(ci) } } else null,
                                overrideBackgroundColor = headerBgColor
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
                }

                // Data rows (nessuna modifica qui)
                itemsIndexed(data.drop(1)) { idx, row ->
                    val r = idx + 1
                    val bothFilled = if (hasEditable) {
                        editableValues.getOrNull(r)
                            ?.let { it.getOrNull(0)?.value?.isNotEmpty() == true && it.getOrNull(1)?.value?.isNotEmpty() == true }
                            ?: false
                    } else false
                    val isComplete = completeStates.getOrNull(r) == true

                    // --- LOGICA DI EVIDENZIAZIONE ---
                    val isErrorRow = errorRowIndexes.contains(r)
                    // Il colore rosso viene usato solo se la riga è errata, altrimenti è null.
                    val highlightColor = if (isErrorRow) Color.Red.copy(alpha = 0.2f) else null
                    // --- FINE LOGICA ---

                    // La Row ora non ha più uno sfondo
                    Row {
                        row.forEachIndexed { ci, cell ->
                            val isMatch = searchMatches.contains(r to ci)
                            when {
                                !generated -> TableCell(
                                    text = cell,
                                    width = cellWidth,
                                    height = cellHeight,
                                    isSelectedColumn = selectedColumns.getOrElse(ci) { false },
                                    isSearchMatch = isMatch,
                                    onCellClick = { selectedColumns[ci] = !selectedColumns[ci] },
                                    // Passiamo il colore di highlight a ogni cella
                                    overrideBackgroundColor = highlightColor
                                )
                                generated -> when {
                                    editMode -> {
                                        val text = when (ci) {
                                            indexQuantita -> editableValues.getOrNull(r)?.getOrNull(0)?.value.orEmpty()
                                            indexPrezzo -> editableValues.getOrNull(r)?.getOrNull(1)?.value.orEmpty()
                                            else -> cell
                                        }
                                        TableCell(
                                            text = text, width = cellWidth, height = cellHeight,
                                            isRowFilled = bothFilled, isSearchMatch = isMatch, isRowComplete = isComplete,
                                            onCellClick = { onCellEditRequest(r, ci) },
                                            overrideBackgroundColor = highlightColor
                                        )
                                    }
                                    hasEditable && ci == indexQuantita -> TableCell(
                                        text = editableValues.getOrNull(r)?.getOrNull(0)?.value.orEmpty(),
                                        width = cellWidth, height = cellHeight,
                                        isRowFilled = bothFilled, isSearchMatch = isMatch, isRowComplete = isComplete,
                                        onCellClick = { onQuantityCellClick(r) },
                                        overrideBackgroundColor = highlightColor
                                    )
                                    hasEditable && ci == indexPrezzo -> TableCell(
                                        text = editableValues.getOrNull(r)?.getOrNull(1)?.value.orEmpty(),
                                        width = cellWidth, height = cellHeight,
                                        isRowFilled = bothFilled, isSearchMatch = isMatch, isRowComplete = isComplete,
                                        onCellClick = { onPriceCellClick(r) },
                                        overrideBackgroundColor = highlightColor
                                    )
                                    hasEditable && ci == indexCompleto -> {
                                        val completeColor = if (isSystemInDarkTheme()) Color(0xFF00C853).copy(alpha = 0.5f) else Color(0xFFB9F6CA)
                                        Box(
                                            modifier = Modifier
                                                .width(cellWidth)
                                                .height(cellHeight)
                                                // La logica del background ora dà priorità all'errore
                                                .background(
                                                    when {
                                                        isErrorRow -> highlightColor!! // Usa il colore rosso se c'è errore
                                                        isComplete -> completeColor     // Altrimenti il colore verde se completo
                                                        else -> MaterialTheme.colorScheme.surface // Altrimenti il default
                                                    }
                                                )
                                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                                .clickable { onCompleteToggle(r) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = stringResource(R.string.header_complete),
                                                tint = if (isComplete) Color.Black.copy(alpha = 0.8f)
                                                else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    else -> TableCell(
                                        text = cell, width = cellWidth, height = cellHeight,
                                        isRowFilled = bothFilled, isSearchMatch = isMatch, isRowComplete = isComplete,
                                        onCellClick = { onRowCellClick(r) },
                                        overrideBackgroundColor = highlightColor
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}
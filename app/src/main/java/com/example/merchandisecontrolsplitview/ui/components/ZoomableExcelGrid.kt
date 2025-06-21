package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R

/**
 * A zoomable and scrollable grid for displaying Excel-like data.
 * Riconosce le ultime 3 colonne come: Quantità, Prezzo, Completo.
 */
@Composable
fun ZoomableExcelGrid(
    data: List<List<String>>,
    cellWidth: Dp,
    cellHeight: Dp,
    selectedColumns: SnapshotStateList<Boolean>,
    editableValues: List<List<MutableState<String>>>,
    completeStates: SnapshotStateList<Boolean>,
    searchMatches: Set<Pair<Int, Int>>,
    generated: Boolean,
    editMode: Boolean,
    onCompleteToggle: (Int) -> Unit,
    onCellEditRequest: (Int, Int) -> Unit,
    onQuantityCellClick: (Int) -> Unit,
    onPriceCellClick: (Int) -> Unit,
    onRowCellClick: (Int) -> Unit
) {
    if (data.isEmpty()) return

    val columnCount = data[0].size
    if (selectedColumns.size != columnCount) {
        selectedColumns.clear().also { repeat(columnCount) { selectedColumns.add(false) } }
    }

    // Indici delle ultime tre colonne
    val hasEditable = columnCount >= 3
    val indexQuantita = columnCount - 3
    val indexPrezzo = columnCount - 2
    val indexCompleto = columnCount - 1

    val horizontalState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF9F9F9))
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalState)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Header row
                item {
                    Row {
                        repeat(columnCount) { ci ->
                            TableCell(
                                text = data[0][ci],
                                width = cellWidth,
                                height = cellHeight,
                                isHeader = true,
                                isSelectedColumn = false,
                                isRowFilled = false,
                                isSearchMatch = false,
                                isRowComplete = false,
                                onCellClick = null
                            )
                        }
                    }
                    HorizontalDivider(color = Color.Gray, thickness = 1.dp)
                }

                // Data rows
                itemsIndexed(data.drop(1)) { idx, row ->
                    val r = idx + 1
                    val bothFilled = if (hasEditable) {
                        editableValues.getOrNull(r)
                            ?.let { it.getOrNull(0)?.value?.isNotEmpty() == true && it.getOrNull(1)?.value?.isNotEmpty() == true }
                            ?: false
                    } else false
                    val isComplete = completeStates.getOrNull(r) == true

                    Row(
                        Modifier
                            .background(if (isComplete) Color(0xFFB9F6CA) else Color.Unspecified) // riga verde se completa
                    ) {
                        row.forEachIndexed { ci, cell ->
                            val isMatch = searchMatches.contains(r to ci)
                            when {
                                // PreGenerateScreen: selezione colonne
                                !generated -> TableCell(
                                    text = row.getOrNull(ci).orEmpty(),
                                    width = cellWidth,
                                    height = cellHeight,
                                    isHeader = false,
                                    isSelectedColumn = selectedColumns.getOrElse(ci) { false },
                                    isRowFilled = false,
                                    isSearchMatch = isMatch,
                                    isRowComplete = false,
                                    onCellClick = { selectedColumns[ci] = !selectedColumns[ci] }
                                )
                                // GeneratedScreen: colonne speciali
                                generated -> when {
                                    // Edit mode: tutte le celle editabili
                                    editMode -> {
                                        val text = when (ci) {
                                            indexQuantita -> editableValues.getOrNull(r)?.getOrNull(0)?.value.orEmpty()
                                            indexPrezzo -> editableValues.getOrNull(r)?.getOrNull(1)?.value.orEmpty()
                                            else -> row.getOrNull(ci).orEmpty()
                                        }
                                        TableCell(
                                            text = text,
                                            width = cellWidth,
                                            height = cellHeight,
                                            isHeader = false,
                                            isSelectedColumn = false,
                                            isRowFilled = bothFilled,
                                            isSearchMatch = isMatch,
                                            isRowComplete = isComplete,
                                            onCellClick = { onCellEditRequest(r, ci) }
                                        )
                                    }
                                    // Colonna Quantità (prima delle ultime 3)
                                    hasEditable && ci == indexQuantita -> TableCell(
                                        text = editableValues.getOrNull(r)?.getOrNull(0)?.value.orEmpty(),
                                        width = cellWidth,
                                        height = cellHeight,
                                        isHeader = false,
                                        isSelectedColumn = false,
                                        isRowFilled = bothFilled,
                                        isSearchMatch = isMatch,
                                        isRowComplete = isComplete,
                                        onCellClick = { onQuantityCellClick(r) }
                                    )
                                    // Colonna Prezzo
                                    hasEditable && ci == indexPrezzo -> TableCell(
                                        text = editableValues.getOrNull(r)?.getOrNull(1)?.value.orEmpty(),
                                        width = cellWidth,
                                        height = cellHeight,
                                        isHeader = false,
                                        isSelectedColumn = false,
                                        isRowFilled = bothFilled,
                                        isSearchMatch = isMatch,
                                        isRowComplete = isComplete,
                                        onCellClick = { onPriceCellClick(r) }
                                    )
                                    // Colonna Completo (spunta cliccabile)
                                    hasEditable && ci == indexCompleto -> Box(
                                        modifier = Modifier
                                            .width(cellWidth)
                                            .height(cellHeight)
                                            .background(if (isComplete) Color.Green else Color.White)
                                            .border(1.dp, Color.Gray)
                                            .clip(RoundedCornerShape(2.dp))
                                            .clickable { onCompleteToggle(r) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.header_complete),
                                            tint = if (isComplete) Color.White else Color.Gray
                                        )
                                    }
                                    // Celle normali
                                    else -> TableCell(
                                        text = row.getOrNull(ci).orEmpty(),
                                        width = cellWidth,
                                        height = cellHeight,
                                        isHeader = false,
                                        isSelectedColumn = selectedColumns.getOrElse(ci) { false },
                                        isRowFilled = bothFilled,
                                        isSearchMatch = isMatch,
                                        isRowComplete = isComplete,
                                        onCellClick = {
                                            if (hasEditable || generated) onRowCellClick(r)
                                            else selectedColumns[ci] = !selectedColumns[ci]
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
                }
            }
        }
    }
}
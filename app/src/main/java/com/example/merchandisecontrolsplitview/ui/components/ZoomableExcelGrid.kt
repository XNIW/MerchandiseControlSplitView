package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable         // ← per clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons       // ← per Icons
import androidx.compose.material.icons.filled.Check // ← per l’icona di “check”
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip               // ← per clip()
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/**
 * A zoomable and scrollable grid for displaying Excel-like data.
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
    // Ensure selectedColumns matches column count
    if (selectedColumns.size != columnCount) {
        selectedColumns.clear().also { repeat(columnCount) { selectedColumns.add(false) } }
    }

    // Detect if "Quantità", "Prezzo", "Completo" columns present
    val hasEditable = columnCount >= 3 && data[0].takeLast(3) == listOf("Quantità", "Prezzo", "Completo")
    val originalColumnCount = if (hasEditable) columnCount - 3 else columnCount

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
                            ?.let { it[0].value.isNotEmpty() && it[1].value.isNotEmpty() }
                            ?: false
                    } else false
                    val isComplete = completeStates.getOrNull(r) == true

                    Row {
                        repeat(columnCount) { ci ->
                            val isMatch = searchMatches.contains(r to ci)
                            when {
                                // In editMode, all cells editable
                                editMode -> {
                                    val text = if (hasEditable && ci >= originalColumnCount)
                                        editableValues.getOrNull(r)?.getOrNull(ci - originalColumnCount)?.value.orEmpty()
                                    else row.getOrNull(ci).orEmpty()

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
                                // Editable columns Quantità, Prezzo, Completo
                                hasEditable && ci >= originalColumnCount -> {
                                    when (ci - originalColumnCount) {
                                        0 -> TableCell(
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
                                        1 -> TableCell(
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
                                        2 -> Box(
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
                                                contentDescription = "Completo",
                                                tint = if (isComplete) Color.White else Color.Gray
                                            )
                                        }
                                        else -> {}
                                    }
                                }
                                // Normal columns
                                else -> {
                                    TableCell(
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

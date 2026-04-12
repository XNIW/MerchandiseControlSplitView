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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.theme.appColors
import com.example.merchandisecontrolsplitview.util.formatGridNumericDisplay
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput

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
    columnKeys: List<String>? = null,
    onHeaderClick: ((colIndex: Int) -> Unit)?,
    // --- NUOVO: Parametri per gestire le colonne essenziali ---
    isColumnEssential: (colIndex: Int) -> Boolean,
    onHeaderEditClick: ((colIndex: Int) -> Unit)?,
    isManualEntry: Boolean,
    // When non-null: data rows are pre-filtered; rowIndexMapping[displayIdx] = real row index in
    // completeStates / editableValues / errorRowIndexes / searchMatches / callbacks.
    rowIndexMapping: List<Int>? = null
) {
    if (data.isEmpty()) return

    val appColors = MaterialTheme.appColors
    val isDarkTheme = isSystemInDarkTheme()
    val headerEssentialAlpha = if (isDarkTheme) 0.38f else 0.22f
    val headerMetaAlpha = if (isDarkTheme) 0.24f else 0.10f
    val rowErrorAlpha = if (isDarkTheme) 0.54f else 0.24f
    val selectedColumnBorderAlpha = if (isDarkTheme) 0.82f else 0.60f
    val completeAccentAlpha = if (isDarkTheme) 0.28f else 0.12f

    val columnCount = data[0].size
    if (selectedColumns.size != columnCount) {
        selectedColumns.clear().also { repeat(columnCount) { selectedColumns.add(false) } }
    }

    val hasEditable = columnCount >= 3
    val indexQuantita = columnCount - 3
    val indexPrezzo = columnCount - 2
    val indexCompleto = columnCount - 1
    val originalQuantityIndex = columnKeys?.indexOf("quantity") ?: -1

    val horizontalState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.horizontalScroll(horizontalState)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 104.dp)
            ) {
                // Header row
                item {
                    Row {
                        repeat(columnCount) { ci ->
                            // --- NUOVO: Verifica se la colonna è essenziale ---
                            val isEssential = isColumnEssential(ci)

                            // Header/meta-state remain intentionally lighter than data-row priorities.
                            val headerBgColor = when {
                                isEssential -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = headerEssentialAlpha)
                                headerTypes?.getOrNull(ci) == "alias" -> appColors.gridAliasBackground.copy(alpha = headerMetaAlpha)
                                headerTypes?.getOrNull(ci) == "pattern" -> appColors.gridPatternBackground.copy(alpha = headerMetaAlpha)
                                else -> null
                            }

                            // --- MODIFICA: La chiamata a TableCell ora passa anche onEditClick ---
                            TableCell(
                                text = data[0][ci],
                                width = cellWidth,
                                height = cellHeight,
                                isHeader = true,
                                onCellClick = if (onHeaderClick != null) { { onHeaderClick(ci) } } else null,
                                onEditClick = if (onHeaderEditClick != null) { { onHeaderEditClick(ci) } } else null,
                                overrideBackgroundColor = headerBgColor,
                                isSelectedColumn = selectedColumns.getOrElse(ci) { false }
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f),
                        thickness = 0.75.dp
                    )
                }

                // Data rows
                itemsIndexed(data.drop(1)) { idx, row ->
                    // When rowIndexMapping is provided the data is pre-filtered; map back to the
                    // real row index so that completeStates / editableValues / callbacks all refer
                    // to the correct position in the full dataset.
                    val r = rowIndexMapping?.getOrNull(idx) ?: (idx + 1)
                    val bothFilled = if (hasEditable) {
                        editableValues.getOrNull(r)
                            ?.let { it.getOrNull(0)?.value?.isNotEmpty() == true && it.getOrNull(1)?.value?.isNotEmpty() == true }
                            ?: false
                    } else false
                    val isComplete = completeStates.getOrNull(r) == true
                    val countedQuantity = if (hasEditable) {
                        parseUserQuantityInput(editableValues.getOrNull(r)?.getOrNull(0)?.value)
                    } else {
                        null
                    }
                    val originalQuantity = if (originalQuantityIndex != -1) {
                        parseUserQuantityInput(row.getOrNull(originalQuantityIndex))
                    } else {
                        null
                    }
                    // Keep the row visibly incomplete when the counted quantity is lower than
                    // the source-file quantity, even if the retail-price field is still blank.
                    val hasIncompleteQuantity = !isComplete &&
                        countedQuantity != null &&
                        originalQuantity != null &&
                        countedQuantity < originalQuantity
                    val hasSecondaryRowState = bothFilled || hasIncompleteQuantity
                    val isErrorRow = errorRowIndexes.contains(r)
                    val highlightColor = if (isErrorRow) {
                        MaterialTheme.colorScheme.errorContainer.copy(
                            alpha = rowErrorAlpha
                        )
                    } else {
                        null
                    }

                    Row {
                        row.forEachIndexed { ci, cell ->
                            val isMatch = searchMatches.contains(r to ci)
                            val formattedCell = formatGridNumericDisplay(
                                rawValue = cell,
                                columnKey = columnKeys?.getOrNull(ci)
                            )
                            if (!generated || isManualEntry) {
                                TableCell(
                                    text = formattedCell,
                                    width = cellWidth,
                                    height = cellHeight,
                                    isSelectedColumn = selectedColumns.getOrElse(ci) { false },
                                    isSearchMatch = isMatch,
                                    // Per la modalità manuale, il click sulla riga apre il dialog di modifica.
                                    onCellClick = if (isManualEntry) { { onRowCellClick(r) } } else { { onHeaderClick?.invoke(ci) } },
                                    overrideBackgroundColor = highlightColor
                                )
                            }
                            // CONDIZIONE 2: Altrimenti (quindi, solo se `generated` è true E `isManualEntry` è false),
                            // usa la logica complessa esistente per le entry da file Excel.
                            else {
                                when {
                                    editMode -> {
                                        val text = when (ci) {
                                            indexQuantita -> editableValues.getOrNull(r)?.getOrNull(0)?.value.orEmpty()
                                            indexPrezzo -> editableValues.getOrNull(r)?.getOrNull(1)?.value.orEmpty()
                                            else -> cell
                                        }
                                        TableCell(
                                            text = text, width = cellWidth, height = cellHeight,
                                            isRowFilled = hasSecondaryRowState, isSearchMatch = isMatch, isRowComplete = isComplete,
                                            onCellClick = { onCellEditRequest(r, ci) },
                                            overrideBackgroundColor = highlightColor
                                        )
                                    }
                                    hasEditable && ci == indexQuantita -> TableCell(
                                        text = formatGridNumericDisplay(
                                            rawValue = editableValues.getOrNull(r)?.getOrNull(0)?.value.orEmpty(),
                                            columnKey = columnKeys?.getOrNull(ci)
                                        ),
                                        width = cellWidth, height = cellHeight,
                                        isRowFilled = hasSecondaryRowState, isSearchMatch = isMatch, isRowComplete = isComplete,
                                        onCellClick = { onQuantityCellClick(r) },
                                        overrideBackgroundColor = highlightColor
                                    )
                                    hasEditable && ci == indexPrezzo -> TableCell(
                                        text = formatGridNumericDisplay(
                                            rawValue = editableValues.getOrNull(r)?.getOrNull(1)?.value.orEmpty(),
                                            columnKey = columnKeys?.getOrNull(ci)
                                        ),
                                        width = cellWidth, height = cellHeight,
                                        isRowFilled = hasSecondaryRowState, isSearchMatch = isMatch, isRowComplete = isComplete,
                                        onCellClick = { onPriceCellClick(r) },
                                        overrideBackgroundColor = highlightColor
                                    )
                                    hasEditable && ci == indexCompleto -> {
                                        val isSelectedColumn = selectedColumns.getOrElse(ci) { false }
                                        val completeBackgroundColor = when {
                                            isErrorRow -> highlightColor!!
                                            isComplete -> appColors.successContainer.copy(alpha = completeAccentAlpha)
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                        val completeBorderColor = when {
                                            isErrorRow -> MaterialTheme.colorScheme.error
                                            isComplete -> appColors.success
                                            isSelectedColumn -> MaterialTheme.colorScheme.primary.copy(alpha = selectedColumnBorderAlpha)
                                            else -> MaterialTheme.colorScheme.outlineVariant
                                        }
                                        Box(
                                            modifier = Modifier
                                                .width(cellWidth)
                                                .height(cellHeight)
                                                .background(completeBackgroundColor)
                                                .border(if (isErrorRow || isSelectedColumn) 1.dp else 0.5.dp, completeBorderColor)
                                                .clickable { onCompleteToggle(r) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = stringResource(R.string.header_complete),
                                                tint = when {
                                                    isErrorRow -> MaterialTheme.colorScheme.onErrorContainer
                                                    isComplete -> appColors.success
                                                    isSelectedColumn -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.outline
                                                }
                                            )
                                        }
                                    }
                                    else -> TableCell(
                                        text = formattedCell, width = cellWidth, height = cellHeight,
                                        isRowFilled = hasSecondaryRowState, isSearchMatch = isMatch, isRowComplete = isComplete,
                                        onCellClick = { onRowCellClick(r) },
                                        overrideBackgroundColor = highlightColor
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}

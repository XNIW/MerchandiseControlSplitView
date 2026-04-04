package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import com.example.merchandisecontrolsplitview.R

@Composable
fun TableCell(
    text: String,
    width: Dp,
    height: Dp,
    isHeader: Boolean = false,
    isSelectedColumn: Boolean = false,
    isRowFilled: Boolean = false,
    isSearchMatch: Boolean = false,
    isRowComplete: Boolean = false,
    onCellClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null, // <-- NUOVO: Parametro per il click sull'icona
    overrideBackgroundColor: Color? = null
) {
    val defaultBackgroundColor = MaterialTheme.colorScheme.surface

    val finalBackgroundColor = overrideBackgroundColor ?: when {
        isHeader -> MaterialTheme.colorScheme.surfaceVariant
        isRowComplete -> if (isSystemInDarkTheme()) Color(0xFF00C853).copy(alpha = 0.5f) else Color(0xFFB9F6CA)
        isRowFilled -> if (isSystemInDarkTheme()) Color(0xFFFFD600).copy(alpha = 0.5f) else Color(0xFFFFF176)
        isSearchMatch -> MaterialTheme.colorScheme.tertiaryContainer
        isSelectedColumn -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else -> defaultBackgroundColor
    }

    val textColor = when {
        isHeader -> MaterialTheme.colorScheme.onSurfaceVariant
        isSearchMatch -> MaterialTheme.colorScheme.onTertiaryContainer
        isSelectedColumn -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(finalBackgroundColor)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(
                // L'intera cella rimane cliccabile per la selezione/deselezione
                if (onCellClick != null) Modifier.clickable { onCellClick() }
                else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // --- INIZIO MODIFICA ---
        // Usiamo una Row per affiancare testo e icona
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = textColor,
                style = if (isHeader)
                    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                else
                    MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f) // Fa in modo che il testo occupi lo spazio rimanente
            )

            // NUOVO: Mostra l'icona solo se è un header e la funzione di click è fornita
            if (isHeader && onEditClick != null) {
                IconButton(onClick = onEditClick, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit_column_type),
                        tint = textColor
                    )
                }
            }
        }
        // --- FINE MODIFICA ---
    }
}

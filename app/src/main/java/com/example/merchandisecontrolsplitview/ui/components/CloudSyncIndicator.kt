package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressState
import com.example.merchandisecontrolsplitview.data.CatalogSyncStatus
import com.example.merchandisecontrolsplitview.data.CatalogSyncStage
import kotlinx.coroutines.delay

/**
 * Indicatore "sync cloud in corso" leggero, da posizionare in alto a destra
 * nelle schermate root. Visibile solo quando [isSyncing] e true.
 *
 * Niente redesign: e un Box circolare con l'icona `Sync` di Material che
 * ruota finche la sync e in corso, con fade-in/out sottile.
 */
@Composable
fun CloudSyncIndicator(
    state: CatalogSyncProgressState,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(state.isBusy) }
    LaunchedEffect(state.status, state.stage, state.current, state.total) {
        when (state.status) {
            CatalogSyncStatus.RUNNING -> visible = true
            CatalogSyncStatus.COMPLETED -> {
                visible = true
                delay(2_200L)
                visible = false
            }
            CatalogSyncStatus.FAILED -> {
                visible = true
                delay(3_600L)
                visible = false
            }
            CatalogSyncStatus.IDLE -> visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
        modifier = modifier
    ) {
        val infinite = rememberInfiniteTransition(label = "cloudSyncIndicatorRotation")
        val angle by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "cloudSyncIndicatorAngle"
        )
        val containerColor = when (state.status) {
            CatalogSyncStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            CatalogSyncStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val contentColor = when (state.status) {
            CatalogSyncStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
            CatalogSyncStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val iconTint = when (state.status) {
            CatalogSyncStatus.FAILED -> MaterialTheme.colorScheme.error
            CatalogSyncStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.primary
        }
        val icon = when (state.status) {
            CatalogSyncStatus.FAILED -> Icons.Default.Error
            CatalogSyncStatus.COMPLETED -> Icons.Default.CheckCircle
            else -> Icons.Default.Sync
        }
        val iconModifier = Modifier
            .size(16.dp)
            .let { base -> if (state.isBusy) base.rotate(-angle) else base }
        val stageMessage = catalogSyncStageMessage(state)
        val indicatorDescription = stringResource(R.string.cloud_sync_indicator_status_cd, stageMessage)
        Surface(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .semantics { contentDescription = indicatorDescription },
            shape = CircleShape,
            color = containerColor,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = iconModifier
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stageMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 2
                    )
                    catalogSyncDetailMessage(state)?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun catalogSyncStageMessage(state: CatalogSyncProgressState): String {
    val current = state.current
    val total = state.total
    @Composable
    fun counted(defaultRes: Int, countedRes: Int): String =
        if (current != null && total != null && total > 0) {
            stringResource(countedRes, current.coerceAtMost(total), total)
        } else {
            stringResource(defaultRes)
        }

    return when (state.stage) {
        CatalogSyncStage.COMPLETED -> when (state.status) {
            CatalogSyncStatus.FAILED -> stringResource(R.string.cloud_sync_indicator_failed)
            else -> stringResource(R.string.cloud_sync_indicator_completed)
        }
        CatalogSyncStage.REALIGN -> stringResource(R.string.catalog_cloud_stage_realign_short)
        CatalogSyncStage.PUSH_SUPPLIERS -> counted(
            R.string.catalog_cloud_stage_push_suppliers_short,
            R.string.catalog_cloud_stage_push_suppliers_count_short
        )
        CatalogSyncStage.PUSH_CATEGORIES -> counted(
            R.string.catalog_cloud_stage_push_categories_short,
            R.string.catalog_cloud_stage_push_categories_count_short
        )
        CatalogSyncStage.PUSH_PRODUCTS -> counted(
            R.string.catalog_cloud_stage_push_products_short,
            R.string.catalog_cloud_stage_push_products_count_short
        )
        CatalogSyncStage.PULL_CATALOG -> stringResource(R.string.catalog_cloud_stage_pull_catalog_short)
        CatalogSyncStage.SYNC_PRICES -> counted(
            R.string.catalog_cloud_stage_sync_prices_short,
            R.string.catalog_cloud_stage_sync_prices_count_short
        )
        CatalogSyncStage.SYNC_PRICES_PUSH -> counted(
            R.string.catalog_cloud_stage_sync_prices_push_short,
            R.string.catalog_cloud_stage_sync_prices_push_count_short
        )
        CatalogSyncStage.SYNC_PRICES_PULL -> counted(
            R.string.catalog_cloud_stage_sync_prices_pull_short,
            R.string.catalog_cloud_stage_sync_prices_pull_count_short
        )
        CatalogSyncStage.SYNC_EVENTS_DRAIN -> stringResource(R.string.catalog_cloud_stage_sync_events_drain_short)
        CatalogSyncStage.SYNC_HISTORY -> stringResource(R.string.catalog_cloud_stage_sync_history_short)
        CatalogSyncStage.IDLE -> stringResource(R.string.catalog_cloud_state_syncing)
    }
}

@Composable
private fun catalogSyncDetailMessage(state: CatalogSyncProgressState): String? =
    if (state.status == CatalogSyncStatus.RUNNING) {
        stringResource(R.string.cloud_sync_indicator_local_ready)
    } else {
        null
    }

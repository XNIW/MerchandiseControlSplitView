package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Task126ReviewInteractionFixtures
import com.example.merchandisecontrolsplitview.data.Task126ReviewInteractionReducer
import com.example.merchandisecontrolsplitview.data.Task126ReviewInteractionState
import com.example.merchandisecontrolsplitview.data.Task126ReviewSurface
import com.example.merchandisecontrolsplitview.data.Task126UserChoice
import org.json.JSONObject

@Composable
fun Task126ReviewInteractionSmokeScreen(kind: String) {
    val context = LocalContext.current
    val state = remember(kind) {
        if (kind == "account-switch-review-ui") {
            Task126ReviewInteractionFixtures.accountSwitchDirty()
        } else {
            Task126ReviewInteractionFixtures.conflictReviewSameField()
        }
    }
    var outcomeText by remember { mutableStateOf("") }

    LaunchedEffect(kind) {
        writeTask126SmokeEvidence(context, kind, state)
    }

    Task126ReviewInteractionDialog(
        state = state,
        onChoice = { choice ->
            outcomeText = Task126ReviewInteractionReducer.apply(choice, state).observedSyncResult
        },
        outcomeText = outcomeText
    )
}

@Composable
fun Task126ReviewInteractionDialog(
    state: Task126ReviewInteractionState,
    onChoice: (Task126UserChoice) -> Unit,
    outcomeText: String = ""
) {
    if (!state.isDialogVisible) return
    AlertDialog(
        onDismissRequest = { onChoice(Task126UserChoice.PostponeReview) },
        title = {
            Text(
                text = stringResource(task126TitleRes(state)),
                modifier = Modifier.semantics { contentDescription = "task126.review.title" }
            )
        },
        text = {
            Column(modifier = Modifier.semantics { contentDescription = "task126.${state.surface.name}.dialog" }) {
                Text(text = stringResource(task126MessageRes(state)), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "pending=${state.pendingBefore}; conflicts=${state.conflictCountBefore}; merged=${state.mergedCount}",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                state.visibleChoices.forEach { choice ->
                    Button(
                        onClick = { onChoice(choice) },
                        enabled = !state.isApplying,
                        colors = if (choice.isDestructive) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "task126.review.choice.${choice.name}" }
                    ) {
                        Text(stringResource(task126ChoiceRes(choice)))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (outcomeText.isNotBlank()) {
                    Text(
                        text = outcomeText,
                        modifier = Modifier.semantics { contentDescription = "task126.review.outcome" }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
        modifier = Modifier.padding(8.dp)
    )
}

private fun writeTask126SmokeEvidence(
    context: Context,
    kind: String,
    state: Task126ReviewInteractionState
) {
    val firstChoice = state.visibleChoices.firstOrNull() ?: Task126UserChoice.PostponeReview
    val outcome = Task126ReviewInteractionReducer.apply(firstChoice, state)
    val payload = JSONObject()
        .put("kind", kind)
        .put("surface", state.surface.name)
        .put("dialogVisible", state.isDialogVisible)
        .put("buttons", state.visibleChoices.joinToString(",") { it.name })
        .put("timeToReviewShownMs", outcome.timeToReviewShownMs)
        .put("timeToApplyChoiceMs", outcome.timeToApplyChoiceMs)
        .put("timeToFinalStateMs", outcome.timeToFinalStateMs)
        .put("pendingBefore", outcome.pendingBefore)
        .put("pendingAfter", outcome.pendingAfter)
        .put("conflictCountBefore", outcome.conflictCountBefore)
        .put("conflictCountAfter", outcome.conflictCountAfter)
        .put("mergedCount", outcome.mergedCount)
        .put("reviewRemainingCount", outcome.reviewRemainingCount)
        .put("status", outcome.status)
    context.filesDir.resolve("task126-ui-smoke-$kind.json").writeText(payload.toString(2))
}

private fun task126TitleRes(state: Task126ReviewInteractionState): Int =
    when {
        state.id == "case3-account-clean-remote-populated" -> R.string.task126_account_clean_populated_title
        state.id == "case3-account-dirty-switch" -> R.string.task126_account_dirty_title
        state.id == "case4-delete-vs-edit" -> R.string.task126_conflict_delete_edit_title
        state.id == "case4-productprice-stale" -> R.string.task126_conflict_price_stale_title
        state.surface == Task126ReviewSurface.ConflictReview && state.conflictCountBefore == 0 -> R.string.task126_conflict_auto_merge_title
        else -> R.string.task126_conflict_same_field_title
    }

private fun task126MessageRes(state: Task126ReviewInteractionState): Int =
    when {
        state.id == "case3-account-clean-remote-populated" -> R.string.task126_account_clean_populated_message
        state.id == "case3-account-dirty-switch" -> R.string.task126_account_dirty_message
        state.id == "case4-delete-vs-edit" -> R.string.task126_conflict_delete_edit_message
        state.id == "case4-productprice-stale" -> R.string.task126_conflict_price_stale_message
        state.surface == Task126ReviewSurface.ConflictReview && state.conflictCountBefore == 0 -> R.string.task126_conflict_auto_merge_message
        else -> R.string.task126_conflict_same_field_message
    }

private fun task126ChoiceRes(choice: Task126UserChoice): Int =
    when (choice) {
        Task126UserChoice.Cancel -> R.string.task126_review_cancel
        Task126UserChoice.KeepCurrentAccount -> R.string.task126_account_keep_current
        Task126UserChoice.ExportBackup -> R.string.task126_account_export_backup
        Task126UserChoice.DiscardPendingAndSwitch -> R.string.task126_account_discard_and_switch
        Task126UserChoice.SwitchAccount -> R.string.task126_account_switch
        Task126UserChoice.UseLocal -> R.string.task126_conflict_use_local
        Task126UserChoice.UseRemote -> R.string.task126_conflict_use_remote
        Task126UserChoice.EditManually -> R.string.task126_conflict_edit_manually
        Task126UserChoice.ApplyToSimilar -> R.string.task126_conflict_apply_to_similar
        Task126UserChoice.PostponeReview -> R.string.task126_conflict_postpone
    }

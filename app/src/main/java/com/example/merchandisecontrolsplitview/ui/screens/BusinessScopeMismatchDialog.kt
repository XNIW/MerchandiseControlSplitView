package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import java.security.MessageDigest
import java.util.UUID

internal const val BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_PREF =
    "business_scope_mismatch_auto_shown_identities_v1"
internal const val BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_MAX = 64
internal const val BUSINESS_SCOPE_MISMATCH_AUTO_SHOW_DISABLED_MARKER =
    "mismatch_auto_show_disabled_after_bounded_history"

internal data class BusinessScopeMismatchDialogEligibility(
    val identity: String?,
    val canReplace: Boolean
)

/**
 * Deriva soltanto lo stato presentazionale della dialog. Il gate autorevole e
 * la transazione restano nell'Application/repository TASK-126 esistenti.
 */
internal fun businessScopeMismatchDialogEligibility(
    state: Task126BusinessDataScopeState,
    verifiedActiveScope: Task126OwnerStoreScope?
): BusinessScopeMismatchDialogEligibility {
    val isMismatch =
        state.status == Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH ||
            state.status == Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH
    val boundScope = state.boundScope
    if (
        !isMismatch ||
        boundScope == null ||
        verifiedActiveScope == null ||
        !verifiedActiveScope.isVerifiedRemoteShopScope()
    ) {
        return BusinessScopeMismatchDialogEligibility(identity = null, canReplace = false)
    }

    val identityMaterial = buildString {
        append("task139-mismatch-dialog-v1|")
        append(state.status.name)
        appendScope(boundScope)
        appendScope(verifiedActiveScope)
    }
    val identity = MessageDigest.getInstance("SHA-256")
        .digest(identityMaterial.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return BusinessScopeMismatchDialogEligibility(
        identity = identity,
        canReplace = state.localSnapshot != null
    )
}

private fun Task126OwnerStoreScope.isVerifiedRemoteShopScope(): Boolean {
    val shopId = storeId.removePrefix("shop:").lowercase()
    return ownerHash.matches(Regex("[0-9a-f]{64}")) &&
        storeId.startsWith("shop:") &&
        VERIFIED_SHOP_UUID_PATTERN.matches(shopId) &&
        runCatching { UUID.fromString(shopId).toString() }.getOrNull() == shopId
}

private val VERIFIED_SHOP_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
        "[0-9a-f]{4}-[0-9a-f]{12}$"
)

internal fun shouldAutoShowBusinessScopeMismatchDialog(
    identity: String?,
    autoShownIdentities: Set<String>
): Boolean = identity != null &&
    BUSINESS_SCOPE_MISMATCH_AUTO_SHOW_DISABLED_MARKER !in autoShownIdentities &&
    identity !in autoShownIdentities

internal fun businessScopeMismatchAutoShownIdentitiesAfterPresentation(
    current: Set<String>,
    identity: String?
): Set<String> {
    if (identity == null) return current
    if (
        identity in current ||
        BUSINESS_SCOPE_MISMATCH_AUTO_SHOW_DISABLED_MARKER in current
    ) return current
    if (current.size < BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_MAX) {
        return current + identity
    }
    // Una cronologia infinita violerebbe il budget locale; eliminare un hash
    // consentirebbe pero' alla stessa identita' di riapparire automaticamente.
    // Al raggiungimento del cap disabilitiamo quindi soltanto l'auto-show futuro:
    // la CTA manuale resta sempre disponibile e nessuna identita' puo' fare loop.
    val retained = current.sorted()
        .takeLast(BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_MAX - 1)
    return (retained + BUSINESS_SCOPE_MISMATCH_AUTO_SHOW_DISABLED_MARKER).toSet()
}

private fun StringBuilder.appendScope(scope: Task126OwnerStoreScope) {
    append('|')
    append(scope.ownerHash)
    append('|')
    append(scope.storeId)
    append('|')
    append(scope.localStoreId.orEmpty())
    append('|')
    append(scope.syncProtocolVersion)
    append('|')
    append(scope.schemaVersion)
    append('|')
    append(scope.storeEpoch)
}

@Composable
internal fun BusinessScopeMismatchChoiceDialog(
    canReplace: Boolean,
    onKeepLocal: () -> Unit,
    onReplaceWithCloud: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepLocal,
        title = { Text(stringResource(R.string.business_scope_mismatch_choice_title)) },
        text = { Text(stringResource(R.string.business_scope_mismatch_choice_message)) },
        confirmButton = {
            TextButton(
                onClick = onReplaceWithCloud,
                enabled = canReplace,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.business_scope_replace_with_cloud))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepLocal) {
                Text(stringResource(R.string.business_scope_mismatch_keep_local))
            }
        }
    )
}

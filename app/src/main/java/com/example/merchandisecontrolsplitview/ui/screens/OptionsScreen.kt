package com.example.merchandisecontrolsplitview.ui.screens

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncBadgeUiState
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncUiState
import com.example.merchandisecontrolsplitview.viewmodel.LocalDatabaseStatusUiState
import com.example.merchandisecontrolsplitview.util.setLocale

@Composable
fun OptionsScreen(
    contentPadding: PaddingValues = PaddingValues(),
    authState: AuthState = AuthState.SignedOut,
    authEnabled: Boolean = false,
    onSignIn: (Context) -> Unit = {},
    onSignOut: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onDiscardUnboundLocalData: () -> Unit = {},
    onReplaceMismatchedLocalData: () -> Unit = {},
    businessScopeMismatchIdentity: String? = null,
    canReplaceMismatchedLocalData: Boolean = false,
    catalogSyncUi: CatalogSyncUiState? = null,
    localDatabaseStatusUi: LocalDatabaseStatusUiState? = null
) {
    val spacing = MaterialTheme.appSpacing
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val savedTheme = prefs.getString("theme", "light") ?: "light"
    var themePref by remember { mutableStateOf(savedTheme) }
    val themeOptions = listOf(
        "auto" to stringResource(R.string.theme_auto),
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)
    )

    val savedLang = prefs.getString("lang", "en") ?: "en"
    var langPref by remember { mutableStateOf(savedLang) }
    val languages = listOf(
        "zh" to stringResource(id = R.string.language_endonym_zh),
        "it" to stringResource(id = R.string.language_endonym_it),
        "es" to stringResource(id = R.string.language_endonym_es),
        "en" to stringResource(id = R.string.language_endonym_en)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xl, vertical = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl)
    ) {
        Text(
            text = stringResource(id = R.string.options),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        OptionsGroup(
            title = stringResource(R.string.select_theme),
            subtitle = themeOptions.first { it.first == themePref }.second,
            icon = Icons.Default.Palette
        ) {
            themeOptions.forEachIndexed { index, (value, label) ->
                SelectableOptionRow(
                    label = label,
                    selected = value == themePref,
                    onClick = {
                        if (themePref != value) {
                            themePref = value
                            prefs.edit { putString("theme", value) }
                            when (value) {
                                "auto" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                            }
                            (context as? Activity)?.recreate()
                        }
                    }
                )
                if (index != themeOptions.lastIndex) {
                    HorizontalDivider()
                }
            }
        }

        OptionsGroup(
            title = stringResource(id = R.string.select_language),
            subtitle = languages.first { it.first == langPref }.second,
            icon = Icons.Default.Language
        ) {
            languages.forEachIndexed { index, (langCode, langName) ->
                SelectableOptionRow(
                    label = langName,
                    selected = langCode == langPref,
                    onClick = {
                        if (langPref != langCode) {
                            langPref = langCode
                            prefs.edit {
                                putString("lang", langCode)
                            }
                            setLocale(context, langCode)
                            (context as? Activity)?.recreate()
                        }
                    }
                )
                if (index != languages.lastIndex) {
                    HorizontalDivider()
                }
            }
        }

        if (authEnabled) {
            AccountCloudSyncSection(
                authState = authState,
                catalogSyncUi = catalogSyncUi,
                onSignIn = { onSignIn(context) },
                onSignOut = onSignOut,
                onDismissError = onDismissError
            )
        }

        localDatabaseStatusUi?.let { status ->
            LocalDatabaseStatusSection(
                state = status,
                onDiscardUnboundLocalData = onDiscardUnboundLocalData,
                onReplaceMismatchedLocalData = onReplaceMismatchedLocalData,
                mismatchIdentity = businessScopeMismatchIdentity,
                canReplaceMismatchedLocalData = canReplaceMismatchedLocalData
            )
        }
    }
}

@Composable
internal fun LocalDatabaseStatusSection(
    state: LocalDatabaseStatusUiState,
    onDiscardUnboundLocalData: () -> Unit,
    onReplaceMismatchedLocalData: () -> Unit,
    mismatchIdentity: String?,
    canReplaceMismatchedLocalData: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
    var showUnboundReviewDetails by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var showMismatchChoiceDialog by remember { mutableStateOf(false) }
    val isBindingMismatch =
        state.businessDataScopeStatus == Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH ||
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH

    fun markMismatchIdentityAsShown() {
        val current = prefs.getStringSet(
            BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_PREF,
            emptySet()
        ).orEmpty().toSet()
        val updated = businessScopeMismatchAutoShownIdentitiesAfterPresentation(
            current = current,
            identity = mismatchIdentity
        )
        if (updated != current) {
            prefs.edit(commit = true) {
                putStringSet(BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_PREF, updated)
            }
        }
    }

    LaunchedEffect(
        isBindingMismatch,
        mismatchIdentity,
        canReplaceMismatchedLocalData
    ) {
        if (!isBindingMismatch) {
            showMismatchChoiceDialog = false
            return@LaunchedEffect
        }
        val autoShownIdentities = prefs.getStringSet(
            BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_PREF,
            emptySet()
        ).orEmpty()
        val shouldAutoShow = canReplaceMismatchedLocalData &&
            shouldAutoShowBusinessScopeMismatchDialog(
                identity = mismatchIdentity,
                autoShownIdentities = autoShownIdentities
            )
        if (shouldAutoShow) {
            markMismatchIdentityAsShown()
            showMismatchChoiceDialog = true
        }
    }
    OptionsGroup(
        title = stringResource(R.string.local_database_status_title),
        subtitle = when {
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.CHECKING ->
                stringResource(R.string.business_scope_checking)
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND ->
                stringResource(R.string.business_scope_unbound_review_short)
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH ->
                stringResource(R.string.business_scope_account_mismatch_short)
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH ->
                stringResource(R.string.business_scope_shop_mismatch_short)
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH ->
                stringResource(R.string.business_scope_schema_mismatch_short)
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.ERROR_RECOVERABLE ->
                stringResource(R.string.business_scope_recoverable_error)
            state.isLoading -> stringResource(R.string.local_database_status_loading)
            state.needsReconciliation -> stringResource(R.string.local_database_status_reconcile)
            state.isEmpty -> stringResource(R.string.local_database_status_empty)
            else -> stringResource(R.string.local_database_status_ready)
        },
        icon = Icons.Default.Storage
    ) {
        val canShowBusinessDataDetails =
            state.businessDataScopeStatus == Task126BusinessDataScopeStatus.READY ||
                state.businessDataScopeStatus == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED ||
                state.businessDataScopeStatus == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND
        if (canShowBusinessDataDetails) {
            val dash = stringResource(R.string.local_database_status_unknown)
            LocalDatabaseStatusRow(
                label = stringResource(R.string.local_database_status_products),
                value = state.productsCount?.toString() ?: dash
            )
            LocalDatabaseStatusRow(
                label = stringResource(R.string.local_database_status_suppliers),
                value = state.suppliersCount?.toString() ?: dash
            )
            LocalDatabaseStatusRow(
                label = stringResource(R.string.local_database_status_categories),
                value = state.categoriesCount?.toString() ?: dash
            )
            LocalDatabaseStatusRow(
                label = stringResource(R.string.local_database_status_price_history),
                value = state.priceHistoryCount?.toString() ?: dash
            )
            LocalDatabaseStatusRow(
                label = stringResource(R.string.local_database_status_history_sessions),
                value = state.historySessionsCount?.toString() ?: dash
            )
            if (
                state.businessDataScopeStatus == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND ||
                (state.pendingLocalChangesCount ?: 0) > 0
            ) {
                LocalDatabaseStatusRow(
                    label = stringResource(R.string.business_scope_pending_local_changes),
                    value = state.pendingLocalChangesCount?.toString() ?: dash
                )
            }
            if (
                state.businessDataScopeStatus == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND ||
                (state.syncEventOutboxPendingCount ?: 0) > 0
            ) {
                LocalDatabaseStatusRow(
                    label = stringResource(R.string.business_scope_pending_outbox),
                    value = state.syncEventOutboxPendingCount?.toString() ?: dash
                )
            }
            state.lastSyncText?.let { lastSync ->
                LocalDatabaseStatusRow(
                    label = stringResource(R.string.local_database_status_last_sync),
                    value = lastSync
                )
            }
        }
        if (state.businessDataScopeStatus == Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND) {
            Text(
                text = stringResource(R.string.business_scope_unbound_review_message),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { showUnboundReviewDetails = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.business_scope_review_details))
            }
            Button(
                onClick = { showDiscardConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.business_scope_continue_discard))
            }
        }
        if (isBindingMismatch) {
            OutlinedButton(
                onClick = {
                    markMismatchIdentityAsShown()
                    showMismatchChoiceDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.business_scope_mismatch_review))
            }
        }
    }

    if (showUnboundReviewDetails) {
        AlertDialog(
            onDismissRequest = { showUnboundReviewDetails = false },
            title = { Text(stringResource(R.string.business_scope_review_details)) },
            text = { Text(stringResource(R.string.business_scope_unbound_review_message)) },
            confirmButton = {
                TextButton(onClick = { showUnboundReviewDetails = false }) {
                    Text(stringResource(R.string.business_scope_keep_local))
                }
            }
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(stringResource(R.string.business_scope_discard_confirm_title)) },
            text = { Text(stringResource(R.string.business_scope_discard_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDiscardUnboundLocalData()
                    }
                ) {
                    Text(stringResource(R.string.business_scope_discard_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text(stringResource(R.string.business_scope_keep_local))
                }
            }
        )
    }

    if (showMismatchChoiceDialog && isBindingMismatch) {
        BusinessScopeMismatchChoiceDialog(
            canReplace = canReplaceMismatchedLocalData,
            onKeepLocal = { showMismatchChoiceDialog = false },
            onReplaceWithCloud = {
                if (canReplaceMismatchedLocalData) {
                    showMismatchChoiceDialog = false
                    onReplaceMismatchedLocalData()
                }
            }
        )
    }
}

@Composable
private fun LocalDatabaseStatusRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CatalogCloudContent(
    state: CatalogSyncUiState
) {
    val spacing = MaterialTheme.appSpacing
    val sectionDescription = stringResource(
        R.string.catalog_cloud_section_cd,
        state.primaryMessage
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = sectionDescription },
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        if (state.statusBadges.isNotEmpty()) {
            CatalogCloudBadgeRow(badges = state.statusBadges)
        }
        state.catalogDetail?.let { detail ->
            CatalogCloudDetailBlock(
                title = stringResource(R.string.catalog_cloud_detail_catalog_title),
                body = detail
            )
        }
        state.sessionDetail?.let { sessionText ->
            CatalogCloudDetailBlock(
                title = stringResource(R.string.catalog_cloud_detail_sessions_title),
                body = sessionText
            )
        }
        if (state.isSyncing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.sm),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        if (
            state.showAutomaticSyncDetail &&
            (
                state.businessDataScopeStatus == Task126BusinessDataScopeStatus.READY ||
                    state.businessDataScopeStatus == Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED
                )
        ) {
            CatalogCloudDetailBlock(
                title = if (state.fullSyncRecommended) {
                    stringResource(R.string.catalog_cloud_auto_reconcile_title)
                } else {
                    stringResource(R.string.catalog_cloud_auto_status_title)
                },
                body = if (state.fullSyncRecommended) {
                    stringResource(R.string.catalog_cloud_auto_reconcile_body)
                } else {
                    stringResource(R.string.catalog_cloud_auto_status_body)
                }
            )
        }
    }
}

@Composable
private fun CatalogCloudBadgeRow(
    badges: List<CatalogSyncBadgeUiState>
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { badge ->
            CatalogCloudBadge(badge = badge)
        }
    }
}

@Composable
private fun CatalogCloudBadge(
    badge: CatalogSyncBadgeUiState
) {
    val label = stringResource(badge.labelRes)
    val isFullRequired = badge.labelRes == R.string.catalog_cloud_badge_full_required
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isFullRequired) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (isFullRequired) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isFullRequired) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CatalogCloudDetailBlock(
    title: String,
    body: String
) {
    val spacing = MaterialTheme.appSpacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OptionsGroup(
    title: String,
    subtitle: String,
    icon: ImageVector,
    showHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalAlignment = Alignment.Start
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SelectableOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = if (selected) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColor.current
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        RadioButton(
            selected = selected,
            onClick = null
        )
    }
}

/**
 * Sezione account nella schermata Opzioni (task 011).
 *
 * Mostra lo stato auth corrente e le CTA login/logout.
 * Nessuna logica auth nel composable: solo trigger (click) e binding stato.
 */
@Composable
private fun AccountCloudSyncSection(
    authState: AuthState,
    catalogSyncUi: CatalogSyncUiState?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit
) {
    val subtitle = when (authState) {
        is AuthState.Checking -> stringResource(R.string.account_checking)
        is AuthState.SignedOut -> stringResource(R.string.account_not_signed_in)
        is AuthState.SignedIn -> catalogSyncUi?.primaryMessage ?: stringResource(R.string.account_signed_in)
        is AuthState.ErrorRecoverable -> authState.message
    }

    OptionsGroup(
        title = stringResource(R.string.account_cloud_sync_section_title),
        subtitle = subtitle,
        icon = Icons.Default.AccountCircle,
        showHeader = authState !is AuthState.SignedIn
    ) {
        when (authState) {
            is AuthState.Checking -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(24.dp).height(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            is AuthState.SignedOut -> {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.account_sign_in_google))
                }
            }
            is AuthState.SignedIn -> {
                ConnectedAccountRow(
                    email = authState.email,
                    onSignOut = onSignOut
                )
                catalogSyncUi?.let { sync ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    CatalogCloudContent(state = sync)
                }
            }
            is AuthState.ErrorRecoverable -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onDismissError()
                            onSignIn()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.account_sign_in_google))
                    }
                    OutlinedButton(
                        onClick = onDismissError,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedAccountRow(
    email: String?,
    onSignOut: () -> Unit
) {
    val spacing = MaterialTheme.appSpacing
    val maskedEmail = maskEmailForOptions(email)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.padding(8.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.account_cloud_connected_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (maskedEmail != null) {
                    stringResource(R.string.account_signed_in_as, maskedEmail)
                } else {
                    stringResource(R.string.account_signed_in)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        OutlinedButton(
            onClick = onSignOut,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.account_sign_out))
        }
    }
}

internal fun maskEmailForOptions(email: String?): String? {
    val trimmed = email?.trim().orEmpty()
    val atIndex = trimmed.indexOf('@')
    if (trimmed.isEmpty() || atIndex <= 0 || atIndex == trimmed.lastIndex) {
        return null
    }
    return "${trimmed.first()}***${trimmed.substring(atIndex)}"
}

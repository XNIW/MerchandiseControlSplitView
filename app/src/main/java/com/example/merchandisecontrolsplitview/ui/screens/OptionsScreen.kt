package com.example.merchandisecontrolsplitview.ui.screens

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncUiState
import com.example.merchandisecontrolsplitview.util.setLocale

@Composable
fun OptionsScreen(
    contentPadding: PaddingValues = PaddingValues(),
    authState: AuthState = AuthState.SignedOut,
    authEnabled: Boolean = false,
    onSignIn: (Context) -> Unit = {},
    onSignOut: () -> Unit = {},
    onDismissError: () -> Unit = {},
    catalogSyncUi: CatalogSyncUiState? = null,
    onCatalogRefresh: () -> Unit = {}
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
            AccountSection(
                authState = authState,
                onSignIn = { onSignIn(context) },
                onSignOut = onSignOut,
                onDismissError = onDismissError
            )
            catalogSyncUi?.let { sync ->
                CatalogCloudSection(
                    state = sync,
                    onRefresh = onCatalogRefresh
                )
            }
        }
    }
}

@Composable
private fun CatalogCloudSection(
    state: CatalogSyncUiState,
    onRefresh: () -> Unit
) {
    OptionsGroup(
        title = stringResource(R.string.catalog_cloud_section_title),
        subtitle = state.primaryMessage,
        icon = Icons.Default.Sync
    ) {
        state.secondaryMessage?.let { secondary ->
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.isSyncing) {
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
        } else {
            OutlinedButton(
                onClick = onRefresh,
                enabled = state.canRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.catalog_cloud_refresh))
            }
        }
    }
}

@Composable
private fun OptionsGroup(
    title: String,
    subtitle: String,
    icon: ImageVector,
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
private fun AccountSection(
    authState: AuthState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit
) {
    val subtitle = when (authState) {
        is AuthState.Checking -> stringResource(R.string.account_checking)
        is AuthState.SignedOut -> stringResource(R.string.account_not_signed_in)
        is AuthState.SignedIn -> if (authState.email != null) {
            stringResource(R.string.account_signed_in_as, authState.email)
        } else {
            stringResource(R.string.account_signed_in)
        }
        is AuthState.ErrorRecoverable -> authState.message
    }

    OptionsGroup(
        title = stringResource(R.string.account_section_title),
        subtitle = subtitle,
        icon = Icons.Default.AccountCircle
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
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.account_sign_out))
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

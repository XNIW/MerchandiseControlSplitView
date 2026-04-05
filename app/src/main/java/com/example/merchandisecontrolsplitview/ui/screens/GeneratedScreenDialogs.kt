package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.merchandisecontrolsplitview.R
import kotlinx.coroutines.delay

/**
 * Dialoghi estratti da [GeneratedScreen] (TASK-002). Ordine di chiamata nel parent = ordine
 * nella composition (z-order). TASK-014 applica solo polish locale, senza cambiare callback
 * o semantica dei flussi.
 */
@Composable
fun GeneratedScreenDiscardDraftDialog(
    visible: Boolean,
    isSavingOrReverting: Boolean,
    onDismissRequest: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.discard_and_exit_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = stringResource(R.string.discard_and_exit_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmDiscard,
                enabled = !isSavingOrReverting
            ) {
                Text(
                    stringResource(R.string.discard),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isSavingOrReverting
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun GeneratedScreenExitFromHistoryDialog(
    visible: Boolean,
    isSavingOrReverting: Boolean,
    onDismissRequest: () -> Unit,
    onExitWithoutSaving: () -> Unit,
    onSaveAndExit: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.exit_confirmation_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (isSavingOrReverting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.saving_changes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.exit_changes_question),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onExitWithoutSaving,
                    enabled = !isSavingOrReverting
                ) {
                    Text(
                        stringResource(R.string.exit_without_saving),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSaveAndExit,
                    enabled = !isSavingOrReverting
                ) {
                    Text(stringResource(R.string.save_and_exit))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isSavingOrReverting
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun GeneratedScreenExitToHomeDialog(
    visible: Boolean,
    isSavingOrReverting: Boolean,
    onDismissRequest: () -> Unit,
    onSaveAndExitToHome: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.dialog_title_return_home),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_message_save_and_return_home),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onSaveAndExitToHome,
                enabled = !isSavingOrReverting
            ) {
                Text(stringResource(R.string.save_and_exit))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isSavingOrReverting
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratedScreenSearchDialog(
    visible: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPerformSearch: () -> Unit,
    onLaunchScanner: () -> Unit,
) {
    if (!visible) return

    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(visible) {
        if (visible) {
            onSearchTextChange("")
            delay(50)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    AlertDialog(
        onDismissRequest = {
            keyboardController?.hide()
            onDismiss()
        },
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.search_number),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                label = { Text(stringResource(R.string.insert_number)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onLaunchScanner) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = stringResource(R.string.scan_icon_desc)
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    onPerformSearch()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
            )
        },
        confirmButton = {
            Button(onClick = {
                keyboardController?.hide()
                onPerformSearch()
            }) { Text(stringResource(R.string.search_number)) }
        },
        dismissButton = {
            TextButton(onClick = {
                keyboardController?.hide()
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        }
    )
}

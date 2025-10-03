package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.auth.AuthManager
import com.example.merchandisecontrolsplitview.util.isGooglePlayServicesAvailable
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    onFilesPicked: (List<Uri>) -> Unit,
    onViewHistory: () -> Unit,
    onDatabase: () -> Unit,
    onOptions: () -> Unit,
    onManualAdd: () -> Unit,
    recentFiles: List<String> = emptyList() // file recenti da mostrare nella Home
) {
    val context = LocalContext.current
    val hasGms = remember(context) { isGooglePlayServicesAvailable(context) }
    val uid by AuthManager.uid.collectAsState()

    // Foto profilo aggiornata su cambio uid
    val photoUrl = remember(uid) { FirebaseAuth.getInstance().currentUser?.photoUrl?.toString() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onFilesPicked(uris)
    }

    var accountSheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { accountSheetOpen = true }) {
                        if (uid != null && !photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = stringResource(R.string.account),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.AccountCircle,
                                contentDescription = stringResource(R.string.account)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->

        if (accountSheetOpen) {
            AccountBottomSheet(
                sheetState = sheetState,
                hasGms = hasGms,
                uid = uid,
                onSignIn = { doGoogleSignIn(context) },
                onSignOut = { AuthManager.signOut() },
                onOptions = onOptions,
                onDismiss = { accountSheetOpen = false }
            )
        }

        // ------------ NUOVO LAYOUT: griglia azioni + lista Recenti ------------
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                Text(
                    text = stringResource(R.string.quick_actions),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                ActionsGrid(
                    onHistory = onViewHistory,
                    onLoadExcel = {
                        launcher.launch(
                            arrayOf(
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/html",
                                "application/octet-stream"
                            )
                        )
                    },
                    onManualAdd = onManualAdd,
                    onDatabase = onDatabase
                )
            }

            if (recentFiles.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.recent_files),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                items(recentFiles) { name ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                        headlineContent = { Text(name) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(24.dp)) }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.recent_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsGrid(
    onHistory: () -> Unit,
    onLoadExcel: () -> Unit,
    onManualAdd: () -> Unit,
    onDatabase: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(204.dp)                 // <-- IMPORTANTE: altezza vincolata
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        item {
            ActionCard(
                label = stringResource(R.string.file_history),
                icon = Icons.Filled.History,
                onClick = onHistory
            )
        }
        item {
            ActionCard(
                label = stringResource(R.string.load_excel_file),
                icon = Icons.Filled.UploadFile,
                onClick = onLoadExcel
            )
        }
        item {
            ActionCard(
                label = stringResource(R.string.add_products_manually),
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onClick = onManualAdd
            )
        }
        item {
            ActionCard(
                label = stringResource(R.string.database),
                icon = Icons.Filled.Storage,
                onClick = onDatabase
            )
        }
    }
}

@Composable
private fun ActionCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .height(96.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBottomSheet(
    sheetState: SheetState,
    hasGms: Boolean,
    uid: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOptions: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = stringResource(R.string.account), style = MaterialTheme.typography.titleLarge)

            val status = if (uid == null)
                stringResource(R.string.sign_in_to_sync)
            else
                stringResource(R.string.sync_active)

            Text(status, style = MaterialTheme.typography.bodyMedium)

            if (!hasGms) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.google_sign_in_unavailable))
                }
            } else if (uid == null) {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sign_in_with_google))
                }
            } else {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.exit))
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))

            Button(
                onClick = {
                    onDismiss()
                    onOptions()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(stringResource(R.string.options))
            }
        }
    }
}

/** Avvia il flusso di Sign-In Google con Credential Manager. */
private fun doGoogleSignIn(context: Context) {
    if (!isGooglePlayServicesAvailable(context)) return
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val cm = CredentialManager.create(context)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(context.getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = cm.getCredential(context, request)
            val cred = result.credential
            if (cred is CustomCredential &&
                cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(cred.data)
                AuthManager.signInWithGoogle(google.idToken)
            }
        } catch (_: GetCredentialException) {
            // utente ha annullato o nessun account disponibile
        } catch (_: Exception) {
            // mantieni la UI consistente
        }
    }
}

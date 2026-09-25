package com.snapcore.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onSignedOut: () -> Unit, onAccountChanged: (com.snapcore.app.domain.Account) -> Unit) {
    val vm = containerViewModel { ProfileViewModel(it.auth, it.accounts, it.pendingRegistration, it.devices) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
    LaunchedEffect(state.account) { state.account?.let(onAccountChanged) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mon compte") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val account = state.account
        if (account == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator() else TextButton(onClick = vm::refresh) { Text("Réessayer") }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(account.username, account.profilePhotoUrl, size = 112.dp)
            Text(account.username, style = MaterialTheme.typography.headlineMedium)
            if (account.isPremium) Text("SnapCore+", color = SnapColors.SnapPurple)
            Spacer(Modifier.height(16.dp))

            ProfileRow("Pseudo", account.username, onEdit = { vm.startEditing(EditableField.USERNAME) })
            HorizontalDivider()
            ProfileRow("Email", account.email ?: "—") {
                // Vérification informative, jamais bloquante (ADR-010, ADR-015).
                when (state.emailVerified) {
                    true -> Icon(Icons.Filled.Verified, "Email vérifié", tint = SnapColors.ChatBlue)
                    false -> TextButton(onClick = vm::resendVerification) {
                        Icon(Icons.Filled.WarningAmber, null, tint = SnapColors.SnapRed)
                        Text(" Non vérifié · renvoyer")
                    }
                    null -> Unit
                }
            }
            HorizontalDivider()
            ProfileRow("Téléphone", account.phoneNumber ?: "—", onEdit = { vm.startEditing(EditableField.PHONE) })
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Se déconnecter", vm::signOut, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = vm::askDeletion, enabled = !state.saving) {
                Text("Supprimer mon compte", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    state.editing?.let { field ->
        AlertDialog(
            onDismissRequest = vm::cancelEditing,
            title = { Text(if (field == EditableField.USERNAME) "Modifier le pseudo" else "Modifier le téléphone") },
            text = {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::onDraft,
                    singleLine = true,
                    isError = state.draftError != null,
                    supportingText = state.draftError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = if (field == EditableField.PHONE) KeyboardType.Phone else KeyboardType.Text),
                )
            },
            confirmButton = { TextButton(onClick = vm::saveEditing, enabled = !state.saving) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::cancelEditing) { Text("Annuler") } },
        )
    }

    if (state.confirmDeletion) {
        AlertDialog(
            onDismissRequest = vm::cancelDeletion,
            title = { Text("Supprimer ton compte ?") },
            text = { Text("Ton compte, tes messages, tes snaps et tes stories seront définitivement supprimés.") },
            confirmButton = { TextButton(onClick = vm::deleteAccount) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancelDeletion) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ProfileRow(label: String, value: String, onEdit: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        trailing()
        if (onEdit != null) Icon(Icons.Filled.Edit, contentDescription = "Modifier $label", tint = SnapColors.Grey)
    }
}

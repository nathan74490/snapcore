package com.snapcore.app.ui.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

// Sélection multiple des destinataires (référence : amis-nouvelle-conversation-multi.png) :
// "Ma story" en tête, puis les amis ACCEPTED.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToScreen(media: CapturedMedia, onBack: () -> Unit, onSent: () -> Unit, onAddFriends: () -> Unit) {
    val vm = containerViewModel { SendToViewModel(media, it.friends, it.mediaPublisher) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onSent() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Envoyer à…") },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.sending) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (state.failures.isNotEmpty()) {
                    Text(
                        "Échec pour ${state.failures.size} destinataire(s) : ${state.failures.values.first()}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PrimaryButton(
                    text = if (state.failures.isNotEmpty()) "Réessayer" else "Envoyer (${state.selected.size})",
                    onClick = vm::send,
                    enabled = state.selected.isNotEmpty(),
                    loading = state.sending,
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                TargetRow(
                    label = "Ma story",
                    subtitle = "Visible par tes amis pendant 24 h",
                    icon = Icons.Filled.AutoStories,
                    checked = SendTarget.ToStory in state.selected,
                    failed = SendTarget.ToStory in state.failures,
                    onToggle = { vm.toggle(SendTarget.ToStory) },
                )
                HorizontalDivider()
                Text("Amis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            when {
                state.loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                state.loadError != null -> item { TextButton(onClick = vm::loadFriends, modifier = Modifier.padding(16.dp)) { Text("${state.loadError} · Réessayer") } }
                state.friends.isEmpty() -> item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Tu n'as pas encore d'amis.", color = SnapColors.Grey)
                        TextButton(onClick = onAddFriends) { Text("Ajouter des amis") }
                    }
                }
                else -> items(state.friends, key = { it.id }) { friend ->
                    val target = SendTarget.ToFriend(friend)
                    TargetRow(
                        label = friend.username,
                        avatar = { Avatar(friend.username, friend.photoUrl, size = 44.dp) },
                        checked = target in state.selected,
                        failed = target in state.failures,
                        onToggle = { vm.toggle(target) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetRow(
    label: String,
    checked: Boolean,
    failed: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    avatar: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar?.invoke() ?: icon?.let { Icon(it, null, tint = SnapColors.SnapPurple, modifier = Modifier.size(44.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            (if (failed) "Échec de l'envoi" else subtitle)?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error else SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

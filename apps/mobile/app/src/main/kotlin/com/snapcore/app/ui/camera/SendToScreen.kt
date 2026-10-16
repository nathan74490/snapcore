package com.snapcore.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.StatusBarIcons
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
import com.snapcore.app.ui.components.SectionTitle
import com.snapcore.app.ui.components.SnapCard
import com.snapcore.app.ui.components.SnapCheckbox
import com.snapcore.app.ui.components.SnapSearchBar
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

    var query by remember { mutableStateOf("") }
    StatusBarIcons(dark = true)
    Scaffold(
        topBar = {

            Row(Modifier.fillMaxWidth().background(SnapColors.Background).statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !state.sending) { FigmaAsset("15-752_imgIconChevronDown", "Retour") }
                SnapSearchBar(query, { query = it }, "Rechercher", Modifier.weight(1f))
                IconButton(onClick = onAddFriends) { FigmaAsset("15-752_imgIconAddFriend", "Ajouter des amis") }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
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
        containerColor = SnapColors.Background,
    ) { padding ->
        // Maquette 15:752 : sections en titres gras, lignes groupées dans des cartes blanches arrondies.
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("Stories", Modifier.padding(top = 8.dp, bottom = 6.dp))
                SnapCard { TargetRow(
                    label = "Ma story · Mes amis",
                    subtitle = "Visible par tes amis pendant 24 h",
                    icon = Icons.Filled.AutoStories,
                    checked = SendTarget.ToStory in state.selected,
                    failed = SendTarget.ToStory in state.failures,
                    onToggle = { if(!state.sending) vm.toggle(SendTarget.ToStory) },
                ) }
                SectionTitle("Amis", Modifier.padding(top = 14.dp, bottom = 6.dp))
            }
            when {
                state.loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                state.loadError != null -> item { TextButton(onClick = vm::loadFriends, modifier = Modifier.padding(16.dp)) { Text("${state.loadError} · Réessayer") } }
                state.friends.isEmpty() -> item {
                    Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                        Text("Tu n'as pas encore d'amis.", color = SnapColors.Grey)
                        TextButton(onClick = onAddFriends) { Text("Ajouter des amis") }
                    }
                }
                else -> item {
                    SnapCard {
                        state.friends.filter { it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true) }.forEach { friend ->
                            val target = SendTarget.ToFriend(friend)
                            TargetRow(
                                label = friend.displayName,
                                avatar = { Avatar(friend.displayName, friend.photoUrl, size = 40.dp) },
                                checked = target in state.selected,
                                failed = target in state.failures,
                                onToggle = { if(!state.sending) vm.toggle(target) },
                            )
                        }
                    }
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar?.invoke() ?: icon?.let { Box(Modifier.size(40.dp).background(SnapColors.Selected, CircleShape), Alignment.Center) { FigmaAsset("17-1301_imgIconStories", tint = SnapColors.ChatBlue) } }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            (if (failed) "Échec de l'envoi" else subtitle)?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error else SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
            }
        }
        SnapCheckbox(checked)
    }
}

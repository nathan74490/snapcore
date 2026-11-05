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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.snapcore.app.ui.components.rememberScreenReaderOn
import com.snapcore.app.ui.components.screenReaderFocusOnAppear
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
import com.snapcore.app.ui.groups.GroupAvatar
import com.snapcore.app.ui.theme.SnapColors

// Sélection multiple des destinataires (référence : amis-nouvelle-conversation-multi.png) :
// "Ma story" en tête, puis les amis ACCEPTED.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToScreen(media: CapturedMedia, onBack: () -> Unit, onSent: (Set<SendTarget>) -> Unit, onAddFriends: () -> Unit, recipientId: String? = null,
    myId: String = "") {
    val vm = containerViewModel { SendToViewModel(media, it.friends, it.outbox, recipientId, groupService = it.groups) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onSent(state.selected) }

    var query by remember { mutableStateOf("") }
    StatusBarIcons(dark = true)
    // TalkBack (ADR-033) : une longue liste se résume en un bouton qui ouvre une fenêtre plein écran,
    // pour atteindre « Envoyer » en quelques glissements.
    val screenReader = rememberScreenReaderOn()
    var openList by remember { mutableStateOf<String?>(null) }
    val shownGroups = state.groups.filter { it.title(myId).contains(query, ignoreCase = true) }
    val shownFriends = state.friends.filter { it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true) }
    val selectedCount = state.selected.size
    val groupRows: @Composable () -> Unit = {
        shownGroups.forEach { group ->
            val target = SendTarget.ToGroup(group)
            TargetRow(
                label = group.title(myId),
                subtitle = "${group.members.size} membres",
                avatar = { GroupAvatar(group, myId, 40.dp) },
                checked = target in state.selected,
                onToggle = { vm.toggle(target) },
            )
        }
    }
    val friendRows: @Composable () -> Unit = {
        shownFriends.forEach { friend ->
            val target = SendTarget.ToFriend(friend)
            TargetRow(
                label = friend.displayName,
                avatar = { Avatar(friend.displayName, friend.pictureUrl, size = 40.dp) },
                checked = target in state.selected,
                onToggle = { vm.toggle(target) },
            )
        }
    }
    when (openList) {
        "groups" -> RecipientListDialog("Groupes", selectedCount, onDone = { openList = null }) { item { SnapCard { groupRows() } } }
        "friends" -> RecipientListDialog("Amis", selectedCount, onDone = { openList = null }) { item { SnapCard { friendRows() } } }
    }
    Scaffold(
        topBar = {

            Row(Modifier.fillMaxWidth().background(SnapColors.Background).statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { FigmaAsset("15-752_imgIconChevronDown", "Retour") }
                SnapSearchBar(query, { query = it }, "Rechercher", Modifier.weight(1f))
                IconButton(onClick = onAddFriends) { FigmaAsset("15-752_imgIconAddFriend", "Ajouter des amis") }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                PrimaryButton(
                    // Avec TalkBack, le bouton dit à combien de destinataires le snap partira.
                    text = when {
                        !screenReader -> "Envoyer (${state.selected.size})"
                        selectedCount == 0 -> "Envoyer, aucun destinataire coché"
                        else -> "Envoyer à $selectedCount ${if (selectedCount > 1) "destinataires" else "destinataire"}"
                    },
                    onClick = vm::send,
                    enabled = state.selected.isNotEmpty(),
                )
            }
        },
        containerColor = SnapColors.Background,
    ) { padding ->
        // Maquette 15:752 : sections en titres gras, lignes groupées dans des cartes blanches arrondies.
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("Stories", Modifier.padding(top = 8.dp, bottom = 6.dp).semantics { heading() })
                SnapCard { TargetRow(
                    label = "Ma story · Mes amis",
                    subtitle = "Visible par tes amis pendant 24 h",
                    icon = Icons.Filled.AutoStories,
                    checked = SendTarget.ToStory in state.selected,
                    onToggle = { vm.toggle(SendTarget.ToStory) },
                ) }
                if (shownGroups.isNotEmpty()) {
                    SectionTitle("Groupes", Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() })
                    if (screenReader && shownGroups.size > LONG_LIST) {
                        val checked = shownGroups.count { SendTarget.ToGroup(it) in state.selected }
                        RecipientListButton(listSummary(shownGroups.size, "Groupes", checked)) { openList = "groups" }
                    } else SnapCard { groupRows() }
                }
                SectionTitle("Amis", Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() })
            }
            when {
                state.loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Chargement des amis" })
                } }
                state.loadError != null -> item { TextButton(onClick = vm::loadFriends, modifier = Modifier.padding(16.dp)) { Text("${state.loadError} · Réessayer") } }
                state.friends.isEmpty() -> item {
                    Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                        Text("Tu n'as pas encore d'amis.", color = SnapColors.Grey)
                        TextButton(onClick = onAddFriends) { Text("Ajouter des amis") }
                    }
                }
                screenReader && shownFriends.size > LONG_LIST -> item {
                    val checked = shownFriends.count { SendTarget.ToFriend(it) in state.selected }
                    RecipientListButton(listSummary(shownFriends.size, "Amis", checked)) { openList = "friends" }
                }
                else -> item {
                    SnapCard { friendRows() }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    avatar: (@Composable () -> Unit)? = null,
) {
    Row(
        // Une ligne = une case à cocher pour TalkBack : « Léa, coché, case à cocher ».
        modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { onToggle() }.heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar?.invoke() ?: icon?.let { Box(Modifier.size(40.dp).background(SnapColors.Selected, CircleShape), Alignment.Center) { FigmaAsset("17-1301_imgIconStories", tint = SnapColors.ChatBlue) } }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall) }
        }
        SnapCheckbox(checked)
    }
}

// Au-delà, avec TalkBack, la liste se résume en un bouton (ADR-033).
private const val LONG_LIST = 6

/** « Amis : 24, dont 2 cochés. » */
private fun listSummary(total: Int, title: String, checked: Int): String =
    "$title : $total, dont $checked ${if (checked > 1) "cochés" else "coché"}."

/** Résumé d'une longue liste de destinataires pour TalkBack ; ouvre [RecipientListDialog]. */
@Composable
private fun RecipientListButton(summary: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
        .clickable(role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics {
            contentDescription = summary
            role = Role.Button
            onClick("parcourir et choisir les destinataires") { onOpen(); true }
        }
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, fontSize = 16.sp, color = SnapColors.Ink, modifier = Modifier.weight(1f))
        Text("Choisir", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Liste de destinataires plein écran, ouverte depuis [RecipientListButton] : « Terminé » en haut et en bas. */
@Composable
private fun RecipientListDialog(title: String, checkedCount: Int, onDone: () -> Unit, content: LazyListScope.() -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SnapColors.Background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    modifier = Modifier.weight(1f).semantics { heading() }.screenReaderFocusOnAppear())
                Text("Terminé", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDone).padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) { content() }
            PrimaryButton(
                if (checkedCount == 0) "Terminé, aucun destinataire coché"
                else "Terminé, $checkedCount ${if (checkedCount > 1) "destinataires cochés" else "destinataire coché"}",
                onDone, height = 50.dp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            )
        }
    }
}

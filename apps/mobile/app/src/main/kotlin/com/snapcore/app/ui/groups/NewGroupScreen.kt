package com.snapcore.app.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.snapcore.app.ui.components.rememberScreenReaderOn
import com.snapcore.app.ui.components.screenReaderFocusOnAppear
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.Group
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.SnapHeader
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

/**
 * « Nouveau chat », comme Snapchat (captures 367 à 370) : nom du groupe facultatif, champ « À : » avec
 * les amis choisis en puces, liste à cases rondes. Un ami : conversation à deux ; deux ou plus : groupe.
 */
@Composable
fun NewGroupScreen(onBack: () -> Unit, onOpenFriend: (Friend) -> Unit, onOpenGroup: (Group) -> Unit) {
    val vm = containerViewModel { NewGroupViewModel(it.friends, it.groups) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.openFriend) { state.openFriend?.let(onOpenFriend) }
    LaunchedEffect(state.created) { state.created?.let(onOpenGroup) }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.consumeError() } }
    StatusBarIcons(dark = true)

    Box(Modifier.fillMaxSize().background(SnapColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            SnapHeader("Nouveau chat", onBack)
            GroupNameField(state.name, vm::onName, Modifier.padding(top = 12.dp))
            ToField(state.selected, state.query, vm::onQuery, onRemove = vm::toggle)
            AccessibleFriendChecklist(
                friends = state.visible,
                selected = state.selected,
                loading = state.loading,
                onToggle = vm::toggle,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = if (state.isGroup) "Discuter avec le groupe" else "Discuter",
                onClick = vm::confirm,
                enabled = state.selected.isNotEmpty(),
                loading = state.creating,
                modifier = Modifier.navigationBarsPadding().imePadding().padding(16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
}

/** « Nom du groupe ✎ », centré et facultatif (capture 368). */
@Composable
internal fun GroupNameField(name: String, onName: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = SnapColors.Ink, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            cursorBrush = SolidColor(SnapColors.ChatBlue),
            modifier = Modifier.weight(1f, fill = false),
            // Libellé lu par TalkBack même une fois le champ rempli ; le texte indicatif est masqué (ADR-033).
            decorationBox = { field ->
                Box(Modifier.semantics { contentDescription = "Nom du groupe, facultatif" }, contentAlignment = Alignment.Center) {
                    if (name.isEmpty()) Text("Nom du groupe", style = MaterialTheme.typography.titleLarge, color = SnapColors.Muted,
                        modifier = Modifier.clearAndSetSemantics {})
                    field()
                }
            },
        )
        Icon(Icons.Filled.Edit, contentDescription = null, tint = SnapColors.Muted, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

/** Champ « À : » : amis choisis en puces (un appui les retire), puis la recherche (capture 370). */
@Composable
private fun ToField(selected: List<Friend>, query: String, onQuery: (String) -> Unit, onRemove: (Friend) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(SnapColors.Control)
            .heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("À :", fontWeight = FontWeight.Bold, color = SnapColors.Ink, modifier = Modifier.clearAndSetSemantics {})
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            selected.forEach { friend ->
                Text(friend.displayName, fontSize = 14.sp, color = SnapColors.Ink, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { onRemove(friend) }
                        // TalkBack (ADR-033) : « Retirer Léa, bouton » plutôt qu'un simple nom.
                        .clearAndSetSemantics {
                            contentDescription = "Retirer ${friend.displayName}"
                            role = Role.Button
                            onClick { onRemove(friend); true }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
                cursorBrush = SolidColor(SnapColors.ChatBlue),
                modifier = Modifier.heightIn(min = 24.dp).padding(vertical = 10.dp),
                decorationBox = { field ->
                    Box(Modifier.semantics { contentDescription = "Rechercher un ami" }) {
                        if (query.isEmpty() && selected.isEmpty()) Text("Rechercher", color = SnapColors.Muted, modifier = Modifier.clearAndSetSemantics {})
                        field()
                    }
                },
            )
        }
    }
}

/** Liste des amis à cases rondes : le nom d'un ami choisi passe en bleu, avec une coche (capture 370). */
@Composable
internal fun FriendChecklist(friends: List<Friend>, selected: List<Friend>, loading: Boolean, onToggle: (Friend) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (loading) item { Box(Modifier.fillMaxWidth().padding(32.dp).semantics { contentDescription = "Chargement de tes amis" }, Alignment.Center) { CircularProgressIndicator() } }
        else if (friends.isEmpty()) item {
            Text("Aucun ami à ajouter.", color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        items(friends, key = { it.id }) { friend ->
            val checked = friend in selected
            // Une vraie case à cocher pour TalkBack : « Léa, @lea, case cochée » (ADR-033).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                    .toggleable(value = checked, role = Role.Checkbox) { onToggle(friend) }
                    .heightIn(min = 60.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(friend.displayName, friend.pictureUrl, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(friend.displayName, color = if (checked) SnapColors.ChatBlue else SnapColors.Ink, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (friend.firstName != null) Text("@${friend.username}", color = SnapColors.Grey, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .then(if (checked) Modifier.background(SnapColors.ChatBlue) else Modifier.border(2.dp, SnapColors.Muted, CircleShape)),
                    Alignment.Center,
                ) { if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Au-delà, avec TalkBack, la liste d'amis se résume en un bouton qui l'ouvre dans sa propre fenêtre (ADR-033). */
private const val LONG_FRIEND_LIST = 6

/**
 * [FriendChecklist], sauf avec TalkBack quand la liste est longue : il fallait alors passer chaque ami pour
 * atteindre le bouton « Discuter ». La liste devient un bouton qui la résume (« 24 amis, dont 2 cochés ») et
 * l'ouvre en plein écran, comme les contacts de l'onboarding (ADR-033). Sans TalkBack, rien ne change.
 */
@Composable
internal fun AccessibleFriendChecklist(friends: List<Friend>, selected: List<Friend>, loading: Boolean, onToggle: (Friend) -> Unit,
    modifier: Modifier = Modifier, title: String = "Choisir des amis") {
    val screenReader = rememberScreenReaderOn()
    if (!screenReader || loading || friends.size <= LONG_FRIEND_LIST) {
        FriendChecklist(friends, selected, loading, onToggle, modifier)
        return
    }
    var open by remember { mutableStateOf(false) }
    val checked = selected.size
    val summary = "${friends.size} amis, dont $checked ${if (checked > 1) "cochés" else "coché"}" +
        if (checked > 0) " : ${selected.joinToString(", ") { it.displayName }}." else "."
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
            .clickable(role = Role.Button) { open = true }
            .clearAndSetSemantics {
                contentDescription = summary
                role = Role.Button
                onClick("parcourir et cocher tes amis") { open = true; true }
            }
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(summary, fontSize = 16.sp, color = SnapColors.Ink, modifier = Modifier.weight(1f))
            Text("Choisir", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SnapColors.Background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    modifier = Modifier.weight(1f).semantics { heading() }.screenReaderFocusOnAppear())
                TextButton(onClick = { open = false }) { Text("Terminé", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            FriendChecklist(friends, selected, loading = false, onToggle = onToggle, modifier = Modifier.weight(1f))
            PrimaryButton(if (checked == 0) "Terminé" else "Terminé, $checked ${if (checked > 1) "cochés" else "coché"}",
                { open = false }, modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp))
        }
    }
}

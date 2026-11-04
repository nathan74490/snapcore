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
            FriendChecklist(
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
            decorationBox = { field ->
                Box(contentAlignment = Alignment.Center) {
                    if (name.isEmpty()) Text("Nom du groupe", style = MaterialTheme.typography.titleLarge, color = SnapColors.Muted)
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
        Text("À :", fontWeight = FontWeight.Bold, color = SnapColors.Ink)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            selected.forEach { friend ->
                Text(friend.displayName, fontSize = 14.sp, color = SnapColors.Ink, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { onRemove(friend) }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
                cursorBrush = SolidColor(SnapColors.ChatBlue),
                modifier = Modifier.heightIn(min = 24.dp).padding(vertical = 10.dp),
                decorationBox = { field ->
                    Box { if (query.isEmpty() && selected.isEmpty()) Text("Rechercher", color = SnapColors.Muted); field() }
                },
            )
        }
    }
}

/** Liste des amis à cases rondes : le nom d'un ami choisi passe en bleu, avec une coche (capture 370). */
@Composable
internal fun FriendChecklist(friends: List<Friend>, selected: List<Friend>, loading: Boolean, onToggle: (Friend) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (loading) item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
        else if (friends.isEmpty()) item {
            Text("Aucun ami à ajouter.", color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        items(friends, key = { it.id }) { friend ->
            val checked = friend in selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { onToggle(friend) }
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

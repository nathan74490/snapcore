package com.snapcore.app.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.Group
import com.snapcore.app.domain.GroupMessage
import com.snapcore.app.domain.MAX_GROUP_MEMBERS
import com.snapcore.app.domain.MessageStatus
import com.snapcore.app.domain.StatusColor
import com.snapcore.app.domain.StatusShape
import com.snapcore.app.domain.canTryToOpen
import com.snapcore.app.domain.seenByLabel
import com.snapcore.app.ui.chat.SnapViewer
import com.snapcore.app.ui.chat.StatusIcon
import com.snapcore.app.ui.chat.TypingIndicator
import com.snapcore.app.ui.chat.dayLabel
import com.snapcore.app.ui.chat.toColor
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.safety.ConfirmActionDialog
import com.snapcore.app.ui.theme.SnapColors
import java.time.LocalDate
import java.time.ZoneId

/** Couleurs des membres, dans l'ordre de la liste du groupe ; moi toujours en rouge, comme dans un chat à deux. */
private val MemberColors = listOf(
    SnapColors.ChatBlue, SnapColors.SnapPurple, Color(0xFF2ECC71), Color(0xFFFF9F1C),
    Color(0xFFE91E63), Color(0xFF00ACC1), Color(0xFF8D6E63), Color(0xFF5C6BC0),
)

private fun Group.colorOf(userId: String, myId: String): Color {
    if (userId == myId) return SnapColors.SnapRed
    val index = others(myId).indexOfFirst { it.id == userId }
    return if (index < 0) SnapColors.Grey else MemberColors[index % MemberColors.size]
}

/** Conversation de groupe (ADR-032) : chats et snaps, prénom et couleur de chaque expéditeur, « Vu par ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupConversationScreen(groupId: String, myId: String, onBack: () -> Unit, onOpenPlus: () -> Unit, onOpenCamera: (Group) -> Unit) {
    val vm = containerViewModel {
        GroupConversationViewModel(groupId, myId, it.groups, it.realtime.events, it.outbox.items, it.outbox::retry)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.consumeError() } }
    StatusBarIcons(dark = state.viewer == null)

    if (state.offerPlus) {
        AlertDialog(
            onDismissRequest = vm::dismissPlusOffer,
            title = { Text("Ce snap a déjà été vu") },
            text = { Text("Avec SnapCore+, revois les snaps reçus autant de fois que tu veux.") },
            confirmButton = { TextButton(onClick = { vm.dismissPlusOffer(); onOpenPlus() }) { Text("Découvrir SnapCore+") } },
            dismissButton = { TextButton(onClick = vm::dismissPlusOffer) { Text("Plus tard") } },
        )
    }
    val group = state.group
    if (settingsOpen && group != null) {
        GroupSettingsSheet(group, myId, onRename = vm::rename, onAdd = vm::addMembers, onLeave = vm::leave, onDismiss = { settingsOpen = false })
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(Color.White).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { FigmaAsset("15-1124_imgIconChevronLeft", "Retour") }
                    // Appui sur l'en-tête : réglages du groupe (membres, nom, ajout, quitter).
                    Row(Modifier.weight(1f).clickable(enabled = group != null) { settingsOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                        if (group != null) GroupAvatar(group, myId, 40.dp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(group?.title(myId).orEmpty(), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (group != null) Text("${group.members.size} membres", color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(color = SnapColors.Border, thickness = .5.dp)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().imePadding().heightIn(min = 60.dp).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(44.dp).background(SnapColors.Ink, CircleShape).clickable(enabled = group != null) { group?.let(onOpenCamera) },
                    Alignment.Center) {
                    FigmaAsset("17-1301_imgIconCamera", "Envoyer un snap au groupe", Modifier.size(24.dp), tint = Color.White)
                }
                BasicTextField(
                    value = state.draft, onValueChange = vm::onDraft, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.draft.isNotBlank()) vm.send() }),
                    cursorBrush = SolidColor(SnapColors.ChatBlue),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).border(1.5.dp, SnapColors.Grey, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    decorationBox = { field -> Box { if (state.draft.isEmpty()) Text("Envoyer un Chat au groupe", color = SnapColors.Muted); field() } },
                )
                if (state.draft.isNotBlank()) IconButton(onClick = vm::send) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = SnapColors.ChatBlue) }
            }
        },
    ) { padding ->
        if (group == null || (state.loading && state.messages.isEmpty())) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val listState = rememberLazyListState()
        val newest = state.messages.firstOrNull()
        LaunchedEffect(newest?.id, state.typing) {
            if ((newest != null || state.typing.isNotEmpty()) && (newest?.isFrom(myId) == true || listState.firstVisibleItemIndex <= 1)) listState.animateScrollToItem(0)
        }
        // Mon dernier message porte « Vu par… », comme dans Snapchat.
        val lastMine = state.messages.firstOrNull { it.isFrom(myId) && !it.pending && !it.failed }?.id
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            state.typing.forEach { userId ->
                item(key = "typing-$userId") {
                    val member = group.member(userId)
                    TypingIndicator(member?.displayName ?: "Quelqu'un", showName = true, color = group.colorOf(userId, myId))
                }
            }
            if (state.messages.isEmpty()) item {
                Text("Envoie le premier message au groupe !", color = SnapColors.Grey, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
            val today = LocalDate.now()
            itemsIndexed(state.messages, key = { _, m -> m.id }) { index, message ->
                val previous = state.messages.getOrNull(index + 1)
                val day = message.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
                val newDay = previous == null || previous.createdAt.atZone(ZoneId.systemDefault()).toLocalDate() != day
                GroupMessageRow(
                    message, group, myId,
                    dayHeader = if (newDay) dayLabel(day, today) else null,
                    showName = newDay || previous?.senderId != message.senderId,
                    seenBy = if (message.id == lastMine) seenByLabel(message, group, myId) else null,
                ) { vm.open(message) }
            }
        }
    }

    state.viewer?.let { viewer ->
        BackHandler(onBack = vm::closeViewer)
        SnapViewer(viewer, onClose = vm::closeViewer)
    }
}

@Composable
private fun GroupMessageRow(message: GroupMessage, group: Group, myId: String, dayHeader: String?, showName: Boolean, seenBy: String?, onOpenSnap: () -> Unit) {
    val mine = message.isFrom(myId)
    val color = group.colorOf(message.senderId, myId)
    val name = if (mine) "MOI" else (group.author(message.senderId)?.displayName ?: "Ancien membre").uppercase()
    Column(Modifier.fillMaxWidth()) {
        dayHeader?.let {
            Text(it, color = SnapColors.Grey, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp))
        }
        if (showName) Text(name, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (dayHeader == null) 12.dp else 4.dp, bottom = 2.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(color))
            if (message.isSnap) {
                val status = snapStatus(message, mine)
                val openable = canTryToOpen(message, myId) || message.failed
                Row(
                    Modifier.weight(1f).then(if (openable) Modifier.clickable(onClick = onOpenSnap) else Modifier).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusIcon(status, size = 18.dp)
                    Text(
                        when {
                            message.failed -> status.label
                            openable && !message.openedByMe -> "Appuie pour voir"
                            openable -> "${status.label} · appuie pour revoir"
                            else -> status.label
                        },
                        color = if (message.failed) SnapColors.SnapRed else status.color.toColor(),
                        fontWeight = if (openable && !message.openedByMe) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.alpha(if (message.pending) .6f else 1f),
                    )
                }
            } else {
                Text(message.content.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = SnapColors.Ink,
                    modifier = Modifier.weight(1f).alpha(if (message.pending) .6f else 1f).padding(start = 10.dp, top = 3.dp, bottom = 3.dp))
            }
        }
        seenBy?.let { Text(it, color = SnapColors.Grey, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp)) }
    }
}

private fun snapStatus(message: GroupMessage, mine: Boolean): MessageStatus {
    val color = if (message.type == com.snapcore.app.domain.ChatContentType.SNAP_PHOTO_VIDEO) StatusColor.PURPLE else StatusColor.RED
    return when {
        message.mediaPurged -> MessageStatus(StatusShape.SQUARE, StatusColor.GREY, false, if (mine) "Ouvert" else "Expiré")
        message.failed -> MessageStatus(StatusShape.SQUARE, color, true, "Échec de l'envoi · appuie pour réessayer")
        message.pending -> MessageStatus(StatusShape.SQUARE, color, true, "Envoi…")
        mine -> MessageStatus(StatusShape.SQUARE, color, message.seenBy.isEmpty(), if (message.seenBy.isEmpty()) "Envoyé" else "Ouvert")
        message.openedByMe -> MessageStatus(StatusShape.SQUARE, color, false, "Reçu")
        else -> MessageStatus(StatusShape.SQUARE, color, true, "Nouveau snap")
    }
}

/** Avatar de groupe : deux membres qui se chevauchent, comme dans la liste Chat de Snapchat. */
@Composable
fun GroupAvatar(group: Group, myId: String, size: Dp, modifier: Modifier = Modifier) {
    val others = group.others(myId)
    Box(modifier.size(size)) {
        val small = size * 0.68f
        others.getOrNull(1)?.let { Avatar(it.displayName, it.pictureUrl, small, Modifier.align(Alignment.TopEnd)) }
        others.getOrNull(0)?.let {
            Avatar(it.displayName, it.pictureUrl, small,
                Modifier.align(Alignment.BottomStart).border(2.dp, Color.White, CircleShape).offset(0.dp, 0.dp))
        }
    }
}

/** Réglages du groupe : membres, renommer, ajouter des amis, quitter. Pas d'administrateur (ADR-032). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSettingsSheet(group: Group, myId: String, onRename: (String) -> Unit, onAdd: (List<Friend>) -> Unit, onLeave: () -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    if (renaming) {
        var name by remember { mutableStateOf(group.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer le groupe") },
            text = { OutlinedTextField(name, { name = it.take(com.snapcore.app.domain.MAX_GROUP_NAME_LENGTH) }, singleLine = true,
                placeholder = { Text(group.title(myId)) }) },
            confirmButton = { TextButton(onClick = { renaming = false; onRename(name) }) { Text("Enregistrer", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuler") } },
        )
    }
    if (leaving) ConfirmActionDialog(
        title = "Quitter « ${group.title(myId)} » ?",
        text = "Tu ne recevras plus les messages du groupe. Tes messages restent visibles pour les autres membres.",
        confirm = "Quitter", onConfirm = { onDismiss(); onLeave() }, onDismiss = { leaving = false },
    )

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        if (adding) {
            AddMembersPanel(group, onAdd = { onAdd(it); adding = false }, onBack = { adding = false })
            return@ModalBottomSheet
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(group, myId, 52.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(group.title(myId), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${group.members.size} membres", color = SnapColors.Grey)
                }
            }
            HorizontalDivider(color = SnapColors.Border, modifier = Modifier.padding(vertical = 8.dp))
            SettingsAction("Renommer le groupe") { renaming = true }
            if (group.members.size < MAX_GROUP_MEMBERS) SettingsAction("Ajouter des membres") { adding = true }
            SettingsAction("Quitter le groupe", color = SnapColors.SnapRed) { leaving = true }
            HorizontalDivider(color = SnapColors.Border, modifier = Modifier.padding(vertical = 8.dp))
            Text("Membres", fontWeight = FontWeight.Bold, color = SnapColors.Ink)
            group.members.sortedBy { if (it.id == myId) "" else it.displayName.lowercase() }.forEach { member ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(member.displayName, member.pictureUrl, 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(if (member.id == myId) "${member.displayName} (moi)" else member.displayName, fontSize = 16.sp)
                        Text("@${member.username}", color = SnapColors.Grey, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(label: String, color: Color = SnapColors.Ink, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}

/** Ajouter des amis au groupe : ceux qui y sont déjà ne sont pas proposés. */
@Composable
private fun AddMembersPanel(group: Group, onAdd: (List<Friend>) -> Unit, onBack: () -> Unit) {
    val excluded = group.members.map { it.id }.toSet()
    val vm = containerViewModel { NewGroupViewModel(it.friends, it.groups, excluded = excluded) }
    val state by vm.state.collectAsStateWithLifecycle()
    val room = MAX_GROUP_MEMBERS - group.members.size
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Retour") }
            Text("Ajouter des membres", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Box(Modifier.width(72.dp))
        }
        FriendChecklist(state.friends, state.selected, state.loading,
            onToggle = { if (it in state.selected || state.selected.size < room) vm.toggle(it) }, modifier = Modifier.weight(1f, fill = false))
        PrimaryButton(
            text = if (state.selected.isEmpty()) "Ajouter" else "Ajouter (${state.selected.size})",
            onClick = { onAdd(state.selected) }, enabled = state.selected.isNotEmpty(),
            modifier = Modifier.padding(16.dp),
        )
    }
}

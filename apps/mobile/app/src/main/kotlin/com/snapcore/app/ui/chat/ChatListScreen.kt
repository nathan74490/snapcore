package com.snapcore.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.*
import com.snapcore.app.ui.components.*
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.groups.GroupAvatar
import java.time.Instant
import com.snapcore.app.ui.theme.SnapColors

// Toutes les icônes de la liste à la taille de celles de l'en-tête (24).
private val ICON = 24.dp

private fun Conversation.unread(myId: String) = headline?.let { !it.isFrom(myId) && it.openedAt == null && !it.mediaPurged } == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(myId: String, onOpen: (Friend, snapId: String?) -> Unit, onAddFriends: () -> Unit, onSnapTo: (Friend) -> Unit, modifier: Modifier = Modifier,
    onOpenGroup: (Group, snapId: String?) -> Unit = { _, _ -> }, onSnapToGroup: (Group) -> Unit = {}, onNewChat: () -> Unit = onAddFriends) {
    val vm = containerViewModel { ChatListViewModel(it.chat, it.realtime.events, myId, it.outbox.items, it.outbox::retry, groupService = it.groups) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Au retour d'une conversation : le snap ouvert ou les chats lus ne déclenchent un événement temps
    // réel que chez l'expéditeur, la liste se recharge donc elle-même (BUG-011 d'ADR-023).
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val unread = state.conversations.count { it.unread(myId) } + state.groups.count { it.unread(myId) }
    // Amis et groupes (ADR-032) dans une seule liste, les envois en cours et la plus récente en tête.
    val rows: List<ChatRow> = remember(state.conversations, state.groups) { chatRows(state.conversations, state.groups) }
    val visible = rows.filter { row ->
        when (filter) {
            1 -> row.unread(myId)
            2 -> row.isNew
            else -> true
        }
    }
    SnapRefresh(state.loading && state.conversations.isNotEmpty(), vm::refresh, modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        // Même marge latérale (12) que l'en-tête et les lignes de conversation.
        // Filtres : des onglets pour TalkBack (« Non lus, 3 conversations, onglet, sélectionné ») (ADR-033).
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tous", "Non lus", "Nouveaux").forEachIndexed { index, label ->
                Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if(filter == index) SnapColors.Selected else Color.Transparent)
                    .selectable(selected = filter == index, role = Role.Tab) { filter = index }.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, fontSize = 16.sp, fontWeight = if(filter == index) FontWeight.Bold else FontWeight.SemiBold,
                        color = if(filter == index) Color(0xFF0A6EA8) else SnapColors.Grey)
                    if(index == 1 && unread > 0) Box(Modifier.size(22.dp).background(Color(0xFF0D4B70), CircleShape)
                        .clearAndSetSemantics { contentDescription = if (unread > 1) "$unread conversations" else "1 conversation" }, Alignment.Center) {
                        Text(unread.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        // Le bouton « Nouveau chat » est lu juste après les filtres, avant la liste, et non tout à la fin (ADR-033).
        Box(Modifier.weight(1f).semantics { isTraversalGroup = true }) {
            // Une conversation qui remonte en tête (message reçu) s'insère au-dessus de l'écran : la liste
            // garde sinon sa ligne de référence. En haut de liste, on remonte pour la montrer.
            val listState = rememberLazyListState()
            val first = visible.firstOrNull()?.key
            LaunchedEffect(first) { if (first != null && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 90.dp)) {
                state.error?.let { error -> item {
                    TextButton(onClick = vm::refresh, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text("$error · Réessayer", color = SnapColors.SnapRed)
                    }
                } }
                if(state.loading && state.conversations.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp).semantics { contentDescription = "Chargement des conversations" }, Alignment.Center) { CircularProgressIndicator() }
                }
                items(visible, key = { it.key }) { row ->
                    if (row is ChatRow.OfGroup) {
                        val summary = row.summary
                        GroupRow(summary, myId, onSnap = { onSnapToGroup(summary.group) }) {
                            val last = summary.headline
                            // Nouveau snap reçu : il s'ouvre directement, comme pour un ami (BUG-024 d'ADR-023).
                            val snap = last?.takeIf { canTryToOpen(it, myId) && !it.openedByMe }
                            if (last?.failed == true) vm.retryGroup(last) else onOpenGroup(summary.group, snap?.id)
                        }
                        return@items
                    }
                    val conversation = (row as ChatRow.OfFriend).conversation
                    // Nouveau snap reçu : il s'ouvre directement, comme dans Snapchat.
                    val snap = conversation.headline?.takeIf { canTryToOpen(it, myId) && it.openedAt == null }
                    ConversationRow(conversation, myId, onSnap = { onSnapTo(conversation.friend) }) {
                        val last = conversation.headline
                        // Envoi en échec : un appui le relance, comme dans Snapchat.
                        if (last?.failed == true) vm.retry(last)
                        else {
                            vm.markSeen(conversation.friend.id, myId)
                            onOpen(conversation.friend, snap?.id)
                        }
                    }
                }
                if(visible.isEmpty() && !state.loading) item {
                    Text(if(filter == 1) "Tous tes chats sont lus." else if(filter == 2) "Aucune nouvelle conversation." else "Retrouve tes amis ici pour commencer à discuter.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = SnapColors.Grey, textAlign = TextAlign.Center)
                }
                item {
                    Text("Ajouter des amis", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
                    // Toute la ligne est cliquable : rien à droite, là où passe le bouton flottant (BUG-010).
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "rechercher un pseudo", onClick = onAddFriends)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(48.dp).background(SnapColors.Control, CircleShape), Alignment.Center) { FigmaAsset("17-1301_imgIconAddFriend", modifier = Modifier.size(ICON)) }
                        Column(Modifier.weight(1f)) {
                            Text("Trouver mes amis", style = MaterialTheme.typography.bodyLarge)
                            Text("Rechercher un pseudo", color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // Jaune et icône noire, comme le bouton « Nouveau chat » de Snapchat.
            // Il ouvre « Nouveau chat » : un ami, ou plusieurs pour créer un groupe (ADR-032).
            FloatingActionButton(onClick = onNewChat, containerColor = SnapColors.Yellow, contentColor = SnapColors.Ink,
                shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(60.dp)
                    .semantics { traversalIndex = -1f }) {
                FigmaAsset("15-925_imgIconEdit", "Nouveau chat", tint = SnapColors.Ink)
            }
        }
    }
}
}

@Composable
private fun ConversationRow(conversation: Conversation, myId: String, onSnap: () -> Unit, onClick: () -> Unit) {
    val last = conversation.headline
    val unread = conversation.unread(myId)
    // Chat Row 11:281 : nom toujours en Regular 18 ; seul le statut non lu passe en gras et en couleur,
    // l'horodatage reste à part en gris clair.
    // TalkBack (ADR-033) : la ligne entière est un seul élément, « Léa, nouveau snap, il y a 5 minutes », dont le
    // double appui ouvre la conversation ; le snap en réponse est une action personnalisée.
    val name = conversation.friend.displayName
    val spoken = if (last == null) "$name, pas encore de message"
        else "$name, ${statusOf(last, myId).spoken()}, ${spokenRelativeTime(last.createdAt)}"
    val action = when {
        last?.failed == true -> "réessayer l'envoi"
        last != null && canTryToOpen(last, myId) && last.openedAt == null -> "voir le snap"
        else -> "ouvrir la conversation"
    }
    Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick)
        .clearAndSetSemantics {
            contentDescription = spoken
            role = Role.Button
            this.onClick(action) { onClick(); true }
            customActions = listOf(CustomAccessibilityAction("Envoyer un snap à $name") { onSnap(); true })
        }
        .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(conversation.friend.displayName, conversation.friend.pictureUrl, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(conversation.friend.displayName, fontSize = 18.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if(last != null) {
                    val status = statusOf(last, myId)
                    StatusIcon(status, size = 16.dp)
                    Text(status.label, color = if(last.failed) SnapColors.SnapRed else if(unread) status.color.toColor() else SnapColors.Grey,
                        fontWeight = if(unread) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("· ${relativeTime(last.createdAt)}", color = SnapColors.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                } else Text("Appuie pour discuter", color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Comme dans Snapchat : l'appareil photo de la ligne ouvre la caméra pour répondre par un snap.
        IconButton(onClick = onSnap) { FigmaAsset("17-1301_imgIconCamera", "Envoyer un snap", Modifier.size(ICON), tint = SnapColors.Ink) }
    }
    HorizontalDivider(color = SnapColors.Border, thickness = 1.dp)
}

/** Une ligne de la liste Chat : un ami ou un groupe (ADR-032). */
private sealed interface ChatRow {
    val key: String
    /** Dernière activité, pour trier les deux familles ensemble. */
    val activity: Instant
    val isNew: Boolean
    fun unread(myId: String): Boolean

    data class OfFriend(val conversation: Conversation) : ChatRow {
        override val key get() = conversation.friend.id
        override val activity: Instant get() = conversation.lastMessage?.createdAt ?: Instant.EPOCH
        override val isNew get() = conversation.lastMessage == null
        override fun unread(myId: String) = conversation.unread(myId)
    }

    data class OfGroup(val summary: GroupSummary) : ChatRow {
        override val key get() = "group-" + summary.group.id
        override val activity: Instant get() = summary.lastMessage?.createdAt ?: summary.group.createdAt
        override val isNew get() = summary.lastMessage == null
        override fun unread(myId: String) = summary.unread(myId)
    }
}

private fun GroupSummary.unread(myId: String) =
    unread || unopenedSnap != null

// Les envois en cours restent en tête ; amis et groupes sont ensuite triés par dernière activité, les amis
// sans conversation en bas, comme avant.
private fun chatRows(conversations: List<Conversation>, groups: List<GroupSummary>): List<ChatRow> {
    val friendRows = conversations.map { ChatRow.OfFriend(it) }
    val (active, untouched) = friendRows.partition { it.conversation.lastMessage != null }
    val sending: (ChatRow) -> Boolean = { row ->
        when (row) {
            is ChatRow.OfFriend -> row.conversation.lastMessage?.pending == true
            is ChatRow.OfGroup -> row.summary.lastMessage?.pending == true
        }
    }
    val withActivity = (active + groups.map { ChatRow.OfGroup(it) })
        .sortedWith(compareByDescending<ChatRow> { sending(it) }.thenByDescending { it.activity })
    return withActivity + untouched
}

@Composable
private fun GroupRow(summary: GroupSummary, myId: String, onSnap: () -> Unit, onClick: () -> Unit) {
    val group = summary.group
    val last = summary.headline
    val unread = summary.unread(myId)
    // Un seul élément pour TalkBack (ADR-033) : « Groupe Soirée samedi, snap de Léa, il y a 5 minutes ».
    val title = group.title(myId)
    val spoken = if (last == null) "Groupe $title, groupe créé, pas encore de message"
        else "Groupe $title, ${statusOf(last, group, myId, unread).spoken()}, ${spokenRelativeTime(last.createdAt)}"
    val action = when {
        last?.failed == true -> "réessayer l'envoi"
        last != null && canTryToOpen(last, myId) && !last.openedByMe -> "voir le snap"
        else -> "ouvrir la conversation"
    }
    Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick)
        .clearAndSetSemantics {
            contentDescription = spoken
            role = Role.Button
            this.onClick(action) { onClick(); true }
            customActions = listOf(CustomAccessibilityAction("Envoyer un snap au groupe") { onSnap(); true })
        }
        .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupAvatar(group, myId, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(group.title(myId), fontSize = 18.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (last != null) {
                    val status = statusOf(last, group, myId, unread)
                    StatusIcon(status, size = 16.dp)
                    Text(status.label, color = if (last.failed) SnapColors.SnapRed else if (unread) status.color.toColor() else SnapColors.Grey,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Text("· ${relativeTime(last.createdAt)}", color = SnapColors.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                } else Text("Groupe créé · appuie pour discuter", color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
            }
        }
        IconButton(onClick = onSnap) { FigmaAsset("17-1301_imgIconCamera", "Envoyer un snap au groupe", Modifier.size(ICON), tint = SnapColors.Ink) }
    }
    HorizontalDivider(color = SnapColors.Border, thickness = 1.dp)
}

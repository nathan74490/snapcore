package com.snapcore.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.snapcore.app.ui.theme.SnapColors

// Toutes les icônes de la liste à la taille de celles de l'en-tête (24).
private val ICON = 24.dp

private fun Conversation.unread(myId: String) = lastMessage?.let { !it.isFrom(myId) && it.openedAt == null && !it.mediaPurged } == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(myId: String, onOpen: (Friend, snapId: String?) -> Unit, onAddFriends: () -> Unit, onSnapTo: (Friend) -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { ChatListViewModel(it.chat, it.realtime.events) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Au retour d'une conversation : le snap ouvert ou les chats lus ne déclenchent un événement temps
    // réel que chez l'expéditeur, la liste se recharge donc elle-même (BUG-011 d'ADR-023).
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val unread = state.conversations.count { it.unread(myId) }
    val visible = state.conversations.filter { when(filter) { 1 -> it.unread(myId); 2 -> it.lastMessage == null; else -> true } }
    SnapRefresh(state.loading && state.conversations.isNotEmpty(), vm::refresh, modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        // Même marge latérale (12) que l'en-tête et les lignes de conversation.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tous", "Non lus", "Nouveaux").forEachIndexed { index, label ->
                Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if(filter == index) SnapColors.Selected else Color.Transparent)
                    .clickable { filter = index }.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, fontSize = 16.sp, fontWeight = if(filter == index) FontWeight.Bold else FontWeight.SemiBold,
                        color = if(filter == index) Color(0xFF0A6EA8) else SnapColors.Grey)
                    if(index == 1 && unread > 0) Box(Modifier.size(22.dp).background(Color(0xFF0D4B70), CircleShape), Alignment.Center) {
                        Text(unread.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
                state.error?.let { error -> item { TextButton(onClick = vm::refresh) { Text("$error · Réessayer", color = SnapColors.SnapRed) } } }
                if(state.loading && state.conversations.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
                items(visible, key = { it.friend.id }) { conversation ->
                    // Nouveau snap reçu : il s'ouvre directement, comme dans Snapchat.
                    val snap = conversation.lastMessage?.takeIf { canTryToOpen(it, myId) && it.openedAt == null }
                    ConversationRow(conversation, myId, onSnap = { onSnapTo(conversation.friend) }) { onOpen(conversation.friend, snap?.id) }
                }
                if(visible.isEmpty() && !state.loading) item {
                    Text(if(filter == 1) "Tous tes chats sont lus." else if(filter == 2) "Aucune nouvelle conversation." else "Retrouve tes amis ici pour commencer à discuter.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp), color = SnapColors.Grey, textAlign = TextAlign.Center)
                }
                item {
                    Text("Ajouter des amis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp))
                    // Toute la ligne est cliquable : rien à droite, là où passe le bouton flottant (BUG-010).
                    Row(Modifier.fillMaxWidth().clickable(onClick = onAddFriends).padding(horizontal = 12.dp, vertical = 12.dp),
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
            FloatingActionButton(onClick = onAddFriends, containerColor = SnapColors.Yellow, contentColor = SnapColors.Ink,
                shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(60.dp)) {
                FigmaAsset("15-925_imgIconEdit", "Nouveau chat", tint = SnapColors.Ink)
            }
        }
    }
}
}

@Composable
private fun ConversationRow(conversation: Conversation, myId: String, onSnap: () -> Unit, onClick: () -> Unit) {
    val last = conversation.lastMessage
    val unread = conversation.unread(myId)
    // Chat Row 11:281 : nom toujours en Regular 18 ; seul le statut non lu passe en gras et en couleur,
    // l'horodatage reste à part en gris clair.
    Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(conversation.friend.displayName, conversation.friend.photoUrl, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(conversation.friend.displayName, fontSize = 18.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if(last != null) {
                    val status = statusOf(last, myId)
                    StatusIcon(status, size = 16.dp)
                    Text(status.label, color = if(unread) status.color.toColor() else SnapColors.Grey,
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

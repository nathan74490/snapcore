package com.snapcore.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Conversation
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.statusOf
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

// Liste des conversations avec l'icône de statut du dernier message (ADR-009,
// référence : chat-liste-conversations.png). Seuls les amis ACCEPTED y figurent (ADR-012).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(myId: String, onOpen: (Friend) -> Unit, onAddFriends: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { ChatListViewModel(it.chat, it.realtime.events) }
    val state by vm.state.collectAsStateWithLifecycle()

    PullToRefreshBox(isRefreshing = state.loading && state.conversations.isNotEmpty(), onRefresh = vm::refresh, modifier = modifier.fillMaxSize()) {
        when {
            state.loading && state.conversations.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.conversations.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error ?: "Aucune conversation pour l'instant.", color = SnapColors.Grey, textAlign = TextAlign.Center)
                TextButton(onClick = onAddFriends) { Text("Ajouter des amis") }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.conversations, key = { it.friend.id }) { conversation ->
                    ConversationRow(conversation, myId) { onOpen(conversation.friend) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, myId: String, onClick: () -> Unit) {
    val last = conversation.lastMessage
    // Un message reçu non ouvert met la conversation en gras, comme dans Snapchat.
    val unread = last != null && !last.isFrom(myId) && last.openedAt == null && !last.mediaPurged
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(conversation.friend.username, conversation.friend.photoUrl, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(conversation.friend.username, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (last != null) {
                    val status = statusOf(last, myId)
                    StatusIcon(status)
                    Text(
                        "${status.label} · ${relativeTime(last.createdAt)}",
                        color = if (unread) status.color.toColor() else SnapColors.Grey,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Appuie pour discuter", color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

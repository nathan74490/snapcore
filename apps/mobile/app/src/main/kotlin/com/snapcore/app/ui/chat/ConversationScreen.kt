package com.snapcore.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.ChatMessage
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.canTryToOpen
import com.snapcore.app.domain.statusOf
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(friend: Friend, myId: String, onBack: () -> Unit) {
    val vm = containerViewModel { ConversationViewModel(friend, myId, it.chat, it.realtime.events) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(friend.username, friend.photoUrl, size = 36.dp)
                        Text(friend.username)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::onDraft,
                    placeholder = { Text("Envoyer un Chat") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::send, enabled = state.draft.isNotBlank() && !state.sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = SnapColors.ChatBlue)
                }
            }
        },
    ) { padding ->
        if (state.loading && state.messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
        } else {
            // reverseLayout : les messages les plus récents en bas, la liste s'ouvre sur eux.
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageRow(message, myId, friend.username) { vm.open(message) }
                }
            }
        }
    }

    state.viewer?.let { viewer ->
        BackHandler(onBack = vm::closeViewer)
        SnapViewer(viewer, onClose = vm::closeViewer)
    }
}

@Composable
private fun MessageRow(message: ChatMessage, myId: String, friendName: String, onOpenSnap: () -> Unit) {
    val mine = message.isFrom(myId)
    val status = statusOf(message, myId)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(
            if (mine) "MOI" else friendName.uppercase(),
            color = if (mine) SnapColors.SnapRed else SnapColors.ChatBlue,
            style = MaterialTheme.typography.labelSmall,
        )
        if (message.isSnap) {
            val openable = canTryToOpen(message, myId)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .then(if (openable) Modifier.clickable(onClick = onOpenSnap) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                StatusIcon(status, size = 18.dp)
                Text(
                    when {
                        openable && message.openedAt == null -> "Appuie pour voir"
                        openable -> "${status.label} · appuie pour revoir"
                        else -> status.label
                    },
                    color = status.color.toColor(),
                )
            }
        } else {
            Text(
                message.content.orEmpty(),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(if (mine) Color(0x1FF23C57) else Color(0x1F0EADFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

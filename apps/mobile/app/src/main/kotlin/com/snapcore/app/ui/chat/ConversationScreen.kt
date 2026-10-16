package com.snapcore.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.ImageBitmap
import com.snapcore.app.ui.components.SwipeBackPane
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.StatusBarIcons
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
fun ConversationScreen(friend: Friend, myId: String, onBack: () -> Unit, onOpenPlus: () -> Unit, backdrop: ImageBitmap? = null, onSwipeBack: () -> Unit = onBack, openSnapId: String? = null) {
    val vm = containerViewModel { ConversationViewModel(friend, myId, it.chat, it.realtime.events, openSnapId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Le clavier arrive après le panneau : aucune variation de largeur pendant le glissement.
    // Ouvert sur un snap : pas de clavier par-dessus la lecture.
    LaunchedEffect(friend.id, lifecycleOwner) {
        if (openSnapId != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.withResumed {
            composerFocus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    // Snap déjà vu : c'est précisément ce que SnapCore+ débloque (ADR-025).
    if (state.offerPlus) {
        AlertDialog(
            onDismissRequest = vm::dismissPlusOffer,
            title = { Text("Ce snap a déjà été vu") },
            text = { Text("Avec SnapCore+, revois les snaps reçus autant de fois que tu veux.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPlusOffer()
                    onOpenPlus()
                }) { Text("Découvrir SnapCore+") }
            },
            dismissButton = { TextButton(onClick = vm::dismissPlusOffer) { Text("Plus tard") } },
        )
    }

    StatusBarIcons(dark = true)
    SwipeBackPane(backdrop, enabled = state.viewer == null, onBack = onSwipeBack) { backGesture ->
        Scaffold(
            topBar = {

                Column(Modifier.background(Color.White).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { FigmaAsset("15-1124_imgIconChevronLeft", "Retour") }
                        Avatar(friend.displayName, friend.photoUrl, 48.dp)
                        Text(friend.displayName, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    }
                    HorizontalDivider(color = SnapColors.Border, thickness = .5.dp)
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Row(
                    // Chat Input Bar 10:677 : champ pilule 40pt (#ECEEEF, rayon 20) dans une barre de 56pt.
                    modifier = Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().imePadding()
                        .heightIn(min = 56.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {

                    BasicTextField(value = state.draft, onValueChange = vm::onDraft,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if(state.draft.isNotBlank()) vm.send() }),
                        maxLines = 4,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(SnapColors.ChatBlue),
                        modifier = Modifier.focusRequester(composerFocus).weight(1f).heightIn(min = 40.dp).background(SnapColors.Control, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                        decorationBox = { field -> Box { if(state.draft.isEmpty()) Text("Envoyer un Chat", color = SnapColors.Muted, style = MaterialTheme.typography.bodyLarge); field() } })
                    // Grisé tant qu'il n'y a rien à envoyer : la teinte suit l'état, pas seulement le clic.
                    val canSend = state.draft.isNotBlank()
                    IconButton(onClick = vm::send, enabled = canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = if (canSend) SnapColors.ChatBlue else SnapColors.Disabled)
                    }
                }
            },
        ) { padding ->
            if (state.loading && state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            } else {
                // reverseLayout : les messages les plus récents en bas, la liste s'ouvre sur eux.
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).then(backGesture),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageRow(message, myId, friend.displayName) { vm.open(message) }
                    }
                }
            }
        }

    }

    // Snap ouvert depuis la liste des chats : le fermer y ramène directement.
    val closeViewer = { vm.closeViewer(); if (openSnapId != null) onBack() }
    state.viewer?.let { viewer ->
        BackHandler(onBack = closeViewer)
        SnapViewer(viewer, onClose = closeViewer)
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
                    .alpha(if (message.pending) .6f else 1f)
                    .background(if (mine) Color(0x1FF23C57) else Color(0x1F0EADFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

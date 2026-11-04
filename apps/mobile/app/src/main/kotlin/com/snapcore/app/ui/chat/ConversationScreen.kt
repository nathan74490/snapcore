package com.snapcore.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.ImageBitmap
import com.snapcore.app.ui.components.SwipeBackPane
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import java.time.ZoneId
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
fun ConversationScreen(friend: Friend, myId: String, onBack: () -> Unit, onOpenPlus: () -> Unit, backdrop: ImageBitmap? = null, onSwipeBack: () -> Unit = onBack, openSnapId: String? = null, onOpenCamera: () -> Unit = {}) {
    val vm = containerViewModel {
        ConversationViewModel(friend, myId, it.chat, it.realtime.events, openSnapId, outbox = it.outbox.items, retrySend = it.outbox::retry)
    }
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
    // Un snap s'ouvre : le clavier se ferme, il ne doit pas rester par-dessus la lecture.
    val focusManager = LocalFocusManager.current
    val viewing = state.viewer != null
    LaunchedEffect(viewing) {
        if (viewing) {
            keyboard?.hide()
            focusManager.clearFocus()
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

    // Icônes claires sur le snap plein écran, sombres sur la conversation. Le réglage dépend de l'état du
    // lecteur pour être réappliqué à sa fermeture : sinon il restait blanc sur fond blanc (BUG-015 d'ADR-023).
    StatusBarIcons(dark = state.viewer == null)
    SwipeBackPane(backdrop, enabled = state.viewer == null, onBack = onSwipeBack) { backGesture ->
        Scaffold(
            topBar = {

                Column(Modifier.background(Color.White).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { FigmaAsset("15-1124_imgIconChevronLeft", "Retour") }
                        // Comme Snapchat : avatar compact, prénom en gras et le pseudo dessous.
                        Avatar(friend.displayName, friend.pictureUrl, 40.dp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(friend.displayName, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                            if (friend.firstName != null) Text("@${friend.username}", color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(color = SnapColors.Border, thickness = .5.dp)
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                // Barre de saisie façon Snapchat : appareil photo noir à gauche (snap à cet ami), champ
                // pilule à contour ; la flèche d'envoi n'apparaît qu'avec du texte à envoyer.
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().imePadding()
                        .heightIn(min = 60.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(SnapColors.Ink).clickable(onClick = onOpenCamera), Alignment.Center) {
                        FigmaAsset("17-1301_imgIconCamera", "Envoyer un snap", Modifier.size(24.dp), tint = Color.White)
                    }
                    BasicTextField(value = state.draft, onValueChange = vm::onDraft,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if(state.draft.isNotBlank()) vm.send() }),
                        maxLines = 4,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(SnapColors.ChatBlue),
                        modifier = Modifier.focusRequester(composerFocus).weight(1f).heightIn(min = 44.dp)
                            .border(1.5.dp, SnapColors.Grey, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
                        decorationBox = { field -> Box { if(state.draft.isEmpty()) Text("Envoyer un Chat", color = SnapColors.Muted, style = MaterialTheme.typography.bodyLarge); field() } })
                    if (state.draft.isNotBlank()) {
                        IconButton(onClick = vm::send) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = SnapColors.ChatBlue)
                        }
                    }
                }
            },
        ) { padding ->
            if (state.loading && state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            } else {
                // reverseLayout : les messages les plus récents en bas, la liste s'ouvre sur eux.
                // Un message reçu s'insère sous la dernière ligne affichée, que la liste garde sinon comme
                // référence (BUG-012) : on le montre si l'on était en bas, et toujours pour son propre envoi.
                val listState = rememberLazyListState()
                val newest = state.messages.firstOrNull()
                LaunchedEffect(newest?.id, state.friendTyping) {
                    val mine = newest?.isFrom(myId) == true
                    if ((newest != null || state.friendTyping) && (mine || listState.firstVisibleItemIndex <= 1)) listState.animateScrollToItem(0)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).then(backGesture),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    // Du plus récent au plus ancien : le message suivant dans la liste est le précédent à l'écran.
                    // Tout en bas, comme dans Snapchat : l'ami est en train d'écrire (ADR-027).
                    if (state.friendTyping) item(key = "typing") {
                        TypingIndicator(friend.displayName, showName = state.messages.firstOrNull()?.senderId != friend.id)
                    }
                    val today = LocalDate.now()
                    itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                        val previous = state.messages.getOrNull(index + 1)
                        val day = message.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
                        val newDay = previous == null || previous.createdAt.atZone(ZoneId.systemDefault()).toLocalDate() != day
                        MessageRow(
                            message, myId, friend.displayName,
                            dayHeader = if (newDay) dayLabel(day, today) else null,
                            // Le nom n'apparaît qu'au début d'une suite de messages du même expéditeur.
                            showName = newDay || previous?.senderId != message.senderId,
                        ) { vm.open(message) }
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

// Comme dans Snapchat : pas de bulles, tout est aligné à gauche avec une barre de couleur (rouge pour
// moi, bleu pour l'ami) ; le nom de l'expéditeur ouvre chaque suite de messages.
@Composable
private fun MessageRow(message: ChatMessage, myId: String, friendName: String, dayHeader: String?, showName: Boolean, onOpenSnap: () -> Unit) {
    val mine = message.isFrom(myId)
    val status = statusOf(message, myId)
    val color = if (mine) SnapColors.SnapRed else SnapColors.ChatBlue
    Column(Modifier.fillMaxWidth()) {
        dayHeader?.let {
            Text(it, color = SnapColors.Grey, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp))
        }
        if (showName) {
            Text(if (mine) "MOI" else friendName.uppercase(), color = color, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (dayHeader == null) 12.dp else 4.dp, bottom = 2.dp))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(color))
            if (message.isSnap) {
                // Un snap envoyé en échec se relance d'un appui.
                val openable = canTryToOpen(message, myId) || message.failed
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                        .then(if (openable) Modifier.clickable(onClick = onOpenSnap) else Modifier)
                        .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    StatusIcon(status, size = 18.dp)
                    Text(
                        when {
                            message.failed -> status.label
                            openable && message.openedAt == null -> "Appuie pour voir"
                            openable -> "${status.label} · appuie pour revoir"
                            else -> status.label
                        },
                        color = if (message.failed) SnapColors.SnapRed else status.color.toColor(),
                        fontWeight = if (openable && message.openedAt == null) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.alpha(if (message.pending) .6f else 1f),
                    )
                }
            } else {
                Text(
                    message.content.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SnapColors.Ink,
                    modifier = Modifier.weight(1f).alpha(if (message.pending) .6f else 1f).padding(start = 10.dp, top = 3.dp, bottom = 3.dp),
                )
            }
        }
    }
}

// Trois points qui rebondissent à tour de rôle sous le nom de l'ami, avec la même barre bleue que
// ses messages ; le nom est omis s'il ouvre déjà la suite de messages juste au-dessus.
@Composable
private fun TypingIndicator(friendName: String, showName: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "$friendName est en train d'écrire" }) {
        if (showName) {
            Text(friendName.uppercase(), color = SnapColors.ChatBlue, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        }
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(SnapColors.ChatBlue))
            Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { index ->
                    val lift by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(keyframes {
                            durationMillis = 1_200
                            0f at index * 160 using FastOutSlowInEasing
                            1f at index * 160 + 280 using FastOutSlowInEasing
                            0f at index * 160 + 560
                        }),
                        label = "dot$index",
                    )
                    Box(Modifier.graphicsLayer { translationY = -lift * 5.dp.toPx(); alpha = .45f + .55f * lift }
                        .size(8.dp).background(SnapColors.Grey, CircleShape))
                }
            }
        }
    }
}

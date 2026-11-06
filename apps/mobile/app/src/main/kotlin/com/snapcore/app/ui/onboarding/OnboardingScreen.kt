package com.snapcore.app.ui.onboarding

import com.snapcore.app.ui.components.screenReaderFocusOnAppear
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.runtime.CompositionLocalProvider
import com.snapcore.app.ui.onboarding.LocalJourneyStep
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import com.snapcore.app.ui.components.rememberScreenReaderOn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.snapcore.app.data.avatarImageUrl
import com.snapcore.app.domain.Countries
import com.snapcore.app.domain.Country
import com.snapcore.app.domain.Relation
import com.snapcore.app.push.Notifications
import com.snapcore.app.ui.auth.FieldBox
import com.snapcore.app.ui.auth.FieldLabel
import com.snapcore.app.ui.auth.FieldStatus
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.SnapCard
import com.snapcore.app.ui.components.SnapCheckbox
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.snapcore.app.ui.components.SnapSearchBar
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.profile.AvatarChange
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.launch

/**
 * Premiers pas après l'inscription (ADR-029), d'après le design onboarding de Snapchat (iOS déc. 2024).
 * @param avatarChange avatar validé dans l'éditeur ouvert depuis l'étape « Crée ton avatar ».
 */
@Composable
fun OnboardingScreen(
    username: String,
    onOpenAvatarEditor: () -> Unit,
    avatarChange: AvatarChange?,
    onAvatarChangeApplied: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val vm = containerViewModel {
        OnboardingViewModel(it.accounts, it.friends, it.contacts, needsNotificationPrompt = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(context) &&
                Notifications.claimFirstPermissionRequest(context)
        }, analytics = it.analytics, experiments = it.experiments)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.DONE) onDone() }
    LaunchedEffect(avatarChange) { avatarChange?.let { vm.applyAvatar(it); onAvatarChangeApplied() } }
    // Pas de retour vers l'inscription : le compte existe déjà, la touche retour passe l'écran.
    BackHandler { vm.quit() }

    StatusBarIcons(dark = true)
    val background = if (state.step == OnboardingStep.ADD_FRIENDS || state.step == OnboardingStep.INVITE) SnapColors.Background else Color.White
    val screenReader = rememberScreenReaderOn()
    Box(Modifier.fillMaxSize().background(background)) {
        // TalkBack : question, contenu, puis « Passer » en fin de lecture.
        Column(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
            // Le fantôme accompagne chaque écran ; la barre continue celle de l'inscription.
            JourneyBar(Journey.firstSteps(state.step.ordinal), onSkip = when (state.step) {
                OnboardingStep.FIND_FRIENDS -> vm::skipFindFriends
                OnboardingStep.ADD_FRIENDS -> vm::skipAddFriends
                OnboardingStep.INVITE -> vm::skipInvites
                OnboardingStep.AVATAR -> vm::skipAvatar
                else -> null
            })
            AnimatedContent(state.step, Modifier.weight(1f), transitionSpec = {
                if (screenReader) EnterTransition.None togetherWith ExitTransition.None
                else (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 2 } + fadeOut())
            }, label = "étape") { step ->
                CompositionLocalProvider(LocalJourneyStep provides Journey.firstStepsLabel(step.ordinal)) {
                when (step) {
                    OnboardingStep.FIND_FRIENDS -> FindFriendsStep(vm)
                    OnboardingStep.ADD_FRIENDS -> AddFriendsStep(state, vm)
                    OnboardingStep.INVITE -> InviteStep(state, vm, username)
                    OnboardingStep.AVATAR -> AvatarStep(state, onOpenAvatarEditor)
                    OnboardingStep.NOTIFICATIONS -> NotificationsStep(vm::onNotificationsPermission, vm::skipNotifications, vm::finish)
                    OnboardingStep.DONE -> Unit
                }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

// --- Trouver ses amis ---------------------------------------------------------------------------

@Composable
private fun FindFriendsStep(vm: OnboardingViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onContactsPermission)
    val copy = vm.state.collectAsStateWithLifecycle().value.contactsCopy
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val line = GhostLines.findFriends(copy)
        GhostSays(line.text, line.mood, Modifier.padding(top = 8.dp), ghostSize = 88.dp)
        Text(copy.text,
            fontSize = 16.sp, color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp).clearAndSetSemantics {}, Alignment.Center) { ContactsIllustration() }
        PrimaryButton(copy.button, { permission.launch(Manifest.permission.READ_CONTACTS) }, container = SnapColors.Yellow,
            content = SnapColors.Ink, height = 50.dp, modifier = Modifier.padding(bottom = 16.dp))
    }
}

// Téléphone stylisé rempli de bulles d'amis, comme l'illustration de Snapchat.
@Composable
private fun ContactsIllustration() {
    val palette = listOf(SnapColors.ChatBlue, SnapColors.SnapRed, SnapColors.SnapPurple, Color(0xFF2ECC71), Color(0xFFFF9F1C), SnapColors.Muted)
    val initials = "JT DC MT AT SE CH AH JC LM NB RO PL EV IN HA YZ".split(" ")
    Box(Modifier.width(220.dp).aspectRatio(.56f).border(8.dp, SnapColors.Ink, RoundedCornerShape(36.dp)).padding(18.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(8) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    repeat(6) { col ->
                        val i = row * 6 + col
                        Box(Modifier.size(22.dp).background(palette[(i * 7) % palette.size].copy(alpha = .85f), CircleShape), Alignment.Center) {
                            Text(initials[i % initials.size], fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// --- Ajouter des amis ---------------------------------------------------------------------------

@Composable
private fun AddFriendsStep(state: OnboardingState, vm: OnboardingViewModel) {
    // TalkBack : la liste est un seul bouton, qui s'ouvre dans sa propre fenêtre (voir ContactListButton).
    val screenReader = rememberScreenReaderOn()
    var listOpen by remember { mutableStateOf(false) }
    val checkedCount = state.addable.count { it.user.id in state.selected }
    if (listOpen) ContactListDialog("Contacts à ajouter", checkedCount, onDone = { listOpen = false }) {
        state.matches.forEach { match -> item(key = match.user.id) { MatchRow(match, state, vm) } }
    }
    Box(Modifier.fillMaxSize().background(SnapColors.Background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    val line = GhostLines.addFriends(state.addable.size)
                    GhostSays(line.text, line.mood, Modifier.padding(top = 4.dp, bottom = 16.dp), ghostSize = 76.dp)
                }
                item {
                    SnapSearchBar(state.query, vm::onQuery, "Rechercher un pseudo", Modifier.fillMaxWidth(), onSearch = vm::search)
                    state.searchError?.let { Text(it, color = SnapColors.SnapRed, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                    state.searchResult?.let { result ->
                        SnapCard(Modifier.padding(top = 10.dp)) {
                            FriendRow(result.user.displayName, result.user.username, result.user.pictureUrl) {
                                when (result.relation) {
                                    Relation.NONE, Relation.INCOMING_REQUEST ->
                                        Text("Ajouter", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, modifier = Modifier
                                            .clickable(role = Role.Button, onClickLabel = "envoyer une demande d'ami", onClick = vm::addSearchResult)
                                            .semantics { contentDescription = "Ajouter ${result.user.displayName}" }.padding(8.dp))
                                    Relation.OUTGOING_REQUEST -> Text("Demande envoyée", color = SnapColors.Grey, fontSize = 13.sp)
                                    Relation.FRIENDS -> Text("Ami", color = SnapColors.Grey, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    state.loadingContacts -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                    state.matches.isEmpty() -> item {
                        Text(if (state.contactsGranted == true) "Aucun de tes contacts n'est encore sur SnapCore. Recherche tes amis par leur pseudo."
                            else "Recherche tes amis par leur pseudo.", color = SnapColors.Grey, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp))
                    }
                    screenReader -> item {
                        ContactListButton(
                            "Contacts déjà sur SnapCore : ${state.matches.size}, dont $checkedCount ${if (checkedCount > 1) "cochés" else "coché"} pour une demande d'ami.",
                        ) { listOpen = true }
                    }
                    else -> item {
                        SnapCard { state.matches.forEach { match -> MatchRow(match, state, vm) } }
                    }
                }
            }
        }
        val count = state.addable.count { it.user.id in state.selected }
        if (count > 0) {
            PrimaryButton(if (count == 1) "Ajouter 1 ami" else "Ajouter $count amis", vm::addSelected, height = 50.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 48.dp, vertical = 16.dp))
        } else if (!state.loadingContacts) {
            PrimaryButton("Continuer", vm::skipAddFriends, height = 50.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 48.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun MatchRow(match: com.snapcore.app.domain.ContactMatch, state: OnboardingState, vm: OnboardingViewModel) {
    val user = match.user
    FriendRow(user.displayName, user.username, user.pictureUrl, subtitle = "Dans mes contacts",
        onClick = { vm.toggle(user.id) }.takeIf { match in state.addable },
        checked = (user.id in state.selected).takeIf { match in state.addable }) {
        when (match.relation) {
            Relation.FRIENDS -> Text("Ami", color = SnapColors.Grey, fontSize = 13.sp)
            Relation.OUTGOING_REQUEST -> Text("Demande envoyée", color = SnapColors.Grey, fontSize = 13.sp)
            else -> SnapCheckbox(user.id in state.selected)
        }
    }
}

/**
 * Avec TalkBack, une longue liste de contacts obligeait à passer chaque contact pour atteindre « Passer » ou
 * « Continuer ». La liste devient un seul bouton qui la résume (« 12 contacts, dont 3 cochés ») : un double
 * appui l'ouvre dans sa propre fenêtre ; sinon, on passe directement à la suite de l'écran (ADR-033).
 */
@Composable
private fun ContactListButton(summary: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
        .clickable(role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics {
            contentDescription = summary
            role = Role.Button
            onClick("parcourir et choisir les contacts") { onOpen(); true }
        }
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, fontSize = 16.sp, color = SnapColors.Ink, modifier = Modifier.weight(1f))
        Text("Choisir", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** La liste de contacts ouverte depuis [ContactListButton] : plein écran, avec « Terminé » en haut et en bas. */
@Composable
private fun ContactListDialog(title: String, checkedCount: Int, onDone: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SnapColors.Background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    modifier = Modifier.weight(1f).semantics { heading() }.screenReaderFocusOnAppear())
                Text("Terminé", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDone).padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) { content() }
            PrimaryButton(if (checkedCount == 0) "Terminé" else "Terminé, $checkedCount ${if (checkedCount > 1) "cochés" else "coché"}",
                onDone, height = 50.dp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun FriendRow(name: String, username: String?, picture: String?, subtitle: String? = null, onClick: (() -> Unit)? = null,
    checked: Boolean? = null, trailing: @Composable () -> Unit) {
    // Une ligne = un seul élément pour TalkBack : « Léa, lea.martin, dans mes contacts, coché ».
    val action = when {
        onClick != null && checked != null -> Modifier.toggleable(checked, role = Role.Checkbox) { onClick() }
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier.semantics(mergeDescendants = true) {}
    }
    Row(Modifier.fillMaxWidth().then(action).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(name, picture, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 17.sp, maxLines = 1)
            username?.let { Text(it, fontSize = 13.sp, color = SnapColors.Grey, maxLines = 1) }
            subtitle?.let { Text(it.uppercase(), fontSize = 10.sp, color = SnapColors.Muted, letterSpacing = .5.sp,
                modifier = Modifier.semantics { contentDescription = it }) }
        }
        trailing()
    }
}

// --- Inviter des contacts -----------------------------------------------------------------------

@Composable
private fun InviteStep(state: OnboardingState, vm: OnboardingViewModel, username: String) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val shown = state.invitable.filter { it.name.contains(query, ignoreCase = true) }
    val screenReader = rememberScreenReaderOn()
    var listOpen by remember { mutableStateOf(false) }
    if (listOpen) ContactListDialog("Contacts à inviter", state.invited.size, onDone = { listOpen = false }) {
        shown.forEach { contact -> item(key = contact.name) { InviteRow(contact.name, state, vm) } }
    }
    Box(Modifier.fillMaxSize().background(SnapColors.Background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    GhostSays(GhostLines.invite.text, GhostLines.invite.mood, Modifier.padding(top = 4.dp, bottom = 16.dp), ghostSize = 76.dp)
                    SnapSearchBar(query, { query = it }, "Rechercher", Modifier.fillMaxWidth())
                    Text("Envoie-leur un message pour les inviter sur SnapCore et les ajouter en amis.", fontSize = 13.sp,
                        color = SnapColors.Grey, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                }
                item {
                    if (screenReader) {
                        val checked = state.invited.size
                        ContactListButton("Contacts à inviter : ${shown.size}, dont $checked ${if (checked > 1) "cochés" else "coché"}.") { listOpen = true }
                    } else SnapCard { shown.forEach { contact -> InviteRow(contact.name, state, vm) } }
                }
            }
        }
        val selected = state.invited.isNotEmpty()
        PrimaryButton(
            if (selected) "Envoyer les invitations" else "Passer",
            onClick = {
                if (!selected) vm.skipInvites()
                else {
                    val numbers = vm.sendInvites()
                    // Un seul SMS groupé, rédigé par l'app de messagerie : l'utilisateur garde la main sur l'envoi.
                    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";")))
                        .putExtra("sms_body", "Rejoins-moi sur SnapCore ! Ajoute-moi : $username")
                    runCatching { context.startActivity(sms) }
                }
            },
            container = if (selected) SnapColors.ChatBlue else Color(0xFF4A5059),
            height = 50.dp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 80.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun InviteRow(name: String, state: OnboardingState, vm: OnboardingViewModel) {
    FriendRow(name, null, null, onClick = { vm.toggleInvite(name) }, checked = name in state.invited) {
        SnapCheckbox(name in state.invited)
    }
}

// --- Avatar -------------------------------------------------------------------------------------

@Composable
private fun AvatarStep(state: OnboardingState, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        GhostSays(GhostLines.avatar.text, GhostLines.avatar.mood, Modifier.padding(top = 8.dp), ghostSize = 88.dp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(240.dp).clip(CircleShape).background(SnapColors.Background)
            .clearAndSetSemantics { contentDescription = "Aperçu de ton avatar" }, Alignment.Center) {
            state.avatarPreview?.let { AsyncImage(avatarImageUrl(it, size = 512), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: FigmaAsset("13-241_imgImageGhostLogo")
        }
        Text("Il apparaîtra dans le chat, les stories et sur la carte.", fontSize = 16.sp,
            color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp))
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continuer", onCreate, height = 50.dp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp))
    }
}

// --- Notifications ------------------------------------------------------------------------------

/**
 * Explication avant la demande d'Android (ADR-029) : le fantôme fête la fin du parcours et montre à quoi
 * ressemble une notification. « Plus tard » termine sans rien demander.
 */
@Composable
private fun NotificationsStep(onAnswer: (Boolean) -> Unit, onLater: () -> Unit, onDone: () -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onAnswer)
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GhostSays(GhostLines.notifications.text, GhostLines.notifications.mood, Modifier.padding(top = 8.dp), ghostSize = 96.dp)
        Spacer(Modifier.weight(1f))
        NotificationPreview()
        Spacer(Modifier.weight(1f))
        PrimaryButton("Activer les notifications", {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
        }, height = 50.dp, modifier = Modifier.padding(horizontal = 24.dp))
        Text("Plus tard", color = SnapColors.Grey, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 12.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "terminer sans activer les notifications", onClick = onLater)
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** Aperçu d'une notification, pour montrer ce que l'on active. */
@Composable
private fun NotificationPreview() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SnapColors.Background)
        .border(1.dp, SnapColors.Border, RoundedCornerShape(18.dp))
        .clearAndSetSemantics { contentDescription = "Exemple de notification : SnapCore, un ami t'a envoyé un snap" }
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        FigmaAsset("13-241_imgImageGhostLogo", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("SnapCore", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SnapColors.Ink)
            Text("Un ami t'a envoyé un snap", fontSize = 14.sp, color = SnapColors.Grey)
        }
        Text("maintenant", fontSize = 12.sp, color = SnapColors.Muted)
    }
}

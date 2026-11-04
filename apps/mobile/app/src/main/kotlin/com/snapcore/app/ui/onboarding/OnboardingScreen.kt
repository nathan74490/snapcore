package com.snapcore.app.ui.onboarding

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
import com.snapcore.app.ui.components.SnapHeader
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
        val sim = context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { code -> code.isNotBlank() }
        OnboardingViewModel(it.accounts, it.friends, it.contacts, sim ?: java.util.Locale.getDefault().country, needsNotificationPrompt = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(context) &&
                Notifications.claimFirstPermissionRequest(context)
        }, analytics = it.analytics)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.DONE) onDone() }
    LaunchedEffect(avatarChange) { avatarChange?.let { vm.applyAvatar(it); onAvatarChangeApplied() } }
    // Pas de retour vers l'inscription : le compte existe déjà, la touche retour passe l'écran.
    BackHandler { if (state.step != OnboardingStep.DONE) vm.finish() }

    StatusBarIcons(dark = true)
    Box(Modifier.fillMaxSize().background(Color.White)) {
        when (state.step) {
            OnboardingStep.PHONE -> PhoneStep(state, vm)
            OnboardingStep.FIND_FRIENDS -> FindFriendsStep(vm)
            OnboardingStep.ADD_FRIENDS -> AddFriendsStep(state, vm)
            OnboardingStep.INVITE -> InviteStep(state, vm, username)
            OnboardingStep.AVATAR -> AvatarStep(state, onOpenAvatarEditor, vm::skipAvatar)
            OnboardingStep.NOTIFICATIONS -> NotificationsStep(vm::finish)
            OnboardingStep.DONE -> Unit
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun SkipAction(onSkip: () -> Unit) {
    Text("Passer", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SnapColors.Ink,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSkip).padding(horizontal = 16.dp, vertical = 8.dp))
}

/** En-tête centré sans retour, avec « Passer » à droite (écrans Add Friends, Invite Contacts). */
@Composable
private fun TitleBar(title: String, onSkip: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), modifier = Modifier.align(Alignment.Center))
        onSkip?.let { Box(Modifier.align(Alignment.CenterEnd)) { SkipAction(it) } }
    }
}

// --- Numéro de téléphone ------------------------------------------------------------------------

@Composable
private fun PhoneStep(state: OnboardingState, vm: OnboardingViewModel) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        CountryPicker(onPick = { vm.onCountry(it); picking = false }, onClose = { picking = false })
        BackHandler { picking = false }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        SnapHeader("Ton numéro", onBack = vm::skipPhone, subtitle = "Facultatif") { SkipAction(vm::skipPhone) }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(48.dp))
            FieldLabel("Pays")
            Box(Modifier.clickable { picking = true }) {
                FieldBox {
                    Text("${state.country.flag}  ${state.country.name}", fontSize = 17.sp, color = SnapColors.Ink, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = SnapColors.Muted, modifier = Modifier.padding(end = 12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            FieldLabel("Numéro de téléphone")
            FieldBox(error = state.phoneError != null) {
                Text("+${state.country.dialCode}", fontSize = 17.sp, color = SnapColors.ChatBlue)
                Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(24.dp).background(SnapColors.Hairline))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.national.isEmpty()) Text("Numéro de téléphone", fontSize = 17.sp, color = SnapColors.Muted)
                    BasicTextField(state.national, vm::onNational, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = SnapColors.Ink),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), cursorBrush = SolidColor(SnapColors.ChatBlue),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
            FieldStatus(state.phoneError, ok = false)
            Text("Ton numéro permet à tes amis de te retrouver grâce à leurs contacts. Il n'est jamais montré aux autres et n'est pas vérifié.",
                fontSize = 13.sp, color = SnapColors.Grey, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            PrimaryButton("Continuer", vm::savePhone, enabled = vm.phoneComplete || state.savingPhone, loading = state.savingPhone,
                modifier = Modifier.padding(vertical = 16.dp), height = 46.dp)
        }
    }
}

/** Liste des pays avec recherche et index alphabétique (« Country or Region » de Snapchat). */
@Composable
private fun CountryPicker(onPick: (Country) -> Unit, onClose: () -> Unit) {
    val all = remember { Countries.all() }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { all.filter { it.name.contains(query, ignoreCase = true) || it.dialCode.startsWith(query.trimStart('+')) } }
    val groups = remember(shown) { shown.groupBy { it.name.first().uppercaseChar().let { c -> java.text.Normalizer.normalize("$c", java.text.Normalizer.Form.NFD).first() } } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Position de chaque lettre dans la liste (en-tête + pays), pour l'index de droite.
    val headerIndex = remember(groups) {
        var position = 0
        groups.mapValues { (_, countries) -> position.also { position += countries.size + 1 } }
    }
    Column(Modifier.fillMaxSize().background(SnapColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.Filled.KeyboardArrowDown, "Fermer", tint = SnapColors.Ink) }
            Text("Pays ou région", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), modifier = Modifier.align(Alignment.Center))
        }
        SnapSearchBar(query, { query = it }, "Rechercher", Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp), state = list) {
                groups.forEach { (letter, countries) ->
                    item(key = "h$letter") { Text("$letter", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) }
                    items(countries, key = { it.code }) { country ->
                        Row(Modifier.fillMaxWidth().background(Color.White).clickable { onPick(country) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${country.flag}  ${country.name}", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("+${country.dialCode}", color = SnapColors.Muted, fontSize = 14.sp)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxHeight().padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
                headerIndex.forEach { (letter, index) ->
                    Text("$letter", fontSize = 11.sp, color = SnapColors.Grey,
                        modifier = Modifier.clickable { scope.launch { list.scrollToItem(index) } }.padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
        }
    }
}

// --- Trouver ses amis ---------------------------------------------------------------------------

@Composable
private fun FindFriendsStep(vm: OnboardingViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onContactsPermission)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) { Box(Modifier.align(Alignment.CenterEnd)) { SkipAction(vm::skipFindFriends) } }
        Text("Trouve tous tes amis", fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text("Autorise l'accès à tes contacts : SnapCore te montrera ceux qui ont déjà un compte, pour les ajouter d'un appui. " +
            "Seuls les numéros servent à la recherche, rien n'est conservé.",
            fontSize = 16.sp, color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) { ContactsIllustration() }
        PrimaryButton("Continuer", { permission.launch(Manifest.permission.READ_CONTACTS) }, container = SnapColors.Yellow,
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
    Box(Modifier.fillMaxSize().background(SnapColors.Background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            TitleBar("Ajouter des amis", vm::skipAddFriends)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    SnapSearchBar(state.query, vm::onQuery, "Rechercher un pseudo", Modifier.fillMaxWidth(), onSearch = vm::search)
                    state.searchError?.let { Text(it, color = SnapColors.SnapRed, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                    state.searchResult?.let { result ->
                        SnapCard(Modifier.padding(top = 10.dp)) {
                            FriendRow(result.user.displayName, result.user.username, result.user.pictureUrl) {
                                when (result.relation) {
                                    Relation.NONE, Relation.INCOMING_REQUEST ->
                                        Text("Ajouter", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = vm::addSearchResult).padding(8.dp))
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
                    else -> item {
                        SnapCard {
                            state.matches.forEach { match ->
                                val user = match.user
                                FriendRow(user.displayName, user.username, user.pictureUrl, subtitle = "Dans mes contacts",
                                    onClick = { vm.toggle(user.id) }.takeIf { match in state.addable }) {
                                    when (match.relation) {
                                        Relation.FRIENDS -> Text("Ami", color = SnapColors.Grey, fontSize = 13.sp)
                                        Relation.OUTGOING_REQUEST -> Text("Demande envoyée", color = SnapColors.Grey, fontSize = 13.sp)
                                        else -> SnapCheckbox(user.id in state.selected)
                                    }
                                }
                            }
                        }
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
private fun FriendRow(name: String, username: String?, picture: String?, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(name, picture, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 17.sp, maxLines = 1)
            username?.let { Text(it, fontSize = 13.sp, color = SnapColors.Grey, maxLines = 1) }
            subtitle?.let { Text(it.uppercase(), fontSize = 10.sp, color = SnapColors.Muted, letterSpacing = .5.sp) }
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
    Box(Modifier.fillMaxSize().background(SnapColors.Background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            TitleBar("Inviter des contacts", vm::skipInvites)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    SnapSearchBar(query, { query = it }, "Rechercher", Modifier.fillMaxWidth())
                    Text("Invite tes amis", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                    Text("Envoie-leur un message pour les inviter sur SnapCore et les ajouter en amis.", fontSize = 13.sp,
                        color = SnapColors.Grey, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                }
                item {
                    SnapCard {
                        shown.forEach { contact ->
                            FriendRow(contact.name, null, null, onClick = { vm.toggleInvite(contact.name) }) { SnapCheckbox(contact.name in state.invited) }
                        }
                    }
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

// --- Avatar -------------------------------------------------------------------------------------

@Composable
private fun AvatarStep(state: OnboardingState, onCreate: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) { Box(Modifier.align(Alignment.CenterEnd)) { SkipAction(onSkip) } }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(240.dp).clip(CircleShape).background(SnapColors.Background), Alignment.Center) {
            state.avatarPreview?.let { AsyncImage(avatarImageUrl(it, size = 512), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: FigmaAsset("13-241_imgImageGhostLogo")
        }
        Text("Crée ton avatar", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp))
        Text("Compose le personnage qui te représentera auprès de tes amis, dans le chat et les stories.", fontSize = 16.sp,
            color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continuer", onCreate, height = 50.dp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp))
    }
}

// --- Notifications ------------------------------------------------------------------------------

/** Explication avant la demande d'Android, comme la carte « Please turn on Notifications » de Snapchat. */
@Composable
private fun NotificationsStep(onDone: () -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    Box(Modifier.fillMaxSize().background(Color.White), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 56.dp)) {
            FigmaAsset("13-241_imgImageGhostLogo", modifier = Modifier.size(72.dp).padding(bottom = 8.dp), tint = SnapColors.Ink)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, SnapColors.Border, RoundedCornerShape(14.dp)),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Active les notifications", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
                Text("Tu pourras voir les snaps et les chats que tes amis t'envoient, directement sur ton téléphone.", fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(SnapColors.Border))
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDone), Alignment.Center) {
                        Text("Ne pas autoriser", color = SnapColors.ChatBlue, fontSize = 16.sp)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(SnapColors.Border))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
                    }, Alignment.Center) {
                        Text("OK", color = SnapColors.ChatBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

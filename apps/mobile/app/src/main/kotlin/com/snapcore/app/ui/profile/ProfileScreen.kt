package com.snapcore.app.ui.profile

import com.snapcore.app.ui.components.screenReaderFocusOnAppear
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.SnapCorePlus
import com.snapcore.app.push.Notifications
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.ChipButton
import com.snapcore.app.ui.components.ChipStyle
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.RoundAction
import com.snapcore.app.ui.components.SnapHeader
import com.snapcore.app.ui.components.SnapToggle
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onAccountChanged: (com.snapcore.app.domain.Account) -> Unit,
    onOpenPlus: () -> Unit,
    onOpenMyStory: () -> Unit,
    onOpenFriends: () -> Unit,
    onNewStory: () -> Unit = {},
    onOpenAvatar: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
    /** Compte déjà connu de l'app, affiché sans attendre le rechargement. */
    initialAccount: com.snapcore.app.domain.Account? = null,
    /** Avatar choisi dans l'éditeur, à afficher puis enregistrer (interface optimiste). */
    avatarChange: AvatarChange? = null,
    onAvatarChangeApplied: () -> Unit = {},
) {
    val vm = containerViewModel {
        ProfileViewModel(it.auth, it.accounts, it.pendingRegistration, it.devices, it.mediaUploader, it.profilePhotoPreparer, initialAccount, analytics = it.analytics, experiments = it.experiments)
    }
    LaunchedEffect(avatarChange) {
        avatarChange?.let {
            vm.applyAvatar(it)
            onAvatarChangeApplied()
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
    LaunchedEffect(state.account) { state.account?.let(onAccountChanged) }
    // Retour du paywall : l'abonnement a pu changer (ADR-025).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }


    var settings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settings) { settings = false }
    StatusBarIcons(dark = settings)
    Scaffold(
        containerColor = SnapColors.Background,
        topBar = { if(settings) SnapHeader("Paramètres", { settings = false }, background = SnapColors.Background) },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val account = state.account
        if(account == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                if(state.loading) CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Chargement du profil" }) else TextButton(onClick = vm::refresh) { Text("Réessayer") }
            }
            return@Scaffold
        }
        if(settings) {
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFF7E7A6), Color(0xFFEFC46E))))
                    .clickable(role = Role.Button, onClickLabel = "voir l'offre SnapCore+") { vm.onPlusOpened("settings"); onOpenPlus() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FigmaAsset("17-1408_imgIconGhost", tint = SnapColors.Ink)
                    Text("Gérer SnapCore+", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FigmaAsset("17-1408_imgIconChevronRight", tint = SnapColors.Ink)
                }
                // TalkBack : titre de section, lu « Mon compte » (et non lettre par lettre).
                Text("MON COMPTE", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp)
                    .semantics { heading(); contentDescription = "Mon compte" })
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    ProfileRow("Prénom", account.firstName ?: "Non renseigné", onEdit = { vm.startEditing(EditableField.FIRST_NAME) })
                    HorizontalDivider(color = SnapColors.Border)
                    ProfileRow("Nom", account.lastName ?: "Non renseigné", onEdit = { vm.startEditing(EditableField.LAST_NAME) })
                    HorizontalDivider(color = SnapColors.Border)
                    ProfileRow("Pseudo", account.username, onEdit = { vm.startEditing(EditableField.USERNAME) })
                    HorizontalDivider(color = SnapColors.Border)
                    // Compte créé par code SMS (ADR-034) : pas d'email, et le numéro vérifié sert à se connecter, il ne se
                    // modifie donc pas ici. Comptes créés avec un email : comme avant.
                    val phoneAccount = account.email == null
                    if (!phoneAccount) {
                        // Email non vérifié : la ligne entière passe en rouge (maquette 17:1408), un appui renvoie le lien.
                        if (state.emailVerified == false) {
                            ProfileRow("Email", account.email ?: "Non renseigné", onEdit = vm::resendVerification, alert = true,
                                spoken = "non vérifié", actionLabel = "renvoyer le lien de vérification")
                        } else {
                            ProfileRow("Email", account.email ?: "Non renseigné", spoken = if (state.emailVerified == true) "vérifié" else null) {
                                if (state.emailVerified == true) Icon(Icons.Filled.Verified, "Email vérifié", tint = SnapColors.ChatBlue)
                            }
                        }
                        HorizontalDivider(color = SnapColors.Border)
                    }
                    if (phoneAccount) ProfileRow("Téléphone", account.phoneNumber ?: "Non renseigné", spoken = "vérifié, sert à te connecter") {
                        Icon(Icons.Filled.Verified, "Numéro vérifié", tint = SnapColors.ChatBlue)
                    }
                    else ProfileRow("Téléphone", account.phoneNumber ?: "Non renseigné", onEdit = { vm.startEditing(EditableField.PHONE) })
                    HorizontalDivider(color = SnapColors.Border)
                    ProfileRow("SnapCore+", if(account.isPremium) "Abonnement actif" else SnapCorePlus.PRICE_LABEL, onEdit = { vm.onPlusOpened("settings"); onOpenPlus() },
                        actionLabel = if (account.isPremium) "gérer l'abonnement" else "voir l'offre")
                    HorizontalDivider(color = SnapColors.Border)
                    // La permission n'est demandée qu'une fois (BUG-007) : on la réactive ici, dans Android.
                    val context = LocalContext.current
                    val notificationsOn = Notifications.canNotify(context)
                    // Toggle 9:101 (vert) qui reflète l'état réel ; un appui ouvre les réglages Android.
                    ProfileRow(
                        "Notifications",
                        if (notificationsOn) "Activées" else "Désactivées",
                        onTap = { context.startActivity(Notifications.settingsIntent(context)) },
                        actionLabel = "ouvrir les réglages de notifications d'Android",
                    ) { SnapToggle(notificationsOn) }
                    HorizontalDivider(color = SnapColors.Border)
                    // Seul endroit d'où retrouver quelqu'un qu'on a bloqué (ADR-012, BUG-019 d'ADR-023).
                    ProfileRow("Utilisateurs bloqués", "Gérer les personnes bloquées", onTap = onOpenBlocked, actionLabel = "ouvrir la liste")
                }
                Spacer(Modifier.height(32.dp))
                PrimaryButton("Se déconnecter", vm::signOut, container = Color.White, content = SnapColors.Ink)
                TextButton(onClick = vm::askDeletion, enabled = !state.saving, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Supprimer mon compte", color = SnapColors.SnapRed)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(290.dp).background(Brush.verticalGradient(
                    0f to Color(0xFF935499), .16f to Color(0xFF944F99), .31f to Color(0xFFBE63C4),
                    .52f to Color(0xFFF67AFF), .73f to Color(0xFFF274FA), .9f to Color(0xFFCD5FCF)))) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp)) {
                        // Comme le Bitmoji de Snapchat : l'avatar maison s'affiche en grand s'il existe (ADR-028).
                        EditableAvatar(account.displayName, account.pictureUrl, vm::changePhoto, vm::removePhoto)
                    }
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 15.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundAction("17-1301_imgIconChevronLeft", "Retour", onBack, dark = true)
                        RoundAction("17-1301_imgIconSettings", "Paramètres", { settings = true }, dark = true)
                    }
                }
                Column(Modifier.offset(y = (-20).dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(SnapColors.Background).padding(horizontal = 16.dp).navigationBarsPadding()) {
                    Box(Modifier.padding(top = 8.dp).size(36.dp, 5.dp).align(Alignment.CenterHorizontally).background(Color(0xFFC7C7CC), RoundedCornerShape(3.dp)))
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(account.displayName, account.pictureUrl, 75.dp)
                        // TalkBack : nom et pseudo lus ensemble, comme titre de l'écran.
                        Column(Modifier.semantics(mergeDescendants = true) { heading() }.screenReaderFocusOnAppear()) {
                            Text(account.displayName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("@${account.username}", color = SnapColors.Grey, fontSize = 16.sp)
                        }
                    }
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Badge : équivalent texte, sans le symbole « ✦ ».
                        Text(if(account.isPremium) "✦ SnapCore+" else "SnapCore", modifier = Modifier
                            .clearAndSetSemantics { contentDescription = if (account.isPremium) "Badge SnapCore+ : abonnement actif" else "Compte SnapCore gratuit" }
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White).border(1.dp, Color(0xFFE3E5E8), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
                            color = if(account.isPremium) SnapColors.SnapPurple else SnapColors.Ink)
                    }
                    if (!account.isPremium) LaunchedEffect(Unit) { vm.onPlusCardShown() }
                    PlusCard(account, state.plusCard) { vm.onPlusOpened("profile_card"); onOpenPlus() }
                    Text("Mon avatar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).semantics { heading() })
                    ProfileAction("13-250_imgIconEmoji", if (account.avatar == null) "Créer mon avatar" else "Modifier mon avatar", onOpenAvatar)
                    // Comme Snapchat : « + Nouvelle Story » à côté du titre ouvre la caméra.
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Mes Stories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                        // TalkBack : « Nouvelle story », sans le « + » ; un seul bouton.
                        Box(Modifier.semantics(mergeDescendants = true) {}.clearAndSetSemantics {
                            contentDescription = "Nouvelle story"
                            role = Role.Button
                            onClick("ouvrir la caméra") { onNewStory(); true }
                        }) { ChipButton("+ Nouvelle Story", onNewStory, style = ChipStyle.Gray) }
                    }
                    ProfileAction("17-1301_imgIconCamera", "Ma story · Mes amis", onOpenMyStory, spoken = "Ma story, visible par mes amis")
                    Text("Amis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp).semantics { heading() })
                    ProfileAction("17-1301_imgIconAddFriend", "Ajouter des amis", onOpenFriends)
                    Spacer(Modifier.height(12.dp))
                    ProfileAction("17-1301_imgIconSettings", "Paramètres du compte", { settings = true })
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    state.editing?.let { field ->
        AlertDialog(
            onDismissRequest = vm::cancelEditing,
            title = { Text(field.title, Modifier.semantics { heading() }) },
            text = {
                val draftError = state.draftError
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::onDraft,
                    singleLine = true,
                    isError = draftError != null,
                    // TalkBack : l'erreur est annoncée dès qu'elle apparaît, et lue avec le champ.
                    supportingText = draftError?.let { { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
                    modifier = Modifier.semantics {
                        contentDescription = when (field) {
                            EditableField.FIRST_NAME -> "Prénom"
                            EditableField.LAST_NAME -> "Nom"
                            EditableField.USERNAME -> "Pseudo"
                            EditableField.PHONE -> "Téléphone"
                        }
                        if (draftError != null) error(draftError)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = if (field == EditableField.PHONE) KeyboardType.Phone else KeyboardType.Text,
                        capitalization = if (field == EditableField.FIRST_NAME || field == EditableField.LAST_NAME) KeyboardCapitalization.Words else KeyboardCapitalization.None),
                )
            },
            confirmButton = { TextButton(onClick = vm::saveEditing) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::cancelEditing) { Text("Annuler") } },
        )
    }

    if (state.confirmDeletion) {
        AlertDialog(
            onDismissRequest = vm::cancelDeletion,
            title = { Text("Supprimer ton compte ?", Modifier.semantics { heading() }) },
            text = { Text("Ton compte, tes messages, tes snaps et tes stories seront définitivement supprimés.") },
            confirmButton = { TextButton(onClick = vm::deleteAccount) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancelDeletion) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ProfileRow(
    label: String,
    value: String,
    onEdit: (() -> Unit)? = null,
    alert: Boolean = false,
    onTap: (() -> Unit)? = onEdit,
    /** Précision lue par TalkBack après la valeur (« vérifié »…), à la place des icônes. */
    spoken: String? = null,
    /** Action annoncée par TalkBack : « double appui pour <actionLabel> ». */
    actionLabel: String = "modifier",
    trailing: @Composable () -> Unit = {},
) {
    // TalkBack : la ligne entière est un seul élément, « Prénom : Nathan, double appui pour modifier ».
    val description = "$label : $value" + (spoken?.let { ", $it" } ?: "")
    val a11y = if (onTap != null) Modifier.clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        onClick(actionLabel) { onTap(); true }
    } else Modifier.clearAndSetSemantics { contentDescription = description }
    // Settings Row 11:304 : 57pt min, marges 24 / 16, titre et sous-titre espacés de 4.
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier).then(a11y).heightIn(min = 57.dp).padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = if (alert) SnapColors.SnapRed else SnapColors.Ink, style = MaterialTheme.typography.bodyLarge)
            Text(value, color = if (alert) SnapColors.SnapRed else SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
        }
        trailing()
        if (onEdit != null) FigmaAsset("17-1408_imgIconChevronRight", "Modifier $label")
    }
}

// Avatar du profil : un appui propose de choisir une photo (sélecteur de photos Android, aucune
// permission requise : l'app n'accède qu'à l'image choisie) ou de revenir à l'avatar par défaut.
@Composable
private fun EditableAvatar(
    username: String,
    photoUrl: String?,
    onPicked: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPicked(it.toString()) }
    }
    Box {
        Box(Modifier.clickable(role = Role.Button, onClickLabel = "choisir ou retirer une photo") { menuOpen = true }
            .semantics { contentDescription = "Changer ta photo de profil" }, contentAlignment = Alignment.Center) {
            Avatar(username, photoUrl, size = 160.dp)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(32.dp).background(SnapColors.Yellow, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = SnapColors.Black, modifier = Modifier.size(18.dp)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Choisir une photo") },
                onClick = {
                    menuOpen = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            if (photoUrl != null) {
                DropdownMenuItem(
                    text = { Text("Retirer la photo") },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

// Accès au paywall SnapCore+ : l'offre pour un compte gratuit, la date de fin pour un abonné (ADR-025).
@Composable
private fun PlusCard(account: com.snapcore.app.domain.Account, offer: com.snapcore.app.domain.ProfilePlusCard, onOpen: () -> Unit) {
    val until = account.premiumUntil?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))

    // TalkBack : carte lue en une fois, « SnapCore+, <offre>, double appui pour voir l'offre ».
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
        .border(1.dp, SnapColors.Gold, RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClickLabel = if (account.isPremium) "gérer l'abonnement" else "voir l'offre", onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1710)), Alignment.Center) {
            FigmaAsset("14-704_imgImageGoldGhost", modifier = Modifier.size(48.dp, 49.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("SnapCore+", fontSize = 18.sp)
            Text(when {
                account.isPremium && until != null -> "Actif jusqu'au $until"
                account.isPremium -> "Abonnement actif"
                else -> offer.subtitle
            }, color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
        }
        FigmaAsset("17-1301_imgIconChevronRight")
    }
}

@Composable
private fun ProfileAction(icon: String, title: String, click: () -> Unit, spoken: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
        .clickable(role = Role.Button, onClick = click)
        .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken; role = Role.Button; onClick { click(); true } } else Modifier)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FigmaAsset(icon, tint = SnapColors.ChatBlue)
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        FigmaAsset("17-1301_imgIconChevronRight")
    }
}

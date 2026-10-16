package com.snapcore.app.ui.profile

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
) {
    val vm = containerViewModel { ProfileViewModel(it.auth, it.accounts, it.pendingRegistration, it.devices, it.mediaUploader, it.profilePhotoPreparer) }
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
                if(state.loading) CircularProgressIndicator() else TextButton(onClick = vm::refresh) { Text("Réessayer") }
            }
            return@Scaffold
        }
        if(settings) {
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFF7E7A6), Color(0xFFEFC46E))))
                    .clickable(onClick = onOpenPlus).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FigmaAsset("17-1408_imgIconGhost", tint = SnapColors.Ink)
                    Text("Gérer SnapCore+", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FigmaAsset("17-1408_imgIconChevronRight", tint = SnapColors.Ink)
                }
                Text("MON COMPTE", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp))
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    ProfileRow("Prénom", account.firstName ?: "Non renseigné", onEdit = { vm.startEditing(EditableField.FIRST_NAME) })
                    HorizontalDivider(color = SnapColors.Border)
                    ProfileRow("Pseudo", account.username, onEdit = { vm.startEditing(EditableField.USERNAME) })
                    HorizontalDivider(color = SnapColors.Border)
                    // Email non vérifié : la ligne entière passe en rouge (maquette 17:1408), un appui renvoie le lien.
                    if (state.emailVerified == false) {
                        ProfileRow("Email", account.email ?: "Non renseigné", onEdit = vm::resendVerification, alert = true)
                    } else {
                        ProfileRow("Email", account.email ?: "Non renseigné") {
                            if (state.emailVerified == true) Icon(Icons.Filled.Verified, "Email vérifié", tint = SnapColors.ChatBlue)
                        }
                    }
                    HorizontalDivider(color = SnapColors.Border)
                    ProfileRow("Téléphone", account.phoneNumber ?: "Non renseigné", onEdit = { vm.startEditing(EditableField.PHONE) })
                    HorizontalDivider(color = SnapColors.Border)
                    ProfileRow("SnapCore+", if(account.isPremium) "Abonnement actif" else SnapCorePlus.PRICE_LABEL, onEdit = onOpenPlus)
                    HorizontalDivider(color = SnapColors.Border)
                    // La permission n'est demandée qu'une fois (BUG-007) : on la réactive ici, dans Android.
                    val context = LocalContext.current
                    val notificationsOn = Notifications.canNotify(context)
                    // Toggle 9:101 (vert) qui reflète l'état réel ; un appui ouvre les réglages Android.
                    ProfileRow(
                        "Notifications",
                        if (notificationsOn) "Activées" else "Désactivées",
                        onTap = { context.startActivity(Notifications.settingsIntent(context)) },
                    ) { SnapToggle(notificationsOn) }
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
                        EditableAvatar(account.displayName, account.profilePhotoUrl, state.photoBusy, vm::changePhoto, vm::removePhoto)
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
                        Avatar(account.displayName, account.profilePhotoUrl, 75.dp)
                        Column {
                            Text(account.displayName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("@${account.username}", color = SnapColors.Grey, fontSize = 16.sp)
                        }
                    }
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if(account.isPremium) "✦ SnapCore+" else "SnapCore", modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(Color.White).border(1.dp, Color(0xFFE3E5E8), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
                            color = if(account.isPremium) SnapColors.SnapPurple else SnapColors.Ink)
                    }
                    PlusCard(account, onOpenPlus)
                    // Comme Snapchat : « + Nouvelle Story » à côté du titre ouvre la caméra.
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Mes Stories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        ChipButton("+ Nouvelle Story", onNewStory, style = ChipStyle.Gray)
                    }
                    ProfileAction("17-1301_imgIconCamera", "Ma story · Mes amis", onOpenMyStory)
                    Text("Amis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
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
            title = { Text(field.title) },
            text = {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::onDraft,
                    singleLine = true,
                    isError = state.draftError != null,
                    supportingText = state.draftError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = if (field == EditableField.PHONE) KeyboardType.Phone else KeyboardType.Text,
                        capitalization = if (field == EditableField.FIRST_NAME) KeyboardCapitalization.Words else KeyboardCapitalization.None),
                )
            },
            confirmButton = { TextButton(onClick = vm::saveEditing, enabled = !state.saving) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::cancelEditing) { Text("Annuler") } },
        )
    }

    if (state.confirmDeletion) {
        AlertDialog(
            onDismissRequest = vm::cancelDeletion,
            title = { Text("Supprimer ton compte ?") },
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
    trailing: @Composable () -> Unit = {},
) {
    // Settings Row 11:304 : 57pt min, marges 24 / 16, titre et sous-titre espacés de 4.
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier).heightIn(min = 57.dp).padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
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
    busy: Boolean,
    onPicked: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPicked(it.toString()) }
    }
    Box {
        Box(Modifier.clickable(enabled = !busy) { menuOpen = true }, contentAlignment = Alignment.Center) {
            Avatar(username, photoUrl, size = 160.dp)
            if (busy) CircularProgressIndicator(color = Color.White)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(32.dp).background(SnapColors.Yellow, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.CameraAlt, contentDescription = "Changer la photo", tint = SnapColors.Black, modifier = Modifier.size(18.dp)) }
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
private fun PlusCard(account: com.snapcore.app.domain.Account, onOpen: () -> Unit) {
    val until = account.premiumUntil?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))

    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
        .border(1.dp, SnapColors.Gold, RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1710)), Alignment.Center) {
            FigmaAsset("14-704_imgImageGoldGhost", modifier = Modifier.size(48.dp, 49.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("SnapCore+", fontSize = 18.sp)
            Text(when {
                account.isPremium && until != null -> "Actif jusqu'au $until"
                account.isPremium -> "Abonnement actif"
                else -> "Des relectures illimitées et des snaps conservés."
            }, color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
        }
        FigmaAsset("17-1301_imgIconChevronRight")
    }
}

@Composable
private fun ProfileAction(icon: String, title: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
        .clickable(onClick = click).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FigmaAsset(icon, tint = SnapColors.ChatBlue)
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        FigmaAsset("17-1301_imgIconChevronRight")
    }
}

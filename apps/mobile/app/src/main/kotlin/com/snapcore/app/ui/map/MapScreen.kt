package com.snapcore.app.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import androidx.compose.ui.platform.LocalDensity
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.viewannotation.annotationAnchor
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import com.snapcore.app.BuildConfig
import com.snapcore.app.domain.*
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.delay

private val RefreshInterval = 30_000L
private const val CityZoom = 13.0
private const val WorldZoom = 1.5
private val Ghost = "👻"

/**
 * Onglet Map (ADR-031) : mes amis qui partagent leur position, leurs stories épinglées là où elles ont été
 * publiées, et ma propre position (visible de moi seul en mode fantôme).
 */
@Composable
fun MapScreen(account: Account, visible: Boolean, onOpenProfile: () -> Unit, onOpenFriends: () -> Unit,
    onOpenConversation: (Friend) -> Unit, onPlayStory: (FriendStory) -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { MapViewModel(account.id, it.map, it.locationReporter, it.location, it.friends, it.analytics) }
    val state by vm.state.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onPermissionResult(result.values.any { it })
    }
    // Rechargement périodique, seulement tant que l'onglet est affiché.
    LaunchedEffect(visible) {
        while (visible) {
            vm.refresh()
            delay(RefreshInterval)
        }
    }
    val viewport = rememberMapViewportState {
        setCameraOptions { center(Point.fromLngLat(2.35, 46.6)); zoom(4.5) }
    }
    // Premier cadrage : sur moi dès que ma position est connue. Sans elle (localisation non activée), sur mes
    // amis et leurs stories, puis sur moi si j'active la localisation plus tard. Ensuite, c'est l'utilisateur
    // qui déplace la carte.
    var centeredOnMe by rememberSaveable { mutableStateOf(false) }
    var centeredOnFriends by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.me, state.friends, state.pins) {
        val me = state.me
        if (me != null) {
            if (!centeredOnMe) { viewport.flyToPoint(me, CityZoom); centeredOnMe = true; centeredOnFriends = true }
        } else if (!centeredOnFriends) {
            val focus = focusOn(state.friends.map { it.position } + state.pins.map { it.position }) ?: return@LaunchedEffect
            viewport.flyToPoint(focus.center, focus.zoom)
            centeredOnFriends = true
        }
    }
    val slots = remember(state.pins, state.friends) { pinSlots(state.pins, state.friends) }
    val density = LocalDensity.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var turnOffGhost by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFFE8ECEF))) {
        if (BuildConfig.MAPBOX_CONFIGURED) {
            MapboxMap(
                Modifier.fillMaxSize(),
                mapViewportState = viewport,
                style = { MapStyle(style = Style.STANDARD) },
                scaleBar = {},
                onMapClickListener = { vm.select(null); false },
            ) {
                state.pins.forEachIndexed { index, pin ->
                    val (dx, dy) = with(density) { pinOffset(slots[index]).let { (x, y) -> x.toPx() to y.toPx() } }
                    ViewAnnotation(options = viewAnnotationOptions {
                        geometry(pin.position.toPoint())
                        allowOverlap(true)
                        annotationAnchor { anchor(ViewAnnotationAnchor.CENTER); offsetX(dx.toDouble()); offsetY(dy.toDouble()) }
                    }) { StoryPinMarker(pin) { onPlayStory(pin.asFriendStory()) } }
                }
                state.friends.forEach { friend ->
                    ViewAnnotation(options = viewAnnotationOptions {
                        geometry(friend.position.toPoint())
                        allowOverlap(true)
                    }) { FriendMarker(friend.friend.displayName, friend.friend.pictureUrl, friend.friend.displayName, selected = state.selected?.friend?.id == friend.friend.id) { vm.select(friend) } }
                }
                state.me?.let { me ->
                    ViewAnnotation(options = viewAnnotationOptions {
                        geometry(me.toPoint())
                        allowOverlap(true)
                    }) {
                        val ghost = state.sharing?.ghost != false
                        FriendMarker(account.displayName, account.pictureUrl, if (ghost) "$Ghost Moi" else "Moi", mine = true,
                            modifier = Modifier.alpha(if (ghost) .75f else 1f)) { settingsOpen = true }
                    }
                }
            }
        } else {
            Text("Carte indisponible : ajoute ton jeton public Mapbox (mapboxAccessToken=pk.…) dans local.properties.",
                Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center, color = SnapColors.Grey)
        }

        MapHeader(account, state.sharing, onOpenProfile, onOpenFriends) { settingsOpen = true; vm.loadFriends() }

        // Marge du bas : le logo et l'attribution Mapbox doivent rester visibles (conditions d'utilisation).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FloatingRound("Me recentrer", Icons.Filled.MyLocation) {
                    val me = state.me
                    if (me != null) viewport.flyToPoint(me, CityZoom)
                    else if (!state.permission) permissions.launch(LocationPermissions)
                    else vm.refresh()
                }
            }
            val selected = state.selected
            when {
                selected != null -> FriendCard(selected, state.now, onChat = { onOpenConversation(selected.friend) },
                    onLocate = { viewport.flyToPoint(selected.position, 15.0) }, onClose = { vm.select(null) })
                !state.permission -> MapBanner("Active ta localisation",
                    "Pour te situer sur la carte, et la partager avec tes amis si tu le souhaites. Seulement quand SnapCore est ouvert.",
                    "Activer") { permissions.launch(LocationPermissions) }
                state.sharing?.ghost == true -> MapBanner("$Ghost Tu es en mode fantôme",
                    "Tes amis ne voient pas ta position.", "Partager ma position") { turnOffGhost = true; vm.loadFriends() }
                state.friends.isEmpty() && state.pins.isEmpty() && state.sharing != null -> MapBanner("Aucun ami sur la carte",
                    "Tes amis apparaissent ici quand ils partagent leur position avec toi.", null) {}
            }
        }
    }

    if (settingsOpen) MapSettingsSheet(
        sharing = state.sharing ?: LocationSharing.Ghost,
        friends = state.allFriends,
        onChange = vm::updateSharing,
        onTurnOffGhost = { turnOffGhost = true },
        onDismiss = { settingsOpen = false },
    )
    if (turnOffGhost) TurnOffGhostDialog(
        sharing = state.sharing ?: LocationSharing.Ghost,
        friends = state.allFriends,
        onConfirm = { vm.updateSharing(it); turnOffGhost = false },
        onDismiss = { turnOffGhost = false },
    )
    state.error?.let { error ->
        AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } }, text = { Text(error) })
    }
}

val LocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * Décalage d'un pin de story selon son rang ([pinSlots]) : 0 sur son point, puis en cercle autour, en
 * commençant en haut à droite (6 places par tour, un tour plus large ensuite). Mapbox compte offsetY vers le haut.
 */
private fun pinOffset(slot: Int): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    if (slot <= 0) return 0.dp to 0.dp
    val ring = (slot - 1) / 6 + 1
    val angle = Math.toRadians(40.0 + ((slot - 1) % 6) * 60.0)
    val radius = 58.0 * ring
    return (radius * kotlin.math.cos(angle)).dp to (radius * kotlin.math.sin(angle)).dp
}

private fun GeoPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

private fun MapViewportState.flyToPoint(point: GeoPoint, zoom: Double) = flyTo(
    cameraOptions { center(point.toPoint()); zoom(zoom) },
    MapAnimationOptions.mapAnimationOptions { duration(900) },
)

@Composable
private fun MapHeader(account: Account, sharing: LocationSharing?, onOpenProfile: () -> Unit, onOpenFriends: () -> Unit, onOpenSettings: () -> Unit) {
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(account.displayName, account.pictureUrl, 40.dp, Modifier.shadow(4.dp, CircleShape).clickable(onClick = onOpenProfile))
        Spacer(Modifier.width(8.dp))
        FloatingRound("Rechercher des amis", Icons.Filled.Search, size = 40.dp, onClick = onOpenFriends)
        Spacer(Modifier.weight(1f))
        // Comme le lieu courant chez Snapchat : ici, l'état de mon partage, qui ouvre les réglages.
        Surface(onClick = onOpenSettings, shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 4.dp) {
            Text(when {
                sharing == null -> "Snap Map"
                sharing.ghost -> "$Ghost Mode fantôme"
                else -> "📍 ${sharing.summary}"
            }, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SnapColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        FloatingRound("Paramètres de la carte", Icons.Filled.Settings, size = 40.dp, onClick = onOpenSettings)
    }
}

@Composable
private fun FloatingRound(description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = 48.dp, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.White, shadowElevation = 4.dp, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, tint = SnapColors.Ink) }
    }
}

/** Avatar d'un ami (ou le mien) posé sur la carte, avec son prénom dessous. */
@Composable
private fun FriendMarker(name: String, pictureUrl: String?, label: String, selected: Boolean = false, mine: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.clickable(onClick = onClick)) {
        val ring = when { mine -> SnapColors.Yellow; selected -> SnapColors.ChatBlue; else -> Color.White }
        Avatar(name, pictureUrl, if (selected) 60.dp else 52.dp, Modifier.shadow(6.dp, CircleShape).border(3.dp, ring, CircleShape))
        Spacer(Modifier.height(3.dp))
        Text(label, Modifier.shadow(2.dp, RoundedCornerShape(10.dp)).background(Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink, maxLines = 1)
    }
}

/** Story d'un ami épinglée sur la carte : vignette dans un anneau violet tant qu'elle n'est pas vue. */
@Composable
private fun StoryPinMarker(pin: StoryPin, onClick: () -> Unit) {
    val ring = if (pin.hasUnviewed) SnapColors.SnapPurple else SnapColors.Muted
    Box(Modifier.size(50.dp).shadow(6.dp, CircleShape).background(Color.White, CircleShape).border(3.dp, ring, CircleShape).padding(5.dp).clip(CircleShape).clickable(onClick = onClick)) {
        val thumbnail = pin.thumbnailUrl
        if (thumbnail != null) AsyncImage(thumbnail, "Story de ${pin.author.displayName}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Avatar(pin.author.displayName, pin.author.pictureUrl, 40.dp)
    }
}

@Composable
private fun MapBanner(title: String, text: String, action: String?, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = SnapColors.Ink)
            Text(text, fontSize = 14.sp, color = SnapColors.Grey)
            if (action != null) PrimaryButton(action, onAction, Modifier.padding(top = 6.dp), height = 44.dp)
        }
    }
}

@Composable
private fun FriendCard(location: FriendLocation, now: java.time.Instant, onChat: () -> Unit, onLocate: () -> Unit, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(location.friend.displayName, location.friend.pictureUrl, 52.dp, Modifier.clickable(onClick = onLocate))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(location.friend.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = SnapColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lastSeenLabel(location.updatedAt, now), fontSize = 13.sp, color = SnapColors.Grey)
            }
            Button(onClick = onChat, shape = RoundedCornerShape(20.dp), modifier = Modifier.height(40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SnapColors.ChatBlue, contentColor = Color.White)) {
                Text("Chat", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            }
            TextButton(onClick = onClose) { Text("✕", color = SnapColors.Grey, fontSize = 18.sp) }
        }
    }
}

/** Réglages de la carte : mode fantôme et audience de ma position (captures Snapchat 319 à 322). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSettingsSheet(sharing: LocationSharing, friends: List<Friend>, onChange: (LocationSharing) -> Unit, onTurnOffGhost: () -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf<LocationSharingMode?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        val mode = picking
        if (mode != null) {
            FriendPicker(mode, sharing, friends, onSave = { onChange(it); picking = null }, onBack = { picking = null })
            return@ModalBottomSheet
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Paramètres de la carte", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = SnapColors.Ink)
            Section("Qui peut voir ma position") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mode fantôme", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SnapColors.Ink)
                        Text("Quand il est activé, tes amis ne voient pas ta position, ni où tu publies tes stories.", fontSize = 13.sp, color = SnapColors.Grey)
                    }
                    Switch(checked = sharing.ghost, onCheckedChange = { ghost ->
                        if (ghost) onChange(sharing.copy(mode = LocationSharingMode.GHOST)) else onTurnOffGhost()
                    }, colors = SwitchDefaults.colors(checkedTrackColor = SnapColors.ChatBlue))
                }
                HorizontalDivider(color = SnapColors.Border)
                AudienceOptions(sharing, enabled = !sharing.ghost) { chosen ->
                    if (chosen.usesList) picking = chosen else onChange(sharing.copy(mode = chosen))
                }
            }
            Text("Ta position n'est envoyée que lorsque SnapCore est ouvert, et disparaît de la carte de tes amis après 8 heures sans mise à jour.",
                fontSize = 13.sp, color = SnapColors.Grey)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SnapColors.Grey)
        Surface(shape = RoundedCornerShape(16.dp), color = SnapColors.Background, border = BorderStroke(1.dp, SnapColors.Border)) { Column(content = content) }
    }
}

private val AudienceModes = listOf(LocationSharingMode.ALL_FRIENDS, LocationSharingMode.FRIENDS_EXCEPT, LocationSharingMode.ONLY_FRIENDS)

@Composable
private fun AudienceOptions(sharing: LocationSharing, enabled: Boolean, onChoose: (LocationSharingMode) -> Unit) {
    AudienceModes.forEach { mode ->
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChoose(mode) }.padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else .4f),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mode.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SnapColors.Ink)
                if (enabled && sharing.mode == mode && mode.usesList) Text(sharing.summary, fontSize = 13.sp, color = SnapColors.Grey)
            }
            RadioButton(selected = enabled && sharing.mode == mode, onClick = null, enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = SnapColors.ChatBlue))
        }
    }
}

/** « Désactiver le mode fantôme ? » : on choisit tout de suite avec qui partager (capture 320). */
@Composable
private fun TurnOffGhostDialog(sharing: LocationSharing, friends: List<Friend>, onConfirm: (LocationSharing) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf<LocationSharingMode?>(null) }
    val mode = picking
    if (mode != null) {
        Dialog(onDismiss) { FriendPicker(mode, sharing, friends, onSave = onConfirm, onBack = { picking = null }) }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White,
        title = { Text("Désactiver le mode fantôme ?", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Choisis qui peut voir ta position sur la carte.", color = SnapColors.Grey)
                Spacer(Modifier.height(8.dp))
                AudienceModes.forEach { option ->
                    Text(option.label, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        if (option.usesList) picking = option else onConfirm(sharing.copy(mode = option))
                    }.padding(vertical = 12.dp, horizontal = 4.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SnapColors.ChatBlue)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = SnapColors.Grey) } })
}

@Composable
private fun Dialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) { content() }
    }
}

/** Amis à exclure (« Mes amis sauf… ») ou à autoriser (« Seulement ces amis… »). */
@Composable
private fun FriendPicker(mode: LocationSharingMode, sharing: LocationSharing, friends: List<Friend>, onSave: (LocationSharing) -> Unit, onBack: () -> Unit) {
    // La liste mémorisée ne vaut que pour le mode où elle a été faite.
    var chosen by remember(mode) { mutableStateOf(if (sharing.mode == mode) sharing.friendIds else emptySet()) }
    val candidate = LocationSharing(mode, chosen)
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Retour", color = SnapColors.Grey) }
        }
        Text(mode.label, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = SnapColors.Ink)
        Text(if (mode == LocationSharingMode.FRIENDS_EXCEPT) "Les amis cochés ne verront pas ta position." else "Seuls les amis cochés verront ta position.",
            fontSize = 14.sp, color = SnapColors.Grey, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        if (friends.isEmpty()) Text("Aucun ami pour l'instant.", color = SnapColors.Grey, modifier = Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(friends, key = { it.id }) { friend ->
                val checked = friend.id in chosen
                Row(Modifier.fillMaxWidth().clickable { chosen = if (checked) chosen - friend.id else chosen + friend.id }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(friend.displayName, friend.pictureUrl, 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(friend.displayName, fontWeight = FontWeight.Bold, color = SnapColors.Ink)
                        Text(friend.username, fontSize = 13.sp, color = SnapColors.Grey)
                    }
                    Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = SnapColors.ChatBlue))
                }
            }
        }
        PrimaryButton("Enregistrer", { onSave(candidate) }, Modifier.padding(top = 12.dp), enabled = candidate.valid)
    }
}

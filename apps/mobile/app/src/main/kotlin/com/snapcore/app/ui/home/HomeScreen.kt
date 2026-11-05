package com.snapcore.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.ui.appContainer
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.map.MapScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapcore.app.domain.*
import com.snapcore.app.ui.camera.CameraScreen
import com.snapcore.app.ui.camera.LensModeBar
import com.snapcore.app.ui.chat.ChatListScreen
import com.snapcore.app.ui.components.*
import com.snapcore.app.ui.stories.StoriesScreen
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

// Comme Snapchat : la Snap Map à gauche du Chat (ADR-031).
const val MAP_TAB = 0
const val CHAT_TAB = 1
const val CAMERA_TAB = 2
const val STORIES_TAB = 3
private val labels = listOf("Map", "Chat", "Caméra", "Stories")
private val icons = listOf("Map", "Chat", "Camera", "Stories")

@Composable
fun HomeScreen(account: Account, selected: Int, onSelect: (Int) -> Unit,
    onOpenProfile: () -> Unit, onOpenFriends: () -> Unit, onCaptured: (CapturedMedia) -> Unit,
    onOpenConversation: (Friend, snapId: String?) -> Unit, onOpenMyStory: () -> Unit, onPlayStory: (FriendStory, fromMap: Boolean) -> Unit,
    modifier: Modifier = Modifier, snapRecipient: Friend? = null, onSnapTo: (Friend) -> Unit = {}, onClearSnapRecipient: () -> Unit = {},
    onOpenGroup: (Group, snapId: String?) -> Unit = { _, _ -> }, onNewChat: () -> Unit = onOpenFriends) {
    // Demandes d'ami en attente : le bouton d'ajout passe en jaune avec un badge, comme dans Snapchat.
    val requestsVm = containerViewModel { FriendRequestsViewModel(it.friends, it.realtime.events) }
    val pendingRequests by requestsVm.count.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { requestsVm.refresh(); onPauseOrDispose {} }
    // Ma position part à chaque ouverture de l'app, sauf en mode fantôme ou sans permission (ADR-031).
    val reporter = appContainer().locationReporter
    val reportScope = rememberCoroutineScope()
    LifecycleResumeEffect(account.id) {
        val job = reportScope.launch { reporter.report(account.id) }
        onPauseOrDispose { job.cancel() }
    }
    val pager = rememberPagerState(initialPage = selected, pageCount = { labels.size })
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) {
        if (pager.settledPage != selected) pager.animateScrollToPage(selected, animationSpec = tween(280, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(pager) {
        // La première valeur est la page restaurée : au retour d'un écran, elle écraserait l'onglet
        // demandé entre-temps (bouton appareil photo d'une conversation).
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { select(it) }
    }
    val camera = pager.currentPage == CAMERA_TAB
    val cameraVisible by remember(pager) {
        derivedStateOf { abs(pager.currentPage + pager.currentPageOffsetFraction - CAMERA_TAB) < 1f }
    }
    var lensMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val navBackground by animateColorAsState(if (camera) Color.Black else Color.White, tween(180), label = "navBackground")
    val navInk by animateColorAsState(if (camera) Color.White else SnapColors.Ink, tween(180), label = "navInk")
    StatusBarIcons(dark = !camera)
    Column(modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 2,
            // Le carrousel des Lenses garde ses propres gestes ; fermer la Lens réactive les pages.
            // Sur la carte, les glissements déplacent la carte : on en sort par la barre d'onglets.
            userScrollEnabled = !lensMode && !recording && pager.settledPage != MAP_TAB,
            key = { it },
        ) { page ->
            // La caméra est toujours le fond fixe. Chat et Stories sont des panneaux au-dessus :
            // ils recouvrent la caméra à l'ouverture et la découvrent au retour, dans les deux sens.
            // La compensation reste dans la couche graphique, sans réagencer le contenu.
            Box(Modifier.fillMaxSize()
                .zIndex(if (page == CAMERA_TAB) 0f else 1f)
                .graphicsLayer {
                    translationX = if (page == CAMERA_TAB) {
                        (pager.currentPage - page + pager.currentPageOffsetFraction) * size.width
                    } else 0f
                    clip = true
                }) {
            if (page == CAMERA_TAB) {
                CameraPageLifecycle(cameraVisible) {
                    // Comme Snapchat : la barre d'état reste noire et l'aperçu est une carte aux coins arrondis.
                    Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))) {
                        CameraScreen(onCaptured, lensMode = lensMode, onLensModeChange = { lensMode = it }, onRecordingChange = { recording = it })
                        if (!recording) Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 8.dp, end = 72.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(account.displayName, account.pictureUrl, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                            IconButton(onClick = onOpenFriends) { FigmaAsset("14-588_imgIconSearch", "Rechercher", tint = Color.White) }
                            Spacer(Modifier.weight(1f))
                            Box {
                                IconButton(onClick = onOpenFriends) { FigmaAsset("14-588_imgIconAddFriend", "Ajouter des amis", tint = Color.White) }
                                RequestsBadge(pendingRequests, Modifier.align(Alignment.TopEnd))
                            }
                        }
                        // Snap en réponse à un ami (bouton appareil photo du chat) : rappel de son nom, ✕ pour annuler.
                        if (snapRecipient != null && !recording) {
                            Row(Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(RoundedCornerShape(18.dp))
                                .background(Color.Black.copy(alpha = .45f)).clickable(onClick = onClearSnapRecipient)
                                .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Snap pour ${snapRecipient.displayName}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                FigmaAsset("15-925_imgIconClose", "Annuler", Modifier.size(16.dp), tint = Color.White)
                            }
                        }
                    }
                }
            } else if (page == MAP_TAB) {
                // La carte n'existe que près de son onglet (Map ou Chat) : inutile de la faire tourner derrière la caméra.
                val nearMap = abs(pager.currentPage + pager.currentPageOffsetFraction - MAP_TAB) < 1.5f
                if (nearMap) MapScreen(account, visible = pager.settledPage == MAP_TAB, onOpenProfile = onOpenProfile, onOpenFriends = onOpenFriends,
                    onOpenConversation = { onOpenConversation(it, null) }, onPlayStory = { onPlayStory(it, true) })
                else Box(Modifier.fillMaxSize().background(Color.White))
            } else {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Row(Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(account.displayName, account.pictureUrl, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                            RoundAction("15-925_imgIconSearch", "Rechercher des amis", onOpenFriends)
                        }
                        Box(Modifier.weight(1f), Alignment.Center) { Text(labels[page], style = MaterialTheme.typography.titleLarge) }
                        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            Box {
                                if (pendingRequests > 0) RoundAction("15-925_imgIconAddFriend", "Ajouter des amis", onOpenFriends, highlighted = true)
                                else RoundAction("15-925_imgIconAddFriend", "Ajouter des amis", onOpenFriends)
                                RequestsBadge(pendingRequests, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
                            }
                            RoundAction("15-925_imgIconMore", "Mon profil et paramètres", onOpenProfile)
                        }
                    }
                    if (page == CHAT_TAB) ChatListScreen(account.id, onOpenConversation, onOpenFriends, onSnapTo, Modifier.weight(1f),
                        onOpenGroup = onOpenGroup, onSnapToGroup = { onSnapTo(it.asRecipient(account.id)) }, onNewChat = onNewChat)
                    else StoriesScreen(account, onOpenMyStory, { onPlayStory(it, false) }, Modifier.weight(1f))
                }
            }
            }
        }
        Column(Modifier.background(navBackground).navigationBarsPadding()) {
            if (!camera) HorizontalDivider(color = SnapColors.Border, thickness = 1.dp)
            // Pendant un enregistrement vidéo, la barre reste noire et vide, comme dans Snapchat.
            AnimatedContent(camera && lensMode, Modifier.graphicsLayer { alpha = if (camera && recording) 0f else 1f }, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "bottomBar") { lensBar ->
                if (lensBar) LensModeBar(onClose = { lensMode = false })
                else Row(Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 8.dp)) {
                    labels.forEachIndexed { index, label ->
                        Column(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !(camera && recording)) {
                            scope.launch { pager.animateScrollToPage(index, animationSpec = tween(280, easing = FastOutSlowInEasing)) }
                        }.padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Tab Bar 10:458 : icône pleine pour l'onglet actif, contour pour les autres.
                            val active = if (index == pager.currentPage) "Active" else ""
                            FigmaAsset("10-458_imgIcon${icons[index]}$active", label, tint = navInk)
                            Text(label, fontSize = 11.sp, lineHeight = 14.sp,
                                fontWeight = if (index == pager.currentPage) FontWeight.Bold else FontWeight.SemiBold, color = navInk)
                        }
                    }
                }
            }
        }
    }
}

/** Nombre de demandes d'ami reçues, en pastille rouge ; rien sans demande. */
@Composable
private fun RequestsBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.sizeIn(minWidth = 18.dp, minHeight = 18.dp).background(SnapColors.SnapRed, CircleShape).padding(horizontal = 4.dp), Alignment.Center) {
        Text(if (count > 9) "9+" else count.toString(), color = Color.White, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
    }
}

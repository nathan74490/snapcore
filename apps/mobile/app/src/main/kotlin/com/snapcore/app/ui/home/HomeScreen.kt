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
import kotlinx.coroutines.launch
import kotlin.math.abs

const val CHAT_TAB = 0
const val CAMERA_TAB = 1
const val STORIES_TAB = 2
private val labels = listOf("Chat", "Caméra", "Stories")
private val icons = listOf("Chat", "Camera", "Stories")

@Composable
fun HomeScreen(account: Account, selected: Int, onSelect: (Int) -> Unit,
    onOpenProfile: () -> Unit, onOpenFriends: () -> Unit, onCaptured: (CapturedMedia) -> Unit,
    onOpenConversation: (Friend, snapId: String?) -> Unit, onOpenMyStory: () -> Unit, onPlayStory: (FriendStory) -> Unit,
    modifier: Modifier = Modifier) {
    val pager = rememberPagerState(initialPage = selected, pageCount = { labels.size })
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) {
        if (pager.settledPage != selected) pager.animateScrollToPage(selected, animationSpec = tween(280, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { select(it) }
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
            userScrollEnabled = !lensMode && !recording,
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
                    Box(Modifier.fillMaxSize()) {
                        CameraScreen(onCaptured, lensMode = lensMode, onLensModeChange = { lensMode = it }, onRecordingChange = { recording = it })
                        if (!recording) Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 8.dp, end = 72.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(account.displayName, account.profilePhotoUrl, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                            IconButton(onClick = onOpenFriends) { FigmaAsset("14-588_imgIconSearch", "Rechercher", tint = Color.White) }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onOpenFriends) { FigmaAsset("14-588_imgIconAddFriend", "Ajouter des amis", tint = Color.White) }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Row(Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(account.displayName, account.profilePhotoUrl, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                            RoundAction("15-925_imgIconSearch", "Rechercher des amis", onOpenFriends)
                        }
                        Box(Modifier.weight(1f), Alignment.Center) { Text(labels[page], style = MaterialTheme.typography.titleLarge) }
                        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            RoundAction("15-925_imgIconAddFriend", "Ajouter des amis", onOpenFriends)
                            RoundAction("15-925_imgIconMore", "Mon profil et paramètres", onOpenProfile)
                        }
                    }
                    if (page == CHAT_TAB) ChatListScreen(account.id, onOpenConversation, onOpenFriends, Modifier.weight(1f))
                    else StoriesScreen(account, onOpenMyStory, onPlayStory, Modifier.weight(1f))
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

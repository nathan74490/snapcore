package com.snapcore.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

const val CHAT_TAB = 0
const val CAMERA_TAB = 1
const val STORIES_TAB = 2
private val labels = listOf("Chat", "Caméra", "Stories")
private val icons = listOf("Chat", "Camera", "Stories")

@Composable
fun HomeScreen(account: Account, selected: Int, onSelect: (Int) -> Unit,
    onOpenProfile: () -> Unit, onOpenFriends: () -> Unit, onCaptured: (CapturedMedia) -> Unit,
    onOpenConversation: (Friend) -> Unit, onOpenMyStory: () -> Unit, onPlayStory: (FriendStory) -> Unit) {
    val camera = selected == CAMERA_TAB
    // Une Lens est active sur la caméra : la barre de navigation laisse place à « ✕ | Pour vous » (ADR-024).
    var lensMode by remember { mutableStateOf(false) }
    StatusBarIcons(dark = !camera)
    Scaffold(
        containerColor = if(camera) Color.Black else Color.White,
        topBar = {
            if(!camera) Row(Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(account.username, account.profilePhotoUrl, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                    RoundAction("15-925_imgIconSearch", "Rechercher des amis", onOpenFriends)
                }
                Box(Modifier.weight(1f), Alignment.Center) { Text(labels[selected], style = MaterialTheme.typography.titleLarge) }
                Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    RoundAction("15-925_imgIconAddFriend", "Ajouter des amis", onOpenFriends)
                    RoundAction("15-925_imgIconMore", "Mon profil et paramètres", onOpenProfile)
                }
            }
        },
        bottomBar = {
            Column(Modifier.background(if(camera) Color.Black else Color.White).navigationBarsPadding()) {
                HorizontalDivider(color = if(camera) Color(0xFF333333) else SnapColors.Border, thickness = .5.dp)
                AnimatedContent(camera && lensMode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bottomBar") { lensBar ->
                    if (lensBar) {
                        LensModeBar(onClose = { lensMode = false })
                    } else {
                        Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 8.dp)) {
                            labels.forEachIndexed { index, label ->
                                Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) }.padding(top = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    FigmaAsset("${if(camera) "14-588" else "15-925"}_imgIcon${icons[index]}", label,
                                        tint = if(camera) Color.White else if(index == selected) SnapColors.ChatBlue else SnapColors.Ink)
                                    Text(label, fontSize = 11.sp, lineHeight = 14.sp,
                                        fontWeight = if(index == selected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if(camera) Color.White else if(index == selected) SnapColors.ChatBlue else SnapColors.Ink)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when(selected) {
            CAMERA_TAB -> Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                CameraScreen(onCaptured, lensMode = lensMode, onLensModeChange = { lensMode = it })
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 8.dp, end = 72.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account.username, account.profilePhotoUrl, 40.dp, Modifier.clickable(onClick = onOpenProfile))
                    IconButton(onClick = onOpenFriends) { FigmaAsset("14-588_imgIconSearch", "Rechercher", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenFriends) { FigmaAsset("14-588_imgIconAddFriend", "Ajouter des amis", tint = Color.White) }
                }
            }
            CHAT_TAB -> ChatListScreen(account.id, onOpenConversation, onOpenFriends, Modifier.padding(padding))
            STORIES_TAB -> StoriesScreen(account, onOpenMyStory, onPlayStory, Modifier.padding(padding))
        }
    }
}

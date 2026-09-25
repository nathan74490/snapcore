package com.snapcore.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendStory
import com.snapcore.app.ui.camera.CameraScreen
import com.snapcore.app.ui.chat.ChatListScreen
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.stories.StoriesScreen

private data class Tab(val label: String, val icon: ImageVector)

private const val CHAT_TAB = 0
private const val CAMERA_TAB = 1
private const val STORIES_TAB = 2

private val TABS = listOf(
    Tab("Chat", Icons.Filled.ChatBubble),
    Tab("Caméra", Icons.Filled.CameraAlt),
    Tab("Stories", Icons.Filled.AutoStories),
)

// Structure de l'accueil Snapchat : caméra plein écran au centre et par défaut, barre de navigation
// en bas, avatar (profil) et ajout d'amis en haut.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    account: Account,
    onOpenProfile: () -> Unit,
    onOpenFriends: () -> Unit,
    onCaptured: (CapturedMedia) -> Unit,
    onOpenConversation: (Friend) -> Unit,
    onOpenMyStory: () -> Unit,
    onPlayStory: (FriendStory) -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(CAMERA_TAB) }
    val onCamera = selected == CAMERA_TAB
    Scaffold(
        topBar = {
            if (!onCamera) {
                CenterAlignedTopAppBar(
                    title = { Text(TABS[selected].label) },
                    navigationIcon = { ProfileAvatar(account, onOpenProfile, Modifier.padding(start = 12.dp)) },
                    actions = { IconButton(onClick = onOpenFriends) { Icon(Icons.Filled.PersonAdd, "Ajouter des amis") } },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = index == selected,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selected) {
            CAMERA_TAB -> {
                Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                    CameraScreen(onCaptured = onCaptured)
                    Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(account, onOpenProfile)
                        IconButton(onClick = onOpenFriends) { Icon(Icons.Filled.PersonAdd, "Ajouter des amis", tint = Color.White) }
                    }
                }
            }
            CHAT_TAB -> ChatListScreen(
                myId = account.id,
                onOpen = onOpenConversation,
                onAddFriends = onOpenFriends,
                modifier = Modifier.padding(padding),
            )
            STORIES_TAB -> StoriesScreen(account, onOpenMine = onOpenMyStory, onPlay = onPlayStory, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun ProfileAvatar(account: Account, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Avatar(account.username, account.profilePhotoUrl, size = 40.dp, modifier = modifier.clickable(onClick = onClick))
}

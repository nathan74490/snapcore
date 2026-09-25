package com.snapcore.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snapcore.app.domain.Account
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.theme.SnapColors

private data class Tab(val label: String, val icon: ImageVector, val placeholder: String)

private val TABS = listOf(
    Tab("Chat", Icons.Filled.ChatBubble, "Tes conversations apparaîtront ici (ADR-009)."),
    Tab("Caméra", Icons.Filled.CameraAlt, "L'écran caméra arrive au prochain lot (ADR-011)."),
    Tab("Stories", Icons.Filled.AutoStories, "Les stories de tes amis apparaîtront ici (ADR-013)."),
)

// Structure de l'accueil Snapchat : barre de navigation en bas, caméra au centre et par défaut,
// avatar en haut à gauche pour ouvrir le profil.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(account: Account, onOpenProfile: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(1) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(TABS[selected].label) },
                navigationIcon = {
                    Avatar(
                        account.username,
                        account.profilePhotoUrl,
                        size = 40.dp,
                        modifier = Modifier.padding(start = 12.dp).clickable(onClick = onOpenProfile),
                    )
                },
            )
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Salut ${account.username} !", style = MaterialTheme.typography.titleLarge)
                Text(TABS[selected].placeholder, color = SnapColors.Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        }
    }
}

package com.snapcore.app.ui.stories

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.FriendStory
import com.snapcore.app.ui.chat.relativeTime
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    account: Account,
    onOpenMine: () -> Unit,
    onPlay: (FriendStory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { StoriesViewModel(it.stories) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Au retour du lecteur, les stories vues passent en gris : on recharge à chaque reprise.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val views = state.mine.sumOf { it.viewCount }
                StoryRow(
                    title = "Ma story",
                    subtitle = if (state.mine.isEmpty()) "Publie un snap depuis la caméra" else "${state.mine.size} snap(s) · $views vue(s)",
                    ring = if (state.mine.isEmpty()) null else SnapColors.SnapPurple,
                    avatar = { Avatar(account.username, account.profilePhotoUrl, size = it) },
                    onClick = onOpenMine,
                )
                HorizontalDivider()
                Text("Amis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            if (state.friends.isEmpty() && !state.loading) {
                item {
                    Text(
                        state.error ?: "Aucune story d'ami pour le moment.",
                        color = SnapColors.Grey,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(state.friends, key = { it.author.id }) { story ->
                StoryRow(
                    title = story.author.username,
                    subtitle = relativeTime(story.lastPostedAt),
                    // Anneau violet = au moins un élément non vu ; gris = tout est vu.
                    ring = if (story.hasUnviewed) SnapColors.SnapPurple else SnapColors.Grey,
                    bold = story.hasUnviewed,
                    avatar = { Avatar(story.author.username, story.author.photoUrl, size = it) },
                    onClick = { onPlay(story) },
                )
            }
        }
    }
}

@Composable
private fun StoryRow(
    title: String,
    subtitle: String,
    ring: Color?,
    avatar: @Composable (Dp) -> Unit,
    onClick: () -> Unit,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.then(if (ring != null) Modifier.border(3.dp, ring, CircleShape).padding(5.dp) else Modifier.padding(8.dp)),
        ) { avatar(48.dp) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            Text(subtitle, color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

package com.snapcore.app.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.snapcore.app.domain.*
import com.snapcore.app.ui.chat.relativeTime
import com.snapcore.app.ui.components.*
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(account: Account, onOpenMine: () -> Unit, onPlay: (FriendStory) -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { StoriesViewModel(it.stories) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    SnapRefresh(state.loading, vm::refresh, modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { Text("Amis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) }
            item {
                // Bulle de 88 pour un anneau de 82 : 9 + 3 de marge interne = 12, comme le reste de la page.
                LazyRow(contentPadding = PaddingValues(horizontal = 9.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        StoryCircle("Ma story", account.displayName, account.profilePhotoUrl,
                            if(state.mine.isEmpty()) SnapColors.Control else SnapColors.SnapPurple, onOpenMine)
                    }
                    items(state.friends, key = { it.author.id }) { story ->
                        StoryCircle(story.author.displayName, story.author.displayName, story.author.photoUrl,
                            if(story.hasUnviewed) SnapColors.SnapPurple else SnapColors.Muted) { onPlay(story) }
                    }
                }
            }
            item {
                Text("Ma story", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp))
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.Background)
                    .clickable(onClick = onOpenMine).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FigmaAsset("17-1301_imgIconCamera", tint = SnapColors.ChatBlue)
                    Column(Modifier.weight(1f)) {
                        Text(if(state.mine.isEmpty()) "Ajouter à Ma story" else "${state.mine.size} snap(s) · ${state.mine.sumOf { it.viewCount }} vue(s)", fontWeight = FontWeight.Bold)
                        Text("Visible par mes amis", color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
                    }
                    FigmaAsset("17-1301_imgIconChevronRight")
                }
            }
            item { Text("Stories récentes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp)) }
            if(state.friends.isEmpty() && !state.loading) item {
                Text(state.error ?: "Les stories de tes amis apparaîtront ici.", color = SnapColors.Grey, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            items(state.friends.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { story ->
                        Box(Modifier.weight(1f).height(254.dp).clip(RoundedCornerShape(10.dp)).background(SnapColors.Control).clickable { onPlay(story) }) {
                            // API-supplied profile image stays dynamic; never use a mock person's Figma photo.
                            if(story.author.photoUrl != null) AsyncImage(story.author.photoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize(), Alignment.Center) { Avatar(story.author.displayName, null, 72.dp) }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=.65f)))))
                            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                Text(story.author.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(relativeTime(story.lastPostedAt), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if(pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StoryCircle(label: String, username: String, photo: String?, ring: Color, click: () -> Unit) {
    // Story Bubble 11:325 : 88 de large, anneau 82 (3pt), avatar 72, libellé Bold 14/18 à 12 sous l'anneau.
    Column(Modifier.width(88.dp).clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(82.dp).border(3.dp, ring, CircleShape), Alignment.Center) { Avatar(username, photo, 72.dp) }
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    }
}

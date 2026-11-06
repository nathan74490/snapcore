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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
fun StoriesScreen(
    account: Account, onOpenMine: () -> Unit, onPlay: (FriendStory) -> Unit,
    onOpenLive: (liveId: String) -> Unit, onLaunchLive: () -> Unit, modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { StoriesViewModel(it.stories, it.outbox.items, it.outbox::retry) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Lives en cours de toutes les App membres, sous les stories (ADR-035).
    val livesVm = containerViewModel { LivesViewModel(it.live::lives) }
    val lives by livesVm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(showIndicator = false); livesVm.refresh(); onPauseOrDispose {} }
    // TalkBack (ADR-033) : la rangée de bulles répète les cartes « Stories récentes » ; on ne la lit pas,
    // les cartes disent tout (nom, nouveau ou déjà vu, date).
    val screenReader = rememberScreenReaderOn()
    SnapRefresh(state.loading, { vm.refresh(); livesVm.refresh() }, modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            // Comme Snapchat : la rangée ne montre que les stories des amis, Ma story a sa propre carte.
            if (state.friends.isNotEmpty()) {
                val hidden = if (screenReader) Modifier.clearAndSetSemantics {} else Modifier
                item { Text("Amis", style = MaterialTheme.typography.titleMedium, modifier = hidden.padding(start = 12.dp, top = 8.dp, bottom = 8.dp).semantics { heading() }) }
                item {
                    // Bulle de 88 pour un anneau de 82 : 9 + 3 de marge interne = 12, comme le reste de la page.
                    LazyRow(contentPadding = PaddingValues(horizontal = 9.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = hidden) {
                        items(state.friends, key = { it.author.id }) { story ->
                            StoryCircle(story.author, story.thumbnailUrl, story.hasUnviewed, if(story.hasUnviewed) SnapColors.SnapPurple else SnapColors.Muted) { onPlay(story) }
                        }
                    }
                }
            }
            item {
                Text("Ma story", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = if (state.friends.isEmpty()) 8.dp else 24.dp, bottom = 8.dp).semantics { heading() })
                // Une seule carte pour TalkBack : « Ma story, 3 snaps, 2 vues, Envoi en cours… » ; réessayer un envoi
                // échoué passe par une action personnalisée (le lien rouge n'est pas atteignable seul).
                val title = if (state.mine.isEmpty()) "Ajouter à Ma story" else myStorySummary(state.mine.size, state.mine.sumOf { it.viewCount })
                val status = when (state.upload?.status) {
                    OutgoingStatus.SENDING -> "Envoi en cours…"
                    OutgoingStatus.FAILED -> "Échec de l'envoi"
                    else -> "Visible par mes amis"
                }
                val failed = state.upload?.status == OutgoingStatus.FAILED
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SnapColors.Background)
                    .clickable(onClick = onOpenMine)
                    .clearAndSetSemantics {
                        contentDescription = "Ma story, ${title.replace(" · ", ", ")}, $status"
                        role = Role.Button
                        liveRegion = LiveRegionMode.Polite
                        onClick("ouvrir Ma story") { onOpenMine(); true }
                        if (failed) customActions = listOf(CustomAccessibilityAction("Réessayer l'envoi") { vm.retryUpload(); true })
                    }
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Vignette de mon dernier snap publié, sinon l'appareil photo.
                    val latest = state.mine.lastOrNull { it.thumbnailUrl != null }?.thumbnailUrl
                    if (latest != null) AsyncImage(latest, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(CircleShape))
                    else FigmaAsset("17-1301_imgIconCamera", tint = SnapColors.ChatBlue)
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold)
                        val upload = state.upload
                        when (upload?.status) {
                            OutgoingStatus.SENDING -> Text("Envoi en cours…", color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
                            OutgoingStatus.FAILED -> Text("Échec de l'envoi · appuie pour réessayer", color = SnapColors.SnapRed,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable(onClick = vm::retryUpload))
                            else -> Text("Visible par mes amis", color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FigmaAsset("17-1301_imgIconChevronRight")
                }
            }
            item { Text("Stories récentes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp).semantics { heading() }) }
            if(state.friends.isEmpty() && !state.loading) item {
                Text(state.error ?: "Les stories de tes amis apparaîtront ici.", color = SnapColors.Grey, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            items(state.friends.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { story ->
                        Box(Modifier.weight(1f).height(254.dp).clip(RoundedCornerShape(10.dp)).background(SnapColors.Control).clickable { onPlay(story) }
                            // TalkBack : « Story de Léa, nouveau, il y a 3 h ».
                            .clearAndSetSemantics {
                                contentDescription = storyDescription(story.author.displayName, story.hasUnviewed, relativeTime(story.lastPostedAt))
                                role = Role.Button
                                onClick("regarder la story") { onPlay(story); true }
                            }) {
                            // Vignette de la story (ADR-026), sinon la photo de profil ; jamais une photo de maquette Figma.
                            val cover = story.thumbnailUrl ?: story.author.photoUrl
                            if(cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize(), Alignment.Center) { Avatar(story.author.displayName, story.author.avatarUrl, 72.dp) }
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
            livesSection(lives, onOpenLive, onLaunchLive)
        }
    }
}

@Composable
private fun StoryCircle(author: Friend, thumbnailUrl: String?, unviewed: Boolean, ring: Color, click: () -> Unit) {
    // Story Bubble 11:325 : 88 de large, anneau 82 (3pt), avatar 72, libellé Bold 14/18 à 12 sous l'anneau ;
    // le pseudo en gris dessous quand l'ami a un prénom, comme dans Snapchat.
    Column(Modifier.width(88.dp).clickable(onClick = click).clearAndSetSemantics {
        contentDescription = storyDescription(author.displayName, unviewed, null)
        role = Role.Button
        onClick("regarder la story") { click(); true }
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        // Comme Snapchat : l'image de la story dans l'anneau (ADR-026), l'avatar à défaut.
        Box(Modifier.size(82.dp).border(3.dp, ring, CircleShape), Alignment.Center) {
            if (thumbnailUrl != null) AsyncImage(thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(CircleShape).background(SnapColors.Control))
            else Avatar(author.displayName, author.pictureUrl, 72.dp)
        }
        Text(author.displayName, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        if (author.firstName != null) Text(author.username, fontSize = 12.sp, lineHeight = 16.sp, color = SnapColors.Grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** « Story de Léa, nouveau, il y a 3 h » : ce que TalkBack lit pour une story d'ami. */
private fun storyDescription(name: String, unviewed: Boolean, ago: String?): String =
    "Story de $name, " + (if (unviewed) "nouveau" else "déjà vue") +
        (ago?.let { if (it == "à l'instant") ", à l'instant" else ", il y a $it" } ?: "")

/** « 1 snap · 1 vue », « 3 snaps · 0 vue » : accord en français (0 et 1 au singulier). */
fun myStorySummary(snaps: Int, views: Int): String {
    fun plural(count: Int, word: String) = "$count $word" + if (count > 1) "s" else ""
    return "${plural(snaps, "snap")} · ${plural(views, "vue")}"
}

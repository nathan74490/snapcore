package com.snapcore.app.ui.stories

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.ui.chat.relativeTime
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import kotlinx.coroutines.delay

private const val PHOTO_DURATION_MS = 5_000

// Lecteur façon Snapchat : barres de progression en haut, tap à droite = suivant, à gauche = précédent.
@Composable
fun StoryPlayerScreen(title: String, authorId: String?, startItemId: String?, onClose: () -> Unit) {
    val vm = containerViewModel { StoryPlayerViewModel(it.stories, authorId, startItemId) }
    val state by vm.state.collectAsStateWithLifecycle()
    StatusBarIcons(dark = false)
    LaunchedEffect(state) { if (state is PlayerState.Finished) onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { offset -> if (offset.x < size.width / 3f) vm.previous() else vm.next() }
            },
    ) {
        when (val s = state) {
            PlayerState.Loading, PlayerState.Finished -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            is PlayerState.Failed -> Text(s.message, color = Color.White, modifier = Modifier.align(Alignment.Center))
            is PlayerState.Playing -> {
                var progress by remember(s.index) { mutableFloatStateOf(0f) }
                when {
                    s.url == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                    s.current.kind == MediaKind.PHOTO -> {
                        StoryPhoto(s.url)
                        val animated = remember(s.index) { Animatable(0f) }
                        LaunchedEffect(s.index) {
                            animated.animateTo(1f, tween(PHOTO_DURATION_MS, easing = LinearEasing)) { progress = value }
                            vm.next()
                        }
                    }
                    else -> StoryVideo(s.url, onProgress = { progress = it }, onEnded = vm::next)
                }
                Column(Modifier.fillMaxWidth().systemBarsPadding().padding(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        s.items.indices.forEach { i ->
                            LinearProgressIndicator(
                                progress = { if (i < s.index) 1f else if (i == s.index) progress else 0f },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.weight(1f).height(3.dp).background(Color.Transparent, RoundedCornerShape(2.dp)),
                                drawStopIndicator = {},
                                // Sans espace entre la partie lue et le reste : sinon le segment en cours
                                // semble coupé en deux, comme un élément de plus (BUG-018 d'ADR-023).
                                gapSize = 0.dp,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                        Text("  ${relativeTime(s.current.createdAt)}", color = Color.White.copy(alpha = 0.8f))
                        Box(Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fermer", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryPhoto(url: String) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(url) {
            // Contenu éphémère : jamais conservé sur le disque de l'appareil.
            ImageRequest.Builder(context).data(url).diskCachePolicy(CachePolicy.DISABLED).build()
        },
        contentDescription = "Story",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun StoryVideo(url: String, onProgress: (Float) -> Unit, onEnded: () -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) onProgress(player.currentPosition.toFloat() / player.duration)
            delay(100)
        }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } }, modifier = Modifier.fillMaxSize())
}

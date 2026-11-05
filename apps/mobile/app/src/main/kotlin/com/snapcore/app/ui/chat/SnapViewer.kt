package com.snapcore.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.ui.components.rememberScreenReaderOn
import com.snapcore.app.ui.components.screenReaderFocusOnAppear

// Lecture d'un Snap en plein écran ; un appui ferme. Le média n'est JAMAIS écrit sur le disque de
// l'appareil (caches désactivés), uniquement gardé en mémoire le temps de l'affichage (ADR-009).
// Avec TalkBack (ADR-033) : le snap est décrit (« Snap photo de Léa ») et reçoit le focus, il ne se ferme
// jamais tout seul (même à la fin d'une vidéo) et des boutons « Fermer » et « Pause » remplacent l'appui.
// [from] : le nom de l'expéditeur, s'il est connu.
@Composable
fun SnapViewer(state: SnapViewerState, onClose: () -> Unit, from: String? = null) {
    val screenReader = rememberScreenReaderOn()
    // Barres système : réglées par la conversation qui affiche ce lecteur (BUG-015 d'ADR-023).
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Avec TalkBack, l'appui n'importe où ne ferme pas : c'est le bouton « Fermer » qui le fait.
            .then(if (screenReader) Modifier else Modifier.clickable(onClick = onClose)),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            SnapViewerState.Loading -> CircularProgressIndicator(color = Color.White,
                modifier = Modifier.semantics { contentDescription = "Ouverture du snap"; liveRegion = LiveRegionMode.Polite })
            is SnapViewerState.Showing -> {
                val video = state.result.type == ChatContentType.SNAP_PHOTO_VIDEO
                val described = (if (video) "Snap vidéo" else "Snap photo") + (from?.let { " de $it" } ?: "") +
                    if (screenReader) ". Il reste affiché jusqu'à ce que tu le fermes." else ""
                var playing by remember(state.result.url) { mutableStateOf(true) }
                if (video) {
                    StreamedVideo(state.result.url, playing,
                        onEnded = { if (screenReader) playing = false else onClose() },
                        modifier = Modifier.semantics { contentDescription = described }.screenReaderFocusOnAppear(state.result.url))
                } else {
                    val context = LocalContext.current
                    AsyncImage(
                        model = remember(state.result.url) {
                            ImageRequest.Builder(context)
                                .data(state.result.url)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .build()
                        },
                        contentDescription = described,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().screenReaderFocusOnAppear(state.result.url),
                    )
                }
                // Boutons visibles seulement avec TalkBack : sans lui, l'écran reste identique (ADR-033).
                if (screenReader) {
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)
                        .background(Color.Black.copy(alpha = .6f)),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (video) TextButton(onClick = { playing = !playing }) {
                            Text(if (playing) "Pause" else "Lire", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = onClose) {
                            Text("Fermer le snap", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// Lecture en streaming depuis l'URL pré-signée (~1 min de validité) : ExoPlayer ne met rien en
// cache disque sans configuration explicite. Le Snap se ferme à la fin de la vidéo (sauf avec TalkBack :
// elle revient au début, en pause, et « Lire » la relance).
@OptIn(UnstableApi::class)
@Composable
private fun StreamedVideo(url: String, playing: Boolean, onEnded: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    val ended by rememberUpdatedState(onEnded)
    LaunchedEffect(player, playing) {
        if (playing && player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = playing
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // La vue native est masquée pour TalkBack : c'est la boîte autour qui décrit le snap.
    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply {
            this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } }, modifier = Modifier.fillMaxSize())
    }
}

package com.snapcore.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.ui.components.StatusBarIcons

// Lecture d'un Snap en plein écran ; un appui ferme. Le média n'est JAMAIS écrit sur le disque de
// l'appareil (caches désactivés), uniquement gardé en mémoire le temps de l'affichage (ADR-009).
@Composable
fun SnapViewer(state: SnapViewerState, onClose: () -> Unit) {
    StatusBarIcons(dark = false)
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            SnapViewerState.Loading -> CircularProgressIndicator(color = Color.White)
            is SnapViewerState.Showing -> when (state.result.type) {
                ChatContentType.SNAP_PHOTO_VIDEO -> StreamedVideo(state.result.url, onEnded = onClose)
                else -> {
                    val context = LocalContext.current
                    AsyncImage(
                        model = remember(state.result.url) {
                            ImageRequest.Builder(context)
                                .data(state.result.url)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .build()
                        },
                        contentDescription = "Snap",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// Lecture en streaming depuis l'URL pré-signée (~1 min de validité) : ExoPlayer ne met rien en
// cache disque sans configuration explicite. Le Snap se ferme à la fin de la vidéo.
@Composable
private fun StreamedVideo(url: String, onEnded: () -> Unit) {
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
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize())
}

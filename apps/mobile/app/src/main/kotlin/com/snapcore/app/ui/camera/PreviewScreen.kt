package com.snapcore.app.ui.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.theme.SnapColors

// Aperçu du snap capturé. Fermer l'aperçu supprime le fichier local : rien n'est conservé
// sans action explicite (ADR-011).
@Composable
fun PreviewScreen(media: CapturedMedia, onDiscard: () -> Unit, onSendTo: () -> Unit) {
    val discard = {
        media.file.delete()
        onDiscard()
    }
    BackHandler(onBack = discard)
    StatusBarIcons(dark = false)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (media.kind) {
            MediaKind.PHOTO -> AsyncImage(model = media.file, contentDescription = "Snap", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            MediaKind.VIDEO -> LoopingVideo(media)
        }
        IconButton(onClick = discard, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
            Icon(Icons.Filled.Close, "Supprimer", tint = Color.White)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onSendTo, colors = ButtonDefaults.buttonColors(containerColor = SnapColors.ChatBlue)) {
                Text("Envoyer à")
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun LoopingVideo(media: CapturedMedia) {
    val context = LocalContext.current
    val player = remember(media.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(media.file.toURI().toString()))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = false } },
        modifier = Modifier.fillMaxSize(),
    )
}

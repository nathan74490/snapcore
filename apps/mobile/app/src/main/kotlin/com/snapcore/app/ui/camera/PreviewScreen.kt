package com.snapcore.app.ui.camera

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import com.snapcore.app.domain.CameraFilter
import com.snapcore.app.domain.CameraFilters
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.ColorMatrix
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

// Aperçu du snap capturé. On y choisit le filtre en glissant à gauche ou à droite, comme dans
// Snapchat ; il n'est incrusté dans le média qu'au moment d'envoyer (ADR-022). Fermer l'aperçu
// supprime le fichier local : rien n'est conservé sans action explicite (ADR-011).
@Composable
fun PreviewScreen(media: CapturedMedia, onDiscard: () -> Unit, onSendTo: (CapturedMedia) -> Unit) {
    val vm = containerViewModel { PreviewViewModel(media, it.filterRenderer, it.analytics, it.experiments) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready) {
        state.ready?.let {
            vm.consumeReady()
            onSendTo(it)
        }
    }

    val discard = {
        vm.onDiscarded()
        media.file.delete()
        onDiscard()
    }
    BackHandler(enabled = !state.rendering, onBack = discard)
    StatusBarIcons(dark = false)

    var labelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.filterIndex, labelVisible) {
        if (labelVisible) {
            delay(1200)
            labelVisible = false
        }
    }
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Glisser vers la gauche = filtre suivant, vers la droite = précédent.
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (abs(dragged) > swipeThreshold) {
                            vm.swipe(if (dragged < 0) 1 else -1)
                            labelVisible = true
                        }
                    },
                ) { _, dx -> dragged += dx }
            },
    ) {
        when (media.kind) {
            MediaKind.PHOTO -> AsyncImage(
                model = media.file,
                contentDescription = "Ta photo",
                contentScale = ContentScale.Crop,
                colorFilter = state.filter.matrix?.let { ColorFilter.colorMatrix(ComposeColorMatrix(it.toAndroidMatrix())) },
                modifier = Modifier.fillMaxSize(),
            )
            MediaKind.VIDEO -> LoopingVideo(media, state.filter, Modifier.semantics { contentDescription = "Ta vidéo" })
        }

        // Nom du filtre affiché brièvement : TalkBack le lit déjà sur le sélecteur de filtre ci-dessous.
        AnimatedVisibility(labelVisible, Modifier.align(Alignment.Center).clearAndSetSemantics {}, enter = fadeIn(), exit = fadeOut()) {
            Text(
                state.filter.label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        IconButton(onClick = discard, enabled = !state.rendering, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
            Icon(Icons.Filled.Close, "Supprimer le snap", tint = Color.White)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // TalkBack (ADR-033) : le glissement n'est pas le seul moyen de changer de filtre. Les points forment
            // un sélecteur « Filtre, Noir et blanc, 2 sur 6 » : double appui = filtre suivant, actions Suivant / Précédent.
            val filterCount = CameraFilters.ALL.size
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Filtre"
                stateDescription = "${state.filter.label}, ${state.filterIndex + 1} sur $filterCount"
                liveRegion = LiveRegionMode.Polite
                onClick("passer au filtre suivant") { vm.swipe(1); true }
                customActions = listOf(
                    CustomAccessibilityAction("Filtre suivant") { vm.swipe(1); true },
                    CustomAccessibilityAction("Filtre précédent") { vm.swipe(-1); true },
                )
            }) {
                CameraFilters.ALL.indices.forEach { i ->
                    Box(Modifier.size(6.dp).background(if (i == state.filterIndex) Color.White else Color(0x80FFFFFF), CircleShape))
                }
            }
            state.error?.let {
                Text(it, color = Color.White, modifier = Modifier.background(Color(0x99000000)).padding(8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = vm::confirm,
                    enabled = !state.rendering,
                    colors = ButtonDefaults.buttonColors(containerColor = SnapColors.ChatBlue),
                    // Annonce « Application du filtre… » pendant le rendu.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    if (state.rendering) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text("Application du filtre…", modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Text(state.sendLabel.label)
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

// Matrice lue par Media3 à chaque image : changer de filtre agit sur la vidéo en cours de lecture.
@OptIn(UnstableApi::class)
private class LiveRgbMatrix : RgbMatrix {
    @Volatile var matrix: FloatArray = ColorMatrix.IDENTITY.toGlMatrix()

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

@OptIn(UnstableApi::class)
@Composable
private fun LoopingVideo(media: CapturedMedia, filter: CameraFilter, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val effect = remember { LiveRgbMatrix() }
    effect.matrix = (filter.matrix ?: ColorMatrix.IDENTITY).toGlMatrix()
    val player = remember(media.file) {
        ExoPlayer.Builder(context).build().apply {
            // Même matrice que l'incrustation par Transformer : l'aperçu montre ce qui sera envoyé.
            setVideoEffects(listOf(effect))
            setMediaItem(MediaItem.fromUri(media.file.toURI().toString()))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } },
        modifier = modifier.fillMaxSize(),
    )
}

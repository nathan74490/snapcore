package com.snapcore.app.ui.stories

import android.widget.Toast
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snapcore.app.domain.ReportTarget
import com.snapcore.app.ui.safety.ReportDialog
import com.snapcore.app.ui.safety.SafetyViewModel
import com.snapcore.app.ui.safety.message
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.snapcore.app.ui.components.rememberScreenReaderOn
import com.snapcore.app.ui.components.screenReaderFocusOnAppear
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
    // Signaler l'élément affiché d'une story d'ami (ADR-021 ; BUG-019 d'ADR-023). La photo est en pause
    // tant que le motif n'est pas choisi.
    val safetyVm = containerViewModel { SafetyViewModel(it.safety) }
    val safety by safetyVm.state.collectAsStateWithLifecycle()
    var reporting by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(safety.outcome, safety.error) {
        safety.outcome?.let { Toast.makeText(context, it.message(title), Toast.LENGTH_SHORT).show(); safetyVm.consume() }
        safety.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); safetyVm.consume() }
    }
    reporting?.let { itemId ->
        ReportDialog(subject = "cette story", onReport = { safetyVm.report(ReportTarget.StoryItem(itemId), it) }, onDismiss = { reporting = null })
    }
    // TalkBack (ADR-033) : pas d'avance automatique, l'élément reste affiché jusqu'à « Suivant » ; chaque geste
    // (taper à gauche / à droite) existe aussi en bouton et en action personnalisée.
    val screenReader = rememberScreenReaderOn()
    // Pause demandée (bouton ou action « Pause ») ; le signalement met aussi en pause.
    var userPaused by remember { mutableStateOf(false) }
    val paused = userPaused || reporting != null

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
            is PlayerState.Failed -> Text(s.message, color = Color.White,
                modifier = Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite })
            is PlayerState.Playing -> {
                var progress by remember(s.index) { mutableFloatStateOf(0f) }
                // Nouvel élément : la lecture reprend.
                LaunchedEffect(s.index) { userPaused = false }
                val description = itemDescription(s, title, mine = authorId == null)
                // Un seul élément décrit le média (« Photo de Léa, il y a 3 h, 2 sur 5 ») avec ses actions ; le focus
                // TalkBack s'y pose à chaque nouvel élément.
                Box(
                    Modifier
                        .fillMaxSize()
                        .screenReaderFocusOnAppear(s.index)
                        .clearAndSetSemantics {
                            contentDescription = description
                            customActions = buildList {
                                add(CustomAccessibilityAction("Suivant") { vm.next(); true })
                                if (s.index > 0) add(CustomAccessibilityAction("Précédent") { vm.previous(); true })
                                if (s.current.kind == MediaKind.VIDEO) {
                                    add(CustomAccessibilityAction(if (userPaused) "Reprendre" else "Pause") { userPaused = !userPaused; true })
                                }
                                if (authorId != null) add(CustomAccessibilityAction("Signaler") { reporting = s.current.id; true })
                                add(CustomAccessibilityAction("Fermer") { onClose(); true })
                            }
                        },
                ) {
                    when {
                        s.url == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                        s.current.kind == MediaKind.PHOTO -> {
                            StoryPhoto(s.url)
                            val animated = remember(s.index) { Animatable(0f) }
                            LaunchedEffect(s.index, paused, screenReader) {
                                if (paused || screenReader) return@LaunchedEffect
                                // Reprend là où la pause l'avait arrêtée.
                                val remaining = ((1f - animated.value) * PHOTO_DURATION_MS).toInt()
                                animated.animateTo(1f, tween(remaining, easing = LinearEasing)) { progress = value }
                                vm.next()
                            }
                        }
                        else -> StoryVideo(s.url, playing = !paused, onProgress = { progress = it },
                            onEnded = { if (!screenReader) vm.next() })
                    }
                }
                if (screenReader) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StoryControl("Précédent", enabled = s.index > 0, onClick = vm::previous)
                        if (s.current.kind == MediaKind.VIDEO) {
                            StoryControl(if (userPaused) "Reprendre" else "Pause", onClick = { userPaused = !userPaused })
                        }
                        StoryControl(if (s.index == s.items.lastIndex) "Terminer" else "Suivant", onClick = vm::next)
                    }
                }
                Column(Modifier.fillMaxWidth().systemBarsPadding().padding(8.dp)) {
                    // Barres de progression : visuelles seulement, la position est dite par la description du média.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}) {
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
                        // Déjà lus dans la description du média.
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {})
                        Text("  ${relativeTime(s.current.createdAt)}", color = Color.White.copy(alpha = 0.8f), modifier = Modifier.clearAndSetSemantics {})
                        Box(Modifier.weight(1f))
                        if (authorId != null) {
                            IconButton(onClick = { reporting = s.current.id }) { Icon(Icons.Outlined.Flag, "Signaler cette story", tint = Color.White) }
                        }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fermer", tint = Color.White) }
                    }
                }
            }
        }
    }
}

/** « Photo de Léa, il y a 3 h, 2 sur 5 » ; « Vidéo de ta story, à l'instant, 1 sur 2 ». */
private fun itemDescription(s: PlayerState.Playing, title: String, mine: Boolean): String {
    val kind = if (s.current.kind == MediaKind.VIDEO) "Vidéo" else "Photo"
    val owner = if (mine) "de ta story" else "de $title"
    val ago = relativeTime(s.current.createdAt).let { if (it == "à l'instant") it else "il y a $it" }
    val loading = if (s.url == null) ", chargement" else ""
    return "$kind $owner, $ago, ${s.index + 1} sur ${s.items.size}$loading"
}

/** Bouton du lecteur affiché seulement avec TalkBack : précédent, pause, suivant. */
@Composable
private fun StoryControl(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f)),
        modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
    ) { Text(label, fontWeight = FontWeight.Bold) }
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
private fun StoryVideo(url: String, playing: Boolean, onProgress: (Float) -> Unit, onEnded: () -> Unit) {
    val context = LocalContext.current
    // Le listener est posé une fois : il doit appeler la version courante de onEnded.
    val ended by rememberUpdatedState(onEnded)
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
                if (playbackState == Player.STATE_ENDED) ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) onProgress(player.currentPosition.toFloat() / player.duration)
            delay(100)
        }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } }, modifier = Modifier.fillMaxSize())
}

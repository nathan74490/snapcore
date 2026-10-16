package com.snapcore.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Retour interactif : la conversation suit le doigt et révèle l'accueil capturé à son ouverture. */
@Composable
fun SwipeBackPane(
    backdrop: ImageBitmap?,
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable (gesture: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val latestBack by rememberUpdatedState(onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
        val width = constraints.maxWidth.toFloat()
        if (backdrop != null && offset > 0f) {
            Image(backdrop, null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = -(width - offset) * .2f })
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = offset
            shadowElevation = if (offset > 0f) 8.dp.toPx() else 0f
        }) {
            val gesture = Modifier.draggable(
                state = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(0f, width) },
                orientation = Orientation.Horizontal,
                enabled = enabled && !settling,
                onDragStarted = { keyboard?.hide() },
                onDragStopped = { velocity ->
                    settling = true
                    val dismiss = offset > width * .28f || (offset > with(density) { 24.dp.toPx() } && velocity > with(density) { 700.dp.toPx() })
                    scope.launch {
                        val animation = Animatable(offset)
                        if (dismiss) animation.animateTo(width, tween(180, easing = FastOutSlowInEasing)) { offset = value }
                        else animation.animateTo(0f, spring(dampingRatio = .85f, stiffness = 600f)) { offset = value }
                        if (dismiss) latestBack()
                        settling = false
                    }
                },
            )
            content(gesture)
        }
    }
}

package com.snapcore.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Clin d'œil pendant le tirage ; envol et fermeture synchronisés après le seuil. */
internal class GhostRefreshState : PullToRefreshState {
    private val distance = Animatable(0f)
    val flight = Animatable(0f)
    var flying by mutableStateOf(false)
        private set
    var launchDistance by mutableFloatStateOf(0f)
        private set
    var launchRequested = false
    override val distanceFraction get() = distance.value
    override val isAnimating get() = distance.isRunning || flying
    override suspend fun snapTo(targetValue: Float) { distance.snapTo(targetValue) }
    override suspend fun animateToThreshold() { distance.animateTo(1f) }
    override suspend fun animateToHidden() {
        if (!launchRequested) { distance.animateTo(0f); return }
        launchRequested = false
        launchDistance = distance.value
        flying = true
        try {
            coroutineScope {
                launch {
                    flight.snapTo(0f)
                    flight.animateTo(1f, tween(360, easing = FastOutLinearInEasing))
                }
                launch {
                    delay(80)
                    distance.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
                }
            }
        } finally { flying = false }
    }
}

/** Personnage vectoriel dessiné d'après la référence : visage, mains, langue puis jet arc-en-ciel. */
@Composable
internal fun RefreshGhost(state: GhostRefreshState, threshold: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (state.distanceFraction <= 0f && !state.flying) return@Canvas
        val unit = 48.dp.toPx() / 100f
        val progress = if (state.flying) state.flight.value else 0f
        val ledge = state.distanceFraction * threshold
        val start = if (state.flying) state.launchDistance * threshold else ledge
        // L'envol accompagne la remontée du bord : la liste ne rattrape pas le fantôme.
        val y = ledge - 62f * unit - progress * (start + 80f * unit)
        val ink = Color(0xFF202124)
        val pink = Color(0xFFFF508C)
        translate(size.width / 2f - 50f * unit, y) {
            scale(unit, pivot = Offset.Zero) {
                val body = Path().apply {
                    moveTo(26f, 59f); lineTo(26f, 34f)
                    cubicTo(26f, 1f, 74f, 1f, 74f, 34f)
                    lineTo(74f, 61f)
                    cubicTo(77f, 71f, 82f, 78f, 94f, 82f)
                    quadraticTo(88f, 89f, 77f, 87f)
                    lineTo(73f, 94f)
                    quadraticTo(62f, 89f, 55f, 94f)
                    quadraticTo(48f, 98f, 40f, 93f)
                    lineTo(28f, 95f); lineTo(24f, 88f)
                    quadraticTo(13f, 91f, 6f, 83f)
                    quadraticTo(24f, 77f, 26f, 59f); close()
                }
                drawPath(body, Color.White)
                drawPath(body, ink, style = Stroke(2.6f))
                for (x in listOf(15f, 74f)) {
                    drawOval(Color.White, Offset(x, 53f), Size(13f, 10f))
                    drawOval(ink, Offset(x, 53f), Size(13f, 10f), style = Stroke(2.2f))
                }
                drawOval(ink, Offset(35f, 31f), Size(5.5f, 9f))
                if (!state.flying) {
                    val wink = Path().apply {
                        moveTo(59f, 36f); quadraticTo(61f, 30f, 67f, 32f)
                        moveTo(59f, 37f); lineTo(66f, 36f)
                    }
                    drawPath(wink, ink, style = Stroke(2.5f, cap = StrokeCap.Round))
                    val tongue = Path().apply {
                        moveTo(54f, 48f); lineTo(63f, 45f)
                        quadraticTo(66f, 59f, 58f, 55f); close()
                    }
                    drawPath(tongue, pink)
                    drawPath(tongue, ink, style = Stroke(1.6f))
                    val smile = Path().apply {
                        moveTo(35f, 44f); cubicTo(40f, 54f, 55f, 53f, 65f, 43f)
                    }
                    drawPath(smile, ink, style = Stroke(2.5f, cap = StrokeCap.Round))
                } else {
                    drawOval(ink, Offset(60f, 31f), Size(5.5f, 9f))
                    val colors = listOf(0xFFFF475B, 0xFFFFA337, 0xFFFFEA38, 0xFF42E66C, 0xFF38CFFF, 0xFF9768FF)
                    val bottom = ((ledge - y) / unit).coerceAtLeast(62f)
                    val alpha = ((1f - progress) / .2f).coerceIn(0f, 1f)
                    // Le jet sort de la bouche et passe devant le corps, jusqu'au bord de la liste.
                    colors.forEachIndexed { index, color ->
                        val x = 36f + index * 4.7f
                        val band = Path().apply {
                            moveTo(x, 48f); lineTo(x + 4.9f, 48f)
                            lineTo(x + 6f, bottom); lineTo(x - 1f, bottom); close()
                        }
                        drawPath(band, Color(color).copy(alpha = alpha))
                    }
                    drawRoundRect(pink, Offset(35f, 43f), Size(31f, 8f), CornerRadius(2f))
                    drawRoundRect(ink, Offset(35f, 43f), Size(31f, 8f), CornerRadius(2f), style = Stroke(1.5f))
                }
            }
        }
    }
}

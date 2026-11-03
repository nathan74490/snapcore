package com.snapcore.app.ui.auth

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/** Vectoriel : le personnage reste net même lorsqu'il remplit entièrement la fenêtre. */
@Composable
internal fun HalloweenStartup(ready: Boolean, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    val zoom = remember { Animatable(1f) }
    var revealed by remember { mutableStateOf(false) }
    var rushing by remember { mutableStateOf(false) }
    val loop = rememberInfiniteTransition(label = "ghost hover")
    val phase by loop.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "hover")
    LaunchedEffect(Unit) { delay(1200); revealed = true }
    LaunchedEffect(ready, revealed) {
        if (ready && revealed) {
            rushing = true
            zoom.animateTo(.86f, tween(160, easing = FastOutSlowInEasing))
            zoom.animateTo(42f, tween(620, easing = CubicBezierEasing(.75f, 0f, .9f, .35f)))
            finished()
        }
    }
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Fantôme Halloween avec sa faucille. Chargement de Snapcore" }) {
        val ink = Color(0xFF20132F)
        val orange = Color(0xFFFF9C37)
        drawRect(Brush.radialGradient(listOf(Color(0xFF4A245C), Color(0xFF150F25)), center, size.maxDimension * .7f))
        val unit = 190.dp.toPx() / 200f
        val floatY = if (rushing) 0f else sin(phase) * 5.dp.toPx()
        // Petites braises calmes autour du personnage, sans clignotement.
        repeat(18) { i ->
            val x = size.width * ((i * 37 % 101) / 101f)
            val y = size.height * ((i * 53 % 97) / 97f)
            drawCircle(orange.copy(alpha = .12f + (i % 3) * .06f), (1 + i % 3).dp.toPx(), Offset(x, y + sin(phase + i) * 8.dp.toPx()))
        }
        drawOval(Color.Black.copy(alpha = .22f), Offset(center.x - 64 * unit, center.y + 110 * unit), Size(128 * unit, 14 * unit))
        // Le centre du zoom vise le ventre blanc : il recouvre réellement les quatre coins.
        translate(center.x, center.y + floatY) {
            scale(unit * zoom.value, pivot = Offset.Zero) {
                translate(-100f, -140f) {
                    val body = Path().apply {
                        moveTo(56f, 116f); lineTo(56f, 87f)
                        cubicTo(56f, 37f, 142f, 37f, 142f, 87f)
                        lineTo(142f, 127f)
                        cubicTo(147f, 153f, 163f, 164f, 181f, 169f)
                        quadraticTo(171f, 183f, 151f, 179f)
                        lineTo(145f, 192f); quadraticTo(127f, 183f, 115f, 193f)
                        quadraticTo(101f, 203f, 87f, 192f)
                        lineTo(65f, 196f); lineTo(58f, 182f)
                        quadraticTo(39f, 187f, 22f, 174f)
                        cubicTo(46f, 163f, 54f, 144f, 56f, 116f); close()
                    }
                    drawPath(body, Color.White)
                    drawPath(body, ink, style = Stroke(3f, join = StrokeJoin.Round))
                    // Chapeau souple et bande orange.
                    val hat = Path().apply {
                        moveTo(45f, 68f); quadraticTo(62f, 58f, 71f, 39f)
                        lineTo(103f, 0f); quadraticTo(112f, -10f, 139f, 8f)
                        lineTo(117f, 12f); lineTo(134f, 49f)
                        quadraticTo(147f, 57f, 154f, 62f)
                        cubicTo(132f, 80f, 65f, 84f, 45f, 68f); close()
                    }
                    drawPath(hat, Color(0xFF663C86)); drawPath(hat, ink, style = Stroke(3f))
                    val ribbon = Path().apply { moveTo(70f,40f); lineTo(128f,36f); lineTo(134f,49f); quadraticTo(101f,61f,63f,56f); close() }
                    drawPath(ribbon, orange)
                    drawRoundRect(Color(0xFFFFD26D), Offset(94f, 40f), Size(15f, 15f), CornerRadius(3f))
                    drawRect(ink, Offset(98f,44f), Size(7f,7f))
                    // Regard malicieux et sourire, pas un logo statique.
                    drawOval(ink, Offset(76f,90f), Size(10f,16f))
                    drawOval(ink, Offset(116f,87f), Size(10f,16f))
                    drawCircle(Color.White, 2f, Offset(80f,94f)); drawCircle(Color.White, 2f, Offset(120f,91f))
                    val smile = Path().apply { moveTo(90f,110f); quadraticTo(103f,122f,115f,107f) }
                    drawPath(smile, ink, style = Stroke(3f, cap = StrokeCap.Round))
                    drawOval(Color(0xFFFFB6AD), Offset(64f,106f), Size(14f,7f))
                    drawOval(Color(0xFFFFB6AD), Offset(127f,103f), Size(14f,7f))
                    // Faucille : manche en bois, lame recourbée argentée, main autour du manche.
                    drawLine(ink, Offset(155f,155f), Offset(181f,91f), 10f, StrokeCap.Round)
                    drawLine(Color(0xFFB97436), Offset(155f,155f), Offset(181f,91f), 6f, StrokeCap.Round)
                    val blade = Path().apply {
                        moveTo(179f,94f); cubicTo(227f,79f,214f,30f,170f,29f)
                        cubicTo(196f,42f,197f,69f,173f,77f); close()
                    }
                    drawPath(blade, Color(0xFFD9E5F4)); drawPath(blade, ink, style = Stroke(2.5f))
                    val shine = Path().apply { moveTo(185f,83f); quadraticTo(211f,63f,191f,42f) }
                    drawPath(shine, Color.White, style = Stroke(3f, cap = StrokeCap.Round))
                    drawOval(Color.White, Offset(144f,128f), Size(24f,17f)); drawOval(ink, Offset(144f,128f), Size(24f,17f), style = Stroke(2.5f))
                    drawOval(Color.White, Offset(38f,125f), Size(24f,17f)); drawOval(ink, Offset(38f,125f), Size(24f,17f), style = Stroke(2.5f))
                }
            }
        }
        if (!rushing) repeat(3) { i ->
            drawCircle(orange.copy(alpha = .3f + .7f * ((sin(phase - i * .8f) + 1f) / 2f)), 3.dp.toPx(), Offset(center.x + (i - 1) * 16.dp.toPx(), center.y + 150.dp.toPx()))
        }
    }
}

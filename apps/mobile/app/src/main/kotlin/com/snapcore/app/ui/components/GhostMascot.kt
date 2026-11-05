package com.snapcore.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Humeur du fantôme de SnapCore : chacune a sa pose, son visage et son animation en boucle.
 * Le personnage est celui du tirer-pour-actualiser ([RefreshGhost]).
 */
enum class GhostMood {
    /** Salue d'une main. */
    WAVE,
    /** Penche la tête, un point d'interrogation au-dessus. */
    CURIOUS,
    /** Balaie l'horizon du regard. */
    SEARCH,
    /** Main au menton, regard en l'air, points de suspension. */
    THINKING,
    /** Rebondit, clin d'œil et langue tirée. */
    HAPPY,
    /** Se cache les yeux, et jette parfois un œil entre ses doigts. */
    SHY,
    /** Yeux en cœur, petits cœurs qui s'envolent. */
    LOVE,
    /** Saute les bras en l'air, entouré d'étincelles. */
    CELEBRATE,
    /** Bouche ronde et goutte de sueur : il tremble un instant. */
    OOPS,
}

private val Ink = Color(0xFF202124)
private val Tongue = Color(0xFFFF508C)
private val Blush = Color(0xFFFF8FB1)
private val Heart = Color(0xFFF23C57)
private val Spark = listOf(Color(0xFFFF475B), Color(0xFFFFA337), Color(0xFFFFD60A), Color(0xFF42E66C), Color(0xFF38CFFF), Color(0xFF9768FF))

/** Corps du fantôme dans un repère de 100 × 100 (le même que celui du tirer-pour-actualiser). */
internal fun ghostBodyPath() = Path().apply {
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

/**
 * Le fantôme animé. Il rebondit à chaque changement d'[mood] (ou de [key]), puis joue la boucle de cette
 * humeur ; il cligne des yeux de temps en temps. Décoratif : le texte qu'il « dit » porte le sens.
 */
@Composable
fun GhostMascot(mood: GhostMood, modifier: Modifier = Modifier, key: Any? = null) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { time.floatValue = (it - start) / 1000f }
    }
    val pop = remember { Animatable(0f) }
    val moodStart = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(mood, key) {
        moodStart.floatValue = time.floatValue
        pop.snapTo(0f)
        pop.animateTo(1f, spring(dampingRatio = .42f, stiffness = Spring.StiffnessMediumLow))
    }
    val body = remember { ghostBodyPath() }
    Canvas(modifier.clearAndSetSemantics {}) {
        drawGhost(body, mood, time.floatValue, time.floatValue - moodStart.floatValue, pop.value)
    }
}

private fun DrawScope.drawGhost(body: Path, mood: GhostMood, t: Float, sinceMood: Float, pop: Float) {
    // Repère de 124 unités : le fantôme (100) et la place de ses effets autour.
    val u = size.minDimension / 124f
    translate(size.width / 2f - 62f * u, size.height / 2f - 58f * u) {
        scale(u, pivot = Offset.Zero) { drawScene(body, mood, t, sinceMood, pop) }
    }
}

private fun DrawScope.drawScene(body: Path, mood: GhostMood, t: Float, sinceMood: Float, pop: Float) {
    // Position et pose par défaut : flotte doucement.
    var lift = sin(t * 2.4f) * 2.5f
    var tilt = 0f
    var squashX = 1f
    var squashY = 1f
    var shakeX = 0f
    var look = Offset.Zero
    var leftHand = Offset(21.5f, 58f)
    var rightHand = Offset(80.5f, 58f)
    when (mood) {
        GhostMood.WAVE -> {
            val wave = sin(t * 9f)
            rightHand = Offset(86f + wave * 5f, 36f - abs(wave) * 2f)
            tilt = -4f + wave * 1.5f
        }
        GhostMood.CURIOUS -> {
            tilt = 9f + sin(t * 1.6f) * 3f
            look = Offset(2.5f, -1f)
        }
        GhostMood.SEARCH -> {
            val scan = sin(t * 1.8f)
            look = Offset(scan * 3.5f, 0f)
            tilt = scan * 4f
            rightHand = Offset(66f, 24f)
        }
        GhostMood.THINKING -> {
            look = Offset(2.5f, -2.5f)
            rightHand = Offset(60f, 52f)
            tilt = 5f
        }
        GhostMood.HAPPY -> {
            val hop = abs(sin(t * 4.5f))
            lift = hop * 9f
            // Écrasé au contact du sol, étiré en l'air.
            squashY = 1f - (1f - hop).let { it * it * it } * .1f + hop * .03f
            squashX = 2f - squashY
        }
        GhostMood.SHY -> {
            // Toutes les 3 s, la main droite descend un instant : il jette un œil.
            val cycle = (t % 3f) / 3f
            val peek = if (cycle in .55f..0.8f) sin((cycle - .55f) / .25f * PI.toFloat()) else 0f
            leftHand = Offset(37f, 35f)
            rightHand = Offset(63f, 35f + peek * 14f)
            tilt = sin(t * 1.5f) * 3f
        }
        GhostMood.LOVE -> {
            tilt = sin(t * 2f) * 5f
            leftHand = Offset(20f, 52f)
            rightHand = Offset(82f, 52f)
        }
        GhostMood.CELEBRATE -> {
            val hop = abs(sin(t * 5f))
            lift = hop * 13f
            squashY = 1f - (1f - hop).let { it * it * it } * .12f + hop * .04f
            squashX = 2f - squashY
            val wave = sin(t * 10f) * 4f
            leftHand = Offset(14f - wave, 26f)
            rightHand = Offset(88f + wave, 26f)
        }
        GhostMood.OOPS -> {
            val fade = (1f - sinceMood / .7f).coerceIn(0f, 1f)
            shakeX = sin(t * 42f) * 3f * fade
            leftHand = Offset(24f, 50f)
            rightHand = Offset(77f, 50f)
            look = Offset(0f, -1f)
        }
    }
    // Apparition : il jaillit d'un peu plus bas en grossissant (avec rebond du ressort).
    val appear = pop.coerceAtLeast(0f)
    val popScale = .55f + .45f * pop
    val popLift = (1f - appear.coerceAtMost(1f)) * -14f

    // Ombre au sol, plus petite quand il s'élève.
    val shadowWidth = 56f * (1f - lift / 40f) * popScale
    drawOval(Color.Black.copy(alpha = .10f * appear.coerceAtMost(1f)), Offset(50f - shadowWidth / 2f, 104f), Size(shadowWidth, 7f))

    behindEffects(mood, t)

    withTransform({
        translate(shakeX, -lift - popLift)
        rotate(tilt, Offset(50f, 96f))
        scale(squashX * popScale, squashY * popScale, Offset(50f, 96f))
    }) {
        drawPath(body, Color.White)
        drawPath(body, Ink, style = Stroke(2.6f))
        drawFace(mood, t, look)
        // Pour se cacher les yeux, ses mains s'élargissent et couvrent tout le regard.
        val cover = mood == GhostMood.SHY
        hand(leftHand, if (cover) 19f else 13f, if (cover) 15f else 10f)
        hand(rightHand, if (cover) 19f else 13f, if (cover) 15f else 10f)
    }
    frontEffects(mood, t, lift)
}

private fun DrawScope.hand(center: Offset, width: Float = 13f, height: Float = 10f) {
    drawOval(Color.White, center - Offset(width / 2f, height / 2f), Size(width, height))
    drawOval(Ink, center - Offset(width / 2f, height / 2f), Size(width, height), style = Stroke(2.2f))
}

private fun DrawScope.drawFace(mood: GhostMood, t: Float, look: Offset) {
    // Clignement : 130 ms toutes les 3,4 s.
    val blink = if (t % 3.4f > 3.27f) .12f else 1f
    val left = Offset(37.75f, 35.5f) + look
    val right = Offset(62.75f, 35.5f) + look
    fun eye(c: Offset, open: Float = blink) = drawOval(Ink, Offset(c.x - 2.75f, c.y - 4.5f * open), Size(5.5f, 9f * open))
    val stroke = Stroke(2.5f, cap = StrokeCap.Round)

    // Joues roses.
    val blushAlpha = when (mood) { GhostMood.SHY, GhostMood.LOVE -> .7f; else -> .35f }
    drawOval(Blush.copy(alpha = blushAlpha), Offset(27f, 42f) + look * .5f, Size(8f, 4f))
    drawOval(Blush.copy(alpha = blushAlpha), Offset(65f, 42f) + look * .5f, Size(8f, 4f))

    when (mood) {
        GhostMood.HAPPY -> {
            // Le visage du tirer-pour-actualiser : clin d'œil et langue.
            eye(left)
            drawPath(Path().apply { moveTo(59f, 36f); quadraticTo(61f, 30f, 67f, 32f) }, Ink, style = stroke)
            val tongue = Path().apply { moveTo(54f, 48f); lineTo(63f, 45f); quadraticTo(66f, 59f, 58f, 55f); close() }
            drawPath(tongue, Tongue)
            drawPath(tongue, Ink, style = Stroke(1.6f))
            drawPath(Path().apply { moveTo(35f, 44f); cubicTo(40f, 54f, 55f, 53f, 65f, 43f) }, Ink, style = stroke)
        }
        GhostMood.CELEBRATE -> {
            // Yeux fermés de joie « ^ ^ » et grande bouche ouverte.
            for (c in listOf(left, right)) drawPath(Path().apply {
                moveTo(c.x - 4f, c.y + 1.5f); lineTo(c.x, c.y - 3f); lineTo(c.x + 4f, c.y + 1.5f)
            }, Ink, style = stroke)
            val mouth = Path().apply { moveTo(40f, 44f); quadraticTo(50f, 44f, 60f, 44f); cubicTo(59f, 56f, 41f, 56f, 40f, 44f); close() }
            drawPath(mouth, Ink)
            drawOval(Tongue, Offset(45f, 49f), Size(10f, 4.5f))
        }
        GhostMood.LOVE -> {
            val beat = 1f + sin(t * 8f).coerceAtLeast(0f) * .18f
            heart(left, 4.6f * beat, Heart)
            heart(right, 4.6f * beat, Heart)
            drawPath(Path().apply { moveTo(41f, 45f); cubicTo(44f, 50f, 56f, 50f, 59f, 45f) }, Ink, style = stroke)
        }
        GhostMood.SHY -> {
            // Les mains cachent les yeux ; on ne voit qu'un sourire gêné.
            eye(right)
            drawPath(Path().apply { moveTo(43f, 47f); quadraticTo(50f, 51f, 57f, 47f) }, Ink, style = stroke)
        }
        GhostMood.OOPS -> {
            eye(left, 1f); eye(right, 1f)
            // Sourcils inquiets et bouche ronde.
            drawLine(Ink, Offset(33f, 27f), Offset(40f, 25f), 2.2f, StrokeCap.Round)
            drawLine(Ink, Offset(60f, 25f), Offset(67f, 27f), 2.2f, StrokeCap.Round)
            drawOval(Ink, Offset(46f, 44f), Size(8f, 9f))
        }
        GhostMood.THINKING -> {
            eye(left); eye(right)
            drawLine(Ink, Offset(44f, 48f), Offset(54f, 46.5f), 2.5f, StrokeCap.Round)
        }
        else -> {
            eye(left); eye(right)
            drawPath(Path().apply { moveTo(40f, 45f); cubicTo(44f, 51f, 56f, 51f, 60f, 45f) }, Ink, style = stroke)
        }
    }
}

private fun DrawScope.heart(center: Offset, radius: Float, color: Color) {
    val r = radius
    val heart = Path().apply {
        moveTo(center.x, center.y + r * 1.1f)
        cubicTo(center.x - r * 1.9f, center.y - r * .2f, center.x - r * .9f, center.y - r * 1.5f, center.x, center.y - r * .45f)
        cubicTo(center.x + r * .9f, center.y - r * 1.5f, center.x + r * 1.9f, center.y - r * .2f, center.x, center.y + r * 1.1f)
        close()
    }
    drawPath(heart, color)
}

/** Effets dessinés derrière le fantôme. */
private fun DrawScope.behindEffects(mood: GhostMood, t: Float) {
    if (mood != GhostMood.CELEBRATE) return
    // Étincelles qui tournent lentement autour de lui.
    repeat(8) { i ->
        val angle = t * .9f + i * (2 * PI.toFloat() / 8)
        val radius = 52f + sin(t * 3f + i) * 4f
        val c = Offset(50f + cos(angle) * radius, 52f + sin(angle) * radius * .8f)
        val s = 2.6f + sin(t * 6f + i * 1.7f) * 1.2f
        sparkle(c, s, Spark[i % Spark.size])
    }
}

private fun DrawScope.sparkle(c: Offset, s: Float, color: Color) {
    val star = Path().apply {
        moveTo(c.x, c.y - s * 2f); quadraticTo(c.x, c.y, c.x + s * 2f, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + s * 2f); quadraticTo(c.x, c.y, c.x - s * 2f, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - s * 2f); close()
    }
    drawPath(star, color)
}

/** Effets dessinés devant : « ? », « … », cœurs, goutte de sueur. */
private fun DrawScope.frontEffects(mood: GhostMood, t: Float, lift: Float) {
    when (mood) {
        GhostMood.CURIOUS -> {
            val bob = sin(t * 3f) * 2f
            val q = Path().apply {
                moveTo(86f, 8f + bob); cubicTo(86f, 0f + bob, 99f, 0f + bob, 99f, 8f + bob)
                cubicTo(99f, 13f + bob, 92.5f, 13f + bob, 92.5f, 19f + bob)
            }
            drawPath(q, Ink, style = Stroke(3f, cap = StrokeCap.Round))
            drawCircle(Ink, 1.9f, Offset(92.5f, 25f + bob))
        }
        GhostMood.THINKING -> {
            // Trois points qui apparaissent l'un après l'autre.
            val shown = ((t * 2.2f) % 4f).toInt()
            repeat(3) { i ->
                val alpha = if (i < shown) 1f else .18f
                drawCircle(Ink.copy(alpha = alpha), 2.6f, Offset(84f + i * 8f, 10f - i * 3f))
            }
        }
        GhostMood.LOVE -> {
            // Petits cœurs qui montent et s'estompent.
            repeat(3) { i ->
                val p = ((t * .55f + i / 3f) % 1f)
                val x = 82f + sin(p * 9f + i) * 5f + i * 4f
                val y = 40f - p * 44f
                heart(Offset(x, y), 3.2f + i * .6f, Heart.copy(alpha = 1f - p))
            }
        }
        GhostMood.OOPS -> {
            val drop = Path().apply {
                moveTo(79f, 14f - lift); quadraticTo(84f, 22f - lift, 79f, 25f - lift)
                quadraticTo(74f, 22f - lift, 79f, 14f - lift); close()
            }
            drawPath(drop, Color(0xFF38CFFF))
        }
        else -> Unit
    }
}

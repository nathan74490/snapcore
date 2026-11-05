package com.snapcore.app.ui.onboarding

import com.snapcore.app.ui.components.screenReaderFocusOnAppear
import com.snapcore.app.ui.components.rememberScreenReaderOn
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapcore.app.ui.components.GhostMascot
import com.snapcore.app.ui.components.GhostMood
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.delay

// Parcours d'arrivée façon Duolingo : le fantôme de SnapCore pose chaque question dans une bulle, au-dessus
// d'une barre de progression commune à l'inscription et aux premiers pas.

/** Avancement du parcours complet : inscription, puis les cinq écrans des premiers pas (ADR-029, ADR-034). */
object Journey {
    const val FIRST_STEPS = 5
    const val SIGN_UP_STEPS = 5

    /** Étape [step] (à partir de 1) d'une inscription en [steps] étapes : questions déjà répondues. */
    fun signUp(step: Int, steps: Int = SIGN_UP_STEPS): Float = ((step - 1).toFloat() / (steps + FIRST_STEPS)).coerceIn(0f, 1f)

    /** Écran [index] (à partir de 0) des premiers pas, après une inscription complète. */
    fun firstSteps(index: Int): Float = ((SIGN_UP_STEPS + index).toFloat() / (SIGN_UP_STEPS + FIRST_STEPS)).coerceIn(0f, 1f)

    /** Avancement lu par TalkBack avant la question : « Étape 2 sur 11 ». */
    fun signUpLabel(step: Int, steps: Int = SIGN_UP_STEPS) = "Étape $step sur ${steps + FIRST_STEPS}"

    fun firstStepsLabel(index: Int) = "Étape ${(SIGN_UP_STEPS + index + 1).coerceAtMost(SIGN_UP_STEPS + FIRST_STEPS)} sur ${SIGN_UP_STEPS + FIRST_STEPS}"
}

/** Étape courante du parcours, lue par TalkBack juste avant la question du fantôme (fournie par l'écran). */
val LocalJourneyStep = compositionLocalOf<String?> { null }

/**
 * Barre du haut : retour (facultatif), barre de progression animée et « Passer » (facultatif).
 * La barre se remplit avec un léger rebond à chaque réponse.
 */
@Composable
fun JourneyBar(progress: Float, onBack: (() -> Unit)? = null, onSkip: (() -> Unit)? = null) {
    val shown by animateFloatAsState(progress, spring(dampingRatio = .55f, stiffness = Spring.StiffnessLow), label = "progression")
    // TalkBack : la barre est lue après le contenu de l'écran (question, champs, Continuer), pour que chaque écran
    // commence par sa question. La progression elle-même est annoncée avec la question (LocalJourneyStep).
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp).semantics { traversalIndex = 2f },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(56.dp), Alignment.Center) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = SnapColors.Muted) }
        }
        Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(SnapColors.Control)
            .clearAndSetSemantics {}) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f).coerceAtLeast(.04f)).clip(RoundedCornerShape(8.dp))
                .background(SnapColors.Yellow)) {
                // Reflet clair sur le haut de la barre, comme une pastille de bonbon.
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 3.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .55f)))
            }
        }
        Box(Modifier.widthIn(min = 56.dp), Alignment.Center) {
            if (onSkip != null) Text("Passer", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SnapColors.Grey,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "passer cette étape", onClick = onSkip)
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/**
 * Le fantôme et sa bulle : il « dit » [text], lettre après lettre, et rebondit à chaque nouvelle phrase.
 * Le texte complet est réservé dès le départ : la mise en page ne bouge pas pendant l'écriture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GhostSays(text: String, mood: GhostMood, modifier: Modifier = Modifier, ghostSize: Dp = 96.dp) {
    // Clavier ouvert : le fantôme et sa bulle se font plus petits pour laisser la place aux champs.
    val compact = WindowInsets.isImeVisible
    val size by animateDpAsState(if (compact) ghostSize * .66f else ghostSize, label = "taille du fantôme")
    // TalkBack : la question passe en premier dans l'ordre de lecture.
    Row(modifier.fillMaxWidth().semantics { traversalIndex = -1f }, verticalAlignment = Alignment.CenterVertically) {
        GhostMascot(mood, Modifier.size(size), key = text)
        SpeechBubble(text, compact, Modifier.weight(1f))
    }
}

@Composable
private fun SpeechBubble(text: String, compact: Boolean, modifier: Modifier) {
    val screenReader = rememberScreenReaderOn()
    // Avec TalkBack, pas d'écriture lettre par lettre ni d'apparition en fondu : la bulle est là tout de suite.
    var typed by remember(text) { mutableIntStateOf(if (screenReader) text.length else 0) }
    LaunchedEffect(text) {
        delay(120)
        while (typed < text.length) { delay(16); typed++ }
    }
    val pop = remember { Animatable(if (screenReader) 1f else 0f) }
    LaunchedEffect(text) {
        if (screenReader) return@LaunchedEffect
        pop.snapTo(.0f)
        pop.animateTo(1f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium))
    }
    // À l'arrivée sur l'écran, TalkBack se place sur la question (le focus suit le focus clavier) et la lit,
    // précédée de l'étape. Ensuite, seule une phrase qui change sur le même écran est annoncée (région live) :
    // sinon TalkBack lirait la question deux fois.
    val step = LocalJourneyStep.current
    val firstText = remember { text }
    val border = SnapColors.Hairline
    Box(
        modifier
            .graphicsLayer {
                val s = .8f + .2f * pop.value
                scaleX = s; scaleY = s
                alpha = pop.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, .5f)
            }
            .drawBehind {
                val tail = 10.dp.toPx()
                val stroke = 2.dp.toPx()
                val radius = 18.dp.toPx()
                val bubble = Path().apply {
                    addRoundRect(RoundRect(tail, stroke / 2, size.width - stroke / 2, size.height - stroke / 2, CornerRadius(radius)))
                }
                val mid = size.height / 2f
                val pointer = Path().apply {
                    moveTo(tail + stroke, mid - 9.dp.toPx()); lineTo(stroke / 2, mid); lineTo(tail + stroke, mid + 9.dp.toPx()); close()
                }
                val shape = Path().apply { op(bubble, pointer, PathOperation.Union) }
                drawPath(shape, Color.White)
                drawPath(shape, border, style = Stroke(stroke))
            }
            .padding(start = 26.dp, end = 16.dp, top = if (compact) 10.dp else 14.dp, bottom = if (compact) 10.dp else 14.dp),
    ) {
        Text(
            buildAnnotatedString {
                append(text.take(typed))
                withStyle(SpanStyle(color = Color.Transparent)) { append(text.drop(typed)) }
            },
            fontSize = if (compact) 15.sp else 17.sp, lineHeight = if (compact) 20.sp else 23.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink,
            // TalkBack lit la phrase entière dès son apparition (pas lettre par lettre), et la relit si elle change
            // sur le même écran (erreur de connexion, pseudo déjà pris).
            modifier = Modifier
                .screenReaderFocusOnAppear()
                .semantics {
                    heading()
                    contentDescription = listOfNotNull(step, text).joinToString(". ")
                    if (text != firstText) liveRegion = LiveRegionMode.Polite
                },
        )
    }
}

/** Le fantôme en grand, centré, quand l'écran n'a rien d'autre à montrer (notifications). */
@Composable
fun BigGhost(mood: GhostMood, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    Box(modifier, Alignment.Center) { GhostMascot(mood, Modifier.size(size)) }
}


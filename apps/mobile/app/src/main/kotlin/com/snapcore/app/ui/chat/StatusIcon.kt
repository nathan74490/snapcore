package com.snapcore.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snapcore.app.domain.MessageStatus
import com.snapcore.app.domain.StatusColor
import com.snapcore.app.domain.StatusShape
import com.snapcore.app.ui.theme.SnapColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.Duration
import java.time.Instant

fun StatusColor.toColor(): Color = when (this) {
    StatusColor.RED -> SnapColors.SnapRed
    StatusColor.PURPLE -> SnapColors.SnapPurple
    StatusColor.BLUE -> SnapColors.ChatBlue
    StatusColor.GREY -> SnapColors.Grey
}

// Carré (Snap) ou flèche (Chat), plein si non ouvert, en contour sinon (ADR-009).
// Pour TalkBack (ADR-033), l'icône n'est jamais muette : sa ligne lit [spoken] (« snap envoyé »). Seule, elle
// porte ce texte si [describe] est vrai ; dans une ligne qui le lit déjà, elle reste masquée.
@Composable
fun StatusIcon(status: MessageStatus, size: Dp = 14.dp, modifier: Modifier = Modifier, describe: Boolean = false) {
    val color = status.color.toColor()
    Canvas(modifier.size(size).clearAndSetSemantics { if (describe) contentDescription = status.spoken() }) {
        val style: DrawStyle = if (status.filled) Fill else Stroke(width = this.size.minDimension * 0.16f)
        when (status.shape) {
            StatusShape.SQUARE -> {
                val inset = this.size.minDimension * 0.1f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
                    cornerRadius = CornerRadius(this.size.minDimension * 0.15f),
                    style = style,
                )
            }
            StatusShape.ARROW -> {
                val w = this.size.width
                val h = this.size.height
                val arrow = Path().apply {
                    moveTo(w * 0.1f, h * 0.1f)
                    lineTo(w * 0.95f, h * 0.5f)
                    lineTo(w * 0.1f, h * 0.9f)
                    lineTo(w * 0.3f, h * 0.5f)
                    close()
                }
                drawPath(arrow, color, style = style)
            }
        }
    }
}

/**
 * L'icône de statut en mots, pour TalkBack (ADR-033) : la forme dit snap ou chat, la couleur photo ou vidéo,
 * le libellé l'état. « snap envoyé », « chat ouvert », « nouveau snap », « snap de Léa », « échec de l'envoi du snap ».
 */
fun MessageStatus.spoken(): String {
    val kind = when {
        shape == StatusShape.ARROW -> "chat"
        color == StatusColor.PURPLE -> "snap vidéo"
        else -> "snap"
    }
    val text = label.substringBefore(" ·").trim()
    return when {
        text.startsWith("Échec") -> "échec de l'envoi du $kind"
        text.startsWith("Envoi") -> "$kind en cours d'envoi"
        text.contains("snap", ignoreCase = true) || text.contains("chat", ignoreCase = true) -> text.replaceFirstChar { it.lowercase() }
        else -> "$kind ${text.lowercase()}"
    }
}

/** L'horodatage de [relativeTime] en toutes lettres pour TalkBack : « il y a 5 minutes », « il y a 2 jours ». */
fun spokenRelativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val elapsed = Duration.between(instant, now)
    fun plural(n: Long, word: String) = "il y a $n $word${if (n > 1) "s" else ""}"
    return when {
        elapsed.toMinutes() < 1 -> "à l'instant"
        elapsed.toHours() < 1 -> plural(elapsed.toMinutes(), "minute")
        elapsed.toDays() < 1 -> plural(elapsed.toHours(), "heure")
        else -> plural(elapsed.toDays(), "jour")
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

/** Heure d'un message, lue par TalkBack à la fin de chaque message du fil (« 14:32 »). */
fun clockTime(instant: Instant): String = instant.atZone(java.time.ZoneId.systemDefault()).format(CLOCK)

/** "à l'instant", "5 min", "3 h", "2 j" : l'horodatage compact de la liste de conversations. */
fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val elapsed = Duration.between(instant, now)
    return when {
        elapsed.toMinutes() < 1 -> "à l'instant"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} min"
        elapsed.toDays() < 1 -> "${elapsed.toHours()} h"
        else -> "${elapsed.toDays()} j"
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val DAY_AND_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

/** Séparateur de jour d'une conversation, en majuscules comme dans Snapchat (« 25 SEPTEMBRE »). */
fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "AUJOURD'HUI"
    today.minusDays(1) -> "HIER"
    else -> day.format(if (day.year == today.year) DAY else DAY_AND_YEAR).uppercase(Locale.FRENCH)
}

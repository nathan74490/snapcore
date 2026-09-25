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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snapcore.app.domain.MessageStatus
import com.snapcore.app.domain.StatusColor
import com.snapcore.app.domain.StatusShape
import com.snapcore.app.ui.theme.SnapColors
import java.time.Duration
import java.time.Instant

fun StatusColor.toColor(): Color = when (this) {
    StatusColor.RED -> SnapColors.SnapRed
    StatusColor.PURPLE -> SnapColors.SnapPurple
    StatusColor.BLUE -> SnapColors.ChatBlue
    StatusColor.GREY -> SnapColors.Grey
}

// Carré (Snap) ou flèche (Chat), plein si non ouvert, en contour sinon (ADR-009).
@Composable
fun StatusIcon(status: MessageStatus, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val color = status.color.toColor()
    Canvas(modifier.size(size)) {
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

package com.snapcore.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bouton « en relief », façon Duolingo : une tranche plus foncée dessous, et le bouton s'y enfonce quand on
 * appuie. [border] dessine un bouton contour (fond [container], tranche et bord de la couleur [edge]).
 */
@Composable
fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    container: Color,
    edge: Color,
    content: Color,
    modifier: Modifier = Modifier,
    border: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth = 4.dp
    val sink by animateDpAsState(if (pressed) depth else 0.dp, tween(70), label = "enfoncement")
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().height(52.dp + depth)
        .clickable(interaction, indication = null, role = Role.Button, onClickLabel = text, onClick = onClick)) {
        // La tranche, visible sous le bouton tant qu'il n'est pas enfoncé.
        Box(Modifier.fillMaxWidth().height(52.dp).offset(y = depth).background(edge, shape))
        Box(Modifier.fillMaxWidth().height(52.dp).offset(y = sink).background(container, shape)
            .then(if (border) Modifier.border(2.dp, edge, shape) else Modifier), Alignment.Center) {
            Text(text.uppercase(), color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

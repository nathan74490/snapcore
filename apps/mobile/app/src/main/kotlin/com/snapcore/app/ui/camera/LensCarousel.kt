package com.snapcore.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.abs

// Largeur d'une position du carrousel et taille de l'icône au centre ; les voisines rapetissent.
private val SLOT = 74.dp
private val ICON = 68.dp
private const val SIDE_SCALE = 0.74f
// L'anneau de capture (86) est plus large que l'icône au centre : sans ce décalage, la première Lens
// le touche presque (≈ 6) alors que les suivantes sont espacées de ≈ 24. Les voisines s'écartent du
// centre pour que tous les espaces soient égaux : SLOT - 86/2 - ICON·SIDE_SCALE/2 + PUSH = SLOT - ICON·SIDE_SCALE.
private val PUSH = 18.dp

/** Position du carrousel qui se trouve au centre (sous l'anneau de capture) ; 0 = aucune Lens. */
fun LazyListState.centeredIndex(): Int {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: 0
}

/**
 * Carrousel des Lenses façon Snapchat (ADR-024) : la position au centre, sous l'anneau du bouton de
 * capture, est la Lens active ; le défilement s'aimante sur une position, et la première (un anneau
 * vide) retire la Lens. Au repos, il est sur cette première position : les Lenses dépassent à droite
 * du bouton de capture.
 */
@Composable
fun LensCarousel(
    lenses: List<Lens>,
    state: LazyListState,
    scrollEnabled: Boolean,
    onTap: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val side = (maxWidth - SLOT) / 2
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            contentPadding = PaddingValues(horizontal = side),
            userScrollEnabled = scrollEnabled,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(SLOT),
        ) {
            item(key = "none") {
                LensSlot(state, 0, onTap) {
                    Box(Modifier.fillMaxSize().border(3.dp, Color.White, CircleShape))
                }
            }
            itemsIndexed(lenses, key = { _, lens -> lens.id }) { i, lens ->
                LensSlot(state, i + 1, onTap) {
                    AsyncImage(
                        model = lens.iconUri,
                        contentDescription = lens.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0x55000000)),
                    )
                }
            }
        }
    }
}

@Composable
private fun LensSlot(state: LazyListState, index: Int, onTap: (Int) -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(SLOT)
            .fillMaxHeight()
            // Taille selon la distance au centre, relue à chaque image du défilement (phase de dessin).
            .graphicsLayer {
                val info = state.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                val signed = if (item == null) 1f else {
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    ((item.offset + item.size / 2f - center) / item.size).coerceIn(-1f, 1f)
                }
                val distance = abs(signed)
                val scale = 1f - (1f - SIDE_SCALE) * distance
                scaleX = scale
                scaleY = scale
                translationX = signed * PUSH.toPx()
                // L'anneau « sans Lens » se confond avec le bouton de capture au centre : visible seulement à côté.
                if (index == 0) alpha = distance
            }
            .clickable(interactionSource = null, indication = null) { onTap(index) },
    ) {
        Box(Modifier.size(ICON).clip(CircleShape)) { content() }
    }
}

/** Remplace la barre de navigation tant qu'une Lens est active : ✕ retire la Lens (ADR-024). */
@Composable
fun LensModeBar(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(55.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Retirer la Lens", tint = Color.White) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text("Pour vous", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        // Équilibre visuel avec le bouton ✕ : le libellé reste centré.
        Box(Modifier.size(48.dp))
    }
}

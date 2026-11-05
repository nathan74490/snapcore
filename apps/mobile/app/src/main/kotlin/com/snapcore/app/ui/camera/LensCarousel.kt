package com.snapcore.app.ui.camera

import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.abs

// Largeur d'une position du carrousel et taille de l'icône au centre ; les voisines rapetissent.
private val SLOT = 74.dp
private val ICON = 68.dp
private const val SIDE_SCALE = 0.74f
/** Diamètre de l'anneau de capture, au repos (Snapchat : 80, contour de 5). */
internal val CAPTURE_RING = 80.dp
// L'anneau de capture est plus large que l'icône au centre : sans ce décalage, la première Lens le
// touche presque alors que les suivantes sont bien espacées. Les voisines s'écartent du centre pour que
// tous les espaces soient égaux : SLOT - CAPTURE_RING/2 - ICON·SIDE_SCALE/2 + PUSH = SLOT - ICON·SIDE_SCALE.
private val PUSH = CAPTURE_RING / 2 - ICON * (SIDE_SCALE / 2)

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
    /** Lens appliquée, quand elle ne dépend pas du centre du carrousel (TalkBack, ADR-033). */
    selectedIndex: Int? = null,
) {
    // Pendant l'enregistrement, le carrousel est invisible : TalkBack ne doit pas le lire non plus.
    BoxWithConstraints(if (scrollEnabled) modifier else modifier.clearAndSetSemantics {}) {
        val side = (maxWidth - SLOT) / 2
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            contentPadding = PaddingValues(horizontal = side),
            userScrollEnabled = scrollEnabled,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(SLOT).selectableGroup(),
        ) {
            item(key = "none") {
                LensSlot(state, 0, "Sans Lens", onTap, selectedIndex) {
                    Box(Modifier.fillMaxSize().border(3.dp, Color.White, CircleShape))
                }
            }
            itemsIndexed(lenses, key = { _, lens -> lens.id }) { i, lens ->
                LensSlot(state, i + 1, "Lens " + lens.name.orEmpty(), onTap, selectedIndex) {
                    AsyncImage(
                        model = lens.iconUri,
                        // Le nom est porté par la position du carrousel (choix exclusif pour TalkBack).
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0x55000000)),
                    )
                }
            }
        }
    }
}

@Composable
private fun LensSlot(state: LazyListState, index: Int, label: String, onTap: (Int) -> Unit, selectedIndex: Int?,
    content: @Composable () -> Unit) {
    // Choix exclusif pour TalkBack : « Lens Chien, sélectionné » pour celle qui est appliquée (ADR-033).
    val centered by remember(state, index) { derivedStateOf { state.centeredIndex() == index } }
    val selected = selectedIndex?.let { it == index } ?: centered
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
            .selectable(selected, interactionSource = null, indication = null, role = Role.RadioButton) { onTap(index) }
            .semantics { onClick(if (index == 0) "retirer la Lens" else "appliquer cette Lens") { onTap(index); true } }
            .semantics { contentDescription = label },
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
            Text("Pour toi", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        // Équilibre visuel avec le bouton ✕ : le libellé reste centré.
        Box(Modifier.size(48.dp))
    }
}

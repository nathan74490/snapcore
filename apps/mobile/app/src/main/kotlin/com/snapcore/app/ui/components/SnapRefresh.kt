package com.snapcore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snapcore.app.ui.theme.SnapColors

/** Tirage natif, puis envol du personnage synchronisé avec la fermeture de la bande jaune. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { GhostRefreshState() }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    PullToRefreshBox(
        // Le mouvement suit le geste, indépendamment de la durée du réseau.
        isRefreshing = false,
        onRefresh = { state.launchRequested = true; if (!refreshing) onRefresh() },
        state = state,
        modifier = modifier.clipToBounds().background(SnapColors.Yellow).semantics {
            customActions = listOf(CustomAccessibilityAction("Actualiser") { onRefresh(); true })
        },
        indicator = {},
    ) {
        RefreshGhost(state, threshold)
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = state.distanceFraction * threshold
        }.background(Color.White), content = content)
    }
}

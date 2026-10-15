package com.snapcore.app.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Couleur des icônes de la barre d'état pour l'écran courant : sombres sur fond clair (jaune
 * SnapCore), claires sur fond sombre (caméra). Rétablit la valeur précédente en quittant l'écran.
 */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(dark) {
        val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = dark
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}

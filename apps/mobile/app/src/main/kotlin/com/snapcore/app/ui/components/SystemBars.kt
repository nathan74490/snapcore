package com.snapcore.app.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import android.os.Build
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Couleur des icônes de la barre d'état pour l'écran courant : sombres sur fond clair (jaune
 * SnapCore), claires sur fond sombre (caméra). L'écran visible réapplique sa couleur après chaque
 * composition : restaurer une ancienne couleur à la destruction créait une course à la navigation.
 */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        controller.isAppearanceLightNavigationBars = dark
        controller.isAppearanceLightStatusBars = dark
    }
}

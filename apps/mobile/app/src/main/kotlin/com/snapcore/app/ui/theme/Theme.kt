package com.snapcore.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SnapColors {
    val Yellow = Color(0xFFFFFC00)
    val Black = Color(0xFF000000)
    // Couleurs des icônes de statut (ADR-009) : Chat, Snap photo, Snap vidéo.
    val ChatBlue = Color(0xFF0EADFF)
    val SnapRed = Color(0xFFF23C57)
    val SnapPurple = Color(0xFFA05DCD)
    val Grey = Color(0xFF9B9B9B)
}

private val Light = lightColorScheme(
    primary = SnapColors.Black,
    onPrimary = Color.White,
    secondary = SnapColors.ChatBlue,
    tertiary = SnapColors.Yellow,
    onTertiary = SnapColors.Black,
    error = SnapColors.SnapRed,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F2F2),
)

private val Dark = darkColorScheme(
    primary = Color.White,
    onPrimary = SnapColors.Black,
    secondary = SnapColors.ChatBlue,
    tertiary = SnapColors.Yellow,
    onTertiary = SnapColors.Black,
    error = SnapColors.SnapRed,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF232323),
)

private val SnapTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun SnapCoreTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = SnapTypography, content = content)
}

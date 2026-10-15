package com.snapcore.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import com.snapcore.app.R

/** Tokens from Snapchat iOS – Dec 2024, Figma file 3FKjYfJBszywgA8U2p4vUt. */
object SnapColors {
    val Yellow = Color(0xFFFFFC00)
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF16191C)
    val ChatBlue = Color(0xFF0FADFF)
    val SnapRed = Color(0xFFF23C57)
    val SnapPurple = Color(0xFF9B31E7)
    val Grey = Color(0xFF656D78)
    val Muted = Color(0xFF9AA2AE)
    val Background = Color(0xFFF6F8FA)
    val Control = Color(0xFFECEEEF)
    val Border = Color(0xFFEEEEEE)
    val Selected = Color(0xFFE7F8FF)
    val Disabled = Color(0xFFB9C1C7)
    val Gold = Color(0xFFF0CD7B)
}

val SnapFont = FontFamily(
    Font(R.font.nunito_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("YTLC", 500f))),
    Font(R.font.nunito_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("YTLC", 500f))),
    Font(R.font.nunito_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("YTLC", 500f))),
)

private fun snapStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = SnapFont, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
)
private val SnapTypography = Typography(
    displayLarge = snapStyle(48, 54, FontWeight.Bold), displayMedium = snapStyle(40, 46, FontWeight.Bold), displaySmall = snapStyle(36, 40, FontWeight.Bold),
    headlineLarge = snapStyle(32, 38, FontWeight.Bold), headlineMedium = snapStyle(26, 32, FontWeight.Bold), headlineSmall = snapStyle(23, 26, FontWeight.Bold),
    titleLarge = snapStyle(22, 26, FontWeight.Bold), titleMedium = snapStyle(18, 22, FontWeight.Bold), titleSmall = snapStyle(17, 22, FontWeight.SemiBold),
    bodyLarge = snapStyle(17, 22), bodyMedium = snapStyle(15, 20), bodySmall = snapStyle(13, 17),
    labelLarge = snapStyle(17, 22, FontWeight.Bold), labelMedium = snapStyle(13, 17, FontWeight.Bold), labelSmall = snapStyle(11, 14, FontWeight.SemiBold),
)
private val Light = lightColorScheme(
    primary = SnapColors.ChatBlue, onPrimary = Color.White,
    secondary = SnapColors.ChatBlue, tertiary = SnapColors.Yellow, onTertiary = SnapColors.Ink,
    error = SnapColors.SnapRed, background = Color.White, surface = Color.White,
    onBackground = SnapColors.Ink, onSurface = SnapColors.Ink,
    surfaceVariant = SnapColors.Control, onSurfaceVariant = SnapColors.Grey,
    surfaceContainer = Color.White, outline = SnapColors.Muted, outlineVariant = SnapColors.Border,
)
private val Dark = darkColorScheme(primary = SnapColors.ChatBlue, secondary = SnapColors.ChatBlue,
    tertiary = SnapColors.Yellow, error = SnapColors.SnapRed, background = Color(0xFF121212), surface = Color(0xFF121212))

@Composable
fun SnapCoreTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = SnapTypography, content = content)
}

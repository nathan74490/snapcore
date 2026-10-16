package com.snapcore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.svg.SvgDecoder
import com.snapcore.app.ui.theme.SnapColors

private var sharedFigmaLoader: ImageLoader? = null

/** Original Figma exports, packaged in the APK. Never fetches a temporary Figma URL. */
@Composable
fun FigmaAsset(key: String, description: String? = null, modifier: Modifier = Modifier, tint: Color? = null) {
    val asset = requireNotNull(figmaAssets[key]) { "Unknown Figma asset: $key" }
    val context = LocalContext.current
    val loader = remember(context) { sharedFigmaLoader ?: ImageLoader.Builder(context.applicationContext).components { add(SvgDecoder.Factory()) }.build().also { sharedFigmaLoader = it } }
    AsyncImage(
        model = "file:///android_asset/figma/${asset.file}", imageLoader = loader,
        contentDescription = description, contentScale = ContentScale.Fit,
        colorFilter = tint?.let { ColorFilter.tint(it) },
        modifier = modifier.size(asset.width.dp, asset.height.dp),
    )
}

@Composable
fun RoundAction(asset: String, description: String, onClick: () -> Unit, dark: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).background(if(dark) Color.Black.copy(alpha=.25f) else SnapColors.Control)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        FigmaAsset(asset, description, tint = if(dark) Color.White else SnapColors.Ink)
    }
}

@Composable
fun SnapHeader(title: String, onBack: () -> Unit, subtitle: String? = null, background: Color = Color.White, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.background(background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                FigmaAsset("13-250_imgIconChevronLeft", "Retour", tint = SnapColors.Ink)
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                // Nav Header 10:42 : titre Snap/Title (Bold 20/26), sous-titre Snap/Caption Bold.
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SnapColors.Muted) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Case ronde de sélection, composant Checkbox 9:92 (26pt). */
@Composable
fun SnapCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    FigmaAsset(if (checked) "9-92_imgStateChecked" else "9-92_imgStateUnchecked", modifier = modifier)
}

enum class ChipStyle(val container: Color, val content: Color) {
    Yellow(SnapColors.Yellow, SnapColors.Ink),
    Gray(SnapColors.Control, SnapColors.Ink),
    Blue(SnapColors.ChatBlue, Color.White),
    GrayDanger(SnapColors.Control, SnapColors.SnapRed),
}

/** Petit bouton pilule, composant Chip Button 9:64 : 36pt min, rayon 18, Snap/Body Bold. */
@Composable
fun ChipButton(label: String, onClick: () -> Unit, style: ChipStyle = ChipStyle.Yellow, showIcon: Boolean = false, enabled: Boolean = true) {
    Row(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(style.container)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showIcon) FigmaAsset("9-64_imgIconAddFriend", tint = style.content)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = style.content)
    }
}

/** Barre de recherche, composant Search Bar 9:119 : 38pt, rayon 19, fond #ECEEEF. */
@Composable
fun SnapSearchBar(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(SnapColors.Control).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FigmaAsset("9-119_imgIconSearch")
        BasicTextField(
            value, onValueChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
            cursorBrush = SolidColor(SnapColors.ChatBlue),
            keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Default),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            modifier = Modifier.weight(1f),
            decorationBox = { field -> Box { if (value.isEmpty()) Text(placeholder, color = SnapColors.Muted, style = MaterialTheme.typography.bodyLarge); field() } },
        )
    }
}

/** Titre de section des listes groupées (Send To, Add Friends) : Snap/Body Bold. */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = SnapColors.Ink, modifier = modifier)
}

/** Carte blanche arrondie (12) qui groupe des lignes sur fond #F6F8FA. */
@Composable
fun SnapCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White), content = content)
}

/** Interrupteur, composant Toggle 9:101 : 51×31, vert #02B791 (Notifications) ou bleu (Snapchat+), éteint #E9E9EB. */
@Composable
fun SnapToggle(checked: Boolean, modifier: Modifier = Modifier, onColor: Color = Color(0xFF02B791)) {
    Box(
        modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(15.5.dp)).background(if (checked) onColor else Color(0xFFE9E9EB)).padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(27.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
    }
}

package com.snapcore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
fun SnapHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.background(Color.White).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                FigmaAsset("13-250_imgIconChevronLeft", "Retour", tint = SnapColors.Ink)
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SnapColors.Muted) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

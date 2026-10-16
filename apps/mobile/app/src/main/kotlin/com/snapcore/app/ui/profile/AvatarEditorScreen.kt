package com.snapcore.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.snapcore.app.data.avatarImageUrl
import com.snapcore.app.domain.Account
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.SnapHeader
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

// Éditeur d'avatar maison (ADR-028) : aperçu en grand, catégories en puces, et pour chacune une grille
// de vignettes rendues par le backend avec ce choix appliqué (ou des pastilles pour les couleurs).
@Composable
fun AvatarEditorScreen(account: Account, onBack: () -> Unit, onSaved: (Account) -> Unit) {
    val vm = containerViewModel { AvatarEditorViewModel(it.accounts, account.avatar) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    LaunchedEffect(state.error) { if (state.catalog != null) state.error?.let { snackbar.showSnackbar(it) } }

    StatusBarIcons(dark = true)
    Scaffold(
        containerColor = SnapColors.Background,
        topBar = {
            SnapHeader(if (account.avatar == null) "Créer mon avatar" else "Mon avatar", onBack, background = SnapColors.Background) {
                if (account.avatar != null) TextButton(onClick = vm::remove, enabled = !state.saving) { Text("Retirer", color = SnapColors.SnapRed) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.catalog != null) {
                Box(Modifier.navigationBarsPadding().padding(16.dp)) {
                    PrimaryButton("Enregistrer", vm::save, loading = state.saving, container = SnapColors.Yellow, content = SnapColors.Ink)
                }
            }
        },
    ) { padding ->
        val catalog = state.catalog
        if (catalog == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                else TextButton(onClick = vm::load) { Text("${state.error ?: "Erreur"} · Réessayer") }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), Alignment.Center) {
                AsyncImage(avatarImageUrl(state.options, size = 512), "Aperçu de mon avatar", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(200.dp).clip(CircleShape).background(SnapColors.Control))
                // Dé : un avatar tiré au sort, comme point de départ.
                Box(Modifier.offset(x = 84.dp, y = 76.dp).size(48.dp).clip(CircleShape).background(SnapColors.Yellow)
                    .clickable(onClick = vm::randomize).semantics { contentDescription = "Avatar aléatoire" }, Alignment.Center) {
                    Icon(Icons.Filled.Casino, null, tint = SnapColors.Ink)
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AvatarPart.entries.filter { it.key in catalog.options }) { part ->
                    val selected = part == state.part
                    Text(part.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (selected) Color(0xFF0A6EA8) else SnapColors.Grey,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) SnapColors.Selected else Color.Transparent)
                            .clickable { vm.showPart(part) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            val values = catalog.options[state.part.key].orEmpty()
            LazyVerticalGrid(GridCells.Fixed(if (state.part.isColor) 5 else 4), Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(values, key = { it }) { value ->
                    val selected = state.options[state.part.key] == value
                    val ring = if (selected) Modifier.border(3.dp, SnapColors.ChatBlue, if (state.part.isColor) CircleShape else RoundedCornerShape(14.dp)) else Modifier
                    if (state.part.isColor) {
                        Box(Modifier.aspectRatio(1f).then(ring).padding(5.dp).clip(CircleShape).background(hexColor(value))
                            .border(1.dp, Color.Black.copy(alpha = .08f), CircleShape).clickable { vm.choose(state.part, value) }
                            .semantics { contentDescription = "${state.part.label} $value" })
                    } else {
                        // Vignette de l'avatar actuel avec ce seul choix changé.
                        AsyncImage(avatarImageUrl(state.options + (state.part.key to value), size = 160), "${state.part.label} $value",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(1f).then(ring).padding(3.dp).clip(RoundedCornerShape(11.dp))
                                .background(Color.White).clickable { vm.choose(state.part, value) })
                    }
                }
            }
        }
    }
}

private fun hexColor(hex: String) = runCatching { Color(("FF" + hex.removePrefix("#")).toLong(16)) }.getOrDefault(Color.Gray)

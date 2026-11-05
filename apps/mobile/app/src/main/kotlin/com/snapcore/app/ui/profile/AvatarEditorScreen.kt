package com.snapcore.app.ui.profile

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import com.snapcore.app.ui.components.screenReaderFocusOnAppear
import com.snapcore.app.ui.components.rememberScreenReaderOn
import com.snapcore.app.domain.avatarLabel
import com.snapcore.app.domain.avatarDescription
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
fun AvatarEditorScreen(account: Account, onBack: () -> Unit, onSaved: (AvatarChange) -> Unit) {
    val vm = containerViewModel { AvatarEditorViewModel(it.accounts, account.avatar, analytics = it.analytics) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.result) { state.result?.let(onSaved) }
    LaunchedEffect(state.error) { if (state.catalog != null) state.error?.let { snackbar.showSnackbar(it) } }

    val screenReader = rememberScreenReaderOn()
    var choosing by remember { mutableStateOf(false) }
    StatusBarIcons(dark = true)
    Scaffold(
        containerColor = SnapColors.Background,
        topBar = {
            SnapHeader(if (account.avatar == null) "Créer mon avatar" else "Mon avatar", onBack, background = SnapColors.Background) {
                if (account.avatar != null) TextButton(onClick = vm::remove) { Text("Retirer", color = SnapColors.SnapRed) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.catalog != null) {
                Box(Modifier.navigationBarsPadding().padding(16.dp)) {
                    PrimaryButton("Enregistrer", vm::save, container = SnapColors.Yellow, content = SnapColors.Ink)
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
                AsyncImage(avatarImageUrl(state.options, size = 512), "Aperçu de mon avatar : " + avatarDescription(state.options),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(200.dp).clip(CircleShape).background(SnapColors.Control))
                // Dé : un avatar tiré au sort, comme point de départ.
                Box(Modifier.offset(x = 84.dp, y = 76.dp).size(48.dp).clip(CircleShape).background(SnapColors.Yellow)
                    .clickable(role = Role.Button, onClick = vm::randomize).semantics { contentDescription = "Avatar aléatoire" }, Alignment.Center) {
                    Icon(Icons.Filled.Casino, null, tint = SnapColors.Ink)
                }
            }
            val parts = AvatarPart.entries.filter { it.key in catalog.options }
            // TalkBack : ni onglets ni grille, mais un bouton par catégorie qui annonce le choix en cours
            // (« Yeux : normaux. 12 choix. ») ; un double appui ouvre ses choix (ADR-033).
            if (screenReader) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(parts, key = { it.key }) { part ->
                        val current = state.options[part.key]?.let { avatarLabel(part.key, it) } ?: "aucun"
                        AvatarChoiceButton("${part.label} : $current. ${catalog.options[part.key].orEmpty().size} choix.") {
                            vm.showPart(part)
                            choosing = true
                        }
                    }
                }
                if (choosing) AvatarChoiceDialog(state.part, catalog.options[state.part.key].orEmpty(), state.options,
                    onChoose = { vm.choose(state.part, it) }, onDone = { choosing = false })
                return@Column
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(parts) { part ->
                    val selected = part == state.part
                    val current = state.options[part.key]?.let { avatarLabel(part.key, it) }
                    Text(part.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (selected) Color(0xFF0A6EA8) else SnapColors.Grey,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) SnapColors.Selected else Color.Transparent)
                            .selectable(selected, role = Role.Tab) { vm.showPart(part) }
                            .semantics { contentDescription = if (current != null) "${part.label} : $current" else part.label }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
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
                            .border(1.dp, Color.Black.copy(alpha = .08f), CircleShape)
                            .selectable(selected, role = Role.RadioButton) { vm.choose(state.part, value) }
                            .semantics { contentDescription = "${state.part.label} ${avatarLabel(state.part.key, value)}" })
                    } else {
                        // Vignette de l'avatar actuel avec ce seul choix changé.
                        AsyncImage(avatarImageUrl(state.options + (state.part.key to value), size = 160), avatarLabel(state.part.key, value),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(1f).then(ring).padding(3.dp).clip(RoundedCornerShape(11.dp))
                                .background(Color.White).selectable(selected, role = Role.RadioButton) { vm.choose(state.part, value) })
                    }
                }
            }
        }
    }
}

/**
 * Avec TalkBack, la grille d'une catégorie (jusqu'à 35 vignettes) obligeait à toutes les passer pour atteindre
 * « Enregistrer ». Elle devient un seul bouton qui annonce le choix en cours ; un double appui ouvre la liste.
 */
@Composable
private fun AvatarChoiceButton(summary: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
        .clickable(role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics {
            contentDescription = summary
            role = Role.Button
            onClick("voir les choix") { onOpen(); true }
        }
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, fontSize = 16.sp, color = SnapColors.Ink, modifier = Modifier.weight(1f))
        Text("Changer", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Les choix d'une catégorie, un par ligne, comme des boutons radio. Chaque choix s'applique tout de suite. */
@Composable
private fun AvatarChoiceDialog(part: AvatarPart, values: List<String>, options: Map<String, String>, onChoose: (String) -> Unit,
    onDone: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SnapColors.Background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(part.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink,
                    modifier = Modifier.weight(1f).semantics { heading() }.screenReaderFocusOnAppear())
                Text("Terminé", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDone).padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                items(values, key = { it }) { value ->
                    val selected = options[part.key] == value
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White)
                        .selectable(selected, role = Role.RadioButton) { onChoose(value) }
                        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (part.isColor) Box(Modifier.size(36.dp).clip(CircleShape).background(hexColor(value)).border(1.dp, Color.Black.copy(alpha = .08f), CircleShape))
                        else AsyncImage(avatarImageUrl(options + (part.key to value), size = 96), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        Text(avatarLabel(part.key, value).replaceFirstChar(Char::uppercase), fontSize = 16.sp, color = SnapColors.Ink,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        RadioButton(selected = selected, onClick = null)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            PrimaryButton("Terminé", onDone, height = 50.dp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp))
        }
    }
}

private fun hexColor(hex: String) = runCatching { Color(("FF" + hex.removePrefix("#")).toLong(16)) }.getOrDefault(Color.Gray)

package com.snapcore.app.ui.stories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.snapcore.app.ui.safety.ConfirmActionDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.StoryItem
import com.snapcore.app.ui.chat.relativeTime
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyStoryScreen(onBack: () -> Unit, onPlay: (StoryItem) -> Unit) {
    val vm = containerViewModel { MyStoryViewModel(it.stories) }
    val state by vm.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<StoryItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ma story") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.items.isEmpty() && !state.loading) {
                item { Text("Ta story est vide. Publie un snap depuis la caméra (« Ma story »).", color = SnapColors.Grey, modifier = Modifier.padding(16.dp)) }
            }
            items(state.items, key = { it.id }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPlay(item) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Vignette de l'élément (ADR-026), sinon l'icône de son type, à la même place.
                    Box(Modifier.size(width = 40.dp, height = 56.dp), Alignment.Center) {
                        if (item.thumbnailUrl != null) {
                            AsyncImage(item.thumbnailUrl, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(SnapColors.Control))
                        } else Icon(if (item.kind == MediaKind.VIDEO) Icons.Filled.Videocam else Icons.Filled.Photo, null, tint = SnapColors.SnapPurple)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(publishedLabel(item.createdAt), style = MaterialTheme.typography.bodyLarge)
                        // Chaque élément expire 24 h après SA publication (ADR-013).
                        Text("Expire dans ${remaining(item.expiresAt)}", color = SnapColors.Grey, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { vm.showViewers(item) }) {
                        Icon(Icons.Filled.Visibility, null)
                        Text(" ${item.viewCount}")
                    }
                    // Confirmation d'abord : la suppression est définitive (BUG-020 d'ADR-023).
                    IconButton(onClick = { deleting = item }) { Icon(Icons.Filled.Delete, "Retirer", tint = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider()
            }
        }
    }

    deleting?.let { item ->
        ConfirmActionDialog(
            title = "Supprimer ce snap de ta story ?",
            text = "Tes amis ne pourront plus le voir. Cette action est définitive.",
            confirm = "Supprimer", onConfirm = { vm.delete(item) }, onDismiss = { deleting = null },
        )
    }

    state.viewersOf?.let {
        AlertDialog(
            onDismissRequest = vm::hideViewers,
            title = { Text("Vu par") },
            text = {
                val viewers = state.viewers
                when {
                    viewers == null -> CircularProgressIndicator()
                    viewers.isEmpty() -> Text("Personne pour l'instant.")
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewers.forEach { friend ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(friend.displayName, friend.pictureUrl, size = 32.dp)
                                Text(friend.displayName)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::hideViewers) { Text("Fermer") } },
        )
    }
}

private fun publishedLabel(createdAt: Instant): String =
    relativeTime(createdAt).let { if (it == "à l'instant") "Publié à l'instant" else "Publié il y a $it" }

private fun remaining(expiresAt: Instant): String {
    val left = Duration.between(Instant.now(), expiresAt)
    return if (left.toHours() >= 1) "${left.toHours()} h" else "${left.toMinutes().coerceAtLeast(0)} min"
}

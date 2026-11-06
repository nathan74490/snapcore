package com.snapcore.app.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.theme.SnapColors
import com.snapcore.app.ui.theme.SnapFont
import fr.echimine.live.ui.LiveSdkTheme
import fr.echimine.live.ui.LiveTheme
import fr.echimine.live.core.LiveResult
import fr.echimine.live.core.LiveSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Les Lives en cours de toutes les App membres ; [lives] null tant que rien n'est chargé. */
data class LivesState(
    val lives: List<LiveSummary>? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Section « Lives » de la page Stories (ADR-035). [load] : `LiveClient.lives()` du SDK Live,
 * triés par Audience décroissante par le serveur commun.
 */
class LivesViewModel(private val load: suspend () -> LiveResult<List<LiveSummary>>) : ViewModel() {
    private val _state = MutableStateFlow(LivesState())
    val state: StateFlow<LivesState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Un échec garde les Lives déjà affichés : le prochain rechargement les remplace. */
    fun refresh() {
        viewModelScope.launch {
            when (val result = load()) {
                is LiveResult.Success -> _state.update { LivesState(lives = result.value, loading = false) }
                is LiveResult.Failure -> _state.update { it.copy(loading = false, error = "Les Lives n'ont pas pu être chargés.") }
            }
        }
    }
}

/** « 0 spectateur », « 1 spectateur », « 12 spectateurs » : 0 et 1 au singulier. */
fun audienceLabel(count: Int): String = "$count spectateur" + if (count > 1) "s" else ""

/**
 * Section « Lives » sous les stories récentes : titre, « Lancer un Live », puis les Lives en cours
 * en rangée horizontale. Un Live s'ouvre dans les écrans du SDK ([onOpen], [onLaunch]).
 */
fun LazyListScope.livesSection(state: LivesState, onOpen: (liveId: String) -> Unit, onLaunch: () -> Unit) {
    item {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 24.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lives", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onLaunch) { Text("Lancer un Live", color = SnapColors.SnapRed, fontWeight = FontWeight.Bold) }
        }
    }
    val lives = state.lives
    when {
        lives.isNullOrEmpty() -> item {
            val message = state.error ?: if (state.loading) "Chargement des Lives…" else "Aucun Live en cours pour l'instant."
            Text(message, color = SnapColors.Grey, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        else -> item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lives, key = { it.id }) { live -> LiveCard(live) { onOpen(live.id) } }
            }
        }
    }
}

/** Une carte de Live, comme les cartes de stories : l'Hôte, « LIVE », le titre et l'Audience. */
@Composable
private fun LiveCard(live: LiveSummary, onClick: () -> Unit) {
    val host = live.host
    Box(
        Modifier.width(150.dp).height(220.dp).clip(RoundedCornerShape(10.dp)).background(SnapColors.Control)
            .clickable(onClick = onClick)
            // TalkBack : « Live de Noa sur twitch, Unboxing, 12 spectateurs ».
            .clearAndSetSemantics {
                contentDescription = "Live de ${host.name} sur ${host.app}" +
                    (live.title?.let { ", $it" } ?: "") + ", ${audienceLabel(live.audience)}"
                role = Role.Button
                onClick("regarder le Live") { onClick(); true }
            },
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Avatar(host.name, host.picture, 72.dp) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .65f)))))
        Text(
            "LIVE",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .clip(RoundedCornerShape(4.dp)).background(SnapColors.SnapRed).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            live.title?.let {
                Text(it, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("${host.name} · ${host.app}", color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(audienceLabel(live.audience), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Les écrans du SDK Live aux couleurs de SnapCore : accent rouge des Lives, police de l'app. */
@Composable
fun SnapLiveTheme(content: @Composable () -> Unit) {
    LiveSdkTheme(LiveTheme(accent = SnapColors.SnapRed, fontFamily = SnapFont), content)
}

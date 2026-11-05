package com.snapcore.app.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.snapcore.app.ui.components.SnapHeader
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.StatusBarIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.snapcore.app.ui.components.ChipStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.Relation
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.ChipButton
import com.snapcore.app.ui.components.SectionTitle
import com.snapcore.app.ui.components.SnapCard
import com.snapcore.app.ui.components.SnapSearchBar
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { FriendsViewModel(it.friends) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    StatusBarIcons(dark = true)
    Scaffold(
        topBar = {
            SnapHeader("Ajouter des amis", onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = SnapColors.Background,
    ) { padding ->
        // Maquette 13:538 : fond #F6F8FA, lignes Friend Row (11:232) groupées dans des cartes blanches,
        // actions en Chip Button (9:64) : jaune pour ajouter / accepter, gris pour refuser.
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                SnapSearchBar(state.query, vm::onQuery, "Rechercher un pseudo", Modifier.fillMaxWidth().padding(top = 4.dp), onSearch = vm::search)
                // TalkBack : l'erreur et le résultat de la recherche sont annoncés dès qu'ils arrivent.
                state.searchError?.let { Text(it, color = SnapColors.SnapRed, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                state.result?.let { result ->
                    SnapCard(Modifier.padding(top = 12.dp)) {
                        PersonRow(result.user, live = true) {
                            val name = result.user.displayName
                            when (result.relation) {
                                Relation.NONE -> NamedChip("Ajouter", "Ajouter $name", "envoyer une demande d'ami", vm::addResult, showIcon = true)
                                Relation.INCOMING_REQUEST -> NamedChip("Accepter", "Accepter $name", "accepter sa demande d'ami", vm::addResult)
                                Relation.OUTGOING_REQUEST -> Status("Demande envoyée")
                                Relation.FRIENDS -> Status("Ami")
                            }
                        }
                    }
                }
            }
            if (state.incoming.isNotEmpty()) {
                item { Section("Demandes reçues") }
                item {
                    SnapCard {
                        state.incoming.forEach { request ->
                            // Comme Snapchat : « Accepter » et une petite croix, pour laisser la place au nom
                            // et au pseudo, qui étaient coupés (BUG-016 d'ADR-023).
                            // TalkBack : la ligne lit « Léa, @lea », puis « Accepter Léa » et « Refuser Léa ».
                            val name = request.user.displayName
                            PersonRow(request.user) {
                                NamedChip("Accepter", "Accepter $name", "devenir amis", { vm.accept(request) })
                                DeclineButton(name) { vm.decline(request) }
                            }
                        }
                    }
                }
            }
            if (state.outgoing.isNotEmpty()) {
                item { Section("Demandes envoyées") }
                item { SnapCard { state.outgoing.forEach { request -> PersonRow(request.user) { Status("En attente") } } } }
            }
            item { Section("Mes amis (${state.friends.size})", spoken = when (state.friends.size) {
                0 -> "Mes amis : aucun pour l'instant"
                1 -> "Mes amis : 1 ami"
                else -> "Mes amis : ${state.friends.size} amis"
            }) }
            if (state.friends.isNotEmpty()) item { SnapCard { state.friends.forEach { friend -> PersonRow(friend) {} } } }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

/** Refuser une demande : croix dans un rond gris, comme Snapchat. TalkBack lit « Refuser Léa ». */
@Composable
private fun DeclineButton(name: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(SnapColors.Control)
        .clickable(role = Role.Button, onClickLabel = "refuser sa demande d'ami", onClick = onClick)
        .semantics { contentDescription = "Refuser $name" }, Alignment.Center) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = SnapColors.Ink, modifier = Modifier.size(18.dp))
    }
}

/**
 * Chip Button nommé pour TalkBack avec la personne concernée (« Ajouter Léa », « Accepter Léa ») :
 * à l'écran, rien ne change.
 */
@Composable
private fun NamedChip(label: String, description: String, actionLabel: String, action: () -> Unit,
    style: ChipStyle = ChipStyle.Yellow, showIcon: Boolean = false) {
    Box(Modifier.semantics(mergeDescendants = true) {}.clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        onClick(actionLabel) { action(); true }
    }) { ChipButton(label, action, style = style, showIcon = showIcon) }
}

@Composable
private fun Status(text: String) {
    Text(text, color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Section(title: String, spoken: String? = null) {
    // TalkBack : titres de section, pour sauter de l'un à l'autre.
    SectionTitle(title, Modifier.padding(top = 20.dp, bottom = 6.dp).semantics {
        heading()
        spoken?.let { contentDescription = it }
    })
}

@Composable
private fun PersonRow(user: Friend, live: Boolean = false, actions: @Composable () -> Unit) {
    // TalkBack : nom, pseudo et statut (« En attente », « Ami ») lus en une fois ; les boutons restent à part.
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (live) liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 66.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(user.displayName, user.pictureUrl, size = 56.dp)
        // Prénom en titre ; le pseudo reste visible, c'est lui qu'on recherche pour ajouter un ami.
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (user.firstName != null) Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = SnapColors.Grey, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

package com.snapcore.app.ui.friends

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.Relation
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.ChipButton
import com.snapcore.app.ui.components.ChipStyle
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
                state.searchError?.let { Text(it, color = SnapColors.SnapRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp, top = 6.dp)) }
                state.result?.let { result ->
                    SnapCard(Modifier.padding(top = 12.dp)) {
                        PersonRow(result.user) {
                            when (result.relation) {
                                Relation.NONE -> ChipButton("Ajouter", vm::addResult, showIcon = true)
                                Relation.INCOMING_REQUEST -> ChipButton("Accepter", vm::addResult)
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
                            PersonRow(request.user) {
                                ChipButton("Refuser", { vm.decline(request) }, style = ChipStyle.Gray)
                                ChipButton("Accepter", { vm.accept(request) })
                            }
                        }
                    }
                }
            }
            if (state.outgoing.isNotEmpty()) {
                item { Section("Demandes envoyées") }
                item { SnapCard { state.outgoing.forEach { request -> PersonRow(request.user) { Status("En attente") } } } }
            }
            item { Section("Mes amis (${state.friends.size})") }
            if (state.friends.isNotEmpty()) item { SnapCard { state.friends.forEach { friend -> PersonRow(friend) {} } } }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, color = SnapColors.Grey, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Section(title: String) {
    SectionTitle(title, Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun PersonRow(user: Friend, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(user.displayName, user.pictureUrl, size = 56.dp)
        // Prénom en titre ; le pseudo reste visible, c'est lui qu'on recherche pour ajouter un ami.
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (user.firstName != null) Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = SnapColors.Grey, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

package com.snapcore.app.ui.friends

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
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQuery,
                    placeholder = { Text("Rechercher un pseudo") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = SnapColors.Control, focusedContainerColor = SnapColors.Control, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, focusedBorderColor = SnapColors.ChatBlue),
                    singleLine = true,
                    trailingIcon = { IconButton(onClick = vm::search) { Icon(Icons.Filled.Search, "Rechercher") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                    isError = state.searchError != null,
                    supportingText = state.searchError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.result?.let { result ->
                    PersonRow(result.user) {
                        when (result.relation) {
                            Relation.NONE -> Button(onClick = vm::addResult, colors = ButtonDefaults.buttonColors(containerColor = SnapColors.Yellow, contentColor = SnapColors.Ink)) { Text("Ajouter") }
                            Relation.INCOMING_REQUEST -> Button(onClick = vm::addResult) { Text("Accepter") }
                            Relation.OUTGOING_REQUEST -> Text("Demande envoyée", color = SnapColors.Grey)
                            Relation.FRIENDS -> Text("Ami", color = SnapColors.Grey)
                        }
                    }
                }
            }
            if (state.incoming.isNotEmpty()) {
                item { Section("Demandes reçues") }
                items(state.incoming, key = { it.friendshipId }) { request ->
                    PersonRow(request.user) {
                        OutlinedButton(onClick = { vm.decline(request) }) { Text("Refuser") }
                        Button(onClick = { vm.accept(request) }, colors = ButtonDefaults.buttonColors(containerColor = SnapColors.Yellow, contentColor = SnapColors.Ink)) { Text("Accepter") }
                    }
                }
            }
            if (state.outgoing.isNotEmpty()) {
                item { Section("Demandes envoyées") }
                items(state.outgoing, key = { it.friendshipId }) { request -> PersonRow(request.user) { Text("En attente", color = SnapColors.Grey) } }
            }
            item { Section("Mes amis (${state.friends.size})") }
            items(state.friends, key = { it.id }) { friend -> PersonRow(friend) {} }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun PersonRow(user: Friend, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(user.username, user.photoUrl, size = 56.dp)
        Text(user.username, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

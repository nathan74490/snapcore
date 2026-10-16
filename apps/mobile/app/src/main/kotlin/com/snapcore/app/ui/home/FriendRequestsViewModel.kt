package com.snapcore.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.domain.FriendsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Nombre de demandes d'ami reçues en attente, pour le badge du bouton d'ajout d'amis. */
class FriendRequestsViewModel(private val friends: FriendsService, events: Flow<RealtimeEvent>) : ViewModel() {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            events.collect { event ->
                if (event is RealtimeEvent.Connected || (event is RealtimeEvent.Message && event.type.startsWith("friend_request."))) refresh()
            }
        }
    }

    // Sans réseau, le badge garde sa dernière valeur : ce n'est qu'un rappel.
    fun refresh() {
        viewModelScope.launch { runCatching { friends.incomingRequests().size }.onSuccess { _count.value = it } }
    }
}

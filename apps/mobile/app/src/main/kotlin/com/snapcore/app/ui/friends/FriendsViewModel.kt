package com.snapcore.app.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.Relation
import com.snapcore.app.domain.UserSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FriendsState(
    val query: String = "",
    val searching: Boolean = false,
    val result: UserSearchResult? = null,
    val searchError: String? = null,
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val friends: List<Friend> = emptyList(),
    val message: String? = null,
)

// Ajout d'amis par recherche de pseudo uniquement en V1 (ADR-012).
class FriendsViewModel(private val service: FriendsService) : ViewModel() {
    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { Triple(service.incomingRequests(), service.outgoingRequests(), service.friends()) }
                .onSuccess { (incoming, outgoing, friends) ->
                    _state.update { it.copy(incoming = incoming, outgoing = outgoing, friends = friends) }
                }
                .onFailure { e -> _state.update { it.copy(message = e.message) } }
        }
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value, searchError = null, result = null) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(searching = true, searchError = null, result = null) }
        viewModelScope.launch {
            try {
                val result = service.search(query)
                _state.update { it.copy(searching = false, result = result) }
            } catch (e: ApiException) {
                // "Utilisateur introuvable" : même réponse pour un pseudo inconnu ou un blocage (ADR-012).
                _state.update { it.copy(searching = false, searchError = e.message) }
            }
        }
    }

    fun addResult() {
        val result = _state.value.result ?: return
        viewModelScope.launch {
            runCatching { service.sendRequest(result.user.username) }
                .onSuccess {
                    // Si l'autre avait déjà demandé, la demande vaut acceptation côté backend.
                    val relation = if (result.relation == Relation.INCOMING_REQUEST) Relation.FRIENDS else Relation.OUTGOING_REQUEST
                    _state.update { it.copy(result = result.copy(relation = relation)) }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(message = e.message) } }
        }
    }

    fun accept(request: FriendRequest) = act { service.accept(request.friendshipId) }
    fun decline(request: FriendRequest) = act { service.decline(request.friendshipId) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _state.update { it.copy(message = e.message) } }
            refresh()
        }
    }
}

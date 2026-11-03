package com.snapcore.app.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.Relation
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.ui.OptimisticGuard
import com.snapcore.app.ui.completeEvenIfClosed
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

    private val guard = OptimisticGuard()

    init {
        refresh()
    }

    fun refresh() {
        val mark = guard.mark()
        viewModelScope.launch {
            runCatching { Triple(service.incomingRequests(), service.outgoingRequests(), service.friends()) }
                .onSuccess { (incoming, outgoing, friends) ->
                    // Une action encore en cours (ou finie entre-temps) affiche déjà mieux que cette réponse.
                    if (guard.canApply(mark)) _state.update { it.copy(incoming = incoming, outgoing = outgoing, friends = friends) }
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

    // Interface optimiste : la demande apparaît envoyée dès l'appui. Si l'autre avait déjà demandé,
    // la demande vaut acceptation côté backend : il passe directement dans les amis.
    fun addResult() {
        val result = _state.value.result ?: return
        if (result.relation == Relation.OUTGOING_REQUEST || result.relation == Relation.FRIENDS) return
        val user = result.user
        val accepting = result.relation == Relation.INCOMING_REQUEST
        val local = FriendRequest("local-${user.id}", user)
        val incoming = _state.value.incoming.firstOrNull { it.user.id == user.id }
        optimistic(
            apply = { s ->
                if (accepting) s.copy(result = result.copy(relation = Relation.FRIENDS),
                    incoming = s.incoming.filter { it.user.id != user.id }, friends = s.friends.withFriend(user))
                else s.copy(result = result.copy(relation = Relation.OUTGOING_REQUEST), outgoing = listOf(local) + s.outgoing)
            },
            revert = { s ->
                val shown = s.result?.takeIf { it.user.id == user.id }?.copy(relation = result.relation) ?: s.result
                if (accepting) s.copy(result = shown, friends = s.friends.filter { it.id != user.id }, incoming = s.incoming + listOfNotNull(incoming))
                else s.copy(result = shown, outgoing = s.outgoing - local)
            },
        ) { service.sendRequest(user.username) }
    }

    fun accept(request: FriendRequest) = optimistic(
        apply = { s -> s.copy(incoming = s.incoming - request, friends = s.friends.withFriend(request.user)) },
        revert = { s -> s.copy(incoming = s.incoming + request, friends = s.friends.filter { it.id != request.user.id }) },
    ) { service.accept(request.friendshipId) }

    fun decline(request: FriendRequest) = optimistic(
        apply = { s -> s.copy(incoming = s.incoming - request) },
        revert = { s -> s.copy(incoming = s.incoming + request) },
    ) { service.decline(request.friendshipId) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Applique l'action à l'écran tout de suite, puis l'envoie. En cas d'échec, seule cette action est
     * défaite (les autres, éventuellement en cours, restent affichées) et le motif s'affiche. Une fois
     * toutes les actions terminées, un rechargement aligne l'écran sur le backend.
     */
    private fun optimistic(apply: (FriendsState) -> FriendsState, revert: (FriendsState) -> FriendsState, call: suspend () -> Unit) {
        guard.begin()
        _state.update(apply)
        viewModelScope.launch {
            val result = completeEvenIfClosed { call() }
            guard.end()
            result.onFailure { e -> _state.update { revert(it).copy(message = e.message ?: "Action impossible") } }
            if (guard.idle) refresh()
        }
    }
}

// Le backend liste les amis de la demande la plus récente à la plus ancienne : un nouvel ami arrive en tête.
private fun List<Friend>.withFriend(friend: Friend) = if (any { it.id == friend.id }) this else listOf(friend) + this

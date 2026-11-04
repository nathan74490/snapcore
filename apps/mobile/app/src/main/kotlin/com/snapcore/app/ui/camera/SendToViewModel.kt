package com.snapcore.app.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.GROUP_RECIPIENT_PREFIX
import com.snapcore.app.domain.Group
import com.snapcore.app.domain.GroupService
import com.snapcore.app.domain.SnapSender
import com.snapcore.app.domain.SendTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SendToState(
    val friends: List<Friend> = emptyList(),
    /** Mes groupes (ADR-032), au-dessus des amis. */
    val groups: List<Group> = emptyList(),
    val loading: Boolean = true,
    val selected: Set<SendTarget> = emptySet(),
    val loadError: String? = null,
    val done: Boolean = false,
)

class SendToViewModel(
    private val media: CapturedMedia,
    private val friendsService: FriendsService,
    private val sender: SnapSender,
    /** Ami dont on a ouvert la caméra (bouton appareil photo du chat) : coché d'office. */
    private val recipientId: String? = null,
    private val groupService: GroupService? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SendToState())
    val state: StateFlow<SendToState> = _state.asStateFlow()

    init {
        loadFriends()
    }

    fun loadFriends() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            // Les groupes sont un plus : s'ils échouent, l'envoi aux amis et à la story reste possible.
            val groups = groupService?.let { service -> runCatching { service.groups().map { it.group } }.getOrNull() }.orEmpty()
            val groupRecipient = recipientId?.takeIf { it.startsWith(GROUP_RECIPIENT_PREFIX) }?.removePrefix(GROUP_RECIPIENT_PREFIX)
                ?.let { id -> groups.firstOrNull { it.id == id } }
            if (groupRecipient != null) _state.update { it.copy(selected = it.selected + SendTarget.ToGroup(groupRecipient)) }
            _state.update { it.copy(groups = groups) }
            runCatching { friendsService.friends() }
                .onSuccess { friends ->
                    val recipient = friends.firstOrNull { it.id == recipientId }
                    _state.update { state ->
                        state.copy(
                            friends = friends,
                            loading = false,
                            selected = if (recipient != null && state.selected.isEmpty()) setOf(SendTarget.ToFriend(recipient)) else state.selected,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, loadError = e.message) } }
        }
    }

    fun toggle(target: SendTarget) = _state.update {
        it.copy(selected = if (target in it.selected) it.selected - target else it.selected + target)
    }

    // Envoi en arrière-plan : le snap part dans la file de l'app et l'écran se ferme tout de suite. Son
    // avancement (« Envoi… », échec à réessayer) s'affiche dans le chat et sur la carte Ma story.
    fun send() {
        val targets = _state.value.selected
        if (targets.isEmpty() || _state.value.done) return
        sender.send(media, targets)
        _state.update { it.copy(done = true) }
    }
}

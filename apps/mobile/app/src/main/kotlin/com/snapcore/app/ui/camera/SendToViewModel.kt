package com.snapcore.app.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaPublisher
import com.snapcore.app.domain.SendTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SendToState(
    val friends: List<Friend> = emptyList(),
    val loading: Boolean = true,
    val selected: Set<SendTarget> = emptySet(),
    val sending: Boolean = false,
    val failures: Map<SendTarget, String> = emptyMap(),
    val loadError: String? = null,
    val done: Boolean = false,
)

class SendToViewModel(
    private val media: CapturedMedia,
    private val friendsService: FriendsService,
    private val publisher: MediaPublisher,
    /** Ami dont on a ouvert la caméra (bouton appareil photo du chat) : coché d'office. */
    private val recipientId: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SendToState())
    val state: StateFlow<SendToState> = _state.asStateFlow()

    init {
        loadFriends()
    }

    fun loadFriends() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
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

    // Envoie aux cibles sélectionnées. En cas d'échec partiel, seules les cibles en échec restent
    // sélectionnées : un nouvel essai ne renvoie pas le snap à ceux qui l'ont déjà reçu.
    fun send() {
        val targets = _state.value.selected
        if (targets.isEmpty()) return
        _state.update { it.copy(sending = true, failures = emptyMap()) }
        viewModelScope.launch {
            val report = publisher.publish(media, targets)
            _state.update {
                it.copy(
                    sending = false,
                    selected = report.failed.keys,
                    failures = report.failed,
                    done = report.allSucceeded,
                )
            }
        }
    }
}

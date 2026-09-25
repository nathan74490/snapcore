package com.snapcore.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.domain.ChatMessage
import com.snapcore.app.domain.ChatService
import com.snapcore.app.domain.Conversation
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.SnapOpenResult
import com.snapcore.app.domain.canTryToOpen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Événements qui changent ce qu'affiche le chat. "Connected" couvre la resynchronisation
// obligatoire à chaque (re)connexion, Socket.io ne garantissant pas la livraison (ADR-006).
private val CHAT_EVENTS = setOf("message.received", "snap.opened", "texts.read", "snap.expired", "friend_request.accepted")

private fun RealtimeEvent.affectsChat() = this is RealtimeEvent.Connected || (this is RealtimeEvent.Message && type in CHAT_EVENTS)

// --- Liste des conversations -------------------------------------------------------------------

data class ChatListState(
    val conversations: List<Conversation> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class ChatListViewModel(private val chat: ChatService, events: Flow<RealtimeEvent>) : ViewModel() {
    private val _state = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { events.collect { if (it.affectsChat()) refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { chat.conversations() }
                .onSuccess { list -> _state.update { it.copy(conversations = list, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

// --- Conversation ------------------------------------------------------------------------------

data class ConversationState(
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = true,
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    /** Snap en cours d'ouverture ou ouvert : l'écran de lecture s'affiche par-dessus. */
    val viewer: SnapViewerState? = null,
)

sealed interface SnapViewerState {
    data object Loading : SnapViewerState
    data class Showing(val result: SnapOpenResult.Ready) : SnapViewerState
}

class ConversationViewModel(
    val friend: Friend,
    val myId: String,
    private val chat: ChatService,
    events: Flow<RealtimeEvent>,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { events.collect { if (it.affectsChat()) refresh() } }
    }

    // Recharge la conversation et marque les Chats reçus comme lus (flèche pleine → contour).
    fun refresh() {
        viewModelScope.launch {
            runCatching { chat.messages(friend.id) }
                .onSuccess { messages ->
                    // Ne touche pas à "error" : un message en attente d'affichage (ex. "déjà vu")
                    // ne doit pas être effacé par le rechargement qui suit ; consumeError() s'en charge.
                    _state.update { it.copy(messages = messages, loading = false) }
                    val unreadText = messages.any { !it.isSnap && !it.isFrom(myId) && it.openedAt == null }
                    if (unreadText) runCatching { chat.markRead(friend.id) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun onDraft(value: String) = _state.update { it.copy(draft = value, error = null) }

    fun send() {
        val content = _state.value.draft.trim()
        if (content.isEmpty()) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            runCatching { chat.sendText(friend.id, content) }
                .onSuccess { message ->
                    _state.update { it.copy(sending = false, draft = "", messages = listOf(message) + it.messages) }
                }
                .onFailure { e -> _state.update { it.copy(sending = false, error = e.message) } }
        }
    }

    // Vue unique (ADR-009) : c'est le backend qui accorde ou refuse l'URL de lecture.
    fun open(message: ChatMessage) {
        if (!canTryToOpen(message, myId) || _state.value.viewer != null) return
        _state.update { it.copy(viewer = SnapViewerState.Loading) }
        viewModelScope.launch {
            when (val result = chat.openSnap(message)) {
                is SnapOpenResult.Ready -> _state.update { it.copy(viewer = SnapViewerState.Showing(result)) }
                SnapOpenResult.AlreadySeen -> closeWith("Ce snap a déjà été vu")
                SnapOpenResult.Expired -> closeWith("Ce snap a expiré")
                is SnapOpenResult.Failed -> closeWith(result.message)
            }
        }
    }

    fun closeViewer() {
        _state.update { it.copy(viewer = null) }
        refresh() // le snap apparaît désormais comme ouvert
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun closeWith(message: String) {
        _state.update { it.copy(viewer = null, error = message) }
        refresh()
    }
}

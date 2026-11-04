package com.snapcore.app.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.data.field
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.Group
import com.snapcore.app.domain.GroupMessage
import com.snapcore.app.domain.GroupService
import com.snapcore.app.domain.MAX_GROUP_MEMBERS
import com.snapcore.app.domain.MAX_GROUP_NAME_LENGTH
import com.snapcore.app.domain.OutgoingSnap
import com.snapcore.app.domain.SentTracker
import com.snapcore.app.domain.SnapOpenResult
import com.snapcore.app.domain.canTryToOpen
import com.snapcore.app.ui.chat.SnapViewerState
import com.snapcore.app.ui.chat.TYPING_SIGNAL_INTERVAL_MS
import com.snapcore.app.ui.chat.TYPING_VISIBLE_MS
import com.snapcore.app.ui.completeEvenIfClosed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

// Événements temps réel des groupes (ADR-032). "Connected" : resynchronisation à chaque (re)connexion (ADR-006).
val GROUP_EVENTS = setOf("group.message.received", "group.message.seen", "group.updated")

private fun RealtimeEvent.concerns(groupId: String) =
    this is RealtimeEvent.Connected || (this is RealtimeEvent.Message && type in GROUP_EVENTS && field("groupId") == groupId)

// --- Conversation de groupe ------------------------------------------------------------------------

data class GroupConversationState(
    val group: Group? = null,
    val messages: List<GroupMessage> = emptyList(),
    val loading: Boolean = true,
    val draft: String = "",
    val error: String? = null,
    val viewer: SnapViewerState? = null,
    /** Snap déjà vu : on propose SnapCore+, qui permet de le revoir (ADR-025). */
    val offerPlus: Boolean = false,
    /** Membres en train d'écrire, avec l'heure du dernier signal (ADR-027). */
    val typing: List<String> = emptyList(),
    /** J'ai quitté le groupe, ou il n'est plus accessible : l'écran se ferme. */
    val closed: Boolean = false,
)

class GroupConversationViewModel(
    private val groupId: String,
    val myId: String,
    private val groups: GroupService,
    events: Flow<RealtimeEvent>,
    private val outbox: StateFlow<List<OutgoingSnap>> = MutableStateFlow(emptyList()),
    private val retrySend: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupConversationState())
    val state: StateFlow<GroupConversationState> = _state.asStateFlow()

    private val sent = SentTracker()
    private var lastTypingSignal = Long.MIN_VALUE / 2
    private var viewing: String? = null
    private val typingTimeouts = mutableMapOf<String, Job>()

    init {
        refresh()
        viewModelScope.launch { outbox.collect { items -> if (sent.newlySent(items)) refresh() else publish() } }
        viewModelScope.launch {
            events.collect { event ->
                when {
                    event is RealtimeEvent.Message && event.type == "group.typing" && event.field("groupId") == groupId ->
                        event.field("userId")?.takeIf { it != myId }?.let(::showTyping)
                    event.concerns(groupId) -> {
                        if (event is RealtimeEvent.Message && event.type == "group.message.received") clearTyping()
                        refresh()
                    }
                }
            }
        }
    }

    fun refresh() {
        val known = sent.mark()
        viewModelScope.launch {
            runCatching { groups.group(groupId) to groups.messages(groupId) }
                .onSuccess { (group, messages) ->
                    sent.acknowledge(known)
                    _state.update { it.copy(group = group, messages = withPending(it.messages, messages), loading = false) }
                    publish()
                    // Chats reçus depuis ma dernière lecture : le groupe passe en « lu » chez les autres.
                    if (messages.any { !it.isFrom(myId) && !it.isSnap && myId !in it.seenBy }) runCatching { groups.markRead(groupId) }
                }
                // 404 : je ne fais plus partie du groupe (parti depuis un autre appareil, groupe supprimé).
                .onFailure { e ->
                    val gone = (e as? com.snapcore.app.data.api.ApiException)?.isNotFound == true
                    _state.update { it.copy(loading = false, error = if (gone) null else e.message, closed = gone, viewer = null) }
                }
        }
    }

    fun onDraft(value: String) {
        _state.update { it.copy(draft = value, error = null) }
        if (value.isNotBlank() && now() - lastTypingSignal >= TYPING_SIGNAL_INTERVAL_MS) {
            lastTypingSignal = now()
            viewModelScope.launch { runCatching { groups.sendTyping(groupId) } }
        }
    }

    // Interface optimiste, comme dans une conversation à deux.
    fun send() {
        val content = _state.value.draft.trim()
        if (content.isEmpty()) return
        lastTypingSignal = Long.MIN_VALUE / 2
        val local = GroupMessage(
            id = "local-${UUID.randomUUID()}", groupId = groupId, senderId = myId, type = ChatContentType.TEXT, content = content,
            createdAt = Instant.now(), mediaPurged = false, openedByMe = false, seenBy = emptyList(), pending = true,
        )
        _state.update { it.copy(draft = "", messages = listOf(local) + it.messages) }
        viewModelScope.launch {
            runCatching { groups.sendText(groupId, content) }
                .onSuccess { message ->
                    _state.update { state ->
                        val others = state.messages.filter { it.id != local.id }
                        state.copy(messages = if (others.any { it.id == message.id }) others else listOf(message) + others)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(messages = it.messages.filter { m -> m.id != local.id }, draft = it.draft.ifEmpty { content }, error = e.message)
                    }
                }
        }
    }

    fun open(message: GroupMessage) {
        if (message.failed) return retrySend(message.id.removePrefix(OutgoingSnap.OUTBOX_PREFIX))
        if (!canTryToOpen(message, myId) || _state.value.viewer != null) return
        viewing = message.id
        _state.update { it.copy(viewer = SnapViewerState.Loading) }
        viewModelScope.launch {
            when (val result = groups.openSnap(message)) {
                is SnapOpenResult.Ready -> _state.update { it.copy(viewer = SnapViewerState.Showing(result)) }
                SnapOpenResult.AlreadySeen -> {
                    _state.update { it.copy(viewer = null, offerPlus = true) }
                    refresh()
                }
                SnapOpenResult.Expired -> closeWith("Ce snap a expiré")
                is SnapOpenResult.Failed -> closeWith(result.message)
            }
        }
    }

    // Le snap fermé passe tout de suite en ouvert, le rechargement confirme.
    fun closeViewer() {
        val opened = viewing.takeIf { _state.value.viewer is SnapViewerState.Showing }
        viewing = null
        _state.update { state ->
            state.copy(viewer = null, messages = state.messages.map { m -> if (m.id == opened) m.copy(openedByMe = true) else m })
        }
        refresh()
    }

    fun rename(name: String) {
        val trimmed = name.trim().take(MAX_GROUP_NAME_LENGTH)
        val previous = _state.value.group ?: return
        _state.update { it.copy(group = previous.copy(name = trimmed.ifEmpty { null })) }
        viewModelScope.launch {
            completeEvenIfClosed { groups.rename(groupId, trimmed.ifEmpty { null }) }
                .onSuccess { group ->
                    _state.update { it.copy(group = group) }
                }
                .onFailure { e -> _state.update { it.copy(group = previous, error = e.message ?: "Nom non enregistré") } }
        }
    }

    fun addMembers(friends: List<Friend>) {
        if (friends.isEmpty()) return
        viewModelScope.launch {
            completeEvenIfClosed { groups.addMembers(groupId, friends.map { it.id }) }
                .onSuccess { group ->
                    _state.update { it.copy(group = group) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Ajout impossible") } }
        }
    }

    fun leave() {
        viewModelScope.launch {
            completeEvenIfClosed { groups.leave(groupId) }
                .onSuccess {
                    _state.update { it.copy(closed = true) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Impossible de quitter le groupe") } }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun dismissPlusOffer() = _state.update { it.copy(offerPlus = false) }

    private fun showTyping(userId: String) {
        typingTimeouts.remove(userId)?.cancel()
        _state.update { it.copy(typing = (it.typing - userId) + userId) }
        typingTimeouts[userId] = viewModelScope.launch {
            delay(TYPING_VISIBLE_MS)
            _state.update { it.copy(typing = it.typing - userId) }
        }
    }

    private fun clearTyping() {
        typingTimeouts.values.forEach { it.cancel() }
        typingTimeouts.clear()
        _state.update { it.copy(typing = emptyList()) }
    }

    private fun closeWith(message: String) {
        _state.update { it.copy(viewer = null, error = message) }
        refresh()
    }

    private fun withPending(current: List<GroupMessage>, loaded: List<GroupMessage>) =
        current.filter { it.pending && !it.id.startsWith(OutgoingSnap.OUTBOX_PREFIX) } + loaded

    // Snaps de la file d'envoi destinés à ce groupe, en tête de la conversation.
    private fun publish() {
        val outgoing = sent.visible(outbox.value).filter { it.groupId == groupId }.map { it.asGroupMessage(myId) }
        _state.update { state ->
            val others = state.messages.filter { !it.id.startsWith(OutgoingSnap.OUTBOX_PREFIX) }
            state.copy(messages = outgoing.sortedByDescending { it.createdAt } + others)
        }
    }
}

// --- Création d'un groupe (captures Snapchat 367 à 370) ---------------------------------------------

data class NewGroupState(
    val friends: List<Friend> = emptyList(),
    val loading: Boolean = true,
    val name: String = "",
    val query: String = "",
    val selected: List<Friend> = emptyList(),
    val creating: Boolean = false,
    val error: String? = null,
    /** Un seul ami choisi : la conversation à deux s'ouvre. */
    val openFriend: Friend? = null,
    /** Groupe créé : sa conversation s'ouvre. */
    val created: Group? = null,
) {
    val visible get() = friends.filter { f ->
        query.isBlank() || f.displayName.contains(query.trim(), ignoreCase = true) || f.username.contains(query.trim(), ignoreCase = true)
    }
    val isGroup get() = selected.size >= 2
    val full get() = selected.size >= MAX_GROUP_MEMBERS - 1
}

class NewGroupViewModel(
    private val friendsService: FriendsService,
    private val groups: GroupService,
    /** Membres déjà dans le groupe (ajout de membres) : ils ne sont pas proposés. */
    private val excluded: Set<String> = emptySet(),
) : ViewModel() {
    private val _state = MutableStateFlow(NewGroupState())
    val state: StateFlow<NewGroupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { friendsService.friends() }
                .onSuccess { list ->
                    _state.update { it.copy(loading = false, friends = list.filter { f -> f.id !in excluded }.sortedBy { f -> f.displayName.lowercase() }) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun onName(value: String) = _state.update { it.copy(name = value.take(MAX_GROUP_NAME_LENGTH)) }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }

    fun toggle(friend: Friend) = _state.update { s ->
        when {
            friend in s.selected -> s.copy(selected = s.selected - friend)
            s.full -> s.copy(error = "Un groupe compte au plus $MAX_GROUP_MEMBERS membres")
            else -> s.copy(selected = s.selected + friend, query = "")
        }
    }

    /** « Discuter » (un ami) ou « Discuter avec le groupe » (deux amis ou plus). */
    fun confirm() {
        val s = _state.value
        if (s.selected.isEmpty() || s.creating) return
        if (!s.isGroup) return _state.update { it.copy(openFriend = s.selected.single()) }
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            runCatching { groups.create(s.name.ifBlank { null }, s.selected.map { it.id }) }
                .onSuccess { group ->
                    _state.update { it.copy(creating = false, created = group) }
                }
                .onFailure { e -> _state.update { it.copy(creating = false, error = e.message ?: "Création impossible") } }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

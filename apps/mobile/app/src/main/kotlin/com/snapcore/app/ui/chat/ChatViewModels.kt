package com.snapcore.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.ChatMessage
import com.snapcore.app.domain.ChatService
import com.snapcore.app.domain.Conversation
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.OutgoingSnap
import com.snapcore.app.domain.SentTracker
import com.snapcore.app.domain.SnapOpenResult
import com.snapcore.app.domain.canTryToOpen
import com.snapcore.app.data.field
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

// Le temps que la conversation ouverte marque ses Chats comme lus côté backend.
const val SEEN_LOCALLY_MS = 30_000L

class ChatListViewModel(
    private val chat: ChatService,
    events: Flow<RealtimeEvent>,
    private val myId: String = "",
    private val outbox: StateFlow<List<OutgoingSnap>> = MutableStateFlow(emptyList()),
    /** Relance d'un snap en échec, par son identifiant dans la file d'envoi. */
    private val retrySend: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = _state.asStateFlow()

    /** Derniers Chats reçus que l'utilisateur vient d'ouvrir, avec l'heure de l'ouverture. */
    private val seenLocally = mutableMapOf<String, Long>()
    private var server = emptyList<Conversation>()
    private val sent = SentTracker()

    init {
        refresh()
        viewModelScope.launch { events.collect { if (it.affectsChat()) refresh() } }
        // Un snap envoyé en arrière-plan : la liste recharge pour afficher le vrai message.
        viewModelScope.launch {
            outbox.collect { items ->
                if (sent.newlySent(items)) refresh() else publish()
            }
        }
    }

    fun refresh() {
        val known = sent.mark()
        viewModelScope.launch {
            runCatching { chat.conversations() }
                .onSuccess { list ->
                    server = list
                    sent.acknowledge(known)
                    _state.update { it.copy(loading = false, error = null) }
                    publish()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    // Conversations du backend, chacune complétée par son snap en cours d'envoi, qui la fait remonter en tête.
    private fun publish() {
        val outgoing = sent.visible(outbox.value).filter { it.friendId != null }.groupBy { it.friendId!! }
        val conversations = withSeen(server).map { conversation ->
            val local = outgoing[conversation.friend.id]?.maxByOrNull { it.createdAt }
            if (local == null) conversation else conversation.copy(lastMessage = local.asMessage(myId))
        }
        val (sending, rest) = conversations.partition { it.friend.id in outgoing }
        _state.update { it.copy(conversations = sending.sortedByDescending { c -> c.lastMessage?.createdAt } + rest) }
    }

    /** Relance un snap dont l'envoi a échoué (ligne « Échec de l'envoi »). */
    fun retry(message: ChatMessage) {
        if (message.failed) retrySend(message.id.removePrefix(OutgoingSnap.OUTBOX_PREFIX))
    }

    /**
     * Interface optimiste : ouvrir une conversation la passe tout de suite en lue dans la liste, sans attendre
     * que la conversation l'ait signalé au backend. Seuls les Chats texte : un snap n'est vu qu'une fois
     * réellement ouvert, et le rechargement au retour dans la liste s'en charge (BUG-011 d'ADR-023).
     */
    fun markSeen(friendId: String, myId: String) {
        val last = _state.value.conversations.firstOrNull { it.friend.id == friendId }?.lastMessage ?: return
        if (last.isSnap || last.isFrom(myId) || last.openedAt != null) return
        seenLocally[last.id] = now()
        publish()
    }

    // Un rechargement arrivé avant que le backend ait enregistré la lecture ne fait pas revenir le « Nouveau chat ».
    private fun withSeen(list: List<Conversation>): List<Conversation> {
        seenLocally.entries.removeAll { (id, at) -> now() - at > SEEN_LOCALLY_MS || list.any { c -> c.lastMessage?.let { it.id == id && it.openedAt != null } == true } }
        return list.map { conversation ->
            val last = conversation.lastMessage
            val seenAt = last?.id?.let(seenLocally::get)
            if (last != null && seenAt != null && last.openedAt == null) conversation.copy(lastMessage = last.copy(openedAt = Instant.ofEpochMilli(seenAt)))
            else conversation
        }
    }
}

// --- Conversation ------------------------------------------------------------------------------

data class ConversationState(
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = true,
    val draft: String = "",
    val error: String? = null,
    /** Snap en cours d'ouverture ou ouvert : l'écran de lecture s'affiche par-dessus. */
    val viewer: SnapViewerState? = null,
    /** Snap déjà vu : on propose SnapCore+, qui permet de le revoir (ADR-025). */
    val offerPlus: Boolean = false,
    /** L'ami est en train d'écrire (ADR-027). */
    val friendTyping: Boolean = false,
)

// Pendant la saisie, au plus un signal toutes les 3 s ; l'indicateur reste affiché 6 s après le
// dernier, ce qui couvre l'intervalle entre deux signaux d'une saisie continue (ADR-027).
const val TYPING_SIGNAL_INTERVAL_MS = 3_000L
const val TYPING_VISIBLE_MS = 6_000L

sealed interface SnapViewerState {
    data object Loading : SnapViewerState
    data class Showing(val result: SnapOpenResult.Ready) : SnapViewerState
}

class ConversationViewModel(
    val friend: Friend,
    val myId: String,
    private val chat: ChatService,
    events: Flow<RealtimeEvent>,
    /** Snap à ouvrir dès le chargement (touché depuis la liste des chats) ; l'écran s'ouvre déjà sur sa lecture. */
    private var openOnLoad: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val outbox: StateFlow<List<OutgoingSnap>> = MutableStateFlow(emptyList()),
    /** Relance d'un snap en échec, par son identifiant dans la file d'envoi. */
    private val retrySend: (String) -> Unit = {},
) : ViewModel() {
    private val sent = SentTracker()
    private var lastTypingSignal = Long.MIN_VALUE / 2
    /** Snap affiché par le lecteur, marqué ouvert dès sa fermeture. */
    private var viewing: String? = null
    private var typingTimeout: Job? = null

    private val _state = MutableStateFlow(ConversationState(viewer = openOnLoad?.let { SnapViewerState.Loading }))
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            outbox.collect { items -> if (sent.newlySent(items)) refresh() else publish() }
        }
        viewModelScope.launch {
            events.collect { event ->
                when {
                    event is RealtimeEvent.Message && event.type == "typing" -> if (event.field("userId") == friend.id) showFriendTyping()
                    event.affectsChat() -> {
                        // Le message attendu est arrivé : l'indicateur laisse place au message.
                        if (event is RealtimeEvent.Message && event.type == "message.received") hideFriendTyping()
                        refresh()
                    }
                }
            }
        }
    }

    private fun showFriendTyping() {
        typingTimeout?.cancel()
        _state.update { it.copy(friendTyping = true) }
        typingTimeout = viewModelScope.launch {
            delay(TYPING_VISIBLE_MS)
            _state.update { it.copy(friendTyping = false) }
        }
    }

    private fun hideFriendTyping() {
        typingTimeout?.cancel()
        _state.update { it.copy(friendTyping = false) }
    }

    // Recharge la conversation et marque les Chats reçus comme lus (flèche pleine → contour).
    fun refresh() {
        val known = sent.mark()
        viewModelScope.launch {
            runCatching { chat.messages(friend.id) }
                .onSuccess { messages ->
                    sent.acknowledge(known)
                    // Ne touche pas à "error" : un message en attente d'affichage (ex. "déjà vu")
                    // ne doit pas être effacé par le rechargement qui suit ; consumeError() s'en charge.
                    _state.update { it.copy(messages = withPending(it.messages, messages), loading = false) }
                    publish()
                    val unreadText = messages.any { !it.isSnap && !it.isFrom(myId) && it.openedAt == null }
                    openOnLoad?.let { id ->
                        openOnLoad = null
                        val snap = messages.firstOrNull { it.id == id }
                        if (snap != null && canTryToOpen(snap, myId)) load(snap) else _state.update { it.copy(viewer = null) }
                    }
                    if (unreadText) runCatching { chat.markRead(friend.id) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message, viewer = null) } }
        }
    }

    fun onDraft(value: String) {
        _state.update { it.copy(draft = value, error = null) }
        if (value.isNotBlank() && now() - lastTypingSignal >= TYPING_SIGNAL_INTERVAL_MS) {
            lastTypingSignal = now()
            viewModelScope.launch { runCatching { chat.sendTyping(friend.id) } }
        }
    }

    // Interface optimiste : le Chat s'affiche tout de suite, en attente, puis est remplacé par celui
    // du backend. En cas d'échec il disparaît et le texte revient dans le champ s'il est vide.
    fun send() {
        val content = _state.value.draft.trim()
        if (content.isEmpty()) return
        // Le message suivant se signale tout de suite, sans attendre la fin de l'intervalle.
        lastTypingSignal = Long.MIN_VALUE / 2
        val local = ChatMessage(
            id = "local-${UUID.randomUUID()}", senderId = myId, recipientId = friend.id, type = ChatContentType.TEXT,
            content = content, createdAt = Instant.now(), openedAt = null, mediaPurged = false, pending = true,
        )
        _state.update { it.copy(draft = "", messages = listOf(local) + it.messages) }
        viewModelScope.launch {
            runCatching { chat.sendText(friend.id, content) }
                .onSuccess { sent ->
                    _state.update { state ->
                        val others = state.messages.filter { it.id != local.id }
                        state.copy(messages = if (others.any { it.id == sent.id }) others else listOf(sent) + others)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(messages = it.messages.filter { m -> m.id != local.id },
                            draft = it.draft.ifEmpty { content }, error = e.message)
                    }
                }
        }
    }

    // Vue unique (ADR-009) : c'est le backend qui accorde ou refuse l'URL de lecture. Un snap envoyé en
    // échec se relance d'un appui.
    fun open(message: ChatMessage) {
        if (message.failed) return retrySend(message.id.removePrefix(OutgoingSnap.OUTBOX_PREFIX))
        if (!canTryToOpen(message, myId) || _state.value.viewer != null) return
        load(message)
    }

    private fun load(message: ChatMessage) {
        viewing = message.id
        _state.update { it.copy(viewer = SnapViewerState.Loading) }
        viewModelScope.launch {
            when (val result = chat.openSnap(message)) {
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

    // Interface optimiste : le snap fermé passe tout de suite en ouvert, le rechargement confirme.
    fun closeViewer() {
        val opened = viewing.takeIf { _state.value.viewer is SnapViewerState.Showing }
        viewing = null
        _state.update { state ->
            state.copy(viewer = null, messages = state.messages.map { m ->
                if (m.id == opened && m.openedAt == null) m.copy(openedAt = Instant.ofEpochMilli(now())) else m
            })
        }
        refresh()
    }

    // Un rechargement ne doit pas effacer les Chats encore en cours d'envoi (les snaps de la file sont
    // ajoutés à part, par publish()).
    private fun withPending(current: List<ChatMessage>, loaded: List<ChatMessage>) =
        current.filter { it.pending && !it.id.startsWith(OutgoingSnap.OUTBOX_PREFIX) } + loaded

    // Snaps de la file d'envoi destinés à cet ami, en tête de la conversation (du plus récent au plus ancien).
    private fun publish() {
        val outgoing = sent.visible(outbox.value).filter { it.friendId == friend.id }.map { it.asMessage(myId) }
        _state.update { state ->
            val others = state.messages.filter { !it.id.startsWith(OutgoingSnap.OUTBOX_PREFIX) }
            state.copy(messages = outgoing.sortedByDescending { it.createdAt } + others)
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun dismissPlusOffer() = _state.update { it.copy(offerPlus = false) }

    private fun closeWith(message: String) {
        _state.update { it.copy(viewer = null, error = message) }
        refresh()
    }
}

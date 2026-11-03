package com.snapcore.app.ui.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.OutgoingSnap
import com.snapcore.app.domain.OutgoingStatus
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.SentTracker
import com.snapcore.app.domain.FriendStory
import com.snapcore.app.domain.StoryItem
import com.snapcore.app.domain.StoryService
import com.snapcore.app.domain.firstUnviewedIndex
import com.snapcore.app.ui.OptimisticGuard
import com.snapcore.app.ui.completeEvenIfClosed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- Onglet Stories ----------------------------------------------------------------------------

data class StoriesState(
    val mine: List<StoryItem> = emptyList(),
    val friends: List<FriendStory> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Ajout à Ma story en cours d'envoi en arrière-plan, ou en échec ; null sinon. */
    val upload: OutgoingSnap? = null,
)

class StoriesViewModel(
    private val service: StoryService,
    private val outbox: StateFlow<List<OutgoingSnap>> = MutableStateFlow(emptyList()),
    private val retrySend: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(StoriesState())
    val state: StateFlow<StoriesState> = _state.asStateFlow()

    private val sent = SentTracker()

    init {
        refresh()
        // Story publiée en arrière-plan : la carte Ma story recharge pour montrer le nouvel élément.
        viewModelScope.launch {
            outbox.collect { items ->
                val story = items.filter { it.target == SendTarget.ToStory }
                if (sent.newlySent(story)) refresh(showIndicator = false)
                // Un échec prime : c'est lui qui attend une action de l'utilisateur.
                val upload = story.firstOrNull { it.status == OutgoingStatus.FAILED } ?: story.firstOrNull { it.status == OutgoingStatus.SENDING }
                _state.update { it.copy(upload = upload) }
            }
        }
    }

    fun retryUpload() {
        _state.value.upload?.takeIf { it.status == OutgoingStatus.FAILED }?.let { retrySend(it.id) }
    }

    /**
     * Le retour sur l'onglet recharge en silence : l'indicateur de rafraîchissement ne s'affiche que
     * pour un tirage vers le bas (ou le tout premier chargement), pas à chaque passage sur la page.
     */
    fun refresh(showIndicator: Boolean = true) {
        if (showIndicator) _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { service.mine() to service.feed() }
                .onSuccess { (mine, friends) -> _state.update { it.copy(mine = mine, friends = friends, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

// --- Lecteur de story --------------------------------------------------------------------------

sealed interface PlayerState {
    data object Loading : PlayerState
    data class Playing(val items: List<StoryItem>, val index: Int, val url: String?) : PlayerState {
        val current get() = items[index]
    }
    data object Finished : PlayerState
    data class Failed(val message: String) : PlayerState
}

/**
 * Lit la story d'un ami (authorId) ou la sienne (authorId = null), à partir du premier élément
 * non vu. Chaque élément affiché demande sa propre URL courte, ce qui enregistre la vue (ADR-013).
 */
class StoryPlayerViewModel(
    private val service: StoryService,
    private val authorId: String?,
    private val startItemId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Loading)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val items = runCatching {
                if (authorId == null) service.mine() else service.feed().firstOrNull { it.author.id == authorId }?.items.orEmpty()
            }.getOrElse {
                _state.value = PlayerState.Failed(it.message ?: "Story indisponible")
                return@launch
            }
            if (items.isEmpty()) {
                _state.value = PlayerState.Finished
                return@launch
            }
            val start = startItemId?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: firstUnviewedIndex(items)
            show(items, start)
        }
    }

    fun next() = move(+1)
    fun previous() = move(-1)

    private fun move(delta: Int) {
        val playing = _state.value as? PlayerState.Playing ?: return
        val target = playing.index + delta
        when {
            target >= playing.items.size -> _state.value = PlayerState.Finished
            target < 0 -> Unit
            else -> show(playing.items, target)
        }
    }

    private fun show(items: List<StoryItem>, index: Int) {
        _state.value = PlayerState.Playing(items, index, url = null)
        viewModelScope.launch {
            runCatching { service.viewUrl(items[index]) }
                .onSuccess { url ->
                    val current = _state.value as? PlayerState.Playing
                    if (current?.index == index) _state.value = current.copy(url = url)
                }
                // Élément expiré entre-temps (404) : on passe au suivant plutôt que de bloquer.
                .onFailure { move(+1) }
        }
    }
}

// --- Ma story : vues et suppression ------------------------------------------------------------

data class MyStoryState(
    val items: List<StoryItem> = emptyList(),
    val loading: Boolean = true,
    val viewersOf: StoryItem? = null,
    val viewers: List<Friend>? = null,
    val message: String? = null,
)

class MyStoryViewModel(private val service: StoryService) : ViewModel() {
    private val _state = MutableStateFlow(MyStoryState())
    val state: StateFlow<MyStoryState> = _state.asStateFlow()

    private val guard = OptimisticGuard()

    init {
        refresh()
    }

    fun refresh() {
        val mark = guard.mark()
        viewModelScope.launch {
            runCatching { service.mine() }
                // Une suppression en cours ne doit pas voir réapparaître son élément.
                .onSuccess { items -> if (guard.canApply(mark)) _state.update { it.copy(items = items, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, message = e.message) } }
        }
    }

    // Seul l'auteur voit qui a regardé chaque élément (ADR-013).
    fun showViewers(item: StoryItem) {
        _state.update { it.copy(viewersOf = item, viewers = null) }
        viewModelScope.launch {
            runCatching { service.viewers(item) }
                .onSuccess { list -> _state.update { it.copy(viewers = list) } }
                .onFailure { e -> _state.update { it.copy(viewersOf = null, message = e.message) } }
        }
    }

    fun hideViewers() = _state.update { it.copy(viewersOf = null, viewers = null) }

    // Interface optimiste : l'élément disparaît dès l'appui et revient à sa place si le backend refuse.
    fun delete(item: StoryItem) {
        if (item !in _state.value.items) return
        guard.begin()
        _state.update { it.copy(items = it.items - item, viewersOf = it.viewersOf?.takeIf { shown -> shown != item }) }
        viewModelScope.launch {
            val result = completeEvenIfClosed { service.delete(item) }
            guard.end()
            result.onFailure { e ->
                _state.update { it.copy(items = (it.items + item).sortedBy(StoryItem::createdAt), message = e.message ?: "Suppression impossible") }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

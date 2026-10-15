package com.snapcore.app.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.CameraFilter
import com.snapcore.app.domain.CameraFilters
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.FilterRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewState(
    val filterIndex: Int = 0,
    val rendering: Boolean = false,
    val error: String? = null,
    /** Média filtré prêt à envoyer, consommé par la navigation vers l'écran d'envoi. */
    val ready: CapturedMedia? = null,
) {
    val filter: CameraFilter get() = CameraFilters.ALL[filterIndex]
}

// Le filtre se choisit sur l'aperçu, après la capture, comme dans Snapchat (ADR-022). Il n'est
// incrusté dans le média qu'au moment d'envoyer : l'original reste intact tant que l'aperçu est
// ouvert, pour pouvoir revenir en arrière et changer de filtre.
class PreviewViewModel(private val media: CapturedMedia, private val renderer: FilterRenderer) : ViewModel() {
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var rendered: CapturedMedia? = null

    fun swipe(step: Int) = _state.update {
        if (it.rendering) it else it.copy(filterIndex = CameraFilters.next(it.filterIndex, step))
    }

    fun confirm() {
        if (_state.value.rendering) return
        _state.update { it.copy(rendering = true, error = null) }
        viewModelScope.launch {
            discardRendered()
            runCatching { renderer.render(media, _state.value.filter) }
                .onSuccess { result ->
                    rendered = result
                    _state.update { it.copy(rendering = false, ready = result) }
                }
                .onFailure { _state.update { it.copy(rendering = false, error = "Impossible d'appliquer le filtre") } }
        }
    }

    fun consumeReady() = _state.update { it.copy(ready = null) }

    private fun discardRendered() {
        rendered?.takeIf { it.file != media.file }?.file?.delete()
        rendered = null
    }

    // L'aperçu est quitté pour de bon (abandon ou snap envoyé) : rien ne reste dans le cache (ADR-011).
    override fun onCleared() {
        discardRendered()
        media.file.delete()
    }
}

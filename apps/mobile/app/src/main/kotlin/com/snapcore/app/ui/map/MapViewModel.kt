package com.snapcore.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendLocation
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.GeoPoint
import com.snapcore.app.domain.LocationProvider
import com.snapcore.app.domain.LocationReporter
import com.snapcore.app.domain.LocationSharing
import com.snapcore.app.domain.MapService
import com.snapcore.app.domain.NoAnalytics
import com.snapcore.app.domain.StoryPin
import com.snapcore.app.domain.storyPins
import com.snapcore.app.ui.completeEvenIfClosed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class MapUiState(
    /** Ma position, affichée sur ma carte même en mode fantôme ; null sans permission ou sans signal. */
    val me: GeoPoint? = null,
    val permission: Boolean = false,
    val friends: List<FriendLocation> = emptyList(),
    val pins: List<StoryPin> = emptyList(),
    /** null tant que le réglage n'a pas été lu. */
    val sharing: LocationSharing? = null,
    val selected: FriendLocation? = null,
    /** Mes amis, pour choisir qui exclure ou autoriser ; chargés à l'ouverture des réglages. */
    val allFriends: List<Friend> = emptyList(),
    val error: String? = null,
    val now: Instant = Instant.now(),
)

/**
 * Snap Map (ADR-031). Rechargée toutes les 30 s par l'écran tant qu'il est visible ; chaque rechargement
 * renvoie aussi ma position (au plus une fois par minute, jamais en mode fantôme, voir [LocationReporter]).
 */
class MapViewModel(
    private val accountId: String,
    private val map: MapService,
    private val reporter: LocationReporter,
    private val location: LocationProvider,
    private val friendsService: FriendsService,
    private val analytics: Analytics = NoAnalytics,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState(permission = location.hasPermission(), sharing = reporter.sharing.value, now = now()))
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /** Un réglage en cours d'enregistrement : un rechargement ne doit pas le défaire à l'écran. */
    private var savingSharing = 0

    fun refresh() {
        viewModelScope.launch {
            val friends = async { attempt { map.friendLocations() } }
            val stories = async { attempt { map.stories() } }
            val sharing = async { if (_state.value.sharing == null) attempt { reporter.loadSharing(accountId) } else null }
            val me = reporter.report(accountId)
            _state.update { current ->
                val friendList = friends.await() ?: current.friends
                current.copy(
                    me = me ?: current.me,
                    permission = location.hasPermission(),
                    friends = friendList,
                    pins = stories.await()?.let(::storyPins) ?: current.pins,
                    sharing = if (savingSharing > 0) current.sharing else sharing.await() ?: reporter.sharing.value ?: current.sharing,
                    // La fiche ouverte suit la nouvelle position de l'ami, ou se ferme s'il n'est plus visible.
                    selected = current.selected?.let { open -> friendList.firstOrNull { it.friend.id == open.friend.id } },
                    now = now(),
                )
            }
        }
    }

    /** Réponse à la demande de permission de localisation. */
    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permission = granted) }
        if (granted) refresh()
    }

    fun select(friend: FriendLocation?) = _state.update { it.copy(selected = friend) }

    fun loadFriends() {
        viewModelScope.launch {
            attempt { friendsService.friends() }?.let { list -> _state.update { it.copy(allFriends = list.sortedBy { f -> f.displayName.lowercase() }) } }
        }
    }

    /** Optimiste : le réglage s'applique tout de suite à l'écran, et revient en arrière si l'envoi échoue. */
    fun updateSharing(sharing: LocationSharing) {
        if (!sharing.valid) return
        val previous = _state.value.sharing
        _state.update { it.copy(sharing = sharing, error = null) }
        savingSharing++
        viewModelScope.launch {
            val result = completeEvenIfClosed { reporter.updateSharing(accountId, sharing) }
            savingSharing--
            result.onSuccess { saved ->
                analytics.capture("location_sharing_changed", mapOf("mode" to saved.mode.name))
                _state.update { it.copy(sharing = saved) }
                if (!saved.ghost) refresh()
            }.onFailure {
                _state.update { it.copy(sharing = previous, error = "Réglage non enregistré. Vérifie ta connexion et réessaie.") }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

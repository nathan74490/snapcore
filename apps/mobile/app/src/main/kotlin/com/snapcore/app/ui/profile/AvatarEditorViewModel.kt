package com.snapcore.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.NoAnalytics
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AvatarCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Catégories de l'éditeur, dans l'ordre d'affichage, avec leur libellé (ADR-028). */
enum class AvatarPart(val key: String, val label: String, val isColor: Boolean = false) {
    TOP("top", "Coiffure"),
    HAIR_COLOR("hairColor", "Cheveux", isColor = true),
    SKIN("skinColor", "Peau", isColor = true),
    EYES("eyes", "Yeux"),
    EYEBROWS("eyebrows", "Sourcils"),
    MOUTH("mouth", "Bouche"),
    FACIAL_HAIR("facialHair", "Barbe"),
    ACCESSORIES("accessories", "Lunettes"),
    CLOTHING("clothing", "Tenue"),
    CLOTHES_COLOR("clothesColor", "Couleur tenue", isColor = true),
    BACKGROUND("backgroundColor", "Fond", isColor = true),
}

// Le tirage au sort reste sur des expressions avenantes ; les autres restent choisissables à la main.
private val FRIENDLY = mapOf(
    "eyes" to setOf("default", "happy", "wink", "side", "squint", "surprised", "hearts"),
    "mouth" to setOf("default", "smile", "twinkle", "tongue", "serious"),
    "eyebrows" to setOf("default", "defaultNatural", "flatNatural", "raisedExcited", "raisedExcitedNatural", "upDownNatural"),
)

/** Choix fait dans l'éditeur, enregistré par le profil (interface optimiste). */
sealed interface AvatarChange {
    data class Set(val options: Map<String, String>) : AvatarChange
    data object Remove : AvatarChange
}

data class AvatarEditorState(
    val catalog: AvatarCatalog? = null,
    val options: Map<String, String> = emptyMap(),
    val part: AvatarPart = AvatarPart.TOP,
    val loading: Boolean = true,
    val error: String? = null,
    /** Avatar validé ou retiré : l'écran se ferme et le profil l'affiche aussitôt, puis l'enregistre. */
    val result: AvatarChange? = null,
)

class AvatarEditorViewModel(
    private val accounts: AccountService,
    private val initial: Map<String, String>?,
    private val random: Random = Random.Default,
    private val analytics: Analytics = NoAnalytics,
) : ViewModel() {
    private val _state = MutableStateFlow(AvatarEditorState())
    val state: StateFlow<AvatarEditorState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { accounts.avatarCatalog() }
                .onSuccess { catalog ->
                    // Avatar existant complété par les valeurs par défaut : une clé ajoutée plus tard ne casse rien.
                    _state.update { it.copy(catalog = catalog, options = catalog.defaults + initial.orEmpty(), loading = false) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Avatars indisponibles") } }
        }
    }

    fun showPart(part: AvatarPart) = _state.update { it.copy(part = part) }

    fun choose(part: AvatarPart, value: String) = _state.update { it.copy(options = it.options + (part.key to value)) }

    fun randomize() {
        val catalog = _state.value.catalog ?: return
        val options = catalog.options.mapValues { (key, values) ->
            val pool = FRIENDLY[key]?.let { friendly -> values.filter { it in friendly } }?.ifEmpty { null } ?: values
            // Barbe et lunettes une fois sur trois seulement, comme un vrai visage.
            if ("none" in values && key != "top" && random.nextInt(3) != 0) "none" else pool[random.nextInt(pool.size)]
        }
        _state.update { it.copy(options = options) }
        analytics.capture(AnalyticsEvents.AVATAR_RANDOMIZED)
    }

    // Pas d'attente du backend : le choix étant pris dans le catalogue, il n'a pas de raison d'être refusé.
    fun save() = _state.update { it.copy(result = AvatarChange.Set(it.options)) }

    fun remove() = _state.update { it.copy(result = AvatarChange.Remove) }
}

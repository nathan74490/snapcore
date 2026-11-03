package com.snapcore.app.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToLong

// Snap Map (ADR-031) : position des amis, mon mode fantôme et les stories géolocalisées de mes amis.

data class GeoPoint(val latitude: Double, val longitude: Double)

/** Qui voit ma position. Mode fantôme par défaut : personne tant que je n'ai rien choisi. */
enum class LocationSharingMode(val label: String) {
    GHOST("Mode fantôme"),
    ALL_FRIENDS("Mes amis"),
    FRIENDS_EXCEPT("Mes amis sauf…"),
    ONLY_FRIENDS("Seulement ces amis…");

    /** Ce mode s'appuie-t-il sur une liste d'amis (exclus ou autorisés) ? */
    val usesList get() = this == FRIENDS_EXCEPT || this == ONLY_FRIENDS
}

/**
 * Réglage de partage. [friendIds] : amis exclus (FRIENDS_EXCEPT) ou autorisés (ONLY_FRIENDS) ; la
 * liste est conservée quand on change de mode, pour la retrouver en y revenant.
 */
data class LocationSharing(val mode: LocationSharingMode, val friendIds: Set<String> = emptySet()) {
    val ghost get() = mode == LocationSharingMode.GHOST

    /** Résumé affiché sous « Qui peut voir ma position ». */
    val summary: String get() = when (mode) {
        LocationSharingMode.GHOST -> "Personne : tu es en mode fantôme"
        LocationSharingMode.ALL_FRIENDS -> "Tous mes amis"
        LocationSharingMode.FRIENDS_EXCEPT -> if (friendIds.isEmpty()) "Tous mes amis" else "Mes amis sauf ${amis(friendIds.size)}"
        LocationSharingMode.ONLY_FRIENDS -> if (friendIds.isEmpty()) "Personne pour l'instant" else "Seulement ${amis(friendIds.size)}"
    }

    /** « Seulement ces amis » sans personne cochée équivaut à ne rien partager : on le refuse. */
    val valid get() = mode != LocationSharingMode.ONLY_FRIENDS || friendIds.isNotEmpty()

    private fun amis(count: Int) = if (count == 1) "1 ami" else "$count amis"

    companion object {
        val Ghost = LocationSharing(LocationSharingMode.GHOST)
    }
}

data class FriendLocation(val friend: Friend, val position: GeoPoint, val updatedAt: Instant)

/** Story d'un ami épinglée là où elle a été publiée. */
data class MapStory(val author: Friend, val position: GeoPoint, val item: StoryItem)

/** Pin de stories sur la carte : celles d'un même ami publiées au même endroit (arrondi ~100 m côté backend). */
data class StoryPin(val author: Friend, val position: GeoPoint, val items: List<StoryItem>) {
    val hasUnviewed get() = items.any { !it.viewed }
    val thumbnailUrl get() = items.lastOrNull { it.thumbnailUrl != null }?.thumbnailUrl

    /** Lecture dans le lecteur de stories habituel, du plus ancien au plus récent. */
    fun asFriendStory() = FriendStory(author, items, hasUnviewed, thumbnailUrl)
}

fun storyPins(stories: List<MapStory>): List<StoryPin> = stories
    .groupBy { Triple(it.author.id, it.position.latitude, it.position.longitude) }
    .map { (_, group) -> StoryPin(group.first().author, group.first().position, group.map { it.item }.sortedBy { it.createdAt }) }
    .sortedBy { pin -> pin.items.maxOf { it.createdAt } }

/** « À l'instant », « Il y a 12 min », « Il y a 3 h » : fraîcheur de la position d'un ami. */
fun lastSeenLabel(updatedAt: Instant, now: Instant): String {
    val minutes = Duration.between(updatedAt, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "À l'instant"
        minutes < 60 -> "Il y a $minutes min"
        else -> "Il y a ${(minutes / 60.0).roundToLong().coerceAtLeast(1)} h"
    }
}

interface MapService {
    /** Amis dont la position m'est visible (règle de partage côté backend), mise à jour depuis moins de 8 h. */
    suspend fun friendLocations(): List<FriendLocation>
    suspend fun stories(): List<MapStory>
    suspend fun sharing(): LocationSharing
    suspend fun updateSharing(sharing: LocationSharing): LocationSharing
    /** Ignorée par le backend en mode fantôme ; l'app ne l'envoie de toute façon pas dans ce cas. */
    suspend fun updateLocation(position: GeoPoint)
}

/** Position du téléphone, seulement pendant l'utilisation de l'app (pas d'accès en arrière-plan). */
interface LocationProvider {
    fun hasPermission(): Boolean
    /** Position actuelle, ou la dernière connue ; null sans permission ou sans signal. */
    suspend fun current(): GeoPoint?
}

object NoLocation : LocationProvider {
    override fun hasPermission() = false
    override suspend fun current(): GeoPoint? = null
}

/** Cadrage de la carte : un centre et un niveau de zoom. */
data class MapFocus(val center: GeoPoint, val zoom: Double)

/**
 * Cadrage qui englobe [points] (mes amis et leurs stories, quand ma propre position est inconnue).
 * Le zoom est déduit de l'étendue, en projection Web Mercator avec une marge, entre [minZoom] et [maxZoom].
 * Un seul point, ou des points confondus, donnent [maxZoom].
 */
fun focusOn(points: List<GeoPoint>, minZoom: Double = 3.0, maxZoom: Double = 13.0): MapFocus? {
    if (points.isEmpty()) return null
    val south = points.minOf { it.latitude }
    val north = points.maxOf { it.latitude }
    val west = points.minOf { it.longitude }
    val east = points.maxOf { it.longitude }
    val center = GeoPoint((south + north) / 2, (west + east) / 2)
    // En Mercator, un degré de latitude s'étire de 1/cos(latitude) par rapport à un degré de longitude.
    val span = maxOf(east - west, (north - south) / cos(center.latitude * PI / 180)) * 1.6
    val zoom = if (span <= 0.0) maxZoom else (ln(360 / span) / ln(2.0)).coerceIn(minZoom, maxZoom)
    return MapFocus(center, zoom)
}

/** Distance approximative en mètres (projection équirectangulaire), suffisante à l'échelle d'une ville. */
fun GeoPoint.metersTo(other: GeoPoint): Double {
    val x = (other.longitude - longitude) * cos((latitude + other.latitude) / 2 * PI / 180)
    val y = other.latitude - latitude
    return hypot(x, y) * PI / 180 * 6_371_000
}

/**
 * Rang de chaque pin parmi les marqueurs qu'il recouvrirait : avatar d'un ami ou pin précédent à moins de
 * [radiusMeters]. 0 : seul à cet endroit, affiché sur son point ; n : n-ième à s'écarter, l'écran le place
 * autour du point pour qu'il ne cache pas l'avatar de son auteur ni un autre pin.
 */
fun pinSlots(pins: List<StoryPin>, friends: List<FriendLocation>, radiusMeters: Double = 500.0): List<Int> =
    pins.mapIndexed { index, pin ->
        friends.count { it.position.metersTo(pin.position) < radiusMeters } +
            pins.take(index).count { it.position.metersTo(pin.position) < radiusMeters }
    }

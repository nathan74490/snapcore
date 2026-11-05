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

/** Position ou décalage à l'écran, en dp ; y compte vers le bas. */
data class ScreenOffset(val x: Double, val y: Double)

// Les dimensions comprennent le prénom, l'avatar sélectionné et leur ombre.
const val FRIEND_MARKER_SIZE_DP = 96.0
const val STORY_MARKER_SIZE_DP = 50.0
const val MAP_MARKER_GAP_DP = 6.0

data class MapMarker(val center: ScreenOffset, val width: Double, val height: Double)
data class MapMarkerArea(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Place les rectangles réellement projetés par la carte (rotation, zoom et densité compris).
 * Le premier marqueur — moi, si ma position est connue — reste à sa position exacte.
 * La recherche s'élargit jusqu'à trouver une place, sans retour sur une place occupée.
 */
fun mapMarkerOffsets(markers: List<MapMarker>, area: MapMarkerArea? = null): List<ScreenOffset> {
    val occupied = mutableListOf<MapMarker>()
    val step = (markers.maxOfOrNull { maxOf(it.width, it.height) } ?: 0.0) + MAP_MARKER_GAP_DP
    return markers.map { marker ->
        fun free(offset: ScreenOffset): Boolean = occupied.none { other ->
            kotlin.math.abs(marker.center.x + offset.x - other.center.x) < (marker.width + other.width) / 2 + MAP_MARKER_GAP_DP &&
                kotlin.math.abs(marker.center.y + offset.y - other.center.y) < (marker.height + other.height) / 2 + MAP_MARKER_GAP_DP
        }
        val candidates = sequence {
            yield(ScreenOffset(0.0, 0.0))
            var ring = 1
            while (ring <= occupied.size + 1) {
                val edge = (-ring..ring).flatMap { x -> (-ring..ring).mapNotNull { y ->
                    ScreenOffset(x * step, y * step).takeIf { kotlin.math.abs(x) == ring || kotlin.math.abs(y) == ring }
                } }.sortedBy { it.x * it.x + it.y * it.y }
                yieldAll(edge)
                ring++
            }
        }
        // Un ami visible ne doit pas être déplacé hors de l'écran pour libérer ma position.
        // Si l'écran est plein, on privilégie malgré tout une place sans collision.
        fun fits(offset: ScreenOffset): Boolean = area == null ||
            (marker.center.x + offset.x - marker.width / 2 >= area.left &&
                marker.center.x + offset.x + marker.width / 2 <= area.right &&
                marker.center.y + offset.y - marker.height / 2 >= area.top &&
                marker.center.y + offset.y + marker.height / 2 <= area.bottom)
        val visible = area != null && marker.center.x in area.left..area.right && marker.center.y in area.top..area.bottom
        val available = candidates.filter(::free)
        val offset = if (occupied.isNotEmpty() && visible) available.firstOrNull(::fits) ?: available.first() else available.first()
        occupied += marker.copy(center = ScreenOffset(marker.center.x + offset.x, marker.center.y + offset.y))
        offset
    }
}

/**
 * Place affichée de chaque marqueur, écartée de ceux qu'il recouvrirait (BUG-027, BUG-028), au [zoom] et à
 * l'orientation [bearing] (degrés) de la carte. [sizes] : taille en dp de chaque marqueur.
 *
 * Projection Web Mercator calculée ici, comme Mapbox (512 dp de monde au zoom 0), et non par la carte : le
 * résultat ne dépend pas du centre. Faire glisser la carte ne déplace donc aucun marqueur, et un ami hors
 * champ ne gêne pas ceux qui sont à l'écran.
 */
fun mapMarkerLayout(positions: List<GeoPoint>, sizes: List<Double>, zoom: Double, bearing: Double = 0.0): List<GeoPoint> {
    val world = 512.0 * Math.pow(2.0, zoom)
    val angle = bearing * PI / 180
    val cosB = cos(angle)
    val sinB = kotlin.math.sin(angle)
    fun project(p: GeoPoint): ScreenOffset {
        val lat = p.latitude.coerceIn(-85.0511, 85.0511) * PI / 180
        val x = world * (p.longitude + 180) / 360
        val y = world * (0.5 - ln(kotlin.math.tan(PI / 4 + lat / 2)) / (2 * PI))
        // Carte tournée de [bearing] : les rectangles des marqueurs restent alignés sur l'écran.
        return ScreenOffset(x * cosB + y * sinB, -x * sinB + y * cosB)
    }
    fun unproject(s: ScreenOffset): GeoPoint {
        val x = s.x * cosB - s.y * sinB
        val y = s.x * sinB + s.y * cosB
        val longitude = x / world * 360 - 180
        val latitude = (2 * kotlin.math.atan(kotlin.math.exp((0.5 - y / world) * 2 * PI)) - PI / 2) * 180 / PI
        return GeoPoint(latitude, longitude)
    }
    val screen = positions.map(::project)
    val offsets = mapMarkerOffsets(screen.mapIndexed { i, s -> MapMarker(s, sizes[i], sizes[i]) })
    return positions.indices.map { i ->
        if (offsets[i] == ScreenOffset(0.0, 0.0)) positions[i]
        else unproject(ScreenOffset(screen[i].x + offsets[i].x, screen[i].y + offsets[i].y))
    }
}

/** Rayon des amis et stories pris en compte dans le premier cadrage sur moi (BUG-026 d'ADR-023). */
const val NEARBY_FOCUS_RADIUS_METERS = 20_000.0

/**
 * Premier cadrage de la carte quand ma position est connue : moi et ce qui est à moins de [radiusMeters]
 * (amis, stories), pour qu'un ami à 2 km ne reste pas hors de l'écran. Les amis lointains (autre ville)
 * n'élargissent pas le cadre : la carte reste à l'échelle d'une ville ou d'une agglomération.
 */
fun focusAround(me: GeoPoint, others: List<GeoPoint>, radiusMeters: Double = NEARBY_FOCUS_RADIUS_METERS): MapFocus =
    focusOn(listOf(me) + others.filter { me.metersTo(it) <= radiusMeters }, minZoom = 9.0, maxZoom = 13.0)!!

/** Distance lue par TalkBack sur un marqueur : « à 300 m », « à 2,4 km », « à 35 km ». */
fun distanceLabel(meters: Double): String = when {
    meters < 50 -> "tout près de toi"
    meters < 1_000 -> "à ${(meters / 50).roundToLong() * 50} m"
    meters < 10_000 -> "à ${"%.1f".format(java.util.Locale.FRENCH, meters / 1_000)} km"
    else -> "à ${(meters / 1_000).roundToLong()} km"
}

/** Ce que TalkBack annonce sur la carte : ce qui s'y trouve, et comment l'explorer. */
fun mapSummary(friends: Int, stories: Int, meKnown: Boolean): String {
    val parts = listOfNotNull(
        when (friends) { 0 -> "aucun ami visible"; 1 -> "1 ami visible"; else -> "$friends amis visibles" },
        when (stories) { 0 -> null; 1 -> "1 story"; else -> "$stories stories" },
        if (meKnown) null else "ta position n'est pas connue",
    )
    return "Carte. " + parts.joinToString(", ").replaceFirstChar(Char::uppercase) +
        ". Glisse ton doigt sur l'écran pour trouver tes amis, ou utilise le bouton Me recentrer."
}

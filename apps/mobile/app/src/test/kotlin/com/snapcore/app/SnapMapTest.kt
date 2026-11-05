package com.snapcore.app

import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendLocation
import com.snapcore.app.domain.GeoPoint
import com.snapcore.app.domain.LocationProvider
import com.snapcore.app.domain.LocationReporter
import com.snapcore.app.domain.LocationSharing
import com.snapcore.app.domain.LocationSharingMode
import com.snapcore.app.domain.MapService
import com.snapcore.app.domain.MapStory
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.MediaPublisher
import com.snapcore.app.domain.MediaUploader
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.SnapService
import com.snapcore.app.domain.StoryItem
import com.snapcore.app.domain.StoryPin
import com.snapcore.app.domain.focusOn
import com.snapcore.app.domain.lastSeenLabel
import com.snapcore.app.domain.metersTo
import com.snapcore.app.domain.MapMarker
import com.snapcore.app.domain.MapMarkerArea
import com.snapcore.app.domain.ScreenOffset
import com.snapcore.app.domain.FRIEND_MARKER_SIZE_DP
import com.snapcore.app.domain.MAP_MARKER_GAP_DP
import com.snapcore.app.domain.STORY_MARKER_SIZE_DP
import com.snapcore.app.domain.focusAround
import com.snapcore.app.domain.mapMarkerOffsets
import com.snapcore.app.domain.mapMarkerLayout
import com.snapcore.app.domain.distanceLabel
import com.snapcore.app.domain.mapSummary
import com.snapcore.app.domain.storyPins
import com.snapcore.app.ui.map.MapViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

private val paris = GeoPoint(48.8566, 2.3522)
private val t0: Instant = Instant.parse("2026-11-04T10:00:00Z")

private class FakeMap(var current: LocationSharing = LocationSharing.Ghost) : MapService {
    val sent = mutableListOf<GeoPoint>()
    var sharingReads = 0
    var friends = emptyList<FriendLocation>()
    var stories = emptyList<MapStory>()
    var failUpdate = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun friendLocations() = friends
    override suspend fun stories() = stories
    override suspend fun sharing(): LocationSharing { sharingReads++; return current }
    override suspend fun updateSharing(sharing: LocationSharing): LocationSharing {
        gate?.await()
        if (failUpdate) error("réseau")
        current = sharing
        return sharing
    }
    override suspend fun updateLocation(position: GeoPoint) { sent += position }
}

private class FixedLocation(var granted: Boolean = true, var position: GeoPoint? = paris) : LocationProvider {
    override fun hasPermission() = granted
    override suspend fun current() = position
}

private class Clock(var now: Instant = t0) { fun advance(minutes: Long) { now = now.plus(Duration.ofMinutes(minutes)) } }

private fun friend(id: String) = Friend(id, id, null)
private fun item(id: String, minute: Long, viewed: Boolean = false) =
    StoryItem(id, MediaKind.PHOTO, t0.plusSeconds(minute * 60), t0.plusSeconds(minute * 60 + 86_400), viewed, thumbnailUrl = "https://cdn/$id.jpg")

class SnapMapRulesTest {
    @Test
    fun `fraicheur de la position d'un ami`() {
        assertEquals("À l'instant", lastSeenLabel(t0, t0.plusSeconds(30)))
        assertEquals("Il y a 12 min", lastSeenLabel(t0, t0.plusSeconds(12 * 60)))
        assertEquals("Il y a 3 h", lastSeenLabel(t0, t0.plusSeconds(3 * 3600 + 600)))
        assertEquals("À l'instant", lastSeenLabel(t0, t0.minusSeconds(5))) // horloges un peu décalées
    }

    @Test
    fun `les stories d'un ami au meme endroit forment un seul pin, de la plus ancienne a la plus recente`() {
        val nathan = friend("nathan")
        val pins = storyPins(listOf(
            MapStory(nathan, paris, item("b", 20)),
            MapStory(nathan, GeoPoint(45.764, 4.835), item("lyon", 5, viewed = true)),
            MapStory(nathan, paris, item("a", 10, viewed = true)),
            MapStory(friend("rober"), paris, item("r", 15)),
        ))
        assertEquals(3, pins.size)
        val parisPin = pins.single { it.author.id == "nathan" && it.position == paris }
        assertEquals(listOf("a", "b"), parisPin.items.map { it.id })
        assertTrue(parisPin.hasUnviewed)
        assertFalse(pins.single { it.position.latitude == 45.764 }.hasUnviewed)
        assertEquals("nathan", parisPin.asFriendStory().author.id)
    }

    @Test
    fun `resume du reglage et liste vide refusee pour seulement ces amis`() {
        assertEquals("Personne : tu es en mode fantôme", LocationSharing.Ghost.summary)
        assertEquals("Tous mes amis", LocationSharing(LocationSharingMode.ALL_FRIENDS).summary)
        assertEquals("Mes amis sauf 2 amis", LocationSharing(LocationSharingMode.FRIENDS_EXCEPT, setOf("a", "b")).summary)
        assertEquals("Seulement 1 ami", LocationSharing(LocationSharingMode.ONLY_FRIENDS, setOf("a")).summary)
        assertFalse(LocationSharing(LocationSharingMode.ONLY_FRIENDS).valid)
        assertTrue(LocationSharing(LocationSharingMode.FRIENDS_EXCEPT).valid)
    }

    @Test
    fun `sans ma position, la carte cadre mes amis et leurs stories`() {
        assertNull(focusOn(emptyList()))
        // Un seul point : zoom ville, centré dessus.
        assertEquals(13.0, focusOn(listOf(paris))!!.zoom, 0.0)
        assertEquals(paris, focusOn(listOf(paris, paris))!!.center)
        // Paris et Lyon (environ 400 km) : centre entre les deux, zoom région.
        val lyon = GeoPoint(45.764, 4.835)
        val focus = focusOn(listOf(paris, lyon))!!
        assertEquals(47.31, focus.center.latitude, 0.01)
        assertEquals(3.59, focus.center.longitude, 0.01)
        assertTrue("zoom ${focus.zoom}", focus.zoom in 5.0..7.0)
        // Bout du monde : zoom minimal.
        assertEquals(3.0, focusOn(listOf(paris, GeoPoint(-33.86, 151.2)))!!.zoom, 0.0)
    }

    @Test
    fun `distance approximative entre deux points`() {
        assertEquals(392_000.0, paris.metersTo(GeoPoint(45.764, 4.835)), 5_000.0)
        assertEquals(0.0, paris.metersTo(paris), 0.0)
    }

    @Test
    fun `moi, amis et stories restent distincts meme dans un amas dense (BUG-027 et BUG-028)`() {
        val markers = List(50) { i ->
            val size = if (i < 25) FRIEND_MARKER_SIZE_DP else STORY_MARKER_SIZE_DP
            MapMarker(ScreenOffset(100.0 + i % 3, 200.0 + i % 5), size, size)
        }
        val offsets = mapMarkerOffsets(markers)
        assertEquals("je reste sur ma position", ScreenOffset(0.0, 0.0), offsets.first())
        for (i in markers.indices) for (j in 0 until i) {
            val a = markers[i]; val b = markers[j]
            val dx = kotlin.math.abs(a.center.x + offsets[i].x - b.center.x - offsets[j].x)
            val dy = kotlin.math.abs(a.center.y + offsets[i].y - b.center.y - offsets[j].y)
            assertTrue("marqueurs $i et $j, libellés compris", dx >= (a.width + b.width) / 2 + MAP_MARKER_GAP_DP ||
                dy >= (a.height + b.height) / 2 + MAP_MARKER_GAP_DP)
        }
    }

    @Test
    fun `un ami superpose a moi au bord de l'ecran reste visible et selectionnable (BUG-028)`() {
        val markers = List(2) { MapMarker(ScreenOffset(32.0, 200.0), 96.0, 96.0) }
        val offsets = mapMarkerOffsets(markers, MapMarkerArea(0.0, 96.0, 384.0, 700.0))
        assertEquals(ScreenOffset(0.0, 0.0), offsets.first())
        assertTrue(markers[1].center.x + offsets[1].x - 48.0 >= 0.0)
        assertTrue(markers[1].center.x + offsets[1].x + 48.0 <= 384.0)
        assertTrue(offsets[1].x >= 102.0)
    }

    @Test
    fun `l'ecartement depend du zoom, pas du centre, et separe des amis au meme endroit (BUG-027 et BUG-028)`() {
        val annecy = GeoPoint(45.8992, 6.1294)
        val positions = listOf(annecy, annecy, GeoPoint(45.8993, 6.1295), GeoPoint(48.8606, 2.3376))
        val sizes = listOf(FRIEND_MARKER_SIZE_DP, FRIEND_MARKER_SIZE_DP, STORY_MARKER_SIZE_DP, FRIEND_MARKER_SIZE_DP)
        val placed = mapMarkerLayout(positions, sizes, zoom = 13.0, bearing = 30.0)
        assertEquals("je reste sur ma position", annecy, placed[0])
        assertEquals("un ami isolé ne bouge pas", positions[3], placed[3])
        // À ce zoom et à cette latitude, 1 dp vaut environ 6,6 m : les trois marqueurs ne se recouvrent plus
        // (au moins 102 dp entre deux avatars, 79 dp entre un avatar et une story).
        assertTrue(placed[0].metersTo(placed[1]) > 102 * 6.6 * 0.9)
        assertTrue(placed[1].metersTo(placed[2]) > 79 * 6.6 * 0.9)
        assertEquals("même calcul, même résultat", placed, mapMarkerLayout(positions, sizes, zoom = 13.0, bearing = 30.0))
        val zoomedIn = mapMarkerLayout(positions, sizes, zoom = 16.0)
        assertTrue("de près, l'écart en mètres se resserre", zoomedIn[0].metersTo(zoomedIn[1]) < placed[0].metersTo(placed[1]))
    }

    @Test
    fun `les marqueurs isoles ne quittent pas leur point et une carte vide reste vide`() {
        assertTrue(mapMarkerOffsets(emptyList()).isEmpty())
        val markers = listOf(MapMarker(ScreenOffset(0.0, 0.0), 96.0, 96.0), MapMarker(ScreenOffset(500.0, 0.0), 50.0, 50.0))
        assertEquals(listOf(ScreenOffset(0.0, 0.0), ScreenOffset(0.0, 0.0)), mapMarkerOffsets(markers))
    }

    @Test
    fun `TalkBack, distances et resume de la carte lisibles`() {
        assertEquals("tout près de toi", distanceLabel(20.0))
        assertEquals("à 300 m", distanceLabel(310.0))
        assertEquals("à 2,4 km", distanceLabel(2_420.0))
        assertEquals("à 35 km", distanceLabel(35_200.0))
        assertEquals("Carte. 3 amis visibles, 1 story. Glisse ton doigt sur l'écran pour trouver tes amis, ou utilise le bouton Me recentrer.",
            mapSummary(3, 1, meKnown = true))
        assertTrue(mapSummary(0, 0, meKnown = false).startsWith("Carte. Aucun ami visible, ta position n'est pas connue."))
    }

    @Test
    fun `premier cadrage, moi et mes amis proches, sans les amis d'une autre ville (BUG-026)`() {
        val annecy = GeoPoint(45.9077, 6.1020)
        val lac = GeoPoint(45.9005, 6.1305) // ~2,3 km
        val focus = focusAround(annecy, listOf(lac, paris))
        assertTrue("centre entre moi et le lac", focus.center.longitude > annecy.longitude && focus.center.longitude < lac.longitude)
        assertTrue("zoom ville : ${focus.zoom}", focus.zoom in 11.0..13.0)
        assertEquals("seul : zoom ville sur moi", 13.0, focusAround(annecy, listOf(paris)).zoom, 0.0)
    }
}

class LocationReporterTest {
    private val clock = Clock()

    @Test
    fun `en mode fantome, ma position s'affiche pour moi mais n'est jamais envoyee`() = runTest {
        val map = FakeMap(LocationSharing.Ghost)
        val reporter = LocationReporter(map, FixedLocation(), clock::now)
        assertEquals(paris, reporter.report("me"))
        assertTrue(map.sent.isEmpty())
    }

    @Test
    fun `partage actif, envoi au plus une fois par minute, sauf envoi force`() = runTest {
        val map = FakeMap(LocationSharing(LocationSharingMode.ALL_FRIENDS))
        val reporter = LocationReporter(map, FixedLocation(), clock::now)
        reporter.report("me")
        clock.advance(0)
        reporter.report("me")
        assertEquals(1, map.sent.size)
        clock.advance(1)
        reporter.report("me")
        reporter.report("me", force = true)
        assertEquals(3, map.sent.size)
        assertEquals(1, map.sharingReads) // réglage mémorisé
    }

    @Test
    fun `sans permission, rien n'est lu ni envoye`() = runTest {
        val map = FakeMap(LocationSharing(LocationSharingMode.ALL_FRIENDS))
        val reporter = LocationReporter(map, FixedLocation(granted = false), clock::now)
        assertNull(reporter.report("me"))
        assertNull(reporter.positionForStory())
        assertTrue(map.sent.isEmpty())
    }

    @Test
    fun `changer de compte relit le reglage du nouveau compte`() = runTest {
        val map = FakeMap(LocationSharing(LocationSharingMode.ALL_FRIENDS))
        val reporter = LocationReporter(map, FixedLocation(), clock::now)
        reporter.report("nathan")
        map.current = LocationSharing.Ghost // rober, sur le même téléphone, est en mode fantôme
        reporter.report("rober", force = true)
        assertEquals(1, map.sent.size)
        assertEquals(LocationSharing.Ghost, reporter.sharing.value)
    }

    @Test
    fun `quitter le mode fantome envoie ma position tout de suite`() = runTest {
        val map = FakeMap(LocationSharing.Ghost)
        val reporter = LocationReporter(map, FixedLocation(), clock::now)
        reporter.report("me")
        reporter.updateSharing("me", LocationSharing(LocationSharingMode.ALL_FRIENDS))
        assertEquals(listOf(paris), map.sent)
    }

    @Test
    fun `une story ne porte ma position que hors mode fantome`() = runTest {
        val map = FakeMap(LocationSharing.Ghost)
        val reporter = LocationReporter(map, FixedLocation(), clock::now)
        assertNull(reporter.positionForStory())
        map.current = LocationSharing(LocationSharingMode.ONLY_FRIENDS, setOf("rober"))
        assertEquals(paris, reporter.positionForStory())
    }
}

class StoryLocationTest {
    private class Uploads : MediaUploader {
        override suspend fun upload(file: File, contentType: String, purpose: String) = "up-$purpose"
    }

    private class Stories : SnapService {
        val locations = mutableListOf<GeoPoint?>()
        override suspend fun sendSnap(friendId: String, uploadId: String, messageContentType: String) = Unit
        override suspend fun publishStory(uploadId: String, storyContentType: String, thumbnailUploadId: String?, location: GeoPoint?) {
            locations += location
        }
    }

    @Test
    fun `la story part avec la position, et part quand meme si elle est illisible`() = runTest {
        val file = File.createTempFile("story", ".jpg").apply { deleteOnExit() }
        val stories = Stories()
        MediaPublisher(Uploads(), stories, storyLocation = { paris }).publishTo(CapturedMedia(file, MediaKind.PHOTO), SendTarget.ToStory)
        MediaPublisher(Uploads(), stories, storyLocation = { error("GPS") }).publishTo(CapturedMedia(file, MediaKind.PHOTO), SendTarget.ToStory)
        assertEquals(listOf(paris, null), stories.locations)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(map: FakeMap, location: FixedLocation = FixedLocation(), analytics: RecordingAnalytics = RecordingAnalytics()) =
        MapViewModel("me", map, LocationReporter(map, location, clock::now), location, StaticFriends(emptyList()), analytics, now = clock::now)

    @Test
    fun `analytics de la carte, sans doublon du reglage deja capture par le backend`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        val map = FakeMap(LocationSharing.Ghost).apply { friends = listOf(FriendLocation(friend("rober"), paris, t0)) }
        val vm = viewModel(map, analytics = analytics)
        vm.onPermissionResult(true)
        advanceUntilIdle()
        vm.select(map.friends.single())
        vm.select(null) // fermer la fiche n'est pas un événement
        vm.onRecentered()
        vm.updateSharing(LocationSharing(LocationSharingMode.ALL_FRIENDS))
        advanceUntilIdle()

        assertEquals(
            listOf(AnalyticsEvents.LOCATION_PERMISSION_ANSWERED, AnalyticsEvents.MAP_FRIEND_SELECTED, AnalyticsEvents.MAP_RECENTERED),
            analytics.names,
        )
        assertEquals(mapOf("granted" to true), analytics.propertiesOf(AnalyticsEvents.LOCATION_PERMISSION_ANSWERED))
    }

    @Test
    fun `rechargement, amis, pins, reglage et ma position`() = runTest(dispatcher) {
        val map = FakeMap(LocationSharing(LocationSharingMode.ALL_FRIENDS)).apply {
            friends = listOf(FriendLocation(friend("rober"), paris, t0))
            stories = listOf(MapStory(friend("rober"), paris, item("s", 1)))
        }
        val vm = viewModel(map)
        vm.refresh()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(paris, state.me)
        assertEquals(listOf("rober"), state.friends.map { it.friend.id })
        assertEquals(1, state.pins.size)
        assertEquals(LocationSharingMode.ALL_FRIENDS, state.sharing?.mode)
        assertEquals(listOf(paris), map.sent)
    }

    @Test
    fun `la fiche d'un ami se ferme s'il disparait de la carte`() = runTest(dispatcher) {
        val rober = FriendLocation(friend("rober"), paris, t0)
        val map = FakeMap().apply { friends = listOf(rober) }
        val vm = viewModel(map)
        vm.refresh(); advanceUntilIdle()
        vm.select(rober)
        map.friends = emptyList() // rober est passé en mode fantôme
        vm.refresh(); advanceUntilIdle()
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `reglage optimiste, annule si l'envoi echoue`() = runTest(dispatcher) {
        val map = FakeMap(LocationSharing.Ghost).apply { failUpdate = true; gate = CompletableDeferred() }
        val vm = viewModel(map)
        vm.refresh(); advanceUntilIdle()
        vm.updateSharing(LocationSharing(LocationSharingMode.ALL_FRIENDS))
        advanceUntilIdle()
        assertEquals(LocationSharingMode.ALL_FRIENDS, vm.state.value.sharing?.mode) // affiché tout de suite
        map.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(LocationSharingMode.GHOST, vm.state.value.sharing?.mode)
        assertTrue(vm.state.value.error != null)
        assertTrue(map.sent.isEmpty())
    }

    @Test
    fun `seulement ces amis sans personne coche n'est pas envoye`() = runTest(dispatcher) {
        val map = FakeMap(LocationSharing.Ghost)
        val vm = viewModel(map)
        vm.updateSharing(LocationSharing(LocationSharingMode.ONLY_FRIENDS))
        advanceUntilIdle()
        assertEquals(LocationSharing.Ghost, map.current)
    }
}

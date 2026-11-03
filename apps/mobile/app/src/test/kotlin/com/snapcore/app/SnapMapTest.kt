package com.snapcore.app

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
import com.snapcore.app.domain.pinSlots
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
    fun `un pin s'ecarte de l'avatar de son auteur et des autres pins au meme endroit`() {
        val annecy = GeoPoint(45.908, 6.102)
        val nathan = FriendLocation(friend("nathan"), GeoPoint(45.9083, 6.1024), t0) // ~40 m du pin arrondi
        val pins = listOf(
            StoryPin(friend("nathan"), annecy, listOf(item("a", 1))),
            StoryPin(friend("rober"), annecy, listOf(item("b", 2))),
            StoryPin(friend("lea"), paris, listOf(item("c", 3))),
        )
        assertEquals(listOf(1, 2, 0), pinSlots(pins, listOf(nathan)))
        assertEquals(listOf(0, 1, 0), pinSlots(pins, emptyList()))
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

    private fun viewModel(map: FakeMap, location: FixedLocation = FixedLocation()) =
        MapViewModel("me", map, LocationReporter(map, location, clock::now), location, StaticFriends(emptyList()), now = clock::now)

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

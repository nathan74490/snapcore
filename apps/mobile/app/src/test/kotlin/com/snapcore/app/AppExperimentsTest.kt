package com.snapcore.app

import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.CameraFilter
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.ContactsReader
import com.snapcore.app.domain.Experiments
import com.snapcore.app.domain.FilterRenderer
import com.snapcore.app.domain.FriendLocation
import com.snapcore.app.domain.GeoPoint
import com.snapcore.app.domain.LocationProvider
import com.snapcore.app.domain.LocationReporter
import com.snapcore.app.domain.LocationSharing
import com.snapcore.app.domain.MapGhostBanner
import com.snapcore.app.domain.MapService
import com.snapcore.app.domain.MapStory
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.OnboardingContactsCopy
import com.snapcore.app.domain.PreviewSendLabel
import com.snapcore.app.domain.ProfilePlusCard
import com.snapcore.app.domain.experimentProperty
import com.snapcore.app.ui.camera.PreviewViewModel
import com.snapcore.app.ui.map.MapViewModel
import com.snapcore.app.ui.onboarding.OnboardingStep
import com.snapcore.app.ui.onboarding.OnboardingViewModel
import com.snapcore.app.ui.profile.ProfileViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Variantes tirées d'avance ; garde la trace des expositions (une lecture = une exposition). */
private class TrackedExperiments(private val variants: Map<String, String> = emptyMap()) : Experiments {
    val exposures = mutableListOf<String>()
    override fun variant(flag: String): String? {
        exposures += flag
        return variants[flag]
    }
}

private class SameRenderer : FilterRenderer {
    override suspend fun render(media: CapturedMedia, filter: CameraFilter) = media
}

private class GhostMap : MapService {
    override suspend fun friendLocations() = emptyList<FriendLocation>()
    override suspend fun stories() = emptyList<MapStory>()
    override suspend fun sharing() = LocationSharing.Ghost
    override suspend fun updateSharing(sharing: LocationSharing) = sharing
    override suspend fun updateLocation(position: GeoPoint) = Unit
}

private object Granted : LocationProvider {
    override fun hasPermission() = true
    override suspend fun current() = GeoPoint(45.9, 6.1)
}

// Expériences n° 2 à 5 (ADR-030) : exposition au moment où la variante s'affiche, une seule fois, témoin
// par défaut, et variante vue posée sur la conversion envoyée par l'app.
@OptIn(ExperimentalCoroutinesApi::class)
class AppExperimentsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var file: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        file = File.createTempFile("snap", ".jpg").apply { deleteOnExit() }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `variante inconnue ou absente, le temoin partout`() {
        assertEquals(OnboardingContactsCopy.CONTROL, OnboardingContactsCopy.of(null))
        assertEquals(PreviewSendLabel.CONTROL, PreviewSendLabel.of("inconnue"))
        assertEquals(MapGhostBanner.CONTROL, MapGhostBanner.of(null))
        assertEquals(ProfilePlusCard.CONTROL, ProfilePlusCard.of("test"))
    }

    @Test
    fun `apercu, libelle teste des l'ouverture et variante sur l'envoi et l'abandon`() = runTest(dispatcher) {
        val experiments = TrackedExperiments(mapOf(PreviewSendLabel.FLAG to "short"))
        val analytics = RecordingAnalytics()
        val vm = PreviewViewModel(CapturedMedia(file, MediaKind.PHOTO), SameRenderer(), analytics, experiments)

        assertEquals("Envoyer", vm.state.value.sendLabel.label)
        assertEquals(listOf(PreviewSendLabel.FLAG), experiments.exposures)

        vm.confirm()
        advanceUntilIdle()
        vm.onDiscarded()
        val variant = experimentProperty(PreviewSendLabel.FLAG) to "short"
        assertEquals(mapOf("media" to "photo", variant), analytics.propertiesOf(AnalyticsEvents.SNAP_PREVIEW_CONFIRMED))
        assertEquals(mapOf("media" to "photo", variant), analytics.propertiesOf(AnalyticsEvents.SNAP_DISCARDED))
        assertEquals("une seule exposition", 1, experiments.exposures.size)
    }

    @Test
    fun `premiers pas, exposition seulement a l'ecran des contacts, variante sur la reponse`() = runTest(dispatcher) {
        val experiments = TrackedExperiments(mapOf(OnboardingContactsCopy.FLAG to "social"))
        val analytics = RecordingAnalytics()
        val contacts = object : ContactsReader { override suspend fun read() = emptyList<com.snapcore.app.domain.PhoneContact>() }
        // Les premiers pas s'ouvrent sur l'écran testé (le numéro est vérifié à l'inscription, ADR-034).
        val vm = OnboardingViewModel(FakeAccounts(), StaticFriends(emptyList()), contacts, analytics, experiments)
        advanceUntilIdle()
        assertEquals(OnboardingStep.FIND_FRIENDS, vm.state.value.step)
        assertEquals(OnboardingContactsCopy.SOCIAL, vm.state.value.contactsCopy)
        assertEquals(listOf(OnboardingContactsCopy.FLAG), experiments.exposures)

        vm.onContactsPermission(true)
        advanceUntilIdle()
        assertEquals(mapOf("granted" to true, experimentProperty(OnboardingContactsCopy.FLAG) to "social"),
            analytics.propertiesOf(AnalyticsEvents.CONTACTS_PERMISSION_ANSWERED))
    }

    @Test
    fun `carte, exposition au bandeau du mode fantome, une fois par ouverture`() = runTest(dispatcher) {
        val experiments = TrackedExperiments(mapOf(MapGhostBanner.FLAG to "friends"))
        val map = GhostMap()
        val vm = MapViewModel("me", map, LocationReporter(map, Granted), Granted, StaticFriends(emptyList()), experiments = experiments)
        vm.refresh()
        advanceUntilIdle()
        assertTrue("bandeau pas encore affiché", experiments.exposures.isEmpty())
        assertEquals(MapGhostBanner.CONTROL, vm.state.value.ghostBanner)

        vm.onGhostBannerShown()
        vm.onGhostBannerShown()
        assertEquals("Me montrer à mes amis", vm.state.value.ghostBanner.action)
        assertEquals(listOf(MapGhostBanner.FLAG), experiments.exposures)
    }

    @Test
    fun `profil, exposition a la carte SnapCore+, variante seulement sur l'ouverture depuis la carte`() = runTest(dispatcher) {
        val experiments = TrackedExperiments(mapOf(ProfilePlusCard.FLAG to "price"))
        val analytics = RecordingAnalytics()
        val vm = ProfileViewModel(FakeAuth().apply { signedIn = true }, FakeAccounts(), FakePending(), FakeDevices(), RecordingUploader(),
            FakePhotoPreparer(), analytics = analytics, experiments = experiments)
        assertTrue(experiments.exposures.isEmpty())

        vm.onPlusCardShown()
        vm.onPlusCardShown()
        assertEquals(ProfilePlusCard.PRICE, vm.state.value.plusCard)
        assertEquals(listOf(ProfilePlusCard.FLAG), experiments.exposures)

        vm.onPlusOpened("settings")
        assertEquals(mapOf("source" to "settings"), analytics.propertiesOf(AnalyticsEvents.PAYWALL_OPENED))
        vm.onPlusOpened("profile_card")
        assertEquals(mapOf("source" to "profile_card", experimentProperty(ProfilePlusCard.FLAG) to "price"),
            analytics.propertiesOf(AnalyticsEvents.PAYWALL_OPENED))
        assertFalse(analytics.names.contains(AnalyticsEvents.CHECKOUT_STARTED))
    }
}

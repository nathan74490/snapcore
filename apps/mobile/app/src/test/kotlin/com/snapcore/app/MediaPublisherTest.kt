package com.snapcore.app

import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.MediaPublisher
import com.snapcore.app.domain.MediaUploader
import com.snapcore.app.domain.OutgoingStatus
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.SnapOutbox
import com.snapcore.app.domain.SnapSender
import com.snapcore.app.domain.SnapService
import com.snapcore.app.domain.StoryThumbnailMaker
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.ui.camera.SendToViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import java.nio.file.Files

class RecordingUploader : MediaUploader {
    val uploads = mutableListOf<String>() // purposes
    override suspend fun upload(file: File, contentType: String, purpose: String): String {
        uploads += purpose
        return "upload-${uploads.size}"
    }
}

class RecordingSnaps : SnapService {
    val snaps = mutableListOf<Pair<String, String>>() // friendId → contentType
    val stories = mutableListOf<String>()
    val thumbnails = mutableListOf<String?>() // thumbnailUploadId de chaque story
    val failingFriends = mutableSetOf<String>()

    override suspend fun sendSnap(friendId: String, uploadId: String, messageContentType: String) {
        if (friendId in failingFriends) throw IllegalStateException("réseau coupé")
        snaps += friendId to messageContentType
    }

    override suspend fun publishStory(uploadId: String, storyContentType: String, thumbnailUploadId: String?, location: com.snapcore.app.domain.GeoPoint?) {
        stories += storyContentType
        thumbnails += thumbnailUploadId
    }
}

class StaticFriends(private val list: List<Friend>) : FriendsService {
    override suspend fun friends() = list
    override suspend fun incomingRequests() = emptyList<FriendRequest>()
    override suspend fun outgoingRequests() = emptyList<FriendRequest>()
    override suspend fun search(username: String): UserSearchResult = error("non utilisé")
    override suspend fun sendRequest(username: String) = Unit
    override suspend fun accept(friendshipId: String) = Unit
    override suspend fun decline(friendshipId: String) = Unit
    override suspend fun matchContacts(phoneNumbers: List<String>) = emptyList<com.snapcore.app.domain.ContactMatch>()
}

class RecordingSender : SnapSender {
    val sends = mutableListOf<Set<SendTarget>>()
    override fun send(media: CapturedMedia, targets: Set<SendTarget>) {
        sends += targets
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPublisherTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = Friend("a", "alice", null)
    private val bob = Friend("b", "bob", null)
    private lateinit var uploader: RecordingUploader
    private lateinit var snaps: RecordingSnaps
    private lateinit var file: File
    private lateinit var outboxDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        uploader = RecordingUploader()
        snaps = RecordingSnaps()
        file = File.createTempFile("snap", ".mp4").apply { writeText("vidéo") }
        outboxDir = Files.createTempDirectory("outbox").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        file.delete()
        outboxDir.deleteRecursively()
    }

    private fun TestScope.outbox() = SnapOutbox(MediaPublisher(uploader, snaps), backgroundScope, outboxDir)

    @Test
    fun `une vidéo part en SNAP_VIDEO vers chaque ami et en STORY_VIDEO vers la story, un upload par cible`() = runTest(dispatcher) {
        val outbox = outbox()
        outbox.send(CapturedMedia(file, MediaKind.VIDEO), setOf(SendTarget.ToFriend(alice), SendTarget.ToFriend(bob), SendTarget.ToStory))
        runCurrent()

        assertEquals(listOf("SNAP_VIDEO", "SNAP_VIDEO", "STORY_VIDEO"), uploader.uploads)
        assertEquals(listOf("a" to "SNAP_PHOTO_VIDEO", "b" to "SNAP_PHOTO_VIDEO"), snaps.snaps)
        assertEquals(listOf("VIDEO"), snaps.stories)
        assertTrue(outbox.items.value.all { it.status == OutgoingStatus.SENT })
        assertFalse("fichier repris par la file d'envoi", file.exists())
        assertTrue("fichier local supprimé après confirmation (ADR-011)", outboxDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `l'écran d'envoi confie le snap à la file et se ferme sans attendre`() = runTest(dispatcher) {
        val sender = RecordingSender()
        val vm = SendToViewModel(CapturedMedia(file, MediaKind.PHOTO), StaticFriends(listOf(alice, bob)), sender)
        advanceUntilIdle()
        vm.toggle(SendTarget.ToFriend(alice))
        vm.toggle(SendTarget.ToStory)

        vm.send()
        vm.send() // double appui : un seul envoi

        assertEquals(listOf(setOf(SendTarget.ToFriend(alice), SendTarget.ToStory)), sender.sends)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `échec partiel, le fichier est conservé et le réessai ne renvoie qu'au destinataire en échec`() = runTest(dispatcher) {
        snaps.failingFriends += "b"
        val outbox = outbox()
        outbox.send(CapturedMedia(file, MediaKind.PHOTO), setOf(SendTarget.ToFriend(alice), SendTarget.ToFriend(bob)))
        runCurrent()

        val failed = outbox.items.value.single { it.status == OutgoingStatus.FAILED }
        assertEquals(SendTarget.ToFriend(bob), failed.target)
        assertEquals("réseau coupé", failed.error)
        assertTrue("conservé pour le réessai", failed.media.file.exists())

        snaps.failingFriends.clear()
        outbox.retry(failed.id)
        runCurrent()

        assertEquals(listOf("a", "b"), snaps.snaps.map { it.first }) // alice n'a reçu qu'un seul snap
        assertTrue(outbox.items.value.all { it.status == OutgoingStatus.SENT })
        assertFalse(failed.media.file.exists())
    }

    @Test
    fun `un envoi réussi reste affiché un moment, puis quitte la file`() = runTest(dispatcher) {
        val outbox = outbox()
        outbox.send(CapturedMedia(file, MediaKind.PHOTO), setOf(SendTarget.ToFriend(alice)))
        runCurrent()
        assertEquals(OutgoingStatus.SENT, outbox.items.value.single().status)

        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `abandonner un envoi en échec supprime le fichier`() = runTest(dispatcher) {
        snaps.failingFriends += "a"
        val outbox = outbox()
        outbox.send(CapturedMedia(file, MediaKind.PHOTO), setOf(SendTarget.ToFriend(alice)))
        runCurrent()
        val failed = outbox.items.value.single()

        outbox.discard(failed.id)

        assertTrue(outbox.items.value.isEmpty())
        assertFalse(failed.media.file.exists())
    }

    @Test
    fun `une story part avec sa vignette, envoyée en STORY_THUMBNAIL puis supprimée`() = runTest(dispatcher) {
        val thumbnail = File.createTempFile("thumb", ".jpg").apply { writeText("jpeg") }
        val maker = object : StoryThumbnailMaker {
            override suspend fun make(media: CapturedMedia) = thumbnail
        }
        MediaPublisher(uploader, snaps, maker).publishTo(CapturedMedia(file, MediaKind.PHOTO), SendTarget.ToStory)

        assertEquals(listOf("STORY_PHOTO", "STORY_THUMBNAIL"), uploader.uploads)
        assertEquals(listOf<String?>("upload-2"), snaps.thumbnails)
        assertFalse("vignette temporaire supprimée", thumbnail.exists())
    }

    @Test
    fun `sans vignette possible, la story part quand même`() = runTest(dispatcher) {
        val maker = object : StoryThumbnailMaker {
            override suspend fun make(media: CapturedMedia): File = error("vidéo illisible")
        }
        MediaPublisher(uploader, snaps, maker).publishTo(CapturedMedia(file, MediaKind.VIDEO), SendTarget.ToStory)

        assertEquals(listOf("VIDEO"), snaps.stories)
        assertEquals(listOf<String?>(null), snaps.thumbnails)
    }
}

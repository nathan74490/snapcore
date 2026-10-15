package com.snapcore.app

import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.MediaPublisher
import com.snapcore.app.domain.MediaUploader
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.SnapService
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.ui.camera.SendToViewModel
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
    val failingFriends = mutableSetOf<String>()

    override suspend fun sendSnap(friendId: String, uploadId: String, messageContentType: String) {
        if (friendId in failingFriends) throw IllegalStateException("réseau coupé")
        snaps += friendId to messageContentType
    }

    override suspend fun publishStory(uploadId: String, storyContentType: String) {
        stories += storyContentType
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPublisherTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = Friend("a", "alice", null)
    private val bob = Friend("b", "bob", null)
    private lateinit var uploader: RecordingUploader
    private lateinit var snaps: RecordingSnaps
    private lateinit var file: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        uploader = RecordingUploader()
        snaps = RecordingSnaps()
        file = File.createTempFile("snap", ".mp4").apply { writeText("vidéo") }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        file.delete()
    }

    @Test
    fun `une vidéo part en SNAP_VIDEO vers chaque ami et en STORY_VIDEO vers la story, un upload par cible`() = runTest(dispatcher) {
        val report = MediaPublisher(uploader, snaps).publish(
            CapturedMedia(file, MediaKind.VIDEO),
            setOf(SendTarget.ToFriend(alice), SendTarget.ToFriend(bob), SendTarget.ToStory),
        )

        assertTrue(report.allSucceeded)
        assertEquals(listOf("SNAP_VIDEO", "SNAP_VIDEO", "STORY_VIDEO"), uploader.uploads)
        assertEquals(listOf("a" to "SNAP_PHOTO_VIDEO", "b" to "SNAP_PHOTO_VIDEO"), snaps.snaps)
        assertEquals(listOf("VIDEO"), snaps.stories)
        assertFalse("fichier local supprimé après confirmation (ADR-011)", file.exists())
    }

    @Test
    fun `échec partiel, le fichier est conservé et le réessai ne renvoie qu'aux destinataires en échec`() = runTest(dispatcher) {
        snaps.failingFriends += "b"
        val vm = SendToViewModel(CapturedMedia(file, MediaKind.PHOTO), StaticFriends(listOf(alice, bob)), MediaPublisher(uploader, snaps))
        advanceUntilIdle()
        vm.toggle(SendTarget.ToFriend(alice))
        vm.toggle(SendTarget.ToFriend(bob))

        vm.send()
        advanceUntilIdle()

        assertTrue(file.exists())
        assertEquals(setOf(SendTarget.ToFriend(bob)), vm.state.value.selected)
        assertFalse(vm.state.value.done)

        snaps.failingFriends.clear()
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), snaps.snaps.map { it.first }) // alice n'a reçu qu'un seul snap
        assertTrue(vm.state.value.done)
        assertFalse(file.exists())
    }
}

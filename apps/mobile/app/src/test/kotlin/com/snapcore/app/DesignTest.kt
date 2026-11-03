package com.snapcore.app

import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.ui.camera.SendToViewModel
import com.snapcore.app.ui.chat.dayLabel
import com.snapcore.app.ui.home.FriendRequestsViewModel
import com.snapcore.app.ui.stories.myStorySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

private class RequestsFriends(var incoming: Int) : FriendsService {
    override suspend fun friends() = emptyList<Friend>()
    override suspend fun incomingRequests() = List(incoming) { FriendRequest("f$it", Friend("u$it", "user$it", null)) }
    override suspend fun outgoingRequests() = emptyList<FriendRequest>()
    override suspend fun search(username: String): UserSearchResult = error("non utilisé")
    override suspend fun sendRequest(username: String) = Unit
    override suspend fun accept(friendshipId: String) = Unit
    override suspend fun decline(friendshipId: String) = Unit
    override suspend fun matchContacts(phoneNumbers: List<String>) = emptyList<com.snapcore.app.domain.ContactMatch>()
}

// Retouches du point design comparé à Snapchat.
@OptIn(ExperimentalCoroutinesApi::class)
class DesignTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `séparateur de jour d'une conversation`() {
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("AUJOURD'HUI", dayLabel(today, today))
        assertEquals("HIER", dayLabel(today.minusDays(1), today))
        assertEquals("25 SEPTEMBRE", dayLabel(LocalDate.of(2026, 9, 25), today))
        assertEquals("3 MARS 2025", dayLabel(LocalDate.of(2025, 3, 3), today))
    }

    @Test
    fun `résumé de ma story accordé en nombre`() {
        assertEquals("1 snap · 0 vue", myStorySummary(1, 0))
        assertEquals("1 snap · 1 vue", myStorySummary(1, 1))
        assertEquals("3 snaps · 12 vues", myStorySummary(3, 12))
    }

    @Test
    fun `le snap ouvert depuis un chat coche l'ami d'office`() = runTest(dispatcher) {
        val alice = Friend("a", "alice", null)
        val bob = Friend("b", "bob", null)
        val file = File.createTempFile("snap", ".jpg")
        try {
            val vm = SendToViewModel(CapturedMedia(file, MediaKind.PHOTO), StaticFriends(listOf(alice, bob)),
                RecordingSender(), recipientId = "b")
            advanceUntilIdle()
            assertEquals(setOf(SendTarget.ToFriend(bob)), vm.state.value.selected)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `le badge suit les demandes d'ami reçues en temps réel`() = runTest(dispatcher) {
        val friends = RequestsFriends(incoming = 2)
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val vm = FriendRequestsViewModel(friends, events)
        advanceUntilIdle()
        assertEquals(2, vm.count.value)

        friends.incoming = 3
        events.emit(RealtimeEvent.Message("friend_request.received", JsonObject(emptyMap())))
        advanceUntilIdle()
        assertEquals(3, vm.count.value)
    }
}

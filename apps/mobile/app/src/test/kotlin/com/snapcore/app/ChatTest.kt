package com.snapcore.app

import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.ChatMessage
import com.snapcore.app.domain.ChatService
import com.snapcore.app.domain.Conversation
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.MessageStatus
import com.snapcore.app.domain.SnapOpenResult
import com.snapcore.app.domain.StatusColor
import com.snapcore.app.domain.StatusShape
import com.snapcore.app.domain.statusOf
import com.snapcore.app.ui.chat.ConversationViewModel
import com.snapcore.app.ui.chat.SnapViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.time.Instant

private const val ME = "me"
private const val FRIEND = "friend"

private fun message(
    type: ChatContentType,
    from: String,
    opened: Boolean = false,
    purged: Boolean = false,
    id: String = "m-${type.name}-$from-$opened-$purged",
) = ChatMessage(
    id = id,
    senderId = from,
    recipientId = if (from == ME) FRIEND else ME,
    type = type,
    content = if (type == ChatContentType.TEXT) "salut" else null,
    createdAt = Instant.parse("2026-09-25T10:00:00Z"),
    openedAt = if (opened) Instant.parse("2026-09-25T10:05:00Z") else null,
    mediaPurged = purged,
)

class StatusIconTest {
    private fun status(type: ChatContentType, from: String, opened: Boolean = false, purged: Boolean = false): MessageStatus =
        statusOf(message(type, from, opened, purged), ME)

    @Test
    fun `carre rouge plein, nouveau Snap photo recu non ouvert`() {
        assertEquals(MessageStatus(StatusShape.SQUARE, StatusColor.RED, true, "Nouveau snap"), status(ChatContentType.SNAP_PHOTO, FRIEND))
    }

    @Test
    fun `carre rouge contour, Snap photo envoye et ouvert par le destinataire`() {
        assertEquals(MessageStatus(StatusShape.SQUARE, StatusColor.RED, false, "Ouvert"), status(ChatContentType.SNAP_PHOTO, ME, opened = true))
    }

    @Test
    fun `carre violet plein, nouveau Snap video recu`() {
        val s = status(ChatContentType.SNAP_PHOTO_VIDEO, FRIEND)
        assertEquals(StatusColor.PURPLE, s.color)
        assertTrue(s.filled)
    }

    @Test
    fun `fleche bleue pleine, nouveau Chat recu non lu, puis contour une fois envoye et lu`() {
        assertEquals(MessageStatus(StatusShape.ARROW, StatusColor.BLUE, true, "Nouveau chat"), status(ChatContentType.TEXT, FRIEND))
        assertEquals(MessageStatus(StatusShape.ARROW, StatusColor.BLUE, false, "Ouvert"), status(ChatContentType.TEXT, ME, opened = true))
    }

    @Test
    fun `gris, Snap ouvert puis expire, mais jamais pour un Chat texte`() {
        assertEquals(StatusColor.GREY, status(ChatContentType.SNAP_PHOTO, FRIEND, opened = true, purged = true).color)
        assertEquals(StatusColor.BLUE, status(ChatContentType.TEXT, FRIEND, opened = true, purged = true).color)
    }
}

class FakeChat : ChatService {
    var messages = mutableListOf<ChatMessage>()
    var markReadCalls = 0
    var openResult: SnapOpenResult = SnapOpenResult.Ready("https://r2.test/snap", ChatContentType.SNAP_PHOTO)
    val opened = mutableListOf<String>()

    override suspend fun conversations() = emptyList<Conversation>()
    override suspend fun messages(friendId: String) = messages.toList()
    override suspend fun sendText(friendId: String, content: String) =
        message(ChatContentType.TEXT, ME, id = "sent-${messages.size}").copy(content = content).also { messages.add(0, it) }
    override suspend fun markRead(friendId: String) {
        markReadCalls++
    }
    override suspend fun openSnap(message: ChatMessage): SnapOpenResult {
        opened += message.id
        return openResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
    private lateinit var chat: FakeChat

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        chat = FakeChat()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ConversationViewModel(Friend(FRIEND, "testeur", null), ME, chat, events)

    @Test
    fun `ouvrir la conversation marque les Chats recus comme lus`() = runTest(dispatcher) {
        chat.messages += message(ChatContentType.TEXT, FRIEND)
        vm()
        advanceUntilIdle()
        assertEquals(1, chat.markReadCalls)
    }

    @Test
    fun `un nouveau message en temps reel recharge la conversation`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        chat.messages += message(ChatContentType.SNAP_PHOTO, FRIEND)

        events.emit(RealtimeEvent.Message("message.received", kotlinx.serialization.json.JsonObject(emptyMap())))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.messages.size)
    }

    @Test
    fun `ouvrir un Snap recu affiche la lecture, jamais pour un Snap envoye`() = runTest(dispatcher) {
        val received = message(ChatContentType.SNAP_PHOTO, FRIEND)
        val sent = message(ChatContentType.SNAP_PHOTO, ME)
        chat.messages += listOf(received, sent)
        val vm = vm()
        advanceUntilIdle()

        vm.open(sent)
        advanceUntilIdle()
        assertTrue(chat.opened.isEmpty())

        vm.open(received)
        advanceUntilIdle()
        assertTrue(vm.state.value.viewer is SnapViewerState.Showing)
    }

    @Test
    fun `Snap deja vu (403), pas de lecture et SnapCore+ propose`() = runTest(dispatcher) {
        chat.openResult = SnapOpenResult.AlreadySeen
        val received = message(ChatContentType.SNAP_PHOTO, FRIEND, opened = true)
        chat.messages += received
        val vm = vm()
        advanceUntilIdle()

        vm.open(received)
        advanceUntilIdle()

        assertNull(vm.state.value.viewer)
        assertTrue(vm.state.value.offerPlus)

        vm.dismissPlusOffer()
        assertFalse(vm.state.value.offerPlus)
    }

    @Test
    fun `envoyer un Chat l'ajoute en tete et vide le champ`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onDraft("  coucou  ")
        vm.send()
        advanceUntilIdle()

        assertEquals("coucou", vm.state.value.messages.first().content)
        assertEquals("", vm.state.value.draft)
    }
}

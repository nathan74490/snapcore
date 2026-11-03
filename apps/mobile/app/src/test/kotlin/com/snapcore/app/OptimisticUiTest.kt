package com.snapcore.app

import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.ChatMessage
import com.snapcore.app.domain.ChatService
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.Conversation
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.OutgoingSnap
import com.snapcore.app.domain.OutgoingStatus
import com.snapcore.app.domain.Relation
import com.snapcore.app.domain.SendTarget
import com.snapcore.app.domain.SnapOpenResult
import com.snapcore.app.domain.StoryItem
import com.snapcore.app.domain.StoryService
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.domain.statusOf
import com.snapcore.app.ui.chat.ChatListViewModel
import com.snapcore.app.ui.chat.ConversationViewModel
import com.snapcore.app.ui.chat.SnapViewerState
import com.snapcore.app.ui.friends.FriendsViewModel
import com.snapcore.app.ui.profile.AvatarChange
import com.snapcore.app.ui.profile.EditableField
import com.snapcore.app.ui.profile.ProfileViewModel
import com.snapcore.app.ui.stories.MyStoryViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private val alice = Friend("alice-id", "alice", null)
private val bob = Friend("bob-id", "bob", null)

/** Réponse du backend retenue par le test : on voit l'écran avant qu'elle arrive. */
private class Gate {
    private var pending = CompletableDeferred<Unit>()
    var error: Exception? = null

    suspend fun pass() {
        pending.await()
        error?.let { throw it }
    }

    fun open() = pending.complete(Unit)
}

private class GatedFriends : FriendsService {
    val gate = Gate()
    var incoming = listOf(FriendRequest("req-alice", alice))
    var outgoing = emptyList<FriendRequest>()
    var friends = listOf(bob)
    val accepted = mutableListOf<String>()

    override suspend fun friends() = friends
    override suspend fun incomingRequests() = incoming
    override suspend fun outgoingRequests() = outgoing
    override suspend fun search(username: String) = UserSearchResult(alice, Relation.NONE)
    override suspend fun sendRequest(username: String) = gate.pass()
    override suspend fun accept(friendshipId: String) {
        gate.pass()
        accepted += friendshipId
        incoming = incoming.filter { it.friendshipId != friendshipId }
        friends = listOf(alice) + friends
    }
    override suspend fun decline(friendshipId: String) = gate.pass()
    override suspend fun matchContacts(phoneNumbers: List<String>) = emptyList<com.snapcore.app.domain.ContactMatch>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticUiTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- Amis ---------------------------------------------------------------------------------

    @Test
    fun `accepter une demande la passe tout de suite dans les amis, puis le backend confirme`() = runTest(dispatcher) {
        val service = GatedFriends()
        val vm = FriendsViewModel(service)
        advanceUntilIdle()

        vm.accept(service.incoming.single())
        runCurrent()
        assertTrue(vm.state.value.incoming.isEmpty())
        assertEquals(listOf(alice, bob), vm.state.value.friends)

        service.gate.open()
        advanceUntilIdle()
        assertEquals(listOf("req-alice"), service.accepted)
        assertEquals(listOf(alice, bob), vm.state.value.friends)
    }

    @Test
    fun `echec de l'acceptation, la demande revient et le motif s'affiche`() = runTest(dispatcher) {
        val service = GatedFriends().apply { gate.error = ApiException(404, "Demande introuvable") }
        val vm = FriendsViewModel(service)
        advanceUntilIdle()

        vm.accept(service.incoming.single())
        runCurrent()
        service.gate.open()
        advanceUntilIdle()

        assertEquals(listOf("req-alice"), vm.state.value.incoming.map { it.friendshipId })
        assertEquals(listOf(bob), vm.state.value.friends)
        assertEquals("Demande introuvable", vm.state.value.message)
    }

    @Test
    fun `un rechargement arrive pendant l'action ne fait pas reapparaitre la demande`() = runTest(dispatcher) {
        val service = GatedFriends()
        val vm = FriendsViewModel(service)
        advanceUntilIdle()

        vm.accept(service.incoming.single())
        vm.refresh() // le backend n'a pas encore traité l'acceptation : il renvoie encore la demande
        runCurrent()

        assertTrue(vm.state.value.incoming.isEmpty())
    }

    @Test
    fun `ajouter un ami affiche la demande envoyee sans attendre`() = runTest(dispatcher) {
        val service = GatedFriends()
        val vm = FriendsViewModel(service)
        vm.onQuery("alice")
        vm.search()
        advanceUntilIdle()

        vm.addResult()
        runCurrent()

        assertEquals(Relation.OUTGOING_REQUEST, vm.state.value.result?.relation)
        assertEquals(listOf(alice), vm.state.value.outgoing.map { it.user })
    }

    // --- Ma story -----------------------------------------------------------------------------

    @Test
    fun `retirer un element de ma story le fait disparaitre, puis revenir a sa place si le backend refuse`() = runTest(dispatcher) {
        val t = Instant.parse("2026-11-03T10:00:00Z")
        val items = listOf("a", "b", "c").mapIndexed { i, id -> StoryItem(id, MediaKind.PHOTO, t.plusSeconds(i * 60L), t.plusSeconds(86_400)) }
        val gate = Gate().apply { error = ApiException(500, "Serveur indisponible") }
        val service = object : StoryService {
            override suspend fun feed() = emptyList<com.snapcore.app.domain.FriendStory>()
            override suspend fun mine() = items
            override suspend fun viewUrl(item: StoryItem) = ""
            override suspend fun viewers(item: StoryItem) = emptyList<Friend>()
            override suspend fun delete(item: StoryItem) = gate.pass()
        }
        val vm = MyStoryViewModel(service)
        advanceUntilIdle()

        vm.delete(items[1])
        runCurrent()
        assertEquals(listOf("a", "c"), vm.state.value.items.map { it.id })

        gate.open()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertEquals("Serveur indisponible", vm.state.value.message)
    }

    // --- Chat ---------------------------------------------------------------------------------

    private fun received(id: String, type: ChatContentType = ChatContentType.TEXT) = ChatMessage(
        id, alice.id, "me", type, if (type == ChatContentType.TEXT) "salut" else null, Instant.parse("2026-11-03T10:00:00Z"), null, false,
    )

    @Test
    fun `ouvrir une conversation la passe en lue dans la liste, meme si le rechargement suivant a du retard`() = runTest(dispatcher) {
        val service = object : ChatService by FakeChat() {
            override suspend fun conversations() = listOf(Conversation(alice, received("m1")))
        }
        var now = 1_000L
        val vm = ChatListViewModel(service, MutableSharedFlow<RealtimeEvent>(), now = { now })
        advanceUntilIdle()

        vm.markSeen(alice.id, "me")
        assertNotNull(vm.state.value.conversations.single().lastMessage?.openedAt)

        vm.refresh() // le backend n'a pas encore enregistré la lecture
        advanceUntilIdle()
        assertNotNull(vm.state.value.conversations.single().lastMessage?.openedAt)

        now += 60_000 // passé ce délai, c'est de nouveau le backend qui fait foi
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.conversations.single().lastMessage?.openedAt)
    }

    @Test
    fun `fermer un snap le marque ouvert tout de suite`() = runTest(dispatcher) {
        val chat = FakeChat().apply {
            messages = mutableListOf(received("snap-1", ChatContentType.SNAP_PHOTO))
            openResult = SnapOpenResult.Ready("https://r2.test/snap", ChatContentType.SNAP_PHOTO)
        }
        val vm = ConversationViewModel(alice, "me", chat, MutableSharedFlow())
        advanceUntilIdle()
        vm.open(chat.messages.single())
        advanceUntilIdle()
        assertTrue(vm.state.value.viewer is SnapViewerState.Showing)

        vm.closeViewer()

        assertNotNull(vm.state.value.messages.single().openedAt)
    }

    // --- Envoi des snaps en arrière-plan -------------------------------------------------------

    private fun outgoing(id: String, status: OutgoingStatus, target: SendTarget = SendTarget.ToFriend(alice)) = OutgoingSnap(
        id, CapturedMedia(java.io.File("snap.jpg"), MediaKind.PHOTO), target, Instant.parse("2026-11-03T11:00:00Z"), status,
    )

    @Test
    fun `un snap en cours d'envoi remonte sa conversation en tete avec Envoi, puis l'echec se relance d'un appui`() = runTest(dispatcher) {
        var serverLast: ChatMessage? = null
        val service = object : ChatService by FakeChat() {
            override suspend fun conversations() = listOf(Conversation(bob, null), Conversation(alice, serverLast))
        }
        val outbox = MutableStateFlow<List<OutgoingSnap>>(emptyList())
        val retried = mutableListOf<String>()
        val vm = ChatListViewModel(service, MutableSharedFlow(), "me", outbox, retrySend = { retried += it })
        advanceUntilIdle()

        outbox.value = listOf(outgoing("o1", OutgoingStatus.SENDING))
        runCurrent()
        val first = vm.state.value.conversations.first()
        assertEquals(alice, first.friend)
        assertEquals("Envoi…", statusOf(first.lastMessage!!, "me").label)

        outbox.value = listOf(outgoing("o1", OutgoingStatus.FAILED))
        runCurrent()
        val failed = vm.state.value.conversations.first().lastMessage!!
        assertTrue(failed.failed)
        vm.retry(failed)
        assertEquals(listOf("o1"), retried)

        // Envoi abouti : la liste recharge et affiche le vrai message du backend.
        serverLast = ChatMessage("m1", "me", alice.id, ChatContentType.SNAP_PHOTO, null, Instant.parse("2026-11-03T11:00:01Z"), null, false)
        outbox.value = listOf(outgoing("o1", OutgoingStatus.SENT))
        advanceUntilIdle()
        assertEquals("m1", vm.state.value.conversations.first { it.friend == alice }.lastMessage?.id)
    }

    @Test
    fun `dans la conversation, le snap en cours d'envoi s'affiche en tete`() = runTest(dispatcher) {
        val chat = FakeChat().apply { messages = mutableListOf(received("m0")) }
        val outbox = MutableStateFlow(listOf(outgoing("o1", OutgoingStatus.SENDING)))
        val vm = ConversationViewModel(alice, "me", chat, MutableSharedFlow(), outbox = outbox)
        advanceUntilIdle()

        assertEquals(listOf("outbox-o1", "m0"), vm.state.value.messages.map { it.id })
        assertTrue(vm.state.value.messages.first().pending)
    }

    // --- Profil -------------------------------------------------------------------------------

    private class GatedAccounts(account: Account) : com.snapcore.app.domain.AccountService by FakeAccounts().apply({ this.account = account }) {
        val gate = Gate()
        var current = account
        override suspend fun me() = current
        override suspend fun updateProfile(username: String?, phoneNumber: String?, firstName: String?, lastName: String?): Account {
            gate.pass()
            return current.copy(firstName = firstName ?: current.firstName).also { current = it }
        }
        override suspend fun setAvatar(options: Map<String, String>): Account {
            gate.pass()
            return current.copy(avatar = options, avatarUrl = "server.png").also { current = it }
        }
    }

    private fun profile(accounts: GatedAccounts, initial: Account? = null) = ProfileViewModel(
        FakeAuth().apply { signedIn = true }, accounts, FakePending(), FakeDevices(), RecordingUploader(), FakePhotoPreparer(),
        initial = initial, avatarUrl = { "local.png" },
    )

    @Test
    fun `le profil s'affiche tout de suite avec le compte deja connu`() = runTest(dispatcher) {
        val me = Account("id", "nathan", null, null, null, false)
        val vm = profile(GatedAccounts(me), initial = me)

        assertEquals(me, vm.state.value.account)
    }

    @Test
    fun `un prenom modifie s'affiche avant la reponse, et un rechargement en cours ne l'efface pas`() = runTest(dispatcher) {
        val me = Account("id", "nathan", null, null, null, false, firstName = "Nathan")
        val accounts = GatedAccounts(me)
        val vm = profile(accounts)
        advanceUntilIdle()

        vm.startEditing(EditableField.FIRST_NAME)
        vm.onDraft("Nat")
        vm.saveEditing()
        vm.refresh()
        runCurrent()
        assertNull(vm.state.value.editing)
        assertEquals("Nat", vm.state.value.account?.firstName)

        accounts.gate.open()
        advanceUntilIdle()
        assertEquals("Nat", vm.state.value.account?.firstName)
    }

    @Test
    fun `l'avatar choisi s'affiche des le retour sur le profil, et revient en arriere si l'enregistrement echoue`() = runTest(dispatcher) {
        val me = Account("id", "nathan", null, null, "https://cdn.test/photo", false)
        val accounts = GatedAccounts(me).apply { gate.error = ApiException(500, "Serveur indisponible") }
        val vm = profile(accounts)
        advanceUntilIdle()

        vm.applyAvatar(AvatarChange.Set(mapOf("top" to "bob")))
        runCurrent()
        assertEquals("local.png", vm.state.value.account?.pictureUrl)

        accounts.gate.open()
        advanceUntilIdle()
        assertEquals("https://cdn.test/photo", vm.state.value.account?.pictureUrl)
        assertEquals("Serveur indisponible", vm.state.value.message)
    }

    @Test
    fun `retirer l'avatar revient tout de suite a la photo`() = runTest(dispatcher) {
        val me = Account("id", "nathan", null, null, "https://cdn.test/photo", false, avatar = mapOf("top" to "bob"), avatarUrl = "a.png")
        val vm = profile(GatedAccounts(me))
        advanceUntilIdle()

        vm.applyAvatar(AvatarChange.Remove)

        assertEquals("https://cdn.test/photo", vm.state.value.account?.pictureUrl)
    }
}

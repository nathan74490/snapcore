package com.snapcore.app

import com.snapcore.app.data.RealtimeEvent
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.Group
import com.snapcore.app.domain.GroupMessage
import com.snapcore.app.domain.GroupService
import com.snapcore.app.domain.GroupSummary
import com.snapcore.app.domain.PushDetails
import com.snapcore.app.domain.SnapOpenResult
import com.snapcore.app.domain.asRecipient
import com.snapcore.app.domain.memberNames
import com.snapcore.app.domain.notificationFor
import com.snapcore.app.domain.seenByLabel
import com.snapcore.app.domain.statusOf
import com.snapcore.app.ui.groups.GroupConversationViewModel
import com.snapcore.app.ui.groups.NewGroupViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private val me = Friend("me", "nathan", null, "Nathan")
private val lea = Friend("lea", "lea.martin", null, "Léa")
private val hugo = Friend("hugo", "hugo_b", null, "Hugo")
private val ines = Friend("ines", "ines.k", null, "Inès")
private val t0: Instant = Instant.parse("2026-11-05T10:00:00Z")

private fun group(name: String? = null, vararg members: Friend = arrayOf(me, lea, hugo)) = Group("g1", name, members.toList(), t0)

private fun message(from: Friend, type: ChatContentType = ChatContentType.TEXT, seenBy: List<String> = emptyList(), openedByMe: Boolean = false, id: String = "m1") =
    GroupMessage(id, "g1", from.id, type, if (type == ChatContentType.TEXT) "salut" else null, t0, mediaPurged = false, openedByMe = openedByMe, seenBy = seenBy)

/** Groupes en mémoire, avec la trace des appels. */
private class FakeGroups(var current: Group = group("Soirée"), var history: List<GroupMessage> = emptyList()) : GroupService {
    val calls = mutableListOf<String>()
    var failCreate = false
    var failLoad = false
    override suspend fun groups() = listOf(GroupSummary(current, history.firstOrNull()))
    override suspend fun group(groupId: String): Group {
        if (failLoad) error("Impossible de joindre le serveur")
        return current
    }
    override suspend fun create(name: String?, memberIds: List<String>): Group {
        calls += "create:$name:${memberIds.joinToString(",")}"
        if (failCreate) error("Serveur injoignable")
        return Group("new", name, listOf(me) + listOf(lea, hugo, ines).filter { it.id in memberIds }, t0)
    }
    override suspend fun rename(groupId: String, name: String?): Group {
        calls += "rename:$name"
        return current.copy(name = name).also { current = it }
    }
    override suspend fun addMembers(groupId: String, userIds: List<String>): Group {
        calls += "add:${userIds.joinToString(",")}"
        return current.copy(members = current.members + listOf(ines).filter { it.id in userIds }).also { current = it }
    }
    override suspend fun leave(groupId: String) { calls += "leave" }
    override suspend fun messages(groupId: String) = history
    override suspend fun sendText(groupId: String, content: String) = message(me).copy(id = "sent", content = content).also { calls += "text:$content" }
    override suspend fun markRead(groupId: String) { calls += "read" }
    override suspend fun sendTyping(groupId: String) { calls += "typing" }
    override suspend fun openSnap(message: GroupMessage) = SnapOpenResult.Ready("https://cdn/snap", message.type)
}

// Groupes de discussion (ADR-032), côté app.
class GroupRulesTest {
    @Test
    fun `nom du groupe, ou a defaut les prenoms des autres membres`() {
        assertEquals("Soirée", group("Soirée").title(me.id))
        assertEquals("Léa et Hugo", group().title(me.id))
        assertEquals("Léa, Hugo, Inès et 2 autres", memberNames(listOf("Léa", "Hugo", "Inès", "Tom", "Chloé")))
        assertEquals("Léa, Hugo, Inès et 1 autre", memberNames(listOf("Léa", "Hugo", "Inès", "Tom")))
    }

    @Test
    fun `statut de la ligne du groupe dans la liste Chat`() {
        val g = group("Soirée")
        assertEquals("Snap de Léa", statusOf(message(lea, ChatContentType.SNAP_PHOTO), g, me.id, unread = true).label)
        assertEquals("Chat de Léa", statusOf(message(lea), g, me.id, unread = true).label)
        assertEquals("Reçu", statusOf(message(lea), g, me.id, unread = false).label)
        assertEquals("Envoyé", statusOf(message(me, seenBy = listOf(lea.id)), g, me.id, unread = false).label)
        // Ouvert seulement quand tous les autres membres ont vu.
        assertEquals("Ouvert", statusOf(message(me, seenBy = listOf(lea.id, hugo.id)), g, me.id, unread = false).label)
    }

    @Test
    fun `vu par, avec les prenoms ou tout le monde`() {
        val g = group(null, me, lea, hugo, ines)
        assertNull(seenByLabel(message(me), g, me.id))
        assertEquals("Vu par Léa et Hugo", seenByLabel(message(me, seenBy = listOf(lea.id, hugo.id)), g, me.id))
        assertEquals("Vu par tout le monde", seenByLabel(message(me, seenBy = listOf(lea.id, hugo.id, ines.id)), g, me.id))
    }

    @Test
    fun `un snap a ouvrir passe avant un chat plus recent, un envoi en echec avant tout`() {
        val snap = message(lea, ChatContentType.SNAP_PHOTO, id = "snap")
        val chat = message(hugo, id = "chat")
        assertEquals(snap, GroupSummary(group(), chat, snap).headline)
        val failed = message(me, ChatContentType.SNAP_PHOTO, id = "outbox-1").copy(failed = true)
        assertEquals(failed, GroupSummary(group(), failed, snap).headline)
    }

    @Test
    fun `notification d'un message de groupe, nom du groupe en titre`() {
        val data = mapOf("type" to "group.message.received", "groupId" to "g1", "messageId" to "m1")
        val snap = notificationFor(data, PushDetails("Léa", isSnap = true, groupName = "Soirée"))!!
        assertEquals("Soirée", snap.title)
        assertEquals("Léa a envoyé un snap", snap.text)
        assertEquals("Nouveau message dans le groupe", notificationFor(data, PushDetails())!!.text)
    }

    @Test
    fun `le groupe comme destinataire de la camera`() {
        val recipient = group("Soirée").asRecipient(me.id)
        assertEquals("group:g1", recipient.id)
        assertEquals("Soirée", recipient.displayName)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `nouveau chat, un ami ouvre la conversation a deux, deux amis creent le groupe`() = runTest(dispatcher) {
        val groups = FakeGroups()
        val vm = NewGroupViewModel(StaticFriends(listOf(hugo, lea, ines)), groups)
        advanceUntilIdle()
        assertEquals(listOf("Hugo", "Inès", "Léa"), vm.state.value.friends.map { it.displayName })

        vm.toggle(lea)
        assertTrue(!vm.state.value.isGroup)
        vm.confirm()
        assertEquals(lea, vm.state.value.openFriend)
        assertTrue(groups.calls.isEmpty())

        val vm2 = NewGroupViewModel(StaticFriends(listOf(hugo, lea)), groups)
        advanceUntilIdle()
        vm2.onName("  ")
        vm2.toggle(lea)
        vm2.toggle(hugo)
        vm2.confirm()
        advanceUntilIdle()
        assertEquals("create:null:lea,hugo", groups.calls.single())
        assertEquals("new", vm2.state.value.created?.id)
    }

    @Test
    fun `recherche dans le champ A, et echec de creation affiche`() = runTest(dispatcher) {
        val groups = FakeGroups().apply { failCreate = true }
        val vm = NewGroupViewModel(StaticFriends(listOf(hugo, lea)), groups)
        advanceUntilIdle()
        vm.onQuery("lé")
        assertEquals(listOf(lea), vm.state.value.visible)
        vm.toggle(lea)
        assertEquals("", vm.state.value.query) // la recherche se vide après un choix
        vm.toggle(hugo)
        vm.confirm()
        advanceUntilIdle()
        assertEquals("Serveur injoignable", vm.state.value.error)
        assertNull(vm.state.value.created)
    }

    @Test
    fun `echec initial persistant, nouvelle tentative et historique conserve apres une autre panne (BUG-029)`() = runTest(dispatcher) {
        val groups = FakeGroups(history = listOf(message(lea))).apply { failLoad = true }
        val vm = GroupConversationViewModel("g1", me.id, groups, MutableSharedFlow())
        advanceUntilIdle()
        assertNull(vm.state.value.group)
        assertTrue(!vm.state.value.loading)
        assertEquals("Impossible de joindre le serveur", vm.state.value.initialLoadError)
        vm.consumeError()
        assertEquals("Impossible de joindre le serveur", vm.state.value.initialLoadError)
        groups.failLoad = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals("Soirée", vm.state.value.group?.name)
        assertNull(vm.state.value.initialLoadError)
        groups.failLoad = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals("Soirée", vm.state.value.group?.name)
        assertEquals(1, vm.state.value.messages.size)
        assertEquals("Impossible de joindre le serveur", vm.state.value.error)
        assertNull(vm.state.value.initialLoadError)
    }

    @Test
    fun `conversation, envoi optimiste, lu, renommer, ajouter, ecriture et quitter`() = runTest(dispatcher) {
        val events = MutableSharedFlow<RealtimeEvent>()
        val groups = FakeGroups(history = listOf(message(lea)))
        val vm = GroupConversationViewModel("g1", me.id, groups, events)
        advanceUntilIdle()
        assertEquals("Soirée", vm.state.value.group?.name)
        assertTrue("chat de Léa non lu : marqué lu", "read" in groups.calls)

        vm.onDraft("on se retrouve où ?")
        vm.send()
        assertTrue(vm.state.value.messages.first().pending)
        advanceUntilIdle()
        assertEquals("sent", vm.state.value.messages.first().id)

        vm.rename("Week-end")
        assertEquals("Week-end", vm.state.value.group?.name) // tout de suite
        vm.addMembers(listOf(ines))
        advanceUntilIdle()
        assertEquals(4, vm.state.value.group?.members?.size)

        events.emit(RealtimeEvent.Message("group.typing", kotlinx.serialization.json.buildJsonObject {
            put("groupId", kotlinx.serialization.json.JsonPrimitive("g1")); put("userId", kotlinx.serialization.json.JsonPrimitive("lea"))
        }))
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf("lea"), vm.state.value.typing)

        vm.leave()
        advanceUntilIdle()
        assertTrue(vm.state.value.closed)
        assertTrue(groups.calls.containsAll(listOf("typing", "text:on se retrouve où ?", "rename:Week-end", "add:ines", "leave")))
    }
}

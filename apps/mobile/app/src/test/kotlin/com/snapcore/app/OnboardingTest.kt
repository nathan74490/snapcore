package com.snapcore.app

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.ContactMatch
import com.snapcore.app.domain.ContactsReader
import com.snapcore.app.domain.Countries
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.PhoneContact
import com.snapcore.app.domain.Relation
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.ui.onboarding.OnboardingStep
import com.snapcore.app.ui.onboarding.OnboardingViewModel
import com.snapcore.app.ui.profile.AvatarChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CountriesTest {
    @Test
    fun `numéro national saisi, enregistré au format international sans le 0`() {
        val france = Countries.byCode("fr")
        assertEquals("33", france.dialCode)
        assertEquals("+33612345678", france.international("06 12 34 56 78"))
        assertEquals("🇫🇷", france.flag)
    }

    @Test
    fun `pays inconnu, la France par défaut, et liste triée par nom`() {
        assertEquals("FR", Countries.byCode("??").code)
        val names = Countries.all().map { it.name }
        assertTrue(names.indexOf("Allemagne") < names.indexOf("France"))
        assertTrue(Countries.all().size > 200)
    }
}

private val leo = Friend("leo-id", "leo", null, "Léo")
private val zoe = Friend("zoe-id", "zoe", null, "Zoé")
private val max = Friend("max-id", "max", null, "Max")

private class ContactFriends : FriendsService {
    val requests = mutableListOf<String>()
    val searched = mutableListOf<List<String>>()
    override suspend fun friends() = emptyList<Friend>()
    override suspend fun incomingRequests() = emptyList<FriendRequest>()
    override suspend fun outgoingRequests() = emptyList<FriendRequest>()
    override suspend fun search(username: String) = UserSearchResult(leo, Relation.NONE)
    override suspend fun sendRequest(username: String) {
        requests += username
    }
    override suspend fun accept(friendshipId: String) = Unit
    override suspend fun decline(friendshipId: String) = Unit
    override suspend fun matchContacts(phoneNumbers: List<String>): List<ContactMatch> {
        searched += phoneNumbers
        return listOf(
            ContactMatch("06 11 11 11 11", leo, Relation.NONE),
            ContactMatch("06 22 22 22 22", zoe, Relation.FRIENDS),
            ContactMatch("06 33 33 33 33", max, Relation.INCOMING_REQUEST),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var accounts: FakeAccounts
    private lateinit var friends: ContactFriends
    private val contacts = object : ContactsReader {
        override suspend fun read() = listOf(
            PhoneContact("Léo", listOf("06 11 11 11 11")),
            PhoneContact("Zoé", listOf("06 22 22 22 22")),
            PhoneContact("Max", listOf("06 33 33 33 33")),
            PhoneContact("Mamie", listOf("04 50 00 00 00")),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accounts = FakeAccounts().apply { account = Account("me", "nathan", null, null, null, false) }
        friends = ContactFriends()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(notify: Boolean = false) = OnboardingViewModel(accounts, friends, contacts) { notify }

    @Test
    fun `contacts autorisés, les amis à ajouter sont cochés d'office et les autres contacts à inviter`() = runTest(dispatcher) {
        val vm = vm()
        vm.onContactsPermission(granted = true)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(OnboardingStep.ADD_FRIENDS, state.step)
        assertEquals(setOf("leo-id", "max-id"), state.selected) // Zoé est déjà une amie
        assertEquals(listOf("Mamie"), state.invitable.map { it.name })

        vm.toggle("max-id")
        vm.addSelected()
        advanceUntilIdle()
        assertEquals(listOf("leo"), friends.requests)
        assertEquals(OnboardingStep.INVITE, vm.state.value.step)

        vm.toggleInvite("Mamie")
        assertEquals(listOf("04 50 00 00 00"), vm.sendInvites())
        assertEquals(OnboardingStep.AVATAR, vm.state.value.step)
    }

    @Test
    fun `contacts refusés, recherche par pseudo seule puis directement l'avatar`() = runTest(dispatcher) {
        val vm = vm()
        vm.onContactsPermission(granted = false)
        advanceUntilIdle()
        assertTrue(friends.searched.isEmpty())

        vm.onQuery("Leo")
        vm.search()
        advanceUntilIdle()
        vm.addSearchResult()
        assertEquals(Relation.OUTGOING_REQUEST, vm.state.value.searchResult?.relation)
        advanceUntilIdle()
        assertEquals(listOf("leo"), friends.requests)

        vm.skipAddFriends()
        assertEquals(OnboardingStep.AVATAR, vm.state.value.step)
    }

    @Test
    fun `avatar créé, enregistré, puis les notifications si Android doit encore les demander`() = runTest(dispatcher) {
        val vm = vm(notify = true)
        vm.onContactsPermission(granted = false)
        vm.skipAddFriends()

        vm.applyAvatar(AvatarChange.Set(mapOf("top" to "bob")))
        advanceUntilIdle()

        assertEquals(mapOf("top" to "bob"), accounts.savedAvatar)
        assertEquals(OnboardingStep.NOTIFICATIONS, vm.state.value.step)
        vm.finish()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
    }

    @Test
    fun `sans notifications à demander, l'avatar passé termine les premiers pas`() {
        val vm = vm(notify = false)
        vm.skipAvatar()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
    }

    @Test
    fun `analytics, etapes affichees, etapes passees, contacts et fin des premiers pas`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        var now = 0L
        val vm = OnboardingViewModel(accounts, friends, contacts, analytics, clock = { now }) { false }
        advanceUntilIdle()
        advanceUntilIdle()
        now = 4_000
        vm.onContactsPermission(false)
        advanceUntilIdle()
        vm.skipAddFriends()
        advanceUntilIdle()
        now = 9_000
        vm.skipAvatar()
        advanceUntilIdle()

        assertEquals(
            listOf(
                AnalyticsEvents.ONBOARDING_STEP_VIEWED to mapOf("step" to "find_friends"),
                AnalyticsEvents.CONTACTS_PERMISSION_ANSWERED to mapOf("granted" to false),
                AnalyticsEvents.ONBOARDING_STEP_COMPLETED to mapOf("step" to "find_friends", "duration_ms" to 4_000L),
                AnalyticsEvents.ONBOARDING_STEP_VIEWED to mapOf("step" to "add_friends"),
                AnalyticsEvents.ONBOARDING_STEP_SKIPPED to mapOf("step" to "add_friends"),
                AnalyticsEvents.ONBOARDING_STEP_VIEWED to mapOf("step" to "avatar"),
                AnalyticsEvents.ONBOARDING_STEP_SKIPPED to mapOf("step" to "avatar"),
                AnalyticsEvents.ONBOARDING_COMPLETED to mapOf(
                    "ended_by" to "done", "duration_ms" to 9_000L, "steps_skipped" to 2, "friends_added" to 0,
                    "invites_sent" to 0, "avatar_created" to false, "contacts_granted" to false,
                ),
            ),
            analytics.events,
        )
    }

    @Test
    fun `analytics, amis ajoutes, notifications acceptees, puis bilan du parcours`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        val vm = OnboardingViewModel(accounts, friends, contacts, analytics) { true }
        advanceUntilIdle()
        vm.onContactsPermission(true)
        advanceUntilIdle()
        val suggested = vm.state.value.addable.size
        val selected = vm.state.value.selected.size
        vm.addSelected()
        advanceUntilIdle()
        if (vm.state.value.step == OnboardingStep.INVITE) vm.skipInvites()
        vm.skipAvatar()
        vm.onNotificationsPermission(true)
        advanceUntilIdle()

        assertEquals(mapOf("count" to selected, "suggested" to suggested), analytics.propertiesOf(AnalyticsEvents.ONBOARDING_FRIENDS_ADDED))
        assertEquals(mapOf("granted" to true, "source" to "onboarding"),
            analytics.propertiesOf(AnalyticsEvents.NOTIFICATIONS_PERMISSION_ANSWERED))
        val summary = analytics.propertiesOf(AnalyticsEvents.ONBOARDING_COMPLETED)
        assertEquals(selected, summary["friends_added"])
        assertEquals(true, summary["contacts_granted"])
        assertEquals(true, summary["notifications_granted"])
        assertEquals("done", summary["ended_by"])
    }

    @Test
    fun `analytics, la touche retour termine les premiers pas et le dit`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        val vm = OnboardingViewModel(accounts, friends, contacts, analytics) { false }
        advanceUntilIdle()
        vm.quit()
        vm.quit()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        assertEquals(1, analytics.names.count { it == AnalyticsEvents.ONBOARDING_COMPLETED })
        assertEquals("back", analytics.propertiesOf(AnalyticsEvents.ONBOARDING_COMPLETED)["ended_by"])
    }
}

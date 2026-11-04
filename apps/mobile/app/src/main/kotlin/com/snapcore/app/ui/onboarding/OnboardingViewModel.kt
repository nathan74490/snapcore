package com.snapcore.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.Experiments
import com.snapcore.app.domain.Exposure
import com.snapcore.app.domain.NoExperiments
import com.snapcore.app.domain.OnboardingContactsCopy
import com.snapcore.app.domain.expose
import com.snapcore.app.domain.NoAnalytics
import com.snapcore.app.domain.ContactMatch
import com.snapcore.app.domain.ContactsReader
import com.snapcore.app.domain.Countries
import com.snapcore.app.domain.Country
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.PhoneContact
import com.snapcore.app.domain.Relation
import com.snapcore.app.domain.UserSearchResult
import com.snapcore.app.ui.completeEvenIfClosed
import com.snapcore.app.ui.profile.AvatarChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Premiers pas après l'inscription, comme Snapchat (ADR-029) : numéro (facultatif), amis retrouvés par
// les contacts, invitations, avatar, puis notifications. Chaque écran se passe.

enum class OnboardingStep { PHONE, FIND_FRIENDS, ADD_FRIENDS, INVITE, AVATAR, NOTIFICATIONS, DONE }

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.PHONE,
    val country: Country,
    /** Texte de l'écran « Trouve tous tes amis » (expérience onboarding-contacts-copy, ADR-030). */
    val contactsCopy: OnboardingContactsCopy = OnboardingContactsCopy.CONTROL,
    val national: String = "",
    val phoneError: String? = null,
    val savingPhone: Boolean = false,
    /** null tant que l'accès aux contacts n'a pas été demandé. */
    val contactsGranted: Boolean? = null,
    val loadingContacts: Boolean = false,
    val contacts: List<PhoneContact> = emptyList(),
    val matches: List<ContactMatch> = emptyList(),
    /** Comptes cochés dans « Ajouter des amis » (tous ceux qu'on peut ajouter, d'office). */
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val searchResult: UserSearchResult? = null,
    val searchError: String? = null,
    /** Contacts cochés pour une invitation, par nom. */
    val invited: Set<String> = emptySet(),
    val avatarPreview: Map<String, String>? = null,
    val message: String? = null,
) {
    /** Contacts sans compte SnapCore : ceux qu'on peut inviter. */
    val invitable: List<PhoneContact>
        get() {
            val found = matches.map { it.phoneNumber }.toSet()
            return contacts.filter { contact -> contact.phoneNumbers.none(found::contains) }
        }

    val addable get() = matches.filter { it.relation == Relation.NONE || it.relation == Relation.INCOMING_REQUEST }
}

class OnboardingViewModel(
    private val accounts: AccountService,
    private val friends: FriendsService,
    private val contactsReader: ContactsReader,
    countryCode: String?,
    private val analytics: Analytics = NoAnalytics,
    private val experiments: Experiments = NoExperiments,
    /** Notifications à demander à la fin (Android 13+, permission pas encore accordée ni demandée). */
    private val needsNotificationPrompt: () -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState(country = Countries.byCode(countryCode)))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** Variante vue de l'expérience onboarding-contacts-copy, lue une fois à l'affichage de l'écran. */
    private var contactsExposure: Exposure<OnboardingContactsCopy>? = null

    // Toute modification passe par ici : un changement d'étape est capturé au moment même où il a lieu,
    // y compris deux étapes enchaînées sans pause (un observateur du StateFlow ne verrait que la dernière).
    private fun update(transform: (OnboardingState) -> OnboardingState) {
        val before = _state.value.step
        _state.update(transform)
        val after = _state.value.step
        if (after != before) captureStep(after)
    }

    init {
        captureStep(_state.value.step)
    }


    init {
        // Aperçu de l'avatar proposé par défaut, pour l'écran « Crée ton avatar ».
        viewModelScope.launch { runCatching { accounts.avatarCatalog() }.onSuccess { c -> update { it.copy(avatarPreview = c.defaults) } } }
    }

    // --- Numéro de téléphone -------------------------------------------------------------------

    fun onCountry(country: Country) = update { it.copy(country = country, phoneError = null) }
    fun onNational(value: String) = update { it.copy(national = value.filter { c -> c.isDigit() || c == ' ' }, phoneError = null) }

    val phoneComplete get() = _state.value.national.count(Char::isDigit) >= 6

    // Le numéro n'est pas vérifié (ADR-010) : il sert à ce que tes amis te retrouvent par leurs contacts.
    fun savePhone() {
        val s = _state.value
        val number = s.country.international(s.national)
        AccountRules.phoneError(number)?.let { e -> update { it.copy(phoneError = e) }; return }
        update { it.copy(savingPhone = true, phoneError = null) }
        viewModelScope.launch {
            try {
                accounts.updateProfile(username = null, phoneNumber = number)
                update { it.copy(savingPhone = false, step = OnboardingStep.FIND_FRIENDS) }
            } catch (e: ApiException) {
                update { it.copy(savingPhone = false, phoneError = e.message) }
            }
        }
    }

    fun skipPhone() = skip(OnboardingStep.FIND_FRIENDS)

    // --- Contacts et amis ----------------------------------------------------------------------

    /** Réponse de l'utilisateur à la demande d'accès aux contacts (refus = recherche par pseudo seule). */
    fun onContactsPermission(granted: Boolean) {
        analytics.capture(AnalyticsEvents.CONTACTS_PERMISSION_ANSWERED,
            mapOf("granted" to granted) + contactsExposure?.conversionProperties.orEmpty())
        update { it.copy(contactsGranted = granted, step = OnboardingStep.ADD_FRIENDS, loadingContacts = granted) }
        if (!granted) return
        viewModelScope.launch {
            val contacts = runCatching { contactsReader.read() }.getOrDefault(emptyList())
            val matches = runCatching { friends.matchContacts(contacts.flatMap { it.phoneNumbers }) }.getOrDefault(emptyList())
                .distinctBy { it.user.id }
            update { s ->
                s.copy(loadingContacts = false, contacts = contacts, matches = matches,
                    selected = s.copy(matches = matches).addable.map { it.user.id }.toSet())
            }
        }
    }

    fun skipFindFriends() = skip(OnboardingStep.ADD_FRIENDS)

    fun toggle(userId: String) = update { it.copy(selected = if (userId in it.selected) it.selected - userId else it.selected + userId) }

    fun onQuery(value: String) = update { it.copy(query = value, searchResult = null, searchError = null) }

    fun search() {
        val query = _state.value.query.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            try {
                val result = friends.search(AccountRules.normalizeUsername(query))
                update { it.copy(searchResult = result) }
            } catch (e: ApiException) {
                update { it.copy(searchError = e.message) }
            }
        }
    }

    // Interface optimiste : « Demande envoyée » tout de suite, l'envoi se termine même si l'écran change.
    fun addSearchResult() {
        val result = _state.value.searchResult ?: return
        if (result.relation != Relation.NONE && result.relation != Relation.INCOMING_REQUEST) return
        val relation = if (result.relation == Relation.INCOMING_REQUEST) Relation.FRIENDS else Relation.OUTGOING_REQUEST
        update { it.copy(searchResult = result.copy(relation = relation)) }
        viewModelScope.launch {
            completeEvenIfClosed { friends.sendRequest(result.user.username) }
                .onFailure { e -> update { it.copy(searchResult = result, message = e.message ?: "Demande impossible") } }
        }
    }

    /** « Ajouter N amis » : les demandes partent en arrière-plan, on passe à l'écran suivant. */
    fun addSelected() {
        val s = _state.value
        val users = s.addable.filter { it.user.id in s.selected }.map { it.user.username }
        viewModelScope.launch {
            users.forEach { username -> completeEvenIfClosed { friends.sendRequest(username) } }
        }
        afterFriends()
    }

    fun skipAddFriends() {
        captureSkip()
        afterFriends()
    }

    private fun afterFriends() = go(if (_state.value.invitable.isNotEmpty()) OnboardingStep.INVITE else OnboardingStep.AVATAR)

    // --- Invitations ---------------------------------------------------------------------------

    fun toggleInvite(name: String) = update { it.copy(invited = if (name in it.invited) it.invited - name else it.invited + name) }

    /** Numéros des contacts cochés, pour le SMS d'invitation ouvert par l'écran. L'étape suivante s'affiche. */
    fun sendInvites(): List<String> {
        val s = _state.value
        val numbers = s.invitable.filter { it.name in s.invited }.mapNotNull { it.phoneNumbers.firstOrNull() }
        if (numbers.isNotEmpty()) analytics.capture(AnalyticsEvents.INVITES_SENT, mapOf("count" to numbers.size))
        go(OnboardingStep.AVATAR)
        return numbers
    }

    fun skipInvites() = skip(OnboardingStep.AVATAR)

    // --- Avatar et notifications ---------------------------------------------------------------

    /** Avatar validé dans l'éditeur : enregistré en arrière-plan (ADR-028), on continue. */
    fun applyAvatar(change: AvatarChange) {
        viewModelScope.launch {
            completeEvenIfClosed {
                when (change) {
                    is AvatarChange.Set -> accounts.setAvatar(change.options)
                    AvatarChange.Remove -> accounts.removeAvatar()
                }
            }.onFailure { e -> update { it.copy(message = e.message ?: "Avatar non enregistré") } }
        }
        afterAvatar()
    }

    fun skipAvatar() {
        captureSkip()
        afterAvatar()
    }

    private fun afterAvatar() = go(if (needsNotificationPrompt()) OnboardingStep.NOTIFICATIONS else OnboardingStep.DONE)

    fun finish() = go(OnboardingStep.DONE)

    fun consumeMessage() = update { it.copy(message = null) }

    private fun go(step: OnboardingStep) = update { it.copy(step = step) }

    /** Entonnoir des premiers pas (ADR-008, ADR-029) : chaque étape affichée, puis la fin. */
    private fun captureStep(step: OnboardingStep) {
        if (step == OnboardingStep.DONE) analytics.capture(AnalyticsEvents.ONBOARDING_COMPLETED)
        else analytics.capture(AnalyticsEvents.ONBOARDING_STEP_VIEWED, mapOf("step" to step.name.lowercase()))
        // Exposition à l'affichage de l'écran testé, une seule fois (ADR-030).
        if (step == OnboardingStep.FIND_FRIENDS && contactsExposure == null) {
            val exposure = experiments.expose(OnboardingContactsCopy.FLAG, OnboardingContactsCopy::of)
            contactsExposure = exposure
            _state.update { it.copy(contactsCopy = exposure.value) }
        }
    }

    private fun skip(next: OnboardingStep) {
        captureSkip()
        go(next)
    }

    private fun captureSkip() =
        analytics.capture(AnalyticsEvents.ONBOARDING_STEP_SKIPPED, mapOf("step" to _state.value.step.name.lowercase()))
}

package com.snapcore.app

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.Session
import com.snapcore.app.domain.SessionResolver
import com.snapcore.app.domain.suggestUsername
import com.snapcore.app.ui.auth.LoginViewModel
import com.snapcore.app.ui.auth.SignUpStep
import com.snapcore.app.ui.auth.SignUpViewModel
import com.snapcore.app.ui.auth.UsernameStatus
import com.snapcore.app.ui.profile.EditableField
import com.snapcore.app.ui.profile.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

// Inscription en 5 étapes façon Snapchat (ADR-029), numéro vérifié par code SMS (ADR-034).
@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 3)
    private lateinit var auth: FakeAuth
    private lateinit var accounts: FakeAccounts
    private lateinit var pending: FakePending
    private lateinit var vm: SignUpViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = FakeAuth()
        accounts = FakeAccounts()
        pending = FakePending()
        vm = viewModel()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(resuming: Boolean = false) =
        SignUpViewModel(auth, accounts, pending, resuming, today = { today }, random = Random(7), checkDelayMs = 10)

    // Jusqu'à l'étape du pseudo, avec le pseudo proposé d'office.
    private fun TestScope.reachUsername() {
        vm.onFirstName("  Nathan   Marie ")
        vm.next()
        vm.onBirthday(LocalDate.of(2004, 5, 12))
        vm.next()
        advanceUntilIdle()
    }

    // Jusqu'à l'étape du code : pseudo accepté, numéro saisi, code demandé.
    private fun TestScope.reachCode() {
        reachUsername()
        vm.next()
        vm.onNational("06 12 34 56 78")
        vm.next()
        advanceUntilIdle()
    }

    @Test
    fun `étape 1, prénom obligatoire, plus de nom de famille`() {
        assertFalse(vm.state.value.canContinue)
        vm.onFirstName("Nathan2")
        vm.next()
        assertEquals(SignUpStep.NAME, vm.state.value.step)
        assertNotNull(vm.state.value.firstNameError)

        vm.onFirstName("Nathan")
        vm.next()
        assertEquals(SignUpStep.BIRTHDAY, vm.state.value.step)
    }

    @Test
    fun `moins de 13 ans, l'inscription est refusée à l'étape de la date de naissance`() {
        vm.onFirstName("Nathan")
        vm.next()
        assertFalse("bouton grisé tant que la roue n'a pas bougé", vm.state.value.canContinue)

        vm.onBirthday(today.minusYears(13).plusDays(1))
        vm.next()
        assertEquals(SignUpStep.BIRTHDAY, vm.state.value.step)
        assertEquals("Tu dois avoir au moins 13 ans pour t'inscrire", vm.state.value.birthdayError)

        vm.onBirthday(today.minusYears(13))
        vm.next()
        assertEquals(SignUpStep.USERNAME, vm.state.value.step)
    }

    @Test
    fun `un pseudo libre est proposé d'office, à partir du prénom`() = runTest(dispatcher) {
        reachUsername()

        val state = vm.state.value
        assertFalse(state.editingUsername)
        assertTrue(state.username, state.username.startsWith("nathanma"))
        assertNull(AccountRules.usernameError(state.username))
        assertEquals(UsernameStatus.Available, state.usernameStatus)
    }

    @Test
    fun `pseudos proposés tous pris, on passe à la saisie`() = runTest(dispatcher) {
        accounts.allUsernamesTaken = true
        reachUsername()

        assertTrue(vm.state.value.editingUsername)
        assertTrue(vm.state.value.usernameStatus is UsernameStatus.Unavailable)
        assertFalse(vm.state.value.canContinue)
    }

    @Test
    fun `pseudo saisi, forme vérifiée sur place puis disponibilité auprès du backend`() = runTest(dispatcher) {
        reachUsername()
        vm.editUsername()
        accounts.takenUsernames += "alice"

        vm.onUsername("1x")
        assertTrue(vm.state.value.usernameStatus is UsernameStatus.Unavailable)

        vm.onUsername("alice")
        assertEquals(UsernameStatus.Checking, vm.state.value.usernameStatus)
        advanceUntilIdle()
        assertEquals(UsernameStatus.Unavailable("Ce pseudo est déjà pris"), vm.state.value.usernameStatus)

        vm.onUsername("alice_74")
        advanceUntilIdle()
        assertEquals(UsernameStatus.Available, vm.state.value.usernameStatus)
    }

    @Test
    fun `étape 4, numéro au format international, puis code envoyé par SMS`() = runTest(dispatcher) {
        reachUsername()
        vm.next()
        assertEquals(SignUpStep.PHONE, vm.state.value.step)
        assertFalse(vm.state.value.canContinue)

        vm.onNational("06 12 34 56 78")
        vm.next()
        advanceUntilIdle()

        assertEquals(listOf("+33612345678"), auth.codesSentTo)
        assertEquals(SignUpStep.CODE, vm.state.value.step)
        assertEquals("+33612345678", vm.state.value.phone.sentTo)
        assertEquals("le SMS simulé montre le code", "482913", vm.state.value.phone.simulatedCode)
        assertFalse("pas de compte Firebase avant le code", auth.signedIn)
    }

    @Test
    fun `numéro refusé par le backend, l'erreur reste à l'étape du numéro`() = runTest(dispatcher) {
        auth.failCodeRequestWith = ApiException(400, "Attends encore 12 s avant de demander un nouveau code")
        reachUsername()
        vm.next()
        vm.onNational("0612345678")
        vm.next()
        advanceUntilIdle()

        assertEquals(SignUpStep.PHONE, vm.state.value.step)
        assertEquals("Attends encore 12 s avant de demander un nouveau code", vm.state.value.phone.numberError)
    }

    @Test
    fun `parcours complet, le code saisi ouvre la session et crée le compte, sans email ni nom de famille`() = runTest(dispatcher) {
        reachCode()
        vm.onCode("48291")
        assertTrue("pas encore vérifié à 5 chiffres", auth.phoneSignIns.isEmpty())
        vm.onCode("482913")
        advanceUntilIdle()

        val registration = accounts.registrations.single()
        assertTrue(vm.state.value.session is Session.Ready)
        assertEquals(listOf("+33612345678"), auth.phoneSignIns)
        assertEquals("Nathan Marie", registration.firstName)
        assertNull(registration.lastName)
        assertEquals(LocalDate.of(2004, 5, 12), registration.birthday)
        assertNull("le numéro vérifié est lu par le backend dans le compte Firebase", registration.phoneNumber)
        assertNull(pending.value) // effacé après succès
    }

    @Test
    fun `mauvais code, les cases se vident et l'erreur s'affiche, puis le bon code passe`() = runTest(dispatcher) {
        reachCode()
        vm.onCode("000000")
        advanceUntilIdle()

        assertEquals(SignUpStep.CODE, vm.state.value.step)
        assertEquals("", vm.state.value.phone.code)
        assertEquals("Code incorrect. Vérifie le SMS et réessaie.", vm.state.value.phone.codeError)
        assertTrue(accounts.registrations.isEmpty())

        vm.useSimulatedCode()
        advanceUntilIdle()
        assertTrue(vm.state.value.session is Session.Ready)
    }

    @Test
    fun `numéro déjà inscrit, le code connecte au compte existant sans en créer un second`() = runTest(dispatcher) {
        reachCode()
        accounts.account = com.snapcore.app.domain.Account("id", "nathan", null, null, null, false)
        vm.useSimulatedCode()
        advanceUntilIdle()

        assertEquals(Session.Ready(accounts.account!!), vm.state.value.session)
        assertTrue(accounts.registrations.isEmpty())
    }

    @Test
    fun `pseudo pris entre-temps, retour à l'étape du pseudo, puis nouvel essai`() = runTest(dispatcher) {
        reachCode()
        accounts.takenUsernames += vm.state.value.username
        vm.useSimulatedCode()
        advanceUntilIdle()

        assertEquals(SignUpStep.USERNAME, vm.state.value.step)
        assertTrue(vm.state.value.editingUsername)
        assertNotNull(pending.value) // conservé : reprise possible au prochain lancement
    }

    @Test
    fun `reprise d'inscription, trois étapes puis Terminer sans recréer de compte Firebase`() = runTest(dispatcher) {
        auth.signedIn = true
        vm = viewModel(resuming = true)
        assertEquals(3, vm.state.value.steps)
        reachUsername()
        assertTrue(vm.state.value.isLastStep)

        vm.next()
        advanceUntilIdle()

        assertTrue(vm.state.value.session is Session.Ready)
        assertTrue("pas de nouveau code en reprise", auth.codesSentTo.isEmpty())
    }

    @Test
    fun `reprise abandonnée depuis la première étape, déconnexion`() {
        auth.signedIn = true
        vm = viewModel(resuming = true)
        assertFalse(vm.back())
        assertEquals(Session.SignedOut, vm.state.value.session)
        assertEquals(1, auth.signOuts)
    }

    @Test
    fun `pseudo proposé, toujours conforme à la règle du backend`() {
        repeat(50) { seed ->
            listOf("Nathan" to "Boudet", "Éloïse-Marie" to null, "O'Brien" to "D", "" to null, "Maximiliennnnnn" to "Zoé").forEach { (first, last) ->
                assertNull(AccountRules.usernameError(suggestUsername(first, last, Random(seed))))
            }
        }
    }

    @Test
    fun `profil, changement de pseudo, 409 affiché dans le champ`() = runTest(dispatcher) {
        auth.signedIn = true
        accounts.account = com.snapcore.app.domain.Account("id", "nathan", null, null, null, false)
        accounts.takenUsernames += "alice"
        val profile = ProfileViewModel(auth, accounts, pending, FakeDevices(), RecordingUploader(), FakePhotoPreparer())
        advanceUntilIdle()

        profile.startEditing(EditableField.USERNAME)
        profile.onDraft("Alice")
        profile.saveEditing()
        advanceUntilIdle()
        assertEquals("Ce pseudo est déjà pris", profile.state.value.draftError)

        profile.onDraft("nathan_74")
        profile.saveEditing()
        advanceUntilIdle()
        assertEquals("nathan_74", profile.state.value.account?.username)
        assertNull(profile.state.value.editing)
    }

    @Test
    fun `suppression de compte, appel backend puis déconnexion Firebase`() = runTest(dispatcher) {
        auth.signedIn = true
        accounts.account = com.snapcore.app.domain.Account("id", "nathan", null, null, null, false)
        val profile = ProfileViewModel(auth, accounts, pending, FakeDevices(), RecordingUploader(), FakePhotoPreparer())
        advanceUntilIdle()

        profile.askDeletion()
        profile.deleteAccount()
        advanceUntilIdle()

        assertTrue(accounts.deleted)
        assertTrue(profile.state.value.signedOut)
        assertEquals(false, auth.signedIn)
    }

    @Test
    fun `analytics, chaque etape affichee, et l'echec avec son etape et son type d'erreur`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        vm = SignUpViewModel(auth, accounts, pending, today = { today }, random = Random(7), checkDelayMs = 10, analytics = analytics)
        advanceUntilIdle()
        reachUsername()
        vm.back()
        advanceUntilIdle()
        vm.next()
        advanceUntilIdle()

        assertEquals(listOf("name", "birthday", "username", "birthday", "username"),
            analytics.events.filter { it.first == AnalyticsEvents.SIGNUP_STEP_VIEWED }.map { it.second["step"] })

        auth.failCodeRequestWith = ApiException(400, "Numéro de téléphone invalide : vérifie le pays et le numéro")
        vm.next()
        vm.onNational("0612345678")
        vm.next()
        advanceUntilIdle()
        val failure = analytics.propertiesOf(AnalyticsEvents.SIGNUP_FAILED)
        assertEquals("phone", failure["step"])
        assertEquals("http_400", failure["reason"])
        assertFalse("jamais le numéro", failure.values.any { "0612" in it.toString() || "+33" in it.toString() })
    }

    @Test
    fun `analytics, connexion refusee avec le code HTTP puis reussie`() = runTest(dispatcher) {
        val analytics = RecordingAnalytics()
        val login = LoginViewModel(auth, SessionResolver(auth, accounts, pending), analytics, countryCode = "FR")
        login.useEmail(true)
        login.onEmail("nathan@snapcore.test")
        login.onPassword("secret")
        auth.failSignInWith = ApiException(503, "Service indisponible")
        login.submit()
        advanceUntilIdle()
        assertEquals(mapOf("reason" to "http_503"), analytics.propertiesOf(AnalyticsEvents.LOGIN_FAILED))

        auth.failSignInWith = null
        login.submit()
        advanceUntilIdle()
        assertEquals(AnalyticsEvents.LOGIN_COMPLETED, analytics.names.last())
    }

    @Test
    fun `connexion par code SMS, numéro, code puis session`() = runTest(dispatcher) {
        accounts.account = com.snapcore.app.domain.Account("id", "nathan", null, null, null, false)
        val login = LoginViewModel(auth, SessionResolver(auth, accounts, pending), countryCode = "FR")
        login.onNational("6 12 34 56 78")
        login.submit()
        advanceUntilIdle()
        assertEquals(com.snapcore.app.ui.auth.LoginMode.CODE, login.state.value.mode)

        login.onCode("482913")
        advanceUntilIdle()
        assertEquals(listOf("+33612345678"), auth.phoneSignIns)
        assertTrue(login.state.value.session is Session.Ready)
    }
}

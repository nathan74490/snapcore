package com.snapcore.app

import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.Session
import com.snapcore.app.domain.suggestUsername
import com.snapcore.app.ui.auth.PasswordHint
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

// Inscription en 5 étapes façon Snapchat (ADR-029).
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
        vm.onLastName(" Boudet ")
        vm.next()
        vm.onBirthday(LocalDate.of(2004, 5, 12))
        vm.next()
        advanceUntilIdle()
    }

    private fun TestScope.fillAllSteps() {
        reachUsername()
        vm.next()
        vm.onPassword("MotDePasse123")
        vm.next()
        vm.onEmail("nathan@snapcore.test")
    }

    @Test
    fun `étape 1, prénom obligatoire et nom facultatif`() {
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
    fun `un pseudo libre est proposé d'office, à partir du nom`() = runTest(dispatcher) {
        reachUsername()

        val state = vm.state.value
        assertFalse(state.editingUsername)
        assertTrue(state.username, state.username.startsWith("nathanmab"))
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
    fun `mot de passe de 8 caractères au moins`() = runTest(dispatcher) {
        reachUsername()
        vm.next()
        assertEquals(PasswordHint.EMPTY, vm.state.value.passwordHint)
        vm.onPassword("court")
        assertEquals(PasswordHint.TOO_SHORT, vm.state.value.passwordHint)
        assertFalse(vm.state.value.canContinue)
        vm.onPassword("assezlong")
        assertEquals(PasswordHint.OK, vm.state.value.passwordHint)
        assertTrue(vm.state.value.canContinue)
    }

    @Test
    fun `raccourci de domaine email`() {
        vm.onEmail("nathan@gm")
        vm.completeEmail("@gmail.com")
        assertEquals("nathan@gmail.com", vm.state.value.email)
    }

    @Test
    fun `parcours complet, compte Firebase, inscription backend normalisée, session prête`() = runTest(dispatcher) {
        fillAllSteps()
        vm.next()
        advanceUntilIdle()

        val registration = accounts.registrations.single()
        assertTrue(vm.state.value.session is Session.Ready)
        assertEquals(listOf("nathan@snapcore.test"), auth.signUps)
        assertEquals("Nathan Marie", registration.firstName)
        assertEquals("Boudet", registration.lastName)
        assertEquals(LocalDate.of(2004, 5, 12), registration.birthday)
        assertNull("le téléphone est demandé après l'inscription", registration.phoneNumber)
        assertNull(pending.value) // effacé après succès
    }

    @Test
    fun `pseudo pris entre-temps, retour à l'étape du pseudo, puis nouvel essai sans recréer le compte Firebase`() = runTest(dispatcher) {
        fillAllSteps()
        accounts.takenUsernames += vm.state.value.username
        vm.next()
        advanceUntilIdle()

        assertEquals(SignUpStep.USERNAME, vm.state.value.step)
        assertTrue(vm.state.value.editingUsername)
        assertTrue(vm.state.value.firebaseAccountCreated)
        assertNotNull(pending.value) // conservé : reprise possible au prochain lancement

        vm.onUsername("nathan_74")
        advanceUntilIdle()
        repeat(3) { vm.next() }
        advanceUntilIdle()

        assertTrue(vm.state.value.session is Session.Ready)
        assertEquals(1, auth.signUps.size)
    }

    @Test
    fun `email déjà utilisé chez Firebase, l'erreur reste à l'étape email`() = runTest(dispatcher) {
        auth.failSignUpWith = "Un compte existe déjà avec cette adresse email"
        fillAllSteps()
        vm.next()
        advanceUntilIdle()

        assertEquals(SignUpStep.EMAIL, vm.state.value.step)
        assertEquals("Un compte existe déjà avec cette adresse email", vm.state.value.emailError)
        assertTrue(accounts.registrations.isEmpty())
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
        assertTrue(auth.signUps.isEmpty())
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
}

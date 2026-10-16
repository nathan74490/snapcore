package com.snapcore.app

import com.snapcore.app.domain.Session
import com.snapcore.app.ui.auth.SignUpViewModel
import com.snapcore.app.ui.profile.EditableField
import com.snapcore.app.ui.profile.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {
    private val dispatcher = StandardTestDispatcher()
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
        vm = SignUpViewModel(auth, accounts, pending)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fillAllSteps(username: String = "Nathan") {
        vm.onFirstName("  Nathan   Marie ")
        vm.onUsername(username)
        vm.next()
        vm.onEmail("nathan@snapcore.test")
        vm.onPhone("+33 6 12 34 56 78")
        vm.next()
        vm.onPassword("MotDePasse123")
    }

    @Test
    fun `refuse un pseudo invalide sans quitter l'étape 1`() {
        vm.onUsername("1x")
        vm.next()
        assertEquals(1, vm.state.value.step)
        assertNotNull(vm.state.value.usernameError)
    }

    @Test
    fun `refuse un prénom invalide sans quitter l'étape 1`() {
        vm.onFirstName("Nathan2")
        vm.onUsername("nathan")
        vm.next()
        assertEquals(1, vm.state.value.step)
        assertNotNull(vm.state.value.firstNameError)
    }

    @Test
    fun `parcours complet, compte Firebase, inscription backend normalisée, session prête`() = runTest(dispatcher) {
        fillAllSteps()
        vm.next()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.session is Session.Ready)
        assertEquals("nathan", accounts.registrations.single().username)
        assertEquals("+33612345678", accounts.registrations.single().phoneNumber)
        assertEquals("Nathan Marie", accounts.registrations.single().firstName)
        assertNull(pending.value) // effacé après succès
    }

    @Test
    fun `pseudo déjà pris, retour à l'étape 1, puis nouvel essai sans recréer le compte Firebase`() = runTest(dispatcher) {
        accounts.takenUsernames += "nathan"
        fillAllSteps()
        vm.next()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.step)
        assertEquals("Ce pseudo est déjà pris", vm.state.value.usernameError)
        assertTrue(vm.state.value.firebaseAccountCreated)
        assertNotNull(pending.value) // conservé : reprise possible au prochain lancement

        vm.onUsername("nathan2")
        repeat(3) { vm.next() }
        advanceUntilIdle()

        assertTrue(vm.state.value.session is Session.Ready)
        assertEquals(1, auth.signUps.size)
    }

    @Test
    fun `email déjà utilisé chez Firebase, retour à l'étape 2 avec l'erreur`() = runTest(dispatcher) {
        auth.failSignUpWith = "Un compte existe déjà avec cette adresse email"
        fillAllSteps()
        vm.next()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.step)
        assertEquals("Un compte existe déjà avec cette adresse email", vm.state.value.emailError)
        assertTrue(accounts.registrations.isEmpty())
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

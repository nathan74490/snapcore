package com.snapcore.app

import com.snapcore.app.domain.OnboardingContactsCopy
import com.snapcore.app.ui.auth.LoginState
import com.snapcore.app.ui.auth.SignUpState
import com.snapcore.app.ui.auth.LoginMode
import com.snapcore.app.ui.auth.PhoneEntry
import com.snapcore.app.domain.Countries
import com.snapcore.app.domain.PhoneCodeSent
import com.snapcore.app.ui.auth.SignUpStep
import com.snapcore.app.ui.auth.UsernameStatus
import com.snapcore.app.ui.components.GhostMood
import com.snapcore.app.ui.onboarding.GhostLines
import com.snapcore.app.ui.onboarding.Journey
import com.snapcore.app.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parcours d'arrivée guidé par le fantôme : progression commune et répliques écran par écran. */
class GhostGuideTest {
    @Test
    fun `la progression part de zero, avance a chaque ecran et finit pleine`() {
        val signUp = (1..5).map { Journey.signUp(it) }
        val firstSteps = OnboardingStep.entries.map { Journey.firstSteps(it.ordinal) }
        val all = signUp + firstSteps
        assertEquals(0f, all.first())
        assertEquals(1f, Journey.firstSteps(OnboardingStep.DONE.ordinal))
        assertTrue("toujours croissante", all.zipWithNext().all { (a, b) -> b > a })
        // Les premiers pas prennent la suite de la dernière étape de l'inscription.
        assertTrue(Journey.firstSteps(0) > Journey.signUp(5))
    }

    private val france = PhoneEntry(Countries.byCode("FR"))
    private fun signUp(step: Int, firstName: String = "", phone: PhoneEntry = france, editingUsername: Boolean = false,
        usernameStatus: UsernameStatus = UsernameStatus.Checking, firstNameError: String? = null, birthdayError: String? = null) =
        SignUpState(step = step, firstName = firstName, phone = phone, editingUsername = editingUsername, usernameStatus = usernameStatus,
            firstNameError = firstNameError, birthdayError = birthdayError)

    @Test
    fun `TalkBack, l'etape est annoncee avant la question, de 1 a 10`() {
        assertEquals("Étape 1 sur 10", Journey.signUpLabel(1))
        assertEquals("Étape 5 sur 10", Journey.signUpLabel(5))
        assertEquals("Étape 3 sur 8", Journey.signUpLabel(3, steps = 3))
        assertEquals("Étape 6 sur 10", Journey.firstStepsLabel(OnboardingStep.FIND_FRIENDS.ordinal))
        assertEquals("Étape 10 sur 10", Journey.firstStepsLabel(OnboardingStep.NOTIFICATIONS.ordinal))
        assertEquals("Étape 10 sur 10", Journey.firstStepsLabel(OnboardingStep.DONE.ordinal))
    }

    @Test
    fun `chaque etape de l'inscription a sa question et son humeur`() {
        val lines = (SignUpStep.NAME..SignUpStep.CODE).map { GhostLines.signUp(signUp(it, firstName = "Léa")) }
        assertEquals("une question différente par écran", lines.size, lines.map { it.text }.toSet().size)
        assertEquals(GhostMood.WAVE, lines.first().mood)
        assertTrue("il retient le prénom", lines[SignUpStep.BIRTHDAY - 1].text.contains("Léa"))
        assertTrue("il demande le numéro et annonce un SMS", lines[SignUpStep.PHONE - 1].text.contains("SMS"))
    }

    @Test
    fun `code envoye, il rappelle le numero, et s'inquiete d'un mauvais code`() {
        val sent = france.codeSent(PhoneCodeSent("+33612345678", 30, "482913"))
        val line = GhostLines.signUp(signUp(SignUpStep.CODE, phone = sent))
        assertTrue(line.text, line.text.contains("+33 6 12 34 56 78"))
        assertEquals(GhostMood.SEARCH, line.mood)
        val wrong = GhostLines.signUp(signUp(SignUpStep.CODE, phone = sent.copy(codeError = "Code incorrect")))
        assertEquals(line.text, wrong.text)
        assertEquals(GhostMood.OOPS, wrong.mood)
    }

    @Test
    fun `une reponse refusee inquiete le fantome sans changer sa phrase`() {
        val ok = GhostLines.signUp(signUp(SignUpStep.USERNAME, editingUsername = true, usernameStatus = UsernameStatus.Available))
        val taken = GhostLines.signUp(signUp(SignUpStep.USERNAME, editingUsername = true, usernameStatus = UsernameStatus.Unavailable("Déjà pris")))
        assertEquals(ok.text, taken.text)
        assertEquals(GhostMood.OOPS, taken.mood)
        assertNotEquals(GhostMood.OOPS, ok.mood)
    }

    @Test
    fun `l'ecran qui s'en va garde sa replique pendant la transition`() {
        val state = signUp(SignUpStep.BIRTHDAY, firstName = "Léa", firstNameError = "x", birthdayError = "Trop jeune")
        val leaving = GhostLines.signUp(state, step = SignUpStep.NAME)
        assertEquals(GhostLines.signUp(signUp(SignUpStep.NAME)).text, leaving.text)
        assertNotEquals("seule l'étape affichée réagit à l'erreur", GhostMood.OOPS, leaving.mood)
    }

    @Test
    fun `connexion, numero, code, email, reflexion et erreur`() {
        val phone = LoginState(phone = france)
        assertEquals(GhostMood.WAVE, GhostLines.login(phone, passwordFocused = false).mood)
        assertTrue(GhostLines.login(phone, false).text.contains("numéro"))
        assertEquals(GhostMood.SEARCH, GhostLines.login(phone.copy(mode = LoginMode.CODE), false).mood)
        assertEquals(GhostMood.SHY, GhostLines.login(phone.copy(mode = LoginMode.EMAIL, email = "a@b.fr"), passwordFocused = true).mood)
        assertEquals(GhostMood.THINKING, GhostLines.login(phone.copy(loading = true), passwordFocused = true).mood)
        val failed = GhostLines.login(phone.copy(mode = LoginMode.EMAIL, error = "Email ou mot de passe incorrect"), passwordFocused = true)
        assertEquals(GhostMood.OOPS, failed.mood)
        assertEquals("Oups ! Email ou mot de passe incorrect.", failed.text)
    }

    @Test
    fun `premiers pas, le titre de l'experience et les amis trouves`() {
        OnboardingContactsCopy.entries.forEach { assertEquals(it.title, GhostLines.findFriends(it).text) }
        assertEquals(GhostMood.THINKING, GhostLines.addFriends(0).mood)
        assertTrue(GhostLines.addFriends(3).text.contains("3 amis"))
        assertEquals(GhostMood.CELEBRATE, GhostLines.notifications.mood)
    }
}

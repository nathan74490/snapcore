package com.snapcore.app

import com.snapcore.app.domain.OnboardingContactsCopy
import com.snapcore.app.ui.auth.LoginState
import com.snapcore.app.ui.auth.SignUpState
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

    @Test
    fun `chaque etape de l'inscription a sa question et son humeur`() {
        val lines = (SignUpStep.NAME..SignUpStep.EMAIL).map { GhostLines.signUp(SignUpState(step = it, firstName = "Léa")) }
        assertEquals("une question différente par écran", lines.size, lines.map { it.text }.toSet().size)
        assertEquals(GhostMood.WAVE, lines.first().mood)
        assertEquals(GhostMood.SHY, lines[SignUpStep.PASSWORD - 1].mood)
        assertTrue("il retient le prénom", lines[SignUpStep.BIRTHDAY - 1].text.contains("Léa"))
    }

    @Test
    fun `une reponse refusee inquiete le fantome sans changer sa phrase`() {
        val ok = GhostLines.signUp(SignUpState(step = SignUpStep.USERNAME, editingUsername = true, usernameStatus = UsernameStatus.Available))
        val taken = GhostLines.signUp(SignUpState(step = SignUpStep.USERNAME, editingUsername = true, usernameStatus = UsernameStatus.Unavailable("Déjà pris")))
        assertEquals(ok.text, taken.text)
        assertEquals(GhostMood.OOPS, taken.mood)
        assertNotEquals(GhostMood.OOPS, ok.mood)
    }

    @Test
    fun `un mot de passe en cours de saisie ne l'inquiete pas`() {
        assertEquals(GhostMood.SHY, GhostLines.signUp(SignUpState(step = SignUpStep.PASSWORD, password = "abc")).mood)
    }

    @Test
    fun `l'ecran qui s'en va garde sa replique pendant la transition`() {
        val state = SignUpState(step = SignUpStep.BIRTHDAY, firstName = "Léa", firstNameError = "x", birthdayError = "Trop jeune")
        val leaving = GhostLines.signUp(state, step = SignUpStep.NAME)
        assertEquals(GhostLines.signUp(SignUpState(step = SignUpStep.NAME)).text, leaving.text)
        assertNotEquals("seule l'étape affichée réagit à l'erreur", GhostMood.OOPS, leaving.mood)
    }

    @Test
    fun `connexion, il accueille, se cache les yeux, reflechit et annonce l'erreur`() {
        assertEquals(GhostMood.WAVE, GhostLines.login(LoginState(), passwordFocused = false).mood)
        assertEquals(GhostMood.SHY, GhostLines.login(LoginState(email = "a@b.fr"), passwordFocused = true).mood)
        assertEquals(GhostMood.THINKING, GhostLines.login(LoginState(loading = true), passwordFocused = true).mood)
        val failed = GhostLines.login(LoginState(error = "Email ou mot de passe incorrect"), passwordFocused = true)
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

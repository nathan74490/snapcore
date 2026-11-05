package com.snapcore.app.ui.onboarding

import com.snapcore.app.domain.OnboardingContactsCopy
import com.snapcore.app.ui.auth.LoginMode
import com.snapcore.app.ui.auth.LoginState
import com.snapcore.app.ui.auth.formatPhone
import com.snapcore.app.ui.auth.SignUpState
import com.snapcore.app.ui.auth.SignUpStep
import com.snapcore.app.ui.auth.UsernameStatus
import com.snapcore.app.ui.components.GhostMood

/** Ce que dit le fantôme, et avec quelle humeur. */
data class GhostLine(val text: String, val mood: GhostMood)

/**
 * Les répliques du fantôme, écran par écran. Il pose la question de l'écran ; quand une réponse est
 * refusée, il prend un air inquiet (OOPS) sans changer de phrase, pour ne pas faire bouger la bulle.
 */
object GhostLines {
    fun signUp(state: SignUpState, step: Int = state.step): GhostLine {
        val firstName = state.firstName.trim()
        val line = when (step) {
            SignUpStep.NAME ->
                if (state.resuming) GhostLine("Re-bonjour ! On reprend où on s'était arrêtés. Comment tu t'appelles ?", GhostMood.WAVE)
                else GhostLine("Salut ! Je suis le fantôme de SnapCore. Et toi, comment tu t'appelles ?", GhostMood.WAVE)
            SignUpStep.BIRTHDAY ->
                GhostLine(if (firstName.isEmpty()) "Enchanté ! C'est quand, ton anniversaire ?" else "Enchanté, $firstName ! C'est quand, ton anniversaire ?",
                    GhostMood.CELEBRATE)
            SignUpStep.USERNAME ->
                if (state.editingUsername) GhostLine("Choisis le pseudo qui te ressemble.", GhostMood.THINKING)
                else GhostLine("J'ai trouvé un pseudo rien que pour toi !", GhostMood.HAPPY)
            SignUpStep.PHONE -> GhostLine("Quel est ton numéro ? Je t'envoie un code par SMS pour vérifier que c'est bien toi.", GhostMood.CURIOUS)
            else -> codeSent(state.phone.sentTo)
        }
        return if (step == state.step && signUpRefused(state)) line.copy(mood = GhostMood.OOPS) else line
    }

    private fun signUpRefused(state: SignUpState): Boolean = state.error != null || when (state.step) {
        SignUpStep.NAME -> state.firstNameError != null
        SignUpStep.BIRTHDAY -> state.birthdayError != null
        SignUpStep.USERNAME -> state.usernameStatus is UsernameStatus.Unavailable
        SignUpStep.PHONE -> state.phone.numberError != null
        else -> state.phone.codeError != null
    }

    /** Code envoyé par SMS (ADR-034) : il cherche le message des yeux. */
    private fun codeSent(sentTo: String?) = GhostLine(
        if (sentTo == null) "Je t'ai envoyé un code par SMS. Tape-le ici !" else "Je t'ai envoyé un code par SMS au ${formatPhone(sentTo)}. Tape-le ici !",
        GhostMood.SEARCH)

    /**
     * Connexion par code SMS (ADR-034), ou par email pour les anciens comptes : il accueille, cherche le SMS,
     * se cache les yeux pendant la saisie du mot de passe, réfléchit pendant la vérification, et annonce
     * lui-même les erreurs qui ne concernent pas un champ.
     */
    fun login(state: LoginState, passwordFocused: Boolean): GhostLine {
        val error = state.error
        val fieldError = state.phone.numberError ?: state.phone.codeError
        val line = when {
            error != null -> return GhostLine("Oups ! " + error.trimEnd('.') + ".", GhostMood.OOPS)
            state.loading -> return GhostLine("Je vérifie… Une seconde !", GhostMood.THINKING)
            state.mode == LoginMode.CODE -> codeSent(state.phone.sentTo)
            state.mode == LoginMode.EMAIL && passwordFocused -> GhostLine("Ton mot de passe ? Je ne regarde pas, promis !", GhostMood.SHY)
            state.mode == LoginMode.EMAIL -> GhostLine("Connecte-toi avec ton email et ton mot de passe.", GhostMood.WAVE)
            else -> GhostLine("Content de te revoir ! Quel est ton numéro ? Je t'envoie un code par SMS.", GhostMood.WAVE)
        }
        return if (fieldError != null) line.copy(mood = GhostMood.OOPS) else line
    }

    /** Le titre reste celui de l'expérience onboarding-contacts-copy (ADR-030). */
    fun findFriends(copy: OnboardingContactsCopy) = GhostLine(copy.title, GhostMood.SEARCH)

    fun addFriends(found: Int) =
        if (found > 0) GhostLine(if (found == 1) "Regarde qui j'ai trouvé dans tes contacts !" else "Regarde, j'ai trouvé $found amis dans tes contacts !", GhostMood.HAPPY)
        else GhostLine("Tu connais le pseudo de tes amis ? Cherche-les ici.", GhostMood.THINKING)

    val invite = GhostLine("Invite tes amis : on s'amuse mieux à plusieurs !", GhostMood.LOVE)

    val avatar = GhostLine("À ton tour : crée l'avatar qui te représentera auprès de tes amis !", GhostMood.WAVE)

    val notifications = GhostLine("Bravo, ton compte est prêt ! Active les notifications pour ne rater aucun snap.", GhostMood.CELEBRATE)
}

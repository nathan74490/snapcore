package com.snapcore.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.PendingRegistration
import com.snapcore.app.domain.PendingRegistrationStore
import com.snapcore.app.domain.Session
import com.snapcore.app.domain.UserFacingException
import com.snapcore.app.domain.suggestUsername
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random

// --- Inscription en 5 étapes, comme Snapchat (ADR-029) ------------------------------------------
// 1 Nom, 2 Date de naissance, 3 Pseudo, 4 Mot de passe, 5 Email. Le compte Firebase n'est créé qu'à la
// dernière étape, puis le compte SnapCore (ADR-015). En reprise (compte Firebase sans compte SnapCore),
// seules les trois premières étapes sont demandées.

object SignUpStep {
    const val NAME = 1
    const val BIRTHDAY = 2
    const val USERNAME = 3
    const val PASSWORD = 4
    const val EMAIL = 5
}

sealed interface UsernameStatus {
    data object Checking : UsernameStatus
    data object Available : UsernameStatus
    data class Unavailable(val reason: String) : UsernameStatus
    /** Vérification impossible (réseau) : on laisse continuer, l'inscription tranchera. */
    data object Unverified : UsernameStatus
}

enum class PasswordHint { EMPTY, TOO_SHORT, OK }

/** Domaines proposés sous le champ email, au-dessus du clavier. */
val EMAIL_DOMAINS = listOf("@gmail.com", "@outlook.fr", "@hotmail.fr", "@yahoo.fr", "@icloud.com")

data class SignUpState(
    val step: Int = SignUpStep.NAME,
    /** Compte Firebase déjà créé (inscription reprise) : pas de mot de passe ni d'email à saisir. */
    val resuming: Boolean = false,
    val firstName: String = "",
    val lastName: String = "",
    val firstNameError: String? = null,
    val lastNameError: String? = null,
    val birthday: LocalDate? = null,
    val birthdayError: String? = null,
    val username: String = "",
    /** false : pseudo proposé d'office (« Ton pseudo est… ») ; true : champ de saisie. */
    val editingUsername: Boolean = false,
    val usernameStatus: UsernameStatus = UsernameStatus.Checking,
    val password: String = "",
    val email: String = "",
    val emailError: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val firebaseAccountCreated: Boolean = false,
    val session: Session? = null,
) {
    val steps get() = if (resuming) SignUpStep.USERNAME else SignUpStep.EMAIL
    val isLastStep get() = step == steps

    val passwordHint
        get() = when {
            password.isEmpty() -> PasswordHint.EMPTY
            password.length < AccountRules.MIN_PASSWORD_LENGTH -> PasswordHint.TOO_SHORT
            else -> PasswordHint.OK
        }

    /** Bouton grisé tant que l'étape n'est pas remplie, comme dans Snapchat. */
    val canContinue: Boolean
        get() = !loading && when (step) {
            SignUpStep.NAME -> firstName.isNotBlank()
            SignUpStep.BIRTHDAY -> birthday != null
            SignUpStep.USERNAME -> usernameStatus == UsernameStatus.Available || usernameStatus == UsernameStatus.Unverified
            SignUpStep.PASSWORD -> passwordHint == PasswordHint.OK
            else -> email.isNotBlank()
        }
}

class SignUpViewModel(
    private val auth: AuthService,
    private val accounts: AccountService,
    private val pending: PendingRegistrationStore,
    resuming: Boolean = false,
    private val today: () -> LocalDate = LocalDate::now,
    private val random: Random = Random.Default,
    private val checkDelayMs: Long = 400,
) : ViewModel() {
    private val _state = MutableStateFlow(SignUpState(resuming = resuming, firebaseAccountCreated = resuming))
    val state: StateFlow<SignUpState> = _state.asStateFlow()

    private var usernameCheck: Job? = null

    /** Adresse du compte Firebase, en reprise d'inscription. */
    val email get() = auth.currentEmail

    fun onFirstName(value: String) = _state.update { it.copy(firstName = value, firstNameError = null, error = null) }
    fun onLastName(value: String) = _state.update { it.copy(lastName = value, lastNameError = null, error = null) }
    fun onBirthday(value: LocalDate) = _state.update { it.copy(birthday = value, birthdayError = null, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onEmail(value: String) = _state.update { it.copy(email = value.trim(), emailError = null, error = null) }

    /** Raccourci de domaine : remplace ce qui suit le « @ » déjà tapé. */
    fun completeEmail(domain: String) = onEmail(_state.value.email.substringBefore('@') + domain)

    // En minuscules dès la saisie : c'est ainsi que le backend l'enregistre (ADR-014).
    fun onUsername(raw: String) {
        val value = raw.lowercase().filterNot(Char::isWhitespace)
        _state.update { it.copy(username = value, error = null) }
        checkUsername(value, delayMs = checkDelayMs)
    }

    fun editUsername() = _state.update { it.copy(editingUsername = true) }

    /** Bouton « actualiser » du champ : un autre pseudo proposé d'office. */
    fun suggestAnother() = proposeUsername()

    /** @return false si on était à la première étape (l'écran se ferme, ou la reprise est abandonnée). */
    fun back(): Boolean {
        if (_state.value.step == SignUpStep.NAME) {
            if (_state.value.resuming) {
                auth.signOut()
                _state.update { it.copy(session = Session.SignedOut) }
            }
            return false
        }
        _state.update { it.copy(step = it.step - 1, error = null) }
        return true
    }

    fun next() {
        val s = _state.value
        if (!s.canContinue) return
        when (s.step) {
            SignUpStep.NAME -> {
                val firstNameError = AccountRules.firstNameError(s.firstName)
                val lastNameError = AccountRules.lastNameError(s.lastName)
                if (firstNameError != null || lastNameError != null) {
                    _state.update { it.copy(firstNameError = firstNameError, lastNameError = lastNameError) }
                } else {
                    _state.update { it.copy(step = SignUpStep.BIRTHDAY) }
                }
            }
            SignUpStep.BIRTHDAY -> {
                val error = AccountRules.birthdayError(s.birthday, today())
                if (error != null) {
                    _state.update { it.copy(birthdayError = error) }
                } else {
                    _state.update { it.copy(step = SignUpStep.USERNAME) }
                    // Comme Snapchat : un pseudo est proposé d'office, à partir du nom.
                    if (s.username.isEmpty()) proposeUsername()
                }
            }
            SignUpStep.USERNAME -> if (s.isLastStep) submit() else _state.update { it.copy(step = SignUpStep.PASSWORD) }
            SignUpStep.PASSWORD -> _state.update { it.copy(step = SignUpStep.EMAIL) }
            else -> {
                val emailError = AccountRules.emailError(s.email)
                if (emailError != null) _state.update { it.copy(emailError = emailError) } else submit()
            }
        }
    }

    // Jusqu'à 5 propositions, chacune vérifiée auprès du backend ; si aucune n'est libre, on passe à la saisie.
    private fun proposeUsername() {
        usernameCheck?.cancel()
        val s = _state.value
        usernameCheck = viewModelScope.launch {
            repeat(5) {
                val candidate = suggestUsername(s.firstName, s.lastName, random)
                _state.update { it.copy(username = candidate, usernameStatus = UsernameStatus.Checking) }
                val status = verify(candidate)
                _state.update { it.copy(usernameStatus = status) }
                if (status !is UsernameStatus.Unavailable) return@launch
            }
            _state.update { it.copy(editingUsername = true) }
        }
    }

    private fun checkUsername(value: String, delayMs: Long) {
        usernameCheck?.cancel()
        // La forme se vérifie sur place, sans appel : seul un pseudo bien formé interroge le backend.
        AccountRules.usernameError(value)?.let { error ->
            _state.update { it.copy(usernameStatus = UsernameStatus.Unavailable(error)) }
            return
        }
        _state.update { it.copy(usernameStatus = UsernameStatus.Checking) }
        usernameCheck = viewModelScope.launch {
            delay(delayMs) // on attend que la saisie se pose
            val status = verify(value)
            _state.update { if (it.username == value) it.copy(usernameStatus = status) else it }
        }
    }

    private suspend fun verify(username: String): UsernameStatus = try {
        val result = accounts.usernameAvailability(AccountRules.normalizeUsername(username))
        if (result.available) UsernameStatus.Available else UsernameStatus.Unavailable(result.reason ?: "Ce pseudo est déjà pris")
    } catch (e: ApiException) {
        UsernameStatus.Unverified
    } catch (e: java.io.IOException) {
        UsernameStatus.Unverified
    }

    private fun submit() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // 1. Compte Firebase (une seule fois, même si l'appel au backend doit être rejoué).
            if (!s.firebaseAccountCreated) {
                try {
                    auth.signUp(s.email, s.password)
                    _state.update { it.copy(firebaseAccountCreated = true) }
                } catch (e: Throwable) {
                    _state.update { it.copy(loading = false, step = SignUpStep.EMAIL, emailError = e.userMessage()) }
                    return@launch
                }
            }
            // 2. Inscription explicite côté backend, avec reprise possible au prochain lancement.
            val registration = PendingRegistration(
                username = AccountRules.normalizeUsername(s.username),
                phoneNumber = null, // demandé juste après, à l'écran « Ton numéro » (ADR-029)
                firstName = AccountRules.normalizeFirstName(s.firstName),
                lastName = AccountRules.normalizeLastName(s.lastName),
                birthday = s.birthday,
            )
            pending.save(registration)
            try {
                val account = accounts.register(registration)
                pending.clear()
                _state.update { it.copy(loading = false, session = Session.Ready(account)) }
            } catch (e: ApiException) {
                _state.update { routeRegistrationError(it.copy(loading = false), e) }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    // Renvoie l'utilisateur à l'étape du champ refusé, pour qu'il le corrige.
    private fun routeRegistrationError(state: SignUpState, e: ApiException): SignUpState = when {
        e.isConflict && e.message.contains("pseudo", ignoreCase = true) ->
            state.copy(step = SignUpStep.USERNAME, editingUsername = true, usernameStatus = UsernameStatus.Unavailable(e.message))
        e.isConflict && e.message.contains("email", ignoreCase = true) && !state.resuming ->
            state.copy(step = SignUpStep.EMAIL, emailError = e.message)
        e.message.contains("ans", ignoreCase = true) || e.message.contains("naissance", ignoreCase = true) ->
            state.copy(step = SignUpStep.BIRTHDAY, birthdayError = e.message)
        else -> state.copy(error = e.message)
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is UserFacingException -> message!!
    is ApiException -> message
    else -> "Une erreur inattendue est survenue"
}

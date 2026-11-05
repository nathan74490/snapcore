package com.snapcore.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.data.api.analyticsReason
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.NoAnalytics
import com.snapcore.app.domain.AccountRules
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.Countries
import com.snapcore.app.domain.Country
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

// --- Inscription en 5 étapes, comme Snapchat (ADR-029, ADR-034) ---------------------------------
// 1 Prénom, 2 Date de naissance, 3 Pseudo, 4 Numéro de téléphone, 5 Code reçu par SMS. Le compte Firebase
// naît quand le code est vérifié, puis le compte SnapCore (ADR-015). En reprise (compte Firebase sans compte
// SnapCore), seules les trois premières étapes sont demandées.

object SignUpStep {
    const val NAME = 1
    const val BIRTHDAY = 2
    const val USERNAME = 3
    const val PHONE = 4
    const val CODE = 5

    /** Nom de l'étape dans l'analytics (ADR-008). */
    fun analyticsName(step: Int) = when (step) {
        NAME -> "name"
        BIRTHDAY -> "birthday"
        USERNAME -> "username"
        PHONE -> "phone"
        CODE -> "code"
        else -> "unknown"
    }
}

sealed interface UsernameStatus {
    data object Checking : UsernameStatus
    data object Available : UsernameStatus
    data class Unavailable(val reason: String) : UsernameStatus
    /** Vérification impossible (réseau) : on laisse continuer, l'inscription tranchera. */
    data object Unverified : UsernameStatus
}

data class SignUpState(
    val step: Int = SignUpStep.NAME,
    /** Compte Firebase déjà créé (inscription reprise) : pas de numéro ni de code à saisir. */
    val resuming: Boolean = false,
    val firstName: String = "",
    val firstNameError: String? = null,
    val birthday: LocalDate? = null,
    val birthdayError: String? = null,
    val username: String = "",
    /** false : pseudo proposé d'office (« Ton pseudo est… ») ; true : champ de saisie. */
    val editingUsername: Boolean = false,
    val usernameStatus: UsernameStatus = UsernameStatus.Checking,
    val phone: PhoneEntry,
    val error: String? = null,
    val loading: Boolean = false,
    val session: Session? = null,
) {
    val steps get() = if (resuming) SignUpStep.USERNAME else SignUpStep.CODE
    val isLastStep get() = step == steps

    /** Bouton grisé tant que l'étape n'est pas remplie, comme dans Snapchat. */
    val canContinue: Boolean
        get() = !loading && when (step) {
            SignUpStep.NAME -> firstName.isNotBlank()
            SignUpStep.BIRTHDAY -> birthday != null
            SignUpStep.USERNAME -> usernameStatus == UsernameStatus.Available || usernameStatus == UsernameStatus.Unverified
            SignUpStep.PHONE -> phone.numberComplete
            else -> phone.codeComplete
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
    private val analytics: Analytics = NoAnalytics,
    countryCode: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SignUpState(resuming = resuming, phone = PhoneEntry(Countries.byCode(countryCode))))
    val state: StateFlow<SignUpState> = _state.asStateFlow()

    // Toute modification passe par ici : un changement d'étape est capturé au moment même où il a lieu,
    // y compris deux étapes enchaînées sans pause (un observateur du StateFlow ne verrait que la dernière).
    private fun update(transform: (SignUpState) -> SignUpState) {
        val before = _state.value.step
        _state.update(transform)
        val after = _state.value.step
        if (after != before) captureStep(after)
    }

    init {
        captureStep(_state.value.step)
    }

    /** Entonnoir de l'inscription (ADR-008) : chaque étape affichée, y compris après un retour en arrière. */
    private fun captureStep(step: Int) =
        analytics.capture(AnalyticsEvents.SIGNUP_STEP_VIEWED, mapOf("step" to SignUpStep.analyticsName(step)))

    private fun captureFailure(e: Throwable) = analytics.capture(AnalyticsEvents.SIGNUP_FAILED,
        mapOf("step" to SignUpStep.analyticsName(_state.value.step), "reason" to e.analyticsReason()))

    private var usernameCheck: Job? = null

    fun onFirstName(value: String) = update { it.copy(firstName = value, firstNameError = null, error = null) }
    fun onBirthday(value: LocalDate) = update { it.copy(birthday = value, birthdayError = null, error = null) }

    // En minuscules dès la saisie : c'est ainsi que le backend l'enregistre (ADR-014).
    fun onUsername(raw: String) {
        val value = raw.lowercase().filterNot(Char::isWhitespace)
        update { it.copy(username = value, error = null) }
        checkUsername(value, delayMs = checkDelayMs)
    }

    fun editUsername() = update { it.copy(editingUsername = true) }

    /** Bouton « actualiser » du champ : un autre pseudo proposé d'office. */
    fun suggestAnother() = proposeUsername()

    // --- Numéro et code ----------------------------------------------------------------------

    fun onCountry(country: Country) = update { it.copy(phone = it.phone.withCountry(country), error = null) }
    fun onNational(value: String) = update { it.copy(phone = it.phone.withNational(value), error = null) }

    /** Six chiffres saisis : le code est vérifié tout de suite, comme dans Snapchat. */
    fun onCode(value: String) {
        update { it.copy(phone = it.phone.withCode(value), error = null) }
        if (_state.value.phone.codeComplete && !_state.value.loading) verifyCode()
    }

    /** Lien « Renvoyer le code ». */
    fun resendCode() = sendCode()

    /** « Utiliser ce code » sur le SMS simulé : comme la saisie automatique d'un vrai SMS. */
    fun useSimulatedCode() = _state.value.phone.simulatedCode?.let(::onCode)

    /** @return false si on était à la première étape (l'écran se ferme, ou la reprise est abandonnée). */
    fun back(): Boolean {
        if (_state.value.step == SignUpStep.NAME) {
            if (_state.value.resuming) {
                auth.signOut()
                update { it.copy(session = Session.SignedOut) }
            }
            return false
        }
        update { it.copy(step = it.step - 1, error = null, loading = false) }
        return true
    }

    fun next() {
        val s = _state.value
        if (!s.canContinue) return
        when (s.step) {
            SignUpStep.NAME -> {
                val error = AccountRules.firstNameError(s.firstName)
                if (error != null) update { it.copy(firstNameError = error) } else update { it.copy(step = SignUpStep.BIRTHDAY) }
            }
            SignUpStep.BIRTHDAY -> {
                val error = AccountRules.birthdayError(s.birthday, today())
                if (error != null) {
                    update { it.copy(birthdayError = error) }
                } else {
                    update { it.copy(step = SignUpStep.USERNAME) }
                    // Comme Snapchat : un pseudo est proposé d'office, à partir du prénom.
                    if (s.username.isEmpty()) proposeUsername()
                }
            }
            SignUpStep.USERNAME -> if (s.isLastStep) register() else update { it.copy(step = SignUpStep.PHONE) }
            SignUpStep.PHONE -> sendCode()
            else -> verifyCode()
        }
    }

    private fun sendCode() {
        val number = _state.value.phone.international
        AccountRules.phoneError(number)?.let { e -> update { it.copy(phone = it.phone.copy(numberError = e)) }; return }
        update { it.copy(loading = true, error = null, phone = it.phone.copy(numberError = null, codeError = null)) }
        viewModelScope.launch {
            try {
                val sent = auth.requestPhoneCode(number)
                update { it.copy(loading = false, step = SignUpStep.CODE, phone = it.phone.codeSent(sent)) }
            } catch (e: Throwable) {
                captureFailure(e)
                // Erreur sur le numéro à l'étape du numéro ; sur un renvoi, elle s'affiche sous le code.
                update {
                    if (it.step == SignUpStep.CODE) it.copy(loading = false, phone = it.phone.copy(codeError = e.userMessage()))
                    else it.copy(loading = false, phone = it.phone.copy(numberError = e.userMessage()))
                }
            }
        }
    }

    private fun verifyCode() {
        val s = _state.value
        update { it.copy(loading = true, error = null, phone = it.phone.copy(codeError = null)) }
        viewModelScope.launch {
            try {
                auth.signInWithPhoneCode(s.phone.sentTo ?: s.phone.international, s.phone.code)
            } catch (e: Throwable) {
                captureFailure(e)
                update { it.copy(loading = false, phone = it.phone.copy(code = "", codeError = e.userMessage())) }
                return@launch
            }
            // Ce numéro a peut-être déjà un compte : on se connecte simplement, sans en créer un second.
            try {
                val existing = accounts.me()
                update { it.copy(loading = false, session = Session.Ready(existing)) }
                return@launch
            } catch (e: ApiException) {
                if (!e.isNotFound) {
                    captureFailure(e)
                    update { it.copy(loading = false, error = e.message) }
                    return@launch
                }
            } catch (e: Throwable) {
                captureFailure(e)
                update { it.copy(loading = false, error = e.userMessage()) }
                return@launch
            }
            register()
        }
    }

    // Jusqu'à 5 propositions, chacune vérifiée auprès du backend ; si aucune n'est libre, on passe à la saisie.
    private fun proposeUsername() {
        usernameCheck?.cancel()
        val s = _state.value
        usernameCheck = viewModelScope.launch {
            repeat(5) {
                val candidate = suggestUsername(s.firstName, null, random)
                update { it.copy(username = candidate, usernameStatus = UsernameStatus.Checking) }
                val status = verify(candidate)
                update { it.copy(usernameStatus = status) }
                if (status !is UsernameStatus.Unavailable) return@launch
            }
            update { it.copy(editingUsername = true) }
        }
    }

    private fun checkUsername(value: String, delayMs: Long) {
        usernameCheck?.cancel()
        // La forme se vérifie sur place, sans appel : seul un pseudo bien formé interroge le backend.
        AccountRules.usernameError(value)?.let { error ->
            update { it.copy(usernameStatus = UsernameStatus.Unavailable(error)) }
            return
        }
        update { it.copy(usernameStatus = UsernameStatus.Checking) }
        usernameCheck = viewModelScope.launch {
            delay(delayMs) // on attend que la saisie se pose
            val status = verify(value)
            update { if (it.username == value) it.copy(usernameStatus = status) else it }
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

    // Inscription explicite côté backend, avec reprise possible au prochain lancement (ADR-015). Le numéro
    // vérifié est lu par le backend dans le compte Firebase : il n'est pas envoyé ici.
    private fun register() {
        val s = _state.value
        update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val registration = PendingRegistration(
                username = AccountRules.normalizeUsername(s.username),
                phoneNumber = null,
                firstName = AccountRules.normalizeFirstName(s.firstName),
                lastName = null,
                birthday = s.birthday,
            )
            pending.save(registration)
            try {
                val account = accounts.register(registration)
                pending.clear()
                update { it.copy(loading = false, session = Session.Ready(account)) }
            } catch (e: ApiException) {
                captureFailure(e)
                update { routeRegistrationError(it.copy(loading = false), e) }
            } catch (e: Throwable) {
                captureFailure(e)
                update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    // Renvoie l'utilisateur à l'étape du champ refusé, pour qu'il le corrige.
    private fun routeRegistrationError(state: SignUpState, e: ApiException): SignUpState = when {
        e.isConflict && e.message.contains("pseudo", ignoreCase = true) ->
            state.copy(step = SignUpStep.USERNAME, editingUsername = true, usernameStatus = UsernameStatus.Unavailable(e.message))
        e.message.contains("ans", ignoreCase = true) || e.message.contains("naissance", ignoreCase = true) ->
            state.copy(step = SignUpStep.BIRTHDAY, birthdayError = e.message)
        else -> state.copy(error = e.message)
    }
}

internal fun Throwable.userMessage(): String = when (this) {
    is UserFacingException -> message!!
    is ApiException -> message
    is java.io.IOException -> "Pas de connexion internet"
    else -> "Une erreur inattendue est survenue"
}

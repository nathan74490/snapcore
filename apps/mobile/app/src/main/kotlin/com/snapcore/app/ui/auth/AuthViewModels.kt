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
import com.snapcore.app.domain.SessionResolver
import com.snapcore.app.domain.UserFacingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun Throwable.userMessage(): String = when (this) {
    is UserFacingException -> message!!
    is ApiException -> message
    else -> "Une erreur inattendue est survenue"
}

// --- Démarrage : choisit l'écran initial ----------------------------------------------------

sealed interface StartState {
    data object Loading : StartState
    data class Resolved(val session: Session) : StartState
    data class Failed(val message: String) : StartState
}

class StartViewModel(private val resolver: SessionResolver) : ViewModel() {
    private val _state = MutableStateFlow<StartState>(StartState.Loading)
    val state: StateFlow<StartState> = _state.asStateFlow()

    init {
        resolve()
    }

    fun resolve() {
        _state.value = StartState.Loading
        viewModelScope.launch {
            _state.value = runCatching { StartState.Resolved(resolver.resolve()) }
                .getOrElse { StartState.Failed(it.userMessage()) }
        }
    }
}

// --- Connexion --------------------------------------------------------------------------------

data class LoginState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val session: Session? = null,
)

class LoginViewModel(private val auth: AuthService, private val resolver: SessionResolver) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (current.email.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Renseignez votre email et votre mot de passe") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                auth.signIn(current.email, current.password)
                resolver.resolve()
            }.onSuccess { session -> _state.update { it.copy(loading = false, session = session) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }
}

// --- Inscription en 3 étapes (ADR-010) --------------------------------------------------------

data class SignUpState(
    val step: Int = 1,
    val username: String = "",
    val email: String = "",
    val phone: String = "",
    val password: String = "",
    val usernameError: String? = null,
    val emailError: String? = null,
    val phoneError: String? = null,
    val passwordError: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    /** Le compte Firebase existe déjà : l'email ne peut plus changer, seul l'appel au backend est rejoué. */
    val firebaseAccountCreated: Boolean = false,
    val session: Session? = null,
) {
    companion object {
        const val STEPS = 3
    }
}

class SignUpViewModel(
    private val auth: AuthService,
    private val accounts: AccountService,
    private val pending: PendingRegistrationStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SignUpState())
    val state: StateFlow<SignUpState> = _state.asStateFlow()

    fun onUsername(value: String) = _state.update { it.copy(username = value, usernameError = null, error = null) }
    fun onEmail(value: String) = _state.update { it.copy(email = value, emailError = null, error = null) }
    fun onPhone(value: String) = _state.update { it.copy(phone = value, phoneError = null, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, passwordError = null, error = null) }

    fun back() = _state.update { if (it.step > 1) it.copy(step = it.step - 1, error = null) else it }

    fun next() {
        val s = _state.value
        when (s.step) {
            1 -> AccountRules.usernameError(s.username)
                ?.let { e -> _state.update { it.copy(usernameError = e) } }
                ?: _state.update { it.copy(step = 2) }
            2 -> {
                val emailError = AccountRules.emailError(s.email)
                val phoneError = AccountRules.phoneError(s.phone)
                if (emailError != null || phoneError != null) {
                    _state.update { it.copy(emailError = emailError, phoneError = phoneError) }
                } else {
                    _state.update { it.copy(step = 3) }
                }
            }
            else -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (!s.firebaseAccountCreated) {
            AccountRules.passwordError(s.password)?.let { e -> _state.update { it.copy(passwordError = e) }; return }
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // 1. Compte Firebase (une seule fois, même si l'appel au backend doit être rejoué).
            if (!s.firebaseAccountCreated) {
                try {
                    auth.signUp(s.email, s.password)
                    _state.update { it.copy(firebaseAccountCreated = true) }
                } catch (e: Throwable) {
                    _state.update { it.copy(loading = false, step = 2, emailError = e.userMessage()) }
                    return@launch
                }
            }
            // 2. Inscription explicite côté backend, avec reprise possible au prochain lancement.
            val registration = PendingRegistration(
                username = AccountRules.normalizeUsername(s.username),
                phoneNumber = s.phone.takeIf { it.isNotBlank() }?.let(AccountRules::normalizePhone),
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

    // Renvoie l'utilisateur à l'étape du champ en conflit (409), pour qu'il le corrige.
    private fun routeRegistrationError(state: SignUpState, e: ApiException): SignUpState = when {
        e.isConflict && e.message.contains("pseudo", ignoreCase = true) -> state.copy(step = 1, usernameError = e.message)
        e.isConflict && e.message.contains("téléphone", ignoreCase = true) -> state.copy(step = 2, phoneError = e.message)
        e.isConflict && e.message.contains("email", ignoreCase = true) -> state.copy(step = 2, emailError = e.message)
        else -> state.copy(error = e.message)
    }
}

// --- Fin d'inscription : compte Firebase sans compte SnapCore (ADR-015) ------------------------

data class CompleteRegistrationState(
    val username: String = "",
    val phone: String = "",
    val usernameError: String? = null,
    val phoneError: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val session: Session? = null,
)

class CompleteRegistrationViewModel(
    private val auth: AuthService,
    private val accounts: AccountService,
    private val pending: PendingRegistrationStore,
) : ViewModel() {
    private val _state = MutableStateFlow(CompleteRegistrationState())
    val state: StateFlow<CompleteRegistrationState> = _state.asStateFlow()

    val email get() = auth.currentEmail

    fun onUsername(value: String) = _state.update { it.copy(username = value, usernameError = null, error = null) }
    fun onPhone(value: String) = _state.update { it.copy(phone = value, phoneError = null, error = null) }

    fun submit() {
        val s = _state.value
        val usernameError = AccountRules.usernameError(s.username)
        val phoneError = AccountRules.phoneError(s.phone)
        if (usernameError != null || phoneError != null) {
            _state.update { it.copy(usernameError = usernameError, phoneError = phoneError) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val account = accounts.register(
                    PendingRegistration(
                        AccountRules.normalizeUsername(s.username),
                        s.phone.takeIf { it.isNotBlank() }?.let(AccountRules::normalizePhone),
                    ),
                )
                pending.clear()
                _state.update { it.copy(loading = false, session = Session.Ready(account)) }
            } catch (e: ApiException) {
                _state.update {
                    when {
                        e.isConflict && e.message.contains("pseudo", true) -> it.copy(loading = false, usernameError = e.message)
                        e.isConflict && e.message.contains("téléphone", true) -> it.copy(loading = false, phoneError = e.message)
                        else -> it.copy(loading = false, error = e.message)
                    }
                }
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _state.update { it.copy(session = Session.SignedOut) }
    }
}

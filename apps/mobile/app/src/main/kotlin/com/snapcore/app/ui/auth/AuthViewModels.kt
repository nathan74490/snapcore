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
import com.snapcore.app.domain.SessionResolver
import com.snapcore.app.domain.UserFacingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


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

// --- Connexion ---------------------------------------------------------------------------------

/** Numéro, puis code reçu par SMS (ADR-034) ; email et mot de passe pour les comptes créés avant. */
enum class LoginMode { PHONE, CODE, EMAIL }

data class LoginState(
    val mode: LoginMode = LoginMode.PHONE,
    val phone: PhoneEntry,
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val session: Session? = null,
) {
    val canSubmit get() = !loading && when (mode) {
        LoginMode.PHONE -> phone.numberComplete
        LoginMode.CODE -> phone.codeComplete
        LoginMode.EMAIL -> email.isNotBlank() && password.isNotBlank()
    }
}

class LoginViewModel(
    private val auth: AuthService,
    private val resolver: SessionResolver,
    private val analytics: Analytics = NoAnalytics,
    countryCode: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState(phone = PhoneEntry(Countries.byCode(countryCode))))
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onCountry(country: Country) = _state.update { it.copy(phone = it.phone.withCountry(country), error = null) }
    fun onNational(value: String) = _state.update { it.copy(phone = it.phone.withNational(value), error = null) }

    /** Six chiffres saisis : le code est vérifié tout de suite. */
    fun onCode(value: String) {
        _state.update { it.copy(phone = it.phone.withCode(value), error = null) }
        if (_state.value.phone.codeComplete && !_state.value.loading) submit()
    }

    fun useSimulatedCode() = _state.value.phone.simulatedCode?.let(::onCode)
    fun resendCode() = sendCode()

    /** Lien « Se connecter avec un email » (anciens comptes) ou retour au numéro. */
    fun useEmail(email: Boolean) = _state.update { it.copy(mode = if (email) LoginMode.EMAIL else LoginMode.PHONE, error = null) }

    /** @return false s'il faut quitter l'écran (on était déjà à la première étape). */
    fun back(): Boolean {
        if (_state.value.mode == LoginMode.PHONE) return false
        _state.update { it.copy(mode = LoginMode.PHONE, error = null, loading = false) }
        return true
    }

    fun submit() {
        val current = _state.value
        when (current.mode) {
            LoginMode.PHONE -> sendCode()
            LoginMode.CODE -> signIn { auth.signInWithPhoneCode(current.phone.sentTo ?: current.phone.international, current.phone.code) }
            LoginMode.EMAIL -> {
                if (current.email.isBlank() || current.password.isBlank()) {
                    _state.update { it.copy(error = "Renseigne ton email et ton mot de passe") }
                    return
                }
                signIn { auth.signIn(current.email, current.password) }
            }
        }
    }

    private fun sendCode() {
        val number = _state.value.phone.international
        AccountRules.phoneError(number)?.let { e -> _state.update { it.copy(phone = it.phone.copy(numberError = e)) }; return }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val sent = auth.requestPhoneCode(number)
                _state.update { it.copy(loading = false, mode = LoginMode.CODE, phone = it.phone.codeSent(sent)) }
            } catch (e: Throwable) {
                _state.update {
                    if (it.mode == LoginMode.CODE) it.copy(loading = false, phone = it.phone.copy(codeError = e.userMessage()))
                    else it.copy(loading = false, phone = it.phone.copy(numberError = e.userMessage()))
                }
            }
        }
    }

    private fun signIn(block: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                block()
                resolver.resolve()
            }.onSuccess { session ->
                // Envoyé avant identify (fait par la navigation) : PostHog rattache alors l'id anonyme au compte.
                analytics.capture(AnalyticsEvents.LOGIN_COMPLETED, mapOf("method" to if (_state.value.mode == LoginMode.EMAIL) "email" else "phone"))
                _state.update { it.copy(loading = false, session = session) }
            }.onFailure { e ->
                analytics.capture(AnalyticsEvents.LOGIN_FAILED, mapOf("reason" to e.analyticsReason()))
                _state.update {
                    // Mauvais code : l'erreur s'affiche sous les cases, qu'on vide pour une nouvelle saisie.
                    if (it.mode == LoginMode.CODE) it.copy(loading = false, phone = it.phone.copy(code = "", codeError = e.userMessage()))
                    else it.copy(loading = false, error = e.userMessage())
                }
            }
        }
    }
}

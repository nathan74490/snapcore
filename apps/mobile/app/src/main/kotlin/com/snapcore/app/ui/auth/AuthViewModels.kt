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

class LoginViewModel(
    private val auth: AuthService,
    private val resolver: SessionResolver,
    private val analytics: Analytics = NoAnalytics,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (current.email.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Renseigne ton email et ton mot de passe") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                auth.signIn(current.email, current.password)
                resolver.resolve()
            }.onSuccess { session ->
                // Envoyé avant identify (fait par la navigation) : PostHog rattache alors l'id anonyme au compte.
                analytics.capture(AnalyticsEvents.LOGIN_COMPLETED)
                _state.update { it.copy(loading = false, session = session) }
            }.onFailure { e ->
                analytics.capture(AnalyticsEvents.LOGIN_FAILED, mapOf("reason" to e.analyticsReason()))
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }
}


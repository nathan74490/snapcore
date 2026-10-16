package com.snapcore.app.ui.plus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.NoAnalytics
import com.snapcore.app.domain.SubscriptionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaywallState(
    val account: Account? = null,
    val loading: Boolean = true,
    /** Demande du lien de paiement en cours. */
    val opening: Boolean = false,
    /** Lien à ouvrir dans le navigateur, consommé par l'écran. */
    val checkoutUrl: String? = null,
    /** Paiement lancé : l'abonnement est attendu (webhook RevenueCat → backend). */
    val awaitingActivation: Boolean = false,
    val error: String? = null,
)

// Paywall SnapCore+ (ADR-025) : le paiement se fait sur la page hébergée par RevenueCat ; l'app relit
// ensuite son compte jusqu'à ce que le webhook ait activé l'abonnement côté backend.
class PaywallViewModel(
    private val accounts: AccountService,
    private val subscriptions: SubscriptionService,
    private val pollDelayMs: Long = 2_000,
    private val pollAttempts: Int = 15,
    private val analytics: Analytics = NoAnalytics,
) : ViewModel() {
    private val _state = MutableStateFlow(PaywallState())
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    private var polling: Job? = null

    init {
        viewModelScope.launch {
            runCatching { accounts.me() }
                .onSuccess { account -> _state.update { it.copy(account = account, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun subscribe() {
        if (_state.value.opening) return
        _state.update { it.copy(opening = true, error = null) }
        viewModelScope.launch {
            runCatching { subscriptions.checkoutUrl() }
                .onSuccess { url ->
                    analytics.capture("checkout_started")
                    _state.update { it.copy(opening = false, checkoutUrl = url, awaitingActivation = true) }
                }
                .onFailure { e -> _state.update { it.copy(opening = false, error = e.message) } }
        }
    }

    fun consumeCheckoutUrl() = _state.update { it.copy(checkoutUrl = null) }

    /**
     * Retour dans l'app après le paiement (lien profond ou simple retour) : relit le compte jusqu'à voir
     * l'abonnement actif, la livraison du webhook pouvant prendre quelques secondes.
     */
    fun checkActivation() {
        if (!_state.value.awaitingActivation || polling?.isActive == true) return
        polling = viewModelScope.launch {
            repeat(pollAttempts) { attempt ->
                val account = runCatching { accounts.me() }.getOrNull()
                if (account != null) _state.update { it.copy(account = account) }
                if (account?.isPremium == true) {
                    _state.update { it.copy(awaitingActivation = false) }
                    return@launch
                }
                if (attempt < pollAttempts - 1) delay(pollDelayMs)
            }
            _state.update {
                it.copy(
                    awaitingActivation = false,
                    error = "Paiement pas encore confirmé. Si tu as payé, SnapCore+ s'activera dans quelques instants.",
                )
            }
        }
    }

    /** Ouverture depuis le lien de retour du paiement : on attend l'activation même sans clic ici. */
    fun returnedFromCheckout() {
        _state.update { it.copy(awaitingActivation = true) }
        checkActivation()
    }
}

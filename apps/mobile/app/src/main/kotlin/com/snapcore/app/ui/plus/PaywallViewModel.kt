package com.snapcore.app.ui.plus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.AnalyticsEvents
import com.snapcore.app.domain.AccountService
import com.snapcore.app.domain.Analytics
import com.snapcore.app.domain.Experiments
import com.snapcore.app.domain.NoExperiments
import com.snapcore.app.domain.PaywallCta
import com.snapcore.app.domain.experimentProperty
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
    /** Variante du bouton d'abonnement (expérience A/B, ADR-030) ; le témoin tant qu'elle n'est pas tirée. */
    val cta: PaywallCta = PaywallCta.CONTROL,
)

// Paywall SnapCore+ (ADR-025) : le paiement se fait sur la page hébergée par RevenueCat ; l'app relit
// ensuite son compte jusqu'à ce que le webhook ait activé l'abonnement côté backend.
class PaywallViewModel(
    private val accounts: AccountService,
    private val subscriptions: SubscriptionService,
    private val pollDelayMs: Long = 2_000,
    private val pollAttempts: Int = 15,
    private val analytics: Analytics = NoAnalytics,
    /** Compte déjà connu de l'app : l'offre s'affiche tout de suite, le rechargement la met à jour. */
    initial: Account? = null,
    private val experiments: Experiments = NoExperiments,
) : ViewModel() {
    private val _state = MutableStateFlow(PaywallState(account = initial, loading = initial == null))
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    private var polling: Job? = null

    /** Variante tirée pour cet affichage du paywall ; null tant que l'utilisateur n'y a pas été exposé. */
    private var exposedVariant: String? = null
    private var exposed = false

    init {
        initial?.let(::exposeIfOffered)
        viewModelScope.launch {
            runCatching { accounts.me() }
                .onSuccess { account ->
                    _state.update { it.copy(account = account, loading = false) }
                    exposeIfOffered(account)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    // Seul un compte non abonné voit le bouton d'abonnement : c'est lui, et lui seul, qui entre dans
    // l'expérience. Une fois par affichage du paywall, pour que la variante ne change pas sous ses yeux.
    private fun exposeIfOffered(account: Account) {
        if (exposed || account.isPremium) return
        exposed = true
        exposedVariant = experiments.variant(PaywallCta.FLAG)
        _state.update { it.copy(cta = PaywallCta.of(exposedVariant)) }
    }

    fun subscribe() {
        if (_state.value.opening) return
        _state.update { it.copy(opening = true, error = null) }
        viewModelScope.launch {
            runCatching { subscriptions.checkoutUrl() }
                .onSuccess { url ->
                    // La variante vue accompagne la conversion, pour que PostHog l'attribue à la bonne branche.
                    analytics.capture(AnalyticsEvents.CHECKOUT_STARTED, exposedVariant?.let { mapOf(experimentProperty(PaywallCta.FLAG) to it) }.orEmpty())
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
                    analytics.capture(AnalyticsEvents.CHECKOUT_RETURNED, mapOf("activated" to true))
                    _state.update { it.copy(awaitingActivation = false) }
                    return@launch
                }
                if (attempt < pollAttempts - 1) delay(pollDelayMs)
            }
            analytics.capture(AnalyticsEvents.CHECKOUT_RETURNED, mapOf("activated" to false))
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

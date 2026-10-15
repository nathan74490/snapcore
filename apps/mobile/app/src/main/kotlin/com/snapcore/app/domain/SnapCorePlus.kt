package com.snapcore.app.domain

import java.time.Instant

/**
 * Offre SnapCore+ affichée sur le paywall (ADR-025). Le prix réellement débité est celui du produit
 * configuré dans RevenueCat ; ce libellé doit rester aligné avec lui.
 */
object SnapCorePlus {
    const val PRICE_LABEL = "3,99 € / mois"

    // Avantages réellement appliqués par le backend (ADR-009, ADR-017).
    val BENEFITS = listOf(
        "Revois les snaps reçus autant de fois que tu veux",
        "Les snaps reçus restent disponibles tant que tu es abonné",
        "Le badge SnapCore+ sur ton profil",
    )
}

interface SubscriptionService {
    /** Lien de paiement hébergé (Web Purchase Link RevenueCat), propre au compte connecté. */
    suspend fun checkoutUrl(): String
}

/** Même règle que le backend : abonné tant que la période payée n'est pas terminée (ADR-009). */
fun isPlusActive(subscriptionTier: String, premiumUntil: Instant?, now: Instant): Boolean =
    subscriptionTier == "premium" && premiumUntil != null && premiumUntil.isAfter(now)

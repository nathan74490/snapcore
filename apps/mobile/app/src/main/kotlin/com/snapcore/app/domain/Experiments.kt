package com.snapcore.app.domain

/**
 * A/B testing (ADR-030) : chaque expérience est un feature flag PostHog à plusieurs variantes, attribuées
 * par PostHog au compte (même identifiant que l'analytics, ADR-008), donc stables d'un téléphone à l'autre.
 */
interface Experiments {
    /**
     * Variante attribuée pour [flag], ou null si elle est inconnue (pas de clé PostHog, flags pas encore
     * chargés, expérience arrêtée). Chaque appel enregistre l'**exposition** de l'utilisateur : à n'appeler
     * qu'au moment où la variante devient visible à l'écran.
     */
    fun variant(flag: String): String?
}

/** Sans clé PostHog (et dans les tests) : tout le monde voit la variante témoin. */
object NoExperiments : Experiments {
    override fun variant(flag: String): String? = null
}

/**
 * Propriété ajoutée aux événements de conversion pour que PostHog les rattache à la variante vue
 * (convention PostHog `$feature/<flag>`).
 */
fun experimentProperty(flag: String) = "\$feature/$flag"

// --- Expérience n° 1 : libellé du bouton du paywall SnapCore+ -----------------------------------

/**
 * Le bouton d'abonnement met-il en avant le prix (témoin) ou ce qu'on y gagne ? Mesure principale :
 * `checkout_started` (paiement lancé) ; secondaire : `snapcoreplus_started` (abonnement payé, côté
 * backend). Le reste du paywall est identique dans les deux variantes, prix compris.
 */
enum class PaywallCta(val variant: String, val label: String) {
    CONTROL("control", "S'abonner pour ${SnapCorePlus.PRICE_LABEL}"),
    BENEFIT("benefit", "Revoir mes snaps à volonté");

    companion object {
        const val FLAG = "paywall-cta"

        /** Variante inconnue ou absente : le témoin, pour ne jamais casser l'écran. */
        fun of(variant: String?) = entries.firstOrNull { it.variant == variant } ?: CONTROL
    }
}

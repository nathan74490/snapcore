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

/**
 * Lit la variante de [flag] (ce qui enregistre l'exposition) et la traduit avec [of] ; la variante brute
 * est gardée pour la poser sur les conversions ([conversionProperties]).
 */
class Exposure<T>(val flag: String, val raw: String?, val value: T) {
    /** `$feature/<flag>` → variante vue, à ajouter aux événements de conversion envoyés par l'app. */
    val conversionProperties: Map<String, Any> get() = raw?.let { mapOf(experimentProperty(flag) to it) }.orEmpty()
}

fun <T> Experiments.expose(flag: String, of: (String?) -> T): Exposure<T> = variant(flag).let { Exposure(flag, it, of(it)) }

// --- Expérience n° 2 : écran « Trouve tous tes amis » des premiers pas (ADR-029) -----------------

/**
 * Le texte de la demande d'accès aux contacts explique-t-il le fonctionnement (témoin) ou met-il en avant
 * les amis déjà présents ? Mesure principale : `contacts_permission_answered` avec `granted = true`.
 */
enum class OnboardingContactsCopy(val variant: String, val title: String, val text: String, val button: String) {
    CONTROL("control", "Trouve tous tes amis",
        "Autorise l'accès à tes contacts : SnapCore te montrera ceux qui ont déjà un compte, pour les ajouter d'un appui. " +
            "Seuls les numéros servent à la recherche, rien n'est conservé.",
        "Continuer"),
    SOCIAL("social", "Tes amis t'attendent",
        "Certains de tes contacts sont peut-être déjà sur SnapCore : retrouve-les et ajoute-les d'un appui. " +
            "Seuls les numéros servent à la recherche, rien n'est conservé.",
        "Retrouver mes amis");

    companion object {
        const val FLAG = "onboarding-contacts-copy"
        fun of(variant: String?) = entries.firstOrNull { it.variant == variant } ?: CONTROL
    }
}

// --- Expérience n° 3 : bouton de l'aperçu d'un snap ----------------------------------------------

/**
 * « Envoyer à » (témoin) ou « Envoyer » : un libellé plus court pousse-t-il à envoyer plutôt qu'à jeter ?
 * Mesure principale : `snap_preview_confirmed` ; secondaire : `snap_discarded` (à la baisse).
 */
enum class PreviewSendLabel(val variant: String, val label: String) {
    CONTROL("control", "Envoyer à"),
    SHORT("short", "Envoyer");

    companion object {
        const val FLAG = "preview-send-label"
        fun of(variant: String?) = entries.firstOrNull { it.variant == variant } ?: CONTROL
    }
}

// --- Expérience n° 4 : bandeau du mode fantôme de la Snap Map (ADR-031) --------------------------

/**
 * Bouton du bandeau « Tu es en mode fantôme » : « Partager ma position » (témoin) ou un libellé centré sur
 * les amis. Mesure principale : `location_sharing_changed` (backend) vers un mode autre que GHOST. Le
 * réglage reste à choisir dans la même fenêtre de confirmation : seul le libellé change.
 */
enum class MapGhostBanner(val variant: String, val action: String) {
    CONTROL("control", "Partager ma position"),
    FRIENDS("friends", "Me montrer à mes amis");

    companion object {
        const val FLAG = "map-ghost-banner"
        fun of(variant: String?) = entries.firstOrNull { it.variant == variant } ?: CONTROL
    }
}

// --- Expérience n° 5 : carte SnapCore+ du profil (ADR-025) ---------------------------------------

/**
 * Sous-titre de la carte SnapCore+ d'un compte gratuit : les avantages seuls (témoin) ou avec le prix.
 * Mesure principale : `paywall_opened` depuis le profil ; secondaire : `checkout_started`.
 */
enum class ProfilePlusCard(val variant: String, val subtitle: String) {
    CONTROL("control", "Des relectures illimitées et des snaps conservés."),
    PRICE("price", "Relectures illimitées et snaps conservés, pour ${SnapCorePlus.PRICE_LABEL}.");

    companion object {
        const val FLAG = "profile-plus-card"
        fun of(variant: String?) = entries.firstOrNull { it.variant == variant } ?: CONTROL
    }
}

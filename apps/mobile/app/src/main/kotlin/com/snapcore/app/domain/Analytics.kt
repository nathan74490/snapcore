package com.snapcore.app.domain

/**
 * Usage de l'app (ADR-008) : écrans visités et actions, complétés côté serveur par les événements qui
 * n'existent que là (abonnement, ami accepté…). L'identifiant est l'id interne du compte, le même que
 * celui du backend : aucune donnée personnelle (email, pseudo) n'est envoyée.
 */
interface Analytics {
    /** Rattache les événements suivants au compte connecté. */
    fun identify(userId: String)

    /** Déconnexion : les événements suivants ne sont plus rattachés au compte. */
    fun reset()

    fun screen(name: String)

    fun capture(event: String, properties: Map<String, Any> = emptyMap())
}

/** Sans clé PostHog (et dans les tests) : rien n'est envoyé. */
object NoAnalytics : Analytics {
    override fun identify(userId: String) = Unit
    override fun reset() = Unit
    override fun screen(name: String) = Unit
    override fun capture(event: String, properties: Map<String, Any>) = Unit
}

/**
 * Nom d'écran lisible pour une route de navigation typée (« com.snapcore.app.ui.PreviewRoute/{path}/{kind} »
 * → « Preview ») ; null pour l'accueil, dont l'onglet affiché (Chat, Caméra, Stories) est l'écran réel.
 */
fun screenNameOf(route: String?): String? {
    val name = route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')?.removeSuffix("Route")
    return name?.takeIf { it.isNotEmpty() && it != "Home" }
}

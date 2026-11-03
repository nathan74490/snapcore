package com.snapcore.app.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Interface optimiste : une action s'affiche dès l'appui, puis le backend confirme ou on revient en arrière.
 *
 * Le garde suit les actions en cours d'un écran pour qu'un rechargement ne réaffiche pas l'état d'avant :
 * une réponse lancée avant ou pendant une action ne s'applique que si aucune action n'a commencé ni fini
 * depuis. Toujours utilisé depuis le thread principal (viewModelScope), sans synchronisation.
 */
class OptimisticGuard {
    private var inFlight = 0
    private var version = 0L

    val idle get() = inFlight == 0

    fun begin() {
        inFlight++
        version++
    }

    fun end() {
        inFlight--
        version++
    }

    /** À relever au lancement d'un rechargement, puis à passer à [canApply] à son arrivée. */
    fun mark(): Long = version

    fun canApply(mark: Long) = inFlight == 0 && version == mark
}

/**
 * Appel réseau d'une action optimiste. Il va au bout même si l'écran se ferme entre-temps : l'utilisateur
 * a déjà vu son action réussir, la quitter ne doit pas l'annuler à mi-chemin.
 */
suspend fun <T> completeEvenIfClosed(block: suspend () -> T): Result<T> =
    withContext(NonCancellable) { runCatching { block() } }

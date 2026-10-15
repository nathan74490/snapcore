package com.snapcore.app.domain

import com.snapcore.app.data.api.ApiException

sealed interface Session {
    data object SignedOut : Session
    /** Compte Firebase sans compte SnapCore, et aucune inscription locale à reprendre. */
    data object NeedsRegistration : Session
    data class Ready(val account: Account) : Session
}

// Décide de l'écran de départ (ADR-015). La ligne User n'est JAMAIS créée implicitement par le
// backend : si elle manque, seule une inscription explicite (reprise depuis les données locales,
// ou ressaisie) peut la créer.
class SessionResolver(
    private val auth: AuthService,
    private val accounts: AccountService,
    private val pending: PendingRegistrationStore,
) {
    suspend fun resolve(): Session {
        if (!auth.isSignedIn) return Session.SignedOut
        return try {
            Session.Ready(accounts.me())
        } catch (e: ApiException) {
            when {
                // Session Firebase invalide ou compte Firebase supprimé : on repart de zéro.
                e.isUnauthorized -> signedOut()
                e.isNotFound -> resumeRegistration()
                else -> throw e
            }
        }
    }

    private suspend fun resumeRegistration(): Session {
        val saved = pending.load() ?: return Session.NeedsRegistration
        return try {
            val account = accounts.register(saved)
            pending.clear()
            Session.Ready(account)
        } catch (e: ApiException) {
            // Compte en cours de suppression (404) : ne jamais le recréer, on déconnecte.
            if (e.isNotFound || e.isUnauthorized) signedOut() else Session.NeedsRegistration
        }
    }

    private suspend fun signedOut(): Session {
        pending.clear()
        auth.signOut()
        return Session.SignedOut
    }
}

package com.snapcore.app.domain

// Ports utilisés par les ViewModels : Firebase, l'API et le stockage local restent derrière ces
// interfaces, ce qui permet de tester les écrans sans réseau (MVVM, ADR-001).

interface AuthService {
    val isSignedIn: Boolean
    val currentEmail: String?

    /** Crée le compte Firebase puis envoie l'email de vérification, sans bloquer (ADR-010). */
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    fun signOut()

    /** Relit l'état du compte chez Firebase (la vérification d'email se fait hors de l'app). */
    suspend fun isEmailVerified(): Boolean
    suspend fun resendVerificationEmail()
}

interface AccountService {
    /** @throws com.snapcore.app.data.api.ApiException 404 si aucun compte SnapCore n'existe pour ce login. */
    suspend fun me(): Account
    suspend fun register(registration: PendingRegistration): Account
    suspend fun updateProfile(username: String?, phoneNumber: String?): Account
    suspend fun deleteAccount()
}

interface PendingRegistrationStore {
    suspend fun load(): PendingRegistration?
    suspend fun save(registration: PendingRegistration)
    suspend fun clear()
}

// Message d'erreur lisible, quelle que soit l'origine (Firebase, API, réseau).
class UserFacingException(message: String) : Exception(message)

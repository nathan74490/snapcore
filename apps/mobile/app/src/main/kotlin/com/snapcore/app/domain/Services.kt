package com.snapcore.app.domain

import java.io.File

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
    suspend fun updateProfile(username: String?, phoneNumber: String?, firstName: String? = null): Account
    suspend fun deleteAccount()
    /** Confirme un upload PROFILE_PHOTO déjà envoyé vers R2 (ADR-016). */
    suspend fun setProfilePhoto(uploadId: String): Account
    suspend fun removeProfilePhoto(): Account
    /** Avatar maison (ADR-028). */
    suspend fun avatarCatalog(): AvatarCatalog
    suspend fun setAvatar(options: Map<String, String>): Account
    suspend fun removeAvatar(): Account
}

/**
 * Prépare la photo de profil choisie : recadrage carré centré et redimensionnement en 512×512 sur
 * le téléphone, avant tout envoi (ADR-014). `source` est l'URI renvoyée par le sélecteur de photos.
 */
interface ProfilePhotoPreparer {
    suspend fun prepare(source: String): File
}

interface PendingRegistrationStore {
    suspend fun load(): PendingRegistration?
    suspend fun save(registration: PendingRegistration)
    suspend fun clear()
}

// Message d'erreur lisible, quelle que soit l'origine (Firebase, API, réseau).
class UserFacingException(message: String) : Exception(message)

/** Carré centré à découper dans une image de w × h pixels (recadrage de la photo de profil). */
data class SquareCrop(val left: Int, val top: Int, val size: Int)

fun centeredSquare(width: Int, height: Int): SquareCrop {
    val size = minOf(width, height)
    return SquareCrop(left = (width - size) / 2, top = (height - size) / 2, size = size)
}

/** Taille fixe de la photo de profil envoyée (ADR-014). */
const val PROFILE_PHOTO_SIZE = 512

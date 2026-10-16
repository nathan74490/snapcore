package com.snapcore.app.domain

import java.time.Instant

data class Account(
    val id: String,
    val username: String,
    val email: String?,
    val phoneNumber: String?,
    val profilePhotoUrl: String?,
    val isPremium: Boolean,
    /** Fin de la période SnapCore+ payée (ADR-025) ; null si jamais abonné. */
    val premiumUntil: Instant? = null,
    /** Absent pour les comptes créés avant l'ajout du prénom. */
    val firstName: String? = null,
    /** Options de l'avatar maison (ADR-028), null sans avatar. */
    val avatar: Map<String, String>? = null,
    val avatarUrl: String? = null,
) {
    val displayName get() = firstName ?: username
    /** Même priorité que pour un ami : avatar, photo, puis initiales. */
    val pictureUrl get() = avatarUrl ?: profilePhotoUrl
}

/** Choix proposés par l'éditeur d'avatar, servis par le backend (ADR-028). */
data class AvatarCatalog(val defaults: Map<String, String>, val options: Map<String, List<String>>)

// Données saisies à l'inscription, conservées localement jusqu'à ce que POST /auth/register
// réussisse : si l'app est fermée entre la création du compte Firebase et cet appel, elle le
// rejoue au lancement suivant (ADR-015).
data class PendingRegistration(val username: String, val phoneNumber: String?, val firstName: String = "")

// Mêmes règles que le backend (apps/backend/src/modules/identity/domain) : la validation côté
// app n'est qu'un confort, le backend reste la référence.
object AccountRules {
    private val USERNAME = Regex("^[a-z][a-z0-9._-]{2,14}$")
    private val PHONE = Regex("^\\+?[0-9]{6,15}$")
    // Contrôle de forme volontairement simple : c'est Firebase qui valide réellement l'adresse.
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    const val MIN_PASSWORD_LENGTH = 6 // minimum imposé par Firebase Authentication

    fun normalizeUsername(raw: String) = raw.trim().lowercase()

    fun usernameError(raw: String): String? =
        if (USERNAME.matches(normalizeUsername(raw))) null
        else "3 à 15 caractères, commence par une lettre (lettres, chiffres, . _ -)"

    // Même règle que le backend (identity/domain/first-name.ts).
    private val FIRST_NAME = Regex("""^(?=.*\p{L})[\p{L} '’-]{1,30}$""")

    fun normalizeFirstName(raw: String) = raw.trim().replace(Regex("""\s+"""), " ")

    fun firstNameError(raw: String): String? =
        if (FIRST_NAME.matches(normalizeFirstName(raw))) null else "1 à 30 caractères : lettres, espaces, apostrophes ou tirets"

    fun normalizePhone(raw: String) = raw.replace(Regex("[\\s.\\-()]"), "")

    fun phoneError(raw: String): String? =
        if (raw.isBlank() || PHONE.matches(normalizePhone(raw))) null else "Numéro de téléphone invalide"

    fun emailError(raw: String): String? = if (EMAIL.matches(raw.trim())) null else "Adresse email invalide"

    fun passwordError(raw: String): String? =
        if (raw.length >= MIN_PASSWORD_LENGTH) null else "Au moins $MIN_PASSWORD_LENGTH caractères"
}

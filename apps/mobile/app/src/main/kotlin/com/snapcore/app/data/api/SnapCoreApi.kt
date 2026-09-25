package com.snapcore.app.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

// Contrat de l'API REST du backend (apps/backend/README.md). Chaque appel porte l'ID token
// Firebase de l'utilisateur, ajouté par FirebaseTokenInterceptor (ADR-015).
interface SnapCoreApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): UserDto

    @GET("me")
    suspend fun me(): UserDto

    @PATCH("me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserDto

    @POST("me/email/sync")
    suspend fun syncEmail(): UserDto

    @DELETE("me")
    suspend fun deleteAccount()
}

// Miroir de `User` dans packages/types (le compte vu par lui-même).
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val subscriptionTier: String,
    val profilePhotoUrl: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
    val premiumUntil: String? = null,
)

@Serializable
data class RegisterRequest(val username: String, val phoneNumber: String? = null)

// Champs absents = inchangés ; `phoneNumber = null` explicite n'est pas utilisé en V1.
@Serializable
data class UpdateProfileRequest(val username: String? = null, val phoneNumber: String? = null)

// Corps d'erreur renvoyé par DomainExceptionFilter côté backend.
@Serializable
data class ApiErrorBody(val statusCode: Int, val error: String? = null, val message: String? = null)

package com.snapcore.app.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    // --- Médias (ADR-016) ---
    @POST("media/uploads")
    suspend fun requestUpload(@Body body: UploadRequest): UploadInstructions

    // --- Snaps et stories (ADR-009, ADR-013) ---
    @POST("conversations/{friendId}/messages/snap")
    suspend fun sendSnap(@Path("friendId") friendId: String, @Body body: SendSnapRequest): MessageDto

    @POST("stories")
    suspend fun publishStory(@Body body: PublishStoryRequest): OwnStoryItemDto

    // --- Conversations (ADR-009) ---
    @GET("conversations")
    suspend fun conversations(): List<ConversationDto>

    @GET("conversations/{friendId}/messages")
    suspend fun messages(@Path("friendId") friendId: String, @Query("before") before: String? = null): List<ChatMessageDto>

    @POST("conversations/{friendId}/messages/text")
    suspend fun sendText(@Path("friendId") friendId: String, @Body body: SendTextRequest): ChatMessageDto

    @POST("conversations/{friendId}/read")
    suspend fun markRead(@Path("friendId") friendId: String)

    @POST("messages/{messageId}/view")
    suspend fun viewSnap(@Path("messageId") messageId: String): SnapViewUrlDto

    // --- Amis (ADR-012) ---
    @GET("friendships")
    suspend fun friends(): List<FriendshipItemDto>

    @GET("friendships/requests/incoming")
    suspend fun incomingRequests(): List<FriendshipItemDto>

    @GET("friendships/requests/outgoing")
    suspend fun outgoingRequests(): List<FriendshipItemDto>

    @GET("users/search")
    suspend fun searchUser(@Query("username") username: String): UserSearchResultDto

    @POST("friendships/requests")
    suspend fun sendFriendRequest(@Body body: FriendRequestBody): FriendshipItemDto

    @POST("friendships/requests/{id}/accept")
    suspend fun acceptFriendRequest(@Path("id") friendshipId: String): FriendshipItemDto

    @POST("friendships/requests/{id}/decline")
    suspend fun declineFriendRequest(@Path("id") friendshipId: String)
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

@Serializable
data class UploadRequest(val purpose: String, val contentType: String, val contentLength: Long)

// En-têtes signés dans l'URL : à renvoyer tels quels lors du PUT, sinon R2 refuse l'envoi (403).
@Serializable
data class UploadInstructions(
    val uploadId: String,
    val uploadUrl: String,
    val method: String,
    val headers: Map<String, String>,
    val expiresAt: String,
)

@Serializable
data class SendSnapRequest(val uploadId: String, val contentType: String)

@Serializable
data class PublishStoryRequest(val uploadId: String, val contentType: String)

@Serializable
data class MessageDto(val id: String, val contentType: String, val createdAt: String)

@Serializable
data class OwnStoryItemDto(val id: String, val contentType: String, val createdAt: String, val expiresAt: String, val viewCount: Int)

@Serializable
data class PublicProfileDto(val id: String, val username: String, val profilePhotoUrl: String? = null)

@Serializable
data class FriendshipItemDto(
    val friendshipId: String,
    val status: String,
    val requestedAt: String,
    val respondedAt: String? = null,
    val user: PublicProfileDto,
)

@Serializable
data class UserSearchResultDto(val id: String, val username: String, val profilePhotoUrl: String? = null, val relation: String)

@Serializable
data class FriendRequestBody(val username: String)

// Miroir de MessageView (packages/types) : la clé R2 n'est jamais exposée, seulement mediaPurged.
@Serializable
data class ChatMessageDto(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val contentType: String,
    val content: String? = null,
    val createdAt: String,
    val openedAt: String? = null,
    val mediaPurged: Boolean = false,
)

@Serializable
data class ConversationDto(val friend: PublicProfileDto, val lastMessage: ChatMessageDto? = null)

@Serializable
data class SendTextRequest(val content: String)

@Serializable
data class SnapViewUrlDto(val url: String, val expiresAt: String)

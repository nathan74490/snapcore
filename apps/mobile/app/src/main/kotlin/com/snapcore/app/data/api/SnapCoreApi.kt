package com.snapcore.app.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Contrat de l'API REST du backend (apps/backend/README.md). Chaque appel porte l'ID token
// Firebase de l'utilisateur, ajouté par FirebaseTokenInterceptor (ADR-015).
interface SnapCoreApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): UserDto

    // Connexion par code SMS (ADR-034) : routes publiques, appelées avant toute session.
    @POST("auth/phone/code")
    suspend fun sendPhoneCode(@Body body: PhoneCodeRequest): PhoneCodeSentDto

    @POST("auth/phone/verify")
    suspend fun verifyPhoneCode(@Body body: PhoneCodeVerification): PhoneSignInDto

    @GET("me")
    suspend fun me(): UserDto

    /** Lien de paiement SnapCore+ propre au compte (ADR-025). */
    @POST("subscription/checkout")
    suspend fun checkout(): CheckoutDto

    @PATCH("me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserDto

    @POST("me/email/sync")
    suspend fun syncEmail(): UserDto

    @DELETE("me")
    suspend fun deleteAccount()

    // Confirme un upload PROFILE_PHOTO (ADR-014, ADR-016).
    @PUT("me/photo")
    suspend fun setProfilePhoto(@Body body: ConfirmUploadRequest): UserDto

    @DELETE("me/photo")
    suspend fun removeProfilePhoto(): UserDto

    // --- Avatar maison (ADR-028) ---
    @GET("avatars/catalog")
    suspend fun avatarCatalog(): AvatarCatalogDto

    @PUT("me/avatar")
    suspend fun setAvatar(@Body body: SetAvatarRequest): UserDto

    @DELETE("me/avatar")
    suspend fun removeAvatar()

    // --- Snap Map (ADR-031) ---
    @PUT("me/location")
    suspend fun updateLocation(@Body body: LocationDto)

    @GET("me/location-sharing")
    suspend fun locationSharing(): LocationSharingDto

    @PUT("me/location-sharing")
    suspend fun updateLocationSharing(@Body body: LocationSharingDto): LocationSharingDto

    @GET("map/friends")
    suspend fun friendLocations(): List<FriendLocationDto>

    @GET("map/stories")
    suspend fun mapStories(): List<MapStoryDto>

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

    @POST("conversations/{friendId}/typing")
    suspend fun sendTyping(@Path("friendId") friendId: String)

    @POST("messages/{messageId}/view")
    suspend fun viewSnap(@Path("messageId") messageId: String): SnapViewUrlDto

    // --- Stories (ADR-013) ---
    @GET("stories")
    suspend fun storyFeed(): List<StoryFeedEntryDto>

    @GET("stories/me")
    suspend fun myStory(): List<OwnStoryItemDto>

    @POST("stories/{storyId}/view")
    suspend fun viewStory(@Path("storyId") storyId: String): SnapViewUrlDto

    @GET("stories/{storyId}/viewers")
    suspend fun storyViewers(@Path("storyId") storyId: String): List<PublicProfileDto>

    @DELETE("stories/{storyId}")
    suspend fun deleteStory(@Path("storyId") storyId: String)

    // --- Appareils (ADR-018) ---
    @POST("devices")
    suspend fun registerDevice(@Body body: DeviceTokenRequest)

    @DELETE("devices/{token}")
    suspend fun unregisterDevice(@Path("token") token: String)

    // --- Amis (ADR-012) ---
    @GET("friendships")
    suspend fun friends(): List<FriendshipItemDto>

    @GET("friendships/requests/incoming")
    suspend fun incomingRequests(): List<FriendshipItemDto>

    @GET("friendships/requests/outgoing")
    suspend fun outgoingRequests(): List<FriendshipItemDto>

    @GET("users/username-availability")
    suspend fun usernameAvailability(@Query("username") username: String): UsernameAvailabilityDto

    @POST("users/contacts/match")
    suspend fun matchContacts(@Body body: ContactsMatchRequest): List<ContactMatchDto>

    @GET("users/search")
    suspend fun searchUser(@Query("username") username: String): UserSearchResultDto

    @POST("friendships/requests")
    suspend fun sendFriendRequest(@Body body: FriendRequestBody): FriendshipItemDto

    @POST("friendships/requests/{id}/accept")
    suspend fun acceptFriendRequest(@Path("id") friendshipId: String): FriendshipItemDto

    @POST("friendships/requests/{id}/decline")
    suspend fun declineFriendRequest(@Path("id") friendshipId: String)

    // --- Sécurité : retirer, bloquer (ADR-012) et signaler (ADR-021) ---
    @DELETE("friendships/{userId}")
    suspend fun removeFriend(@Path("userId") userId: String)

    @POST("blocks/{userId}")
    suspend fun block(@Path("userId") userId: String)

    @DELETE("blocks/{userId}")
    suspend fun unblock(@Path("userId") userId: String)

    @GET("friendships/blocked")
    suspend fun blocked(): List<FriendshipItemDto>

    @POST("reports")
    suspend fun report(@Body body: ReportRequest)

    // --- Groupes (ADR-032) ---
    @GET("groups")
    suspend fun groups(): List<GroupSummaryDto>

    @POST("groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): GroupDto

    @GET("groups/{groupId}")
    suspend fun group(@Path("groupId") groupId: String): GroupDto

    @PATCH("groups/{groupId}")
    suspend fun renameGroup(@Path("groupId") groupId: String, @Body body: RenameGroupRequest): GroupDto

    @POST("groups/{groupId}/members")
    suspend fun addGroupMembers(@Path("groupId") groupId: String, @Body body: AddGroupMembersRequest): GroupDto

    @DELETE("groups/{groupId}/members/me")
    suspend fun leaveGroup(@Path("groupId") groupId: String)

    @GET("groups/{groupId}/messages")
    suspend fun groupMessages(@Path("groupId") groupId: String, @Query("before") before: String? = null): List<GroupMessageDto>

    @POST("groups/{groupId}/messages/text")
    suspend fun sendGroupText(@Path("groupId") groupId: String, @Body body: SendTextRequest): GroupMessageDto

    @POST("groups/{groupId}/messages/snap")
    suspend fun sendGroupSnap(@Path("groupId") groupId: String, @Body body: SendSnapRequest): GroupMessageDto

    @POST("groups/{groupId}/read")
    suspend fun markGroupRead(@Path("groupId") groupId: String)

    @POST("groups/{groupId}/typing")
    suspend fun sendGroupTyping(@Path("groupId") groupId: String)

    @POST("group-messages/{messageId}/view")
    suspend fun viewGroupSnap(@Path("messageId") messageId: String): SnapViewUrlDto
}

// Miroirs de GroupView, GroupMessageView et GroupSummary (packages/types, ADR-032).
@Serializable
data class GroupDto(
    val id: String,
    val name: String? = null,
    val members: List<PublicProfileDto>,
    val createdAt: String,
    val formerMembers: List<PublicProfileDto> = emptyList(),
)

@Serializable
data class GroupMessageDto(
    val id: String,
    val groupId: String,
    val senderId: String,
    val contentType: String,
    val content: String? = null,
    val createdAt: String,
    val mediaPurged: Boolean = false,
    val openedByMe: Boolean = false,
    val seenBy: List<String> = emptyList(),
)

@Serializable
data class GroupSummaryDto(
    val group: GroupDto,
    val lastMessage: GroupMessageDto? = null,
    val unopenedSnap: GroupMessageDto? = null,
    val unread: Boolean = false,
)

// Nom absent = groupe sans nom (une valeur par défaut nulle n'est pas sérialisée).
@Serializable
data class CreateGroupRequest(val memberIds: List<String>, val name: String? = null)

// Pas de valeur par défaut : le nom part toujours, vide pour retirer le nom.
@Serializable
data class RenameGroupRequest(val name: String)

@Serializable
data class AddGroupMembersRequest(val userIds: List<String>)

@Serializable
data class ReportRequest(val targetType: String, val targetId: String, val reason: String)

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
    val firstName: String? = null,
    val avatar: Map<String, String>? = null,
    val lastName: String? = null,
    /** YYYY-MM-DD. */
    val birthday: String? = null,
)

@Serializable
data class AvatarCatalogDto(val defaults: Map<String, String>, val options: Map<String, List<String>>)

@Serializable
data class SetAvatarRequest(val options: Map<String, String>)

@Serializable
data class CheckoutDto(val url: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val firstName: String,
    val phoneNumber: String? = null,
    val lastName: String? = null,
    /** YYYY-MM-DD (ADR-029). */
    val birthday: String? = null,
)

@Serializable
data class UsernameAvailabilityDto(val available: Boolean, val reason: String? = null)

@Serializable
data class PhoneCodeRequest(val phoneNumber: String)

@Serializable
data class PhoneCodeSentDto(val phoneNumber: String, val expiresInSeconds: Int, val resendInSeconds: Int, val simulatedCode: String? = null)

@Serializable
data class PhoneCodeVerification(val phoneNumber: String, val code: String)

@Serializable
data class PhoneSignInDto(val customToken: String)

@Serializable
data class ContactsMatchRequest(val phoneNumbers: List<String>)

@Serializable
data class ContactMatchDto(val phoneNumber: String, val user: PublicProfileDto, val relation: String)

// Champs absents = inchangés ; `phoneNumber = null` explicite n'est pas utilisé en V1.
@Serializable
data class UpdateProfileRequest(val username: String? = null, val phoneNumber: String? = null, val firstName: String? = null, val lastName: String? = null)

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
data class PublishStoryRequest(
    val uploadId: String,
    val contentType: String,
    val thumbnailUploadId: String? = null,
    // Position de publication, pour la Snap Map (ADR-031) : les deux ou aucune.
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class MessageDto(val id: String, val contentType: String, val createdAt: String)

@Serializable
data class OwnStoryItemDto(val id: String, val contentType: String, val createdAt: String, val expiresAt: String, val viewCount: Int, val thumbnailUrl: String? = null)

@Serializable
data class PublicProfileDto(val id: String, val username: String, val profilePhotoUrl: String? = null, val firstName: String? = null, val avatar: Map<String, String>? = null)

@Serializable
data class FriendshipItemDto(
    val friendshipId: String,
    val status: String,
    val requestedAt: String,
    val respondedAt: String? = null,
    val user: PublicProfileDto,
)

@Serializable
data class UserSearchResultDto(val id: String, val username: String, val profilePhotoUrl: String? = null, val relation: String, val firstName: String? = null, val avatar: Map<String, String>? = null)

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
data class ConversationDto(val friend: PublicProfileDto, val lastMessage: ChatMessageDto? = null, val unopenedSnap: ChatMessageDto? = null)

@Serializable
data class SendTextRequest(val content: String)

@Serializable
data class SnapViewUrlDto(val url: String, val expiresAt: String)

@Serializable
data class StoryItemDto(val id: String, val contentType: String, val createdAt: String, val expiresAt: String, val viewed: Boolean)

@Serializable
data class StoryFeedEntryDto(val author: PublicProfileDto, val items: List<StoryItemDto>, val hasUnviewed: Boolean, val thumbnailUrl: String? = null)

@Serializable
data class DeviceTokenRequest(val token: String)

@Serializable
data class ConfirmUploadRequest(val uploadId: String)

// --- Snap Map (ADR-031) ---
@Serializable
data class LocationDto(val latitude: Double, val longitude: Double)

@Serializable
// Pas de valeur par défaut : la liste doit partir même vide (le backend l'exige, et une valeur par défaut
// n'est pas sérialisée).
data class LocationSharingDto(val mode: String, val friendIds: List<String>)

@Serializable
data class FriendLocationDto(val user: PublicProfileDto, val latitude: Double, val longitude: Double, val updatedAt: String)

@Serializable
data class MapStoryDto(
    val id: String,
    val author: PublicProfileDto,
    val contentType: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: String,
    val expiresAt: String,
    val thumbnailUrl: String? = null,
    val viewed: Boolean = false,
)

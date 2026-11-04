package com.snapcore.app.data

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.data.api.ContactsMatchRequest
import com.snapcore.app.data.api.FriendRequestBody
import com.snapcore.app.data.api.FriendshipItemDto
import com.snapcore.app.data.api.PublicProfileDto
import com.snapcore.app.data.api.PublishStoryRequest
import com.snapcore.app.data.api.ReportRequest
import com.snapcore.app.data.api.SendSnapRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.UploadRequest
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.ContactMatch
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.ReportReason
import com.snapcore.app.domain.ReportTarget
import com.snapcore.app.domain.SafetyService
import com.snapcore.app.domain.GeoPoint
import com.snapcore.app.domain.FriendRequest
import com.snapcore.app.domain.FriendsService
import com.snapcore.app.domain.MediaUploader
import com.snapcore.app.domain.Relation
import com.snapcore.app.domain.SnapService
import com.snapcore.app.domain.UserSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

// Upload direct vers R2 (ADR-016). Le PUT passe par un client HTTP SANS l'intercepteur Firebase :
// un en-tête Authorization en plus de la signature de l'URL serait refusé par R2.
class R2MediaUploader(private val api: SnapCoreApi, private val storageClient: OkHttpClient) : MediaUploader {
    override suspend fun upload(file: File, contentType: String, purpose: String): String {
        val instructions = apiCall { api.requestUpload(UploadRequest(purpose, contentType, file.length())) }
        withContext(Dispatchers.IO) {
            // Content-Type et Content-Length doivent être exactement ceux signés : OkHttp déduit la
            // longueur du fichier et n'ajoute pas de charset pour un type binaire.
            val request = Request.Builder()
                .url(instructions.uploadUrl)
                .put(file.asRequestBody(contentType.toMediaType()))
                .build()
            try {
                storageClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ApiException(response.code, "Envoi du fichier refusé (${response.code})")
                }
            } catch (e: IOException) {
                throw ApiException(0, "Envoi du fichier interrompu. Réessaie.")
            }
        }
        return instructions.uploadId
    }
}

class ApiSnapService(private val api: SnapCoreApi) : SnapService {
    override suspend fun sendSnap(friendId: String, uploadId: String, messageContentType: String) {
        apiCall { api.sendSnap(friendId, SendSnapRequest(uploadId, messageContentType)) }
    }

    override suspend fun publishStory(uploadId: String, storyContentType: String, thumbnailUploadId: String?, location: GeoPoint?) {
        apiCall { api.publishStory(PublishStoryRequest(uploadId, storyContentType, thumbnailUploadId, location?.latitude, location?.longitude)) }
    }
}

class ApiFriendsService(private val api: SnapCoreApi) : FriendsService {
    override suspend fun friends() = apiCall { api.friends() }.map { it.user.toFriend() }
    override suspend fun incomingRequests() = apiCall { api.incomingRequests() }.map(::toRequest)
    override suspend fun outgoingRequests() = apiCall { api.outgoingRequests() }.map(::toRequest)

    override suspend fun search(username: String): UserSearchResult {
        val result = apiCall { api.searchUser(username) }
        return UserSearchResult(
            Friend(result.id, result.username, result.profilePhotoUrl, result.firstName, result.avatar?.let(::avatarImageUrl)),
            runCatching { Relation.valueOf(result.relation) }.getOrDefault(Relation.NONE),
        )
    }

    override suspend fun sendRequest(username: String) {
        apiCall { api.sendFriendRequest(FriendRequestBody(username)) }
    }

    override suspend fun accept(friendshipId: String) {
        apiCall { api.acceptFriendRequest(friendshipId) }
    }

    override suspend fun decline(friendshipId: String) = apiCall { api.declineFriendRequest(friendshipId) }

    // Le backend borne la requête à 2000 numéros : un grand carnet d'adresses part en plusieurs fois.
    override suspend fun matchContacts(phoneNumbers: List<String>): List<ContactMatch> =
        phoneNumbers.distinct().chunked(2000).flatMap { chunk ->
            apiCall { api.matchContacts(ContactsMatchRequest(chunk)) }.map {
                ContactMatch(it.phoneNumber, it.user.toFriend(), runCatching { Relation.valueOf(it.relation) }.getOrDefault(Relation.NONE))
            }
        }

    private fun toRequest(item: FriendshipItemDto) = FriendRequest(item.friendshipId, item.user.toFriend())
}

class ApiSafetyService(private val api: SnapCoreApi) : SafetyService {
    override suspend fun removeFriend(userId: String) = apiCall { api.removeFriend(userId) }
    override suspend fun block(userId: String) = apiCall { api.block(userId) }
    override suspend fun unblock(userId: String) = apiCall { api.unblock(userId) }
    override suspend fun blocked() = apiCall { api.blocked() }.map { it.user.toFriend() }

    override suspend fun report(target: ReportTarget, reason: ReportReason) {
        val type = when (target) {
            is ReportTarget.User -> "USER"
            is ReportTarget.Message -> "MESSAGE"
            is ReportTarget.StoryItem -> "STORY_ITEM"
        }
        apiCall { api.report(ReportRequest(type, target.id, reason.label)) }
    }
}

internal fun PublicProfileDto.toFriend() = Friend(id, username, profilePhotoUrl, firstName, avatar?.let(::avatarImageUrl))

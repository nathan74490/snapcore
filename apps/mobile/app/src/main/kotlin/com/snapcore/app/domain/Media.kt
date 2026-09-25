package com.snapcore.app.domain

import java.io.File

// Type de média capturé, et sa correspondance avec les contrats du backend (ADR-009, ADR-013, ADR-016).
enum class MediaKind(
    val contentType: String,
    val snapUploadPurpose: String,
    val storyUploadPurpose: String,
    val messageContentType: String,
    val storyContentType: String,
) {
    PHOTO("image/jpeg", "SNAP_PHOTO", "STORY_PHOTO", "SNAP_PHOTO", "PHOTO"),
    VIDEO("video/mp4", "SNAP_VIDEO", "STORY_VIDEO", "SNAP_PHOTO_VIDEO", "VIDEO"),
}

/** Média capturé, stocké dans le cache privé de l'app, jamais dans la galerie (ADR-011). */
data class CapturedMedia(val file: File, val kind: MediaKind)

data class Friend(val id: String, val username: String, val photoUrl: String?)

sealed interface SendTarget {
    data class ToFriend(val friend: Friend) : SendTarget
    data object ToStory : SendTarget
}

data class SendReport(val succeeded: Set<SendTarget>, val failed: Map<SendTarget, String>) {
    val allSucceeded get() = failed.isEmpty()
}

interface MediaUploader {
    /** Demande une URL pré-signée puis envoie le fichier vers R2. @return l'uploadId à confirmer. */
    suspend fun upload(file: File, contentType: String, purpose: String): String
}

interface SnapService {
    suspend fun sendSnap(friendId: String, uploadId: String, messageContentType: String)
    suspend fun publishStory(uploadId: String, storyContentType: String)
}

// Envoie un média capturé à des amis et/ou à sa story. Un upload par cible : le backend copie
// chaque upload confirmé vers sa propre clé (messages/<id>, stories/<id>).
class MediaPublisher(private val uploader: MediaUploader, private val snaps: SnapService) {
    suspend fun publish(media: CapturedMedia, targets: Set<SendTarget>): SendReport {
        val succeeded = mutableSetOf<SendTarget>()
        val failed = mutableMapOf<SendTarget, String>()
        for (target in targets) {
            try {
                when (target) {
                    is SendTarget.ToFriend -> {
                        val uploadId = uploader.upload(media.file, media.kind.contentType, media.kind.snapUploadPurpose)
                        snaps.sendSnap(target.friend.id, uploadId, media.kind.messageContentType)
                    }
                    SendTarget.ToStory -> {
                        val uploadId = uploader.upload(media.file, media.kind.contentType, media.kind.storyUploadPurpose)
                        snaps.publishStory(uploadId, media.kind.storyContentType)
                    }
                }
                succeeded += target
            } catch (e: Exception) {
                failed[target] = e.message ?: "Échec de l'envoi"
            }
        }
        // Fichier local supprimé seulement une fois TOUT confirmé ; sinon conservé pour réessayer (ADR-011).
        if (failed.isEmpty()) media.file.delete()
        return SendReport(succeeded, failed)
    }
}

interface FriendsService {
    suspend fun friends(): List<Friend>
    suspend fun incomingRequests(): List<FriendRequest>
    suspend fun outgoingRequests(): List<FriendRequest>
    suspend fun search(username: String): UserSearchResult
    suspend fun sendRequest(username: String)
    suspend fun accept(friendshipId: String)
    suspend fun decline(friendshipId: String)
}

data class FriendRequest(val friendshipId: String, val user: Friend)

enum class Relation { NONE, OUTGOING_REQUEST, INCOMING_REQUEST, FRIENDS }

data class UserSearchResult(val user: Friend, val relation: Relation)

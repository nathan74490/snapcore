package com.snapcore.app.domain

import java.io.File
import kotlin.coroutines.cancellation.CancellationException

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

/** Le prénom est affiché partout ; le pseudo reste l'identifiant (recherche, ajout d'ami). */
data class Friend(
    val id: String,
    val username: String,
    val photoUrl: String?,
    val firstName: String? = null,
    /** Rendu PNG de son avatar (ADR-028), null s'il n'en a pas créé. */
    val avatarUrl: String? = null,
) {
    val displayName get() = firstName ?: username
    /** Image affichée pour lui : son avatar, sinon sa photo de profil ; null = ses initiales. */
    val pictureUrl get() = avatarUrl ?: photoUrl
}

sealed interface SendTarget {
    data class ToFriend(val friend: Friend) : SendTarget
    data object ToStory : SendTarget
}

interface MediaUploader {
    /** Demande une URL pré-signée puis envoie le fichier vers R2. @return l'uploadId à confirmer. */
    suspend fun upload(file: File, contentType: String, purpose: String): String
}

interface SnapService {
    suspend fun sendSnap(friendId: String, uploadId: String, messageContentType: String)
    /** [location] : là où la story est publiée, pour l'épingler sur la carte des amis (ADR-031) ; null = nulle part. */
    suspend fun publishStory(uploadId: String, storyContentType: String, thumbnailUploadId: String? = null, location: GeoPoint? = null)
}

/** Usage d'upload de la vignette d'une story (ADR-026). */
const val STORY_THUMBNAIL_PURPOSE = "STORY_THUMBNAIL"

/** Petite image JPEG d'une story (photo réduite ou première image de la vidéo), fabriquée sur le téléphone (ADR-026). */
interface StoryThumbnailMaker {
    /** @return un fichier temporaire, ou null si le média ne s'y prête pas. */
    suspend fun make(media: CapturedMedia): File?
}

// Envoie un média capturé à un ami ou à sa story. Un upload par cible : le backend copie chaque upload
// confirmé vers sa propre clé (messages/<id>, stories/<id>). La file d'envoi (SnapOutbox) s'en sert cible
// par cible, et gère le fichier local.
// Une story publiée hors mode fantôme part avec la position du téléphone (Snap Map, ADR-031).
class MediaPublisher(
    private val uploader: MediaUploader,
    private val snaps: SnapService,
    private val thumbnails: StoryThumbnailMaker? = null,
    private val storyLocation: suspend () -> GeoPoint? = { null },
) {
    /** Envoie le média à une seule cible, sans toucher au fichier local ; lève une exception en cas d'échec. */
    suspend fun publishTo(media: CapturedMedia, target: SendTarget) {
        when (target) {
            is SendTarget.ToFriend -> {
                val uploadId = uploader.upload(media.file, media.kind.contentType, media.kind.snapUploadPurpose)
                snaps.sendSnap(target.friend.id, uploadId, media.kind.messageContentType)
            }
            SendTarget.ToStory -> {
                val uploadId = uploader.upload(media.file, media.kind.contentType, media.kind.storyUploadPurpose)
                snaps.publishStory(uploadId, media.kind.storyContentType, uploadThumbnail(media), locate())
            }
        }
    }

    // Comme la vignette, la position est un plus : sans elle, la story part quand même, hors de la carte.
    private suspend fun locate(): GeoPoint? = try {
        storyLocation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // La vignette n'est qu'un confort d'affichage : sans elle, la story part quand même (ADR-026).
    private suspend fun uploadThumbnail(media: CapturedMedia): String? {
        val file = try {
            thumbnails?.make(media)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            uploader.upload(file, "image/jpeg", STORY_THUMBNAIL_PURPOSE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
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
    /** Comptes SnapCore dont le numéro figure parmi ceux des contacts du téléphone (ADR-029). */
    suspend fun matchContacts(phoneNumbers: List<String>): List<ContactMatch>
}

data class FriendRequest(val friendshipId: String, val user: Friend)

enum class Relation { NONE, OUTGOING_REQUEST, INCOMING_REQUEST, FRIENDS }

data class UserSearchResult(val user: Friend, val relation: Relation)

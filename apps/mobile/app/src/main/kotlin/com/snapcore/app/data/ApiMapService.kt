package com.snapcore.app.data

import com.snapcore.app.data.api.LocationDto
import com.snapcore.app.data.api.LocationSharingDto
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.FriendLocation
import com.snapcore.app.domain.GeoPoint
import com.snapcore.app.domain.LocationSharing
import com.snapcore.app.domain.LocationSharingMode
import com.snapcore.app.domain.MapService
import com.snapcore.app.domain.MapStory
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.StoryItem
import java.time.Instant

/** Snap Map via l'API REST (ADR-031). */
class ApiMapService(private val api: SnapCoreApi) : MapService {
    override suspend fun friendLocations() = apiCall { api.friendLocations() }.map {
        FriendLocation(it.user.toFriend(), GeoPoint(it.latitude, it.longitude), Instant.parse(it.updatedAt))
    }

    override suspend fun stories() = apiCall { api.mapStories() }.map {
        val kind = if (it.contentType == "VIDEO") MediaKind.VIDEO else MediaKind.PHOTO
        MapStory(
            author = it.author.toFriend(),
            position = GeoPoint(it.latitude, it.longitude),
            item = StoryItem(it.id, kind, Instant.parse(it.createdAt), Instant.parse(it.expiresAt), it.viewed, thumbnailUrl = it.thumbnailUrl),
        )
    }

    override suspend fun sharing() = apiCall { api.locationSharing() }.toDomain()

    override suspend fun updateSharing(sharing: LocationSharing) =
        apiCall { api.updateLocationSharing(LocationSharingDto(sharing.mode.name, sharing.friendIds.toList())) }.toDomain()

    override suspend fun updateLocation(position: GeoPoint) = apiCall { api.updateLocation(LocationDto(position.latitude, position.longitude)) }

    // Mode inconnu (backend plus récent) : on retombe sur le mode fantôme, le plus protecteur.
    private fun LocationSharingDto.toDomain() = LocationSharing(
        mode = LocationSharingMode.entries.firstOrNull { it.name == mode } ?: LocationSharingMode.GHOST,
        friendIds = friendIds.toSet(),
    )
}

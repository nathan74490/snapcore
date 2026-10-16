package com.snapcore.app.data

import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.FriendStory
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.StoryItem
import com.snapcore.app.domain.StoryService
import java.time.Instant

class ApiStoryService(private val api: SnapCoreApi) : StoryService {
    override suspend fun feed() = apiCall { api.storyFeed() }.map { entry ->
        FriendStory(
            author = Friend(entry.author.id, entry.author.username, entry.author.profilePhotoUrl, entry.author.firstName),
            items = entry.items.map { StoryItem(it.id, kindOf(it.contentType), Instant.parse(it.createdAt), Instant.parse(it.expiresAt), it.viewed) },
            hasUnviewed = entry.hasUnviewed,
        )
    }

    override suspend fun mine() = apiCall { api.myStory() }.map {
        StoryItem(it.id, kindOf(it.contentType), Instant.parse(it.createdAt), Instant.parse(it.expiresAt), viewCount = it.viewCount)
    }

    override suspend fun viewUrl(item: StoryItem) = apiCall { api.viewStory(item.id) }.url

    override suspend fun viewers(item: StoryItem) = apiCall { api.storyViewers(item.id) }.map { Friend(it.id, it.username, it.profilePhotoUrl, it.firstName) }

    override suspend fun delete(item: StoryItem) = apiCall { api.deleteStory(item.id) }

    private fun kindOf(contentType: String) = if (contentType == "VIDEO") MediaKind.VIDEO else MediaKind.PHOTO
}

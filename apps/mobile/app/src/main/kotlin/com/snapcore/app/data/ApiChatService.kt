package com.snapcore.app.data

import com.snapcore.app.data.api.ApiException
import com.snapcore.app.data.api.ChatMessageDto
import com.snapcore.app.data.api.SendTextRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.ChatMessage
import com.snapcore.app.domain.ChatService
import com.snapcore.app.domain.Conversation
import com.snapcore.app.domain.Friend
import com.snapcore.app.domain.SnapOpenResult
import java.time.Instant

class ApiChatService(private val api: SnapCoreApi) : ChatService {
    override suspend fun conversations() = apiCall { api.conversations() }.map { dto ->
        Conversation(dto.friend.toFriend(), dto.lastMessage?.toDomain(), dto.unopenedSnap?.toDomain())
    }

    override suspend fun messages(friendId: String) = apiCall { api.messages(friendId) }.map { it.toDomain() }

    override suspend fun sendText(friendId: String, content: String) =
        apiCall { api.sendText(friendId, SendTextRequest(content)) }.toDomain()

    override suspend fun markRead(friendId: String) = apiCall { api.markRead(friendId) }

    override suspend fun sendTyping(friendId: String) = apiCall { api.sendTyping(friendId) }

    // 403 = déjà vu hors fenêtre de grâce, 410 = média purgé (ADR-009).
    override suspend fun openSnap(message: ChatMessage): SnapOpenResult =
        try {
            SnapOpenResult.Ready(apiCall { api.viewSnap(message.id) }.url, message.type)
        } catch (e: ApiException) {
            when (e.status) {
                403 -> SnapOpenResult.AlreadySeen
                410 -> SnapOpenResult.Expired
                else -> SnapOpenResult.Failed(e.message)
            }
        }
}

private fun ChatMessageDto.toDomain() = ChatMessage(
    id = id,
    senderId = senderId,
    recipientId = recipientId,
    type = runCatching { ChatContentType.valueOf(contentType) }.getOrDefault(ChatContentType.TEXT),
    content = content,
    createdAt = Instant.parse(createdAt),
    openedAt = openedAt?.let(Instant::parse),
    mediaPurged = mediaPurged,
)

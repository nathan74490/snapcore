package com.snapcore.app.data

import com.snapcore.app.data.api.AddGroupMembersRequest
import com.snapcore.app.data.api.ApiException
import com.snapcore.app.data.api.CreateGroupRequest
import com.snapcore.app.data.api.GroupDto
import com.snapcore.app.data.api.GroupMessageDto
import com.snapcore.app.data.api.RenameGroupRequest
import com.snapcore.app.data.api.SendTextRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.ChatContentType
import com.snapcore.app.domain.Group
import com.snapcore.app.domain.GroupMessage
import com.snapcore.app.domain.GroupService
import com.snapcore.app.domain.GroupSummary
import com.snapcore.app.domain.SnapOpenResult
import java.time.Instant

// Groupes de discussion (ADR-032).
class ApiGroupService(private val api: SnapCoreApi) : GroupService {
    override suspend fun groups() = apiCall { api.groups() }.map { dto ->
        GroupSummary(dto.group.toDomain(), dto.lastMessage?.toDomain(), dto.unopenedSnap?.toDomain(), dto.unread)
    }

    override suspend fun group(groupId: String) = apiCall { api.group(groupId) }.toDomain()

    override suspend fun create(name: String?, memberIds: List<String>) =
        apiCall { api.createGroup(CreateGroupRequest(memberIds, name?.trim()?.takeIf { it.isNotEmpty() })) }.toDomain()

    override suspend fun rename(groupId: String, name: String?) =
        apiCall { api.renameGroup(groupId, RenameGroupRequest(name?.trim().orEmpty())) }.toDomain()

    override suspend fun addMembers(groupId: String, userIds: List<String>) =
        apiCall { api.addGroupMembers(groupId, AddGroupMembersRequest(userIds)) }.toDomain()

    override suspend fun leave(groupId: String) = apiCall { api.leaveGroup(groupId) }

    override suspend fun messages(groupId: String) = apiCall { api.groupMessages(groupId) }.map { it.toDomain() }

    override suspend fun sendText(groupId: String, content: String) =
        apiCall { api.sendGroupText(groupId, SendTextRequest(content)) }.toDomain()

    override suspend fun markRead(groupId: String) = apiCall { api.markGroupRead(groupId) }

    override suspend fun sendTyping(groupId: String) = apiCall { api.sendGroupTyping(groupId) }

    // Mêmes réponses que pour un snap à deux : 403 = déjà vu hors fenêtre de grâce, 410 = purgé (ADR-009).
    override suspend fun openSnap(message: GroupMessage): SnapOpenResult =
        try {
            SnapOpenResult.Ready(apiCall { api.viewGroupSnap(message.id) }.url, message.type)
        } catch (e: ApiException) {
            when (e.status) {
                403 -> SnapOpenResult.AlreadySeen
                410 -> SnapOpenResult.Expired
                else -> SnapOpenResult.Failed(e.message)
            }
        }
}

internal fun GroupDto.toDomain() =
    Group(id, name?.takeIf { it.isNotBlank() }, members.map { it.toFriend() }, Instant.parse(createdAt), formerMembers.map { it.toFriend() })

private fun GroupMessageDto.toDomain() = GroupMessage(
    id = id,
    groupId = groupId,
    senderId = senderId,
    type = runCatching { ChatContentType.valueOf(contentType) }.getOrDefault(ChatContentType.TEXT),
    content = content,
    createdAt = Instant.parse(createdAt),
    mediaPurged = mediaPurged,
    openedByMe = openedByMe,
    seenBy = seenBy,
)

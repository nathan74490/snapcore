package com.snapcore.app.domain

import java.time.Instant

enum class ChatContentType { TEXT, SNAP_PHOTO, SNAP_PHOTO_VIDEO }

data class ChatMessage(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val type: ChatContentType,
    val content: String?,
    val createdAt: Instant,
    val openedAt: Instant?,
    val mediaPurged: Boolean,
    /** Chat affiché avant la réponse du backend (interface optimiste), jamais reçu de l'API. */
    val pending: Boolean = false,
) {
    val isSnap get() = type != ChatContentType.TEXT
    fun isFrom(userId: String) = senderId == userId
}

data class Conversation(val friend: Friend, val lastMessage: ChatMessage?)

// --- Icônes de statut façon Snapchat (ADR-009) --------------------------------------------------

enum class StatusShape { SQUARE, ARROW }
enum class StatusColor { RED, PURPLE, BLUE, GREY }

data class MessageStatus(val shape: StatusShape, val color: StatusColor, val filled: Boolean, val label: String)

/**
 * Rendu calculé entièrement côté app à partir du type, du sens et de `openedAt` (ADR-009) :
 * forme (carré pour un Snap, flèche pour un Chat), couleur (rouge photo, violet vidéo, bleu texte),
 * plein = pas encore ouvert, contour = ouvert. Gris = Snap dont le média a été purgé ; un Chat
 * texte n'est jamais gris.
 */
fun statusOf(message: ChatMessage, myId: String): MessageStatus {
    val shape = if (message.isSnap) StatusShape.SQUARE else StatusShape.ARROW
    val sent = message.isFrom(myId)
    val opened = message.openedAt != null

    if (message.isSnap && message.mediaPurged) {
        return MessageStatus(shape, StatusColor.GREY, filled = false, label = if (sent) "Ouvert" else "Expiré")
    }
    val color = when (message.type) {
        ChatContentType.SNAP_PHOTO -> StatusColor.RED
        ChatContentType.SNAP_PHOTO_VIDEO -> StatusColor.PURPLE
        ChatContentType.TEXT -> StatusColor.BLUE
    }
    val label = when {
        message.pending -> "Envoi…"
        sent && opened -> "Ouvert"
        sent -> "Envoyé"
        opened -> "Reçu"
        message.isSnap -> "Nouveau snap"
        else -> "Nouveau chat"
    }
    return MessageStatus(shape, color, filled = !opened, label = label)
}

/** Un Snap reçu peut être ouvert s'il n'est pas purgé ; le backend tranche la vue unique (ADR-009). */
fun canTryToOpen(message: ChatMessage, myId: String) = message.isSnap && !message.isFrom(myId) && !message.mediaPurged

// --- Service -------------------------------------------------------------------------------------

sealed interface SnapOpenResult {
    data class Ready(val url: String, val type: ChatContentType) : SnapOpenResult
    data object AlreadySeen : SnapOpenResult
    data object Expired : SnapOpenResult
    data class Failed(val message: String) : SnapOpenResult
}

interface ChatService {
    suspend fun conversations(): List<Conversation>
    /** Du plus récent au plus ancien. */
    suspend fun messages(friendId: String): List<ChatMessage>
    suspend fun sendText(friendId: String, content: String): ChatMessage
    suspend fun markRead(friendId: String)
    suspend fun openSnap(message: ChatMessage): SnapOpenResult
}

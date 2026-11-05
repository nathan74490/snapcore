package com.snapcore.app.domain

import java.time.Instant

// Groupes de discussion (ADR-032) : chats et snaps à plusieurs, sans administrateur, comme Snapchat.

/** Taille maximale d'un groupe, créateur compris (comme Snapchat). */
const val MAX_GROUP_MEMBERS = 32

/** Longueur maximale du nom d'un groupe. */
const val MAX_GROUP_NAME_LENGTH = 50

data class Group(
    val id: String,
    /** Facultatif : sans nom, l'app affiche les prénoms des autres membres. */
    val name: String?,
    val members: List<Friend>,
    val createdAt: Instant,
    /** Membres partis : leurs messages restent, avec leur nom (ADR-032). */
    val formerMembers: List<Friend> = emptyList(),
) {
    fun others(myId: String) = members.filter { it.id != myId }

    /** « Soirée samedi », ou à défaut « Léa, Hugo et Inès ». */
    fun title(myId: String): String = name?.takeIf { it.isNotBlank() } ?: memberNames(others(myId).map { it.displayName })

    fun member(userId: String) = members.firstOrNull { it.id == userId }

    /** Membre actuel ou ancien membre, pour afficher le nom de l'expéditeur d'un message. */
    fun author(userId: String) = member(userId) ?: formerMembers.firstOrNull { it.id == userId }
}

/** « Léa », « Léa et Hugo », « Léa, Hugo et Inès », « Léa, Hugo, Inès et 2 autres ». */
fun memberNames(names: List<String>): String = when (names.size) {
    0 -> "Groupe"
    1 -> names[0]
    2 -> "${names[0]} et ${names[1]}"
    3 -> "${names[0]}, ${names[1]} et ${names[2]}"
    else -> "${names[0]}, ${names[1]}, ${names[2]} et ${names.size - 3} autre${if (names.size - 3 > 1) "s" else ""}"
}

data class GroupMessage(
    val id: String,
    val groupId: String,
    val senderId: String,
    val type: ChatContentType,
    val content: String?,
    val createdAt: Instant,
    val mediaPurged: Boolean,
    /** J'ai déjà ouvert ce snap (sans objet pour un chat ou pour mes propres envois). */
    val openedByMe: Boolean,
    /** Membres qui ont lu le chat ou ouvert le snap, expéditeur exclu. */
    val seenBy: List<String>,
    /** Chat affiché avant la réponse du backend (interface optimiste). */
    val pending: Boolean = false,
    /** Snap dont l'envoi en arrière-plan a échoué : un appui le relance. */
    val failed: Boolean = false,
) {
    val isSnap get() = type != ChatContentType.TEXT
    fun isFrom(userId: String) = senderId == userId
}

/** Un snap de groupe reçu s'ouvre s'il n'est pas purgé ; le backend tranche la vue unique (ADR-009). */
fun canTryToOpen(message: GroupMessage, myId: String) = message.isSnap && !message.isFrom(myId) && !message.mediaPurged

data class GroupSummary(
    val group: Group,
    val lastMessage: GroupMessage?,
    /** Plus ancien snap reçu encore à ouvrir, même si d'autres messages l'ont suivi. */
    val unopenedSnap: GroupMessage? = null,
    val unread: Boolean = false,
) {
    /** Message résumé sur la ligne de la liste Chat, même priorité que pour un ami (BUG-014 d'ADR-023). */
    val headline: GroupMessage? get() = lastMessage?.takeIf { it.pending || it.failed } ?: unopenedSnap ?: lastMessage
}

/**
 * Statut d'un message de groupe sur la ligne de la liste Chat, mêmes icônes que pour un ami (ADR-009) :
 * « Snap de Léa », « Chat de Léa », « Envoyé », « Ouvert » quand tous les autres ont vu, « Reçu ».
 */
fun statusOf(message: GroupMessage, group: Group, myId: String, unread: Boolean): MessageStatus {
    val shape = if (message.isSnap) StatusShape.SQUARE else StatusShape.ARROW
    val color = when (message.type) {
        ChatContentType.SNAP_PHOTO -> StatusColor.RED
        ChatContentType.SNAP_PHOTO_VIDEO -> StatusColor.PURPLE
        ChatContentType.TEXT -> StatusColor.BLUE
    }
    if (message.isSnap && message.mediaPurged) {
        return MessageStatus(shape, StatusColor.GREY, filled = false, label = if (message.isFrom(myId)) "Ouvert" else "Expiré")
    }
    val sender = group.author(message.senderId)?.displayName ?: "Un ancien membre"
    return when {
        message.failed -> MessageStatus(shape, color, filled = true, label = "Échec de l'envoi · appuie pour réessayer")
        message.pending -> MessageStatus(shape, color, filled = true, label = "Envoi…")
        message.isFrom(myId) -> {
            val everyone = group.others(myId).isNotEmpty() && group.others(myId).all { it.id in message.seenBy }
            MessageStatus(shape, color, filled = !everyone, label = if (everyone) "Ouvert" else "Envoyé")
        }
        // Libellé court : « Nouveau snap de Léa » était coupé avant le prénom sur un téléphone (BUG-025 d'ADR-023).
        // La couleur et le gras signalent déjà la nouveauté.
        message.isSnap && !message.openedByMe -> MessageStatus(shape, color, filled = true, label = "Snap de $sender")
        !message.isSnap && unread -> MessageStatus(shape, color, filled = true, label = "Chat de $sender")
        else -> MessageStatus(shape, color, filled = false, label = "Reçu")
    }
}

/** « Vu par Léa et Hugo », « Vu par tout le monde », ou null si personne n'a encore vu. */
fun seenByLabel(message: GroupMessage, group: Group, myId: String): String? {
    val others = group.others(myId)
    if (message.seenBy.isEmpty()) return null
    if (others.isNotEmpty() && others.all { it.id in message.seenBy }) return "Vu par tout le monde"
    val names = message.seenBy.mapNotNull { id -> group.member(id)?.displayName }
    return if (names.isEmpty()) null else "Vu par ${memberNames(names)}"
}

/** Préfixe du destinataire d'un snap quand c'est un groupe (caméra ouverte depuis un groupe). */
const val GROUP_RECIPIENT_PREFIX = "group:"

/** Le groupe vu comme destinataire de la caméra : « Snap pour Soirée samedi ». */
fun Group.asRecipient(myId: String) = Friend(GROUP_RECIPIENT_PREFIX + id, "", null, title(myId))

interface GroupService {
    suspend fun groups(): List<GroupSummary>
    suspend fun group(groupId: String): Group
    suspend fun create(name: String?, memberIds: List<String>): Group
    suspend fun rename(groupId: String, name: String?): Group
    suspend fun addMembers(groupId: String, userIds: List<String>): Group
    suspend fun leave(groupId: String)
    /** Du plus récent au plus ancien, depuis mon arrivée dans le groupe. */
    suspend fun messages(groupId: String): List<GroupMessage>
    suspend fun sendText(groupId: String, content: String): GroupMessage
    suspend fun markRead(groupId: String)
    suspend fun sendTyping(groupId: String)
    suspend fun openSnap(message: GroupMessage): SnapOpenResult
}

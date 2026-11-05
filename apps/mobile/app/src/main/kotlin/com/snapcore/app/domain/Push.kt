package com.snapcore.app.domain

/** Écran ouvert quand on touche une notification. */
// Écran à ouvrir au lancement : notification touchée, ou retour du paiement SnapCore+ (ADR-025).
enum class LaunchTarget { CHAT, FRIENDS, SNAPCORE_PLUS }

enum class PushKind(val label: String, val messageLabel: String = label) {
    MESSAGE("Message", "Nouveau message"), CHAT("Chat", "Nouveau chat"),
    PHOTO("Snap photo", "Nouveau snap photo"), VIDEO("Snap vidéo", "Nouveau snap vidéo"),
    FRIEND_REQUEST("Demande d'ami"), FRIEND_ACCEPTED("Nouvel ami"), RETAINED("SnapCore+")
}

data class PushNotification(
    val title: String, val text: String, val target: LaunchTarget,
    val kind: PushKind = PushKind.MESSAGE,
    val senderName: String? = null,
    val avatarUrl: String? = null,
    val isGroup: Boolean = false,
) {
    /** Libellé court, sans répéter le prénom déjà affiché en titre d'une conversation à deux. */
    val previewText: String get() = when (kind) {
        PushKind.MESSAGE, PushKind.CHAT, PushKind.PHOTO, PushKind.VIDEO ->
            if (isGroup && senderName != null) "$senderName · ${kind.messageLabel}" else kind.messageLabel
        else -> text
    }
}

/**
 * Détails résolus via l'API au moment de la réception : le push lui-même ne contient que le type
 * d'événement et un identifiant (ADR-018). Tout est facultatif : sans réseau, le texte reste générique.
 */
data class PushDetails(val senderName: String? = null, val isSnap: Boolean? = null, val groupName: String? = null,
    val avatarUrl: String? = null, val isVideo: Boolean = false)

/**
 * Texte de la notification pour un push reçu, ou null pour un événement qui ne doit pas en afficher.
 * Le backend n'envoie en push que les événements qui justifient de réveiller le téléphone
 * (voir deservesPush côté backend), mais l'app reste défensive.
 */
fun notificationFor(data: Map<String, String>, details: PushDetails): PushNotification? {
    val who = (details.senderName ?: data["senderName"])?.trim()?.takeIf { it.isNotEmpty() }
    val contentType = data["contentType"]
    val isSnap = details.isSnap ?: contentType?.let { it != "TEXT" }
    val kind = when (data["type"]) {
        "friend_request.received" -> PushKind.FRIEND_REQUEST
        "friend_request.accepted" -> PushKind.FRIEND_ACCEPTED
        "snap.retained" -> PushKind.RETAINED
        else -> when {
            details.isVideo || contentType == "SNAP_PHOTO_VIDEO" -> PushKind.VIDEO
            isSnap == true -> PushKind.PHOTO
            isSnap == false -> PushKind.CHAT
            else -> PushKind.MESSAGE
        }
    }
    val notification = when (data["type"]) {
        "message.received" -> when (isSnap) {
            true -> PushNotification(who ?: "SnapCore", if (who != null) "Nouveau snap de $who" else "Tu as reçu un snap", LaunchTarget.CHAT)
            false -> PushNotification(who ?: "SnapCore", if (who != null) "Nouveau chat de $who" else "Tu as reçu un message", LaunchTarget.CHAT)
            null -> PushNotification(who ?: "SnapCore", if (who != null) "Nouveau message de $who" else "Tu as reçu un message", LaunchTarget.CHAT)
        }
        // Groupe (ADR-032) : le nom du groupe en titre, comme Snapchat.
        "group.message.received" -> {
            val title = (details.groupName ?: data["groupName"])?.takeIf { it.isNotBlank() } ?: "Groupe SnapCore"
            val text = when {
                who == null -> "Nouveau message dans le groupe"
                isSnap == true -> "$who a envoyé un snap"
                isSnap == false -> "$who a envoyé un chat"
                else -> "Nouveau message de $who"
            }
            PushNotification(title, text, LaunchTarget.CHAT)
        }
        "friend_request.received" ->
            PushNotification("Demande d'ami", if (who != null) "$who veut t'ajouter en ami" else "Quelqu'un veut t'ajouter en ami", LaunchTarget.FRIENDS)
        "friend_request.accepted" ->
            PushNotification("Nouvel ami", if (who != null) "$who a accepté ta demande d'ami" else "Ta demande d'ami a été acceptée", LaunchTarget.FRIENDS)
        // Le destinataire est abonné SnapCore+ : le Snap de l'expéditeur a été conservé (ADR-009).
        "snap.retained" -> PushNotification("Snap conservé", "Ton snap a été conservé par son destinataire", LaunchTarget.CHAT)
        else -> null
    }
    return notification?.copy(kind = kind, senderName = who, avatarUrl = details.avatarUrl, isGroup = data["type"] == "group.message.received")
}

interface DeviceRegistry {
    /** Enregistre le token FCM de cet appareil pour le compte connecté (upsert côté backend). */
    suspend fun registerCurrentDevice()
    /** Au logout : l'appareil ne doit plus recevoir de push pour ce compte (ADR-018). */
    suspend fun unregisterCurrentDevice()
}

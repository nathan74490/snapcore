package com.snapcore.app.domain

/** Écran ouvert quand on touche une notification. */
enum class LaunchTarget { CHAT, FRIENDS }

data class PushNotification(val title: String, val text: String, val target: LaunchTarget)

/**
 * Détails résolus via l'API au moment de la réception : le push lui-même ne contient que le type
 * d'événement et un identifiant (ADR-018). Tout est facultatif : sans réseau, le texte reste générique.
 */
data class PushDetails(val senderName: String? = null, val isSnap: Boolean? = null)

/**
 * Texte de la notification pour un push reçu, ou null pour un événement qui ne doit pas en afficher.
 * Le backend n'envoie en push que les événements qui justifient de réveiller le téléphone
 * (voir deservesPush côté backend), mais l'app reste défensive.
 */
fun notificationFor(data: Map<String, String>, details: PushDetails): PushNotification? {
    val who = details.senderName
    return when (data["type"]) {
        "message.received" -> when (details.isSnap) {
            true -> PushNotification("SnapCore", if (who != null) "Nouveau snap de $who" else "Tu as reçu un snap", LaunchTarget.CHAT)
            false -> PushNotification("SnapCore", if (who != null) "Nouveau chat de $who" else "Tu as reçu un message", LaunchTarget.CHAT)
            null -> PushNotification("SnapCore", if (who != null) "Nouveau message de $who" else "Tu as reçu un message", LaunchTarget.CHAT)
        }
        "friend_request.received" ->
            PushNotification("Nouvelle demande d'ami", if (who != null) "$who veut t'ajouter en ami" else "Quelqu'un veut t'ajouter en ami", LaunchTarget.FRIENDS)
        "friend_request.accepted" ->
            PushNotification("SnapCore", if (who != null) "$who a accepté ta demande d'ami" else "Ta demande d'ami a été acceptée", LaunchTarget.FRIENDS)
        // Le destinataire est abonné SnapCore+ : le Snap de l'expéditeur a été conservé (ADR-009).
        "snap.retained" -> PushNotification("SnapCore", "Ton snap a été conservé par son destinataire", LaunchTarget.CHAT)
        else -> null
    }
}

interface DeviceRegistry {
    /** Enregistre le token FCM de cet appareil pour le compte connecté (upsert côté backend). */
    suspend fun registerCurrentDevice()
    /** Au logout : l'appareil ne doit plus recevoir de push pour ce compte (ADR-018). */
    suspend fun unregisterCurrentDevice()
}

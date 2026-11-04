package com.snapcore.app.domain

// Sécurité entre utilisateurs : retirer un ami, bloquer (ADR-012) et signaler (ADR-021).

/** Motif d'un signalement, choisi dans une liste ; envoyé au backend comme texte libre (ADR-021). */
enum class ReportReason(val label: String) {
    HARASSMENT("Harcèlement ou intimidation"),
    INAPPROPRIATE("Contenu inapproprié ou choquant"),
    SPAM("Spam ou arnaque"),
    IMPERSONATION("Usurpation d'identité"),
    OTHER("Autre"),
}

/** Ce qui est signalé : un utilisateur, un message (chat ou snap) ou un élément de story. */
sealed interface ReportTarget {
    val id: String

    data class User(override val id: String) : ReportTarget
    data class Message(override val id: String) : ReportTarget
    data class StoryItem(override val id: String) : ReportTarget
}

interface SafetyService {
    /** Retire l'ami sans le bloquer : chacun peut renvoyer une demande ensuite (ADR-012). */
    suspend fun removeFriend(userId: String)

    /** Bloque l'utilisateur : plus de messages, profil masqué ; seul celui qui bloque peut débloquer. */
    suspend fun block(userId: String)

    /** Débloque : les deux redeviennent des inconnus, l'amitié précédente n'est pas restaurée (ADR-012). */
    suspend fun unblock(userId: String)

    /** Utilisateurs que j'ai bloqués. */
    suspend fun blocked(): List<Friend>

    suspend fun report(target: ReportTarget, reason: ReportReason)
}

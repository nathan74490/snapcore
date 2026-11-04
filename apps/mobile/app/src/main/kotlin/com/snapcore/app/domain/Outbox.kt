package com.snapcore.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID

/** Envoi d'un snap ou d'une story, confié à l'app : l'écran d'envoi se ferme sans attendre. */
interface SnapSender {
    fun send(media: CapturedMedia, targets: Set<SendTarget>)
}

enum class OutgoingStatus { SENDING, FAILED, SENT }

/** Un média en route vers une cible (un ami ou ma story). */
data class OutgoingSnap(
    val id: String,
    val media: CapturedMedia,
    val target: SendTarget,
    val createdAt: Instant,
    val status: OutgoingStatus = OutgoingStatus.SENDING,
    val error: String? = null,
) {
    val friendId get() = (target as? SendTarget.ToFriend)?.friend?.id

    /** Ligne de chat affichée en attendant le vrai message du backend. */
    fun asMessage(myId: String) = ChatMessage(
        id = OUTBOX_PREFIX + id,
        senderId = myId,
        recipientId = friendId.orEmpty(),
        type = if (media.kind == MediaKind.VIDEO) ChatContentType.SNAP_PHOTO_VIDEO else ChatContentType.SNAP_PHOTO,
        content = null,
        createdAt = createdAt,
        openedAt = null,
        mediaPurged = false,
        pending = status == OutgoingStatus.SENDING,
        failed = status == OutgoingStatus.FAILED,
    )

    companion object {
        const val OUTBOX_PREFIX = "outbox-"
    }
}

/**
 * File d'envoi des snaps et des stories, au niveau de l'app : l'utilisateur touche « Envoyer » et retourne
 * tout de suite à la caméra, l'envoi continue en arrière-plan. Une entrée par cible : chaque ami et ma story
 * réussissent ou échouent séparément, et seule une cible en échec se réessaie.
 *
 * Les envois passent un par un, dans l'ordre : la bande passante va au premier snap plutôt que d'être
 * partagée. Le fichier est déplacé dans [dir] dès l'envoi (l'aperçu qui l'a produit peut se fermer et
 * nettoyer les siens), puis supprimé quand plus aucune cible ne l'attend (ADR-011).
 * Un envoi réussi reste visible [sentRetentionMs] ms, le temps que les écrans rechargent le vrai message.
 *
 * Limite assumée : la file vit avec le processus de l'app. Si Android tue l'app pendant un envoi, il est
 * perdu (le fichier restant est effacé au démarrage suivant par [clearLeftovers]).
 */
class SnapOutbox(
    private val publisher: MediaPublisher,
    private val scope: CoroutineScope,
    private val dir: File,
    private val clock: () -> Instant = Instant::now,
    private val sentRetentionMs: Long = 30_000,
    private val analytics: Analytics = NoAnalytics,
) : SnapSender {
    private val _items = MutableStateFlow<List<OutgoingSnap>>(emptyList())
    /** Du plus récent au plus ancien. */
    val items: StateFlow<List<OutgoingSnap>> = _items.asStateFlow()

    private val lane = Mutex()

    override fun send(media: CapturedMedia, targets: Set<SendTarget>) {
        if (targets.isEmpty()) return
        val owned = takeOwnership(media)
        val createdAt = clock()
        val entries = targets.map { OutgoingSnap(UUID.randomUUID().toString(), owned, it, createdAt) }
        _items.update { entries + it }
        analytics.capture(AnalyticsEvents.SNAP_SEND_STARTED, mapOf(
            "media" to media.kind.analyticsName,
            "friends" to targets.count { it is SendTarget.ToFriend },
            "story" to targets.any { it !is SendTarget.ToFriend },
        ))
        entries.forEach(::launch)
    }

    fun retry(id: String) {
        val entry = _items.value.firstOrNull { it.id == id && it.status == OutgoingStatus.FAILED } ?: return
        val again = entry.copy(status = OutgoingStatus.SENDING, error = null)
        analytics.capture(AnalyticsEvents.SNAP_SEND_RETRIED, entry.analyticsProperties())
        replace(again)
        launch(again)
    }

    /** Abandonne un envoi en échec. */
    fun discard(id: String) {
        val entry = _items.value.firstOrNull { it.id == id && it.status == OutgoingStatus.FAILED } ?: return
        _items.update { list -> list.filter { it.id != id } }
        analytics.capture(AnalyticsEvents.SNAP_SEND_ABANDONED, entry.analyticsProperties())
        deleteIfUnused(entry.media)
    }

    /** Fichiers d'envois interrompus par l'arrêt de l'app : plus personne ne les enverra. */
    fun clearLeftovers() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun launch(entry: OutgoingSnap) {
        scope.launch {
            val error = lane.withLock {
                try {
                    publisher.publishTo(entry.media, entry.target)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "Échec de l'envoi"
                }
            }
            if (error == null) {
                replace(entry.copy(status = OutgoingStatus.SENT))
                deleteIfUnused(entry.media)
                delay(sentRetentionMs)
                _items.update { list -> list.filter { it.id != entry.id } }
            } else {
                replace(entry.copy(status = OutgoingStatus.FAILED, error = error))
                analytics.capture(AnalyticsEvents.SNAP_SEND_FAILED, entry.analyticsProperties())
            }
        }
    }

    private fun OutgoingSnap.analyticsProperties(): Map<String, Any> =
        mapOf("media" to media.kind.analyticsName, "target" to if (target is SendTarget.ToFriend) "friend" else "story")

    private fun replace(entry: OutgoingSnap) = _items.update { list -> list.map { if (it.id == entry.id) entry else it } }

    private fun deleteIfUnused(media: CapturedMedia) {
        val waiting = _items.value.any { it.media.file == media.file && it.status != OutgoingStatus.SENT }
        if (!waiting) media.file.delete()
    }

    private fun takeOwnership(media: CapturedMedia): CapturedMedia {
        dir.mkdirs()
        val target = File(dir, "${UUID.randomUUID()}.${media.file.extension.ifEmpty { "bin" }}")
        if (media.file.renameTo(target)) return media.copy(file = target)
        // Autre volume (cas improbable : tout est dans le cache de l'app) : copie puis suppression.
        media.file.copyTo(target)
        media.file.delete()
        return media.copy(file = target)
    }
}

/**
 * Pour un écran qui affiche la file d'envoi : un snap envoyé y reste affiché jusqu'au premier rechargement
 * lancé après la fin de son envoi, qui apporte le vrai message du backend. Sans ça, il disparaîtrait un
 * instant entre la fin de l'envoi et l'arrivée du rechargement.
 */
class SentTracker {
    private val seen = mutableSetOf<String>()
    private val acknowledged = mutableSetOf<String>()

    /** @return true si un envoi a abouti depuis le dernier appel : l'écran doit recharger. */
    fun newlySent(items: List<OutgoingSnap>): Boolean {
        val ids = items.mapTo(mutableSetOf()) { it.id }
        seen.retainAll(ids)
        acknowledged.retainAll(ids)
        val fresh = items.filter { it.status == OutgoingStatus.SENT && it.id !in seen }
        fresh.forEach { seen += it.id }
        return fresh.isNotEmpty()
    }

    /** À relever au lancement d'un rechargement… */
    fun mark(): Set<String> = seen.toSet()

    /** …puis à confirmer à son arrivée : ces envois figurent désormais dans la réponse du backend. */
    fun acknowledge(known: Set<String>) {
        acknowledged += known
    }

    fun visible(items: List<OutgoingSnap>) = items.filter { it.status != OutgoingStatus.SENT || it.id !in acknowledged }
}

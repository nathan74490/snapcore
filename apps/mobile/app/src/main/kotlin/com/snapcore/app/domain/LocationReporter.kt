package com.snapcore.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Envoie ma position au backend pendant l'utilisation de l'app (ADR-031) : à l'ouverture de l'accueil et
 * pendant que la carte est affichée, au plus une fois par [minInterval]. Jamais en mode fantôme, jamais
 * sans permission. Le réglage de partage est mémorisé par compte, pour qu'un changement de compte sur le
 * téléphone ne réutilise pas celui du compte précédent.
 */
class LocationReporter(
    private val map: MapService,
    private val location: LocationProvider,
    private val now: () -> Instant = Instant::now,
    private val minInterval: Duration = Duration.ofMinutes(1),
) {
    private val mutex = Mutex()
    private var accountId: String? = null
    private var lastSentAt: Instant? = null
    private val _sharing = MutableStateFlow<LocationSharing?>(null)

    /** Réglage du compte courant ; null tant qu'il n'a pas été lu. */
    val sharing: StateFlow<LocationSharing?> = _sharing.asStateFlow()

    /** Relit le réglage auprès du backend. */
    suspend fun loadSharing(accountId: String): LocationSharing = mutex.withLock {
        switchTo(accountId)
        map.sharing().also { _sharing.value = it }
    }

    /**
     * Enregistre un nouveau réglage. En quittant le mode fantôme, la position part tout de suite, pour que
     * les amis me voient sans attendre le prochain envoi.
     */
    suspend fun updateSharing(accountId: String, sharing: LocationSharing): LocationSharing {
        val saved = mutex.withLock {
            switchTo(accountId)
            map.updateSharing(sharing).also { _sharing.value = it; lastSentAt = null }
        }
        if (!saved.ghost) report(accountId)
        return saved
    }

    /**
     * Ma position actuelle (affichée sur ma carte même en mode fantôme), envoyée au backend si le partage
     * est actif et que le dernier envoi date d'au moins [minInterval]. Ne lève jamais : la carte reste
     * utilisable hors ligne ou sans permission.
     */
    suspend fun report(accountId: String, force: Boolean = false): GeoPoint? {
        if (!location.hasPermission()) return null
        val position = attempt { location.current() } ?: return null
        mutex.withLock {
            switchTo(accountId)
            val sharing = _sharing.value ?: attempt { map.sharing() }?.also { _sharing.value = it } ?: return position
            val last = lastSentAt
            val due = force || last == null || Duration.between(last, now()) >= minInterval
            if (!sharing.ghost && due && attempt { map.updateLocation(position) } != null) lastSentAt = now()
        }
        return position
    }

    /**
     * Position à joindre à une story qu'on publie (épinglée sur la carte de mes amis), ou null en mode
     * fantôme, sans permission ou si le réglage est illisible. Le réglage est relu à chaque fois : la story
     * part en arrière-plan, éventuellement longtemps après l'ouverture de l'app.
     */
    suspend fun positionForStory(): GeoPoint? {
        if (!location.hasPermission()) return null
        val sharing = attempt { map.sharing() } ?: return null
        if (sharing.ghost) return null
        return attempt { location.current() }
    }

    private fun switchTo(id: String) {
        if (id == accountId) return
        accountId = id
        lastSentAt = null
        _sharing.value = null
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

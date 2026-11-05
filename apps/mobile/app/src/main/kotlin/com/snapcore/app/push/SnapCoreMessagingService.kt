package com.snapcore.app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.snapcore.app.SnapCoreApplication
import com.snapcore.app.domain.PushDetails
import com.snapcore.app.domain.notificationFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

// Réception des pushes FCM (ADR-018). Le backend n'en envoie que si l'app n'est pas connectée en
// temps réel : app en arrière-plan ou fermée.
class SnapCoreMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container get() = (application as SnapCoreApplication).container

    // Le jeton FCM peut changer : on le renvoie au backend à chaque renouvellement (ADR-018).
    // Déprécié en 25.1 au profit du mode "installation ID" : voir FcmDeviceRegistry.
    @Deprecated("Mode FCM par défaut, voir FcmDeviceRegistry")
    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { container.devices.onTokenReceived(token) }
                .onFailure { Log.w(TAG, "Token FCM non enregistré : ${it.message}") }
        }
    }

    // Appelé sur un thread d'arrière-plan, avec un délai limité : on attend au plus quelques
    // secondes les détails (nom de l'expéditeur), puis on affiche quoi qu'il arrive.
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val details = runBlocking { withTimeoutOrNull(5_000) { container.pushDetails.resolve(data) } }
        val notification = notificationFor(data, details ?: PushDetails()) ?: return
        val avatar = runBlocking { loadNotificationAvatar(this@SnapCoreMessagingService, notification) }
        Notifications.show(this, (data["messageId"] ?: data["friendshipId"] ?: data["type"]).hashCode(), notification, avatar)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SnapCorePush"
    }
}

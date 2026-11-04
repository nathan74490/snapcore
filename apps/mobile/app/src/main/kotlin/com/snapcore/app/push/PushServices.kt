package com.snapcore.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.snapcore.app.MainActivity
import com.snapcore.app.R
import com.snapcore.app.data.api.DeviceTokenRequest
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.DeviceRegistry
import com.snapcore.app.data.toFriend
import com.snapcore.app.domain.LaunchTarget
import com.snapcore.app.domain.PushDetails
import com.snapcore.app.domain.PushNotification
import kotlinx.coroutines.tasks.await

const val MESSAGES_CHANNEL_ID = "messages"
const val EXTRA_LAUNCH_TARGET = "com.snapcore.app.LAUNCH_TARGET"

// Mode FCM par défaut : jeton d'enregistrement via getToken() / onNewToken(). firebase-messaging 25.1
// les marque dépréciés au profit d'un mode "installation ID" (register() / onRegistered()), mais ce
// mode est optionnel (meta-data firebase_messaging_installation_id_enabled), exclusif de celui-ci, et
// change l'identifiant envoyé au backend : le SDK Admin côté backend (sendEachForMulticast) cible
// des jetons d'enregistrement. Migration à faire côté app ET backend ensemble, pas ici seul.
@Suppress("DEPRECATION")
class FcmDeviceRegistry(private val api: SnapCoreApi, private val auth: FirebaseAuth) : DeviceRegistry {
    override suspend fun registerCurrentDevice() {
        if (auth.currentUser == null) return
        onTokenReceived(FirebaseMessaging.getInstance().token.await())
    }

    /** Appelé au démarrage et par le service à chaque renouvellement du jeton (ADR-018). */
    suspend fun onTokenReceived(token: String) {
        if (auth.currentUser != null) apiCall { api.registerDevice(DeviceTokenRequest(token)) }
    }

    override suspend fun unregisterCurrentDevice() {
        apiCall { api.unregisterDevice(FirebaseMessaging.getInstance().token.await()) }
    }
}

// Retrouve l'expéditeur via l'API : le push ne contient qu'un type et un identifiant (ADR-018).
// Le nom affiché est le prénom (à défaut le pseudo), comme partout dans l'app (BUG-017 d'ADR-023).
class PushDetailsResolver(private val api: SnapCoreApi) {
    suspend fun resolve(data: Map<String, String>): PushDetails = runCatching {
        when (data["type"]) {
            "message.received" -> {
                val conversation = api.conversations().firstOrNull { it.lastMessage?.id == data["messageId"] }
                PushDetails(conversation?.friend?.toFriend()?.displayName, conversation?.lastMessage?.let { it.contentType != "TEXT" })
            }
            "friend_request.received" ->
                PushDetails(api.incomingRequests().firstOrNull { it.friendshipId == data["friendshipId"] }?.user?.toFriend()?.displayName)
            "friend_request.accepted" ->
                PushDetails(api.friends().firstOrNull { it.friendshipId == data["friendshipId"] }?.user?.toFriend()?.displayName)
            else -> PushDetails()
        }
    }.getOrDefault(PushDetails())
}

object Notifications {
    fun createChannels(context: Context) {
        val channel = NotificationChannel(MESSAGES_CHANNEL_ID, "Messages et amis", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Snaps, chats et demandes d'ami reçus quand l'app est fermée"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * La permission n'est demandée automatiquement qu'une fois par installation : après un refus,
     * l'utilisateur la réactive depuis les paramètres du profil (BUG-007 d'ADR-023).
     * @return true si c'est la première demande, qui est alors marquée comme faite.
     */
    fun claimFirstPermissionRequest(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERMISSION_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        return true
    }

    /** Réglages de notification de l'app dans Android, pour les réactiver après un refus. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private const val PREFS = "notifications"
    private const val KEY_PERMISSION_ASKED = "permission_asked"

    fun show(context: Context, id: Int, notification: PushNotification) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_LAUNCH_TARGET, notification.target.name)
        val pending = PendingIntent.getActivity(
            context,
            notification.target.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built = NotificationCompat.Builder(context, MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        @Suppress("MissingPermission") // vérifiée par canNotify()
        NotificationManagerCompat.from(context).notify(id, built)
    }

    fun launchTargetOf(intent: Intent?): LaunchTarget? =
        intent?.getStringExtra(EXTRA_LAUNCH_TARGET)?.let { runCatching { LaunchTarget.valueOf(it) }.getOrNull() }
}

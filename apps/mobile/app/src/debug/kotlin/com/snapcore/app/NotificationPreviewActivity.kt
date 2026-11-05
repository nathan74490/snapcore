package com.snapcore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.snapcore.app.data.avatarImageUrl
import com.snapcore.app.domain.PushDetails
import com.snapcore.app.domain.notificationFor
import com.snapcore.app.push.Notifications
import com.snapcore.app.push.loadNotificationAvatar
import kotlinx.coroutines.launch

/** Démonstration locale uniquement : aucun message envoyé, aucun accès au compte connecté. */
class NotificationPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sample = intent.getStringExtra("sample") ?: "all"
        val manager = NotificationManagerCompat.from(this)
        (0..4).forEach { manager.cancel(TAG, BASE_ID + it) }
        if (sample == "clear") { finish(); return }
        lifecycleScope.launch {
            val defaults = mapOf("top" to "shortFlat", "hairColor" to "2c1b18", "skinColor" to "edb98a",
                "eyes" to "default", "eyebrows" to "defaultNatural", "mouth" to "smile", "facialHair" to "none",
                "accessories" to "none", "clothing" to "hoodie", "clothesColor" to "65c9ff", "backgroundColor" to "b6e3f4")
            val lea = avatarImageUrl(defaults + mapOf("top" to "straight01", "hairColor" to "724133", "backgroundColor" to "ffd5dc"), 128)
            val hugo = avatarImageUrl(defaults + mapOf("top" to "shortCurly", "facialHair" to "beardLight"), 128)
            val examples = listOf(
                Triple("photo", mapOf("type" to "message.received"), PushDetails("Léa", true, avatarUrl = lea)),
                Triple("chat", mapOf("type" to "message.received"), PushDetails("Hugo", false, avatarUrl = hugo)),
                Triple("group", mapOf("type" to "group.message.received", "groupId" to "preview"), PushDetails("Léa", true, "Soirée samedi", lea, isVideo = true)),
                Triple("friend", mapOf("type" to "friend_request.received"), PushDetails("Chloé")),
                Triple("fallback", mapOf("type" to "message.received"), PushDetails()),
            )
            examples.forEachIndexed { index, (key, data, details) ->
                if (sample == key || sample == "all" && key != "fallback") {
                    val notification = notificationFor(data, details) ?: return@forEachIndexed
                    val avatar = loadNotificationAvatar(this@NotificationPreviewActivity, notification)
                    Notifications.show(this@NotificationPreviewActivity, BASE_ID + index, notification, avatar, TAG)
                }
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "snapcore-preview"
        private const val BASE_ID = 910_000
    }
}

package com.snapcore.app.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.snapcore.app.R
import com.snapcore.app.domain.PushKind
import com.snapcore.app.domain.PushNotification
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal data class NotificationAppearance(val color: Int, val icon: Int)

internal fun PushKind.appearance() = when (this) {
    PushKind.PHOTO -> NotificationAppearance(0xFFF23C57.toInt(), R.drawable.ic_notification_photo)
    PushKind.VIDEO -> NotificationAppearance(0xFF9B31E7.toInt(), R.drawable.ic_notification_video)
    PushKind.FRIEND_REQUEST, PushKind.FRIEND_ACCEPTED -> NotificationAppearance(0xFF16191C.toInt(), R.drawable.ic_notification_friend)
    PushKind.RETAINED -> NotificationAppearance(0xFF997018.toInt(), R.drawable.ic_notification_star)
    else -> NotificationAppearance(0xFF0FADFF.toInt(), R.drawable.ic_notification)
}

/** Image de profil seulement : jamais de photo de snap ni de contenu de conversation. */
suspend fun loadNotificationAvatar(context: Context, notification: PushNotification): Bitmap? =
    notification.avatarUrl?.let { url ->
        withTimeoutOrNull(1_500) {
            runCatching {
                SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url)
                    .size(128).allowHardware(false).build()).image?.toBitmap()
            }.getOrNull()
        }
    }

/** Avatar circulaire, liseré jaune et initiales lisibles si l'image n'est pas disponible. */
internal fun notificationAvatar(notification: PushNotification, image: Bitmap?): Bitmap {
    val bitmap = createBitmap(128, 128)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFFFFC00.toInt()
    canvas.drawCircle(64f, 64f, 64f, paint)
    if (image != null && image.width > 0 && image.height > 0) {
        val scale = 120f / minOf(image.width, image.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(64f - image.width * scale / 2, 64f - image.height * scale / 2)
        }
        paint.shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        canvas.drawCircle(64f, 64f, 60f, paint)
    } else {
        paint.color = ColorUtils.blendARGB(notification.kind.appearance().color, Color.WHITE, .88f)
        canvas.drawCircle(64f, 64f, 60f, paint)
        paint.color = 0xFF16191C.toInt()
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 44f
        val initials = (notification.senderName ?: notification.title).trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase(Locale.FRENCH).ifEmpty { "SC" }
        canvas.drawText(initials, 64f, 64f - (paint.ascent() + paint.descent()) / 2, paint)
    }
    return bitmap
}

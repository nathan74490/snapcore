package com.snapcore.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.domain.StoryThumbnailMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

// Plus grand côté de la vignette : assez pour l'anneau (72 dp) et les cartes de la page Stories.
private const val THUMBNAIL_SIZE = 320

// Vignette d'une story fabriquée sur le téléphone, qui a déjà le média en main : photo réduite ou
// première image de la vidéo, en JPEG de quelques dizaines de Ko (ADR-026). Aucun traitement serveur.
class BitmapStoryThumbnailMaker(private val outputDir: File) : StoryThumbnailMaker {
    override suspend fun make(media: CapturedMedia): File? = withContext(Dispatchers.IO) {
        val frame = when (media.kind) {
            MediaKind.PHOTO -> decodeSampled(media.file)
            MediaKind.VIDEO -> firstFrame(media.file)
        } ?: return@withContext null
        val scale = THUMBNAIL_SIZE.toFloat() / max(frame.width, frame.height)
        val thumbnail = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
        } else frame

        val file = File(outputDir, "story-thumbnail-${UUID.randomUUID()}.jpg")
        file.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        listOf(frame, thumbnail).distinct().forEach(Bitmap::recycle)
        file
    }

    // Les photos capturées sont enregistrées déjà droites (sans EXIF) : un simple décodage réduit suffit.
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_SIZE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun firstFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }
}

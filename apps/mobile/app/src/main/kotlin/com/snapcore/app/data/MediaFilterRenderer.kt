package com.snapcore.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.snapcore.app.domain.CameraFilter
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.ColorMatrix
import com.snapcore.app.domain.FilterRenderer
import com.snapcore.app.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Débit de la vidéo réencodée : 60 s ≈ 30 Mo, sous la limite de 60 Mo du backend (ADR-016).
private const val VIDEO_BITRATE = 4_000_000

/**
 * Incruste le filtre choisi sur l'aperçu dans un nouveau fichier du cache privé (ADR-022) : la photo
 * est redessinée avec la matrice de couleur, la vidéo réencodée par Media3 Transformer avec la même
 * matrice que celle de l'aperçu (ExoPlayer).
 *
 * Une vidéo est réencodée même sans filtre : Camera Kit enregistre autour de 11 Mbit/s (ADR-024), une
 * vidéo de 60 s dépasserait la limite du backend sans ce passage à 4 Mbit/s.
 */
class MediaFilterRenderer(private val context: Context, private val outputDir: File) : FilterRenderer {

    override suspend fun render(media: CapturedMedia, filter: CameraFilter): CapturedMedia {
        val matrix = filter.matrix
        val file = when (media.kind) {
            MediaKind.PHOTO -> renderPhoto(media.file, (matrix ?: return media).toAndroidMatrix())
            // La matrice identité force le réencodage : sans effet, Transformer recopierait le flux tel quel.
            MediaKind.VIDEO -> renderVideo(media.file, (matrix ?: ColorMatrix.IDENTITY).toGlMatrix())
        }
        return CapturedMedia(file, media.kind)
    }

    private suspend fun renderPhoto(source: File, matrix: FloatArray): File = withContext(Dispatchers.IO) {
        val original = decodeUpright(context, Uri.fromFile(source))
        val filtered = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        Canvas(filtered).drawBitmap(original, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        original.recycle()

        val output = newFile("jpg")
        try {
            output.outputStream().use { filtered.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            filtered.recycle()
        }
        output
    }

    // Transformer doit être piloté depuis le thread principal.
    @OptIn(UnstableApi::class)
    private suspend fun renderVideo(source: File, matrix: FloatArray): File = withContext(Dispatchers.Main) {
        val output = newFile("mp4")
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setEffects(Effects(emptyList(), listOf(RgbMatrix { _, _ -> matrix })))
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
            .build()

        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        continuation.resume(output)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        output.delete()
                        continuation.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(item, output.absolutePath)
            continuation.invokeOnCancellation {
                ContextCompat.getMainExecutor(context).execute {
                    transformer.cancel()
                    output.delete()
                }
            }
        }
    }

    private fun newFile(extension: String) = File(outputDir, "filtered-${UUID.randomUUID()}.$extension")
}

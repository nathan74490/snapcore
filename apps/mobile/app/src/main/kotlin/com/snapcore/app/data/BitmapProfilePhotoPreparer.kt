package com.snapcore.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.snapcore.app.domain.PROFILE_PHOTO_SIZE
import com.snapcore.app.domain.ProfilePhotoPreparer
import com.snapcore.app.domain.centeredSquare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Recadrage carré centré + redimensionnement en 512×512 sur le téléphone, avant envoi (ADR-014) :
// aucun traitement d'image côté serveur, et une photo de profil toujours légère (< 2 Mo, ADR-016).
class BitmapProfilePhotoPreparer(private val context: Context, private val outputDir: File) : ProfilePhotoPreparer {
    override suspend fun prepare(source: String): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        val original = decodeUpright(uri)
        val crop = centeredSquare(original.width, original.height)
        val square = Bitmap.createBitmap(original, crop.left, crop.top, crop.size, crop.size)
        val scaled = Bitmap.createScaledBitmap(square, PROFILE_PHOTO_SIZE, PROFILE_PHOTO_SIZE, true)

        val file = File(outputDir, "profile-${UUID.randomUUID()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        listOf(original, square, scaled).distinct().forEach(Bitmap::recycle)
        file
    }

    // Décode en tenant compte de l'orientation EXIF (photo prise téléphone tourné) : ImageDecoder
    // l'applique lui-même depuis Android 9 ; en dessous, on lit l'EXIF et on fait pivoter.
    private fun decodeUpright(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Bitmap.createBitmap refuse le HARDWARE
            }
        }
        val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: error("Image illisible")
        val degrees = context.contentResolver.openInputStream(uri).use { input ->
            when (ExifInterface(input!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}

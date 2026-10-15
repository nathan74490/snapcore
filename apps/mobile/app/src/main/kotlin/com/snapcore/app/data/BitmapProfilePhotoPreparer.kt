package com.snapcore.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
        val original = decodeUpright(context, uri)
        val crop = centeredSquare(original.width, original.height)
        val square = Bitmap.createBitmap(original, crop.left, crop.top, crop.size, crop.size)
        val scaled = Bitmap.createScaledBitmap(square, PROFILE_PHOTO_SIZE, PROFILE_PHOTO_SIZE, true)

        val file = File(outputDir, "profile-${UUID.randomUUID()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        listOf(original, square, scaled).distinct().forEach(Bitmap::recycle)
        file
    }
}

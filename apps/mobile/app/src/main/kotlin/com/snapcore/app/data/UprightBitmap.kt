package com.snapcore.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build

// Décode en tenant compte de l'orientation EXIF (photo prise téléphone tourné) : ImageDecoder
// l'applique lui-même depuis Android 9 ; en dessous, on lit l'EXIF et on fait pivoter.
internal fun decodeUpright(context: Context, uri: Uri): Bitmap {
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

package com.dosely.app.data.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Thrown when an image attachment cannot be read or is too large. */
class ImageAttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Converts a content [Uri] (e.g. from the Android photo picker) to a Base64
 * payload for upload through the Cloudflare worker.
 *
 * Images are re-encoded as PNG (stripping EXIF/private metadata), capped to
 * [MAX_DIMENSION_PX] on the long edge, and limited to ~8 MB decoded.
 */
object ImageHelper {

    private const val MAX_DIMENSION_PX = 2048
    private const val PNG_QUALITY = 100

    /**
     * Reads, downscales, re-encodes and Base64-encodes the image at [uri].
     *
     * @return PNG bytes of the image, Base64-encoded with NO_WRAP.
     * @throws ImageAttachmentException when the image cannot be read or exceeds size limits.
     */
    fun uriToBase64(context: Context, uri: Uri): String {
        val bytes = readScaledPngBytes(context, uri)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun readScaledPngBytes(context: Context, uri: Uri): ByteArray {
        // First pass: bounds only, to pick a downscale factor. decodeStream
        // returns null here by design while populating outWidth/outHeight, so
        // success is judged by the bounds, not the null return.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrElse { throw ImageAttachmentException("Could not read the selected image.", it) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageAttachmentException("The selected file is not a supported image.")
        }

        val sample = chooseSampleSize(bounds.outWidth, bounds.outHeight)

        // Second pass: decoded with the sample factor applied.
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        }.getOrElse { throw ImageAttachmentException("Could not read the selected image.", it) }
            ?: throw ImageAttachmentException("The selected file is not a supported image.")

        val scaled = scaleDown(bitmap)
        val pngBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) bitmap.recycle()
        if (pngBytes.size > 8 * 1024 * 1024) {
            throw ImageAttachmentException("The selected image is too large (max 8 MB).")
        }
        return pngBytes
    }

    /** Loads a small bitmap for UI previews, or null if the image can't be read. */
    fun loadThumbnail(context: Context, uri: Uri): Bitmap? {
        // Bounds pass to pick a sample size for a ~512px preview.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = chooseSampleSize(bounds.outWidth, bounds.outHeight, 512)
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull()
    }

    private fun chooseSampleSize(width: Int, height: Int, maxDim: Int = MAX_DIMENSION_PX): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDim || h / 2 >= maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_DIMENSION_PX) return bitmap
        val scale = MAX_DIMENSION_PX.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}

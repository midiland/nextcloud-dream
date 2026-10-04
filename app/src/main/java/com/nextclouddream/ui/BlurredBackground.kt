package com.nextclouddream.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.File

/**
 * Fond flou pour les photos affichées en entier (portrait) : une miniature
 * de la photo, floutée puis étirée en plein écran.
 *
 * RenderEffect n'existe qu'à partir d'Android 12 : on floute donc nous-même
 * une toute petite image (48 px de large), ce qui ne coûte presque rien.
 * L'agrandissement filtré par l'ImageView finit de lisser le résultat.
 */
object BlurredBackground {

    private const val WIDTH = 48
    private const val BLUR_RADIUS = 2
    private const val BLUR_PASSES = 3

    /** @return la miniature floutée, ou null si la photo est illisible. */
    fun create(file: File): Bitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)

            // Sous-échantillonnage au décodage : on ne charge jamais l'image en pleine taille
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= WIDTH) sampleSize *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null

            val height = (WIDTH * decoded.height / decoded.width).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(decoded, WIDTH, height, true)
            if (small !== decoded) decoded.recycle()
            boxBlur(small)
        } catch (e: Exception) {
            Timber.w(e, "Fond flou impossible : %s", file.name)
            null
        }

    /** Flou par moyenne glissante, horizontal puis vertical, répété (≈ flou gaussien). */
    private fun boxBlur(source: Bitmap): Bitmap {
        val bitmap = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true).also { source.recycle() }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val buffer = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        repeat(BLUR_PASSES) {
            blurPass(pixels, buffer, width, height, horizontal = true)
            blurPass(buffer, pixels, width, height, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun blurPass(src: IntArray, dst: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val lineCount = if (horizontal) height else width
        val lineLength = if (horizontal) width else height
        val kernelSize = 2 * BLUR_RADIUS + 1

        for (line in 0 until lineCount) {
            for (i in 0 until lineLength) {
                var red = 0
                var green = 0
                var blue = 0
                for (k in -BLUR_RADIUS..BLUR_RADIUS) {
                    // Bords : on répète le pixel du bord
                    val j = (i + k).coerceIn(0, lineLength - 1)
                    val color = if (horizontal) src[line * width + j] else src[j * width + line]
                    red += (color shr 16) and 0xFF
                    green += (color shr 8) and 0xFF
                    blue += color and 0xFF
                }
                val blurred = (0xFF shl 24) or
                    ((red / kernelSize) shl 16) or
                    ((green / kernelSize) shl 8) or
                    (blue / kernelSize)
                if (horizontal) dst[line * width + i] = blurred else dst[i * width + line] = blurred
            }
        }
    }
}

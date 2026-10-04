package com.nextclouddream.image

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import timber.log.Timber
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Réduit une photo à la taille de l'écran avant de la mettre en cache
 * (solution de repli quand Nextcloud ne fournit pas d'aperçu, ex. HEIC).
 *
 * Une photo de smartphone (4000×3000, 3-5 Mo) devient un JPEG d'environ 1920×1440
 * de ~400 Ko : aucune perte visible sur une TV 1080p, ~10x moins de stockage,
 * et un décodage bien plus rapide au moment de l'affichage.
 *
 * ImageDecoder (Android 9+) applique l'orientation EXIF et décode le HEIC :
 * le fichier produit est toujours un JPEG « droit », sans métadonnées.
 */
object ImageResizer {

    const val TARGET_WIDTH = 1920
    const val TARGET_HEIGHT = 1080
    private const val JPEG_QUALITY = 88

    /**
     * Écrit dans [destination] une version réduite de [source], en gardant ses proportions :
     *  - paysage : couvre au moins TARGET_WIDTH × TARGET_HEIGHT (recadrage plein écran net)
     *  - portrait : tient dans TARGET_WIDTH × TARGET_HEIGHT (affichage en entier)
     *
     * Utilisé seulement quand le serveur ne peut pas fournir d'aperçu réduit.
     * @return false si l'image n'a pas pu être décodée (format non supporté).
     */
    fun resizeToScreen(source: File, destination: File): Boolean {
        val bitmap = try {
            decode(source, exactSize = true)
        } catch (e: Exception) {
            // Android 9 : ImageDecoder refuse certaines tailles exactes
            // ("getPixels failed with error invalid scale", vu sur la Mi Box).
            // On décode alors par puissance de 2, puis on ajuste la taille.
            Timber.i("Réduction exacte refusée (%s), décodage par paliers : %s", e.message, source.name)
            try {
                decode(source, exactSize = false)
            } catch (e2: Exception) {
                Timber.w(e2, "Redimensionnement impossible : %s", source.name)
                return false
            }
        }
        return try {
            destination.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            true
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(source: File, exactSize: Boolean): Bitmap {
        var targetWidth = 0
        var targetHeight = 0
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scaleX = TARGET_WIDTH / width.toFloat()
            val scaleY = TARGET_HEIGHT / height.toFloat()
            // "cover" pour le paysage, "fit" pour le portrait ; jamais d'agrandissement
            val scale = min(1f, if (Framing.shouldShowWholeImage(width, height)) min(scaleX, scaleY) else max(scaleX, scaleY))
            targetWidth = (width * scale).roundToInt()
            targetHeight = (height * scale).roundToInt()
            if (scale < 1f) {
                if (exactSize) {
                    decoder.setTargetSize(targetWidth, targetHeight)
                } else {
                    // Plus grande puissance de 2 qui reste au-dessus de la taille visée
                    var sampleSize = 1
                    while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) sampleSize *= 2
                    decoder.setTargetSampleSize(sampleSize)
                }
            }
            // Bitmap logiciel : obligatoire pour pouvoir le compresser ensuite
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (decoded.width == targetWidth && decoded.height == targetHeight) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}

package com.nextclouddream.remote

import androidx.exifinterface.media.ExifInterface
import com.nextclouddream.photos.PhotoMetadata
import timber.log.Timber
import java.io.File

/** Lecture de la date de prise de vue et des coordonnées GPS dans l'EXIF d'une photo. */
object ExifReader {

    /** EXIF d'un fichier complet (JPEG, HEIC, PNG, WebP). */
    fun read(original: File): PhotoMetadata = read(original.name) { ExifInterface(original) }

    /** EXIF à partir du début d'un JPEG (l'EXIF est toujours en tête de fichier). */
    fun read(name: String, jpegHead: ByteArray): PhotoMetadata =
        read(name) { ExifInterface(jpegHead.inputStream()) }

    /** Lit la date de prise de vue et les coordonnées GPS. */
    private fun read(name: String, open: () -> ExifInterface): PhotoMetadata =
        try {
            val exif = open()
            val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            // (0, 0) = GPS présent mais vide sur certains téléphones : on l'ignore
            val latLong = exif.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }
            PhotoMetadata(
                dateTaken = date?.takeUnless { it.isBlank() || it.startsWith("0000") },
                latitude = latLong?.get(0),
                longitude = latLong?.get(1),
            )
        } catch (e: Exception) {
            Timber.w(e, "EXIF illisible : %s", name)
            PhotoMetadata()
        }
}

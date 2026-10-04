package com.nextclouddream.cache

import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Informations affichées sous la photo. Extraites de l'EXIF de l'original
 * pendant la synchro (les aperçus et versions réduites n'ont plus d'EXIF),
 * puis conservées dans l'index des photos ([PhotoIndexStore]).
 */
data class PhotoMetadata(
    /** Date EXIF brute, format "yyyy:MM:dd HH:mm:ss". */
    val dateTaken: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Lieu lisible ("Saint-Raphaël, France"), si le géocodage a réussi. */
    val place: String? = null,
) {
    val hasLocation: Boolean
        get() = latitude != null && longitude != null

    /** Date au format long de la langue de l'appareil (ex. "31 juillet 2017"). */
    fun formattedDate(locale: Locale = Locale.getDefault()): String? {
        val raw = dateTaken ?: return null
        return try {
            val date = SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).parse(raw) ?: return null
            DateFormat.getDateInstance(DateFormat.LONG, locale).format(date)
        } catch (e: Exception) {
            null
        }
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        dateTaken?.let { put(KEY_DATE, it) }
        latitude?.let { put(KEY_LAT, it) }
        longitude?.let { put(KEY_LON, it) }
        place?.let { put(KEY_PLACE, it) }
    }

    companion object {
        private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"
        private const val KEY_DATE = "date"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_PLACE = "place"

        fun fromJsonObject(obj: JSONObject): PhotoMetadata =
            PhotoMetadata(
                dateTaken = obj.optString(KEY_DATE).ifEmpty { null },
                latitude = if (obj.has(KEY_LAT)) obj.getDouble(KEY_LAT) else null,
                longitude = if (obj.has(KEY_LON)) obj.getDouble(KEY_LON) else null,
                place = obj.optString(KEY_PLACE).ifEmpty { null },
            )

        /** EXIF d'un fichier complet (JPEG, HEIC, PNG, WebP). */
        fun readExif(original: File): PhotoMetadata = readExif(original.name) { ExifInterface(original) }

        /** EXIF à partir du début d'un JPEG (l'EXIF est toujours en tête de fichier). */
        fun readExif(name: String, jpegHead: ByteArray): PhotoMetadata =
            readExif(name) { ExifInterface(jpegHead.inputStream()) }

        /** Lit la date de prise de vue et les coordonnées GPS. */
        private fun readExif(name: String, open: () -> ExifInterface): PhotoMetadata =
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
}

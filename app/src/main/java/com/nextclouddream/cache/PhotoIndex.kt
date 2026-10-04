package com.nextclouddream.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Une photo du dossier Nextcloud, telle que connue après la dernière synchro.
 * L'image elle-même n'est pas forcément en cache : elle est téléchargée à la volée.
 */
data class IndexedPhoto(
    /** Identifiant stable = nom du fichier dans le cache. Change si la photo est modifiée (ETag). */
    val key: String,
    val url: String,
    val name: String,
    val fileId: Long?,
    /** null tant que l'EXIF n'a pas pu être lu (nouvel essai à la synchro suivante). */
    val metadata: PhotoMetadata?,
)

/**
 * Index des photos, stocké en JSON dans filesDir. Écrit par la synchro, lu par
 * l'économiseur : il permet de démarrer instantanément et hors connexion,
 * sans refaire de PROPFIND.
 */
class PhotoIndexStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(): List<IndexedPhoto> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            Timber.w(e, "Index des photos illisible, il sera reconstruit")
            emptyList()
        }
    }

    /** Écriture atomique : l'économiseur ne lit jamais un index à moitié écrit. */
    @Synchronized
    fun save(photos: List<IndexedPhoto>) {
        val array = JSONArray()
        photos.forEach { array.put(toJson(it)) }
        val temp = File(file.parentFile, "$FILE_NAME.part")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Écriture de l'index impossible")
        }
    }

    private fun toJson(photo: IndexedPhoto) = JSONObject().apply {
        put(KEY_KEY, photo.key)
        put(KEY_URL, photo.url)
        put(KEY_NAME, photo.name)
        photo.fileId?.let { put(KEY_FILE_ID, it) }
        photo.metadata?.let { put(KEY_METADATA, it.toJsonObject()) }
    }

    private fun fromJson(obj: JSONObject) = IndexedPhoto(
        key = obj.getString(KEY_KEY),
        url = obj.getString(KEY_URL),
        name = obj.getString(KEY_NAME),
        fileId = if (obj.has(KEY_FILE_ID)) obj.getLong(KEY_FILE_ID) else null,
        metadata = obj.optJSONObject(KEY_METADATA)?.let(PhotoMetadata::fromJsonObject),
    )

    private companion object {
        const val FILE_NAME = "photo_index.json"
        const val KEY_KEY = "key"
        const val KEY_URL = "url"
        const val KEY_NAME = "name"
        const val KEY_FILE_ID = "fileId"
        const val KEY_METADATA = "metadata"
    }
}

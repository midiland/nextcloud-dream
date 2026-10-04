package com.nextclouddream.storage

import androidx.core.util.AtomicFile
import com.nextclouddream.photos.IndexedPhoto
import com.nextclouddream.photos.PhotoMetadata
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException

/**
 * Index des photos, stocké en JSON (filesDir/photo_index.json). Écrit par la synchro, lu par
 * l'économiseur : il permet de démarrer instantanément et hors connexion,
 * sans refaire de PROPFIND.
 */
class PhotoIndexStore(indexFile: File) {

    // AtomicFile : écriture dans un fichier à part, fsync puis remplacement ; un index
    // à moitié écrit (coupure de courant) n'est jamais lu à la place du précédent
    private val file = AtomicFile(indexFile)

    @Synchronized
    fun load(): List<IndexedPhoto> =
        try {
            val array = JSONArray(file.readFully().decodeToString())
            (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
        } catch (e: FileNotFoundException) {
            emptyList() // pas encore de synchro
        } catch (e: Exception) {
            Timber.w(e, "Index des photos illisible, il sera reconstruit")
            emptyList()
        }

    /** Écriture atomique : l'économiseur ne lit jamais un index à moitié écrit. */
    @Synchronized
    fun save(photos: List<IndexedPhoto>) {
        val array = JSONArray()
        photos.forEach { array.put(toJson(it)) }
        val out = file.startWrite()
        try {
            out.write(array.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
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
        const val KEY_KEY = "key"
        const val KEY_URL = "url"
        const val KEY_NAME = "name"
        const val KEY_FILE_ID = "fileId"
        const val KEY_METADATA = "metadata"
    }
}

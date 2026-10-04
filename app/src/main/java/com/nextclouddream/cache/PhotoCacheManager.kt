package com.nextclouddream.cache

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Cache de secours : les dernières photos affichées (ou préchargées), prêtes
 * à l'affichage. Taille plafonnée, les photos les moins récemment vues sont
 * supprimées en premier (LRU, via la date de modification du fichier).
 *
 * Il sert à deux choses :
 *  - éviter de re-télécharger une photo revue peu après
 *  - continuer le diaporama si le serveur Nextcloud est injoignable
 *
 * Stocké dans filesDir (et non cacheDir) : Android peut vider cacheDir quand
 * l'espace manque, ce qui casserait le fonctionnement hors connexion.
 */
class PhotoCacheManager(context: Context) {

    val directory: File = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val storageManager: StorageManager? = context.getSystemService()

    /** Photos complètes présentes en cache (les fichiers temporaires sont exclus). */
    fun listPhotos(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(PHOTO_SUFFIX) }
            .orEmpty()

    fun fileFor(key: String): File = File(directory, "$key$PHOTO_SUFFIX")

    /** Fichier de travail pour un téléchargement en cours (jamais listé comme photo). */
    fun tempFileFor(key: String, purpose: String): File = File(directory, "$key.$purpose$TEMP_SUFFIX")

    /**
     * Écrit une photo de façon atomique : [writer] remplit un fichier temporaire,
     * renommé seulement une fois complet. Le diaporama ne voit jamais d'image tronquée.
     */
    fun store(key: String, writer: (File) -> Unit): File {
        val target = fileFor(key)
        val temp = tempFileFor(key, "write")
        try {
            writer(temp)
            if (!temp.renameTo(target)) error("Renommage impossible : ${temp.name}")
        } finally {
            temp.delete()
        }
        return target
    }

    /** Marque la photo comme récemment utilisée (elle sera supprimée en dernier). */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Supprime les photos les moins récemment utilisées jusqu'à repasser sous [maxBytes]. */
    fun trim(maxBytes: Long) {
        val photos = listPhotos().sortedBy { it.lastModified() }
        var total = photos.sumOf { it.length() }
        for (file in photos) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
            Timber.d("Cache : %s supprimée (plus ancienne)", file.name)
        }
    }

    /**
     * Supprime les photos qui ne sont plus dans le dossier Nextcloud (ou modifiées),
     * ainsi que les restes de téléchargements interrompus et les fichiers d'anciennes versions.
     */
    fun retainOnly(keys: Set<String>) {
        directory.listFiles()
            ?.filter { !it.name.endsWith(PHOTO_SUFFIX) || it.name.removeSuffix(PHOTO_SUFFIX) !in keys }
            ?.forEach { file ->
                Timber.d("Cache : suppression de %s", file.name)
                file.delete()
            }
    }

    /**
     * Vrai s'il reste assez d'espace pour télécharger une photo sans mettre
     * l'appareil en saturation (espace réellement allouable, hors réserve système).
     */
    fun hasRoomForDownload(): Boolean {
        val allocatable = try {
            storageManager?.getAllocatableBytes(StorageManager.UUID_DEFAULT) ?: directory.usableSpace
        } catch (e: Exception) {
            directory.usableSpace
        }
        return allocatable > MIN_FREE_BYTES
    }

    companion object {
        private const val DIR_NAME = "photos"
        private const val PHOTO_SUFFIX = ".jpg"
        private const val TEMP_SUFFIX = ".part"

        // Marge pour un original téléchargé quand l'aperçu serveur n'est pas disponible
        private const val MIN_FREE_BYTES = 30L * 1024 * 1024

        /** Clé de cache d'une photo : change si le fichier est modifié sur Nextcloud (ETag). */
        fun keyFor(url: String, etag: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$url|$etag".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
    }
}

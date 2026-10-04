package com.nextclouddream.network

import android.content.Context
import com.nextclouddream.cache.ImageResizer
import com.nextclouddream.cache.IndexedPhoto
import com.nextclouddream.cache.PhotoCacheManager
import com.nextclouddream.cache.PhotoIndexStore
import com.nextclouddream.cache.PhotoMetadata
import com.nextclouddream.data.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber
import java.io.File

/**
 * Point d'accès unique aux photos, en mode hybride :
 *  - la synchro ne télécharge que la liste des photos et leurs métadonnées (index)
 *  - chaque image est téléchargée à la volée au moment de l'affichage,
 *    en version réduite par le serveur (aperçu Nextcloud)
 *  - les dernières photos vues restent dans un cache de secours plafonné,
 *    qui permet de continuer hors connexion
 *
 * Utilisé par l'économiseur d'écran, SyncWorker et l'écran de configuration.
 */
class PhotoRepository(context: Context) {

    private val settings = SettingsManager(context)
    private val cache = PhotoCacheManager(context)
    private val indexStore = PhotoIndexStore(context)
    private val placeResolver = PlaceResolver(context)

    /** Photos connues à la dernière synchro (disponible hors connexion). */
    suspend fun getIndex(): List<IndexedPhoto> = withContext(Dispatchers.IO) { indexStore.load() }

    /** Photos présentes dans le cache de secours. */
    suspend fun getCachedPhotos(): List<File> = withContext(Dispatchers.IO) { cache.listPhotos() }

    /**
     * Met à jour l'index depuis Nextcloud, puis remplit le cache de secours s'il est
     * presque vide (pour pouvoir fonctionner hors connexion dès le départ).
     * Ne lève jamais d'exception.
     * @return le nombre de photos dans le dossier.
     */
    suspend fun syncNow(): Result<Int> = withContext(Dispatchers.IO) {
        // Une seule synchro à la fois (économiseur + worker + écran de config)
        syncMutex.withLock {
            runCatching {
                check(settings.isConfigured) { "Application non configurée" }
                val client = client()
                val remotePhotos = client.listPhotos(settings.folderPath)

                // 1. Index à jour tout de suite (en reprenant les métadonnées déjà connues),
                //    pour que l'économiseur puisse démarrer sans attendre la lecture des EXIF
                val previous = indexStore.load().associateBy { it.key }
                var index = remotePhotos.map { remote ->
                    val key = PhotoCacheManager.keyFor(remote.url.toString(), remote.etag)
                    IndexedPhoto(key, remote.url.toString(), remote.name, remote.fileId, previous[key]?.metadata)
                }
                indexStore.save(index)
                cache.retainOnly(index.map { it.key }.toSet())

                // 2. Métadonnées (date, GPS, lieu) des nouvelles photos, enregistrées au fur et à mesure
                var enriched = 0
                val updated = index.toMutableList()
                for (i in updated.indices) {
                    ensureActive()
                    val metadata = completeMetadata(client, updated[i])
                    if (metadata != updated[i].metadata) {
                        updated[i] = updated[i].copy(metadata = metadata)
                        enriched++
                        if (enriched % SAVE_EVERY == 0) indexStore.save(updated)
                    }
                }
                index = updated
                indexStore.save(index)

                // 3. Cache de secours : quelques photos au hasard si le cache est presque vide
                val missing = PREWARM_COUNT - cache.listPhotos().size
                var prewarmed = 0
                if (missing > 0) {
                    for (photo in index.filterNot { cache.fileFor(it.key).exists() }.shuffled().take(missing)) {
                        ensureActive()
                        if (runCatching { downloadToCache(client, photo) }.isSuccess) prewarmed++
                    }
                }

                Timber.i(
                    "Synchro : %d photo(s), %d métadonnée(s) lue(s), %d préchargée(s), %d en cache",
                    index.size, enriched, prewarmed, cache.listPhotos().size,
                )
                index.size
            }.onFailure { e ->
                // Ne pas avaler une annulation de coroutine
                if (e is CancellationException) throw e
                Timber.w(e, "Synchro échouée")
            }
        }
    }

    /**
     * Fichier prêt à afficher : depuis le cache s'il y est, sinon téléchargé maintenant.
     * @return null si la photo n'a pas pu être obtenue (serveur injoignable, stockage plein…).
     */
    suspend fun fetchForDisplay(photo: IndexedPhoto): File? = withContext(Dispatchers.IO) {
        val cached = cache.fileFor(photo.key)
        if (cached.exists()) {
            cache.touch(cached)
            return@withContext cached
        }
        if (!settings.isConfigured) return@withContext null
        try {
            downloadToCache(client(), photo)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w("Téléchargement impossible : %s (%s)", photo.name, e.message)
            null
        }
    }

    /**
     * Télécharge une photo dans le cache : aperçu réduit par le serveur si possible,
     * sinon l'original, réduit sur l'appareil. Puis applique la limite de taille du cache.
     */
    private fun downloadToCache(client: NextcloudWebDavClient, photo: IndexedPhoto): File {
        check(cache.hasRoomForDownload()) { "Stockage de l'appareil presque plein" }
        val file = cache.store(photo.key) { destination ->
            val previewOk = photo.fileId != null && try {
                client.downloadPreview(photo.fileId, destination, PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
                true
            } catch (e: Exception) {
                Timber.i("Aperçu indisponible pour %s (%s), téléchargement de l'original", photo.name, e.message)
                false
            }
            if (!previewOk) downloadAndResizeOriginal(client, photo, destination)
        }
        cache.trim(settings.maxCacheMb * 1024L * 1024L)
        Timber.d("Cache : %s → %d Ko", photo.name, file.length() / 1024)
        return file
    }

    private fun downloadAndResizeOriginal(client: NextcloudWebDavClient, photo: IndexedPhoto, destination: File) {
        val original = cache.tempFileFor(photo.key, "original")
        try {
            client.download(photo.url.toHttpUrl(), original)
            // Si la réduction échoue, on garde l'original : le diaporama l'écartera s'il est illisible
            if (!ImageResizer.resizeToScreen(original, destination)) original.copyTo(destination, overwrite = true)
        } finally {
            original.delete()
        }
    }

    /**
     * Métadonnées d'une photo : lues dans l'EXIF si inconnues, et lieu calculé
     * si la photo a des coordonnées GPS mais pas encore de nom de lieu.
     */
    private fun completeMetadata(client: NextcloudWebDavClient, photo: IndexedPhoto): PhotoMetadata? {
        val metadata = photo.metadata ?: readRemoteMetadata(client, photo) ?: return null
        if (metadata.place != null || metadata.latitude == null || metadata.longitude == null) return metadata
        return metadata.copy(place = placeResolver.resolve(metadata.latitude, metadata.longitude))
    }

    /**
     * EXIF d'une photo distante. JPEG : seulement le début du fichier.
     * Autres formats (HEIC…) : l'EXIF peut être n'importe où, on télécharge tout.
     * @return null en cas d'erreur réseau (nouvel essai à la prochaine synchro).
     */
    private fun readRemoteMetadata(client: NextcloudWebDavClient, photo: IndexedPhoto): PhotoMetadata? =
        try {
            val url = photo.url.toHttpUrl()
            if (NextcloudWebDavClient.isJpeg(photo.name)) {
                PhotoMetadata.readExif(photo.name, client.readHead(url, EXIF_HEAD_BYTES))
            } else {
                val original = cache.tempFileFor(photo.key, "exif")
                try {
                    client.download(url, original)
                    PhotoMetadata.readExif(original)
                } finally {
                    original.delete()
                }
            }
        } catch (e: Exception) {
            Timber.w("Métadonnées illisibles pour %s (%s)", photo.name, e.message)
            null
        }

    private fun client() = NextcloudWebDavClient(settings.serverUrl, settings.username, settings.appPassword)

    private companion object {
        val syncMutex = Mutex()

        // Aperçu demandé au serveur : assez grand pour couvrir un écran 1080p après recadrage
        // (une photo 4:3 revient en 1920×1440, une photo en portrait en 1080×1440)
        const val PREVIEW_MAX_WIDTH = 2560
        const val PREVIEW_MAX_HEIGHT = 1440

        // L'EXIF d'un JPEG (miniature incluse) dépasse rarement 64 Ko
        const val EXIF_HEAD_BYTES = 256 * 1024

        // Photos téléchargées d'avance par la synchro, pour le mode hors connexion
        const val PREWARM_COUNT = 20

        const val SAVE_EVERY = 10
    }
}

package com.nextclouddream.photos

import com.nextclouddream.image.ImageResizer
import com.nextclouddream.remote.ExifReader
import com.nextclouddream.remote.HttpStatusException
import com.nextclouddream.remote.NextcloudWebDavClient
import com.nextclouddream.remote.PlaceResolver
import com.nextclouddream.settings.SettingsManager
import com.nextclouddream.storage.PhotoCacheManager
import com.nextclouddream.storage.PhotoIndexStore
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
 * Une seule instance par processus (AppContainer), partagée par l'économiseur,
 * SyncWorker et l'écran de configuration.
 */
class PhotoRepository(
    private val settings: SettingsManager,
    private val cache: PhotoCacheManager,
    private val indexStore: PhotoIndexStore,
    private val placeResolver: PlaceResolver,
) : PhotoSource {

    /** Photos connues à la dernière synchro (disponible hors connexion). */
    override suspend fun getIndex(): List<IndexedPhoto> = withContext(Dispatchers.IO) { indexStore.load() }

    /** Photos présentes dans le cache de secours. */
    override suspend fun getCachedPhotos(): List<File> = withContext(Dispatchers.IO) { cache.listPhotos() }

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

                // 2. Cache de secours d'abord (indispensable hors connexion) : quelques photos
                //    au hasard, dans la limite de ce que le cache peut contenir
                val prewarmTarget = minOf(PREWARM_COUNT, (maxCacheBytes() / AVERAGE_PREVIEW_BYTES).toInt())
                var prewarmed = 0
                for (photo in index.shuffled()) {
                    if (cache.listPhotos().size >= prewarmTarget) break
                    ensureActive()
                    // Revérifié à chaque fois : l'économiseur a pu la télécharger entre-temps
                    if (cache.fileFor(photo.key).exists()) continue
                    if (runCatching { downloadToCache(client, photo) }.isSuccess) prewarmed++
                }

                // 3. Métadonnées (date, GPS, lieu) des nouvelles photos, sauvegardées
                //    régulièrement pour que l'économiseur les affiche sans attendre la fin
                var enriched = 0
                var lastSave = System.currentTimeMillis()
                val updated = index.toMutableList()
                for (i in updated.indices) {
                    ensureActive()
                    val metadata = completeMetadata(client, updated[i])
                    if (metadata != updated[i].metadata) {
                        updated[i] = updated[i].copy(metadata = metadata)
                        enriched++
                        if (System.currentTimeMillis() - lastSave > SAVE_INTERVAL_MS) {
                            indexStore.save(updated)
                            lastSave = System.currentTimeMillis()
                        }
                    }
                }
                index = updated
                indexStore.save(index)

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
     * Photo prête à afficher : depuis le cache si elle y est, sinon téléchargée maintenant.
     * Distingue une photo indisponible (on passe à la suivante) d'un serveur injoignable
     * (l'économiseur bascule sur le cache de secours).
     */
    override suspend fun fetchForDisplay(photo: IndexedPhoto): FetchResult = withContext(Dispatchers.IO) {
        val cached = cache.fileFor(photo.key)
        if (cached.exists()) {
            cache.touch(cached)
            return@withContext FetchResult.Ready(cached)
        }
        if (!settings.isConfigured) return@withContext FetchResult.Offline
        try {
            FetchResult.Ready(downloadToCache(client(), photo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            Timber.w("Téléchargement impossible : %s (%s)", photo.name, e.message)
            if (e.isFileSpecific) FetchResult.Unavailable else FetchResult.Offline
        } catch (e: Exception) {
            Timber.w("Téléchargement impossible : %s (%s)", photo.name, e.message)
            FetchResult.Offline
        }
    }

    /** Retire une photo illisible du cache (elle sera re-téléchargée au prochain passage). */
    suspend fun evict(key: String) = withContext(Dispatchers.IO) { cache.evict(key) }

    /**
     * Télécharge une photo dans le cache : aperçu réduit par le serveur si possible,
     * sinon l'original, réduit sur l'appareil. Puis applique la limite de taille du cache.
     */
    private fun downloadToCache(client: NextcloudWebDavClient, photo: IndexedPhoto): File {
        check(cache.hasRoomForDownload()) { "Stockage de l'appareil presque plein" }
        val file = cache.store(photo.key) { destination ->
            // Repli sur l'original seulement si le serveur ne peut pas produire d'aperçu
            // pour ce fichier ; un timeout ou une erreur 5xx remonte (serveur en difficulté)
            val previewOk = photo.fileId != null && try {
                client.downloadPreview(photo.fileId, destination, PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
                true
            } catch (e: HttpStatusException) {
                if (!e.isFileSpecific) throw e
                Timber.i("Aperçu indisponible pour %s (HTTP %d), téléchargement de l'original", photo.name, e.code)
                false
            }
            if (!previewOk) downloadAndResizeOriginal(client, photo, destination)
        }
        cache.trim(maxCacheBytes())
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
     * HEIC : l'EXIF peut être n'importe où, on télécharge tout (si l'espace le permet).
     * PNG / WebP : pas d'EXIF en pratique, rien à télécharger.
     * @return null en cas d'erreur réseau (nouvel essai à la prochaine synchro).
     */
    private fun readRemoteMetadata(client: NextcloudWebDavClient, photo: IndexedPhoto): PhotoMetadata? =
        try {
            val url = photo.url.toHttpUrl()
            if (NextcloudWebDavClient.isJpeg(photo.name)) {
                ExifReader.read(photo.name, client.readHead(url, EXIF_HEAD_BYTES))
            } else if (!NextcloudWebDavClient.mayHaveExif(photo.name)) {
                PhotoMetadata()
            } else if (!cache.hasRoomForDownload()) {
                Timber.w("Stockage presque plein : métadonnées de %s lues plus tard", photo.name)
                null
            } else {
                val original = cache.tempFileFor(photo.key, "exif")
                try {
                    client.download(url, original)
                    ExifReader.read(original)
                } finally {
                    original.delete()
                }
            }
        } catch (e: Exception) {
            Timber.w("Métadonnées illisibles pour %s (%s)", photo.name, e.message)
            null
        }

    private fun maxCacheBytes() = settings.maxCacheMb * 1024L * 1024L

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

        // Taille moyenne observée d'un aperçu (~1 Mo) : borne le préchargement au plafond du cache
        const val AVERAGE_PREVIEW_BYTES = 1024L * 1024L

        const val SAVE_INTERVAL_MS = 30_000L
    }
}

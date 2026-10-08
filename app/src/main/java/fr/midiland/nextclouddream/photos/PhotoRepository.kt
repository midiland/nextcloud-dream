package fr.midiland.nextclouddream.photos

import fr.midiland.nextclouddream.image.ImageResizer
import fr.midiland.nextclouddream.remote.ExifReader
import fr.midiland.nextclouddream.remote.HttpStatusException
import fr.midiland.nextclouddream.remote.MotionPhoto
import fr.midiland.nextclouddream.remote.NextcloudWebDavClient
import fr.midiland.nextclouddream.remote.PlaceResolver
import fr.midiland.nextclouddream.settings.SettingsManager
import fr.midiland.nextclouddream.storage.PhotoCacheManager
import fr.midiland.nextclouddream.storage.PhotoIndexStore
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
    /** Clips des photos animées : dossier et plafond distincts du cache de secours. */
    private val motionCache: PhotoCacheManager,
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
                    val known = previous[key]
                    IndexedPhoto(
                        key = key,
                        url = remote.url.toString(),
                        name = remote.name,
                        fileId = remote.fileId,
                        metadata = known?.metadata,
                        size = remote.size,
                        // La vidéo d'une Live Photo est connue dès le listing ; celle d'un
                        // Motion Photo est cherchée plus bas, avec l'EXIF. Un jumeau que le
                        // serveur ne signale plus doit disparaître de l'index.
                        motion = remote.sidecar?.let { MotionRef.Sidecar(it.url.toString(), it.size) }
                            ?: known?.motion?.takeUnless { it is MotionRef.Sidecar },
                    )
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

                // 3. Métadonnées (date, GPS, lieu) et vidéo embarquée des nouvelles photos,
                //    sauvegardées régulièrement pour que l'économiseur les affiche sans
                //    attendre la fin
                var enriched = 0
                var lastSave = System.currentTimeMillis()
                val updated = index.toMutableList()
                for (i in updated.indices) {
                    ensureActive()
                    val details = completeDetails(client, updated[i])
                    if (details.metadata != updated[i].metadata || details.motion != updated[i].motion) {
                        updated[i] = updated[i].copy(metadata = details.metadata, motion = details.motion)
                        enriched++
                        if (System.currentTimeMillis() - lastSave > SAVE_INTERVAL_MS) {
                            indexStore.save(updated)
                            lastSave = System.currentTimeMillis()
                        }
                    }
                }
                index = updated
                indexStore.save(index)

                // Les clips ne sont gardés que pour les photos qui en ont encore une
                motionCache.retainOnly(index.filter { it.motion.hasVideo }.map { it.key }.toSet())

                Timber.i(
                    "Synchro : %d photo(s) dont %d animée(s), %d métadonnée(s) lue(s), %d préchargée(s), %d en cache",
                    index.size, index.count { it.motion.hasVideo }, enriched, prewarmed, cache.listPhotos().size,
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
     * Clip d'une photo animée, depuis le cache des clips ou téléchargé maintenant
     * (un simple GET pour une Live Photo, une requête Range pour un Motion Photo).
     *
     * **Ne lève jamais et n'est jamais indispensable** : une photo sans clip s'affiche
     * normalement, sans animation. C'est ce qui permet de ne rien casser quand le
     * réglage est désactivé, le stockage saturé, le serveur injoignable ou le format
     * inattendu.
     *
     * @return le fichier du clip, ou null s'il n'y en a pas (ou pas encore).
     */
    suspend fun fetchMotion(key: String, motion: MotionRef?): File? = withContext(Dispatchers.IO) {
        if (!settings.playLivePhotos || !motion.hasVideo) return@withContext null

        val cached = motionCache.fileFor(key)
        if (cached.exists()) {
            motionCache.touch(cached)
            return@withContext cached
        }
        if (!settings.isConfigured) return@withContext null

        val bytes = motion?.byteCount ?: 0L
        if (bytes <= 0 || bytes > MAX_MOTION_BYTES) {
            Timber.i("Clip de %s ignoré : %d Ko", key, bytes / 1024)
            return@withContext null
        }
        if (!motionCache.hasRoomForDownload()) {
            Timber.i("Stockage presque plein : clip de %s non téléchargé", key)
            return@withContext null
        }

        try {
            val client = client()
            val file = motionCache.store(key) { destination ->
                when (motion) {
                    is MotionRef.Sidecar -> client.download(motion.url.toHttpUrl(), destination)
                    is MotionRef.Trailer -> client.downloadRange(
                        url = motion.url.toHttpUrl(),
                        from = motion.start,
                        to = motion.start + motion.length - 1,
                        destination = destination,
                    )
                    // hasVideo a déjà écarté ce cas
                    MotionRef.None, null -> error("Aucune vidéo pour $key")
                }
            }
            motionCache.trim(MOTION_CACHE_BYTES)
            Timber.d("Clip : %s → %d Ko", key, file.length() / 1024)
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Clip indisponible pour %s (%s)", key, e.message)
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

    /** Ce qu'une seule lecture du début du fichier permet de connaître. */
    private class PhotoDetails(val metadata: PhotoMetadata?, val motion: MotionRef?)

    /**
     * Métadonnées et vidéo embarquée d'une photo. Les deux viennent du même début de
     * fichier : une seule lecture suffit, et elle n'a lieu que s'il manque l'une ou
     * l'autre. Le lieu est calculé si la photo a des coordonnées GPS sans nom de lieu.
     */
    private fun completeDetails(client: NextcloudWebDavClient, photo: IndexedPhoto): PhotoDetails {
        // Une Live Photo a été repérée au listing : son jumeau ne se lit pas dans le JPEG
        val knownMotion = photo.motion
        if (photo.metadata != null && knownMotion != null) {
            return PhotoDetails(withPlace(photo.metadata), knownMotion)
        }
        val read = readRemoteDetails(client, photo)
        return PhotoDetails(
            metadata = (photo.metadata ?: read?.metadata)?.let(::withPlace),
            motion = knownMotion ?: read?.motion,
        )
    }

    private fun withPlace(metadata: PhotoMetadata): PhotoMetadata {
        if (metadata.place != null || metadata.latitude == null || metadata.longitude == null) return metadata
        return metadata.copy(place = placeResolver.resolve(metadata.latitude, metadata.longitude))
    }

    /**
     * EXIF et vidéo embarquée d'une photo distante. JPEG : seulement le début du
     * fichier, qui porte les deux. HEIC : l'EXIF peut être n'importe où, on télécharge
     * tout (si l'espace le permet). PNG / WebP : pas d'EXIF en pratique, rien à lire.
     * @return null en cas d'erreur réseau (nouvel essai à la prochaine synchro).
     */
    private fun readRemoteDetails(client: NextcloudWebDavClient, photo: IndexedPhoto): PhotoDetails? =
        try {
            val url = photo.url.toHttpUrl()
            when {
                NextcloudWebDavClient.isJpeg(photo.name) -> {
                    val head = client.readHead(url, EXIF_HEAD_BYTES)
                    PhotoDetails(ExifReader.read(photo.name, head), trailerOf(photo, head))
                }
                // Les formats sans EXIF n'ont pas de vidéo embarquée non plus
                !NextcloudWebDavClient.mayHaveExif(photo.name) -> PhotoDetails(PhotoMetadata(), MotionRef.None)
                !cache.hasRoomForDownload() -> {
                    Timber.w("Stockage presque plein : métadonnées de %s lues plus tard", photo.name)
                    null
                }
                else -> {
                    val original = cache.tempFileFor(photo.key, "exif")
                    try {
                        client.download(url, original)
                        PhotoDetails(ExifReader.read(original), MotionRef.None)
                    } finally {
                        original.delete()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Métadonnées illisibles pour %s (%s)", photo.name, e.message)
            null
        }

    /**
     * Vidéo collée à la fin du JPEG, si le XMP en décrit une. La taille du fichier vient
     * du listing : sans elle, impossible de situer la vidéo dans le fichier.
     */
    private fun trailerOf(photo: IndexedPhoto, head: ByteArray): MotionRef {
        val trailer = MotionPhoto.findTrailer(head) ?: return MotionRef.None
        val start = photo.size - trailer.offsetFromEnd
        if (start <= 0) {
            Timber.w("Vidéo annoncée hors des limites de %s (%d octets)", photo.name, photo.size)
            return MotionRef.None
        }
        Timber.i("Photo animée : %s (clip de %d Ko)", photo.name, trailer.length / 1024)
        return MotionRef.Trailer(photo.url, start, trailer.length, trailer.startUs)
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

        // Clips mesurés sur de vrais fichiers : 3,5 Mo (Pixel) à 6 Mo (iPhone). Au-delà
        // du plafond, ce n'est pas une photo animée mais une vidéo, qu'on ne joue pas.
        const val MAX_MOTION_BYTES = 20L * 1024 * 1024

        // Place réservée aux clips : de quoi en garder trois, pas de quoi entamer
        // les 50 Mo du cache de secours (qui, lui, sert au mode hors connexion)
        const val MOTION_CACHE_BYTES = 20L * 1024 * 1024

        const val SAVE_INTERVAL_MS = 30_000L
    }
}

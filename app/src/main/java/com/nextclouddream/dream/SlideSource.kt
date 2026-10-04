package com.nextclouddream.dream

import com.nextclouddream.photos.FetchResult
import com.nextclouddream.photos.IndexedPhoto
import com.nextclouddream.photos.PhotoMetadata
import com.nextclouddream.photos.PhotoSource
import timber.log.Timber
import java.io.File
import kotlin.random.Random

/** Photo prête à l'écran : fichier local et informations à afficher. */
class Slide(val key: String, val file: File, val metadata: PhotoMetadata?)

/**
 * Choisit la prochaine photo à afficher :
 *  - en ligne : depuis le cache de secours si elle y est, sinon téléchargée maintenant ;
 *    une photo indisponible (supprimée, aperçu impossible) est simplement sautée ;
 *  - serveur injoignable : photos de la playlist déjà en cache, et le réseau
 *    n'est retenté qu'après OFFLINE_RETRY_MS.
 *
 * Indépendant d'Android (source et horloge injectées) : testable sur la JVM.
 * Non thread-safe : appelé depuis un seul thread (le thread principal de l'économiseur).
 */
class SlideSource(
    private val photos: PhotoSource,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var playlist = PhotoPlaylist<String>(emptyList(), random)

    /** Index courant (clé → photo), relu à chaque fin de cycle. */
    private var indexByKey: Map<String, IndexedPhoto> = emptyMap()

    /** Après un échec réseau, on reste sur le cache de secours jusqu'à cette date. */
    private var offlineUntil = 0L

    /** Dernière relecture de l'index pour récupérer des métadonnées encore absentes. */
    private var lastMetadataRefresh = 0L

    /** Vrai tant que l'on reste sur le cache de secours après un échec réseau. */
    val isOffline: Boolean
        get() = now() < offlineUntil

    /**
     * Repart avec ces photos. [index] peut être vide : au premier lancement sans
     * connexion, seules les photos du cache de secours (leurs clés) sont connues.
     */
    fun reset(keys: List<String>, index: List<IndexedPhoto>) {
        playlist = PhotoPlaylist(keys, random)
        indexByKey = index.associateBy { it.key }
    }

    /** Écarte une photo de ce cycle (ex. fichier illisible). */
    fun remove(key: String) {
        playlist.remove(key)
    }

    /** Prochaine photo affichable, ou null si ni le serveur ni le cache n'en fournissent. */
    suspend fun next(): Slide? {
        repeat(MAX_UNAVAILABLE_IN_A_ROW) {
            if (playlist.isCycleOver) refreshIndex()
            val key = playlist.next() ?: return null
            val photo = photoWithMetadata(key)

            if (photo == null || isOffline) return cachedSlide(key)

            when (val result = photos.fetchForDisplay(photo)) {
                is FetchResult.Ready -> return Slide(key, result.file, photo.metadata)
                FetchResult.Unavailable -> Unit // photo suivante
                FetchResult.Offline -> {
                    Timber.w("Serveur injoignable, bascule sur le cache de secours")
                    offlineUntil = now() + OFFLINE_RETRY_MS
                    return cachedSlide(key)
                }
            }
        }
        return null
    }

    /** Relit l'index (mis à jour par SyncWorker) pour intégrer photos et métadonnées nouvelles. */
    private suspend fun refreshIndex() {
        val index = photos.getIndex()
        lastMetadataRefresh = now()
        if (index.isEmpty()) return
        indexByKey = index.associateBy { it.key }
        playlist.updatePhotos(index.map { it.key })
    }

    /**
     * Photo de l'index. Au premier lancement, la synchro lit les métadonnées en même
     * temps que le diaporama démarre : l'index est relu (au plus une fois par minute)
     * pour les récupérer dès qu'elles sont prêtes.
     */
    private suspend fun photoWithMetadata(key: String): IndexedPhoto? {
        val photo = indexByKey[key]
        val time = now()
        if (photo != null && photo.metadata == null && time - lastMetadataRefresh > METADATA_REFRESH_MS) {
            lastMetadataRefresh = time
            indexByKey = photos.getIndex().associateBy { it.key }.ifEmpty { indexByKey }
            return indexByKey[key]
        }
        return photo
    }

    /**
     * Hors ligne : prochaine photo de la playlist (à partir de [key]) présente dans le cache.
     * On parcourt jusqu'à deux cycles : une fenêtre d'un seul cycle, à cheval sur deux
     * cycles, peut ne contenir aucune des photos en cache ; deux cycles en contiennent
     * forcément un complet, donc toutes les photos.
     */
    private suspend fun cachedSlide(key: String): Slide? {
        val cached = photos.getCachedPhotos().associateBy { it.nameWithoutExtension }
        if (cached.isEmpty()) return null
        var candidate: String? = key
        repeat(2 * playlist.size) {
            val current = candidate ?: return null
            cached[current]?.let { file -> return Slide(current, file, indexByKey[current]?.metadata) }
            candidate = playlist.next()
        }
        return null
    }

    companion object {
        const val OFFLINE_RETRY_MS = 5 * 60_000L
        const val METADATA_REFRESH_MS = 60_000L

        // Photos indisponibles consécutives avant d'abandonner pour ce tour
        const val MAX_UNAVAILABLE_IN_A_ROW = 10
    }
}

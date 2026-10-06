package fr.midiland.nextclouddream.dream

import kotlin.random.Random

/**
 * Ordre de passage des photos : mélange aléatoire par cycles complets,
 * chaque photo passe une fois par cycle.
 *
 * Pour éviter les répétitions rapprochées à la jonction de deux cycles,
 * les photos vues récemment sont repoussées en fin du cycle suivant.
 */
class PhotoPlaylist<T>(
    photos: List<T>,
    private val random: Random = Random.Default,
) {
    private var photos: List<T> = photos.distinct()
    private val queue = ArrayDeque<T>()
    private val recent = ArrayDeque<T>()

    val size: Int
        get() = photos.size

    /** Vrai quand le cycle courant est terminé (moment idéal pour relire le cache). */
    val isCycleOver: Boolean
        get() = queue.isEmpty()

    /** Photo suivante, ou null si la playlist est vide. */
    fun next(): T? {
        if (queue.isEmpty()) refill()
        val photo = queue.removeFirstOrNull() ?: return null
        recent.addLast(photo)
        while (recent.size > historySize()) recent.removeFirst()
        return photo
    }

    /**
     * Remplace la liste des photos disponibles. Les nouvelles photos entreront
     * au prochain cycle ; celles supprimées du cache sont retirées tout de suite.
     */
    fun updatePhotos(newPhotos: List<T>) {
        photos = newPhotos.distinct()
        val available = photos.toSet()
        queue.retainAll(available)
        recent.retainAll(available)
    }

    /** Retire définitivement une photo (ex. fichier illisible). */
    fun remove(photo: T) {
        photos = photos - photo
        queue.remove(photo)
        recent.remove(photo)
    }

    private fun refill() {
        if (photos.isEmpty()) return
        val recentSet = recent.toSet()
        val (seenRecently, fresh) = photos.shuffled(random).partition { it in recentSet }
        queue.addAll(fresh)
        queue.addAll(seenRecently)
    }

    // Historique = moitié de la collection, plafonné : avec 4 photos on n'en écarte que 2.
    // Minimum 1 : au démarrage la playlist peut ne contenir qu'une photo (synchro en cours),
    // elle ne doit pas repasser en tête quand les autres arrivent.
    private fun historySize(): Int = (photos.size / 2).coerceIn(1, MAX_HISTORY)

    private companion object {
        const val MAX_HISTORY = 30
    }
}

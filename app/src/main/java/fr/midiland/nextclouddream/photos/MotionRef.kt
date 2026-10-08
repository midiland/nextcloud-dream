package fr.midiland.nextclouddream.photos

import org.json.JSONObject

/**
 * Vidéo associée à une photo animée. Deux provenances, deux façons de la récupérer :
 *  - iPhone (Live Photo) : un fichier distinct à côté de la photo, que le serveur
 *    Nextcloud désigne lui-même (propriété `nc:metadata-files-live-photo`) ;
 *  - Pixel (Motion Photo) : un MP4 collé à la fin du JPEG, récupéré par une
 *    requête Range, sans télécharger la photo.
 *
 * L'URL est portée par la référence pour qu'elle suffise seule au téléchargement :
 * le diaporama n'a pas toujours l'entrée d'index correspondante sous la main.
 *
 * Dans [IndexedPhoto] le champ est nullable, et les deux états se distinguent :
 * **null = photo pas encore examinée**, [None] = examinée et sans vidéo. L'état est
 * conservé dans l'index pour ne sonder chaque photo qu'une fois.
 */
/** Vrai si une vidéo exploitable est connue (ni absente, ni encore à chercher). */
val MotionRef?.hasVideo: Boolean
    get() = this != null && this != MotionRef.None

sealed interface MotionRef {

    /** Live Photo iPhone : la vidéo est le fichier [url]. */
    data class Sidecar(val url: String, val size: Long) : MotionRef

    /**
     * Motion Photo Pixel : la vidéo occupe [length] octets de [url], à partir de
     * [start]. [startUs] situe l'image fixe dans le clip : y commencer la lecture
     * rend le passage de la photo à la vidéo invisible.
     */
    data class Trailer(
        val url: String,
        val start: Long,
        val length: Long,
        val startUs: Long?,
    ) : MotionRef

    /** Photo examinée, sans vidéo. */
    data object None : MotionRef

    /** Octets à télécharger pour obtenir le clip. */
    val byteCount: Long
        get() = when (this) {
            is Sidecar -> size
            is Trailer -> length
            None -> 0L
        }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        when (val ref = this@MotionRef) {
            is Sidecar -> {
                put(KEY_KIND, KIND_SIDECAR)
                put(KEY_URL, ref.url)
                put(KEY_SIZE, ref.size)
            }
            is Trailer -> {
                put(KEY_KIND, KIND_TRAILER)
                put(KEY_URL, ref.url)
                put(KEY_START, ref.start)
                put(KEY_LENGTH, ref.length)
                ref.startUs?.let { put(KEY_START_US, it) }
            }
            None -> put(KEY_KIND, KIND_NONE)
        }
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_URL = "url"
        private const val KEY_SIZE = "size"
        private const val KEY_START = "start"
        private const val KEY_LENGTH = "length"
        private const val KEY_START_US = "startUs"

        private const val KIND_SIDECAR = "sidecar"
        private const val KIND_TRAILER = "trailer"
        private const val KIND_NONE = "none"

        /** @return null si la forme est inconnue : la photo sera simplement réexaminée. */
        fun fromJsonObject(obj: JSONObject): MotionRef? =
            when (obj.optString(KEY_KIND)) {
                KIND_SIDECAR -> Sidecar(obj.optString(KEY_URL), obj.optLong(KEY_SIZE))
                KIND_TRAILER -> Trailer(
                    url = obj.optString(KEY_URL),
                    start = obj.optLong(KEY_START),
                    length = obj.optLong(KEY_LENGTH),
                    startUs = if (obj.has(KEY_START_US)) obj.getLong(KEY_START_US) else null,
                )
                KIND_NONE -> None
                else -> null
            }
    }
}

package fr.midiland.nextclouddream.photos

/** Contenu du dossier après une synchro : photos (animées ou non) et vidéos seules. */
data class MediaCount(val photos: Int, val videos: Int) {
    val total: Int
        get() = photos + videos
}

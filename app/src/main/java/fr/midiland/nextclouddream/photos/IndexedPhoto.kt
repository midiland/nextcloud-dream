package fr.midiland.nextclouddream.photos

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

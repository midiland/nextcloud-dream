package fr.midiland.nextclouddream.photos

import java.io.File

/** Résultat d'une demande de photo à afficher. */
sealed interface FetchResult {
    /** Photo prête, en cache local. */
    class Ready(val file: File) : FetchResult

    /** Vidéo joignable, à lire en streaming (rien n'est téléchargé à l'avance). */
    class Stream(val stream: VideoStream) : FetchResult

    /** Cette photo-là est indisponible (supprimée, aperçu impossible…) : passer à la suivante. */
    data object Unavailable : FetchResult

    /** Serveur injoignable ou stockage plein : basculer sur le cache de secours un moment. */
    data object Offline : FetchResult
}

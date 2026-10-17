package fr.midiland.nextclouddream.photos

/**
 * Vidéo à lire en streaming : son adresse et les en-têtes HTTP à envoyer
 * (authentification Basic).
 *
 * Volontairement **pas** une data class : son toString() ne doit jamais exposer
 * l'en-tête Authorization, qui contient le mot de passe d'application (logs,
 * messages d'erreur). Pour la même raison, l'authentification n'est jamais
 * placée dans l'URL : MediaPlayer journalise les URL qu'il ouvre.
 */
class VideoStream(val url: String, val headers: Map<String, String>) {
    override fun toString() = "VideoStream($url)"
}

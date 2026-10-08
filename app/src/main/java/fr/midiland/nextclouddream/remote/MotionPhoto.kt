package fr.midiland.nextclouddream.remote

/** Vidéo collée à la fin d'un JPEG (« Motion Photo » des Pixel). */
data class MotionTrailer(
    /** Longueur de la vidéo, en octets. */
    val length: Long,
    /** Distance entre le premier octet de la vidéo et la fin du fichier. */
    val offsetFromEnd: Long,
    /** Position de l'image fixe dans le clip, en microsecondes (null si non indiquée). */
    val startUs: Long?,
)

/**
 * Repère la vidéo d'un Motion Photo dans l'en-tête d'un JPEG. Les Pixel collent le MP4
 * à la fin du fichier et le décrivent dans le XMP, placé en tête : les 256 Ko déjà
 * téléchargés pour l'EXIF suffisent donc, la détection ne coûte aucune requête.
 *
 * Deux formats, celui des Pixel récents d'abord :
 *  - `Container:Directory` : une liste ordonnée d'items, la vidéo en est un ;
 *  - `GCamera:MicroVideoOffset` : un simple nombre d'octets (Pixel 2 et avant).
 *
 * Indépendant d'Android (simple analyse de texte) : testable sur la JVM.
 *
 * Trois pièges relevés sur de vrais fichiers, qui justifient cette prudence :
 *  1. la présence de `GCamera` ou d'un `Container` ne signifie pas qu'il y a une vidéo :
 *     les photos Ultra HDR ont un Container qui ne décrit qu'une carte de gain ;
 *  2. la longueur de la vidéo doit être lue sur **son** item : chercher le premier
 *     `Length` près de `video/mp4` renvoie celle de la carte de gain ;
 *  3. le JPEG contient un second paquet XMP (le XMP « étendu ») découpé en segments
 *     de 64 Ko, donc tronqué dans ce qu'on lit : il est ignoré sans bruit.
 */
object MotionPhoto {

    /**
     * @param jpegHead début du fichier JPEG (l'EXIF et le XMP y sont).
     * @return la position de la vidéo, ou null si la photo n'est pas animée.
     */
    fun findTrailer(jpegHead: ByteArray): MotionTrailer? {
        // ISO-8859-1 : conserve les octets un pour un (le XMP est en UTF-8, mais on
        // n'y cherche que des motifs ASCII, et un octet invalide ne doit rien casser)
        val head = String(jpegHead, Charsets.ISO_8859_1)
        for (packet in xmpPackets(head)) {
            microVideoTrailer(packet)?.let { return it }
            containerTrailer(packet)?.let { return it }
        }
        return null
    }

    /** Paquets XMP complets. Un paquet sans balise de fin est tronqué : on l'ignore. */
    private fun xmpPackets(head: String): List<String> {
        val packets = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = head.indexOf(PACKET_START, from)
            if (start < 0) return packets
            val end = head.indexOf(PACKET_END, start)
            if (end < 0) return packets
            packets += head.substring(start, end)
            from = end + PACKET_END.length
        }
    }

    /**
     * Format récent : la remorque du fichier est la concaténation des items qui suivent
     * l'image principale, dans l'ordre de la liste. La vidéo commence donc à la somme
     * des longueurs de son propre item et de tous les suivants, comptée depuis la fin.
     */
    private fun containerTrailer(packet: String): MotionTrailer? {
        val items = packet.split(ITEM).drop(1).map { chunk ->
            ContainerItem(
                mime = MIME.find(chunk)?.groupValues?.get(1).orEmpty(),
                length = chunk.longOrZero(LENGTH),
                padding = chunk.longOrZero(PADDING),
            )
        }
        val video = items.indexOfFirst { it.mime.startsWith("video/") }
        if (video < 0) return null
        val length = items[video].length
        if (length <= 0) return null
        return MotionTrailer(
            length = length,
            offsetFromEnd = items.drop(video).sumOf { it.length + it.padding },
            startUs = packet.positiveLongOrNull(MOTION_PHOTO_START),
        )
    }

    /** Ancien format : l'offset depuis la fin du fichier est donné directement. */
    private fun microVideoTrailer(packet: String): MotionTrailer? {
        val offset = packet.positiveLongOrNull(MICRO_VIDEO_OFFSET) ?: return null
        return MotionTrailer(
            length = offset,
            offsetFromEnd = offset,
            startUs = packet.positiveLongOrNull(MICRO_VIDEO_START),
        )
    }

    private class ContainerItem(val mime: String, val length: Long, val padding: Long)

    private fun String.longOrZero(pattern: Regex): Long =
        pattern.find(this)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** Les horodatages valent -1 quand l'appareil ne les connaît pas. */
    private fun String.positiveLongOrNull(pattern: Regex): Long? =
        pattern.find(this)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

    private const val PACKET_START = "<x:xmpmeta"
    private const val PACKET_END = "</x:xmpmeta>"

    // Les attributs s'écrivent indifféremment Nom="valeur" ou <Nom>valeur</Nom>, et le
    // préfixe de namespace varie selon les versions de l'appareil (Container, GContainer…)
    private val ITEM = Regex(""":Item\b""")
    private val MIME = Regex("""Mime[=>"\s]*([\w/+.-]+)""")
    private val LENGTH = Regex("""Length[=>"\s]*(\d+)""")
    private val PADDING = Regex("""Padding[=>"\s]*(\d+)""")
    private val MOTION_PHOTO_START = Regex("""MotionPhotoPresentationTimestampUs[=>"\s]*(-?\d+)""")
    private val MICRO_VIDEO_OFFSET = Regex("""MicroVideoOffset[=>"\s]*(\d+)""")
    private val MICRO_VIDEO_START = Regex("""MicroVideoPresentationTimestampUs[=>"\s]*(-?\d+)""")
}

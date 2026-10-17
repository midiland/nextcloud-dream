package fr.midiland.nextclouddream.remote

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date de tournage et position d'une vidéo MP4 / QuickTime (`.mp4`, `.mov`), lues
 * dans ses boîtes sans la télécharger : quelques petites lectures par Range.
 *
 * Où se trouvent les informations :
 *  - date : `moov/mvhd` (heure UTC, secondes depuis 1904), ou la clé QuickTime
 *    `com.apple.quicktime.creationdate` (heure locale de l'iPhone), prioritaire ;
 *  - position : `moov/udta/©xyz` (téléphones Android, ffmpeg) ou la clé QuickTime
 *    `com.apple.quicktime.location.ISO6709` (iPhone), au format ISO 6709.
 *
 * L'index `moov` peut être au début du fichier ou **à la fin**, après les données
 * (`mdat`, plusieurs centaines de Mo) : on saute de boîte en boîte en ne lisant que
 * leurs en-têtes. MediaMetadataRetriever n'est pas utilisé : bloquant, sans délai
 * maximal et ininterruptible pendant la synchro.
 *
 * Indépendant d'Android : testé sur la JVM avec des fichiers fabriqués.
 */
object Mp4Metadata {

    class Info(val dateTaken: String?, val latitude: Double?, val longitude: Double?)

    // Le moov d'une vidéo de téléphone pèse quelques centaines de Ko ; au-delà de ce
    // plafond, on renonce plutôt que de charger plusieurs Mo en mémoire
    private const val MAX_MOOV_BYTES = 8 * 1024 * 1024
    // Premier morceau lu d'un coup : couvre ftyp + moov quand moov est au début
    private const val FIRST_READ_BYTES = 64 * 1024
    private const val MAX_TOP_LEVEL_BOXES = 64

    // Secondes entre le 1er janvier 1904 (origine MP4) et le 1er janvier 1970
    private const val SECONDS_1904_TO_1970 = 2_082_844_800L
    // Avant 1990 : horloge non réglée (date 0 = 1904), pas une vraie date de tournage
    private const val MIN_PLAUSIBLE_UNIX_SECONDS = 631_152_000L

    private const val KEY_APPLE_LOCATION = "com.apple.quicktime.location.ISO6709"
    private const val KEY_APPLE_DATE = "com.apple.quicktime.creationdate"
    private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"

    /**
     * @param fileSize taille du fichier (connue par le listing WebDAV).
     * @param readAt lit `length` octets à partir de `offset` (peut en rendre moins en fin
     *        de fichier). Ses IOException (réseau) remontent : la vidéo sera réexaminée
     *        à la prochaine synchro.
     * @return null si le fichier n'est pas un MP4 lisible ou n'a pas de `moov`.
     */
    fun read(
        fileSize: Long,
        readAt: (offset: Long, length: Int) -> ByteArray,
        zone: TimeZone = TimeZone.getDefault(),
    ): Info? {
        if (fileSize < 8) return null
        return try {
            val moov = findMoov(fileSize, readAt) ?: return null
            parseMoov(moov, zone)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Fichier tronqué ou boîte incohérente : pas de métadonnées, mais jamais d'exception
            null
        }
    }

    /** Contenu de la boîte `moov` (sans son en-tête), ou null. */
    private fun findMoov(fileSize: Long, readAt: (Long, Int) -> ByteArray): ByteArray? {
        val first = readAt(0, minOf(fileSize, FIRST_READ_BYTES.toLong()).toInt())
        // Lecture à une position : depuis le premier morceau si possible, sinon par Range
        fun bytesAt(offset: Long, length: Int): ByteArray =
            if (offset + length <= first.size) first.copyOfRange(offset.toInt(), (offset + length).toInt())
            else readAt(offset, length)

        var offset = 0L
        repeat(MAX_TOP_LEVEL_BOXES) {
            if (offset + 8 > fileSize) return null
            val header = bytesAt(offset, minOf(16L, fileSize - offset).toInt())
            if (header.size < 8) return null
            val size32 = u32(header, 0)
            val type = fourCc(header, 4)
            val (size, headerLength) = when (size32) {
                1L -> if (header.size >= 16) u64(header, 8) to 16 else return null
                0L -> (fileSize - offset) to 8
                else -> size32 to 8
            }
            if (size < headerLength) return null
            if (type == "moov") {
                val contentLength = size - headerLength
                if (contentLength > MAX_MOOV_BYTES) return null
                return bytesAt(offset + headerLength, contentLength.toInt())
            }
            offset += size
        }
        return null
    }

    private fun parseMoov(moov: ByteArray, zone: TimeZone): Info {
        var mvhdDate: String? = null
        var xyz: Pair<Double, Double>? = null
        var appleDate: String? = null
        var appleLocation: Pair<Double, Double>? = null

        for (box in boxes(moov, 0, moov.size)) {
            when (box.type) {
                "mvhd" -> {
                    val version = moov[box.start].toInt()
                    val created = if (version == 1) u64(moov, box.start + 4) else u32(moov, box.start + 4)
                    mvhdDate = mvhdDate(created, zone)
                }
                "udta" -> for (child in boxes(moov, box.start, box.end)) {
                    when (child.type) {
                        "©xyz" -> {
                            // longueur du texte (2 octets), langue (2 octets), puis le texte
                            val length = u16(moov, child.start)
                            xyz = parseIso6709(String(moov, child.start + 4, length, Charsets.UTF_8))
                        }
                        "meta" -> readKeyedMeta(moov, child.start, child.end).let {
                            appleDate = appleDate ?: it[KEY_APPLE_DATE]?.let(::appleDate)
                            appleLocation = appleLocation ?: it[KEY_APPLE_LOCATION]?.let(::parseIso6709)
                        }
                    }
                }
                "meta" -> readKeyedMeta(moov, box.start, box.end).let {
                    appleDate = appleDate ?: it[KEY_APPLE_DATE]?.let(::appleDate)
                    appleLocation = appleLocation ?: it[KEY_APPLE_LOCATION]?.let(::parseIso6709)
                }
            }
        }
        val location = appleLocation ?: xyz
        return Info(dateTaken = appleDate ?: mvhdDate, latitude = location?.first, longitude = location?.second)
    }

    /**
     * Valeurs texte d'une boîte `meta` QuickTime (`keys` + `ilst`), indexées par clé.
     *
     * Piège : la `meta` QuickTime (iPhone, sous `moov`) n'a **pas** les 4 octets
     * version/flags d'une boîte MP4 complète, celle sous `udta` les a. On le détecte
     * en regardant si la première boîte enfant (`hdlr`) commence tout de suite.
     */
    private fun readKeyedMeta(buf: ByteArray, start: Int, end: Int): Map<String, String> {
        val childrenStart = if (end - start >= 8 && fourCc(buf, start + 4) == "hdlr") start else start + 4
        var keys = emptyList<String>()
        val values = mutableMapOf<Int, String>()
        for (box in boxes(buf, childrenStart, end)) {
            when (box.type) {
                "keys" -> keys = readKeys(buf, box.start, box.end)
                "ilst" -> for (item in boxes(buf, box.start, box.end)) {
                    // Le "type" d'un élément d'ilst est l'index (base 1) de sa clé
                    val keyIndex = u32(buf, item.start - 4).toInt()
                    val data = boxes(buf, item.start, item.end).firstOrNull { it.type == "data" } ?: continue
                    // data : type (4 octets) + langue (4 octets) + valeur
                    val valueStart = data.start + 8
                    if (valueStart <= data.end) {
                        values[keyIndex] = String(buf, valueStart, data.end - valueStart, Charsets.UTF_8)
                    }
                }
            }
        }
        return values.mapNotNull { (index, value) -> keys.getOrNull(index - 1)?.let { it to value } }.toMap()
    }

    /** Boîte `keys` : version/flags, nombre de clés, puis (taille, espace de noms, nom). */
    private fun readKeys(buf: ByteArray, start: Int, end: Int): List<String> {
        val count = u32(buf, start + 4).toInt()
        val keys = mutableListOf<String>()
        var offset = start + 8
        repeat(count) {
            if (offset + 8 > end) return keys
            val size = u32(buf, offset).toInt()
            if (size < 8 || offset + size > end) return keys
            keys += String(buf, offset + 8, size - 8, Charsets.UTF_8)
            offset += size
        }
        return keys
    }

    private class Box(val type: String, val start: Int, val end: Int)

    /** Boîtes enfants de [start, end) : type et contenu (sans en-tête). */
    private fun boxes(buf: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var offset = start
        while (offset + 8 <= end) {
            val size32 = u32(buf, offset)
            val type = fourCc(buf, offset + 4)
            val (size, headerLength) = when (size32) {
                1L -> if (offset + 16 <= end) u64(buf, offset + 8) to 16 else break
                0L -> (end - offset).toLong() to 8
                else -> size32 to 8
            }
            if (size < headerLength || offset + size > end) break
            result += Box(type, offset + headerLength, (offset + size).toInt())
            offset += size.toInt()
        }
        return result
    }

    /**
     * Position ISO 6709 en degrés décimaux : `+48.8566+002.3522/`, avec ou sans
     * altitude. Les autres formes (degrés-minutes-secondes) sont refusées plutôt que
     * mal interprétées. (0, 0) est un GPS vide, comme pour les photos.
     */
    internal fun parseIso6709(value: String): Pair<Double, Double>? {
        val match = ISO6709.matchEntire(value.trim()) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (kotlin.math.abs(latitude) > 90 || kotlin.math.abs(longitude) > 180) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return latitude to longitude
    }

    // Latitude ±DD(.d), longitude ±DDD(.d), altitude facultative, système (CRS) facultatif
    private val ISO6709 = Regex("""^([+-]\d{1,2}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)(?:[+-]\d+(?:\.\d+)?)?(?:CRS[^/]*)?/?$""")

    /** Date `mvhd` (UTC) convertie dans le fuseau local, au format EXIF. */
    internal fun mvhdDate(secondsSince1904: Long, zone: TimeZone): String? {
        val unixSeconds = secondsSince1904 - SECONDS_1904_TO_1970
        if (unixSeconds < MIN_PLAUSIBLE_UNIX_SECONDS) return null
        return SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US)
            .apply { timeZone = zone }
            .format(Date(unixSeconds * 1000))
    }

    /**
     * Date QuickTime d'un iPhone (« 2023-07-14T18:32:11+0200 ») : c'est déjà l'heure
     * locale du tournage, on garde l'heure telle quelle et on ignore le décalage.
     */
    internal fun appleDate(value: String): String? {
        val match = APPLE_DATE.find(value.trim()) ?: return null
        val (year, month, day, hour, minute, second) = match.destructured
        return "$year:$month:$day $hour:$minute:$second"
    }

    private val APPLE_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""")

    private fun u16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xFF) shl 24) or ((buf[at + 1].toLong() and 0xFF) shl 16) or
            ((buf[at + 2].toLong() and 0xFF) shl 8) or (buf[at + 3].toLong() and 0xFF)

    private fun u64(buf: ByteArray, at: Int): Long = (u32(buf, at) shl 32) or u32(buf, at + 4)

    // ISO-8859-1 : le « © » de ©xyz est l'octet 0xA9, pas une séquence UTF-8
    private fun fourCc(buf: ByteArray, at: Int): String = String(buf, at, 4, Charsets.ISO_8859_1)
}

package fr.midiland.nextclouddream.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.TimeZone

class Mp4MetadataTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")

    // --- Fabrication de fichiers MP4 boîte par boîte ---

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun u32(value: Long) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )
    private fun u64(value: Long) = u32(value ushr 32) + u32(value and 0xFFFFFFFFL)
    private fun fourCc(type: String) = type.toByteArray(Charsets.ISO_8859_1)

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val content = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        return u32(8L + content.size) + fourCc(type) + content
    }

    /** mvhd version 0 : version/flags, création (u32), modification, puis le reste. */
    private fun mvhdV0(created: Long) = box("mvhd", byteArrayOf(0, 0, 0, 0), u32(created), u32(created), ByteArray(88))
    private fun mvhdV1(created: Long) = box("mvhd", byteArrayOf(1, 0, 0, 0), u64(created), u64(created), ByteArray(100))

    private fun xyz(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return box("©xyz", u16(bytes.size), u16(0x15C7), bytes)
    }

    /** Contenu d'une meta QuickTime : hdlr, keys, ilst (valeurs texte). */
    private fun keyedMeta(entries: Map<String, String>): Array<ByteArray> {
        val keys = entries.keys.toList()
        val keysBox = box(
            "keys",
            byteArrayOf(0, 0, 0, 0), u32(keys.size.toLong()),
            *keys.map { key -> val name = key.toByteArray(); u32(8L + name.size) + fourCc("mdta") + name }.toTypedArray(),
        )
        val items = keys.mapIndexed { i, key ->
            val value = entries.getValue(key).toByteArray(Charsets.UTF_8)
            val data = box("data", u32(1), u32(0), value)
            // Le « type » d'un élément d'ilst est l'index (base 1) de sa clé
            u32(8L + data.size) + u32(i + 1L) + data
        }
        val hdlr = box("hdlr", ByteArray(24))
        return arrayOf(hdlr, keysBox, box("ilst", *items.toTypedArray()))
    }

    /** meta QuickTime (iPhone, sous moov) : sans version/flags. */
    private fun quickTimeMeta(entries: Map<String, String>) = box("meta", *keyedMeta(entries))

    /** meta MP4 (sous udta) : avec version/flags. */
    private fun mp4Meta(entries: Map<String, String>) = box("meta", byteArrayOf(0, 0, 0, 0), *keyedMeta(entries))

    private fun file(vararg boxes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", fourCc("isom"), u32(512)))
        boxes.forEach(out::write)
        return out.toByteArray()
    }

    /** Lecteur qui compte les octets demandés, comme le ferait le réseau. */
    private class Reader(val bytes: ByteArray) {
        var bytesRead = 0L
        val readAt: (Long, Int) -> ByteArray = { offset, length ->
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            bytesRead += end - offset
            bytes.copyOfRange(offset.toInt(), end)
        }
    }

    private fun read(bytes: ByteArray) = Reader(bytes).let { Mp4Metadata.read(bytes.size.toLong(), it.readAt, paris) }

    // 2021-07-14T10:00:00Z et 2021-01-14T10:00:00Z, en secondes depuis 1904
    private val summer = 1_626_256_800L + 2_082_844_800L
    private val winter = 1_610_618_400L + 2_082_844_800L

    // --- Tests ---

    @Test
    fun `moov au debut, date mvhd convertie en heure locale (ete)`() {
        val info = read(file(box("moov", mvhdV0(summer)), box("mdat", ByteArray(1000))))!!
        assertEquals("2021:07:14 12:00:00", info.dateTaken)
        assertNull(info.latitude)
    }

    @Test
    fun `heure d'hiver`() {
        assertEquals("2021:01:14 11:00:00", read(file(box("moov", mvhdV0(winter))))!!.dateTaken)
    }

    @Test
    fun `mvhd version 1 (dates sur 64 bits)`() {
        assertEquals("2021:07:14 12:00:00", read(file(box("moov", mvhdV1(summer))))!!.dateTaken)
    }

    @Test
    fun `date nulle ou anterieure a 1990 ignoree`() {
        assertNull(read(file(box("moov", mvhdV0(0))))!!.dateTaken)
        assertNull(read(file(box("moov", mvhdV0(100_000))))!!.dateTaken)
    }

    @Test
    fun `moov a la fin, apres de grosses donnees, sans tout lire`() {
        val bytes = file(box("mdat", ByteArray(3_000_000)), box("moov", mvhdV0(summer)))
        val reader = Reader(bytes)
        val info = Mp4Metadata.read(bytes.size.toLong(), reader.readAt, paris)!!
        assertEquals("2021:07:14 12:00:00", info.dateTaken)
        assertTrue("octets lus : ${reader.bytesRead}", reader.bytesRead < 70_000)
    }

    @Test
    fun `boite mdat de plus de 4 Go (taille sur 64 bits)`() {
        // En-tête largesize : taille 1, type, puis la vraie taille sur 8 octets
        val mdat = u32(1) + fourCc("mdat") + u64(16L + 5000) + ByteArray(5000)
        val info = read(file(mdat, box("moov", mvhdV0(summer))))!!
        assertEquals("2021:07:14 12:00:00", info.dateTaken)
    }

    @Test
    fun `position Android dans udta xyz`() {
        val info = read(file(box("moov", mvhdV0(summer), box("udta", xyz("+48.8566+002.3522/")))))!!
        assertEquals(48.8566, info.latitude!!, 1e-9)
        assertEquals(2.3522, info.longitude!!, 1e-9)
    }

    @Test
    fun `iPhone, meta QuickTime sans version, date locale prioritaire sur mvhd`() {
        val meta = quickTimeMeta(
            mapOf(
                "com.apple.quicktime.location.ISO6709" to "+35.6895+139.6917+040.000/",
                "com.apple.quicktime.creationdate" to "2019-04-23T12:45:33+0900",
            )
        )
        val info = read(file(box("moov", mvhdV0(summer), meta)))!!
        // Heure du tournage telle que vue sur place, décalage ignoré
        assertEquals("2019:04:23 12:45:33", info.dateTaken)
        assertEquals(35.6895, info.latitude!!, 1e-9)
        assertEquals(139.6917, info.longitude!!, 1e-9)
    }

    @Test
    fun `meta MP4 avec version sous udta`() {
        val meta = mp4Meta(mapOf("com.apple.quicktime.location.ISO6709" to "-33.8688+151.2093/"))
        val info = read(file(box("moov", mvhdV0(summer), box("udta", meta))))!!
        assertEquals(-33.8688, info.latitude!!, 1e-9)
        assertEquals(151.2093, info.longitude!!, 1e-9)
    }

    @Test
    fun `fichier sans moov, tronque ou incoherent`() {
        assertNull(read(file(box("mdat", ByteArray(100)))))
        assertNull(read(byteArrayOf(0, 0, 0)))
        assertNull(read(ByteArray(64) { 0x7F }))
        // moov annoncé plus grand que le fichier
        val truncated = file(box("moov", mvhdV0(summer))).let { it.copyOf(it.size - 20) }
        assertNull(read(truncated)?.dateTaken)
    }

    @Test
    fun `moov demesure ignore`() {
        // En-tête seul : moov annoncé à 20 Mo, au-delà du plafond
        val header = u32(20L * 1024 * 1024) + fourCc("moov")
        val bytes = file(header)
        assertNull(Mp4Metadata.read(bytes.size + 20L * 1024 * 1024, Reader(bytes + ByteArray(64)).readAt, paris))
    }

    @Test(expected = IOException::class)
    fun `une erreur reseau remonte (nouvel essai a la prochaine synchro)`() {
        Mp4Metadata.read(10_000, { _, _ -> throw IOException("réseau coupé") }, paris)
    }

    @Test
    fun `ISO 6709 en degres decimaux uniquement`() {
        assertEquals(48.8566 to 2.3522, Mp4Metadata.parseIso6709("+48.8566+002.3522/"))
        assertEquals(48.8566 to 2.3522, Mp4Metadata.parseIso6709("+48.8566+002.3522+035.000/"))
        assertEquals(-12.5 to -77.25, Mp4Metadata.parseIso6709("-12.5-077.25/"))
        assertEquals(48.8566 to 2.3522, Mp4Metadata.parseIso6709("+48.8566+002.3522CRSWGS_84/"))
        assertNull(Mp4Metadata.parseIso6709("+00.0000+000.0000/"))
        assertNull(Mp4Metadata.parseIso6709("+4851.40+00221.12/")) // degrés-minutes : refusé
        assertNull(Mp4Metadata.parseIso6709("+95.0+002.0/"))
        assertNull(Mp4Metadata.parseIso6709(""))
    }

    @Test
    fun `date Apple au format EXIF`() {
        assertEquals("2023:07:14 18:32:11", Mp4Metadata.appleDate("2023-07-14T18:32:11+0200"))
        assertEquals("2023:07:14 18:32:11", Mp4Metadata.appleDate("2023-07-14T18:32:11Z"))
        assertNull(Mp4Metadata.appleDate("14/07/2023"))
    }
}

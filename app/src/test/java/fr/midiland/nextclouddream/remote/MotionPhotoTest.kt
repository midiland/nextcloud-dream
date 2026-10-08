package fr.midiland.nextclouddream.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Les valeurs de ces exemples sont relevées sur de vrais fichiers, lus sur le serveur
 * Nextcloud de l'utilisateur : un Motion Photo de 6 281 220 octets dont la vidéo fait
 * 3 612 543 octets, et une photo Ultra HDR du même appareil qui n'est pas animée.
 */
class MotionPhotoTest {

    @Test
    fun `motion photo d'un pixel recent`() {
        val trailer = MotionPhoto.findTrailer(jpegHead(MOTION_PHOTO_XMP))

        assertEquals(3_612_543L, trailer?.length)
        assertEquals(3_612_543L, trailer?.offsetFromEnd)
        assertEquals(1_043_541L, trailer?.startUs)
    }

    /**
     * Le piège principal : une photo Ultra HDR a bien un GCamera et un Container,
     * mais celui-ci ne décrit qu'une carte de gain. Elle ne doit pas être animée.
     */
    @Test
    fun `photo ultra hdr sans video`() {
        assertNull(MotionPhoto.findTrailer(jpegHead(ULTRA_HDR_XMP)))
    }

    /**
     * La longueur doit être lue sur l'item de la vidéo, pas sur le premier Length
     * rencontré : ici la carte de gain fait 37 395 octets et la vidéo 3 612 543.
     */
    @Test
    fun `longueur lue sur l'item video et non sur la carte de gain`() {
        assertEquals(3_612_543L, MotionPhoto.findTrailer(jpegHead(MOTION_PHOTO_XMP))?.length)
    }

    /**
     * La vidéo commence avant tout ce qui la suit dans la liste : son offset depuis la
     * fin du fichier est la somme de sa longueur et de celles des items suivants.
     */
    @Test
    fun `offset compte les items qui suivent la video`() {
        val xmp = container(
            item("image/jpeg", "Primary", 0L),
            item("video/mp4", "MotionPhoto", 1_000L),
            item("image/jpeg", "GainMap", 200L),
        )
        val trailer = MotionPhoto.findTrailer(jpegHead(xmp))

        assertEquals(1_000L, trailer?.length)
        assertEquals(1_200L, trailer?.offsetFromEnd)
    }

    @Test
    fun `micro video des anciens pixel`() {
        val xmp = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description rdf:about=""
               xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
               GCamera:MicroVideo="1"
               GCamera:MicroVideoVersion="1"
               GCamera:MicroVideoOffset="4032418"
               GCamera:MicroVideoPresentationTimestampUs="500000"/>
            </rdf:RDF>
        """.trimIndent()
        val trailer = MotionPhoto.findTrailer(jpegHead(xmp))

        assertEquals(4_032_418L, trailer?.length)
        assertEquals(4_032_418L, trailer?.offsetFromEnd)
        assertEquals(500_000L, trailer?.startUs)
    }

    /** -1 = l'appareil n'a pas indiqué la position de l'image fixe. */
    @Test
    fun `horodatage inconnu`() {
        val xmp = container(item("image/jpeg", "Primary", 0L), item("video/mp4", "MotionPhoto", 1_000L))
            .replace("1043541", "-1")
        assertNull(MotionPhoto.findTrailer(jpegHead(xmp))?.startUs)
    }

    /**
     * Le XMP étendu est découpé en segments de 64 Ko : ce qu'on lit en tête est donc
     * un paquet sans balise de fin. Il doit être ignoré sans faire échouer la lecture
     * du paquet principal, qui le précède.
     */
    @Test
    fun `xmp etendu tronque ignore`() {
        val head = headText(MOTION_PHOTO_XMP) + TRUNCATED_PACKET
        val trailer = MotionPhoto.findTrailer(bytes(head))

        assertEquals(3_612_543L, trailer?.length)
    }

    @Test
    fun `paquet tronque seul`() {
        assertNull(MotionPhoto.findTrailer(bytes("ÿØ$TRUNCATED_PACKET")))
    }

    @Test
    fun `jpeg ordinaire sans xmp`() {
        assertNull(MotionPhoto.findTrailer(jpegHead(xmp = null)))
        assertNull(MotionPhoto.findTrailer(ByteArray(0)))
    }

    /** Un item vidéo de longueur nulle n'est pas exploitable. */
    @Test
    fun `video de longueur nulle`() {
        val xmp = container(item("image/jpeg", "Primary", 0L), item("video/mp4", "MotionPhoto", 0L))
        assertNull(MotionPhoto.findTrailer(jpegHead(xmp)))
    }

    /** Des Pixel écrivent GContainer:Item plutôt que Container:Item. */
    @Test
    fun `prefixe de namespace different`() {
        val xmp = container(item("image/jpeg", "Primary", 0L), item("video/mp4", "MotionPhoto", 1_000L))
            .replace("Container:", "GContainer:")
            .replace("Item:", "GContainerItem:")
        assertEquals(1_000L, MotionPhoto.findTrailer(jpegHead(xmp))?.length)
    }

    private companion object {

        const val TRUNCATED_PACKET = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><truc sans fin"

        /** JPEG minimal : marqueur de début, un segment APP1 contenant le XMP, des données. */
        fun headText(xmp: String?): String {
            val packet = xmp?.let { "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">$it</x:xmpmeta>" }.orEmpty()
            return "ÿØÿá" + packet + "ÿÚ" + "donnees".repeat(10)
        }

        /** Les octets du JPEG : un pour un, comme ce que lit readHead. */
        fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

        fun jpegHead(xmp: String?): ByteArray = bytes(headText(xmp))

        fun item(mime: String, semantic: String, length: Long) =
            """<Container:Item Item:Mime="$mime" Item:Semantic="$semantic" """ +
                """Item:Length="$length" Item:Padding="0"/>"""

        // En chaîne échappée et non brute : une chaîne brute qui se termine par un
        // guillemet, suivie de ses trois guillemets de fin, ne compile pas.
        const val GCAMERA_MOTION =
            "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" " +
                "GCamera:MotionPhotoPresentationTimestampUs=\"1043541\""

        /** [animated] à false : un Container sans aucune marque de photo animée (Ultra HDR). */
        fun container(vararg items: String, animated: Boolean = true) = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description rdf:about=""
               xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
               xmlns:Container="http://ns.google.com/photos/1.0/container/"
               xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
               ${if (animated) GCAMERA_MOTION else ""}>
              <Container:Directory>
               <rdf:Seq>
            ${items.joinToString("\n") { "    <rdf:li rdf:parseType=\"Resource\">$it</rdf:li>" }}
               </rdf:Seq>
              </Container:Directory>
             </rdf:Description>
            </rdf:RDF>
        """.trimIndent()

        /** Les trois items du vrai fichier, carte de gain comprise. */
        val MOTION_PHOTO_XMP = container(
            item("image/jpeg", "Primary", 0L),
            item("image/jpeg", "GainMap", 37_395L),
            item("video/mp4", "MotionPhoto", 3_612_543L),
        )

        /** Même appareil, photo non animée : un Container, mais aucun item vidéo. */
        val ULTRA_HDR_XMP = container(
            item("image/jpeg", "Primary", 0L),
            item("image/jpeg", "GainMap", 9_626L),
            animated = false,
        )
    }
}

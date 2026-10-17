package fr.midiland.nextclouddream.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPairingTest {

    private val mb = 1024L * 1024

    private fun entry(name: String, fileId: Long, size: Long = mb, twin: Long? = null) = DavEntry(
        url = "https://cloud.example.com/remote.php/dav/files/bob/Photos/$name".toHttpUrl(),
        name = name,
        isCollection = false,
        etag = "e$fileId",
        size = size,
        fileId = fileId,
        livePhotoFileId = twin,
    )

    private fun pair(images: List<DavEntry>, videos: List<DavEntry>) = NextcloudWebDavClient.pairMedia(images, videos)

    @Test
    fun `Live Photo appairee par le serveur, pas de video seule`() {
        val media = pair(listOf(entry("IMG_1.HEIC", 1, twin = 2)), listOf(entry("IMG_1.MOV", 2, twin = 1)))
        assertEquals(1, media.size)
        assertEquals("IMG_1.MOV", media.single().sidecar!!.url.pathSegments.last())
    }

    @Test
    fun `appairage par nom de base, sans aide du serveur`() {
        val media = pair(listOf(entry("IMG_1.JPG", 1)), listOf(entry("img_1.mov", 2)))
        assertEquals(1, media.size)
        assertTrue(media.single().sidecar != null)
    }

    @Test
    fun `une grosse video au meme nom n'est pas un clip, jouee seule`() {
        val media = pair(listOf(entry("IMG_1.JPG", 1)), listOf(entry("IMG_1.MOV", 2, size = 300 * mb)))
        assertNull(media.first().sidecar)
        assertTrue(media.last().isVideo)
    }

    @Test
    fun `un mp4 au meme nom qu'une photo est une video seule`() {
        val media = pair(listOf(entry("vacances.jpg", 1)), listOf(entry("vacances.mp4", 2)))
        assertNull(media.first().sidecar)
        assertEquals("vacances.mp4", media.last().name)
        assertTrue(media.last().isVideo)
    }

    @Test
    fun `lien dans un seul sens (la video designe la photo), jamais jouee seule`() {
        val media = pair(listOf(entry("IMG_1.HEIC", 1)), listOf(entry("IMG_1_clip.MOV", 2, twin = 1)))
        assertEquals(1, media.size)
        assertTrue(media.none { it.isVideo })
    }

    @Test
    fun `jumeau absent du dossier, la video est jouee seule`() {
        val media = pair(emptyList(), listOf(entry("IMG_9.MOV", 9, twin = 8)))
        assertTrue(media.single().isVideo)
    }

    @Test
    fun `video ordinaire, seule, sans clip, avec ses proprietes`() {
        val media = pair(listOf(entry("a.jpg", 1)), listOf(entry("anniversaire.mp4", 7, size = 120 * mb)))
        val video = media.last()
        assertTrue(video.isVideo)
        assertNull(video.sidecar)
        assertEquals(7L, video.fileId)
        assertEquals(120 * mb, video.size)
        assertEquals("e7", video.etag)
    }

    @Test
    fun `video designee par deux photos, rattachee, jamais seule`() {
        val video = entry("IMG_1.MOV", 3)
        val media = pair(listOf(entry("IMG_1.JPG", 1, twin = 3), entry("IMG_1.HEIC", 2, twin = 3)), listOf(video))
        assertEquals(2, media.size)
        assertTrue(media.all { it.sidecar != null && !it.isVideo })
    }

    @Test
    fun `les photos d'abord, puis les videos seules`() {
        val media = pair(listOf(entry("a.jpg", 1), entry("b.jpg", 2)), listOf(entry("c.mp4", 3), entry("d.mov", 4, size = 50 * mb)))
        assertEquals(listOf(false, false, true, true), media.map { it.isVideo })
    }
}

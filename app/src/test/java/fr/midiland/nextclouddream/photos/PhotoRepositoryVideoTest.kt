package fr.midiland.nextclouddream.photos

import fr.midiland.nextclouddream.remote.MotionPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests pour les aspects logiques de PhotoRepository liés aux vidéos.
 * Tests JVM purs sans dépendances Android.
 */
class PhotoRepositoryVideoTest {

    private fun photo(key: String, isVideo: Boolean = false, motion: MotionRef? = null): IndexedPhoto =
        IndexedPhoto(
            key = key,
            url = "https://x/$key",
            name = key,
            fileId = 1L,
            metadata = PhotoMetadata(dateTaken = "2020:01:01 00:00:00"),
            size = 1024L,
            motion = motion,
            isVideo = isVideo,
        )

    @Test
    fun `une photo avec isVideo true a toujours motion null`() {
        val video = photo("video.mp4", isVideo = true, motion = null)
        assertTrue(video.isVideo)
        assertNull(video.motion)
    }

    @Test
    fun `une photo animée a isVideo false et motion non null`() {
        val livePhoto = photo("live.jpg", isVideo = false, motion = MotionRef.Sidecar("url", 1024L))
        assertFalse(livePhoto.isVideo)
        assertTrue(livePhoto.motion?.hasVideo == true)
    }

    @Test
    fun `MotionRef hasVideo pour Sidecar`() {
        val sidecar = MotionRef.Sidecar("url", 1024L)
        assertTrue(sidecar.hasVideo)
    }

    @Test
    fun `MotionRef hasVideo pour Trailer`() {
        val trailer = MotionRef.Trailer("url", 0L, 1024L, 500L)
        assertTrue(trailer.hasVideo)
    }

    @Test
    fun `MotionRef hasVideo pour None`() {
        assertFalse(MotionRef.None.hasVideo)
    }

    @Test
    fun `MotionRef hasVideo pour null`() {
        val ref: MotionRef? = null
        assertFalse(ref.hasVideo)
    }

    @Test
    fun `byteCount pour Sidecar`() {
        val sidecar = MotionRef.Sidecar("url", 2048L)
        assertEquals(2048L, sidecar.byteCount)
    }

    @Test
    fun `byteCount pour Trailer`() {
        val trailer = MotionRef.Trailer("url", 0L, 3072L, 500L)
        assertEquals(3072L, trailer.byteCount)
    }

    @Test
    fun `byteCount pour None`() {
        assertEquals(0L, MotionRef.None.byteCount)
    }

    @Test
    fun `toJsonObject et fromJsonObject pour Sidecar`() {
        val original = MotionRef.Sidecar("https://x/clip.mov", 1024L)
        val json = original.toJsonObject()
        val restored = MotionRef.fromJsonObject(json)
        
        assertTrue(restored is MotionRef.Sidecar)
        assertEquals(original.url, (restored as MotionRef.Sidecar).url)
        assertEquals(original.size, (restored as MotionRef.Sidecar).size)
    }

    @Test
    fun `toJsonObject et fromJsonObject pour Trailer`() {
        val original = MotionRef.Trailer("https://x/photo.jpg", 1000L, 2000L, 500L)
        val json = original.toJsonObject()
        val restored = MotionRef.fromJsonObject(json)
        
        assertTrue(restored is MotionRef.Trailer)
        val trailer = restored as MotionRef.Trailer
        assertEquals(original.url, trailer.url)
        assertEquals(original.start, trailer.start)
        assertEquals(original.length, trailer.length)
        assertEquals(original.startUs, trailer.startUs)
    }

    @Test
    fun `toJsonObject et fromJsonObject pour None`() {
        val original = MotionRef.None
        val json = original.toJsonObject()
        val restored = MotionRef.fromJsonObject(json)
        
        assertEquals(MotionRef.None, restored)
    }
}

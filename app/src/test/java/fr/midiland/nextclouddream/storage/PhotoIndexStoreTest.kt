package fr.midiland.nextclouddream.storage

import fr.midiland.nextclouddream.photos.IndexedPhoto
import fr.midiland.nextclouddream.photos.MotionRef
import fr.midiland.nextclouddream.photos.PhotoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotoIndexStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val indexFile by lazy { File(tmp.root, "photo_index.json") }
    private val store by lazy { PhotoIndexStore(indexFile) }

    @Test
    fun `aller-retour complet`() {
        val photos = listOf(
            IndexedPhoto(
                key = "k1",
                url = "https://cloud.example.com/remote.php/dav/files/bob/a.jpg",
                name = "a.jpg",
                fileId = 42L,
                metadata = PhotoMetadata("2019:04:23 12:45:33", 51.50, -0.12, "Londres, Royaume-Uni"),
            ),
            IndexedPhoto("k2", "https://cloud.example.com/b.jpg", "b.jpg", fileId = null, metadata = null),
            IndexedPhoto("k3", "https://cloud.example.com/c.jpg", "c.jpg", 7L, PhotoMetadata(dateTaken = "2017:07:31 16:36:21")),
        )
        store.save(photos)
        assertEquals(photos, PhotoIndexStore(indexFile).load())
    }

    @Test
    fun `index d'une ancienne version sans fileId ni metadonnees`() {
        indexFile.writeText("""[{"key":"k1","url":"https://x/a.jpg","name":"a.jpg"}]""")
        val photo = store.load().single()
        assertEquals("k1", photo.key)
        assertNull(photo.fileId)
        assertNull(photo.metadata)
        // null et non None : la photo n'a jamais été examinée, elle le sera à la synchro
        assertNull(photo.motion)
    }

    /** Les trois formes de référence doivent revenir identiques. */
    @Test
    fun `aller-retour des photos animees`() {
        val photos = listOf(
            IndexedPhoto(
                key = "live",
                url = "https://cloud.example.com/IMG_0707.jpg",
                name = "IMG_0707.jpg",
                fileId = 2067820L,
                metadata = null,
                size = 6_416_384L,
                motion = MotionRef.Sidecar("https://cloud.example.com/IMG_0707.mov", 6_115_328L),
            ),
            IndexedPhoto(
                key = "pixel",
                url = "https://cloud.example.com/PXL.jpg",
                name = "PXL.jpg",
                fileId = 3L,
                metadata = null,
                size = 6_281_220L,
                motion = MotionRef.Trailer("https://cloud.example.com/PXL.jpg", 2_668_677L, 3_612_543L, 1_043_541L),
            ),
            IndexedPhoto(
                key = "fixe",
                url = "https://cloud.example.com/c.jpg",
                name = "c.jpg",
                fileId = 4L,
                metadata = null,
                size = 1_024L,
                motion = MotionRef.None,
            ),
        )
        store.save(photos)
        assertEquals(photos, PhotoIndexStore(indexFile).load())
    }

    /** Un Motion Photo dont l'appareil n'a pas indiqué la position de l'image fixe. */
    @Test
    fun `trailer sans horodatage`() {
        val photo = IndexedPhoto(
            key = "k", url = "https://x/a.jpg", name = "a.jpg", fileId = null, metadata = null,
            motion = MotionRef.Trailer("https://x/a.jpg", 10L, 20L, startUs = null),
        )
        store.save(listOf(photo))
        assertEquals(photo, PhotoIndexStore(indexFile).load().single())
    }

    @Test
    fun `index absent ou corrompu donne une liste vide`() {
        assertTrue(store.load().isEmpty())
        indexFile.writeText("""[{"key": tronqué""")
        assertTrue(store.load().isEmpty())
    }
}

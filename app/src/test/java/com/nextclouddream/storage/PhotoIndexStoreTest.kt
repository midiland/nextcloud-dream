package com.nextclouddream.storage

import com.nextclouddream.photos.IndexedPhoto
import com.nextclouddream.photos.PhotoMetadata
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
    }

    @Test
    fun `index absent ou corrompu donne une liste vide`() {
        assertTrue(store.load().isEmpty())
        indexFile.writeText("""[{"key": tronqué""")
        assertTrue(store.load().isEmpty())
    }
}

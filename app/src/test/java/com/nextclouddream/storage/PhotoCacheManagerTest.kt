package com.nextclouddream.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotoCacheManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cache by lazy { PhotoCacheManager(File(tmp.root, "photos"), storageManager = null) }

    private fun photo(key: String, bytes: Int, lastModified: Long): File =
        cache.fileFor(key).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(lastModified)
        }

    @Test
    fun `cle de cache stable (sinon tout le cache existant serait perdu)`() {
        // Valeur de référence : ne doit jamais changer d'une version à l'autre
        assertEquals(
            "3429e6baeb0d5115f5ca16fe6d37e3eb",
            PhotoCacheManager.keyFor("https://cloud.example.com/remote.php/dav/files/bob/Photos/a.jpg", "abc123"),
        )
    }

    @Test
    fun `store ecrit la photo sans laisser de fichier temporaire`() {
        val file = cache.store("k1") { it.writeText("image") }
        assertEquals("image", file.readText())
        assertEquals(listOf("k1.jpg"), cache.directory.list()!!.toList())
    }

    @Test
    fun `store en echec ne laisse ni photo ni fichier temporaire`() {
        try {
            cache.store("k1") {
                it.writeText("début")
                error("réseau coupé")
            }
            fail("exception attendue")
        } catch (e: IllegalStateException) {
            // attendu
        }
        assertTrue(cache.directory.list()!!.isEmpty())
    }

    @Test
    fun `deux telechargements simultanes de la meme photo ont des fichiers temporaires distincts`() {
        assertNotEquals(cache.tempFileFor("k1", "write"), cache.tempFileFor("k1", "write"))
    }

    @Test
    fun `trim supprime les photos les moins recemment vues`() {
        val now = System.currentTimeMillis()
        val oldest = photo("a", 100, now - 3_000)
        val middle = photo("b", 100, now - 2_000)
        val newest = photo("c", 100, now - 1_000)

        cache.trim(maxBytes = 250)

        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `retainOnly garde les telechargements en cours et purge le reste`() {
        val now = System.currentTimeMillis()
        val kept = photo("kept", 10, now)
        val removed = photo("removed", 10, now)
        val inProgress = File(cache.directory, "x.write.123.part").apply { writeText("…") }
        val abandoned = File(cache.directory, "y.write.456.part").apply {
            writeText("…")
            setLastModified(now - 2 * 60 * 60_000L)
        }
        val legacySidecar = File(cache.directory, "old.json").apply {
            writeText("{}")
            setLastModified(now - 2 * 60 * 60_000L)
        }

        cache.retainOnly(setOf("kept"))

        assertTrue(kept.exists())
        assertFalse(removed.exists())
        assertTrue(inProgress.exists())
        assertFalse(abandoned.exists())
        assertFalse(legacySidecar.exists())
    }

    @Test
    fun `listPhotos ignore les fichiers temporaires et evict supprime une photo`() {
        photo("a", 10, System.currentTimeMillis())
        cache.tempFileFor("b", "write").writeText("…")

        assertEquals(listOf("a.jpg"), cache.listPhotos().map { it.name })

        cache.evict("a")
        assertTrue(cache.listPhotos().isEmpty())
    }
}

package fr.midiland.nextclouddream.dream

import fr.midiland.nextclouddream.photos.FetchResult
import fr.midiland.nextclouddream.photos.IndexedPhoto
import fr.midiland.nextclouddream.photos.PhotoMetadata
import fr.midiland.nextclouddream.photos.PhotoSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SlideSourceTest {

    /** Fausse source : chaque photo a un comportement réglable, les appels sont comptés. */
    private class FakeSource(var index: List<IndexedPhoto>) : PhotoSource {
        val behaviour = mutableMapOf<String, FetchResult>()
        val cached = mutableSetOf<String>()
        var fetchCount = 0
        var indexReads = 0

        override suspend fun getIndex(): List<IndexedPhoto> {
            indexReads++
            return index
        }

        override suspend fun getCachedPhotos(): List<File> = cached.map { File("$it.jpg") }

        override suspend fun fetchForDisplay(photo: IndexedPhoto): FetchResult {
            fetchCount++
            return behaviour[photo.key] ?: FetchResult.Ready(File("${photo.key}.jpg"))
        }
    }

    private var time = 1_000_000L

    private fun photo(key: String, metadata: PhotoMetadata? = PhotoMetadata(dateTaken = "2020:01:01 00:00:00")) =
        IndexedPhoto(key, "https://x/$key.jpg", "$key.jpg", fileId = 1L, metadata = metadata)

    private fun sourceWith(fake: FakeSource) =
        SlideSource(fake, Random(1)) { time }.apply { reset(fake.index.map { it.key }, fake.index) }

    @Test
    fun `en ligne, chaque photo est fournie avec ses metadonnees`() = runBlocking {
        val fake = FakeSource(listOf(photo("a"), photo("b"), photo("c")))
        val slides = sourceWith(fake)

        val shown = List(3) { slides.next()!! }
        assertEquals(setOf("a", "b", "c"), shown.map { it.key }.toSet())
        assertTrue(shown.all { it.metadata?.dateTaken != null })
        assertFalse(slides.isOffline)
    }

    @Test
    fun `une photo indisponible est sautee sans passer hors ligne`() = runBlocking {
        val fake = FakeSource(listOf(photo("a"), photo("b")))
        fake.behaviour["a"] = FetchResult.Unavailable
        val slides = sourceWith(fake)

        repeat(4) { assertEquals("b", slides.next()!!.key) }
        assertFalse(slides.isOffline)
    }

    @Test
    fun `serveur injoignable, bascule sur le cache puis retente apres 5 minutes`() = runBlocking {
        val fake = FakeSource(listOf(photo("a"), photo("b"), photo("c")))
        fake.behaviour.putAll(listOf("a", "b", "c").associateWith { FetchResult.Offline })
        fake.cached += "b"
        val slides = sourceWith(fake)

        assertEquals("b", slides.next()!!.key)
        assertTrue(slides.isOffline)
        val fetchesWhenOffline = fake.fetchCount

        // Pendant 5 min : uniquement le cache, aucune tentative réseau
        repeat(5) { assertEquals("b", slides.next()!!.key) }
        assertEquals(fetchesWhenOffline, fake.fetchCount)

        // Après 5 min, le serveur répond de nouveau
        time += SlideSource.OFFLINE_RETRY_MS + 1
        fake.behaviour.clear()
        assertNotNull(slides.next())
        assertTrue(fake.fetchCount > fetchesWhenOffline)
        assertFalse(slides.isOffline)
    }

    @Test
    fun `rien en ligne ni en cache`() = runBlocking {
        val fake = FakeSource(listOf(photo("a"), photo("b")))
        fake.behaviour.putAll(mapOf("a" to FetchResult.Offline, "b" to FetchResult.Offline))
        assertNull(sourceWith(fake).next())
    }

    @Test
    fun `cache seul, sans index (premier lancement hors ligne)`() = runBlocking {
        val fake = FakeSource(emptyList())
        fake.cached += listOf("x", "y")
        val slides = SlideSource(fake, Random(1)) { time }.apply { reset(listOf("x", "y"), index = emptyList()) }

        val slide = slides.next()!!
        assertTrue(slide.key in setOf("x", "y"))
        assertNull(slide.metadata)
        assertEquals(0, fake.fetchCount)
    }

    @Test
    fun `metadonnees manquantes recuperees dans l'index, au plus une fois par minute`() = runBlocking {
        val keys = listOf("a", "b", "c", "d")
        val fake = FakeSource(keys.map { photo(it, metadata = null) })
        val slides = sourceWith(fake)

        // Début de cycle : une lecture de l'index, puis pas d'autre dans la minute
        assertNull(slides.next()!!.metadata)
        assertNull(slides.next()!!.metadata)
        assertEquals(1, fake.indexReads)

        // Une minute plus tard, la synchro a lu les EXIF : l'index est relu une fois
        time += SlideSource.METADATA_REFRESH_MS + 1
        fake.index = keys.map { photo(it) }
        assertNotNull(slides.next()!!.metadata)
        assertNotNull(slides.next()!!.metadata)
        assertEquals(2, fake.indexReads)
    }

    @Test
    fun `hors ligne, une photo en cache est toujours trouvee (quel que soit l'ordre)`() = runBlocking {
        repeat(100) { seed ->
            val keys = ('a'..'h').map { it.toString() }
            val fake = FakeSource(keys.map { photo(it) })
            fake.behaviour.putAll(keys.associateWith { FetchResult.Offline })
            fake.cached += "e"
            val slides = SlideSource(fake, Random(seed)) { time }.apply { reset(keys, fake.index) }
            repeat(20) { assertEquals("seed $seed", "e", slides.next()?.key) }
        }
    }

    @Test
    fun `nouvelles photos de l'index integrees au cycle suivant`() = runBlocking {
        val fake = FakeSource(listOf(photo("a")))
        val slides = sourceWith(fake)
        assertEquals("a", slides.next()!!.key)

        fake.index = listOf(photo("a"), photo("b"))
        val nextCycle = List(2) { slides.next()!!.key }
        assertEquals(setOf("a", "b"), nextCycle.toSet())
    }
}

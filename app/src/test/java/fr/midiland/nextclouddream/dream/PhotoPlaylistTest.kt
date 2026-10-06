package fr.midiland.nextclouddream.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhotoPlaylistTest {

    private val photos = ('a'..'j').map { it.toString() }

    private fun PhotoPlaylist<String>.takeCycle(): List<String> {
        val cycle = mutableListOf<String>()
        do {
            cycle += next()!!
        } while (!isCycleOver)
        return cycle
    }

    @Test
    fun `chaque photo passe une fois par cycle`() {
        val playlist = PhotoPlaylist(photos, Random(1))
        repeat(5) {
            assertEquals(photos.toSet(), playlist.takeCycle().toSet())
        }
    }

    @Test
    fun `les photos vues en fin de cycle ne reviennent pas en tete du suivant`() {
        repeat(200) { seed ->
            val playlist = PhotoPlaylist(photos, Random(seed))
            val first = playlist.takeCycle()
            val second = playlist.takeCycle()
            // Historique = moitié de la collection : les 5 dernières passent en fin de cycle
            val recent = first.takeLast(photos.size / 2).toSet()
            assertTrue("seed $seed", second.take(photos.size / 2).none { it in recent })
        }
    }

    @Test
    fun `une photo supprimee disparait tout de suite, une nouvelle arrive au cycle suivant`() {
        val playlist = PhotoPlaylist(photos, Random(2))
        playlist.next()
        playlist.updatePhotos(photos - "c" + "z")

        val restOfCycle = buildList { while (!playlist.isCycleOver) add(playlist.next()!!) }
        assertFalse("c" in restOfCycle)
        assertFalse("z" in restOfCycle)

        val nextCycle = playlist.takeCycle()
        assertTrue("z" in nextCycle)
        assertFalse("c" in nextCycle)
    }

    @Test
    fun `remove retire definitivement une photo`() {
        val playlist = PhotoPlaylist(photos, Random(3))
        playlist.remove("a")
        repeat(3) { assertFalse("a" in playlist.takeCycle()) }
        assertEquals(photos.size - 1, playlist.size)
    }

    @Test
    fun `premiere photo seule puis arrivee des autres sans repetition immediate`() {
        repeat(50) { seed ->
            val playlist = PhotoPlaylist(listOf("a"), Random(seed))
            assertEquals("a", playlist.next())
            playlist.updatePhotos(listOf("a", "b", "c", "d"))
            assertTrue("seed $seed", playlist.next() != "a")
        }
    }

    @Test
    fun `playlist vide`() {
        assertNull(PhotoPlaylist(emptyList<String>()).next())
    }
}

package fr.midiland.nextclouddream.ui

import fr.midiland.nextclouddream.photos.MotionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests pour les parties non-UI de MotionLayer (logique pure).
 */
class MotionLayerTest {

    @Test
    fun `startOf retourne startUs pour Trailer`() {
        val trailer = MotionRef.Trailer(
            url = "https://x/photo.jpg",
            start = 1000L,
            length = 5000L,
            startUs = 1043541L,
        )
        
        val result = MotionLayer.startOf(trailer)
        
        assertEquals(1043541L, result)
    }

    @Test
    fun `startOf retourne null pour Sidecar`() {
        val sidecar = MotionRef.Sidecar(
            url = "https://x/clip.mov",
            size = 1024L,
        )
        
        val result = MotionLayer.startOf(sidecar)
        
        assertNull(result)
    }

    @Test
    fun `startOf retourne null pour None`() {
        val result = MotionLayer.startOf(MotionRef.None)
        
        assertNull(result)
    }

    @Test
    fun `startOf retourne null pour null`() {
        val result = MotionLayer.startOf(null)
        
        assertNull(result)
    }

    // Note: Les méthodes UI comme playOnce(), openStream(), etc. nécessitent un contexte
    // Android et une Surface valide, elles ne peuvent pas être testées en unit tests JVM.
    // Ces méthodes devraient être couvertes par des tests instrumentés (Espresso/Compose).
}

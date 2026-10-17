package fr.midiland.nextclouddream.ui

import fr.midiland.nextclouddream.photos.MotionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests pour les méthodes utilitaires liées à MotionLayer.
 */
class MotionLayerTest {

    /**
     * Logique de startOf extraite pour test : retourne startUs d'un Trailer, null sinon.
     * Correspond à la méthode privée startOf() dans NextcloudDreamService.
     */
    private fun startOf(motion: MotionRef?): Long? = (motion as? MotionRef.Trailer)?.startUs

    @Test
    fun `startOf retourne startUs pour Trailer`() {
        val trailer = MotionRef.Trailer(
            url = "https://x/photo.jpg",
            start = 1000L,
            length = 5000L,
            startUs = 1043541L,
        )
        
        val result = startOf(trailer)
        
        assertEquals(1043541L, result)
    }

    @Test
    fun `startOf retourne null pour Sidecar`() {
        val sidecar = MotionRef.Sidecar(
            url = "https://x/clip.mov",
            size = 1024L,
        )
        
        val result = startOf(sidecar)
        
        assertNull(result)
    }

    @Test
    fun `startOf retourne null pour None`() {
        val result = startOf(MotionRef.None)
        
        assertNull(result)
    }

    @Test
    fun `startOf retourne null pour null`() {
        val result = startOf(null)
        
        assertNull(result)
    }
}

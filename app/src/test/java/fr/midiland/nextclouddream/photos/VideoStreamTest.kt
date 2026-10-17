package fr.midiland.nextclouddream.photos

import org.junit.Assert.assertFalse
import org.junit.Test

class VideoStreamTest {

    @Test
    fun `toString n'expose jamais le mot de passe`() {
        val stream = VideoStream("https://x/v.mp4", mapOf("Authorization" to "Basic Ym9iOnNlY3JldA=="))
        assertFalse(stream.toString().contains("Basic"))
        assertFalse(stream.toString().contains("Ym9i"))
        assertFalse("$stream".contains("Authorization"))
    }
}

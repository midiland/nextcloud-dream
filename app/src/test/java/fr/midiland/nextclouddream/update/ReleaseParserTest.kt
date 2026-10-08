package fr.midiland.nextclouddream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {

    /**
     * Ces valeurs doivent rester identiques à celles de `parseAppVersion`
     * dans app/build.gradle.kts : c'est ce qui rend la comparaison de versions juste.
     */
    @Test
    fun `numero de version calcule comme a la compilation`() {
        assertEquals(1_002_003, ReleaseParser.versionCodeOf("v1.2.3"))
        assertEquals(1_010_000, ReleaseParser.versionCodeOf("V1.10.00"))
        assertEquals(12_345_678, ReleaseParser.versionCodeOf("V12.345.678"))
        assertEquals(1_000_000, ReleaseParser.versionCodeOf("1.0.0"))
    }

    @Test
    fun `tags hors format ignores`() {
        assertNull(ReleaseParser.versionCodeOf("V1.2"))
        assertNull(ReleaseParser.versionCodeOf("V1.2.3-rc.4"))
        assertNull(ReleaseParser.versionCodeOf("V1.1234.0"))
        assertNull(ReleaseParser.versionCodeOf(""))
    }

    @Test
    fun `release complete`() {
        val release = ReleaseParser.parse(
            """
            {
              "tag_name": "V1.1.0",
              "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"},
                {"name": "nextcloud-dream.apk", "browser_download_url": "https://example.com/app.apk"}
              ]
            }
            """
        )
        assertEquals(AppRelease("1.1.0", 1_001_000, "https://example.com/app.apk"), release)
    }

    @Test
    fun `release sans APK attache`() {
        val json = """{"tag_name": "V1.1.0", "assets": [{"name": "notes.txt",
            "browser_download_url": "https://example.com/notes.txt"}]}"""
        assertNull(ReleaseParser.parse(json))
    }

    /** Une release publiée à la main, sans tag de version, ne doit pas être proposée. */
    @Test
    fun `release sans tag exploitable`() {
        val json = """{"tag_name": "nightly", "assets": [{"name": "a.apk",
            "browser_download_url": "https://example.com/a.apk"}]}"""
        assertNull(ReleaseParser.parse(json))
    }

    /** Un APK servi en clair serait un vecteur d'installation trop commode. */
    @Test
    fun `apk hors HTTPS refuse`() {
        val json = """{"tag_name": "V1.1.0", "assets": [{"name": "a.apk",
            "browser_download_url": "http://example.com/a.apk"}]}"""
        assertNull(ReleaseParser.parse(json))
    }

    @Test
    fun `reponse illisible`() {
        assertNull(ReleaseParser.parse("pas du JSON"))
        assertNull(ReleaseParser.parse("{}"))
    }
}

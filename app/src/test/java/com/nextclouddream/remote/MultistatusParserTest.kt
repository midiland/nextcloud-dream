package com.nextclouddream.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class MultistatusParserTest {

    // Réponse PROPFIND Depth:1 de Nextcloud (structure réelle, données anonymisées)
    private val response = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/bob/Photos/ScreenSaver/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                <d:getetag>&quot;6ac281a1c1ae5&quot;</d:getetag>
                <oc:fileid>100</oc:fileid>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
            <d:propstat>
              <d:prop><d:getcontentlength/></d:prop>
              <d:status>HTTP/1.1 404 Not Found</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/bob/Photos/ScreenSaver/Vacances%20%C3%A9t%C3%A9.jpg</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype/>
                <d:getetag>&quot;dce7407e36fa8cf586029b76ccdf5058&quot;</d:getetag>
                <d:getcontentlength>7030376</d:getcontentlength>
                <oc:fileid>2066910</oc:fileid>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/bob/Photos/ScreenSaver/Voyages/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                <d:getetag>&quot;abc&quot;</d:getetag>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    private val requestUrl = "https://cloud.example.com/remote.php/dav/files/bob/Photos/ScreenSaver/".toHttpUrl()

    private fun parse(xml: String): List<DavEntry> {
        val parser = KXmlParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        return MultistatusParser.parse(parser, requestUrl)
    }

    @Test
    fun `fichiers et dossiers avec leurs proprietes`() {
        val entries = parse(response)
        assertEquals(3, entries.size)

        val (folder, photo, subfolder) = entries
        assertTrue(folder.isCollection)
        assertEquals(requestUrl, folder.url)

        assertFalse(photo.isCollection)
        assertEquals("Vacances été.jpg", photo.name)
        assertEquals("dce7407e36fa8cf586029b76ccdf5058", photo.etag)
        assertEquals(7030376L, photo.size)
        assertEquals(2066910L, photo.fileId)
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/bob/Photos/ScreenSaver/Vacances%20%C3%A9t%C3%A9.jpg",
            photo.url.toString(),
        )

        assertTrue(subfolder.isCollection)
        assertEquals("Voyages", subfolder.name)
        assertNull(subfolder.fileId)
    }

    @Test
    fun `les proprietes d'une reponse ne debordent pas sur la suivante`() {
        // Le dossier a un fileid, le sous-dossier n'en a pas : il ne doit pas en hériter
        val subfolder = parse(response).last()
        assertNull(subfolder.fileId)
        assertEquals(0L, subfolder.size)
    }

    @Test
    fun `filtre des extensions d'images`() {
        assertTrue(NextcloudWebDavClient.isImage("IMG_1.JPG"))
        assertTrue(NextcloudWebDavClient.isImage("photo.heic"))
        assertFalse(NextcloudWebDavClient.isImage("video.mp4"))
        assertFalse(NextcloudWebDavClient.isImage("sans_extension"))
        assertFalse(NextcloudWebDavClient.mayHaveExif("capture.png"))
        assertTrue(NextcloudWebDavClient.mayHaveExif("photo.heic"))
    }
}

package com.nextclouddream.remote

import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser

/** Une entrée (fichier ou dossier) d'une réponse PROPFIND. */
class DavEntry(
    val url: HttpUrl,
    val name: String,
    val isCollection: Boolean,
    val etag: String,
    val size: Long,
    val fileId: Long?,
)

/**
 * Lecture d'une réponse WebDAV PROPFIND. Séparé du client HTTP et indépendant
 * d'Android (le XmlPullParser est fourni) pour être testable sur la JVM.
 */
object MultistatusParser {

    private const val DAV_NS = "DAV:"
    private const val OC_NS = "http://owncloud.org/ns"

    /**
     * Analyse une réponse <d:multistatus> (une <d:response> par fichier/dossier).
     * [parser] doit être configuré avec FEATURE_PROCESS_NAMESPACES.
     */
    fun parse(parser: XmlPullParser, requestUrl: HttpUrl): List<DavEntry> {
        val entries = mutableListOf<DavEntry>()
        var href: String? = null
        var isCollection = false
        var etag = ""
        var size = 0L
        var fileId: Long? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.namespace != DAV_NS && parser.namespace != OC_NS) continue
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> { href = null; isCollection = false; etag = ""; size = 0L; fileId = null }
                    "href" -> href = parser.nextText().trim()
                    "collection" -> isCollection = true
                    "getetag" -> etag = parser.nextText().trim().trim('"')
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "fileid" -> fileId = parser.nextText().trim().toLongOrNull()
                }
                XmlPullParser.END_TAG -> if (parser.name == "response" && parser.namespace == DAV_NS) {
                    // href est un chemin absolu encodé (ex. /remote.php/dav/files/bob/Photos/a%20b.jpg)
                    val url = href?.let { requestUrl.resolve(it) }
                    if (url != null) {
                        val name = url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
                        entries += DavEntry(url, name, isCollection, etag, size, fileId)
                    }
                }
            }
        }
        return entries
    }
}

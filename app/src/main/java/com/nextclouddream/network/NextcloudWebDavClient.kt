package com.nextclouddream.network

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Photo distante telle que listée par PROPFIND. */
data class RemotePhoto(
    val url: HttpUrl,
    val name: String,
    val etag: String,
    val size: Long,
    /** Identifiant Nextcloud du fichier, nécessaire pour demander un aperçu. */
    val fileId: Long?,
)

/**
 * Client Nextcloud minimal, authentification Basic avec un mot de passe d'application :
 *  - listing WebDAV (PROPFIND)
 *  - aperçu réduit généré par le serveur (API core/preview)
 *  - téléchargement complet ou partiel (début du fichier, pour l'EXIF)
 *
 * Toutes les méthodes sont bloquantes : à appeler depuis Dispatchers.IO.
 */
class NextcloudWebDavClient(
    serverUrl: String,
    private val username: String,
    appPassword: String,
    private val httpClient: OkHttpClient = defaultHttpClient,
) {
    private val credentials = Credentials.basic(username, appPassword)
    private val serverBaseUrl: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()

    /**
     * Liste récursivement les images du dossier [folderPath] (et de ses sous-dossiers).
     * @throws IOException si le serveur est injoignable ou répond une erreur.
     */
    fun listPhotos(folderPath: String): List<RemotePhoto> {
        val photos = mutableListOf<RemotePhoto>()
        val pending = ArrayDeque<HttpUrl>().apply { add(folderUrl(folderPath)) }
        val visited = mutableSetOf<String>()

        while (pending.isNotEmpty()) {
            val dirUrl = pending.removeFirst()
            if (!visited.add(dirUrl.encodedPath) || visited.size > MAX_FOLDERS) continue

            for (entry in propfind(dirUrl)) {
                when {
                    // PROPFIND Depth:1 renvoie aussi le dossier lui-même : on l'ignore
                    entry.url.encodedPath.trimEnd('/') == dirUrl.encodedPath.trimEnd('/') -> Unit
                    entry.isCollection -> pending.add(entry.url)
                    isImage(entry.name) -> photos += RemotePhoto(entry.url, entry.name, entry.etag, entry.size, entry.fileId)
                }
            }
        }
        Timber.i("WebDAV : %d image(s) trouvée(s) dans %s", photos.size, folderPath)
        return photos
    }

    /** Télécharge le fichier complet [url] dans [destination] (écrasé s'il existe). */
    fun download(url: HttpUrl, destination: File) {
        execute(request(url)) { body -> destination.outputStream().use { out -> body.copyTo(out) } }
    }

    /**
     * Télécharge un aperçu JPEG réduit par le serveur, proportions conservées,
     * tenant dans [maxWidth] × [maxHeight]. Quelques centaines de Ko au lieu de plusieurs Mo.
     * @throws IOException si le serveur ne sait pas générer d'aperçu (ex. HEIC sans module dédié).
     */
    fun downloadPreview(fileId: Long, destination: File, maxWidth: Int, maxHeight: Int) {
        val url = serverBaseUrl.newBuilder()
            .addPathSegments("index.php/core/preview")
            .addQueryParameter("fileId", fileId.toString())
            .addQueryParameter("x", maxWidth.toString())
            .addQueryParameter("y", maxHeight.toString())
            .addQueryParameter("a", "1")              // conserver les proportions
            .addQueryParameter("forceIcon", "0")
            .addQueryParameter("mimeFallback", "false") // erreur plutôt qu'une icône générique
            .build()
        execute(request(url)) { body ->
            destination.outputStream().use { out -> body.copyTo(out) }
        }
    }

    /**
     * Lit au plus [maxBytes] octets au début du fichier (requête HTTP Range).
     * Suffit pour l'EXIF d'un JPEG, toujours placé en tête de fichier.
     */
    fun readHead(url: HttpUrl, maxBytes: Int): ByteArray {
        val request = request(url).newBuilder()
            .header("Range", "bytes=0-${maxBytes - 1}")
            .build()
        return execute(request) { body ->
            // Si le serveur ignore Range (réponse 200 complète), on s'arrête quand même à maxBytes
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val read = body.read(buffer, total, maxBytes - total)
                if (read < 0) break
                total += read
            }
            buffer.copyOf(total)
        }
    }

    private fun request(url: HttpUrl): Request =
        Request.Builder().url(url).header("Authorization", credentials).get().build()

    private fun <T> execute(request: Request, readBody: (InputStream) -> T): T =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET ${request.url.encodedPath} : HTTP ${response.code}")
            val body = response.body ?: throw IOException("GET ${request.url.encodedPath} : réponse vide")
            body.byteStream().use(readBody)
        }

    /** URL WebDAV du dossier : {serveur}/remote.php/dav/files/{user}/{dossier}/ */
    private fun folderUrl(folderPath: String): HttpUrl {
        val builder = serverBaseUrl.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(username)
        folderPath.split('/').filter { it.isNotBlank() }.forEach { builder.addPathSegment(it) }
        return builder.addPathSegment("").build() // slash final = collection
    }

    private fun propfind(url: HttpUrl): List<DavEntry> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credentials)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                207 -> Unit
                401 -> throw IOException("Authentification refusée (401) : vérifiez l'identifiant et le token")
                404 -> throw IOException("Dossier introuvable (404) : ${url.encodedPath}")
                else -> throw IOException("PROPFIND : HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("PROPFIND : réponse vide")
            return parseMultistatus(body.byteStream(), url)
        }
    }

    private class DavEntry(
        val url: HttpUrl,
        val name: String,
        val isCollection: Boolean,
        val etag: String,
        val size: Long,
        val fileId: Long?,
    )

    /** Analyse la réponse XML <d:multistatus> (une <d:response> par fichier/dossier). */
    private fun parseMultistatus(input: InputStream, requestUrl: HttpUrl): List<DavEntry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
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

    companion object {
        private const val DAV_NS = "DAV:"
        private const val OC_NS = "http://owncloud.org/ns"
        private const val MAX_FOLDERS = 200

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp")

        fun isImage(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        fun isJpeg(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")

        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:prop>
                <d:resourcetype/>
                <d:getetag/>
                <d:getcontentlength/>
                <oc:fileid/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

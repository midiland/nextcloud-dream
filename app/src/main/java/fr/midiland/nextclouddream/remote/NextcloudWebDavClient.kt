package fr.midiland.nextclouddream.remote

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
    /** Vidéo de Live Photo (iPhone) : un fichier distinct, désigné par le serveur. */
    val sidecar: SidecarVideo? = null,
)

/** Vidéo d'une Live Photo, stockée à côté de la photo. */
data class SidecarVideo(val url: HttpUrl, val size: Long)

/**
 * Réponse HTTP en erreur. Le code permet de distinguer une photo indisponible
 * (404, aperçu impossible…) d'un serveur en panne ou injoignable.
 */
class HttpStatusException(val code: Int, message: String) : IOException(message) {
    /** Erreur liée à ce fichier (pas d'aperçu possible, fichier supprimé…), pas au serveur. */
    val isFileSpecific: Boolean
        get() = code in FILE_SPECIFIC_CODES

    /** Erreur qui ne se corrigera pas en réessayant (identifiants, dossier). */
    val isPermanent: Boolean
        get() = code == 401 || code == 403 || code == 404

    private companion object {
        val FILE_SPECIFIC_CODES = setOf(400, 403, 404, 410, 415, 501)
    }
}

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
    // UTF-8 : un identifiant ou mot de passe accentué serait refusé (401) en ISO-8859-1
    private val credentials = Credentials.basic(username, appPassword, Charsets.UTF_8)
    private val serverBaseUrl: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()

    /**
     * Liste récursivement les images du dossier [folderPath] (et de ses sous-dossiers).
     * @throws IOException si le serveur est injoignable ou répond une erreur.
     */
    fun listPhotos(folderPath: String): List<RemotePhoto> {
        val images = mutableListOf<DavEntry>()
        val videos = mutableListOf<DavEntry>()
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
                    isImage(entry.name) -> images += entry
                    // Jamais affichées comme des photos : seulement rattachées à l'une d'elles
                    isVideo(entry.name) -> videos += entry
                }
            }
        }
        val photos = pairLivePhotos(images, videos)
        Timber.i(
            "WebDAV : %d image(s) trouvée(s) dans %s, dont %d animée(s)",
            photos.size, folderPath, photos.count { it.sidecar != null },
        )
        return photos
    }

    /**
     * Rattache à chaque photo la vidéo de sa Live Photo. Nextcloud donne le fileid du
     * fichier jumeau (depuis la version 29) ; sinon on retombe sur la convention de
     * l'application iOS, qui envoie les deux fichiers sous le même nom de base.
     *
     * Le repli est volontairement restreint aux `.mov` et aux fichiers de taille
     * plausible : un `.mp4` qui porterait le même nom qu'une photo serait sans doute
     * une vidéo sans rapport, qu'il ne faut ni télécharger ni jouer.
     */
    private fun pairLivePhotos(images: List<DavEntry>, videos: List<DavEntry>): List<RemotePhoto> {
        val byFileId = videos.mapNotNull { video -> video.fileId?.let { it to video } }.toMap()
        val byStem = videos
            .filter { extensionOf(it.name) == "mov" && it.size <= MAX_SIDECAR_BYTES }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }

        return images.map { image ->
            val video = byFileId[image.livePhotoFileId]
                ?: byStem[image.name.substringBeforeLast('.').lowercase()]
            RemotePhoto(
                url = image.url,
                name = image.name,
                etag = image.etag,
                size = image.size,
                fileId = image.fileId,
                sidecar = video?.let { SidecarVideo(it.url, it.size) },
            )
        }
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

    /**
     * Télécharge les octets [from]..[to] (inclus) de [url] dans [destination].
     * Sert à extraire la vidéo collée à la fin d'un Motion Photo sans télécharger
     * la photo. Un serveur qui ignorerait l'en-tête Range répondrait 200 et le
     * fichier complet : c'est traité comme une erreur, pas écrit tel quel.
     */
    fun downloadRange(url: HttpUrl, from: Long, to: Long, destination: File) {
        val request = request(url).newBuilder()
            .header("Range", "bytes=$from-$to")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code != 206) {
                throw HttpStatusException(response.code, "GET Range ${url.encodedPath} : HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("GET Range ${url.encodedPath} : réponse vide")
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    private fun request(url: HttpUrl): Request =
        Request.Builder().url(url).header("Authorization", credentials).get().build()

    private fun <T> execute(request: Request, readBody: (InputStream) -> T): T =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "GET ${request.url.encodedPath} : HTTP ${response.code}")
            }
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
                401 -> throw HttpStatusException(401, "Authentification refusée (401) : vérifiez l'identifiant et le token")
                404 -> throw HttpStatusException(404, "Dossier introuvable (404) : ${url.encodedPath}")
                else -> throw HttpStatusException(response.code, "PROPFIND : HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("PROPFIND : réponse vide")
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(body.byteStream(), null)
            }
            return MultistatusParser.parse(parser, url)
        }
    }

    companion object {
        private const val MAX_FOLDERS = 200

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp")

        /** Vidéos des Live Photos : jamais affichées seules, seulement jouées sur une photo. */
        val VIDEO_EXTENSIONS = setOf("mov", "mp4")

        // Un clip de Live Photo pèse quelques Mo ; au-delà, c'est une vidéo sans rapport
        private const val MAX_SIDECAR_BYTES = 20L * 1024 * 1024

        fun isImage(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTENSIONS

        fun isVideo(fileName: String): Boolean = extensionOf(fileName) in VIDEO_EXTENSIONS

        fun isJpeg(fileName: String): Boolean = extensionOf(fileName) in setOf("jpg", "jpeg")

        /** PNG et WebP ne contiennent presque jamais d'EXIF : inutile de les télécharger pour ça. */
        fun mayHaveExif(fileName: String): Boolean = extensionOf(fileName) !in setOf("png", "webp")

        private fun extensionOf(fileName: String) = fileName.substringAfterLast('.', "").lowercase()

        // metadata-files-live-photo : Nextcloud 29 et plus. Une propriété inconnue du
        // serveur est simplement renvoyée vide, le listing fonctionne sans elle.
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:resourcetype/>
                <d:getetag/>
                <d:getcontentlength/>
                <oc:fileid/>
                <nc:metadata-files-live-photo/>
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

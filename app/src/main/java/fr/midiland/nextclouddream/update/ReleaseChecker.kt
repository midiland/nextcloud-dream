package fr.midiland.nextclouddream.update

import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Recherche de mise à jour sur les releases GitHub du projet, et téléchargement
 * de l'APK. L'installation elle-même est confiée au système (voir [ApkInstaller]).
 *
 * Toutes les méthodes sont bloquantes : à appeler depuis Dispatchers.IO.
 */
class ReleaseChecker(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {

    /**
     * Dernière version publiée, ou null si la release n'a pas d'APK exploitable.
     * @throws IOException si GitHub est injoignable ou répond une erreur.
     */
    fun fetchLatest(): AppRelease? {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub : HTTP ${response.code}")
            response.body?.string() ?: throw IOException("GitHub : réponse vide")
        }
        return ReleaseParser.parse(json).also {
            Timber.i("Dernière version publiée : %s", it?.versionName ?: "inconnue")
        }
    }

    /** Télécharge l'APK de [release] dans [destination] (écrasé s'il existe). */
    fun download(release: AppRelease, destination: File) {
        val request = Request.Builder().url(release.apkUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Téléchargement : HTTP ${response.code}")
            val body = response.body ?: throw IOException("Téléchargement : réponse vide")
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        Timber.i("APK %s téléchargé (%d octets)", release.versionName, destination.length())
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/midiland/nextcloud-dream/releases/latest"

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

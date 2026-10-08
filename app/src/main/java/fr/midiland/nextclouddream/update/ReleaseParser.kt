package fr.midiland.nextclouddream.update

import org.json.JSONObject

/** Dernière version publiée sur GitHub, telle que lue dans l'API des releases. */
data class AppRelease(
    /** Sans le v initial du tag (ex. « 1.2.3 »). */
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
)

/**
 * Lecture de la réponse de l'API GitHub `/releases/latest`.
 *
 * Séparé du client HTTP et indépendant d'Android (comme `MultistatusParser`)
 * pour être testable sur la JVM.
 */
object ReleaseParser {

    private val TAG_FORMAT = Regex("""^[vV]?(\d{1,2})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Numéro de version comparable, calculé depuis le tag git.
     * Même formule que `parseAppVersion` dans `app/build.gradle.kts` (MMmmmppp) :
     * les deux doivent rester d'accord, sinon la comparaison est fausse.
     */
    fun versionCodeOf(tag: String): Int? {
        val match = TAG_FORMAT.matchEntire(tag.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
    }

    /**
     * @return null si le JSON est inexploitable : tag hors format, ou aucun APK attaché
     * (release en cours de publication, build en échec…).
     */
    fun parse(json: String): AppRelease? {
        val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = release.optString("tag_name").trim()
        val versionCode = versionCodeOf(tag) ?: return null
        val assets = release.optJSONArray("assets") ?: return null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name").endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                return AppRelease(tag.trimStart('v', 'V'), versionCode, url)
            }
        }
        return null
    }
}

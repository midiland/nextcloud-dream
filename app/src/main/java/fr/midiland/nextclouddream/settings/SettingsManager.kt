package fr.midiland.nextclouddream.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Réglages de l'application.
 *
 * Seul le mot de passe d'application est secret : il est chiffré par [KeystoreCipher]
 * (clé AES dans l'Android Keystore). Les autres réglages (URL, identifiant, durées…)
 * sont dans des préférences classiques, privées à l'app et exclues des sauvegardes
 * (allowBackup="false").
 *
 * Une seule instance par processus (AppContainer).
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEYSTORE_ALIAS)

    // Mot de passe déchiffré gardé en mémoire : évite un appel au Keystore à chaque photo
    @Volatile
    private var decryptedPassword: String? = null

    /** URL de base du serveur, sans slash final (ex. https://cloud.exemple.fr). */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value.trim().trimEnd('/')) }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USERNAME, value.trim()) }

    /** Mot de passe d'application Nextcloud (Paramètres → Sécurité), pas le mot de passe principal. */
    var appPassword: String
        get() = decryptedPassword
            ?: prefs.getString(KEY_APP_PASSWORD_ENCRYPTED, null)?.let(cipher::decrypt).orEmpty()
                .also { decryptedPassword = it }
        set(value) {
            val password = value.trim()
            prefs.edit {
                if (password.isEmpty()) remove(KEY_APP_PASSWORD_ENCRYPTED)
                else putString(KEY_APP_PASSWORD_ENCRYPTED, cipher.encrypt(password))
            }
            decryptedPassword = password
        }

    /** Dossier à afficher, relatif à la racine de l'utilisateur (ex. /Photos/ScreenSaver). */
    var folderPath: String
        get() = prefs.getString(KEY_FOLDER_PATH, DEFAULT_FOLDER).orEmpty()
        set(value) = prefs.edit { putString(KEY_FOLDER_PATH, value.trim()) }

    var slideIntervalSeconds: Int
        get() = prefs.getInt(KEY_SLIDE_INTERVAL, DEFAULT_SLIDE_INTERVAL_S)
        set(value) = prefs.edit { putInt(KEY_SLIDE_INTERVAL, value.coerceIn(MIN_SLIDE_INTERVAL_S, MAX_SLIDE_INTERVAL_S)) }

    var showClock: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_CLOCK, value) }

    /** Affiche la date de prise de vue et le lieu de chaque photo. */
    var showPhotoInfo: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PHOTO_INFO, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_PHOTO_INFO, value) }

    /**
     * Joue la courte vidéo des photos animées (Live Photo iPhone, Motion Photo Pixel).
     * Chaque clip coûte quelques Mo de plus que la photo : à décocher sur une
     * connexion limitée, ou sur un appareil dont le stockage est juste.
     */
    var playLivePhotos: Boolean
        get() = prefs.getBoolean(KEY_PLAY_LIVE_PHOTOS, true)
        set(value) = prefs.edit { putBoolean(KEY_PLAY_LIVE_PHOTOS, value) }

    var syncIntervalHours: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_H)
        set(value) = prefs.edit { putInt(KEY_SYNC_INTERVAL, value.coerceIn(MIN_SYNC_INTERVAL_H, MAX_SYNC_INTERVAL_H)) }

    /** Taille maximale du cache de secours, en Mo (~300-500 Ko par photo). */
    var maxCacheMb: Int
        get() = prefs.getInt(KEY_MAX_CACHE, DEFAULT_MAX_CACHE_MB)
        set(value) = prefs.edit { putInt(KEY_MAX_CACHE, value.coerceIn(MIN_MAX_CACHE_MB, MAX_MAX_CACHE_MB)) }

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank()

    companion object {
        private const val PREFS_NAME = "nextcloud_dream_prefs"
        private const val KEYSTORE_ALIAS = "nextcloud_dream_app_password"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_PASSWORD_ENCRYPTED = "app_password_encrypted"
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_SLIDE_INTERVAL = "slide_interval_s"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_PHOTO_INFO = "show_photo_info"
        private const val KEY_PLAY_LIVE_PHOTOS = "play_live_photos"
        private const val KEY_SYNC_INTERVAL = "sync_interval_h"
        private const val KEY_MAX_CACHE = "max_cache_mb"

        const val DEFAULT_FOLDER = "/Photos/ScreenSaver"
        const val DEFAULT_SLIDE_INTERVAL_S = 20
        const val MIN_SLIDE_INTERVAL_S = 5
        const val MAX_SLIDE_INTERVAL_S = 300
        const val DEFAULT_SYNC_INTERVAL_H = 6
        const val MIN_SYNC_INTERVAL_H = 1
        const val MAX_SYNC_INTERVAL_H = 48
        const val DEFAULT_MAX_CACHE_MB = 50
        const val MIN_MAX_CACHE_MB = 10
        const val MAX_MAX_CACHE_MB = 2000
    }
}

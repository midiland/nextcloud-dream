package com.nextclouddream.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Réglages de l'application, stockés chiffrés (clé AES dans l'Android Keystore).
 * Le token d'application Nextcloud n'est jamais écrit en clair sur le disque.
 */
class SettingsManager(context: Context) {

    // Ouvertes une seule fois par processus : la création (Keystore + Tink) est lente
    // sur la Mi Box et n'est pas sûre en accès concurrent (économiseur + worker)
    private val prefs: SharedPreferences = sharedPrefs(context.applicationContext)

    /** URL de base du serveur, sans slash final (ex. https://cloud.exemple.fr). */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value.trim().trimEnd('/')) }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USERNAME, value.trim()) }

    /** Mot de passe d'application Nextcloud (Paramètres → Sécurité), pas le mot de passe principal. */
    var appPassword: String
        get() = prefs.getString(KEY_APP_PASSWORD, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_APP_PASSWORD, value.trim()) }

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
        private const val PREFS_NAME = "nextcloud_dream_secure_prefs"

        @Volatile
        private var instance: SharedPreferences? = null

        private fun sharedPrefs(context: Context): SharedPreferences =
            instance ?: synchronized(this) {
                instance ?: openEncryptedPrefs(context).also { instance = it }
            }

        /**
         * Ouvre les préférences chiffrées. Elles ne sont effacées que si la clé du Keystore
         * est réellement perdue (erreur de chiffrement deux fois de suite, ex. après une
         * restauration) : une erreur passagère ne doit jamais effacer les identifiants.
         */
        private fun openEncryptedPrefs(context: Context): SharedPreferences {
            var lastError: Exception? = null
            repeat(2) {
                try {
                    return createEncryptedPrefs(context)
                } catch (e: GeneralSecurityException) {
                    lastError = e
                } catch (e: IOException) {
                    // Inclut InvalidProtocolBufferException (keyset Tink illisible)
                    lastError = e
                }
            }
            Timber.e(lastError, "Préférences chiffrées illisibles (clé perdue ?), réinitialisation")
            context.deleteSharedPreferences(PREFS_NAME)
            return createEncryptedPrefs(context)
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_PASSWORD = "app_password"
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_SLIDE_INTERVAL = "slide_interval_s"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_PHOTO_INFO = "show_photo_info"
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

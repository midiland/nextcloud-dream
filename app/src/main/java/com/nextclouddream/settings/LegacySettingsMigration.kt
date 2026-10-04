package com.nextclouddream.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.io.File
import java.security.KeyStore

/**
 * Migration des réglages des premières versions, stockés avec EncryptedSharedPreferences
 * (bibliothèque androidx.security:security-crypto, dépréciée) vers [SettingsManager].
 *
 * Exécutée une seule fois : les anciennes préférences et leur clé sont supprimées ensuite.
 * À retirer, avec la dépendance security-crypto, quand plus aucun appareil n'a l'ancienne version.
 */
object LegacySettingsMigration {

    private const val LEGACY_PREFS_NAME = "nextcloud_dream_secure_prefs"
    private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    @Synchronized
    fun migrateIfNeeded(context: Context, settings: SettingsManager) {
        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")
        if (!legacyFile.exists()) return

        try {
            val legacy = EncryptedSharedPreferences.create(
                context,
                LEGACY_PREFS_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            legacy.getString("server_url", null)?.let { settings.serverUrl = it }
            legacy.getString("username", null)?.let { settings.username = it }
            legacy.getString("app_password", null)?.let { settings.appPassword = it }
            legacy.getString("folder_path", null)?.let { settings.folderPath = it }
            if (legacy.contains("slide_interval_s")) settings.slideIntervalSeconds = legacy.getInt("slide_interval_s", 0)
            if (legacy.contains("sync_interval_h")) settings.syncIntervalHours = legacy.getInt("sync_interval_h", 0)
            if (legacy.contains("max_cache_mb")) settings.maxCacheMb = legacy.getInt("max_cache_mb", 0)
            if (legacy.contains("show_clock")) settings.showClock = legacy.getBoolean("show_clock", true)
            if (legacy.contains("show_photo_info")) settings.showPhotoInfo = legacy.getBoolean("show_photo_info", true)
            Timber.i("Réglages migrés depuis l'ancien stockage chiffré")
        } catch (e: Exception) {
            // Clé perdue ou fichier abîmé : rien de récupérable, l'utilisateur ressaisira la configuration
            Timber.e(e, "Anciens réglages illisibles, migration impossible")
        }

        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Timber.w(e, "Ancienne clé du Keystore non supprimée")
        }
    }
}

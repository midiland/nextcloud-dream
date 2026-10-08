package fr.midiland.nextclouddream.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Installation d'un APK téléchargé, via le `PackageInstaller` du système.
 *
 * L'écran de confirmation du système est incontournable : une installation
 * silencieuse demanderait d'être device owner ou application système. L'utilisateur
 * doit aussi avoir autorisé l'application à installer des APK, une fois pour toutes
 * (voir [canInstall] et [unknownSourcesSettings]).
 *
 * ⚠️ Android refuse une mise à jour dont la signature diffère de la version installée :
 * la release doit être signée avec la même clé (secrets SIGNING_* de la CI).
 */
class ApkInstaller(private val context: Context) {

    /** Vrai si l'utilisateur a autorisé l'application à installer des APK. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Écran système où accorder cette autorisation. */
    fun unknownSourcesSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Fichier de travail du téléchargement. Dans le cache : le système peut le purger. */
    fun downloadTarget(): File = File(context.cacheDir, DOWNLOAD_NAME)

    /** Faux si l'appareil est trop plein pour télécharger puis installer (Mi Box…). */
    fun hasRoomForUpdate(): Boolean = context.cacheDir.usableSpace > MIN_FREE_BYTES

    /**
     * Remet [apk] au système, qui affichera sa demande de confirmation.
     * Retourne dès que la session est validée : la suite se passe dans l'interface système.
     */
    fun install(apk: File) {
        if (!apk.isFile || apk.length() == 0L) throw IOException("APK téléchargé introuvable ou vide")

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_ENTRY, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusSender(sessionId))
        }
        Timber.i("Session d'installation %d transmise au système", sessionId)
    }

    /** Le système renvoie l'avancement ici, et c'est par là qu'arrive l'écran de confirmation. */
    private fun statusSender(sessionId: Int): android.content.IntentSender {
        val intent = Intent(InstallResultReceiver.ACTION).setPackage(context.packageName)
        // MUTABLE : le système complète l'intent avec le statut et l'écran de confirmation
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private companion object {
        const val DOWNLOAD_NAME = "update.apk"
        const val APK_ENTRY = "nextcloud-dream"

        /** Même seuil que le cache des photos : sous cette barre, l'appareil sature. */
        const val MIN_FREE_BYTES = 30L * 1024 * 1024
    }
}

/**
 * Reçoit l'avancement de l'installation. Son seul rôle utile est d'ouvrir l'écran
 * de confirmation que le système nous renvoie ; le reste part dans les logs.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    Timber.w("Installation : écran de confirmation absent")
                    return
                }
                // Lancé depuis un receiver : hors de toute tâche existante
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }

            PackageInstaller.STATUS_SUCCESS ->
                Timber.i("Mise à jour installée")

            else ->
                Timber.w(
                    "Installation refusée (statut %d) : %s",
                    status,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(),
                )
        }
    }

    companion object {
        const val ACTION = "fr.midiland.nextclouddream.INSTALL_RESULT"
    }
}

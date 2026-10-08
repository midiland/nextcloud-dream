package fr.midiland.nextclouddream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fr.midiland.nextclouddream.settings.SettingsManager
import fr.midiland.nextclouddream.databinding.ActivityMainBinding
import fr.midiland.nextclouddream.remote.HttpStatusException
import fr.midiland.nextclouddream.remote.NextcloudWebDavClient
import fr.midiland.nextclouddream.update.ApkInstaller
import fr.midiland.nextclouddream.update.AppRelease
import fr.midiland.nextclouddream.update.InstallResultReceiver
import fr.midiland.nextclouddream.update.ReleaseChecker
import fr.midiland.nextclouddream.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Écran de configuration : serveur, identifiants, dossier et options du diaporama.
 * Accessible depuis le launcher et depuis les paramètres de l'économiseur d'écran.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = container.settings

        loadSettings()
        enableDpadNavigation()
        binding.saveButton.setOnClickListener { saveAndSync() }
        binding.testButton.setOnClickListener { testConnection() }

        // Inscrit pour toute la vie de l'activité, pas seulement quand elle est visible :
        // le verdict de l'installation arrive pendant que l'écran système est au premier plan.
        ContextCompat.registerReceiver(
            this,
            installResult,
            IntentFilter(InstallResultReceiver.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        checkForUpdate()
    }

    /**
     * Cherche une version plus récente sur les releases GitHub, à l'ouverture de l'écran.
     *
     * Le bouton n'apparaît que si une mise à jour existe : un bouton qui ne fait rien
     * dans la quasi-totalité des cas n'apprend rien à l'utilisateur. Un échec de la
     * requête ne dit rien non plus — l'écran de configuration doit rester utilisable
     * hors ligne, c'est même le cas le plus fréquent quand le serveur ne répond pas.
     */
    private fun checkForUpdate() {
        val current = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        binding.updateStatus.text = current

        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { ReleaseChecker().fetchLatest() }.getOrNull()
            }
            if (release == null) return@launch
            if (release.versionCode <= BuildConfig.VERSION_CODE) {
                binding.updateStatus.text =
                    getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
                return@launch
            }
            binding.updateButton.text = getString(R.string.update_available, release.versionName)
            binding.updateButton.visibility = View.VISIBLE
            binding.updateButton.setOnClickListener { downloadAndInstall(release) }
        }
    }

    /** Télécharge l'APK de [release], puis laisse le système demander confirmation. */
    private fun downloadAndInstall(release: AppRelease) {
        val installer = ApkInstaller(this)

        if (!installer.canInstall()) {
            // Autorisation « sources inconnues », à accorder une fois à cette application
            binding.updateStatus.setText(R.string.update_permission_needed)
            runCatching { startActivity(installer.unknownSourcesSettings()) }
            return
        }
        if (!installer.hasRoomForUpdate()) {
            binding.updateStatus.setText(R.string.update_no_room)
            return
        }

        binding.updateButton.isEnabled = false
        binding.updateStatus.setText(R.string.update_downloading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = installer.downloadTarget()
                    ReleaseChecker().download(release, apk)
                    installer.install(apk)
                }
            }
            binding.updateStatus.text = result.fold(
                onSuccess = { getString(R.string.update_installing) },
                onFailure = { getString(R.string.update_failed, describeError(it)) },
            )
            binding.updateButton.isEnabled = result.isFailure
        }
    }

    /**
     * Suite de l'installation, une fois l'écran système passé. Sans cela, un refus
     * laisse l'écran sur « installation en cours » et le bouton grisé : l'utilisateur
     * ne sait pas que c'est fini, ni pourquoi ça a échoué.
     *
     * Le cas le plus probable est de loin la signature : une release construite avec
     * une autre clé que celle de la version installée.
     */
    private val installResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            when (status) {
                // Écran de confirmation : InstallResultReceiver s'en charge
                PackageInstaller.STATUS_PENDING_USER_ACTION -> return
                PackageInstaller.STATUS_SUCCESS ->
                    binding.updateStatus.setText(R.string.update_installed)
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    // Annulé par l'utilisateur : rien à expliquer, on repropose
                    binding.updateStatus.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    binding.updateStatus.setText(R.string.update_signature_mismatch)
                else ->
                    binding.updateStatus.text = getString(R.string.update_refused, status)
            }
            binding.updateButton.isEnabled = status != PackageInstaller.STATUS_SUCCESS
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installResult)
    }

    /**
     * Navigation à la télécommande entre les champs de saisie.
     *
     * Un EditText consomme les flèches haut et bas (elles déplacent le curseur dans
     * le texte) : sans cela, le focus reste bloqué sur le premier champ et l'écran
     * est inutilisable sans clavier. Les champs tiennent sur une ligne, donc on rend
     * ces touches au déplacement du focus. Le premier champ prend le focus au
     * démarrage, sinon le premier appui sur la télécommande est perdu.
     */
    private fun enableDpadNavigation() = with(binding) {
        listOf(serverUrl, username, appPassword, folderPath, slideInterval, syncInterval, maxCache)
            .forEach { it.moveFocusOnVerticalDpad() }
        serverUrl.requestFocus()
    }

    private fun EditText.moveFocusOnVerticalDpad() = setOnKeyListener { view, keyCode, event ->
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return@setOnKeyListener false
        }
        // On ne réagit qu'à l'appui, mais les deux événements sont consommés
        // pour éviter que le relâchement ne retombe sur le champ d'origine.
        if (event.action == KeyEvent.ACTION_DOWN) {
            view.focusSearch(direction)?.requestFocus(direction)
        }
        true
    }

    private fun loadSettings() = with(binding) {
        serverUrl.setText(settings.serverUrl)
        username.setText(settings.username)
        appPassword.setText(settings.appPassword)
        folderPath.setText(settings.folderPath)
        slideInterval.setText(settings.slideIntervalSeconds.toString())
        syncInterval.setText(settings.syncIntervalHours.toString())
        maxCache.setText(settings.maxCacheMb.toString())
        showClock.isChecked = settings.showClock
        showPhotoInfo.isChecked = settings.showPhotoInfo
    }

    private fun saveAndSync() {
        with(binding) {
            settings.serverUrl = serverUrl.text.toString()
            settings.username = username.text.toString()
            settings.appPassword = appPassword.text.toString()
            settings.folderPath = folderPath.text.toString()
            slideInterval.text.toString().toIntOrNull()?.let { settings.slideIntervalSeconds = it }
            syncInterval.text.toString().toIntOrNull()?.let { settings.syncIntervalHours = it }
            maxCache.text.toString().toIntOrNull()?.let { settings.maxCacheMb = it }
            settings.showClock = showClock.isChecked
            settings.showPhotoInfo = showPhotoInfo.isChecked
        }
        // Réaffiche les valeurs normalisées (bornes, slash final retiré…)
        loadSettings()

        if (!settings.isConfigured) {
            binding.status.setText(R.string.config_incomplete)
            return
        }
        SyncWorker.schedule(this)

        binding.status.setText(R.string.config_syncing)
        lifecycleScope.launch {
            val result = container.repository.syncNow()
            binding.status.text = result.fold(
                onSuccess = { count -> resources.getQuantityString(R.plurals.config_sync_done, count, count) },
                onFailure = ::describeError,
            )
        }
    }

    /** Vérifie l'accès au dossier sans rien enregistrer ni télécharger. */
    private fun testConnection() {
        val url = binding.serverUrl.text.toString().trim()
        val user = binding.username.text.toString().trim()
        val password = binding.appPassword.text.toString().trim()
        val folder = binding.folderPath.text.toString().trim()

        binding.status.setText(R.string.config_testing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { NextcloudWebDavClient(url, user, password).listPhotos(folder).size }
            }
            binding.status.text = result.fold(
                onSuccess = { count -> resources.getQuantityString(R.plurals.config_test_ok, count, count) },
                onFailure = ::describeError,
            )
        }
    }

    /** Message d'erreur compréhensible, dans la langue de l'appareil. */
    private fun describeError(error: Throwable): String = when (error) {
        is HttpStatusException -> when (error.code) {
            401, 403 -> getString(R.string.error_auth)
            404 -> getString(R.string.error_folder_not_found)
            else -> getString(R.string.error_http, error.code)
        }
        is SSLException -> getString(R.string.error_certificate)
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            getString(R.string.error_server_unreachable)
        // URL mal formée (refusée par OkHttp)
        is IllegalArgumentException -> getString(R.string.error_invalid_url)
        // Configuration incomplète (synchro lancée sans identifiants)
        is IllegalStateException -> getString(R.string.config_incomplete)
        else -> getString(R.string.error_generic, error.message ?: error.javaClass.simpleName)
    }
}

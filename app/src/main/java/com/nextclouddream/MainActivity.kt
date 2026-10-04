package com.nextclouddream

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nextclouddream.settings.SettingsManager
import com.nextclouddream.databinding.ActivityMainBinding
import com.nextclouddream.remote.HttpStatusException
import com.nextclouddream.remote.NextcloudWebDavClient
import com.nextclouddream.worker.SyncWorker
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
        binding.saveButton.setOnClickListener { saveAndSync() }
        binding.testButton.setOnClickListener { testConnection() }
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

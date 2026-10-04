package com.nextclouddream

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.nextclouddream.worker.SyncWorker
import timber.log.Timber

/**
 * Point d'entrée de l'application : initialise les logs et
 * s'assure que la synchro périodique est planifiée.
 */
class NextcloudDreamApp : Application(), ImageLoaderFactory {

    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Logs visibles avec : adb logcat --pid=$(adb shell pidof com.nextclouddream)
        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())
        if (container.settings.isConfigured) {
            SyncWorker.schedule(this)
        }
    }

    override fun newImageLoader(): ImageLoader = buildImageLoader()
}

/**
 * Chargeur d'images de toute l'app, sans cache : les photos sont déjà des fichiers
 * locaux et chacune ne revient qu'une fois par cycle ; garder des bitmaps en mémoire
 * ne ferait que consommer la RAM limitée de la box.
 */
private fun NextcloudDreamApp.buildImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
        .memoryCache(null)
        .diskCache(null)
        .build()

/**
 * En release : seulement les infos, avertissements et erreurs (synchro, échecs),
 * pour pouvoir diagnostiquer sur la box sans le bruit des logs de debug.
 */
private class ReleaseTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        Log.println(priority, TAG, if (t != null) "$message\n${Log.getStackTraceString(t)}" else message)
    }

    private companion object {
        const val TAG = "NextcloudDream"
    }
}

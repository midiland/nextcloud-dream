package com.nextclouddream

import android.app.Application
import android.util.Log
import com.nextclouddream.data.SettingsManager
import com.nextclouddream.worker.SyncWorker
import timber.log.Timber

/**
 * Point d'entrée de l'application : initialise les logs et
 * s'assure que la synchro périodique est planifiée.
 */
class NextcloudDreamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Logs visibles avec : adb logcat --pid=$(adb shell pidof com.nextclouddream)
        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())
        if (SettingsManager(this).isConfigured) {
            SyncWorker.schedule(this)
        }
    }
}

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

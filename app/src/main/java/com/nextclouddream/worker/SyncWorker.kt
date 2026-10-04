package com.nextclouddream.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nextclouddream.data.SettingsManager
import com.nextclouddream.network.HttpStatusException
import com.nextclouddream.network.PhotoRepository
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Rafraîchit le cache de photos en arrière-plan, même quand l'économiseur ne tourne pas. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("SyncWorker : début (tentative %d)", runAttemptCount + 1)
        return PhotoRepository(applicationContext).syncNow().fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                // Non configuré, identifiants ou dossier invalides : réessayer ne changera rien.
                // Serveur injoignable : WorkManager réessaie plus tard (backoff exponentiel).
                val permanent = e is IllegalStateException || (e is HttpStatusException && e.isPermanent)
                if (permanent || runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
            },
        )
    }

    companion object {
        private const val PERIODIC_WORK = "nextcloud_photo_sync"
        private const val ONE_TIME_WORK = "nextcloud_photo_sync_now"
        private const val MAX_RETRIES = 3

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Planifie (ou met à jour) la synchro périodique selon les réglages. */
        fun schedule(context: Context) {
            val hours = SettingsManager(context).syncIntervalHours.toLong()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            Timber.i("Synchro périodique planifiée toutes les %d h", hours)
        }

        /** Lance une synchro immédiate (après modification des réglages). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

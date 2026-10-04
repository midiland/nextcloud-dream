package com.nextclouddream.dream

import com.nextclouddream.container
import android.service.dreams.DreamService
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.nextclouddream.R
import com.nextclouddream.photos.IndexedPhoto
import com.nextclouddream.photos.PhotoMetadata
import com.nextclouddream.settings.SettingsManager
import com.nextclouddream.photos.PhotoRepository
import com.nextclouddream.ui.SlideshowView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

/**
 * Économiseur d'écran Android TV : diaporama des photos d'un dossier Nextcloud.
 *
 * Cycle de vie d'un DreamService :
 *  - onAttachedToWindow : la fenêtre existe → on configure l'affichage et le layout
 *  - onDreamingStarted  : le rêve démarre → on lance le diaporama
 *  - onDreamingStopped  : l'utilisateur réveille la TV → on arrête le diaporama
 *  - onDetachedFromWindow : la fenêtre disparaît → on libère tout
 *
 * Mode hybride : la liste des photos vient de l'index local (mis à jour par
 * SyncWorker), chaque image est téléchargée à la volée avec une d'avance, et
 * le cache de secours prend le relais si le serveur est injoignable.
 */
class NextcloudDreamService : DreamService() {

    // Une exception non gérée dans une coroutine ne doit jamais faire planter l'économiseur
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Timber.e(e, "Erreur inattendue dans le diaporama")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)

    private var slideshowJob: Job? = null
    private var clockJob: Job? = null

    private lateinit var settings: SettingsManager
    private lateinit var repository: PhotoRepository
    private lateinit var slides: SlideSource

    private lateinit var slideshowView: SlideshowView
    private lateinit var clockContainer: View
    private lateinit var photoInfoContainer: View
    private lateinit var photoDateView: TextView
    private lateinit var photoPlaceView: TextView
    private lateinit var messageView: TextView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Pas d'interaction : n'importe quelle touche de la télécommande réveille la TV
        isInteractive = false
        // Plein écran, barres système masquées
        isFullscreen = true
        // Luminosité normale (true par défaut) : on veut voir les photos, pas un écran assombri
        isScreenBright = true

        setContentView(R.layout.dream_nextcloud)
        slideshowView = findViewById(R.id.slideshow)
        clockContainer = findViewById(R.id.clock_container)
        photoInfoContainer = findViewById(R.id.photo_info_container)
        photoDateView = findViewById(R.id.photo_date)
        photoPlaceView = findViewById(R.id.photo_place)
        messageView = findViewById(R.id.message)

        settings = container.settings
        repository = container.repository
        slides = SlideSource(repository)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Timber.i("Économiseur démarré")

        slideshowView.fadeDurationMs = FADE_DURATION_MS
        clockContainer.isVisible = settings.showClock
        if (settings.showClock) {
            clockJob = scope.launch { shiftClockPeriodically() }
        }
        slideshowJob = scope.launch { runSlideshow() }
    }

    override fun onDreamingStopped() {
        Timber.i("Économiseur arrêté")
        slideshowJob?.cancel()
        clockJob?.cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        // Annule aussi une éventuelle synchro lancée depuis le service
        scope.cancel()
        slideshowView.clear()
        super.onDetachedFromWindow()
    }

    /**
     * Boucle principale : affiche une photo, précharge la suivante pendant l'affichage,
     * attend l'intervalle, puis passe à la suivante (déjà prête : aucune attente réseau).
     * Une erreur imprévue ne doit jamais figer l'écran : elle est journalisée et la
     * boucle repart après une pause.
     */
    private suspend fun runSlideshow() {
        val intervalMs = settings.slideIntervalSeconds * 1000L
        val showPhotoInfo = settings.showPhotoInfo
        awaitPhotos()
        hideMessage()

        while (currentCoroutineContext().isActive) {
            try {
                coroutineScope {
                    var prefetched: Deferred<Slide?>? = null
                    while (isActive) {
                        val slide = prefetched?.await() ?: slides.next()
                        prefetched = null
                        if (slide == null) {
                            // Ni le serveur ni le cache de secours ne fournissent de photo
                            Timber.w("Aucune photo affichable, nouvel essai dans %d min", RETRY_DELAY_MS / 60_000)
                            showMessage(R.string.dream_no_photos)
                            delay(RETRY_DELAY_MS)
                            awaitPhotos()
                            hideMessage()
                            continue
                        }

                        hideMessage()
                        val metadata = if (showPhotoInfo) slide.metadata else null
                        if (!slideshowView.showPhoto(slide.file) { updatePhotoInfo(metadata) }) {
                            // Fichier corrompu ou format non décodable : supprimé du cache
                            // (re-téléchargé au prochain passage) et écarté de ce cycle
                            Timber.w("Impossible d'afficher %s, photo ignorée", slide.key)
                            repository.evict(slide.key)
                            slides.remove(slide.key)
                            continue
                        }

                        // Une photo d'avance : la suivante se télécharge pendant l'affichage de celle-ci
                        prefetched = async { slides.next() }
                        delay(intervalMs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Erreur dans le diaporama, reprise dans %d s", ERROR_PAUSE_MS / 1000)
                delay(ERROR_PAUSE_MS)
            }
        }
    }

    /**
     * Fournit à [slides] les photos à afficher, en attendant si nécessaire.
     *  1. Index connu → utilisé immédiatement (même hors connexion)
     *  2. Pas d'index (premier lancement) → synchro, diaporama lancé dès que la liste est connue
     *  3. Synchro impossible mais photos en cache → on affiche le cache
     *  4. Toujours rien → message discret, nouvel essai plus tard
     */
    private suspend fun awaitPhotos() {
        while (true) {
            val index = repository.getIndex()
            if (index.isNotEmpty()) {
                Timber.i("%d photo(s) dans l'index", index.size)
                slides.reset(index.map { it.key }, index)
                return
            }

            if (!settings.isConfigured) {
                Timber.w("Application non configurée")
                showMessage(R.string.dream_not_configured)
            } else {
                showMessage(R.string.dream_loading)
                val synced = syncAndWaitForIndex()
                if (synced.isNotEmpty()) {
                    slides.reset(synced.map { it.key }, synced)
                    return
                }
                val cached = repository.getCachedPhotos()
                if (cached.isNotEmpty()) {
                    slides.reset(cached.map { it.nameWithoutExtension }, index = emptyList())
                    return
                }
                showMessage(R.string.dream_no_photos)
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * Lance une synchro et rend la main dès que la liste des photos est connue,
     * sans attendre la lecture des métadonnées ni le préchargement du cache.
     * La synchro continue en arrière-plan dans [scope].
     */
    private suspend fun syncAndWaitForIndex(): List<IndexedPhoto> {
        val sync = scope.launch {
            repository.syncNow().onSuccess { count -> Timber.i("Synchro terminée : %d photo(s)", count) }
        }
        while (sync.isActive) {
            delay(INDEX_POLL_INTERVAL_MS)
            val index = repository.getIndex()
            if (index.isNotEmpty()) return index
        }
        return repository.getIndex()
    }

    /**
     * Décale légèrement l'horloge chaque minute pour éviter le marquage
     * (burn-in) des dalles OLED/plasma par un élément fixe.
     */
    private suspend fun shiftClockPeriodically() {
        val maxShiftPx = CLOCK_MAX_SHIFT_DP * resources.displayMetrics.density
        while (currentCoroutineContext().isActive) {
            clockContainer.animate()
                .translationX(Random.nextFloat() * maxShiftPx * -1f)
                .translationY(Random.nextFloat() * maxShiftPx * -1f)
                .setDuration(FADE_DURATION_MS)
                .start()
            delay(CLOCK_SHIFT_INTERVAL_MS)
        }
    }

    /**
     * Change la date/le lieu en même temps que la photo : fondu sortant de l'ancien
     * texte pendant la 1re moitié du fondu, entrant du nouveau pendant la 2de.
     */
    private fun updatePhotoInfo(metadata: PhotoMetadata?) {
        val date = metadata?.formattedDate()
        val place = metadata?.place
        val halfFade = FADE_DURATION_MS / 2
        photoInfoContainer.animate().cancel()
        photoInfoContainer.animate()
            .alpha(0f)
            .setDuration(halfFade)
            .withEndAction {
                photoDateView.text = date
                photoDateView.isVisible = date != null
                photoPlaceView.text = place
                photoPlaceView.isVisible = place != null
                if (date != null || place != null) {
                    photoInfoContainer.animate().alpha(1f).setDuration(halfFade).start()
                }
            }
            .start()
    }

    private fun showMessage(@StringRes resId: Int) {
        messageView.setText(resId)
        messageView.isVisible = true
    }

    private fun hideMessage() {
        messageView.isVisible = false
    }

    private companion object {
        const val FADE_DURATION_MS = 1500L
        const val INDEX_POLL_INTERVAL_MS = 2_000L
        const val ERROR_PAUSE_MS = 10_000L
        const val RETRY_DELAY_MS = 5 * 60_000L
        const val CLOCK_SHIFT_INTERVAL_MS = 60_000L
        const val CLOCK_MAX_SHIFT_DP = 24f
    }
}

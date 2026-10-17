package fr.midiland.nextclouddream.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import fr.midiland.nextclouddream.photos.VideoStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lecture du court clip d'une photo animée : une fois, sans son, par-dessus la photo.
 *
 * `MediaPlayer` plutôt qu'ExoPlayer : il fait partie du framework (zéro octet dans
 * l'APK, qui en pèse 1,7) et il suffit largement pour un MP4 local de trois secondes.
 *
 * `TextureView` plutôt que `SurfaceView` : son contenu se fond comme n'importe quelle
 * vue (alpha animé) et se transforme par une matrice, deux choses qu'une SurfaceView
 * ne sait pas faire. C'est ce qui permet de caler la vidéo exactement sur le
 * rectangle de la photo et d'enchaîner sans que l'image saute.
 *
 * Le volume est forcé à zéro et **aucun focus audio n'est demandé** : un économiseur
 * d'écran ne doit pas couper la musique que l'utilisateur écoute.
 *
 * Sert aussi aux **vidéos seules**, lues en streaming depuis Nextcloud ([openStream],
 * [startVideo], [awaitVideoEnd], [releaseVideo]). Là, rien n'est local : chaque étape
 * réseau a un délai maximal, pour qu'un flux bloqué ne fige jamais le diaporama.
 */
class MotionLayer(context: Context) : TextureView(context) {

    /** Créée une seule fois : une Surface par clip fuirait les ressources natives. */
    private var surface: Surface? = null

    init {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        // Visible mais transparente : une TextureView masquée n'obtient pas de surface
        alpha = 0f
        isOpaque = false
    }

    /**
     * Joue [file] une fois, puis s'efface. Suspend jusqu'à la fin de la lecture.
     *
     * @param showWhole même décision de cadrage que la photo (voir [fr.midiland.nextclouddream.image.Framing]).
     * @param startUs position de l'image fixe dans le clip : y commencer rend le
     *   passage de la photo à la vidéo invisible. null → lecture depuis le début.
     * @return false si le clip n'a pas pu être lu (codec absent, fichier tronqué…).
     */
    suspend fun playOnce(file: File, showWhole: Boolean, startUs: Long?): Boolean {
        var player: MediaPlayer? = null
        try {
            val target = awaitSurface()
            player = withContext(Dispatchers.IO) {
                MediaPlayer().apply {
                    setDataSource(file.path)
                    setSurface(target)
                    setVolume(0f, 0f)
                    isLooping = false
                    prepare() // bloquant : jamais sur le thread principal
                }
            }
            if (player.videoWidth <= 0 || player.videoHeight <= 0) {
                Timber.w("Clip sans piste vidéo : %s", file.name)
                return false
            }
            Timber.d(
                "Clip %s : %dx%d, départ à %d ms",
                file.name, player.videoWidth, player.videoHeight, (startUs ?: 0) / 1000,
            )
            frameVideo(player.videoWidth, player.videoHeight, showWhole)
            // La texture garde la dernière image du clip précédent : on ne fait
            // apparaître la vue qu'une fois la première image de celui-ci posée dessus,
            // sinon l'ancien clip « flashe » au début du nouveau. Écouteur posé avant le
            // seekTo, qui peut déjà rendre une image.
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    animate().alpha(1f).setDuration(FADE_MS).withLayer().start()
                }
                false
            }
            if (startUs != null) {
                // SEEK_CLOSEST : on veut l'image exacte, pas l'image-clé la plus proche
                player.seekTo(startUs / 1000L, MediaPlayer.SEEK_CLOSEST)
            }
            awaitPlayback(player)
            fadeOut()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Clip illisible : %s (%s)", file.name, e.message)
            return false
        } finally {
            // Toujours libérer le MediaPlayer pour éviter les fuites, même en cas d'erreur
            animate().cancel()
            alpha = 0f
            runCatching { player?.release() }
        }
    }

    /**
     * Vidéo seule ouverte (flux préparé), prête à démarrer.
     * **IMPORTANT** : L'appelant DOIT appeler [releaseVideo] une fois la vidéo terminée
     * (même en cas d'erreur) pour libérer le MediaPlayer et éviter les fuites de ressources.
     */
    class OpenedVideo internal constructor(internal val player: MediaPlayer, val width: Int, val height: Int)

    /** Comment une vidéo seule s'est terminée. */
    enum class VideoEnd { Completed, Capped, Stalled, Error }

    /**
     * Ouvre le flux d'une vidéo seule, pendant que la photo courante reste à l'écran.
     * `prepareAsync` + délai maximal : un `prepare()` bloquant sur le réseau serait
     * ininterruptible, et la TV mettrait autant de temps à se réveiller.
     *
     * @throws Exception si le flux est injoignable, illisible, sans image, ou trop lent.
     */
    suspend fun openStream(stream: VideoStream): OpenedVideo {
        val target = awaitSurface()
        // Créé hors du thread principal (setDataSource peut toucher au réseau) ; ses
        // rappels arrivent quand même sur le thread principal, faute de Looper ici
        val player = withContext(Dispatchers.IO) {
            MediaPlayer().apply {
                // En-têtes à part, jamais dans l'URL : MediaPlayer journalise les URL
                setDataSource(context, Uri.parse(stream.url), stream.headers)
                setSurface(target)
                setVolume(0f, 0f)
                isLooping = false
            }
        }
        try {
            // withTimeoutOrNull, pas withTimeout : le dépassement de withTimeout est une
            // CancellationException, qui arrêterait tout le diaporama au lieu de cette vidéo
            withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    player.setOnPreparedListener { if (cont.isActive) cont.resume(Unit) }
                    player.setOnErrorListener { _, what, extra ->
                        if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer $what/$extra"))
                        true
                    }
                    player.prepareAsync()
                }
            } ?: throw IOException("Flux trop long à ouvrir (${PREPARE_TIMEOUT_MS / 1000} s)")
            if (player.videoWidth <= 0 || player.videoHeight <= 0) error("Vidéo sans piste d'image")
            Timber.d("Vidéo %s : %dx%d, %d s", stream, player.videoWidth, player.videoHeight, player.duration / 1000)
            return OpenedVideo(player, player.videoWidth, player.videoHeight)
        } catch (e: Throwable) {
            releaseVideo(player)
            throw e
        }
    }

    /**
     * Démarre la vidéo, cadrée comme une photo (voir [frameVideo]), et rend la main
     * quand sa première image est posée sur la surface. La vue reste transparente :
     * c'est à l'appelant de la faire apparaître (fondu enchaîné avec la photo).
     */
    suspend fun startVideo(video: OpenedVideo, showWhole: Boolean) {
        frameVideo(video.width, video.height, showWhole)
        // withTimeoutOrNull : voir openStream (un dépassement ne doit pas tout arrêter)
        withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                video.player.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && cont.isActive) cont.resume(Unit)
                    false
                }
                video.player.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer $what/$extra"))
                    true
                }
                video.player.start()
            }
        } ?: throw IOException("Première image trop longue à venir (${FIRST_FRAME_TIMEOUT_MS / 1000} s)")
    }

    /**
     * Attend la fin de la vidéo : fin naturelle, erreur, plafond de durée [capMs]
     * (compté depuis la première image) ou flux bloqué (position immobile pendant
     * STALL_TIMEOUT_MS, ex. réseau coupé). Au plafond ou au blocage, la vidéo est
     * mise en pause : sa dernière image reste à l'écran pour le fondu suivant.
     *
     * Une vidéo plus courte que [minMs] (la durée d'affichage d'une photo) tourne en
     * boucle, sans coupure, jusqu'à l'atteindre : elle s'arrête alors à la fin du tour
     * en cours, jamais au milieu. Le plafond reste prioritaire.
     */
    suspend fun awaitVideoEnd(video: OpenedVideo, capMs: Long, minMs: Long): VideoEnd {
        val player = video.player
        // Durée inconnue (flux sans index de durée) : pas de boucle
        val looping = player.duration in 1 until minMs
        runCatching { player.isLooping = looping }
        val ended = CompletableDeferred<VideoEnd>()
        player.setOnCompletionListener { ended.complete(VideoEnd.Completed) }
        player.setOnErrorListener { _, what, extra ->
            Timber.w("Vidéo interrompue (erreur %d/%d)", what, extra)
            ended.complete(VideoEnd.Error)
            true
        }
        val start = SystemClock.elapsedRealtime()
        var lastPosition = -1
        var lastMove = start
        while (true) {
            withTimeoutOrNull(WATCH_INTERVAL_MS) { ended.await() }?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now - start >= capMs) return VideoEnd.Capped.also { runCatching { player.pause() } }
            // Durée d'une photo atteinte : le tour en cours sera le dernier
            if (looping && now - start >= minMs && player.isLooping) runCatching { player.isLooping = false }
            val position = runCatching { player.currentPosition }.getOrDefault(lastPosition)
            if (position != lastPosition) {
                lastPosition = position
                lastMove = now
            } else if (now - lastMove >= STALL_TIMEOUT_MS) {
                return VideoEnd.Stalled.also { runCatching { player.pause() } }
            }
        }
    }

    /**
     * Libère le lecteur, hors du thread principal (le flux réseau peut mettre un
     * moment à se fermer) et **en attendant la fin** : le lecteur suivant ne doit se
     * connecter à la surface qu'une fois celui-ci détaché. Jamais interrompu.
     */
    suspend fun releaseVideo(video: OpenedVideo) = releaseVideo(video.player)

    private suspend fun releaseVideo(player: MediaPlayer) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { player.release() } }
    }

    /** Petite copie de l'image affichée (pour le fond flou d'une vidéo en portrait). */
    fun grabFrame(video: OpenedVideo): Bitmap? = runCatching {
        val height = (FRAME_GRAB_WIDTH * video.height / video.width).coerceAtLeast(1)
        getBitmap(FRAME_GRAB_WIDTH, height)
    }.getOrNull()

    /** Libère la surface (appelé quand l'économiseur se ferme). */
    fun release() {
        surface?.release()
        surface = null
    }

    private suspend fun awaitSurface(): Surface =
        surface ?: Surface(awaitSurfaceTexture()).also { surface = it }

    /** La texture n'existe qu'une fois la vue attachée et visible. */
    private suspend fun awaitSurfaceTexture(): SurfaceTexture =
        surfaceTexture ?: suspendCancellableCoroutine { cont ->
            surfaceTextureListener = object : SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    if (cont.isActive) cont.resume(texture)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            cont.invokeOnCancellation { surfaceTextureListener = null }
        }

    /**
     * Cale la vidéo sur le même rectangle que la photo. Le contenu d'une TextureView
     * est étiré sur toute la vue : la matrice le ramène aux proportions de la vidéo,
     * centrées, selon la même règle de cadrage que la photo affichée dessous.
     *
     * **Pas de rotation à appliquer ici**, bien que ce soit souvent conseillé : les
     * dimensions annoncées par MediaPlayer sont déjà celles de l'affichage (un `.mov`
     * d'iPhone codé en 1280×720 avec une rotation de 90° est annoncé 720×1280), et
     * l'image arrive déjà redressée sur la surface. Tourner une seconde fois donnait
     * une vidéo couchée et étirée en plein écran — constaté à l'écran sur Android 9.
     */
    private fun frameVideo(clipWidth: Int, clipHeight: Int, showWhole: Boolean) {
        val videoWidth = clipWidth.toFloat()
        val videoHeight = clipHeight.toFloat()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            // Vue pas encore mesurée : la vidéo sera simplement étirée sur la surface
            Timber.w("Clip joué sans cadrage : vue non mesurée")
            return
        }

        val scale = if (showWhole) {
            minOf(viewWidth / videoWidth, viewHeight / videoHeight)
        } else {
            maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
        }
        val drawnWidth = videoWidth * scale
        val drawnHeight = videoHeight * scale

        setTransform(
            Matrix().apply {
                setScale(drawnWidth / viewWidth, drawnHeight / viewHeight)
                postTranslate((viewWidth - drawnWidth) / 2f, (viewHeight - drawnHeight) / 2f)
            }
        )
    }

    /**
     * Démarre la lecture et rend la main à la fin du clip. La vidéo apparaît à sa
     * première image (voir l'écouteur posé dans [playOnce]).
     */
    private suspend fun awaitPlayback(player: MediaPlayer) = suspendCancellableCoroutine { cont ->
        player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
        // Un codec absent ne doit pas figer le diaporama : on abandonne ce clip
        player.setOnErrorListener { _, what, extra ->
            Timber.w("Lecture impossible (erreur %d/%d)", what, extra)
            if (cont.isActive) cont.resume(Unit)
            true
        }
        player.start()
        cont.invokeOnCancellation { runCatching { player.stop() } }
    }

    private suspend fun fadeOut() = suspendCancellableCoroutine { cont ->
        animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withLayer()
            .withEndAction { if (cont.isActive) cont.resume(Unit) }
            .start()
        cont.invokeOnCancellation { animate().cancel() }
    }


    private companion object {
        // Court : l'image de départ est la photo elle-même, il n'y a presque rien à masquer
        const val FADE_MS = 300L

        // Vidéos seules, en streaming : au-delà, on renonce et le diaporama continue
        const val PREPARE_TIMEOUT_MS = 20_000L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        const val STALL_TIMEOUT_MS = 15_000L
        const val WATCH_INTERVAL_MS = 500L

        // Assez pour un fond flou (il est réduit à 48 px de large)
        const val FRAME_GRAB_WIDTH = 96
    }
}

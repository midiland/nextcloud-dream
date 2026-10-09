package fr.midiland.nextclouddream.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume

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
            animate().cancel()
            alpha = 0f
            runCatching { player?.release() }
        }
    }

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
    }
}

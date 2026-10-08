package fr.midiland.nextclouddream.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import fr.midiland.nextclouddream.image.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume

/**
 * Vue de diaporama : deux "diapositives" superposées, la nouvelle apparaît
 * en fondu par-dessus l'ancienne, qui est ensuite libérée.
 *
 * Cadrage :
 *  - paysage : plein écran, recadré au centre (center crop)
 *  - portrait / carré : photo entière au centre, sur un fond flou tiré de la photo
 *
 * Mémoire : chaque image est décodée à la taille de l'écran (≈ 8 Mo en 1080p)
 * et le cache mémoire Coil est désactivé, donc au plus deux photos vivent
 * en même temps, quelle que soit la taille des originaux.
 */
class SlideshowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Durée du fondu enchaîné. */
    var fadeDurationMs: Long = 1500L

    private var front = Slide(context)
    private var back = Slide(context)

    /** Au-dessus des deux diapositives : la vidéo des photos animées. */
    private val motion = MotionLayer(context)

    /** Cadrage de la photo affichée : la vidéo doit suivre exactement la même règle. */
    private var showingWholeImage = false

    init {
        addView(back)
        addView(front)
        addView(motion)
    }

    /**
     * Décode [file] puis l'affiche en fondu. Suspend jusqu'à la fin de l'animation.
     * [onFadeStart] est appelé au début du fondu (pour synchroniser l'overlay date/lieu).
     * @return false si l'image n'a pas pu être décodée.
     */
    suspend fun showPhoto(file: File, onFadeStart: () -> Unit = {}): Boolean {
        val bounds = withContext(Dispatchers.IO) { readImageSize(file) }
        if (bounds == null) {
            Timber.w("Dimensions illisibles : %s", file.name)
            return false
        }
        val showWhole = Framing.shouldShowWholeImage(bounds.first, bounds.second)

        // Taille cible = taille de la vue, ou de l'écran si la vue n'est pas encore mesurée
        val targetWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val request = ImageRequest.Builder(context)
            .data(file)
            .size(targetWidth, targetHeight)
            // FILL = couvre l'écran (recadrage), FIT = tient dans l'écran (photo entière)
            .scale(if (showWhole) Scale.FIT else Scale.FILL)
            .precision(Precision.INEXACT)
            // Les fichiers sont déjà locaux et chaque photo ne revient qu'une fois par cycle :
            // inutile de garder des bitmaps en mémoire ou de dupliquer sur disque
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

        val result = context.imageLoader.execute(request)
        val drawable = (result as? SuccessResult)?.drawable
        if (drawable == null) {
            Timber.w("Décodage impossible : %s", file.name)
            return false
        }
        val blurredBackground = if (showWhole) {
            withContext(Dispatchers.Default) { BlurredBackground.create(file) }
        } else {
            null
        }

        // La diapositive "arrière" reçoit la nouvelle photo et passe au premier plan
        val incoming = back
        val outgoing = front
        incoming.photo.scaleType = if (showWhole) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        incoming.photo.setImageDrawable(drawable)
        incoming.background.setImageBitmap(blurredBackground)
        incoming.background.isVisible = blurredBackground != null
        incoming.alpha = 0f
        incoming.bringToFront()
        // La diapositive entrante vient de passer devant : la vidéo doit rester au-dessus
        motion.bringToFront()
        showingWholeImage = showWhole

        onFadeStart()
        crossfade(incoming, outgoing)

        // L'ancienne photo est invisible : on libère ses bitmaps
        outgoing.clear()
        front = incoming
        back = outgoing
        Timber.d("Photo affichée : %s (%s)", file.name, if (showWhole) "entière" else "plein écran")
        return true
    }

    /**
     * Joue le clip de la photo affichée, une fois et sans son, puis revient à la photo.
     * Suspend jusqu'à la fin. L'annulation (photo suivante) rend la main tout de suite.
     *
     * @param startUs position de l'image fixe dans le clip, pour un départ sans saut.
     */
    suspend fun playMotion(file: File, startUs: Long?): Boolean =
        motion.playOnce(file, showingWholeImage, startUs)

    /** Libère les images (appelé quand l'économiseur se ferme). */
    fun clear() {
        front.animate().cancel()
        back.animate().cancel()
        motion.animate().cancel()
        motion.alpha = 0f
        motion.release()
        front.clear()
        back.clear()
    }

    /** Largeur et hauteur de l'image, sans la décoder. */
    private fun readImageSize(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private suspend fun crossfade(incoming: Slide, outgoing: Slide) =
        suspendCancellableCoroutine { cont ->
            // withLayer : chaque diapositive est rendue une fois dans un calque GPU puis
            // simplement mélangée, au lieu d'être recomposée à chaque image (GPU modeste de la Mi Box)
            outgoing.animate().alpha(0f).setDuration(fadeDurationMs).withLayer().start()
            incoming.animate()
                .alpha(1f)
                .setDuration(fadeDurationMs)
                .withLayer()
                .withEndAction { if (cont.isActive) cont.resume(Unit) }
                .start()

            cont.invokeOnCancellation {
                incoming.animate().cancel()
                outgoing.animate().cancel()
            }
        }

    /** Une diapositive : fond flou (optionnel) + photo par-dessus. */
    private class Slide(context: Context) : FrameLayout(context) {

        val background = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Assombri pour que la photo nette ressorte
            setColorFilter(Color.argb(110, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            isVisible = false
        }

        val photo = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            addView(background)
            addView(photo)
        }

        fun clear() {
            photo.setImageDrawable(null)
            background.setImageDrawable(null)
            background.isVisible = false
        }
    }
}

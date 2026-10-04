package com.nextclouddream.image

/**
 * Règle de cadrage, partagée entre l'affichage (SlideshowView) et la réduction
 * des photos (ImageResizer) pour qu'ils prennent toujours la même décision.
 */
object Framing {

    /**
     * Photos en portrait ou presque carrées : affichées en entier sur fond flou,
     * car un recadrage plein écran 16:9 en couperait la moitié (souvent les visages).
     */
    fun shouldShowWholeImage(width: Int, height: Int): Boolean =
        width < height * MIN_CROP_ASPECT_RATIO

    // En dessous de ce ratio largeur/hauteur, on n'applique pas de recadrage plein écran
    private const val MIN_CROP_ASPECT_RATIO = 1.2f
}

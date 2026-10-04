package com.nextclouddream.photos

import java.io.File

/**
 * Ce dont l'économiseur a besoin pour obtenir les photos (implémenté par PhotoRepository).
 * Interface séparée pour pouvoir tester la logique du diaporama avec une fausse source.
 */
interface PhotoSource {
    /** Photos connues à la dernière synchro. */
    suspend fun getIndex(): List<IndexedPhoto>

    /** Photos présentes dans le cache de secours. */
    suspend fun getCachedPhotos(): List<File>

    /** Photo prête à afficher : depuis le cache, sinon téléchargée. */
    suspend fun fetchForDisplay(photo: IndexedPhoto): FetchResult
}

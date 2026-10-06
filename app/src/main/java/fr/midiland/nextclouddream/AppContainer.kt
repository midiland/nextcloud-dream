package fr.midiland.nextclouddream

import android.content.Context
import androidx.core.content.getSystemService
import fr.midiland.nextclouddream.photos.PhotoRepository
import fr.midiland.nextclouddream.remote.PlaceResolver
import fr.midiland.nextclouddream.settings.SettingsManager
import fr.midiland.nextclouddream.storage.PhotoCacheManager
import fr.midiland.nextclouddream.storage.PhotoIndexStore
import java.io.File

/**
 * Objets partagés par tout le processus : économiseur, worker et écran de configuration
 * utilisent les mêmes instances (une seule ouverture des préférences chiffrées, un seul
 * index, verrous et cache de géocodage communs). Créés à la première utilisation.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settings: SettingsManager by lazy { SettingsManager(appContext) }

    val repository: PhotoRepository by lazy {
        PhotoRepository(
            settings = settings,
            cache = PhotoCacheManager(File(appContext.filesDir, "photos"), appContext.getSystemService()),
            indexStore = PhotoIndexStore(File(appContext.filesDir, "photo_index.json")),
            placeResolver = PlaceResolver(appContext),
        )
    }
}

/** Accès au conteneur depuis n'importe quel composant Android. */
val Context.container: AppContainer
    get() = (applicationContext as NextcloudDreamApp).container

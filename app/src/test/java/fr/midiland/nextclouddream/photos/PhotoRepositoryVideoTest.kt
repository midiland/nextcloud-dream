package fr.midiland.nextclouddream.photos

import fr.midiland.nextclouddream.remote.MotionPhoto
import fr.midiland.nextclouddream.settings.SettingsManager
import fr.midiland.nextclouddream.storage.PhotoCacheManager
import fr.midiland.nextclouddream.storage.PhotoIndexStore
import fr.midiland.nextclouddream.remote.PlaceResolver
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests spécifiques pour la gestion des vidéos dans PhotoRepository.
 */
class PhotoRepositoryVideoTest {

    private lateinit var repo: PhotoRepository
    private lateinit var settings: SettingsManager
    private lateinit var cache: PhotoCacheManager
    private lateinit var motionCache: PhotoCacheManager
    private lateinit var indexStore: PhotoIndexStore
    private lateinit var placeResolver: PlaceResolver

    @Before
    fun setup() {
        // Création d'un repository avec des dépendances mockées
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        settings = SettingsManager(context)
        cache = PhotoCacheManager(File(context.cacheDir, "photos"), null)
        motionCache = PhotoCacheManager(File(context.cacheDir, "motion"), null, PhotoCacheManager.MOTION_SUFFIX)
        indexStore = PhotoIndexStore(File(context.cacheDir, "photo_index.json"))
        placeResolver = PlaceResolver(context)
        repo = PhotoRepository(settings, cache, motionCache, indexStore, placeResolver)
    }

    private fun photo(key: String, isVideo: Boolean = false, motion: MotionRef? = null): IndexedPhoto =
        IndexedPhoto(
            key = key,
            url = "https://x/$key",
            name = key,
            fileId = 1L,
            metadata = PhotoMetadata(dateTaken = "2020:01:01 00:00:00"),
            size = 1024L,
            motion = motion,
            isVideo = isVideo,
        )

    @Test
    fun `getIndex filtre les videos quand playVideos est desactive`() = runBlocking {
        // Configuration : vidéos désactivées
        settings.playVideos = false
        
        // Sauvegardons un index avec photos et vidéos
        val index = listOf(
            photo("photo1.jpg", isVideo = false),
            photo("video1.mp4", isVideo = true),
            photo("photo2.jpg", isVideo = false),
            photo("video2.mp4", isVideo = true),
        )
        indexStore.save(index)
        
        // Récupération de l'index
        val result = repo.getIndex()
        
        // Vérification : seule les photos sont retournées
        assertEquals(2, result.size)
        assertTrue(result.all { !it.isVideo })
        assertEquals(setOf("photo1.jpg", "photo2.jpg"), result.map { it.name }.toSet())
    }

    @Test
    fun `getIndex retourne les videos quand playVideos est active`() = runBlocking {
        // Configuration : vidéos activées (défaut)
        settings.playVideos = true
        
        // Sauvegardons un index avec photos et vidéos
        val index = listOf(
            photo("photo1.jpg", isVideo = false),
            photo("video1.mp4", isVideo = true),
            photo("photo2.jpg", isVideo = false),
            photo("video2.mp4", isVideo = true),
        )
        indexStore.save(index)
        
        // Récupération de l'index
        val result = repo.getIndex()
        
        // Vérification : toutes les entrées sont retournées
        assertEquals(4, result.size)
        assertEquals(2, result.count { !it.isVideo })
        assertEquals(2, result.count { it.isVideo })
    }

    @Test
    fun `fetchMotion retourne null quand playLivePhotos est desactive`() = runBlocking {
        settings.playLivePhotos = false
        
        val motionRef = MotionRef.Sidecar("https://x/clip.mp4", 1024L)
        val result = repo.fetchMotion("test", motionRef)
        
        assertNull(result)
    }

    @Test
    fun `fetchMotion retourne null quand motionRef est null`() = runBlocking {
        settings.playLivePhotos = true
        
        val result = repo.fetchMotion("test", null)
        
        assertNull(result)
    }

    @Test
    fun `fetchMotion retourne null quand motionRef est None`() = runBlocking {
        settings.playLivePhotos = true
        
        val result = repo.fetchMotion("test", MotionRef.None)
        
        assertNull(result)
    }

    @Test
    fun `une photo avec isVideo true a toujours motion null`() {
        // Une vidéo seule n'a pas de clip MotionRef
        val video = photo("video.mp4", isVideo = true, motion = MotionRef.None)
        assertTrue(video.isVideo)
        assertNull(video.motion)
    }

    @Test
    fun `une photo animée a isVideo false et motion non null`() {
        val livePhoto = photo("live.jpg", isVideo = false, motion = MotionRef.Sidecar("url", 1024L))
        assertFalse(livePhoto.isVideo)
        assertTrue(livePhoto.motion?.hasVideo == true)
    }
}

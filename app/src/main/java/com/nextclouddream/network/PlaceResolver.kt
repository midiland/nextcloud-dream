package com.nextclouddream.network

import android.content.Context
import android.location.Address
import android.location.Geocoder
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Convertit des coordonnées GPS en nom de lieu ("Saint-Raphaël, France")
 * avec le géocodeur intégré d'Android (service Google Play, présent sur la Mi Box).
 *
 * Appelé uniquement pendant la synchro : le résultat est stocké avec la photo,
 * l'économiseur n'a jamais besoin du réseau pour l'afficher.
 * Les appels sont bloquants : à utiliser depuis Dispatchers.IO.
 */
class PlaceResolver(context: Context) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

    // Plusieurs photos prises au même endroit → un seul appel (précision ~1 km)
    private val cache = mutableMapOf<Pair<Int, Int>, String?>()

    init {
        if (geocoder == null) Timber.w("Aucun géocodeur sur cet appareil : les lieux ne seront pas affichés")
    }

    fun resolve(latitude: Double, longitude: Double): String? {
        val geocoder = geocoder ?: return null
        val key = (latitude * 100).roundToInt() to (longitude * 100).roundToInt()
        return cache.getOrPut(key) {
            try {
                @Suppress("DEPRECATION") // la version asynchrone n'existe qu'à partir d'Android 13
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()?.let(::format)
            } catch (e: Exception) {
                // Pas de réseau ou service indisponible : on réessaiera à la prochaine synchro
                Timber.w(e, "Géocodage impossible (%.4f, %.4f)", latitude, longitude)
                return null
            }
        }
    }

    /** "Ville, Pays", en se rabattant sur le département ou la région si pas de ville. */
    private fun format(address: Address): String? {
        val area = address.locality ?: address.subAdminArea ?: address.adminArea
        return listOfNotNull(area, address.countryName)
            .distinct()
            .joinToString(", ")
            .ifBlank { null }
    }
}
